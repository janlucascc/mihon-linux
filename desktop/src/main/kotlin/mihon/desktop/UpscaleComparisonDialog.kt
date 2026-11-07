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
 * Menu de Teste & Laboratório Visual:
 * Compara a qualidade da imagem padrão (Original vs Ultra HD vs Bilinear)
 * na Página 2 do Capítulo 1 dos mangás da biblioteca sem upscaling forçado.
 */
class UpscaleComparisonDialog(
    owner: JFrame,
    private val ctx: AppContext,
) : JDialog(owner, "Laboratório Visual de Qualidade — Menu de Teste", true) {

    private val progressBar = JProgressBar().apply {
        isIndeterminate = true
        isVisible = false
        preferredSize = Dimension(180, 18)
    }

    private val statusLabel = JLabel("Carregando capítulos da biblioteca...").apply {
        foreground = Theme.TEXT_MUTED
        font = font.deriveFont(12f)
    }

    private val mangaSelector = JComboBox<SampleOption>().apply {
        font = font.deriveFont(12.5f)
        preferredSize = Dimension(320, 32)
        maximumSize = Dimension(400, 32)
    }

    private val lblMetaManga = JLabel("—").apply { font = font.deriveFont(Font.BOLD, 12.5f); foreground = Theme.TEXT_PRIMARY }
    private val lblMetaRes = JLabel("—").apply { font = font.deriveFont(Font.BOLD, 12.5f); foreground = Theme.TEXT_PRIMARY }
    private val lblMetaSize = JLabel("—").apply { font = font.deriveFont(Font.BOLD, 12.5f); foreground = Theme.TEXT_PRIMARY }
    private val lblMetaStatus = JLabel("—").apply { font = font.deriveFont(Font.BOLD, 12.5f); foreground = Theme.ACCENT_HOVER }

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
        add(createMetaCol("RESOLUÇÃO NATIVA", lblMetaRes))
        add(createMetaCol("TAMANHO DO ARQUIVO", lblMetaSize))
        add(createMetaCol("STATUS DE QUALIDADE", lblMetaStatus))
    }

    private val tabs = JTabbedPane().apply {
        font = font.deriveFont(Font.BOLD, 12.5f)
    }

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
        val ultraHdImg: BufferedImage,
        val bilinearImg: BufferedImage,
    )

    init {
        setSize(1140, 800)
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
                    JLabel("Laboratório de Qualidade de Imagem").apply {
                        font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 17f)
                        foreground = Theme.TEXT_PRIMARY
                    },
                )
                add(Box.createVerticalStrut(3))
                add(
                    JLabel("Avalie a melhoria de traço e contraste (Ultra HD) comparado à scan bruta original").apply {
                        font = font.deriveFont(12.5f)
                        foreground = Theme.TEXT_MUTED
                    },
                )
            }

            val rightBox = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply {
                isOpaque = false

                val lblSelect = JLabel("Mangá:").apply {
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

            // 1. Ultra HD (Restauração de traço de nanquim e cores na resolução padrão)
            val ultraHd = MangaImageEnhancer.scale(
                src = orig,
                targetW = orig.width,
                targetH = orig.height,
                quality = MangaImageEnhancer.QualityMode.ULTRA_HD,
            )

            // 2. Bilinear Convencional
            val bilinear = MangaImageEnhancer.scale(
                src = orig,
                targetW = orig.width,
                targetH = orig.height,
                quality = MangaImageEnhancer.QualityMode.BILINEAR,
            )

            ProcessedData(
                mangaTitle = raw.mangaTitle,
                chapterName = raw.chapterName,
                pageName = raw.pageName,
                originalBytes = raw.originalBytes,
                originalImg = orig,
                ultraHdImg = ultraHd,
                bilinearImg = bilinear,
            )
        }) { result ->
            progressBar.isVisible = false
            result.onSuccess { data ->
                populateTabsAndDiagnostics(data)
            }.onFailure {
                statusLabel.text = "Falha ao processar: ${it.message}"
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

        lblMetaManga.text = "${data.mangaTitle} • ${data.chapterName}"
        lblMetaRes.text = "${orig.width} × ${orig.height} px"
        val kbSize = data.originalBytes.size / 1024
        lblMetaSize.text = "$kbSize KB (${data.pageName})"
        lblMetaStatus.text = "Ultra HD Pronto"

        tabs.removeAll()

        // 1. Aba Split Interativo (Recorte central com zoom 200% para evidenciar o traço)
        val cropW = min(400, orig.width)
        val cropH = min(400, orig.height)
        val cropX = (orig.width - cropW) / 2
        val cropY = (orig.height - cropH) / 2

        val cropOrig = orig.getSubimage(cropX, cropY, cropW, cropH)
        val cropHd = data.ultraHdImg.getSubimage(cropX, cropY, cropW, cropH)

        // Escala o crop em 2x bilinearmente apenas para exibição confortável na tela
        val zoomCropOrig = scaleImageFast(cropOrig, cropW * 2, cropH * 2)
        val zoomCropHd = scaleImageFast(cropHd, cropW * 2, cropH * 2)

        tabs.addTab("🔍 Comparador Split (Lado a Lado)", createSplitPanel(zoomCropOrig, zoomCropHd))

        // 2. Ultra HD (Página Completa)
        tabs.addTab(
            "✨ Ultra HD (Traço Nítido)",
            createImageViewer(
                data.ultraHdImg,
                "Ultra HD (Linhas de nanquim reconstruídas + Contraste dinâmico) — ${data.ultraHdImg.width}x${data.ultraHdImg.height} px",
            ),
        )

        // 3. Original (Scanlator Bruta)
        tabs.addTab(
            "🖼️ Original (Scan Bruta)",
            createImageViewer(
                orig,
                "Original da Scanlator (1x) — ${orig.width}x${orig.height} px (${data.originalBytes.size / 1024} KB)",
            ),
        )

        // 4. Guia Técnico
        tabs.addTab("📊 Resumo Técnico", createBenchmarkPanel(data))

        statusLabel.text = "Exibindo '${data.mangaTitle}' — ${data.chapterName} (${data.pageName})"
    }

    private fun createSplitPanel(leftCrop: BufferedImage, rightCrop: BufferedImage): JPanel {
        return JPanel(BorderLayout()).apply {
            background = Theme.BG_APP

            val banner = JPanel(BorderLayout()).apply {
                background = Theme.BG_SURFACE
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SUBTLE),
                    BorderFactory.createEmptyBorder(8, 16, 8, 16),
                )
                val left = JLabel("🔍 Comparação de Traços e Nitidez (Recorte com Zoom de Inspeção)").apply {
                    font = font.deriveFont(Font.BOLD, 12.5f)
                    foreground = Theme.TEXT_PRIMARY
                }
                val right = JLabel("⬅ Esquerda: Original da Scanlator  │  Direita: Ultra HD ➡").apply {
                    font = font.deriveFont(Font.BOLD, 12f)
                    foreground = Theme.ACCENT_HOVER
                }
                add(left, BorderLayout.WEST)
                add(right, BorderLayout.EAST)
            }

            val split = JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                createImageViewer(leftCrop, "Original (Scanlator com ruído JPEG e serrilhado)"),
                createImageViewer(rightCrop, "Ultra HD (Traço limpo e preto profundo sem esticar)"),
            ).apply {
                resizeWeight = 0.5
                dividerSize = 8
                border = null
            }

            add(banner, BorderLayout.NORTH)
            add(split, BorderLayout.CENTER)
        }
    }

    private fun createBenchmarkPanel(data: ProcessedData): JPanel {
        return JPanel(BorderLayout()).apply {
            background = Theme.BG_APP
            border = BorderFactory.createEmptyBorder(20, 24, 24, 24)

            val explanation = JLabel(
                "<html><div style='width:800px; line-height: 1.4;'>" +
                    "<h3 style='color:#E0E0E0; margin-bottom: 6px;'>Motor de Melhoria de Imagem Padrão (Sem Upscaling)</h3>" +
                    "<p style='color:#A0A0A0;'>" +
                    "Ao contrário de algoritmos de upscaling que inflam o tamanho em pixels e aumentam o peso dos arquivos, " +
                    "o modo <b>Ultra HD</b> opera na resolução original da obra. Ele limpa artefatos de compressão JPEG, " +
                    "reconstrói linhas finas de nanquim e calibra o contraste para telas modernas sem consumir memória RAM adicional." +
                    "</p></div></html>",
            )

            val columnNames = arrayOf(
                "Modo",
                "Resolução",
                "Reconstrução de Linhas",
                "Consumo de Disco",
                "Descrição",
            )

            val rowData = arrayOf(
                arrayOf(
                    "Ultra HD",
                    "${data.ultraHdImg.width}x${data.ultraHdImg.height}",
                    "Ativado (Traço Nítido + Cores Vivas)",
                    "Idêntico ao original (~${data.originalBytes.size / 1024} KB)",
                    "Melhoria visual direta mantendo a fidelidade da obra",
                ),
                arrayOf(
                    "Bilinear",
                    "${data.bilinearImg.width}x${data.bilinearImg.height}",
                    "Nenhum (Padrão simples)",
                    "Idêntico ao original (~${data.originalBytes.size / 1024} KB)",
                    "Exibição convencional sem pós-processamento",
                ),
                arrayOf(
                    "Original",
                    "${data.originalImg.width}x${data.originalImg.height}",
                    "Nenhum (Scan bruta)",
                    "~${data.originalBytes.size / 1024} KB",
                    "Imagem exatamente como foi escaneada e comprimida",
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

                val centerRenderer = DefaultTableCellRenderer().apply {
                    horizontalAlignment = SwingConstants.CENTER
                }
                columnModel.getColumn(1).cellRenderer = centerRenderer
                columnModel.getColumn(3).cellRenderer = centerRenderer
            }

            val scrollTable = JScrollPane(table).apply {
                border = BorderFactory.createLineBorder(Theme.BORDER_SUBTLE)
                preferredSize = Dimension(800, 160)
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
