package mihon.desktop

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import mihon.data.database.DatabaseManager
import mihon.data.extension.ExtensionLoader
import mihon.data.repository.ChapterRepositoryImpl
import mihon.data.repository.HistoryRepositoryImpl
import mihon.data.repository.MangaRepositoryImpl
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
import javax.swing.JToggleButton
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.plaf.ColorUIResource

private const val PREF_DARK = "darkTheme"
private const val PREF_PRESET = "themePreset"
private const val PREF_CUSTOM_BG = "customBgApp"
private const val PREF_CUSTOM_SIDEBAR = "customBgSidebar"
private const val PREF_CUSTOM_SURFACE = "customBgSurface"
private const val PREF_CUSTOM_ACCENT = "customAccent"
private const val PREF_CUSTOM_TEXT = "customTextPrimary"
private val prefs: Preferences = Preferences.userRoot().node("mihon-linux")
private val readerPrefs: Preferences = Preferences.userRoot().node("mihon-linux-reader")

internal fun configureTheme(presetName: String? = null) {
    val chosenName = presetName ?: prefs.get(PREF_PRESET, if (prefs.getBoolean(PREF_DARK, true)) "AMOLED" else "LIGHT")
    val preset = runCatching { Theme.ThemePreset.valueOf(chosenName) }.getOrDefault(Theme.ThemePreset.AMOLED)

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
    UIManager.put("Component.arc", 999) // M3 Pill shapes
    UIManager.put("Button.arc", 999)
    UIManager.put("TextComponent.arc", 16)
    UIManager.put("ProgressBar.arc", 999)
    UIManager.put("ScrollBar.thumbArc", 999)
    UIManager.put("ScrollBar.width", 10)
}

