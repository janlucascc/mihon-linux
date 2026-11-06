package mihon.data.repository

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.coroutines.runBlocking
import mihon.data.database.DatabaseManager
import mihon.data.database.tables.ChapterTable
import mihon.data.database.tables.HistoryTable
import mihon.data.database.tables.MangaTable
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.HistoryUpdate
import java.io.File
import java.sql.DriverManager
import kotlin.time.Instant

class M1LegacyMigrationAdversarialTest {

    private lateinit var tempFile: File

    @BeforeEach
    fun createTempFile() {
        tempFile = File.createTempFile("legacy-migration-test-", ".db")
    }

    @AfterEach
    fun cleanUp() {
        if (tempFile.exists()) {
            tempFile.delete()
        }
        File(tempFile.absolutePath + "-wal").delete()
        File(tempFile.absolutePath + "-shm").delete()
    }

    private fun createLegacyDatabase(populateData: Boolean) {
        val jdbcUrl = "jdbc:sqlite:${tempFile.absolutePath}"
        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE manga (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        title TEXT NOT NULL,
                        url TEXT UNIQUE NOT NULL,
                        cover_url TEXT,
                        author TEXT,
                        artist TEXT,
                        description TEXT,
                        status INTEGER DEFAULT 0,
                        last_updated INTEGER DEFAULT 0
                    );
                """.trimIndent())

                stmt.execute("""
                    CREATE TABLE chapter (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        manga_id INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        url TEXT NOT NULL,
                        volume INTEGER DEFAULT 0,
                        chapter INTEGER DEFAULT 0,
                        FOREIGN KEY(manga_id) REFERENCES manga(id)
                    );
                """.trimIndent())

                if (populateData) {
                    stmt.execute("""
                        INSERT INTO manga (id, title, url, cover_url, author, artist, description, status, last_updated)
                        VALUES (1, 'Legacy Berserk', '/legacy/berserk', 'https://covers.org/berserk.jpg', 'Kentaro Miura', 'Kentaro Miura', 'Dark fantasy epic', 2, 1600000000000);
                    """.trimIndent())

                    stmt.execute("""
                        INSERT INTO manga (id, title, url, cover_url, author, artist, description, status, last_updated)
                        VALUES (2, 'Legacy JoJo 🧛', '/legacy/jojo', NULL, 'Hirohiko Araki', NULL, NULL, 1, 1700000000000);
                    """.trimIndent())

                    stmt.execute("""
                        INSERT INTO chapter (id, manga_id, title, url, volume, chapter)
                        VALUES (10, 1, 'The Black Swordsman', '/legacy/berserk/1', 1, 1);
                    """.trimIndent())

                    stmt.execute("""
                        INSERT INTO chapter (id, manga_id, title, url, volume, chapter)
                        VALUES (11, 1, 'The Brand', '/legacy/berserk/2', 1, 2);
                    """.trimIndent())

                    stmt.execute("""
                        INSERT INTO chapter (id, manga_id, title, url, volume, chapter)
                        VALUES (20, 2, 'Dio the Invader 🧛', '/legacy/jojo/1', 0, 1);
                    """.trimIndent())
                }
            }
        }
    }

    @Test
    fun testPopulatedLegacyMigrationUpgradesCleanlyWithoutDataLoss() = runBlocking {
        createLegacyDatabase(populateData = true)

        val dbManager = DatabaseManager(tempFile)
        // Run migration
        dbManager.initialize()

        // 1. Verify schema: legacy tables dropped, modern tables exist
        val jdbcUrl = "jdbc:sqlite:${tempFile.absolutePath}"
        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN ('_legacy_manga', '_legacy_chapter')").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt(1), "Temporary legacy tables must be dropped")
                }

                // Verify modern columns in manga table
                stmt.executeQuery("PRAGMA table_info(manga)").use { rs ->
                    val columns = mutableListOf<String>()
                    while (rs.next()) {
                        columns.add(rs.getString("name"))
                    }
                    assertTrue(columns.contains("source"), "Modern manga table must have 'source'")
                    assertTrue(columns.contains("genre"), "Modern manga table must have 'genre'")
                    assertTrue(columns.contains("update_strategy"), "Modern manga table must have 'update_strategy'")
                    assertTrue(columns.contains("thumbnail_url"), "Modern manga table must have 'thumbnail_url'")
                    assertTrue(columns.contains("last_update"), "Modern manga table must have 'last_update'")
                    assertFalse(columns.contains("cover_url"), "Old 'cover_url' must not exist in modern manga table")
                    assertFalse(columns.contains("last_updated"), "Old 'last_updated' must not exist in modern manga table")
                }

                // Verify modern columns in chapter table
                stmt.executeQuery("PRAGMA table_info(chapter)").use { rs ->
                    val columns = mutableListOf<String>()
                    while (rs.next()) {
                        columns.add(rs.getString("name"))
                    }
                    assertTrue(columns.contains("chapter_number"), "Modern chapter table must have 'chapter_number'")
                    assertTrue(columns.contains("name"), "Modern chapter table must have 'name'")
                    assertFalse(columns.contains("chapter"), "Old 'chapter' column must be replaced")
                }
            }
        }

        // 2. Verify Repositories can query migrated data cleanly
        val mangaRepo = MangaRepositoryImpl(dbManager)
        val chapterRepo = ChapterRepositoryImpl(dbManager)
        val historyRepo = HistoryRepositoryImpl(dbManager)

        val allManga = mangaRepo.getAll()
        assertEquals(2, allManga.size)

        // Verify Manga 1 data preservation & column translation
        val berserk = mangaRepo.getMangaById(1L)
        assertNotNull(berserk)
        assertEquals("Legacy Berserk", berserk!!.title)
        assertEquals("/legacy/berserk", berserk.url)
        assertEquals("https://covers.org/berserk.jpg", berserk.thumbnailUrl)
        assertEquals("Kentaro Miura", berserk.author)
        assertEquals("Kentaro Miura", berserk.artist)
        assertEquals("Dark fantasy epic", berserk.description)
        assertEquals(2L, berserk.status)
        assertEquals(Instant.fromEpochMilliseconds(1600000000000L), berserk.lastUpdate)
        assertEquals(0L, berserk.source)
        assertFalse(berserk.favorite)
        assertEquals(UpdateStrategy.ALWAYS_UPDATE, berserk.updateStrategy)
        assertFalse(berserk.initialized)

        // Verify Manga 2 with null fields and UTF-8
        val jojo = mangaRepo.getMangaById(2L)
        assertNotNull(jojo)
        assertEquals("Legacy JoJo 🧛", jojo!!.title)
        assertEquals("/legacy/jojo", jojo.url)
        assertNull(jojo.thumbnailUrl)
        assertEquals("Hirohiko Araki", jojo.author)
        assertNull(jojo.artist)
        assertNull(jojo.description)
        assertEquals(1L, jojo.status)
        assertEquals(Instant.fromEpochMilliseconds(1700000000000L), jojo.lastUpdate)

        // Verify Chapter data preservation & column translation
        val berserkChapters = chapterRepo.getChapterByMangaId(1L)
        assertEquals(2, berserkChapters.size)

        val ch10 = chapterRepo.getChapterById(10L)
        assertNotNull(ch10)
        assertEquals(1L, ch10!!.mangaId)
        assertEquals("The Black Swordsman", ch10.name)
        assertEquals("/legacy/berserk/1", ch10.url)
        assertEquals(1.0, ch10.chapterNumber)
        assertFalse(ch10.read)
        assertFalse(ch10.bookmark)
        assertEquals(0, ch10.lastPageRead)

        val ch11 = chapterRepo.getChapterById(11L)
        assertNotNull(ch11)
        assertEquals("The Brand", ch11!!.name)
        assertEquals(2.0, ch11.chapterNumber)

        val ch20 = chapterRepo.getChapterById(20L)
        assertNotNull(ch20)
        assertEquals(2L, ch20!!.mangaId)
        assertEquals("Dio the Invader 🧛", ch20.name)
        assertEquals(1.0, ch20.chapterNumber)

        // 3. Verify ongoing write operations on migrated database
        // Insert new chapter
        val newChapter = Chapter.create().copy(
            mangaId = 1L,
            url = "/legacy/berserk/3",
            name = "Guardians of Desire",
            chapterNumber = 3.0,
        )
        val insertedChapters = chapterRepo.updateFromRemote(emptyList(), listOf(newChapter), emptyList())
        assertEquals(1, insertedChapters.size)
        val ch3Id = insertedChapters[0].id
        assertTrue(ch3Id > 20L)

        // Upsert history for migrated chapter
        val history = historyRepo.upsert(
            HistoryUpdate(
                chapterId = 10L,
                readAt = Instant.fromEpochMilliseconds(1700005000000L),
                sessionReadDuration = 1200L,
            ),
        )
        assertNotNull(historyRepo.getHistoryById(history.id))

        // Delete migrated manga and verify cascading delete
        mangaRepo.delete(2L)
        assertNull(mangaRepo.getMangaById(2L))
        assertNull(chapterRepo.getChapterById(20L))
    }

    @Test
    fun testEmptyLegacyMigrationInitializesCleanModernSchema() = runBlocking {
        createLegacyDatabase(populateData = false)

        val dbManager = DatabaseManager(tempFile)
        dbManager.initialize()

        val mangaRepo = MangaRepositoryImpl(dbManager)
        assertTrue(mangaRepo.getAll().isEmpty())

        // Verify insertion into cleanly migrated empty DB
        val newId = mangaRepo.insert(
            tachiyomi.domain.manga.model.Manga.create().copy(
                url = "/modern-1",
                title = "Modern Title",
                source = 99L,
            ),
        )
        assertTrue(newId > 0)
        val retrieved = mangaRepo.getMangaById(newId)
        assertNotNull(retrieved)
        assertEquals("Modern Title", retrieved!!.title)
        assertEquals(99L, retrieved.source)
    }

    @Test
    fun testDatabaseManagerInitializationIdempotence() = runBlocking {
        val dbManager = DatabaseManager(tempFile)
        // First initialization on clean DB
        dbManager.initialize()

        val mangaRepo = MangaRepositoryImpl(dbManager)
        val mangaId = mangaRepo.insert(
            tachiyomi.domain.manga.model.Manga.create().copy(url = "/idempotent", title = "Idempotent Manga"),
        )
        assertTrue(mangaId > 0)

        // Second initialization on already modern DB
        dbManager.initialize()

        // Third initialization
        dbManager.initialize()

        // Verify data intact and undisturbed
        val retrieved = mangaRepo.getMangaById(mangaId)
        assertNotNull(retrieved)
        assertEquals("Idempotent Manga", retrieved!!.title)
    }
}
