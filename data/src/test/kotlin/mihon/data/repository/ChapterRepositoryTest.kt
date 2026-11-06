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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterRemoteUpdate
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Instant

class ChapterRepositoryTest : DatabaseTestBase() {

    private lateinit var mangaRepository: MangaRepositoryImpl
    private lateinit var chapterRepository: ChapterRepositoryImpl
    private var parentMangaId: Long = -1L

    @BeforeEach
    fun initRepositories() = runBlocking {
        mangaRepository = MangaRepositoryImpl(databaseManager)
        chapterRepository = ChapterRepositoryImpl(databaseManager)
        parentMangaId = mangaRepository.insert(
            Manga.create().copy(url = "/manga/parent", title = "Parent Manga", source = 1L),
        )
    }

    private fun sampleChapter(
        mangaId: Long = parentMangaId,
        url: String = "/chapter/1",
        name: String = "Chapter 1",
        chapterNumber: Double = 1.0,
        sourceOrder: Long = 1L,
        read: Boolean = false,
        bookmark: Boolean = false,
        scanlator: String? = "ScanGroupA",
    ): Chapter = Chapter(
        id = -1L,
        mangaId = mangaId,
        read = read,
        bookmark = bookmark,
        lastPageRead = 0,
        dateFetch = Instant.fromEpochMilliseconds(1000L),
        sourceOrder = sourceOrder,
        url = url,
        name = name,
        dateUpload = Instant.fromEpochMilliseconds(2000L),
        chapterNumber = chapterNumber,
        scanlator = scanlator,
        memo = JsonObject.EMPTY,
    )

    @Test
    fun testUpdateFromRemoteAndGetById() = runBlocking {
        // Method 3 (updateFromRemote - insert) and Method 10 (getChapterById)
        val ch = sampleChapter()
        val inserted = chapterRepository.updateFromRemote(emptyList(), listOf(ch), emptyList())
        assertEquals(1, inserted.size)
        val generatedId = inserted[0].id
        assertTrue(generatedId > 0)

        val retrieved = chapterRepository.getChapterById(generatedId)
        assertNotNull(retrieved)
        assertEquals(generatedId, retrieved!!.id)
        assertEquals(parentMangaId, retrieved.mangaId)
        assertEquals(ch.url, retrieved.url)
        assertEquals(ch.name, retrieved.name)
        assertEquals(ch.chapterNumber, retrieved.chapterNumber)
        assertEquals(ch.scanlator, retrieved.scanlator)
        assertEquals(ch.dateFetch, retrieved.dateFetch)
        assertEquals(ch.dateUpload, retrieved.dateUpload)
        assertFalse(retrieved.read)
        assertFalse(retrieved.bookmark)
    }

    @Test
    fun testUpdateSingleChapter() = runBlocking {
        // Method 1 (update)
        val inserted = chapterRepository.updateFromRemote(emptyList(), listOf(sampleChapter()), emptyList())
        val id = inserted[0].id

        val update = ChapterUpdate(id) {
            read = true
            bookmark = true
            lastPageRead = 15
            dateFetch = Instant.fromEpochMilliseconds(5000L)
        }
        chapterRepository.update(update)

        val refreshed = chapterRepository.getChapterById(id)!!
        assertTrue(refreshed.read)
        assertTrue(refreshed.bookmark)
        assertEquals(15, refreshed.lastPageRead)
        assertEquals(Instant.fromEpochMilliseconds(5000L), refreshed.dateFetch)
    }

    @Test
    fun testUpdateAllChapters() = runBlocking {
        // Method 2 (updateAll)
        val ch1 = sampleChapter(url = "/ch1", name = "Ch 1", sourceOrder = 1L)
        val ch2 = sampleChapter(url = "/ch2", name = "Ch 2", sourceOrder = 2L)
        val inserted = chapterRepository.updateFromRemote(emptyList(), listOf(ch1, ch2), emptyList())
        val id1 = inserted[0].id
        val id2 = inserted[1].id

        val updates = listOf(
            ChapterUpdate(id1) { read = true; lastPageRead = 20 },
            ChapterUpdate(id2) { bookmark = true; lastPageRead = 5 },
        )
        chapterRepository.updateAll(updates)

        val ref1 = chapterRepository.getChapterById(id1)!!
        val ref2 = chapterRepository.getChapterById(id2)!!

        assertTrue(ref1.read)
        assertFalse(ref1.bookmark)
        assertEquals(20, ref1.lastPageRead)

        assertFalse(ref2.read)
        assertTrue(ref2.bookmark)
        assertEquals(5, ref2.lastPageRead)
    }