internal fun applyTheme(preset: Theme.ThemePreset) {
    prefs.put(PREF_PRESET, preset.name)
    prefs.putBoolean(PREF_DARK, preset.isDark)
    configureTheme(preset.name)

    fun resetComponentStyles(comp: java.awt.Component) {
        if (comp is JButton || comp is JComboBox<*>) {
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

internal fun applyCustomTheme(bgApp: Color, bgSidebar: Color, bgSurface: Color, accent: Color, textPrimary: Color) {
    prefs.put(PREF_PRESET, Theme.ThemePreset.CUSTOM.name)
    prefs.putInt(PREF_CUSTOM_BG, bgApp.rgb and 0xFFFFFF)
    prefs.putInt(PREF_CUSTOM_SIDEBAR, bgSidebar.rgb and 0xFFFFFF)
    prefs.putInt(PREF_CUSTOM_SURFACE, bgSurface.rgb and 0xFFFFFF)
    prefs.putInt(PREF_CUSTOM_ACCENT, accent.rgb and 0xFFFFFF)
    prefs.putInt(PREF_CUSTOM_TEXT, textPrimary.rgb and 0xFFFFFF)

    configureTheme(Theme.ThemePreset.CUSTOM.name)

    fun resetComponentStyles(comp: java.awt.Component) {
        if (comp is JButton || comp is JComboBox<*>) {
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

fun main() {
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

class MihonApp : JFrame("Mihon"), Navigator {
    private val home = System.getProperty("user.home")
    private val dbFile = File(home, ".mihon/mihon.db")
    private val db = DatabaseManager(dbFile).apply { initialize() }
    private val statusBar = JLabel("  Pronto", SwingConstants.LEFT)

    private val ctx = AppContext(
        dbFile = dbFile,
        service = LibraryService(MangaRepositoryImpl(db), ChapterRepositoryImpl(db), HistoryRepositoryImpl(db)),
        extensions = ExtensionLoader(File(home, ".mihon/extensions")).loadExtensions(),
        covers = CoverCache(),
        setStatus = { text -> SwingUtilities.invokeLater { statusBar.text = "  $text" } },
    )

    private val cards = CardLayout()
    private val content = JPanel(cards)
    private val library = LibraryScreen(ctx)
    private val details = DetailsScreen(ctx)
    private val browseHub = BrowseHubScreen(ctx)
    private val historyScreen = HistoryScreen(ctx)
    private val updatesScreen = UpdatesPlaceholderScreen(ctx)
    private val moreScreen = MoreScreen(
        ctx = ctx,
        onOpenDownloadsQueue = { DownloadsQueueDialog(this, ctx).isVisible = true },
        onOpenCategories = {
            CategoriesDialog(this, ctx, onCategoryFilter = {
                showLibrary()
            }).isVisible = true
        },
        onOpenStatistics = { StatisticsDialog(this, ctx).isVisible = true },
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
        onOpenAbout = {
            JOptionPane.showMessageDialog(
                this,
                "Mihon Linux v0.3.0\nPort Desktop de Alta Performance (Kotlin + Swing/FlatLaf)",
                "Sobre",
                JOptionPane.INFORMATION_MESSAGE,
            )
        },
    )
    private val navButtons = linkedMapOf<String, JToggleButton>()

    init {
        iconImage = AppIcon.createImage(64)
        ctx.navigator = this
        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(1240, 840)
        setLocationRelativeTo(null)

        content.add(library, LIBRARY)
        content.add(details, DETAILS)
        content.add(browseHub, BROWSE)
        content.add(historyScreen, HISTORY)
        content.add(updatesScreen, UPDATES)
        content.add(moreScreen, MORE)
        content.add(settingsScreen(), SETTINGS)

        val side = sidebar()
        add(side, BorderLayout.WEST)
        add(content, BorderLayout.CENTER)
        statusBar.border = BorderFactory.createEmptyBorder(5, 10, 5, 10)
        add(statusBar, BorderLayout.SOUTH)

        ctx.addThemeListener {
            side.background = Theme.BG_SIDEBAR
            updateSidebarLayout()
            brandLabel.foreground = Theme.TEXT_PRIMARY
            versionLabel.foreground = Theme.TEXT_MUTED
            content.background = Theme.BG_APP
            statusBar.background = Theme.BG_SIDEBAR
            statusBar.foreground = Theme.TEXT_MUTED
            repaint()
        }

        showLibrary()
        isVisible = true
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
        browseHub.openSourceByName(sourceName)
        navigate(BROWSE)
    }

    private fun navigate(card: String, highlight: String = card) {
        cards.show(content, card)
        navButtons[highlight]?.isSelected = true
    }

    private var sidebarCollapsed = prefs.getBoolean("sidebar_collapsed", false)
    private var sidebarAnimTimer: javax.swing.Timer? = null
    private var sidebarProgress: Float = if (sidebarCollapsed) 1f else 0f
    private lateinit var sidePanel: JPanel
    private lateinit var btnToggleSidebar: JButton
    private lateinit var topRow: JPanel
    private lateinit var brandLabel: JLabel
    private lateinit var versionLabel: JLabel

    private fun applySidebarProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        sidebarProgress = p
        val width = (210f + (64f - 210f) * p).toInt()
        val padH = (12f + (8f - 12f) * p).toInt()
        sidePanel.preferredSize = Dimension(width, 0)
        sidePanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER_SUBTLE),
            BorderFactory.createEmptyBorder(16, padH, 16, padH),
        )

        val showBrand = p < 0.45f
        if (brandLabel.isVisible != showBrand) {
            brandLabel.isVisible = showBrand
            versionLabel.isVisible = showBrand
            topRow.removeAll()
            if (!showBrand) {
                topRow.add(btnToggleSidebar, BorderLayout.CENTER)
            } else {
                topRow.add(brandLabel, BorderLayout.WEST)
                topRow.add(btnToggleSidebar, BorderLayout.EAST)
            }
        }
        val textAlpha = ((1f - p * 1.8f).coerceIn(0f, 1f) * 255).toInt()
        brandLabel.foreground = Color(Theme.TEXT_PRIMARY.red, Theme.TEXT_PRIMARY.green, Theme.TEXT_PRIMARY.blue, textAlpha)
        versionLabel.foreground = Color(Theme.TEXT_MUTED.red, Theme.TEXT_MUTED.green, Theme.TEXT_MUTED.blue, textAlpha)

        navButtons.values.forEach { btn ->
            if (btn is M3NavPillButton) {
                btn.collapseProgress = p
                btn.isCollapsed = p > 0.5f
            }
        }
        sidePanel.revalidate()
        sidePanel.repaint()
    }

    private fun updateSidebarLayout(animate: Boolean = false) {
        btnToggleSidebar.icon = ModernIcons.sidebarToggle(18, sidebarCollapsed) { Theme.TEXT_PRIMARY }
        btnToggleSidebar.toolTipText = if (sidebarCollapsed) "Expandir menu lateral" else "Recolher menu lateral"

        val target = if (sidebarCollapsed) 1f else 0f
        sidebarAnimTimer?.stop()
        if (!animate) {
            applySidebarProgress(target)
            return
        }

        val start = sidebarProgress
        val durationMs = 220L
        val startTime = System.currentTimeMillis()
        sidebarAnimTimer = javax.swing.Timer(12) { e ->
            val elapsed = System.currentTimeMillis() - startTime
            val rawT = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            // Ease-out cubic for silky smooth deceleration
            val inv = 1f - rawT
            val eased = 1f - (inv * inv * inv)
            val current = start + (target - start) * eased
            applySidebarProgress(current)
            if (rawT >= 1f) {
                (e.source as? javax.swing.Timer)?.stop()
                applySidebarProgress(target)
            }
        }.also { it.start() }
    }

    private fun sidebar(): JPanel {
        sidePanel = JPanel().apply {
            background = Theme.BG_SIDEBAR
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(16, if (sidebarCollapsed) 8 else 12, 16, if (sidebarCollapsed) 8 else 12),
            )
            preferredSize = Dimension(if (sidebarCollapsed) 64 else 210, 0)
        }
        brandLabel = JLabel("Mihon", AppIcon.getIcon(26), SwingConstants.LEFT).apply {
            font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 20f)
            foreground = Theme.TEXT_PRIMARY
            iconTextGap = 8
            isVisible = !sidebarCollapsed
        }
        btnToggleSidebar = JButton().apply {
            icon = ModernIcons.sidebarToggle(18, sidebarCollapsed) { Theme.TEXT_PRIMARY }
            isFocusable = false
            isContentAreaFilled = false
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            toolTipText = if (sidebarCollapsed) "Expandir menu lateral" else "Recolher menu lateral"
            addActionListener {
                sidebarCollapsed = !sidebarCollapsed
                prefs.putBoolean("sidebar_collapsed", sidebarCollapsed)
                updateSidebarLayout(animate = true)
            }
        }
        topRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = 0f
            maximumSize = Dimension(Int.MAX_VALUE, 36)
            if (sidebarCollapsed) {
                add(btnToggleSidebar, BorderLayout.CENTER)
            } else {
                add(brandLabel, BorderLayout.WEST)
                add(btnToggleSidebar, BorderLayout.EAST)
            }
        }
        sidePanel.add(topRow)
        sidePanel.add(Box.createVerticalStrut(2))
        versionLabel = JLabel("Desktop Linux").apply {
            font = Theme.FONT_MONO.deriveFont(Font.PLAIN, 11f)
            foreground = Theme.TEXT_MUTED
            alignmentX = 0f
            isVisible = !sidebarCollapsed
        }
        sidePanel.add(versionLabel)
        sidePanel.add(Box.createVerticalStrut(18))

        val group = ButtonGroup()
        // 5 Primary Material 3 destinations matching Mihon Android's NavigationBar:
        // Biblioteca, Atualizações, Histórico, Navegar, Mais
        listOf(
            Triple(LIBRARY, "Biblioteca", ModernIcons.library(19) { Theme.TEXT_PRIMARY }),
            Triple(UPDATES, "Atualizações", ModernIcons.updates(19) { Theme.TEXT_PRIMARY }),
            Triple(HISTORY, "Histórico", ModernIcons.history(19) { Theme.TEXT_PRIMARY }),
            Triple(BROWSE, "Navegar", ModernIcons.browse(19) { Theme.TEXT_PRIMARY }),
            Triple(MORE, "Mais", ModernIcons.more(19) { Theme.TEXT_PRIMARY }),
        ).forEach { (card, label, icon) ->
            val button = M3NavPillButton(label, icon).apply {
                alignmentX = 0f
                maximumSize = Dimension(Int.MAX_VALUE, 42)
                isCollapsed = sidebarCollapsed
                collapseProgress = if (sidebarCollapsed) 1f else 0f
                addActionListener {
                    when (card) {
                        LIBRARY -> showLibrary()
                        HISTORY -> showHistory()
                        BROWSE -> { browseHub.reload(); navigate(card) }
                        else -> navigate(card)
                    }
                }
            }
            group.add(button)
            navButtons[card] = button
            sidePanel.add(button)
            sidePanel.add(Box.createVerticalStrut(5))
        }
        return sidePanel
    }

    private fun settingsScreen(): JComponent = AntiGravitySettingsScreen(
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
        onToggleSidebarDefault = { collapsed ->
            sidebarCollapsed = collapsed
            updateSidebarLayout(animate = true)
        },
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
        val sub = JLabel("Selecione as cores da interface ao seu gosto:").apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = Theme.TEXT_MUTED
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

            val pickBtn = JButton("Escolher").apply {
                font = font.deriveFont(11.5f)
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
            val cancel = JButton("Cancelar").apply { addActionListener { dlg.dispose() } }
            val save = JButton("Aplicar Tema").apply {
                font = font.deriveFont(Font.BOLD, 13f)
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
                                "Mihon Linux v0.3.0\nLeitor de Mangá Desktop (CBZ, ZIP, Pastas e Fontes Online)\nKotlin + Exposed/SQLite + FlatLaf Swing",
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
        val start = animProgress
        val durationMs = 180L
        val startTime = System.currentTimeMillis()

        animTimer = javax.swing.Timer(12) { e ->
            val elapsed = System.currentTimeMillis() - startTime
            val rawT = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            // Ease-out cubic for crisp, fluid responsiveness
            val inv = 1f - rawT
            val eased = 1f - (inv * inv * inv)
            animProgress = (start + (target - start) * eased).coerceIn(0f, 1f)
            repaint()
            if (rawT >= 1f) {
                (e.source as? javax.swing.Timer)?.stop()
                animProgress = target
                repaint()
            }
        }.also { it.start() }
    }

    init {
        isOpaque = false
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        preferredSize = Dimension(52, 32)
        minimumSize = Dimension(52, 32)
        maximumSize = Dimension(52, 32)

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
                isSelected = !isSelected
            }
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

        val trackW = 50
        val trackH = 28
        val trackX = 1
        val trackY = (height - trackH) / 2
        val arc = trackH

        val p = animProgress // 0f (off) -> 1f (on)

        // Track fill color blending
        val inactiveBg = if (isHovered) (if (Theme.isDark) Color(0x28, 0x28, 0x28) else Color(0xEE, 0xF0, 0xF4))
                         else (if (Theme.isDark) Color(0x12, 0x12, 0x12) else Color(0xFA, 0xFA, 0xFA))
        val activeBg = if (Theme.isDark) Color(0xFF, 0xFF, 0xFF) else Color(0x1C, 0x1D, 0x1F)
        val currentTrackBg = blendColor(inactiveBg, activeBg, p)

        g2.color = currentTrackBg
        g2.fillRoundRect(trackX, trackY, trackW, trackH, arc, arc)

        // Border outline fading out as switch reaches on state
        val borderAlpha = ((1f - p) * (if (isHovered) (if (Theme.isDark) 180 else 160) else (if (Theme.isDark) 120 else 100))).toInt().coerceIn(0, 255)
        if (borderAlpha > 0) {
            g2.stroke = java.awt.BasicStroke(2f)
            val borderColor = if (Theme.isDark) Color(255, 255, 255, borderAlpha) else Color(0, 0, 0, borderAlpha)
            g2.color = borderColor
            g2.drawRoundRect(trackX + 1, trackY + 1, trackW - 2, trackH - 2, arc - 2, arc - 2)
        }

        // Thumb metrics interpolation:
        // Off: diameter 14px, starts at trackX + 6
        // On: diameter 22px, ends at trackX + trackW - 22 - 3 (which is trackX + 25)
        val startThumbD = 14f
        val endThumbD = 22f
        val thumbD = startThumbD + (endThumbD - startThumbD) * p

        val startThumbX = trackX + 6f
        val endThumbX = (trackX + trackW - endThumbD - 3)
        val thumbX = startThumbX + (endThumbX - startThumbX) * p
        val thumbY = trackY + (trackH - thumbD) / 2f

        // Thumb color interpolation:
        // Off: Light translucent (dark theme) / Dark translucent (light theme)
        // On: Contrast against active track (Black on white in dark mode, White on dark in light mode)
        val offThumbColor = if (Theme.isDark) Color(255, 255, 255, 200) else Color(0, 0, 0, 160)
        val onThumbColor = if (Theme.isDark) Color(0x00, 0x00, 0x00) else Color(0xFF, 0xFF, 0xFF)
        val currentThumbColor = blendColor(offThumbColor, onThumbColor, p)

        g2.color = currentThumbColor
        g2.fill(java.awt.geom.Ellipse2D.Float(thumbX, thumbY, thumbD, thumbD))

        g2.dispose()
    }
}

/**
 * Material 3 Navigation Pill Button (emulating Mihon Android's active pill selection, icons, and badge counters).
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

    init {
        isOpaque = false
        isFocusable = false
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        border = null
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        toolTipText = null
    }

    override fun paintBorder(g: java.awt.Graphics) {
        // Suppress FlatLaf/Swing default button borders to preserve clean M3 look
    }

    override fun paintComponent(g: java.awt.Graphics) {
        val g2 = g.create() as java.awt.Graphics2D
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        val w = width
        val h = height

        val pillX = 2
        val pillY = 2
        val pillW = w - 4
        val pillH = h - 4
        val arc = 12

        // Modern Squircle Active Pill Container
        if (isSelected) {
            g2.color = Theme.BG_PILL_ACTIVE
            g2.fillRoundRect(pillX, pillY, pillW, pillH, arc, arc)
            // Subtle accent indicator bar on the left
            g2.color = Theme.ACCENT
            g2.fillRoundRect(pillX, pillY + 8, 3, pillH - 16, 3, 3)
        } else if (model.isRollover) {
            g2.color = Theme.BG_CARD_HOVER
            g2.fillRoundRect(pillX, pillY, pillW, pillH, arc, arc)
        }

        val iconW = navIcon.iconWidth
        val iconH = navIcon.iconHeight
        val iconY = (h - iconH) / 2

        val expandedIconX = 14f
        val collapsedIconX = (w - iconW) / 2f
        val iconX = (expandedIconX + (collapsedIconX - expandedIconX) * collapseProgress).toInt()
        navIcon.paintIcon(this, g2, iconX, iconY)

        // Smooth alpha fade-out for text and badge during collapse
        val textAlpha = ((1f - collapseProgress * 1.6f).coerceIn(0f, 1f) * 255).toInt()
        if (textAlpha > 4) {
            g2.font = font.deriveFont(if (isSelected) Font.BOLD else Font.PLAIN, 13f)
            val baseColor = if (isSelected) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY
            g2.color = Color(baseColor.red, baseColor.green, baseColor.blue, textAlpha)
            val fm = g2.fontMetrics
            val textX = iconX + iconW + 12
            val textY = ((h - fm.height) / 2) + fm.ascent
            g2.drawString(labelText, textX, textY)

            // Subtle M3 Counter Badge on the right (if provided)
            if (!badgeText.isNullOrBlank()) {
                g2.font = Theme.FONT_MONO.deriveFont(Font.BOLD, 10.5f)
                val bFm = g2.fontMetrics
                val bw = (bFm.stringWidth(badgeText) + 12).coerceAtLeast(20)
                val bh = 18
                val bx = w - bw - 12
                val by = (h - bh) / 2
                val badgeBgAlpha = ((if (isSelected) 45 else 25) * (textAlpha / 255f)).toInt()
                g2.color = if (Theme.isDark) Color(255, 255, 255, badgeBgAlpha) else Color(0, 0, 0, badgeBgAlpha)
                g2.fillRoundRect(bx, by, bw, bh, bh, bh)
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
class BrowseHubScreen(private val ctx: AppContext) : JPanel(BorderLayout()) {
    private val cards = CardLayout()
    private val subContent = JPanel(cards)

    private val sourcesCards = CardLayout()
    private val sourcesHub = JPanel(sourcesCards)

    private val searchScreen = SearchScreen(ctx, onBackToSources = {
        sourcesCards.show(sourcesHub, "sources_list")
    })

    private val sourcesListScreen = SourcesListScreen(
        ctx = ctx,
        onSelectSource = { ext ->
            searchScreen.openSource(ext.name)
            sourcesCards.show(sourcesHub, "catalog")
        },
        onSelectLocalSource = {
            ctx.navigator.showLibrary()
            ctx.setStatus("Exibindo biblioteca local")
        },
    )

    private val extensionsScreen = ExtensionsScreen(ctx)
    private val btnSources = JToggleButton("Fontes")
    private val btnExtensions = JToggleButton("Extensões (${ctx.extensions.size})")
    private val btnMigrate = JToggleButton("Migrar")
    private val tabBar: JPanel

    init {
        background = Theme.BG_APP
        subContent.background = Theme.BG_APP

        sourcesHub.background = Theme.BG_APP
        sourcesHub.add(sourcesListScreen, "sources_list")
        sourcesHub.add(searchScreen, "catalog")

        subContent.add(sourcesHub, "sources")
        subContent.add(extensionsScreen, "extensions")
        subContent.add(migratePlaceholder(), "migrate")

        tabBar = JPanel(FlowLayout(FlowLayout.CENTER, 10, 10)).apply {
            background = Theme.BG_APP
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SUBTLE)
            val group = ButtonGroup()
            listOf(btnSources to "sources", btnExtensions to "extensions", btnMigrate to "migrate").forEach { (btn, card) ->
                btn.isFocusable = false
                btn.font = font.deriveFont(Font.BOLD, 13f)
                btn.addActionListener {
                    if (card == "sources") {
                        sourcesCards.show(sourcesHub, "sources_list")
                    }
                    cards.show(subContent, card)
                }
                group.add(btn)
                add(btn)
            }
        }
        btnSources.isSelected = true
        add(tabBar, BorderLayout.NORTH)
        add(subContent, BorderLayout.CENTER)

        ctx.addThemeListener {
            background = Theme.BG_APP
            subContent.background = Theme.BG_APP
            sourcesHub.background = Theme.BG_APP
            tabBar.background = Theme.BG_APP
            tabBar.border = BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SUBTLE)
            repaint()
        }
    }

    private fun migratePlaceholder(): JPanel = JPanel(java.awt.GridBagLayout()).apply {
        background = Theme.BG_APP
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
            add(titleLbl)
            add(Box.createVerticalStrut(6))
            add(descLbl)
        }
        add(p)
        ctx.addThemeListener {
            background = Theme.BG_APP
            titleLbl.foreground = Theme.TEXT_PRIMARY
            descLbl.foreground = Theme.TEXT_MUTED
            repaint()
        }
    }

    fun reload() {
        btnExtensions.text = "Extensões (${ctx.extensions.size})"
        sourcesListScreen.rebuildList()
        searchScreen.loadPopular()
    }

    fun openSourceByName(sourceName: String) {
        val ext = ctx.extensions.firstOrNull { it.name.equals(sourceName, ignoreCase = true) || it.name.startsWith(sourceName, ignoreCase = true) }
        if (ext != null) {
            searchScreen.openSource(ext.name)
            sourcesCards.show(sourcesHub, "catalog")
            cards.show(subContent, "sources")
            btnSources.isSelected = true
        }
    }
}

/**
 * Placeholder screen for "Atualizações" (recent chapters feed).
 */
class UpdatesPlaceholderScreen(ctx: AppContext) : JPanel(BorderLayout()) {
    init {
        background = Theme.BG_APP
        val titleLbl = JLabel("Atualizações", SwingConstants.CENTER).apply {
            font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 22f)
            foreground = Theme.TEXT_PRIMARY
            alignmentX = 0.5f
        }
        val descLbl = JLabel("Aqui são listados os capítulos mais recentes lançados pelas suas fontes instaladas.", SwingConstants.CENTER).apply {
            font = Theme.FONT_UI.deriveFont(13f)
            foreground = Theme.TEXT_MUTED
            alignmentX = 0.5f
        }
        val center = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(titleLbl)
            add(Box.createVerticalStrut(8))
            add(descLbl)
        }
        val wrap = JPanel(java.awt.GridBagLayout()).apply {
            isOpaque = false
            add(center)
        }
        add(wrap, BorderLayout.CENTER)
        ctx.addThemeListener {
            background = Theme.BG_APP
            titleLbl.foreground = Theme.TEXT_PRIMARY
            descLbl.foreground = Theme.TEXT_MUTED
            repaint()
        }
    }
}

