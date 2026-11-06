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
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

private const val PREF_DARK = "darkTheme"
private val prefs: Preferences = Preferences.userRoot().node("mihon-linux")
private val readerPrefs: Preferences = Preferences.userRoot().node("mihon-linux-reader")

internal fun applyTheme(dark: Boolean) {
    prefs.putBoolean(PREF_DARK, dark)
    if (dark) FlatDarkLaf.setup() else FlatLightLaf.setup()
    FlatLaf.updateUI()
}

fun main() {
    setupUncaughtErrorHandling()
    if (prefs.getBoolean(PREF_DARK, true)) FlatDarkLaf.setup() else FlatLightLaf.setup()
    UIManager.put("Panel.background", Theme.BG_APP)
    UIManager.put("ScrollPane.background", Theme.BG_APP)
    UIManager.put("Viewport.background", Theme.BG_APP)
    UIManager.put("List.background", Theme.BG_APP)
    UIManager.put("Button.background", Theme.BG_CARD)
    UIManager.put("Button.foreground", Theme.TEXT_PRIMARY)
    UIManager.put("ComboBox.background", Theme.BG_CARD)
    UIManager.put("TextField.background", Theme.BG_CARD)
    UIManager.put("ToggleButton.selectedBackground", Theme.ACCENT)
    UIManager.put("ToggleButton.selectedForeground", Color.WHITE)
    UIManager.put("Component.arc", 14)
    UIManager.put("Button.arc", 12)
    UIManager.put("TextComponent.arc", 12)
    UIManager.put("ProgressBar.arc", 12)
    UIManager.put("ScrollBar.thumbArc", 999)
    UIManager.put("ScrollBar.width", 10)
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
    private val historyScreen = HistoryScreen(ctx)
    private val navButtons = linkedMapOf<String, JToggleButton>()

    init {
        ctx.navigator = this
        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(1220, 820)
        setLocationRelativeTo(null)

        content.add(library, LIBRARY)
        content.add(details, DETAILS)
        content.add(SearchScreen(ctx), SEARCH)
        content.add(historyScreen, HISTORY)
        content.add(ExtensionsScreen(ctx), EXTENSIONS)
        content.add(settingsScreen(), SETTINGS)

        add(sidebar(), BorderLayout.WEST)
        add(content, BorderLayout.CENTER)
        statusBar.border = BorderFactory.createEmptyBorder(5, 10, 5, 10)
        add(statusBar, BorderLayout.SOUTH)
        jMenuBar = menuBar()

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

    private fun sidebar(): JPanel {
        val panel = JPanel().apply {
            background = Theme.BG_SIDEBAR
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(22, 14, 18, 14),
            )
            preferredSize = Dimension(224, 0)
        }
        val brandRow = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
            isOpaque = false
            alignmentX = 0f
            maximumSize = Dimension(Int.MAX_VALUE, 38)
            // Hanko Vermilion Seal Mark ("本" - Book/Origin)
            add(
                JLabel("本", SwingConstants.CENTER).apply {
                    isOpaque = true
                    background = Theme.ACCENT
                    foreground = Color.WHITE
                    font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 16f)
                    preferredSize = Dimension(30, 30)
                },
            )
            add(
                JLabel("Mihon").apply {
                    font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 22f)
                    foreground = Theme.TEXT_PRIMARY
                },
            )
        }
        panel.add(brandRow)
        panel.add(Box.createVerticalStrut(4))
        panel.add(
            JLabel("  Edição Linux • v0.3").apply {
                font = Theme.FONT_MONO.deriveFont(Font.PLAIN, 11f)
                foreground = Theme.TEXT_MUTED
                alignmentX = 0f
            },
        )
        panel.add(Box.createVerticalStrut(24))
        val group = ButtonGroup()
        listOf(
            LIBRARY to "Acervo da Biblioteca",
            SEARCH to "Explorar Catálogo",
            HISTORY to "Leituras Recentes",
            EXTENSIONS to "Fontes Instaladas",
            SETTINGS to "Preferências",
        ).forEach { (card, label) ->
            val button = JToggleButton(label).apply {
                alignmentX = 0f
                maximumSize = Dimension(Int.MAX_VALUE, 40)
                font = font.deriveFont(Font.BOLD, 13f)
                horizontalAlignment = SwingConstants.LEFT
                addActionListener {
                    when (card) {
                        LIBRARY -> showLibrary()
                        HISTORY -> showHistory()
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

    private fun settingsScreen(): JPanel {
        val dark = JCheckBox("Tema escuro (FlatLaf Dark)", prefs.getBoolean(PREF_DARK, true))
        dark.addActionListener { applyTheme(dark.isSelected) }

        val defaultModeBox = JComboBox(ReadMode.entries.map { it.label }.toTypedArray()).apply {
            val saved = runCatching { ReadMode.valueOf(readerPrefs.get("mode", "SINGLE")) }.getOrDefault(ReadMode.SINGLE)
            selectedIndex = saved.ordinal
            maximumSize = Dimension(280, 32)
            addActionListener { readerPrefs.put("mode", ReadMode.entries[selectedIndex].name) }
        }
        val defaultFitBox = JComboBox(FitMode.entries.map { it.label }.toTypedArray()).apply {
            val saved = runCatching { FitMode.valueOf(readerPrefs.get("fit", "WINDOW")) }.getOrDefault(FitMode.WINDOW)
            selectedIndex = saved.ordinal
            maximumSize = Dimension(280, 32)
            addActionListener { readerPrefs.put("fit", FitMode.entries[selectedIndex].name) }
        }
        val defaultQualityBox = JComboBox(MangaImageEnhancer.QualityMode.entries.map { it.label }.toTypedArray()).apply {
            val saved = runCatching { MangaImageEnhancer.QualityMode.valueOf(readerPrefs.get("quality", "ULTRA")) }
                .getOrDefault(MangaImageEnhancer.QualityMode.ULTRA)
            selectedIndex = saved.ordinal
            maximumSize = Dimension(320, 32)
            addActionListener { readerPrefs.put("quality", MangaImageEnhancer.QualityMode.entries[selectedIndex].name) }
        }

        val btnBackup = JButton("Exportar Backup (.zip)").apply { addActionListener { exportBackup() } }
        val btnRestore = JButton("Restaurar Backup (.zip)").apply { addActionListener { importBackup() } }
        val backupRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = 0f
            add(btnBackup)
            add(Box.createHorizontalStrut(10))
            add(btnRestore)
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(20, 24, 20, 24)
            add(JLabel("Configurações").apply { font = font.deriveFont(Font.BOLD, 20f); alignmentX = 0f })
            add(Box.createVerticalStrut(18))

            dark.alignmentX = 0f
            add(dark)
            add(Box.createVerticalStrut(16))

            add(JLabel("Modo de leitura padrão:").apply { alignmentX = 0f })
            add(Box.createVerticalStrut(4))
            defaultModeBox.alignmentX = 0f
            add(defaultModeBox)
            add(Box.createVerticalStrut(12))

            add(JLabel("Ajuste de página padrão:").apply { alignmentX = 0f })
            add(Box.createVerticalStrut(4))
            defaultFitBox.alignmentX = 0f
            add(defaultFitBox)
            add(Box.createVerticalStrut(12))

            add(JLabel("Qualidade de renderização e Upscaling (Zoom):").apply { alignmentX = 0f })
            add(Box.createVerticalStrut(4))
            defaultQualityBox.alignmentX = 0f
            add(defaultQualityBox)
            add(Box.createVerticalStrut(20))

            add(JLabel("Backup da Biblioteca e Histórico:").apply { font = font.deriveFont(Font.BOLD, 14f); alignmentX = 0f })
            add(Box.createVerticalStrut(8))
            add(backupRow)
            add(Box.createVerticalStrut(24))

            add(JLabel("Caminhos do sistema:").apply { font = font.deriveFont(Font.BOLD, 14f); alignmentX = 0f })
            add(Box.createVerticalStrut(6))
            add(JLabel("Banco SQLite: ${dbFile.absolutePath}").apply { alignmentX = 0f })
            add(JLabel("Downloads: ${ctx.service.downloadsDir.absolutePath}").apply { alignmentX = 0f })
            add(JLabel("Fontes JSON: ${File(home, ".mihon/sources").absolutePath}").apply { alignmentX = 0f })
            add(JLabel("Logs: ${File(home, ".mihon/logs/mihon.log").absolutePath}").apply { alignmentX = 0f })
        }
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
                add(JMenuItem("Sair").apply { addActionListener { System.exit(0) } })
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
        const val SEARCH = "search"
        const val HISTORY = "history"
        const val EXTENSIONS = "extensions"
        const val SETTINGS = "settings"
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
