package mihon.desktop

import tachiyomi.domain.manga.model.Manga
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Library screen ported from Mihon's `LibraryScreen`, `LibraryTabs`, `LibraryToolbar`, and `CommonMangaItem`:
 * - Material 3 primary scrollable category tabs (`LibraryTabs`) with item count badges
 * - Comfortable Grid and Compact Overlay Grid modes
 * - `DownloadsBadge` + `UnreadBadge` + `LanguageBadge` on every manga cover
 */
class LibraryScreen(private val ctx: AppContext) : JPanel(BorderLayout()) {

    private enum class Sort(val label: String) {
        TITLE("Título (A-Z)"),
        UNREAD("Não lidos"),
        ADDED("Recentes"),
        DOWNLOADED("Mais baixados"),
        AUTHOR("Autor (A-Z)"),
    }

    private enum class DisplayMode(val label: String) {
        COMFORTABLE_GRID("Grade confortável"),
        COMPACT_GRID("Grade compacta (título sobre capa)"),
        COMPACT_LIST("Lista compacta detalhada"),
    }

    private var all: List<Manga> = emptyList()
    private var unreadByManga: Map<Long, Int> = emptyMap()
    private var downloadedByManga: Map<Long, Int> = emptyMap()
    private var hoveredIndex: Int = -1
    private var activeCategoryStatus: Long = LibraryCategory.ALL
    private var displayMode: DisplayMode = DisplayMode.COMFORTABLE_GRID
    private var unreadOnlyFilter: Boolean = false
    private var completedOnlyFilter: Boolean = false
    private var selectedGenreFilter: String? = null
    private var selectedSourceFilter: Long? = null
    private var onlyDownloadedFilter: Boolean
        get() = ctx.onlyOfflineFilter
        set(v) { ctx.setOfflineFilter(v) }

    private val model = DefaultListModel<Manga>()
    private val grid = JList(model)
    private val hoverAnimator = GridHoverAnimator(grid)
    private val cardPrototype = MangaPosterCard()
    private val listRowPrototype = MangaListRowCard()
    private val tabsBar = LibraryTabsBar()
    private val centerCards = java.awt.CardLayout()
    private val centerContainer = JPanel(centerCards)
    private val emptyIconLbl = JLabel(ModernIcons.library(56) { Theme.TEXT_MUTED })
    private val emptyTitleLbl = JLabel("Sua biblioteca está vazia")
    private val emptySubLbl = JLabel("Explore fontes online ou importe arquivos locais (.cbz/.zip) para começar.")
    private val emptyActionBtn = ModernButton("Explorar Fontes", ModernIcons.browse(15) { Color.WHITE }, ModernButton.Variant.PRIMARY)
    private var themeListener: ((Boolean) -> Unit)? = null

