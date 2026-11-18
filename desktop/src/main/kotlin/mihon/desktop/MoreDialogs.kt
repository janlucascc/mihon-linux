package mihon.desktop

import mihon.data.database.DatabaseManager
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.Window
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Material 3 Dialog: Fila de downloads
 */
class DownloadsQueueDialog(owner: JFrame, private val ctx: AppContext) : JDialog(owner, "Fila de downloads", true) {
    private val queuePanel = JPanel().apply {
        background = Theme.BG_SURFACE
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    private fun renderQueue() {
        queuePanel.removeAll()
        val items = ctx.service.downloadQueue
        if (items.isEmpty()) {
            val emptyPanel = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createEmptyBorder(40, 20, 40, 20)
                add(JLabel(ModernIcons.downloads(42) { Theme.TEXT_MUTED }).apply {
                    alignmentX = 0.5f
                })
                add(Box.createVerticalStrut(12))
                add(JLabel("Nenhum download na fila", SwingConstants.CENTER).apply {
                    font = font.deriveFont(Font.BOLD, 15f)
                    foreground = Theme.TEXT_PRIMARY
                    alignmentX = 0.5f
                })
                add(Box.createVerticalStrut(6))
                add(JLabel("Capítulos baixados para leitura offline aparecerão aqui.", SwingConstants.CENTER).apply {
                    font = font.deriveFont(12f)
                    foreground = Theme.TEXT_MUTED
                    alignmentX = 0.5f
                })
            }
            queuePanel.add(emptyPanel)
        } else {
            items.forEach { item ->
                val row = JPanel(BorderLayout(12, 0)).apply {
                    background = Theme.BG_CARD
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                        BorderFactory.createEmptyBorder(10, 14, 10, 14),
                    )
                    maximumSize = Dimension(Int.MAX_VALUE, 56)

                    val info = JPanel().apply {
                        isOpaque = false
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        add(JLabel("${item.mangaTitle} — ${item.chapterName}").apply {
                            font = font.deriveFont(Font.BOLD, 13f)
                            foreground = Theme.TEXT_PRIMARY
                        })
                        add(JLabel(item.progress).apply {
                            font = font.deriveFont(11f)
                            foreground = if (item.hasError) Theme.DANGER else if (item.isCompleted) Theme.SUCCESS else Theme.TEXT_MUTED
                        })
                    }
                    add(info, BorderLayout.CENTER)
                }
                queuePanel.add(row)
                queuePanel.add(Box.createVerticalStrut(6))
            }
        }
        queuePanel.revalidate()
        queuePanel.repaint()
    }

    init {
        setSize(560, 420)
        setLocationRelativeTo(owner)
        val content = JPanel(BorderLayout()).apply {
            background = Theme.BG_SURFACE
            border = BorderFactory.createEmptyBorder(20, 24, 20, 24)
        }

        val topBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 0, 14, 0)
            add(JLabel("Fila de downloads").apply {
                font = font.deriveFont(Font.BOLD, 18f)
                foreground = Theme.TEXT_PRIMARY
            }, BorderLayout.WEST)

            val btnClear = JButton("Limpar concluídos").apply {
                isFocusable = false
                addActionListener {
                    ctx.service.clearCompletedDownloads()
                    renderQueue()
                }
            }
            add(btnClear, BorderLayout.EAST)
        }
        content.add(topBar, BorderLayout.NORTH)

        renderQueue()
        content.add(JScrollPane(queuePanel).apply {
            border = BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1)
            verticalScrollBar.unitIncrement = 16
        }, BorderLayout.CENTER)

        val bottomBar = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            isOpaque = false
            val closeBtn = JButton("Fechar").apply {
                isFocusable = false
                addActionListener { dispose() }
            }
            add(closeBtn)
        }
        content.add(bottomBar, BorderLayout.SOUTH)
        add(content)
    }
}

/**
 * Material 3 Dialog: Categorias da Biblioteca
 */