/**
 * AntiGravity-styled "Mais" Screen (MoreScreen):
 * Uses grouped rounded cards (`SettingsGroupCard`) with internal 1px hairline dividers,
 * clear section headers, 15px icon margins, animated M3Switch toggles, and interactive hover rows.
 */
class MoreScreen(
    private val ctx: AppContext,
    private val onOpenDownloadsQueue: () -> Unit,
    private val onOpenCategories: () -> Unit,
    private val onOpenStatistics: () -> Unit,
    private val onOpenStorageManager: () -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onOpenAbout: () -> Unit,
) : JPanel(BorderLayout()) {

    private val themeUpdaters = mutableListOf<() -> Unit>()

    private val offlineSwitch = M3Switch(ctx.onlyOfflineFilter) { enabled ->
        ctx.setOfflineFilter(enabled)
        ctx.setStatus(if (enabled) "Filtro: Somente disponíveis offline ativado" else "Filtro offline desativado")
    }

    private val incognitoSwitch = M3Switch(ctx.incognitoMode) { enabled ->
        ctx.incognitoMode = enabled
        ctx.setStatus(if (enabled) "Modo anônimo ativado (histórico pausado)" else "Modo anônimo desativado")
    }

    init {
        ctx.addOfflineFilterListener { offlineSwitch.isSelected = it }
        background = Theme.BG_APP

        val content = JPanel().apply {
            background = Theme.BG_APP
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(28, 36, 36, 36)
        }

        // Page Header (AntiGravity H1 + Subtitle)
        val h1 = JLabel("Mais").apply {
            font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 24f)
            foreground = Theme.TEXT_PRIMARY
            alignmentX = 0f
        }
        val subtitle = JLabel("Controle modos rápidos de leitura, ferramentas da biblioteca, estatísticas e preferências.").apply {
            font = Theme.FONT_UI.deriveFont(Font.PLAIN, 13f)
            foreground = Theme.TEXT_MUTED
            alignmentX = 0f
        }
        content.add(h1)
        content.add(Box.createVerticalStrut(4))
        content.add(subtitle)
        content.add(Box.createVerticalStrut(24))
        themeUpdaters.add {
            h1.foreground = Theme.TEXT_PRIMARY
            subtitle.foreground = Theme.TEXT_MUTED
        }

        fun addSectionHeader(title: String, desc: String) {
            val tLbl = JLabel(title).apply {
                font = Theme.FONT_UI.deriveFont(Font.BOLD, 14f)
                foreground = Theme.TEXT_PRIMARY
                alignmentX = 0f
            }
            val dLbl = JLabel(desc).apply {
                font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12f)
                foreground = Theme.TEXT_MUTED
                alignmentX = 0f
            }
            content.add(tLbl)
            content.add(Box.createVerticalStrut(3))
            content.add(dLbl)
            content.add(Box.createVerticalStrut(10))
            themeUpdaters.add {
                tLbl.foreground = Theme.TEXT_PRIMARY
                dLbl.foreground = Theme.TEXT_MUTED
            }
        }

        fun createInteractiveRow(
            icon: Icon,
            title: String,
            desc: String,
            rightComponent: JComponent,
            onClick: () -> Unit,
        ): JPanel {
            var hovered = false
            val titleLbl = JLabel(title).apply {
                font = Theme.FONT_UI.deriveFont(Font.BOLD, 13f)
                foreground = Theme.TEXT_PRIMARY
                alignmentX = 0f
            }
            val descLbl = JLabel(desc).apply {
                font = Theme.FONT_UI.deriveFont(Font.PLAIN, 11.5f)
                foreground = Theme.TEXT_MUTED
                alignmentX = 0f
            }
            val iconLbl = JLabel(icon)

            val row = object : JPanel(BorderLayout(12, 0)) {
                override fun paintComponent(g: java.awt.Graphics) {
                    if (hovered) {
                        val g2 = g.create() as java.awt.Graphics2D
                        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.color = if (Theme.isDark) Color(255, 255, 255, 10) else Color(0, 0, 0, 8)
                        g2.fillRoundRect(4, 2, width - 8, height - 4, 10, 10)
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
                    // 15px left of icon and 15px right of icon
                    border = BorderFactory.createEmptyBorder(0, 15, 0, 0)
                    add(iconLbl)
                    add(Box.createHorizontalStrut(15))
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

                val rightWrap = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
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
                descLbl.foreground = Theme.TEXT_MUTED
                iconLbl.repaint()
            }
            return row
        }

        // --- SEÇÃO 1: Modos Rápidos ---
        addSectionHeader(
            "Modos Rápidos",
            "Ative filtros globais de leitura e privacidade instantaneamente.",
        )
        val quickModesCard = SettingsGroupCard()
        quickModesCard.addRow(
            createInteractiveRow(
                icon = ModernIcons.offline(20) { Theme.TEXT_PRIMARY },
                title = "Somente disponíveis offline",
                desc = "Filtra todos os itens em sua biblioteca para exibir apenas capítulos salvos no dispositivo.",
                rightComponent = offlineSwitch,
                onClick = { offlineSwitch.isSelected = !offlineSwitch.isSelected },
            ),
        )
        quickModesCard.addRow(
            createInteractiveRow(
                icon = ModernIcons.incognito(20) { Theme.TEXT_PRIMARY },
                title = "Modo anônimo",
                desc = "Pausa o histórico de leitura e não registra novas páginas lidas enquanto estiver ativo.",
                rightComponent = incognitoSwitch,
                onClick = { incognitoSwitch.isSelected = !incognitoSwitch.isSelected },
            ),
        )
        content.add(quickModesCard)
        content.add(Box.createVerticalStrut(24))

        // --- SEÇÃO 2: Biblioteca & Atividade ---
        addSectionHeader(
            "Biblioteca & Atividade",
            "Gerencie seus downloads em segundo plano, categorias e estatísticas de leitura.",
        )
        val libraryCard = SettingsGroupCard()
        libraryCard.addRow(
            createInteractiveRow(
                icon = ModernIcons.downloads(20) { Theme.TEXT_PRIMARY },
                title = "Fila de downloads",
                desc = "Acompanhe e gerencie os capítulos que estão sendo baixados no momento.",
                rightComponent = JLabel(ModernIcons.chevronRight(16) { Theme.TEXT_MUTED }),
                onClick = onOpenDownloadsQueue,
            ),
        )
        libraryCard.addRow(
            createInteractiveRow(
                icon = ModernIcons.categories(20) { Theme.TEXT_PRIMARY },
                title = "Categorias",
                desc = "Organize os mangás da sua biblioteca por status e coleções.",
                rightComponent = JLabel(ModernIcons.chevronRight(16) { Theme.TEXT_MUTED }),
                onClick = onOpenCategories,
            ),
        )
        libraryCard.addRow(
            createInteractiveRow(
                icon = ModernIcons.statistics(20) { Theme.TEXT_PRIMARY },
                title = "Estatísticas",
                desc = "Visualize seu tempo total de leitura, obras lidas e progresso de capítulos.",
                rightComponent = JLabel(ModernIcons.chevronRight(16) { Theme.TEXT_MUTED }),
                onClick = onOpenStatistics,
            ),
        )
        content.add(libraryCard)
        content.add(Box.createVerticalStrut(24))

        // --- SEÇÃO 3: Sistema & Preferências ---
        addSectionHeader(
            "Sistema & Preferências",
            "Gerenciamento de armazenamento local, ajustes gerais do aplicativo e versão.",
        )
        val systemCard = SettingsGroupCard()
        systemCard.addRow(
            createInteractiveRow(
                icon = ModernIcons.storage(20) { Theme.TEXT_PRIMARY },
                title = "Dados e armazenamento",
                desc = "Inspecione o uso de disco, limpe caches de capas e gerencie backups.",
                rightComponent = JLabel(ModernIcons.chevronRight(16) { Theme.TEXT_MUTED }),
                onClick = onOpenStorageManager,
            ),
        )
        systemCard.addRow(
            createInteractiveRow(
                icon = ModernIcons.settings(20) { Theme.TEXT_PRIMARY },
                title = "Configurações",
                desc = "Personalize aparência, leitor, gráficos Ultra HD, pastas e atalhos.",
                rightComponent = JLabel(ModernIcons.chevronRight(16) { Theme.TEXT_MUTED }),
                onClick = onOpenSettings,
            ),
        )
        systemCard.addRow(
            createInteractiveRow(
                icon = ModernIcons.about(20) { Theme.TEXT_PRIMARY },
                title = "Sobre",
                desc = "Mihon Linux v0.3.0 • Port Desktop de Alta Performance",
                rightComponent = JLabel(ModernIcons.chevronRight(16) { Theme.TEXT_MUTED }),
                onClick = onOpenAbout,
            ),
        )
        content.add(systemCard)

        val wrapper = JPanel(BorderLayout()).apply {
            background = Theme.BG_APP
            add(content, BorderLayout.NORTH)
        }
        val scroll = JScrollPane(wrapper).apply {
            border = null
            background = Theme.BG_APP
            viewport.background = Theme.BG_APP
            verticalScrollBar.unitIncrement = 24
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scroll, BorderLayout.CENTER)

        ctx.addThemeListener {
            background = Theme.BG_APP
            content.background = Theme.BG_APP
            wrapper.background = Theme.BG_APP
            scroll.background = Theme.BG_APP
            scroll.viewport.background = Theme.BG_APP
            offlineSwitch.repaint()
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
