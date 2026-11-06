package mihon.source

interface ExtensionSource {
    val name: String
    val baseUrl: String
    val language: String
    val id: Long
    val isNsfw: Boolean

    fun searchManga(query: String): List<MangaResult>
    fun getPopularManga(): List<MangaResult> = searchManga("a")
    fun getMangaDetails(url: String): MangaDetails
    fun getChapterImages(url: String): List<String>
}

data class MangaResult(
    val title: String,
    val url: String,
    val coverUrl: String?,
    val author: String?,
    val latestChapter: String?
)

data class MangaDetails(
    val title: String,
    val author: String?,
    val artist: String?,
    val description: String?,
    val coverUrl: String?,
    val chapters: List<ChapterResult>
)

data class ChapterResult(
    val title: String,
    val url: String,
    val volume: Int?,
    val chapter: Float?
)
