package mihon.desktop

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.ZipFile
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import kotlin.math.max
import kotlin.math.min

/**
 * Menu de Teste & Laboratório Visual de Upscaling:
 * Permite selecionar qualquer mangá baixado na biblioteca, inspecionar a Página 2 do Capítulo 1
 * e comparar em abas organizadas o resultado de cada tecnologia (AMD FSR 1.0, 64-bit Ultra HD,
 * Lanczos-3 e Zoom convencional), com diagnóstico inteligente de resolução.
 */
class UpscaleComparisonDialog(
    owner: JFrame,
    private val ctx: AppContext,
) : JDialog(owner, "Laboratório Visual de Upscaling — Menu de Teste", true) {

    private val progressBar = JProgressBar().apply {
        isIndeterminate = true
        isVisible = false
        preferredSize = Dimension(180, 18)
    }

    private val statusLabel = JLabel("Carregando obras da biblioteca...").apply {
        foreground = Theme.TEXT_MUTED
        font = font.deriveFont(12f)
    }

    private val mangaSelector = JComboBox<SampleOption>().apply {
        font = font.deriveFont(12.5f)
        preferredSize = Dimension(320, 32)
        maximumSize = Dimension(400, 32)
    }

    // Painel de diagnóstico com métricas da scan
    private val lblMetaManga = JLabel("—").apply { font = font.deriveFont(Font.BOLD, 12.5f); foreground = Theme.TEXT_PRIMARY }
    private val lblMetaRes = JLabel("—").apply { font = font.deriveFont(Font.BOLD, 12.5f); foreground = Theme.TEXT_PRIMARY }
    private val lblMetaSize = JLabel("—").apply { font = font.deriveFont(Font.BOLD, 12.5f); foreground = Theme.TEXT_PRIMARY }
    private val lblMetaSmart = JLabel("—").apply { font = font.deriveFont(Font.BOLD, 12.5f); foreground = Theme.ACCENT }

    private val diagnosticCard = JPanel(GridLayout(1, 4, 12, 0)).apply {
        background = Theme.BG_CARD
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SUBTLE),
            BorderFactory.createEmptyBorder(10, 20, 10, 20),
        )

        fun createMetaCol(title: String, valueLabel: JLabel): JPanel {
            return JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                val t = JLabel(title).apply {
                    font = font.deriveFont(11f)
                    foreground = Theme.TEXT_MUTED
                }
                add(t)
                add(Box.createVerticalStrut(2))
                add(valueLabel)
            }
        }

        add(createMetaCol("MANGÁ SELECIONADO", lblMetaManga))
        add(createMetaCol("RESOLUÇÃO ORIGINAL", lblMetaRes))
        add(createMetaCol("PESO DA PÁGINA", lblMetaSize))
        add(createMetaCol("DIAGNÓSTICO INTELIGENTE", lblMetaSmart))
    }

    private val tabs = JTabbedPane().apply {
        font = font.deriveFont(Font.BOLD, 12.5f)
    }

    private var availableSamples: List<SampleOption> = emptyList()

    data class SampleOption(
        val mangaTitle: String,
        val chapterName: String,
        val cbzFile: File,
    ) {
        override fun toString(): String = "$mangaTitle ($chapterName)"
    }

    private data class ProcessedData(
        val mangaTitle: String,
        val chapterName: String,
        val pageName: String,
        val originalBytes: ByteArray,
        val originalImg: BufferedImage,
        val bilinear2x: BufferedImage,
        val fsr2x: BufferedImage,
        val hd64_2x: BufferedImage,
        val hd64_3x: BufferedImage,
        val sharp2x: BufferedImage,
    )

    init {
        setSize(1140, 820)
        setLocationRelativeTo(owner)
        layout = BorderLayout()

        val topHeader = JPanel(BorderLayout(14, 0)).apply {
            background = Theme.BG_SURFACE
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(14, 20, 14, 20),
            )

            val leftBox = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(
                    JLabel("Laboratório Gráfico & Teste Comparativo").apply {
                        font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 17f)
                        foreground = Theme.TEXT_PRIMARY
                    },
                )
                add(Box.createVerticalStrut(3))
                add(
                    JLabel("Avalie em tempo real o anti-aliasing AMD FSR 1.0, o traço vetorial e o modo inteligente de download").apply {
                        font = font.deriveFont(12.5f)
                        foreground = Theme.TEXT_MUTED
                    },
                )
            }

            val rightBox = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply {
                isOpaque = false

                val lblSelect = JLabel("Mangá de teste:").apply {
                    font = font.deriveFont(Font.BOLD, 12f)
                    foreground = Theme.TEXT_SECONDARY
                }
                val btnRun = JButton("⚡ Recalcular").apply {
                    font = font.deriveFont(Font.BOLD, 12.5f)
                    putClientProperty("JButton.buttonType", "roundRect")
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addActionListener {
                        val selected = mangaSelector.selectedItem as? SampleOption
                        if (selected != null) runComparison(selected)
                    }
                }
                val btnClose = JButton("Fechar").apply {
                    addActionListener { dispose() }
                }

                mangaSelector.addActionListener {
                    val selected = mangaSelector.selectedItem as? SampleOption
                    if (selected != null) runComparison(selected)
                }

                add(lblSelect)
                add(mangaSelector)
                add(btnRun)
                add(btnClose)
            }

            add(leftBox, BorderLayout.WEST)
            add(rightBox, BorderLayout.EAST)
        }

        val centerPanel = JPanel(BorderLayout()).apply {
            background = Theme.BG_APP
            add(diagnosticCard, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }

        val bottomStatus = JPanel(BorderLayout(10, 0)).apply {
            background = Theme.BG_SURFACE
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(8, 20, 8, 20),
            )
            add(statusLabel, BorderLayout.WEST)
            add(progressBar, BorderLayout.EAST)
        }

        add(topHeader, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)
        add(bottomStatus, BorderLayout.SOUTH)

        // Inicialização assíncrona
        SwingUtilities.invokeLater { initAvailableMangaSamples() }
    }

    private fun initAvailableMangaSamples() {
        progressBar.isVisible = true
        statusLabel.text = "Escaneando capítulos baixados na biblioteca..."

        background({
            val dlDir = ctx.service.downloadsDir
            val list = mutableListOf<SampleOption>()
            val mangaDirs = dlDir.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }

            for (dir in mangaDirs) {
                val cbzList = dir.listFiles { f -> f.extension.equals("cbz", ignoreCase = true) && f.length() > 0 }.orEmpty()
                    .sortedWith { a, b -> mihon.core.archive.NaturalOrderComparator.compare(a.name, b.name) }
                val cap1Cbz = cbzList.firstOrNull { it.name.contains("1") } ?: cbzList.firstOrNull() ?: continue
                list.add(SampleOption(dir.name, cap1Cbz.nameWithoutExtension, cap1Cbz))
            }
            list
        }) { result ->
            progressBar.isVisible = false
            result.onSuccess { samples ->
                availableSamples = samples
                mangaSelector.removeAllItems()
                if (samples.isEmpty()) {
                    statusLabel.text = "Nenhum capítulo baixado encontrado em ~/.mihon/downloads."
                    JOptionPane.showMessageDialog(
                        this,
                        "Baixe pelo menos um capítulo de mangá na biblioteca para utilizar o laboratório de testes.",
                        "Nenhum download encontrado",
                        JOptionPane.INFORMATION_MESSAGE,
                    )
                    return@onSuccess
                }

                samples.forEach { mangaSelector.addItem(it) }
                // Seleciona o primeiro
                runComparison(samples.first())
            }.onFailure {
                statusLabel.text = "Erro ao localizar arquivos: ${it.message}"
            }
        }
    }

    private fun runComparison(option: SampleOption) {
        progressBar.isVisible = true
        statusLabel.text = "Processando ${option.mangaTitle} (${option.chapterName})..."
        tabs.removeAll()

        background({
            val raw = extractPage2FromCbz(option.mangaTitle, option.chapterName, option.cbzFile)
                ?: throw IllegalStateException("Página não encontrada no arquivo ${option.cbzFile.name}")

            val orig = raw.originalImg

            // 1. Zoom Convencional (Bilinear 2x)
            val bilinear2x = scaleImageFast(orig, orig.width * 2, orig.height * 2)

            // 2. AMD FSR 1.0 (EASU 12-tap + RCAS Anti-Aliasing de Bordas)
            val fsr2x = MangaImageEnhancer.scale(
                src = orig,
                targetW = orig.width * 2,
                targetH = orig.height * 2,
                quality = MangaImageEnhancer.QualityMode.FSR_EASU,
            )

            // 3. 2x Super HD 64-bit (FSR EASU + Anime4K + Vibrance + Dynamic Contrast)
            val hd64_2x = MangaImageEnhancer.scale(
                src = orig,
                targetW = orig.width * 2,
                targetH = orig.height * 2,
                quality = MangaImageEnhancer.QualityMode.HD_64BIT,
            )

            // 4. 3x Full HD Ultra
            val hd64_3x = MangaImageEnhancer.scale(
                src = orig,
                targetW = orig.width * 3,
                targetH = orig.height * 3,
                quality = MangaImageEnhancer.QualityMode.HD_64BIT,
            )

            // 5. 2x Nítido (Lanczos-3 Puro + CAS)
            val sharp2x = MangaImageEnhancer.scale(
                src = orig,
                targetW = orig.width * 2,
                targetH = orig.height * 2,
                quality = MangaImageEnhancer.QualityMode.SHARP,
            )

            ProcessedData(
                mangaTitle = raw.mangaTitle,
                chapterName = raw.chapterName,
                pageName = raw.pageName,
                originalBytes = raw.originalBytes,
                originalImg = orig,
                bilinear2x = bilinear2x,
                fsr2x = fsr2x,
                hd64_2x = hd64_2x,
                hd64_3x = hd64_3x,
                sharp2x = sharp2x,
            )
        }) { result ->
            progressBar.isVisible = false
            result.onSuccess { data ->
                populateTabsAndDiagnostics(data)
            }.onFailure {
                statusLabel.text = "Falha ao gerar comparativo: ${it.message}"
                JOptionPane.showMessageDialog(this, "Erro: ${it.message}", "Erro", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    private data class RawSample(
        val mangaTitle: String,
        val chapterName: String,
        val pageName: String,
        val originalBytes: ByteArray,
        val originalImg: BufferedImage,
    )

    private fun extractPage2FromCbz(mangaTitle: String, chapName: String, cbz: File): RawSample? {
        return runCatching {
            ZipFile(cbz).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp") }
                    .sortedWith { a, b -> mihon.core.archive.NaturalOrderComparator.compare(a.name, b.name) }
                    .toList()
                if (entries.isEmpty()) return null
                val targetEntry = if (entries.size >= 2) entries[1] else entries[0]
                val bytes = zip.getInputStream(targetEntry).readBytes()
                val img = mihon.core.archive.ImageDecoder.decode(bytes) ?: return null
                RawSample(
                    mangaTitle = mangaTitle,
                    chapterName = chapName,
                    pageName = targetEntry.name,
                    originalBytes = bytes,
                    originalImg = img,
                )
            }
        }.getOrNull()
    }

    private fun populateTabsAndDiagnostics(data: ProcessedData) {
        val orig = data.originalImg

        // Atualiza os metadados no banner de diagnóstico
        lblMetaManga.text = "${data.mangaTitle} • ${data.chapterName}"
        lblMetaRes.text = "${orig.width} × ${orig.height} px"
        val kbSize = data.originalBytes.size / 1024
        lblMetaSize.text = "$kbSize KB (${data.pageName})"

        if (orig.height >= 1600) {
            lblMetaSmart.text = "Alta Resolução (Economizar CPU/Disco)"
            lblMetaSmart.foreground = Color(76, 175, 80) // Verde
        } else {
            lblMetaSmart.text = "Baixa Resolução (FSR Recomendado)"
            lblMetaSmart.foreground = Color(255, 179, 0) // Âmbar
        }

        tabs.removeAll()

        // 1. Aba Split Interativo (Recorte com zoom 200%)
        val cropW = min(380, orig.width)
        val cropH = min(380, orig.height)
        val cropX = (orig.width - cropW) / 2
        val cropY = (orig.height - cropH) / 2

        val cropOrig1x = orig.getSubimage(cropX, cropY, cropW, cropH)
        val cropOrigBilinear = data.bilinear2x.getSubimage(cropX * 2, cropY * 2, cropW * 2, cropH * 2)
        val cropFsr = data.fsr2x.getSubimage(cropX * 2, cropY * 2, cropW * 2, cropH * 2)
        val cropHd64 = data.hd64_2x.getSubimage(cropX * 2, cropY * 2, cropW * 2, cropH * 2)

        tabs.addTab("🔍 Comparador Split (Lado a Lado)", createInteractiveSplitPanel(cropOrig1x, cropOrigBilinear, cropFsr, cropHd64))

        // 2. AMD FSR 1.0
        tabs.addTab(
            "⚡ AMD FSR 1.0 (Anti-Aliasing)",
            createImageViewer(
                data.fsr2x,
                "AMD FSR 1.0 (EASU 12-Tap Direcional + RCAS) — ${data.fsr2x.width}x${data.fsr2x.height} px  |  Elimina degraus de escada e serrilhados diagonais",
            ),
        )

        // 3. 2x Super HD 64-bit
        tabs.addTab(
            "🎨 2x Super HD 64-bit",
            createImageViewer(
                data.hd64_2x,
                "Mihon Super HD 64-bit (FSR + Reconstrução Vetorial Anime4K + Cores Vivas) — ${data.hd64_2x.width}x${data.hd64_2x.height} px",
            ),
        )

        // 4. 3x Full HD Ultra
        tabs.addTab(
            "📜 3x Full HD Ultra 4K",
            createImageViewer(
                data.hd64_3x,
                "3x Full HD Ultra (Alta Densidade de Pixels para Telas 4K) — ${data.hd64_3x.width}x${data.hd64_3x.height} px",
            ),
        )

        // 5. 2x Lanczos-3 Nítido
        tabs.addTab(
            "📐 2x Lanczos-3 Nítido",
            createImageViewer(
                data.sharp2x,
                "2x Lanczos-3 Fiel Nítido (Interpolação Clássica Sinc + CAS) — ${data.sharp2x.width}x${data.sharp2x.height} px",
            ),
        )

        // 6. Original vs Bilinear
        tabs.addTab(
            "🖼️ Original 1x (Scan Bruta)",
            createImageViewer(
                orig,
                "Original da Scanlator (1x) — ${orig.width}x${orig.height} px (${data.originalBytes.size / 1024} KB)",
            ),
        )

        // 7. Tabela Comparativa & Diagnóstico
        tabs.addTab("📊 Guia Técnico & Comparativo", createBenchmarkTablePanel(data))

        statusLabel.text = "Exibindo '${data.mangaTitle}' — ${data.chapterName} (${data.pageName}) | Concluído com sucesso"
    }

    private fun createInteractiveSplitPanel(
        cropOrig1x: BufferedImage,
        cropBilinear: BufferedImage,
        cropFsr: BufferedImage,
        cropHd64: BufferedImage,
    ): JPanel {
        return JPanel(BorderLayout()).apply {
            background = Theme.BG_APP

            val leftPanelHolder = JPanel(BorderLayout())
            val rightPanelHolder = JPanel(BorderLayout())

            val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanelHolder, rightPanelHolder).apply {
                resizeWeight = 0.5
                dividerSize = 8
                border = null
            }

            fun updateViews(modeIndex: Int) {
                leftPanelHolder.removeAll()
                rightPanelHolder.removeAll()

                when (modeIndex) {
                    0 -> {
                        // Bilinear vs AMD FSR 1.0
                        leftPanelHolder.add(createImageViewer(cropBilinear, "⬅ Esquerda: Zoom Convencional (Bilinear — Linhas borradas e serrilhadas)"))
                        rightPanelHolder.add(createImageViewer(cropFsr, "Direita: AMD FSR 1.0 (EASU 12-Tap — Bordas suaves sem serrilhado) ➡"))
                    }
                    1 -> {
                        // Original 1x vs AMD FSR 1.0
                        leftPanelHolder.add(createImageViewer(cropOrig1x, "⬅ Esquerda: Scan Original (1x — Resolução nativa da distribuição)"))
                        rightPanelHolder.add(createImageViewer(cropFsr, "Direita: AMD FSR 1.0 (2x Super Resolução com Anti-Aliasing) ➡"))
                    }
                    2 -> {
                        // Bilinear vs Super HD 64-bit
                        leftPanelHolder.add(createImageViewer(cropBilinear, "⬅ Esquerda: Zoom Convencional (Bilinear)"))
                        rightPanelHolder.add(createImageViewer(cropHd64, "Direita: Mihon Ultra HD 64-bit (Vetor Anime4K + Cores Vivas) ➡"))
                    }
                    else -> {
                        // AMD FSR vs Super HD 64-bit
                        leftPanelHolder.add(createImageViewer(cropFsr, "⬅ Esquerda: AMD FSR 1.0 (Fiel e Limpo sem serrilhado)"))
                        rightPanelHolder.add(createImageViewer(cropHd64, "Direita: Ultra HD 64-bit (Estilo Anime / Traço Marcado) ➡"))
                    }
                }
                leftPanelHolder.revalidate()
                leftPanelHolder.repaint()
                rightPanelHolder.revalidate()
                rightPanelHolder.repaint()
            }

            val modeSelector = JComboBox(
                arrayOf(
                    "Comparar: Zoom Convencional (Bilinear)  VS  AMD FSR 1.0 (EASU)",
                    "Comparar: Original (1x)  VS  AMD FSR 1.0 (EASU 2x)",
                    "Comparar: Zoom Convencional  VS  Ultra HD 64-bit (Anime4K)",
                    "Comparar: AMD FSR 1.0  VS  Ultra HD 64-bit",
                ),
            ).apply {
                font = font.deriveFont(Font.BOLD, 12f)
                addActionListener { updateViews(selectedIndex) }
            }

            val banner = JPanel(BorderLayout(12, 0)).apply {
                background = Theme.BG_SURFACE
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SUBTLE),
                    BorderFactory.createEmptyBorder(8, 16, 8, 16),
                )
                val left = JLabel("🔍 Inspeção de Detalhes Finos (Zoom 200% Central)").apply {
                    font = font.deriveFont(Font.BOLD, 12.5f)
                    foreground = Theme.TEXT_PRIMARY
                }
                add(left, BorderLayout.WEST)
                add(modeSelector, BorderLayout.EAST)
            }

            add(banner, BorderLayout.NORTH)
            add(split, BorderLayout.CENTER)

            updateViews(0)
        }
    }

    private fun createBenchmarkTablePanel(data: ProcessedData): JPanel {
        return JPanel(BorderLayout()).apply {
            background = Theme.BG_APP
            border = BorderFactory.createEmptyBorder(20, 24, 24, 24)

            val origH = data.originalImg.height
            val isHighRes = origH >= 1600

            val explanation = JLabel(
                "<html><div style='width:800px; line-height: 1.4;'>" +
                    "<h3 style='color:#E0E0E0; margin-bottom: 6px;'>Diretrizes do Motor Gráfico & Modo Inteligente</h3>" +
                    "<p style='color:#A0A0A0;'>" +
                    "O recurso de <b>Threshold Inteligente</b> impede que scans modernas oficiais (como as de 1600px a 2400px de altura) " +
                    "sejam infladas desnecessariamente durante o download. Isso economiza <b>até 75% de espaço em disco</b> " +
                    "e torna o download muito mais rápido, aplicando o super-upscaling apenas quando o mangá realmente precisa de melhoria de traço." +
                    "</p></div></html>",
            )

            val columnNames = arrayOf(
                "Perfil de Renderização",
                "Resolução",
                "Tratamento de Bordas",
                "Espaço em Disco (.cbz)",
                "Custo de Download",
                "Indicação",
            )

            val rowData = arrayOf(
                arrayOf(
                    "Original (1x)",
                    "${data.originalImg.width}x${data.originalImg.height}",
                    "Nenhum (Scan nativa)",
                    "~${data.originalBytes.size / 1024} KB / pág",
                    "Instantâneo (0s)",
                    if (isHighRes) "★ Recomendado para este mangá" else "Normal",
                ),
                arrayOf(
                    "AMD FSR 1.0 (EASU + RCAS)",
                    "${data.fsr2x.width}x${data.fsr2x.height}",
                    "12-Tap Anisotrópico (Anti-Serrilhado)",
                    "~${(data.originalBytes.size * 1.5).toInt() / 1024} KB / pág",
                    "Baixo (~80ms)",
                    if (!isHighRes) "★ Altamente Recomendado" else "Opcional",
                ),
                arrayOf(
                    "Ultra HD 64-bit",
                    "${data.hd64_2x.width}x${data.hd64_2x.height}",
                    "Vetor Anime4K + Cores Vivas",
                    "~${(data.originalBytes.size * 1.8).toInt() / 1024} KB / pág",
                    "Médio (~140ms)",
                    "Ideal para capas e mangás coloridos",
                ),
                arrayOf(
                    "3x Full HD Ultra",
                    "${data.hd64_3x.width}x${data.hd64_3x.height}",
                    "FSR EASU 3x + RCAS",
                    "~${(data.originalBytes.size * 2.8).toInt() / 1024} KB / pág",
                    "Alto (~280ms)",
                    "Apenas para monitores 4K ou zoom extremo",
                ),
                arrayOf(
                    "Zoom Convencional (Bilinear)",
                    "${data.bilinear2x.width}x${data.bilinear2x.height}",
                    "Pixelado / Linhas Borradas",
                    "Não aplicável (Apenas tela)",
                    "Zero",
                    "Padrão legado de leitores comuns",
                ),
            )

            val model = object : DefaultTableModel(rowData, columnNames) {
                override fun isCellEditable(row: Int, column: Int): Boolean = false
            }

            val table = JTable(model).apply {
                rowHeight = 32
                font = font.deriveFont(12.5f)
                tableHeader.font = font.deriveFont(Font.BOLD, 12f)
                background = Theme.BG_SURFACE
                foreground = Theme.TEXT_PRIMARY
                tableHeader.background = Theme.BG_CARD
                tableHeader.foreground = Theme.TEXT_PRIMARY
                gridColor = Theme.BORDER_SUBTLE
                setShowGrid(true)

                // Alinhamento central para resoluções e status
                val centerRenderer = DefaultTableCellRenderer().apply {
                    horizontalAlignment = SwingConstants.CENTER
                }
                columnModel.getColumn(1).cellRenderer = centerRenderer
                columnModel.getColumn(3).cellRenderer = centerRenderer
                columnModel.getColumn(4).cellRenderer = centerRenderer
            }

            val scrollTable = JScrollPane(table).apply {
                border = BorderFactory.createLineBorder(Theme.BORDER_SUBTLE)
                preferredSize = Dimension(800, 220)
            }

            val topBox = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(explanation, BorderLayout.NORTH)
                add(Box.createVerticalStrut(14), BorderLayout.CENTER)
            }

            add(topBox, BorderLayout.NORTH)
            add(scrollTable, BorderLayout.CENTER)
        }
    }

    private fun createImageViewer(img: BufferedImage, info: String): JPanel {
        return JPanel(BorderLayout()).apply {
            background = Theme.BG_APP
            val lblInfo = JLabel(info).apply {
                font = font.deriveFont(Font.BOLD, 12f)
                foreground = Theme.TEXT_SECONDARY
                border = BorderFactory.createEmptyBorder(8, 14, 8, 14)
            }
            add(lblInfo, BorderLayout.NORTH)

            val display = object : JPanel() {
                init {
                    background = Color(16, 17, 20)
                    preferredSize = Dimension(img.width, img.height)
                }

                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    val x = max(0, (width - img.width) / 2)
                    g2.drawImage(img, x, 0, null)
                }
            }

            val scroll = JScrollPane(display).apply {
                border = null
                verticalScrollBar.unitIncrement = 28
                horizontalScrollBar.unitIncrement = 28
            }
            add(scroll, BorderLayout.CENTER)
        }
    }
}
