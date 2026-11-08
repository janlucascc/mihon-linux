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
    private lateinit var sidePanel: JPanel
    private lateinit var btnToggleSidebar: JButton
    private lateinit var topRow: JPanel
    private lateinit var brandLabel: JLabel
    private lateinit var versionLabel: JLabel

    private fun updateSidebarLayout() {
        val width = if (sidebarCollapsed) 64 else 210
        val padH = if (sidebarCollapsed) 8 else 12
        sidePanel.preferredSize = Dimension(width, 0)
        sidePanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER_SUBTLE),
            BorderFactory.createEmptyBorder(16, padH, 16, padH),
        )
        brandLabel.isVisible = !sidebarCollapsed
        versionLabel.isVisible = !sidebarCollapsed
        btnToggleSidebar.icon = ModernIcons.sidebarToggle(18, sidebarCollapsed) { Theme.TEXT_PRIMARY }
        btnToggleSidebar.toolTipText = if (sidebarCollapsed) "Expandir menu lateral" else "Recolher menu lateral"

        topRow.removeAll()
        if (sidebarCollapsed) {
            topRow.add(btnToggleSidebar, BorderLayout.CENTER)
        } else {
            topRow.add(brandLabel, BorderLayout.WEST)
            topRow.add(btnToggleSidebar, BorderLayout.EAST)
        }

        navButtons.values.forEach { btn ->
            if (btn is M3NavPillButton) {
                btn.isCollapsed = sidebarCollapsed
            }
        }
        sidePanel.revalidate()
        sidePanel.repaint()
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
                updateSidebarLayout()
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

    private fun settingsScreen(): JComponent {
        val themeBox = JComboBox(Theme.ThemePreset.entries.map { it.displayName }.toTypedArray()).apply {
            val savedPreset = runCatching { Theme.ThemePreset.valueOf(prefs.get(PREF_PRESET, "AMOLED")) }
                .getOrDefault(Theme.ThemePreset.AMOLED)
            selectedIndex = savedPreset.ordinal
            maximumSize = Dimension(320, 32)
            alignmentX = 0f
            addActionListener {
                val selected = Theme.ThemePreset.entries[selectedIndex]
                if (selected == Theme.ThemePreset.CUSTOM) {
                    openCustomThemeDialog()
                } else {
                    applyTheme(selected)
                }
            }
        }

        val btnCustomTheme = JButton("Editar Cores do Tema...").apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            alignmentX = 0f
            margin = java.awt.Insets(4, 12, 4, 12)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addActionListener { openCustomThemeDialog() }
        }

        val incognitoCheck = JCheckBox("Modo anônimo (Pausa gravação no histórico de leitura)", ctx.incognitoMode).apply {
            isOpaque = false
            alignmentX = 0f
            toolTipText = "Mihon M3: Quando ativado, os capítulos lidos não serão salvos no histórico"
            addActionListener {
                ctx.incognitoMode = isSelected
                ctx.setStatus(if (isSelected) "Modo anônimo ativado (histórico pausado)" else "Modo anônimo desativado")
            }
        }

        val autoPrefetchCheck = JCheckBox("Pré-baixar próximos capítulos automaticamente ao ler online", readerPrefs.getBoolean("autoPrefetch", false)).apply {
            isOpaque = false
            alignmentX = 0f
            toolTipText = "Quando ativado, os 2 próximos capítulos são baixados em segundo plano enquanto você lê"
            addActionListener { readerPrefs.putBoolean("autoPrefetch", isSelected) }
        }

        val defaultModeBox = JComboBox(ReadMode.entries.map { it.label }.toTypedArray()).apply {
            val saved = runCatching { ReadMode.valueOf(readerPrefs.get("mode", "SINGLE")) }.getOrDefault(ReadMode.SINGLE)
            selectedIndex = saved.ordinal
            maximumSize = Dimension(280, 32)
            alignmentX = 0f
            addActionListener { readerPrefs.put("mode", ReadMode.entries[selectedIndex].name) }
        }
        val defaultFitBox = JComboBox(FitMode.entries.map { it.label }.toTypedArray()).apply {
            val saved = runCatching { FitMode.valueOf(readerPrefs.get("fit", "WINDOW")) }.getOrDefault(FitMode.WINDOW)
            selectedIndex = saved.ordinal
            maximumSize = Dimension(280, 32)
            alignmentX = 0f
            addActionListener { readerPrefs.put("fit", FitMode.entries[selectedIndex].name) }
        }
        val defaultQualityBox = JComboBox(MangaImageEnhancer.QualityMode.entries.map { it.label }.toTypedArray()).apply {
            val saved = runCatching {
                val qStr = readerPrefs.get("quality", "ULTRA_HD")
                if (qStr in setOf("FAST", "BILINEAR")) MangaImageEnhancer.QualityMode.BILINEAR else MangaImageEnhancer.QualityMode.ULTRA_HD
            }.getOrDefault(MangaImageEnhancer.QualityMode.ULTRA_HD)
            selectedIndex = saved.ordinal
            maximumSize = Dimension(380, 32)
            alignmentX = 0f
            addActionListener { readerPrefs.put("quality", MangaImageEnhancer.QualityMode.entries[selectedIndex].name) }
        }

        // --- Gráficos & Instalação (Melhoria visual padrão nos downloads .cbz) ---
        val installEnhanceBox = JComboBox(MangaImageEnhancer.InstallEnhanceMode.entries.map { it.label }.toTypedArray()).apply {
            val saved = runCatching {
                MangaImageEnhancer.InstallEnhanceMode.valueOf(readerPrefs.get("installEnhanceMode", "OFF"))
            }.getOrDefault(MangaImageEnhancer.InstallEnhanceMode.OFF)
            selectedIndex = saved.ordinal
            maximumSize = Dimension(460, 32)
            alignmentX = 0f
            addActionListener {
                val chosen = MangaImageEnhancer.InstallEnhanceMode.entries[selectedIndex]
                readerPrefs.put("installEnhanceMode", chosen.name)
                ctx.setStatus("Melhoria visual nos downloads: ${chosen.label}")
            }
        }

        val installFormatBox = JComboBox(MangaImageEnhancer.InstallOutputFormat.entries.map { it.label }.toTypedArray()).apply {
            val saved = runCatching {
                MangaImageEnhancer.InstallOutputFormat.valueOf(readerPrefs.get("installOutputFormat", "JPEG_HQ"))
            }.getOrDefault(MangaImageEnhancer.InstallOutputFormat.JPEG_HQ)
            selectedIndex = saved.ordinal
            maximumSize = Dimension(460, 32)
            alignmentX = 0f
            addActionListener {
                val chosen = MangaImageEnhancer.InstallOutputFormat.entries[selectedIndex]
                readerPrefs.put("installOutputFormat", chosen.name)
            }
        }

        val btnBackup = JButton("Exportar Backup (.zip)").apply { addActionListener { exportBackup() } }
        val btnRestore = JButton("Restaurar Backup (.zip)").apply { addActionListener { importBackup() } }
        val backupRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = 0f
            maximumSize = Dimension(Int.MAX_VALUE, 38)
            add(btnBackup)
            add(Box.createHorizontalStrut(10))
            add(btnRestore)
        }

        val titleLbl = JLabel("Configurações").apply {
            font = font.deriveFont(Font.BOLD, 20f)
            foreground = Theme.TEXT_PRIMARY
        }

        val topBar = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = 0f
            maximumSize = Dimension(Int.MAX_VALUE, 38)
            preferredSize = Dimension(400, 38)
            val backBtn = JButton("← Voltar").apply {
                isFocusable = false
                font = font.deriveFont(Font.BOLD, 13f)
                margin = java.awt.Insets(4, 10, 4, 10)
                putClientProperty("JButton.buttonType", "roundRect")
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                toolTipText = "Voltar para o menu Mais"
                addActionListener {
                    navigate(MORE)
                }
            }
            add(backBtn)
            add(Box.createHorizontalStrut(8))
            add(titleLbl)
        }

        val lblThemeTitle = JLabel("Aparência e Tema:").apply { alignmentX = 0f; foreground = Theme.TEXT_PRIMARY; font = font.deriveFont(Font.BOLD, 13.5f) }
        val lblMode = JLabel("Modo de leitura padrão:").apply { alignmentX = 0f; foreground = Theme.TEXT_PRIMARY }
        val lblFit = JLabel("Ajuste de página padrão:").apply { alignmentX = 0f; foreground = Theme.TEXT_PRIMARY }
        val lblQuality = JLabel("Qualidade de renderização em Tempo Real (Leitor / Online):").apply { alignmentX = 0f; foreground = Theme.TEXT_PRIMARY }
        val lblBackupTitle = JLabel("Backup da Biblioteca e Histórico:").apply { font = font.deriveFont(Font.BOLD, 14f); alignmentX = 0f; foreground = Theme.TEXT_PRIMARY }
        val lblSystemTitle = JLabel("Caminhos do sistema:").apply { font = font.deriveFont(Font.BOLD, 14f); alignmentX = 0f; foreground = Theme.TEXT_PRIMARY }
        val lblDb = JLabel("Banco SQLite: ${dbFile.absolutePath}").apply { alignmentX = 0f; foreground = Theme.TEXT_MUTED }
        val lblDl = JLabel("Downloads: ${ctx.service.downloadsDir.absolutePath}").apply { alignmentX = 0f; foreground = Theme.TEXT_MUTED }
        val lblSources = JLabel("Fontes JSON: ${File(home, ".mihon/sources").absolutePath}").apply { alignmentX = 0f; foreground = Theme.TEXT_MUTED }
        val lblLogs = JLabel("Logs: ${File(home, ".mihon/logs/mihon.log").absolutePath}").apply { alignmentX = 0f; foreground = Theme.TEXT_MUTED }

        val lblAppActionsTitle = JLabel("Aplicativo & Ajuda:").apply { font = font.deriveFont(Font.BOLD, 14f); alignmentX = 0f; foreground = Theme.TEXT_PRIMARY }
        val btnAbout = JButton("ℹ️ Sobre o Mihon").apply {
            addActionListener {
                JOptionPane.showMessageDialog(
                    this@MihonApp,
                    "Mihon Desktop Linux\nLeitor de Mangá de Alta Performance\nKotlin + Exposed/SQLite + FlatLaf Swing",
                    "Sobre o Mihon",
                    JOptionPane.INFORMATION_MESSAGE,
                )
            }
        }
        val btnExit = JButton("Encerrar Aplicativo").apply {
            addActionListener {
                this@MihonApp.dispatchEvent(java.awt.event.WindowEvent(this@MihonApp, java.awt.event.WindowEvent.WINDOW_CLOSING))
            }
        }
        val appActionsRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = 0f
            maximumSize = Dimension(Int.MAX_VALUE, 38)
            add(btnAbout)
            add(Box.createHorizontalStrut(10))
            add(btnExit)
        }

        // --- Aba 1: Geral & Sistema ---
        val generalPanel = JPanel().apply {
            background = Theme.BG_APP
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(16, 20, 20, 20)

            add(lblThemeTitle)
            add(Box.createVerticalStrut(6))
            add(themeBox)
            add(Box.createVerticalStrut(6))
            add(btnCustomTheme)
            add(Box.createVerticalStrut(14))

            add(incognitoCheck)
            add(Box.createVerticalStrut(8))

            add(autoPrefetchCheck)
            add(Box.createVerticalStrut(16))

            add(lblMode)
            add(Box.createVerticalStrut(4))
            add(defaultModeBox)
            add(Box.createVerticalStrut(12))

            add(lblFit)
            add(Box.createVerticalStrut(4))
            add(defaultFitBox)
            add(Box.createVerticalStrut(20))

            add(lblBackupTitle)
            add(Box.createVerticalStrut(8))
            add(backupRow)
            add(Box.createVerticalStrut(24))

            add(lblSystemTitle)
            add(Box.createVerticalStrut(6))
            add(lblDb)
            add(lblDl)
            add(lblSources)
            add(lblLogs)
            add(Box.createVerticalStrut(24))

            add(lblAppActionsTitle)
            add(Box.createVerticalStrut(8))
            add(appActionsRow)
            add(Box.createVerticalGlue())
        }

        // --- Aba 2: Gráficos (Qualidade Visual & Motor de Imagem) ---
        val lblGraphicsHero = JLabel("Qualidade de Imagem & Motor Visual").apply {
            font = font.deriveFont(Font.BOLD, 16f)
            foreground = Theme.TEXT_PRIMARY
            alignmentX = 0f
        }
        val lblGraphicsDesc = JLabel(
            "<html><div style='width:580px'>Aprimoramento de traço nítido de nanquim e cores vivas " +
                "<b>mantendo a resolução original</b> da obra, sem distorção nem consumo excessivo de memória.</div></html>",
        ).apply {
            font = font.deriveFont(Font.PLAIN, 12.5f)
            foreground = Theme.TEXT_SECONDARY
            alignmentX = 0f
        }

        // Botão para abrir o Menu Teste comparativo
        val btnOpenTestLab = JButton("Abrir Menu Teste Comparativo (Pág. 2 • Cap. 1)").apply {
            icon = ModernIcons.sparkles(16) { Theme.TEXT_PRIMARY }
            font = font.deriveFont(Font.BOLD, 12.5f)
            putClientProperty("JButton.buttonType", "roundRect")
            alignmentX = 0f
            background = Theme.BG_CARD_HOVER
            foreground = Theme.TEXT_PRIMARY
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            toolTipText = "Compara a qualidade da imagem padrão (Original vs Ultra HD vs Bilinear)"
            addActionListener {
                UpscaleComparisonDialog(this@MihonApp, ctx).isVisible = true
            }
        }

        val lblInstallEnhance = JLabel("Melhoria Visual na Instalação (Capítulos Baixados .cbz):").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            foreground = Theme.TEXT_PRIMARY
            alignmentX = 0f
        }
        val lblInstallFormat = JLabel("Formato de Compressão no .cbz:").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            foreground = Theme.TEXT_PRIMARY
            alignmentX = 0f
        }
        val lblGraphicsNote = JLabel(
            "<html><div style='width:580px'>💡 <b>Modo Ultra HD:</b> Refina as linhas escuras de nanquim e melhora o contraste e claridade da imagem padrão, eliminando a pixelização e a sujeira de compressão JPEG sem esticar a imagem.</div></html>",
        ).apply {
            font = font.deriveFont(Font.PLAIN, 11.5f)
            foreground = Theme.TEXT_MUTED
            alignmentX = 0f
        }
        val lblRealtimeSection = JLabel("Renderização do Leitor:").apply {
            font = font.deriveFont(Font.BOLD, 14f)
            foreground = Theme.TEXT_PRIMARY
            alignmentX = 0f
        }

        val graphicsPanel = JPanel().apply {
            background = Theme.BG_APP
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(16, 20, 20, 20)

            add(lblGraphicsHero)
            add(Box.createVerticalStrut(6))
            add(lblGraphicsDesc)
            add(Box.createVerticalStrut(14))
            add(btnOpenTestLab)
            add(Box.createVerticalStrut(20))

            add(lblInstallEnhance)
            add(Box.createVerticalStrut(6))
            add(installEnhanceBox)
            add(Box.createVerticalStrut(14))

            add(lblInstallFormat)
            add(Box.createVerticalStrut(6))
            add(installFormatBox)
            add(Box.createVerticalStrut(12))
            add(lblGraphicsNote)
            add(Box.createVerticalStrut(24))

            add(lblRealtimeSection)
            add(Box.createVerticalStrut(6))
            add(lblQuality)
            add(Box.createVerticalStrut(6))
            add(defaultQualityBox)

            add(Box.createVerticalGlue())
        }

        val scrollGeneral = JScrollPane(generalPanel).apply {
            border = null
            background = Theme.BG_APP
            viewport.background = Theme.BG_APP
            verticalScrollBar.unitIncrement = 20
        }
        val scrollGraphics = JScrollPane(graphicsPanel).apply {
            border = null
            background = Theme.BG_APP
            viewport.background = Theme.BG_APP
            verticalScrollBar.unitIncrement = 20
        }

        val tabs = javax.swing.JTabbedPane().apply {
            background = Theme.BG_APP
            foreground = Theme.TEXT_PRIMARY
            font = font.deriveFont(Font.BOLD, 13f)
            addTab("⚙ Geral & Sistema", scrollGeneral)
            addTab("✨ Gráficos", scrollGraphics)
        }

        val root = JPanel(BorderLayout()).apply {
            background = Theme.BG_APP
            val headerWrap = JPanel(BorderLayout()).apply {
                background = Theme.BG_APP
                border = BorderFactory.createEmptyBorder(16, 24, 8, 24)
                add(topBar, BorderLayout.WEST)
            }
            add(headerWrap, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }

        ctx.addThemeListener {
            titleLbl.foreground = Theme.TEXT_PRIMARY
            lblThemeTitle.foreground = Theme.TEXT_PRIMARY
            lblMode.foreground = Theme.TEXT_PRIMARY
            lblFit.foreground = Theme.TEXT_PRIMARY
            lblQuality.foreground = Theme.TEXT_PRIMARY
            lblBackupTitle.foreground = Theme.TEXT_PRIMARY
            lblSystemTitle.foreground = Theme.TEXT_PRIMARY
            lblAppActionsTitle.foreground = Theme.TEXT_PRIMARY
            lblGraphicsHero.foreground = Theme.TEXT_PRIMARY
            lblGraphicsDesc.foreground = Theme.TEXT_SECONDARY
            lblInstallEnhance.foreground = Theme.TEXT_PRIMARY
            lblInstallFormat.foreground = Theme.TEXT_PRIMARY
            lblGraphicsNote.foreground = Theme.TEXT_MUTED
            lblRealtimeSection.foreground = Theme.TEXT_PRIMARY
            incognitoCheck.foreground = Theme.TEXT_PRIMARY
            autoPrefetchCheck.foreground = Theme.TEXT_PRIMARY
            incognitoCheck.isOpaque = false
            autoPrefetchCheck.isOpaque = false
            root.background = Theme.BG_APP
            tabs.background = Theme.BG_APP
            tabs.foreground = Theme.TEXT_PRIMARY
            generalPanel.background = Theme.BG_APP
            graphicsPanel.background = Theme.BG_APP
            scrollGeneral.background = Theme.BG_APP
            scrollGeneral.viewport.background = Theme.BG_APP
            scrollGraphics.background = Theme.BG_APP
            scrollGraphics.viewport.background = Theme.BG_APP
            lblDb.foreground = Theme.TEXT_MUTED
            lblDl.foreground = Theme.TEXT_MUTED
            lblSources.foreground = Theme.TEXT_MUTED
            lblLogs.foreground = Theme.TEXT_MUTED
            val currentIdx = Theme.currentPreset.ordinal
            if (themeBox.selectedIndex != currentIdx) {
                themeBox.selectedIndex = currentIdx
            }
            root.repaint()
        }

        return root
    }

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
 * Sleek stadium pill track with round thumb, smooth hover effect, and on/off state.
 */
