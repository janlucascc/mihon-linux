package mihon.desktop.e2e

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mihon.core.archive.ArchiveReaderFactory
import mihon.desktop.translator.CompositeTranslationProvider
import mihon.desktop.translator.DetectedTextRegion
import mihon.desktop.translator.GoogleTranslateGtxProvider
import mihon.desktop.translator.LibreTranslateProvider
import mihon.desktop.translator.MyMemoryTranslationProvider
import mihon.desktop.translator.NormalizedRect
import mihon.desktop.translator.PageTranslationResult
import mihon.desktop.translator.TessdataManager
import mihon.desktop.translator.TesseractJnaEngine
import mihon.desktop.translator.TextOrientation
import mihon.desktop.translator.TextRegionDetector
import mihon.desktop.translator.TranslatedBlock
import mihon.desktop.translator.TranslationException
import mihon.desktop.translator.TranslationStatus
import mihon.desktop.translator.TranslatorConfig
import mihon.desktop.translator.TranslatorLanguage
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslatorCoreE2eTest {

    // =========================================================================
    // F1: Translator Models & Config (T1-F1-01..05, T2-F1-01..05)
    // =========================================================================

    @Test
    fun `T1-F1-01 NormalizedRect toPixelRect scales fractional bounds accurately`() {
        val rect = NormalizedRect(0.25f, 0.5f, 0.4f, 0.2f).toPixelRect(1000, 2000)
        assertEquals(Rectangle(250, 1000, 400, 400), rect)
    }

    @Test
    fun `T1-F1-02 and T1-F1-03 NormalizedRect clipToHalf left and right halves`() {
        val left = NormalizedRect(0.1f, 0.2f, 0.3f, 0.2f).clipToHalf(isSecondHalf = false)
        assertNotNull(left)
        assertTrue(abs(left.x - 0.2f) <= 1e-4f)
        assertTrue(abs(left.width - 0.6f) <= 1e-4f)

        val right = NormalizedRect(0.6f, 0.3f, 0.2f, 0.2f).clipToHalf(isSecondHalf = true)
        assertNotNull(right)
        assertTrue(abs(right.x - 0.2f) <= 1e-4f)
        assertTrue(abs(right.width - 0.4f) <= 1e-4f)
    }

    @Test
    fun `T1-F1-04 and T1-F1-05 PageTranslationResult statuses and TranslatorConfig defaults`() {
        assertEquals(4, TranslationStatus.entries.size)
        val block = TranslatedBlock(NormalizedRect(0.1f, 0.1f, 0.2f, 0.2f), "HI", "OI", TextOrientation.VERTICAL)
        val res = PageTranslationResult(0, 1L, 800, 1200, listOf(block), TranslationStatus.READY)
        assertEquals(res, res.copy())

        val cfg = TranslatorConfig()
        assertFalse(cfg.enabledByDefault)
        assertEquals(TranslatorLanguage.JAPANESE, cfg.sourceLanguage)
        assertEquals(TranslatorLanguage.PORTUGUESE_BR, cfg.targetLanguage)
        assertEquals("google_gtx", cfg.providerId)
        assertTrue(cfg.autoDownloadTessdata)
    }

    @Test
    fun `T2-F1-01 to T2-F1-05 NormalizedRect boundary and clipping edge cases`() {
        val clamped = NormalizedRect(-0.2f, 1.2f, 1.5f, 0.5f).toPixelRect(1000, 1000)
        assertTrue(clamped.width >= 0 && clamped.height >= 0)

        assertEquals(Rectangle(0, 0, 0, 0), NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f).toPixelRect(0, 0))
        assertTrue(NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f).toPixelRect(1, 1).width in 0..1)

        assertNull(NormalizedRect(0.6f, 0.2f, 0.3f, 0.2f).clipToHalf(isSecondHalf = false))
        assertNull(NormalizedRect(0.1f, 0.2f, 0.3f, 0.2f).clipToHalf(isSecondHalf = true))

        val straddle = NormalizedRect(0.4f, 0.2f, 0.2f, 0.2f)
        val sLeft = straddle.clipToHalf(isSecondHalf = false)
        val sRight = straddle.clipToHalf(isSecondHalf = true)
        assertNotNull(sLeft)
        assertNotNull(sRight)
        assertTrue(abs(sLeft.x - 0.8f) <= 1e-4f && abs(sLeft.width - 0.2f) <= 1e-4f)
        assertTrue(abs(sRight.x - 0.0f) <= 1e-4f && abs(sRight.width - 0.2f) <= 1e-4f)

        assertNull(NormalizedRect(0.5f, 0.2f, 0.2f, 0.2f).clipToHalf(isSecondHalf = false))
    }

    // =========================================================================
    // F2: Pure-JVM Text Region Detector (T1-F2-01..05, T2-F2-01..05)
    // =========================================================================

    @Test
    fun `T1-F2-01 to T1-F2-05 TextRegionDetector detects bubbles on EN, JP, Webtoon and scaled fixtures`() {
        val detector = TextRegionDetector()

        // T1-F2-01: English CBZ page 0
        ArchiveReaderFactory.of(E2eTestHarness.resourceFile("manga_en_chapter.cbz")).use { reader ->
            val p0 = reader.getPageImage(reader.pages[0])!!
            val regions = detector.detectRegions(p0)
            assertTrue(regions.size >= 2, "Expected >= 2 regions on English page 0, got ${regions.size}")
            val manifestBlocks = E2eTestHarness.fixtureMeta("manga_en_chapter.cbz").pages[0].blocks
            for (mb in manifestBlocks) {
                val mRect = mb.bounds.toNormalizedRect()
                assertTrue(
                    regions.any { it.bounds.iou(mRect) >= 0.1f || it.bounds.intersectionArea(mRect) > 0f },
                    "Expected detected region overlapping manifest bubble ${mb.sourceText}",
                )
            }

            // T1-F2-05: Scale invariance
            val halfImg = BufferedImage(400, 600, BufferedImage.TYPE_INT_ARGB).apply {
                val g = createGraphics()
                g.drawImage(p0, 0, 0, 400, 600, null)
                g.dispose()
            }
            val scaledRegions = detector.detectRegions(halfImg)
            assertTrue(scaledRegions.isNotEmpty())
        }

        // T1-F2-02 & T1-F2-03: Japanese horizontal (page 0) and vertical (page 1)
        ArchiveReaderFactory.of(E2eTestHarness.resourceFile("manga_jp_chapter.cbz")).use { reader ->
            val jp0 = reader.getPageImage(reader.pages[0])!!
            val r0 = detector.detectRegions(jp0)
            assertTrue(r0.isNotEmpty())

            val jp1 = reader.getPageImage(reader.pages[1])!!
            val r1 = detector.detectRegions(jp1)
            assertTrue(r1.isNotEmpty())
            assertTrue(r1.any { it.orientation == TextOrientation.VERTICAL || it.bounds.height > it.bounds.width })
        }

        // T1-F2-04: Tall Webtoon strip
        val webtoonImg = E2eTestHarness.loadPng("manga_webtoon_strip.png")
        val webtoonRegions = detector.detectRegions(webtoonImg)
        assertTrue(webtoonRegions.size >= 3, "Expected >= 3 regions on webtoon strip, got ${webtoonRegions.size}")
    }

    @Test
    fun `T2-F2-01 to T2-F2-05 TextRegionDetector boundary images`() {
        val detector = TextRegionDetector()

        val blankWhite = BufferedImage(800, 1200, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, 800, 1200)
            g.dispose()
        }
        assertTrue(detector.detectRegions(blankWhite).isEmpty())

        val solidBlack = BufferedImage(800, 1200, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            g.color = Color.BLACK
            g.fillRect(0, 0, 800, 1200)
            g.dispose()
        }
        assertTrue(detector.detectRegions(solidBlack).isEmpty())

        assertTrue(detector.detectRegions(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)).isEmpty())
        assertTrue(detector.detectRegions(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)).isEmpty())

        val blankAndDark = E2eTestHarness.loadPng("manga_blank_and_dark.png")
        val bdRegions = detector.detectRegions(blankAndDark)
        assertTrue(bdRegions.none { it.bounds.bottom < 0.45f }, "Top blank half must produce 0 regions")

        val grayStrip = BufferedImage(100, 4000, BufferedImage.TYPE_BYTE_GRAY)
        val grayRegions = detector.detectRegions(grayStrip)
        assertTrue(grayRegions.all { !it.bounds.isEmpty })
    }

    // =========================================================================
    // F3: JNA Tesseract OCR & Tessdata (T1-F3-01..05, T2-F3-01..05)
    // =========================================================================

    @Test
    fun `T1-F3-01 to T1-F3-05 Real JNA Tesseract OCR and TessdataManager`(@TempDir tempDir: File) = runBlocking {
        val tessdataManager = TessdataManager(userTessdataDir = File(tempDir, "tessdata"))
        assertTrue(tessdataManager.isLanguageAvailable("eng"))

        TesseractJnaEngine(tessdataManager = tessdataManager).use { engine ->
            assertTrue(engine.isAvailable(), "TesseractJnaEngine should bind libtesseract.so.5.5 on host")

            ArchiveReaderFactory.of(E2eTestHarness.resourceFile("manga_en_chapter.cbz")).use { reader ->
                val expectedTokensByPage = listOf("HELLO", "WATCH", "STAY", "MADE")
                for (pageIdx in 0 until 4) {
                    val pageImg = reader.getPageImage(reader.pages[pageIdx])!!
                    val manifestRegions = E2eTestHarness.fixtureMeta("manga_en_chapter.cbz").pages[pageIdx].blocks.map {
                        DetectedTextRegion(it.bounds.toNormalizedRect(), it.textOrientation())
                    }
                    val snippets = engine.recognizePage(pageImg, manifestRegions, TranslatorLanguage.ENGLISH)
                    assertTrue(snippets.isNotEmpty(), "Expected non-empty OCR snippets on page $pageIdx")
                    val combinedUpper = snippets.joinToString(" ") { it.text.uppercase() }
                    assertTrue(
                        combinedUpper.contains(expectedTokensByPage[pageIdx]),
                        "Page $pageIdx OCR '$combinedUpper' should contain '${expectedTokensByPage[pageIdx]}'",
                    )
                }
            }
        }

        // T1-F3-05: MockWebServer download of missing model
        MockWebServer().use { server ->
            val payload = ByteArray(2048) { (it % 251).toByte() }
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(payload)))
            server.start()

            val dlDir = File(tempDir, "dl_tessdata")
            val customManager = TessdataManager(
                userTessdataDir = dlDir,
                searchDirs = listOf(dlDir),
                baseUrl = server.url("/tessdata").toString().trimEnd('/'),
                minModelBytes = 1024L,
            )
            val res = customManager.ensureLanguage("kor", allowDownload = true)
            assertTrue(res.isSuccess)
            assertTrue(customManager.isLanguageAvailable("kor"))
        }
    }

    @Test
    fun `T2-F3-01 to T2-F3-05 TesseractJnaEngine and TessdataManager boundary & concurrency`(@TempDir tempDir: File) = runBlocking {
        val tessdataManager = TessdataManager(userTessdataDir = File(tempDir, "tessdata"))
        val engine = TesseractJnaEngine(tessdataManager = tessdataManager)
        val sampleImg = ArchiveReaderFactory.of(E2eTestHarness.resourceFile("manga_en_chapter.cbz")).use {
            it.getPageImage(it.pages[0])!!
        }

        // T2-F3-02: degenerate tiny rect
        val tinyRes = engine.recognizePage(
            sampleImg,
            listOf(DetectedTextRegion(NormalizedRect(0.999f, 0.999f, 0.0001f, 0.0001f))),
            TranslatorLanguage.ENGLISH,
        )
        assertNotNull(tinyRes)

        // T2-F3-03: 4 concurrent coroutines
        val regions = E2eTestHarness.fixtureMeta("manga_en_chapter.cbz").pages[0].blocks.map {
            DetectedTextRegion(it.bounds.toNormalizedRect())
        }
        val jobs = (0 until 4).map {
            async {
                engine.recognizePage(sampleImg, regions, TranslatorLanguage.ENGLISH)
            }
        }
        val results = jobs.awaitAll()
        assertEquals(4, results.size)
        assertTrue(results.all { list -> list.any { it.text.uppercase().contains("HELLO") } })

        // T2-F3-04: HTTP 404 leaves no corrupt file
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()
            val dlDir = File(tempDir, "fail_tessdata")
            val failMgr = TessdataManager(
                userTessdataDir = dlDir,
                searchDirs = listOf(dlDir),
                baseUrl = server.url("/tessdata").toString().trimEnd('/'),
                minModelBytes = 1024L,
            )
            assertTrue(failMgr.ensureLanguage("jpn", allowDownload = true).isFailure)
            assertFalse(File(dlDir, "jpn.traineddata").exists())
        }

        // T2-F3-05: idempotent close
        engine.close()
        engine.close()
        assertFalse(engine.isAvailable())
        assertTrue(engine.recognizePage(sampleImg, regions, TranslatorLanguage.ENGLISH).isEmpty())
    }

    // =========================================================================
    // F4: Pluggable Translation Providers (T1-F4-01..05, T2-F4-01..05)
    // =========================================================================

    @Test
    fun `T1-F4-01 to T1-F4-05 TranslationProviders and Composite fallback`() = runBlocking {
        // GTX
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""[[["OLÁ HERÓI!\n[[[MIHON_SEG_7A3E]]]\n","HELLO HERO!\n[[[MIHON_SEG_7A3E]]]\n",null,null,1],["CUIDADO!","WATCH OUT!",null,null,1]],null,"en"]"""),
            )
            server.start()
            val gtx = GoogleTranslateGtxProvider(endpointUrl = server.url("/translate_a/single").toString())
            val out = gtx.translateBatch(listOf("HELLO HERO!", "WATCH OUT!"), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR)
            assertEquals(listOf("OLÁ HERÓI!", "CUIDADO!"), out)
            val req = server.takeRequest()
            assertEquals("en", req.requestUrl?.queryParameter("sl"))
            assertEquals("pt", req.requestUrl?.queryParameter("tl"))
        }

        // MyMemory
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"responseData":{"translatedText":"Onde estamos?"},"responseStatus":200}"""),
            )
            server.start()
            val mm = MyMemoryTranslationProvider(endpointUrl = server.url("/get").toString())
            val out = mm.translateBatch(listOf("Where are we?"), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR)
            assertEquals(listOf("Onde estamos?"), out)
        }

        // LibreTranslate + Composite fallback
        MockWebServer().use { primaryServer ->
            MockWebServer().use { secondaryServer ->
                primaryServer.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"down"}"""))
                secondaryServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"translatedText":"Por aqui!"}"""))
                primaryServer.start()
                secondaryServer.start()

                val p1 = LibreTranslateProvider(baseUrl = primaryServer.url("/translate").toString(), apiKey = "k1")
                val p2 = LibreTranslateProvider(baseUrl = secondaryServer.url("/translate").toString(), apiKey = "k2")
                val composite = CompositeTranslationProvider(listOf(p1, p2))

                val res = composite.translateBatch(listOf("This way!"), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR)
                assertEquals(listOf("Por aqui!"), res)
                assertEquals(1, primaryServer.requestCount)
                assertEquals(1, secondaryServer.requestCount)
            }
        }
    }

    @Test
    fun `T2-F4-01 to T2-F4-05 TranslationProvider boundary conditions`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val gtx = GoogleTranslateGtxProvider(endpointUrl = server.url("/translate_a/single").toString())

            // T2-F4-01: Empty list sends 0 requests
            assertEquals(emptyList(), gtx.translateBatch(emptyList(), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR))
            assertEquals(0, server.requestCount)

            // T2-F4-04: Same language short-circuit
            assertEquals(listOf("HELLO"), gtx.translateBatch(listOf("HELLO"), TranslatorLanguage.ENGLISH, TranslatorLanguage.ENGLISH))
            assertEquals(0, server.requestCount)

            // T2-F4-03: HTML error page / 429 throws TranslationException
            server.enqueue(MockResponse().setResponseCode(429).setBody("<html>Rate limit</html>"))
            assertFailsWith<TranslationException> {
                gtx.translateBatch(listOf("TEST"), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR)
            }
        }
    }
}
