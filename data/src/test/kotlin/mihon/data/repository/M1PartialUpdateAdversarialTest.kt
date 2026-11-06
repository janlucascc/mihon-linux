package mihon.data.repository

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mihon.data.DatabaseTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import kotlin.time.Instant

class M1PartialUpdateAdversarialTest : DatabaseTestBase() {

    private lateinit var mangaRepository: MangaRepositoryImpl
    private lateinit var chapterRepository: ChapterRepositoryImpl

    @BeforeEach
    fun initRepos() {
        mangaRepository = MangaRepositoryImpl(databaseManager)
        chapterRepository = ChapterRepositoryImpl(databaseManager)
    }

    private fun createReferenceManga(urlSuffix: String = "ref"): Manga = Manga(
        id = -1L,
        source = 777L,
        favorite = true,
        lastUpdate = Instant.fromEpochMilliseconds(1000L),
        nextUpdate = Instant.fromEpochMilliseconds(2000L),
        fetchInterval = 7,
        dateAdded = Instant.fromEpochMilliseconds(3000L),
        viewerFlags = 111L,
        chapterFlags = 222L,
        coverLastModified = Instant.fromEpochMilliseconds(4000L),
        url = "/manga/ref-$urlSuffix",
        title = "Reference Manga $urlSuffix",
        artist = "Original Artist",
        author = "Original Author",
        description = "Original Description",
        genre = listOf("Action", "Fantasy", "Mystery"),
        status = 1L,
        thumbnailUrl = "https://cdn.example.org/ref.jpg",
        updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
        initialized = true,
        notes = "Original Notes",
        memo = JsonObject(mapOf("key1" to JsonPrimitive("val1"))),
        favoriteAt = Instant.fromEpochMilliseconds(5000L),
    )

    @Test
    fun testMangaUpdateModifiesOnlyNotesPreservingAllOtherFields() = runBlocking {
        val original = createReferenceManga("notes-test")
        val id = mangaRepository.insert(original)

        // Partially update ONLY notes
        val update = MangaUpdate(id) {
            notes = "Brand New Notes Value"
        }
        mangaRepository.update(update)

        val updated = mangaRepository.getMangaById(id)!!

        // Explicitly modified field
        assertEquals("Brand New Notes Value", updated.notes)

        // ALL other 22 fields MUST match original exactly
        assertEquals(id, updated.id)
        assertEquals(original.source, updated.source)
        assertEquals(original.favorite, updated.favorite)
        assertEquals(original.lastUpdate, updated.lastUpdate)
        assertEquals(original.nextUpdate, updated.nextUpdate)
        assertEquals(original.fetchInterval, updated.fetchInterval)
        assertEquals(original.dateAdded, updated.dateAdded)
        assertEquals(original.viewerFlags, updated.viewerFlags)
        assertEquals(original.chapterFlags, updated.chapterFlags)
        assertEquals(original.coverLastModified, updated.coverLastModified)
        assertEquals(original.url, updated.url)
        assertEquals(original.title, updated.title)
        assertEquals(original.artist, updated.artist)
        assertEquals(original.author, updated.author)
        assertEquals(original.description, updated.description)
        assertEquals(original.genre, updated.genre)
        assertEquals(original.status, updated.status)
        assertEquals(original.thumbnailUrl, updated.thumbnailUrl)
        assertEquals(original.updateStrategy, updated.updateStrategy)
        assertEquals(original.initialized, updated.initialized)
        assertEquals(original.memo, updated.memo)
        assertEquals(original.favoriteAt, updated.favoriteAt)
    }

    @Test
    fun testMangaUpdateModifiesOnlyFlagsAndIntervalPreservingAllOtherFields() = runBlocking {
        val original = createReferenceManga("flags-test")
        val id = mangaRepository.insert(original)

        val update = MangaUpdate(id) {
            viewerFlags = 9999L
            fetchInterval = 30
        }
        mangaRepository.update(update)

        val updated = mangaRepository.getMangaById(id)!!

        assertEquals(9999L, updated.viewerFlags)
        assertEquals(30, updated.fetchInterval)

        // Untouched fields preserved
        assertEquals(original.title, updated.title)
        assertEquals(original.author, updated.author)
        assertEquals(original.artist, updated.artist)
        assertEquals(original.description, updated.description)
        assertEquals(original.genre, updated.genre)
        assertEquals(original.notes, updated.notes)
        assertEquals(original.memo, updated.memo)
        assertEquals(original.chapterFlags, updated.chapterFlags)
        assertEquals(original.favoriteAt, updated.favoriteAt)
        assertEquals(original.lastUpdate, updated.lastUpdate)
        assertEquals(original.nextUpdate, updated.nextUpdate)
        assertEquals(original.coverLastModified, updated.coverLastModified)
    }

