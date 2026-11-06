package mihon.data.repository

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Instant

class M1DataBoundaryAdversarialTest : DatabaseTestBase() {

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
    fun testEmojisAndMultiByteUtf8Integrity() = runBlocking {
        val complexMemo = buildJsonObject {
            put("emoji_tag", JsonPrimitive("🏴‍☠️🔥⚡🚀✨"))
            put("japanese", JsonPrimitive("進撃の巨人 / 鬼滅の刃 / 呪術廻戦"))
            put("arabic", JsonPrimitive("مرحبا بالعالم"))
            put("hebrew", JsonPrimitive("שלום עולם"))
            put("special_chars", JsonPrimitive("!@#$%^&*()_+-=[]{}|;':\",./<>?`~"))
            putJsonObject("nested") {
                put("inner_utf8", JsonPrimitive("日本語テスト 🌸💮"))
                put("code", JsonPrimitive(42))
            }
        }

        val complexManga = Manga(
            id = -1L,
            source = 42L,
            favorite = true,
            lastUpdate = Instant.fromEpochMilliseconds(1700000000000L),
            nextUpdate = Instant.fromEpochMilliseconds(1700003600000L),
            fetchInterval = 1,
            dateAdded = Instant.fromEpochMilliseconds(1690000000000L),
            viewerFlags = 1L,
            chapterFlags = 2L,
            coverLastModified = Instant.fromEpochMilliseconds(1700000000000L),
            url = "/manga/ワンピース-one-piece-🗡️",
            title = "🏴‍☠️ ONE PIECE ⚔️ ワンピース (Original) 🌸",
            artist = "尾田 栄一郎 🎨 (Eiichiro Oda)",
            author = "尾田 栄一郎 ✍️",
            description = "海賊王に、俺はなる！ 🌊🚢🏴‍☠️\nMulti-line description with quotes: \"It's real!\" and 'single' and \nline breaks.",
            genre = listOf("Action 💥", "Adventure 🗺️", "Shōnen 少年"),
            status = 1L,
            thumbnailUrl = "https://cdn.example.org/covers/ワンピース.jpg?sig=✨&id=123",
            updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
            initialized = true,
            notes = "User note: <script>alert('xss');</script> & Robert'); DROP TABLE manga;-- 🛡️",
            memo = complexMemo,
            favoriteAt = Instant.fromEpochMilliseconds(1695000000000L),
        )

        val mangaId = mangaRepository.insert(complexManga)
        assertTrue(mangaId > 0)

        val retrievedManga = mangaRepository.getMangaById(mangaId)
        assertNotNull(retrievedManga)
        assertEquals(complexManga.title, retrievedManga!!.title)
        assertEquals(complexManga.artist, retrievedManga.artist)
        assertEquals(complexManga.author, retrievedManga.author)
        assertEquals(complexManga.description, retrievedManga.description)
        assertEquals(complexManga.genre, retrievedManga.genre)
        assertEquals(complexManga.notes, retrievedManga.notes)
        assertEquals(complexManga.url, retrievedManga.url)
        assertEquals(complexManga.thumbnailUrl, retrievedManga.thumbnailUrl)
        assertEquals(complexMemo, retrievedManga.memo)

        // Chapter with UTF-8 and SQL characters
        val complexChapter = Chapter(
            id = -1L,
            mangaId = mangaId,
            read = true,
            bookmark = true,
            lastPageRead = 45,
            dateFetch = Instant.fromEpochMilliseconds(1700001000000L),
            sourceOrder = 100L,
            url = "/ch/第1000話-麦わらのルフィ-🔥",
            name = "第1000話: 麦わらのルフィ 👒 (Straw Hat Luffy) 💥",
            dateUpload = Instant.fromEpochMilliseconds(1680000000000L),
            chapterNumber = 1000.0,
            scanlator = "ScanGroup 🌸 日本語翻訳'); DROP TABLE chapter;--",
            memo = complexMemo,
        )

        val insertedChapters = chapterRepository.updateFromRemote(emptyList(), listOf(complexChapter), emptyList())
        assertEquals(1, insertedChapters.size)
        val chapterId = insertedChapters[0].id

        val retrievedChapter = chapterRepository.getChapterById(chapterId)
        assertNotNull(retrievedChapter)
        assertEquals(complexChapter.name, retrievedChapter!!.name)
        assertEquals(complexChapter.url, retrievedChapter.url)
        assertEquals(complexChapter.scanlator, retrievedChapter.scanlator)
        assertEquals(complexMemo, retrievedChapter.memo)
    }

