package mihon.desktop

import mihon.source.ExtensionSource
import mihon.source.MangaResult
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities

/**
 * Visual poster grid for online manga discovery across all active sources (Manga Livre, MangaDex, JSONs).
 * Single-click on any cover opens its chapters and details immediately.
 */
class SearchScreen(private val ctx: AppContext) : JPanel(BorderLayout()) {

    data class Hit(val source: ExtensionSource, val item: MangaResult)

    private val model = DefaultListModel<Hit>()
    private val grid = JList(model)
    private val cardPrototype = MangaPosterCard()
    private var hoveredIndex = -1

    private val field = JTextField(26).apply {
        putClientProperty("JTextField.placeholderText", "Pesquisar mangá (ex: One Piece, Naruto, Solo Leveling)…")
    }
    private val sourceFilter = JComboBox(
        (listOf("Todas as fontes") + ctx.extensions.map { it.name }).toTypedArray(),
    )

    init {
        background = Theme.BG_APP
        grid.background = Theme.BG_APP
        grid.layoutOrientation = JList.HORIZONTAL_WRAP
        grid.visibleRowCount = -1
        grid.fixedCellWidth = CARD_W
        grid.fixedCellHeight = CARD_H
        grid.border = BorderFactory.createEmptyBorder(16, 18, 16, 18)
        grid.cellRenderer = ListCellRenderer { _, hit, index, selected, _ ->
            renderHit(hit, index, selected)
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

            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val idx = grid.locationToIndex(e.point)
                if (idx >= 0 && grid.getCellBounds(idx, idx)?.contains(e.point) == true) {
                    grid.selectedIndex = idx
                    addSelected()
                }
            }
        })

        val btnPopular = JButton("🔥 Em Alta").apply {
            addActionListener { loadPopular() }
        }
        val btnSearch = JButton("Buscar").apply {
            background = Theme.ACCENT
            foreground = Theme.TEXT_PRIMARY
            font = font.deriveFont(Font.BOLD, 12.5f)
            addActionListener { search() }
        }
        field.addActionListener { search() }
        sourceFilter.addActionListener {
            if (field.text.isBlank()) loadPopular() else search()
        }

        val top = JPanel(FlowLayout(FlowLayout.LEFT, 10, 10)).apply {
            background = Theme.BG_SURFACE
            border = BorderFactory.createEmptyBorder(4, 12, 4, 12)
            add(JLabel("Fonte:"))
            add(sourceFilter)
            add(btnPopular)
            add(field)
            add(btnSearch)
        }
        val scroll = JScrollPane(grid).apply {
            border = null
            verticalScrollBar.unitIncrement = 28
        }
        add(top, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        loadPopular()
    }

    fun loadPopular() {
        val chosenIdx = sourceFilter.selectedIndex
        val sourcesToQuery = if (chosenIdx <= 0) ctx.extensions else listOfNotNull(ctx.extensions.getOrNull(chosenIdx - 1))
        if (sourcesToQuery.isEmpty()) return
        ctx.setStatus("Carregando mangás em alta de ${sourcesToQuery.joinToString { it.name }}…")
        background({
            sourcesToQuery.flatMap { ext ->
                runCatching { ext.getPopularManga().map { Hit(ext, it) } }
                    .getOrElse { emptyList() }
            }
        }) { result ->
            val found = result.getOrDefault(emptyList())
            model.clear()
            found.forEach { hit ->
                model.addElement(hit)
                ctx.covers.loadUrl(hit.item.coverUrl) { grid.repaint() }
            }
            ctx.setStatus("Exibindo ${found.size} mangá(s) em alta — clique em qualquer capa para abrir")
        }
    }

    private fun search() {
        val query = field.text.trim()
        if (query.isEmpty()) return
        val chosenIdx = sourceFilter.selectedIndex
        val sourcesToQuery = if (chosenIdx <= 0) ctx.extensions else listOfNotNull(ctx.extensions.getOrNull(chosenIdx - 1))
        if (sourcesToQuery.isEmpty()) {
            ctx.setStatus("Nenhuma fonte disponível")
            return
        }
        ctx.setStatus("Buscando '$query' em ${sourcesToQuery.joinToString { it.name }}…")
        background({
            sourcesToQuery.flatMap { ext ->
                runCatching { ext.searchManga(query).map { Hit(ext, it) } }
                    .getOrElse { emptyList() }
            }
        }) { result ->
            val found = result.getOrDefault(emptyList())
            model.clear()
            found.forEach { hit ->
                model.addElement(hit)
                ctx.covers.loadUrl(hit.item.coverUrl) { grid.repaint() }
            }
            ctx.setStatus("Busca por '$query': ${found.size} resultado(s) — clique na capa para abrir")
        }
    }

    private fun renderHit(hit: Hit, index: Int, selected: Boolean): Component {
        val shortSource = hit.source.name.substringBefore(" (")
        return cardPrototype.apply {
            coverIcon = ctx.covers.getByUrl(hit.item.coverUrl, hit.item.title)
            titleText = hit.item.title
            subtitleText = hit.item.author ?: hit.source.name
            topLeftBadge = shortSource
            topRightBadge = hit.item.latestChapter
            topRightColor = Theme.SUCCESS
            selectedState = selected
            hoveredState = index == hoveredIndex
        }
    }

    private fun addSelected() {
        val hit = grid.selectedValue ?: return
        ctx.setStatus("Abrindo '${hit.item.title}' (${hit.source.name})…")
        background({ ctx.service.addOnlineManga(hit.source, hit.item) }) { result ->
            result.onSuccess { manga ->
                ctx.setStatus("'${manga.title}' carregado")
                ctx.covers.load(manga, firstChapterPath = { null }, onLoaded = {})
                ctx.navigator.showDetails(manga)
            }.onFailure {
                ctx.setStatus("Erro ao carregar mangá: ${it.message}")
                JOptionPane.showMessageDialog(
                    this,
                    "Não foi possível obter os detalhes de '${hit.item.title}':\n${it.message}",
                    "Erro na Fonte",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }
}

/** Shows recent reading history with custom-painted row cards and 1-click resume. */
class HistoryScreen(private val ctx: AppContext) : JPanel(BorderLayout()) {

    private val model = DefaultListModel<HistoryEntry>()
    private val list = JList(model)
    private val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
    private val renderer = HistoryRowCard()

    init {
        background = Theme.BG_APP
        list.background = Theme.BG_APP
        list.fixedCellHeight = 64
        list.border = BorderFactory.createEmptyBorder(12, 20, 18, 20)
        list.cellRenderer = ListCellRenderer { _, entry, _, selected, _ ->
            renderer.apply {
                mangaTitle = entry.manga.title
                chapterName = entry.chapter.name
                progressLabel = if (entry.chapter.lastPageRead > 0) "Página ${entry.chapter.lastPageRead + 1}" else "Início"
                dateLabel = entry.history.readAt?.let { fmt.format(java.time.Instant.ofEpochMilli(it.toEpochMilliseconds())) } ?: ""
                selectedState = selected
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = list.locationToIndex(e.point)
                if (idx >= 0 && list.getCellBounds(idx, idx)?.contains(e.point) == true) {
                    ctx.navigator.showDetails(model[idx].manga)
                }
            }
        })

        val top = JPanel(FlowLayout(FlowLayout.LEFT, 10, 10)).apply {
            background = Theme.BG_SURFACE
            border = BorderFactory.createEmptyBorder(4, 12, 4, 12)
            add(JButton("↻ Atualizar").apply { addActionListener { refresh() } })
            add(JButton("Limpar histórico").apply { addActionListener { clearAll() } })
        }
        add(top, BorderLayout.NORTH)
        add(JScrollPane(list).apply { border = null }, BorderLayout.CENTER)
    }

    fun refresh() {
        background({ ctx.service.recentHistory() }) { result ->
            model.clear()
            result.getOrDefault(emptyList()).forEach { model.addElement(it) }
        }
    }

    private fun clearAll() {
        val ok = JOptionPane.showConfirmDialog(this, "Limpar todo o histórico de leitura?", "Histórico", JOptionPane.YES_NO_OPTION)
        if (ok != JOptionPane.YES_OPTION) return
        background({ ctx.service.clearHistory() }) {
            refresh()
            ctx.setStatus("Histórico limpo")
        }
    }

    private class HistoryRowCard : JPanel() {
        var mangaTitle = ""
        var chapterName = ""
        var progressLabel = ""
        var dateLabel = ""
        var selectedState = false

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
            if (selectedState) {
                g2.color = Theme.BG_CARD_HOVER
                g2.fillRoundRect(x, 2, w, h - 4, 6, 6)
            }
            g2.color = Theme.BORDER_SUBTLE
            g2.drawLine(x + 6, h - 1, x + w - 6, h - 1)

            g2.color = Theme.ACCENT
            g2.fillRoundRect(x + 4, 14, 3, h - 28, 2, 2)

            g2.font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 15f)
            g2.color = Theme.TEXT_PRIMARY
            g2.drawString(mangaTitle, x + 18, 25)

            g2.font = font.deriveFont(Font.PLAIN, 12.5f)
            g2.color = Theme.TEXT_SECONDARY
            g2.drawString("$chapterName  —  $progressLabel", x + 18, 46)

            g2.font = Theme.FONT_MONO.deriveFont(Font.PLAIN, 11.5f)
            g2.color = Theme.TEXT_MUTED
            val fm = g2.fontMetrics
            g2.drawString(dateLabel, x + w - fm.stringWidth(dateLabel) - 16, (h / 2) + 5)
            g2.dispose()
        }
    }
}

