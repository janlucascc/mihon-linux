package mihon.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import mihon.core.common.extensions.EMPTY
import mihon.data.DatabaseTestBase
import mihon.data.database.tables.ChapterTable
import mihon.data.database.tables.HistoryTable
import mihon.data.database.tables.MangaTable
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PersistenceStressTest : DatabaseTestBase() {

    private lateinit var mangaRepository: MangaRepositoryImpl
    private lateinit var chapterRepository: ChapterRepositoryImpl
    private lateinit var historyRepository: HistoryRepositoryImpl

    @BeforeEach
    fun initRepos() {
        mangaRepository = MangaRepositoryImpl(databaseManager)
        chapterRepository = ChapterRepositoryImpl(databaseManager)
        historyRepository = HistoryRepositoryImpl(databaseManager)
    }

    @Test
    fun testConcurrentMangaInsertsAndReads() = runBlocking {
        val totalManga = 50
        val readCounter = AtomicInteger(0)

        withTimeout(30.seconds) {
            coroutineScope {
                // Launch 50 concurrent writers
                val writerJobs = (1..totalManga).map { i ->
                    async(Dispatchers.IO) {
                        mangaRepository.insert(
                            Manga.create().copy(
                                source = 100L,
                                url = "/manga/concurrent-$i",
                                title = "Concurrent Manga $i",
                                favorite = (i % 2 == 0),
                            ),
                        )
                    }
                }

                // Launch 25 concurrent readers querying database concurrently
                val readerJobs = (1..25).map {
                    async(Dispatchers.IO) {
                        for (j in 1..10) {
                            val all = mangaRepository.getAll()
                            val favs = mangaRepository.getFavorites()
                            if (all.isNotEmpty()) {
                                readCounter.incrementAndGet()
                            }
                        }
                    }
                }

                val insertedIds = writerJobs.awaitAll()
                readerJobs.awaitAll()

                assertEquals(totalManga, insertedIds.size)
                assertEquals(totalManga, insertedIds.distinct().size)

                val allMangas = mangaRepository.getAll()
                assertEquals(totalManga, allMangas.size)

                val favorites = mangaRepository.getFavorites()
                assertEquals(25, favorites.size)
            }
        }
    }

    @Test
    fun testConcurrentBatchUpdatesAndReads(): Unit = runBlocking {
        // Create 1 manga and 50 chapters
        val mangaId = mangaRepository.insert(
            Manga.create().copy(source = 1L, url = "/batch-manga", title = "Batch Manga"),
        )
        val initialChapters = (1..50).map { i ->
            Chapter.create().copy(
                mangaId = mangaId,
                url = "/chapter/$i",
                name = "Chapter $i",
                chapterNumber = i.toDouble(),
                read = false,
                bookmark = false,
            )
        }
        val insertedChapters = chapterRepository.updateFromRemote(emptyList(), initialChapters, emptyList())
        assertEquals(50, insertedChapters.size)

        withTimeout(30.seconds) {
            coroutineScope {
                // Launch 10 coroutines updating bookmark status concurrently
                val updateJobs = (1..10).map { round ->
                    async(Dispatchers.IO) {
                        val updates = insertedChapters.map { ch ->
                            ChapterUpdate(ch.id) {
                                bookmark = (round % 2 == 1)
                                read = (ch.id % 2L == 0L)
                            }
                        }
                        chapterRepository.updateAll(updates)
                    }
                }

                // Launch 10 coroutines reading summaries and lists concurrently
                val readerJobs = (1..10).map {
                    async(Dispatchers.IO) {
                        for (k in 1..5) {
                            val summary = chapterRepository.getChapterSummaryByMangaId(mangaId)
                            assertEquals(50L, summary.chapterCount)
                            assertEquals(50.0, summary.latestChapterNumber)
                            val list = chapterRepository.getChapterByMangaId(mangaId)
                            assertEquals(50, list.size)
                        }
                    }
                }

                updateJobs.awaitAll()
                readerJobs.awaitAll()
            }
        }
    }

    @Test
    fun testConcurrentHistoryDurationAccumulationAcrossChapters() = runBlocking {
        val mangaId = mangaRepository.insert(
            Manga.create().copy(source = 1L, url = "/manga/hist-acc", title = "Hist Acc Manga"),
        )
        val chapters = (1..30).map { i ->
            Chapter.create().copy(mangaId = mangaId, url = "/hist-ch/$i", name = "Chapter $i")
        }
        val insertedChapters = chapterRepository.updateFromRemote(emptyList(), chapters, emptyList())

        withTimeout(30.seconds) {
            coroutineScope {
                // 30 parallel coroutines on Dispatchers.IO, each upserting history for a chapter
                val jobs = insertedChapters.map { chapter ->
                    async(Dispatchers.IO) {
                        historyRepository.upsert(
                            HistoryUpdate(
                                chapterId = chapter.id,
                                readAt = Instant.fromEpochMilliseconds(5000L),
                                sessionReadDuration = 120L,
                            ),
                        )
                    }
                }
                val results = jobs.awaitAll()
                assertEquals(30, results.size)
                assertEquals(30, results.map { it.chapterId }.distinct().size)
            }
        }

        // Verify that all 30 chapters have exactly 1 history record with 120L duration
        val mangaHistory = historyRepository.getHistoryByMangaId(mangaId)
        assertEquals(30, mangaHistory.size)
        mangaHistory.forEach { h ->
            assertEquals(120L, h.readDuration)
            assertEquals(Instant.fromEpochMilliseconds(5000L), h.readAt)
        }
    }

    @Test
    fun testConcurrentHistoryUpsertsOnSameChapter() = runBlocking {
        val mangaId = mangaRepository.insert(
            Manga.create().copy(source = 1L, url = "/manga/single-hist", title = "Single Hist Manga"),
        )
        val inserted = chapterRepository.updateFromRemote(
            emptyList(),
            listOf(Chapter.create().copy(mangaId = mangaId, url = "/ch/single", name = "Ch Single")),
            emptyList(),
        )
        val chapterId = inserted.first().id

        // Pre-create initial history
        historyRepository.upsert(
            HistoryUpdate(chapterId = chapterId, readAt = Instant.fromEpochMilliseconds(1000L), sessionReadDuration = 50L),
        )

        val iterations = 20
        withTimeout(30.seconds) {
            coroutineScope {
                val jobs = (1..iterations).map { idx ->
                    async(Dispatchers.IO) {
                        historyRepository.upsert(
                            HistoryUpdate(
                                chapterId = chapterId,
                                readAt = Instant.fromEpochMilliseconds(1000L + idx),
                                sessionReadDuration = 10L,
                            ),
                        )
                    }
                }
                jobs.awaitAll()
            }
        }

        val finalHistory = historyRepository.getHistoryByChapterId(chapterId)
        assertNotNull(finalHistory)
        // Duration must be at least initial (50) + at least some accumulated sessions, and no errors
        assertTrue(finalHistory!!.readDuration >= 60L, "Expected duration to accumulate, got ${finalHistory.readDuration}")
    }

    @Test
    fun testDeepRelationalCascadeDeleteSingleManga() = runBlocking {
        // 1. Create Target Manga -> 50 Chapters -> 50 History records
        val targetMangaId = mangaRepository.insert(
            Manga.create().copy(source = 1L, url = "/manga/target", title = "Target Manga"),
        )
        val targetChapters = (1..50).map { i ->
            Chapter.create().copy(
                mangaId = targetMangaId,
                url = "/target/ch-$i",
                name = "Target Ch $i",
                chapterNumber = i.toDouble(),
            )
        }
        val insertedTargetChapters = chapterRepository.updateFromRemote(emptyList(), targetChapters, emptyList())
        assertEquals(50, insertedTargetChapters.size)

        insertedTargetChapters.forEachIndexed { idx, ch ->
            historyRepository.upsert(
                HistoryUpdate(
                    chapterId = ch.id,
                    readAt = Instant.fromEpochMilliseconds(1000L + idx),
                    sessionReadDuration = (idx + 1) * 10L,
                ),
            )
        }

        // 2. Create Control Manga -> 10 Chapters -> 10 History records
        val controlMangaId = mangaRepository.insert(
            Manga.create().copy(source = 1L, url = "/manga/control", title = "Control Manga"),
        )
        val controlChapters = (1..10).map { i ->
            Chapter.create().copy(
                mangaId = controlMangaId,
                url = "/control/ch-$i",
                name = "Control Ch $i",
                chapterNumber = i.toDouble(),
            )
        }
        val insertedControlChapters = chapterRepository.updateFromRemote(emptyList(), controlChapters, emptyList())
        assertEquals(10, insertedControlChapters.size)

        insertedControlChapters.forEachIndexed { idx, ch ->
            historyRepository.upsert(
                HistoryUpdate(
                    chapterId = ch.id,
                    readAt = Instant.fromEpochMilliseconds(2000L + idx),
                    sessionReadDuration = 50L,
                ),
            )
        }

        // Verify pre-delete counts via Exposed raw queries
        transaction(databaseManager.database) {
            assertEquals(2L, MangaTable.selectAll().count())
            assertEquals(60L, ChapterTable.selectAll().count())
            assertEquals(60L, HistoryTable.selectAll().count())
        }

        // 3. Delete Target Manga
        mangaRepository.delete(targetMangaId)

        // 4. Verify target manga is deleted
        assertNull(mangaRepository.getMangaById(targetMangaId))

        // 5. Verify control manga and its children remain intact
        assertNotNull(mangaRepository.getMangaById(controlMangaId))
        val remainingControlChapters = chapterRepository.getChapterByMangaId(controlMangaId)
        assertEquals(10, remainingControlChapters.size)
        val remainingControlHistory = historyRepository.getHistoryByMangaId(controlMangaId)
        assertEquals(10, remainingControlHistory.size)

        // 6. Direct SQL verification: 0 orphans in ChapterTable and HistoryTable
        val rawConn = databaseManager.getConnection()
        rawConn.use { conn ->
            conn.createStatement().use { stmt ->
                // Check no chapters point to targetMangaId
                stmt.executeQuery("SELECT COUNT(*) FROM chapter WHERE manga_id = $targetMangaId").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt(1))
                }
                // Check no orphan chapters exist (chapters whose manga does not exist)
                stmt.executeQuery("SELECT COUNT(*) FROM chapter WHERE manga_id NOT IN (SELECT id FROM manga)").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt(1))
                }
                // Check no orphan history exists (history whose chapter does not exist)
                stmt.executeQuery("SELECT COUNT(*) FROM history WHERE chapter_id NOT IN (SELECT id FROM chapter)").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt(1))
                }
                // Check exact remaining counts
                stmt.executeQuery("SELECT COUNT(*) FROM manga").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1))
                }
                stmt.executeQuery("SELECT COUNT(*) FROM chapter").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(10, rs.getInt(1))
                }
                stmt.executeQuery("SELECT COUNT(*) FROM history").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(10, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun testDeepRelationalCascadeDeleteAllMangas() = runBlocking {
        // Insert 5 Mangas, each with 50 Chapters, each chapter with 1 History record
        val mangaCount = 5
        val chaptersPerManga = 50

        for (m in 1..mangaCount) {
            val mId = mangaRepository.insert(
                Manga.create().copy(source = 1L, url = "/manga/deep-$m", title = "Deep Manga $m"),
            )
            val chapters = (1..chaptersPerManga).map { c ->
                Chapter.create().copy(
                    mangaId = mId,
                    url = "/manga/deep-$m/ch-$c",
                    name = "Ch $c",
                    chapterNumber = c.toDouble(),
                )
            }
            val insertedChapters = chapterRepository.updateFromRemote(emptyList(), chapters, emptyList())
            insertedChapters.forEach { ch ->
                historyRepository.upsert(
                    HistoryUpdate(
                        chapterId = ch.id,
                        readAt = Instant.fromEpochMilliseconds(10000L),
                        sessionReadDuration = 300L,
                    ),
                )
            }
        }

        // Verify total records: 5 mangas, 250 chapters, 250 history
        transaction(databaseManager.database) {
            assertEquals(5L, MangaTable.selectAll().count())
            assertEquals(250L, ChapterTable.selectAll().count())
            assertEquals(250L, HistoryTable.selectAll().count())
        }

        // Delete all mangas via repository
        val deleted = mangaRepository.deleteAll()
        assertTrue(deleted)

        // Raw SQL verification of zero residual rows and zero orphans
        val rawConn = databaseManager.getConnection()
        rawConn.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM manga").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt(1), "Expected 0 manga rows")
                }
                stmt.executeQuery("SELECT COUNT(*) FROM chapter").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt(1), "Expected 0 chapter rows")
                }
                stmt.executeQuery("SELECT COUNT(*) FROM history").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt(1), "Expected 0 history rows")
                }
            }
        }
    }

    @Test
    fun testCascadeDeleteChaptersViaUpdateFromRemote() = runBlocking {
        val mangaId = mangaRepository.insert(
            Manga.create().copy(source = 1L, url = "/remote-del", title = "Remote Del Manga"),
        )
        val chapters = (1..50).map { i ->
            Chapter.create().copy(
                mangaId = mangaId,
                url = "/remote-del/ch-$i",
                name = "Chapter $i",
                chapterNumber = i.toDouble(),
            )
        }
        val insertedChapters = chapterRepository.updateFromRemote(emptyList(), chapters, emptyList())
        assertEquals(50, insertedChapters.size)

        insertedChapters.forEach { ch ->
            historyRepository.upsert(
                HistoryUpdate(
                    chapterId = ch.id,
                    readAt = Instant.fromEpochMilliseconds(5000L),
                    sessionReadDuration = 60L,
                ),
            )
        }

        // Remove the first 25 chapters via updateFromRemote
        val toRemove = insertedChapters.take(25).map { it.id }
        chapterRepository.updateFromRemote(toRemove, emptyList(), emptyList())

        // Verify 25 chapters remain and their history remains
        val remainingChapters = chapterRepository.getChapterByMangaId(mangaId)
        assertEquals(25, remainingChapters.size)

        val remainingHistory = historyRepository.getHistoryByMangaId(mangaId)
        assertEquals(25, remainingHistory.size)

        // Verify raw SQLite database state: exactly 25 chapters, 25 history, 0 orphan history
        val rawConn = databaseManager.getConnection()
        rawConn.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM chapter WHERE manga_id = $mangaId").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(25, rs.getInt(1))
                }
                stmt.executeQuery("SELECT COUNT(*) FROM history WHERE chapter_id NOT IN (SELECT id FROM chapter)").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt(1), "Expected 0 orphan history rows")
                }
            }
        }
    }

    @Test
    fun testReactiveFlowUnderHighFrequencyConcurrentMutations() = runBlocking {
        val collectedSnapshots = mutableListOf<List<Manga>>()

        coroutineScope {
            val flow = mangaRepository.getFavoritesAsFlow()
            val collectorJob = launch(Dispatchers.IO) {
                flow.collect { list ->
                    synchronized(collectedSnapshots) {
                        collectedSnapshots.add(list)
                    }
                }
            }

            // Concurrently insert 20 favorites
            val mutators = (1..20).map { i ->
                async(Dispatchers.IO) {
                    mangaRepository.insert(
                        Manga.create().copy(
                            source = 1L,
                            url = "/fav-flow/$i",
                            title = "Fav $i",
                            favorite = true,
                        ),
                    )
                }
            }
            mutators.awaitAll()

            // Allow flow collector to process
            kotlinx.coroutines.delay(200)
            collectorJob.cancel()
        }

        synchronized(collectedSnapshots) {
            assertTrue(collectedSnapshots.isNotEmpty())
            val lastSnapshot = collectedSnapshots.last()
            assertEquals(20, lastSnapshot.size)
        }
    }
}