class CategoriesDialog(
    owner: JFrame,
    private val ctx: AppContext,
    private val onCategoryFilter: (Long) -> Unit,
) : JDialog(owner, "Personalizar Categorias", true) {

    private fun createIconButton(icon: javax.swing.Icon, tip: String, onClick: () -> Unit): JButton {
        return JButton(icon).apply {
            isFocusable = false
            toolTipText = tip
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            preferredSize = Dimension(28, 28)
            minimumSize = Dimension(28, 28)
            maximumSize = Dimension(28, 28)
            border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
            addActionListener { onClick() }
        }
    }
    init {
        setSize(520, 500)
        setLocationRelativeTo(owner)
        val content = JPanel(BorderLayout()).apply {
            background = Theme.BG_SURFACE
            border = BorderFactory.createEmptyBorder(20, 24, 20, 24)
        }

        val listPanel = JPanel().apply {
            background = Theme.BG_SURFACE
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(14, 0, 14, 0)
        }

        fun refreshList() {
            background({
                val favs = ctx.service.library()
                val counts = favs.groupBy { it.status }.mapValues { it.value.size }
                counts to favs.size
            }) { res ->
                res.onSuccess { (counts, total) ->
                    listPanel.removeAll()
                    val entries = LibraryCategory.entries

                    entries.forEachIndexed { idx, (catId, title) ->
                        val count = if (catId == LibraryCategory.ALL) total else (counts[catId] ?: 0)
                        val icon = when (catId) {
                            LibraryCategory.ALL -> ModernIcons.library(18) { Theme.TEXT_PRIMARY }
                            LibraryCategory.READING -> ModernIcons.bookOpen(18) { Theme.ACCENT }
                            LibraryCategory.COMPLETED -> ModernIcons.checkCircle(18) { Theme.SUCCESS }
                            LibraryCategory.PLAN_TO_READ -> ModernIcons.bookmark(18) { Theme.TEXT_MUTED }
                            else -> ModernIcons.tag(18) { Theme.TEXT_PRIMARY }
                        }

                        val row = JPanel(BorderLayout(12, 0)).apply {
                            background = Theme.BG_CARD
                            border = BorderFactory.createCompoundBorder(
                                BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                                BorderFactory.createEmptyBorder(8, 16, 8, 16),
                            )
                            maximumSize = Dimension(Int.MAX_VALUE, 52)

                            val left = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
                                isOpaque = false
                                add(JLabel(icon))
                                add(JLabel(title).apply {
                                    font = font.deriveFont(Font.BOLD, 13.5f)
                                    foreground = Theme.TEXT_PRIMARY
                                    cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                                    addMouseListener(object : java.awt.event.MouseAdapter() {
                                        override fun mouseClicked(e: java.awt.event.MouseEvent) {
                                            onCategoryFilter(catId)
                                            dispose()
                                        }
                                    })
                                })
                            }

                            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                                isOpaque = false
                                add(JLabel("$count mangá(s)").apply {
                                    font = font.deriveFont(12f)
                                    foreground = Theme.TEXT_MUTED
                                    border = BorderFactory.createEmptyBorder(0, 0, 0, 8)
                                })

                                // Move buttons (Up/Down in list)
                                if (catId != LibraryCategory.ALL) {
                                    if (idx > 1) {
                                        add(createIconButton(ModernIcons.arrowUp(14) { Theme.TEXT_SECONDARY }, "Mover para cima") {
                                            LibraryCategory.moveCategory(catId, -1)
                                            refreshList()
                                        })
                                    }
                                    if (idx < entries.size - 1) {
                                        add(createIconButton(ModernIcons.arrowDown(14) { Theme.TEXT_SECONDARY }, "Mover para baixo") {
                                            LibraryCategory.moveCategory(catId, 1)
                                            refreshList()
                                        })
                                    }
                                    add(createIconButton(ModernIcons.edit(15) { Theme.TEXT_SECONDARY }, "Renomear categoria") {
                                        val newName = JOptionPane.showInputDialog(
                                            this@CategoriesDialog,
                                            "Novo nome para a categoria '$title':",
                                            title,
                                        )?.trim()
                                        if (!newName.isNullOrBlank() && newName != title) {
                                            LibraryCategory.updateCategory(catId, newName)
                                            refreshList()
                                        }
                                    })
                                }

                                if (catId != LibraryCategory.ALL) {
                                    add(createIconButton(ModernIcons.trash(15) { Theme.DANGER }, "Excluir categoria") {
                                        val confirm = JOptionPane.showConfirmDialog(
                                            this@CategoriesDialog,
                                            "Excluir a categoria '$title'?\n(Os mangás não serão excluídos, voltarão para 'Lendo')",
                                            "Excluir Categoria",
                                            JOptionPane.YES_NO_OPTION,
                                        )
                                        if (confirm == JOptionPane.YES_OPTION) {
                                            LibraryCategory.removeCategory(catId)
                                            refreshList()
                                        }
                                    })
                                }
                            }

                            add(left, BorderLayout.WEST)
                            add(right, BorderLayout.EAST)
                        }
                        listPanel.add(row)
                        listPanel.add(Box.createVerticalStrut(8))
                    }
                    listPanel.revalidate()
                    listPanel.repaint()
                }
            }
        }

        val topBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
            add(JLabel("Categorias da Biblioteca").apply {
                font = font.deriveFont(Font.BOLD, 18f)
                foreground = Theme.TEXT_PRIMARY
            }, BorderLayout.WEST)

            val btnAdd = JButton("+ Nova Categoria").apply {
                isFocusable = false
                font = font.deriveFont(Font.BOLD, 12f)
                addActionListener {
                    val name = JOptionPane.showInputDialog(
                        this@CategoriesDialog,
                        "Digite o nome da nova categoria:",
                        "Criar Categoria",
                        JOptionPane.PLAIN_MESSAGE,
                    )?.trim()
                    if (!name.isNullOrBlank()) {
                        LibraryCategory.addCategory(name)
                        refreshList()
                    }
                }
            }
            add(btnAdd, BorderLayout.EAST)
        }
        content.add(topBar, BorderLayout.NORTH)

        content.add(JScrollPane(listPanel).apply {
            border = null
            verticalScrollBar.unitIncrement = 16
        }, BorderLayout.CENTER)

        val bottomBar = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            isOpaque = false
            add(JButton("Fechar").apply {
                isFocusable = false
                addActionListener { dispose() }
            })
        }
        content.add(bottomBar, BorderLayout.SOUTH)
        add(content)

        refreshList()
    }
}

/**
 * Material 3 Dialog: Estatísticas de Leitura
 */