    @Test
    fun testUpdateFromRemoteFull() = runBlocking {
        // Method 3 (updateFromRemote with add, update, remove)
        val ch1 = sampleChapter(url = "/ch1", name = "Old Ch 1", chapterNumber = 1.0)
        val ch2 = sampleChapter(url = "/ch2", name = "To Remove", chapterNumber = 2.0)
        val initial = chapterRepository.updateFromRemote(emptyList(), listOf(ch1, ch2), emptyList())
        val id1 = initial[0].id
        val id2 = initial[1].id

        val remoteUpdate = ChapterRemoteUpdate(
            id = id1,
            name = "Ch 1: Romance Dawn",
            scanlator = "UpdatedGroup",
            chapterNumber = 1.0,
            dateUpload = Instant.fromEpochMilliseconds(8888L),
            sourceOrder = 10L,
            memo = JsonObject.EMPTY,
        )
        val newChapter = sampleChapter(url = "/ch3", name = "Ch 3: Buggy", chapterNumber = 3.0, sourceOrder = 30L)

        val result = chapterRepository.updateFromRemote(
            removedIds = listOf(id2),
            added = listOf(newChapter),
            updated = listOf(remoteUpdate),
        )

        assertEquals(1, result.size)
        val id3 = result[0].id

        // id2 should be removed
        assertNull(chapterRepository.getChapterById(id2))

        // id1 should be updated
        val ref1 = chapterRepository.getChapterById(id1)!!
        assertEquals("Ch 1: Romance Dawn", ref1.name)
        assertEquals("UpdatedGroup", ref1.scanlator)
        assertEquals(10L, ref1.sourceOrder)
        assertEquals(Instant.fromEpochMilliseconds(8888L), ref1.dateUpload)

        // id3 should be added
        val ref3 = chapterRepository.getChapterById(id3)!!
        assertEquals("Ch 3: Buggy", ref3.name)
        assertEquals(30L, ref3.sourceOrder)
    }

    @Test
    fun testGetChapterByMangaId() = runBlocking {
        // Method 4 (getChapterByMangaId)
        val ch1 = sampleChapter(url = "/c1", name = "Ch 1", sourceOrder = 10L)
        val ch2 = sampleChapter(url = "/c2", name = "Ch 2", sourceOrder = 20L)
        chapterRepository.updateFromRemote(emptyList(), listOf(ch1, ch2), emptyList())

        val list = chapterRepository.getChapterByMangaId(parentMangaId)
        assertEquals(2, list.size)
        // Ordered by sourceOrder DESC
        assertEquals("Ch 2", list[0].name)
        assertEquals("Ch 1", list[1].name)
    }

    @Test
    fun testGetChaptersByMangaIds() = runBlocking {
        // Method 5 (getChaptersByMangaIds)
        val m2Id = mangaRepository.insert(Manga.create().copy(url = "/m2", title = "Manga 2", source = 1L))
        val chM1 = sampleChapter(mangaId = parentMangaId, url = "/m1/c1", name = "M1C1")
        val chM2 = sampleChapter(mangaId = m2Id, url = "/m2/c1", name = "M2C1")
        chapterRepository.updateFromRemote(emptyList(), listOf(chM1, chM2), emptyList())

        val map = chapterRepository.getChaptersByMangaIds(listOf(parentMangaId, m2Id))
        assertEquals(2, map.size)
        assertEquals(1, map[parentMangaId]?.size)
        assertEquals("M1C1", map[parentMangaId]?.get(0)?.name)
        assertEquals(1, map[m2Id]?.size)
        assertEquals("M2C1", map[m2Id]?.get(0)?.name)
    }

