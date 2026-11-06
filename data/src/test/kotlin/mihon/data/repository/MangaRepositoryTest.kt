package mihon.data.repository

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaRemoteUpdate
import tachiyomi.domain.manga.model.MangaUpdate
import kotlin.time.Instant

class MangaRepositoryTest : DatabaseTestBase() {

    private lateinit var mangaRepository: MangaRepositoryImpl

    @BeforeEach
    fun initRepository() {
        mangaRepository = MangaRepositoryImpl(databaseManager)
    }

    private fun sampleManga(
        url: String = "/manga/one-piece",
        title: String = "One Piece",
        source: Long = 100L,
        favorite: Boolean = false,
    ): Manga = Manga(
        id = -1L,
        source = source,
        favorite = favorite,
        lastUpdate = Instant.fromEpochMilliseconds(1000L),
        nextUpdate = Instant.fromEpochMilliseconds(2000L),
        fetchInterval = 7,
        dateAdded = Instant.fromEpochMilliseconds(3000L),
        viewerFlags = 2L,
        chapterFlags = 4L,
        coverLastModified = Instant.fromEpochMilliseconds(5000L),
        url = url,
        title = title,
        artist = "Eiichiro Oda",
        author = "Eiichiro Oda",
        description = "Pirate adventure story",
        genre = listOf("Action", "Adventure", "Comedy"),
        status = 1L,
        thumbnailUrl = "https://example.com/one-piece.jpg",
        updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
        initialized = true,
        notes = "User note about One Piece",
        memo = JsonObject(mapOf("key" to JsonPrimitive("value"))),
        favoriteAt = Instant.fromEpochMilliseconds(6000L),
    )

    @Test
    fun testInsertAndGetById() = runBlocking {
        val manga = sampleManga()
        val generatedId = mangaRepository.insert(manga)
        assertTrue(generatedId > 0)

        val retrieved = mangaRepository.getMangaById(generatedId)
        assertNotNull(retrieved)
        assertEquals(generatedId, retrieved!!.id)
        assertEquals(manga.source, retrieved.source)
        assertEquals(manga.favorite, retrieved.favorite)
        assertEquals(manga.lastUpdate, retrieved.lastUpdate)
        assertEquals(manga.nextUpdate, retrieved.nextUpdate)
        assertEquals(manga.fetchInterval, retrieved.fetchInterval)
        assertEquals(manga.dateAdded, retrieved.dateAdded)
        assertEquals(manga.viewerFlags, retrieved.viewerFlags)
        assertEquals(manga.chapterFlags, retrieved.chapterFlags)
        assertEquals(manga.coverLastModified, retrieved.coverLastModified)
        assertEquals(manga.url, retrieved.url)
        assertEquals(manga.title, retrieved.title)
        assertEquals(manga.artist, retrieved.artist)
        assertEquals(manga.author, retrieved.author)
        assertEquals(manga.description, retrieved.description)
        assertEquals(manga.genre, retrieved.genre)
        assertEquals(manga.status, retrieved.status)
        assertEquals(manga.thumbnailUrl, retrieved.thumbnailUrl)
        assertEquals(manga.updateStrategy, retrieved.updateStrategy)
        assertEquals(manga.initialized, retrieved.initialized)
        assertEquals(manga.notes, retrieved.notes)
        assertEquals(manga.memo, retrieved.memo)
        assertEquals(manga.favoriteAt, retrieved.favoriteAt)
    }

    @Test
    fun testGetByUrlAndSource() = runBlocking {
        val manga = sampleManga(url = "/manga/naruto", title = "Naruto", source = 101L)
        val id = mangaRepository.insert(manga)

        val found = mangaRepository.getMangaByUrlAndSource("/manga/naruto", 101L)
        assertNotNull(found)
        assertEquals(id, found!!.id)
        assertEquals("Naruto", found.title)

        val notFound = mangaRepository.getMangaByUrlAndSource("/manga/bleach", 101L)
        assertNull(notFound)

        val wrongSource = mangaRepository.getMangaByUrlAndSource("/manga/naruto", 999L)
        assertNull(wrongSource)
    }

    @Test
    fun testUpdateFullManga() = runBlocking {
        val manga = sampleManga()
        val id = mangaRepository.insert(manga)
        val inserted = mangaRepository.getMangaById(id)!!

        val updated = inserted.copy(
            title = "One Piece (Updated)",
            description = "New description",
            genre = listOf("Action", "Shounen"),
            status = 2L,
        )
        mangaRepository.update(updated)

        val refreshed = mangaRepository.getMangaById(id)!!
        assertEquals("One Piece (Updated)", refreshed.title)
        assertEquals("New description", refreshed.description)
        assertEquals(listOf("Action", "Shounen"), refreshed.genre)
        assertEquals(2L, refreshed.status)
    }

