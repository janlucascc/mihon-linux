package mihon.desktop

import mihon.core.archive.ArchivePage
import mihon.core.archive.ArchiveReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.imageio.ImageIO
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

class ReaderRegressionTest {

    private class MemoryArchiveReader(
        override val sourceName: String,
        private val width: Int,
        private val height: Int,
        count: Int,
    ) : ArchiveReader {
        private val imageBytes: List<ByteArray> = (0 until count).map { idx ->
            val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = Color(24 + idx * 10, 28, 36)
            g.fillRect(0, 0, width, height)
            g.dispose()
            ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        }

        override val pages: List<ArchivePage> = (0 until count).map { idx ->
            ArchivePage(index = idx, name = "page_${idx + 1}.png", path = "page_${idx + 1}.png", size = imageBytes[idx].size.toLong())
        }

        override fun getPageStream(page: ArchivePage): InputStream = ByteArrayInputStream(imageBytes[page.index])

        override fun getPageBytes(page: ArchivePage): ByteArray = imageBytes[page.index]

        override fun close() = Unit
    }

    @Test
    fun `loadChapter updates bookmark chapterId callbacks and Shift+H does not hide HUD`() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires non-headless AWT display for ReaderWindow")

        val toggledChapters = mutableListOf<Pair<Long, Int>>()
        val highlightCalls = mutableListOf<Long>()
        val source1 = MemoryArchiveReader("ch1", 600, 900, 3)
        val source2 = MemoryArchiveReader("ch2", 600, 900, 3)

        var window: ReaderWindow? = null
        try {
            SwingUtilities.invokeAndWait {
                window = ReaderWindow(
                    title = "Cap 1",
                    source = source1,
                    startPage = 0,
                    chapterId = 101L,
                    mangaTitle = "Test Manga",
                    onToggleBookmark = { chId, page ->
                        toggledChapters.add(chId to page)
                        true
                    },
                )
            }
            val win = window!!

            SwingUtilities.invokeAndWait {
                val eventB1 = KeyEvent(win, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_B, 'b')
                win.keyListeners.forEach { it.keyPressed(eventB1) }

                win.loadChapter(
                    newTitle = "Cap 2",
                    newSource = source2,
                    startPage = 1,
                    newChapterId = 202L,
                    newMangaTitle = "Test Manga",
                    newOnToggleBookmark = { chId, page ->
                        toggledChapters.add(chId to page)
                        true
                    },
                    newOnAddHighlight = { chId, _, _ ->
                        highlightCalls.add(chId)
                    },
                )

                val eventB2 = KeyEvent(win, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_B, 'b')
                win.keyListeners.forEach { it.keyPressed(eventB2) }

                val hudTop = (win.contentPane.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH)
                assertTrue(hudTop.isVisible, "HUD should start visible")

                // Pressing Shift+H must not trigger plain H HUD toggle
                val shiftH = KeyEvent(win, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), KeyEvent.SHIFT_DOWN_MASK, KeyEvent.VK_H, 'H')
                // Intercept modal dialog launch on Shift+H by clearing highlight callback only for HUD check:
                win.loadChapter(
                    newTitle = "Cap 2",
                    newSource = MemoryArchiveReader("ch2b", 600, 900, 2),
                    startPage = 0,
                    newChapterId = 202L,
                    newMangaTitle = "Test Manga",
                    newOnAddHighlight = null,
                )
                win.keyListeners.forEach { it.keyPressed(shiftH) }
                assertTrue(hudTop.isVisible, "Shift+H must not hide HUD via TOGGLE_HUD")

                // Plain H still toggles HUD off
                val plainH = KeyEvent(win, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_H, 'h')
                win.keyListeners.forEach { it.keyPressed(plainH) }
                assertFalse(hudTop.isVisible, "Plain H must toggle HUD visibility")
            }

