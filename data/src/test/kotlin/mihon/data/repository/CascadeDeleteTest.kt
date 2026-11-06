package mihon.data.repository

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import mihon.core.common.extensions.EMPTY
import mihon.data.DatabaseTestBase
import mihon.data.database.tables.ChapterTable
import mihon.data.database.tables.HistoryTable
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Instant

class CascadeDeleteTest : DatabaseTestBase() {

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
    fun testCascadeDeleteMangaDeletesChaptersAndHistory() = runBlocking {
        // 1. Create Manga
        val mId = mangaRepository.insert(
            Manga.create().copy(url = "/m/cascade", title = "Cascade Manga", source = 1L),
        )

        // 2. Create Chapter
        val insertedChapters = chapterRepository.updateFromRemote(
            emptyList(),
            listOf(
                Chapter(
                    id = -1L,
                    mangaId = mId,
                    read = false,
                    bookmark = false,
                    lastPageRead = 0,
                    dateFetch = Instant.fromEpochMilliseconds(1000L),
                    sourceOrder = 1L,
                    url = "/ch/cascade-1",
                    name = "Chapter 1",
                    dateUpload = null,
                    chapterNumber = 1.0,
                    scanlator = null,
                    memo = JsonObject.EMPTY,
                ),
            ),
            emptyList(),
        )
        val cId = insertedChapters[0].id

        // 3. Create History
        val history = historyRepository.upsert(
            HistoryUpdate(
                chapterId = cId,
                readAt = Instant.fromEpochMilliseconds(2000L),
                sessionReadDuration = 500L,
            ),
        )

        assertNotNull(mangaRepository.getMangaById(mId))
        assertNotNull(chapterRepository.getChapterById(cId))
        assertNotNull(historyRepository.getHistoryById(history.id))

        // 4. Delete Manga
        mangaRepository.delete(mId)

        // 5. Assert Manga is deleted
        assertNull(mangaRepository.getMangaById(mId))

        // 6. Assert Chapter is automatically deleted by CASCADE
        assertNull(chapterRepository.getChapterById(cId))

        // 7. Assert History is automatically deleted by CASCADE
        assertNull(historyRepository.getHistoryById(history.id))
        assertNull(historyRepository.getHistoryByChapterId(cId))
    }

    @Test
    fun testCascadeDeleteChapterDeletesHistory() = runBlocking {
        val mId = mangaRepository.insert(
            Manga.create().copy(url = "/m/cascade2", title = "Cascade Manga 2", source = 1L),
        )

        val insertedChapters = chapterRepository.updateFromRemote(
            emptyList(),
            listOf(
                Chapter(
                    id = -1L,
                    mangaId = mId,
                    read = false,
                    bookmark = false,
                    lastPageRead = 0,
                    dateFetch = Instant.fromEpochMilliseconds(1000L),
                    sourceOrder = 1L,
                    url = "/ch/cascade-2",
                    name = "Chapter 2",
                    dateUpload = null,
                    chapterNumber = 2.0,
                    scanlator = null,
                    memo = JsonObject.EMPTY,
                ),
            ),
            emptyList(),
        )
        val cId = insertedChapters[0].id

        val history = historyRepository.upsert(
            HistoryUpdate(
                chapterId = cId,
                readAt = Instant.fromEpochMilliseconds(3000L),
                sessionReadDuration = 100L,
            ),
        )

        // Delete Chapter via updateFromRemote (removedIds)
        chapterRepository.updateFromRemote(listOf(cId), emptyList(), emptyList())

        assertNull(chapterRepository.getChapterById(cId))
        assertNull(historyRepository.getHistoryById(history.id))
        assertNull(historyRepository.getHistoryByChapterId(cId))
    }

    @Test
    fun testZeroOrphanIntegrityAfterMangaWipe() = runBlocking {
        val m1 = mangaRepository.insert(Manga.create().copy(url = "/m1", title = "Manga 1", source = 1L))
        val m2 = mangaRepository.insert(Manga.create().copy(url = "/m2", title = "Manga 2", source = 1L))

        val chs1 = chapterRepository.updateFromRemote(
            emptyList(),
            listOf(
                Chapter.create().copy(mangaId = m1, url = "/m1/c1", name = "M1C1"),
                Chapter.create().copy(mangaId = m1, url = "/m1/c2", name = "M1C2"),
            ),
            emptyList(),
        )
        val chs2 = chapterRepository.updateFromRemote(
            emptyList(),
            listOf(
                Chapter.create().copy(mangaId = m2, url = "/m2/c1", name = "M2C1"),
            ),
            emptyList(),
        )

        historyRepository.upsert(HistoryUpdate(chs1[0].id, Instant.fromEpochMilliseconds(1000L), 60L))
        historyRepository.upsert(HistoryUpdate(chs1[1].id, Instant.fromEpochMilliseconds(2000L), 120L))
        historyRepository.upsert(HistoryUpdate(chs2[0].id, Instant.fromEpochMilliseconds(3000L), 180L))

        // Delete all manga
        mangaRepository.deleteAll()

        // Verify zero residual rows in chapter and history tables
        transaction(databaseManager.database) {
            val residualChapters = ChapterTable.selectAll().count()
            val residualHistory = HistoryTable.selectAll().count()
            assertEquals(0L, residualChapters)
            assertEquals(0L, residualHistory)
        }
    }
}
