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
private val prefs: Preferences = Preferences.userRoot().node("mihon-linux")
private val readerPrefs: Preferences = Preferences.userRoot().node("mihon-linux-reader")

internal fun configureTheme(dark: Boolean) {
    Theme.setDarkMode(dark)

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

internal fun applyTheme(dark: Boolean) {
    prefs.putBoolean(PREF_DARK, dark)
    configureTheme(dark)

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
    val isDark = prefs.getBoolean(PREF_DARK, true)
    configureTheme(isDark)
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

class MihonApp : JFrame("Mihon Linux v0.3.0"), Navigator {
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
        jMenuBar = menuBar()

        ctx.addThemeListener {
            side.background = Theme.BG_SIDEBAR
            side.border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(20, 14, 18, 14),
            )
            brandLabel.foreground = Theme.TEXT_PRIMARY
            versionLabel.foreground = Theme.TEXT_MUTED
            content.background = Theme.BG_APP
            statusBar.background = Theme.BG_SIDEBAR
            statusBar.foreground = Theme.TEXT_MUTED
            jMenuBar = menuBar()
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

    private fun navigate(card: String, highlight: String = card) {
        cards.show(content, card)
        navButtons[highlight]?.isSelected = true
    }

    private lateinit var brandLabel: JLabel
    private lateinit var versionLabel: JLabel

    private fun sidebar(): JPanel {
        val panel = JPanel().apply {
            background = Theme.BG_SIDEBAR
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(20, 14, 18, 14),
            )
            preferredSize = Dimension(220, 0)
        }
        brandLabel = JLabel("Mihon", SwingConstants.LEFT).apply {
            font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 22f)
            foreground = Theme.TEXT_PRIMARY
        }
        val brandRow = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
            isOpaque = false
            alignmentX = 0f
            maximumSize = Dimension(Int.MAX_VALUE, 40)
            add(brandLabel)
        }
        panel.add(brandRow)
        panel.add(Box.createVerticalStrut(2))
        versionLabel = JLabel("  Desktop Linux • v0.3").apply {
            font = Theme.FONT_MONO.deriveFont(Font.PLAIN, 11f)
            foreground = Theme.TEXT_MUTED
            alignmentX = 0f
        }
        panel.add(versionLabel)
        panel.add(Box.createVerticalStrut(22))

        val group = ButtonGroup()
        // 5 Primary Material 3 destinations matching Mihon Android's NavigationBar:
        // Biblioteca, Atualizações, Histórico, Navegar, Mais
        listOf(
            Triple(LIBRARY, "Biblioteca", null),
            Triple(UPDATES, "Atualizações", null),
            Triple(HISTORY, "Histórico", null),
            Triple(BROWSE, "Navegar", null),
            Triple(MORE, "Mais", null),
        ).forEach { (card, label, badge) ->
            val button = M3NavPillButton(label, badge).apply {
                alignmentX = 0f
                maximumSize = Dimension(Int.MAX_VALUE, 44)
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
            panel.add(button)
            panel.add(Box.createVerticalStrut(6))
        }
        return panel
    }

    private fun settingsScreen(): JComponent {
        val dark = JCheckBox("Tema escuro (FlatLaf Dark)", prefs.getBoolean(PREF_DARK, true)).apply {
            isOpaque = false
            alignmentX = 0f
            addActionListener { applyTheme(isSelected) }
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
            val saved = runCatching { MangaImageEnhancer.QualityMode.valueOf(readerPrefs.get("quality", "ULTRA")) }
                .getOrDefault(MangaImageEnhancer.QualityMode.ULTRA)
            selectedIndex = saved.ordinal
            maximumSize = Dimension(320, 32)
            alignmentX = 0f
            addActionListener { readerPrefs.put("quality", MangaImageEnhancer.QualityMode.entries[selectedIndex].name) }
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

        val lblMode = JLabel("Modo de leitura padrão:").apply { alignmentX = 0f; foreground = Theme.TEXT_PRIMARY }
        val lblFit = JLabel("Ajuste de página padrão:").apply { alignmentX = 0f; foreground = Theme.TEXT_PRIMARY }
        val lblQuality = JLabel("Qualidade de renderização e Upscaling (Zoom):").apply { alignmentX = 0f; foreground = Theme.TEXT_PRIMARY }
        val lblBackupTitle = JLabel("Backup da Biblioteca e Histórico:").apply { font = font.deriveFont(Font.BOLD, 14f); alignmentX = 0f; foreground = Theme.TEXT_PRIMARY }
        val lblSystemTitle = JLabel("Caminhos do sistema:").apply { font = font.deriveFont(Font.BOLD, 14f); alignmentX = 0f; foreground = Theme.TEXT_PRIMARY }
        val lblDb = JLabel("Banco SQLite: ${dbFile.absolutePath}").apply { alignmentX = 0f; foreground = Theme.TEXT_MUTED }
        val lblDl = JLabel("Downloads: ${ctx.service.downloadsDir.absolutePath}").apply { alignmentX = 0f; foreground = Theme.TEXT_MUTED }
        val lblSources = JLabel("Fontes JSON: ${File(home, ".mihon/sources").absolutePath}").apply { alignmentX = 0f; foreground = Theme.TEXT_MUTED }
        val lblLogs = JLabel("Logs: ${File(home, ".mihon/logs/mihon.log").absolutePath}").apply { alignmentX = 0f; foreground = Theme.TEXT_MUTED }

        val panel = JPanel().apply {
            background = Theme.BG_APP
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(20, 24, 20, 24)
            add(topBar)
            add(Box.createVerticalStrut(18))

            add(dark)
            add(Box.createVerticalStrut(8))

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
            add(Box.createVerticalStrut(12))

            add(lblQuality)
            add(Box.createVerticalStrut(4))
            add(defaultQualityBox)
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

            add(Box.createVerticalGlue())
        }

        val scroll = JScrollPane(panel).apply {
            border = null
            background = Theme.BG_APP
            viewport.background = Theme.BG_APP
            verticalScrollBar.unitIncrement = 20
        }

        ctx.addThemeListener { isDark ->
            dark.isSelected = isDark
            dark.isOpaque = false
            incognitoCheck.isOpaque = false
            autoPrefetchCheck.isOpaque = false
            panel.background = Theme.BG_APP
            scroll.background = Theme.BG_APP
            scroll.viewport.background = Theme.BG_APP
            lblDb.foreground = Theme.TEXT_MUTED
            lblDl.foreground = Theme.TEXT_MUTED
            lblSources.foreground = Theme.TEXT_MUTED
            lblLogs.foreground = Theme.TEXT_MUTED
            scroll.repaint()
        }

        return scroll
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
 * Material 3 Navigation Pill Button (emulating Mihon Android's active pill selection and badge counters).
 */
class M3NavPillButton(private val label: String, private val badgeText: String? = null) : JToggleButton() {
    init {
        isOpaque = false
        isFocusable = false
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        border = null
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
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

        val pillX = 4
        val pillY = 3
        val pillW = w - 8
        val pillH = h - 6
        // Material 3 Stadium Pill: corner arc equals height so ends are smooth semicircles and top/bottom are straight
        val arc = pillH

        // M3 Active Pill Container
        if (isSelected) {
            g2.color = Theme.BG_PILL_ACTIVE
            g2.fillRoundRect(pillX, pillY, pillW, pillH, arc, arc)
        } else if (model.isRollover) {
            g2.color = Theme.BG_CARD_HOVER
            g2.fillRoundRect(pillX, pillY, pillW, pillH, arc, arc)
        }

        g2.font = font.deriveFont(if (isSelected) Font.BOLD else Font.PLAIN, 13f)
        g2.color = if (isSelected) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY
        val fm = g2.fontMetrics
        g2.drawString(label, 20, ((h - fm.height) / 2) + fm.ascent)

        // Subtle M3 Counter Badge on the right (if provided)
        if (!badgeText.isNullOrBlank()) {
            g2.font = Theme.FONT_MONO.deriveFont(Font.BOLD, 10.5f)
            val bFm = g2.fontMetrics
            val bw = (bFm.stringWidth(badgeText) + 12).coerceAtLeast(20)
            val bh = 18
            val bx = w - bw - 14
            val by = (h - bh) / 2
            g2.color = if (isSelected) (if (Theme.isDark) Color(255, 255, 255, 45) else Color(0, 0, 0, 30)) else (if (Theme.isDark) Color(255, 255, 255, 25) else Color(0, 0, 0, 16))
            g2.fillRoundRect(bx, by, bw, bh, bh, bh)
            g2.color = Theme.TEXT_PRIMARY
            g2.drawString(badgeText, bx + (bw - bFm.stringWidth(badgeText)) / 2, by + ((bh - bFm.height) / 2) + bFm.ascent)
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

    private val offlineSwitch = JCheckBox("Somente disponíveis offline", ctx.onlyOfflineFilter).apply {
        isOpaque = false
        font = font.deriveFont(Font.BOLD, 14f)
        toolTipText = "Filtra todos os itens da sua biblioteca para exibir apenas mangás salvos localmente"
        addActionListener {
            ctx.setOfflineFilter(isSelected)
            ctx.setStatus(if (isSelected) "Filtro: Somente disponíveis offline ativado" else "Filtro offline desativado")
        }
    }

    private val incognitoSwitch = JCheckBox("Modo anônimo", ctx.incognitoMode).apply {
        isOpaque = false
        font = font.deriveFont(Font.BOLD, 14f)
        toolTipText = "Pausa temporariamente a gravação no histórico de leitura"
        addActionListener {
            ctx.incognitoMode = isSelected
            ctx.setStatus(if (isSelected) "Modo anônimo ativado (histórico pausado)" else "Modo anônimo desativado")
        }
    }

    init {
        ctx.addOfflineFilterListener { offlineSwitch.isSelected = it }
        background = Theme.BG_APP
        val content = JPanel().apply {
            background = Theme.BG_APP
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(24, 32, 24, 32)
        }

        // Header Switch Group (Material 3 Toggle Cards)
        val lblOfflineDesc = JLabel("Filtra todos os itens em sua biblioteca").apply { foreground = Theme.TEXT_MUTED; font = font.deriveFont(11.5f) }
        val lblIncognitoDesc = JLabel("Pausa o histórico de leitura").apply { foreground = Theme.TEXT_MUTED; font = font.deriveFont(11.5f) }

        val switchGroup = JPanel().apply {
            background = Theme.BG_SURFACE
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                BorderFactory.createEmptyBorder(16, 20, 16, 20),
            )
            alignmentX = 0f
            maximumSize = Dimension(Int.MAX_VALUE, 130)

            add(offlineSwitch)
            add(lblOfflineDesc)
            add(Box.createVerticalStrut(14))
            add(incognitoSwitch)
            add(lblIncognitoDesc)
        }
        content.add(switchGroup)
        content.add(Box.createVerticalStrut(20))

        // Action Rows from Configs.jpeg
        val rows = listOf(
            Triple("📥", "Fila de downloads", "Gerencie os capítulos sendo baixados"),
            Triple("📁", "Categorias", "Organize os mangás da biblioteca"),
            Triple("📈", "Estatísticas", "Tempo de leitura e mangás lidos"),
            Triple("🗄️", "Dados e armazenamento", "Limpar caches e backups"),
            Triple("⚙️", "Configurações", "Preferências gerais do Mihon Linux"),
            Triple("ℹ️", "Sobre", "Mihon Linux v0.3.0"),
        )

        data class RowHolders(val row: JPanel, val titleLbl: JLabel, val descLbl: JLabel, val chevLbl: JLabel)
        val rowHolders = mutableListOf<RowHolders>()

        rows.forEach { (icon, title, desc) ->
            val titleLbl = JLabel(title).apply { font = font.deriveFont(Font.BOLD, 13.5f); foreground = Theme.TEXT_PRIMARY }
            val descLbl = JLabel(desc).apply { font = font.deriveFont(Font.PLAIN, 11f); foreground = Theme.TEXT_MUTED }
            val chevLbl = JLabel("›").apply { font = font.deriveFont(Font.BOLD, 18f); foreground = Theme.TEXT_MUTED }

            val row = JPanel(BorderLayout(14, 0)).apply {
                background = Theme.BG_SURFACE
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                    BorderFactory.createEmptyBorder(14, 18, 14, 18),
                )
                alignmentX = 0f
                maximumSize = Dimension(Int.MAX_VALUE, 64)
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)

                val left = JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)).apply {
                    isOpaque = false
                    add(JLabel(icon).apply { font = font.deriveFont(18f) })
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
            content.add(Box.createVerticalStrut(8))
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
            offlineSwitch.foreground = Theme.TEXT_PRIMARY
            incognitoSwitch.foreground = Theme.TEXT_PRIMARY
            lblOfflineDesc.foreground = Theme.TEXT_MUTED
            lblIncognitoDesc.foreground = Theme.TEXT_MUTED
            switchGroup.background = Theme.BG_SURFACE
            switchGroup.border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                BorderFactory.createEmptyBorder(16, 20, 16, 20),
            )
            rowHolders.forEach { (row, titleLbl, descLbl, chevLbl) ->
                row.background = Theme.BG_SURFACE
                row.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                    BorderFactory.createEmptyBorder(14, 18, 14, 18),
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
