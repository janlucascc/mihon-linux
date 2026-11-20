package mihon.desktop

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import mihon.data.database.DatabaseManager
import mihon.data.extension.ExtensionLoader
import mihon.data.repository.ChapterRepositoryImpl
import mihon.data.repository.HistoryRepositoryImpl
import mihon.data.repository.MangaRepositoryImpl
import mihon.data.repository.BookmarkRepositoryImpl
import tachiyomi.domain.manga.model.Manga
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.prefs.Preferences
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.WindowConstants
import javax.swing.JToggleButton
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.plaf.ColorUIResource

private const val PREF_DARK = "darkTheme"
private const val PREF_PRESET = "themePresetIndex"
private const val PREF_CUSTOM_BG = "customBgApp"
private const val PREF_CUSTOM_SIDEBAR = "customBgSidebar"
private const val PREF_CUSTOM_SURFACE = "customBgSurface"
private const val PREF_CUSTOM_ACCENT = "customAccent"
private const val PREF_CUSTOM_TEXT = "customTextPrimary"
private val prefs: Preferences = Preferences.userRoot().node("mihon-linux")
private val readerPrefs: Preferences = Preferences.userRoot().node("mihon-linux-reader")

internal fun configureTheme(presetName: String? = null) {
    val chosenName = presetName ?: prefs.get(PREF_PRESET, if (prefs.getBoolean(PREF_DARK, true)) "DARK" else "LIGHT")
    val preset = runCatching { Theme.ThemePreset.valueOf(chosenName) }.getOrDefault(Theme.ThemePreset.DARK)

    if (preset == Theme.ThemePreset.CUSTOM) {
        val bgApp = Color(prefs.getInt(PREF_CUSTOM_BG, 0x141414))
        val bgSidebar = Color(prefs.getInt(PREF_CUSTOM_SIDEBAR, 0x1A1A1A))
        val bgSurface = Color(prefs.getInt(PREF_CUSTOM_SURFACE, 0x222222))
        val accent = Color(prefs.getInt(PREF_CUSTOM_ACCENT, 0xFFFFFF))
        val textPrimary = Color(prefs.getInt(PREF_CUSTOM_TEXT, 0xFFFFFF))
        Theme.setCustomColors(bgApp, bgSidebar, bgSurface, accent, textPrimary)
    } else {
        Theme.applyPreset(preset)
    }

    val dark = Theme.isDark

    // Descartar overrides manuais para que o Look and Feel assuma as cores nativas limpas sem cache
    UIManager.put("Button.background", null)
    UIManager.put("Button.foreground", null)
    UIManager.put("ComboBox.background", null)
    UIManager.put("ComboBox.foreground", null)
    UIManager.put("TextField.background", null)
    UIManager.put("TextField.foreground", null)
    UIManager.put("CheckBox.background", null)
    UIManager.put("CheckBox.foreground", null)
    UIManager.put("Label.foreground", null)

    try {
        if (dark) {
            UIManager.setLookAndFeel(FlatDarkLaf())
        } else {
            UIManager.setLookAndFeel(FlatLightLaf())
        }
    } catch (_: Exception) {
        if (dark) FlatDarkLaf.setup() else FlatLightLaf.setup()
    }

    UIManager.put("Panel.background", ColorUIResource(Theme.BG_APP))
    UIManager.put("ScrollPane.background", ColorUIResource(Theme.BG_APP))
    UIManager.put("Viewport.background", ColorUIResource(Theme.BG_APP))
    UIManager.put("List.background", ColorUIResource(Theme.BG_APP))
    UIManager.put("List.foreground", ColorUIResource(Theme.TEXT_PRIMARY))
    UIManager.put("Label.foreground", ColorUIResource(Theme.TEXT_PRIMARY))
    UIManager.put("ToggleButton.selectedBackground", ColorUIResource(Theme.BG_PILL_ACTIVE))
    UIManager.put("ToggleButton.selectedForeground", ColorUIResource(Theme.TEXT_PRIMARY))
    UIManager.put("Component.focusColor", ColorUIResource(Theme.PRIMARY))
    UIManager.put("Component.focusedBorderColor", ColorUIResource(Theme.PRIMARY))
    UIManager.put("Button.focusedBorderColor", ColorUIResource(Theme.PRIMARY))
    UIManager.put("ProgressBar.foreground", ColorUIResource(Theme.PRIMARY))
    UIManager.put("Component.arc", Theme.CONTROL_RADIUS * 2)
    UIManager.put("Button.arc", Theme.CONTROL_RADIUS * 2)
    UIManager.put("TextComponent.arc", Theme.CONTROL_RADIUS * 2)
    UIManager.put("ProgressBar.arc", Theme.CONTROL_RADIUS * 2)
    UIManager.put("ScrollBar.thumbArc", 999)
    UIManager.put("ScrollBar.width", 10)
}

private fun resetComponentStylesAndRefreshWindows() {
    fun resetComponentStyles(comp: java.awt.Component) {
        if ((comp is JButton && comp !is ModernButton && comp !is ModernIconButton) || comp is JComboBox<*>) {
            comp.background = null
            comp.foreground = null
        }
        if (comp is JCheckBox) {
            comp.background = null
            comp.foreground = null
            comp.isOpaque = false
        }
        if (comp is java.awt.Container) {
            comp.components.forEach { resetComponentStyles(it) }
        }
    }

    java.awt.Window.getWindows().forEach { win ->
        resetComponentStyles(win)
        SwingUtilities.updateComponentTreeUI(win)
        win.repaint()
    }
    FlatLaf.updateUI()
}

internal fun applyTheme(preset: Theme.ThemePreset) {
    prefs.put(PREF_PRESET, preset.name)
    prefs.putBoolean(PREF_DARK, preset.isDark)
    configureTheme(preset.name)
    resetComponentStylesAndRefreshWindows()
}

internal fun applyCustomTheme(bgApp: Color, bgSidebar: Color, bgSurface: Color, accent: Color, textPrimary: Color) {
    prefs.put(PREF_PRESET, Theme.ThemePreset.CUSTOM.name)
    prefs.putInt(PREF_CUSTOM_BG, bgApp.rgb and 0xFFFFFF)
    prefs.putInt(PREF_CUSTOM_SIDEBAR, bgSidebar.rgb and 0xFFFFFF)
    prefs.putInt(PREF_CUSTOM_SURFACE, bgSurface.rgb and 0xFFFFFF)
    prefs.putInt(PREF_CUSTOM_ACCENT, accent.rgb and 0xFFFFFF)
    prefs.putInt(PREF_CUSTOM_TEXT, textPrimary.rgb and 0xFFFFFF)

    configureTheme(Theme.ThemePreset.CUSTOM.name)
    resetComponentStylesAndRefreshWindows()
}

internal fun sidebarWidthForState(collapsed: Boolean): Int = if (collapsed) 72 else 220

fun main() {
    javax.imageio.ImageIO.setUseCache(false)
    AppIcon.installLinuxDesktopIntegration()
    setupUncaughtErrorHandling()
    configureTheme()
    SwingUtilities.invokeLater { MihonApp() }
}

