package mihon.desktop.translator

import org.junit.jupiter.api.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextRegionDetectorTest {

    private val detector = TextRegionDetector()

    private fun newPage(w: Int = 800, h: Int = 1200, bg: Color = Color.WHITE, type: Int = BufferedImage.TYPE_INT_RGB): BufferedImage {
        val img = BufferedImage(w, h, type)
        val g = img.createGraphics()
        g.color = bg
        g.fillRect(0, 0, w, h)
        g.dispose()
        return img
    }

    private fun drawSpeechBubble(
        img: BufferedImage,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        lines: List<String>,
        fontSize: Int = 26,
        tailTargetY: Int? = null,
    ) {
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        if (tailTargetY != null) {
            val cx = x + w / 2
            val xs = intArrayOf(cx - 12, cx + 12, cx + 4)
            val ys = intArrayOf(y + h - 8, y + h - 8, tailTargetY)
            g.color = Color.WHITE
            g.fillPolygon(xs, ys, 3)
            g.color = Color.BLACK
            g.stroke = BasicStroke(3f)
            g.drawPolyline(intArrayOf(xs[0], xs[2], xs[1]), intArrayOf(ys[0], ys[2], ys[1]), 3)
        }

        g.color = Color.WHITE
        g.fillOval(x, y, w, h)
        g.color = Color.BLACK
        g.stroke = BasicStroke(4f)
        g.drawOval(x, y, w, h)

        g.font = Font(Font.SANS_SERIF, Font.BOLD, fontSize)
        val fm = g.fontMetrics
        val totalTextH = lines.size * fm.height
        var curY = y + (h - totalTextH) / 2 + fm.ascent
        for (line in lines) {
            val lw = fm.stringWidth(line)
            g.drawString(line, x + (w - lw) / 2, curY)
            curY += fm.height
        }
        g.dispose()
    }

    @Test
    fun `blank white and solid dark pages return empty list`() {
        assertTrue(detector.detectRegions(newPage(800, 1200, Color.WHITE)).isEmpty())
        assertTrue(detector.detectRegions(newPage(800, 1200, Color(16, 16, 16))).isEmpty())
    }

    @Test
    fun `empty panel borders and empty oval bubbles produce no detections`() {
        val img = newPage(800, 1200, Color.WHITE)
        val g = img.createGraphics()
        g.color = Color.BLACK
        g.stroke = BasicStroke(5f)
        g.drawRect(30, 30, 740, 540)
        g.drawRect(30, 610, 740, 560)
        g.color = Color(210, 210, 210)
        g.fillRect(40, 40, 720, 520)
        // Empty white oval bubble inside gray panel
        g.color = Color.WHITE
        g.fillOval(140, 140, 240, 160)
        g.color = Color.BLACK
        g.drawOval(140, 140, 240, 160)
        g.dispose()

        assertTrue(detector.detectRegions(img).isEmpty())
    }

    @Test
    fun `detects closed speech bubble with horizontal text`() {
        val img = newPage(800, 1200, Color(220, 220, 220))
        drawSpeechBubble(img, 120, 140, 320, 160, listOf("HELLO WORLD", "HOW ARE YOU?"))

        val regions = detector.detectRegions(img)
        assertEquals(1, regions.size)
        val region = regions.first()
        assertEquals(TextOrientation.HORIZONTAL, region.orientation)
        assertTrue(region.confidence > 0.5f)
        val px = region.bounds.toPixelRect(800, 1200)
        assertTrue(px.x <= 180 && px.x + px.width >= 380, "Expected rect to cover text horizontally: $px")
        assertTrue(px.y <= 200 && px.y + px.height >= 240, "Expected rect to cover text vertically: $px")
    }

    @Test
    fun `detects tall bubble with vertical columns as VERTICAL`() {
        val img = newPage(800, 1200, Color(200, 200, 200))
        val bx = 520
        val by = 100
        val bw = 130
        val bh = 380
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillOval(bx, by, bw, bh)
        g.color = Color.BLACK
        g.stroke = BasicStroke(4f)
        g.drawOval(bx, by, bw, bh)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 24)
        // Draw 2 vertical columns of characters
        val col1 = listOf("A", "B", "C", "D", "E", "F", "G")
        val col2 = listOf("H", "I", "J", "K", "L", "M", "N")
        for (i in col1.indices) {
            g.drawString(col1[i], bx + 78, by + 70 + i * 38)
            g.drawString(col2[i], bx + 36, by + 70 + i * 38)
        }
        g.dispose()

        val regions = detector.detectRegions(img)
        assertEquals(1, regions.size)
        assertEquals(TextOrientation.VERTICAL, regions.first().orientation)
    }

    @Test
    fun `speech bubble with long thin tail excludes tail tip from detected rect`() {
        val img = newPage(800, 1200, Color(210, 210, 210))
        drawSpeechBubble(img, 180, 150, 300, 160, listOf("WAIT HERE!"), tailTargetY = 520)

        val regions = detector.detectRegions(img)
        assertEquals(1, regions.size)
        val px = regions.first().bounds.toPixelRect(800, 1200)
        assertTrue(px.y + px.height < 360, "Detected box should trim long tail: $px")
    }

    @Test
    fun `detects floating dark text without closed bubble border`() {
        val img = newPage(800, 1200, Color.WHITE)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.BLACK
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 28)
        g.drawString("FLOATING TEXT", 180, 320)
        g.drawString("NO BUBBLE HERE", 175, 360)
        g.dispose()

        val regions = detector.detectRegions(img)
        assertTrue(regions.isNotEmpty(), "Expected floating text cluster to be detected")
        val px = regions.first().bounds.toPixelRect(800, 1200)
        assertTrue(px.x <= 190 && px.x + px.width >= 400, "Expected detected region to cover floating text: $px")
    }

    @Test
    fun `detects light text on dark narration panel`() {
        val img = newPage(800, 1200, Color.WHITE)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(24, 24, 24)
        g.fillRect(120, 740, 560, 200)
        g.color = Color(245, 245, 245)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 30)
        g.drawString("DARK PANEL TEXT", 210, 850)
        g.dispose()

        val regions = detector.detectRegions(img)
        assertTrue(regions.isNotEmpty(), "Expected light text on dark panel to be detected")
        assertTrue(regions.any { r ->
            val px = r.bounds.toPixelRect(800, 1200)
            px.y in 740..900 && px.y + px.height >= 840
        }, "Expected dark panel text region, got $regions")
    }

    @Test
    fun `halftone checkerboard pattern produces no false positive explosion`() {
        val img = newPage(800, 1200, Color.WHITE)
        for (y in 0 until 1200 step 3) {
            for (x in 0 until 800 step 3) {
                img.setRGB(x, y, Color(60, 60, 60).rgb)
            }
        }
        val regions = detector.detectRegions(img)
        assertTrue(regions.isEmpty(), "Expected halftone pattern to produce 0 regions, got $regions")
    }

    @Test
    fun `orders bubbles in manga right-to-left top-to-bottom reading order`() {
        val img = newPage(800, 1200, Color(210, 210, 210))
        // Top-left bubble
        drawSpeechBubble(img, 60, 100, 250, 140, listOf("TOP LEFT"))
        // Top-right bubble (same vertical band -> should come FIRST)
        drawSpeechBubble(img, 480, 110, 250, 140, listOf("TOP RIGHT"))
        // Bottom-center bubble (should come LAST)
        drawSpeechBubble(img, 260, 760, 280, 150, listOf("BOTTOM"))

        val regions = detector.detectRegions(img)
        assertEquals(3, regions.size)
        assertTrue(regions[0].bounds.centerX > regions[1].bounds.centerX, "First region should be top-right")
        assertTrue(regions[2].bounds.centerY > regions[0].bounds.centerY, "Third region should be bottom")
    }

    @Test
    fun `handles tall webtoon strip and transparent ARGB image cleanly`() {
        val webtoon = newPage(800, 3600, Color(220, 220, 220))
        drawSpeechBubble(webtoon, 160, 120, 480, 180, listOf("TOP BUBBLE"))
        drawSpeechBubble(webtoon, 160, 3100, 480, 180, listOf("BOTTOM BUBBLE"))

        val webtoonRegions = detector.detectRegions(webtoon)
        assertEquals(2, webtoonRegions.size)
        assertTrue(webtoonRegions[0].bounds.centerY < 0.2f)
        assertTrue(webtoonRegions[1].bounds.centerY > 0.8f)

        // Transparent ARGB page with no text must not turn into dark false positives
        val transparent = BufferedImage(400, 600, BufferedImage.TYPE_INT_ARGB)
        assertTrue(detector.detectRegions(transparent).isEmpty())
    }

    @Test
    fun `diagonal screentone patterns produce zero false positive regions`() {
        val patterns = listOf(
            "1px mod 5" to { x: Int, y: Int -> (x + y) % 5 == 0 },
            "1px mod 4" to { x: Int, y: Int -> (x + y) % 4 == 0 },
            "2px mod 6" to { x: Int, y: Int -> (x + y) % 6 < 2 },
        )
        for ((label, predicate) in patterns) {
            val img = newPage(800, 1200, Color.WHITE)
            for (y in 0 until 1200) {
                for (x in 0 until 800) {
                    if (predicate(x, y)) {
                        img.setRGB(x, y, Color(60, 60, 60).rgb)
                    }
                }
            }
            val regions = detector.detectRegions(img)
            assertTrue(regions.isEmpty(), "Expected 0 regions for diagonal screentone ($label), got $regions")
        }
    }

    @Test
    fun `solid dark character silhouette on halftone produces zero regions`() {
        val img = newPage(800, 1200, Color(210, 210, 210))
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(45, 45, 45)
        // Head circle + shoulder semi-ellipse mimicking manga character silhouette
        g.fillOval(330, 520, 140, 140)
        g.fillArc(250, 650, 300, 220, 0, 180)
        g.dispose()

        val regions = detector.detectRegions(img)
        assertTrue(regions.isEmpty(), "Expected 0 regions for solid silhouette, got $regions")
    }

    @Test
    fun `detects single-line floating text on tall webtoon strip and short 2-char floating text`() {
        val webtoon = newPage(800, 3600, Color.WHITE)
        val gw = webtoon.createGraphics()
        gw.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        gw.color = Color.BLACK
        gw.font = Font(Font.SANS_SERIF, Font.BOLD, 24)
        gw.drawString("SFX BOOM!", 280, 1800)
        gw.dispose()

        val webtoonRegions = detector.detectRegions(webtoon)
        assertTrue(webtoonRegions.isNotEmpty(), "Expected single-line 24pt text on 800x3600 webtoon to be detected")
        val wPx = webtoonRegions.first().bounds.toPixelRect(800, 3600)
        assertTrue(wPx.y <= 1800 && wPx.y + wPx.height >= 1785, "Expected region to cover webtoon caption: $wPx")

        val shortPage = newPage(800, 1200, Color.WHITE)
        val gs = shortPage.createGraphics()
        gs.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        gs.color = Color.BLACK
        gs.font = Font(Font.SANS_SERIF, Font.BOLD, 26)
        gs.drawString("OK", 340, 500)
        gs.dispose()

        val shortRegions = detector.detectRegions(shortPage)
        assertEquals(1, shortRegions.size, "Expected 1 region for 2-char floating 'OK', got $shortRegions")
    }

    @Test
    fun `does not emit oversized cluster swallowing two stacked speech bubbles or detect warm face art`() {
        // Panel with dark monster artwork bridging vertically between two distinct speech bubbles
        val stackedPanel = newPage(800, 1100, Color(42, 42, 48))
        val g = stackedPanel.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        // Bright curved fang & yellow brow shapes between the two bubbles
        g.color = Color(235, 185, 65)
        g.fillRoundRect(390, 270, 160, 85, 25, 25)
        g.color = Color(240, 240, 240)
        g.fillArc(370, 355, 80, 95, 180, 150)
        g.color = Color.BLACK
        g.stroke = BasicStroke(3f)
        g.drawArc(370, 355, 80, 95, 180, 150)
        g.dispose()

        drawSpeechBubble(stackedPanel, 280, 180, 250, 115, listOf("I WANT TO SEE", "HOW YOU TAKE IT"), fontSize = 20)
        drawSpeechBubble(stackedPanel, 270, 480, 270, 115, listOf("YOU ARE THE ONE", "WHO MUST STAY!"), fontSize = 20)

        val regions = detector.detectRegions(stackedPanel)
        val giantMerges = regions.filter { it.bounds.height > 0.24f }
        assertTrue(giantMerges.isEmpty(), "Should not merge two bubbles into an oversized region: $regions")
        assertTrue(regions.size >= 2, "Expected both speech bubbles to remain distinct, got $regions")
        assertTrue(
            regions.any { it.bounds.y in 0.14f..0.24f && it.bounds.height < 0.16f },
            "Expected top speech bubble intact: $regions",
        )
        assertTrue(
            regions.any { it.bounds.y in 0.40f..0.52f && it.bounds.height < 0.16f },
            "Expected bottom speech bubble intact: $regions",
        )

        // Warm pale face artwork with 2 eyes and mouth must not become a false closed bubble
        val facePage = newPage(800, 1100, Color(115, 108, 120))
        val gf = facePage.createGraphics()
        gf.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        gf.color = Color(250, 224, 206)
        gf.fillOval(340, 300, 95, 115)
        gf.color = Color.BLACK
        gf.stroke = BasicStroke(3f)
        gf.drawOval(340, 300, 95, 115)
        gf.fillOval(362, 342, 11, 10)
        gf.fillOval(401, 342, 11, 10)
        gf.fillOval(382, 378, 12, 7)
        gf.dispose()

        val faceRegions = detector.detectRegions(facePage)
        assertTrue(faceRegions.isEmpty(), "Expected 0 false regions on pale character face artwork, got $faceRegions")
    }
}
