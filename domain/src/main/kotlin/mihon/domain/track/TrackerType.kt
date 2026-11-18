package mihon.domain.track

enum class TrackerType(
    val displayName: String,
    val baseUrl: String,
    val supportsOAuth: Boolean = true,
    val supportsSearch: Boolean = true,
    val supportsSync: Boolean = true,
) {
    ANILIST(
        displayName = "AniList",
        baseUrl = "https://graphql.anilist.co",
        supportsOAuth = true,
    ),
    MYANIMELIST(
        displayName = "MyAnimeList",
        baseUrl = "https://api.myanimelist.net/v2",
        supportsOAuth = true,
    ),
    KITSU(
        displayName = "Kitsu",
        baseUrl = "https://kitsu.app/api",
        supportsOAuth = true,
    ),
    MANGAUPDATES(
        displayName = "MangaUpdates",
        baseUrl = "https://www.mangaupdates.com",
        supportsOAuth = false,
        supportsSearch = true,
        supportsSync = true,
    ),
    SHIKIMORI(
        displayName = "Shikimori",
        baseUrl = "https://shikimori.one/api",
        supportsOAuth = true,
    ),
    BANGUMI(
        displayName = "Bangumi",
        baseUrl = "https://bgm.tv/api",
        supportsOAuth = true,
    ),
    HIKKA(
        displayName = "Hikka (Self-hosted)",
        baseUrl = "",
        supportsOAuth = true,
    ),
    LOCAL(
        displayName = "Local Tracker",
        baseUrl = "",
        supportsOAuth = false,
        supportsSearch = false,
        supportsSync = false,
    ),
}