private fun setupUncaughtErrorHandling() {
    val logDir = File(System.getProperty("user.home"), ".mihon/logs").apply { mkdirs() }
    val logFile = File(logDir, "mihon.log")
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching {
            logFile.appendText("[${fmt.format(LocalDateTime.now())}] [${thread.name}] ${throwable.stackTraceToString()}\n")
        }
        SwingUtilities.invokeLater {
            JOptionPane.showMessageDialog(
                null,
                "Ocorreu um erro inesperado:\n${throwable.message ?: throwable.javaClass.simpleName}\n\nDetalhes gravados em ~/.mihon/logs/mihon.log",
                "Erro",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }
}

class MihonApp : JFrame("Mihon Linux"), Navigator {
    private val home = System.getProperty("user.home")
    private val dbFile = File(home, ".mihon/mihon.db")
    private val db = DatabaseManager(dbFile).apply { initialize() }
    private val toastHost = mihon.desktop.ui.components.ToastHost(maxVisible = 3)
    private val navigationHistory = ArrayDeque<String>()

    private fun showToast(message: String, type: mihon.desktop.ui.components.ToastType? = null) {
        val clean = message.trim()
        if (clean.isEmpty()) return
        val resolvedType = type ?: mihon.desktop.ui.components.ToastHost.inferType(clean)
        toastHost.show(clean, resolvedType)
    }

    private fun updateStatusBar(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val lower = clean.lowercase()
        val isSilentProgress = lower.startsWith("exibindo ") ||
            lower.startsWith("biblioteca: ") ||
            lower.matches(Regex("^\\d+ de \\d+ mangá.*"))
        if (!isSilentProgress) {
            showToast(clean)
        }
    }

    private val ctx = AppContext(
        dbFile = dbFile,
        service = LibraryService(
            MangaRepositoryImpl(db),
            ChapterRepositoryImpl(db),
            HistoryRepositoryImpl(db),
            BookmarkRepositoryImpl(db),
        ),
        extensions = ExtensionLoader(File(home, ".mihon/extensions")).loadExtensionsBlocking(),
        covers = CoverCache(),
        setStatus = { text -> SwingUtilities.invokeLater { updateStatusBar(text) } },
        coroutineScope = appScope,
    )

    private val cards = CardLayout()
    private val content = object : JPanel(cards) {
        private var transitionProgress = 1f
        private var transitionDirection = 1
        private var transitionTimer: javax.swing.Timer? = null

        fun playTransition(direction: Int = 1) {
            val durMs = mihon.desktop.ui.animation.MotionPreferences.effectiveDurationMs(180)
            transitionTimer?.stop()
            if (durMs <= 0L) {
                transitionProgress = 1f
                repaint()
                return
            }
            transitionDirection = direction
            transitionProgress = 0f
            val startNanos = System.nanoTime()
            val durNanos = durMs * 1_000_000.0
            transitionTimer = javax.swing.Timer(12) { e ->
                val rawT = ((System.nanoTime() - startNanos) / durNanos).toFloat().coerceIn(0f, 1f)
                transitionProgress = mihon.desktop.ui.animation.MotionCurves.easeOutQuint(rawT)
                repaint()
                if (rawT >= 1f) {
                    (e.source as? javax.swing.Timer)?.stop()
                    transitionProgress = 1f
                    repaint()
                }
            }.also { it.start() }
        }

        override fun removeNotify() {
            transitionTimer?.stop()
            transitionTimer = null
            super.removeNotify()
        }

        override fun paint(g: java.awt.Graphics) {
            if (transitionProgress < 0.995f) {
                val g2 = g.create() as java.awt.Graphics2D
                val bg = Theme.BG_APP
                g2.color = bg
                g2.fillRect(0, 0, width, height)
                val slidePx = ((1f - transitionProgress) * 14f * transitionDirection).toDouble()
                g2.translate(slidePx, 0.0)
                super.paint(g2)
                val veilAlpha = ((1f - transitionProgress) * 0.22f * 255f).toInt().coerceIn(0, 255)
                if (veilAlpha > 2) {
                    g2.translate(-slidePx, 0.0)
                    g2.color = Color(bg.red, bg.green, bg.blue, veilAlpha)
                    g2.fillRect(0, 0, width, height)
                }
                g2.dispose()
            } else {
                super.paint(g)
            }
        }
    }
    private var topHeaderBar: JPanel? = null
    private val library = LibraryScreen(
        ctx = ctx,
        onOpenCategories = {
            CategoriesDialog(this, ctx, onCategoryFilter = { showLibrary() }).isVisible = true
        },
        onOpenDownloads = { DownloadsQueueDialog(this, ctx).isVisible = true },
    )
    private val details = DetailsScreen(ctx)
    private val browseHub = BrowseHubScreen(
        ctx = ctx,
        onCatalogVisibilityChanged = { inCatalog ->
            topHeaderBar?.let { bar ->
                bar.isVisible = !inCatalog
                revalidate()
                repaint()
            }
        },
    )
    private val historyScreen = HistoryScreen(ctx)
    private val updatesScreen = UpdatesScreen(ctx)
    private val settingsPanel: AntiGravitySettingsScreen by lazy {
        settingsInitialized = true
        createSettingsScreen()
    }
    private val moreScreen = MoreScreen(
        ctx = ctx,
        onOpenDownloadsQueue = { DownloadsQueueDialog(this, ctx).isVisible = true },
        onOpenCategories = {
            CategoriesDialog(this, ctx, onCategoryFilter = {
                showLibrary()
            }).isVisible = true
        },
        onOpenStatistics = { StatisticsDialog(this, ctx).isVisible = true },
        onOpenBookmarks = { AllBookmarksDialog(this, ctx).isVisible = true },
        onOpenStorageManager = {
            StorageManagerDialog(
                owner = this,
                ctx = ctx,
                dbManager = db,
                onExportBackup = { exportBackup() },
                onImportBackup = { importBackup() },
                onCoversCleared = { library.refresh() },
            ).isVisible = true
        },
        onOpenSettings = { navigate(SETTINGS, highlight = MORE) },
        onOpenSettingsCategory = { categoryId ->
            settingsPanel.openCategory(categoryId)
            navigate(SETTINGS, highlight = MORE)
        },
        onOpenSources = {
            browseHub.openExtensionsTab()
            navigate(BROWSE)
        },
        onOpenAbout = {
            settingsPanel.openCategory("about")
            navigate(SETTINGS, highlight = MORE)
        },
    )
    private val navButtons = linkedMapOf<String, JToggleButton>()
    private var settingsInitialized = false
    private var settingsMounted = false
    private var currentCard: String = ""

    private fun ensureSettingsMounted() {
        if (!settingsMounted) {
            settingsMounted = true
            content.add(settingsPanel, SETTINGS)
        }
    }

    init {
        iconImages = AppIcon.getWindowIcons()
        ctx.navigator = this
        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(1280, 820)
        minimumSize = Dimension(800, 600)
        setLocationRelativeTo(null)

        content.add(library, LIBRARY)
        content.add(details, DETAILS)
        content.add(browseHub, BROWSE)
        content.add(historyScreen, HISTORY)
        content.add(updatesScreen, UPDATES)
        content.add(moreScreen, MORE)

        val headerBar = buildTopHeaderBar()
        topHeaderBar = headerBar
        add(headerBar, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)

        layeredPane.add(toastHost, javax.swing.JLayeredPane.POPUP_LAYER)
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                toastHost.setBounds(0, 0, layeredPane.width, layeredPane.height)
            }
        })
        SwingUtilities.invokeLater {
            toastHost.setBounds(0, 0, layeredPane.width, layeredPane.height)
        }
        installGlobalShortcuts()

        ctx.addThemeListener {
            headerBar.background = Theme.BG_SIDEBAR
            headerBar.border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(5, 16, 5, 16),
            )
            content.background = Theme.BG_APP
            navButtons.values.forEach { it.repaint() }
            repaint()
        }

        showLibrary()
        SystemTrayManager.install(
            mainFrame = this,
            onResumeLastReading = {
                background({ ctx.service.recentHistory().firstOrNull() }) { res ->
                    res.getOrNull()?.let { hist ->
                        showDetails(hist.manga)
                    } ?: showHistory()
                }
            },
            onCheckUpdates = {
                updatesScreen.refresh()
                navigate(UPDATES)
                LibraryUpdateScheduler.checkNow(ctx, silent = false)
            },
            onIncognito = {
                ctx.incognitoMode = !ctx.incognitoMode
                moreScreen.syncIncognito(ctx.incognitoMode)
                ctx.setStatus(if (ctx.incognitoMode) "Modo anônimo ativado (histórico pausado)" else "Modo anônimo desativado")
            },
            onExit = {
                shutdownGracefully()
            },
        )
        // Handle window close: minimize to tray if enabled, otherwise shut down cleanly
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) {
                if (SystemTrayManager.minimizeToTray && SystemTrayManager.isSupported) {
                    SystemTrayManager.minimizeToTray()
                } else {
                    shutdownGracefully()
                }
            }
        })
        LibraryUpdateScheduler.start(ctx)
        isVisible = true
    }

    private fun shutdownGracefully() {
        runCatching { ReaderWindow.activeReader?.takeIf { it.isDisplayable }?.dispose() }
        runCatching { LibraryUpdateScheduler.stop() }
        runCatching { library.dispose() }
        runCatching { details.dispose() }
        runCatching { historyScreen.dispose() }
        runCatching { updatesScreen.dispose() }
        runCatching { browseHub.dispose() }
        if (settingsInitialized) {
            runCatching { settingsPanel.dispose() }
        }
        runCatching { db.checkpoint() }
        dispose()
        System.exit(0)
    }

    private fun installGlobalShortcuts() {
        val rp = rootPane
        fun bind(key: Int, mask: Int, id: String, action: () -> Unit) {
            rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(key, mask), id)
            rp.actionMap.put(id, object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = action()
            })
        }
        val ctrl = java.awt.event.InputEvent.CTRL_DOWN_MASK
        val alt = java.awt.event.InputEvent.ALT_DOWN_MASK
        bind(java.awt.event.KeyEvent.VK_1, ctrl, "nav_1") { showLibrary() }
        bind(java.awt.event.KeyEvent.VK_2, ctrl, "nav_2") { updatesScreen.refresh(); navigate(UPDATES) }
        bind(java.awt.event.KeyEvent.VK_3, ctrl, "nav_3") { showHistory() }
        bind(java.awt.event.KeyEvent.VK_4, ctrl, "nav_4") { browseHub.reload(); navigate(BROWSE) }
        bind(java.awt.event.KeyEvent.VK_5, ctrl, "nav_5") { navigate(MORE) }
        bind(java.awt.event.KeyEvent.VK_LEFT, alt, "nav_back") { navigateBack() }
    }

    private fun navigateBack() {
        if (navigationHistory.isEmpty()) {
            if (currentCard != LIBRARY) showLibrary()
            return
        }
        val prev = navigationHistory.removeLast()
        navigate(prev, recordHistory = false, direction = -1)
    }

    override fun showLibrary() {
        library.refresh()
        navigate(LIBRARY)
    }

    override fun showDetails(manga: Manga) {
        details.show(manga)
        navigate(DETAILS, highlight = LIBRARY)
    }

    override fun showHistory() {
        historyScreen.refresh()
        navigate(HISTORY)
    }

    override fun openSourceCatalog(sourceName: String) {
        navigate(BROWSE)
        browseHub.openSourceByName(sourceName)
    }

    private fun navigate(card: String, highlight: String = card, recordHistory: Boolean = true, direction: Int = 1) {
        if (card == SETTINGS) ensureSettingsMounted()
        val changed = card != currentCard
        if (changed && recordHistory && currentCard.isNotEmpty() && currentCard != DETAILS) {
            if (navigationHistory.size >= 16) navigationHistory.removeFirst()
            navigationHistory.addLast(currentCard)
        }
        val tabOrder = listOf(LIBRARY, UPDATES, HISTORY, BROWSE, MORE, SETTINGS)
        val computedDir = if (direction != 1) {
            direction
        } else if (card == DETAILS) {
            1
        } else if (currentCard == DETAILS) {
            -1
        } else {
            val fromIdx = tabOrder.indexOf(currentCard)
            val toIdx = tabOrder.indexOf(card)
            if (fromIdx >= 0 && toIdx >= 0 && toIdx < fromIdx) -1 else 1
        }
        currentCard = card
        if (card != BROWSE) {
            topHeaderBar?.isVisible = true
        }
        cards.show(content, card)
        navButtons[highlight]?.isSelected = true
        if (changed) content.playTransition(computedDir)
    }

    private fun buildTopHeaderBar(): JPanel {
        val bar = JPanel(FlowLayout(FlowLayout.CENTER, 6, 0)).apply {
            background = Theme.BG_SIDEBAR
            preferredSize = Dimension(0, 46)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(5, 16, 5, 16),
            )
        }

        val group = ButtonGroup()
        val navDefs: List<Triple<String, String, (() -> Color) -> Icon>> = listOf(
            Triple(LIBRARY, "Biblioteca") { color -> ModernIcons.library(16, color) },
            Triple(UPDATES, "Atualizações") { color -> ModernIcons.updates(16, color) },
            Triple(HISTORY, "Histórico") { color -> ModernIcons.history(16, color) },
            Triple(BROWSE, "Navegar") { color -> ModernIcons.browse(16, color) },
            Triple(MORE, "Mais") { color -> ModernIcons.more(16, color) },
        )
        navDefs.forEach { (card, label, iconFactory) ->
            val icon = iconFactory { Theme.TEXT_PRIMARY }
            val button = M3NavPillButton(label, icon).apply {
                preferredSize = Dimension(122, 34)
                addActionListener {
                    when (card) {
                        LIBRARY -> showLibrary()
                        UPDATES -> { updatesScreen.refresh(); navigate(card) }
                        HISTORY -> showHistory()
                        BROWSE -> { browseHub.reload(); navigate(card) }
                        else -> navigate(card)
                    }
                }
            }
            group.add(button)
            navButtons[card] = button
            bar.add(button)
        }
        return bar
    }

    private fun createSettingsScreen(): AntiGravitySettingsScreen = AntiGravitySettingsScreen(
        ctx = ctx,
        prefs = prefs,
        readerPrefs = readerPrefs,
        dbFile = dbFile,
        homeDir = File(home),
        onNavigateBack = { navigate(MORE) },
        onApplyTheme = { preset -> applyTheme(preset) },
        onOpenCustomThemeDialog = { openCustomThemeDialog() },
        onExportBackup = { exportBackup() },
        onImportBackup = { importBackup() },
        onOpenTestLab = { UpscaleComparisonDialog(this@MihonApp, ctx).isVisible = true },
        onToggleSidebarDefault = { _ -> },
        onExitApp = {
            this@MihonApp.dispatchEvent(java.awt.event.WindowEvent(this@MihonApp, java.awt.event.WindowEvent.WINDOW_CLOSING))
        },
    )

    private fun openCustomThemeDialog() {
        val dlg = javax.swing.JDialog(this, "Personalizar Cores do Tema", true)
        dlg.layout = BorderLayout()
        dlg.setSize(480, 420)
        dlg.setLocationRelativeTo(this)

        val root = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Theme.BG_APP
            border = BorderFactory.createEmptyBorder(20, 24, 20, 24)
        }

        val title = JLabel("Tema Personalizado").apply {
            font = font.deriveFont(Font.BOLD, 18f)
            foreground = Theme.TEXT_PRIMARY
            alignmentX = 0f
        }
        dlg.minimumSize = Dimension(420, 300)
        val sub = JLabel("Selecione as cores da interface ao seu gosto:").apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
            alignmentX = 0f
        }
        root.add(title)
        root.add(Box.createVerticalStrut(4))
        root.add(sub)
        root.add(Box.createVerticalStrut(18))

        var curBgApp = Theme.BG_APP
        var curBgSidebar = Theme.BG_SIDEBAR
        var curBgSurface = Theme.BG_SURFACE
        var curAccent = Theme.ACCENT
        var curTextPrimary = Theme.TEXT_PRIMARY

        fun createColorRow(label: String, initialColor: Color, onPicked: (Color) -> Unit): JPanel {
            val row = JPanel(BorderLayout(12, 0)).apply {
                isOpaque = false
                maximumSize = Dimension(Int.MAX_VALUE, 36)
                alignmentX = 0f
            }
            val lbl = JLabel(label).apply { foreground = Theme.TEXT_PRIMARY; font = font.deriveFont(13f) }
            val preview = object : JPanel() {
                var color: Color = initialColor
                override fun paintComponent(g: java.awt.Graphics) {
                    super.paintComponent(g)
                    val g2 = g as java.awt.Graphics2D
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = color
                    g2.fillRoundRect(2, 2, width - 4, height - 4, 10, 10)
                    g2.color = Color(255, 255, 255, 70)
                    g2.drawRoundRect(2, 2, width - 4, height - 4, 10, 10)
                }
            }.apply {
                preferredSize = Dimension(42, 26)
                isOpaque = false
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            }

            val pickBtn = ModernButton("Escolher", variant = ModernButton.Variant.SURFACE, compact = true).apply {
                addActionListener {
                    val picked = javax.swing.JColorChooser.showDialog(dlg, "Escolher $label", preview.color)
                    if (picked != null) {
                        preview.color = picked
                        preview.repaint()
                        onPicked(picked)
                    }
                }
            }

            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(preview)
                add(pickBtn)
            }
            row.add(lbl, BorderLayout.WEST)
            row.add(right, BorderLayout.EAST)
            return row
        }

        root.add(createColorRow("Fundo do Aplicativo", curBgApp) { curBgApp = it })
        root.add(Box.createVerticalStrut(8))
        root.add(createColorRow("Barra Lateral", curBgSidebar) { curBgSidebar = it })
        root.add(Box.createVerticalStrut(8))
        root.add(createColorRow("Superfície dos Cartões", curBgSurface) { curBgSurface = it })
        root.add(Box.createVerticalStrut(8))
        root.add(createColorRow("Cor de Destaque / Acentos", curAccent) { curAccent = it })
        root.add(Box.createVerticalStrut(8))
        root.add(createColorRow("Texto Principal", curTextPrimary) { curTextPrimary = it })
        root.add(Box.createVerticalStrut(24))

        val btnRow = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply {
            isOpaque = false
            alignmentX = 0f
            val cancel = ModernButton("Cancelar", variant = ModernButton.Variant.SURFACE).apply {
                addActionListener { dlg.dispose() }
            }
            val save = ModernButton("Aplicar Tema", variant = ModernButton.Variant.PRIMARY).apply {
                addActionListener {
                    applyCustomTheme(curBgApp, curBgSidebar, curBgSurface, curAccent, curTextPrimary)
                    dlg.dispose()
                }
            }
            add(cancel)
            add(save)
        }
        root.add(btnRow)

        dlg.add(root)
        dlg.isVisible = true
    }

    private fun exportBackup() {
        val chooser = JFileChooser().apply {
            selectedFile = File(home, "mihon-backup.zip")
            fileFilter = FileNameExtensionFilter("Arquivo ZIP (.zip)", "zip")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val dest = chooser.selectedFile.let { if (it.name.endsWith(".zip", true)) it else File("${it.absolutePath}.zip") }
        runCatching {
            BackupManager.exportDatabase(dbFile, dest)
            ctx.setStatus("Backup exportado para ${dest.name}")
            JOptionPane.showMessageDialog(this, "Backup salvo com sucesso em:\n${dest.absolutePath}", "Backup", JOptionPane.INFORMATION_MESSAGE)
        }.onFailure {
            JOptionPane.showMessageDialog(this, "Falha ao exportar backup:\n${it.message}", "Erro", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun importBackup() {
        val chooser = JFileChooser().apply {
            fileFilter = FileNameExtensionFilter("Arquivo ZIP (.zip)", "zip")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val src = chooser.selectedFile
        val ok = JOptionPane.showConfirmDialog(
            this,
            "Restaurar o backup de '${src.name}' substituirá a biblioteca atual.\nDeseja continuar?",
            "Restaurar Backup",
            JOptionPane.YES_NO_OPTION,
        )
        if (ok != JOptionPane.YES_OPTION) return
        runCatching {
            db.checkpoint()
            BackupManager.restoreDatabase(src, dbFile)
            db.initialize()
            showLibrary()
            ctx.setStatus("Backup restaurado com sucesso")
        }.onFailure {
            JOptionPane.showMessageDialog(this, "Falha ao restaurar backup:\n${it.message}", "Erro", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun menuBar() = JMenuBar().apply {
        add(
            JMenu("Arquivo").apply {
                add(JMenuItem("Exportar Backup…").apply { addActionListener { exportBackup() } })
                add(JMenuItem("Restaurar Backup…").apply { addActionListener { importBackup() } })
                addSeparator()
                add(
                    JMenuItem("Sair").apply {
                        addActionListener {
                            this@MihonApp.dispatchEvent(java.awt.event.WindowEvent(this@MihonApp, java.awt.event.WindowEvent.WINDOW_CLOSING))
                        }
                    },
                )
            },
        )
        add(
            JMenu("Ajuda").apply {
                add(
                    JMenuItem("Sobre").apply {
                        addActionListener {
                            JOptionPane.showMessageDialog(
                                this@MihonApp,
                                "Mihon Linux v0.4.0\nLeitor de Mangá Desktop (CBZ, ZIP, Pastas e Fontes Online)\nKotlin + Exposed/SQLite + FlatLaf Swing",
                            )
                        }
                    },
                )
            },
        )
    }

    private companion object {
        const val LIBRARY = "library"
        const val DETAILS = "details"
        const val BROWSE = "browse"
        const val UPDATES = "updates"
        const val HISTORY = "history"
        const val MORE = "more"
        const val SETTINGS = "settings"
    }
}

/**
 * Material 3 Switch Component matching Android Mihon:
 * Sleek stadium pill track with animated thumb sliding, size morphing (14px -> 22px),
 * smooth color interpolation, and ease-out motion.
 */
class M3Switch(
    initialState: Boolean = false,
    private val onToggle: (Boolean) -> Unit = {},
) : JComponent() {
    private var animProgress: Float = if (initialState) 1f else 0f
    private var animTimer: javax.swing.Timer? = null

    var isSelected: Boolean = initialState
        set(value) {
            if (field != value) {
                field = value
                animateTo(if (value) 1f else 0f)
                onToggle(value)
            }
        }

    private var isHovered: Boolean = false

    private fun animateTo(target: Float) {
        animTimer?.stop()
        val durationMs = mihon.desktop.ui.animation.MotionPreferences.effectiveDurationMs(180)
        if (durationMs <= 0L) {
            animProgress = target
            repaint()
            return
        }
        val start = animProgress
        val startNanos = System.nanoTime()
        val durationNanos = durationMs * 1_000_000.0

        animTimer = javax.swing.Timer(12) { e ->
            val rawT = ((System.nanoTime() - startNanos) / durationNanos).toFloat().coerceIn(0f, 1f)
            val eased = easeOutCubic(rawT)
            animProgress = (start + (target - start) * eased).coerceIn(0f, 1f)
            repaint()
            if (rawT >= 1f) {
                (e.source as? javax.swing.Timer)?.stop()
                animProgress = target
                repaint()
            }
        }.also { it.start() }
    }

    override fun removeNotify() {
        animTimer?.stop()
        animTimer = null
        super.removeNotify()
    }

    init {
        isOpaque = false
        isFocusable = true
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        preferredSize = Dimension(48, 28)
        minimumSize = Dimension(48, 28)
        maximumSize = Dimension(48, 28)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                isHovered = true
                repaint()
            }
            override fun mouseExited(e: MouseEvent) {
                isHovered = false
                repaint()
            }
            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                isSelected = !isSelected
            }
        })
        addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_SPACE || e.keyCode == java.awt.event.KeyEvent.VK_ENTER) {
                    isSelected = !isSelected
                    e.consume()
                }
            }
        })
        addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent?) { repaint() }
            override fun focusLost(e: java.awt.event.FocusEvent?) { repaint() }
        })
    }

    private fun blendColor(c1: Color, c2: Color, ratio: Float): Color {
        val r = ratio.coerceIn(0f, 1f)
        val inv = 1f - r
        val red = (c1.red * inv + c2.red * r).toInt().coerceIn(0, 255)
        val green = (c1.green * inv + c2.green * r).toInt().coerceIn(0, 255)
        val blue = (c1.blue * inv + c2.blue * r).toInt().coerceIn(0, 255)
        val alpha = (c1.alpha * inv + c2.alpha * r).toInt().coerceIn(0, 255)
        return Color(red, green, blue, alpha)
    }

    override fun paintComponent(g: java.awt.Graphics) {
        val g2 = g.create() as java.awt.Graphics2D
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)

        val trackW = 46
        val trackH = 26
        val trackX = (width - trackW) / 2
        val trackY = (height - trackH) / 2
        val arc = trackH

        val p = animProgress // 0f (off) -> 1f (on)

        // Libadwaita stadium track (solid #4E4E53 when OFF without bright border ring, #4F46E5 Cobalt when ON)
        val offTrack = if (Theme.isDark) {
            if (isHovered) Color(0x58, 0x58, 0x5E) else Color(0x4E, 0x4E, 0x53)
        } else {
            if (isHovered) Color(0xC6, 0xC8, 0xD2) else Color(0xD4, 0xD6, 0xDF)
        }
        val currentTrackBg = blendColor(offTrack, Theme.PRIMARY, p)

        g2.color = currentTrackBg
        g2.fillRoundRect(trackX, trackY, trackW, trackH, arc, arc)

        // Large 20px thumb with 3px margin from track edge (matching Foto 5)
        val thumbD = 20f
        val startThumbX = trackX + 3f
        val endThumbX = (trackX + trackW - thumbD - 3f)
        val thumbX = startThumbX + (endThumbX - startThumbX) * p
        val thumbY = trackY + (trackH - thumbD) / 2f

        val offThumbColor = if (Theme.isDark) Color(0xD4, 0xD3, 0xD8) else Color.WHITE
        val currentThumbColor = blendColor(offThumbColor, Color.WHITE, p)

        g2.color = currentThumbColor
        g2.fill(java.awt.geom.Ellipse2D.Float(thumbX, thumbY, thumbD, thumbD))

        g2.dispose()
    }
}