class StatisticsDialog(owner: JFrame, private val ctx: AppContext) : JDialog(owner, "Estatísticas & Wrapped", true) {
    init {
        setSize(780, 680)
        setLocationRelativeTo(owner)
        val content = JPanel(BorderLayout()).apply {
            background = Theme.BG_SURFACE
            border = BorderFactory.createEmptyBorder(20, 24, 20, 24)
        }

        val topBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 0, 12, 0)
            val titleLabel = JLabel("Estatísticas & Mihon Wrapped").apply {
                font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 22f)
                foreground = Theme.TEXT_PRIMARY
            }
            val subtitleLabel = JLabel("Seus hábitos de leitura, mangás favoritos e recordes de tempo").apply {
                font = font.deriveFont(Font.PLAIN, 12f)
                foreground = Theme.TEXT_MUTED
            }
            val headerBox = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(titleLabel)
                add(Box.createVerticalStrut(3))
                add(subtitleLabel)
            }
            add(headerBox, BorderLayout.WEST)
        }
        content.add(topBar, BorderLayout.NORTH)

        val mainContainer = JPanel().apply {
            background = Theme.BG_SURFACE
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        val grid = JPanel(GridLayout(2, 3, 10, 10)).apply {
            background = Theme.BG_SURFACE
            maximumSize = Dimension(Int.MAX_VALUE, 150)
            alignmentX = 0f
        }

        fun statCard(icon: javax.swing.Icon, title: String, value: String): JPanel {
            return object : JPanel() {
                override fun paintComponent(g: java.awt.Graphics) {
                    val g2 = g.create() as java.awt.Graphics2D
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = Theme.BG_CARD
                    g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)
                    g2.color = Theme.BORDER_SUBTLE
                    g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
                    g2.dispose()
                }
            }.apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createEmptyBorder(12, 14, 12, 14)
                val top = JPanel(FlowLayout(FlowLayout.LEFT, 7, 0)).apply {
                    isOpaque = false
                    add(JLabel(icon))
                    add(JLabel(title).apply {
                        font = font.deriveFont(11.5f)
                        foreground = Theme.TEXT_MUTED
                    })
                }
                val valLabel = JLabel(value).apply {
                    font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 18f)
                    foreground = Theme.TEXT_PRIMARY
                    border = BorderFactory.createEmptyBorder(6, 4, 0, 0)
                }
                add(top)
                add(valLabel)
            }
        }

        val wrappedContainer = JPanel().apply {
            background = Theme.BG_SURFACE
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(18, 0, 8, 0)
            alignmentX = 0f
        }

        fun formatReadingDuration(ms: Long): String {
            if (ms <= 0L) return "0m"
            val totalSeconds = (ms / 1000L).coerceAtLeast(1L)
            if (totalSeconds < 60L) return "${totalSeconds}s"
            val totalMinutes = Math.round(totalSeconds / 60.0).coerceAtLeast(1L)
            return if (totalMinutes >= 60L) {
                "${totalMinutes / 60L}h ${totalMinutes % 60L}m"
            } else {
                "${totalMinutes} min"
            }
        }

        background({
            val stats = ctx.service.getReadingStatistics()
            stats.topMangas.forEach { item ->
                ctx.covers.getOrReadDisk(item.manga)
            }
            val cacheMb = ctx.covers.getCacheSize() / (1024 * 1024)
            stats to cacheMb
        }) { res ->
            res.onSuccess { (stats, cacheMb) ->
                grid.removeAll()
                val timeStr = formatReadingDuration(stats.totalReadingTimeMs)

                grid.add(statCard(ModernIcons.library(16) { Theme.ACCENT }, "Mangás na Biblioteca", "${stats.totalManga}"))
                grid.add(statCard(ModernIcons.bookOpen(16) { Theme.ACCENT_HOVER }, "Capítulos Lidos", "${stats.totalChaptersRead}"))
                grid.add(statCard(ModernIcons.downloads(16) { Theme.SUCCESS }, "Capítulos Baixados", "${stats.totalChaptersDownloaded}"))
                grid.add(statCard(ModernIcons.history(16) { Theme.TEXT_PRIMARY }, "Tempo Estimado", timeStr))
                grid.add(statCard(ModernIcons.plug(16) { Theme.ACCENT }, "Fontes Instaladas", "${ctx.extensions.size}"))
                grid.add(statCard(ModernIcons.storage(16) { Theme.TEXT_SECONDARY }, "Cache de Capas", "${cacheMb} MB"))

                wrappedContainer.removeAll()

                // Spotify Wrapped Section Header
                val wrappedHeader = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    maximumSize = Dimension(Int.MAX_VALUE, 30)
                    alignmentX = 0f
                    add(
                        JLabel(
                            "SEU RECAP (Mihon Wrapped)",
                            ModernIcons.sparkles(16) { Theme.ACCENT },
                            SwingConstants.LEFT,
                        ).apply {
                            iconTextGap = 7
                            font = font.deriveFont(Font.BOLD, 14f)
                            foreground = Theme.ACCENT
                        },
                        BorderLayout.WEST,
                    )
                }
                wrappedContainer.add(wrappedHeader)
                wrappedContainer.add(Box.createVerticalStrut(12))

                // Wrapped Content Row: Left is Top Manga Hero Card, Right is Top List + Genres
                val wrappedRow = JPanel(GridLayout(1, 2, 14, 0)).apply {
                    isOpaque = false
                    maximumSize = Dimension(Int.MAX_VALUE, 260)
                    alignmentX = 0f
                }

                // Top Manga Hero Card (Spotify Wrapped Style)
                val topManga = stats.topMangas.firstOrNull()
                val heroCard = JPanel(BorderLayout(12, 12)).apply {
                    background = Color(0x17, 0x17, 0x1C)
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Color(Theme.PRIMARY.red, Theme.PRIMARY.green, Theme.PRIMARY.blue, 140), 1),
                        BorderFactory.createEmptyBorder(16, 16, 16, 16),
                    )

                    val topBadge = JLabel(
                        "MANGÁ #1 MAIS LIDO",
                        ModernIcons.sparkles(14) { Theme.WARNING },
                        SwingConstants.LEFT,
                    ).apply {
                        iconTextGap = 6
                        font = font.deriveFont(Font.BOLD, 11f)
                        foreground = Color(0xF4, 0xC8, 0x75)
                    }
                    add(topBadge, BorderLayout.NORTH)

                    if (topManga != null) {
                        var currentCoverIcon = ctx.covers.getOrReadDisk(topManga.manga)
                        val coverPanel = object : JPanel() {
                            init {
                                isOpaque = false
                                preferredSize = Dimension(96, 138)
                                minimumSize = Dimension(96, 138)
                                maximumSize = Dimension(96, 138)
                            }

                            override fun paintComponent(g: java.awt.Graphics) {
                                super.paintComponent(g)
                                val g2 = g.create() as java.awt.Graphics2D
                                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                                g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                                g2.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
                                val w = width
                                val h = height
                                val arc = 12f
                                g2.clip = java.awt.geom.RoundRectangle2D.Float(0f, 0f, w.toFloat(), h.toFloat(), arc, arc)
                                val img = currentCoverIcon.image
                                if (img != null) {
                                    g2.drawImage(img, 0, 0, w, h, null)
                                } else {
                                    g2.color = Color(36, 38, 48)
                                    g2.fillRect(0, 0, w, h)
                                }
                                g2.clip = null
                                g2.color = Color(255, 255, 255, 45)
                                g2.drawRoundRect(0, 0, w - 1, h - 1, arc.toInt(), arc.toInt())
                                g2.dispose()
                            }
                        }

                        if (!ctx.covers.has(topManga.manga.id)) {
                            ctx.covers.load(
                                topManga.manga,
                                firstChapterPath = { null },
                            ) {
                                currentCoverIcon = ctx.covers.get(topManga.manga.id, topManga.manga.title)
                                coverPanel.repaint()
                            }
                        }

                        val infoPanel = JPanel().apply {
                            isOpaque = false
                            layout = BoxLayout(this, BoxLayout.Y_AXIS)
                            val titleLbl = JLabel(topManga.manga.title).apply {
                                font = font.deriveFont(Font.BOLD, 15f)
                                foreground = Color.WHITE
                            }
                            val authorLbl = JLabel(topManga.manga.author ?: "Autor desconhecido").apply {
                                font = font.deriveFont(Font.PLAIN, 12f)
                                foreground = Color(180, 180, 190)
                            }
                            val chapsLbl = JLabel("${topManga.chaptersRead} capítulo(s) lido(s)").apply {
                                font = font.deriveFont(Font.BOLD, 13f)
                                foreground = Color(110, 175, 255)
                            }
                            val mangaTimeStr = formatReadingDuration(topManga.totalReadingTimeMs)
                            val timeLbl = JLabel(
                                if (topManga.totalReadingTimeMs > 0L) "$mangaTimeStr dedicados" else "Em leitura ativa",
                            ).apply {
                                font = font.deriveFont(Font.PLAIN, 11.5f)
                                foreground = Color(200, 205, 215)
                            }
                            add(titleLbl)
                            add(Box.createVerticalStrut(4))
                            add(authorLbl)
                            add(Box.createVerticalStrut(12))
                            add(chapsLbl)
                            add(Box.createVerticalStrut(4))
                            add(timeLbl)
                        }
                        val centerBox = JPanel(BorderLayout(14, 0)).apply {
                            isOpaque = false
                            add(coverPanel, BorderLayout.WEST)
                            add(infoPanel, BorderLayout.CENTER)
                        }
                        add(centerBox, BorderLayout.CENTER)
                    } else {
                        val emptyLbl = JLabel("Leia seus primeiros capítulos para desbloquear seu mangá #1!", SwingConstants.CENTER).apply {
                            foreground = Theme.TEXT_MUTED
                            font = font.deriveFont(12f)
                        }
                        add(emptyLbl, BorderLayout.CENTER)
                    }
                }

                // Right Panel: Top 5 Mangas & Top Genres
                val rightPanel = JPanel().apply {
                    background = Theme.BG_CARD
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                        BorderFactory.createEmptyBorder(14, 16, 14, 16),
                    )

                    val topMangasTitle = JLabel("Top Mangás").apply {
                        font = font.deriveFont(Font.BOLD, 12f)
                        foreground = Theme.TEXT_MUTED
                        alignmentX = 0f
                    }
                    add(topMangasTitle)
                    add(Box.createVerticalStrut(8))

                    if (stats.topMangas.isNotEmpty()) {
                        stats.topMangas.forEachIndexed { index, item ->
                            val row = JPanel(BorderLayout(8, 0)).apply {
                                isOpaque = false
                                maximumSize = Dimension(Int.MAX_VALUE, 24)
                                alignmentX = 0f
                                val rank = JLabel("${index + 1}. ").apply {
                                    font = font.deriveFont(Font.BOLD, 12f)
                                    foreground = if (index == 0) Color(255, 215, 0) else Theme.TEXT_MUTED
                                }
                                val name = JLabel(item.manga.title).apply {
                                    font = font.deriveFont(Font.BOLD, 12f)
                                    foreground = Theme.TEXT_PRIMARY
                                }
                                val count = JLabel("${item.chaptersRead} cap. • ${formatReadingDuration(item.totalReadingTimeMs)}").apply {
                                    font = font.deriveFont(11f)
                                    foreground = Theme.TEXT_MUTED
                                }
                                val left = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                                    isOpaque = false
                                    add(rank)
                                    add(name)
                                }
                                add(left, BorderLayout.WEST)
                                add(count, BorderLayout.EAST)
                            }
                            add(row)
                            add(Box.createVerticalStrut(4))
                        }
                    } else {
                        add(JLabel("Nenhuma leitura registrada ainda.").apply {
                            foreground = Theme.TEXT_MUTED
                            font = font.deriveFont(11f)
                        })
                    }

                    add(Box.createVerticalStrut(10))
                    val genreTitle = JLabel("Gêneros Favoritos").apply {
                        font = font.deriveFont(Font.BOLD, 12f)
                        foreground = Theme.TEXT_MUTED
                        alignmentX = 0f
                    }
                    add(genreTitle)
                    add(Box.createVerticalStrut(6))

                    val genrePillRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
                        isOpaque = false
                        alignmentX = 0f
                        if (stats.topGenres.isNotEmpty()) {
                            stats.topGenres.forEach { (genre, count) ->
                                val pill = JLabel("$genre ($count)").apply {
                                    font = font.deriveFont(11f)
                                    foreground = Theme.TEXT_PRIMARY
                                    border = BorderFactory.createCompoundBorder(
                                        BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                                        BorderFactory.createEmptyBorder(2, 6, 2, 6),
                                    )
                                }
                                add(pill)
                            }
                        } else {
                            add(JLabel("Gêneros aparecerão conforme você adiciona mangás.").apply {
                                foreground = Theme.TEXT_MUTED
                                font = font.deriveFont(11f)
                            })
                        }
                    }
                    add(genrePillRow)

                    if (stats.mostActiveDay != null) {
                        add(Box.createVerticalStrut(8))
                        val dayLbl = JLabel(
                            "Dia mais ativo de leitura: ${stats.mostActiveDay}",
                            ModernIcons.history(14) { Theme.SUCCESS },
                            SwingConstants.LEFT,
                        ).apply {
                            iconTextGap = 6
                            font = font.deriveFont(Font.BOLD, 11.5f)
                            foreground = Theme.SUCCESS
                            alignmentX = 0f
                        }
                        add(dayLbl)
                    }
                }

                wrappedRow.add(heroCard)
                wrappedRow.add(rightPanel)
                wrappedContainer.add(wrappedRow)

                mainContainer.revalidate()
                mainContainer.repaint()
            }
        }

        mainContainer.add(grid)
        mainContainer.add(wrappedContainer)

        val scroll = JScrollPane(mainContainer).apply {
            border = null
            verticalScrollBar.unitIncrement = 20
        }
        content.add(scroll, BorderLayout.CENTER)

        val bottomBar = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            isOpaque = false
            add(JButton("Fechar").apply {
                isFocusable = false
                addActionListener { dispose() }
            })
        }
        content.add(bottomBar, BorderLayout.SOUTH)
        add(content)
    }
}

