package mihon.desktop.translator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import mihon.desktop.MemoryManager
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Coordinates asynchronous Google Lens page detection, OCR, translation, generation tracking,
 * and memory-bounded LRU caching for the manga reader.
 */
class PageTranslationController(
    private val textDetector: TextDetector = TextRegionDetector(),
    private val ocrEngine: OcrEngine = TesseractJnaEngine(),
    private val translationProvider: TranslationProvider = DynamicConfigTranslationProvider { TranslatorPreferences.load() },
    private val configProvider: () -> TranslatorConfig = { TranslatorPreferences.load() },
    private val isWebtoonProvider: () -> Boolean = { false },
    private val onPageResultReady: (PageTranslationResult) -> Unit = {},
    private val onStatusChanged: (TranslationStatus, String?) -> Unit = { _, _ -> },
) : AutoCloseable {

    private class DynamicConfigTranslationProvider(
        private val configSupplier: () -> TranslatorConfig,
    ) : TranslationProvider {
        @Volatile
        private var cachedConfig: TranslatorConfig? = null
        @Volatile
        private var cachedDelegate: TranslationProvider? = null

        private val phraseCacheLock = Any()
        private val phraseCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 512
        }

        override val id: String
            get() = configSupplier().providerId
        override val displayName: String
            get() = "Configured (${configSupplier().providerId})"

        private fun resolveDelegate(cfg: TranslatorConfig): TranslationProvider {
            val prevCfg = cachedConfig
            val prevDel = cachedDelegate
            if (prevCfg == cfg && prevDel != null) return prevDel
            val created = TranslationProviderRegistry.createWithFallback(cfg)
            cachedConfig = cfg
            cachedDelegate = created
            return created
        }

        override suspend fun translateBatch(
            texts: List<String>,
            sourceLang: TranslatorLanguage,
            targetLang: TranslatorLanguage,
        ): List<String> {
            val cfg = configSupplier()
            val delegate = resolveDelegate(cfg)
            if (texts.isEmpty()) return emptyList()

            val results = arrayOfNulls<String>(texts.size)
            val missingIndices = ArrayList<Int>(texts.size)
            val missingTexts = ArrayList<String>(texts.size)

            synchronized(phraseCacheLock) {
                for (i in texts.indices) {
                    val t = texts[i]
                    if (t.isBlank()) {
                        results[i] = t
                        continue
                    }
                    val cacheKey = "${cfg.providerId}|${sourceLang.name}|${targetLang.name}|$t"
                    val hit = phraseCache[cacheKey]
                    if (hit != null) {
                        results[i] = hit
                    } else {
                        missingIndices.add(i)
                        missingTexts.add(t)
                    }
                }
            }

            if (missingTexts.isNotEmpty()) {
                val fetched = delegate.translateBatch(missingTexts, sourceLang, targetLang)
                synchronized(phraseCacheLock) {
                    for (j in missingIndices.indices) {
                        val origIdx = missingIndices[j]
                        val srcText = missingTexts[j]
                        val trText = fetched.getOrNull(j)?.trim().orEmpty().ifEmpty { srcText }
                        results[origIdx] = trText
                        if (srcText.length <= 240) {
                            phraseCache["${cfg.providerId}|${sourceLang.name}|${targetLang.name}|$srcText"] = trText
                        }
                    }
                }
            }
            return results.mapIndexed { idx, v -> v ?: texts[idx] }
        }

        fun clearPhraseCache() {
            synchronized(phraseCacheLock) {
                phraseCache.clear()
            }
        }
    }

    private data class CacheKey(
        val generation: Long,
        val pageIndex: Int,
        val sourceLang: TranslatorLanguage,
        val targetLang: TranslatorLanguage,
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(2)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val closed = AtomicBoolean(false)
    private val generationCounter = AtomicLong(1L)

    @Volatile
    var isEnabled: Boolean = false
        private set

    private val cacheLock = Any()
    private val resultCache = object : LinkedHashMap<Int, Pair<CacheKey, PageTranslationResult>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Int, Pair<CacheKey, PageTranslationResult>>?,
        ): Boolean = size > maxCacheEntries()
    }

    private val inFlightJobs = ConcurrentHashMap<Int, Job>()

    private val trimCallback: () -> Unit = {
        trimMemory()
    }

    init {
        MemoryManager.registerTrimCallback(trimCallback)
    }

    fun currentGeneration(): Long = generationCounter.get()

    private fun maxCacheEntries(): Int =
        MemoryManager.calculateReaderCacheEntries(isWebtoonProvider()).coerceIn(3, 24)

    fun setEnabled(
        enabled: Boolean,
        visiblePages: List<Int>,
        pageImageProvider: suspend (Int) -> BufferedImage?,
    ) {
        if (closed.get()) return
        isEnabled = enabled
        if (!enabled) {
            cancelAllInFlight()
            onStatusChanged(TranslationStatus.IDLE, null)
            return
        }
        onVisiblePagesChanged(visiblePages, pageImageProvider)
    }

    fun onVisiblePagesChanged(
        visiblePages: List<Int>,
        pageImageProvider: suspend (Int) -> BufferedImage?,
    ) {
        if (closed.get() || !isEnabled) return
        val distinctPages = visiblePages.filter { it >= 0 }.distinct()
        if (distinctPages.isEmpty()) return

        val visibleSet = distinctPages.toSet()
        // Cancel superseded jobs for pages no longer in the visible set
        val toCancel = inFlightJobs.keys.filter { it !in visibleSet }
        for (pageIdx in toCancel) {
            inFlightJobs.remove(pageIdx)?.cancel()
        }

        val gen = generationCounter.get()
        val cfg = configProvider()

        for (pageIndex in distinctPages) {
            val cached = getValidCachedResult(pageIndex, gen, cfg)
            if (cached != null) {
                onPageResultReady(cached)
                if (inFlightJobs.isEmpty()) {
                    onStatusChanged(TranslationStatus.READY, null)
                }
                continue
            }

            val existingJob = inFlightJobs[pageIndex]
            if (existingJob != null && existingJob.isActive) {
                continue
            }

            val job = scope.launch(start = CoroutineStart.LAZY) {
                translateSinglePage(
                    pageIndex = pageIndex,
                    expectedGen = gen,
                    config = cfg,
                    pageImageProvider = pageImageProvider,
                )
            }
            inFlightJobs[pageIndex] = job
            job.invokeOnCompletion {
                inFlightJobs.remove(pageIndex, job)
            }
            job.start()
        }
    }

    private suspend fun translateSinglePage(
        pageIndex: Int,
        expectedGen: Long,
        config: TranslatorConfig,
        pageImageProvider: suspend (Int) -> BufferedImage?,
    ) {
        try {
            if (closed.get() || !isEnabled || generationCounter.get() != expectedGen) return

            onStatusChanged(TranslationStatus.TRANSLATING, "Traduzindo página ${pageIndex + 1}…")

            val image = pageImageProvider(pageIndex)
            coroutineContext.ensureActive()
            if (closed.get() || !isEnabled || generationCounter.get() != expectedGen) return

            if (image == null || image.width <= 0 || image.height <= 0) {
                if (inFlightJobs.keys.none { it != pageIndex } && isEnabled && !closed.get() && generationCounter.get() == expectedGen) {
                    onStatusChanged(TranslationStatus.IDLE, null)
                }
                return
            }

            if (!ocrEngine.isAvailable()) {
                if (isEnabled && !closed.get() && generationCounter.get() == expectedGen) {
                    onStatusChanged(TranslationStatus.ERROR, "Motor OCR indisponível")
                }
                return
            }

            val detectedRegions = textDetector.detectRegions(image)
            coroutineContext.ensureActive()
            if (closed.get() || !isEnabled || generationCounter.get() != expectedGen) return

            val snippets = ocrEngine.recognizePage(image, detectedRegions, config.sourceLanguage)
                .filter { it.text.isNotBlank() && !it.bounds.isEmpty }
            coroutineContext.ensureActive()
            if (closed.get() || !isEnabled || generationCounter.get() != expectedGen) return

            val blocks: List<TranslatedBlock> = if (snippets.isEmpty()) {
                emptyList()
            } else {
                val sourceTexts = snippets.map { cleanTextForTranslation(it.text) }
                val translatedTexts = translationProvider.translateBatch(
                    texts = sourceTexts,
                    sourceLang = config.sourceLanguage,
                    targetLang = config.targetLanguage,
                )
                coroutineContext.ensureActive()
                if (closed.get() || !isEnabled || generationCounter.get() != expectedGen) return

                snippets.mapIndexed { idx, snippet ->
                    val translated = translatedTexts.getOrNull(idx)?.trim().orEmpty()
                    val matchedOrientation = detectedRegions
                        .maxByOrNull { it.bounds.iou(snippet.bounds) }
                        ?.takeIf { it.bounds.iou(snippet.bounds) > 0.1f }
                        ?.orientation
                        ?: if (snippet.bounds.height > snippet.bounds.width * 1.6f) {
                            TextOrientation.VERTICAL
                        } else {
                            TextOrientation.HORIZONTAL
                        }
                    TranslatedBlock(
                        bounds = snippet.bounds.clamped(),
                        originalText = snippet.text.trim(),
                        translatedText = translated.ifEmpty { snippet.text.trim() },
                        orientation = matchedOrientation,
                        confidence = snippet.confidence,
                    )
                }
            }

            if (closed.get() || !isEnabled || generationCounter.get() != expectedGen) return

            val result = PageTranslationResult(
                pageIndex = pageIndex,
                generation = expectedGen,
                sourceWidth = image.width,
                sourceHeight = image.height,
                blocks = blocks,
                status = TranslationStatus.READY,
            )

            val key = CacheKey(
                generation = expectedGen,
                pageIndex = pageIndex,
                sourceLang = config.sourceLanguage,
                targetLang = config.targetLanguage,
            )
            synchronized(cacheLock) {
                if (!closed.get() && isEnabled && generationCounter.get() == expectedGen) {
                    resultCache[pageIndex] = key to result
                } else {
                    return
                }
            }

            onPageResultReady(result)
            if (isEnabled && !closed.get() && generationCounter.get() == expectedGen &&
                inFlightJobs.keys.none { it != pageIndex }
            ) {
                onStatusChanged(TranslationStatus.READY, null)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (!closed.get() && isEnabled && generationCounter.get() == expectedGen) {
                onStatusChanged(
                    TranslationStatus.ERROR,
                    t.message?.takeIf { it.isNotBlank() } ?: "Erro na tradução",
                )
            }
        }
    }

    fun onChapterChanged(newGeneration: Long) {
        if (closed.get()) return
        generationCounter.updateAndGet { current -> maxOf(current + 1L, newGeneration) }
        cancelAllInFlight()
        synchronized(cacheLock) {
            resultCache.clear()
        }
        onStatusChanged(TranslationStatus.IDLE, null)
    }

    fun getCachedResult(pageIndex: Int): PageTranslationResult? {
        if (closed.get()) return null
        val gen = generationCounter.get()
        synchronized(cacheLock) {
            val pair = resultCache[pageIndex] ?: return null
            if (pair.first.generation != gen) {
                resultCache.remove(pageIndex)
                return null
            }
            return pair.second
        }
    }

    private fun getValidCachedResult(
        pageIndex: Int,
        expectedGen: Long,
        config: TranslatorConfig,
    ): PageTranslationResult? {
        synchronized(cacheLock) {
            val (key, result) = resultCache[pageIndex] ?: return null
            if (key.generation != expectedGen ||
                key.sourceLang != config.sourceLanguage ||
                key.targetLang != config.targetLanguage ||
                result.status != TranslationStatus.READY
            ) {
                resultCache.remove(pageIndex)
                return null
            }
            return result
        }
    }

    fun trimMemory() {
        synchronized(cacheLock) {
            resultCache.clear()
        }
        (translationProvider as? DynamicConfigTranslationProvider)?.clearPhraseCache()
    }

    private fun cleanTextForTranslation(raw: String): String {
        return raw.trim()
            .replace(Regex("-\\s*\\n+\\s*"), "")
            .replace(Regex("\\s*\\n+\\s*"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun cancelAllInFlight() {
        val jobs = inFlightJobs.values.toList()
        inFlightJobs.clear()
        jobs.forEach { runCatching { it.cancel() } }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            isEnabled = false
            MemoryManager.unregisterTrimCallback(trimCallback)
            cancelAllInFlight()
            synchronized(cacheLock) {
                resultCache.clear()
            }
            (translationProvider as? DynamicConfigTranslationProvider)?.clearPhraseCache()
            runCatching { scope.cancel() }
            if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                Thread({ runCatching { ocrEngine.close() } }, "mihon-ocr-close").apply {
                    isDaemon = true
                    start()
                }
            } else {
                runCatching { ocrEngine.close() }
            }
        }
    }
}