/**
 * Material 3 / Libadwaita Top ViewSwitcher Pill Button (matching Fotos 4 & 5).
 */
class M3NavPillButton(
    val labelText: String,
    val navIcon: Icon,
    private val badgeText: String? = null,
) : JToggleButton() {

    var isCollapsed: Boolean = false
        set(value) {
            field = value
            toolTipText = if (value) labelText else null
        }

    var collapseProgress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            repaint()
        }

    private var hoverProgress = 0f
    private var activeProgress = 0f
    private val animTimer = javax.swing.Timer(14) {
        val targetHover = if (model.isRollover && !isSelected) 1f else 0f
        val targetActive = if (isSelected) 1f else 0f
        hoverProgress += (targetHover - hoverProgress) * 0.25f
        activeProgress += (targetActive - activeProgress) * 0.28f
        if (kotlin.math.abs(targetHover - hoverProgress) < 0.01f &&
            kotlin.math.abs(targetActive - activeProgress) < 0.01f
        ) {
            hoverProgress = targetHover
            activeProgress = targetActive
            (it.source as javax.swing.Timer).stop()
        }
        repaint()
    }

    init {
        isOpaque = false
        isFocusable = false
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        border = null
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        toolTipText = null
        model.addChangeListener {
            if (!isDisplayable) {
                hoverProgress = if (model.isRollover && !isSelected) 1f else 0f
                activeProgress = if (isSelected) 1f else 0f
                repaint()
            } else if (!animTimer.isRunning) {
                animTimer.start()
            }
        }
    }

    override fun addNotify() {
        super.addNotify()
        hoverProgress = if (model.isRollover && !isSelected) 1f else 0f
        activeProgress = if (isSelected) 1f else 0f
    }

    override fun removeNotify() {
        animTimer.stop()
        super.removeNotify()
    }

    override fun paintBorder(g: java.awt.Graphics) {
        // Handled in paintComponent
    }

    override fun paintComponent(g: java.awt.Graphics) {
        val g2 = g.create() as java.awt.Graphics2D
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        val w = width
        val h = height

        val pillX = 1
        val pillY = 1
        val pillW = w - 2
        val pillH = h - 2
        val arc = Theme.CONTROL_RADIUS * 2

        if (hoverProgress > 0.01f && activeProgress < 0.99f) {
            val hc = Theme.BG_CARD_HOVER
            g2.color = Color(hc.red, hc.green, hc.blue, (255 * hoverProgress * (1f - activeProgress)).toInt().coerceIn(0, 255))
            g2.fillRoundRect(pillX, pillY, pillW, pillH, arc, arc)
        }
        if (activeProgress > 0.01f) {
            val ac = Theme.BG_PILL_ACTIVE
            g2.color = Color(ac.red, ac.green, ac.blue, (ac.alpha * activeProgress).toInt().coerceIn(0, 255))
            g2.fillRoundRect(pillX, pillY, pillW, pillH, arc, arc)
        }

        val iconW = navIcon.iconWidth
        val iconH = navIcon.iconHeight
        val iconY = (h - iconH) / 2

        g2.font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 13f)
        val fm = g2.fontMetrics
        val textW = fm.stringWidth(labelText)
        val gap = 8
        val totalContentW = iconW + gap + textW
        val centeredStartX = ((w - totalContentW) / 2f).coerceAtLeast(8f)
        val collapsedIconX = (w - iconW) / 2f
        val iconX = (centeredStartX + (collapsedIconX - centeredStartX) * collapseProgress).toInt()
        navIcon.paintIcon(this, g2, iconX, iconY)

        val textAlpha = ((1f - collapseProgress * 1.6f).coerceIn(0f, 1f) * 255).toInt()
        if (textAlpha > 4) {
            val baseColor = Theme.TEXT_PRIMARY
            g2.color = Color(baseColor.red, baseColor.green, baseColor.blue, textAlpha)
            val textX = iconX + iconW + gap
            val textY = ((h - fm.height) / 2) + fm.ascent
            g2.drawString(labelText, textX, textY)

            if (!badgeText.isNullOrBlank()) {
                g2.font = Theme.FONT_MONO.deriveFont(Font.BOLD, 10.5f)
                val bFm = g2.fontMetrics
                val bw = (bFm.stringWidth(badgeText) + 12).coerceAtLeast(20)
                val bh = 18
                val bx = w - bw - 12
                val by = (h - bh) / 2
                val badgeBgAlpha = (((25f + 20f * activeProgress)) * (textAlpha / 255f)).toInt()
                g2.color = if (Theme.isDark) Color(255, 255, 255, badgeBgAlpha) else Color(0, 0, 0, badgeBgAlpha)
                g2.fillRoundRect(bx, by, bw, bh, Theme.BADGE_RADIUS * 2, Theme.BADGE_RADIUS * 2)
                g2.color = Color(Theme.TEXT_PRIMARY.red, Theme.TEXT_PRIMARY.green, Theme.TEXT_PRIMARY.blue, textAlpha)
                g2.drawString(badgeText, bx + (bw - bFm.stringWidth(badgeText)) / 2, by + ((bh - bFm.height) / 2) + bFm.ascent)
            }
        }
        g2.dispose()
    }
}