    @Test
    fun testComprehensiveNullAndEmptyFields() = runBlocking {
        // Minimal Manga with all nullable fields null
        val nullManga = Manga(
            id = -1L,
            source = 0L,
            favorite = false,
            lastUpdate = Instant.fromEpochMilliseconds(0),
            nextUpdate = Instant.fromEpochMilliseconds(0),
            fetchInterval = 0,
            dateAdded = Instant.fromEpochMilliseconds(0),
            viewerFlags = 0L,
            chapterFlags = 0L,
            coverLastModified = Instant.fromEpochMilliseconds(0),
            url = "/null-manga",
            title = "Null Manga",
            artist = null,
            author = null,
            description = null,
            genre = null,
            status = 0L,
            thumbnailUrl = null,
            updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
            initialized = false,
            notes = null,
            memo = JsonObject.EMPTY,
            favoriteAt = null,
        )

        val mangaId = mangaRepository.insert(nullManga)
        val retrieved = mangaRepository.getMangaById(mangaId)!!

        assertNull(retrieved.artist)
        assertNull(retrieved.author)
        assertNull(retrieved.description)
        assertNull(retrieved.genre)
        assertTrue(retrieved.genres.isEmpty())
        assertNull(retrieved.thumbnailUrl)
        assertNull(retrieved.notes)
        assertNull(retrieved.favoriteAt)
        assertEquals(JsonObject.EMPTY, retrieved.memo)

        // Chapter with null nullable fields
        val nullChapter = Chapter(
            id = -1L,
            mangaId = mangaId,
            read = false,
            bookmark = false,
            lastPageRead = 0,
            dateFetch = Instant.fromEpochMilliseconds(0),
            sourceOrder = 0L,
            url = "/null-ch",
            name = "Null Chapter",
            dateUpload = null,
            chapterNumber = -1.0,
            scanlator = null,
            memo = JsonObject.EMPTY,
        )

        val chs = chapterRepository.updateFromRemote(emptyList(), listOf(nullChapter), emptyList())
        val retrievedCh = chapterRepository.getChapterById(chs[0].id)!!

        assertNull(retrievedCh.dateUpload)
        assertNull(retrievedCh.scanlator)
        assertEquals(JsonObject.EMPTY, retrievedCh.memo)

        // History with null readAt
        val nullHistory = History(
            id = -1L,
            chapterId = chs[0].id,
            readAt = null,
            readDuration = 0L,
        )
        val insertedHistory = historyRepository.upsert(nullHistory)
        val retrievedHistory = historyRepository.getHistoryById(insertedHistory.id)!!
        assertNull(retrievedHistory.readAt)
        assertEquals(0L, retrievedHistory.readDuration)
    }

