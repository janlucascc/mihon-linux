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
    }

    private var all: List<Manga> = emptyList()
    private var unreadByManga: Map<Long, Int> = emptyMap()
    private var downloadedByManga: Map<Long, Int> = emptyMap()
    private var hoveredIndex: Int = -1
    private var activeCategoryStatus: Long = LibraryCategory.ALL
    private var compactMode: Boolean = false
    private var onlyDownloadedFilter: Boolean
        get() = ctx.onlyOfflineFilter
        set(v) { ctx.setOfflineFilter(v) }

    private val model = DefaultListModel<Manga>()
    private val grid = JList(model)
    private val cardPrototype = MangaPosterCard()
    private val tabsBar = LibraryTabsBar()

    private var currentSort: Sort = Sort.TITLE
    private val filterField = JTextField(20).apply {
        putClientProperty("JTextField.placeholderText", "Pesquisar na biblioteca…")
        isVisible = false
    }

    private fun m3HeaderIconButton(icon: javax.swing.Icon, tip: String, onClick: (JButton) -> Unit): JButton {
        return JButton(icon).apply {
            foreground = Theme.TEXT_PRIMARY
            background = Theme.BG_APP
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = tip
            preferredSize = Dimension(38, 38)
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
                    val prev = hoveredIndex
                    hoveredIndex = valid
                    if (prev >= 0) grid.getCellBounds(prev, prev)?.let { grid.repaint(it) }
                    if (valid >= 0) grid.getCellBounds(valid, valid)?.let { grid.repaint(it) }
                }
            }
        })
        grid.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                if (hoveredIndex != -1) {
                    val prev = hoveredIndex
                    hoveredIndex = -1
                    grid.getCellBounds(prev, prev)?.let { grid.repaint(it) }
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
            val popup = JPopupMenu()

            val offlineItem = JCheckBoxMenuItem("Somente disponíveis offline", onlyDownloadedFilter).apply {
                addActionListener {
                    onlyDownloadedFilter = isSelected
                    applyFilter()
                }
            }
            val compactItem = JCheckBoxMenuItem("Grade compacta (título sobre capa)", compactMode).apply {
                addActionListener {
                    compactMode = isSelected
                    grid.fixedCellHeight = if (compactMode) COVER_H + 22 else CARD_H
                    grid.revalidate()
                    grid.repaint()
                }
            }
            popup.add(offlineItem)
            popup.add(compactItem)
            popup.addSeparator()

            val sortMenuLabel = JMenuItem("ORDENAR POR:").apply {
                isEnabled = false
                font = font.deriveFont(Font.BOLD, 11f)
            }
            popup.add(sortMenuLabel)

            val sortGroup = ButtonGroup()
            Sort.entries.forEach { sort ->
                val rdo = JRadioButtonMenuItem(sort.label, sort == currentSort).apply {
                    addActionListener {
                        currentSort = sort
                        applyFilter()
                    }
                }
                sortGroup.add(rdo)
                popup.add(rdo)
            }

            popup.show(anchor, 0, anchor.height)
        }

        val btnOverflow = m3HeaderIconButton(ModernIcons.moreVert(20) { Theme.TEXT_PRIMARY }, "Mais opções") { anchor ->
            val popup = JPopupMenu()
            popup.add(JMenuItem("↻ Atualizar biblioteca").apply {
                addActionListener { checkUpdatesForAll() }
            })
            popup.addSeparator()
            popup.add(JMenuItem("📦 Adicionar arquivo (.cbz / .zip)...").apply {
                addActionListener { importLocal(directories = false) }
            })
            popup.add(JMenuItem("📁 Adicionar pasta...").apply {
                addActionListener { importLocal(directories = true) }
            })
            popup.show(anchor, 0, anchor.height)
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
        add(topHeader, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)

        ctx.addThemeListener {
            background = Theme.BG_APP
            grid.background = Theme.BG_APP
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
            repaint()
        }
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

    private fun applyFilter() {
        val query = filterField.text.trim().lowercase()
        val cat = activeCategoryStatus
        val sort = currentSort
        val comparator: Comparator<Manga> = when (sort) {
            Sort.TITLE -> compareBy { it.title.lowercase() }
            Sort.UNREAD -> compareByDescending<Manga> { unreadByManga[it.id] ?: 0 }.thenBy { it.title.lowercase() }
            Sort.ADDED -> compareByDescending { it.dateAdded }
        }
        val shown = all
            .filter { (cat == LibraryCategory.ALL || it.status == cat) &&
                (!onlyDownloadedFilter || (downloadedByManga[it.id] ?: 0) > 0) &&
                (query.isEmpty() || it.title.lowercase().contains(query))
            }
            .sortedWith(comparator)
        model.clear()
        shown.forEach { model.addElement(it) }
        if (query.isNotEmpty() || cat != LibraryCategory.ALL) {
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
        val srcLabel = if (manga.isLocal) "📁 Local" else (ctx.sourceFor(manga.source)?.language?.uppercase() ?: "WEB")
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
        return cardPrototype.apply {
            coverIcon = ctx.covers.get(manga.id, manga.title)
            titleText = manga.title
            subtitleText = manga.author ?: LibraryCategory.labelOf(manga.status)
            downloadsBadgeCount = downloaded
            unreadBadgeCount = unread
            topLeftBadge = srcLabel
            topRightBadge = null
            compactOverlayMode = compactMode
            selectedState = selected
            hoveredState = index == hoveredIndex
        }
    }

    /**
     * Port of Mihon's `LibraryTabs.kt` (`PrimaryScrollableTabRow` + `TabText(text, badgeCount)`).
     */
    private inner class LibraryTabsBar : JPanel() {
        private var hoveredTab = -1
        private val tabBounds = mutableListOf<Pair<Long, java.awt.Rectangle>>()

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
            })
            addMouseListener(object : MouseAdapter() {
                override fun mouseExited(e: MouseEvent) {
                    if (hoveredTab != -1) {
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
                    if (activeCategoryStatus != hit.first) {
                        activeCategoryStatus = hit.first
                        repaint()
                        applyFilter()
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
                if (currentIdx > 1) { // can move left
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
                if (currentIdx < entries.size - 1) { // can move right
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

                // Active primary indicator bar at bottom (3px rounded pill)
                if (active) {
                    g2.color = Theme.ACCENT
                    g2.fillRoundRect(x + 10, h - 3, tabW - 20, 3, 3, 3)
                }

                x += tabW + 4
            }

            g2.dispose()
        }
    }
}
