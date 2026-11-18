package mihon.desktop.translator

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.ComponentSampleModel
import java.awt.image.DataBufferByte
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.imageio.ImageIO
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

internal interface TessLibrary : Library {
    fun TessVersion(): String
    fun TessBaseAPICreate(): Pointer?
    fun TessBaseAPIDelete(handle: Pointer)
    fun TessBaseAPIEnd(handle: Pointer)
    fun TessBaseAPIClear(handle: Pointer)
    fun TessBaseAPIInit3(handle: Pointer, datapath: String?, language: String?): Int
    fun TessBaseAPISetPageSegMode(handle: Pointer, mode: Int)
    fun TessBaseAPISetVariable(handle: Pointer, name: String, value: String): Int
    fun TessBaseAPISetImage(
        handle: Pointer,
        imagedata: ByteArray,
        width: Int,
        height: Int,
        bytesPerPixel: Int,
        bytesPerLine: Int,
    )
    fun TessBaseAPIRecognize(handle: Pointer, monitor: Pointer?): Int
    fun TessBaseAPIGetUTF8Text(handle: Pointer): Pointer?
    fun TessBaseAPIMeanTextConf(handle: Pointer): Int
    fun TessBaseAPIGetIterator(handle: Pointer): Pointer?
    fun TessResultIteratorGetPageIterator(handle: Pointer): Pointer?
    fun TessResultIteratorGetUTF8Text(handle: Pointer, level: Int): Pointer?
    fun TessResultIteratorConfidence(handle: Pointer, level: Int): Float
    fun TessResultIteratorNext(handle: Pointer, level: Int): Int
    fun TessResultIteratorDelete(handle: Pointer)
    fun TessPageIteratorBoundingBox(
        handle: Pointer,
        level: Int,
        left: IntByReference,
        top: IntByReference,
        right: IntByReference,
        bottom: IntByReference,
    ): Int
    fun TessPageIteratorBlockType(handle: Pointer): Int
    fun TessDeleteText(text: Pointer)
}

/**
 * Local Tesseract OCR engine backed by JNA (`libtesseract.so.5.5` / standard Linux names)
 * with optional CLI `tesseract` fallback and single-instance thread-safe handle reuse.
 */