/**
 * Material 3 Dialog: Dados e armazenamento
 */
class StorageManagerDialog(
    owner: JFrame,
    private val ctx: AppContext,
    private val dbManager: DatabaseManager,
    private val onExportBackup: () -> Unit,
    private val onImportBackup: () -> Unit,
    private val onCoversCleared: () -> Unit,
) : JDialog(owner, "Dados e armazenamento", true) {
    init {
        setSize(540, 460)
        setLocationRelativeTo(owner)
        val content = JPanel(BorderLayout()).apply {
            background = Theme.BG_SURFACE
            border = BorderFactory.createEmptyBorder(22, 26, 22, 26)
        }

        val titleLabel = JLabel("Dados e armazenamento").apply {
            font = font.deriveFont(Font.BOLD, 18f)
            foreground = Theme.TEXT_PRIMARY
        }
        content.add(titleLabel, BorderLayout.NORTH)

        val body = JPanel().apply {
            background = Theme.BG_SURFACE
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(16, 0, 16, 0)
        }

        // Card 1: Cache de capas
        val cacheSizeLabel = JLabel("Calculando…").apply {
            font = font.deriveFont(12f)
            foreground = Theme.TEXT_MUTED
        }
        fun updateCacheLabel() {
            val mb = ctx.covers.getCacheSize() / (1024 * 1024)
            cacheSizeLabel.text = "Espaço ocupado em disco: ~$mb MB"
        }
        updateCacheLabel()

        val cardCovers = JPanel(BorderLayout(12, 0)).apply {
            background = Theme.BG_CARD
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                BorderFactory.createEmptyBorder(14, 16, 14, 16),
            )
            maximumSize = Dimension(Int.MAX_VALUE, 70)

            val left = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JLabel("Cache de capas").apply {
                    font = font.deriveFont(Font.BOLD, 13.5f)
                    foreground = Theme.TEXT_PRIMARY
                })
                add(cacheSizeLabel)
            }
            val clearBtn = JButton("Limpar cache").apply {
                isFocusable = false
                addActionListener {
                    val freed = ctx.covers.clearDiskCache() / (1024 * 1024)
                    updateCacheLabel()
                    onCoversCleared()
                    JOptionPane.showMessageDialog(this@StorageManagerDialog, "Cache de capas limpo com sucesso!\nLiberados: ~$freed MB", "Cache", JOptionPane.INFORMATION_MESSAGE)
                }
            }
            add(left, BorderLayout.CENTER)
            add(clearBtn, BorderLayout.EAST)
        }
        body.add(cardCovers)
        body.add(Box.createVerticalStrut(12))

        // Card 2: Banco de dados SQLite
        val dbSizeKb = (ctx.dbFile.length() / 1024).coerceAtLeast(1)
        val cardDb = JPanel(BorderLayout(12, 0)).apply {
            background = Theme.BG_CARD
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                BorderFactory.createEmptyBorder(14, 16, 14, 16),
            )
            maximumSize = Dimension(Int.MAX_VALUE, 70)

            val left = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JLabel("Banco de dados SQLite").apply {
                    font = font.deriveFont(Font.BOLD, 13.5f)
                    foreground = Theme.TEXT_PRIMARY
                })
                add(JLabel("Tamanho atual: ~$dbSizeKb KB (mihon.db)").apply {
                    font = font.deriveFont(12f)
                    foreground = Theme.TEXT_MUTED
                })
            }
            val vacuumBtn = JButton("Otimizar (VACUUM)").apply {
                isFocusable = false
                addActionListener {
                    background({ dbManager.vacuum() }) {
                        ctx.setStatus("Banco de dados otimizado com VACUUM")
                        JOptionPane.showMessageDialog(this@StorageManagerDialog, "Banco de dados otimizado com sucesso!", "Otimização", JOptionPane.INFORMATION_MESSAGE)
                    }
                }
            }
            add(left, BorderLayout.CENTER)
            add(vacuumBtn, BorderLayout.EAST)
        }
        body.add(cardDb)
        body.add(Box.createVerticalStrut(12))

        // Card 3: Backups
        val cardBackup = JPanel(BorderLayout(12, 0)).apply {
            background = Theme.BG_CARD
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                BorderFactory.createEmptyBorder(14, 16, 14, 16),
            )
            maximumSize = Dimension(Int.MAX_VALUE, 70)

            val left = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JLabel("Backups da Biblioteca").apply {
                    font = font.deriveFont(Font.BOLD, 13.5f)
                    foreground = Theme.TEXT_PRIMARY
                })
                add(JLabel("Exportar ou importar banco em arquivo .zip").apply {
                    font = font.deriveFont(12f)
                    foreground = Theme.TEXT_MUTED
                })
            }
            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                isOpaque = false
                add(JButton("Exportar…").apply {
                    isFocusable = false
                    addActionListener {
                        dispose()
                        onExportBackup()
                    }
                })
                add(JButton("Restaurar…").apply {
                    isFocusable = false
                    addActionListener {
                        dispose()
                        onImportBackup()
                    }
                })
            }
            add(left, BorderLayout.CENTER)
            add(buttons, BorderLayout.EAST)
        }
        body.add(cardBackup)

        content.add(body, BorderLayout.CENTER)

        val bottomBar = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            isOpaque = false
            add(JButton("Fechar").apply {
                isFocusable = false
                addActionListener { dispose() }
            })
        }
        content.add(bottomBar, BorderLayout.SOUTH)
        add(content)
    }
}

