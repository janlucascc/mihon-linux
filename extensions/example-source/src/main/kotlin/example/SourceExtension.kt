package example

import mihon.source.ExtensionSource
import mihon.source.MangaResult
import mihon.source.MangaDetails
import mihon.source.ChapterResult

class SourceExtension : ExtensionSource {
    override val name: String = "Example Source (Demo)"
    override val baseUrl: String = "https://example.com"
    override val language: String = "en"
    override val id: Long = 1
    override val isNsfw: Boolean = false

    override fun searchManga(query: String): List<MangaResult> {
        return listOf(
            MangaResult("Demo Manga 1", "/manga/1", null, "Autor Demo", "Capítulo 5"),
            MangaResult("Demo Manga 2", "/manga/2", null, "Autor Demo 2", "Capítulo 3")
        )
    }

    override fun getMangaDetails(url: String): MangaDetails {
        return MangaDetails(
            title = "Demo Manga",
            author = "Autor Demo",
            artist = "Artista Demo",
            description = "Mangá de demonstração para o sistema de extensões do Mihon Linux.",
            coverUrl = null,
            chapters = listOf(
                ChapterResult("Capítulo 1", "/chapter/1", 1, 1.0f),
                ChapterResult("Capítulo 2", "/chapter/2", 1, 2.0f)
            )
        )
    }

    override fun getChapterImages(url: String): List<String> {
        return listOf(
            "https://example.com/page1.jpg",
            "https://example.com/page2.jpg"
        )
    }
}
