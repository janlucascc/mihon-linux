package mihon.desktop

import mihon.core.archive.ArchiveReaderFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChapterExportServiceTest {

    @TempDir
    lateinit var tmp: File

    private fun createPng(w: Int = 10, h: Int = 10): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "png", baos)
        return baos.toByteArray()
    }

    private fun createSampleCbz(name: String, pageCount: Int): File {
        val file = File(tmp, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for (i in 1..pageCount) {
                zip.putNextEntry(ZipEntry("p%03d.png".format(i)))
                zip.write(createPng())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun testExportToPdfColorAndGrayscale() {
        val cbz = createSampleCbz("ch1.cbz", 3)
        val reader = ArchiveReaderFactory.of(cbz)

        val pdfColor = File(tmp, "ch1_color.pdf")
        ChapterExportService.exportToPdf(reader, pdfColor, grayscale = false)

        assertTrue(pdfColor.exists())
        assertTrue(pdfColor.length() > 200L)
        val header = pdfColor.inputStream().use { String(it.readNBytes(8)) }
        assertEquals("%PDF-1.4", header)

        val pdfGray = File(tmp, "ch1_gray.pdf")
        ChapterExportService.exportToPdf(reader, pdfGray, grayscale = true)
        assertTrue(pdfGray.exists())
        assertTrue(pdfGray.length() > 200L)

        reader.close()
    }

    @Test
    fun testExportToEpubStructure() {
        val cbz = createSampleCbz("ch2.cbz", 2)
        val reader = ArchiveReaderFactory.of(cbz)

        val epub = File(tmp, "ch2.epub")
        ChapterExportService.exportToEpub(
            mangaTitle = "One Piece",
            chapterTitle = "Capítulo 1000",
            author = "Eiichiro Oda",
            reader = reader,
            outputFile = epub,
            grayscale = false,
        )

        assertTrue(epub.exists())
        assertTrue(epub.length() > 500L)

        // Validate standard EPUB zip archive structure
        ZipFile(epub).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toList()
            assertTrue(entries.contains("mimetype"))
            assertTrue(entries.contains("META-INF/container.xml"))
            assertTrue(entries.contains("OEBPS/content.opf"))
            assertTrue(entries.contains("OEBPS/nav.xhtml"))
            assertTrue(entries.contains("OEBPS/page_0001.xhtml"))
            assertTrue(entries.contains("OEBPS/page_0002.xhtml"))
            assertTrue(entries.contains("OEBPS/images/page_0001.jpg"))
            assertTrue(entries.contains("OEBPS/images/page_0002.jpg"))

            // mimetype must be uncompressed and first entry
            val firstEntry = zip.entries().nextElement()
            assertEquals("mimetype", firstEntry.name)
            assertEquals(ZipEntry.STORED, firstEntry.method)
            val mimetypeContent = zip.getInputStream(firstEntry).bufferedReader().readText()
            assertEquals("application/epub+zip", mimetypeContent)

            // Validate OPF metadata
            val opfContent = zip.getInputStream(zip.getEntry("OEBPS/content.opf")).bufferedReader().readText()
            assertTrue(opfContent.contains("One Piece - Capítulo 1000"))
            assertTrue(opfContent.contains("Eiichiro Oda"))
        }

        reader.close()
    }

    @Test
    fun testExportRejectsEmptyChapter() {
        val emptyCbz = File(tmp, "empty.cbz")
        ZipOutputStream(emptyCbz.outputStream()).close()
        val reader = ArchiveReaderFactory.of(emptyCbz)

        assertFailsWith<IllegalArgumentException> {
            ChapterExportService.exportToPdf(reader, File(tmp, "empty.pdf"))
        }

        assertFailsWith<IllegalArgumentException> {
            ChapterExportService.exportToEpub("Test", "0", null, reader, File(tmp, "empty.epub"))
        }

        reader.close()
    }
}