/**
 * Material 3 Dialog: Add/Edit Note
 */
class NoteDialog(
    owner: Window?,
    private val title: String,
    private val currentNote: String? = null,
    private val onSave: (String) -> Unit,
) : JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL) {

    init {
        setSize(500, 380)
        setLocationRelativeTo(owner)
        val content = JPanel(BorderLayout()).apply {
            background = Theme.BG_SURFACE
            border = BorderFactory.createEmptyBorder(20, 24, 20, 24)
        }

        val topBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
            add(JLabel(title).apply {
                font = font.deriveFont(Font.BOLD, 18f)
                foreground = Theme.TEXT_PRIMARY
            }, BorderLayout.WEST)
        }
        content.add(topBar, BorderLayout.NORTH)

        val textArea = javax.swing.JTextArea(currentNote ?: "").apply {
            font = Theme.FONT_UI
            foreground = Theme.TEXT_PRIMARY
            background = Theme.BG_CARD
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                BorderFactory.createEmptyBorder(10, 10, 10, 10),
            )
            lineWrap = true
            wrapStyleWord = true
            rows = 12
            columns = 50
            caretColor = Theme.ACCENT
        }
        val scroll = JScrollPane(textArea).apply {
            border = null
            verticalScrollBar.unitIncrement = 16
        }
        content.add(scroll, BorderLayout.CENTER)

        val bottomBar = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(14, 0, 0, 0)
            add(ModernButton("Cancelar", variant = ModernButton.Variant.SURFACE).apply {
                isFocusable = false
                addActionListener { dispose() }
            })
            add(ModernButton("Salvar", variant = ModernButton.Variant.PRIMARY).apply {
                isFocusable = false
                addActionListener {
                    val note = textArea.text.trim()
                    if (note.isNotEmpty()) {
                        onSave(note)
                        dispose()
                    } else {
                        JOptionPane.showMessageDialog(this@NoteDialog, "A nota não pode estar vazia.", "Erro", JOptionPane.ERROR_MESSAGE)
                    }
                }
            })
        }
        content.add(bottomBar, BorderLayout.SOUTH)
        add(content)
    }
}

