package mihon.desktop

import mihon.source.ExtensionSource
import mihon.source.MangaResult
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
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
import javax.swing.ImageIcon
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Visual poster grid for online manga discovery across all active sources (Manga Livre, MangaDex, JSONs).
 * Single-click on any cover opens its chapters and details immediately.
 */
class SearchScreen(
    private val ctx: AppContext,
    private val onBackToSources: (() -> Unit)? = null,
) : JPanel(BorderLayout()) {

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
            if (onBackToSources != null) {
                add(
                    JButton("← Fontes").apply {
                        font = font.deriveFont(Font.BOLD, 12.5f)
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        addActionListener { onBackToSources() }
                    },
                )
            }
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

    fun openSource(sourceName: String) {
        val idx = (1 until sourceFilter.itemCount).firstOrNull { sourceFilter.getItemAt(it) == sourceName } ?: 0
        sourceFilter.selectedIndex = idx
        field.text = ""
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
sealed interface HistoryListItem {
    data class DateHeader(val title: String) : HistoryListItem
    data class Entry(val entry: HistoryEntry) : HistoryListItem
}

/** Shows recent reading history with sticky date headers, covers, and quick actions (favorite & remove). */
class HistoryScreen(private val ctx: AppContext) : JPanel(BorderLayout()) {

    private val model = DefaultListModel<HistoryListItem>()
    private val list = JList(model)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    private val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault())
    private val itemRenderer = HistoryRowCard()
    private val headerRenderer = HistoryDateHeaderCard()

    init {
        background = Theme.BG_APP
        list.background = Theme.BG_APP
        list.border = BorderFactory.createEmptyBorder(12, 20, 18, 20)
        list.cellRenderer = ListCellRenderer { _, item, _, selected, _ ->
            when (item) {
                is HistoryListItem.DateHeader -> headerRenderer.apply { text = item.title }
                is HistoryListItem.Entry -> {
                    val entry = item.entry
                    val timeStr = entry.history.readAt?.let {
                        timeFmt.format(java.time.Instant.ofEpochMilli(it.toEpochMilliseconds()))
                    }.orEmpty()
                    itemRenderer.apply {
                        mangaTitle = entry.manga.title
                        chapterName = entry.chapter.name
                        chapterTime = timeStr
                        isFavorite = entry.manga.favorite
                        coverIcon = ctx.covers.get(entry.manga.id, entry.manga.title)
                        selectedState = selected
                    }
                }
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = list.locationToIndex(e.point)
                if (idx < 0 || list.getCellBounds(idx, idx)?.contains(e.point) != true) return
                val item = model[idx] as? HistoryListItem.Entry ?: return

                val cellBounds = list.getCellBounds(idx, idx)
                val relX = e.x - cellBounds.x
                val cardW = cellBounds.width - 16

                // Right action buttons click detection:
                // Delete button: (cardW - 40 .. cardW - 8)
                // Favorite button: (cardW - 80 .. cardW - 48)
                if (relX in (cardW - 40)..(cardW - 4)) {
                    // Quick Delete individual history entry
                    background({ ctx.service.deleteHistoryEntry(item.entry.chapter.id) }) {
                        refresh()
                        ctx.setStatus("Registro removido do histórico")
                    }
                    return
                } else if (relX in (cardW - 80)..(cardW - 44)) {
                    // Quick Toggle Favorite
                    background({ ctx.service.toggleFavorite(item.entry.manga.id) }) { res ->
                        refresh()
                        val state = if (res.getOrDefault(false)) "adicionado aos favoritos" else "removido dos favoritos"
                        ctx.setStatus("'${item.entry.manga.title}' $state")
                    }
                    return
                }

                if (SwingUtilities.isLeftMouseButton(e)) {
                    ctx.navigator.showDetails(item.entry.manga)
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
        add(JScrollPane(list).apply { border = null; verticalScrollBar.unitIncrement = 24 }, BorderLayout.CENTER)
    }

    fun refresh() {
        background({ ctx.service.recentHistory() }) { result ->
            model.clear()
            val entries = result.getOrDefault(emptyList())
            val todayStr = dateFmt.format(java.time.Instant.now())
            val yesterdayStr = dateFmt.format(java.time.Instant.now().minus(java.time.Duration.ofDays(1)))

            var lastGroup = ""
            for (entry in entries) {
                val itemDateStr = entry.history.readAt?.let {
                    dateFmt.format(java.time.Instant.ofEpochMilli(it.toEpochMilliseconds()))
                } ?: "Anterior"

                val groupHeader = when (itemDateStr) {
                    todayStr -> "Hoje"
                    yesterdayStr -> "Ontem"
                    else -> itemDateStr
                }

                if (groupHeader != lastGroup) {
                    model.addElement(HistoryListItem.DateHeader(groupHeader))
                    lastGroup = groupHeader
                }
                model.addElement(HistoryListItem.Entry(entry))
                if (!ctx.covers.has(entry.manga.id)) {
                    ctx.covers.load(entry.manga, firstChapterPath = { null }, onLoaded = { list.repaint() })
                }
            }
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

    private class HistoryDateHeaderCard : JPanel() {
        var text: String = ""
        init {
            isOpaque = false
            preferredSize = java.awt.Dimension(0, 36)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
            g2.font = Theme.FONT_MONO.deriveFont(Font.BOLD, 12.5f)
            g2.color = Theme.TEXT_MUTED
            g2.drawString(text, 14, 24)
            g2.dispose()
        }
    }

    private class HistoryRowCard : JPanel() {
        var mangaTitle = ""
        var chapterName = ""
        var chapterTime = ""
        var isFavorite = false
        var coverIcon: ImageIcon? = null
        var selectedState = false

        init {
            isOpaque = false
            preferredSize = java.awt.Dimension(0, 68)
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
                g2.fillRoundRect(x, 2, w, h - 4, 8, 8)
            }
            g2.color = Theme.BORDER_SUBTLE
            g2.drawLine(x + 6, h - 1, x + w - 6, h - 1)

            // Small vertical cover thumbnail on the left (Mihon Android style)
            val thumbW = 38
            val thumbH = 54
            val thumbY = (h - thumbH) / 2
            val coverImg = coverIcon?.image
            if (coverImg != null) {
                g2.clip = java.awt.geom.RoundRectangle2D.Float((x + 8).toFloat(), thumbY.toFloat(), thumbW.toFloat(), thumbH.toFloat(), 6f, 6f)
                g2.drawImage(coverImg, x + 8, thumbY, thumbW, thumbH, null)
                g2.clip = null
            } else {
                g2.color = Theme.BG_SURFACE
                g2.fillRoundRect(x + 8, thumbY, thumbW, thumbH, 6, 6)
            }
            g2.color = Color(255, 255, 255, 24)
            g2.drawRoundRect(x + 8, thumbY, thumbW - 1, thumbH - 1, 6, 6)

            // Manga Title
            val textX = x + thumbW + 20
            g2.font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 14.5f)
            g2.color = Theme.TEXT_PRIMARY
            g2.drawString(mangaTitle, textX, 28)

            // Chapter name + timestamp
            g2.font = font.deriveFont(Font.PLAIN, 12f)
            g2.color = Theme.TEXT_SECONDARY
            val sub = if (chapterTime.isNotBlank()) "$chapterName  —  $chapterTime" else chapterName
            g2.drawString(sub, textX, 48)

            // Action 1: Favorite icon (Heart: solid if favorited, outline if not)
            val favX = x + w - 74
            val favY = (h - 24) / 2
            g2.font = font.deriveFont(Font.PLAIN, 16f)
            g2.color = if (isFavorite) Theme.ACCENT else Theme.TEXT_MUTED
            g2.drawString(if (isFavorite) "♥" else "♡", favX + 6, favY + 18)

            // Action 2: Delete icon (Trash bin)
            val delX = x + w - 36
            val delY = (h - 24) / 2
            g2.color = Theme.TEXT_MUTED
            g2.font = font.deriveFont(Font.PLAIN, 15f)
            g2.drawString("🗑", delX + 6, delY + 18)

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

/**
 * Material 3 Sources List Screen matching Mihon Android's Extensoes.jpeg:
 * - Search bar ("Pesquisar fontes...")
 * - Section headers ("Última utilizada", "Outras", "Português (Brasil)", "Inglês / Global")
 * - Source rows with leading icon, title, language subtitle, pill button "Recentes", and pin (📌) toggle.
 */
class SourcesListScreen(
    private val ctx: AppContext,
    private val onSelectSource: (ExtensionSource) -> Unit,
    private val onSelectLocalSource: () -> Unit,
) : JPanel(BorderLayout()) {

    private val pinnedSources = mutableSetOf<String>()
    private val searchField = JTextField(24).apply {
        putClientProperty("JTextField.placeholderText", "Pesquisar fontes...")
    }
    private val container = JPanel().apply {
        background = Theme.BG_APP
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(12, 24, 24, 24)
    }

    init {
        background = Theme.BG_APP

        val searchBar = JPanel(FlowLayout(FlowLayout.CENTER, 0, 10)).apply {
            background = Theme.BG_APP
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SUBTLE)
            searchField.preferredSize = Dimension(420, 36)
            add(searchField)
        }
        add(searchBar, BorderLayout.NORTH)

        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuildList()
            override fun removeUpdate(e: DocumentEvent) = rebuildList()
            override fun changedUpdate(e: DocumentEvent) = rebuildList()
        })

        val scroll = JScrollPane(container).apply {
            border = null
            verticalScrollBar.unitIncrement = 24
        }
        add(scroll, BorderLayout.CENTER)

        rebuildList()
    }

    fun rebuildList() {
        container.removeAll()
        val query = searchField.text.trim().lowercase()

        val allSources = ctx.extensions.filter { query.isEmpty() || it.name.lowercase().contains(query) }
        val pinned = allSources.filter { pinnedSources.contains(it.name) }
        val unpinned = allSources.filter { !pinnedSources.contains(it.name) }

        if (pinned.isNotEmpty()) {
            addSectionHeader("Fixadas")
            pinned.forEach { ext -> addSourceRow(ext, isPinned = true) }
        }

        // 1. Última utilizada
        val lastUsed = unpinned.firstOrNull()
        if (lastUsed != null && query.isEmpty()) {
            addSectionHeader("Última utilizada")
            addSourceRow(lastUsed, isPinned = false)
        }

        // 2. Outras (Fonte local)
        if (query.isEmpty() || "fonte local".contains(query) || "local".contains(query)) {
            addSectionHeader("Outras")
            addLocalSourceRow()
        }

        // 3. Português (Brasil)
        val ptSources = unpinned.filter {
            (it != lastUsed || query.isNotEmpty()) &&
                (it.language.lowercase().contains("pt") || it.language.lowercase().contains("br"))
        }
        if (ptSources.isNotEmpty()) {
            addSectionHeader("Português (Brasil)")
            ptSources.forEach { ext -> addSourceRow(ext, isPinned = false) }
        }

        // 4. Outros idiomas / Global
        val otherSources = unpinned.filter {
            (it != lastUsed || query.isNotEmpty()) &&
                !it.language.lowercase().contains("pt") && !it.language.lowercase().contains("br")
        }
        if (otherSources.isNotEmpty()) {
            addSectionHeader("Inglês / Outras")
            otherSources.forEach { ext -> addSourceRow(ext, isPinned = false) }
        }

        container.revalidate()
        container.repaint()
    }

    private fun addSectionHeader(title: String) {
        val lbl = JLabel(title).apply {
            font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 13f)
            foreground = Theme.TEXT_MUTED
            border = BorderFactory.createEmptyBorder(16, 8, 8, 8)
            alignmentX = 0f
        }
        container.add(lbl)
    }

    private fun addLocalSourceRow() {
        val row = createRowPanel("📁", "Fonte local", "Local", onRecents = onSelectLocalSource, onRowClick = onSelectLocalSource)
        container.add(row)
        container.add(Box.createVerticalStrut(6))
    }

    private fun addSourceRow(ext: ExtensionSource, isPinned: Boolean) {
        val iconText = ext.name.take(2).uppercase()
        val row = createRowPanel(
            iconText = iconText,
            title = ext.name,
            subtitle = ext.language.uppercase(),
            isPinned = isPinned,
            onTogglePin = {
                if (pinnedSources.contains(ext.name)) pinnedSources.remove(ext.name) else pinnedSources.add(ext.name)
                rebuildList()
            },
            onRecents = { onSelectSource(ext) },
            onRowClick = { onSelectSource(ext) },
        )
        container.add(row)
        container.add(Box.createVerticalStrut(6))
    }

    private fun createRowPanel(
        iconText: String,
        title: String,
        subtitle: String,
        isPinned: Boolean = false,
        onTogglePin: (() -> Unit)? = null,
        onRecents: () -> Unit,
        onRowClick: () -> Unit,
    ): JPanel {
        return object : JPanel(BorderLayout(14, 0)) {
            private var hovered = false

            init {
                isOpaque = false
                alignmentX = 0f
                maximumSize = Dimension(Int.MAX_VALUE, 58)
                preferredSize = Dimension(400, 58)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = BorderFactory.createEmptyBorder(6, 12, 6, 12)

                // Leading Icon
                val iconBox = object : JPanel() {
                    init {
                        preferredSize = Dimension(42, 42)
                        minimumSize = Dimension(42, 42)
                        maximumSize = Dimension(42, 42)
                        isOpaque = false
                    }
                    override fun paintComponent(g: Graphics) {
                        super.paintComponent(g)
                        val g2 = g.create() as Graphics2D
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.color = Theme.BG_SURFACE
                        g2.fillRoundRect(0, 0, width, height, 10, 10)
                        g2.color = Theme.BORDER_SUBTLE
                        g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
                        g2.font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 14f)
                        g2.color = Theme.ACCENT
                        val fm = g2.fontMetrics
                        val tw = fm.stringWidth(iconText)
                        val th = fm.ascent
                        g2.drawString(iconText, (width - tw) / 2, (height + th) / 2 - 2)
                        g2.dispose()
                    }
                }
                add(iconBox, BorderLayout.WEST)

                // Center Column
                val center = JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(Box.createVerticalGlue())
                    add(
                        JLabel(title).apply {
                            font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 14.5f)
                            foreground = Theme.TEXT_PRIMARY
                        },
                    )
                    add(Box.createVerticalStrut(2))
                    add(
                        JLabel(subtitle).apply {
                            font = font.deriveFont(Font.PLAIN, 11.5f)
                            foreground = Theme.TEXT_MUTED
                        },
                    )
                    add(Box.createVerticalGlue())
                }
                add(center, BorderLayout.CENTER)

                // Trailing actions: "Recentes" pill button + Pin button
                val rightActions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply {
                    isOpaque = false

                    val btnRecents = JButton("Recentes").apply {
                        font = font.deriveFont(Font.BOLD, 11.5f)
                        foreground = Theme.TEXT_PRIMARY
                        background = Theme.BG_SURFACE
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        isFocusPainted = false
                        border = BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                            BorderFactory.createEmptyBorder(4, 12, 4, 12),
                        )
                        addActionListener { onRecents() }
                    }
                    add(btnRecents)

                    val btnPin = JButton(if (isPinned) "📌" else "📍").apply {
                        font = font.deriveFont(Font.PLAIN, 15f)
                        foreground = if (isPinned) Theme.ACCENT else Theme.TEXT_MUTED
                        isOpaque = false
                        isContentAreaFilled = false
                        isBorderPainted = false
                        isFocusPainted = false
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        toolTipText = if (isPinned) "Desafixar fonte" else "Fixar fonte no topo"
                        addActionListener { onTogglePin?.invoke() }
                    }
                    add(btnPin)
                }
                add(rightActions, BorderLayout.EAST)

                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) {
                        hovered = true
                        repaint()
                    }
                    override fun mouseExited(e: MouseEvent) {
                        hovered = false
                        repaint()
                    }
                    override fun mouseClicked(e: MouseEvent) {
                        onRowClick()
                    }
                })
            }

            override fun paintComponent(g: Graphics) {
                if (hovered) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = Theme.BG_CARD_HOVER
                    g2.fillRoundRect(0, 0, width, height, 10, 10)
                    g2.dispose()
                }
                super.paintComponent(g)
            }
        }
    }
}
