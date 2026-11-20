package mihon.desktop

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color

class VisualIdentityTest {

    @AfterEach
    fun restoreDefaultTheme() {
        Theme.applyPreset(Theme.ThemePreset.DARK)
    }

    @Test
    fun `brand constants and geometry follow Mihon Index visual identity spec`() {
        assertEquals(10, COVER_ARC)
        assertEquals(10, Theme.CARD_RADIUS)
        assertEquals(8, Theme.CONTROL_RADIUS)
        assertEquals(7, Theme.BADGE_RADIUS)
        assertEquals(38, Theme.CONTROL_HEIGHT_COMPACT)
        assertEquals(48, Theme.CONTROL_HEIGHT_PRIMARY)
        assertEquals(16, Theme.PAGE_GUTTER)
        assertEquals(Color(0x4F, 0x46, 0xE5), Theme.PRIMARY)
        assertEquals(Color(0x43, 0x38, 0xCA), Theme.PRIMARY_HOVER)
        assertEquals(Color(0x37, 0x30, 0xA3), Theme.PRIMARY_PRESS)
        assertEquals(Color(0xF9, 0x73, 0x16), Theme.SIGNAL)
        assertEquals(Color(0xEA, 0x58, 0x0C), Theme.SIGNAL_HOVER)
    }

    @Test
    fun `light dark and amoled presets expose cohesive neutral and semantic tokens`() {
        Theme.applyPreset(Theme.ThemePreset.LIGHT)
        assertFalse(Theme.isDark)
        assertEquals(Color(0xF7, 0xF7, 0xFA), Theme.BG_APP)
        assertEquals(Color(0xEE, 0xEF, 0xF5), Theme.BG_SIDEBAR)
        assertEquals(Color(0xFF, 0xFF, 0xFF), Theme.BG_SURFACE)
        assertEquals(Color(0xE8, 0xE9, 0xF5), Theme.BG_CARD_HOVER)
        assertEquals(Color(0x16, 0x17, 0x22), Theme.TEXT_PRIMARY)
        assertEquals(Color(0x56, 0x58, 0x6B), Theme.TEXT_SECONDARY)
        assertEquals(Color(0x4F, 0x46, 0xE5), Theme.ACCENT)
        assertEquals(Color(0x2F, 0xB3, 0x44), Theme.SUCCESS)
        assertEquals(Color(0xE5, 0xA5, 0x0A), Theme.WARNING)
        assertEquals(Color(0xE0, 0x1B, 0x24), Theme.DANGER)

        Theme.applyPreset(Theme.ThemePreset.DARK)
        assertTrue(Theme.isDark)
        assertEquals(Color(0x22, 0x22, 0x26), Theme.BG_APP)
        assertEquals(Color(0x2E, 0x2E, 0x32), Theme.BG_SIDEBAR)
        assertEquals(Color(0x33, 0x33, 0x37), Theme.BG_SURFACE)
        assertEquals(Color(0x38, 0x38, 0x3B), Theme.BG_CARD)
        assertEquals(Color(0x44, 0x44, 0x49), Theme.BG_CARD_HOVER)
        assertEquals(Color(0xF6, 0xF5, 0xF4), Theme.TEXT_PRIMARY)
        assertEquals(Color(0xB8, 0xB7, 0xBC), Theme.TEXT_SECONDARY)
        assertEquals(Color(0x81, 0x8C, 0xF8), Theme.ACCENT)
        assertEquals(Color(0x78, 0xB8, 0x86), Theme.SUCCESS)
        assertEquals(Color(0xF4, 0xC8, 0x75), Theme.WARNING)
        assertEquals(Color(0xFF, 0x8B, 0x8B), Theme.DANGER)

        Theme.applyPreset(Theme.ThemePreset.AMOLED)
        assertTrue(Theme.isDark)
        assertEquals(Color(0x00, 0x00, 0x00), Theme.BG_APP)
        assertEquals(Color(0x07, 0x08, 0x0B), Theme.BG_SIDEBAR)
        assertEquals(Color(0x0E, 0x0F, 0x14), Theme.BG_SURFACE)
        assertEquals(Color(0x14, 0x15, 0x1B), Theme.BG_CARD)
    }

    @Test
    fun `canonical svg resource is pure vector markup with Mihon Index palette`() {
        val stream = AppIcon::class.java.getResourceAsStream("/icons/mihon-linux.svg")
        assertNotNull(stream, "Bundled /icons/mihon-linux.svg must exist on classpath")
        val svgText = stream!!.bufferedReader().readText()
        assertEquals(AppIcon.CANONICAL_SVG.trim(), svgText.trim())
        assertTrue(svgText.contains("#4F46E5"))
        assertTrue(svgText.contains("#F97316"))
        assertFalse(svgText.contains("data:image/"), "SVG must stay purely vector without embedded rasters")
    }

    @Test
    fun `procedural app icon renders cobalt squircle book pages and signal checkmark badge`() {
        val img = AppIcon.createImage(128)
        assertEquals(128, img.width)
        assertEquals(128, img.height)

        // Outer corner outside rx=26 squircle must be transparent
        assertEquals(0, (img.getRGB(1, 1) ushr 24) and 0xFF)

        // Top-left inside squircle must be primary cobalt #4F46E5
        assertEquals(Color(0x4F, 0x46, 0xE5).rgb, img.getRGB(20, 20))

        // Open book left page (#F7F7FA) and right page (#E8E9FF)
        assertEquals(Color(0xF7, 0xF7, 0xFA).rgb, img.getRGB(42, 72))
        assertEquals(Color(0xE8, 0xE9, 0xFF).rgb, img.getRGB(84, 72))

        // Bottom-right completion circle must use signal orange #F97316 and white checkmark #FFFFFF
        assertEquals(Color(0xF9, 0x73, 0x16).rgb, img.getRGB(94, 81))
        assertEquals(Color(0xF9, 0x73, 0x16).rgb, img.getRGB(94, 107))
        assertEquals(Color.WHITE.rgb, img.getRGB(92, 100))
    }
}
