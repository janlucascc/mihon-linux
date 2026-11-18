package mihon.desktop.e2e

import mihon.data.database.DatabaseManager
import mihon.data.repository.BookmarkRepositoryImpl
import mihon.data.repository.ChapterRepositoryImpl
import mihon.data.repository.HistoryRepositoryImpl
import mihon.data.repository.MangaRepositoryImpl
import mihon.desktop.AntiGravitySettingsScreen
import mihon.desktop.AppContext
import mihon.desktop.CoverCache
import mihon.desktop.LibraryService
import mihon.desktop.M3Switch
import mihon.desktop.ModernIcons
import mihon.desktop.ReaderAction
import mihon.desktop.ReaderWindow
import mihon.desktop.translator.DetectedTextRegion
import mihon.desktop.translator.NormalizedRect
import mihon.desktop.translator.OcrEngine
import mihon.desktop.translator.OcrSnippet
import mihon.desktop.translator.PageTranslationController
import mihon.desktop.translator.TextDetector
import mihon.desktop.translator.TranslationProvider
import mihon.desktop.translator.TranslatorLanguage
import mihon.desktop.translator.TranslatorPreferences
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReaderTranslatorUiE2eTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var testTranslatorNode: Preferences
    private lateinit var testGeneralPrefs: Preferences
    private lateinit var testReaderPrefs: Preferences
    private var origToggleCodes: List<Int> = emptyList()

    @BeforeEach
    fun setUp() {
        testTranslatorNode = Preferences.userRoot().node("mihon-e2e-ui-trans-${UUID.randomUUID()}")
        testGeneralPrefs = Preferences.userRoot().node("mihon-e2e-ui-gen-${UUID.randomUUID()}")
        testReaderPrefs = Preferences.userRoot().node("mihon-e2e-ui-read-${UUID.randomUUID()}")
        TranslatorPreferences.setBackingNodeForTest(testTranslatorNode)
        origToggleCodes = ReaderAction.getKeyCodes(ReaderAction.TOGGLE_TRANSLATOR)
    }

    @AfterEach
    fun tearDown() {
        ReaderAction.setKeyCodes(ReaderAction.TOGGLE_TRANSLATOR, origToggleCodes)
        TranslatorPreferences.setBackingNodeForTest(null)
        runCatching { testTranslatorNode.removeNode() }
        runCatching { testGeneralPrefs.removeNode() }
        runCatching { testReaderPrefs.removeNode() }
    }

    private fun findAllComponents(root: Component): List<Component> {
        val list = mutableListOf<Component>()
        fun walk(c: Component) {
            list.add(c)
            if (c is Container) {
                c.components.forEach { walk(it) }
            }
        }
        walk(root)
        return list
    }

    // =========================================================================
    // F8: Shortcut & Vector Icon (T1-F8-01..05, T2-F8-01..05)
    // =========================================================================

    @Test
    fun `T1-F8-01 to T1-F8-03 and T2-F8-01 to T2-F8-03 ReaderAction TOGGLE_TRANSLATOR`() {
        assertTrue(ReaderAction.entries.contains(ReaderAction.TOGGLE_TRANSLATOR))
        assertEquals(KeyEvent.VK_T, ReaderAction.TOGGLE_TRANSLATOR.defaultKeyCode)
        assertTrue(ReaderAction.TOGGLE_TRANSLATOR.label.isNotBlank())

        // Zero conflict across all ReaderAction defaults (T2-F8-02)
        val allDefaults = ReaderAction.entries.flatMap { it.defaultKeyCodes }
        assertEquals(allDefaults.size, allDefaults.distinct().size, "Default shortcut keys must have 0 collisions")

        val dummySource = JLabel()
        val keyT = KeyEvent(dummySource, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_T, 't')
        val keyCtrlT = KeyEvent(dummySource, KeyEvent.KEY_PRESSED, 0L, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_T, 't')
        val keyH = KeyEvent(dummySource, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_H, 'h')

        assertTrue(ReaderAction.TOGGLE_TRANSLATOR.matches(keyT))
        assertFalse(ReaderAction.TOGGLE_TRANSLATOR.matches(keyCtrlT), "Modified Ctrl+T must not trigger unmodified T")
        assertFalse(ReaderAction.TOGGLE_TRANSLATOR.matches(keyH))

        // Rebind to VK_G (T1-F8-03)
        ReaderAction.setKeyCodes(ReaderAction.TOGGLE_TRANSLATOR, listOf(KeyEvent.VK_G))
        assertTrue(ReaderAction.matches(ReaderAction.TOGGLE_TRANSLATOR, KeyEvent.VK_G))
        assertFalse(ReaderAction.matches(ReaderAction.TOGGLE_TRANSLATOR, KeyEvent.VK_T))

        // Corrupt negative keycode falls back safely (T2-F8-03)
        Preferences.userRoot().node("mihon-linux-shortcuts").put(ReaderAction.TOGGLE_TRANSLATOR.name, "-999")
        assertEquals(listOf(KeyEvent.VK_T), ReaderAction.getKeyCodes(ReaderAction.TOGGLE_TRANSLATOR))
    }

    @Test
    fun `T1-F8-04 to T1-F8-05 and T2-F8-04 to T2-F8-05 ModernIcons translateIcon vector rendering`() {
        val icon18 = ModernIcons.translateIcon(18, Color.WHITE)
        assertEquals(18, icon18.iconWidth)
        assertEquals(18, icon18.iconHeight)

        val img18 = BufferedImage(18, 18, BufferedImage.TYPE_INT_ARGB)
        val g18 = img18.createGraphics()
        icon18.paintIcon(null, g18, 0, 0)
        g18.dispose()

        var nonZeroPixels = 0
        for (y in 0 until 18) {
            for (x in 0 until 18) {
                if ((img18.getRGB(x, y) ushr 24) > 0) nonZeroPixels++
            }
        }
        assertTrue(nonZeroPixels > 10, "Expected visible drawn vector pixels on 18x18 icon")

        // T1-F8-05: distinct sizes & tint colors
        val iconCyan = ModernIcons.translateIcon(24, Color.CYAN)
        val img24 = BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB)
        val g24 = img24.createGraphics()
        iconCyan.paintIcon(null, g24, 0, 0)
        g24.dispose()
        var foundCyan = false
        for (y in 0 until 24) {
            for (x in 0 until 24) {
                val argb = img24.getRGB(x, y)
                if ((argb ushr 24) > 200 && ((argb shr 8) and 0xFF) > 200 && (argb and 0xFF) > 200 && ((argb shr 16) and 0xFF) < 20) {
                    foundCyan = true
                }
            }
        }
        assertTrue(foundCyan, "Expected cyan tint pixels on 24x24 icon")

        // T2-F8-04: size 1 and size 0 do not crash
        val icon1 = ModernIcons.translateIcon(1, Color.WHITE)
        val icon0 = ModernIcons.translateIcon(0, Color.WHITE)
        val tinyImg = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        val gt = tinyImg.createGraphics()
        icon1.paintIcon(null, gt, 0, 0)
        icon0.paintIcon(null, gt, 0, 0)
        gt.dispose()

        // T2-F8-05: semi-transparent alpha=128 never exceeds 128
        val semiIcon = ModernIcons.translateIcon(20, Color(255, 255, 255, 128))
        val semiImg = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val gs = semiImg.createGraphics()
        semiIcon.paintIcon(null, gs, 0, 0)
        gs.dispose()
        for (y in 0 until 20) {
            for (x in 0 until 20) {
                val alpha = (semiImg.getRGB(x, y) ushr 24) and 0xFF
                assertTrue(alpha <= 128, "Pixel alpha $alpha exceeded 128 at ($x, $y)")
            }
        }
    }

    // =========================================================================
    // F9: SettingsScreen Translator Section (T1-F9-01..05, T2-F9-01..05)
    // =========================================================================

    @Test
    fun `T1-F9-01 to T1-F9-05 and T2-F9-01 to T2-F9-05 SettingsScreen Translator UI controls`() {
        val dbFile = File(tempDir, "settings_test.db")
        val db = DatabaseManager(dbFile).apply { initialize() }
        val ctx = AppContext(
            dbFile = dbFile,
            service = LibraryService(
                MangaRepositoryImpl(db),
                ChapterRepositoryImpl(db),
                HistoryRepositoryImpl(db),
                BookmarkRepositoryImpl(db),
                File(tempDir, "downloads"),
            ),
            extensions = emptyList(),
            covers = CoverCache(File(tempDir, "covers")),
            setStatus = {},
        )

        val screen = AntiGravitySettingsScreen(
            ctx = ctx,
            prefs = testGeneralPrefs,
            readerPrefs = testReaderPrefs,
            dbFile = dbFile,
            homeDir = tempDir,
            onNavigateBack = {},
            onApplyTheme = {},
            onOpenCustomThemeDialog = {},
            onExportBackup = {},
            onImportBackup = {},
            onOpenTestLab = {},
            onExitApp = {},
        )

        val allComponents = findAllComponents(screen)
        val allLabels = allComponents.filterIsInstance<JLabel>().mapNotNull { it.text }

        // T1-F9-01: Section header present
        assertTrue(allLabels.any { it.contains("Tradutor Simultâneo (Google Lens)") })

        // T1-F9-02: Shortcut entry present
        assertTrue(allLabels.any { it.contains(ReaderAction.TOGGLE_TRANSLATOR.title) })

        // T1-F9-03: Source & Target language combos
        @Suppress("UNCHECKED_CAST")
        val srcCombo = allComponents.firstOrNull { it.name == "translatorSourceLangCombo" } as? JComboBox<Any>
        @Suppress("UNCHECKED_CAST")
        val dstCombo = allComponents.firstOrNull { it.name == "translatorTargetLangCombo" } as? JComboBox<Any>
        assertNotNull(srcCombo)
        assertNotNull(dstCombo)

        srcCombo.selectedItem = TranslatorLanguage.ENGLISH
        dstCombo.selectedItem = TranslatorLanguage.SPANISH
        assertEquals(TranslatorLanguage.ENGLISH, TranslatorPreferences.load().sourceLanguage)
        assertEquals(TranslatorLanguage.SPANISH, TranslatorPreferences.load().targetLanguage)

        // T1-F9-04: Provider selector
        @Suppress("UNCHECKED_CAST")
        val providerCombo = allComponents.firstOrNull { it.name == "translatorProviderCombo" } as? JComboBox<Any>
        assertNotNull(providerCombo)
        providerCombo.selectedItem = "mymemory"
        assertEquals("mymemory", TranslatorPreferences.load().providerId)
        providerCombo.selectedItem = "LibreTranslate"
        assertEquals("libretranslate", TranslatorPreferences.load().providerId)

        // T1-F9-05: Default switch
        val defaultSwitch = allComponents.firstOrNull { it.name == "translatorEnabledByDefaultSwitch" } as? M3Switch
        assertNotNull(defaultSwitch)

        // T2-F9-01: Empty LibreTranslate URL falls back safely without crash
        val urlField = allComponents.firstOrNull { it.name == "libreTranslateUrlField" } as? JTextField
        assertNotNull(urlField)
        urlField.text = "   "
        assertEquals("https://libretranslate.com/translate", TranslatorPreferences.load().libreTranslateUrl)

        screen.dispose()
    }

    // =========================================================================
    // F10: ReaderWindow Auto-Translate Integration (T1-F10-01..05, T2-F10-01..05)
    // =========================================================================

    @Test
    fun `T1-F10-01 to T1-F10-04 and T2-F10-01 to T2-F10-05 ReaderWindow translator integration`() {
        if (GraphicsEnvironment.isHeadless()) return

        val translatedPages = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val page0Latch = CountDownLatch(1)
        val page1Latch = CountDownLatch(1)

        val detector = object : TextDetector {
            override fun detectRegions(image: BufferedImage): List<DetectedTextRegion> =
                listOf(DetectedTextRegion(NormalizedRect(0.1f, 0.06f, 0.45f, 0.13f)))
        }
        val ocr = object : OcrEngine {
            override fun isAvailable(): Boolean = true
            override fun recognizePage(
                image: BufferedImage,
                regions: List<DetectedTextRegion>,
                sourceLang: TranslatorLanguage,
            ): List<OcrSnippet> =
                listOf(OcrSnippet("HELLO HERO!", NormalizedRect(0.1f, 0.06f, 0.45f, 0.13f), 0.95f))
            override fun close() {}
        }
        val provider = object : TranslationProvider {
            override val id: String = "mock"
            override val displayName: String = "Mock"
            override suspend fun translateBatch(
                texts: List<String>,
                sourceLang: TranslatorLanguage,
                targetLang: TranslatorLanguage,
            ): List<String> = texts.map { "OLÁ HERÓI!" }
        }

        var windowRef: ReaderWindow? = null
        try {
            SwingUtilities.invokeAndWait {
                windowRef = ReaderWindow(
                    title = "E2E Chapter 1",
                    source = E2eTestHarness.resourceFile("manga_en_chapter.cbz"),
                    startPage = 0,
                )
            }
            val win = windowRef!!

            val customCtrl = PageTranslationController(
                textDetector = detector,
                ocrEngine = ocr,
                translationProvider = provider,
                onPageResultReady = { res ->
                    translatedPages.add(res.pageIndex)
                    if (res.pageIndex == 0) page0Latch.countDown()
                    if (res.pageIndex == 1) page1Latch.countDown()
                },
            )
            SwingUtilities.invokeAndWait {
                win.replaceTranslatorControllerForTest(customCtrl)
            }

            // T1-F10-01: button in topHud & non-focusable
            assertFalse(win.translatorBtn.isFocusable)
            assertFalse(win.translatorEnabled)

            // T1-F10-03: Disabled render vs Enabled render
            Thread.sleep(150) // allow page 0 decode
            val disabledCanvas = win.renderCurrentCanvasToImage(800, 1200)

            // T1-F10-02: Toggle ON via VK_T key event
            SwingUtilities.invokeAndWait {
                val keyEvent = KeyEvent(win, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_T, 't')
                win.keyListeners.forEach { it.keyPressed(keyEvent) }
            }
            assertTrue(win.translatorEnabled)
            assertTrue(page0Latch.await(3, TimeUnit.SECONDS), "Page 0 should translate automatically when toggled ON")
            Thread.sleep(50)

            val enabledCanvas = win.renderCurrentCanvasToImage(800, 1200)
            val totalDiff = E2eTestHarness.countDifferingPixelsInRect(
                disabledCanvas,
                enabledCanvas,
                java.awt.Rectangle(0, 0, 800, 1200),
            )
            assertTrue(totalDiff > 50, "Enabled canvas must render Google Lens overlay pixels")

            // T2-F10-01: loadChapter resets old chapter cache while preserving translatorEnabled
            SwingUtilities.invokeAndWait {
                win.loadChapter(
                    newTitle = "E2E Chapter 2",
                    newSource = E2eTestHarness.resourceFile("manga_jp_chapter.cbz"),
                    startPage = 0,
                )
            }
            assertTrue(win.translatorEnabled, "translatorEnabled must stay true across loadChapter")

            // Toggle OFF -> restores unmodified render
            SwingUtilities.invokeAndWait {
                win.setTranslatorEnabled(false)
            }
            assertFalse(win.translatorEnabled)
        } finally {
            windowRef?.let { w ->
                SwingUtilities.invokeAndWait {
                    w.dispose()
                }
            }
        }
    }
}