/**
 * Material 3 Dialog: Color Picker for Highlights
 */
class HighlightColorDialog(
    owner: Window?,
    private val onColorSelected: (String) -> Unit,
) : JDialog(owner, "Cor do Destaque", Dialog.ModalityType.APPLICATION_MODAL) {

    private val presetColors = listOf(
        "#FFFF00" to "Amarelo",
        "#FFD700" to "Dourado",
        "#FFA500" to "Laranja",
        "#FF6B6B" to "Vermelho Claro",
        "#4ECDC4" to "Turquesa",
        "#45B7D1" to "Azul Claro",
        "#96CEB4" to "Verde Claro",
        "#FFEAA7" to "Creme",
        "#DDA0DD" to "Lilás",
        "#98D8C8" to "Menta",
    )

    init {
        setSize(420, 300)
        setLocationRelativeTo(owner)
        val content = JPanel(BorderLayout()).apply {
            background = Theme.BG_SURFACE
            border = BorderFactory.createEmptyBorder(20, 24, 20, 24)
        }

        val topBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 0, 12, 0)
            add(JLabel("Escolha a cor do destaque").apply {
                font = font.deriveFont(Font.BOLD, 16f)
                foreground = Theme.TEXT_PRIMARY
            }, BorderLayout.WEST)
        }
        content.add(topBar, BorderLayout.NORTH)

        val grid = JPanel(GridLayout(2, 5, 10, 10)).apply {
            background = Theme.BG_SURFACE
            border = BorderFactory.createEmptyBorder(10, 0, 0, 0)
        }

        presetColors.forEach { (hex, name) ->
            val swatchColor = Color.decode(hex)
            val btn = object : JPanel(BorderLayout()) {
                private var hovered = false

                init {
                    isOpaque = false
                    background = swatchColor
                    preferredSize = Dimension(56, 56)
                    border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
                    cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                    addMouseListener(object : java.awt.event.MouseAdapter() {
                        override fun mouseClicked(e: java.awt.event.MouseEvent) {
                            onColorSelected(hex)
                            dispose()
                        }

                        override fun mouseEntered(e: java.awt.event.MouseEvent) {
                            hovered = true
                            repaint()
                        }

                        override fun mouseExited(e: java.awt.event.MouseEvent) {
                            hovered = false
                            repaint()
                        }
                    })
                }

                override fun paintComponent(g: java.awt.Graphics) {
                    val g2 = g.create() as java.awt.Graphics2D
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = swatchColor
                    g2.fillRoundRect(1, 1, width - 2, height - 2, 12, 12)
                    g2.color = if (hovered) Theme.PRIMARY else Theme.BORDER_SUBTLE
                    g2.stroke = java.awt.BasicStroke(if (hovered) 2.5f else 1.2f)
                    g2.drawRoundRect(1, 1, width - 3, height - 3, 12, 12)
                    g2.dispose()
                    super.paintComponent(g)
                }
            }
            val lum = 0.299 * swatchColor.red + 0.587 * swatchColor.green + 0.114 * swatchColor.blue
            val label = JLabel(name, SwingConstants.CENTER).apply {
                font = font.deriveFont(Font.BOLD, 10.5f)
                foreground = if (lum > 145) Color(0x16, 0x17, 0x22) else Color.WHITE
                border = BorderFactory.createEmptyBorder(2, 2, 4, 2)
            }
            btn.add(label, BorderLayout.SOUTH)
            grid.add(btn)
        }

        content.add(grid, BorderLayout.CENTER)

        val bottomBar = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(12, 0, 0, 0)
            add(ModernButton("Cancelar", variant = ModernButton.Variant.SURFACE).apply {
                isFocusable = false
                addActionListener { dispose() }
            })
        }
        content.add(bottomBar, BorderLayout.SOUTH)
        add(content)
    }
}

/**
 * Material 3 Dialog: Bookmarks List for a Chapter
 */