/**
 * Material 3 "Navegar" Hub (BrowseScreen):
 * Top TabRow with [Fontes], [Extensões (N)], and [Migrar] tabs, matching Mihon Android's Extensoes.jpeg.
 */
class BrowseHubScreen(
    private val ctx: AppContext,
    private val onCatalogVisibilityChanged: (Boolean) -> Unit = {},
) : JPanel(BorderLayout()) {
    private val cards = CardLayout()
    private val subContent = JPanel(cards)

    private val sourcesCards = CardLayout()
    private val sourcesHub = JPanel(sourcesCards)

    private val searchScreen = SearchScreen(ctx, onBackToSources = {
        sourcesCards.show(sourcesHub, "sources_list")
        tabStrip.isVisible = true
        onCatalogVisibilityChanged(false)
        revalidate()
        repaint()
    })

    private val sourcesListScreen = SourcesListScreen(
        ctx = ctx,
        onSelectSource = { ext ->
            searchScreen.openSource(ext.name)
            sourcesCards.show(sourcesHub, "catalog")
            tabStrip.isVisible = false
            onCatalogVisibilityChanged(true)
            revalidate()
            repaint()
        },
        onSelectLocalSource = {
            ctx.navigator.showLibrary()
            ctx.setStatus("Exibindo biblioteca local")
        },
    )

    private val extensionsScreen = ExtensionsScreen(ctx)
    private var extCount = ctx.extensions.size
    private val tabStrip = BrowseTabStrip()

    init {
        background = Theme.BG_APP
        subContent.background = Theme.BG_APP

        sourcesHub.background = Theme.BG_APP
        sourcesHub.add(sourcesListScreen, "sources_list")
        sourcesHub.add(searchScreen, "catalog")

        subContent.add(sourcesHub, "sources")
        subContent.add(extensionsScreen, "extensions")
        subContent.add(migratePlaceholder(), "migrate")

        add(tabStrip, BorderLayout.NORTH)
        add(subContent, BorderLayout.CENTER)

        ctx.addThemeListener {
            background = Theme.BG_APP
            subContent.background = Theme.BG_APP
            sourcesHub.background = Theme.BG_APP
            tabStrip.background = Theme.BG_APP
            tabStrip.repaint()
            repaint()
        }

        ctx.addExtensionListener {
            extCount = ctx.extensions.size
            tabStrip.repaint()
            sourcesListScreen.rebuildList()
        }
    }

    private inner class BrowseTabStrip : JPanel() {
        private val tabs = listOf(
            Triple("sources", "Fontes", ModernIcons.browse(16) { Theme.TEXT_PRIMARY }),
            Triple("extensions", "Extensões", ModernIcons.plug(16) { Theme.TEXT_PRIMARY }),
            Triple("migrate", "Migrar", ModernIcons.sync(16) { Theme.TEXT_PRIMARY }),
        )
        private var activeCard = "sources"
        private var hoveredIdx = -1
        private val tabRects = Array(3) { java.awt.Rectangle() }
        private var indX = -1f
        private var indW = -1f
        private var indTimer: javax.swing.Timer? = null

        init {
            background = Theme.BG_APP
            preferredSize = Dimension(0, 46)
            addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val idx = tabRects.indexOfFirst { it.contains(e.point) }
                    cursor = if (idx >= 0) java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR) else java.awt.Cursor.getDefaultCursor()
                    if (idx != hoveredIdx) {
                        hoveredIdx = idx
                        repaint()
                    }
                }
            })
            addMouseListener(object : MouseAdapter() {
                override fun mouseExited(e: MouseEvent) {
                    if (hoveredIdx != -1) {
                        hoveredIdx = -1
                        repaint()
                    }
                }
                override fun mousePressed(e: MouseEvent) {
                    val idx = tabRects.indexOfFirst { it.contains(e.point) }
                    if (idx >= 0) selectTab(tabs[idx].first)
                }
            })
        }

        override fun removeNotify() {
            indTimer?.stop()
            indTimer = null
            super.removeNotify()
        }

        fun selectTab(card: String) {
            if (card == "sources") sourcesCards.show(sourcesHub, "sources_list")
            cards.show(subContent, card)
            if (activeCard == card) return
            activeCard = card
            repaint()
        }

        override fun paintComponent(g: java.awt.Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as java.awt.Graphics2D
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

            g2.color = Theme.BORDER_SUBTLE
            g2.drawLine(0, height - 1, width, height - 1)

            val h = height
            val spacing = 8
            val widths = IntArray(tabs.size)
            for (i in tabs.indices) {
                val (_, label, icon) = tabs[i]
                g2.font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 13f)
                val lw = g2.fontMetrics.stringWidth(label)
                val badgeW = if (tabs[i].first == "extensions") {
                    g2.font = Theme.FONT_MONO.deriveFont(Font.BOLD, 10.5f)
                    g2.fontMetrics.stringWidth("$extCount") + 18
                } else {
                    0
                }
                widths[i] = 14 + icon.iconWidth + 8 + lw + badgeW + 14
            }
            val totalW = widths.sum() + spacing * (tabs.size - 1)
            var x = ((width - totalW) / 2).coerceAtLeast(16)
            val arc = Theme.CONTROL_RADIUS * 2

            for (i in tabs.indices) {
                val (card, label, icon) = tabs[i]
                val w = widths[i]
                tabRects[i].setBounds(x, 6, w, h - 12)
                val isAct = activeCard == card
                val isHov = hoveredIdx == i

                if (isAct) {
                    g2.color = Theme.BG_PILL_ACTIVE
                    g2.fillRoundRect(x, 6, w, h - 12, arc, arc)
                } else if (isHov) {
                    g2.color = Theme.BG_CARD_HOVER
                    g2.fillRoundRect(x, 6, w, h - 12, arc, arc)
                }

                icon.paintIcon(this, g2, x + 14, (h - icon.iconHeight) / 2)

                g2.font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 13f)
                g2.color = Theme.TEXT_PRIMARY
                val fm = g2.fontMetrics
                val textX = x + 14 + icon.iconWidth + 8
                g2.drawString(label, textX, (h - fm.height) / 2 + fm.ascent)

                if (card == "extensions") {
                    val bText = "$extCount"
                    g2.font = Theme.FONT_MONO.deriveFont(Font.BOLD, 10.5f)
                    val bFm = g2.fontMetrics
                    val bw = bFm.stringWidth(bText) + 12
                    val bx = textX + fm.stringWidth(label) + 6
                    val by = (h - 18) / 2
                    g2.color = if (Theme.isDark) Color(255, 255, 255, 28) else Color(22, 23, 34, 18)
                    g2.fillRoundRect(bx, by, bw, 18, Theme.BADGE_RADIUS * 2, Theme.BADGE_RADIUS * 2)
                    g2.color = Theme.TEXT_PRIMARY
                    g2.drawString(bText, bx + 6, by + (18 - bFm.height) / 2 + bFm.ascent)
                }

                x += w + spacing
            }
            g2.dispose()
        }
    }

    private fun migratePlaceholder(): JPanel = JPanel(java.awt.GridBagLayout()).apply {
        background = Theme.BG_APP
        val iconLbl = JLabel(ModernIcons.sync(44) { Theme.TEXT_MUTED }).apply {
            alignmentX = 0.5f
        }
        val titleLbl = JLabel("Migrar Fontes", SwingConstants.CENTER).apply {
            font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 18f)
            foreground = Theme.TEXT_PRIMARY
            alignmentX = 0.5f
        }
        val descLbl = JLabel("Transfira mangás favoritados de uma fonte para outra fonte ativa.", SwingConstants.CENTER).apply {
            font = Theme.FONT_UI
            foreground = Theme.TEXT_MUTED
            alignmentX = 0.5f
        }
        val p = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(iconLbl)
            add(Box.createVerticalStrut(10))
            add(titleLbl)
            add(Box.createVerticalStrut(6))
            add(descLbl)
        }
        add(p)
        ctx.addThemeListener {
            background = Theme.BG_APP
            titleLbl.foreground = Theme.TEXT_PRIMARY
            descLbl.foreground = Theme.TEXT_MUTED
            iconLbl.repaint()
            repaint()
        }
    }

    fun reload() {
        extCount = ctx.extensions.size
        tabStrip.isVisible = true
        onCatalogVisibilityChanged(false)
        sourcesCards.show(sourcesHub, "sources_list")
        tabStrip.repaint()
        sourcesListScreen.rebuildList()
    }

    fun openExtensionsTab() {
        extCount = ctx.extensions.size
        tabStrip.isVisible = true
        onCatalogVisibilityChanged(false)
        tabStrip.selectTab("extensions")
    }

    fun openSourceByName(sourceName: String) {
        val ext = ctx.extensions.firstOrNull { it.name.equals(sourceName, ignoreCase = true) || it.name.startsWith(sourceName, ignoreCase = true) }
        if (ext != null) {
            tabStrip.selectTab("sources")
            searchScreen.openSource(ext.name)
            sourcesCards.show(sourcesHub, "catalog")
            tabStrip.isVisible = false
            onCatalogVisibilityChanged(true)
            revalidate()
            repaint()
        }
    }

    fun dispose() {
        searchScreen.dispose()
        sourcesListScreen.dispose()
        extensionsScreen.dispose()
    }
}

