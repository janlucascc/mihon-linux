package mihon.desktop

import mihon.data.database.DatabaseManager
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
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
                add(JLabel("📥", SwingConstants.CENTER).apply {
                    font = font.deriveFont(38f)
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
                            LibraryCategory.READING -> ModernIcons.play(15) { Theme.ACCENT }
                            LibraryCategory.COMPLETED -> ModernIcons.check(17) { Theme.SUCCESS }
                            LibraryCategory.PLAN_TO_READ -> ModernIcons.tag(18) { Theme.TEXT_MUTED }
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

                                if (catId != LibraryCategory.ALL && catId != LibraryCategory.READING && catId != LibraryCategory.COMPLETED && catId != LibraryCategory.PLAN_TO_READ) {
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

        fun statCard(icon: String, title: String, value: String): JPanel {
            return JPanel().apply {
                background = Theme.BG_CARD
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                    BorderFactory.createEmptyBorder(10, 14, 10, 14),
                )
                val top = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                    isOpaque = false
                    add(JLabel(icon).apply { font = font.deriveFont(16f) })
                    add(JLabel(title).apply {
                        font = font.deriveFont(11.5f)
                        foreground = Theme.TEXT_MUTED
                    })
                }
                val valLabel = JLabel(value).apply {
                    font = font.deriveFont(Font.BOLD, 17f)
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

        background({
            val stats = ctx.service.getReadingStatistics()
            val cacheMb = ctx.covers.getCacheSize() / (1024 * 1024)
            stats to cacheMb
        }) { res ->
            res.onSuccess { (stats, cacheMb) ->
                grid.removeAll()
                val minutes = stats.totalReadingTimeMs / 1000 / 60
                val timeStr = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

                grid.add(statCard("📚", "Mangás na Biblioteca", "${stats.totalManga}"))
                grid.add(statCard("📖", "Capítulos Lidos", "${stats.totalChaptersRead}"))
                grid.add(statCard("📥", "Capítulos Baixados", "${stats.totalChaptersDownloaded}"))
                grid.add(statCard("⏱️", "Tempo Estimado", timeStr))
                grid.add(statCard("🔌", "Fontes Instaladas", "${ctx.extensions.size}"))
                grid.add(statCard("💾", "Cache de Capas", "${cacheMb} MB"))

                wrappedContainer.removeAll()

                // Spotify Wrapped Section Header
                val wrappedHeader = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    maximumSize = Dimension(Int.MAX_VALUE, 30)
                    alignmentX = 0f
                    add(JLabel("⭐ SEU RECAP (Mihon Wrapped)").apply {
                        font = font.deriveFont(Font.BOLD, 14f)
                        foreground = Theme.ACCENT
                    }, BorderLayout.WEST)
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
                    background = Color(24, 26, 34)
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Color(90, 100, 140, 120), 1),
                        BorderFactory.createEmptyBorder(16, 16, 16, 16),
                    )

                    val topBadge = JLabel("👑 MANGÁ #1 MAIS LIDO").apply {
                        font = font.deriveFont(Font.BOLD, 11f)
                        foreground = Color(255, 215, 0)
                    }
                    add(topBadge, BorderLayout.NORTH)

                    if (topManga != null) {
                        val coverLbl = JLabel(ctx.covers.get(topManga.manga.id, topManga.manga.title)).apply {
                            preferredSize = Dimension(90, 130)
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
                            val mins = topManga.totalReadingTimeMs / 1000 / 60
                            val chapsLbl = JLabel("${topManga.chaptersRead} capítulo(s) lido(s)").apply {
                                font = font.deriveFont(Font.BOLD, 13f)
                                foreground = Theme.ACCENT
                            }
                            val timeLbl = JLabel(if (mins > 0) "~$mins min dedicados" else "Em leitura ativa").apply {
                                font = font.deriveFont(Font.PLAIN, 11.5f)
                                foreground = Color(160, 160, 170)
                            }
                            add(titleLbl)
                            add(Box.createVerticalStrut(4))
                            add(authorLbl)
                            add(Box.createVerticalStrut(12))
                            add(chapsLbl)
                            add(Box.createVerticalStrut(4))
                            add(timeLbl)
                        }
                        val centerBox = JPanel(BorderLayout(12, 0)).apply {
                            isOpaque = false
                            add(coverLbl, BorderLayout.WEST)
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
                                val count = JLabel("${item.chaptersRead} cap.").apply {
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
                        val dayLbl = JLabel("📅 Dia mais ativo de leitura: ${stats.mostActiveDay}").apply {
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
