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

    private var manga: Manga? = null
    private var chapters: List<Chapter> = emptyList()
    private var resumeChapter: Chapter? = null
    private var updatingCategory = false
    private var hoveredRow = -1
    private var openingChapterId: Long? = null
    private var cancelStreamingTask: (() -> Unit)? = null
    private var filterMode = 0 // 0 = All, 1 = Unread, 2 = Downloaded
    private var reverseOrder = false

    private val header = JPanel(BorderLayout(22, 0)).apply {
        background = Theme.BG_SURFACE
        border = BorderFactory.createEmptyBorder(18, 24, 18, 24)
    }
    private val chapterHeaderLabel = JLabel("0 capítulos").apply {
        foreground = Theme.TEXT_PRIMARY
        font = font.deriveFont(Font.BOLD, 14f)
    }
    private val filterCombo = JComboBox(arrayOf("Todos os capítulos", "Apenas não lidos", "Apenas baixados"))
    private val sortOrderBtn = ModernButton(
        text = "Ordem",
        icon = ModernIcons.sort(14) { Theme.TEXT_PRIMARY },
        variant = ModernButton.Variant.SURFACE,
    ).apply {
        addActionListener {
            reverseOrder = !reverseOrder
            applyChapterListFilter()
        }
    }
    private val catPicker = JComboBox(
        LibraryCategory.entries.filter { it.first != LibraryCategory.ALL }.map { it.second }.toTypedArray(),
    )
    private val btnFavorite = ModernButton(
        text = "Adicionar",
        icon = ModernIcons.heart(15, filled = false) { Theme.TEXT_PRIMARY },
        variant = ModernButton.Variant.SURFACE,
    ).apply {
        addActionListener { toggleFavorite() }
    }
    private val btnContinue = ModernButton(
        text = "Continuar Leitura",
        icon = ModernIcons.play(13) { Color.WHITE },
        variant = ModernButton.Variant.PRIMARY,
    ).apply {
        addActionListener { continueReading() }
    }
    private val progressBar = JProgressBar(0, 100).apply {
        isStringPainted = true
        isVisible = false
        preferredSize = Dimension(100, 22)
    }
    private val btnCancelStreaming = ModernButton(
        text = "Cancelar",
        icon = ModernIcons.close(12) { Color(0xE0, 0x55, 0x65) },
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
                lastPage = ch.lastPageRead
                selectedState = selected
                hoveredState = index == hoveredRow
            }
        }
        chapterList.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val idx = chapterList.locationToIndex(e.point)
                val valid = if (idx >= 0 && chapterList.getCellBounds(idx, idx)?.contains(e.point) == true) idx else -1
                chapterList.cursor = if (valid >= 0) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                if (valid != hoveredRow) {
                    val prev = hoveredRow
                    hoveredRow = valid
                    if (prev >= 0) chapterList.getCellBounds(prev, prev)?.let { chapterList.repaint(it) }
                    if (valid >= 0) chapterList.getCellBounds(valid, valid)?.let { chapterList.repaint(it) }
                }
            }
        })
        chapterList.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                if (hoveredRow != -1) {
                    val prev = hoveredRow
                    hoveredRow = -1
                    chapterList.getCellBounds(prev, prev)?.let { chapterList.repaint(it) }
                }
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

        val btnOverflow = ModernButton(
            text = "",
            icon = ModernIcons.moreVert(18) { Theme.TEXT_PRIMARY },
            variant = ModernButton.Variant.SURFACE,
        ).apply {
            toolTipText = "Mais opções"
            addActionListener {
                val menu = JPopupMenu().apply {
                    val miMarkRead = JMenuItem("Marcar selecionados como lidos", ModernIcons.check(14) { Theme.TEXT_PRIMARY }).apply {
                        addActionListener { markSelected(true) }
                    }
                    val miMarkUnread = JMenuItem("Marcar selecionados como não lidos").apply {
                        addActionListener { markSelected(false) }
                    }
                    val miRemove = JMenuItem("Remover da biblioteca", ModernIcons.trash(14) { Color(0xE0, 0x55, 0x65) }).apply {
                        foreground = Color(0xE0, 0x55, 0x65)
                        addActionListener { removeCurrent() }
                    }
                    add(miMarkRead)
                    add(miMarkUnread)
                    addSeparator()
                    add(miRemove)
                }
                menu.show(this, 0, height)
            }
        }

        catPicker.apply {
            putClientProperty("JComboBox.buttonType", "roundRect")
            preferredSize = Dimension(140, 32)
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
                foreground = Theme.TEXT_MUTED
                font = font.deriveFont(Font.PLAIN, 12f)
            })
            add(catPicker)
            add(btnOverflow)
        }

        val actions = JPanel(BorderLayout(8, 0)).apply {
            background = Theme.BG_APP
            border = BorderFactory.createEmptyBorder(8, 16, 8, 16)
            add(leftActions, BorderLayout.WEST)
            add(rightActions, BorderLayout.EAST)
        }

        // Mihon ChapterHeader bar (chapter count + filter + sort toggle)
        val chapterHeaderBar = JPanel(BorderLayout()).apply {
            background = Theme.BG_APP
            border = BorderFactory.createEmptyBorder(8, 24, 2, 24)
            add(chapterHeaderLabel, BorderLayout.WEST)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                    isOpaque = false
                    add(filterCombo)
                    add(sortOrderBtn)
                },
                BorderLayout.EAST,
            )
        }

        val progressWrap = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 20, 4, 20)
            add(progressBar, BorderLayout.CENTER)
            add(btnCancelStreaming, BorderLayout.EAST)
        }

        val bottomBar = JPanel(BorderLayout()).apply {
            background = Theme.BG_APP
            add(progressWrap, BorderLayout.NORTH)
            add(chapterHeaderBar, BorderLayout.SOUTH)
        }

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

        ctx.addThemeListener {
            background = Theme.BG_APP
            top.background = Theme.BG_APP
            header.background = Theme.BG_SURFACE
            actions.background = Theme.BG_APP
            bottomBar.background = Theme.BG_APP
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

        updateContinueButtonStyle()
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

        GooeyContextMenu(gooeyItems).showAt(chapterList, x, y)
    }

    fun show(manga: Manga) {
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
        header.add(JLabel(ctx.covers.get(manga.id, manga.title)), BorderLayout.WEST)
        val sourceName = if (manga.isLocal) "Arquivo local" else (ctx.sourceFor(manga.source)?.name ?: "Fonte online")
        val statusBadge = LibraryCategory.labelOf(manga.status)
        val unreadCount = chapters.count { !it.read }
        val downloadedCount = chapters.count { ctx.service.isChapterDownloaded(manga, it) }

        val sourceLinkHtml = if (!manga.isLocal && ctx.sourceFor(manga.source) != null) {
            "<a href='source:${escapeHtml(sourceName)}' style='color:${Theme.ACCENT.toHex()};text-decoration:underline;'>${escapeHtml(sourceName)}</a>"
        } else {
            escapeHtml(sourceName)
        }

        val metaItems = buildList {
            add("<b>$statusBadge</b>")
            add(sourceLinkHtml)
            add("${chapters.size} cap.")
            if (unreadCount > 0) add("$unreadCount não lido(s)")
            if (downloadedCount > 0) add("<span style='color:#2e9e6b'>⬇ $downloadedCount salvos offline</span>")
            manga.genre?.take(4)?.forEach { add(escapeHtml(it)) }
        }.joinToString(" &nbsp;│&nbsp; ")

        val titleColor = Theme.TEXT_PRIMARY.toHex()
        val authorColor = Theme.TEXT_PRIMARY.toHex()
        val metaColor = Theme.TEXT_MUTED.toHex()
        val descColor = Theme.TEXT_SECONDARY.toHex()

        val descHtml = manga.description?.takeIf { it.isNotBlank() }?.let {
            "<p style='margin-top:12px;color:$descColor;line-height:1.5;font-size:12px'>${escapeHtml(it.take(500))}${if (it.length > 500) "…" else ""}</p>"
        }.orEmpty()
        val headerInfo = javax.swing.JEditorPane("text/html", "").apply {
            isEditable = false
            isOpaque = false
            putClientProperty(javax.swing.JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            text = "<html><div style='width:720px'>" +
                "<h1 style='margin:0 0 6px 0;color:$titleColor;font-family:SansSerif,Serif;font-size:22px'>${escapeHtml(manga.title)}</h1>" +
                (manga.author?.let { "<p style='margin:2px 0 8px 0;color:$metaColor;font-size:12px'>Por <b style='color:$authorColor'>${escapeHtml(it)}</b>" + (manga.artist?.takeIf { a -> a != it }?.let { a -> " &nbsp;•&nbsp; Arte de <b style='color:$authorColor'>${escapeHtml(a)}</b>" } ?: "") + "</p>" } ?: "") +
                "<p style='margin:6px 0;color:$metaColor;font-size:11.5px'>$metaItems</p>" +
                descHtml +
                "</div></html>"
            addHyperlinkListener { e ->
                if (e.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                    val desc = e.description
                    if (desc.startsWith("source:")) {
                        val srcName = desc.removePrefix("source:")
                        ctx.navigator.openSourceCatalog(srcName)
                    }
                }
            }
        }
        val heroActions = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(8, -10, 0, 0)
            add(btnContinue)
            add(btnFavorite)
        }
        val centerCol = JPanel(BorderLayout(0, 6)).apply {
            isOpaque = false
            add(headerInfo, BorderLayout.CENTER)
            add(heroActions, BorderLayout.SOUTH)
        }
        header.add(centerCol, BorderLayout.CENTER)
        header.revalidate()
        header.repaint()
    }

    private fun applyChapterListFilter() {
        val current = manga ?: return
        val filtered = chapters.filter { ch ->
            when (filterMode) {
                1 -> !ch.read
                2 -> ctx.service.isChapterDownloaded(current, ch)
                else -> true
            }
        }.let { if (reverseOrder) it.reversed() else it }

        listModel.clear()
        filtered.forEach { listModel.addElement(it) }
        val unread = chapters.count { !it.read }
        chapterHeaderLabel.text = "${filtered.size} de ${chapters.size} capítulos" +
            (if (unread > 0) "  —  $unread por ler" else "  —  Leitura em dia")
    }

    private fun reloadChapters() {
        val current = manga ?: return
        background({
            val list = ctx.service.chaptersOf(current.id)
            val resume = ctx.service.nextChapterToRead(current.id)
            list to resume
        }) { result ->
            result.onSuccess { (list, resume) ->
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
        if (openingChapterId != null) {
            ctx.setStatus("Já existe um capítulo sendo aberto. Aguarde ou cancele…")
            return
        }

        if (ctx.service.isChapterDownloaded(current, chapter)) {
            openReaderFile(current, chapter, ctx.service.chapterCbzFile(current, chapter))
            return
        }
        val source = ctx.sourceFor(current.source)
        if (source == null) {
            JOptionPane.showMessageDialog(this, "Fonte não encontrada para ler o capítulo.", "Erro", JOptionPane.ERROR_MESSAGE)
            return
        }

        openingChapterId = chapter.id
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
            val userAgent = if (source.name.contains("MangaDex", ignoreCase = true)) "MihonLinux/0.3 (Linux Desktop)" else "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            val cookie = if (source.baseUrl.contains("hentaidatia")) "tia_idade_confirmada=1" else null
            OnlineStreamArchiveReader(
                sourceName = "${current.title} - ${chapter.name}",
                imageUrls = imageUrls,
                client = ctx.service.httpClient,
                referer = referer,
                userAgent = userAgent,
                cookie = cookie,
            )
        }) { result ->
            progressBar.isIndeterminate = false
            progressBar.isVisible = false
            btnCancelStreaming.isVisible = false
            val wasOpening = openingChapterId == chapter.id
            openingChapterId = null
            cancelStreamingTask = null

            if (isCancelled || !wasOpening) {
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
    }

    private fun openReaderArchive(current: Manga, chapter: Chapter, reader: mihon.core.archive.ArchiveReader) {
        try {
            val started = System.currentTimeMillis()
            val idx = chapters.indexOfFirst { it.id == chapter.id }
            val prevChap = if (idx > 0) chapters[idx - 1] else null
            val nextChap = if (idx >= 0 && idx + 1 < chapters.size) chapters[idx + 1] else null

            ReaderWindow(
                title = "${current.title} - ${chapter.name}",
                source = reader,
                startPage = if (chapter.read) 0 else chapter.lastPageRead,
                onPrevChapter = prevChap?.let { { openChapter(current, it) } },
                onNextChapter = nextChap?.let { { openChapter(current, it) } },
            ) { page, count ->
                val elapsed = System.currentTimeMillis() - started
                background({ ctx.service.saveProgress(chapter, page, count, elapsed, incognitoMode = ctx.incognitoMode) }) { reloadChapters() }
            }
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Não foi possível abrir o leitor: ${e.message}", "Erro", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun openReaderFile(current: Manga, chapter: Chapter, file: java.io.File, canRetryDownload: Boolean = true) {
        try {
            val started = System.currentTimeMillis()
            val idx = chapters.indexOfFirst { it.id == chapter.id }
            val prevChap = if (idx > 0) chapters[idx - 1] else null
            val nextChap = if (idx >= 0 && idx + 1 < chapters.size) chapters[idx + 1] else null

            // Pre-download upcoming 2 chapters in background only if opted-in via settings
            val autoPrefetch = Preferences.userRoot().node("mihon-linux-reader").getBoolean("autoPrefetch", false)
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

            ReaderWindow(
                title = "${current.title} - ${chapter.name}",
                source = file,
                startPage = if (chapter.read) 0 else chapter.lastPageRead,
                onPrevChapter = prevChap?.let { { openChapter(current, it) } },
                onNextChapter = nextChap?.let { { openChapter(current, it) } },
            ) { page, count ->
                val elapsed = System.currentTimeMillis() - started
                background({ ctx.service.saveProgress(chapter, page, count, elapsed, incognitoMode = ctx.incognitoMode) }) { reloadChapters() }
            }
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
                reloadChapters()
            }.onFailure {
                ctx.setStatus("Falha no download: ${it.message}")
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
    private class ChapterRowCard : JPanel() {
        var chapterIndex: Int = 1
        var chapterTitle: String = ""
        var isRead: Boolean = false
        var isDownloaded: Boolean = false
        var isDownloading: Boolean = false
        var lastPage: Int = 0
        var selectedState: Boolean = false
        var hoveredState: Boolean = false

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

            if (selectedState || hoveredState) {
                g2.color = if (selectedState) Theme.BG_CARD_HOVER else Theme.BG_SURFACE
                g2.fillRoundRect(x, 2, w, h - 4, 6, 6)
            }

            // Hairline editorial bottom rule
            g2.color = Theme.BORDER_SUBTLE
            g2.drawLine(x + 8, h - 1, x + w - 8, h - 1)

            // Left vertical Hanko accent bar for unread or partially read chapters
            if (!isRead) {
                g2.color = if (lastPage > 0) Theme.ACCENT else Color(0xE0, 0x4F, 0x3E, 140)
                g2.fillRoundRect(x + 4, 12, 3, h - 24, 2, 2)
            }

            // Tabular Monospace Index Number
            g2.font = Theme.FONT_MONO.deriveFont(Font.BOLD, 12f)
            g2.color = if (isRead) Theme.TEXT_MUTED else Theme.ACCENT_HOVER
            val numStr = "%03d".format(chapterIndex)
            g2.drawString(numStr, x + 18, (h / 2) + 5)

            // Chapter Title
            g2.font = font.deriveFont(if (isRead) Font.PLAIN else Font.BOLD, 13.5f)
            g2.color = if (isRead) Theme.TEXT_MUTED else Theme.TEXT_PRIMARY
            g2.drawString(chapterTitle, x + 58, (h / 2) + 5)

            // Right side status stamp
            var rightX = x + w - 14
            val pillText = when {
                isDownloaded -> "✔ Salvo no disco"
                isDownloading -> "⏳ Baixando…"
                else -> "Ler capítulo"
            }
            val pillBg = when {
                isDownloaded -> Color(0x2E, 0x9E, 0x6B, 38)
                isDownloading -> Color(245, 158, 11, 40)
                else -> Color(0xE0, 0x4F, 0x3E, 32)
            }
            val pillFg = when {
                isDownloaded -> Theme.SUCCESS
                isDownloading -> Color(251, 191, 36)
                else -> Theme.ACCENT_HOVER
            }
            g2.font = Theme.FONT_MONO.deriveFont(Font.BOLD, 11f)
            val pFm = g2.fontMetrics
            val pw = pFm.stringWidth(pillText) + 16
            rightX -= pw
            g2.color = pillBg
            g2.fillRoundRect(rightX, 13, pw, h - 26, 6, 6)
            g2.color = pillFg
            g2.drawString(pillText, rightX + 8, ((h - pFm.height) / 2) + pFm.ascent)

            if (isRead || lastPage > 0) {
                val progText = if (isRead) "Lido" else "Parou na pág. ${lastPage + 1}"
                g2.font = Theme.FONT_MONO.deriveFont(Font.PLAIN, 11.5f)
                val prFm = g2.fontMetrics
                rightX -= prFm.stringWidth(progText) + 18
                g2.color = if (!isRead && lastPage > 0) Theme.TEXT_SECONDARY else Theme.TEXT_MUTED
                g2.drawString(progText, rightX, ((h - prFm.height) / 2) + prFm.ascent)
            }

            g2.dispose()
        }
    }
}
