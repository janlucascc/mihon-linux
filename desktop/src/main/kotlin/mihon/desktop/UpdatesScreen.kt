package mihon.desktop

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
        listeners.add(listener)
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
    private var hoveredRow = -1

    private val listModel = DefaultListModel<UpdateFeedEntry>()
    private val feedList = JList(listModel)
    private val rowRenderer = UpdateRowCard()

    private val subtitleLabel = JLabel("Carregando feed de capítulos…").apply {
        font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12f)
        foreground = Theme.TEXT_MUTED
    }

    private val progressBar = JProgressBar(0, 100).apply {
        isStringPainted = true
        isVisible = false
        preferredSize = Dimension(220, 22)
    }

    private val btnCheckNow = ModernButton(
        text = "Verificar Agora",
        icon = ModernIcons.sync(15) { Color.WHITE },
        variant = ModernButton.Variant.PRIMARY,
    ).apply {
        toolTipText = "Buscar novos capítulos em todas as fontes da biblioteca"
        addActionListener { triggerManualCheck() }
    }

    private val btnFilterUnread = ModernButton(
        text = "Apenas Não Lidos",
        icon = ModernIcons.filter(14) { if (unreadOnly) Theme.ACCENT else Theme.TEXT_PRIMARY },
        variant = ModernButton.Variant.SURFACE,
    ).apply {
        addActionListener {
            unreadOnly = !unreadOnly
            variant = if (unreadOnly) ModernButton.Variant.FAVORITE else ModernButton.Variant.SURFACE
            applyFilter()
        }
    }

    private val intervalCombo = JComboBox(
        LibraryUpdateScheduler.INTERVAL_OPTIONS.map { it.second }.toTypedArray(),
    ).apply {
        val currentHours = LibraryUpdateScheduler.intervalHours
        val idx = LibraryUpdateScheduler.INTERVAL_OPTIONS.indexOfFirst { it.first == currentHours }.coerceAtLeast(0)
        selectedIndex = idx
        preferredSize = Dimension(185, 32)
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

    private var themeListener: ((Boolean) -> Unit)? = null

    init {
        background = Theme.BG_APP

        val titleLabel = JLabel("Atualizações da Biblioteca").apply {
            font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 22f)
            foreground = Theme.TEXT_PRIMARY
        }

        val leftHeader = JPanel(BorderLayout(0, 4)).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.NORTH)
            add(subtitleLabel, BorderLayout.SOUTH)
        }

        val rightControls = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(JLabel("Automático:").apply {
                font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12f)
                foreground = Theme.TEXT_MUTED
            })
            add(intervalCombo)
            add(btnFilterUnread)
            add(btnCheckNow)
        }

        val topBar = JPanel(BorderLayout(12, 8)).apply {
            background = Theme.BG_APP
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(16, 24, 14, 24),
            )
            add(leftHeader, BorderLayout.WEST)
            add(rightControls, BorderLayout.EAST)
            add(progressBar, BorderLayout.SOUTH)
        }

        feedList.background = Theme.BG_APP
        feedList.fixedCellHeight = 68
        feedList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        feedList.border = BorderFactory.createEmptyBorder(10, 20, 18, 20)
        feedList.cellRenderer = ListCellRenderer { _, entry, index, selected, _ ->
            val downloaded = ctx.service.isChapterDownloaded(entry.manga, entry.chapter)
            val downloading = ctx.service.isChapterDownloading(entry.chapter.id)
            rowRenderer.apply {
                mangaTitle = entry.manga.title
                chapterTitle = entry.chapter.name
                dateText = formatInstant(entry.chapter.dateFetch)
                isRead = entry.chapter.read
                isDownloaded = downloaded
                isDownloading = downloading
                selectedState = selected
                hoveredState = index == hoveredRow
            }
        }

        feedList.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val idx = feedList.locationToIndex(e.point)
                val valid = if (idx >= 0 && feedList.getCellBounds(idx, idx)?.contains(e.point) == true) idx else -1
                feedList.cursor = if (valid >= 0) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                if (valid != hoveredRow) {
                    val prev = hoveredRow
                    hoveredRow = valid
                    if (prev >= 0) feedList.getCellBounds(prev, prev)?.let { feedList.repaint(it) }
                    if (valid >= 0) feedList.getCellBounds(valid, valid)?.let { feedList.repaint(it) }
                }
            }
        })

        feedList.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                if (hoveredRow != -1) {
                    val prev = hoveredRow
                    hoveredRow = -1
                    feedList.getCellBounds(prev, prev)?.let { feedList.repaint(it) }
                }
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

        add(topBar, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)

        LibraryUpdateScheduler.addUpdateListener {
            btnCheckNow.isEnabled = !LibraryUpdateScheduler.isChecking
            btnCheckNow.text = if (LibraryUpdateScheduler.isChecking) "Verificando…" else "Verificar Agora"
            if (!LibraryUpdateScheduler.isChecking) {
                refresh()
            }
        }

        val listener = { _: Boolean ->
            background = Theme.BG_APP
            topBar.background = Theme.BG_APP
            topBar.border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(16, 24, 14, 24),
            )
            titleLabel.foreground = Theme.TEXT_PRIMARY
            subtitleLabel.foreground = Theme.TEXT_MUTED
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
                allEntries = list
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val filtered = if (unreadOnly) allEntries.filter { !it.chapter.read } else allEntries
        listModel.clear()
        filtered.forEach { listModel.addElement(it) }

        val lastCheck = LibraryUpdateScheduler.lastCheckEpochMs
        val lastCheckStr = if (lastCheck > 0L) {
            val fmt = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault())
            "Última verificação: ${fmt.format(Instant.ofEpochMilli(lastCheck))}"
        } else {
            "Nenhuma verificação automática recente"
        }
        subtitleLabel.text = "${filtered.size} capítulo(s) listado(s)  •  $lastCheckStr"
    }

    private fun triggerManualCheck() {
        progressBar.value = 0
        progressBar.string = "Iniciando verificação da biblioteca…"
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
        GooeyContextMenu(items).showAt(feedList, x, y)
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
        var mangaTitle: String = ""
        var chapterTitle: String = ""
        var dateText: String = ""
        var isRead: Boolean = false
        var isDownloaded: Boolean = false
        var isDownloading: Boolean = false
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
                g2.fillRoundRect(x, 3, w, h - 6, 10, 10)
            }

            g2.color = Theme.BORDER_SUBTLE
            g2.drawLine(x + 12, h - 1, x + w - 12, h - 1)

            // Barra de destaque à esquerda para capítulos não lidos
            if (!isRead) {
                g2.color = Theme.ACCENT
                g2.fillRoundRect(x + 6, 14, 4, h - 28, 4, 4)
            }

            // Selo de status à direita (calculado primeiro para evitar sobreposição no texto)
            val badgeText = when {
                isDownloaded -> "Offline"
                isDownloading -> "Baixando…"
                isRead -> "Lido"
                else -> "NOVO"
            }
            val badgeBg = when {
                isDownloaded -> Color(Theme.SUCCESS.red, Theme.SUCCESS.green, Theme.SUCCESS.blue, 38)
                isDownloading -> Color(Theme.WARNING.red, Theme.WARNING.green, Theme.WARNING.blue, 38)
                isRead -> Theme.BG_SURFACE
                else -> Theme.ACCENT_SOFT
            }
            val badgeFg = when {
                isDownloaded -> Theme.SUCCESS
                isDownloading -> Theme.WARNING
                isRead -> Theme.TEXT_MUTED
                else -> Theme.ACCENT
            }
            val badgeIcon = when {
                isDownloaded -> ModernIcons.check(12) { badgeFg }
                isDownloading -> ModernIcons.sync(12) { badgeFg }
                !isRead -> ModernIcons.flame(12) { badgeFg }
                else -> null
            }

            g2.font = Theme.FONT_MONO.deriveFont(Font.BOLD, 11f)
            val fm = g2.fontMetrics
            val iconGap = if (badgeIcon != null) badgeIcon.iconWidth + 5 else 0
            val bw = iconGap + fm.stringWidth(badgeText) + 18
            val bx = x + w - bw - 16
            val by = (h - 26) / 2
            g2.color = badgeBg
            g2.fillRoundRect(bx, by, bw, 26, 26, 26)
            if (badgeIcon != null) {
                badgeIcon.paintIcon(this, g2, bx + 9, (h - badgeIcon.iconHeight) / 2)
            }
            g2.color = badgeFg
            g2.drawString(badgeText, bx + 9 + iconGap, by + ((26 - fm.height) / 2) + fm.ascent)

            val textX = x + 22
            val maxTextW = (bx - textX - 16).coerceAtLeast(60)

            // Título do Mangá
            g2.font = Theme.FONT_UI.deriveFont(Font.BOLD, 13.5f)
            g2.color = if (isRead) Theme.TEXT_MUTED else Theme.TEXT_PRIMARY
            g2.drawString(clipUiText(g2, mangaTitle, maxTextW), textX, 26)

            // Nome do Capítulo + Data
            g2.font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12f)
            g2.color = if (isRead) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
            g2.drawString(clipUiText(g2, "$chapterTitle   •   $dateText", maxTextW), textX, 48)

            g2.dispose()
        }
    }
}