    @Test
    fun testUpdatePartialManga() = runBlocking {
        val manga = sampleManga()
        val id = mangaRepository.insert(manga)

        val partial = MangaUpdate(id) {
            viewerFlags = 99L
            notes = "Updated user note"
        }
        mangaRepository.update(partial)

        val refreshed = mangaRepository.getMangaById(id)!!
        assertEquals(99L, refreshed.viewerFlags)
        assertEquals("Updated user note", refreshed.notes)
        // Verify untouched columns remain identical
        assertEquals(manga.title, refreshed.title)
        assertEquals(manga.author, refreshed.author)
        assertEquals(manga.url, refreshed.url)
    }

    @Test
    fun testUpdateRemoteManga() = runBlocking {
        val manga = sampleManga()
        val id = mangaRepository.insert(manga)

        val remoteUpdate = MangaRemoteUpdate(
            id = id,
            title = "One Piece Remastered",
            author = "Oda",
            artist = "Oda",
            description = "High quality remake",
            genre = listOf("Action"),
            status = 3L,
            thumbnailUrl = "https://example.com/remastered.jpg",
            updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE,
            memo = JsonObject(emptyMap()),
            initialized = true,
            coverLastModified = Instant.fromEpochMilliseconds(99999L),
        )
        mangaRepository.update(remoteUpdate)

        val refreshed = mangaRepository.getMangaById(id)!!
        assertEquals("One Piece Remastered", refreshed.title)
        assertEquals("High quality remake", refreshed.description)
        assertEquals(3L, refreshed.status)
        assertEquals(UpdateStrategy.ONLY_FETCH_ONCE, refreshed.updateStrategy)
        assertEquals(Instant.fromEpochMilliseconds(99999L), refreshed.coverLastModified)
    }

    @Test
    fun testGetFavoritesAndFlow() = runBlocking {
        val m1 = sampleManga(url = "/m1", title = "Favorite Manga", favorite = true)
        val m2 = sampleManga(url = "/m2", title = "Non-Favorite Manga", favorite = false)

        mangaRepository.insert(m1)
        mangaRepository.insert(m2)

        val favorites = mangaRepository.getFavorites()
        assertEquals(1, favorites.size)
        assertEquals("Favorite Manga", favorites[0].title)

        val initialFlowEmission = mangaRepository.getFavoritesAsFlow().first()
        assertEquals(1, initialFlowEmission.size)
        assertEquals("Favorite Manga", initialFlowEmission[0].title)

        // Reactive flow emission test
        val flowResults = mutableListOf<List<Manga>>()
        val job = launch(Dispatchers.Default) {
            mangaRepository.getFavoritesAsFlow().toList(flowResults)
        }

        withTimeout(5000) {
            while (flowResults.isEmpty()) {
                delay(20)
            }
        }

        // Insert second favorite to trigger flow emission
        val m3 = sampleManga(url = "/m3", title = "Another Favorite", favorite = true)
        mangaRepository.insert(m3)

        withTimeout(5000) {
            while (flowResults.size < 2) {
                delay(20)
            }
        }

        job.cancel()
        assertEquals(2, flowResults.size)
        assertEquals(1, flowResults[0].size)
        assertEquals(2, flowResults[1].size)
    }

    @Test
    fun testGetMangasByIds() = runBlocking {
        val id1 = mangaRepository.insert(sampleManga(url = "/m1", title = "Title 1"))
        val id2 = mangaRepository.insert(sampleManga(url = "/m2", title = "Title 2"))
        val id3 = mangaRepository.insert(sampleManga(url = "/m3", title = "Title 3"))

        val subset = mangaRepository.getMangasByIds(listOf(id1, id3))
        assertEquals(2, subset.size)
        assertTrue(subset.any { it.id == id1 })
        assertTrue(subset.any { it.id == id3 })
        assertFalse(subset.any { it.id == id2 })
    }

    @Test
    fun testDeleteAndClear() = runBlocking {
        val id1 = mangaRepository.insert(sampleManga(url = "/m1", title = "Title 1"))
        val id2 = mangaRepository.insert(sampleManga(url = "/m2", title = "Title 2"))

        mangaRepository.delete(id1)
        assertNull(mangaRepository.getMangaById(id1))
        assertNotNull(mangaRepository.getMangaById(id2))

        val deletedAll = mangaRepository.deleteAll()
        assertTrue(deletedAll)
        assertTrue(mangaRepository.getAll().isEmpty())
    }

    @Test
    fun testResetViewerFlags() = runBlocking {
        val id1 = mangaRepository.insert(sampleManga(url = "/m1", title = "Title 1").copy(viewerFlags = 10L))
        val id2 = mangaRepository.insert(sampleManga(url = "/m2", title = "Title 2").copy(viewerFlags = 20L))

        mangaRepository.resetViewerFlags()

        assertEquals(0L, mangaRepository.getMangaById(id1)!!.viewerFlags)
        assertEquals(0L, mangaRepository.getMangaById(id2)!!.viewerFlags)
    }
}
