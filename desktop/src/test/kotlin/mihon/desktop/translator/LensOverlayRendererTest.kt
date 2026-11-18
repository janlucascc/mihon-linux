package mihon.desktop.translator

import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LensOverlayRendererTest {

    private fun createSyntheticPage(w: Int = 400, h: Int = 600, darkPanel: Boolean = false): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.color = Color(235, 236, 240)
            g.fillRect(0, 0, w, h)
            // Draw checkerboard artwork outside bubble
            g.color = Color(190, 195, 210)
            for (y in 0 until h step 20) {
                for (x in 0 until w step 20) {
                    if ((x / 20 + y / 20) % 2 == 0) {
                        g.fillRect(x, y, 10, 10)
                    }
                }
            }
            // Speech bubble at [0.2..0.8, 0.2..0.4]
            val bx = (w * 0.2).toInt()
            val by = (h * 0.2).toInt()
            val bw = (w * 0.6).toInt()
            val bh = (h * 0.2).toInt()
            g.color = if (darkPanel) Color(28, 30, 36) else Color.WHITE
            g.fillRect(bx, by, bw, bh)
            g.color = if (darkPanel) Color.WHITE else Color.BLACK
            g.font = Font("SansSerif", Font.BOLD, 22)
            g.drawString("HELLO HERO!", bx + 20, by + bh / 2)
        } finally {
            g.dispose()
        }
        return img
    }

    @Test
    fun `renderPageWithOverlay returns exact bit-for-bit copy when result is null or not ready`() {
        val original = createSyntheticPage()
        val origPixels = original.getRGB(0, 0, original.width, original.height, null, 0, original.width)

        val renderedNull = LensOverlayRenderer.renderPageWithOverlay(original, null)
        val nullPixels = renderedNull.getRGB(0, 0, renderedNull.width, renderedNull.height, null, 0, renderedNull.width)
        assertTrue(origPixels.contentEquals(nullPixels), "Null result must match original pixels bit-for-bit")

        val renderedIdle = LensOverlayRenderer.renderPageWithOverlay(
            original,
            PageTranslationResult(
                pageIndex = 0,
                generation = 1L,
                sourceWidth = original.width,
                sourceHeight = original.height,
                blocks = emptyList(),
                status = TranslationStatus.TRANSLATING,
            ),
        )
        val idlePixels = renderedIdle.getRGB(0, 0, renderedIdle.width, renderedIdle.height, null, 0, renderedIdle.width)
        assertTrue(origPixels.contentEquals(idlePixels), "Non-READY result must match original pixels bit-for-bit")
    }

    @Test
    fun `blur and overlay strictly scoped to block bounds without mutating source or outside pixels`() {
        val original = createSyntheticPage(400, 600)
        val origSnapshot = original.getRGB(0, 0, 400, 600, null, 0, 400)

        val blockBounds = NormalizedRect(0.2f, 0.2f, 0.6f, 0.2f)
        val result = PageTranslationResult(
            pageIndex = 0,
            generation = 1L,
            sourceWidth = 400,
            sourceHeight = 600,
            blocks = listOf(
                TranslatedBlock(
                    bounds = blockBounds,
                    originalText = "HELLO HERO!",
                    translatedText = "OLÁ HERÓI!",
                ),
            ),
            status = TranslationStatus.READY,
        )

        val rendered = LensOverlayRenderer.renderPageWithOverlay(original, result)
        val afterOrigSnapshot = original.getRGB(0, 0, 400, 600, null, 0, 400)
        assertTrue(origSnapshot.contentEquals(afterOrigSnapshot), "Source image must never be mutated")

        val pixelRect: Rectangle = blockBounds.toPixelRect(400, 600)
        var insideDiffCount = 0
        for (y in 0 until 600) {
            for (x in 0 until 400) {
                val before = origSnapshot[y * 400 + x]
                val after = rendered.getRGB(x, y)
                if (pixelRect.contains(x, y)) {
                    if (before != after) insideDiffCount++
                } else {
                    assertEquals(before, after, "Pixel outside bounds at ($x, $y) must remain 100% untouched")
                }
            }
        }
        assertTrue(insideDiffCount > 100, "Pixels inside translated bounds must be blurred and overlaid")
    }

    @Test
    fun `dark background bubble renders high-contrast light text`() {
        val darkPage = createSyntheticPage(400, 600, darkPanel = true)
        val blockBounds = NormalizedRect(0.2f, 0.2f, 0.6f, 0.2f)
        val result = PageTranslationResult(
            pageIndex = 0,
            generation = 1L,
            sourceWidth = 400,
            sourceHeight = 600,
            blocks = listOf(
                TranslatedBlock(
                    bounds = blockBounds,
                    originalText = "DARK PANEL TEXT",
                    translatedText = "TEXTO NO PAINEL ESCURO",
                ),
            ),
            status = TranslationStatus.READY,
        )

        val rendered = LensOverlayRenderer.renderPageWithOverlay(darkPage, result)
        val rect = blockBounds.toPixelRect(400, 600)
        var minLum = 1.0
        var maxLum = 0.0
        for (y in rect.y until rect.y + rect.height) {
            for (x in rect.x until rect.x + rect.width) {
                val lum = LensOverlayRenderer.relativeLuminance(rendered.getRGB(x, y))
                if (lum < minLum) minLum = lum
                if (lum > maxLum) maxLum = lum
            }
        }
        val contrast = (maxLum + 0.05) / (minLum + 0.05)
        assertTrue(contrast >= 3.0, "Expected contrast ratio >= 3.0 on dark bubble, got $contrast")
    }

    @Test
    fun `halfSlice clips wide spread blocks to left and right halves accurately`() {
        val wide = BufferedImage(800, 500, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, 800, 500)
            g.dispose()
        }
        val result = PageTranslationResult(
            pageIndex = 0,
            generation = 1L,
            sourceWidth = 800,
            sourceHeight = 500,
            blocks = listOf(
                TranslatedBlock(
                    bounds = NormalizedRect(0.1f, 0.2f, 0.2f, 0.2f), // Left half [0.1..0.3]
                    originalText = "LEFT",
                    translatedText = "ESQUERDA",
                ),
                TranslatedBlock(
                    bounds = NormalizedRect(0.7f, 0.6f, 0.2f, 0.2f), // Right half [0.7..0.9]
                    originalText = "RIGHT",
                    translatedText = "DIREITA",
                ),
            ),
            status = TranslationStatus.READY,
        )

        val leftHalfImg = BufferedImage(400, 500, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, 400, 500)
            g.dispose()
        }
        val renderedLeft = LensOverlayRenderer.renderPageWithOverlay(leftHalfImg, result, halfSlice = false)
        // Left block renormalizes to x=[0.2..0.6], y=[0.2..0.4]; right block is excluded
        var bottomHalfDiffsOnLeft = 0
        for (y in 260 until 490) {
            for (x in 0 until 400) {
                if (renderedLeft.getRGB(x, y) != leftHalfImg.getRGB(x, y)) bottomHalfDiffsOnLeft++
            }
        }
        assertEquals(0, bottomHalfDiffsOnLeft, "Right-half block must not appear on left-half slice")

        val renderedRight = LensOverlayRenderer.renderPageWithOverlay(leftHalfImg, result, halfSlice = true)
        var topHalfDiffsOnRight = 0
        for (y in 0 until 240) {
            for (x in 0 until 400) {
                if (renderedRight.getRGB(x, y) != leftHalfImg.getRGB(x, y)) topHalfDiffsOnRight++
            }
        }
        assertEquals(0, topHalfDiffsOnRight, "Left-half block must not appear on right-half slice")
    }

    @Test
    fun `preserves inline chromatic accent color on highlighted token and uniform chromatic blocks`() {
        val page = BufferedImage(760, 240, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, width, height)
            g.font = Font("SansSerif", Font.BOLD, 24)
            val fm = g.fontMetrics
            var x = 40
            val y1 = 68
            val parts = listOf(
                "VISIT AT " to Color(25, 25, 30),
                "MANHUAPLUS.COM" to Color(215, 28, 28),
                " TEAM" to Color(25, 25, 30),
            )
            for ((txt, col) in parts) {
                g.color = col
                g.drawString(txt, x, y1)
                x += fm.stringWidth(txt)
            }
            g.color = Color(205, 30, 30)
            g.drawString("DANGER AHEAD!", 120, 175)
            g.dispose()
        }

        val bannerBounds = NormalizedRect(0.03f, 0.14f, 0.90f, 0.24f)
        val uniformRedBounds = NormalizedRect(0.10f, 0.58f, 0.55f, 0.28f)
        val result = PageTranslationResult(
            pageIndex = 0,
            generation = 1L,
            sourceWidth = 760,
            sourceHeight = 240,
            blocks = listOf(
                TranslatedBlock(
                    bounds = bannerBounds,
                    originalText = "VISIT AT MANHUAPLUS.COM TEAM",
                    translatedText = "VISITE EM MANHUAPLUS.COM EQUIPE",
                ),
                TranslatedBlock(
                    bounds = uniformRedBounds,
                    originalText = "DANGER AHEAD!",
                    translatedText = "PERIGO À FRENTE!",
                ),
            ),
            status = TranslationStatus.READY,
        )

        val rendered = LensOverlayRenderer.renderPageWithOverlay(page, result)

        val bannerPx = bannerBounds.toPixelRect(760, 240)
        var bannerRedCount = 0
        var bannerDarkCount = 0
        for (y in bannerPx.y until bannerPx.y + bannerPx.height) {
            for (x in bannerPx.x until bannerPx.x + bannerPx.width) {
                val rgb = rendered.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (r - maxOf(g, b) >= 85) bannerRedCount++
                if (r < 60 && g < 60 && b < 60) bannerDarkCount++
            }
        }
        assertTrue(bannerRedCount >= 20, "Expected red accent highlight preserved on MANHUAPLUS.COM, got $bannerRedCount")
        assertTrue(bannerDarkCount >= 20, "Expected dark base ink preserved on surrounding words, got $bannerDarkCount")

        val uniformPx = uniformRedBounds.toPixelRect(760, 240)
        var uniformRedCount = 0
        for (y in uniformPx.y until uniformPx.y + uniformPx.height) {
            for (x in uniformPx.x until uniformPx.x + uniformPx.width) {
                val rgb = rendered.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (r - maxOf(g, b) >= 85) uniformRedCount++
            }
        }
        assertTrue(uniformRedCount >= 30, "Expected uniform red block text to retain red hue, got $uniformRedCount")
    }

    @Test
    fun `preserves surrounding red artwork and curved outline while erasing corner-hugging interior text`() {
        val page = BufferedImage(320, 240, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            // Upper-left red sleeve artwork + brown background outside bubble
            g.color = Color(98, 68, 65)
            g.fillRect(0, 0, width, height)
            g.color = Color(205, 34, 30)
            g.fillRect(0, 0, 150, 110)

            // Speech bubble oval with white interior and thin black border
            g.color = Color.WHITE
            g.fillOval(26, 20, 268, 200)
            g.color = Color.BLACK
            g.stroke = java.awt.BasicStroke(3f)
            g.drawOval(26, 20, 268, 200)

            // Original English text spanning close to the lower-right inner oval boundary
            g.font = Font("SansSerif", Font.BOLD, 16)
            g.drawString("FINALLY MADE", 95, 75)
            g.drawString("TO BECOME ONE", 88, 120)
            g.drawString("WITH DUZE", 130, 185)
            g.dispose()
        }

        val bounds = NormalizedRect(0.05f, 0.05f, 0.90f, 0.88f)
        val result = PageTranslationResult(
            pageIndex = 0,
            generation = 1L,
            sourceWidth = 320,
            sourceHeight = 240,
            blocks = listOf(
                TranslatedBlock(
                    bounds = bounds,
                    originalText = "FINALLY MADE TO BECOME ONE WITH DUZE",
                    translatedText = "FINALMENTE FEITO PARA SE TORNAR UM COM DUZE",
                ),
            ),
            status = TranslationStatus.READY,
        )

        val rendered = LensOverlayRenderer.renderPageWithOverlay(page, result)

        // Upper-left red sleeve pixels outside the oval must remain untouched (no square step leak!)
        for (y in 18..40) {
            for (x in 18..36) {
                val origRgb = page.getRGB(x, y)
                val outRgb = rendered.getRGB(x, y)
                if (((origRgb shr 16) and 0xFF) > 180 && ((origRgb shr 8) and 0xFF) < 60) {
                    assertEquals(origRgb, outRgb, "Red sleeve outside bubble at ($x, $y) must remain intact")
                }
            }
        }

        // Bottom-right corner of the bubble interior around 'DUZE' (x=175..225, y=180..192) must not retain unmasked background text streaks
        val darkRemnantsAtLowerEdge = (180..188).sumOf { y ->
            (130..215).count { x ->
                val rgb = rendered.getRGB(x, y)
                val lum = 0.299 * ((rgb shr 16) and 0xFF) + 0.587 * ((rgb shr 8) and 0xFF) + 0.114 * (rgb and 0xFF)
                lum < 90.0
            }
        }
        // Only rendered Portuguese text (not full unmasked original English block outside mask) should remain
        assertTrue(darkRemnantsAtLowerEdge < 140, "Expected bottom-right original 'WITH DUZE' to be masked cleanly inside oval")
    }
}