    @Test
    fun testMangaUpdateExplicitNullAssignment() = runBlocking {
        val original = createReferenceManga("null-assignment")
        val id = mangaRepository.insert(original)

        // Explicitly set favoriteAt to null
        val update1 = MangaUpdate(id) {
            favoriteAt = null
        }
        mangaRepository.update(update1)

        val step1 = mangaRepository.getMangaById(id)!!
        assertNull(step1.favoriteAt)
        assertEquals(original.notes, step1.notes)
        assertEquals(original.viewerFlags, step1.viewerFlags)
        assertEquals(original.title, step1.title)

        // Explicitly set notes to null
        val update2 = MangaUpdate(id) {
            notes = null
        }
        mangaRepository.update(update2)

        val step2 = mangaRepository.getMangaById(id)!!
        assertNull(step2.favoriteAt)
        assertNull(step2.notes)
        assertEquals(original.viewerFlags, step2.viewerFlags)
        assertEquals(original.title, step2.title)
    }

    @Test
    fun testBatchMangaUpdateAllPreservesUntouchedFields() = runBlocking {
        val m1 = createReferenceManga("batch-1")
        val m2 = createReferenceManga("batch-2")

        val id1 = mangaRepository.insert(m1)
        val id2 = mangaRepository.insert(m2)

        val updates = listOf(
            MangaUpdate(id1) {
                notes = "Batch Updated Notes 1"
            },
            MangaUpdate(id2) {
                viewerFlags = 555L
                fetchInterval = 14
            },
        )
        mangaRepository.updateAll(updates)

        val r1 = mangaRepository.getMangaById(id1)!!
        assertEquals("Batch Updated Notes 1", r1.notes)
        assertEquals(m1.viewerFlags, r1.viewerFlags)
        assertEquals(m1.title, r1.title)

        val r2 = mangaRepository.getMangaById(id2)!!
        assertEquals(m2.notes, r2.notes)
        assertEquals(555L, r2.viewerFlags)
        assertEquals(14, r2.fetchInterval)
        assertEquals(m2.title, r2.title)
    }

    @Test
    fun testChapterUpdatePreservesUntouchedFields() = runBlocking {
        val mangaId = mangaRepository.insert(createReferenceManga("ch-partial"))
        val originalChapter = Chapter(
            id = -1L,
            mangaId = mangaId,
            read = false,
            bookmark = false,
            lastPageRead = 0,
            dateFetch = Instant.fromEpochMilliseconds(1000L),
            sourceOrder = 42L,
            url = "/ch/partial-1",
            name = "Chapter Partial 1",
            dateUpload = Instant.fromEpochMilliseconds(2000L),
            chapterNumber = 12.0,
            scanlator = "Scanlator Group",
            memo = JsonObject(mapOf("ch_key" to JsonPrimitive("ch_val"))),
        )

        val inserted = chapterRepository.updateFromRemote(emptyList(), listOf(originalChapter), emptyList())
        val chId = inserted[0].id

        // Partially update only 'read'
        val u1 = ChapterUpdate(chId) {
            read = true
        }
        chapterRepository.update(u1)

        val r1 = chapterRepository.getChapterById(chId)!!
        assertTrue(r1.read)
        // Untouched preserved
        assertFalse(r1.bookmark)
        assertEquals(0, r1.lastPageRead)
        assertEquals(originalChapter.dateFetch, r1.dateFetch)
        assertEquals(originalChapter.sourceOrder, r1.sourceOrder)
        assertEquals(originalChapter.name, r1.name)
        assertEquals(originalChapter.chapterNumber, r1.chapterNumber)
        assertEquals(originalChapter.scanlator, r1.scanlator)
        assertEquals(originalChapter.memo, r1.memo)

        // Partially update only 'lastPageRead'
        val u2 = ChapterUpdate(chId) {
            lastPageRead = 37
        }
        chapterRepository.update(u2)

        val r2 = chapterRepository.getChapterById(chId)!!
        assertTrue(r2.read)
        assertEquals(37, r2.lastPageRead)
        assertFalse(r2.bookmark)
        assertEquals(originalChapter.name, r2.name)

        // Partially update only 'bookmark'
        val u3 = ChapterUpdate(chId) {
            bookmark = true
        }
        chapterRepository.update(u3)

        val r3 = chapterRepository.getChapterById(chId)!!
        assertTrue(r3.read)
        assertEquals(37, r3.lastPageRead)
        assertTrue(r3.bookmark)
        assertEquals(originalChapter.name, r3.name)
    }
}
