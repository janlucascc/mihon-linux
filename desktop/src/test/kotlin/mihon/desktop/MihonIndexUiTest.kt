package mihon.desktop

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.util.prefs.Preferences
import javax.swing.ImageIcon
import kotlin.math.pow

class MihonIndexUiTest {

    @AfterEach
    fun restoreTheme() {
        Theme.applyPreset(Theme.ThemePreset.DARK)
    }

    private fun contrastRatio(fg: Color, bg: Color): Double {
        fun relLum(c: Color): Double {
            fun ch(v: Int): Double {
                val s = v / 255.0
                return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
        }
        val l1 = maxOf(relLum(fg), relLum(bg))
        val l2 = minOf(relLum(fg), relLum(bg))
        return (l1 + 0.05) / (l2 + 0.05)
    }

    @Test
    fun `theme presets maintain WCAG contrast and notify listeners safely`() {
        Theme.ThemePreset.entries.filter { it != Theme.ThemePreset.CUSTOM }.forEach { preset ->
            Theme.applyPreset(preset)
            assertTrue(
                contrastRatio(Theme.TEXT_PRIMARY, Theme.BG_APP) >= 7.0,
                "Primary text on ${preset.name} BG_APP must have high contrast",
            )
            assertTrue(
                contrastRatio(Theme.TEXT_SECONDARY, Theme.BG_SURFACE) >= 4.5,
                "Secondary text on ${preset.name} BG_SURFACE must meet 4.5:1",
            )
        }

        var calls = 0
        val listener: (Boolean) -> Unit = { calls++ }
        Theme.addListener(listener)
        Theme.addListener(listener) // duplicate add must be deduplicated
        Theme.applyPreset(Theme.ThemePreset.LIGHT)
        assertEquals(1, calls)

        Theme.removeListener(listener)
        Theme.applyPreset(Theme.ThemePreset.DARK)
        assertEquals(1, calls, "Removed listener must no longer receive updates")
    }

    @Test
    fun `custom theme preserves isDark based on luminance and configures tokens`() {
        Theme.setCustomColors(
            bgApp = Color(0xF6, 0xF7, 0xFB),
            bgSidebar = Color(0xEA, 0xEC, 0xF5),
            bgSurface = Color.WHITE,
            accent = Color(0x4F, 0x46, 0xE5),
            textPrimary = Color(0x16, 0x17, 0x22),
        )
        assertFalse(Theme.isDark)
        assertEquals(Theme.ThemePreset.CUSTOM, Theme.currentPreset)

        Theme.setCustomColors(
            bgApp = Color(0x12, 0x13, 0x18),
            bgSidebar = Color(0x18, 0x19, 0x20),
            bgSurface = Color(0x1E, 0x20, 0x28),
            accent = Color(0x81, 0x8C, 0xF8),
            textPrimary = Color(0xEE, 0xF0, 0xF6),
        )
        assertTrue(Theme.isDark)
    }

    @Test
    fun `library responsive columns and header count formatting follow viewport breakpoints`() {
        val colsNarrow = computeLibraryColumnCount(720)
        val colsDefault = computeLibraryColumnCount(1240)
        val colsWide = computeLibraryColumnCount(1600)
        assertTrue(colsNarrow < colsDefault, "Narrower viewport should use fewer columns ($colsNarrow vs $colsDefault)")
        assertTrue(colsDefault <= colsWide, "Wide viewport should fit at least as many columns ($colsWide vs $colsDefault)")
        assertEquals(1, computeLibraryColumnCount(120))

        assertEquals("128 títulos", formatLibraryCount(128, 128, hasQueryOrFilter = false, unreadOnly = false))
        assertEquals("1 título", formatLibraryCount(1, 1, hasQueryOrFilter = false, unreadOnly = false))
        assertEquals("24 títulos com não lidos", formatLibraryCount(24, 128, hasQueryOrFilter = true, unreadOnly = true))
        assertEquals("4 resultados", formatLibraryCount(4, 128, hasQueryOrFilter = true, unreadOnly = false))
        assertEquals("0 resultados", formatLibraryCount(0, 128, hasQueryOrFilter = true, unreadOnly = false))
        assertEquals(220, sidebarWidthForState(collapsed = false))
        assertEquals(72, sidebarWidthForState(collapsed = true))
        assertTrue(sidebarWidthForState(collapsed = true) < sidebarWidthForState(collapsed = false))
    }

    @Test
    fun `poster card keeps fixed dimensions and renders placeholder signal download and primary selection`() {
        val card = MangaPosterCard()
        assertEquals(Dimension(CARD_W, CARD_H), card.preferredSize)

        // 1. Card without cover renders neutral BG_CARD placeholder
        Theme.applyPreset(Theme.ThemePreset.LIGHT)
        card.setSize(CARD_W, CARD_H)
        card.coverIcon = null
        card.titleText = "A Very Long Manga Title That Must Wrap Cleanly Within Two Lines Without Overflowing Card Height"
        card.subtitleText = "Author Name"
        card.unreadBadgeCount = 0
        card.downloadsBadgeCount = 0
        card.selectedState = false
        val emptyCoverImg = BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB)
        card.paintComponentForTest(emptyCoverImg)
        val coverX = (CARD_W - COVER_W) / 2
        val coverY = 6
        assertEquals(Theme.BG_CARD.rgb, emptyCoverImg.getRGB(coverX + 20, coverY + 20))
        assertEquals(Dimension(CARD_W, CARD_H), card.preferredSize)

        // 2. Card with unread badge paints Signal Orange (#F97316) and selected state uses Primary (#4F46E5) border
        val dummyCover = BufferedImage(COVER_W, COVER_H, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            g.color = Color(0x22, 0x24, 0x2E)
            g.fillRect(0, 0, COVER_W, COVER_H)
            g.dispose()
        }
        card.coverIcon = ImageIcon(dummyCover)
        card.unreadBadgeCount = 12
        card.downloadsBadgeCount = 5
        card.selectedState = true
        val activeImg = BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB)
        card.paintComponentForTest(activeImg)

