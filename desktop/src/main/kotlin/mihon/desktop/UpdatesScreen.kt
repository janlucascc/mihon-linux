package mihon.desktop

import mihon.desktop.ui.components.MihonDropdown
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.awt.BorderLayout
import java.awt.Color
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.prefs.Preferences
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * Representa um item no feed de Atualizações (Mangá + Capítulo recente).
 */
data class UpdateFeedEntry(
    val manga: Manga,
    val chapter: Chapter,
)

/**
 * Agendador periódico de verificação de novos capítulos em segundo plano.
 * Respeita intervalos configuráveis pelo usuário e emite notificações nativas via [SystemTrayManager].
 */
object LibraryUpdateScheduler {
    private val prefs = Preferences.userRoot().node("mihon-linux")
    private const val KEY_INTERVAL_HOURS = "update_check_interval_hours"
    private const val KEY_LAST_CHECK_MS = "update_last_check_ms"

    /**
     * Opções de intervalo:
     * 0 = Desativado
     * 1 = Apenas ao iniciar
     * 3 = A cada 3 horas
     * 6 = A cada 6 horas (Padrão)
     * 12 = A cada 12 horas
     * 24 = Diariamente (24h)
     */
    val INTERVAL_OPTIONS = listOf(
        0 to "Desativado",
        1 to "Ao iniciar o app",
        3 to "A cada 3 horas",
        6 to "A cada 6 horas (Padrão)",
        12 to "A cada 12 horas",
        24 to "Diariamente (24h)",
    )

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mihon-update-scheduler").apply { isDaemon = true }
    }
    private var scheduledTask: ScheduledFuture<*>? = null
    private val checking = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    var intervalHours: Int
        get() = prefs.getInt(KEY_INTERVAL_HOURS, 6)
        set(value) {
            prefs.putInt(KEY_INTERVAL_HOURS, value)
            appCtxRef?.let { reschedule(it, runImmediateIfStartup = false) }
        }

    val lastCheckEpochMs: Long
        get() = prefs.getLong(KEY_LAST_CHECK_MS, 0L)

    val isChecking: Boolean
        get() = checking.get()

    @Volatile
    private var appCtxRef: AppContext? = null

    fun addUpdateListener(listener: () -> Unit) {
        if (listener !in listeners) listeners.add(listener)
    }

    fun removeUpdateListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        SwingUtilities.invokeLater {
            listeners.forEach { runCatching { it.invoke() } }
        }
    }

    fun start(ctx: AppContext) {
        appCtxRef = ctx
        reschedule(ctx, runImmediateIfStartup = true)
    }

    fun stop() {
        scheduledTask?.cancel(false)
        scheduledTask = null
    }

    private fun reschedule(ctx: AppContext, runImmediateIfStartup: Boolean) {
        scheduledTask?.cancel(false)
        scheduledTask = null
        val hours = intervalHours
        if (hours <= 0) return

        if (hours == 1) {
            if (runImmediateIfStartup) {
                scheduler.schedule({ checkNow(ctx, silent = true) }, 8, TimeUnit.SECONDS)
            }
            return
        }

        val initialDelayMinutes = if (runImmediateIfStartup) 2L else (hours * 60L)
        val periodMinutes = hours * 60L
        scheduledTask = scheduler.scheduleAtFixedRate(
            { checkNow(ctx, silent = true) },
            initialDelayMinutes,
            periodMinutes,
            TimeUnit.MINUTES,
        )
    }

    /**
     * Executa uma verificação completa de novos capítulos para todos os mangás online da biblioteca.
     */
    fun checkNow(
        ctx: AppContext,
        silent: Boolean = false,
        onProgress: (current: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
        onCompleted: (newChaptersCount: Int, updatedMangasCount: Int) -> Unit = { _, _ -> },
    ) {
        if (!checking.compareAndSet(false, true)) {
            if (!silent) {
                ctx.setStatus("Uma verificação de atualizações já está em andamento…")
            }
            return
        }
        notifyListeners()

        background({
            val favs = ctx.service.library().filter { !it.isLocal }
            var newChaptersTotal = 0
            var updatedMangas = 0
            val newlyFoundTitles = mutableListOf<String>()

            favs.forEachIndexed { idx, manga ->
                val src = ctx.sourceFor(manga.source) ?: return@forEachIndexed
                SwingUtilities.invokeLater {
                    onProgress(idx + 1, favs.size, manga.title)
                    if (!silent) {
                        ctx.setStatus("Verificando (${idx + 1}/${favs.size}): ${manga.title}…")
                    }
                }
                runCatching {
                    val beforeUrls = ctx.service.chaptersOf(manga.id).mapTo(HashSet()) { it.url }
                    val afterList = ctx.service.refreshOnlineChapters(manga, src)
                    val added = afterList.count { it.url !in beforeUrls }
                    if (added > 0) {
                        newChaptersTotal += added
                        updatedMangas++
                        newlyFoundTitles.add("${manga.title} (+$added)")
                    }
                }
            }

            prefs.putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis())
            Triple(newChaptersTotal, updatedMangas, newlyFoundTitles)
        }) { result ->
            checking.set(false)
            notifyListeners()
            result.onSuccess { (newCount, mangasCount, titles) ->
                onCompleted(newCount, mangasCount)
                if (newCount > 0) {
                    val summary = titles.take(3).joinToString(", ") +
                        if (titles.size > 3) " e mais ${titles.size - 3}" else ""
                    SystemTrayManager.notify(
                        "Novos capítulos encontrados ($newCount)",
                        summary,
                    )
                    ctx.setStatus("Atualização concluída: $newCount novo(s) capítulo(s) em $mangasCount mangá(s)!")
                } else if (!silent) {
                    ctx.setStatus("Biblioteca verificada — todos os capítulos estão em dia.")
                }
            }.onFailure { err ->
                onCompleted(0, 0)
                if (!silent) {
                    ctx.setStatus("Falha na verificação: ${err.message}")
                }
            }
        }
    }
}

