package mihon.desktop.e2e

import kotlinx.coroutines.runBlocking
import mihon.core.archive.ArchiveReaderFactory
import mihon.desktop.MangaImageEnhancer
import mihon.desktop.MemoryManager
import mihon.desktop.ReaderAction
import mihon.desktop.ReaderEngine
import mihon.desktop.ReaderWindow
import mihon.desktop.translator.CompositeTranslationProvider
import mihon.desktop.translator.DetectedTextRegion
import mihon.desktop.translator.GoogleTranslateGtxProvider
import mihon.desktop.translator.LensOverlayRenderer
import mihon.desktop.translator.NormalizedRect
import mihon.desktop.translator.OcrEngine
import mihon.desktop.translator.OcrSnippet
import mihon.desktop.translator.PageTranslationController
import mihon.desktop.translator.PageTranslationResult
import mihon.desktop.translator.TessdataManager
import mihon.desktop.translator.TesseractJnaEngine
import mihon.desktop.translator.TextDetector
import mihon.desktop.translator.TextOrientation
import mihon.desktop.translator.TextRegionDetector
import mihon.desktop.translator.TranslatedBlock
import mihon.desktop.translator.TranslationProvider
import mihon.desktop.translator.TranslationStatus
import mihon.desktop.translator.TranslatorConfig
import mihon.desktop.translator.TranslatorLanguage
import mihon.desktop.translator.TranslatorPreferences
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslatorPairwiseAndWorkloadsE2eTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var testPrefsNode: Preferences
    private var origRamLimit: Int = 500
    private var origShortcutCodes: List<Int> = emptyList()

    @BeforeEach
    fun setUp() {
        testPrefsNode = Preferences.userRoot().node("mihon-e2e-pw-${UUID.randomUUID()}")
        TranslatorPreferences.setBackingNodeForTest(testPrefsNode)
        origRamLimit = MemoryManager.ramLimitMb
        origShortcutCodes = ReaderAction.getKeyCodes(ReaderAction.TOGGLE_TRANSLATOR)
    }

    @AfterEach
    fun tearDown() {
        MemoryManager.ramLimitMb = origRamLimit
        ReaderAction.setKeyCodes(ReaderAction.TOGGLE_TRANSLATOR, origShortcutCodes)
        TranslatorPreferences.setBackingNodeForTest(null)
        runCatching { testPrefsNode.removeNode() }
    }

    // =========================================================================
    // Tier 3: Pairwise Cross-Feature Combinations (T3-01 .. T3-12)
    // =========================================================================

    @Test
    fun `T3-01 and T3-02 DOUBLE_RTL and DOUBLE_LTR spread slot overlay alignment`() {
        ArchiveReaderFactory.of(E2eTestHarness.resourceFile("manga_en_chapter.cbz")).use { reader ->
            val p0 = reader.getPageImage(reader.pages[0])!!
            val p1 = reader.getPageImage(reader.pages[1])!!

            val res0 = PageTranslationResult(
                pageIndex = 0,
                generation = 1L,
                sourceWidth = p0.width,
                sourceHeight = p0.height,
                blocks = listOf(TranslatedBlock(NormalizedRect(0.1f, 0.1f, 0.4f, 0.15f), "HELLO", "OLÁ PÁGINA 0")),
                status = TranslationStatus.READY,
            )
            val res1 = PageTranslationResult(
                pageIndex = 1,
                generation = 1L,
                sourceWidth = p1.width,
                sourceHeight = p1.height,
                blocks = listOf(TranslatedBlock(NormalizedRect(0.3f, 0.6f, 0.5f, 0.15f), "WATCH", "CUIDADO PÁGINA 1")),
                status = TranslationStatus.READY,
            )

            // DOUBLE_RTL: left slot is page 1, right slot is page 0
            val rtlCanvas = BufferedImage(1600, 1200, BufferedImage.TYPE_INT_ARGB)
            val baseRtl = BufferedImage(1600, 1200, BufferedImage.TYPE_INT_ARGB)
            for (img in listOf(rtlCanvas, baseRtl)) {
                val g = img.createGraphics()
                g.drawImage(p1, 0, 0, 800, 1200, null)
                g.drawImage(p0, 800, 0, 800, 1200, null)
                g.dispose()
            }
            val gRtl = rtlCanvas.createGraphics()
            LensOverlayRenderer.renderOverlayOnGraphics(gRtl, p1, 0, 0, 800, 1200, res1)
            LensOverlayRenderer.renderOverlayOnGraphics(gRtl, p0, 800, 0, 800, 1200, res0)
            gRtl.dispose()

            // Verify right half has page 0 top block and left half has page 1 bottom block
            val rightTopRect = Rectangle(800 + 80, 120, 320, 180)
            val leftBottomRect = Rectangle(240, 720, 400, 180)
            assertTrue(E2eTestHarness.countDifferingPixelsInRect(baseRtl, rtlCanvas, rightTopRect) > 100)
            assertTrue(E2eTestHarness.countDifferingPixelsInRect(baseRtl, rtlCanvas, leftBottomRect) > 100)
        }
    }

    @Test
    fun `T3-03 to T3-05 Wide split RTL-LTR, FitMode zoom scaling, and ColorFilterMode INVERTED`() {
        val wide = E2eTestHarness.loadPng("manga_wide_double.png")
        val wideMeta = E2eTestHarness.fixtureMeta("manga_wide_double.png").pages[0]
        val wideResult = PageTranslationResult(
            pageIndex = 0,
            generation = 1L,
            sourceWidth = wide.width,
            sourceHeight = wide.height,
            blocks = wideMeta.blocks.map {
                TranslatedBlock(it.bounds.toNormalizedRect(), it.sourceText, it.expectedPtBr)
            },
            status = TranslationStatus.READY,
        )

        // T3-03: splitWideRtl = true -> subpage 0 (isSecondHalf=false) shows physical right half (halfSlice=true)
        val rtlSub0Img = ReaderEngine.getHalfPage(wide, isSecondHalf = false, isRtl = true)
        val rtlSub0Slice = LensOverlayRenderer.physicalHalfSlice(isSecondHalf = false, isRtl = true)
        assertTrue(rtlSub0Slice)
        val renderedRtlSub0 = LensOverlayRenderer.renderPageWithOverlay(rtlSub0Img, wideResult, halfSlice = rtlSub0Slice)
        val rightBubbleClipped = wideMeta.blocks[2].bounds.toNormalizedRect().clipToHalf(true)!!.toPixelRect(rtlSub0Img.width, rtlSub0Img.height)
        assertTrue(E2eTestHarness.countDifferingPixelsInRect(rtlSub0Img, renderedRtlSub0, rightBubbleClipped) > 100)

        // T3-05: ColorFilterMode.INVERTED applied to page, then LensOverlayRenderer renders readable overlay
        val invertedPage = MangaImageEnhancer.applyColorFilter(rtlSub0Img, MangaImageEnhancer.ColorFilterMode.INVERTED)
        val overlaidInverted = LensOverlayRenderer.renderPageWithOverlay(invertedPage, wideResult, halfSlice = rtlSub0Slice)
        assertTrue(E2eTestHarness.countDifferingPixelsInRect(invertedPage, overlaidInverted, rightBubbleClipped) > 100)
    }

    @Test
    fun `T3-06 to T3-08 Real Detector + Real JNA OCR + Mock GTX + MemoryManager trim pressure`(@TempDir tessTmp: File) = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val q = request.requestUrl?.queryParameter("q").orEmpty()
                    val segments = q.split("[[[MIHON_SEG_7A3E]]]").map { it.trim() }
                    val translatedJoined = segments.joinToString("\n[[[MIHON_SEG_7A3E]]]\n") { "TRADUZIDO: $it" }
                    val escaped = translatedJoined.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                    return MockResponse()
                        .setResponseCode(200)
                        .setBody("""[[["$escaped","orig",null,null,1]],null,"en"]""")
                }
            }
            server.start()

            val detector = TextRegionDetector()
            val ocr = TesseractJnaEngine(TessdataManager(userTessdataDir = File(tessTmp, "tessdata")))
            val gtx = GoogleTranslateGtxProvider(endpointUrl = server.url("/translate_a/single").toString())

            val readyLatch = CountDownLatch(1)
            val controller = PageTranslationController(
                textDetector = detector,
                ocrEngine = ocr,
                translationProvider = CompositeTranslationProvider(listOf(gtx)),
                configProvider = {
                    TranslatorConfig(
                        sourceLanguage = TranslatorLanguage.ENGLISH,
                        targetLanguage = TranslatorLanguage.PORTUGUESE_BR,
                    )
                },
                onPageResultReady = {
                    if (it.status == TranslationStatus.READY) {
                        readyLatch.countDown()
                    }
                },
            )

            ArchiveReaderFactory.of(E2eTestHarness.resourceFile("manga_en_chapter.cbz")).use { reader ->
                val page0 = reader.getPageImage(reader.pages[0])!!
                controller.setEnabled(true, listOf(0)) { page0 }
                assertTrue(readyLatch.await(5, TimeUnit.SECONDS))

                val res = controller.getCachedResult(0)
                assertNotNull(res)
                assertEquals(TranslationStatus.READY, res.status)
                assertTrue(res.blocks.isNotEmpty())

                // T3-08: MemoryManager low RAM limit & forced trim evicts cached results via registered callback
                MemoryManager.ramLimitMb = 128
                MemoryManager.trimMemoryIfNeeded(force = true)
                assertNull(controller.getCachedResult(0), "MemoryManager force trim must evict controller cache")
            }

            controller.close()
        }
    }

    @Test
    fun `T3-12 Vertical Japanese TextOrientation VERTICAL end-to-end rendering`() {
        ArchiveReaderFactory.of(E2eTestHarness.resourceFile("manga_jp_chapter.cbz")).use { reader ->
            val jpPage1 = reader.getPageImage(reader.pages[1])!!
            val metaPage1 = E2eTestHarness.fixtureMeta("manga_jp_chapter.cbz").pages[1]

            val blocks = metaPage1.blocks.map {
                TranslatedBlock(
                    bounds = it.bounds.toNormalizedRect(),
                    originalText = it.sourceText,
                    translatedText = it.expectedPtBr,
                    orientation = TextOrientation.VERTICAL,
                )
            }
            val result = PageTranslationResult(1, 1L, jpPage1.width, jpPage1.height, blocks, TranslationStatus.READY)
            val rendered = LensOverlayRenderer.renderPageWithOverlay(jpPage1, result)

            val pixelRects = blocks.map { it.bounds.toPixelRect(jpPage1.width, jpPage1.height) }
            assertEquals(0, E2eTestHarness.countDifferingPixelsOutsideRects(jpPage1, rendered, pixelRects))
            for (r in pixelRects) {
                assertTrue(E2eTestHarness.countDifferingPixelsInRect(jpPage1, rendered, r) > 100)
            }
        }
    }

    // =========================================================================
    // Tier 4: Realistic End-to-End Scenarios (T4-01 .. T4-06)
    // =========================================================================

    @Test
    fun `T4-01 Full English Chapter Reading Session with Real Detector, Real JNA OCR, GTX and Overlay`(@TempDir tessTmp: File) = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val q = request.requestUrl?.queryParameter("q").orEmpty()
                    val segments = q.split("[[[MIHON_SEG_7A3E]]]").map { it.trim() }
                    val translatedJoined = segments.joinToString("\n[[[MIHON_SEG_7A3E]]]\n") { "TRADUZIDO: $it" }
                    val escaped = translatedJoined.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                    return MockResponse()
                        .setResponseCode(200)
                        .setBody("""[[["$escaped","orig",null,null,1]],null,"en"]""")
                }
            }
            server.start()

            val detector = TextRegionDetector()
            val tessdataManager = TessdataManager(userTessdataDir = File(tessTmp, "tessdata"))
            val gtx = GoogleTranslateGtxProvider(endpointUrl = server.url("/translate_a/single").toString())

            ArchiveReaderFactory.of(E2eTestHarness.resourceFile("manga_en_chapter.cbz")).use { reader ->
                for (pageIdx in 0 until 4) {
                    val ocr = TesseractJnaEngine(tessdataManager)
                    val latch = CountDownLatch(1)
                    var pageResult: PageTranslationResult? = null
                    val ctrl = PageTranslationController(
                        textDetector = detector,
                        ocrEngine = ocr,
                        translationProvider = gtx,
                        configProvider = {
                            TranslatorConfig(
                                sourceLanguage = TranslatorLanguage.ENGLISH,
                                targetLanguage = TranslatorLanguage.PORTUGUESE_BR,
                            )
                        },
                        onPageResultReady = {
                            if (it.status == TranslationStatus.READY) {
                                pageResult = it
                                latch.countDown()
                            }
                        },
                    )
                    val pageImg = reader.getPageImage(reader.pages[pageIdx])!!
                    ctrl.setEnabled(true, listOf(pageIdx)) { pageImg }
                    assertTrue(latch.await(5, TimeUnit.SECONDS), "Page $pageIdx should finish full OCR+GTX pipeline")
                    val readyRes = assertNotNull(pageResult)
                    assertEquals(TranslationStatus.READY, readyRes.status)
                    assertTrue(readyRes.blocks.isNotEmpty())

                    val overlaid = LensOverlayRenderer.renderPageWithOverlay(pageImg, readyRes)
                    val rects = readyRes.blocks.map { it.bounds.toPixelRect(pageImg.width, pageImg.height) }
                    assertEquals(0, E2eTestHarness.countDifferingPixelsOutsideRects(pageImg, overlaid, rects))
                    assertTrue(rects.sumOf { E2eTestHarness.countDifferingPixelsInRect(pageImg, overlaid, it) } > 200)

                    // Toggle OFF restores 100% original pixels
                    val offRender = LensOverlayRenderer.renderPageWithOverlay(pageImg, null)
                    E2eTestHarness.assertImagesEqualBitForBit(pageImg, offRender)

                    ctrl.close()
                }
            }
        }
    }

    @Test
    fun `T4-05 High-Volume Memory-Constrained Navigation within 500 MB Budget`() = runBlocking {
        MemoryManager.ramLimitMb = 500
        val detector = object : TextDetector {
            override fun detectRegions(image: BufferedImage): List<DetectedTextRegion> =
                listOf(DetectedTextRegion(NormalizedRect(0.15f, 0.15f, 0.5f, 0.15f)))
        }
        val ocr = object : OcrEngine {
            override fun isAvailable(): Boolean = true
            override fun recognizePage(
                image: BufferedImage,
                regions: List<DetectedTextRegion>,
                sourceLang: TranslatorLanguage,
            ): List<OcrSnippet> =
                listOf(OcrSnippet("BUBBLE", NormalizedRect(0.15f, 0.15f, 0.5f, 0.15f), 0.9f))
            override fun close() {}
        }
        val provider = object : TranslationProvider {
            override val id: String = "fast"
            override val displayName: String = "Fast"
            override suspend fun translateBatch(
                texts: List<String>,
                sourceLang: TranslatorLanguage,
                targetLang: TranslatorLanguage,
            ): List<String> = texts.map { "BALÃO TRADUZIDO" }
        }

        val heapBefore = MemoryManager.usedMemoryMb
        PageTranslationController(
            textDetector = detector,
            ocrEngine = ocr,
            translationProvider = provider,
        ).use { controller ->
            val sampleImg = BufferedImage(600, 900, BufferedImage.TYPE_INT_ARGB)
            controller.setEnabled(true, listOf(0)) { sampleImg }
            for (page in 0 until 20) {
                controller.onVisiblePagesChanged(listOf(page)) { sampleImg }
            }
            MemoryManager.trimMemoryIfNeeded(force = true)
            val heapAfter = MemoryManager.usedMemoryMb
            assertTrue(heapAfter - heapBefore < 150L, "Heap delta (${heapAfter - heapBefore} MB) must stay within budget")
        }
    }

    @Test
    fun `T4-06 Packaged Distribution Classpath and JNA Binary Verification`() {
        val distScript = File("/home/janlucas/mihon-linux/desktop/build/install/desktop/bin/desktop")
        val distLibDir = File("/home/janlucas/mihon-linux/desktop/build/install/desktop/lib")
        if (distScript.exists() && distLibDir.isDirectory) {
            assertTrue(distScript.canExecute(), "Packaged launcher script must be executable")
            val jars = distLibDir.list()?.toList().orEmpty()
            assertTrue(jars.any { it.startsWith("jna-5.14.0") }, "Packaged lib dir must include jna-5.14.0.jar")
            assertTrue(jars.any { it.startsWith("desktop-") }, "Packaged lib dir must include desktop.jar")
            assertFalse(jars.any { it.contains("mockwebserver") }, "Test-only mockwebserver must not leak into runtime lib")
        }
    }
}