/** Lists the loaded extensions as crisp editorial rows. */
class ExtensionsScreen(ctx: AppContext) : JPanel(BorderLayout()) {
    init {
        background = Theme.BG_APP
        val model = DefaultListModel<ExtensionSource>()
        ctx.extensions.forEach { model.addElement(it) }
        val list = JList(model).apply {
            background = Theme.BG_APP
            fixedCellHeight = 66
            border = BorderFactory.createEmptyBorder(14, 20, 18, 20)
            cellRenderer = ListCellRenderer { _, ext, _, selected, _ ->
                object : JPanel() {
                    init { isOpaque = false }
                    override fun paintComponent(g: Graphics) {
                        val g2 = g.create() as Graphics2D
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
                        val x = 8; val w = width - 16; val h = height
                        if (selected) {
                            g2.color = Theme.BG_CARD_HOVER
                            g2.fillRoundRect(x, 2, w, h - 4, 6, 6)
                        }
                        g2.color = Theme.BORDER_SUBTLE
                        g2.drawLine(x + 6, h - 1, x + w - 6, h - 1)

                        g2.font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 15f)
                        g2.color = Theme.TEXT_PRIMARY
                        g2.drawString(ext.name, x + 16, 26)
                        if (ext.isNsfw) {
                            val titleW = g2.fontMetrics.stringWidth(ext.name)
                            g2.font = Theme.FONT_MONO.deriveFont(Font.BOLD, 10.5f)
                            val nsfwText = "18+"
                            val nw = g2.fontMetrics.stringWidth(nsfwText) + 10
                            g2.color = Theme.ACCENT_SOFT
                            g2.fillRoundRect(x + 24 + titleW, 12, nw, 18, 6, 6)
                            g2.color = Theme.ACCENT_HOVER
                            g2.drawString(nsfwText, x + 29 + titleW, 25)
                        }

                        g2.font = Theme.FONT_MONO.deriveFont(Font.PLAIN, 11.5f)
                        g2.color = Theme.TEXT_MUTED
                        g2.drawString("${ext.baseUrl}  │  ${ext.language.uppercase()}", x + 16, 46)

                        val badge = "● Instalada"
                        g2.font = Theme.FONT_MONO.deriveFont(Font.BOLD, 11.5f)
                        g2.color = Theme.SUCCESS
                        g2.drawString(badge, x + w - g2.fontMetrics.stringWidth(badge) - 18, (h / 2) + 5)
                        g2.dispose()
                    }
                }
            }
        }
        add(JScrollPane(list).apply { border = null }, BorderLayout.CENTER)
        val footer = JLabel("  ${ctx.extensions.size} fonte(s) ativa(s)  •  Adicione arquivos .json em ~/.mihon/sources/ ou .jar em ~/.mihon/extensions/").apply {
            foreground = Theme.TEXT_MUTED
            border = BorderFactory.createEmptyBorder(10, 14, 10, 14)
        }
        add(footer, BorderLayout.SOUTH)
    }
}