class TesseractJnaEngine internal constructor(
    private val tessdataManager: TessdataManager = TessdataManager(),
    private val autoDownloadProvider: () -> Boolean = { true },
    private val libraryCandidates: List<String> = DEFAULT_LIBRARY_CANDIDATES,
    private val cliAvailabilityCheck: (() -> Boolean)? = null,
    private val cliCommandRunner: ((List<String>) -> CliResult)? = null,
) : OcrEngine {

    constructor(
        tessdataManager: TessdataManager = TessdataManager(),
        autoDownloadProvider: () -> Boolean = { true },
        libraryCandidates: List<String> = DEFAULT_LIBRARY_CANDIDATES,
    ) : this(
        tessdataManager = tessdataManager,
        autoDownloadProvider = autoDownloadProvider,
        libraryCandidates = libraryCandidates,
        cliAvailabilityCheck = null,
        cliCommandRunner = null,
    )

    internal data class CliResult(val exitCode: Int, val stdout: String)

    private data class GrayBuffer(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val stride: Int,
    )

    private data class ResolvedLangTarget(
        val datapath: File,
        val ocrLang: String,
    )

    private data class RoiWordToken(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val conf: Float,
    ) {
        val width: Int get() = (right - left).coerceAtLeast(1)
        val height: Int get() = (bottom - top).coerceAtLeast(1)
    }

    private data class RoiGrayData(
        val buffer: GrayBuffer,
        val crop: java.awt.Rectangle,
        val scale: Int,
        val border: Int,
        val scaledW: Int,
        val scaledH: Int,
    )

    private data class RegionWorkItem(
        val originalIndex: Int,
        val region: DetectedTextRegion,
        val target: ResolvedLangTarget,
        val psm: Int,
    )

    private val apiLock = ReentrantLock()
    private val closed = AtomicBoolean(false)
    private val langPathCache = ConcurrentHashMap<String, File>()
    private var apiHandle: Pointer? = null
    private var initializedDatapath: String? = null
    private var initializedLanguage: String? = null

    private val nativeLib: TessLibrary? by lazy { loadNativeLibrary(libraryCandidates) }
    private val cliAvailable: Boolean by lazy {
        cliAvailabilityCheck?.invoke() ?: checkCliAvailable()
    }

    override fun isAvailable(): Boolean = !closed.get() && (nativeLib != null || cliAvailable)

    override fun recognizePage(
        image: BufferedImage,
        regions: List<DetectedTextRegion>,
        sourceLang: TranslatorLanguage,
    ): List<OcrSnippet> {
        if (closed.get() || image.width <= 0 || image.height <= 0) return emptyList()

        val lib = nativeLib
        if (lib == null && !cliAvailable) {
            return emptyList()
        }

        return if (regions.isEmpty()) {
            recognizeFullPageFallback(lib, image, sourceLang)
        } else {
            recognizeRegions(lib, image, regions, sourceLang)
        }
    }

    fun releaseNativeHandle() {
        apiLock.withLock {
            releaseHandleLocked()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            releaseNativeHandle()
        }
    }

    private fun recognizeRegions(
        lib: TessLibrary?,
        image: BufferedImage,
        regions: List<DetectedTextRegion>,
        sourceLang: TranslatorLanguage,
    ): List<OcrSnippet> {
        if (sourceLang == TranslatorLanguage.AUTO) {
            return recognizeRegionsAuto(lib, image, regions)
        }

        val workItems = regions.mapIndexedNotNull { idx, region ->
            val rect = region.bounds.toPixelRect(image.width, image.height)
            if (rect.width <= 0 || rect.height <= 0) return@mapIndexedNotNull null
            val target = resolveTargetFor(sourceLang, region.orientation)
            val psm = selectPsm(sourceLang, region.orientation, target.ocrLang)
            RegionWorkItem(idx, region, target, psm)
        }
        if (workItems.isEmpty()) return emptyList()

        val grouped = workItems.sortedWith(
            compareBy<RegionWorkItem>({ it.target.datapath.absolutePath }, { it.target.ocrLang }, { it.originalIndex }),
        )
        val orderedResults = arrayOfNulls<OcrSnippet>(regions.size)

        if (lib != null) {
            apiLock.withLock {
                if (closed.get()) return emptyList()
                for (item in grouped) {
                    if (closed.get()) break
                    val roi = toRoiGrayData(image, item.region.bounds) ?: continue
                    val handle = ensureInitializedHandleLocked(
                        lib,
                        item.target.datapath.absolutePath,
                        item.target.ocrLang,
                    )
                    val isCjk = isCjkLang(item.target.ocrLang, sourceLang)
                    val snippet = recognizeSingleRoiLocked(
                        lib = lib,
                        handle = handle,
                        roi = roi,
                        regionBounds = item.region.bounds,
                        psm = item.psm,
                        isCjk = isCjk,
                        requireStrictCjk = false,
                    )
                    if (snippet != null) {
                        orderedResults[item.originalIndex] = snippet
                    }
                }
            }
        } else {
            for (item in grouped) {
                if (closed.get()) break
                val roi = toRoiGrayData(image, item.region.bounds) ?: continue
                val snippet = recognizeRoiWithCli(roi, item, sourceLang)
                if (snippet != null) {
                    orderedResults[item.originalIndex] = snippet
                }
            }
        }

        return orderedResults.filterNotNull()
    }

    private fun recognizeRegionsAuto(
        lib: TessLibrary?,
        image: BufferedImage,
        regions: List<DetectedTextRegion>,
    ): List<OcrSnippet> {
        val validRegions = regions.mapIndexedNotNull { idx, region ->
            val rect = region.bounds.toPixelRect(image.width, image.height)
            if (rect.width <= 0 || rect.height <= 0) null else idx to region
        }
        if (validRegions.isEmpty()) return emptyList()

        val engTarget = resolveOptionalLanguage("eng")
        val jpnHorizTarget = resolveOptionalLanguage("jpn")
        val jpnVertTarget = resolveOptionalLanguage("jpn_vert") ?: jpnHorizTarget

        // If only one language is installed, run directly without probing
        if (engTarget == null || jpnHorizTarget == null || jpnVertTarget == null) {
            return when {
                engTarget == null && jpnHorizTarget == null -> {
                    val fallbackTarget = resolveTargetFor(TranslatorLanguage.AUTO, TextOrientation.HORIZONTAL)
                    recognizeRegionsWithSingleTarget(lib, image, regions, fallbackTarget, TranslatorLanguage.AUTO)
                }
                jpnHorizTarget == null && engTarget != null -> {
                    recognizeRegionsWithSingleTarget(lib, image, regions, engTarget, TranslatorLanguage.ENGLISH)
                }
                else -> {
                    recognizeRegionsWithExplicitLang(lib, image, regions, TranslatorLanguage.JAPANESE)
                }
            }
        }

        val orderedResults = arrayOfNulls<OcrSnippet>(regions.size)
        if (lib == null) {
            // CLI path: probe eng first, fall back to jpn for misses if not strongly English
            for ((idx, region) in validRegions) {
                if (closed.get()) break
                val roi = toRoiGrayData(image, region.bounds) ?: continue
                val engItem = RegionWorkItem(idx, region, engTarget, PSM_SINGLE_BLOCK)
                val engRes = recognizeRoiWithCli(roi, engItem, TranslatorLanguage.ENGLISH)
                if (engRes != null) {
                    orderedResults[idx] = engRes
                }
            }
            val strongEnglishPage = orderedResults.any {
                it != null && it.confidence >= 0.72f && it.text.count { ch -> ch.isLetter() } >= 4
            }
            if (!strongEnglishPage) {
                for ((idx, region) in validRegions) {
                    if (closed.get()) break
                    val jTarget = if (region.orientation == TextOrientation.VERTICAL) jpnVertTarget else jpnHorizTarget
                    val psm = selectPsm(TranslatorLanguage.JAPANESE, region.orientation, jTarget.ocrLang)
                    val jItem = RegionWorkItem(idx, region, jTarget, psm)
                    val roi = toRoiGrayData(image, region.bounds) ?: continue
                    val jRes = recognizeRoiWithCli(roi, jItem, TranslatorLanguage.JAPANESE)
                    val existing = orderedResults[idx]
                    if (jRes != null && (existing == null || jRes.confidence > existing.confidence + 0.08f)) {
                        orderedResults[idx] = jRes
                    }
                }
            }
            return orderedResults.filterNotNull()
        }

        apiLock.withLock {
            if (closed.get()) return emptyList()
            val roiCache = HashMap<Int, RoiGrayData>(validRegions.size)
            for ((idx, region) in validRegions) {
                toRoiGrayData(image, region.bounds)?.let { roiCache[idx] = it }
            }
            if (roiCache.isEmpty()) return emptyList()

            // Pass 1: Run crisp horizontal English model first (never pollutes English spacing with jpn)
            val engHandle = ensureInitializedHandleLocked(lib, engTarget.datapath.absolutePath, engTarget.ocrLang)
            for ((idx, region) in validRegions) {
                if (closed.get()) break
                val roi = roiCache[idx] ?: continue
                val snippet = recognizeSingleRoiLocked(
                    lib = lib,
                    handle = engHandle,
                    roi = roi,
                    regionBounds = region.bounds,
                    psm = PSM_SINGLE_BLOCK,
                    isCjk = false,
                    requireStrictCjk = false,
                )?.let { cur ->
                    if (cur.confidence < 0.66f && roi.crop.width in 32..360 && roi.crop.height in 14..110) {
                        retrySmallTiltedRoiLocked(lib, engHandle, image, region.bounds, cur)
                    } else {
                        cur
                    }
                } ?: if (roi.crop.width in 32..360 && roi.crop.height in 14..110) {
                    retrySmallTiltedRoiLocked(lib, engHandle, image, region.bounds, null)
                } else {
                    null
                }
                if (snippet != null) {
                    orderedResults[idx] = snippet
                }
            }

            val strongEnglishCount = orderedResults.count {
                it != null && it.confidence >= 0.72f && it.text.count { ch -> ch.isLetter() } >= 4
            }
            val missedOrWeakIndices = validRegions.filter { (idx, _) ->
                val cur = orderedResults[idx]
                cur == null || cur.confidence < 0.70f
            }

            // Pass 2: Try Japanese models if English didn't strongly claim this page (or strictly on missed blocks)
            if (missedOrWeakIndices.isNotEmpty() && (!strongEnglishCount.let { it >= 1 } || missedOrWeakIndices.size == validRegions.size)) {
                val sortedMissed = missedOrWeakIndices.sortedBy {
                    if (it.second.orientation == TextOrientation.VERTICAL) 1 else 0
                }
                for ((idx, region) in sortedMissed) {
                    if (closed.get()) break
                    val roi = roiCache[idx] ?: continue
                    val jTarget = if (region.orientation == TextOrientation.VERTICAL) jpnVertTarget else jpnHorizTarget
                    val psm = selectPsm(TranslatorLanguage.JAPANESE, region.orientation, jTarget.ocrLang)
                    val jHandle = ensureInitializedHandleLocked(lib, jTarget.datapath.absolutePath, jTarget.ocrLang)
                    val jRes = recognizeSingleRoiLocked(
                        lib = lib,
                        handle = jHandle,
                        roi = roi,
                        regionBounds = region.bounds,
                        psm = psm,
                        isCjk = true,
                        requireStrictCjk = strongEnglishCount > 0,
                    )
                    if (jRes != null) {
                        val existing = orderedResults[idx]
                        if (existing == null || jRes.confidence > existing.confidence + 0.05f) {
                            orderedResults[idx] = jRes
                        }
                    }
                }
            }
        }

        return orderedResults.filterNotNull()
    }

    private fun recognizeRegionsWithSingleTarget(
        lib: TessLibrary?,
        image: BufferedImage,
        regions: List<DetectedTextRegion>,
        target: ResolvedLangTarget,
        effectiveLang: TranslatorLanguage,
    ): List<OcrSnippet> {
        val orderedResults = arrayOfNulls<OcrSnippet>(regions.size)
        val isCjk = isCjkLang(target.ocrLang, effectiveLang)
        if (lib != null) {
            apiLock.withLock {
                if (closed.get()) return emptyList()
                val handle = ensureInitializedHandleLocked(lib, target.datapath.absolutePath, target.ocrLang)
                for ((idx, region) in regions.withIndex()) {
                    if (closed.get()) break
                    val roi = toRoiGrayData(image, region.bounds) ?: continue
                    val psm = selectPsm(effectiveLang, region.orientation, target.ocrLang)
                    val primary = recognizeSingleRoiLocked(lib, handle, roi, region.bounds, psm, isCjk, false)
                    orderedResults[idx] = if (!isCjk && (primary == null || primary.confidence < 0.66f) &&
                        roi.crop.width in 32..360 && roi.crop.height in 14..110
                    ) {
                        retrySmallTiltedRoiLocked(lib, handle, image, region.bounds, primary)
                    } else {
                        primary
                    }
                }
            }
        } else {
            for ((idx, region) in regions.withIndex()) {
                if (closed.get()) break
                val roi = toRoiGrayData(image, region.bounds) ?: continue
                val psm = selectPsm(effectiveLang, region.orientation, target.ocrLang)
                orderedResults[idx] = recognizeRoiWithCli(roi, RegionWorkItem(idx, region, target, psm), effectiveLang)
            }
        }
        return orderedResults.filterNotNull()
    }

    private fun recognizeRegionsWithExplicitLang(
        lib: TessLibrary?,
        image: BufferedImage,
        regions: List<DetectedTextRegion>,
        lang: TranslatorLanguage,
    ): List<OcrSnippet> = recognizeRegions(lib, image, regions, lang)

    private fun recognizeSingleRoiLocked(
        lib: TessLibrary,
        handle: Pointer,
        roi: RoiGrayData,
        regionBounds: NormalizedRect,
        psm: Int,
        isCjk: Boolean,
        requireStrictCjk: Boolean,
    ): OcrSnippet? {
        try {
            lib.TessBaseAPISetPageSegMode(handle, psm)
            lib.TessBaseAPISetImage(handle, roi.buffer.data, roi.buffer.width, roi.buffer.height, 1, roi.buffer.stride)
            if (lib.TessBaseAPIRecognize(handle, null) != 0) return null

            val tokens = extractRoiWordsLocked(lib, handle)
            if (tokens.isNotEmpty()) {
                return evaluateRoiTokens(tokens, roi, regionBounds, isCjk, requireStrictCjk)
            }
            return null
        } finally {
            lib.TessBaseAPIClear(handle)
        }
    }

    private fun extractRoiWordsLocked(
        lib: TessLibrary,
        handle: Pointer,
    ): List<RoiWordToken> {
        val it = lib.TessBaseAPIGetIterator(handle) ?: return emptyList()
        return try {
            val pi = lib.TessResultIteratorGetPageIterator(it) ?: return emptyList()
            val leftRef = IntByReference()
            val topRef = IntByReference()
            val rightRef = IntByReference()
            val bottomRef = IntByReference()
            val tokens = mutableListOf<RoiWordToken>()
            do {
                val ptr = lib.TessResultIteratorGetUTF8Text(it, RIL_WORD)
                val raw = if (ptr != null) {
                    try {
                        ptr.getString(0, "UTF-8")
                    } finally {
                        lib.TessDeleteText(ptr)
                    }
                } else {
                    ""
                }
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) continue
                val ok = lib.TessPageIteratorBoundingBox(pi, RIL_WORD, leftRef, topRef, rightRef, bottomRef)
                if (ok == 0) continue
                val l = leftRef.value
                val t = topRef.value
                val r = rightRef.value
                val b = bottomRef.value
                if (r <= l || b <= t) continue
                val conf = lib.TessResultIteratorConfidence(it, RIL_WORD).coerceIn(0f, 100f) / 100f
                tokens.add(RoiWordToken(trimmed, l, t, r, b, conf))
            } while (lib.TessResultIteratorNext(it, RIL_WORD) != 0)
            tokens
        } finally {
            lib.TessResultIteratorDelete(it)
        }
    }

    private fun resolveOptionalLanguage(ocrLang: String): ResolvedLangTarget? {
        langPathCache[ocrLang]?.let { cached ->
            if (cached.isDirectory) return ResolvedLangTarget(cached, ocrLang)
            langPathCache.remove(ocrLang, cached)
        }
        tessdataManager.resolveDatapath(ocrLang)?.let {
            langPathCache[ocrLang] = it
            return ResolvedLangTarget(it, ocrLang)
        }
        val res = tessdataManager.ensureLanguageBlocking(ocrLang, allowDownload = autoDownloadProvider())
        return if (res.isSuccess) {
            val dir = res.getOrThrow()
            langPathCache[ocrLang] = dir
            ResolvedLangTarget(dir, ocrLang)
        } else {
            null
        }
    }

    private fun evaluateRoiTokens(
        tokens: List<RoiWordToken>,
        roi: RoiGrayData,
        regionBounds: NormalizedRect,
        isCjk: Boolean,
        requireStrictCjk: Boolean,
    ): OcrSnippet? {
        if (tokens.isEmpty()) return null

        val edgePad = maxOf(2, roi.scale)
        val allowBorderTrim = roi.crop.width >= 28 && roi.crop.height >= 28
        val validHeights = tokens.filter { it.conf >= 0.70f && hasAlphaOrCjkChar(it.text) }.map { it.height }.sorted()
        val refHeight = if (validHeights.isNotEmpty()) {
            validHeights[validHeights.size / 2].toFloat().coerceAtLeast(6f)
        } else {
            tokens.map { it.height }.sorted()[tokens.size / 2].toFloat().coerceAtLeast(6f)
        }

        // First pass: strip pure symbol noise and obvious border frame artifacts
        val candidates = ArrayList<RoiWordToken>(tokens.size)
        var strippedEdgeOrNoise = false
        for (tok in tokens) {
            val touchesEdge = allowBorderTrim && (
                tok.left <= roi.border + edgePad ||
                    tok.right >= roi.border + roi.scaledW - edgePad ||
                    tok.top <= roi.border + edgePad ||
                    tok.bottom >= roi.border + roi.scaledH - edgePad
                )

            // Comic sans uppercase 'I' is occasionally read by Tesseract as lone '|' inside horizontal English lines
            val normalizedRaw = if (!isCjk &&
                !touchesEdge &&
                tok.text.trim() == "|" &&
                tok.height <= refHeight * 1.35f &&
                tok.width <= refHeight * 0.65f &&
                tokens.any { other ->
                    other !== tok &&
                        other.conf >= 0.70f &&
                        hasAlphaOrCjkChar(other.text) &&
                        kotlin.math.abs((other.top + other.bottom) * 0.5f - (tok.top + tok.bottom) * 0.5f) <= refHeight * 0.65f
                }
            ) {
                "I"
            } else {
                tok.text
            }

            val cleaned = cleanSurroundingFrameChars(normalizedRaw, isCjk)
            if (cleaned.isEmpty() || !hasAlphaOrDigitOrCjkChar(cleaned)) {
                strippedEdgeOrNoise = true
                continue
            }

            if (touchesEdge && !isCjk) {
                val letters = cleaned.count { it.isLetter() }
                val digits = cleaned.count { it.isDigit() }
                if (tok.height > refHeight * 1.65f ||
                    (digits <= 2 && letters == 0 && tok.conf < 0.82f) ||
                    (letters <= 2 && tok.conf < 0.88f) ||
                    (tok.conf < 0.42f && letters <= 4)
                ) {
                    strippedEdgeOrNoise = true
                    continue
                }
            } else if (touchesEdge && isCjk) {
                val cjkChars = cleaned.count { Character.UnicodeScript.of(it.code) in CJK_SCRIPTS && it != 'ー' }
                if (tok.height > refHeight * 1.85f || (cjkChars <= 1 && tok.conf < 0.80f)) {
                    strippedEdgeOrNoise = true
                    continue
                }
            }

            candidates.add(if (cleaned == tok.text) tok else tok.copy(text = cleaned))
        }

        if (candidates.isEmpty()) return null

        // Second pass: drop out-of-band low-confidence hair/tail noise when strong core text exists
        val coreTokens = candidates.filter { tok ->
            val validLen = if (isCjk) {
                tok.text.count { Character.UnicodeScript.of(it.code) in CJK_SCRIPTS }
            } else {
                tok.text.count { it.isLetter() }
            }
            tok.conf >= 0.72f && validLen >= 2
        }

        val kept = if (coreTokens.isNotEmpty()) {
            val coreMinX = coreTokens.minOf { it.left }
            val coreMinY = coreTokens.minOf { it.top }
            val coreMaxX = coreTokens.maxOf { it.right }
            val coreMaxY = coreTokens.maxOf { it.bottom }
            val gapTol = maxOf(refHeight * 2.2f, 32f * roi.scale).toInt()
            val out = ArrayList<RoiWordToken>(candidates.size)
            for (tok in candidates) {
                val dx = maxOf(coreMinX - tok.right, tok.left - coreMaxX, 0)
                val dy = maxOf(coreMinY - tok.bottom, tok.top - coreMaxY, 0)
                val outsideCoreBand = dx > gapTol || dy > gapTol
                val alphaOnly = tok.text.filter { it.isLetter() }
                val hasVowelOrAllowed = isCjk ||
                    alphaOnly.any { it in VOWEL_CHARS } ||
                    alphaOnly.uppercase() in ALLOWED_NO_VOWEL_TOKENS
                val isShortLowConfJunk = !isCjk && tok.conf < 0.65f && (alphaOnly.length <= 2 || !hasVowelOrAllowed)

                if ((outsideCoreBand && tok.conf < 0.68f) || (outsideCoreBand && isShortLowConfJunk)) {
                    strippedEdgeOrNoise = true
                    continue
                }
                if (!isCjk && tok.conf < 0.35f && alphaOnly.length <= 2 && alphaOnly.uppercase() !in ALLOWED_SHORT_WORDS) {
                    strippedEdgeOrNoise = true
                    continue
                }
                if (!isCjk && alphaOnly.length >= 2 && !hasVowelOrAllowed && tok.conf < 0.85f) {
                    strippedEdgeOrNoise = true
                    continue
                }
                out.add(tok)
            }
            out
        } else {
            candidates
        }

        if (kept.isEmpty()) return null

        val joinedRaw = kept.joinToString(if (isCjk) "" else " ") { it.text }
        val normalized = normalizeRecognizedText(joinedRaw, isCjk) ?: return null
        val meanConf = kept.map { it.conf }.average().toFloat().coerceIn(0f, 1f)
        val maxConf = kept.maxOf { it.conf }.coerceIn(0f, 1f)

        if (!passesSnippetQualityGate(normalized, kept, meanConf, maxConf, isCjk, requireStrictCjk)) {
            return null
        }

        // Tighten bounding box when significant leaked perimeter/tail junk was stripped
        val tightBounds = computeTightBoundsOrPreserve(
            kept = kept,
            roi = roi,
            regionBounds = regionBounds,
            refHeight = refHeight,
            strippedEdgeOrNoise = strippedEdgeOrNoise,
        )

        return OcrSnippet(text = normalized, bounds = tightBounds, confidence = meanConf)
    }

    private fun passesSnippetQualityGate(
        text: String,
        tokens: List<RoiWordToken>,
        meanConf: Float,
        maxConf: Float,
        isCjk: Boolean,
        requireStrictCjk: Boolean,
    ): Boolean {
        if (isCjk) {
            val cjkChars = text.count { Character.UnicodeScript.of(it.code) in CJK_SCRIPTS }
            val realCjkChars = text.count { Character.UnicodeScript.of(it.code) in CJK_SCRIPTS && it != 'ー' && it != '一' }
            if (cjkChars == 0) return false
            if (requireStrictCjk && (cjkChars < 4 || meanConf < 0.70f)) return false
            if (realCjkChars == 0) return false
            if (realCjkChars == 1 && (meanConf < 0.72f || maxConf < 0.88f)) return false
            if (cjkChars <= 2 && meanConf < 0.68f) return false
            if (cjkChars <= 3 && meanConf < 0.68f && maxConf < 0.84f) return false
            return meanConf >= 0.48f || maxConf >= 0.76f
        }

        val letterCount = text.count { it.isLetter() }
        if (letterCount < 2) return false
        val digitCount = text.count { it.isDigit() }
        val junkCount = text.count {
            !it.isLetterOrDigit() && !it.isWhitespace() && it !in NORMAL_PUNCTUATION
        }
        if (letterCount <= 8 && junkCount >= letterCount / 2 + 1) return false
        if (junkCount > letterCount * 0.45f && meanConf < 0.80f) return false

        val words = tokens.map { it.text }
        val alphaUpperWords = words.map { w -> w.filter { it.isLetter() }.uppercase() }.filter { it.isNotEmpty() }
        if (alphaUpperWords.isEmpty()) return false

        val hasAnyVowel = alphaUpperWords.any { w -> w.any { it in VOWEL_CHARS } || w in ALLOWED_NO_VOWEL_TOKENS }
        if (!hasAnyVowel && maxConf < 0.88f) return false

        // Reject mixed digit+letter gibberish blobs like "KW7", "3ao)", "D3SS" when isolated
        val mixedAlnumTokens = words.count { w -> w.any { it.isDigit() } && w.any { it.isLetter() } }
        if (mixedAlnumTokens > 0 && words.size <= 3 && mixedAlnumTokens * 2 >= words.size && maxConf < 0.84f) {
            return false
        }

        // Reject tiny multi-token fragments paired with digits/symbols like "Wy 4)" or weak 2..3-letter pairs
        if (words.size >= 2 && letterCount <= 4) {
            val hasStandaloneDigitOrSymbolToken = words.any { w -> w.none { it.isLetter() } }
            if (hasStandaloneDigitOrSymbolToken && meanConf < 0.86f) return false
            if (letterCount <= 3 && meanConf < 0.84f) return false
        }

        // Reject single tiny weak fragment like "aA", "nw", "Lr", or low-confidence 2-letter artwork hallucinations like "IF" -> "se"
        if (words.size == 1 && letterCount <= 3) {
            val firstToken = words.first()
            val singleUpper = alphaUpperWords.first()
            val mixedCamelNoise = firstToken.length == 2 && firstToken[0].isLowerCase() && firstToken[1].isUpperCase()
            if (mixedCamelNoise && maxConf < 0.90f) return false
            if (letterCount <= 2) {
                if (digitCount + junkCount > 0 && maxConf < 0.92f) return false
                if (firstToken.all { it.isLowerCase() } && maxConf < 0.92f) return false
                val minAllowedConf = if (singleUpper in ALLOWED_SHORT_WORDS) 0.86f else 0.91f
                if (maxConf < minAllowedConf) return false
            } else {
                val minAllowedConf = if (singleUpper in ALLOWED_SHORT_WORDS) 0.78f else 0.83f
                if (maxConf < minAllowedConf) return false
            }
        } else if (words.size == 1 && letterCount == 4) {
            val firstToken = words.first()
            if (firstToken.all { it.isLowerCase() } && maxConf < 0.85f) return false
            if (maxConf < 0.72f) return false
        }

        if (meanConf < 0.54f && maxConf < 0.78f && digitCount + letterCount < 14) return false
        if (meanConf < 0.52f && alphaUpperWords.size >= 3 && letterCount.toFloat() / alphaUpperWords.size < 2.75f) return false
        if (meanConf < 0.38f && maxConf < 0.72f) return false
        return true
    }

    private fun computeTightBoundsOrPreserve(
        kept: List<RoiWordToken>,
        roi: RoiGrayData,
        regionBounds: NormalizedRect,
        refHeight: Float,
        strippedEdgeOrNoise: Boolean,
    ): NormalizedRect {
        if (!strippedEdgeOrNoise || kept.isEmpty() || regionBounds.area <= 0.015f) {
            return regionBounds
        }
        val minBX = kept.minOf { it.left }
        val minBY = kept.minOf { it.top }
        val maxBX = kept.maxOf { it.right }
        val maxBY = kept.maxOf { it.bottom }

        val origPad = maxOf(6f, (refHeight / roi.scale) * 0.45f)
        val x1 = (roi.crop.x + ((minBX - roi.border).toFloat() / roi.scale) - origPad)
            .coerceIn(roi.crop.x.toFloat(), (roi.crop.x + roi.crop.width).toFloat())
        val y1 = (roi.crop.y + ((minBY - roi.border).toFloat() / roi.scale) - origPad)
            .coerceIn(roi.crop.y.toFloat(), (roi.crop.y + roi.crop.height).toFloat())
        val x2 = (roi.crop.x + ((maxBX - roi.border).toFloat() / roi.scale) + origPad)
            .coerceIn(x1, (roi.crop.x + roi.crop.width).toFloat())
        val y2 = (roi.crop.y + ((maxBY - roi.border).toFloat() / roi.scale) + origPad)
            .coerceIn(y1, (roi.crop.y + roi.crop.height).toFloat())

        val imgW = (roi.crop.width / regionBounds.width).coerceAtLeast(1f)
        val imgH = (roi.crop.height / regionBounds.height).coerceAtLeast(1f)
        val tight = NormalizedRect(
            x = x1 / imgW,
            y = y1 / imgH,
            width = (x2 - x1) / imgW,
            height = (y2 - y1) / imgH,
        ).clamped()

        return if (!tight.isEmpty && tight.area < regionBounds.area * 0.65f) {
            tight
        } else {
            regionBounds
        }
    }

    private fun cleanSurroundingFrameChars(raw: String, isCjk: Boolean): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || isCjk) return trimmed
        var start = 0
        var end = trimmed.length
        while (start < end && trimmed[start] in FRAME_STRIP_CHARS) start++
        while (end > start && trimmed[end - 1] in FRAME_STRIP_END_CHARS) end--
        return trimmed.substring(start, end)
    }

    private fun hasAlphaOrCjkChar(text: String): Boolean =
        text.any { ch ->
            ch.isLetter() || Character.UnicodeScript.of(ch.code) in CJK_SCRIPTS
        }

    private fun hasAlphaOrDigitOrCjkChar(text: String): Boolean =
        text.any { ch ->
            ch.isLetterOrDigit() || Character.UnicodeScript.of(ch.code) in CJK_SCRIPTS
        }

    private fun retrySmallTiltedRoiLocked(
        lib: TessLibrary,
        handle: com.sun.jna.Pointer,
        image: BufferedImage,
        regionBounds: NormalizedRect,
        currentBest: OcrSnippet?,
    ): OcrSnippet? {
        var best = currentBest
        val crop = regionBounds.toPixelRect(image.width, image.height)
        if (crop.width <= 0 || crop.height <= 0) return best
        val scale = (if (minOf(crop.width, crop.height) < 38) 4 else 3)
            .coerceAtMost((1400 / maxOf(crop.width, crop.height)).coerceAtLeast(1))
        val scaledW = crop.width * scale
        val scaledH = crop.height * scale
        val border = 32
        val targetW = scaledW + border * 2
        val targetH = scaledH + border * 2

        for (angleDeg in doubleArrayOf(10.0, -10.0)) {
            if (closed.get()) break
            val imgBuf = BufferedImage(targetW, targetH, BufferedImage.TYPE_BYTE_GRAY)
            val g2 = imgBuf.createGraphics()
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g2.color = Color.WHITE
                g2.fillRect(0, 0, targetW, targetH)
                g2.rotate(Math.toRadians(angleDeg), targetW * 0.5, targetH * 0.5)
                g2.drawImage(
                    image,
                    border,
                    border,
                    border + scaledW,
                    border + scaledH,
                    crop.x,
                    crop.y,
                    crop.x + crop.width,
                    crop.y + crop.height,
                    null,
                )
            } finally {
                g2.dispose()
            }
            val rawData = (imgBuf.raster.dataBuffer as DataBufferByte).data
            val stride = (imgBuf.raster.sampleModel as? ComponentSampleModel)?.scanlineStride ?: targetW
            val copied = if (stride == targetW && rawData.size >= targetW * targetH) {
                rawData.copyOf(targetW * targetH)
            } else {
                ByteArray(targetW * targetH).also { out ->
                    for (y in 0 until targetH) System.arraycopy(rawData, y * stride, out, y * targetW, targetW)
                }
            }
            imgBuf.flush()
            normalizeRoiPolarityAndContrast(copied, targetW, targetH, border, scaledW, scaledH)
            val roi = RoiGrayData(GrayBuffer(copied, targetW, targetH, targetW), crop, scale, border, scaledW, scaledH)
            val cand = recognizeSingleRoiLocked(
                lib = lib,
                handle = handle,
                roi = roi,
                regionBounds = regionBounds,
                psm = PSM_SINGLE_LINE,
                isCjk = false,
                requireStrictCjk = false,
            )
            if (cand != null && (best == null || cand.confidence > best.confidence + 0.05f)) {
                best = cand
            }
        }
        return best
    }

    private fun recognizeFullPageFallback(
        lib: TessLibrary?,
        image: BufferedImage,
        sourceLang: TranslatorLanguage,
    ): List<OcrSnippet> {
        val buf = toFullPageGrayBuffer(image)
        if (sourceLang == TranslatorLanguage.AUTO) {
            val engTarget = resolveOptionalLanguage("eng")
            val jpnTarget = resolveOptionalLanguage("jpn")
            if (engTarget != null && lib != null) {
                val engRes = apiLock.withLock {
                    if (closed.get()) return emptyList()
                    val h = ensureInitializedHandleLocked(lib, engTarget.datapath.absolutePath, engTarget.ocrLang)
                    extractPageBlocksWithIteratorLocked(lib, h, buf, PSM_AUTO, RIL_BLOCK, isCjk = false)
                        .ifEmpty { extractPageBlocksWithIteratorLocked(lib, h, buf, PSM_SPARSE_TEXT, RIL_TEXTLINE, isCjk = false) }
                }
                if (engRes.any { it.confidence >= 0.70f && it.text.count { ch -> ch.isLetter() } >= 4 }) {
                    return engRes
                }
            }
            if (jpnTarget != null && lib != null) {
                return apiLock.withLock {
                    if (closed.get()) return emptyList()
                    val h = ensureInitializedHandleLocked(lib, jpnTarget.datapath.absolutePath, jpnTarget.ocrLang)
                    extractPageBlocksWithIteratorLocked(lib, h, buf, PSM_AUTO, RIL_BLOCK, isCjk = true)
                        .ifEmpty { extractPageBlocksWithIteratorLocked(lib, h, buf, PSM_SPARSE_TEXT, RIL_TEXTLINE, isCjk = true) }
                }
            }
        }

        val target = resolveTargetFor(sourceLang, TextOrientation.HORIZONTAL)
        val isCjk = isCjkLang(target.ocrLang, sourceLang)

        if (lib != null) {
            return apiLock.withLock {
                if (closed.get()) return emptyList()
                val handle = ensureInitializedHandleLocked(lib, target.datapath.absolutePath, target.ocrLang)
                val autoBlocks = extractPageBlocksWithIteratorLocked(lib, handle, buf, PSM_AUTO, RIL_BLOCK, isCjk)
                if (autoBlocks.isNotEmpty()) {
                    autoBlocks
                } else {
                    extractPageBlocksWithIteratorLocked(lib, handle, buf, PSM_SPARSE_TEXT, RIL_TEXTLINE, isCjk)
                }
            }
        }

        return recognizeFullPageWithCli(buf, target, isCjk)
    }

    private fun extractPageBlocksWithIteratorLocked(
        lib: TessLibrary,
        handle: Pointer,
        buf: GrayBuffer,
        psm: Int,
        level: Int,
        isCjk: Boolean,
    ): List<OcrSnippet> {
        val snippets = mutableListOf<OcrSnippet>()
        try {
            lib.TessBaseAPISetPageSegMode(handle, psm)
            lib.TessBaseAPISetImage(handle, buf.data, buf.width, buf.height, 1, buf.stride)
            if (lib.TessBaseAPIRecognize(handle, null) != 0) return emptyList()

            val it = lib.TessBaseAPIGetIterator(handle) ?: return emptyList()
            try {
                val pi = lib.TessResultIteratorGetPageIterator(it) ?: return emptyList()
                val leftRef = IntByReference()
                val topRef = IntByReference()
                val rightRef = IntByReference()
                val bottomRef = IntByReference()

                do {
                    val btype = lib.TessPageIteratorBlockType(pi)
                    if (btype !in TEXT_BLOCK_TYPES) continue

                    val txtPtr = lib.TessResultIteratorGetUTF8Text(it, level)
                    val rawText = if (txtPtr != null) {
                        try {
                            txtPtr.getString(0, "UTF-8")
                        } finally {
                            lib.TessDeleteText(txtPtr)
                        }
                    } else {
                        ""
                    }

                    val normalized = normalizeRecognizedText(rawText, isCjk) ?: continue
                    val ok = lib.TessPageIteratorBoundingBox(pi, level, leftRef, topRef, rightRef, bottomRef)
                    if (ok == 0) continue
                    val left = leftRef.value
                    val top = topRef.value
                    val right = rightRef.value
                    val bottom = bottomRef.value
                    if (right <= left || bottom <= top) continue

                    val bounds = NormalizedRect(
                        x = left.toFloat() / buf.width,
                        y = top.toFloat() / buf.height,
                        width = (right - left).toFloat() / buf.width,
                        height = (bottom - top).toFloat() / buf.height,
                    ).clamped()
                    if (bounds.isEmpty) continue

                    val conf = (lib.TessResultIteratorConfidence(it, level).coerceIn(0f, 100f) / 100f)
                    if (isLowConfidenceNoise(normalized, conf, bounds, isFullPageBlock = true)) continue
                    snippets.add(OcrSnippet(text = normalized, bounds = bounds, confidence = conf))
                } while (lib.TessResultIteratorNext(it, level) != 0)
            } finally {
                lib.TessResultIteratorDelete(it)
            }
        } finally {
            lib.TessBaseAPIClear(handle)
        }
        return snippets
    }

    private fun ensureInitializedHandleLocked(
        lib: TessLibrary,
        datapath: String,
        ocrLang: String,
    ): Pointer {
        var handle = apiHandle
        if (handle == null) {
            handle = lib.TessBaseAPICreate()
                ?: throw IllegalStateException("TessBaseAPICreate retornou null")
            apiHandle = handle
        }
        if (initializedDatapath != datapath || initializedLanguage != ocrLang) {
            val rc = lib.TessBaseAPIInit3(handle, datapath, ocrLang)
            if (rc != 0) {
                initializedDatapath = null
                initializedLanguage = null
                throw IllegalStateException("TessBaseAPIInit3 falhou (rc=$rc) para '$ocrLang' em '$datapath'")
            }
            lib.TessBaseAPISetVariable(handle, "user_defined_dpi", "300")
            initializedDatapath = datapath
            initializedLanguage = ocrLang
        }
        return handle
    }

    private fun releaseHandleLocked() {
        val handle = apiHandle ?: return
        val lib = nativeLib
        if (lib != null) {
            runCatching { lib.TessBaseAPIEnd(handle) }
            runCatching { lib.TessBaseAPIDelete(handle) }
        }
        apiHandle = null
        initializedDatapath = null
        initializedLanguage = null
    }

    private fun resolveTargetFor(
        sourceLang: TranslatorLanguage,
        orientation: TextOrientation,
    ): ResolvedLangTarget {
        val allowDownload = autoDownloadProvider()
        val desiredLang = if (
            orientation == TextOrientation.VERTICAL &&
            (sourceLang == TranslatorLanguage.JAPANESE ||
                sourceLang == TranslatorLanguage.CHINESE_SIMPLIFIED ||
                sourceLang == TranslatorLanguage.AUTO)
        ) {
            sourceLang.tessCodeFor(TextOrientation.VERTICAL)
        } else {
            sourceLang.tessCode
        }

        val primary = tessdataManager.ensureLanguageBlocking(desiredLang, allowDownload = allowDownload)
        if (primary.isSuccess) {
            return ResolvedLangTarget(primary.getOrThrow(), desiredLang)
        }

        // If vertical model failed (e.g. offline), try base horizontal model for same language
        if (desiredLang == "jpn_vert") {
            val fallbackJpn = tessdataManager.ensureLanguageBlocking("jpn", allowDownload = allowDownload)
            if (fallbackJpn.isSuccess) {
                return ResolvedLangTarget(fallbackJpn.getOrThrow(), "jpn")
            }
        }

        // For AUTO, degrade gracefully to any locally available model if compound download failed
        if (sourceLang == TranslatorLanguage.AUTO) {
            val jpnFile = tessdataManager.findLocalModelFile("jpn")
            val engFile = tessdataManager.findLocalModelFile("eng")
            if (jpnFile != null && engFile != null) {
                tessdataManager.resolveDatapath("jpn+eng")?.let {
                    return ResolvedLangTarget(it, "jpn+eng")
                }
            }
            for (candidate in listOf("jpn", "eng", "kor", "chi_sim", "por", "spa")) {
                tessdataManager.resolveDatapath(candidate)?.let {
                    return ResolvedLangTarget(it, candidate)
                }
            }
        }

        throw IllegalStateException(
            "Modelo OCR '$desiredLang' indisponível: ${primary.exceptionOrNull()?.message}",
            primary.exceptionOrNull(),
        )
    }

    private fun selectPsm(
        sourceLang: TranslatorLanguage,
        orientation: TextOrientation,
        ocrLang: String,
    ): Int {
        val useVerticalPsm = orientation == TextOrientation.VERTICAL && (
            ocrLang == "jpn_vert" ||
                sourceLang == TranslatorLanguage.JAPANESE ||
                sourceLang == TranslatorLanguage.CHINESE_SIMPLIFIED
            )
        return if (useVerticalPsm) PSM_SINGLE_BLOCK_VERT_TEXT else PSM_SINGLE_BLOCK
    }

    private fun toRoiGrayData(image: BufferedImage, bounds: NormalizedRect): RoiGrayData? {
        val crop = bounds.toPixelRect(image.width, image.height)
        if (crop.width <= 0 || crop.height <= 0) return null

        val minDim = minOf(crop.width, crop.height)
        val maxDim = maxOf(crop.width, crop.height)
        val rawScale = when {
            minDim < 38 -> 4
            minDim < 145 -> 3
            minDim < 190 -> 2
            else -> 1
        }
        val maxAllowedScale = (2400 / maxDim).coerceAtLeast(1)
        val scale = rawScale.coerceAtMost(maxAllowedScale)
        val scaledW = (crop.width * scale).coerceAtLeast(1)
        val scaledH = (crop.height * scale).coerceAtLeast(1)
        val border = 16
        val targetW = scaledW + border * 2
        val targetH = scaledH + border * 2

        val rgbImg = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
        val g2 = rgbImg.createGraphics()
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.color = Color.WHITE
            g2.fillRect(0, 0, targetW, targetH)
            g2.drawImage(
                image,
                border,
                border,
                border + scaledW,
                border + scaledH,
                crop.x,
                crop.y,
                crop.x + crop.width,
                crop.y + crop.height,
                null,
            )
        } finally {
            g2.dispose()
        }

        val rgbPixels = IntArray(targetW * targetH)
        rgbImg.getRGB(0, 0, targetW, targetH, rgbPixels, 0, targetW)
        rgbImg.flush()

        val copied = ByteArray(targetW * targetH)
        val x1 = border
        val x2 = (border + scaledW - 1).coerceAtMost(targetW - 1)
        val y1 = border
        val y2 = (border + scaledH - 1).coerceAtMost(targetH - 1)

        // Check if ROI border has strong chromatic tint (e.g. dark teal text on orange sunset clouds)
        var bRSum = 0L
        var bGSum = 0L
        var bBSum = 0L
        var bCnt = 0
        for (x in x1..x2) {
            val tPx = rgbPixels[y1 * targetW + x]
            val bPx = rgbPixels[y2 * targetW + x]
            bRSum += ((tPx shr 16) and 0xFF) + ((bPx shr 16) and 0xFF)
            bGSum += ((tPx shr 8) and 0xFF) + ((bPx shr 8) and 0xFF)
            bBSum += (tPx and 0xFF) + (bPx and 0xFF)
            bCnt += 2
        }
        for (y in (y1 + 1) until y2) {
            val lPx = rgbPixels[y * targetW + x1]
            val rPx = rgbPixels[y * targetW + x2]
            bRSum += ((lPx shr 16) and 0xFF) + ((rPx shr 16) and 0xFF)
            bGSum += ((lPx shr 8) and 0xFF) + ((rPx shr 8) and 0xFF)
            bBSum += (lPx and 0xFF) + (rPx and 0xFF)
            bCnt += 2
        }
        val bR = if (bCnt > 0) (bRSum / bCnt).toInt() else 255
        val bG = if (bCnt > 0) (bGSum / bCnt).toInt() else 255
        val bB = if (bCnt > 0) (bBSum / bCnt).toInt() else 255
        val borderChroma = maxOf(bR, bG, bB) - minOf(bR, bG, bB)
        val borderLum = (0.299 * bR + 0.587 * bG + 0.114 * bB).toInt()

        var brightWhiteCount = 0
        var darkOutlineCount = 0
        var insideCount = 0
        copied.fill(255.toByte())
        for (y in y1..y2) {
            val off = y * targetW
            for (x in x1..x2) {
                val px = rgbPixels[off + x]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).roundToInt().coerceIn(0, 255)
                copied[off + x] = lum.toByte()
                insideCount++
                if (lum >= 205) brightWhiteCount++
                if (lum <= 85) darkOutlineCount++
            }
        }

        // For chromatic non-outlined text over colorful art (teal on orange sky), color distance separates glyphs far better than luminance
        val isLikelyWhiteOutlined = (borderLum < 120 && brightWhiteCount > insideCount * 0.025) ||
            (borderLum in 115..185 && brightWhiteCount in (insideCount * 0.025).toInt()..(insideCount * 0.42).toInt() &&
                darkOutlineCount in (insideCount * 0.04).toInt()..(insideCount * 0.38).toInt())

        if (borderChroma >= 42 && borderLum >= 120 && !isLikelyWhiteOutlined && insideCount > 0) {
            var maxDist = 0
            for (y in y1..y2) {
                val off = y * targetW
                for (x in x1..x2) {
                    val px = rgbPixels[off + x]
                    val dr = ((px shr 16) and 0xFF) - bR
                    val dg = ((px shr 8) and 0xFF) - bG
                    val db = (px and 0xFF) - bB
                    val d = kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble()).toInt()
                    if (d > maxDist) maxDist = d
                }
            }
            if (maxDist >= 95) {
                for (y in y1..y2) {
                    val off = y * targetW
                    for (x in x1..x2) {
                        val px = rgbPixels[off + x]
                        val dr = ((px shr 16) and 0xFF) - bR
                        val dg = ((px shr 8) and 0xFF) - bG
                        val db = (px and 0xFF) - bB
                        val d = kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble())
                        val mapped = when {
                            d <= 38.0 -> 255
                            d >= 135.0 -> 0
                            else -> ((1.0 - (d - 38.0) / 97.0) * 255.0).roundToInt().coerceIn(0, 255)
                        }
                        copied[off + x] = mapped.toByte()
                    }
                }
                return RoiGrayData(
                    buffer = GrayBuffer(copied, targetW, targetH, targetW),
                    crop = crop,
                    scale = scale,
                    border = border,
                    scaledW = scaledW,
                    scaledH = scaledH,
                )
            }
        }

        // Normalize contrast and invert white-on-dark / white-outlined regions so Tesseract sees clean black-on-white
        normalizeRoiPolarityAndContrast(copied, targetW, targetH, border, scaledW, scaledH)

        return RoiGrayData(
            buffer = GrayBuffer(copied, targetW, targetH, targetW),
            crop = crop,
            scale = scale,
            border = border,
            scaledW = scaledW,
            scaledH = scaledH,
        )
    }

    private fun normalizeRoiPolarityAndContrast(
        data: ByteArray,
        targetW: Int,
        targetH: Int,
        border: Int,
        scaledW: Int,
        scaledH: Int,
    ) {
        if (scaledW < 4 || scaledH < 4) return
        val x1 = border
        val x2 = (border + scaledW - 1).coerceAtMost(targetW - 1)
        val y1 = border
        val y2 = (border + scaledH - 1).coerceAtMost(targetH - 1)

        var borderSum = 0L
        var borderCount = 0
        val borderBandY = minOf(2, (y2 - y1 + 1) / 4).coerceAtLeast(1)
        val borderBandX = minOf(2, (x2 - x1 + 1) / 4).coerceAtLeast(1)
        for (dy in 0 until borderBandY) {
            val topOff = (y1 + dy) * targetW
            val botOff = (y2 - dy) * targetW
            for (x in x1..x2) {
                borderSum += (data[topOff + x].toInt() and 0xFF)
                borderSum += (data[botOff + x].toInt() and 0xFF)
                borderCount += 2
            }
        }
        for (y in (y1 + borderBandY)..(y2 - borderBandY)) {
            val rowOff = y * targetW
            for (dx in 0 until borderBandX) {
                borderSum += (data[rowOff + x1 + dx].toInt() and 0xFF)
                borderSum += (data[rowOff + x2 - dx].toInt() and 0xFF)
                borderCount += 2
            }
        }
        val borderMean = if (borderCount > 0) (borderSum / borderCount).toInt() else 255

        val hist = IntArray(256)
        var totalInside = 0
        var darkOutlineCount = 0
        var veryBrightCount = 0
        val veryBrightThresh = maxOf(205, borderMean + 42)
        for (y in y1..y2) {
            val off = y * targetW
            for (x in x1..x2) {
                val lum = data[off + x].toInt() and 0xFF
                hist[lum]++
                totalInside++
                if (lum <= 85) darkOutlineCount++
                if (lum >= veryBrightThresh) veryBrightCount++
            }
        }
        if (totalInside <= 0) return

        fun percentileLum(frac: Float): Int {
            val target = (totalInside * frac).roundToInt().coerceIn(1, totalInside)
            var acc = 0
            for (v in 0..255) {
                acc += hist[v]
                if (acc >= target) return v
            }
            return 255
        }

        val p05 = percentileLum(0.05f)
        val p50 = percentileLum(0.50f)
        val p75 = percentileLum(0.75f)
        val p97 = percentileLum(0.97f)
        val darkOutlinePct = darkOutlineCount.toFloat() / totalInside
        val veryBrightPct = veryBrightCount.toFloat() / totalInside

        // Never treat white-interior speech bubbles whose crop edge grazes a black oval border as dark panels.
        val isDarkPanel = borderMean < 115 && p50 < 175 && veryBrightPct < 0.55f
        val isWhiteOutlinedOnMidArt = borderMean in 115..185 &&
            veryBrightPct in 0.025f..0.42f &&
            darkOutlinePct in 0.04f..0.38f &&
            p50 <= 180 &&
            p97 >= 212

        when {
            isDarkPanel || isWhiteOutlinedOnMidArt -> {
                if (isDarkPanel && (p97 <= borderMean + 35 || p97 < 145)) {
                    for (y in y1..y2) {
                        val off = y * targetW
                        for (x in x1..x2) {
                            val idx = off + x
                            data[idx] = (255 - (data[idx].toInt() and 0xFF)).toByte()
                        }
                    }
                    return
                }
                // Guard bright cosmic/nebula streaks using dark-halo check: real white text strokes sit near a dark border/bg
                val useDarkHaloGuard = p97 >= 200 && darkOutlinePct >= 0.04f && borderMean >= 38
                var localMinBuf: IntArray? = null
                if (useDarkHaloGuard) {
                    val rw = x2 - x1 + 1
                    val rh = y2 - y1 + 1
                    val radius = (minOf(rw, rh) / 14).coerceIn(5, 11)
                    val hMin = IntArray(rw * rh)
                    for (ry in 0 until rh) {
                        val rowOff = (y1 + ry) * targetW
                        val outOff = ry * rw
                        for (rx in 0 until rw) {
                            var m = 255
                            val k1 = (rx - radius).coerceAtLeast(0)
                            val k2 = (rx + radius).coerceAtMost(rw - 1)
                            for (kx in k1..k2) {
                                val v = data[rowOff + x1 + kx].toInt() and 0xFF
                                if (v < m) m = v
                            }
                            hMin[outOff + rx] = m
                        }
                    }
                    val fullMin = IntArray(rw * rh)
                    for (rx in 0 until rw) {
                        for (ry in 0 until rh) {
                            var m = 255
                            val k1 = (ry - radius).coerceAtLeast(0)
                            val k2 = (ry + radius).coerceAtMost(rh - 1)
                            for (ky in k1..k2) {
                                val v = hMin[ky * rw + rx]
                                if (v < m) m = v
                            }
                            fullMin[ry * rw + rx] = m
                        }
                    }
                    localMinBuf = fullMin
                }

                val low = if (useDarkHaloGuard) {
                    134
                } else {
                    val bgWeight = if (isDarkPanel) 0.50f else 0.46f
                    val maxLow = if (isDarkPanel) 195 else 208
                    (borderMean * bgWeight + p97 * (1f - bgWeight))
                        .roundToInt()
                        .coerceIn((borderMean + 24).coerceAtMost(maxLow), maxLow)
                }
                val high = maxOf(p97 - 15, low + 22).coerceAtMost(245)
                val span = (high - low).coerceAtLeast(1).toFloat()
                val rw = x2 - x1 + 1
                for (y in y1..y2) {
                    val off = y * targetW
                    val ry = y - y1
                    for (x in x1..x2) {
                        val idx = off + x
                        if (localMinBuf != null && localMinBuf[ry * rw + (x - x1)] > 95) {
                            data[idx] = 255.toByte()
                            continue
                        }
                        val lum = data[idx].toInt() and 0xFF
                        val mapped = when {
                            lum <= low -> 255
                            lum >= high -> 0
                            else -> ((1f - (lum - low) / span) * 255f).roundToInt().coerceIn(0, 255)
                        }
                        data[idx] = mapped.toByte()
                    }
                }
            }

            p75 in 135..238 && p50 >= 130 && (p75 - p05) >= 42 && p05 <= 140 -> {
                val darkFloor = (p05 + 10).coerceIn(15, 130)
                val paperFloor = (p75 - 22).coerceIn(darkFloor + 30, 232)
                val span = (paperFloor - darkFloor).coerceAtLeast(1).toFloat()
                for (y in y1..y2) {
                    val off = y * targetW
                    for (x in x1..x2) {
                        val idx = off + x
                        val lum = data[idx].toInt() and 0xFF
                        val mapped = when {
                            lum <= darkFloor -> 0
                            lum >= paperFloor -> 255
                            else -> (((lum - darkFloor) / span) * 255f).roundToInt().coerceIn(0, 255)
                        }
                        data[idx] = mapped.toByte()
                    }
                }
            }
        }
    }

    private fun toFullPageGrayBuffer(image: BufferedImage): GrayBuffer {
        val w = image.width
        val h = image.height
        val scale = when {
            w < 120 || h < 120 -> 2.0
            h > w * 2.5 -> minOf(1.0, minOf(1000.0 / w, 3200.0 / h))
            w > h * 2.5 -> minOf(1.0, minOf(3200.0 / w, 1000.0 / h))
            else -> minOf(1.0, 1600.0 / maxOf(w, h))
        }
        val targetW = (w * scale).roundToInt().coerceAtLeast(1)
        val targetH = (h * scale).roundToInt().coerceAtLeast(1)

        val grayImg = BufferedImage(targetW, targetH, BufferedImage.TYPE_BYTE_GRAY)
        val g2 = grayImg.createGraphics()
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g2.color = Color.WHITE
            g2.fillRect(0, 0, targetW, targetH)
            g2.drawImage(image, 0, 0, targetW, targetH, null)
        } finally {
            g2.dispose()
        }

        val rawData = (grayImg.raster.dataBuffer as DataBufferByte).data
        val stride = (grayImg.raster.sampleModel as? ComponentSampleModel)?.scanlineStride ?: targetW
        val copied = if (stride == targetW && rawData.size >= targetW * targetH) {
            rawData.copyOf(targetW * targetH)
        } else {
            ByteArray(targetW * targetH).also { out ->
                for (y in 0 until targetH) {
                    System.arraycopy(rawData, y * stride, out, y * targetW, targetW)
                }
            }
        }
        grayImg.flush()
        return GrayBuffer(copied, targetW, targetH, targetW)
    }

    private fun recognizeRoiWithCli(
        roi: RoiGrayData,
        item: RegionWorkItem,
        sourceLang: TranslatorLanguage,
    ): OcrSnippet? {
        val tsv = runCliTsv(roi.buffer, item.target.datapath, item.target.ocrLang, item.psm) ?: return null
        val words = parseTsvWords(tsv)
        if (words.isEmpty()) return null
        val isCjk = isCjkLang(item.target.ocrLang, sourceLang)
        val tokens = words.map { w ->
            RoiWordToken(
                text = w.text,
                left = w.left,
                top = w.top,
                right = w.left + w.width,
                bottom = w.top + w.height,
                conf = w.conf.coerceIn(0f, 100f) / 100f,
            )
        }
        return evaluateRoiTokens(tokens, roi, item.region.bounds, isCjk, requireStrictCjk = false)
    }

    private fun recognizeFullPageWithCli(
        buf: GrayBuffer,
        target: ResolvedLangTarget,
        isCjk: Boolean,
    ): List<OcrSnippet> {
        val tsv = runCliTsv(buf, target.datapath, target.ocrLang, PSM_AUTO) ?: return emptyList()
        val words = parseTsvWords(tsv)
        if (words.isEmpty()) return emptyList()
        return words.groupBy { it.blockNum }.values.mapNotNull { blockWords ->
            val raw = blockWords.joinToString(if (isCjk) "" else " ") { it.text }
            val normalized = normalizeRecognizedText(raw, isCjk) ?: return@mapNotNull null
            val minX = blockWords.minOf { it.left }
            val minY = blockWords.minOf { it.top }
            val maxX = blockWords.maxOf { it.left + it.width }
            val maxY = blockWords.maxOf { it.top + it.height }
            val bounds = NormalizedRect(
                x = minX.toFloat() / buf.width,
                y = minY.toFloat() / buf.height,
                width = (maxX - minX).toFloat() / buf.width,
                height = (maxY - minY).toFloat() / buf.height,
            ).clamped()
            if (bounds.isEmpty) return@mapNotNull null
            val avgConf = blockWords.map { it.conf }.average().toFloat().coerceIn(0f, 100f) / 100f
            if (isLowConfidenceNoise(normalized, avgConf, bounds, isFullPageBlock = true)) return@mapNotNull null
            OcrSnippet(normalized, bounds, avgConf)
        }
    }

    private fun runCliTsv(buf: GrayBuffer, datapath: File, ocrLang: String, psm: Int): String? {
        val tmpPng = Files.createTempFile("mihon-ocr-", ".png").toFile()
        return try {
            val img = BufferedImage(buf.width, buf.height, BufferedImage.TYPE_BYTE_GRAY)
            val dst = (img.raster.dataBuffer as DataBufferByte).data
            System.arraycopy(buf.data, 0, dst, 0, minOf(buf.data.size, dst.size))
            ImageIO.write(img, "png", tmpPng)
            img.flush()

            val cmd = listOf(
                "tesseract",
                tmpPng.absolutePath,
                "stdout",
                "--tessdata-dir",
                datapath.absolutePath,
                "-l",
                ocrLang,
                "--psm",
                psm.toString(),
                "--dpi",
                "300",
                "tsv",
            )
            val res = cliCommandRunner?.invoke(cmd) ?: executeCliProcess(cmd)
            if (res.exitCode == 0) res.stdout else null
        } finally {
            tmpPng.delete()
        }
    }

    private fun executeCliProcess(cmd: List<String>): CliResult {
        val process = ProcessBuilder(cmd).redirectErrorStream(false).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return CliResult(-1, "")
        }
        return CliResult(process.exitValue(), stdout)
    }

    private data class TsvWord(
        val blockNum: Int,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val conf: Float,
        val text: String,
    )

    private fun parseTsvWords(tsv: String): List<TsvWord> {
        val out = mutableListOf<TsvWord>()
        for (line in tsv.lineSequence()) {
            if (line.startsWith("level\t") || line.isBlank()) continue
            val cols = line.split('\t')
            if (cols.size < 12) continue
            val level = cols[0].toIntOrNull() ?: continue
            if (level != 5) continue
            val text = cols[11].trim()
            if (text.isEmpty()) continue
            val blockNum = cols[2].toIntOrNull() ?: 0
            val left = cols[6].toIntOrNull() ?: 0
            val top = cols[7].toIntOrNull() ?: 0
            val width = cols[8].toIntOrNull() ?: 0
            val height = cols[9].toIntOrNull() ?: 0
            val conf = cols[10].toFloatOrNull() ?: 0f
            out.add(TsvWord(blockNum, left, top, width, height, conf, text))
        }
        return out
    }

    private fun isCjkLang(ocrLang: String, sourceLang: TranslatorLanguage): Boolean =
        ocrLang.contains("jpn") ||
            ocrLang.contains("kor") ||
            ocrLang.contains("chi") ||
            sourceLang == TranslatorLanguage.JAPANESE ||
            sourceLang == TranslatorLanguage.KOREAN ||
            sourceLang == TranslatorLanguage.CHINESE_SIMPLIFIED

    private fun normalizeRecognizedText(raw: String, isCjk: Boolean): String? {
        val lines = raw.replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trim().replace(MULTI_SPACE_REGEX, " ") }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) return null
        val joined = lines.joinToString(if (isCjk) "" else " ").trim()
        if (!hasAlphaNumericOrCjk(joined)) return null
        return joined
    }

    private fun hasAlphaNumericOrCjk(text: String): Boolean =
        text.any { ch ->
            ch.isLetterOrDigit() || Character.UnicodeScript.of(ch.code) in CJK_SCRIPTS
        }

    private fun isLowConfidenceNoise(
        normalized: String,
        confidence: Float,
        bounds: NormalizedRect? = null,
        isFullPageBlock: Boolean = false,
    ): Boolean {
        val hasCjk = normalized.any { Character.UnicodeScript.of(it.code) in CJK_SCRIPTS }
        val compactLength = normalized.count { !it.isWhitespace() }
        if (!hasCjk) {
            if (compactLength <= 2 && confidence < 0.85f) return true
            if (compactLength <= 3 && confidence < 0.68f) return true
            if (confidence < 0.20f) return true
            if (isFullPageBlock) {
                val letters = normalized.count { it.isLetter() }
                val junk = normalized.count { !it.isLetterOrDigit() && !it.isWhitespace() && it !in NORMAL_PUNCTUATION }
                val hasVowel = normalized.any { it in VOWEL_CHARS }
                if (letters < 3) return true
                if (!hasVowel && confidence < 0.86f) return true
                if (junk >= letters / 2 + 1) return true
                if (confidence < 0.62f && letters < 12) return true
                if (bounds != null && compactLength <= 2 && bounds.width > 0.75f && bounds.height > 0.75f) {
                    return true
                }
            }
        } else {
            val realCjk = normalized.count { Character.UnicodeScript.of(it.code) in CJK_SCRIPTS && it != 'ー' && it != '一' }
            if (realCjk == 0 || confidence < 0.30f) return true
            if (compactLength <= 2 && confidence < 0.68f) return true
            if (isFullPageBlock && (realCjk < 2 || confidence < 0.52f)) return true
        }
        return false
    }

    companion object {
        const val PSM_AUTO = 3
        const val PSM_SINGLE_BLOCK_VERT_TEXT = 5
        const val PSM_SINGLE_BLOCK = 6
        const val PSM_SINGLE_LINE = 7
        const val PSM_SPARSE_TEXT = 11

        const val RIL_BLOCK = 0
        const val RIL_TEXTLINE = 2
        const val RIL_WORD = 3

        private val TEXT_BLOCK_TYPES = 1..8
        private val MULTI_SPACE_REGEX = Regex("[ \\t]+")
        private val VOWEL_CHARS = setOf('a', 'e', 'i', 'o', 'u', 'y', 'A', 'E', 'I', 'O', 'U', 'Y')
        private val NORMAL_PUNCTUATION = setOf('.', ',', '!', '?', '\'', '"', '-', ':', ';', '(', ')', '…', '’', '“', '”', '—', '*', '%', '&')
        private val ALLOWED_SHORT_WORDS = setOf(
            "OK", "NO", "SO", "IS", "IT", "TO", "ME", "HE", "WE", "GO", "IN", "ON", "UP", "IF",
            "AT", "BY", "OF", "OR", "AN", "AS", "DO", "MY", "OH", "AH", "HA", "EH", "HM", "HI",
            "YA", "YO", "US", "AM", "BE", "VS", "MR", "DR", "TV", "QI", "HP", "MP",
        )
        private val ALLOWED_NO_VOWEL_TOKENS = setOf("HMM", "SHH", "TSK", "GRR", "ZZZ", "MR", "DR", "VS", "TV", "QI", "HP", "MP")
        private val FRAME_STRIP_CHARS = setOf('|', '[', ']', '<', '>', '_', '~', '\\')
        private val FRAME_STRIP_END_CHARS = setOf('|', '[', ']', '<', '>', '_', '~', '\\')
        private val CJK_SCRIPTS = setOf(
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL,
        )

        val DEFAULT_LIBRARY_CANDIDATES = listOf(
            "tesseract",
            "libtesseract.so.5.5",
            "/lib64/libtesseract.so.5.5",
            "/usr/lib64/libtesseract.so.5.5",
            "libtesseract.so.5",
            "/usr/lib/x86_64-linux-gnu/libtesseract.so.5",
            "/usr/lib/aarch64-linux-gnu/libtesseract.so.5",
        )

        private fun loadNativeLibrary(candidates: List<String>): TessLibrary? {
            for (candidate in candidates) {
                val loaded = runCatching {
                    val lib = Native.load(candidate, TessLibrary::class.java)
                    lib.TessVersion()
                    lib
                }.getOrNull()
                if (loaded != null) return loaded
            }
            return null
        }

        private fun checkCliAvailable(): Boolean = runCatching {
            val proc = ProcessBuilder("tesseract", "--version")
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                false
            } else {
                proc.exitValue() == 0
            }
        }.getOrDefault(false)
    }
}
