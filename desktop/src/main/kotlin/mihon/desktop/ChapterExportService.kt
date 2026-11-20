package mihon.desktop

import mihon.core.archive.ArchiveReader
import mihon.core.archive.ArchiveReaderFactory
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar

/**
 * Serviço de exportação e conversão de capítulos para formatos portáteis de e-Readers e Desktop:
 * - **PDF 1.4 Nativo** (streaming página a página com `/DCTDecode`, baixíssimo uso de RAM)
 * - **EPUB 3.0 Fixed-Layout** (compatível com Kindle via Send-to-Kindle, Kobo, Calibre e Foliate)
 * - Suporte a modo **e-Ink (Tons de Cinza 8-bit)** para redução de tamanho e contraste em telas e-Paper.
 */
object ChapterExportService {

    enum class ExportFormat(val extension: String, val displayName: String) {
        PDF("pdf", "Documento PDF (.pdf)"),
        EPUB("epub", "E-Book EPUB 3 (.epub)"),
    }

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            bytesWritten += len
        }

        fun writeAscii(text: String) {
            val bytes = text.toByteArray(StandardCharsets.ISO_8859_1)
            write(bytes, 0, bytes.size)
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    /**
     * Converte um [BufferedImage] em bytes JPEG (colorido ou tons de cinza para e-Ink) e libera o bitmap imediatamente.
     */
    private fun encodeJpegPage(
        sourceImg: BufferedImage,
        grayscale: Boolean,
        quality: Float = 0.88f,
    ): Triple<ByteArray, Int, Int> {
        val targetImg = if (grayscale && sourceImg.type != BufferedImage.TYPE_BYTE_GRAY) {
            val gray = BufferedImage(sourceImg.width, sourceImg.height, BufferedImage.TYPE_BYTE_GRAY)
            val g = gray.createGraphics()
            try {
                g.drawImage(sourceImg, 0, 0, null)
            } finally {
                g.dispose()
            }
            sourceImg.flush()
            gray
        } else if (!grayscale && sourceImg.colorModel.hasAlpha()) {
            val rgb = BufferedImage(sourceImg.width, sourceImg.height, BufferedImage.TYPE_INT_RGB)
            val g = rgb.createGraphics()
            try {
                g.color = Color.WHITE
                g.fillRect(0, 0, rgb.width, rgb.height)
                g.drawImage(sourceImg, 0, 0, null)
            } finally {
                g.dispose()
            }
            sourceImg.flush()
            rgb
        } else {
            sourceImg
        }

        val w = targetImg.width
        val h = targetImg.height
        val baos = ByteArrayOutputStream(256 * 1024)
        val writers = ImageIO.getImageWritersByFormatName("jpeg")
        if (writers.hasNext()) {
            val writer = writers.next()
            try {
                val param = writer.defaultWriteParam.apply {
                    if (canWriteCompressed()) {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = quality.coerceIn(0.5f, 0.98f)
                    }
                }
                ImageIO.createImageOutputStream(baos).use { ios ->
                    writer.output = ios
                    writer.write(null, IIOImage(targetImg, null, null), param)
                }
            } finally {
                writer.dispose()
            }
        } else {
            ImageIO.write(targetImg, "jpg", baos)
        }
        targetImg.flush()
        return Triple(baos.toByteArray(), w, h)
    }

    /**
     * Exporta as páginas de [reader] para um arquivo `.pdf` nativo página por página sem acumular imagens na RAM.
     */
    fun exportToPdf(
        reader: ArchiveReader,
        outputFile: File,
        grayscale: Boolean = false,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val pages = reader.pages
        val pageCount = pages.size
        require(pageCount > 0) { "O capítulo não contém páginas para exportar." }
        outputFile.parentFile?.mkdirs()

        // Reservamos IDs de objetos PDF:
        // 1 = Catalog, 2 = Pages, e para cada página i (0..pageCount-1):
        // imgObjId = 3 + i * 3
        // contentObjId = 4 + i * 3
        // pageObjId = 5 + i * 3
        val totalObjects = 2 + pageCount * 3
        val offsets = LongArray(totalObjects + 1)
        val pageObjIds = IntArray(pageCount)

        CountingOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { out ->
            out.writeAscii("%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n")

            for (i in 0 until pageCount) {
                onProgress(i + 1, pageCount)
                val rawImg = reader.getPageImage(pages[i], maxDim = 2200)
                    ?: error("Não foi possível decodificar a página ${i + 1}")
                val (jpegBytes, w, h) = encodeJpegPage(rawImg, grayscale = grayscale)

                val imgObjId = 3 + i * 3
                val contentObjId = 4 + i * 3
                val pageObjId = 5 + i * 3
                pageObjIds[i] = pageObjId

                val colorSpace = if (grayscale) "/DeviceGray" else "/DeviceRGB"

                // 1. Image XObject
                offsets[imgObjId] = out.bytesWritten
                out.writeAscii(
                    "$imgObjId 0 obj\n" +
                        "<< /Type /XObject /Subtype /Image /Width $w /Height $h " +
                        "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpegBytes.size} >>\n" +
                        "stream\n",
                )
                out.write(jpegBytes, 0, jpegBytes.size)
                out.writeAscii("\nendstream\nendobj\n")

                // 2. Content Stream
                val contentCmd = "q $w 0 0 $h 0 0 cm /Im0 Do Q\n"
                val contentBytes = contentCmd.toByteArray(StandardCharsets.ISO_8859_1)
                offsets[contentObjId] = out.bytesWritten
                out.writeAscii("$contentObjId 0 obj\n<< /Length ${contentBytes.size} >>\nstream\n")
                out.write(contentBytes, 0, contentBytes.size)
                out.writeAscii("endstream\nendobj\n")

                // 3. Page Object
                offsets[pageObjId] = out.bytesWritten
                out.writeAscii(
                    "$pageObjId 0 obj\n" +
                        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $w $h] " +
                        "/Resources << /XObject << /Im0 $imgObjId 0 R >> >> " +
                        "/Contents $contentObjId 0 R >>\n" +
                        "endobj\n",
                )
            }

            // Objeto 1: Catalog
            offsets[1] = out.bytesWritten
            out.writeAscii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

            // Objeto 2: Pages
            offsets[2] = out.bytesWritten
            val kidsRef = pageObjIds.joinToString(" ") { "$it 0 R" }
            out.writeAscii("2 0 obj\n<< /Type /Pages /Kids [ $kidsRef ] /Count $pageCount >>\nendobj\n")

            // Tabela XREF
            val xrefOffset = out.bytesWritten
            out.writeAscii("xref\n0 ${totalObjects + 1}\n")
            out.writeAscii("0000000000 65535 f \n")
            for (objId in 1..totalObjects) {
                out.writeAscii("%010d 00000 n \n".format(offsets[objId]))
            }

            out.writeAscii(
                "trailer\n<< /Size ${totalObjects + 1} /Root 1 0 R >>\n" +
                    "startxref\n$xrefOffset\n%%EOF\n",
            )
        }
    }

    /**
     * Exporta as páginas de [reader] para um arquivo `.epub` (EPUB 3.0) compatível com e-Readers.
     */
    fun exportToEpub(
        mangaTitle: String,
        chapterTitle: String,
        author: String?,
        reader: ArchiveReader,
        outputFile: File,
        grayscale: Boolean = false,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val pages = reader.pages
        val pageCount = pages.size
        require(pageCount > 0) { "O capítulo não contém páginas para exportar." }
        outputFile.parentFile?.mkdirs()

        val bookId = "urn:uuid:${UUID.randomUUID()}"
        val fullTitle = "$mangaTitle - $chapterTitle"
        val creator = author?.takeIf { it.isNotBlank() } ?: "Mihon Linux"

        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zip ->
            // 1. mimetype (sem compressão, conforme especificação EPUB)
            val mimeBytes = "application/epub+zip".toByteArray(StandardCharsets.US_ASCII)
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimeBytes.size.toLong()
                compressedSize = mimeBytes.size.toLong()
                val crc = CRC32()
                crc.update(mimeBytes)
                this.crc = crc.value
            }
            zip.putNextEntry(mimeEntry)
            zip.write(mimeBytes)
            zip.closeEntry()

            // 2. META-INF/container.xml
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""".toByteArray(StandardCharsets.UTF_8),
            )
            zip.closeEntry()

            // 3. Páginas e Imagens (streaming página a página)
            for (i in 0 until pageCount) {
                onProgress(i + 1, pageCount)
                val pageNum = i + 1
                val rawImg = reader.getPageImage(pages[i], maxDim = 2200)
                    ?: error("Não foi possível decodificar a página $pageNum")
                val (jpegBytes, w, h) = encodeJpegPage(rawImg, grayscale = grayscale)

                val imgRelPath = "images/page_%04d.jpg".format(pageNum)
                zip.putNextEntry(ZipEntry("OEBPS/$imgRelPath"))
                zip.write(jpegBytes)
                zip.closeEntry()

                val xhtml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head>
  <meta charset="UTF-8"/>
  <title>${escapeXml(fullTitle)} - Página $pageNum</title>
  <meta name="viewport" content="width=$w, height=$h"/>
  <style type="text/css">
    html, body { margin: 0; padding: 0; background-color: #000000; text-align: center; height: 100%; }
    img { max-width: 100%; max-height: 100%; object-fit: contain; display: block; margin: 0 auto; }
  </style>
</head>
<body>
  <img src="$imgRelPath" alt="Página $pageNum"/>
</body>
</html>"""
                zip.putNextEntry(ZipEntry("OEBPS/page_%04d.xhtml".format(pageNum)))
                zip.write(xhtml.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }

            // 4. OEBPS/nav.xhtml
            val navItems = (1..pageCount).joinToString("\n") { p ->
                """      <li><a href="page_%04d.xhtml">Página $p</a></li>""".format(p)
            }
            val navXhtml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><meta charset="UTF-8"/><title>${escapeXml(fullTitle)}</title></head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>${escapeXml(fullTitle)}</h1>
    <ol>