    private var currentSort: Sort = Sort.TITLE
    private val filterField = JTextField(20).apply {
        putClientProperty("JTextField.placeholderText", "Pesquisar na biblioteca…")
        putClientProperty("JComponent.roundRect", true)
        font = Theme.FONT_UI.deriveFont(13f)
        foreground = Theme.TEXT_PRIMARY
        background = Theme.BG_CARD
        caretColor = Theme.ACCENT
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1, true),
            BorderFactory.createEmptyBorder(6, 12, 6, 12),
        )
        isVisible = false
    }

    private fun m3HeaderIconButton(icon: javax.swing.Icon, tip: String, onClick: (JButton) -> Unit): JButton {
        return ModernIconButton(icon, tip, sizePx = 38).apply {
            foreground = Theme.TEXT_PRIMARY
            addActionListener { onClick(this) }
        }
    }

    init {
        ctx.addOfflineFilterListener { applyFilter() }
        LibraryCategory.addChangeListener {
            tabsBar.repaint()
            applyFilter()
        }
        background = Theme.BG_APP
        grid.background = Theme.BG_APP
        grid.layoutOrientation = JList.HORIZONTAL_WRAP
        grid.visibleRowCount = -1
        grid.fixedCellWidth = CARD_W
        grid.fixedCellHeight = CARD_H
        grid.border = BorderFactory.createEmptyBorder(16, 18, 16, 18)
        grid.cellRenderer = ListCellRenderer { _, manga, index, selected, _ ->
            renderCard(manga, index, selected)
        }
        grid.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val idx = grid.locationToIndex(e.point)
                val valid = if (idx >= 0 && grid.getCellBounds(idx, idx)?.contains(e.point) == true) idx else -1
                grid.cursor = if (valid >= 0) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                if (valid != hoveredIndex) {
                    hoveredIndex = valid
                    hoverAnimator.setHovered(valid)
                }
            }
        })
        grid.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                if (hoveredIndex != -1) {
                    hoveredIndex = -1
                    hoverAnimator.setHovered(-1)
                }
            }

            override fun mousePressed(e: MouseEvent) {
                val index = grid.locationToIndex(e.point)
                if (index < 0 || grid.getCellBounds(index, index)?.contains(e.point) != true) return
                val manga = model[index]
                if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger) {
                    grid.selectedIndex = index
                    showContextMenu(manga, e.x, e.y)
                }
            }

            override fun mouseClicked(e: MouseEvent) {
                val index = grid.locationToIndex(e.point)
                if (index < 0 || grid.getCellBounds(index, index)?.contains(e.point) != true) return
                val manga = model[index]
                if (SwingUtilities.isLeftMouseButton(e)) {
                    ctx.navigator.showDetails(manga)
                }
            }
        })

        filterField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })

        // Material 3 TopAppBar matching Mihon Android's barra superior.jpeg:
        // Left: Clean large title "Biblioteca"
        // Right: Search toggle, Filter & Sort popup, Overflow menu
        val btnSearch = m3HeaderIconButton(ModernIcons.search(20) { Theme.TEXT_PRIMARY }, "Pesquisar") {
            filterField.isVisible = !filterField.isVisible
            if (filterField.isVisible) {
                filterField.requestFocusInWindow()
            } else {
                filterField.text = ""
                applyFilter()
            }
            revalidate()
            repaint()
        }

        val btnFilter = m3HeaderIconButton(ModernIcons.filter(20) { Theme.TEXT_PRIMARY }, "Filtros e exibição") { anchor ->
            val popup = JPopupMenu().apply {
                background = Theme.BG_SURFACE
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1, true),
                    BorderFactory.createEmptyBorder(6, 6, 6, 6),
                )
            }

            val filterHeader = JMenuItem("FILTRAR POR:").apply {
                icon = ModernIcons.filter(14) { Theme.TEXT_MUTED }
                isEnabled = false
                font = Theme.FONT_UI.deriveFont(Font.BOLD, 10.5f)
                foreground = Theme.TEXT_MUTED
                background = Theme.BG_SURFACE
            }
            popup.add(filterHeader)

            val offlineItem = JCheckBoxMenuItem("Somente disponíveis offline", onlyDownloadedFilter).apply {
                icon = ModernIcons.offline(16) { if (isSelected) Theme.ACCENT else Theme.TEXT_MUTED }
                font = Theme.FONT_UI.deriveFont(13f)
                foreground = Theme.TEXT_PRIMARY
                background = Theme.BG_SURFACE
                addActionListener {
                    onlyDownloadedFilter = isSelected
                    applyFilter()
                }
            }
            val unreadItem = JCheckBoxMenuItem("Apenas com capítulos não lidos", unreadOnlyFilter).apply {
                icon = ModernIcons.checkCircle(16) { if (isSelected) Theme.ACCENT else Theme.TEXT_MUTED }
                font = Theme.FONT_UI.deriveFont(13f)
                foreground = Theme.TEXT_PRIMARY
                background = Theme.BG_SURFACE
                addActionListener {
                    unreadOnlyFilter = isSelected
                    if (isSelected) completedOnlyFilter = false
                    applyFilter()
                }
            }
            val completedItem = JCheckBoxMenuItem("Apenas leitura em dia (0 não lidos)", completedOnlyFilter).apply {
                icon = ModernIcons.check(16) { if (isSelected) Theme.ACCENT else Theme.TEXT_MUTED }
                font = Theme.FONT_UI.deriveFont(13f)
                foreground = Theme.TEXT_PRIMARY
                background = Theme.BG_SURFACE
                addActionListener {
                    completedOnlyFilter = isSelected
                    if (isSelected) unreadOnlyFilter = false
                    applyFilter()
                }
            }
            popup.add(offlineItem)
            popup.add(unreadItem)
            popup.add(completedItem)

            // Dynamic Genre Submenu
            val allGenres = all.flatMap { it.genre.orEmpty() }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
            if (allGenres.isNotEmpty()) {
                val genreMenu = javax.swing.JMenu("Gênero: ${selectedGenreFilter ?: "Todos"}").apply {
                    font = Theme.FONT_UI.deriveFont(13f)
                    foreground = Theme.TEXT_PRIMARY
                    background = Theme.BG_SURFACE
                }
                genreMenu.add(JMenuItem("Todos os gêneros").apply {
                    font = Theme.FONT_UI.deriveFont(13f)
                    addActionListener {
                        selectedGenreFilter = null
                        applyFilter()
                    }
                })
                genreMenu.addSeparator()
                allGenres.take(25).forEach { gName ->
                    genreMenu.add(JCheckBoxMenuItem(gName, selectedGenreFilter.equals(gName, ignoreCase = true)).apply {
                        font = Theme.FONT_UI.deriveFont(13f)
                        addActionListener {
                            selectedGenreFilter = if (selectedGenreFilter.equals(gName, ignoreCase = true)) null else gName
                            applyFilter()
                        }
                    })
                }
                popup.add(genreMenu)
            }

            if (onlyDownloadedFilter || unreadOnlyFilter || completedOnlyFilter || selectedGenreFilter != null || selectedSourceFilter != null) {
                popup.add(JMenuItem("Limpar filtros ativos").apply {
                    font = Theme.FONT_UI.deriveFont(Font.BOLD, 12.5f)
                    foreground = Theme.DANGER
                    background = Theme.BG_SURFACE
                    addActionListener {
                        onlyDownloadedFilter = false
                        unreadOnlyFilter = false
                        completedOnlyFilter = false
                        selectedGenreFilter = null
                        selectedSourceFilter = null
                        applyFilter()
                    }
                })
            }

            popup.addSeparator()
            val displayHeader = JMenuItem("MODO DE EXIBIÇÃO:").apply {
                icon = ModernIcons.library(14) { Theme.TEXT_MUTED }
                isEnabled = false
                font = Theme.FONT_UI.deriveFont(Font.BOLD, 10.5f)
                foreground = Theme.TEXT_MUTED
                background = Theme.BG_SURFACE
            }
            popup.add(displayHeader)

            val displayGroup = ButtonGroup()
            DisplayMode.entries.forEach { mode ->
                val rdo = JRadioButtonMenuItem(mode.label, mode == displayMode).apply {
                    font = Theme.FONT_UI.deriveFont(13f)
                    foreground = Theme.TEXT_PRIMARY
                    background = Theme.BG_SURFACE
                    addActionListener {
                        applyDisplayMode(mode)
                    }
                }
                displayGroup.add(rdo)
                popup.add(rdo)
            }

            popup.addSeparator()

            val sortMenuLabel = JMenuItem("ORDENAR POR:").apply {
                icon = ModernIcons.sort(14) { Theme.TEXT_MUTED }
                isEnabled = false
                font = Theme.FONT_UI.deriveFont(Font.BOLD, 10.5f)
                foreground = Theme.TEXT_MUTED
                background = Theme.BG_SURFACE
            }
            popup.add(sortMenuLabel)

            val sortGroup = ButtonGroup()
            Sort.entries.forEach { sort ->
                val rdo = JRadioButtonMenuItem(sort.label, sort == currentSort).apply {
                    font = Theme.FONT_UI.deriveFont(13f)
                    foreground = Theme.TEXT_PRIMARY
                    background = Theme.BG_SURFACE
                    addActionListener {
                        currentSort = sort
                        applyFilter()
                    }
                }
                sortGroup.add(rdo)
                popup.add(rdo)
            }

            popup.show(anchor, 0, anchor.height + 4)
        }

        val btnOverflow = m3HeaderIconButton(ModernIcons.moreVert(20) { Theme.TEXT_PRIMARY }, "Mais opções") { anchor ->
            val popup = JPopupMenu().apply {
                background = Theme.BG_SURFACE
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1, true),
                    BorderFactory.createEmptyBorder(6, 6, 6, 6),
                )
            }
            popup.add(JMenuItem("Atualizar biblioteca", ModernIcons.sync(16) { Theme.TEXT_PRIMARY }).apply {
                font = Theme.FONT_UI.deriveFont(13f)
                foreground = Theme.TEXT_PRIMARY
                background = Theme.BG_SURFACE
                addActionListener { checkUpdatesForAll() }
            })
            popup.addSeparator()
            popup.add(JMenuItem("Adicionar arquivo (.cbz / .zip)...", ModernIcons.downloads(16) { Theme.TEXT_PRIMARY }).apply {
                font = Theme.FONT_UI.deriveFont(13f)
                foreground = Theme.TEXT_PRIMARY
                background = Theme.BG_SURFACE
                addActionListener { importLocal(directories = false) }
            })
            popup.add(JMenuItem("Adicionar pasta de mangás...", ModernIcons.library(16) { Theme.TEXT_PRIMARY }).apply {
                font = Theme.FONT_UI.deriveFont(13f)
                foreground = Theme.TEXT_PRIMARY
                background = Theme.BG_SURFACE
                addActionListener { importLocal(directories = true) }
            })
            popup.show(anchor, 0, anchor.height + 4)
        }

        val titleLabel = JLabel("Biblioteca").apply {
            font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 22f)
            foreground = Theme.TEXT_PRIMARY
            border = BorderFactory.createEmptyBorder(0, 0, 0, 8)
        }

        val topAppBar = JPanel(BorderLayout(8, 0)).apply {
            background = Theme.BG_APP
            border = BorderFactory.createEmptyBorder(12, 16, 10, 16)
            add(titleLabel, BorderLayout.WEST)

            val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(filterField)
                add(btnSearch)
                add(btnFilter)
                add(btnOverflow)
            }
            add(actions, BorderLayout.CENTER)
        }

        val topHeader = JPanel(BorderLayout()).apply {
            background = Theme.BG_APP
            add(topAppBar, BorderLayout.NORTH)
            add(tabsBar, BorderLayout.SOUTH)
        }

        val scroll = JScrollPane(grid).apply {
            border = null
            background = Theme.BG_APP
            viewport.background = Theme.BG_APP
            verticalScrollBar.unitIncrement = 28
        }
        emptyIconLbl.alignmentX = 0.5f
        emptyTitleLbl.apply {
            font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 18f)
            foreground = Theme.TEXT_PRIMARY
            alignmentX = 0.5f
        }
        emptySubLbl.apply {
            font = Theme.FONT_UI.deriveFont(Font.PLAIN, 13f)
            foreground = Theme.TEXT_MUTED
            alignmentX = 0.5f
        }
        emptyActionBtn.apply {
            alignmentX = 0.5f
            addActionListener {
                if (all.isEmpty()) {
                    ctx.navigator.openSourceCatalog(ctx.extensions.firstOrNull()?.name ?: "")
                } else {
                    filterField.text = ""
                    onlyDownloadedFilter = false
                    unreadOnlyFilter = false
                    completedOnlyFilter = false
                    selectedGenreFilter = null
                    selectedSourceFilter = null
                    activeCategoryStatus = LibraryCategory.ALL
                    tabsBar.repaint()
                    applyFilter()
                }
            }
        }
        val emptyBox = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(emptyIconLbl)
            add(Box.createVerticalStrut(14))
            add(emptyTitleLbl)
            add(Box.createVerticalStrut(6))
            add(emptySubLbl)
            add(Box.createVerticalStrut(18))
            add(emptyActionBtn)
        }
        val emptyWrap = JPanel(java.awt.GridBagLayout()).apply {
            background = Theme.BG_APP
            add(emptyBox)
        }
        centerContainer.background = Theme.BG_APP
        centerContainer.add(scroll, "grid")
        centerContainer.add(emptyWrap, "empty")
        add(topHeader, BorderLayout.NORTH)
        add(centerContainer, BorderLayout.CENTER)

        val listener = { _: Boolean ->
            background = Theme.BG_APP
            grid.background = Theme.BG_APP
            centerContainer.background = Theme.BG_APP
            emptyWrap.background = Theme.BG_APP
            emptyTitleLbl.foreground = Theme.TEXT_PRIMARY
            emptySubLbl.foreground = Theme.TEXT_MUTED
            topAppBar.background = Theme.BG_APP
            topHeader.background = Theme.BG_APP
            tabsBar.background = Theme.BG_SURFACE
            titleLabel.foreground = Theme.TEXT_PRIMARY
            btnSearch.foreground = Theme.TEXT_PRIMARY
            btnFilter.foreground = Theme.TEXT_PRIMARY
            btnOverflow.foreground = Theme.TEXT_PRIMARY
            scroll.background = Theme.BG_APP
            scroll.viewport.background = Theme.BG_APP
            grid.repaint()
            emptyIconLbl.repaint()
            repaint()
        }
        themeListener = listener
        ctx.addThemeListener(listener)
    }

    fun dispose() {
        hoverAnimator.stop()
        themeListener?.let { ctx.removeThemeListener(it) }
    }

    private fun showContextMenu(manga: Manga, x: Int, y: Int) {
        val gooeyItems = listOf(
            GooeyContextMenu.GooeyMenuItem(
                id = "details",
                label = "Abrir detalhes",
                iconType = GooeyContextMenu.IconType.OPEN_DETAILS,
                onClick = { ctx.navigator.showDetails(manga) },
            ),
            GooeyContextMenu.GooeyMenuItem(
                id = "reading",
                label = "Mover para: Lendo",
                iconType = GooeyContextMenu.IconType.READING,
                onClick = {
                    background({ ctx.service.setCategory(manga.id, LibraryCategory.READING) }) {
                        ctx.setStatus("'${manga.title}' movido para: Lendo")
                        refresh()
                    }
                },
            ),
            GooeyContextMenu.GooeyMenuItem(
                id = "completed",
                label = "Mover para: Completos",
                iconType = GooeyContextMenu.IconType.COMPLETED,
                onClick = {
                    background({ ctx.service.setCategory(manga.id, LibraryCategory.COMPLETED) }) {
                        ctx.setStatus("'${manga.title}' movido para: Completos")
                        refresh()
                    }
                },
            ),
            GooeyContextMenu.GooeyMenuItem(
                id = "plan_to_read",
                label = "Mover para: Quero ler",
                iconType = GooeyContextMenu.IconType.PLAN_TO_READ,
                onClick = {
                    background({ ctx.service.setCategory(manga.id, LibraryCategory.PLAN_TO_READ) }) {
                        ctx.setStatus("'${manga.title}' movido para: Quero ler")
                        refresh()
                    }
                },
            ),
            GooeyContextMenu.GooeyMenuItem(
                id = "remove",
                label = "Remover da biblioteca",
                iconType = GooeyContextMenu.IconType.REMOVE,
                isDanger = true,
                onClick = {
                    val ok = JOptionPane.showConfirmDialog(
                        this@LibraryScreen,
                        "Remover '${manga.title}' da biblioteca?",
                        "Remover",
                        JOptionPane.YES_NO_OPTION,
                    )
                    if (ok == JOptionPane.YES_OPTION) {
                        background({ ctx.service.removeFromLibrary(manga.id) }) {
                            ctx.covers.remove(manga.id)
                            refresh()
                        }
                    }
                },
            ),
        )

        GooeyContextMenu(gooeyItems).showAt(grid, x, y)
    }

    fun refresh() {
        background({
            val list = ctx.service.library()
            val unread = ctx.service.unreadCounts()
            val downloaded = ctx.service.downloadedCounts()
            Triple(list, unread, downloaded)
        }) { result ->
            result.onSuccess { (mangas, unread, downloaded) ->
                all = mangas
                unreadByManga = unread
                downloadedByManga = downloaded
                tabsBar.repaint()
                applyFilter()
                ctx.setStatus("Biblioteca: ${mangas.size} mangá(s)")
                mangas.filter { !ctx.covers.has(it.id) }.forEach { manga ->
                    ctx.covers.load(
                        manga,
                        firstChapterPath = {
                            val firstCh = ctx.service.chaptersOf(manga.id).firstOrNull() ?: return@load null
                            ctx.service.chapterCbzFile(manga, firstCh).absolutePath
                        },
                        onLoaded = { grid.repaint() },
                    )
                }
            }.onFailure { ctx.setStatus("Erro ao carregar biblioteca: ${it.message}") }
        }
    }

    private fun applyDisplayMode(mode: DisplayMode) {
        displayMode = mode
        when (mode) {
            DisplayMode.COMFORTABLE_GRID -> {
                grid.layoutOrientation = JList.HORIZONTAL_WRAP
                grid.visibleRowCount = -1
                grid.fixedCellWidth = CARD_W
                grid.fixedCellHeight = CARD_H
            }
            DisplayMode.COMPACT_GRID -> {
                grid.layoutOrientation = JList.HORIZONTAL_WRAP
                grid.visibleRowCount = -1
                grid.fixedCellWidth = CARD_W
                grid.fixedCellHeight = COVER_H + 22
            }
            DisplayMode.COMPACT_LIST -> {
                grid.layoutOrientation = JList.VERTICAL
                grid.visibleRowCount = 0
                grid.fixedCellWidth = -1
                grid.fixedCellHeight = 64
            }
        }
        grid.revalidate()
        grid.repaint()
    }

    private fun applyFilter() {
        val query = filterField.text.trim().lowercase()
        val cat = activeCategoryStatus
        val sort = currentSort
        val comparator: Comparator<Manga> = when (sort) {
            Sort.TITLE -> compareBy { it.title.lowercase() }
            Sort.UNREAD -> compareByDescending<Manga> { unreadByManga[it.id] ?: 0 }.thenBy { it.title.lowercase() }
            Sort.ADDED -> compareByDescending { it.dateAdded }
            Sort.DOWNLOADED -> compareByDescending<Manga> { downloadedByManga[it.id] ?: 0 }.thenBy { it.title.lowercase() }
            Sort.AUTHOR -> compareBy<Manga> { (it.author ?: "zzzz").lowercase() }.thenBy { it.title.lowercase() }
        }
        val shown = all
            .filter { manga ->
                val matchesCat = cat == LibraryCategory.ALL || manga.status == cat
                val matchesOffline = !onlyDownloadedFilter || (downloadedByManga[manga.id] ?: 0) > 0
                val matchesUnread = !unreadOnlyFilter || (unreadByManga[manga.id] ?: 0) > 0
                val matchesCompleted = !completedOnlyFilter || (unreadByManga[manga.id] ?: 0) == 0
                val matchesGenre = selectedGenreFilter == null ||
                    manga.genre.orEmpty().any { it.trim().equals(selectedGenreFilter, ignoreCase = true) }
                val matchesSource = selectedSourceFilter == null || manga.source == selectedSourceFilter
                val matchesQuery = query.isEmpty() ||
                    manga.title.lowercase().contains(query) ||
                    manga.author?.lowercase()?.contains(query) == true
                matchesCat && matchesOffline && matchesUnread && matchesCompleted && matchesGenre && matchesSource && matchesQuery
            }
            .sortedWith(comparator)
        model.clear()
        shown.forEach { model.addElement(it) }
        if (shown.isEmpty()) {
            if (all.isEmpty()) {
                emptyTitleLbl.text = "Sua biblioteca está vazia"
                emptySubLbl.text = "Explore fontes online ou importe arquivos locais (.cbz / .zip) pelo menu acima."
                emptyActionBtn.text = "Explorar Fontes"
                emptyActionBtn.isVisible = ctx.extensions.isNotEmpty()
            } else {
                emptyTitleLbl.text = "Nenhum mangá encontrado"
                emptySubLbl.text = "Nenhum título corresponde aos filtros ou à categoria selecionada."
                emptyActionBtn.text = "Limpar Filtros"
                emptyActionBtn.isVisible = true
            }
            centerCards.show(centerContainer, "empty")
        } else {
            centerCards.show(centerContainer, "grid")
        }
        if (query.isNotEmpty() || cat != LibraryCategory.ALL || onlyDownloadedFilter || unreadOnlyFilter || completedOnlyFilter || selectedGenreFilter != null) {
            ctx.setStatus("${shown.size} de ${all.size} mangá(s)")
        }
    }

    private fun checkUpdatesForAll() {
        val onlineMangas = all.filter { !it.isLocal }
        if (onlineMangas.isEmpty()) {
            refresh()
            return
        }
        ctx.setStatus("Verificando novos capítulos para ${onlineMangas.size} mangá(s)…")
        background({
            var updatedCount = 0
            onlineMangas.forEach { manga ->
                val src = ctx.sourceFor(manga.source) ?: return@forEach
                runCatching {
                    ctx.service.refreshOnlineChapters(manga, src)
                    updatedCount++
                }
            }
            updatedCount
        }) {
            refresh()
            ctx.setStatus("Biblioteca sincronizada (${it.getOrDefault(0)} mangá(s) verificado(s))")
        }
    }

    private fun importLocal(directories: Boolean) {
        val chooser = JFileChooser().apply {
            fileSelectionMode = if (directories) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
            if (!directories) fileFilter = FileNameExtensionFilter("Mangá (.cbz, .zip)", "cbz", "zip")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile
        ctx.setStatus("Importando ${file.name}…")
        background({ ctx.service.addLocal(file) }) { result ->
            result.onSuccess {
                ctx.setStatus("'${it.title}' adicionado")
                refresh()
            }.onFailure {
                ctx.setStatus("Falha ao importar")
                JOptionPane.showMessageDialog(this, it.message ?: it.toString(), "Erro", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    private fun renderCard(manga: Manga, index: Int, selected: Boolean): Component {
        val unread = unreadByManga[manga.id] ?: 0
        val downloaded = downloadedByManga[manga.id] ?: 0
        val srcLabel = if (manga.isLocal) "LOCAL" else (ctx.sourceFor(manga.source)?.language?.uppercase() ?: "WEB")
        if (!ctx.covers.has(manga.id)) {
            ctx.covers.load(
                manga,
                firstChapterPath = {
                    val firstCh = ctx.service.chaptersOf(manga.id).firstOrNull() ?: return@load null
                    ctx.service.chapterCbzFile(manga, firstCh).absolutePath
                },
                onLoaded = { grid.repaint() },
            )
        }
        val hp = hoverAnimator.progressFor(index)
        if (displayMode == DisplayMode.COMPACT_LIST) {
            return listRowPrototype.apply {
                coverIcon = ctx.covers.get(manga.id, manga.title)
                titleText = manga.title
                subtitleText = buildString {
                    manga.author?.takeIf { it.isNotBlank() }?.let { append(it).append("  •  ") }
                    append(LibraryCategory.labelOf(manga.status))
                    manga.genre?.take(3)?.takeIf { it.isNotEmpty() }?.let {
                        append("  •  ").append(it.joinToString(", "))
                    }
                }
                sourceText = if (manga.isLocal) "Local" else (ctx.sourceFor(manga.source)?.name ?: "Online")
                downloadsCount = downloaded
                unreadCount = unread
                selectedState = selected
                hoveredState = index == hoveredIndex
                hoverProgress = hp
            }
        }
        return cardPrototype.apply {
            coverIcon = ctx.covers.get(manga.id, manga.title)
            titleText = manga.title
            subtitleText = manga.author ?: LibraryCategory.labelOf(manga.status)
            downloadsBadgeCount = downloaded
            unreadBadgeCount = unread
            topLeftBadge = srcLabel
            topRightBadge = null
            compactOverlayMode = (displayMode == DisplayMode.COMPACT_GRID)
            selectedState = selected
            hoveredState = index == hoveredIndex
            hoverProgress = hp
        }
    }

    /**
     * Compact horizontal list row renderer for large libraries.
     */
    private class MangaListRowCard : JPanel() {
        var coverIcon: javax.swing.ImageIcon? = null
        var titleText: String = ""
        var subtitleText: String = ""
        var sourceText: String = ""
        var downloadsCount: Int = 0
        var unreadCount: Int = 0
        var selectedState: Boolean = false
        var hoveredState: Boolean = false
        var hoverProgress: Float = 0f

        init {
            isOpaque = false
            preferredSize = Dimension(400, 64)
        }

        private fun clipStr(g2: Graphics2D, text: String, maxW: Int): String {
            if (maxW <= 12) return ""
            val fm = g2.fontMetrics
            if (fm.stringWidth(text) <= maxW) return text
            var s = text
            while (s.length > 1 && fm.stringWidth("$s…") > maxW) s = s.dropLast(1)
            return "$s…"
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

            val hp = if (hoverProgress > 0f) hoverProgress.coerceIn(0f, 1f) else if (hoveredState) 1f else 0f
            val w = width
            val h = height
            if (selectedState) {
                g2.color = Theme.BG_CARD_HOVER
                g2.fillRoundRect(4, 2, w - 8, h - 4, 10, 10)
            } else if (hp > 0.02f) {
                val hc = Theme.BG_CARD
                g2.color = Color(hc.red, hc.green, hc.blue, (255 * hp).toInt().coerceIn(0, 255))
                g2.fillRoundRect(4, 2, w - 8, h - 4, 10, 10)
            }

            // Bottom subtle divider
            g2.color = Theme.BORDER_SUBTLE
            g2.drawLine(14, h - 1, w - 14, h - 1)

            // Mini cover (36x50)
            val thumbW = 36
            val thumbH = 50
            val thumbX = 14
            val thumbY = (h - thumbH) / 2
            val icon = coverIcon
            if (icon != null) {
                g2.clip = java.awt.geom.RoundRectangle2D.Float(thumbX.toFloat(), thumbY.toFloat(), thumbW.toFloat(), thumbH.toFloat(), 6f, 6f)
                g2.drawImage(icon.image, thumbX, thumbY, thumbW, thumbH, null)
                g2.clip = null
            } else {
                g2.color = Theme.BG_SURFACE
                g2.fillRoundRect(thumbX, thumbY, thumbW, thumbH, 6, 6)
            }
            g2.color = Color(255, 255, 255, 26)
            g2.drawRoundRect(thumbX, thumbY, thumbW - 1, thumbH - 1, 6, 6)

            // Right badges (Unread Pill + Downloaded + Source)
            var rightX = w - 18
            if (unreadCount > 0) {
                val uStr = "$unreadCount"
                g2.font = Theme.FONT_MONO.deriveFont(Font.BOLD, 11f)
                val fm = g2.fontMetrics
                val pw = (fm.stringWidth(uStr) + 12).coerceAtLeast(22)
                val ph = 20
                rightX -= pw
                val py = (h - ph) / 2
                g2.color = Color.WHITE
                g2.fillRoundRect(rightX, py, pw, ph, 10, 10)
                g2.color = Color.BLACK
                g2.drawString(uStr, rightX + (pw - fm.stringWidth(uStr)) / 2, py + ((ph - fm.height) / 2) + fm.ascent)
                rightX -= 8
            }

            if (downloadsCount > 0) {
                val pw = drawVectorDownloadPill(
                    g2,
                    downloadsCount,
                    rightX,
                    (h - 20) / 2,
                    Color(0x1B, 0x3B, 0x2B),
                    Color(0x52, 0xD1, 0x92),
                    alignRight = true,
                )
                rightX -= pw + 10
            }

            if (sourceText.isNotBlank()) {
                g2.font = Theme.FONT_MONO.deriveFont(Font.PLAIN, 11.5f)
                val fm = g2.fontMetrics
                val sw = fm.stringWidth(sourceText)
                rightX -= sw
                g2.color = Theme.TEXT_MUTED
                g2.drawString(sourceText, rightX, (h - fm.height) / 2 + fm.ascent)
                rightX -= 12
            }

            // Title & Subtitle safely ellipsized before badges
            val textX = thumbX + thumbW + 14
            val maxTextW = (rightX - textX).coerceAtLeast(40)
            g2.font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 14f)
            g2.color = Theme.TEXT_PRIMARY
            g2.drawString(clipStr(g2, titleText, maxTextW), textX, 27)

            g2.font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12f)
            g2.color = Theme.TEXT_MUTED
            g2.drawString(clipStr(g2, subtitleText, maxTextW), textX, 46)

            g2.dispose()
        }
    }

    /**
     * Port of Mihon's `LibraryTabs.kt` (`PrimaryScrollableTabRow` + `TabText(text, badgeCount)`).
     * Supports smooth animated indicator sliding, click-and-drag reordering, and right-click GooeyContextMenu.
     */
    private inner class LibraryTabsBar : JPanel() {
        private var hoveredTab = -1
        private val tabBounds = mutableListOf<Pair<Long, java.awt.Rectangle>>()
        private var animIndicatorX = -1f
        private var animIndicatorW = -1f
        private var indicatorTimer: javax.swing.Timer? = null

        private var draggingCatId: Long? = null
        private var dragStartX = 0
        private var isDragging = false
        private var dropTargetIndex: Int? = null

        override fun removeNotify() {
            indicatorTimer?.stop()
            indicatorTimer = null
            super.removeNotify()
        }

        private fun animateIndicatorTo(targetRect: java.awt.Rectangle) {
            val targetX = (targetRect.x + 10).toFloat()
            val targetW = (targetRect.width - 20).coerceAtLeast(16).toFloat()
            if (animIndicatorX < 0f || animIndicatorW <= 0f) {
                animIndicatorX = targetX
                animIndicatorW = targetW
                repaint()
                return
            }
            val startX = animIndicatorX
            val startW = animIndicatorW
            val startTime = System.currentTimeMillis()
            val durationMs = 170L
            indicatorTimer?.stop()
            indicatorTimer = javax.swing.Timer(14) { e ->
                val rawT = ((System.currentTimeMillis() - startTime).toFloat() / durationMs).coerceIn(0f, 1f)
                val inv = 1f - rawT
                val eased = 1f - (inv * inv * inv)
                animIndicatorX = startX + (targetX - startX) * eased
                animIndicatorW = startW + (targetW - startW) * eased
                repaint()
                if (rawT >= 1f) {
                    (e.source as? javax.swing.Timer)?.stop()
                    animIndicatorX = targetX
                    animIndicatorW = targetW
                }
            }.also { it.start() }
        }

        init {
            background = Theme.BG_SURFACE
            preferredSize = Dimension(0, 44)
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val idx = tabBounds.indexOfFirst { it.second.contains(e.point) }
                    cursor = if (idx >= 0) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                    if (idx != hoveredTab) {
                        hoveredTab = idx
                        repaint()
                    }
                }

                override fun mouseDragged(e: MouseEvent) {
                    val catId = draggingCatId ?: return
                    if (!isDragging) {
                        if (kotlin.math.abs(e.x - dragStartX) > 8) {
                            isDragging = true
                            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                        }
                    }
                    if (isDragging) {
                        val entries = LibraryCategory.entries
                        // Find closest index among movable categories (index >= 1)
                        var target = entries.size - 1
                        for (i in 1 until entries.size) {
                            val r = tabBounds.getOrNull(i)?.second
                            if (r != null) {
                                if (e.x < r.x + r.width / 2) {
                                    target = i
                                    break
                                }
                            }
                        }
                        dropTargetIndex = target.coerceIn(1, entries.size - 1)
                        repaint()
                    }
                }
            })
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val hit = tabBounds.firstOrNull { it.second.contains(e.point) } ?: return
                    if (SwingUtilities.isLeftMouseButton(e) && hit.first != LibraryCategory.ALL) {
                        draggingCatId = hit.first
                        dragStartX = e.x
                        isDragging = false
                        dropTargetIndex = null
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    val catId = draggingCatId
                    val target = dropTargetIndex
                    val wasDragging = isDragging
                    draggingCatId = null
                    isDragging = false
                    dropTargetIndex = null
                    cursor = Cursor.getDefaultCursor()

                    if (wasDragging && catId != null && target != null) {
                        LibraryCategory.reorderCategory(catId, target)
                        repaint()
                        return
                    }

                    if (!wasDragging && SwingUtilities.isLeftMouseButton(e)) {
                        val hit = tabBounds.firstOrNull { it.second.contains(e.point) } ?: return
                        if (activeCategoryStatus != hit.first) {
                            activeCategoryStatus = hit.first
                            animateIndicatorTo(hit.second)
                            applyFilter()
                        }
                    }
                }

                override fun mouseExited(e: MouseEvent) {
                    if (hoveredTab != -1 && !isDragging) {
                        hoveredTab = -1
                        repaint()
                    }
                }

                override fun mouseClicked(e: MouseEvent) {
                    val hit = tabBounds.firstOrNull { it.second.contains(e.point) } ?: return
                    if (SwingUtilities.isRightMouseButton(e)) {
                        showCategoryGooeyMenu(hit.first, e.x, e.y)
                        return
                    }
                }
            })
        }

        private fun showCategoryGooeyMenu(catId: Long, x: Int, y: Int) {
            val entries = LibraryCategory.entries
            val currentIdx = entries.indexOfFirst { it.first == catId }
            if (currentIdx < 0) return
            val catTitle = entries[currentIdx].second

            val items = mutableListOf<GooeyContextMenu.GooeyMenuItem>()

            if (catId != LibraryCategory.ALL) {
                // Extremity left: Move left (index = 0 maps to leftmost arc angle)
                if (currentIdx > 1) {
                    items.add(
                        GooeyContextMenu.GooeyMenuItem(
                            id = "move_left",
                            label = "Mover para Esquerda",
                            iconType = GooeyContextMenu.IconType.MOVE_LEFT,
                        ) {
                            LibraryCategory.moveCategory(catId, -1)
                        }
                    )
                }

                // Center actions
                items.add(
                    GooeyContextMenu.GooeyMenuItem(
                        id = "rename",
                        label = "Renomear '$catTitle'",
                        iconType = GooeyContextMenu.IconType.EDIT,
                    ) {
                        val newName = JOptionPane.showInputDialog(
                            this@LibraryScreen,
                            "Novo nome para a categoria '$catTitle':",
                            catTitle,
                        )?.trim()
                        if (!newName.isNullOrBlank() && newName != catTitle) {
                            LibraryCategory.updateCategory(catId, newName)
                        }
                    }
                )
                items.add(
                    GooeyContextMenu.GooeyMenuItem(
                        id = "remove",
                        label = "Excluir Categoria",
                        iconType = GooeyContextMenu.IconType.REMOVE,
                        isDanger = true,
                    ) {
                        val confirm = JOptionPane.showConfirmDialog(
                            this@LibraryScreen,
                            "Deseja excluir a categoria '$catTitle'?",
                            "Excluir Categoria",
                            JOptionPane.YES_NO_OPTION,
                        )
                        if (confirm == JOptionPane.YES_OPTION) {
                            if (activeCategoryStatus == catId) {
                                activeCategoryStatus = LibraryCategory.ALL
                            }
                            LibraryCategory.removeCategory(catId)
                        }
                    }
                )

                // Extremity right: Move right (last index maps to rightmost arc angle)
                if (currentIdx < entries.size - 1) {
                    items.add(
                        GooeyContextMenu.GooeyMenuItem(
                            id = "move_right",
                            label = "Mover para Direita",
                            iconType = GooeyContextMenu.IconType.MOVE_RIGHT,
                        ) {
                            LibraryCategory.moveCategory(catId, 1)
                        }
                    )
                }
            }

            if (items.isNotEmpty()) {
                GooeyContextMenu(items).showAt(this, x, y)
            }
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

            tabBounds.clear()
            var x = 22
            val h = height

            // Subtle bottom divider line
            g2.color = Theme.BORDER_SUBTLE
            g2.drawLine(0, h - 1, width, h - 1)

            LibraryCategory.entries.forEachIndexed { idx, (status, label) ->
                val count = if (status == LibraryCategory.ALL) all.size else all.count { it.status == status }
                val active = status == activeCategoryStatus
                val hovered = idx == hoveredTab

                g2.font = font.deriveFont(if (active) Font.BOLD else Font.PLAIN, 13.5f)
                val fm = g2.fontMetrics
                val labelW = fm.stringWidth(label)

                g2.font = font.deriveFont(Font.BOLD, 11f)
                val bFm = g2.fontMetrics
                val countStr = "$count"
                val pillW = bFm.stringWidth(countStr) + 14
                val tabW = labelW + 8 + pillW + 28

                val rect = java.awt.Rectangle(x, 0, tabW, h)
                tabBounds.add(status to rect)

                if (hovered && !active) {
                    g2.color = Color(255, 255, 255, 12)
                    g2.fillRoundRect(x + 2, 4, tabW - 4, h - 8, 10, 10)
                }

                // Tab label
                g2.font = font.deriveFont(if (active) Font.BOLD else Font.PLAIN, 13.5f)
                g2.color = if (active) Theme.ACCENT_HOVER else Theme.TEXT_SECONDARY
                val textY = (h - fm.height) / 2 + fm.ascent
                g2.drawString(label, x + 14, textY)

                // Tab count pill badge (Mihon TabText badgeCount)
                val pillX = x + 14 + labelW + 8
                val pillH = 19
                val pillY = (h - pillH) / 2
                g2.color = if (active) Theme.ACCENT_SOFT else Color(255, 255, 255, 18)
                g2.fillRoundRect(pillX, pillY, pillW, pillH, 10, 10)
                g2.font = font.deriveFont(Font.BOLD, 11f)
                g2.color = if (active) Theme.ACCENT_HOVER else Theme.TEXT_MUTED
                g2.drawString(countStr, pillX + 7, pillY + (pillH - bFm.height) / 2 + bFm.ascent)

                // Record target bar when active (drawn smoothly below using animIndicatorX/W)
                if (active && indicatorTimer?.isRunning != true) {
                    animIndicatorX = (x + 10).toFloat()
                    animIndicatorW = (tabW - 20).coerceAtLeast(16).toFloat()
                }

                // Drag indicator line
                if (isDragging && dropTargetIndex == idx) {
                    g2.color = Theme.ACCENT
                    g2.fillRoundRect(x - 2, 6, 4, h - 12, 2, 2)
                }

                x += tabW + 4
            }

            if (animIndicatorX >= 0f && animIndicatorW > 0f) {
                g2.color = Theme.ACCENT
                g2.fillRoundRect(animIndicatorX.toInt(), h - 3, animIndicatorW.toInt(), 3, 3, 3)
            }

            g2.dispose()
        }
    }
}
