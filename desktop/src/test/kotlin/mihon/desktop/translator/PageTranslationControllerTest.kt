package mihon.desktop.translator

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageTranslationControllerTest {

    private fun dummyImage(w: Int = 200, h: Int = 300): BufferedImage =
        BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)

    @Test
    fun `disabled controller triggers zero OCR or translation calls`() {
        val ocrCalls = AtomicInteger(0)
        val translateCalls = AtomicInteger(0)

        val detector = object : TextDetector {
            override fun detectRegions(image: BufferedImage): List<DetectedTextRegion> =
                listOf(DetectedTextRegion(NormalizedRect(0.1f, 0.1f, 0.5f, 0.2f)))
        }
        val ocr = object : OcrEngine {
            override fun isAvailable(): Boolean = true
            override fun recognizePage(
                image: BufferedImage,
                regions: List<DetectedTextRegion>,
                sourceLang: TranslatorLanguage,
            ): List<OcrSnippet> {
                ocrCalls.incrementAndGet()
                return listOf(OcrSnippet("HELLO", NormalizedRect(0.1f, 0.1f, 0.5f, 0.2f), 0.95f))
            }
            override fun close() {}
        }
        val provider = object : TranslationProvider {
            override val id: String = "fake"
            override val displayName: String = "Fake"
            override suspend fun translateBatch(
                texts: List<String>,
                sourceLang: TranslatorLanguage,
                targetLang: TranslatorLanguage,
            ): List<String> {
                translateCalls.incrementAndGet()
                return texts.map { "PT:$it" }
            }
        }

        PageTranslationController(
            textDetector = detector,
            ocrEngine = ocr,
            translationProvider = provider,
        ).use { controller ->
            controller.setEnabled(false, listOf(0, 1)) { dummyImage() }
            controller.onVisiblePagesChanged(listOf(0, 1)) { dummyImage() }
            Thread.sleep(80)
            assertEquals(0, ocrCalls.get())
            assertEquals(0, translateCalls.get())
            assertNull(controller.getCachedResult(0))
        }
    }

    @Test
    fun `enabling controller translates visible pages and serves LRU cache on revisit`() {
        val ocrCalls = AtomicInteger(0)
        val translateCalls = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val results = CopyOnWriteArrayList<PageTranslationResult>()

        val detector = object : TextDetector {
            override fun detectRegions(image: BufferedImage): List<DetectedTextRegion> =
                listOf(DetectedTextRegion(NormalizedRect(0.2f, 0.2f, 0.4f, 0.2f)))
        }
        val ocr = object : OcrEngine {
            override fun isAvailable(): Boolean = true
            override fun recognizePage(
                image: BufferedImage,
                regions: List<DetectedTextRegion>,
                sourceLang: TranslatorLanguage,
            ): List<OcrSnippet> {
                ocrCalls.incrementAndGet()
                return listOf(OcrSnippet("HELLO WORLD", NormalizedRect(0.2f, 0.2f, 0.4f, 0.2f), 0.9f))
            }
            override fun close() {}
        }
        val provider = object : TranslationProvider {
            override val id: String = "fake"
            override val displayName: String = "Fake"
            override suspend fun translateBatch(
                texts: List<String>,
                sourceLang: TranslatorLanguage,
                targetLang: TranslatorLanguage,
            ): List<String> {
                translateCalls.incrementAndGet()
                return texts.map { "OLÁ MUNDO" }
            }
        }

        PageTranslationController(
            textDetector = detector,
            ocrEngine = ocr,
            translationProvider = provider,
            onPageResultReady = {
                results.add(it)
                latch.countDown()
            },
        ).use { controller ->
            controller.setEnabled(true, listOf(0)) { dummyImage() }
            assertTrue(latch.await(3, TimeUnit.SECONDS), "Expected page 0 translation to complete")

            val cached = controller.getCachedResult(0)
            assertNotNull(cached)
            assertEquals(TranslationStatus.READY, cached.status)
            assertEquals(1, cached.blocks.size)
            assertEquals("OLÁ MUNDO", cached.blocks.first().translatedText)
            assertEquals(1, ocrCalls.get())
            assertEquals(1, translateCalls.get())

            // Revisit page 0 -> served from cache without calling OCR/provider again
            controller.onVisiblePagesChanged(listOf(0)) { dummyImage() }
            Thread.sleep(60)
            assertEquals(1, ocrCalls.get())
            assertEquals(1, translateCalls.get())

            // Trim memory -> evicts cache
            controller.trimMemory()
            assertNull(controller.getCachedResult(0))
        }
    }

    @Test
    fun `stale generation results are discarded after onChapterChanged`() = runBlocking {
        val results = CopyOnWriteArrayList<PageTranslationResult>()
        val startedLatch = CountDownLatch(1)
        val allowFinishLatch = CountDownLatch(1)

        val detector = object : TextDetector {
            override fun detectRegions(image: BufferedImage): List<DetectedTextRegion> =
                listOf(DetectedTextRegion(NormalizedRect(0.1f, 0.1f, 0.3f, 0.2f)))
        }
        val ocr = object : OcrEngine {
            override fun isAvailable(): Boolean = true
            override fun recognizePage(
                image: BufferedImage,
                regions: List<DetectedTextRegion>,
                sourceLang: TranslatorLanguage,
            ): List<OcrSnippet> =
                listOf(OcrSnippet("SLOW", NormalizedRect(0.1f, 0.1f, 0.3f, 0.2f), 0.9f))
            override fun close() {}
        }
        val provider = object : TranslationProvider {
            override val id: String = "slow"
            override val displayName: String = "Slow"
            override suspend fun translateBatch(
                texts: List<String>,
                sourceLang: TranslatorLanguage,
                targetLang: TranslatorLanguage,
            ): List<String> {
                startedLatch.countDown()
                allowFinishLatch.await(2, TimeUnit.SECONDS)
                delay(20)
                return listOf("LENTO")
            }
        }

        PageTranslationController(
            textDetector = detector,
            ocrEngine = ocr,
            translationProvider = provider,
            onPageResultReady = { results.add(it) },
        ).use { controller ->
            controller.setEnabled(true, listOf(0)) { dummyImage() }
            assertTrue(startedLatch.await(2, TimeUnit.SECONDS))

            // Advance chapter generation while job is mid-flight
            controller.onChapterChanged(99L)
            allowFinishLatch.countDown()
            delay(100)

            assertTrue(results.isEmpty(), "Stale chapter generation result must not be emitted")
            assertNull(controller.getCachedResult(0))
        }
    }

    @Test
    fun `provider error transitions to ERROR status without crashing controller`() {
        val statuses = CopyOnWriteArrayList<Pair<TranslationStatus, String?>>()
        val errorLatch = CountDownLatch(1)

        val detector = object : TextDetector {
            override fun detectRegions(image: BufferedImage): List<DetectedTextRegion> =
                listOf(DetectedTextRegion(NormalizedRect(0.1f, 0.1f, 0.3f, 0.2f)))
        }
        val ocr = object : OcrEngine {
            override fun isAvailable(): Boolean = true
            override fun recognizePage(
                image: BufferedImage,
                regions: List<DetectedTextRegion>,
                sourceLang: TranslatorLanguage,
            ): List<OcrSnippet> =
                listOf(OcrSnippet("FAIL", NormalizedRect(0.1f, 0.1f, 0.3f, 0.2f), 0.9f))
            override fun close() {}
        }
        val failingProvider = object : TranslationProvider {
            override val id: String = "failing"
            override val displayName: String = "Failing"
            override suspend fun translateBatch(
                texts: List<String>,
                sourceLang: TranslatorLanguage,
                targetLang: TranslatorLanguage,
            ): List<String> {
                throw IOException("Network timeout")
            }
        }

        PageTranslationController(
            textDetector = detector,
            ocrEngine = ocr,
            translationProvider = failingProvider,
            onStatusChanged = { st, msg ->
                statuses.add(st to msg)
                if (st == TranslationStatus.ERROR) errorLatch.countDown()
            },
        ).use { controller ->
            controller.setEnabled(true, listOf(0)) { dummyImage() }
            assertTrue(errorLatch.await(3, TimeUnit.SECONDS))
            assertTrue(statuses.any { it.first == TranslationStatus.ERROR && !it.second.isNullOrBlank() })
        }
    }
}
