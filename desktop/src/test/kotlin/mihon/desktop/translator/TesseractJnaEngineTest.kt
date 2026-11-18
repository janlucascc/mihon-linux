package mihon.desktop.translator

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TesseractJnaEngineTest {

    private fun createTextImage(
        width: Int = 640,
        height: Int = 400,
        bg: Color = Color.WHITE,
        block: (java.awt.Graphics2D) -> Unit,
    ): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = bg
        g.fillRect(0, 0, width, height)
        g.color = Color.BLACK
        block(g)
        g.dispose()
        return img
    }

    @Test
    fun `real JNA libtesseract 5 is available on host`() {
        TesseractJnaEngine().use { engine ->
            assertTrue(engine.isAvailable(), "Expected real JNA libtesseract.so.5.5 to be available")
        }
    }

    @Test
    fun `recognizes multiple English text ROIs via real JNA and eng traineddata`() {
        val img = createTextImage(640, 400) { g ->
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 30)
            g.drawString("HELLO MANGA", 40, 80)
            g.drawString("SECOND BUBBLE", 40, 260)
        }

        val regions = listOf(
            DetectedTextRegion(NormalizedRect(30f / 640f, 35f / 400f, 320f / 640f, 70f / 400f)),
            DetectedTextRegion(NormalizedRect(30f / 640f, 215f / 400f, 360f / 640f, 70f / 400f)),
        )

        TesseractJnaEngine().use { engine ->
            val snippets = engine.recognizePage(img, regions, TranslatorLanguage.ENGLISH)
            assertEquals(2, snippets.size)
            assertTrue(snippets[0].text.uppercase().contains("HELLO"), "Got: ${snippets[0].text}")
            assertTrue(snippets[1].text.uppercase().contains("SECOND"), "Got: ${snippets[1].text}")
            assertEquals(regions[0].bounds, snippets[0].bounds)
            assertTrue(snippets[0].confidence > 0.4f)
        }
    }

    @Test
    fun `upscales small font ROI and recognizes dark panel light text`() {
        val img = createTextImage(600, 300) { g ->
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
            g.drawString("SMALL TEXT", 30, 50)

            g.color = Color(24, 24, 24)
            g.fillRect(30, 140, 420, 110)
            g.color = Color(245, 245, 245)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 28)
            g.drawString("DARK PANEL", 60, 205)
        }

        val regions = listOf(
            DetectedTextRegion(NormalizedRect(24f / 600f, 28f / 300f, 150f / 600f, 32f / 300f)),
            DetectedTextRegion(NormalizedRect(35f / 600f, 145f / 300f, 400f / 600f, 100f / 300f)),
        )

        TesseractJnaEngine().use { engine ->
            val snippets = engine.recognizePage(img, regions, TranslatorLanguage.ENGLISH)
            assertEquals(2, snippets.size)
            assertTrue(snippets[0].text.uppercase().contains("SMALL"), "Got: ${snippets[0].text}")
            assertTrue(snippets[1].text.uppercase().contains("DARK"), "Got: ${snippets[1].text}")
        }
    }

    @Test
    fun `full page iterator fallback detects text blocks when regions list is empty`() {
        val img = createTextImage(800, 1000, Color(240, 240, 240)) { g ->
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 32)
            g.drawString("FIRST BLOCK LINE", 80, 140)
            // Solid dark artwork rectangle in the middle (should not emit text)
            g.fillRect(150, 320, 500, 260)
            g.drawString("BOTTOM TEXT BLOCK", 80, 760)
        }

        TesseractJnaEngine().use { engine ->
            val snippets = engine.recognizePage(img, emptyList(), TranslatorLanguage.ENGLISH)
            assertTrue(snippets.isNotEmpty(), "Expected iterator fallback to extract text blocks")
            val combined = snippets.joinToString(" ") { it.text.uppercase() }
            assertTrue(combined.contains("FIRST"), "Expected FIRST in $combined")
            assertTrue(combined.contains("BOTTOM"), "Expected BOTTOM in $combined")
            assertTrue(snippets.all { it.bounds.x >= 0f && it.bounds.y >= 0f && it.bounds.right <= 1f && it.bounds.bottom <= 1f })
        }
    }

    @Test
    fun `blank white image and degenerate ROI return empty list safely`() {
        val blank = createTextImage(400, 300) { }
        TesseractJnaEngine().use { engine ->
            assertTrue(engine.recognizePage(blank, emptyList(), TranslatorLanguage.ENGLISH).isEmpty())
            assertTrue(
                engine.recognizePage(
                    blank,
                    listOf(DetectedTextRegion(NormalizedRect(0f, 0f, 0f, 0f))),
                    TranslatorLanguage.ENGLISH,
                ).isEmpty(),
            )
        }
    }

    @Test
    fun `thread-safe concurrent recognition, releaseNativeHandle reinit, and close safety`() {
        val img = createTextImage(500, 200) { g ->
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 30)
            g.drawString("CONCURRENT OK", 40, 110)
        }
        val regions = listOf(DetectedTextRegion(NormalizedRect(0.05f, 0.2f, 0.85f, 0.6f)))

        val engine = TesseractJnaEngine()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map {
                pool.submit<List<OcrSnippet>> {
                    engine.recognizePage(img, regions, TranslatorLanguage.ENGLISH)
                }
            }
            for (future in futures) {
                val res = future.get(15, TimeUnit.SECONDS)
                assertEquals(1, res.size)
                assertTrue(res[0].text.uppercase().contains("CONCURRENT"))
            }

            // Releasing native handle frees C++ memory and allows seamless re-initialization
            engine.releaseNativeHandle()
            val afterRelease = engine.recognizePage(img, regions, TranslatorLanguage.ENGLISH)
            assertEquals(1, afterRelease.size)

            engine.close()
            assertFalse(engine.isAvailable())
            assertTrue(engine.recognizePage(img, regions, TranslatorLanguage.ENGLISH).isEmpty())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `CLI fallback parses TSV output when native library cannot be loaded`(@TempDir tempDir: Path) {
        val userDir = tempDir.resolve("tess").toFile().apply { mkdirs() }
        File(userDir, "eng.traineddata").writeBytes(ByteArray(12_000) { 1 })
        val manager = TessdataManager(userTessdataDir = userDir, searchDirs = listOf(userDir))

        val tsvOutput = buildString {
            appendLine("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext")
            appendLine("5\t1\t1\t1\t1\t1\t20\t20\t60\t20\t94.5\tHELLO")
            appendLine("5\t1\t1\t1\t1\t2\t90\t20\t70\t20\t92.0\tCLI")
        }

        val engine = TesseractJnaEngine(
            tessdataManager = manager,
            autoDownloadProvider = { false },
            libraryCandidates = listOf("/nonexistent/libtesseract.so.999"),
            cliAvailabilityCheck = { true },
            cliCommandRunner = { TesseractJnaEngine.CliResult(0, tsvOutput) },
        )

        val img = createTextImage(300, 120) { g ->
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 24)
            g.drawString("HELLO CLI", 20, 60)
        }
        val snippets = engine.recognizePage(
            img,
            listOf(DetectedTextRegion(NormalizedRect(0.05f, 0.1f, 0.9f, 0.8f))),
            TranslatorLanguage.ENGLISH,
        )
        assertEquals(1, snippets.size)
        assertEquals("HELLO CLI", snippets[0].text)
        assertTrue(snippets[0].confidence > 0.9f)
        engine.close()
    }

    @Test
    fun `detector and JNA OCR recognize synthetic manga chapter and special pages`() {
        val detector = TextRegionDetector()
        TesseractJnaEngine().use { engine ->
            // 1. CBZ English chapter pages
            val cbzStream = assertNotNull(
                javaClass.getResourceAsStream("/e2e/manga_en_chapter.cbz"),
                "Missing test resource /e2e/manga_en_chapter.cbz",
            )
            java.util.zip.ZipInputStream(cbzStream).use { zis ->
                val pageTexts = mutableListOf<String>()
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (!entry.name.endsWith(".png", ignoreCase = true)) continue
                    val img = javax.imageio.ImageIO.read(zis)
                    val regions = detector.detectRegions(img)
                    assertTrue(regions.size >= 2, "Expected >=2 regions in ${entry.name}, got ${regions.size}")
                    val snippets = engine.recognizePage(img, regions, TranslatorLanguage.ENGLISH)
                    assertTrue(snippets.isNotEmpty(), "Expected OCR snippets in ${entry.name}")
                    pageTexts.add(snippets.joinToString(" ") { it.text.uppercase() })
                }
                assertTrue(pageTexts.any { it.contains("HELLO") }, "Expected HELLO in $pageTexts")
                assertTrue(pageTexts.any { it.contains("WATCH") }, "Expected WATCH in $pageTexts")
                assertTrue(pageTexts.any { it.contains("STAY") }, "Expected STAY in $pageTexts")
                assertTrue(pageTexts.any { it.contains("END") }, "Expected END in $pageTexts")
            }

            // 2. Wide double-page spread (must return strictly 4 regions and 4 snippets, zero silhouette false positives)
            val wideStream = assertNotNull(
                javaClass.getResourceAsStream("/e2e/manga_wide_double.png"),
                "Missing test resource /e2e/manga_wide_double.png",
            )
            wideStream.use { stream ->
                val img = javax.imageio.ImageIO.read(stream)
                val regions = detector.detectRegions(img)
                assertEquals(4, regions.size, "Expected strictly 4 regions in wide double, got $regions")
                val snippets = engine.recognizePage(img, regions, TranslatorLanguage.ENGLISH)
                assertEquals(4, snippets.size, "Expected strictly 4 OCR snippets in wide double, got $snippets")
                val allText = snippets.joinToString(" ") { it.text.uppercase() }
                assertTrue(allText.contains("LEFT"), "Expected LEFT in $allText")
                assertTrue(allText.contains("HOLD ON"), "Expected HOLD ON in $allText")
                assertTrue(allText.contains("RIGHT"), "Expected RIGHT in $allText")
                assertTrue(allText.contains("ATTACK"), "Expected ATTACK in $allText")
            }

            // 3. Blank + dark narration box
            val darkStream = assertNotNull(
                javaClass.getResourceAsStream("/e2e/manga_blank_and_dark.png"),
                "Missing test resource /e2e/manga_blank_and_dark.png",
            )
            darkStream.use { stream ->
                val img = javax.imageio.ImageIO.read(stream)
                val regions = detector.detectRegions(img)
                assertTrue(regions.isNotEmpty(), "Expected dark panel text region in manga_blank_and_dark.png")
                val snippets = engine.recognizePage(img, regions, TranslatorLanguage.ENGLISH)
                val allText = snippets.joinToString(" ") { it.text.uppercase() }
                assertTrue(allText.contains("DARK"), "Expected DARK in $allText (regions=$regions, snippets=$snippets)")
            }

            // 4. Webtoon strip
            val webtoonStream = assertNotNull(
                javaClass.getResourceAsStream("/e2e/manga_webtoon_strip.png"),
                "Missing test resource /e2e/manga_webtoon_strip.png",
            )
            webtoonStream.use { stream ->
                val img = javax.imageio.ImageIO.read(stream)
                val regions = detector.detectRegions(img)
                assertTrue(regions.size >= 3, "Expected >=3 regions in webtoon strip, got ${regions.size}")
                val snippets = engine.recognizePage(img, regions, TranslatorLanguage.ENGLISH)
                val allText = snippets.joinToString(" ") { it.text.uppercase() }
                assertTrue(allText.contains("TOP") && allText.contains("BOTTOM"), "Expected TOP and BOTTOM in $allText")
            }
        }
    }

    @Test
    fun `diagonal screentones produce zero regions and zero OCR snippets in both detector and fallback modes`() {
        val detector = TextRegionDetector()
        val cases = listOf(
            Triple("mod5", Color(60, 60, 60)) { x: Int, y: Int -> (x + y) % 5 == 0 },
            Triple("mod4", Color(55, 55, 55)) { x: Int, y: Int -> (x + y) % 4 == 0 },
            Triple("2px_mod6", Color(60, 60, 60)) { x: Int, y: Int -> (x + y) % 6 < 2 },
        )
        TesseractJnaEngine().use { engine ->
            for ((label, color, predicate) in cases) {
                val img = createTextImage(800, 1200) { }
                for (y in 0 until 1200) {
                    for (x in 0 until 800) {
                        if (predicate(x, y)) {
                            img.setRGB(x, y, color.rgb)
                        }
                    }
                }
                val regions = detector.detectRegions(img)
                assertEquals(0, regions.size, "Expected 0 detector regions for $label, got $regions")
                val fallbackSnippets = engine.recognizePage(img, emptyList(), TranslatorLanguage.ENGLISH)
                assertEquals(0, fallbackSnippets.size, "Expected 0 full-page OCR snippets for $label, got $fallbackSnippets")
            }
        }
    }

    @Test
    fun `filters low-confidence short-char noise while preserving real short words`(@TempDir tempDir: Path) {
        val userDir = tempDir.resolve("tess").toFile().apply { mkdirs() }
        File(userDir, "eng.traineddata").writeBytes(ByteArray(12_000) { 1 })
        val manager = TessdataManager(userTessdataDir = userDir, searchDirs = listOf(userDir))

        val noisyTsv = buildString {
            appendLine("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext")
            appendLine("5\t1\t1\t1\t1\t1\t10\t10\t20\t20\t34.0\tp")
        }
        val cliNoisyEngine = TesseractJnaEngine(
            tessdataManager = manager,
            autoDownloadProvider = { false },
            libraryCandidates = listOf("/nonexistent/libtesseract.so.999"),
            cliAvailabilityCheck = { true },
            cliCommandRunner = { TesseractJnaEngine.CliResult(0, noisyTsv) },
        )

        val dummyImg = createTextImage(300, 120) { }
        val filtered = cliNoisyEngine.recognizePage(
            dummyImg,
            listOf(DetectedTextRegion(NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f))),
            TranslatorLanguage.ENGLISH,
        )
        assertEquals(0, filtered.size, "Expected low-confidence single-char noise to be filtered: $filtered")
        cliNoisyEngine.close()

        // Real short words via JNA must be preserved
        val shortImg = createTextImage(400, 220) { g ->
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 28)
            g.drawString("OK", 50, 80)
            g.drawString("NO", 50, 170)
        }
        val shortRegions = listOf(
            DetectedTextRegion(NormalizedRect(30f / 400f, 35f / 220f, 120f / 400f, 65f / 220f)),
            DetectedTextRegion(NormalizedRect(30f / 400f, 125f / 220f, 120f / 400f, 65f / 220f)),
        )
        TesseractJnaEngine().use { engine ->
            val snippets = engine.recognizePage(shortImg, shortRegions, TranslatorLanguage.ENGLISH)
            assertEquals(2, snippets.size, "Expected both real short words to be preserved: $snippets")
            assertTrue(snippets[0].text.uppercase().contains("OK"))
            assertTrue(snippets[1].text.uppercase().contains("NO"))
        }

        // Low-confidence 2-letter ALLOWED_SHORT_WORDS hallucination ("IF" -> "se") and multi-token junk ("Wy 4)") must be rejected
        val hallucinationTsv = buildString {
            appendLine("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext")
            appendLine("5\t1\t1\t1\t1\t1\t20\t20\t35\t28\t71.0\tIF")
        }
        val wyJunkTsv = buildString {
            appendLine("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext")
            appendLine("5\t1\t1\t1\t1\t1\t20\t20\t35\t26\t72.0\tWy")
            appendLine("5\t1\t1\t1\t1\t2\t60\t20\t28\t26\t70.0\t4)")
        }
        for (tsvPayload in listOf(hallucinationTsv, wyJunkTsv)) {
            TesseractJnaEngine(
                tessdataManager = manager,
                autoDownloadProvider = { false },
                libraryCandidates = listOf("/nonexistent/libtesseract.so.999"),
                cliAvailabilityCheck = { true },
                cliCommandRunner = { TesseractJnaEngine.CliResult(0, tsvPayload) },
            ).use { cliHallucinationEngine ->
                val rejected = cliHallucinationEngine.recognizePage(
                    dummyImg,
                    listOf(DetectedTextRegion(NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f))),
                    TranslatorLanguage.ENGLISH,
                )
                assertEquals(
                    0,
                    rejected.size,
                    "Expected artwork hallucination TSV to be rejected, got: $rejected",
                )
            }
        }
    }
}