$navItems
    </ol>
  </nav>
</body>
</html>"""
            zip.putNextEntry(ZipEntry("OEBPS/nav.xhtml"))
            zip.write(navXhtml.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            // 5. OEBPS/content.opf
            val manifestItems = buildString {
                appendLine("""    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
                for (p in 1..pageCount) {
                    val coverProp = if (p == 1) """ properties="cover-image"""" else ""
                    appendLine("""    <item id="img_%04d" href="images/page_%04d.jpg" media-type="image/jpeg"$coverProp/>""".format(p, p))
                    appendLine("""    <item id="page_%04d" href="page_%04d.xhtml" media-type="application/xhtml+xml"/>""".format(p, p))
                }
            }
            val spineItems = (1..pageCount).joinToString("\n") { p ->
                """    <itemref idref="page_%04d"/>""".format(p)
            }
            val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="BookId" dir="rtl">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="BookId">$bookId</dc:identifier>
    <dc:title>${escapeXml(fullTitle)}</dc:title>
    <dc:creator>${escapeXml(creator)}</dc:creator>
    <dc:language>pt-BR</dc:language>
  </metadata>
  <manifest>
$manifestItems  </manifest>
  <spine page-progression-direction="rtl">
$spineItems
  </spine>
</package>"""
            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(opf.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}

/**
 * Diálogo modal de exportação de capítulos para PDF ou EPUB.
 * Baixa automaticamente capítulos online caso ainda não estejam salvos localmente.
 */
class ExportChapterDialog(
    owner: Window?,
    private val ctx: AppContext,
    private val manga: Manga,
    private val chaptersToExport: List<Chapter>,
) : JDialog(owner, "Exportar Capítulos (PDF / EPUB)", ModalityType.APPLICATION_MODAL) {

    private val formatCombo = mihon.desktop.ui.components.MihonDropdown(
        ChapterExportService.ExportFormat.entries.map { it.displayName }.toTypedArray(),
    ).apply {
        preferredSize = Dimension(240, Theme.CONTROL_HEIGHT_COMPACT)
    }

    private val grayscaleCheck = mihon.desktop.ui.components.MihonCheckbox(
        "Otimizar para e-Reader / Kindle (Tons de cinza 8-bit)",
    )

    private var targetDir = File(System.getProperty("user.home"), "Downloads").let {
        if (it.exists()) it else File(System.getProperty("user.home"))
    }

    private val dirLabel = JLabel(targetDir.absolutePath).apply {
        font = Theme.FONT_MONO.deriveFont(Font.PLAIN, 11.5f)
        foreground = Theme.TEXT_SECONDARY
    }

    private val progressBar = JProgressBar(0, 100).apply {
        isStringPainted = true
        isVisible = false
    }

    private val btnExport = ModernButton(
        text = "Exportar Agora",
        icon = ModernIcons.downloads(14) { Color.WHITE },
        variant = ModernButton.Variant.PRIMARY,
    )

    init {
        setSize(520, 330)
        setLocationRelativeTo(owner)
        layout = BorderLayout()

        val root = JPanel().apply {
            background = Theme.BG_SURFACE
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(20, 24, 20, 24)
        }

        val headerTitle = JLabel("Exportar ${chaptersToExport.size} capítulo(s) de '${manga.title.take(36)}'").apply {
            font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 16f)
            foreground = Theme.TEXT_PRIMARY
            alignmentX = 0f
        }
        val headerSub = JLabel("Gere arquivos portáteis prontos para Kindle, Kobo ou leitores de PDF.").apply {
            font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12f)
            foreground = Theme.TEXT_MUTED
            alignmentX = 0f
        }

        root.add(headerTitle)
        root.add(Box.createVerticalStrut(4))
        root.add(headerSub)
        root.add(Box.createVerticalStrut(18))

        val formatRow = JPanel(BorderLayout(12, 0)).apply {
            isOpaque = false
            alignmentX = 0f
            maximumSize = Dimension(Int.MAX_VALUE, 36)
            add(JLabel("Formato de saída:").apply {
                foreground = Theme.TEXT_PRIMARY
                font = Theme.FONT_UI.deriveFont(Font.BOLD, 13f)
            }, BorderLayout.WEST)
            add(formatCombo, BorderLayout.EAST)
        }
        root.add(formatRow)
        root.add(Box.createVerticalStrut(12))

        grayscaleCheck.alignmentX = 0f
        root.add(grayscaleCheck)
        root.add(Box.createVerticalStrut(14))

        val folderRow = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false
            alignmentX = 0f
            maximumSize = Dimension(Int.MAX_VALUE, 34)
            add(dirLabel, BorderLayout.CENTER)
            add(ModernButton("Escolher Pasta…", variant = ModernButton.Variant.SURFACE, compact = true).apply {
                addActionListener {
                    val chooser = JFileChooser(targetDir).apply {
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        dialogTitle = "Selecionar pasta de destino"
                    }
                    if (chooser.showOpenDialog(this@ExportChapterDialog) == JFileChooser.APPROVE_OPTION) {
                        targetDir = chooser.selectedFile
                        dirLabel.text = targetDir.absolutePath
                    }
                }
            }, BorderLayout.EAST)
        }
        root.add(folderRow)
        root.add(Box.createVerticalStrut(16))

        progressBar.alignmentX = 0f
        root.add(progressBar)
        root.add(Box.createVerticalGlue())

        val btnCancel = ModernButton(
            text = "Fechar",
            variant = ModernButton.Variant.SURFACE,
        ).apply {
            addActionListener { dispose() }
        }

        btnExport.addActionListener { startExport() }

        val footer = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply {
            isOpaque = false
            alignmentX = 0f
            add(btnCancel)
            add(btnExport)
        }
        root.add(footer)

        add(root, BorderLayout.CENTER)
    }

    private fun startExport() {
        val format = ChapterExportService.ExportFormat.entries[formatCombo.selectedIndex]
        val grayscale = grayscaleCheck.isSelected
        btnExport.isEnabled = false
        progressBar.value = 0
        progressBar.string = "Preparando exportação…"
        progressBar.isVisible = true

        background({
            val exportedFiles = mutableListOf<File>()
            chaptersToExport.forEachIndexed { idx, ch ->
                val cbzFile = if (ctx.service.isChapterDownloaded(manga, ch)) {
                    ctx.service.chapterCbzFile(manga, ch)
                } else {
                    val src = ctx.sourceFor(manga.source)
                        ?: error("Fonte não encontrada para baixar '${ch.name}' antes da exportação.")
                    ctx.service.downloadChapter(manga, ch, src) { p, total ->
                        javax.swing.SwingUtilities.invokeLater {
                            progressBar.maximum = total.coerceAtLeast(1)
                            progressBar.value = p
                            progressBar.string = "Baixando '${ch.name}' ($p/$total)…"
                        }
                    }
                }

                val safeManga = manga.title.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").trim()
                val safeChap = ch.name.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").trim()
                val outFile = File(targetDir, "${safeManga} - ${safeChap}.${format.extension}")

                ArchiveReaderFactory.of(cbzFile).use { reader ->
                    when (format) {
                        ChapterExportService.ExportFormat.PDF -> {
                            ChapterExportService.exportToPdf(reader, outFile, grayscale) { p, total ->
                                javax.swing.SwingUtilities.invokeLater {
                                    progressBar.maximum = total.coerceAtLeast(1)
                                    progressBar.value = p
                                    progressBar.string = "Convertendo PDF (${idx + 1}/${chaptersToExport.size}): pág. $p/$total"
                                }
                            }
                        }
                        ChapterExportService.ExportFormat.EPUB -> {
                            ChapterExportService.exportToEpub(
                                mangaTitle = manga.title,
                                chapterTitle = ch.name,
                                author = manga.author,
                                reader = reader,
                                outputFile = outFile,
                                grayscale = grayscale,
                            ) { p, total ->
                                javax.swing.SwingUtilities.invokeLater {
                                    progressBar.maximum = total.coerceAtLeast(1)
                                    progressBar.value = p
                                    progressBar.string = "Empacotando EPUB (${idx + 1}/${chaptersToExport.size}): pág. $p/$total"
                                }
                            }
                        }
                    }
                }
                exportedFiles.add(outFile)
            }
            exportedFiles
        }) { result ->
            btnExport.isEnabled = true
            result.onSuccess { files ->
                progressBar.value = progressBar.maximum
                progressBar.string = "Exportação concluída (${files.size} arquivo(s))!"
                ctx.setStatus("${files.size} capítulo(s) exportado(s) para ${targetDir.absolutePath}")
                JOptionPane.showMessageDialog(
                    this,
                    "${files.size} arquivo(s) ${format.extension.uppercase()} gerado(s) com sucesso em:\n${targetDir.absolutePath}",
                    "Exportação Concluída",
                    JOptionPane.INFORMATION_MESSAGE,
                )
                dispose()
            }.onFailure { err ->
                progressBar.isVisible = false
                JOptionPane.showMessageDialog(
                    this,
                    "Erro ao exportar capítulo(s):\n${err.message}",
                    "Erro na Exportação",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }
}
