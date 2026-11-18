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
        Theme.applyPreset(Theme.ThemePreset.AMOLED)
    }

    @Test
    fun `brand constants and geometry follow visual identity spec`() {
        assertEquals(14, COVER_ARC)
        assertEquals(Color(0x5B, 0x5B, 0xD6), Theme.PRIMARY)
        assertEquals(Color(0x49, 0x49, 0xC7), Theme.PRIMARY_HOVER)
        assertEquals(Color(0x36, 0xC5, 0xF0), Theme.ACCENT_CYAN)
    }

    @Test
    fun `light and dark presets expose cohesive brand and semantic tokens`() {
        Theme.applyPreset(Theme.ThemePreset.LIGHT)
        assertFalse(Theme.isDark)
        assertEquals(Color(0xF7, 0xF7, 0xFA), Theme.BG_APP)
        assertEquals(Color(0xFF, 0xFF, 0xFF), Theme.BG_SURFACE)
        assertEquals(Color(0x5B, 0x5B, 0xD6), Theme.ACCENT)
        assertEquals(Color(0x2F, 0xB3, 0x44), Theme.SUCCESS)
        assertEquals(Color(0xE5, 0xA5, 0x0A), Theme.WARNING)
        assertEquals(Color(0xE0, 0x1B, 0x24), Theme.DANGER)

        Theme.applyPreset(Theme.ThemePreset.DARK)
        assertTrue(Theme.isDark)
        assertEquals(Color(0x20, 0x21, 0x26), Theme.BG_SURFACE)
        assertEquals(Color(0xA6, 0xA6, 0xFF), Theme.ACCENT)
        assertEquals(Color(0x78, 0xB8, 0x86), Theme.SUCCESS)
        assertEquals(Color(0xF4, 0xC8, 0x75), Theme.WARNING)
        assertEquals(Color(0xFF, 0x8B, 0x8B), Theme.DANGER)
    }

    @Test
    fun `canonical svg resource is pure vector markup with brand palette`() {
        val stream = AppIcon::class.java.getResourceAsStream("/icons/mihon-linux.svg")
        assertNotNull(stream, "Bundled /icons/mihon-linux.svg must exist on classpath")
        val svgText = stream!!.bufferedReader().readText()
        assertEquals(AppIcon.CANONICAL_SVG.trim(), svgText.trim())
        assertTrue(svgText.contains("#5B5BD6"))
        assertTrue(svgText.contains("#36C5F0"))
        assertFalse(svgText.contains("data:image/"), "SVG must stay purely vector without embedded rasters")
    }

    @Test
    fun `procedural app icon renders indigo squircle book pages and cyan checkmark badge`() {
        val img = AppIcon.createImage(128)
        assertEquals(128, img.width)
        assertEquals(128, img.height)

        // Outer corner outside rx=26 squircle must be transparent
        assertEquals(0, (img.getRGB(1, 1) ushr 24) and 0xFF)

        // Top-left inside squircle must be primary indigo #5B5BD6
        assertEquals(Color(0x5B, 0x5B, 0xD6).rgb, img.getRGB(20, 20))

        // Open book left page (#F7F7FA) and right page (#E8E9FF)
        assertEquals(Color(0xF7, 0xF7, 0xFA).rgb, img.getRGB(42, 72))
        assertEquals(Color(0xE8, 0xE9, 0xFF).rgb, img.getRGB(84, 72))

        // Bottom-right completion circle must use cyan #36C5F0 and white checkmark #FFFFFF
        assertEquals(Color(0x36, 0xC5, 0xF0).rgb, img.getRGB(94, 81))
        assertEquals(Color(0x36, 0xC5, 0xF0).rgb, img.getRGB(94, 107))
        assertEquals(Color.WHITE.rgb, img.getRGB(92, 100))
    }
}