/**
 * Mihon Index "Mais" Screen (MoreScreen) matching Foto 3:
 * Top standalone `Modo anônimo` boxed-list card, followed by `Configurações` header
 * and a single unified 8-row `SettingsGroupCard`.
 */
class MoreScreen(
    private val ctx: AppContext,
    private val onOpenDownloadsQueue: () -> Unit,
    private val onOpenCategories: () -> Unit,
    private val onOpenStatistics: () -> Unit,
    private val onOpenBookmarks: () -> Unit = {},
    private val onOpenStorageManager: () -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onOpenSettingsCategory: (String) -> Unit = { onOpenSettings() },
    private val onOpenSources: () -> Unit = {},
    private val onOpenAbout: () -> Unit,
) : JPanel(BorderLayout()) {

    private val themeUpdaters = mutableListOf<() -> Unit>()

    private val incognitoEmojiBadge = mihon.desktop.ui.components.IncognitoEmojiBadge(
        initialIncognito = ctx.incognitoMode,
        sizePx = 42,
    )

    private val incognitoSwitch = M3Switch(ctx.incognitoMode) { enabled ->
        ctx.incognitoMode = enabled
        incognitoEmojiBadge.isIncognito = enabled
        ctx.setStatus(if (enabled) "Modo anônimo ativado" else "Modo anônimo desativado")
    }

    fun syncIncognito(enabled: Boolean) {
        if (incognitoSwitch.isSelected != enabled) {
            incognitoSwitch.isSelected = enabled
        }
        incognitoEmojiBadge.isIncognito = enabled
    }

    init {
        background = Theme.BG_APP

        val content = JPanel().apply {
            background = Theme.BG_APP
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(18, 28, 28, 28)
        }

        fun createInteractiveRow(
            icon: Icon? = null,
            leadingComponent: JComponent? = null,
            title: String,
            desc: String,
            rightComponent: JComponent,
            onClick: () -> Unit,
        ): JPanel {
            var hovered = false
            val titleLbl = JLabel(title).apply {
                font = Theme.FONT_UI.deriveFont(Font.BOLD, 13.5f)
                foreground = Theme.TEXT_PRIMARY
                alignmentX = 0f
            }
            val descLbl = JLabel(desc).apply {
                font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12f)
                foreground = Theme.TEXT_SECONDARY
                alignmentX = 0f
            }
            val leadView: JComponent = leadingComponent ?: JLabel(icon)

            val row = object : JPanel(BorderLayout(12, 0)) {
                override fun paintComponent(g: java.awt.Graphics) {
                    if (hovered) {
                        val g2 = g.create() as java.awt.Graphics2D
                        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.color = Theme.BG_CARD_HOVER
                        g2.fillRoundRect(4, 2, width - 8, height - 4, Theme.CONTROL_RADIUS * 2, Theme.CONTROL_RADIUS * 2)
                        g2.dispose()
                    }
                    super.paintComponent(g)
                }
            }.apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(13, 4, 13, 18)
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)

                val left = JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    border = BorderFactory.createEmptyBorder(0, 14, 0, 0)
                    add(leadView)
                    add(Box.createHorizontalStrut(14))
                    add(
                        JPanel().apply {
                            isOpaque = false
                            layout = BoxLayout(this, BoxLayout.Y_AXIS)
                            add(titleLbl)
                            add(Box.createVerticalStrut(3))
                            add(descLbl)
                        },
                    )
                }

                val rightWrap = JPanel(java.awt.GridBagLayout()).apply {
                    isOpaque = false
                    add(rightComponent)
                }

                add(left, BorderLayout.WEST)
                add(rightWrap, BorderLayout.EAST)

                val rowRef = this
                fun bindMouse(c: java.awt.Component) {
                    c.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                    c.addMouseListener(object : MouseAdapter() {
                        override fun mouseEntered(e: MouseEvent) {
                            if (!hovered) {
                                hovered = true
                                rowRef.repaint()
                            }
                        }
                        override fun mouseExited(e: MouseEvent) {
                            val pt = javax.swing.SwingUtilities.convertPoint(c, e.point, rowRef)
                            if (!rowRef.contains(pt)) {
                                hovered = false
                                rowRef.repaint()
                            }
                        }
                        override fun mousePressed(e: MouseEvent) {
                            if (c !== rightComponent || rightComponent !is M3Switch) {
                                onClick()
                            }
                        }
                    })
                    if (c is java.awt.Container && c !== rightComponent) {
                        c.components.forEach { bindMouse(it) }
                    }
                }
                bindMouse(this)
            }

            themeUpdaters.add {
                titleLbl.foreground = Theme.TEXT_PRIMARY
                descLbl.foreground = Theme.TEXT_SECONDARY
                leadView.repaint()
            }
            return row
        }

        // Top standalone boxed-list card: Modo anônimo (Incognito mode) with expressive animated emoji
        val incognitoCard = SettingsGroupCard()
        incognitoCard.addRow(
            createInteractiveRow(
                leadingComponent = incognitoEmojiBadge,
                title = "Modo anônimo",
                desc = "A leitura não registra histórico, página salva ou atualização no rastreador",
                rightComponent = incognitoSwitch,
                onClick = { incognitoSwitch.isSelected = !incognitoSwitch.isSelected },
            ),
        )
        content.add(incognitoCard)
        content.add(Box.createVerticalStrut(18))

        // Group header: Configurações (Settings)
        val settingsTitle = JLabel("Configurações").apply {
            font = Theme.FONT_UI.deriveFont(Font.BOLD, 14f)
            foreground = Theme.TEXT_PRIMARY
            alignmentX = 0f
            border = BorderFactory.createEmptyBorder(0, 2, 10, 0)
        }
        content.add(settingsTitle)
        themeUpdaters.add { settingsTitle.foreground = Theme.TEXT_PRIMARY }

        // Single unified 8-row SettingsGroupCard matching Foto 5
        val settingsCard = SettingsGroupCard()
        settingsCard.addRow(
            createInteractiveRow(
                icon = ModernIcons.readerPages(20) { Theme.TEXT_PRIMARY },
                title = "Leitor",
                desc = "Direção, layout, plano de fundo",
                rightComponent = JLabel(ModernIcons.chevronRight(16) { Theme.TEXT_SECONDARY }),
                onClick = { onOpenSettingsCategory("reader") },
            ),
        )
        settingsCard.addRow(
            createInteractiveRow(
                icon = ModernIcons.palette(20) { Theme.TEXT_PRIMARY },
                title = "Aparência",
                desc = "Tema, cores e animações",
                rightComponent = JLabel(ModernIcons.chevronRight(16) { Theme.TEXT_SECONDARY }),
                onClick = { onOpenSettingsCategory("appearance") },
            ),
        )
        settingsCard.addRow(
            createInteractiveRow(
                icon = ModernIcons.libraryTag(20) { Theme.TEXT_PRIMARY },
                title = "Biblioteca",
                desc = "Atualizações, emblemas e notificações",
                rightComponent = JLabel(ModernIcons.chevronRight(16) { Theme.TEXT_SECONDARY }),
                onClick = { onOpenSettingsCategory("general") },
            ),
        )
        settingsCard.addRow(
            createInteractiveRow(
                icon = ModernIcons.downloads(20) { Theme.TEXT_PRIMARY },
                title = "Downloads",
                desc = "Fila, armazenamento e cache",
                rightComponent = JLabel(ModernIcons.chevronRight(16) { Theme.TEXT_SECONDARY }),
                onClick = onOpenStorageManager,
            ),
        )
        settingsCard.addRow(
            createInteractiveRow(
                icon = ModernIcons.storage(20) { Theme.TEXT_PRIMARY },
                title = "Backup e Restauração",
                desc = "Exportar e importar arquivos .zip",
                rightComponent = JLabel(ModernIcons.chevronRight(16) { Theme.TEXT_SECONDARY }),
                onClick = { onOpenSettingsCategory("storage") },
            ),
        )
        settingsCard.addRow(
            createInteractiveRow(
                icon = ModernIcons.browse(20) { Theme.TEXT_PRIMARY },
                title = "Fontes",
                desc = "Pasta local, repositórios e extensões",
                rightComponent = JLabel(ModernIcons.chevronRight(16) { Theme.TEXT_SECONDARY }),
                onClick = onOpenSources,
            ),
        )
        settingsCard.addRow(
            createInteractiveRow(
                icon = ModernIcons.statistics(20) { Theme.TEXT_PRIMARY },
                title = "Estatísticas",
                desc = "Hábitos de leitura, Mihon Wrapped e recordes",
                rightComponent = JLabel(ModernIcons.chevronRight(16) { Theme.TEXT_SECONDARY }),
                onClick = onOpenStatistics,
            ),
        )
        settingsCard.addRow(
            createInteractiveRow(
                icon = ModernIcons.about(20) { Theme.TEXT_PRIMARY },
                title = "Sobre",
                desc = "Versão 0.4.0 e informações",
                rightComponent = JLabel(ModernIcons.chevronRight(16) { Theme.TEXT_SECONDARY }),
                onClick = onOpenAbout,
            ),
        )
        content.add(settingsCard)

        val wrapper = JPanel(BorderLayout()).apply {
            background = Theme.BG_APP
            add(content, BorderLayout.NORTH)
        }
        val scroll = JScrollPane(wrapper).apply {
            border = null
            background = Theme.BG_APP
            viewport.background = Theme.BG_APP
            verticalScrollBar.unitIncrement = 24
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }
        add(scroll, BorderLayout.CENTER)

        ctx.addThemeListener {
            background = Theme.BG_APP
            content.background = Theme.BG_APP
            wrapper.background = Theme.BG_APP
            scroll.background = Theme.BG_APP
            scroll.viewport.background = Theme.BG_APP
            incognitoSwitch.repaint()
            themeUpdaters.forEach { it.invoke() }
            repaint()
        }
    }
}