    @Test
    fun testGetChapterSummaryByMangaId() = runBlocking {
        // Method 6 (getChapterSummaryByMangaId)
        val ch1 = sampleChapter(url = "/c1", chapterNumber = 1.0)
        val ch2 = sampleChapter(url = "/c2", chapterNumber = 2.5)
        val ch3 = sampleChapter(url = "/c3", chapterNumber = -1.0) // unrecognised
        chapterRepository.updateFromRemote(emptyList(), listOf(ch1, ch2, ch3), emptyList())

        val summary = chapterRepository.getChapterSummaryByMangaId(parentMangaId)
        assertEquals(3L, summary.chapterCount)
        assertEquals(2.5, summary.latestChapterNumber)
    }

    @Test
    fun testGetScanlatorsAndFlow() = runBlocking {
        // Method 7 (getScanlatorsByMangaId) and Method 8 (getScanlatorsByMangaIdAsFlow)
        val ch1 = sampleChapter(url = "/c1", scanlator = "Group A")
        val ch2 = sampleChapter(url = "/c2", scanlator = "Group B")
        val ch3 = sampleChapter(url = "/c3", scanlator = "Group A") // duplicate
        val ch4 = sampleChapter(url = "/c4", scanlator = null)
        chapterRepository.updateFromRemote(emptyList(), listOf(ch1, ch2, ch3, ch4), emptyList())

        val scanlators = chapterRepository.getScanlatorsByMangaId(parentMangaId)
        assertEquals(2, scanlators.size)
        assertTrue(scanlators.contains("Group A"))
        assertTrue(scanlators.contains("Group B"))

        val flowEmission = chapterRepository.getScanlatorsByMangaIdAsFlow(parentMangaId).first()
        assertEquals(scanlators, flowEmission)
    }

    @Test
    fun testGetBookmarkedChaptersByMangaId() = runBlocking {
        // Method 9 (getBookmarkedChaptersByMangaId)
        val ch1 = sampleChapter(url = "/c1", bookmark = false)
        val ch2 = sampleChapter(url = "/c2", bookmark = true)
        chapterRepository.updateFromRemote(emptyList(), listOf(ch1, ch2), emptyList())

        val bookmarks = chapterRepository.getBookmarkedChaptersByMangaId(parentMangaId)
        assertEquals(1, bookmarks.size)
        assertEquals("/c2", bookmarks[0].url)
        assertTrue(bookmarks[0].bookmark)
    }

    @Test
    fun testGetChapterByMangaIdAsFlow() = runBlocking {
        // Method 11 (getChapterByMangaIdAsFlow)
        val initialEmission = chapterRepository.getChapterByMangaIdAsFlow(parentMangaId).first()
        assertTrue(initialEmission.isEmpty())

        val flowResults = mutableListOf<List<Chapter>>()
        val job = launch(Dispatchers.Default) {
            chapterRepository.getChapterByMangaIdAsFlow(parentMangaId).toList(flowResults)
        }

        withTimeout(5000) {
            while (flowResults.isEmpty()) {
                delay(20)
            }
        }

        chapterRepository.updateFromRemote(emptyList(), listOf(sampleChapter(url = "/flow1")), emptyList())

        withTimeout(5000) {
            while (flowResults.size < 2) {
                delay(20)
            }
        }

        job.cancel()
        assertEquals(2, flowResults.size)
        assertEquals(0, flowResults[0].size)
        assertEquals(1, flowResults[1].size)
    }

    @Test
    fun testGetChapterByUrlAndMangaId() = runBlocking {
        // Method 12 (getChapterByUrlAndMangaId)
        chapterRepository.updateFromRemote(emptyList(), listOf(sampleChapter(url = "/unique-ch")), emptyList())

        val found = chapterRepository.getChapterByUrlAndMangaId("/unique-ch", parentMangaId)
        assertNotNull(found)
        assertEquals("/unique-ch", found!!.url)

        val notFound = chapterRepository.getChapterByUrlAndMangaId("/non-existent", parentMangaId)
        assertNull(notFound)
    }
}
