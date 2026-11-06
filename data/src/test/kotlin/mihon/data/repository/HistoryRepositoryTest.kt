package mihon.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import mihon.core.common.extensions.EMPTY
import mihon.data.DatabaseTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Instant

class HistoryRepositoryTest : DatabaseTestBase() {

    private lateinit var mangaRepository: MangaRepositoryImpl
    private lateinit var chapterRepository: ChapterRepositoryImpl
    private lateinit var historyRepository: HistoryRepositoryImpl

    private var mangaId: Long = -1L
    private var chapterId: Long = -1L

    @BeforeEach
    fun setupData() = runBlocking {
        mangaRepository = MangaRepositoryImpl(databaseManager)
        chapterRepository = ChapterRepositoryImpl(databaseManager)
        historyRepository = HistoryRepositoryImpl(databaseManager)

        mangaId = mangaRepository.insert(
            Manga.create().copy(url = "/manga/h-test", title = "History Test Manga", source = 1L),
        )
        val insertedChapters = chapterRepository.updateFromRemote(
            emptyList(),
            listOf(
                Chapter(
                    id = -1L,
                    mangaId = mangaId,
                    read = false,
                    bookmark = false,
                    lastPageRead = 0,
                    dateFetch = Instant.fromEpochMilliseconds(1000L),
                    sourceOrder = 1L,
                    url = "/ch/h-test-1",
                    name = "Chapter 1",
                    dateUpload = null,
                    chapterNumber = 1.0,
                    scanlator = null,
                    memo = JsonObject.EMPTY,
                ),
            ),
            emptyList(),
        )
        chapterId = insertedChapters[0].id
    }

    @Test
    fun testUpsertNewHistoryUpdate() = runBlocking {
        val update = HistoryUpdate(
            chapterId = chapterId,
            readAt = Instant.fromEpochMilliseconds(5000L),
            sessionReadDuration = 300L,
        )
        val history = historyRepository.upsert(update)

        assertTrue(history.id > 0)
        assertEquals(chapterId, history.chapterId)
        assertEquals(Instant.fromEpochMilliseconds(5000L), history.readAt)
        assertEquals(300L, history.readDuration)

        val retrieved = historyRepository.getHistoryById(history.id)
        assertNotNull(retrieved)
        assertEquals(history, retrieved)

        val retrievedByChapter = historyRepository.getHistoryByChapterId(chapterId)
        assertNotNull(retrievedByChapter)
        assertEquals(history, retrievedByChapter)
    }

    @Test
    fun testUpsertCumulativeDuration() = runBlocking {
        val update1 = HistoryUpdate(
            chapterId = chapterId,
            readAt = Instant.fromEpochMilliseconds(5000L),
            sessionReadDuration = 300L,
        )
        historyRepository.upsert(update1)

        // Second session of reading same chapter
        val update2 = HistoryUpdate(
            chapterId = chapterId,
            readAt = Instant.fromEpochMilliseconds(8000L),
            sessionReadDuration = 250L,
        )
        val history2 = historyRepository.upsert(update2)

        // Read duration should accumulate to 300 + 250 = 550
        assertEquals(550L, history2.readDuration)
        assertEquals(Instant.fromEpochMilliseconds(8000L), history2.readAt)

        val inDb = historyRepository.getHistoryByChapterId(chapterId)!!
        assertEquals(550L, inDb.readDuration)
        assertEquals(Instant.fromEpochMilliseconds(8000L), inDb.readAt)
    }

    @Test
    fun testUpsertDirectHistory() = runBlocking {
        val direct = History(
            id = -1L,
            chapterId = chapterId,
            readAt = Instant.fromEpochMilliseconds(12345L),
            readDuration = 700L,
        )
        val saved = historyRepository.upsert(direct)
        assertTrue(saved.id > 0)
        assertEquals(700L, saved.readDuration)

        // Update with known ID
        val updateDirect = saved.copy(readDuration = 900L)
        val updated = historyRepository.upsert(updateDirect)
        assertEquals(saved.id, updated.id)
        assertEquals(900L, updated.readDuration)
    }

    @Test
    fun testGetHistoryByMangaId() = runBlocking {
        // Create second chapter
        val inserted = chapterRepository.updateFromRemote(
            emptyList(),
            listOf(
                Chapter(
                    id = -1L,
                    mangaId = mangaId,
                    read = false,
                    bookmark = false,
                    lastPageRead = 0,
                    dateFetch = Instant.fromEpochMilliseconds(1000L),
                    sourceOrder = 2L,
                    url = "/ch/h-test-2",
                    name = "Chapter 2",
                    dateUpload = null,
                    chapterNumber = 2.0,
                    scanlator = null,
                    memo = JsonObject.EMPTY,
                ),
            ),
            emptyList(),
        )
        val ch2Id = inserted[0].id

        historyRepository.upsert(HistoryUpdate(chapterId, Instant.fromEpochMilliseconds(1000L), 100L))
        historyRepository.upsert(HistoryUpdate(ch2Id, Instant.fromEpochMilliseconds(2000L), 200L))

        val list = historyRepository.getHistoryByMangaId(mangaId)
        assertEquals(2, list.size)
        // Ordered by readAt DESC
        assertEquals(ch2Id, list[0].chapterId)
        assertEquals(chapterId, list[1].chapterId)
    }

    @Test
    fun testGetAllHistoryAndFlow() = runBlocking {
        historyRepository.upsert(HistoryUpdate(chapterId, Instant.fromEpochMilliseconds(1000L), 100L))

        val all = historyRepository.getAllHistory()
        assertEquals(1, all.size)

        val flowEmission = historyRepository.getAllHistoryAsFlow().first()
        assertEquals(1, flowEmission.size)

        // Test reactive Flow emission on subsequent upsert
        val flowEvents = mutableListOf<List<History>>()
        val job = launch(Dispatchers.Default) {
            historyRepository.getAllHistoryAsFlow().toList(flowEvents)
        }

        withTimeout(5000) {
            while (flowEvents.isEmpty()) {
                delay(20)
            }
        }

        historyRepository.upsert(HistoryUpdate(chapterId, Instant.fromEpochMilliseconds(3000L), 50L))

        withTimeout(5000) {
            while (flowEvents.size < 2) {
                delay(20)
            }
        }

        job.cancel()
        assertEquals(2, flowEvents.size)
        assertEquals(1, flowEvents[0].size)
        assertEquals(1, flowEvents[1].size)
    }

    @Test
    fun testDeleteOperations() = runBlocking {
        val h = historyRepository.upsert(HistoryUpdate(chapterId, Instant.fromEpochMilliseconds(1000L), 100L))
        assertNotNull(historyRepository.getHistoryById(h.id))

        historyRepository.delete(h.id)
        assertNull(historyRepository.getHistoryById(h.id))

        val h2 = historyRepository.upsert(HistoryUpdate(chapterId, Instant.fromEpochMilliseconds(2000L), 200L))
        assertNotNull(historyRepository.getHistoryByChapterId(chapterId))

        historyRepository.deleteByChapterId(chapterId)
        assertNull(historyRepository.getHistoryByChapterId(chapterId))

        historyRepository.upsert(HistoryUpdate(chapterId, Instant.fromEpochMilliseconds(3000L), 300L))
        historyRepository.deleteByMangaId(mangaId)
        assertNull(historyRepository.getHistoryByChapterId(chapterId))

        historyRepository.upsert(HistoryUpdate(chapterId, Instant.fromEpochMilliseconds(4000L), 400L))
        val cleared = historyRepository.deleteAll()
        assertTrue(cleared)
        assertTrue(historyRepository.getAllHistory().isEmpty())
    }
}
