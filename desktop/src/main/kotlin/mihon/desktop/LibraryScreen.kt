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
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.util.prefs.Preferences
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * Library screen ported from Mihon's `LibraryScreen`, `LibraryTabs`, `LibraryToolbar`, and `CommonMangaItem`:
 * - Material 3 primary scrollable category tabs (`LibraryTabs`) with item count badges
 * - Comfortable Grid and Compact Overlay Grid modes
 * - `DownloadsBadge` + `UnreadBadge` + `LanguageBadge` on every manga cover
 */
class LibraryScreen(
    private val ctx: AppContext,
    private val onOpenCategories: () -> Unit = {},
    private val onOpenDownloads: () -> Unit = {},
) : JPanel(BorderLayout()) {

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

    private val libPrefs = Preferences.userRoot().node("mihon-linux")
    private var all: List<Manga> = emptyList()
    private var unreadByManga: Map<Long, Int> = emptyMap()
    private var downloadedByManga: Map<Long, Int> = emptyMap()
    private var hoveredIndex: Int = -1
    private var activeCategoryStatus: Long = LibraryCategory.ALL
    private var displayMode: DisplayMode = runCatching {
        DisplayMode.valueOf(libPrefs.get("library_display_mode", DisplayMode.COMFORTABLE_GRID.name))
    }.getOrDefault(DisplayMode.COMFORTABLE_GRID)
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
    private val tabsScroll = object : JScrollPane(tabsBar) {
        init {
            border = null
            isOpaque = false
            viewport.isOpaque = false
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBar.preferredSize = Dimension(0, 0)
            addMouseWheelListener { e ->
                val bar = horizontalScrollBar
                bar.value = (bar.value + e.unitsToScroll * 20).coerceIn(0, (bar.maximum - bar.visibleAmount).coerceAtLeast(0))
            }
        }
    }
    private val centerCards = java.awt.CardLayout()
    private val centerContainer = JPanel(centerCards)
    private val emptyIconLbl = JLabel(
        ModernIcons.library(52) {
            if (Theme.isDark) Theme.ACCENT else Theme.PRIMARY
        },
    )
    private val emptyTitleLbl = JLabel("Sua biblioteca está vazia")
    private val emptySubLbl = JLabel("Explore fontes online ou importe arquivos locais (.cbz/.zip) para começar.")
    private val emptyActionBtn = ModernButton("Explorar Fontes", ModernIcons.browse(16) { Color.WHITE }, ModernButton.Variant.PRIMARY)
    private var themeListener: ((Boolean) -> Unit)? = null
    private val categoryChangeListener: () -> Unit = {
        tabsBar.revalidate()
        tabsBar.repaint()
        applyFilter()
    }

    private var currentSort: Sort = runCatching {
        Sort.valueOf(libPrefs.get("library_sort", Sort.TITLE.name))
    }.getOrDefault(Sort.TITLE)
    private var sortAscending: Boolean = libPrefs.getBoolean("library_sort_asc", true)

    private val countLabel = JLabel("0 títulos").apply {
        font = Theme.FONT_UI.deriveFont(Font.BOLD, 12f)
        foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
        alignmentX = 0f
    }

    private fun hasActiveFilters(): Boolean =
        onlyDownloadedFilter || unreadOnlyFilter || completedOnlyFilter ||
            selectedGenreFilter != null || selectedSourceFilter != null

    private fun activeFilterCount(): Int {
        var c = 0
        if (onlyDownloadedFilter) c++
        if (unreadOnlyFilter) c++
        if (completedOnlyFilter) c++
        if (selectedGenreFilter != null) c++
        if (selectedSourceFilter != null) c++
        return c
    }

    private var btnFilterRef: ModernIconButton? = null

    private val filterField = mihon.desktop.ui.components.MihonSearchField(
        columns = 20,
        placeholder = "Pesquisar na biblioteca…",
        compact = false,
        shortcutBadge = "Ctrl+F",
        debounceMs = 120,
        onEscapeEmpty = { grid.requestFocusInWindow() },
    ).apply {
        toolTipText = "Pesquisar na biblioteca (Ctrl+F)"
        minimumSize = Dimension(180, Theme.CONTROL_HEIGHT_COMPACT)
        preferredSize = Dimension(260, Theme.CONTROL_HEIGHT_COMPACT)
        onDebouncedChange = { applyFilter() }
        onSubmit = { applyFilter() }
    }

    private fun indexHeaderActionButton(
        icon: javax.swing.Icon,
        tip: String,
        activeSupplier: () -> Boolean = { false },
        onClick: (ModernIconButton) -> Unit,
    ): ModernIconButton {
        return object : ModernIconButton(
            icon = icon,
            tip = tip,
            sizePx = Theme.CONTROL_HEIGHT_COMPACT,
            cornerRadius = (Theme.CONTROL_RADIUS * 2).toFloat(),
            surfaceStyled = true,
        ) {
            override fun paintComponent(g: Graphics) {
                activeHighlight = activeSupplier()
                super.paintComponent(g)
            }
        }.apply {
            foreground = Theme.TEXT_PRIMARY
            addActionListener { onClick(this) }
        }
    }

    init {
        ctx.addOfflineFilterListener { applyFilter() }
        LibraryCategory.addChangeListener(categoryChangeListener)
        background = Theme.BG_APP
        grid.background = Theme.BG_APP
        grid.border = BorderFactory.createEmptyBorder(14, Theme.PAGE_GUTTER, Theme.PAGE_GUTTER, Theme.PAGE_GUTTER)
        grid.cellRenderer = ListCellRenderer { _, manga, index, selected, cellHasFocus ->
            renderCard(manga, index, selected, cellHasFocus)
        }
        applyDisplayMode(displayMode)
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

        fun createIndexPopup(): JPopupMenu = mihon.desktop.ui.components.MihonPopupMenu.create()

        fun styleMenuItem(item: JMenuItem, active: Boolean = false, danger: Boolean = false) {
            mihon.desktop.ui.components.MihonPopupMenu.styleItem(item, active = active, danger = danger)
            val pref = item.preferredSize
            item.preferredSize = Dimension(maxOf(pref.width, 260).coerceAtMost(360), Theme.CONTROL_HEIGHT_COMPACT)
        }

        // 1. [Filtro] button
        val btnFilter = indexHeaderActionButton(
            ModernIcons.filter(18) { if (hasActiveFilters()) Theme.PRIMARY else Theme.TEXT_PRIMARY },
            "Filtrar",
            activeSupplier = { hasActiveFilters() },
        ) { anchor ->
            val popup = createIndexPopup()

            val filterHeader = JMenuItem("FILTRAR POR:").apply {
                icon = ModernIcons.filter(14) { Theme.TEXT_MUTED }
                isEnabled = false
                font = Theme.FONT_UI.deriveFont(Font.BOLD, 10.5f)
                foreground = Theme.TEXT_MUTED
                background = Theme.BG_SURFACE
                border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            }
            popup.add(filterHeader)

            val offlineItem = JCheckBoxMenuItem("Somente disponíveis offline", onlyDownloadedFilter).apply {
                icon = ModernIcons.offline(16) { if (isSelected) Theme.PRIMARY else Theme.TEXT_MUTED }
                styleMenuItem(this, active = isSelected)
                addActionListener {
                    onlyDownloadedFilter = isSelected
                    applyFilter()
                }
            }
            val unreadItem = JCheckBoxMenuItem("Apenas com capítulos não lidos", unreadOnlyFilter).apply {
                icon = ModernIcons.checkCircle(16) { if (isSelected) Theme.PRIMARY else Theme.TEXT_MUTED }
                styleMenuItem(this, active = isSelected)
                addActionListener {
                    unreadOnlyFilter = isSelected
                    if (isSelected) completedOnlyFilter = false
                    applyFilter()
                }
            }
            val completedItem = JCheckBoxMenuItem("Apenas leitura em dia (0 não lidos)", completedOnlyFilter).apply {
                icon = ModernIcons.check(16) { if (isSelected) Theme.PRIMARY else Theme.TEXT_MUTED }
                styleMenuItem(this, active = isSelected)
                addActionListener {
                    completedOnlyFilter = isSelected
                    if (isSelected) unreadOnlyFilter = false
                    applyFilter()
                }
            }
            popup.add(offlineItem)
            popup.add(unreadItem)
            popup.add(completedItem)

            val allGenres = all.flatMap { it.genre.orEmpty() }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
            if (allGenres.isNotEmpty()) {
                val genreMenu = javax.swing.JMenu("Gênero: ${selectedGenreFilter ?: "Todos"}").apply {
                    styleMenuItem(this, active = selectedGenreFilter != null)
                }
                genreMenu.add(JMenuItem("Todos os gêneros").apply {
                    styleMenuItem(this, active = selectedGenreFilter == null)
                    addActionListener {
                        selectedGenreFilter = null
                        applyFilter()
                    }
                })
                genreMenu.addSeparator()
                allGenres.take(25).forEach { gName ->
                    genreMenu.add(JCheckBoxMenuItem(gName, selectedGenreFilter.equals(gName, ignoreCase = true)).apply {
                        styleMenuItem(this, active = isSelected)
                        addActionListener {
                            selectedGenreFilter = if (selectedGenreFilter.equals(gName, ignoreCase = true)) null else gName
                            applyFilter()
                        }
                    })
                }
                popup.add(genreMenu)
            }

            popup.addSeparator()
            popup.add(JMenuItem("Limpar filtros", ModernIcons.close(15) { Theme.DANGER }).apply {
                styleMenuItem(this, danger = true)
                addActionListener {
                    filterField.text = ""
                    onlyDownloadedFilter = false
                    unreadOnlyFilter = false
                    completedOnlyFilter = false
                    selectedGenreFilter = null
                    selectedSourceFilter = null
                    activeCategoryStatus = LibraryCategory.ALL
                    tabsBar.repaint()
                    applyFilter()
                    popup.isVisible = false
                }
            })

            popup.show(anchor, 0, anchor.height + 4)
        }
        btnFilterRef = btnFilter

        // 2. [Ordenar] button
        val btnSort = indexHeaderActionButton(
            ModernIcons.sort(18) { Theme.TEXT_PRIMARY },
            "Ordenar",
        ) { anchor ->
            val popup = createIndexPopup()
            val sortMenuLabel = JMenuItem("ORDENAR POR:").apply {
                icon = ModernIcons.sort(14) { Theme.TEXT_MUTED }
                isEnabled = false
                font = Theme.FONT_UI.deriveFont(Font.BOLD, 10.5f)
                foreground = Theme.TEXT_MUTED
                background = Theme.BG_SURFACE
                border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            }
            popup.add(sortMenuLabel)

            val sortGroup = ButtonGroup()
            Sort.entries.forEach { sort ->
                val rdo = JRadioButtonMenuItem(sort.label, sort == currentSort).apply {
                    styleMenuItem(this, active = sort == currentSort)
                    addActionListener {
                        currentSort = sort
                        libPrefs.put("library_sort", sort.name)
                        applyFilter()
                    }
                }
                sortGroup.add(rdo)
                popup.add(rdo)
            }

            popup.addSeparator()
            popup.add(JCheckBoxMenuItem("Ordem crescente (A→Z / Menor→Maior)", sortAscending).apply {
                icon = if (sortAscending) ModernIcons.arrowUp(14) { Theme.PRIMARY } else ModernIcons.arrowDown(14) { Theme.PRIMARY }
                styleMenuItem(this, active = sortAscending)
                addActionListener {
                    sortAscending = isSelected
                    libPrefs.putBoolean("library_sort_asc", sortAscending)
                    applyFilter()
                }
            })

            popup.show(anchor, 0, anchor.height + 4)
        }

        // 3. [Grade] button
        val btnDisplay = indexHeaderActionButton(
            ModernIcons.grid(18) { Theme.TEXT_PRIMARY },
            "Exibição",
        ) { anchor ->
            val popup = createIndexPopup()
            val hdr = JMenuItem("MODO DE EXIBIÇÃO:").apply {
                isEnabled = false
                font = Theme.FONT_UI.deriveFont(Font.BOLD, 10.5f)
                foreground = Theme.TEXT_MUTED
                background = Theme.BG_SURFACE
                border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            }
            popup.add(hdr)
            val grp = ButtonGroup()
            DisplayMode.entries.forEach { mode ->
                val item = JRadioButtonMenuItem(mode.label, mode == displayMode).apply {
                    styleMenuItem(this, active = mode == displayMode)
                    addActionListener { applyDisplayMode(mode) }
                }
                grp.add(item)
                popup.add(item)
            }
            popup.show(anchor, 0, anchor.height + 4)
        }

        // 4. [Lote / Ações] button
        val btnBatch = indexHeaderActionButton(
            ModernIcons.batchSelect(18) { Theme.TEXT_PRIMARY },
            "Ações em lote e importação",
        ) { anchor ->
            val popup = createIndexPopup()
            popup.add(JMenuItem("Atualizar biblioteca", ModernIcons.sync(16) { Theme.TEXT_PRIMARY }).apply {
                styleMenuItem(this)
                addActionListener { checkUpdatesForAll() }
            })
            popup.addSeparator()
            popup.add(JMenuItem("Adicionar arquivo (.cbz / .zip)...", ModernIcons.downloads(16) { Theme.TEXT_PRIMARY }).apply {
                styleMenuItem(this)
                addActionListener { importLocal(directories = false) }
            })
            popup.add(JMenuItem("Adicionar pasta de mangás...", ModernIcons.library(16) { Theme.TEXT_PRIMARY }).apply {
                styleMenuItem(this)
                addActionListener { importLocal(directories = true) }
            })
            popup.show(anchor, 0, anchor.height + 4)
        }

        // 5. [Categorias] button
        val btnCategories = ModernButton("Categorias", variant = ModernButton.Variant.SURFACE).apply {
            toolTipText = "Gerenciar categorias"
            preferredSize = Dimension(108, Theme.CONTROL_HEIGHT_COMPACT)
            addActionListener { onOpenCategories() }
        }

        // 6. [Downloads] button
        val btnDownloads = indexHeaderActionButton(
            ModernIcons.downloads(18) { Theme.TEXT_PRIMARY },
            "Fila de downloads",
        ) {
            onOpenDownloads()
        }

        val titleLabel = JLabel("Biblioteca").apply {
            font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 25f)
            foreground = Theme.TEXT_PRIMARY
            alignmentX = 0f
        }
        val titleCol = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(titleLabel)
            add(Box.createVerticalStrut(2))
            add(countLabel)
        }
        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            add(btnFilter)
            add(btnSort)
            add(btnDisplay)
            add(btnBatch)
            add(btnCategories)
            add(btnDownloads)
        }

        val searchCenterWrap = JPanel(java.awt.GridBagLayout()).apply {
            isOpaque = false
            val gbc = java.awt.GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                weightx = 1.0
                fill = java.awt.GridBagConstraints.HORIZONTAL
                anchor = java.awt.GridBagConstraints.CENTER
            }
            add(filterField, gbc)
        }

        val actionsCenterWrap = JPanel(java.awt.GridBagLayout()).apply {
            isOpaque = false
            val gbc = java.awt.GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = java.awt.GridBagConstraints.CENTER
            }
            add(actionsPanel, gbc)
        }

        val topAppBar = JPanel(BorderLayout(12, 8)).apply {
            background = Theme.BG_APP
            border = BorderFactory.createEmptyBorder(14, Theme.PAGE_GUTTER, 6, Theme.PAGE_GUTTER)
            add(titleCol, BorderLayout.WEST)
            add(searchCenterWrap, BorderLayout.CENTER)
            add(actionsCenterWrap, BorderLayout.EAST)
        }

        var narrowHeader = false
        topAppBar.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                val shouldNarrow = topAppBar.width in 1..679
                if (shouldNarrow != narrowHeader) {
                    narrowHeader = shouldNarrow
                    topAppBar.removeAll()
                    topAppBar.add(titleCol, BorderLayout.WEST)
                    topAppBar.add(actionsCenterWrap, BorderLayout.EAST)
                    topAppBar.add(searchCenterWrap, if (shouldNarrow) BorderLayout.SOUTH else BorderLayout.CENTER)
                    topAppBar.revalidate()
                    topAppBar.repaint()
                }
            }
        })

        // Ctrl+F focuses library search; Esc clears search text and returns focus to grid
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_F, java.awt.event.InputEvent.CTRL_DOWN_MASK),
            "focusLibrarySearch",
        )
        actionMap.put(
            "focusLibrarySearch",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    filterField.requestFocusInWindow()
                    filterField.selectAll()
                }
            },
        )
        filterField.getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            "clearLibrarySearch",
        )
        filterField.actionMap.put(
            "clearLibrarySearch",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    if (filterField.text.isNotEmpty()) {
                        filterField.text = ""
                        applyFilter()
                    } else {
                        grid.requestFocusInWindow()
                    }
                }
            },
        )

        val infoLabel = JLabel("Configure filtros, ordenação e exibição. As preferências são salvas por categoria.").apply {
            font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12.5f)
            foreground = Theme.TEXT_SECONDARY
            border = BorderFactory.createEmptyBorder(0, Theme.PAGE_GUTTER, 8, Theme.PAGE_GUTTER)
        }

        val tabsOuter = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, Theme.PAGE_GUTTER, 10, Theme.PAGE_GUTTER)
            add(tabsScroll, BorderLayout.CENTER)
        }

        val subHeader = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(infoLabel, BorderLayout.NORTH)
            add(tabsOuter, BorderLayout.SOUTH)
        }

        val topHeader = JPanel(BorderLayout()).apply {
            background = Theme.BG_APP
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SUBTLE)
            add(topAppBar, BorderLayout.NORTH)
            add(subHeader, BorderLayout.SOUTH)
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
            foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
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
            emptySubLbl.foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
            topAppBar.background = Theme.BG_APP
            topHeader.background = Theme.BG_APP
            topHeader.border = BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SUBTLE)
            infoLabel.foreground = Theme.TEXT_SECONDARY
            tabsBar.background = Theme.BG_SURFACE
            tabsBar.repaint()
            titleLabel.foreground = Theme.TEXT_PRIMARY
            countLabel.foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
            filterField.foreground = Theme.TEXT_PRIMARY
            filterField.background = Theme.BG_SURFACE
            filterField.caretColor = Theme.PRIMARY
            filterField.repaint()
            btnFilter.foreground = Theme.TEXT_PRIMARY
            btnSort.foreground = Theme.TEXT_PRIMARY
            btnDisplay.foreground = Theme.TEXT_PRIMARY
            btnBatch.foreground = Theme.TEXT_PRIMARY
            btnDownloads.foreground = Theme.TEXT_PRIMARY
            btnFilter.repaint()
            btnSort.repaint()
            btnDisplay.repaint()
            btnBatch.repaint()
            btnCategories.repaint()
            btnDownloads.repaint()
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
        LibraryCategory.removeChangeListener(categoryChangeListener)
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

        GooeyContextMenu.showSmart(gooeyItems, grid, x, y)
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
        libPrefs.put("library_display_mode", mode.name)
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
                grid.fixedCellHeight = 72
            }
        }
        grid.revalidate()
        grid.repaint()
    }

    private fun applyFilter() {
        val query = filterField.text.trim().lowercase()
        val cat = activeCategoryStatus
        val sort = currentSort
        val baseComparator: Comparator<Manga> = when (sort) {
            Sort.TITLE -> compareBy { it.title.lowercase() }
            Sort.UNREAD -> compareByDescending<Manga> { unreadByManga[it.id] ?: 0 }.thenBy { it.title.lowercase() }
            Sort.ADDED -> compareByDescending { it.dateAdded }
            Sort.DOWNLOADED -> compareByDescending<Manga> { downloadedByManga[it.id] ?: 0 }.thenBy { it.title.lowercase() }
            Sort.AUTHOR -> compareBy<Manga> { (it.author ?: "zzzz").lowercase() }.thenBy { it.title.lowercase() }
        }
        val comparator = if (sortAscending) baseComparator else baseComparator.reversed()
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
        val hasQueryOrFilter = query.isNotEmpty() || cat != LibraryCategory.ALL || hasActiveFilters()
        countLabel.text = formatLibraryCount(shown.size, all.size, hasQueryOrFilter, unreadOnlyFilter && query.isEmpty() && !onlyDownloadedFilter && !completedOnlyFilter && selectedGenreFilter == null)
        countLabel.repaint()
        btnFilterRef?.badgeCount = activeFilterCount()
        btnFilterRef?.repaint()
        if (shown.isEmpty()) {
            if (all.isEmpty()) {
                emptyTitleLbl.text = "Sua biblioteca está vazia"
                emptySubLbl.text = "Explore fontes online ou importe arquivos locais (.cbz / .zip) pelo menu acima."
                emptyActionBtn.text = "Explorar Fontes"
                emptyActionBtn.isVisible = ctx.extensions.isNotEmpty()
            } else {
                emptyTitleLbl.text = "Nenhum título encontrado"
                emptySubLbl.text = "O filtro ou a busca atual não retornou resultados nesta categoria."
                emptyActionBtn.text = "Limpar filtros"
                emptyActionBtn.isVisible = true
            }
            centerCards.show(centerContainer, "empty")
        } else {
            centerCards.show(centerContainer, "grid")
        }
        if (hasQueryOrFilter) {
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

    private fun renderCard(manga: Manga, index: Int, selected: Boolean, focused: Boolean): Component {
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
                focusedState = focused && !selected
                hoveredState = index == hoveredIndex
                hoverProgress = hp
            }
        }
        val fadeAlpha = ctx.covers.fadeAlphaForManga(manga.id)
        if (fadeAlpha < 0.99f) {
            SwingUtilities.invokeLater { grid.repaint() }
        }
        return cardPrototype.apply {
            coverIcon = ctx.covers.get(manga.id, manga.title)
            coverFadeAlpha = fadeAlpha
            isSkeleton = false
            titleText = manga.title
            subtitleText = manga.author ?: LibraryCategory.labelOf(manga.status)
            downloadsBadgeCount = downloaded
            unreadBadgeCount = unread
            topLeftBadge = null
            topRightBadge = null
            compactOverlayMode = (displayMode == DisplayMode.COMPACT_GRID)
            selectedState = selected
            focusedState = focused && !selected
            hoveredState = index == hoveredIndex
            hoverProgress = hp
        }
    }

    /**
     * Compact horizontal list row renderer for large libraries (10px card radius, Signal orange unread badge).
     */
    private class MangaListRowCard : JPanel() {
        var coverIcon: javax.swing.ImageIcon? = null
        var titleText: String = ""
        var subtitleText: String = ""
        var sourceText: String = ""
        var downloadsCount: Int = 0
        var unreadCount: Int = 0
        var selectedState: Boolean = false
        var focusedState: Boolean = false
        var hoveredState: Boolean = false
        var hoverProgress: Float = 0f

        init {
            isOpaque = false
            preferredSize = Dimension(400, 72)
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

            val hp = easeOutCubic(if (hoverProgress > 0f) hoverProgress.coerceIn(0f, 1f) else if (hoveredState) 1f else 0f)
            val w = width
            val h = height
            val cardArc = Theme.CARD_RADIUS * 2
            if (selectedState) {
                g2.color = Theme.BG_CARD_HOVER
                g2.fillRoundRect(4, 2, w - 8, h - 4, cardArc, cardArc)
                g2.color = Theme.PRIMARY
                g2.stroke = java.awt.BasicStroke(1.5f)
                g2.drawRoundRect(4, 2, w - 9, h - 5, cardArc, cardArc)
            } else if (hp > 0.02f) {
                val hc = Theme.BG_CARD_HOVER
                g2.color = Color(hc.red, hc.green, hc.blue, (255 * hp).toInt().coerceIn(0, 255))
                g2.fillRoundRect(4, 2, w - 8, h - 4, cardArc, cardArc)
            }
            if (focusedState && !selectedState) {
                g2.color = Theme.PRIMARY
                g2.stroke = java.awt.BasicStroke(1.5f)
                g2.drawRoundRect(4, 2, w - 9, h - 5, cardArc, cardArc)
            }

            // Bottom subtle divider
            g2.color = Theme.BORDER_SUBTLE
            g2.drawLine(14, h - 1, w - 14, h - 1)

            // Mini cover (38x54)
            val thumbW = 38
            val thumbH = 54
            val thumbX = 14
            val thumbY = (h - thumbH) / 2
            val icon = coverIcon
            if (icon != null) {
                g2.clip = java.awt.geom.RoundRectangle2D.Float(thumbX.toFloat(), thumbY.toFloat(), thumbW.toFloat(), thumbH.toFloat(), 6f, 6f)
                g2.drawImage(icon.image, thumbX, thumbY, thumbW, thumbH, null)
                g2.clip = null
            } else {
                g2.color = Theme.BG_CARD
                g2.fillRoundRect(thumbX, thumbY, thumbW, thumbH, 6, 6)
            }
            g2.color = Theme.BORDER_SUBTLE
            g2.drawRoundRect(thumbX, thumbY, thumbW - 1, thumbH - 1, 6, 6)

            // Right badges (Unread Signal Pill + Downloaded + Source)
            var rightX = w - 18
            if (unreadCount > 0) {
                val uStr = "$unreadCount"
                g2.font = Theme.FONT_MONO.deriveFont(Font.BOLD, 11f)
                val fm = g2.fontMetrics
                val pw = (fm.stringWidth(uStr) + 14).coerceAtLeast(22)
                val ph = 20
                rightX -= pw
                val py = (h - ph) / 2
                val arc = Theme.BADGE_RADIUS * 2
                g2.color = Theme.SIGNAL
                g2.fillRoundRect(rightX, py, pw, ph, arc, arc)
                g2.color = Color.WHITE
                g2.drawString(uStr, rightX + (pw - fm.stringWidth(uStr)) / 2, py + ((ph - fm.height) / 2) + fm.ascent)
                rightX -= 8
            }

            if (downloadsCount > 0) {
                val pw = drawVectorDownloadPill(
                    g2,
                    downloadsCount,
                    rightX,
                    (h - 20) / 2,
                    Theme.SUCCESS,
                    badgeForegroundFor(Theme.SUCCESS),
                    alignRight = true,
                )
                rightX -= pw + 10
            }

            if (sourceText.isNotBlank()) {
                g2.font = Theme.FONT_UI.deriveFont(Font.PLAIN, 11.5f)
                val fm = g2.fontMetrics
                val sw = fm.stringWidth(sourceText)
                rightX -= sw
                g2.color = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
                g2.drawString(sourceText, rightX, (h - fm.height) / 2 + fm.ascent)
                rightX -= 12
            }

            // Title & Subtitle safely ellipsized before badges
            val textX = thumbX + thumbW + 14
            val maxTextW = (rightX - textX).coerceAtLeast(40)
            g2.font = Theme.FONT_UI.deriveFont(Font.BOLD, 13.5f)
            g2.color = if (selectedState) Theme.ACCENT else Theme.TEXT_PRIMARY
            Theme.drawStringWithFallback(g2, clipStr(g2, titleText, maxTextW), textX, 30)

            g2.font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12f)
            g2.color = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
            Theme.drawStringWithFallback(g2, clipStr(g2, subtitleText, maxTextW), textX, 50)

            g2.dispose()
        }
    }

    /**
     * Segmented editorial category selector (38px height, BG_SURFACE track, solid PRIMARY active segment).
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
            val targetX = targetRect.x.toFloat()
            val targetW = targetRect.width.coerceAtLeast(24).toFloat()
            if (animIndicatorX < 0f || animIndicatorW <= 0f) {
                animIndicatorX = targetX
                animIndicatorW = targetW
                repaint()
                return
            }
            val startX = animIndicatorX
            val startW = animIndicatorW
            val startTime = System.currentTimeMillis()
            val durationMs = Theme.MOTION_FAST_MS.toLong()
            indicatorTimer?.stop()
            indicatorTimer = javax.swing.Timer(12) { e ->
                val rawT = ((System.currentTimeMillis() - startTime).toFloat() / durationMs).coerceIn(0f, 1f)
                val eased = easeOutCubic(rawT)
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
            isOpaque = false
            background = Theme.BG_SURFACE
            preferredSize = Dimension(320, Theme.CONTROL_HEIGHT_COMPACT)
            minimumSize = Dimension(120, Theme.CONTROL_HEIGHT_COMPACT)
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
                            scrollRectToVisible(hit.second)
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
                        val ownerWin = SwingUtilities.getWindowAncestor(this@LibraryScreen)
                        mihon.desktop.ui.components.MihonPromptDialog(
                            owner = ownerWin,
                            dialogTitle = "Renomear Categoria",
                            promptLabel = "Novo nome para a categoria '$catTitle':",
                            initialValue = catTitle,
                            placeholder = "Nome da categoria…",
                            confirmText = "Salvar",
                        ) { newName ->
                            if (newName != catTitle) {
                                LibraryCategory.updateCategory(catId, newName)
                            }
                        }.isVisible = true
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
                GooeyContextMenu.showSmart(items, this, x, y)
            }
        }

        override fun getPreferredSize(): Dimension {
            val fm = getFontMetrics(Theme.FONT_UI.deriveFont(Font.BOLD, 12.5f))
            var x = 4
            LibraryCategory.entries.forEach { (_, label) ->
                val tabW = 14 + fm.stringWidth(label) + 14
                x += tabW + 4
            }
            val parentW = parent?.width ?: 200
            return Dimension(maxOf(x, parentW), Theme.CONTROL_HEIGHT_COMPACT)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

            val h = Theme.CONTROL_HEIGHT_COMPACT
            val trackW = width.coerceAtLeast(1)
            val trackArc = (Theme.CONTROL_RADIUS * 2).toFloat()

            g2.color = if (Theme.isDark) Color(0x2A, 0x2A, 0x2E) else Theme.BG_SURFACE
            g2.fill(java.awt.geom.RoundRectangle2D.Float(0.5f, 0.5f, trackW - 1f, h - 1f, trackArc, trackArc))
            g2.color = Theme.BORDER_SUBTLE
            g2.draw(java.awt.geom.RoundRectangle2D.Float(0.5f, 0.5f, trackW - 1f, h - 1f, trackArc, trackArc))

            tabBounds.clear()
            var x = 4
            val innerY = 3
            val innerH = Theme.CONTROL_HEIGHT_SMALL

            g2.font = Theme.FONT_UI.deriveFont(Font.BOLD, 12.5f)
            val boldFm = g2.fontMetrics
            g2.font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12.5f)
            val regFm = g2.fontMetrics

            LibraryCategory.entries.forEach { (status, label) ->
                val active = status == activeCategoryStatus
                val labelW = boldFm.stringWidth(label)
                val tabW = 14 + labelW + 14

                val rect = java.awt.Rectangle(x, innerY, tabW, innerH)
                tabBounds.add(status to rect)

                if (active && indicatorTimer?.isRunning != true) {
                    animIndicatorX = x.toFloat()
                    animIndicatorW = tabW.toFloat()
                }
                x += tabW + 4
            }

            // Hover pill underneath active segment
            LibraryCategory.entries.forEachIndexed { idx, (status, _) ->
                val active = status == activeCategoryStatus
                if (!active && idx == hoveredTab && idx < tabBounds.size) {
                    val r = tabBounds[idx].second
                    g2.color = Theme.BG_CARD_HOVER
                    g2.fillRoundRect(r.x, r.y, r.width, r.height, 12, 12)
                }
            }

            // Solid cobalt active segment
            if (animIndicatorX >= 0f && animIndicatorW > 0f) {
                g2.color = Theme.PRIMARY
                g2.fillRoundRect(animIndicatorX.toInt(), innerY, animIndicatorW.toInt(), innerH, 12, 12)
            }

            LibraryCategory.entries.forEachIndexed { idx, (status, label) ->
                val rect = tabBounds.getOrNull(idx)?.second ?: return@forEachIndexed
                val active = status == activeCategoryStatus
                val hovered = idx == hoveredTab

                g2.font = Theme.FONT_UI.deriveFont(if (active) Font.BOLD else Font.PLAIN, 12.5f)
                val fm = if (active) boldFm else regFm
                val labelW = fm.stringWidth(label)
                g2.color = when {
                    active -> Color.WHITE
                    hovered -> Theme.TEXT_PRIMARY
                    else -> Theme.TEXT_SECONDARY
                }
                val textX = rect.x + (rect.width - labelW) / 2
                val textY = innerY + (innerH - fm.height) / 2 + fm.ascent
                g2.drawString(label, textX, textY)

                if (isDragging && dropTargetIndex == idx) {
                    g2.color = Theme.PRIMARY
                    g2.fillRoundRect(rect.x - 2, innerY, 3, innerH, 3, 3)
                }
            }

            g2.dispose()
        }
    }
}

internal fun formatLibraryCount(
    shown: Int,
    total: Int,
    hasQueryOrFilter: Boolean,
    unreadOnly: Boolean,
): String = when {
    !hasQueryOrFilter -> "$total ${if (total == 1) "título" else "títulos"}"
    unreadOnly -> "$shown ${if (shown == 1) "título com não lidos" else "títulos com não lidos"}"
    else -> "$shown ${if (shown == 1) "resultado" else "resultados"}"
}

internal fun computeLibraryColumnCount(
    viewportWidth: Int,
    cellWidth: Int = CARD_W,
    horizontalPadding: Int = Theme.PAGE_GUTTER * 2,
): Int = ((viewportWidth - horizontalPadding).coerceAtLeast(cellWidth) / cellWidth).coerceAtLeast(1)