/**
 * Tela principal de "Atualizações":
 * Exibe o feed dos capítulos mais recentes dos mangás favoritos da biblioteca,
 * permite verificar novas publicações com 1 clique, filtrar não lidos, baixar (.cbz) ou abrir diretamente no leitor.
 */
class UpdatesScreen(private val ctx: AppContext) : JPanel(BorderLayout()) {
    private var allEntries: List<UpdateFeedEntry> = emptyList()
    private var unreadOnly = false

    private val listModel = DefaultListModel<UpdateFeedEntry>()
    private val feedList = JList(listModel)
    private val hoverAnimator = GridHoverAnimator(feedList)
    private val rowRenderer = UpdateRowCard()

    private val subtitleLabel = JLabel("Carregando feed de capítulos…").apply {
        font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12f)
        foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
    }

    private val progressBar = JProgressBar(0, 100).apply {
        isStringPainted = true
        isVisible = false
        preferredSize = Dimension(220, 22)
    }

    private val errorLabel = JLabel("").apply {
        font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12.5f)
        foreground = Theme.DANGER
    }
    private val btnRetryBanner = ModernButton("Tentar novamente", variant = ModernButton.Variant.SURFACE, compact = true).apply {
        addActionListener { triggerManualCheck() }
    }
    private val errorBanner = JPanel(BorderLayout(12, 0)).apply {
        isOpaque = true
        background = Theme.BG_SURFACE
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DANGER),
            BorderFactory.createEmptyBorder(8, Theme.PAGE_GUTTER, 8, Theme.PAGE_GUTTER),
        )
        add(errorLabel, BorderLayout.CENTER)
        add(btnRetryBanner, BorderLayout.EAST)
        isVisible = false
    }

    private val btnCheckNow = ModernButton(
        text = "Verificar agora",
        icon = ModernIcons.sync(16) { Color.WHITE },
        variant = ModernButton.Variant.PRIMARY,
    ).apply {
        toolTipText = "Buscar novos capítulos em todas as fontes da biblioteca"
        addActionListener { triggerManualCheck() }
    }

    private val btnFilterUnread = ModernButton(
        text = "Apenas não lidos",
        icon = ModernIcons.filter(16) { if (unreadOnly) Color.WHITE else Theme.TEXT_PRIMARY },
        variant = ModernButton.Variant.SURFACE,
    ).apply {
        addActionListener {
            unreadOnly = !unreadOnly
            variant = if (unreadOnly) ModernButton.Variant.PRIMARY else ModernButton.Variant.SURFACE
            applyFilter()
        }
    }

    private val intervalCombo = MihonDropdown(
        LibraryUpdateScheduler.INTERVAL_OPTIONS.map { it.second }.toTypedArray(),
    ).apply {
        val currentHours = LibraryUpdateScheduler.intervalHours
        val idx = LibraryUpdateScheduler.INTERVAL_OPTIONS.indexOfFirst { it.first == currentHours }.coerceAtLeast(0)
        selectedIndex = idx
        preferredSize = Dimension(185, Theme.CONTROL_HEIGHT_COMPACT)
        toolTipText = "Frequência da verificação automática em segundo plano"
        addActionListener {
            val chosen = LibraryUpdateScheduler.INTERVAL_OPTIONS.getOrNull(selectedIndex)?.first ?: 6
            if (LibraryUpdateScheduler.intervalHours != chosen) {
                LibraryUpdateScheduler.intervalHours = chosen
                val label = LibraryUpdateScheduler.INTERVAL_OPTIONS.getOrNull(selectedIndex)?.second ?: ""
                ctx.setStatus("Verificação periódica alterada para: $label")
            }
        }
    }

    private val centerCards = java.awt.CardLayout()
    private val centerPanel = JPanel(centerCards)
    private val emptyTitleLbl = JLabel("Nenhuma atualização recente").apply {
        font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 18f)
        foreground = Theme.TEXT_PRIMARY
        alignmentX = 0.5f
    }
    private val emptyDescLbl = JLabel("Clique em \"Verificar agora\" para buscar novos capítulos nas fontes da sua biblioteca.").apply {
        font = Theme.FONT_UI.deriveFont(Font.PLAIN, 13f)
        foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
        alignmentX = 0.5f
    }
    private val emptyCheckBtn = ModernButton(
        text = "Verificar agora",
        icon = ModernIcons.sync(16) { Color.WHITE },
        variant = ModernButton.Variant.PRIMARY,
    ).apply {
        alignmentX = 0.5f
        addActionListener { triggerManualCheck() }
    }

    private var themeListener: ((Boolean) -> Unit)? = null
    private var schedulerListener: (() -> Unit)? = null

    init {
        background = Theme.BG_APP

        val titleLabel = JLabel("Atualizações").apply {
            font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 25f)
            foreground = Theme.TEXT_PRIMARY
        }

        val leftHeader = JPanel(BorderLayout(0, 2)).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.NORTH)
            add(subtitleLabel, BorderLayout.SOUTH)
        }

        val rightControls = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(JLabel("Automático:").apply {
                font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12f)
                foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
            })
            add(intervalCombo)
            add(btnFilterUnread)
            add(btnCheckNow)
        }

        val headerRow = JPanel(BorderLayout(12, 8)).apply {
            isOpaque = false
            add(leftHeader, BorderLayout.WEST)
            add(rightControls, BorderLayout.EAST)
        }

        val topBar = JPanel(BorderLayout(0, 8)).apply {
            background = Theme.BG_SURFACE
            minimumSize = Dimension(0, 64)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(14, Theme.PAGE_GUTTER, 14, Theme.PAGE_GUTTER),
            )
            add(headerRow, BorderLayout.NORTH)
            add(progressBar, BorderLayout.CENTER)
            add(errorBanner, BorderLayout.SOUTH)
        }

        feedList.background = Theme.BG_APP
        feedList.fixedCellHeight = 76
        feedList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        feedList.border = BorderFactory.createEmptyBorder(10, Theme.PAGE_GUTTER, 18, Theme.PAGE_GUTTER)
        feedList.cellRenderer = ListCellRenderer { _, entry, index, selected, _ ->
            val downloaded = ctx.service.isChapterDownloaded(entry.manga, entry.chapter)
            val downloading = ctx.service.isChapterDownloading(entry.chapter.id)
            if (!ctx.covers.has(entry.manga.id)) {
                ctx.covers.load(entry.manga, firstChapterPath = { null }, onLoaded = { feedList.repaint() })
            }
            rowRenderer.apply {
                coverIcon = ctx.covers.get(entry.manga.id, entry.manga.title)
                mangaTitle = entry.manga.title
                chapterTitle = entry.chapter.name
                dateText = formatInstant(entry.chapter.dateFetch)
                isRead = entry.chapter.read
                isDownloaded = downloaded
                isDownloading = downloading
                selectedState = selected
                hoverProgress = hoverAnimator.progressFor(index)
            }
        }

        feedList.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val idx = feedList.locationToIndex(e.point)
                val valid = if (idx >= 0 && feedList.getCellBounds(idx, idx)?.contains(e.point) == true) idx else -1
                feedList.cursor = if (valid >= 0) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                hoverAnimator.setHovered(valid)
            }
        })

        feedList.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                hoverAnimator.setHovered(-1)
            }

            override fun mouseClicked(e: MouseEvent) {
                val idx = feedList.locationToIndex(e.point)
                if (idx < 0 || feedList.getCellBounds(idx, idx)?.contains(e.point) != true) return
                val entry = listModel.getElementAt(idx)
                if (SwingUtilities.isRightMouseButton(e)) {
                    feedList.selectedIndex = idx
                    showContextMenu(entry, e.x, e.y)
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    ctx.navigator.showDetails(entry.manga)
                }
            }
        })

        val scroll = JScrollPane(feedList).apply {
            border = null
            background = Theme.BG_APP
            viewport.background = Theme.BG_APP
            verticalScrollBar.unitIncrement = 24
        }

        val emptyIcon = JLabel(ModernIcons.updates(48) { if (Theme.isDark) Theme.ACCENT else Theme.PRIMARY }).apply {
            alignmentX = 0.5f
        }
        val emptyBox = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(emptyIcon)
            add(Box.createVerticalStrut(12))
            add(emptyTitleLbl)
            add(Box.createVerticalStrut(6))
            add(emptyDescLbl)
            add(Box.createVerticalStrut(16))
            add(emptyCheckBtn)
        }
        val emptyWrap = JPanel(java.awt.GridBagLayout()).apply {
            background = Theme.BG_APP
            add(emptyBox)
        }

        centerPanel.background = Theme.BG_APP
        centerPanel.add(scroll, "feed")
        centerPanel.add(emptyWrap, "empty")

        add(topBar, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)

        val schedListener = {
            val checking = LibraryUpdateScheduler.isChecking
            btnCheckNow.isEnabled = !checking
            emptyCheckBtn.isEnabled = !checking
            btnCheckNow.text = if (checking) "Verificando biblioteca…" else "Verificar agora"
            if (!checking) {
                refresh()
            }
        }
        schedulerListener = schedListener
        LibraryUpdateScheduler.addUpdateListener(schedListener)

        val listener = { _: Boolean ->
            background = Theme.BG_APP
            topBar.background = Theme.BG_SURFACE
            topBar.border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(14, Theme.PAGE_GUTTER, 14, Theme.PAGE_GUTTER),
            )
            errorBanner.background = Theme.BG_SURFACE
            errorLabel.foreground = Theme.DANGER
            titleLabel.foreground = Theme.TEXT_PRIMARY
            subtitleLabel.foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
            emptyWrap.background = Theme.BG_APP
            emptyTitleLbl.foreground = Theme.TEXT_PRIMARY
            emptyDescLbl.foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
            centerPanel.background = Theme.BG_APP
            feedList.background = Theme.BG_APP
            scroll.background = Theme.BG_APP
            scroll.viewport.background = Theme.BG_APP
            feedList.repaint()
            repaint()
        }
        themeListener = listener
        ctx.addThemeListener(listener)
    }

    fun dispose() {
        hoverAnimator.stop()
        schedulerListener?.let { LibraryUpdateScheduler.removeUpdateListener(it) }
        themeListener?.let { ctx.removeThemeListener(it) }
    }

    fun refresh() {
        val currentHours = LibraryUpdateScheduler.intervalHours
        val idx = LibraryUpdateScheduler.INTERVAL_OPTIONS.indexOfFirst { it.first == currentHours }.coerceAtLeast(0)
        if (intervalCombo.selectedIndex != idx) {
            intervalCombo.selectedIndex = idx
        }

        background({
            val favs = ctx.service.library()
            val entries = mutableListOf<UpdateFeedEntry>()
            for (m in favs) {
                val chaps = ctx.service.chaptersOf(m.id)
                // Pega os capítulos mais recentes de cada mangá da biblioteca
                val latest = chaps.sortedWith(
                    compareByDescending<Chapter> { it.dateFetch.toEpochMilliseconds() }
                        .thenByDescending { it.chapterNumber }
                ).take(8)
                latest.forEach { ch -> entries.add(UpdateFeedEntry(m, ch)) }
            }
            entries.sortedWith(
                compareByDescending<UpdateFeedEntry> { it.chapter.dateFetch.toEpochMilliseconds() }
                    .thenByDescending { it.chapter.chapterNumber }
            ).take(120)
        }) { result ->
            result.onSuccess { list ->
                errorBanner.isVisible = false
                allEntries = list
                applyFilter()
            }.onFailure { err ->
                errorLabel.text = "Não foi possível carregar atualizações: ${err.message ?: "erro inesperado"}"
                errorBanner.isVisible = true
            }
        }
    }

    private fun applyFilter() {
        val filtered = if (unreadOnly) allEntries.filter { !it.chapter.read } else allEntries
        listModel.clear()
        filtered.forEach { listModel.addElement(it) }

        val lastCheck = LibraryUpdateScheduler.lastCheckEpochMs
        val lastCheckStr = if (lastCheck > 0L) {
            val fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
            "Última checagem: ${fmt.format(Instant.ofEpochMilli(lastCheck))}"
        } else {
            "Nenhuma checagem recente"
        }
        subtitleLabel.text = "${filtered.size} capítulo(s)  •  $lastCheckStr"

        if (filtered.isEmpty()) {
            if (unreadOnly && allEntries.isNotEmpty()) {
                emptyTitleLbl.text = "Nenhum capítulo não lido"
                emptyDescLbl.text = "Todos os capítulos recentes já foram marcados como lidos."
                emptyCheckBtn.isVisible = false
            } else {
                emptyTitleLbl.text = "Nenhuma atualização recente"
                emptyDescLbl.text = "Clique em \"Verificar agora\" para buscar novos capítulos nas fontes da sua biblioteca."
                emptyCheckBtn.isVisible = true
            }
            centerCards.show(centerPanel, "empty")
        } else {
            centerCards.show(centerPanel, "feed")
        }
    }

    private fun triggerManualCheck() {
        errorBanner.isVisible = false
        progressBar.value = 0
        progressBar.string = "Verificando biblioteca…"
        progressBar.isVisible = true
        LibraryUpdateScheduler.checkNow(
            ctx = ctx,
            silent = false,
            onProgress = { cur, total, title ->
                progressBar.maximum = total.coerceAtLeast(1)
                progressBar.value = cur
                progressBar.string = "Verificando ($cur/$total): $title"
            },
            onCompleted = { _, _ ->
                progressBar.isVisible = false
                refresh()
            },
        )
    }

    private fun showContextMenu(entry: UpdateFeedEntry, x: Int, y: Int) {
        val items = mutableListOf<GooeyContextMenu.GooeyMenuItem>()
        items.add(
            GooeyContextMenu.GooeyMenuItem(
                id = "open_manga",
                label = "Abrir detalhes de '${entry.manga.title.take(24)}'",
                iconType = GooeyContextMenu.IconType.OPEN_DETAILS,
                onClick = { ctx.navigator.showDetails(entry.manga) },
            ),
        )
        if (!entry.manga.isLocal && !ctx.service.isChapterDownloaded(entry.manga, entry.chapter)) {
            items.add(
                GooeyContextMenu.GooeyMenuItem(
                    id = "download_ch",
                    label = "Baixar capítulo (.cbz)",
                    iconType = GooeyContextMenu.IconType.DOWNLOAD,
                    onClick = {
                        val src = ctx.sourceFor(entry.manga.source)
                        if (src != null) {
                            ctx.setStatus("Baixando '${entry.chapter.name}'…")
                            background({ ctx.service.downloadChapter(entry.manga, entry.chapter, src) }) { res ->
                                res.onSuccess {
                                    ctx.setStatus("Capítulo '${entry.chapter.name}' salvo offline!")
                                    SystemTrayManager.notifyChapterDownloaded(entry.manga.title, entry.chapter.name)
                                    feedList.repaint()
                                }.onFailure {
                                    SystemTrayManager.notifyDownloadError(entry.manga.title, entry.chapter.name, it.message ?: "Erro desconhecido")
                                    JOptionPane.showMessageDialog(this, "Erro ao baixar: ${it.message}", "Erro", JOptionPane.ERROR_MESSAGE)
                                }
                            }
                        }
                    },
                ),
            )
        }
        items.add(
            GooeyContextMenu.GooeyMenuItem(
                id = "toggle_read",
                label = if (entry.chapter.read) "Marcar como não lido" else "Marcar como lido",
                iconType = if (entry.chapter.read) GooeyContextMenu.IconType.UNREAD else GooeyContextMenu.IconType.COMPLETED,
                onClick = {
                    background({ ctx.service.markChaptersRead(listOf(entry.chapter), !entry.chapter.read) }) {
                        refresh()
                    }
                },
            ),
        )
        GooeyContextMenu.showSmart(items, feedList, x, y)
    }

    private fun formatInstant(instant: kotlin.time.Instant): String {
        val ms = instant.toEpochMilliseconds()
        if (ms <= 0L) return "Recente"
        return runCatching {
            val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
            fmt.format(Instant.ofEpochMilli(ms))
        }.getOrDefault("Recente")
    }

    private class UpdateRowCard : JPanel() {
        var coverIcon: javax.swing.ImageIcon? = null
        var mangaTitle: String = ""
        var chapterTitle: String = ""
        var dateText: String = ""
        var isRead: Boolean = false
        var isDownloaded: Boolean = false
        var isDownloading: Boolean = false
        var selectedState: Boolean = false
        var hoverProgress: Float = 0f

        init {
            isOpaque = false
            preferredSize = Dimension(420, 76)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

            val x = 6
            val w = width - 12
            val h = height
            val cardArc = Theme.CARD_RADIUS * 2

            if (selectedState) {
                g2.color = Theme.ACCENT_SOFT
                g2.fillRoundRect(x, 3, w, h - 6, cardArc, cardArc)
                g2.color = Theme.PRIMARY
                g2.stroke = java.awt.BasicStroke(1.2f)
                g2.drawRoundRect(x, 3, w - 1, h - 7, cardArc, cardArc)
            } else if (hoverProgress > 0.01f) {
                val hc = Theme.BG_CARD_HOVER
                g2.color = Color(hc.red, hc.green, hc.blue, (hc.alpha * hoverProgress).toInt().coerceIn(0, 255))
                g2.fillRoundRect(x, 3, w, h - 6, cardArc, cardArc)
            }

            g2.color = Theme.BORDER_SUBTLE
            g2.drawLine(x + 12, h - 1, x + w - 12, h - 1)

            // Barra de sinalização laranja à esquerda para capítulos não lidos
            if (!isRead) {
                g2.color = Theme.SIGNAL
                g2.fillRoundRect(x + 6, 14, 3, h - 28, 3, 3)
            }

            // Capa pequena (40x56)
            val thumbX = x + 18
            val thumbW = 40
            val thumbH = 56
            val thumbY = (h - thumbH) / 2
            val icon = coverIcon
            if (icon != null) {
                val oldClip = g2.clip
                g2.clip = java.awt.geom.RoundRectangle2D.Float(thumbX.toFloat(), thumbY.toFloat(), thumbW.toFloat(), thumbH.toFloat(), 12f, 12f)
                g2.drawImage(icon.image, thumbX, thumbY, thumbW, thumbH, null)
                g2.clip = oldClip
            } else {
                g2.color = Theme.BG_CARD
                g2.fillRoundRect(thumbX, thumbY, thumbW, thumbH, 12, 12)
            }
            g2.color = Theme.BORDER_SUBTLE
            g2.drawRoundRect(thumbX, thumbY, thumbW - 1, thumbH - 1, 12, 12)

            // Selo de status à direita
            val badgeText = when {
                isDownloaded -> "Offline"
                isDownloading -> "Baixando…"
                isRead -> "Lido"
                else -> "Novo"
            }
            val badgeBg = when {
                isDownloaded -> Color(Theme.SUCCESS.red, Theme.SUCCESS.green, Theme.SUCCESS.blue, 36)
                isDownloading -> Color(Theme.WARNING.red, Theme.WARNING.green, Theme.WARNING.blue, 36)
                isRead -> Theme.BG_SURFACE
                else -> Theme.SIGNAL
            }
            val badgeFg = when {
                isDownloaded -> if (Theme.isDark) Theme.SUCCESS else Color(0x16, 0x65, 0x34)
                isDownloading -> if (Theme.isDark) Theme.WARNING else Color(0x9A, 0x67, 0x00)
                isRead -> if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
                else -> Color.WHITE
            }
            val badgeIcon = when {
                isDownloaded -> ModernIcons.check(12) { badgeFg }
                isDownloading -> ModernIcons.sync(12) { badgeFg }
                else -> null
            }

            g2.font = Theme.FONT_UI.deriveFont(Font.BOLD, 11f)
            val fm = g2.fontMetrics
            val iconGap = if (badgeIcon != null) badgeIcon.iconWidth + 5 else 0
            val bw = iconGap + fm.stringWidth(badgeText) + 16
            val bh = 22
            val arc = Theme.BADGE_RADIUS * 2
            val bx = x + w - bw - 14
            val by = (h - bh) / 2
            g2.color = badgeBg
            g2.fillRoundRect(bx, by, bw, bh, arc, arc)
            if (isRead) {
                g2.color = Theme.BORDER_SUBTLE
                g2.drawRoundRect(bx, by, bw - 1, bh - 1, arc, arc)
            }
            if (badgeIcon != null) {
                badgeIcon.paintIcon(this, g2, bx + 8, (h - badgeIcon.iconHeight) / 2)
            }
            g2.color = badgeFg
            g2.drawString(badgeText, bx + 8 + iconGap, by + ((bh - fm.height) / 2) + fm.ascent)

            val textX = thumbX + thumbW + 14
            val maxTextW = (bx - textX - 14).coerceAtLeast(60)

            // Título do Mangá
            g2.font = Theme.FONT_UI.deriveFont(Font.BOLD, 13.5f)
            g2.color = if (isRead) Theme.TEXT_SECONDARY else Theme.TEXT_PRIMARY
            Theme.drawStringWithFallback(g2, clipUiText(g2, mangaTitle, maxTextW), textX, 30)

            // Nome do Capítulo + Data
            g2.font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12f)
            g2.color = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
            Theme.drawStringWithFallback(g2, clipUiText(g2, "$chapterTitle   •   $dateText", maxTextW), textX, 51)

            g2.dispose()
        }
    }
}
