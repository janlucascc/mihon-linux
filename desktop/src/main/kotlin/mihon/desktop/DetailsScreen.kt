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
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

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
    private val sortOrderBtn = JButton("⇅ Ordem").apply {
        addActionListener {
            reverseOrder = !reverseOrder
            applyChapterListFilter()
        }
    }
    private val catPicker = JComboBox(
        LibraryCategory.entries.filter { it.first != LibraryCategory.ALL }.map { it.second }.toTypedArray(),
    )
    private val btnContinue = JButton("▶ Continuar Leitura").apply {
        background = Theme.ACCENT
        foreground = Theme.TEXT_PRIMARY
        font = font.deriveFont(Font.BOLD, 13f)
        addActionListener { continueReading() }
    }
    private val progressBar = JProgressBar(0, 100).apply {
        isStringPainted = true
        isVisible = false
        preferredSize = Dimension(100, 22)
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
                manga = current.copy(status = chosen)
                ctx.setStatus("Categoria alterada para '${LibraryCategory.labelOf(chosen)}'")
            }
        }

        filterCombo.addActionListener {
            filterMode = filterCombo.selectedIndex.coerceAtLeast(0)
            applyChapterListFilter()
        }

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 10, 10)).apply {
            background = Theme.BG_APP
            border = BorderFactory.createEmptyBorder(4, 14, 4, 14)
            add(JButton("← Biblioteca").apply { addActionListener { ctx.navigator.showLibrary() } })
            add(btnContinue)
            add(JButton("⬇ Baixar Tudo").apply { addActionListener { downloadSelected() } })
            add(JButton("✔ Marcar Lido").apply { addActionListener { markSelected(true) } })
            add(JButton("↻ Sincronizar").apply { addActionListener { refreshRemoteChapters() } })
            add(JLabel("Categoria:"))
            add(catPicker)
            add(JButton("Remover").apply { addActionListener { removeCurrent() } })
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

        val bottomBar = JPanel(BorderLayout()).apply {
            background = Theme.BG_APP
            add(progressBar, BorderLayout.NORTH)
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
            verticalScrollBar.unitIncrement = 24
        }
        add(top, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
    }

    private fun showChapterMenu(x: Int, y: Int) {
        val current = manga ?: return
        val selected = chapterList.selectedValuesList
        if (selected.isEmpty()) return
        val menu = JPopupMenu()
        menu.add("▶ Ler agora").addActionListener { openChapter(current, selected.first()) }
        if (!current.isLocal) {
            menu.add("⬇ Baixar selecionado(s) (.cbz)").addActionListener { downloadSelected() }
        }
        menu.addSeparator()
        menu.add("✔ Marcar como lido").addActionListener { markSelected(true) }
        menu.add("Marcar como não lido").addActionListener { markSelected(false) }
        menu.show(chapterList, x, y)
    }

    fun show(manga: Manga) {
        this.manga = manga
        updatingCategory = true
        val catIdx = LibraryCategory.entries.filter { it.first != LibraryCategory.ALL }
            .indexOfFirst { it.first == manga.status }.coerceAtLeast(0)
        catPicker.selectedIndex = catIdx
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

        val metaItems = buildList {
            add("<b>$statusBadge</b>")
            add(escapeHtml(sourceName))
            add("${chapters.size} cap.")
            if (unreadCount > 0) add("$unreadCount não lido(s)")
            if (downloadedCount > 0) add("<span style='color:#2e9e6b'>⬇ $downloadedCount salvos offline</span>")
            manga.genre?.take(4)?.forEach { add(escapeHtml(it)) }
        }.joinToString(" &nbsp;│&nbsp; ")

        val descHtml = manga.description?.takeIf { it.isNotBlank() }?.let {
            "<p style='margin-top:12px;color:#c8c4bc;line-height:1.5;font-size:12px'>${escapeHtml(it.take(500))}${if (it.length > 500) "…" else ""}</p>"
        }.orEmpty()
        header.add(
            JLabel(
                "<html><div style='width:720px'>" +
                    "<h1 style='margin:0 0 6px 0;color:#f2efe9;font-family:Serif;font-size:22px'>${escapeHtml(manga.title)}</h1>" +
                    (manga.author?.let { "<p style='margin:2px 0 8px 0;color:#8e8b82;font-size:12px'>Por <b style='color:#f2efe9'>${escapeHtml(it)}</b>" + (manga.artist?.takeIf { a -> a != it }?.let { a -> " &nbsp;•&nbsp; Arte de <b style='color:#f2efe9'>${escapeHtml(a)}</b>" } ?: "") + "</p>" } ?: "") +
                    "<p style='margin:6px 0;color:#8e8b82;font-size:11.5px'>$metaItems</p>" +
                    descHtml +
                    "</div></html>",
            ),
            BorderLayout.CENTER,
        )
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
                    "▶ Continuar: ${resume.name.take(22)}$pageSuffix"
                } else {
                    "▶ Ler do início"
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
        if (ctx.service.isChapterDownloaded(current, chapter)) {
            openReaderFile(current, chapter, ctx.service.chapterCbzFile(current, chapter))
            return
        }
        val source = ctx.sourceFor(current.source)
        if (source == null) {
            JOptionPane.showMessageDialog(this, "Fonte não encontrada para baixar o capítulo.", "Erro", JOptionPane.ERROR_MESSAGE)
            return
        }
        progressBar.value = 0
        progressBar.string = "Iniciando download de '${chapter.name}'…"
        progressBar.isVisible = true
        background({
            ctx.service.downloadChapter(current, chapter, source) { page, total ->
                SwingUtilities.invokeLater {
                    progressBar.maximum = total.coerceAtLeast(1)
                    progressBar.value = page
                    progressBar.string = "Baixando '${chapter.name}' — página $page de $total"
                    ctx.setStatus("Baixando '${chapter.name}': página $page/$total…")
                }
            }
        }) { result ->
            progressBar.isVisible = false
            result.onSuccess { cbz ->
                ctx.setStatus("Capítulo '${chapter.name}' pronto")
                reloadChapters()
                openReaderFile(current, chapter, cbz, canRetryDownload = false)
            }.onFailure {
                ctx.setStatus("Erro ao baixar capítulo: ${it.message}")
                JOptionPane.showMessageDialog(this, "Falha ao baixar capítulo:\n${it.message}", "Erro", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    private fun openReaderFile(current: Manga, chapter: Chapter, file: java.io.File, canRetryDownload: Boolean = true) {
        try {
            val started = System.currentTimeMillis()
            val idx = chapters.indexOfFirst { it.id == chapter.id }
            val prevChap = if (idx > 0) chapters[idx - 1] else null
            val nextChap = if (idx >= 0 && idx + 1 < chapters.size) chapters[idx + 1] else null

            // Pre-download upcoming 2 chapters silently in background so switching chapters is instant
            if (!current.isLocal && idx >= 0) {
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