/** Handles atomic ZIP backup and restoration of the SQLite database. */
object BackupManager {
    private const val DB_ENTRY = "mihon.db"

    fun exportDatabase(dbFile: File, targetZip: File) {
        require(dbFile.exists()) { "Banco de dados não encontrado: ${dbFile.absolutePath}" }
        targetZip.parentFile?.mkdirs()
        ZipOutputStream(targetZip.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(DB_ENTRY))
            dbFile.inputStream().buffered().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    fun restoreDatabase(sourceZip: File, dbFile: File) {
        require(sourceZip.exists()) { "Arquivo de backup não encontrado: ${sourceZip.absolutePath}" }
        ZipFile(sourceZip).use { zip ->
            val entry = zip.getEntry(DB_ENTRY)
                ?: throw IllegalArgumentException("Backup inválido: '$DB_ENTRY' não encontrado no arquivo ZIP")
            val tmp = File(dbFile.parentFile, "${dbFile.name}.restore.tmp")
            zip.getInputStream(entry).use { input ->
                tmp.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            // Remove stale WAL/SHM files before replacing main DB file
            File("${dbFile.absolutePath}-wal").delete()
            File("${dbFile.absolutePath}-shm").delete()
            tmp.copyTo(dbFile, overwrite = true)
            tmp.delete()
        }
    }
}
