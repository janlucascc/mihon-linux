package mihon.desktop

import mihon.data.database.DatabaseManager
import java.awt.BorderLayout
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
) : JDialog(owner, "Categorias", true) {
    init {
        setSize(480, 420)
        setLocationRelativeTo(owner)
        val content = JPanel(BorderLayout()).apply {
            background = Theme.BG_SURFACE
            border = BorderFactory.createEmptyBorder(20, 24, 20, 24)
        }

        val topBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JLabel("Categorias da Biblioteca").apply {
                font = font.deriveFont(Font.BOLD, 18f)
                foreground = Theme.TEXT_PRIMARY
            }, BorderLayout.WEST)
        }
        content.add(topBar, BorderLayout.NORTH)

        val listPanel = JPanel().apply {
            background = Theme.BG_SURFACE
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(14, 0, 14, 0)
        }

        background({
            val favs = ctx.service.library()
            val counts = favs.groupBy { it.status }.mapValues { it.value.size }
            counts to favs.size
        }) { res ->
            res.onSuccess { (counts, total) ->
                listPanel.removeAll()
                val categories = listOf(
                    Triple("📁", "Todos", 0L to total),
                    Triple("📖", "Lendo", 1L to (counts[1L] ?: 0)),
                    Triple("✅", "Completos", 2L to (counts[2L] ?: 0)),
                    Triple("🔖", "Quero ler", 3L to (counts[3L] ?: 0)),
                )

                categories.forEach { (icon, title, pair) ->
                    val (catId, count) = pair
                    val row = JPanel(BorderLayout(12, 0)).apply {
                        background = Theme.BG_CARD
                        border = BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                            BorderFactory.createEmptyBorder(12, 16, 12, 16),
                        )
                        maximumSize = Dimension(Int.MAX_VALUE, 54)
                        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)

                        val left = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
                            isOpaque = false
                            add(JLabel(icon).apply { font = font.deriveFont(16f) })
                            add(JLabel(title).apply {
                                font = font.deriveFont(Font.BOLD, 13.5f)
                                foreground = Theme.TEXT_PRIMARY
                            })
                        }
                        val countBadge = JLabel("$count mangá(s)").apply {
                            font = font.deriveFont(12f)
                            foreground = Theme.TEXT_MUTED
                        }
                        add(left, BorderLayout.WEST)
                        add(countBadge, BorderLayout.EAST)

                        addMouseListener(object : java.awt.event.MouseAdapter() {
                            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                                onCategoryFilter(catId)
                                dispose()
                            }
                        })
                    }
                    listPanel.add(row)
                    listPanel.add(Box.createVerticalStrut(8))
                }
                listPanel.revalidate()
                listPanel.repaint()
            }
        }

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
    }
}

/**
 * Material 3 Dialog: Estatísticas de Leitura
 */
class StatisticsDialog(owner: JFrame, private val ctx: AppContext) : JDialog(owner, "Estatísticas", true) {
    init {
        setSize(520, 460)
        setLocationRelativeTo(owner)
        val content = JPanel(BorderLayout()).apply {
            background = Theme.BG_SURFACE
            border = BorderFactory.createEmptyBorder(22, 26, 22, 26)
        }

        val titleLabel = JLabel("Estatísticas de Leitura").apply {
            font = font.deriveFont(Font.BOLD, 18f)
            foreground = Theme.TEXT_PRIMARY
        }
        content.add(titleLabel, BorderLayout.NORTH)

        val grid = JPanel(GridLayout(3, 2, 12, 12)).apply {
            background = Theme.BG_SURFACE
            border = BorderFactory.createEmptyBorder(16, 0, 16, 0)
        }

        fun statCard(icon: String, title: String, value: String): JPanel {
            return JPanel().apply {
                background = Theme.BG_CARD
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
                    BorderFactory.createEmptyBorder(14, 16, 14, 16),
                )
                val top = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    isOpaque = false
                    add(JLabel(icon).apply { font = font.deriveFont(18f) })
                    add(JLabel(title).apply {
                        font = font.deriveFont(12f)
                        foreground = Theme.TEXT_MUTED
                    })
                }
                val valLabel = JLabel(value).apply {
                    font = font.deriveFont(Font.BOLD, 18f)
                    foreground = Theme.TEXT_PRIMARY
                    border = BorderFactory.createEmptyBorder(8, 6, 0, 0)
                }
                add(top)
                add(valLabel)
            }
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

                grid.revalidate()
                grid.repaint()
            }
        }

        content.add(grid, BorderLayout.CENTER)

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