    @Test
    fun testBoundaryChapterNumbers() = runBlocking {
        val mangaId = mangaRepository.insert(
            Manga.create().copy(url = "/boundary-manga", title = "Boundary Numbers Test"),
        )

        // Insert chapters with boundary chapter numbers:
        // -1.0 (unrecognized / special)
        // -10.5 (negative fractional)
        // 0.0 (prologue)
        // 0.5 (fractional)
        // 9999.5 (large fractional)
        val boundaryChapters = listOf(
            Chapter.create().copy(mangaId = mangaId, url = "/c/spec", name = "Special", chapterNumber = -1.0, sourceOrder = 1L),
            Chapter.create().copy(mangaId = mangaId, url = "/c/neg", name = "Negative", chapterNumber = -10.5, sourceOrder = 2L),
            Chapter.create().copy(mangaId = mangaId, url = "/c/0", name = "Chapter 0 (Prologue)", chapterNumber = 0.0, sourceOrder = 3L),
            Chapter.create().copy(mangaId = mangaId, url = "/c/0.5", name = "Chapter 0.5", chapterNumber = 0.5, sourceOrder = 4L),
            Chapter.create().copy(mangaId = mangaId, url = "/c/9999.5", name = "Chapter 9999.5", chapterNumber = 9999.5, sourceOrder = 5L),
        )

        val inserted = chapterRepository.updateFromRemote(emptyList(), boundaryChapters, emptyList())
        assertEquals(5, inserted.size)

        // Verify individual numbers and recognition status
        val chSpec = chapterRepository.getChapterByUrlAndMangaId("/c/spec", mangaId)!!
        assertEquals(-1.0, chSpec.chapterNumber)
        assertFalse(chSpec.isRecognizedNumber)

        val chNeg = chapterRepository.getChapterByUrlAndMangaId("/c/neg", mangaId)!!
        assertEquals(-10.5, chNeg.chapterNumber)
        assertFalse(chNeg.isRecognizedNumber)

        val ch0 = chapterRepository.getChapterByUrlAndMangaId("/c/0", mangaId)!!
        assertEquals(0.0, ch0.chapterNumber)
        assertTrue(ch0.isRecognizedNumber)

        val ch05 = chapterRepository.getChapterByUrlAndMangaId("/c/0.5", mangaId)!!
        assertEquals(0.5, ch05.chapterNumber)
        assertTrue(ch05.isRecognizedNumber)

        val ch99995 = chapterRepository.getChapterByUrlAndMangaId("/c/9999.5", mangaId)!!
        assertEquals(9999.5, ch99995.chapterNumber)
        assertTrue(ch99995.isRecognizedNumber)

        // Verify ChapterSummary calculation
        val summary = chapterRepository.getChapterSummaryByMangaId(mangaId)
        assertEquals(5L, summary.chapterCount)
        assertEquals(9999.5, summary.latestChapterNumber)

        // Verify summary when only unnumbered chapters exist
        val mangaOnlySpecId = mangaRepository.insert(
            Manga.create().copy(url = "/only-spec", title = "Only Specials"),
        )
        chapterRepository.updateFromRemote(
            emptyList(),
            listOf(
                Chapter.create().copy(mangaId = mangaOnlySpecId, url = "/s1", name = "S1", chapterNumber = -1.0),
                Chapter.create().copy(mangaId = mangaOnlySpecId, url = "/s2", name = "S2", chapterNumber = -5.0),
            ),
            emptyList(),
        )
        val specSummary = chapterRepository.getChapterSummaryByMangaId(mangaOnlySpecId)
        assertEquals(2L, specSummary.chapterCount)
        assertNull(specSummary.latestChapterNumber)
    }

    @Test
    fun testHugeReadingSessionDurations() = runBlocking {
        val mangaId = mangaRepository.insert(
            Manga.create().copy(url = "/duration-manga", title = "Duration Test"),
        )
        val chapters = chapterRepository.updateFromRemote(
            emptyList(),
            listOf(Chapter.create().copy(mangaId = mangaId, url = "/c/dur", name = "Ch 1")),
            emptyList(),
        )
        val chId = chapters[0].id

        // 1. First session: 10 billion ms (10,000,000,000L)
        val duration1 = 10_000_000_000L
        val h1 = historyRepository.upsert(
            HistoryUpdate(
                chapterId = chId,
                readAt = Instant.fromEpochMilliseconds(1000L),
                sessionReadDuration = duration1,
            ),
        )
        assertEquals(duration1, h1.readDuration)

        val retrieved1 = historyRepository.getHistoryByChapterId(chId)!!
        assertEquals(duration1, retrieved1.readDuration)

        // 2. Accumulate second session: 5 billion ms -> total 15 billion ms
        val duration2 = 5_000_000_000L
        val h2 = historyRepository.upsert(
            HistoryUpdate(
                chapterId = chId,
                readAt = Instant.fromEpochMilliseconds(2000L),
                sessionReadDuration = duration2,
            ),
        )
        assertEquals(15_000_000_000L, h2.readDuration)

        val retrieved2 = historyRepository.getHistoryByChapterId(chId)!!
        assertEquals(15_000_000_000L, retrieved2.readDuration)

        // 3. Direct upsert with near-max 64-bit signed integer
        val hugeDuration = Long.MAX_VALUE - 5000L
        val directHistory = History(
            id = h2.id,
            chapterId = chId,
            readAt = Instant.fromEpochMilliseconds(3000L),
            readDuration = hugeDuration,
        )
        val h3 = historyRepository.upsert(directHistory)
        assertEquals(hugeDuration, h3.readDuration)

        val retrieved3 = historyRepository.getHistoryById(h3.id)!!
        assertEquals(hugeDuration, retrieved3.readDuration)
    }
}
