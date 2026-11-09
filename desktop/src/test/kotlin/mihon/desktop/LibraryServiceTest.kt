package mihon.desktop

import kotlinx.coroutines.runBlocking
import mihon.data.database.DatabaseManager
import mihon.data.repository.ChapterRepositoryImpl
import mihon.data.repository.HistoryRepositoryImpl
import mihon.data.repository.MangaRepositoryImpl
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LibraryServiceTest {

    @TempDir
    lateinit var tmp: File

    private lateinit var history: HistoryRepositoryImpl
    private lateinit var service: LibraryService

    @BeforeEach
    fun setUp() {
        val db = DatabaseManager(File(tmp, "test.db")).apply { initialize() }
        history = HistoryRepositoryImpl(db)
        service = LibraryService(MangaRepositoryImpl(db), ChapterRepositoryImpl(db), history, File(tmp, "downloads"))
    }

    private fun png(): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(4, 6, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    private fun cbz(name: String, pages: Int, dir: File = tmp): File =
        File(dir, name).also { f ->
            ZipOutputStream(f.outputStream()).use { zip ->
                repeat(pages) { i ->
                    zip.putNextEntry(ZipEntry("%03d.png".format(i + 1)))
                    zip.write(png())
                    zip.closeEntry()
                }
            }
        }

    @Test
    fun `adds a cbz as manga with one chapter and is idempotent`() = runBlocking {
        val manga = service.addLocal(cbz("Berserk.cbz", 3))

        assertEquals("Berserk", manga.title)
        assertEquals(1, service.library().size)
        assertEquals(1, service.chaptersOf(manga.id).size)
        assertEquals(manga.id, service.addLocal(File(tmp, "Berserk.cbz")).id)
        assertEquals(1, service.library().size)
    }

    @Test
    fun `folder of archives becomes chapters in natural order`() = runBlocking {
        val dir = File(tmp, "Series").also { it.mkdirs() }
        cbz("ch 10.cbz", 1, dir); cbz("ch 2.cbz", 1, dir); cbz("ch 1.cbz", 1, dir)

        val manga = service.addLocal(dir)

        assertEquals(listOf("ch 1", "ch 2", "ch 10"), service.chaptersOf(manga.id).map { it.name })
    }

    @Test
    fun `folder of loose images is a single chapter`() = runBlocking {
        val dir = File(tmp, "Loose").also { it.mkdirs() }
        File(dir, "1.png").writeBytes(png()); File(dir, "2.png").writeBytes(png())

        val manga = service.addLocal(dir)

        assertEquals(listOf("Loose"), service.chaptersOf(manga.id).map { it.name })
    }

    @Test
    fun `rejects unsupported and empty sources without touching the library`() = runBlocking {
        val txt = File(tmp, "notes.txt").also { it.writeText("x") }
        assertFailsWith<IllegalArgumentException> { service.addLocal(txt) }
        assertFailsWith<IllegalArgumentException> { service.addLocal(cbz("empty.cbz", 0)) }
        assertTrue(service.library().isEmpty())
    }

    @Test
    fun `saveProgress stores page, marks read at last page and writes history`() = runBlocking {
        val manga = service.addLocal(cbz("Vagabond.cbz", 5))
        val chapter = service.chaptersOf(manga.id).single()

        service.saveProgress(chapter, page = 2, pageCount = 5)
        service.chaptersOf(manga.id).single().let {
            assertEquals(2, it.lastPageRead)
            assertEquals(false, it.read)
        }
        assertEquals(1, history.getAllHistory().size)

        service.saveProgress(chapter, page = 4, pageCount = 5)
        service.chaptersOf(manga.id).single().let {
            assertEquals(4, it.lastPageRead)
            assertTrue(it.read)
        }
        assertEquals(1, history.getAllHistory().size)
    }

    @Test
    fun `removeFromLibrary unfavorites manga while preserving history data`() = runBlocking {
        val manga = service.addLocal(cbz("Gone.cbz", 2))
        service.removeFromLibrary(manga.id)
        assertTrue(service.library().isEmpty())
        // Chapters and manga record are preserved for reading history & statistics
        assertEquals(1, service.chaptersOf(manga.id).size)

        // Deleting completely removes from database
        service.deleteMangaCompletely(manga.id)
        assertTrue(service.chaptersOf(manga.id).isEmpty())
    }

    @Test
    fun `backup export and restore preserves library`() = runBlocking {
        val dbFile = File(tmp, "test.db")
        val backupZip = File(tmp, "backup.zip")
        val manga = service.addLocal(cbz("Saved.cbz", 2))
        service.setCategory(manga.id, LibraryCategory.COMPLETED)

        BackupManager.exportDatabase(dbFile, backupZip)
        service.removeFromLibrary(manga.id)
        assertTrue(service.library().isEmpty())

        BackupManager.restoreDatabase(backupZip, dbFile)
        val restored = service.library().single()
        assertEquals("Saved", restored.title)
        assertEquals(LibraryCategory.COMPLETED, restored.status)
    }
}
