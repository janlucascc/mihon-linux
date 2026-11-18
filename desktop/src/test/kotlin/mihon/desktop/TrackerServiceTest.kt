package mihon.desktop

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.prefs.Preferences
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackerServiceTest {

    private val prefs = Preferences.userRoot().node("mihon-linux-trackers")
    private val testMangaId = 9999991L

    @BeforeEach
    fun setUp() {
        prefs.remove("track_$testMangaId")
    }

    @AfterEach
    fun tearDown() {
        prefs.remove("track_$testMangaId")
    }

    @Test
    fun testProviderEnums() {
        val providers = TrackerService.Provider.entries
        assertEquals(3, providers.size)
        assertTrue(providers.contains(TrackerService.Provider.ANILIST))
        assertTrue(providers.contains(TrackerService.Provider.MYANIMELIST))
        assertTrue(providers.contains(TrackerService.Provider.LOCAL))

        assertEquals("AniList (GraphQL)", TrackerService.Provider.ANILIST.displayName)
        assertEquals("MyAnimeList (API v2)", TrackerService.Provider.MYANIMELIST.displayName)
        assertEquals("Rastreador Local (Offline)", TrackerService.Provider.LOCAL.displayName)
    }

    @Test
    fun testTrackStatusMappings() {
        assertEquals("CURRENT", TrackerService.TrackStatus.READING.anilistValue)
        assertEquals("reading", TrackerService.TrackStatus.READING.malValue)

        assertEquals("COMPLETED", TrackerService.TrackStatus.COMPLETED.anilistValue)
        assertEquals("completed", TrackerService.TrackStatus.COMPLETED.malValue)

        assertEquals("PLANNING", TrackerService.TrackStatus.PLANNING.anilistValue)
        assertEquals("plan_to_read", TrackerService.TrackStatus.PLANNING.malValue)

        assertEquals("PAUSED", TrackerService.TrackStatus.PAUSED.anilistValue)
        assertEquals("on_hold", TrackerService.TrackStatus.PAUSED.malValue)

        assertEquals("DROPPED", TrackerService.TrackStatus.DROPPED.anilistValue)
        assertEquals("dropped", TrackerService.TrackStatus.DROPPED.malValue)
    }

    @Test
    fun testPreferencesTokensAndAutoSync() {
        val origAnilist = TrackerService.anilistToken
        val origMal = TrackerService.malToken
        val origAutoSync = TrackerService.autoSyncOnRead

        try {
            TrackerService.anilistToken = "token_ani_123"
            assertEquals("token_ani_123", TrackerService.anilistToken)

            TrackerService.malToken = "token_mal_456"
            assertEquals("token_mal_456", TrackerService.malToken)

            TrackerService.autoSyncOnRead = false
            assertFalse(TrackerService.autoSyncOnRead)

            TrackerService.autoSyncOnRead = true
            assertTrue(TrackerService.autoSyncOnRead)
        } finally {
            TrackerService.anilistToken = origAnilist
            TrackerService.malToken = origMal
            TrackerService.autoSyncOnRead = origAutoSync
        }
    }

    @Test
    fun testSaveAndGetRecordRoundtrip() {
        assertNull(TrackerService.getRecord(testMangaId))

        val record = TrackerService.TrackRecord(
            mangaId = testMangaId,
            provider = TrackerService.Provider.ANILIST.name,
            remoteId = 12345L,
            remoteTitle = "Chainsaw Man",
            status = TrackerService.TrackStatus.READING.name,
            lastChapterRead = 42,
            totalChapters = 150,
            score = 9.5,
        )

        TrackerService.saveRecord(record)

        val loaded = TrackerService.getRecord(testMangaId)
        assertNotNull(loaded)
        assertEquals(testMangaId, loaded.mangaId)
        assertEquals("ANILIST", loaded.provider)
        assertEquals(12345L, loaded.remoteId)
        assertEquals("Chainsaw Man", loaded.remoteTitle)
        assertEquals("READING", loaded.status)
        assertEquals(42, loaded.lastChapterRead)
        assertEquals(150, loaded.totalChapters)
        assertEquals(9.5, loaded.score)

        TrackerService.removeRecord(testMangaId)
        assertNull(TrackerService.getRecord(testMangaId))
    }

    @Test
    fun testLocalSearchGeneratesDeterministicResult() {
        val okHttpClient = okhttp3.OkHttpClient()
        val emptyResults = TrackerService.searchRemote(okHttpClient, TrackerService.Provider.LOCAL, "   ")
        assertTrue(emptyResults.isEmpty())

        val results = TrackerService.searchRemote(okHttpClient, TrackerService.Provider.LOCAL, "Berserk")
        assertEquals(1, results.size)
        val first = results[0]
        assertEquals(TrackerService.Provider.LOCAL, first.provider)
        assertEquals("Berserk", first.title)
        assertTrue(first.remoteId > 0)
        assertEquals("Local", first.statusText)
        assertTrue(first.toString().contains("Berserk"))
    }
}
