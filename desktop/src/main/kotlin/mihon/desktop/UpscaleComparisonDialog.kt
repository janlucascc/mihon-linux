package mihon.desktop

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO
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
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.math.min

/**
 * Menu de Teste & Laboratório Visual de Upscaling:
 * Carrega a Página 2 do Capítulo 1 de um mangá da biblioteca e exibe um comparador
 * visual interativo lado a lado e em abas entre:
 * 1. Original (1x)
 * 2. 2x Super HD (Ultra HD 64-bit)
 * 3. 3x Full HD Ultra
 * 4. 2x Lanczos-3 Nítido (Fiel / Traço puro)
 * 5. Zoom Convencional (Bilinear padrão de visualizadores comuns)
 */
class UpscaleComparisonDialog(
    owner: JFrame,
    private val ctx: AppContext,
) : JDialog(owner, "Laboratório Visual de Upscaling — Menu de Teste", true) {

    private val progressBar = JProgressBar().apply {
        isIndeterminate = true
        isVisible = false
        preferredSize = Dimension(200, 18)
    }
    private val statusLabel = JLabel("Selecione e inicie o teste comparativo").apply {
        foreground = Theme.TEXT_MUTED
        font = font.deriveFont(12f)
    }

    private val tabs = JTabbedPane().apply {
        font = font.deriveFont(Font.BOLD, 12.5f)
    }

    private var sampleInfoText = "Página 2 • Capítulo 1"

    init {
        setSize(1100, 780)
        setLocationRelativeTo(owner)
        layout = BorderLayout()

        val topHeader = JPanel(BorderLayout(14, 0)).apply {
            background = Theme.BG_SURFACE
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(16, 20, 16, 20),
            )

            val leftBox = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(
                    JLabel("Laboratório de Comparação Gráfica").apply {
                        font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 17f)
                        foreground = Theme.TEXT_PRIMARY
                    },
                )
                add(Box.createVerticalStrut(4))
                add(
                    JLabel("Compara a qualidade do mangá da sua biblioteca em tempo real (Original vs 2x vs 3x vs Convencional)").apply {
                        font = font.deriveFont(12.5f)
                        foreground = Theme.TEXT_MUTED
                    },
                )
            }

            val rightBox = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                isOpaque = false
                val btnRun = JButton("⚡ Gerar Comparativo Agora").apply {
                    font = font.deriveFont(Font.BOLD, 13f)
                    background = Theme.ACCENT
                    foreground = Theme.TEXT_PRIMARY
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addActionListener { runComparison() }
                }
                val btnClose = JButton("Fechar").apply {
                    addActionListener { dispose() }
                }
                add(btnRun)
                add(btnClose)
            }

            add(leftBox, BorderLayout.WEST)
            add(rightBox, BorderLayout.EAST)
        }

        val bottomStatus = JPanel(BorderLayout(10, 0)).apply {
            background = Theme.BG_SURFACE
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(8, 16, 8, 16),
            )
            add(statusLabel, BorderLayout.WEST)
            add(progressBar, BorderLayout.EAST)
        }

        add(topHeader, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        add(bottomStatus, BorderLayout.SOUTH)

        // Carrega automaticamente ao abrir
        SwingUtilities.invokeLater { runComparison() }
    }

    private fun runComparison() {
        progressBar.isVisible = true
        statusLabel.text = "Localizando página 2 do capítulo 1 na biblioteca..."
        tabs.removeAll()

        background({
            // 1. Obter mangá favorito da biblioteca
            val favs = ctx.service.library()
            val sampleData = loadPageTwoOfChapterOne(favs)
            sampleData
        }) { result ->
            progressBar.isVisible = false
            result.onSuccess { data ->
                if (data == null) {
                    statusLabel.text = "Nenhum mangá com capítulo 1 baixado foi encontrado na biblioteca."
                    JOptionPane.showMessageDialog(
                        this,
                        "Para testar, baixe o Capítulo 01 de qualquer mangá da sua biblioteca primeiro.",
                        "Capítulo não encontrado",
                        JOptionPane.INFORMATION_MESSAGE,
                    )
                    return@onSuccess
                }

                buildTabsWithProcessedImages(data)
            }.onFailure {
                statusLabel.text = "Erro: ${it.message}"
                JOptionPane.showMessageDialog(this, "Falha ao gerar comparativo: ${it.message}", "Erro", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    private data class SampleData(
        val mangaTitle: String,
        val chapterName: String,
        val pageName: String,
        val originalBytes: ByteArray,
        val originalImg: BufferedImage,
    )

    private suspend fun loadPageTwoOfChapterOne(favs: List<tachiyomi.domain.manga.model.Manga>): SampleData? {
        // Tenta encontrar em mangás favoritos primeiro, ou em qualquer mangá com downloads
        for (m in favs) {
            val chaps = ctx.service.chaptersOf(m.id)
            val cap1 = chaps.firstOrNull { it.name.contains("1") || it.chapterNumber <= 1.5 } ?: chaps.firstOrNull() ?: continue
            val cbz = ctx.service.chapterCbzFile(m, cap1)
            if (cbz.exists() && cbz.length() > 0) {
                val data = extractPage2FromCbz(m.title, cap1.name, cbz)
                if (data != null) return data
            }
        }

        // Fallback: verificar pasta de downloads diretamente
        val dlDir = ctx.service.downloadsDir
        val mangaDirs = dlDir.listFiles { f -> f.isDirectory }.orEmpty()
        for (dir in mangaDirs) {
            val cbzList = dir.listFiles { f -> f.extension.equals("cbz", ignoreCase = true) && f.length() > 0 }.orEmpty()
            val cap1Cbz = cbzList.firstOrNull { it.name.contains("1") } ?: cbzList.firstOrNull() ?: continue
            val data = extractPage2FromCbz(dir.name, cap1Cbz.nameWithoutExtension, cap1Cbz)
            if (data != null) return data
        }
        return null
    }

    private fun extractPage2FromCbz(mangaTitle: String, chapName: String, cbz: File): SampleData? {
        return runCatching {
            ZipFile(cbz).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp") }
                    .sortedWith { a, b -> mihon.core.archive.NaturalOrderComparator.compare(a.name, b.name) }
                    .toList()
                if (entries.isEmpty()) return null
                // Página 2 (índice 1) ou página 1 se só houver 1
                val targetEntry = if (entries.size >= 2) entries[1] else entries[0]
                val bytes = zip.getInputStream(targetEntry).readBytes()
                val img = mihon.core.archive.ImageDecoder.decode(bytes) ?: return null
                SampleData(
                    mangaTitle = mangaTitle,
                    chapterName = chapName,
                    pageName = targetEntry.name,
                    originalBytes = bytes,
                    originalImg = img,
                )
            }
        }.getOrNull()
    }

    private fun buildTabsWithProcessedImages(data: SampleData) {
        statusLabel.text = "Processando resoluções (Original, 2x HD 64-bit, 3x Ultra 4K, Nítido e Bilinear)..."
        progressBar.isVisible = true

        background({
            val orig = data.originalImg

            // 1. Zoom Convencional (Bilinear 2x)
            val bilinear2x = scaleImageFast(orig, orig.width * 2, orig.height * 2)

            // 2. AMD FSR 1.0 (EASU 12-tap + RCAS Anti-Aliasing de Bordas)
            val fsr2x = MangaImageEnhancer.scale(
                src = orig,
                targetW = orig.width * 2,
                targetH = orig.height * 2,
                quality = MangaImageEnhancer.QualityMode.FSR_EASU,
            )

            // 3. 2x Super HD 64-bit (FSR EASU + Anime4K + Vibrance + Contrast)
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

            // Recortes de zoom de alta densidade (crop central)
            val cropW = min(360, orig.width)
            val cropH = min(360, orig.height)
            val cropX = (orig.width - cropW) / 2
            val cropY = (orig.height - cropH) / 2

            val cropOrigBilinear = bilinear2x.getSubimage(cropX * 2, cropY * 2, cropW * 2, cropH * 2)
            val cropFsr2x = fsr2x.getSubimage(cropX * 2, cropY * 2, cropW * 2, cropH * 2)
            val cropHd2x = hd64_2x.getSubimage(cropX * 2, cropY * 2, cropW * 2, cropH * 2)

            listOf(
                "Comparador Split (FSR vs Comum)" to createSplitPanel(cropOrigBilinear, cropFsr2x),
                "AMD FSR 1.0 (Anti-Aliasing)" to createImageViewer(fsr2x, "AMD FSR 1.0 (EASU 12-Tap Direcional + RCAS — Bordas Limpas Sem Serrilhado) — ${fsr2x.width}x${fsr2x.height}"),
                "2x Super HD 64-bit" to createImageViewer(hd64_2x, "2x Super HD (FSR + Traço Vetorial Anime4K + Cores Vivas) — ${hd64_2x.width}x${hd64_2x.height}"),
                "3x Full HD Ultra" to createImageViewer(hd64_3x, "3x Full HD Ultra 4K (Densidade Máxima) — ${hd64_3x.width}x${hd64_3x.height}"),
                "2x Lanczos-3 Nítido" to createImageViewer(sharp2x, "2x Lanczos-3 Fiel Nítido — ${sharp2x.width}x${sharp2x.height}"),
                "Zoom Comum (Bilinear)" to createImageViewer(bilinear2x, "Bilinear Convencional (Sem IA / Embaçado / Serrilhado) — ${bilinear2x.width}x${bilinear2x.height}"),
                "Original (1x)" to createImageViewer(orig, "Original sem modificações — ${orig.width}x${orig.height}"),
            )
        }) { result ->
            progressBar.isVisible = false
            result.onSuccess { tabList ->
                tabs.removeAll()
                tabList.forEach { (title, comp) ->
                    tabs.addTab(title, comp)
                }
                statusLabel.text = "Exibindo '${data.mangaTitle}' — ${data.chapterName} (${data.pageName}) | ${data.originalImg.width}x${data.originalImg.height} original"
            }.onFailure {
                statusLabel.text = "Erro no processamento: ${it.message}"
            }
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

    private fun createSplitPanel(beforeBilinear: BufferedImage, afterFsr: BufferedImage): JPanel {
        return JPanel(BorderLayout()).apply {
            background = Theme.BG_APP

            val banner = JPanel(BorderLayout()).apply {
                background = Theme.BG_SURFACE
                border = BorderFactory.createEmptyBorder(8, 16, 8, 16)
                add(
                    JLabel("🔍 Comparação de Bordas & Detalhes Finos (Recorte Central 200%)").apply {
                        font = font.deriveFont(Font.BOLD, 13f)
                        foreground = Theme.TEXT_PRIMARY
                    },
                    BorderLayout.WEST,
                )
                add(
                    JLabel("⬅ Esquerda: Zoom Convencional (Pixelado/Bilinear)  │  Direita: AMD FSR 1.0 (EASU Anti-Serrilhado) ➡").apply {
                        font = font.deriveFont(Font.BOLD, 12f)
                        foreground = Theme.ACCENT_HOVER
                    },
                    BorderLayout.EAST,
                )
            }
            add(banner, BorderLayout.NORTH)

            val split = JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                createImageViewer(beforeBilinear, "Zoom Convencional (Bilinear — Linhas borradas e serrilhadas)"),
                createImageViewer(afterFsr, "AMD FSR 1.0 (EASU 12-Tap Direcional — Bordas curvas suaves sem jaggies)"),
            ).apply {
                resizeWeight = 0.5
                dividerSize = 8
                border = null
            }
            add(split, BorderLayout.CENTER)
        }
    }
}