class M3Switch(
    initialState: Boolean = false,
    private val onToggle: (Boolean) -> Unit = {},
) : JComponent() {
    var isSelected: Boolean = initialState
        set(value) {
            if (field != value) {
                field = value
                repaint()
                onToggle(value)
            }
        }

    private var isHovered: Boolean = false

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

    override fun paintComponent(g: java.awt.Graphics) {
        val g2 = g.create() as java.awt.Graphics2D
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)

        val trackW = 50
        val trackH = 28
        val trackX = 1
        val trackY = (height - trackH) / 2
        val arc = trackH

        if (isSelected) {
            // Active / Checked Track: filled stadium pill
            g2.color = if (Theme.isDark) Color(0xFF, 0xFF, 0xFF) else Color(0x1C, 0x1D, 0x1F)
            g2.fillRoundRect(trackX, trackY, trackW, trackH, arc, arc)

            // Thumb: inside active track (contrast color)
            val thumbD = 22
            val thumbX = trackX + trackW - thumbD - 3
            val thumbY = trackY + (trackH - thumbD) / 2
            g2.color = if (Theme.isDark) Color(0x00, 0x00, 0x00) else Color(0xFF, 0xFF, 0xFF)
            g2.fillOval(thumbX, thumbY, thumbD, thumbD)
        } else {
            // Inactive Track: outlined stadium pill with transparent/surface background
            g2.color = if (isHovered) (if (Theme.isDark) Color(0x28, 0x28, 0x28) else Color(0xEE, 0xF0, 0xF4))
                       else (if (Theme.isDark) Color(0x12, 0x12, 0x12) else Color(0xFA, 0xFA, 0xFA))
            g2.fillRoundRect(trackX, trackY, trackW, trackH, arc, arc)

            // Pill outline (thick crisp border)
            g2.stroke = java.awt.BasicStroke(2f)
            g2.color = if (isHovered) (if (Theme.isDark) Color(255, 255, 255, 180) else Color(0, 0, 0, 160))
                       else (if (Theme.isDark) Color(255, 255, 255, 120) else Color(0, 0, 0, 100))
            g2.drawRoundRect(trackX + 1, trackY + 1, trackW - 2, trackH - 2, arc - 2, arc - 2)

            // Thumb: small circle inside inactive track
            val thumbD = 14
            val thumbX = trackX + 6
            val thumbY = trackY + (trackH - thumbD) / 2
            g2.color = if (Theme.isDark) Color(255, 255, 255, 200) else Color(0, 0, 0, 160)
            g2.fillOval(thumbX, thumbY, thumbD, thumbD)
        }
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
            revalidate()
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
        val arc = pillH

        // M3 Active Pill Container
        if (isSelected) {
            g2.color = Theme.BG_PILL_ACTIVE
            g2.fillRoundRect(pillX, pillY, pillW, pillH, arc, arc)
        } else if (model.isRollover) {
            g2.color = Theme.BG_CARD_HOVER
            g2.fillRoundRect(pillX, pillY, pillW, pillH, arc, arc)
        }

        val iconW = navIcon.iconWidth
        val iconH = navIcon.iconHeight
        val iconY = (h - iconH) / 2

        if (isCollapsed) {
            val iconX = (w - iconW) / 2
            navIcon.paintIcon(this, g2, iconX, iconY)
        } else {
            val iconX = 14
            navIcon.paintIcon(this, g2, iconX, iconY)

            g2.font = font.deriveFont(if (isSelected) Font.BOLD else Font.PLAIN, 13f)
            g2.color = if (isSelected) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY
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
                g2.color = if (isSelected) (if (Theme.isDark) Color(255, 255, 255, 45) else Color(0, 0, 0, 30)) else (if (Theme.isDark) Color(255, 255, 255, 25) else Color(0, 0, 0, 16))
                g2.fillRoundRect(bx, by, bw, bh, bh, bh)
                g2.color = Theme.TEXT_PRIMARY
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
 * Material 3 "Mais" Screen (MoreScreen / Configs.jpeg):
 * Switches for "Somente disponíveis offline" & "Modo anônimo", quick menu rows for Fila de Downloads,
 * Categorias, Estatísticas, Dados & Armazenamento, Configurações e Sobre.
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
            border = BorderFactory.createEmptyBorder(14, 18, 14, 18)
        }

        // Header Switch Group (Material 3 Toggle Cards with Switch on right)
        val lblOfflineTitle = JLabel("Somente disponíveis offline").apply { foreground = Theme.TEXT_PRIMARY; font = font.deriveFont(Font.BOLD, 13f) }
        val lblOfflineDesc = JLabel("Filtra todos os itens em sua biblioteca").apply { foreground = Theme.TEXT_MUTED; font = font.deriveFont(11f) }
        val lblOfflineIcon = JLabel(ModernIcons.offline(20) { Theme.TEXT_PRIMARY })

        val lblIncognitoTitle = JLabel("Modo anônimo").apply { foreground = Theme.TEXT_PRIMARY; font = font.deriveFont(Font.BOLD, 13f) }
        val lblIncognitoDesc = JLabel("Pausa o histórico de leitura").apply { foreground = Theme.TEXT_MUTED; font = font.deriveFont(11f) }
        val lblIncognitoIcon = JLabel(ModernIcons.incognito(20) { Theme.TEXT_PRIMARY })

        fun createSwitchRow(iconLbl: JLabel, titleLbl: JLabel, descLbl: JLabel, switchComp: M3Switch): JPanel {
            val row = JPanel(BorderLayout(10, 0)).apply {
                isOpaque = false
                alignmentX = 0f
                maximumSize = Dimension(Int.MAX_VALUE, 38)
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                val left = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
                    isOpaque = false
                    add(iconLbl)
                    add(
                        JPanel().apply {
                            isOpaque = false
                            layout = BoxLayout(this, BoxLayout.Y_AXIS)
                            add(titleLbl)
                            add(descLbl)
                        },
                    )
                }
                add(left, BorderLayout.WEST)
                add(switchComp, BorderLayout.EAST)

                fun bindClick(c: java.awt.Component) {
                    c.addMouseListener(object : MouseAdapter() {
                        override fun mousePressed(e: MouseEvent) {
                            if (c !== switchComp) {
                                switchComp.isSelected = !switchComp.isSelected
                            }
                        }
                    })
                    if (c is java.awt.Container) {
                        c.components.forEach { bindClick(it) }
                    }
                }
                bindClick(this)
            }
            return row
        }

        val offlineRow = createSwitchRow(lblOfflineIcon, lblOfflineTitle, lblOfflineDesc, offlineSwitch)
        val incognitoRow = createSwitchRow(lblIncognitoIcon, lblIncognitoTitle, lblIncognitoDesc, incognitoSwitch)

        val switchGroup = JPanel().apply {
            background = Theme.BG_SURFACE
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                BorderFactory.createEmptyBorder(8, 14, 8, 14),
            )
            alignmentX = 0f
            maximumSize = Dimension(Int.MAX_VALUE, 96)

            add(offlineRow)
            add(Box.createVerticalStrut(8))
            add(incognitoRow)
        }
        content.add(switchGroup)
        content.add(Box.createVerticalStrut(10))

        // Action Rows from Configs.jpeg with modern vector icons
        val rows = listOf(
            Triple(ModernIcons.downloads(20) { Theme.TEXT_PRIMARY }, "Fila de downloads", "Gerencie os capítulos sendo baixados"),
            Triple(ModernIcons.categories(20) { Theme.TEXT_PRIMARY }, "Categorias", "Organize os mangás da biblioteca"),
            Triple(ModernIcons.statistics(20) { Theme.TEXT_PRIMARY }, "Estatísticas", "Tempo de leitura e mangás lidos"),
            Triple(ModernIcons.storage(20) { Theme.TEXT_PRIMARY }, "Dados e armazenamento", "Limpar caches e backups"),
            Triple(ModernIcons.settings(20) { Theme.TEXT_PRIMARY }, "Configurações", "Preferências gerais do Mihon Linux"),
            Triple(ModernIcons.about(20) { Theme.TEXT_PRIMARY }, "Sobre", "Mihon Linux v0.3.0"),
        )

        data class RowHolders(val row: JPanel, val titleLbl: JLabel, val descLbl: JLabel, val chevLbl: JLabel)
        val rowHolders = mutableListOf<RowHolders>()

        rows.forEach { (icon, title, desc) ->
            val titleLbl = JLabel(title).apply { font = font.deriveFont(Font.BOLD, 13f); foreground = Theme.TEXT_PRIMARY }
            val descLbl = JLabel(desc).apply { font = font.deriveFont(Font.PLAIN, 11f); foreground = Theme.TEXT_MUTED }
            val chevLbl = JLabel(ModernIcons.chevronRight(16) { Theme.TEXT_MUTED })

            val row = JPanel(BorderLayout(10, 0)).apply {
                background = Theme.BG_SURFACE
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                    BorderFactory.createEmptyBorder(8, 14, 8, 14),
                )
                alignmentX = 0f
                maximumSize = Dimension(Int.MAX_VALUE, 48)
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)

                val left = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
                    isOpaque = false
                    add(JLabel(icon))
                    add(
                        JPanel().apply {
                            isOpaque = false
                            layout = BoxLayout(this, BoxLayout.Y_AXIS)
                            add(titleLbl)
                            add(descLbl)
                        },
                    )
                }
                add(left, BorderLayout.WEST)
                add(chevLbl, BorderLayout.EAST)

                val rowPanel = this
                val action = {
                    when (title) {
                        "Fila de downloads" -> onOpenDownloadsQueue()
                        "Categorias" -> onOpenCategories()
                        "Estatísticas" -> onOpenStatistics()
                        "Dados e armazenamento" -> onOpenStorageManager()
                        "Configurações" -> onOpenSettings()
                        "Sobre" -> onOpenAbout()
                        else -> ctx.setStatus("Opção '$title' selecionada")
                    }
                }

                fun bindClick(c: java.awt.Component) {
                    c.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                    c.addMouseListener(object : MouseAdapter() {
                        override fun mousePressed(e: MouseEvent) {
                            action()
                        }
                        override fun mouseEntered(e: MouseEvent) {
                            rowPanel.background = Theme.BG_CARD_HOVER
                        }
                        override fun mouseExited(e: MouseEvent) {
                            rowPanel.background = Theme.BG_SURFACE
                        }
                    })
                    if (c is java.awt.Container) {
                        c.components.forEach { bindClick(it) }
                    }
                }
                bindClick(this)
            }
            rowHolders.add(RowHolders(row, titleLbl, descLbl, chevLbl))
            content.add(row)
            content.add(Box.createVerticalStrut(4))
        }

        val scroll = JScrollPane(content).apply {
            border = null
            background = Theme.BG_APP
            viewport.background = Theme.BG_APP
            verticalScrollBar.unitIncrement = 24
        }
        add(scroll, BorderLayout.CENTER)

        ctx.addThemeListener {
            background = Theme.BG_APP
            content.background = Theme.BG_APP
            scroll.background = Theme.BG_APP
            scroll.viewport.background = Theme.BG_APP
            lblOfflineIcon.foreground = Theme.TEXT_PRIMARY
            lblOfflineTitle.foreground = Theme.TEXT_PRIMARY
            lblOfflineDesc.foreground = Theme.TEXT_MUTED
            lblIncognitoIcon.foreground = Theme.TEXT_PRIMARY
            lblIncognitoTitle.foreground = Theme.TEXT_PRIMARY
            lblIncognitoDesc.foreground = Theme.TEXT_MUTED
            switchGroup.background = Theme.BG_SURFACE
            switchGroup.border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                BorderFactory.createEmptyBorder(8, 14, 8, 14),
            )
            offlineSwitch.repaint()
            incognitoSwitch.repaint()
            rowHolders.forEach { (row, titleLbl, descLbl, chevLbl) ->
                row.background = Theme.BG_SURFACE
                row.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                    BorderFactory.createEmptyBorder(8, 14, 8, 14),
                )
                titleLbl.foreground = Theme.TEXT_PRIMARY
                descLbl.foreground = Theme.TEXT_MUTED
                chevLbl.foreground = Theme.TEXT_MUTED
            }
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