            assertEquals(listOf(101L to 0, 202L to 1), toggledChapters)
        } finally {
            window?.let { w ->
                SwingUtilities.invokeAndWait { w.dispose() }
            }
        }
    }

    @Test
    fun `zoomed reader horizontal arrow pans before flipping pages`() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires non-headless AWT display for ReaderWindow")

        val prefs = java.util.prefs.Preferences.userRoot().node("mihon-linux-reader")
        val prevMode = prefs.get("mode", null)
        prefs.put("mode", "SINGLE")
        val source = MemoryArchiveReader("wide_pan", 1200, 900, 3)
        var window: ReaderWindow? = null
        try {
            SwingUtilities.invokeAndWait {
                window = ReaderWindow(title = "Zoom Pan", source = source, startPage = 0)
            }
            val win = window!!

            SwingUtilities.invokeAndWait {
                win.setSize(700, 700)
                win.validate()
                val zoomIn = KeyEvent(win, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_EQUALS, '=')
                repeat(6) { win.keyListeners.forEach { it.keyPressed(zoomIn) } }
            }

            // Wait briefly for initial async page decode to populate PagedView preferred size
            val deadline = System.currentTimeMillis() + 3000L
            var hasOverflow = false
            while (System.currentTimeMillis() < deadline && !hasOverflow) {
                SwingUtilities.invokeAndWait {
                    win.validate()
                    val sp = findFirstJScrollPane(win.contentPane)
                    val hb = sp?.horizontalScrollBar
                    hasOverflow = hb != null && hb.maximum > hb.visibleAmount + 20
                }
                if (!hasOverflow) Thread.sleep(25)
            }

            SwingUtilities.invokeAndWait {
                val scrollPane = findFirstJScrollPane(win.contentPane)!!
                val hBar = scrollPane.horizontalScrollBar
                assertTrue(hBar.maximum > hBar.visibleAmount + 20, "Zoomed page should create horizontal overflow")

                val beforeVal = hBar.value
                val rightArrow = KeyEvent(win, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_RIGHT, KeyEvent.CHAR_UNDEFINED)
                win.keyListeners.forEach { it.keyPressed(rightArrow) }

                assertTrue(hBar.value > beforeVal, "VK_RIGHT while horizontally scrollable should pan right first")
            }
        } finally {
            window?.let { w ->
                SwingUtilities.invokeAndWait { w.dispose() }
            }
            if (prevMode == null) prefs.remove("mode") else prefs.put("mode", prevMode)
        }
    }

    @Test
    fun `highlight color dialog renders opaque color swatches`() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires non-headless AWT display for HighlightColorDialog")

        var dialog: HighlightColorDialog? = null
        try {
            SwingUtilities.invokeAndWait {
                val dlg = HighlightColorDialog(null) {}
                dialog = dlg
                dlg.addNotify()
                dlg.setSize(420, 300)
                dlg.validate()
                dlg.contentPane.setSize(420, 300)
                layoutTree(dlg.contentPane)

                val img = BufferedImage(420, 300, BufferedImage.TYPE_INT_ARGB)
                val g = img.createGraphics()
                dlg.contentPane.paint(g)
                g.dispose()

                // Swatches should contain yellow (#FFFF00) rather than all-background grey
                var foundYellow = false
                for (y in 0 until img.height step 2) {
                    for (x in 0 until img.width step 2) {
                        if ((img.getRGB(x, y) and 0xFFFFFF) == 0xFFFF00) {
                            foundYellow = true
                            break
                        }
                    }
                    if (foundYellow) break
                }
                assertTrue(foundYellow, "HighlightColorDialog must actually paint swatch colors")
            }
        } finally {
            dialog?.let { d ->
                SwingUtilities.invokeAndWait { d.dispose() }
            }
        }
    }

    private fun layoutTree(comp: java.awt.Container) {
        comp.doLayout()
        for (child in comp.components) {
            if (child is java.awt.Container) layoutTree(child)
        }
    }

    private fun findFirstJScrollPane(container: java.awt.Container): JScrollPane? {
        for (c in container.components) {
            if (c is JScrollPane) return c
            if (c is java.awt.Container) {
                findFirstJScrollPane(c)?.let { return it }
            }
        }
        return null
    }
}