class BookmarksListDialog(
    owner: Window?,
    private val ctx: AppContext,
    private val chapterId: Long,
    private val chapterName: String,
    private val pageCount: Int,
    private val onNavigateToPage: (Int) -> Unit,
) : JDialog(owner, "Bookmarks do Capítulo: $chapterName", Dialog.ModalityType.APPLICATION_MODAL) {

    private val bookmarksPanel = JPanel().apply {
        background = Theme.BG_SURFACE
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    }

    init {
        setSize(480, 520)
        setLocationRelativeTo(owner)
        val content = JPanel(BorderLayout()).apply {
            background = Theme.BG_SURFACE
            border = BorderFactory.createEmptyBorder(20, 24, 20, 24)
        }

        val topBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 0, 12, 0)
            add(JLabel("Bookmarks & Anotações").apply {
                font = font.deriveFont(Font.BOLD, 18f)
                foreground = Theme.TEXT_PRIMARY
            }, BorderLayout.WEST)
        }
        content.add(topBar, BorderLayout.NORTH)

        content.add(JScrollPane(bookmarksPanel).apply {
            border = BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1)
            verticalScrollBar.unitIncrement = 16
        }, BorderLayout.CENTER)

        val bottomBar = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(12, 0, 0, 0)
            add(ModernButton("Fechar", variant = ModernButton.Variant.SURFACE).apply {
                isFocusable = false
                addActionListener { dispose() }
            })
        }
        content.add(bottomBar, BorderLayout.SOUTH)
        add(content)

        refreshBookmarks()
    }

    private fun refreshBookmarks() {
        background({ ctx.service.getBookmarksByChapterId(chapterId) }) { res ->
            res.onSuccess { bookmarks ->
                SwingUtilities.invokeLater {
                    bookmarksPanel.removeAll()
                    if (bookmarks.isEmpty()) {
                        val emptyPanel = JPanel().apply {
                            isOpaque = false
                            layout = BoxLayout(this, BoxLayout.Y_AXIS)
                            border = BorderFactory.createEmptyBorder(40, 20, 40, 20)
                            add(JLabel(ModernIcons.bookmark(42) { Theme.TEXT_MUTED }).apply {
                                alignmentX = 0.5f
                            })
                            add(Box.createVerticalStrut(12))
                            add(JLabel("Nenhum bookmark ou anotação", SwingConstants.CENTER).apply {
                                font = font.deriveFont(Font.BOLD, 14f)
                                foreground = Theme.TEXT_PRIMARY
                                alignmentX = 0.5f
                            })
                            add(Box.createVerticalStrut(6))
                            add(JLabel("Pressione B para bookmark, N para nota", SwingConstants.CENTER).apply {
                                font = font.deriveFont(12f)
                                foreground = Theme.TEXT_MUTED
                                alignmentX = 0.5f
                            })
                        }
                        bookmarksPanel.add(emptyPanel)
                    } else {
                        bookmarks.forEach { bm ->
                            val row = createBookmarkRow(bm)
                            bookmarksPanel.add(row)
                            bookmarksPanel.add(Box.createVerticalStrut(6))
                        }
                    }
                    bookmarksPanel.revalidate()
                    bookmarksPanel.repaint()
                }
            }
        }
    }

    private fun createBookmarkRow(bm: tachiyomi.domain.bookmark.model.Bookmark): JPanel {
        val isNote = bm.type == tachiyomi.domain.bookmark.model.BookmarkType.NOTE
        val isHighlight = bm.type == tachiyomi.domain.bookmark.model.BookmarkType.HIGHLIGHT

        val typeIcon = when {
            isNote -> ModernIcons.edit(14) { Theme.ACCENT }
            isHighlight -> ModernIcons.sparkles(14) { Theme.WARNING }
            else -> ModernIcons.bookmark(14) { Theme.PRIMARY }
        }
        val typeLabel = when {
            isNote -> "Nota"
            isHighlight -> "Destaque"
            else -> "Bookmark"
        }

        val row = JPanel(BorderLayout(12, 0)).apply {
            background = Theme.BG_CARD
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                BorderFactory.createEmptyBorder(12, 14, 12, 14),
            )
            maximumSize = Dimension(Int.MAX_VALUE, 90)
        }

        val left = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("$typeLabel • Página ${bm.page + 1}/$pageCount", typeIcon, SwingConstants.LEFT).apply {
                iconTextGap = 7
                font = font.deriveFont(Font.BOLD, 12.5f)
                foreground = Theme.TEXT_PRIMARY
            })
            bm.note?.let { note ->
                add(Box.createVerticalStrut(4))
                val preview = if (note.length > 80) note.substring(0, 80) + "…" else note
                add(JLabel(preview).apply {
                    font = font.deriveFont(Font.PLAIN, 11.5f)
                    foreground = Theme.TEXT_MUTED
                })
            }
            if (isHighlight) {
                bm.color?.let { color ->
                    add(Box.createVerticalStrut(4))
                    val colorPanel = JPanel().apply {
                        isOpaque = true
                        background = runCatching { Color.decode(color) }.getOrDefault(Theme.WARNING)
                        preferredSize = Dimension(120, 18)
                        border = BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1)
                    }
                    add(colorPanel)
                }
            }
        }

        val right = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        val goToBtn = ModernButton("Ir para a página", variant = ModernButton.Variant.PRIMARY, compact = true).apply {
            isFocusable = false
            addActionListener {
                onNavigateToPage(bm.page)
                dispose()
            }
        }
        right.add(goToBtn)
        right.add(Box.createVerticalStrut(6))

        if (isNote) {
            val editBtn = ModernButton("Editar nota", variant = ModernButton.Variant.SURFACE, compact = true).apply {
                isFocusable = false
                addActionListener {
                    NoteDialog(owner, "Editar Nota", bm.note) { newNote ->
                        background({
                            ctx.service.updateNote(bm, newNote)
                        }) { _ ->
                            refreshBookmarks()
                        }
                    }.isVisible = true
                }
            }
            right.add(editBtn)
            right.add(Box.createVerticalStrut(6))
        }

        val deleteBtn = ModernButton("Excluir", ModernIcons.trash(13) { Theme.DANGER }, ModernButton.Variant.SURFACE, compact = true).apply {
            isFocusable = false
            addActionListener {
                val confirm = JOptionPane.showConfirmDialog(
                    this@BookmarksListDialog,
                    "Excluir este $typeLabel?",
                    "Confirmar Exclusão",
                    JOptionPane.YES_NO_OPTION,
                )
                if (confirm == JOptionPane.YES_OPTION) {
                    background({
                        ctx.service.deleteBookmark(bm.id)
                    }) { _ ->
                        refreshBookmarks()
                    }
                }
            }
        }
        right.add(deleteBtn)

        row.add(left, BorderLayout.CENTER)
        row.add(right, BorderLayout.EAST)
        return row
    }
}

/**
 * Material 3 Dialog: All Bookmarks Across Library
 */