        // Selected cover lifts by -3px -> coverY becomes 3
        val liftedCoverY = 3
        assertEquals(Theme.PRIMARY.rgb, activeImg.getRGB(coverX + COVER_W / 2, liftedCoverY))

        // Unread badge top-left inside cover uses Signal Orange #F97316
        assertEquals(Theme.SIGNAL.rgb, activeImg.getRGB(coverX + 12, liftedCoverY + 11))

        // 3. Card with only downloads badge uses Semantic Success
        card.unreadBadgeCount = 0
        card.downloadsBadgeCount = 8
        card.selectedState = false
        val dlImg = BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB)
        card.paintComponentForTest(dlImg)
        assertEquals(Theme.SUCCESS.rgb, dlImg.getRGB(coverX + 12, coverY + 11))
    }

    @Test
    fun `read chapter row preserves title visibility without erasing chapter text`() {
        Theme.applyPreset(Theme.ThemePreset.LIGHT)
        val row = DetailsScreen.ChapterRowCard().apply {
            setSize(640, 56)
            chapterIndex = 12
            chapterTitle = "O Despertar da Biblioteca"
            isRead = true
            isDownloaded = true
            selectedState = false
        }
        val canvas = BufferedImage(640, 56, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            g.color = Theme.BG_APP
            g.fillRect(0, 0, 640, 56)
            row.paint(g)
            g.dispose()
        }
        val titleRegionHasText = (70 until 230).any { px ->
            (18 until 38).any { py -> canvas.getRGB(px, py) != Theme.BG_APP.rgb }
        }
        assertTrue(titleRegionHasText, "Read chapter row must still render its chapter title")
    }

    @Test
    fun `modern button respects 38px compact and 48px primary heights and flat switching works without NPE`() {
        val compactBtn = ModernButton("Ação", variant = ModernButton.Variant.SURFACE)
        assertEquals(38, compactBtn.preferredSize.height)

        val primaryBtn = ModernButton("Continuar Leitura", variant = ModernButton.Variant.PRIMARY).apply {
            minHeightOverride = Theme.CONTROL_HEIGHT_PRIMARY
        }
        assertEquals(48, primaryBtn.preferredSize.height)

        // Switching themes when no windows exist must not throw NPE
        val rootNode = Preferences.userRoot().node("mihon-linux")
        val prevPreset = rootNode.get("themePresetIndex", null)
        val prevDark = rootNode.get("darkTheme", null)
        try {
            assertDoesNotThrow {
                applyTheme(Theme.ThemePreset.LIGHT)
                applyTheme(Theme.ThemePreset.AMOLED)
                applyTheme(Theme.ThemePreset.DARK)
            }
            val savedName = rootNode.get("themePresetIndex", "")
            assertEquals("DARK", savedName)
        } finally {
            if (prevPreset != null) rootNode.put("themePresetIndex", prevPreset) else rootNode.remove("themePresetIndex")
            if (prevDark != null) rootNode.put("darkTheme", prevDark) else rootNode.remove("darkTheme")
            configureTheme()
        }
    }

    @Test
    fun `placeholder generation uses neutral card background and removed listener is not retained`() {
        Theme.applyPreset(Theme.ThemePreset.LIGHT)
        val cache = CoverCache()
        val ph = cache.get(987654321L, "Test Title")
        assertEquals(COVER_W, ph.iconWidth)
        assertEquals(COVER_H, ph.iconHeight)
        val buffered = ph.image as BufferedImage
        assertEquals(Theme.BG_CARD.rgb, buffered.getRGB(8, 8))

        val dbFile = java.io.File.createTempFile("mihon-ui", ".db").apply { deleteOnExit() }
        val db = mihon.data.database.DatabaseManager(dbFile).apply { initialize() }
        val service = LibraryService(
            mihon.data.repository.MangaRepositoryImpl(db),
            mihon.data.repository.ChapterRepositoryImpl(db),
            mihon.data.repository.HistoryRepositoryImpl(db),
            mihon.data.repository.BookmarkRepositoryImpl(db),
        )
        val ctx = AppContext(
            dbFile = dbFile,
            service = service,
            extensions = emptyList(),
            covers = cache,
            setStatus = {},
            coroutineScope = appScope,
        )
        var fireCount = 0
        val tempPanel = object : javax.swing.JPanel() {
            private val tl: (Boolean) -> Unit = { fireCount++ }
            init { ctx.addThemeListener(tl) }
            override fun removeNotify() {
                ctx.removeThemeListener(tl)
                super.removeNotify()
            }
        }
        val weakRef = java.lang.ref.WeakReference(tempPanel)
        Theme.applyPreset(Theme.ThemePreset.DARK)
        assertEquals(1, fireCount)
        tempPanel.removeNotify()
        Theme.applyPreset(Theme.ThemePreset.LIGHT)
        assertEquals(1, fireCount, "Listener removed in removeNotify must not fire")
        assertTrue(weakRef.get() != null)
        dbFile.delete()
    }
}

private fun MangaPosterCard.paintComponentForTest(img: BufferedImage) {
    val g = img.createGraphics()
    paint(g)
    g.dispose()
}
