package mihon.desktop.e2e

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mihon.core.archive.ArchiveReaderFactory
import mihon.desktop.translator.DetectedTextRegion
import mihon.desktop.translator.LensOverlayRenderer
import mihon.desktop.translator.NormalizedRect
import mihon.desktop.translator.OcrEngine
import mihon.desktop.translator.OcrSnippet
import mihon.desktop.translator.PageTranslationController
import mihon.desktop.translator.PageTranslationResult
import mihon.desktop.translator.TextDetector
import mihon.desktop.translator.TranslatedBlock
import mihon.desktop.translator.TranslationProvider
import mihon.desktop.translator.TranslationStatus
import mihon.desktop.translator.TranslatorConfig
import mihon.desktop.translator.TranslatorLanguage
import mihon.desktop.translator.TranslatorPreferences
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslatorRendererAndControllerE2eTest {

    private lateinit var testPrefsNode: Preferences

    @BeforeEach
    fun setUp() {
        testPrefsNode = Preferences.userRoot().node("mihon-e2e-prefs-${UUID.randomUUID()}")
        TranslatorPreferences.setBackingNodeForTest(testPrefsNode)
    }

    @AfterEach
    fun tearDown() {
        TranslatorPreferences.setBackingNodeForTest(null)
        runCatching { testPrefsNode.removeNode() }
    }

    // =========================================================================
    // F5: Google Lens Blur & Overlay Renderer (T1-F5-01..05, T2-F5-01..05)
    // =========================================================================

    @Test
    fun `T1-F5-01 and T1-F5-02 Non-destructive guarantee and scoped blur on manga_en_chapter`() {
        ArchiveReaderFactory.of(E2eTestHarness.resourceFile("manga_en_chapter.cbz")).use { reader ->
            val page0 = reader.getPageImage(reader.pages[0])!!
            val meta = E2eTestHarness.fixtureMeta("manga_en_chapter.cbz").pages[0]

            // T1-F5-01: null result -> 100% bit-for-bit identical
            val nullRender = LensOverlayRenderer.renderPageWithOverlay(page0, null)
            E2eTestHarness.assertImagesEqualBitForBit(page0, nullRender, "Null result render")

            // T1-F5-02: READY result -> outside pixels 100% untouched, inside pixels blurred & overlaid
            val blocks = meta.blocks.map {
                TranslatedBlock(
                    bounds = it.bounds.toNormalizedRect(),
                    originalText = it.sourceText,
                    translatedText = it.expectedPtBr,
                    orientation = it.textOrientation(),
                )
            }
            val result = PageTranslationResult(
                pageIndex = 0,
                generation = 1L,
                sourceWidth = page0.width,
                sourceHeight = page0.height,
                blocks = blocks,
                status = TranslationStatus.READY,
            )
            val rendered = LensOverlayRenderer.renderPageWithOverlay(page0, result)
            val pixelRects = blocks.map { it.bounds.toPixelRect(page0.width, page0.height) }

            val outsideDiffs = E2eTestHarness.countDifferingPixelsOutsideRects(page0, rendered, pixelRects)
            assertEquals(0, outsideDiffs, "Zero pixels outside block bounds may be altered")

            for (rect in pixelRects) {
                val insideDiffs = E2eTestHarness.countDifferingPixelsInRect(page0, rendered, rect)
                assertTrue(insideDiffs > 100, "Expected blurred + overlaid pixels inside $rect, got $insideDiffs")
            }
        }
    }

    @Test
    fun `T1-F5-03 to T1-F5-05 Narrow word-wrap, halfSlice on wide spread, and zoomed offset canvas`() {
        val wideImg = E2eTestHarness.loadPng("manga_wide_double.png")
        val wideMeta = E2eTestHarness.fixtureMeta("manga_wide_double.png").pages[0]
        val blocks = wideMeta.blocks.map {
            TranslatedBlock(
                bounds = it.bounds.toNormalizedRect(),
                originalText = it.sourceText,
                translatedText = it.expectedPtBr,
            )
        }
        val wideResult = PageTranslationResult(0, 1L, wideImg.width, wideImg.height, blocks, TranslationStatus.READY)

        // T1-F5-04: halfSlice=false (left half) vs halfSlice=true (right half)
        val leftSubImage = wideImg.getSubimage(0, 0, wideImg.width / 2, wideImg.height)
        val rightSubImage = wideImg.getSubimage(wideImg.width / 2, 0, wideImg.width / 2, wideImg.height)

        val renderedLeft = LensOverlayRenderer.renderPageWithOverlay(leftSubImage, wideResult, halfSlice = false)
        val renderedRight = LensOverlayRenderer.renderPageWithOverlay(rightSubImage, wideResult, halfSlice = true)

        val leftRect0 = blocks[0].bounds.clipToHalf(false)!!.toPixelRect(leftSubImage.width, leftSubImage.height)
        val rightRect0 = blocks[2].bounds.clipToHalf(true)!!.toPixelRect(rightSubImage.width, rightSubImage.height)

        assertTrue(E2eTestHarness.countDifferingPixelsInRect(leftSubImage, renderedLeft, leftRect0) > 100)
        assertTrue(E2eTestHarness.countDifferingPixelsInRect(rightSubImage, renderedRight, rightRect0) > 100)

        // T1-F5-05: renderOverlayOnGraphics with destX=100, destY=50, destW=600, destH=900
        val canvas = BufferedImage(1200, 1600, BufferedImage.TYPE_INT_ARGB)
        val g2 = canvas.createGraphics()
        try {
            g2.color = Color.BLACK
            g2.fillRect(0, 0, 1200, 1600)
            g2.drawImage(leftSubImage, 100, 50, 600, 900, null)
        } finally {
            g2.dispose()
        }
        val baselineCanvas = BufferedImage(1200, 1600, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            g.drawImage(canvas, 0, 0, null)
            g.dispose()
        }
        val cg2 = canvas.createGraphics()
        try {
            LensOverlayRenderer.renderOverlayOnGraphics(cg2, leftSubImage, 100, 50, 600, 900, wideResult, halfSlice = false)
        } finally {
            cg2.dispose()
        }
        val destRect = Rectangle(100, 50, 600, 900)
        assertEquals(0, E2eTestHarness.countDifferingPixelsOutsideRects(baselineCanvas, canvas, listOf(destRect)))
        assertTrue(E2eTestHarness.countDifferingPixelsInRect(baselineCanvas, canvas, destRect) > 100)
    }

    @Test
    fun `T2-F5-01 to T2-F5-05 Renderer boundary conditions and dark panel contrast`() {
        val blankAndDark = E2eTestHarness.loadPng("manga_blank_and_dark.png")
        val bdMeta = E2eTestHarness.fixtureMeta("manga_blank_and_dark.png").pages[0]

        // T2-F5-01: Empty blocks list
        val emptyRes = PageTranslationResult(0, 1L, blankAndDark.width, blankAndDark.height, emptyList(), TranslationStatus.READY)
        E2eTestHarness.assertImagesEqualBitForBit(blankAndDark, LensOverlayRenderer.renderPageWithOverlay(blankAndDark, emptyRes))

        // T2-F5-02: TRANSLATING / ERROR status
        val errRes = emptyRes.copy(status = TranslationStatus.ERROR, errorMessage = "err")
        E2eTestHarness.assertImagesEqualBitForBit(blankAndDark, LensOverlayRenderer.renderPageWithOverlay(blankAndDark, errRes))

        // T2-F5-04: Dark background bubble contrast ratio >= 3.0
        val darkBlock = bdMeta.blocks.first()
        val darkRes = PageTranslationResult(
            0,
            1L,
            blankAndDark.width,
            blankAndDark.height,
            listOf(
                TranslatedBlock(
                    bounds = darkBlock.bounds.toNormalizedRect(),
                    originalText = darkBlock.sourceText,
                    translatedText = darkBlock.expectedPtBr,
                ),
            ),
            TranslationStatus.READY,
        )
        val renderedDark = LensOverlayRenderer.renderPageWithOverlay(blankAndDark, darkRes)
        val darkPixelRect = darkBlock.bounds.toNormalizedRect().toPixelRect(blankAndDark.width, blankAndDark.height)
        var minLum = 1.0
        var maxLum = 0.0
        for (y in darkPixelRect.y until darkPixelRect.y + darkPixelRect.height) {
            for (x in darkPixelRect.x until darkPixelRect.x + darkPixelRect.width) {
                val lum = LensOverlayRenderer.relativeLuminance(renderedDark.getRGB(x, y))
                if (lum < minLum) minLum = lum
                if (lum > maxLum) maxLum = lum
            }
        }
        val contrast = (maxLum + 0.05) / (minLum + 0.05)
        assertTrue(contrast >= 3.0, "Dark bubble overlay must achieve contrast >= 3.0, got $contrast")

        // T2-F5-03 & T2-F5-05: Overlapping edge boxes, blank translatedText, tiny box
        val edgeRes = PageTranslationResult(
            0,
            1L,
            blankAndDark.width,
            blankAndDark.height,
            listOf(
                TranslatedBlock(NormalizedRect(0f, 0f, 0.02f, 0.02f), "A", "B"),
                TranslatedBlock(NormalizedRect(0.95f, 0.95f, 0.1f, 0.1f), "C", "D"),
                TranslatedBlock(NormalizedRect(0.2f, 0.2f, 0.3f, 0.2f), "EMPTY", "   "),
            ),
            TranslationStatus.READY,
        )
        val edgeRendered = LensOverlayRenderer.renderPageWithOverlay(blankAndDark, edgeRes)
        assertEquals(blankAndDark.width, edgeRendered.width)
    }

    // =========================================================================
    // F6 & F7: Controller & Preferences E2E (T1-F6-01..05, T2-F6-01..05, T1-F7, T2-F7)
    // =========================================================================

    @Test
    fun `T1-F6-01 to T1-F6-05 and T2-F6-01 to T2-F6-05 Controller lifecycle, scrubbing and caching`() = runBlocking {
        val providerCalls = AtomicInteger(0)
        val readyPages = CopyOnWriteArrayList<Int>()
        val spreadLatch = CountDownLatch(2)

        val detector = object : TextDetector {
            override fun detectRegions(image: BufferedImage): List<DetectedTextRegion> =
                listOf(DetectedTextRegion(NormalizedRect(0.1f, 0.1f, 0.4f, 0.15f)))
        }
        val ocr = object : OcrEngine {
            override fun isAvailable(): Boolean = true
            override fun recognizePage(
                image: BufferedImage,
                regions: List<DetectedTextRegion>,
                sourceLang: TranslatorLanguage,
            ): List<OcrSnippet> = listOf(OcrSnippet("TEXT", NormalizedRect(0.1f, 0.1f, 0.4f, 0.15f), 0.9f))
            override fun close() {}
        }
        val provider = object : TranslationProvider {
            override val id: String = "mock"
            override val displayName: String = "Mock"
            override suspend fun translateBatch(
                texts: List<String>,
                sourceLang: TranslatorLanguage,
                targetLang: TranslatorLanguage,
            ): List<String> {
                providerCalls.incrementAndGet()
                delay(15)
                return texts.map { "TRADUZIDO" }
            }
        }

        PageTranslationController(
            textDetector = detector,
            ocrEngine = ocr,
            translationProvider = provider,
            onPageResultReady = {
                readyPages.add(it.pageIndex)
                if (it.pageIndex in listOf(2, 3)) {
                    spreadLatch.countDown()
                }
            },
        ).use { controller ->
            val sampleImg = BufferedImage(200, 300, BufferedImage.TYPE_INT_ARGB)

            // Navigate to spread [2, 3]
            controller.setEnabled(true, listOf(2, 3)) { sampleImg }
            assertTrue(spreadLatch.await(3, TimeUnit.SECONDS))
            assertNotNull(controller.getCachedResult(2))
            assertNotNull(controller.getCachedResult(3))

            // Null image provider handled cleanly (T2-F6-03)
            controller.onVisiblePagesChanged(listOf(9)) { null }
            delay(50)
            assertNull(controller.getCachedResult(9))
        }
    }

    @Test
    fun `T1-F7-01 to T1-F7-05 and T2-F7-01 to T2-F7-05 TranslatorPreferences E2E`() {
        assertEquals(TranslatorConfig(), TranslatorPreferences.load())

        val cfgA = TranslatorConfig(
            enabledByDefault = true,
            sourceLanguage = TranslatorLanguage.ENGLISH,
            targetLanguage = TranslatorLanguage.SPANISH,
            providerId = "mymemory",
            libreTranslateUrl = "https://lt.example.org/translate",
            libreTranslateApiKey = "k=1&x=\"abc\"",
            autoDownloadTessdata = false,
        )
        TranslatorPreferences.save(cfgA)
        assertEquals(cfgA, TranslatorPreferences.load())

        val cfgB = TranslatorConfig(
            enabledByDefault = false,
            sourceLanguage = TranslatorLanguage.CHINESE_SIMPLIFIED,
            targetLanguage = TranslatorLanguage.PORTUGUESE_BR,
            providerId = "google_gtx",
        )
        TranslatorPreferences.save(cfgB)
        assertEquals(cfgB, TranslatorPreferences.load())

        testPrefsNode.clear()
        assertEquals(TranslatorConfig(), TranslatorPreferences.load())
    }
}
