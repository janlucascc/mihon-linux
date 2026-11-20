package mihon.desktop

import tachiyomi.domain.chapter.model.Chapter
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
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import java.util.prefs.Preferences

/**
 * Modern details screen: hero banner with cover & metadata, smart "Continuar Leitura" button,
 * sleek custom-painted chapter row cards (no raw JTable), 1-click read, and right-click context menu.
 */
class DetailsScreen(private val ctx: AppContext) : JPanel(BorderLayout()) {

    private val detailsPrefs = Preferences.userRoot().node("mihon-linux")
    private var manga: Manga? = null
    private var chapters: List<Chapter> = emptyList()
    private var resumeChapter: Chapter? = null
    private var updatingCategory = false
    @Volatile private var openingChapterId: Long? = null
    private var cancelStreamingTask: (() -> Unit)? = null
    private var filterMode = 0 // 0 = All, 1 = Unread, 2 = Downloaded
    private var reverseOrder = detailsPrefs.getBoolean("details_reverse_order", false)

    private val header = object : JPanel(BorderLayout(20, 14)) {
        init {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(16, Theme.PAGE_GUTTER, 16, Theme.PAGE_GUTTER)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val base = Theme.BG_SURFACE
            val wash = blendUiColor(base, Theme.PRIMARY, if (Theme.isDark) 0.07f else 0.05f)
            g2.paint = java.awt.GradientPaint(0f, 0f, wash, 0f, height.toFloat(), base)
            g2.fillRect(0, 0, width, height)
            g2.color = Theme.BORDER_SUBTLE
            g2.drawLine(0, height - 1, width, height - 1)
            g2.dispose()
        }
    }
    private var lastHeaderStacked = false
    private val chapterErrorLabel = JLabel("").apply {
        font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12.5f)
        foreground = Theme.DANGER
    }
    private val chapterErrorBtn = ModernButton("Tentar novamente", variant = ModernButton.Variant.SURFACE, compact = true).apply {
        addActionListener { reloadChapters() }
    }
    private val chapterErrorBanner = JPanel(BorderLayout(12, 0)).apply {
        isOpaque = true
        background = Theme.BG_SURFACE
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DANGER),
            BorderFactory.createEmptyBorder(8, Theme.PAGE_GUTTER, 8, Theme.PAGE_GUTTER),
        )
        add(chapterErrorLabel, BorderLayout.CENTER)
        add(chapterErrorBtn, BorderLayout.EAST)
        isVisible = false
    }
    private val chapterHeaderLabel = JLabel("0 capítulos").apply {
        foreground = Theme.TEXT_PRIMARY
        font = Theme.FONT_UI.deriveFont(Font.BOLD, 14f)
    }
    private val chapterSearchField = mihon.desktop.ui.components.MihonSearchField(
        columns = 16,
        placeholder = "Filtrar capítulo ou scan…",
        compact = true,
        shortcutBadge = null,
        debounceMs = 90,
    ).apply {
        preferredSize = Dimension(190, 34)
        onDebouncedChange = { applyChapterListFilter() }
        onSubmit = { applyChapterListFilter() }
    }
    private val filterCombo = mihon.desktop.ui.components.MihonDropdown(
        arrayOf("Todos", "Não lidos", "Baixados"),
    ).apply {
        preferredSize = Dimension(136, Theme.CONTROL_HEIGHT_COMPACT)
    }
    private val sortOrderBtn = ModernButton(
        text = if (reverseOrder) "Mais antigos" else "Mais recentes",
        icon = if (reverseOrder) ModernIcons.arrowUp(14) { Theme.TEXT_PRIMARY } else ModernIcons.arrowDown(14) { Theme.TEXT_PRIMARY },
        variant = ModernButton.Variant.SURFACE,
    ).apply {
        toolTipText = "Inverter ordem dos capítulos"
        addActionListener {
            reverseOrder = !reverseOrder
            detailsPrefs.putBoolean("details_reverse_order", reverseOrder)
            updateSortOrderButton()
            applyChapterListFilter()
        }
    }

    private fun updateSortOrderButton() {
        sortOrderBtn.text = if (reverseOrder) "Mais antigos" else "Mais recentes"
        sortOrderBtn.icon = if (reverseOrder) {
            ModernIcons.arrowUp(14) { Theme.TEXT_PRIMARY }
        } else {
            ModernIcons.arrowDown(14) { Theme.TEXT_PRIMARY }
        }
    }
    private val catPicker = mihon.desktop.ui.components.MihonDropdown(
        LibraryCategory.entries.filter { it.first != LibraryCategory.ALL }.map { it.second }.toTypedArray(),
    )
    private val btnFavorite = ModernButton(
        text = "Adicionar",
        icon = ModernIcons.heart(15, filled = false) { Theme.TEXT_PRIMARY },
        variant = ModernButton.Variant.SURFACE,
    ).apply {
        addActionListener { toggleFavorite() }
    }
    private val btnTrack = ModernButton(
        text = "Rastrear",
        icon = ModernIcons.sync(14) { Theme.TEXT_PRIMARY },
        variant = ModernButton.Variant.SURFACE,
    ).apply {
        toolTipText = "Vincular e sincronizar progresso com AniList, MyAnimeList ou Rastreador Local"
        addActionListener { openTrackerDialog() }
    }
    private val btnContinue = ModernButton(
        text = "Continuar Leitura",
        icon = ModernIcons.play(14) { Color.WHITE },
        variant = ModernButton.Variant.PRIMARY,
    ).apply {
        minHeightOverride = Theme.CONTROL_HEIGHT_PRIMARY
        font = font.deriveFont(Font.BOLD, 13.5f)
        addActionListener { continueReading() }
    }
    private val progressBar = JProgressBar(0, 100).apply {
        isStringPainted = true
        isVisible = false
        preferredSize = Dimension(100, 22)
    }
    private val btnCancelStreaming = ModernButton(
        text = "Cancelar",
        icon = ModernIcons.close(12) { Theme.DANGER },
        variant = ModernButton.Variant.SURFACE,
    ).apply {
        isVisible = false
        margin = java.awt.Insets(2, 8, 2, 8)
        addActionListener {
            cancelStreamingTask?.invoke()
        }
    }

    private val listModel = DefaultListModel<Chapter>()
    private val chapterList = JList(listModel)
    private val rowRenderer = ChapterRowCard()
    private val hoverAnimator = GridHoverAnimator(chapterList)
    private var themeListener: ((Boolean) -> Unit)? = null

    init {
        background = Theme.BG_APP
        chapterList.background = Theme.BG_APP
        chapterList.fixedCellHeight = 56
        chapterList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        chapterList.border = BorderFactory.createEmptyBorder(10, 20, 18, 20)
        chapterList.cellRenderer = ListCellRenderer { _, ch, index, selected, _ ->
            val current = manga
            val downloaded = current != null && ctx.service.isChapterDownloaded(current, ch)
            val downloading = ctx.service.isChapterDownloading(ch.id)
            rowRenderer.apply {
                chapterIndex = index + 1
                chapterTitle = ch.name
                isRead = ch.read
                isDownloaded = downloaded
                isDownloading = downloading
                isOpening = (openingChapterId == ch.id)
                lastPage = ch.lastPageRead
                selectedState = selected
                hoverProgress = hoverAnimator.progressFor(index)
            }
        }
        chapterList.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val idx = chapterList.locationToIndex(e.point)
                val valid = if (idx >= 0 && chapterList.getCellBounds(idx, idx)?.contains(e.point) == true) idx else -1
                chapterList.cursor = if (valid >= 0) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                hoverAnimator.setHovered(valid)
            }
        })
        chapterList.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                hoverAnimator.setHovered(-1)
            }

            override fun mouseClicked(e: MouseEvent) {
                val idx = chapterList.locationToIndex(e.point)
                if (idx < 0 || chapterList.getCellBounds(idx, idx)?.contains(e.point) != true) return
                val ch = listModel[idx]
                if (SwingUtilities.isRightMouseButton(e)) {
                    if (!chapterList.isSelectedIndex(idx)) chapterList.selectedIndex = idx
                    showChapterMenu(e.x, e.y)
                } else if (SwingUtilities.isLeftMouseButton(e) && !e.isShiftDown && !e.isControlDown) {
                    val current = manga ?: return
                    openChapter(current, ch)
                }
            }
        })

        catPicker.addActionListener {
            if (updatingCategory) return@addActionListener
            val current = manga ?: return@addActionListener
            val chosen = LibraryCategory.entries.filter { it.first != LibraryCategory.ALL }
                .getOrNull(catPicker.selectedIndex)?.first ?: return@addActionListener
            background({ ctx.service.setCategory(current.id, chosen) }) {
                val updated = current.copy(status = chosen, favorite = true)
                manga = updated
                updateFavoriteButtonState(true)
                renderHeader(updated)
                ctx.setStatus("Categoria alterada para '${LibraryCategory.labelOf(chosen)}'")
            }
        }

        filterCombo.addActionListener {
            filterMode = filterCombo.selectedIndex.coerceAtLeast(0)
            applyChapterListFilter()
        }

        val btnBack = ModernButton(
            text = "Biblioteca",
            icon = ModernIcons.chevronLeft(16) { Theme.TEXT_PRIMARY },
            variant = ModernButton.Variant.SURFACE,
        ).apply {
            addActionListener { ctx.navigator.showLibrary() }
        }

        val btnDownload = ModernButton(
            text = "Baixar Tudo",
            icon = ModernIcons.downloads(15) { Theme.TEXT_PRIMARY },
            variant = ModernButton.Variant.SURFACE,
        ).apply {
            toolTipText = "Baixar todos os capítulos do mangá"
            addActionListener { downloadSelected() }
        }

        val btnSync = ModernButton(
            text = "Sincronizar",
            icon = ModernIcons.sync(15) { Theme.TEXT_PRIMARY },
            variant = ModernButton.Variant.SURFACE,
        ).apply {
            toolTipText = "Atualizar capítulos da fonte online"
            addActionListener { refreshRemoteChapters() }
        }

        val btnOverflow = ModernIconButton(
            icon = ModernIcons.moreVert(18) { Theme.TEXT_PRIMARY },
            tip = "Mais opções",
            sizePx = Theme.CONTROL_HEIGHT_COMPACT,
            surfaceStyled = true,
        ).apply {
            addActionListener {
                val menu = mihon.desktop.ui.components.MihonPopupMenu.create().apply {
                    add(mihon.desktop.ui.components.MihonPopupMenu.item("Marcar selecionados como lidos", ModernIcons.check(14) { Theme.TEXT_PRIMARY }) {
                        markSelected(true)
                    })
                    add(mihon.desktop.ui.components.MihonPopupMenu.item("Marcar selecionados como não lidos") {
                        markSelected(false)
                    })
                    addSeparator()
                    add(mihon.desktop.ui.components.MihonPopupMenu.item("Exportar selecionados (PDF / EPUB)…", ModernIcons.downloads(14) { Theme.TEXT_PRIMARY }) {
                        exportSelected()
                    })
                    add(mihon.desktop.ui.components.MihonPopupMenu.item("Configurar Rastreamento (AniList / MAL)…", ModernIcons.sync(14) { Theme.TEXT_PRIMARY }) {
                        openTrackerDialog()
                    })
                    addSeparator()
                    add(mihon.desktop.ui.components.MihonPopupMenu.item("Remover da biblioteca", ModernIcons.trash(14) { Theme.DANGER }, danger = true) {
                        removeCurrent()
                    })
                }
                menu.show(this, 0, height + 4)
            }
        }

        catPicker.apply {
            preferredSize = Dimension(148, Theme.CONTROL_HEIGHT_COMPACT)
        }

        val leftActions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(btnBack)
        }

        val rightActions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(btnDownload)
            add(btnSync)
            add(JLabel("Categoria:").apply {
                foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
                font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12f)
            })
            add(catPicker)
            add(btnOverflow)
        }

        val actions = JPanel(BorderLayout(8, 0)).apply {
            background = Theme.BG_APP
            border = BorderFactory.createEmptyBorder(8, Theme.PAGE_GUTTER, 8, Theme.PAGE_GUTTER)
            add(leftActions, BorderLayout.WEST)
            add(rightActions, BorderLayout.EAST)
        }

        val chapterHeaderBar = JPanel(BorderLayout(12, 8)).apply {
            background = Theme.BG_APP
            border = BorderFactory.createEmptyBorder(10, Theme.PAGE_GUTTER + 4, 4, Theme.PAGE_GUTTER + 4)
            add(chapterHeaderLabel, BorderLayout.WEST)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                    isOpaque = false
                    add(chapterSearchField)
                    add(filterCombo)
                    add(sortOrderBtn)
                },
                BorderLayout.EAST,
            )
        }

        val progressWrap = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, Theme.PAGE_GUTTER, 4, Theme.PAGE_GUTTER)
            add(progressBar, BorderLayout.CENTER)
            add(btnCancelStreaming, BorderLayout.EAST)
        }

        val bottomBar = JPanel(BorderLayout()).apply {
            background = Theme.BG_APP
            add(chapterErrorBanner, BorderLayout.NORTH)
            add(progressWrap, BorderLayout.CENTER)
            add(chapterHeaderBar, BorderLayout.SOUTH)
        }

        header.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val stacked = header.width in 1..660
                if (stacked != lastHeaderStacked) {
                    manga?.let { renderHeader(it) }
                }
            }
        })

        val top = JPanel(BorderLayout()).apply {
            background = Theme.BG_APP
            add(actions, BorderLayout.NORTH)
            add(header, BorderLayout.CENTER)
            add(bottomBar, BorderLayout.SOUTH)
        }
        val scroll = JScrollPane(chapterList).apply {
            border = null
            background = Theme.BG_APP
            viewport.background = Theme.BG_APP
            verticalScrollBar.unitIncrement = 24
        }
        add(top, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)

        val listener = { _: Boolean ->
            background = Theme.BG_APP
            top.background = Theme.BG_APP
            header.repaint()
            actions.background = Theme.BG_APP
            bottomBar.background = Theme.BG_APP
            chapterErrorBanner.background = Theme.BG_SURFACE
            chapterErrorLabel.foreground = Theme.DANGER
            chapterHeaderBar.background = Theme.BG_APP
            chapterList.background = Theme.BG_APP
            scroll.background = Theme.BG_APP
            scroll.viewport.background = Theme.BG_APP
            chapterHeaderLabel.foreground = Theme.TEXT_PRIMARY
            updateContinueButtonStyle()
            manga?.let { renderHeader(it) }
            chapterList.repaint()
            repaint()
        }
        themeListener = listener
        ctx.addThemeListener(listener)

        updateContinueButtonStyle()
    }

    fun dispose() {
        hoverAnimator.stop()
        themeListener?.let { ctx.removeThemeListener(it) }
    }

    private fun updateContinueButtonStyle() {
        btnContinue.variant = ModernButton.Variant.PRIMARY
        btnContinue.foreground = Color.WHITE
        btnContinue.icon = ModernIcons.play(13) { Color.WHITE }
    }

    private fun showChapterMenu(x: Int, y: Int) {
        val current = manga ?: return
        val selected = chapterList.selectedValuesList
        if (selected.isEmpty()) return
        val target = selected.first()

        val gooeyItems = mutableListOf<GooeyContextMenu.GooeyMenuItem>()

        gooeyItems.add(
            GooeyContextMenu.GooeyMenuItem(
                id = "read_now",
                label = "Ler agora",
                iconType = GooeyContextMenu.IconType.READING,
                onClick = { openChapter(current, target) }
            )
        )

        if (!current.isLocal) {
            gooeyItems.add(
                GooeyContextMenu.GooeyMenuItem(
                    id = "download",
                    label = "Baixar selecionado(s) (.cbz)",
                    iconType = GooeyContextMenu.IconType.DOWNLOAD,
                    onClick = { downloadSelected() }
                )
            )
        }

        gooeyItems.add(
            GooeyContextMenu.GooeyMenuItem(
                id = "export_pdf_epub",
                label = "Exportar capítulo (PDF / EPUB)…",
                iconType = GooeyContextMenu.IconType.DOWNLOAD,
                onClick = { exportSelected() }
            )
        )

        gooeyItems.add(
            GooeyContextMenu.GooeyMenuItem(
                id = "mark_read",
                label = "Marcar como lido",
                iconType = GooeyContextMenu.IconType.COMPLETED,
                onClick = { markSelected(true) }
            )
        )

        gooeyItems.add(
            GooeyContextMenu.GooeyMenuItem(
                id = "mark_unread",
                label = "Marcar como não lido",
                iconType = GooeyContextMenu.IconType.UNREAD,
                onClick = { markSelected(false) }
            )
        )

        GooeyContextMenu.showSmart(gooeyItems, chapterList, x, y)
    }

    fun show(manga: Manga) {
        val switchingManga = this.manga?.id != manga.id
        if (switchingManga) {
            cancelStreamingTask?.invoke()
            cancelStreamingTask = null
            openingChapterId = null
            if (chapterSearchField.text.isNotEmpty()) {
                chapterSearchField.text = ""
            }
        }
        this.manga = manga
        updatingCategory = true
        val validCats = LibraryCategory.entries.filter { it.first != LibraryCategory.ALL }
        catPicker.removeAllItems()
        validCats.forEach { catPicker.addItem(it.second) }
        val catIdx = validCats.indexOfFirst { it.first == manga.status }.coerceAtLeast(0)
        catPicker.selectedIndex = catIdx
        updateFavoriteButtonState(manga.favorite)
        updatingCategory = false

        renderHeader(manga)
        if (!ctx.covers.has(manga.id)) {
            ctx.covers.load(manga, firstChapterPath = { null }, onLoaded = { renderHeader(manga) })
        }
        reloadChapters()
    }

    private fun renderHeader(manga: Manga) {
        header.removeAll()
        val coverIcon = ctx.covers.get(manga.id, manga.title)
        val detailW = 148
        val detailH = 220
        val stacked = header.width in 1..660
        lastHeaderStacked = stacked

        val coverHolder = object : JPanel() {
            init {
                isOpaque = false
                preferredSize = Dimension(detailW + 12, detailH + 12)
                minimumSize = Dimension(detailW + 12, detailH + 12)
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                val cx = if (stacked) (width - detailW) / 2 else 4
                val cy = 4
                val arcInt = Theme.CARD_RADIUS * 2
                val arc = arcInt.toFloat()
                // Subtle cover shadow (0/8/16)
                g2.color = Color(0, 0, 0, if (Theme.isDark) 85 else 34)
                g2.fillRoundRect(cx, cy + 6, detailW, detailH, arcInt + 2, arcInt + 2)
                g2.color = Color(0, 0, 0, if (Theme.isDark) 115 else 52)
                g2.fillRoundRect(cx, cy + 3, detailW, detailH, arcInt, arcInt)

                val oldClip = g2.clip
                g2.clip = java.awt.geom.RoundRectangle2D.Float(cx.toFloat(), cy.toFloat(), detailW.toFloat(), detailH.toFloat(), arc, arc)
                g2.drawImage(coverIcon.image, cx, cy, detailW, detailH, null)
                g2.clip = oldClip

                g2.color = Theme.BORDER_SUBTLE
                g2.drawRoundRect(cx, cy, detailW - 1, detailH - 1, arcInt, arcInt)
                g2.dispose()
            }
        }
        val sourceName = if (manga.isLocal) "Arquivo local" else (ctx.sourceFor(manga.source)?.name ?: "Fonte online")
        val statusBadge = LibraryCategory.labelOf(manga.status)
        val unreadCount = chapters.count { !it.read }
        val readCount = (chapters.size - unreadCount).coerceAtLeast(0)
        val downloadedCount = chapters.count { ctx.service.isChapterDownloaded(manga, it) }

        val authorLine = manga.author?.takeIf { it.isNotBlank() }?.let { auth ->
            val artistPart = manga.artist?.takeIf { a -> a != auth && a.isNotBlank() }?.let { "  •  Arte: $it" } ?: ""
            "$auth$artistPart"
        }

        val titleView = mihon.desktop.ui.components.CjkLabel(
            labelText = manga.title,
            fontStyle = Font.BOLD,
            fontSize = 23f,
            colorSupplier = { Theme.TEXT_PRIMARY },
            maxLines = 2,
        ).apply {
            alignmentX = 0f
        }

        val metaColor = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
        val metaRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 2)).apply {
            isOpaque = false
            alignmentX = 0f
            fun addSep() {
                add(JLabel("  •  ").apply {
                    font = Theme.FONT_UI.deriveFont(12.5f)
                    foreground = metaColor
                })
            }
            if (!authorLine.isNullOrBlank()) {
                add(mihon.desktop.ui.components.CjkLabel(authorLine, Font.PLAIN, 12.5f, { metaColor }, maxLines = 1).apply {
                    val fm = getFontMetrics(Theme.FONT_UI.deriveFont(12.5f))
                    preferredSize = Dimension((fm.stringWidth(authorLine) + 6).coerceAtMost(300), 20)
                })
                addSep()
            }
            add(JLabel(statusBadge).apply {
                font = Theme.FONT_UI.deriveFont(Font.BOLD, 12.5f)
                foreground = Theme.TEXT_PRIMARY
            })
            addSep()
            if (!manga.isLocal && ctx.sourceFor(manga.source) != null) {
                add(JLabel(sourceName).apply {
                    font = Theme.FONT_UI.deriveFont(Font.BOLD, 12.5f)
                    foreground = if (Theme.isDark) Theme.ACCENT else Theme.PRIMARY
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    toolTipText = "Abrir catálogo de $sourceName"
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            ctx.navigator.openSourceCatalog(sourceName)
                        }
                    })
                })
            } else {
                add(JLabel(sourceName).apply {
                    font = Theme.FONT_UI.deriveFont(12.5f)
                    foreground = metaColor
                })
            }
            addSep()
            add(JLabel("${chapters.size} cap.").apply {
                font = Theme.FONT_UI.deriveFont(12.5f)
                foreground = metaColor
            })
            if (unreadCount > 0) {
                addSep()
                add(JLabel("$unreadCount por ler").apply {
                    font = Theme.FONT_UI.deriveFont(Font.BOLD, 12.5f)
                    foreground = Theme.SIGNAL
                })
            }
            if (downloadedCount > 0) {
                addSep()
                add(JLabel("$downloadedCount offline").apply {
                    font = Theme.FONT_UI.deriveFont(Font.BOLD, 12.5f)
                    foreground = Theme.SUCCESS
                })
            }
        }

        val headerInfo = JPanel().apply {
            isOpaque = false
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(titleView)
            add(javax.swing.Box.createVerticalStrut(4))
            add(metaRow)
            if (chapters.isNotEmpty()) {
                val frac = (readCount.toFloat() / chapters.size.toFloat()).coerceIn(0f, 1f)
                val progressRow = object : javax.swing.JComponent() {
                    init {
                        isOpaque = false
                        preferredSize = Dimension(320, 14)
                        maximumSize = Dimension(420, 14)
                        alignmentX = 0f
                    }
                    override fun paintComponent(g: Graphics) {
                        val g2 = g.create() as Graphics2D
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        val barW = (width - 96).coerceAtLeast(80)
                        val barY = 5
                        g2.color = Theme.BORDER_SUBTLE
                        g2.fillRoundRect(0, barY, barW, 5, 5, 5)
                        if (frac > 0f) {
                            g2.color = if (frac >= 0.999f) Theme.SUCCESS else Theme.SIGNAL
                            g2.fillRoundRect(0, barY, (barW * frac).toInt().coerceAtLeast(5), 5, 5, 5)
                        }
                        g2.font = Theme.FONT_MONO.deriveFont(Font.BOLD, 10.5f)
                        g2.color = metaColor
                        g2.drawString("$readCount/${chapters.size} lidos", barW + 8, 11)
                        g2.dispose()
                    }
                }
                add(javax.swing.Box.createVerticalStrut(4))
                add(progressRow)
            }
            val descText = manga.description?.trim()?.takeIf { it.isNotEmpty() }
            if (descText != null) {
                add(javax.swing.Box.createVerticalStrut(6))
                add(mihon.desktop.ui.components.ExpandableCjkText(descText, collapsedMaxLines = 3).apply {
                    alignmentX = 0f
                })
            }
        }

        val genresPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(4, 0, 2, 0)
            val genres = manga.genre.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(6)
            genres.forEach { gName ->
                add(
                    object : JLabel(gName) {
                        init {
                            font = Theme.FONT_UI.deriveFont(Font.BOLD, 11.5f)
                            foreground = if (Theme.isDark) Theme.ACCENT else Theme.PRIMARY
                            border = BorderFactory.createEmptyBorder(3, 8, 3, 8)
                            preferredSize = Dimension(preferredSize.width, 24)
                        }

                        override fun paintComponent(g: Graphics) {
                            val g2 = g.create() as Graphics2D
                            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                            g2.color = Theme.ACCENT_SOFT
                            val bArc = Theme.BADGE_RADIUS * 2
                            g2.fillRoundRect(0, 0, width, height, bArc, bArc)
                            g2.dispose()
                            super.paintComponent(g)
                        }
                    },
                )
            }
        }

        val trackRec = TrackerService.getRecord(manga.id)
        if (trackRec != null) {
            btnTrack.text = "Rastreado: Cap. ${trackRec.lastChapterRead}"
            btnTrack.variant = ModernButton.Variant.FAVORITE
        } else {
            btnTrack.text = "Rastrear"
            btnTrack.variant = ModernButton.Variant.SURFACE
        }

        btnContinue.minWidthOverride = 190
        btnContinue.minHeightOverride = Theme.CONTROL_HEIGHT_PRIMARY

        val heroActions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(4, -6, 0, 0)
            add(btnContinue)
            add(btnFavorite)
            add(btnTrack)
        }

        val infoStack = JPanel().apply {
            isOpaque = false
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            headerInfo.alignmentX = 0f
            genresPanel.alignmentX = 0f
            add(headerInfo)
            if (genresPanel.componentCount > 0) add(genresPanel)
        }

        val centerCol = JPanel(BorderLayout(0, 6)).apply {
            isOpaque = false
            add(infoStack, BorderLayout.CENTER)
            add(heroActions, BorderLayout.SOUTH)
        }
        if (stacked) {
            header.add(coverHolder, BorderLayout.NORTH)
            header.add(centerCol, BorderLayout.CENTER)
        } else {
            header.add(coverHolder, BorderLayout.WEST)
            header.add(centerCol, BorderLayout.CENTER)
        }
        header.revalidate()
        header.repaint()
    }

    private fun applyChapterListFilter() {
        val current = manga ?: return
        val query = chapterSearchField.text?.trim().orEmpty()
        val filtered = chapters.filter { ch ->
            val modeOk = when (filterMode) {
                1 -> !ch.read
                2 -> ctx.service.isChapterDownloaded(current, ch)
                else -> true
            }
            val queryOk = query.isEmpty() ||
                ch.name.contains(query, ignoreCase = true) ||
                (ch.scanlator?.contains(query, ignoreCase = true) == true) ||
                ch.chapterNumber.toString().contains(query, ignoreCase = true)
            modeOk && queryOk
        }.let { if (reverseOrder) it.reversed() else it }

        listModel.clear()
        filtered.forEach { listModel.addElement(it) }
        val unread = chapters.count { !it.read }
        chapterHeaderLabel.text = "${filtered.size} de ${chapters.size} capítulos" +
            (if (unread > 0) "  —  $unread por ler" else "  —  Leitura em dia")
    }

    private fun reloadChapters() {
        val current = manga ?: return
        chapterErrorBanner.isVisible = false
        background({
            val list = ctx.service.chaptersOf(current.id)
            val resume = ctx.service.nextChapterToRead(current.id)
            list to resume
        }) { result ->
            result.onSuccess { (list, resume) ->
                chapterErrorBanner.isVisible = false
                chapters = list
                resumeChapter = resume
                applyChapterListFilter()
                btnContinue.text = if (resume != null) {
                    val pageSuffix = if (!resume.read && resume.lastPageRead > 0) " (pág. ${resume.lastPageRead + 1})" else ""
                    "Continuar: ${resume.name.take(20)}$pageSuffix"
                } else {
                    "Ler do início"
                }
                renderHeader(current)
            }.onFailure { err ->
                chapterErrorLabel.text = "Não foi possível carregar os capítulos: ${err.message ?: "verifique sua conexão"}."
                chapterErrorBanner.isVisible = true
            }
        }
    }

    private fun continueReading() {
        val current = manga ?: return
        val target = chapterList.selectedValue ?: resumeChapter ?: chapters.firstOrNull { !it.read } ?: chapters.firstOrNull() ?: return
        openChapter(current, target)
    }

    private fun openChapter(current: Manga, chapter: Chapter) {
        // Prevent opening multiple chapters simultaneously or re-triggering while loading
        // Atomic check-and-set to avoid race condition
        if (openingChapterId != null) {
            ctx.setStatus("Já existe um capítulo sendo aberto. Aguarde ou cancele…")
            return
        }
        openingChapterId = chapter.id
        try {
            if (ctx.service.isChapterDownloaded(current, chapter)) {
                try {
                    openReaderFile(current, chapter, ctx.service.chapterCbzFile(current, chapter))
                } finally {
                    openingChapterId = null
                    chapterList.repaint()
                }
                return
            }
            val source = ctx.sourceFor(current.source)
            if (source == null) {
                openingChapterId = null
                chapterList.repaint()
                JOptionPane.showMessageDialog(this, "Fonte não encontrada para ler o capítulo.", "Erro", JOptionPane.ERROR_MESSAGE)
                return
            }

            var isCancelled = false
            cancelStreamingTask = {
                isCancelled = true
                openingChapterId = null
                progressBar.isIndeterminate = false
                progressBar.isVisible = false
                btnCancelStreaming.isVisible = false
                ctx.setStatus("Abertura do capítulo '${chapter.name}' cancelada.")
            }

            // Open streaming reader directly without forcing full download
            progressBar.value = 0
            progressBar.string = "Obtendo páginas de '${chapter.name}'…"
            progressBar.isIndeterminate = true
            progressBar.isVisible = true
            btnCancelStreaming.isVisible = true
            ctx.setStatus("Carregando páginas de '${chapter.name}'…")

            background({
                val imageUrls = source.getChapterImages(chapter.url)
                require(imageUrls.isNotEmpty()) { "Nenhuma imagem encontrada para o capítulo '${chapter.name}'" }
                val referer = if (source.name.contains("MangaDex", ignoreCase = true)) "https://mangadex.org/" else "${source.baseUrl}/"
                val userAgent = if (source.name.contains("MangaDex", ignoreCase = true)) "MihonLinux/0.4 (Linux Desktop)" else "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                OnlineStreamArchiveReader(
                    sourceName = "${current.title} - ${chapter.name}",
                    imageUrls = imageUrls,
                    client = ctx.service.httpClient,
                    referer = referer,
                    userAgent = userAgent,
                    cookie = null,
                )
            }) { result ->
                progressBar.isIndeterminate = false
                progressBar.isVisible = false
                btnCancelStreaming.isVisible = false
                val wasOpening = openingChapterId == chapter.id
                openingChapterId = null
                cancelStreamingTask = null

                if (isCancelled || !wasOpening) {
                    runCatching { result.getOrNull()?.close() }
                    return@background
                }

                result.onSuccess { streamReader ->
                    ctx.setStatus("Pronto para leitura: '${chapter.name}'")
                    openReaderArchive(current, chapter, streamReader)
                }.onFailure {
                    ctx.setStatus("Erro ao abrir capítulo: ${it.message}")
                    JOptionPane.showMessageDialog(this, "Falha ao carregar páginas do capítulo:\n${it.message}", "Erro", JOptionPane.ERROR_MESSAGE)
                }
            }
        } catch (e: Exception) {
            openingChapterId = null
            cancelStreamingTask = null
            throw e
        }
    }

    private fun triggerAutoPrefetch(current: Manga, idx: Int) {
        val autoPrefetch = Preferences.userRoot().node("mihon-linux-reader").getBoolean("autoPrefetch", true)
        if (!current.isLocal && idx >= 0 && autoPrefetch) {
            val upcoming = chapters.drop(idx + 1).take(2)
                .filter { !ctx.service.isChapterDownloaded(current, it) }
            val source = ctx.sourceFor(current.source)
            if (upcoming.isNotEmpty() && source != null) {
                background({
                    SwingUtilities.invokeLater { chapterList.repaint() }
                    ctx.service.prefetchChapters(current, upcoming, source) { cached ->
                        SwingUtilities.invokeLater {
                            ctx.setStatus("Pré-carregado em cache: '${cached.name}' ✔")
                            chapterList.repaint()
                        }
                    }
                }) {
                    chapterList.repaint()
                }
            }
        }
    }

    private fun launchOrReuseReader(current: Manga, chapter: Chapter, readerSource: Any) {
        val idx = chapters.indexOfFirst { it.id == chapter.id }
        val prevChap = if (idx > 0) chapters[idx - 1] else null
        val nextChap = if (idx >= 0 && idx + 1 < chapters.size) chapters[idx + 1] else null

        triggerAutoPrefetch(current, idx)

        val title = "${current.title} - ${chapter.name}"
        val startPage = if (chapter.read) 0 else chapter.lastPageRead
        val prevAction = prevChap?.let { { openChapter(current, it) } }
        val nextAction = nextChap?.let { { openChapter(current, it) } }
        val progressCallback: (Int, Int, Long) -> Unit = { page, count, deltaMillis ->
            background({ ctx.service.saveProgress(chapter, page, count, deltaMillis, incognitoMode = ctx.incognitoMode) }) { reloadChapters() }
        }
        val toggleBookmarkAsyncCb: (Long, Int, (Boolean) -> Unit) -> Unit = { chId, page, onDone ->
            background({ ctx.service.toggleBookmark(chId, page) }) { res ->
                res.onSuccess { added -> onDone(added) }
            }
        }
        val loadBookmarksCb: (Long, (List<tachiyomi.domain.bookmark.model.Bookmark>) -> Unit) -> Unit = { chId, onLoaded ->
            background({ ctx.service.getBookmarksByChapterId(chId) }) { res ->
                res.onSuccess { list -> onLoaded(list) }
            }
        }
        val chapterNavList: List<Pair<String, () -> Unit>> = chapters.map { ch ->
            ch.name to { openChapter(current, ch) }
        }
        val addNoteCb: (Long, Int, String) -> Unit = { chId, page, note ->
            background({ ctx.service.addNote(chId, page, note) }) {}
        }
        val addHighlightCb: (Long, Int, String) -> Unit = { chId, page, color ->
            background({ ctx.service.addHighlight(chId, page, color) }) {}
        }
        val showBookmarksCb: (Long, String, Int, (Int) -> Unit) -> Unit = { chId, _, pgCount, onNavigate ->
            BookmarksListDialog(
                owner = ReaderWindow.activeReader,
                ctx = ctx,
                chapterId = chId,
                chapterName = chapter.name,
                pageCount = pgCount,
                onNavigateToPage = onNavigate,
            ).isVisible = true
        }

        val existing = ReaderWindow.activeReader
        if (existing != null && existing.isDisplayable) {
            existing.loadChapter(
                newTitle = title,
                newSource = readerSource,
                startPage = startPage,
                newPrevChapter = prevAction,
                newNextChapter = nextAction,
                newOnProgress = progressCallback,
                newChapterId = chapter.id,
                newMangaTitle = current.title,
                newOnAddNote = addNoteCb,
                newOnAddHighlight = addHighlightCb,
                newOnShowBookmarks = showBookmarksCb,
                newOnToggleBookmarkAsync = toggleBookmarkAsyncCb,
                newOnLoadBookmarks = loadBookmarksCb,
                newChapterNavEntries = chapterNavList,
                newCurrentChapterIndex = idx,
            )
        } else {
            ReaderWindow(
                title = title,
                source = readerSource,
                startPage = startPage,
                onPrevChapter = prevAction,
                onNextChapter = nextAction,
                onProgress = progressCallback,
                chapterId = chapter.id,
                mangaTitle = current.title,
                onAddNote = addNoteCb,
                onAddHighlight = addHighlightCb,
                onShowBookmarks = showBookmarksCb,
                onToggleBookmarkAsync = toggleBookmarkAsyncCb,
                onLoadBookmarks = loadBookmarksCb,
                chapterNavEntries = chapterNavList,
                currentChapterIndex = idx,
            )
        }
    }

    private fun openReaderArchive(current: Manga, chapter: Chapter, reader: mihon.core.archive.ArchiveReader) {
        try {
            launchOrReuseReader(current, chapter, reader)
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Não foi possível abrir o leitor: ${e.message}", "Erro", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun openReaderFile(current: Manga, chapter: Chapter, file: java.io.File, canRetryDownload: Boolean = true) {
        try {
            launchOrReuseReader(current, chapter, file)
        } catch (e: Exception) {
            if (!current.isLocal && canRetryDownload && file.exists()) {
                file.delete()
                ctx.setStatus("Arquivo local corrompido detectado; baixando '${chapter.name}' novamente…")
                openChapter(current, chapter)
                return
            }
            JOptionPane.showMessageDialog(this, "Não foi possível abrir: ${e.message}", "Erro", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun downloadSelected() {
        val current = manga ?: return
        if (current.isLocal) {
            ctx.setStatus("Este mangá já é local")
            return
        }
        val source = ctx.sourceFor(current.source) ?: run {
            JOptionPane.showMessageDialog(this, "Fonte original não encontrada.", "Erro", JOptionPane.ERROR_MESSAGE)
            return
        }
        val targets = chapterList.selectedValuesList.ifEmpty { chapters }
        progressBar.value = 0
        progressBar.string = "Preparando download de ${targets.size} capítulo(s)…"
        progressBar.isVisible = true
        background({
            var okCount = 0
            targets.forEachIndexed { idx, ch ->
                ctx.service.downloadChapter(current, ch, source) { page, total ->
                    SwingUtilities.invokeLater {
                        progressBar.maximum = total.coerceAtLeast(1)
                        progressBar.value = page
                        progressBar.string = "Cap. ${idx + 1}/${targets.size} ('${ch.name}'): página $page/$total"
                        ctx.setStatus("Cap. ${idx + 1}/${targets.size} ('${ch.name}'): pág. $page/$total")
                    }
                }
                okCount++
            }
            okCount
        }) { result ->
            progressBar.isVisible = false
            result.onSuccess { count ->
                ctx.setStatus("$count capítulo(s) baixado(s) em ~/.mihon/downloads")
                if (count == 1) {
                    SystemTrayManager.notifyChapterDownloaded(current.title, targets.first().name)
                } else {
                    SystemTrayManager.notify("Capítulos baixados", "$count capítulos de ${current.title} salvos offline")
                }
                reloadChapters()
            }.onFailure {
                ctx.setStatus("Falha no download: ${it.message}")
                SystemTrayManager.notifyDownloadError(current.title, targets.firstOrNull()?.name ?: "Capítulo", it.message ?: "Erro desconhecido")
                JOptionPane.showMessageDialog(this, "Erro durante download:\n${it.message}", "Erro", JOptionPane.ERROR_MESSAGE)
                reloadChapters()
            }
        }
    }

    private fun markSelected(readState: Boolean) {
        val targets = chapterList.selectedValuesList.ifEmpty { chapters }
        if (targets.isEmpty()) return
        background({ ctx.service.markChaptersRead(targets, readState) }) {
            reloadChapters()
            ctx.setStatus("${targets.size} capítulo(s) atualizado(s)")
        }
    }

    private fun exportSelected() {
        val current = manga ?: return
        val targets = chapterList.selectedValuesList.ifEmpty {
            listOfNotNull(resumeChapter ?: chapters.firstOrNull())
        }
        if (targets.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Nenhum capítulo disponível para exportar.")
            return
        }
        val win = SwingUtilities.getWindowAncestor(this)
        ExportChapterDialog(win, ctx, current, targets).isVisible = true
    }

    private fun openTrackerDialog() {
        val current = manga ?: return
        val maxRead = chapters.filter { it.read }.maxOfOrNull { it.chapterNumber.toInt() } ?: 0
        val win = SwingUtilities.getWindowAncestor(this)
        TrackerDialog(win, ctx, current, maxRead) {
            renderHeader(current)
        }.isVisible = true
    }

    private fun refreshRemoteChapters() {
        val current = manga ?: return
        if (current.isLocal) {
            reloadChapters()
            return
        }
        val source = ctx.sourceFor(current.source) ?: return
        ctx.setStatus("Buscando novos capítulos em ${source.name}…")
        background({ ctx.service.refreshOnlineChapters(current, source) }) { result ->
            result.onSuccess { list ->
                reloadChapters()
                ctx.setStatus("Capítulos atualizados (${list.size} no total)")
            }.onFailure {
                ctx.setStatus("Erro ao atualizar: ${it.message}")
            }
        }
    }

    private fun updateFavoriteButtonState(isFav: Boolean) {
        btnFavorite.icon = ModernIcons.heart(15, filled = isFav) {
            if (isFav) Color(0xF4, 0x3F, 0x5E) else Theme.TEXT_PRIMARY
        }
        if (isFav) {
            btnFavorite.text = "Na Biblioteca"
            btnFavorite.variant = ModernButton.Variant.FAVORITE
        } else {
            btnFavorite.text = "Adicionar à Biblioteca"
            btnFavorite.variant = ModernButton.Variant.SURFACE
        }
        btnFavorite.revalidate()
        btnFavorite.repaint()
    }

    private fun toggleFavorite() {
        val current = manga ?: return
        background({ ctx.service.toggleFavorite(current.id) }) { result ->
            result.onSuccess { isFav ->
                manga = current.copy(favorite = isFav)
                updateFavoriteButtonState(isFav)
                val msg = if (isFav) "'${current.title}' adicionado à biblioteca" else "'${current.title}' removido da biblioteca"
                ctx.setStatus(msg)
            }
        }
    }

    private fun removeCurrent() {
        val current = manga ?: return
        val ok = JOptionPane.showConfirmDialog(
            this,
            "Remover '${current.title}' da biblioteca? (os arquivos baixados não são apagados)",
            "Remover",
            JOptionPane.YES_NO_OPTION,
        )
        if (ok != JOptionPane.YES_OPTION) return
        background({ ctx.service.removeFromLibrary(current.id) }) {
            ctx.covers.remove(current.id)
            ctx.navigator.showLibrary()
        }
    }

    /**
     * Editorial Index Table of Contents Row (grounded in frontend-design principles):
     * - No generic rounded SaaS card per row; uses crisp hairline rules and tabular monospace index numbers.
     * - Vermilion bookmark bar on the left when unread or in-progress.
     */
    internal class ChapterRowCard : JPanel() {
        var chapterIndex: Int = 1
        var chapterTitle: String = ""
        var isRead: Boolean = false
        var isDownloaded: Boolean = false
        var isDownloading: Boolean = false
        var isOpening: Boolean = false
        var lastPage: Int = 0
        var selectedState: Boolean = false
        var hoverProgress: Float = 0f

        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

            val x = 8
            val w = width - 16
            val h = height
            val arc = Theme.CONTROL_RADIUS * 2

            if (selectedState) {
                g2.color = Theme.ACCENT_SOFT
                g2.fillRoundRect(x, 2, w, h - 4, arc, arc)
                g2.color = Theme.PRIMARY
                g2.stroke = java.awt.BasicStroke(1f)
                g2.drawRoundRect(x, 2, w - 1, h - 5, arc, arc)
            } else if (hoverProgress > 0.01f) {
                val hc = Theme.BG_CARD_HOVER
                g2.color = Color(hc.red, hc.green, hc.blue, (220 * easeOutCubic(hoverProgress)).toInt().coerceIn(0, 255))
                g2.fillRoundRect(x, 2, w, h - 4, arc, arc)
            }

            // Hairline editorial bottom rule
            g2.color = Theme.BORDER_SUBTLE
            g2.drawLine(x + 8, h - 1, x + w - 8, h - 1)

            // Left Signal accent bar for unread or partially read chapters
            if (!isRead) {
                g2.color = Theme.SIGNAL
                g2.fillRoundRect(x + 4, 12, 3, h - 24, 2, 2)
            }

            // Right side status stamp (drawn first to reserve space and prevent title overlap)
            var rightX = x + w - 14
            val pillText = when {
                isOpening -> "Abrindo…"
                isDownloaded -> "Baixado"
                isDownloading -> "Baixando…"
                isRead -> "Lido"
                else -> "Ler"
            }
            val pillBg = when {
                isOpening -> Theme.ACCENT_SOFT
                isDownloaded -> Color(Theme.SUCCESS.red, Theme.SUCCESS.green, Theme.SUCCESS.blue, 34)
                isDownloading -> Color(Theme.WARNING.red, Theme.WARNING.green, Theme.WARNING.blue, 34)
                isRead -> Theme.BG_SURFACE
                else -> Theme.ACCENT_SOFT
            }
            val pillFg = when {
                isOpening -> if (Theme.isDark) Theme.ACCENT else Theme.PRIMARY
                isDownloaded -> if (Theme.isDark) Theme.SUCCESS else Color(0x16, 0x65, 0x34)
                isDownloading -> if (Theme.isDark) Theme.WARNING else Color(0x9A, 0x67, 0x00)
                isRead -> if (Theme.isDark) Theme.SUCCESS else Color(0x16, 0x65, 0x34)
                else -> if (Theme.isDark) Theme.ACCENT else Theme.PRIMARY
            }
            val pillIcon = when {
                isOpening || isDownloading -> ModernIcons.sync(12) { pillFg }
                isDownloaded || isRead -> ModernIcons.check(12) { pillFg }
                else -> ModernIcons.play(11) { pillFg }
            }
            g2.font = Theme.FONT_UI.deriveFont(Font.BOLD, 11f)
            val pFm = g2.fontMetrics
            val iconGap = pillIcon.iconWidth + 6
            val pw = iconGap + pFm.stringWidth(pillText) + 16
            rightX -= pw
            g2.color = pillBg
            g2.fillRoundRect(rightX, 14, pw, h - 28, Theme.BADGE_RADIUS * 2, Theme.BADGE_RADIUS * 2)
            pillIcon.paintIcon(this, g2, rightX + 8, (h - pillIcon.iconHeight) / 2)
            g2.color = pillFg
            g2.drawString(pillText, rightX + 8 + iconGap, ((h - pFm.height) / 2) + pFm.ascent)

            if (isRead || lastPage > 0) {
                val progText = if (isRead) "Concluído" else "Pág. ${lastPage + 1}"
                g2.font = Theme.FONT_MONO.deriveFont(Font.PLAIN, 11.5f)
                val prFm = g2.fontMetrics
                rightX -= prFm.stringWidth(progText) + 16
                g2.color = if (!isRead && lastPage > 0) Theme.SIGNAL else if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
                g2.drawString(progText, rightX, ((h - prFm.height) / 2) + prFm.ascent)
            }

            // Tabular Monospace Index Number
            g2.font = Theme.FONT_MONO.deriveFont(Font.BOLD, 12f)
            g2.color = if (isRead) (if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY) else (if (Theme.isDark) Theme.ACCENT else Theme.PRIMARY)
            val numStr = "%03d".format(chapterIndex)
            g2.drawString(numStr, x + 18, (h / 2) + 5)

            // Chapter Title (preserved clearly as secondary text when read, CJK-safe)
            g2.font = Theme.FONT_UI.deriveFont(if (isRead) Font.PLAIN else Font.BOLD, 13.5f)
            g2.color = if (isRead) Theme.TEXT_SECONDARY else Theme.TEXT_PRIMARY
            val titleX = x + 58
            val maxTitleW = (rightX - titleX - 16).coerceAtLeast(60)
            Theme.drawStringWithFallback(g2, clipUiText(g2, chapterTitle, maxTitleW), titleX, (h / 2) + 5)

            g2.dispose()
        }
    }
}