class AllBookmarksDialog(
    owner: Window?,
    private val ctx: AppContext,
) : JDialog(owner, "Todos os Bookmarks", Dialog.ModalityType.APPLICATION_MODAL) {

    private val bookmarksPanel = JPanel().apply {
        background = Theme.BG_SURFACE
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    }

    init {
        setSize(600, 600)
        setLocationRelativeTo(owner)
        val content = JPanel(BorderLayout()).apply {
            background = Theme.BG_SURFACE
            border = BorderFactory.createEmptyBorder(20, 24, 20, 24)
        }

        val topBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 0, 12, 0)
            add(JLabel("Todos os Bookmarks & Anotações").apply {
                font = font.deriveFont(Font.BOLD, 18f)
                foreground = Theme.TEXT_PRIMARY
            }, BorderLayout.WEST)
        }
        content.add(topBar, BorderLayout.NORTH)

        content.add(JScrollPane(bookmarksPanel).apply {
            border = BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1)
            verticalScrollBar.unitIncrement = 16
        }, BorderLayout.CENTER)

        val bottomBar = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(12, 0, 0, 0)
            add(ModernButton("Fechar", variant = ModernButton.Variant.SURFACE).apply {
                isFocusable = false
                addActionListener { dispose() }
            })
        }
        content.add(bottomBar, BorderLayout.SOUTH)
        add(content)

        refreshBookmarks()
    }

    private fun refreshBookmarks() {
        background({ ctx.service.getAllBookmarks() }) { res ->
            res.onSuccess { bookmarks ->
                SwingUtilities.invokeLater {
                    bookmarksPanel.removeAll()
                    if (bookmarks.isEmpty()) {
                        val emptyPanel = JPanel().apply {
                            isOpaque = false
                            layout = BoxLayout(this, BoxLayout.Y_AXIS)
                            border = BorderFactory.createEmptyBorder(40, 20, 40, 20)
                            add(JLabel(ModernIcons.bookmark(42) { Theme.TEXT_MUTED }).apply {
                                alignmentX = 0.5f
                            })
                            add(Box.createVerticalStrut(12))
                            add(JLabel("Nenhum bookmark ou anotação", SwingConstants.CENTER).apply {
                                font = font.deriveFont(Font.BOLD, 14f)
                                foreground = Theme.TEXT_PRIMARY
                                alignmentX = 0.5f
                            })
                            add(Box.createVerticalStrut(6))
                            add(JLabel("Pressione B para bookmark, N para nota no leitor", SwingConstants.CENTER).apply {
                                font = font.deriveFont(12f)
                                foreground = Theme.TEXT_MUTED
                                alignmentX = 0.5f
                            })
                        }
                        bookmarksPanel.add(emptyPanel)
                    } else {
                        // Group by chapter
                        val byChapter = bookmarks.groupBy { it.chapterId }
                        byChapter.forEach { (chapterId, bms) ->
                            background({ ctx.service.chapters.getChapterById(chapterId) }) { chRes ->
                                chRes.onSuccess { chapter ->
                                    if (chapter != null) {
                                        background({ ctx.service.mangas.getMangaById(chapter.mangaId) }) { mRes ->
                                            mRes.onSuccess { manga ->
                                                SwingUtilities.invokeLater {
                                                    addChapterSection(
                                                        manga?.title ?: "Mangá #${chapter.mangaId}",
                                                        chapter.name,
                                                        bms,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    bookmarksPanel.revalidate()
                    bookmarksPanel.repaint()
                }
            }
        }
    }

    private fun addChapterSection(mangaTitle: String, chapterName: String, bookmarks: List<tachiyomi.domain.bookmark.model.Bookmark>) {
        val sectionHeader = JLabel(
            "$mangaTitle — $chapterName",
            ModernIcons.bookOpen(15) { Theme.ACCENT },
            SwingConstants.LEFT,
        ).apply {
            iconTextGap = 7
            font = font.deriveFont(Font.BOLD, 13f)
            foreground = Theme.ACCENT
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(8, 4, 8, 4),
            )
        }
        bookmarksPanel.add(sectionHeader)

        bookmarks.forEach { bm ->
            val row = createBookmarkRow(bm, mangaTitle, chapterName)
            bookmarksPanel.add(row)
            bookmarksPanel.add(Box.createVerticalStrut(4))
        }
        bookmarksPanel.add(Box.createVerticalStrut(8))
    }

    private fun createBookmarkRow(
        bm: tachiyomi.domain.bookmark.model.Bookmark,
        mangaTitle: String,
        chapterName: String,
    ): JPanel {
        val isNote = bm.type == tachiyomi.domain.bookmark.model.BookmarkType.NOTE
        val isHighlight = bm.type == tachiyomi.domain.bookmark.model.BookmarkType.HIGHLIGHT

        val typeIcon = when {
            isNote -> ModernIcons.edit(14) { Theme.ACCENT }
            isHighlight -> ModernIcons.sparkles(14) { Theme.WARNING }
            else -> ModernIcons.bookmark(14) { Theme.PRIMARY }
        }
        val typeLabel = when {
            isNote -> "Nota"
            isHighlight -> "Destaque"
            else -> "Bookmark"
        }

        val row = JPanel(BorderLayout(12, 0)).apply {
            background = Theme.BG_CARD
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                BorderFactory.createEmptyBorder(10, 14, 10, 14),
            )
            maximumSize = Dimension(Int.MAX_VALUE, 80)
        }

        val left = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("$typeLabel • Página ${bm.page + 1}", typeIcon, SwingConstants.LEFT).apply {
                iconTextGap = 7
                font = font.deriveFont(Font.BOLD, 12f)
                foreground = Theme.TEXT_PRIMARY
            })
            bm.note?.let { note ->
                add(Box.createVerticalStrut(4))
                val preview = if (note.length > 80) note.substring(0, 80) + "…" else note
                add(JLabel(preview).apply {
                    font = font.deriveFont(Font.PLAIN, 11f)
                    foreground = Theme.TEXT_MUTED
                })
            }
            if (isHighlight) {
                bm.color?.let { color ->
                    add(Box.createVerticalStrut(4))
                    val colorPanel = JPanel().apply {
                        isOpaque = true
                        background = runCatching { Color.decode(color) }.getOrDefault(Theme.WARNING)
                        preferredSize = Dimension(120, 18)
                        border = BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1)
                    }
                    add(colorPanel)
                }
            }
        }

        val right = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        val deleteBtn = ModernButton("Excluir", ModernIcons.trash(13) { Theme.DANGER }, ModernButton.Variant.SURFACE, compact = true).apply {
            isFocusable = false
            addActionListener {
                val confirm = JOptionPane.showConfirmDialog(
                    this@AllBookmarksDialog,
                    "Excluir este $typeLabel de '$chapterName'?",
                    "Confirmar Exclusão",
                    JOptionPane.YES_NO_OPTION,
                )
                if (confirm == JOptionPane.YES_OPTION) {
                    background({
                        ctx.service.deleteBookmark(bm.id)
                    }) { _ ->
                        refreshBookmarks()
                    }
                }
            }
        }
        right.add(deleteBtn)

        row.add(left, BorderLayout.CENTER)
        row.add(right, BorderLayout.EAST)
        return row
    }
}
