package mihon.data.extension

import kotlinx.serialization.json.Json
import mihon.source.ExtensionSource
import mihon.source.HttpScraperSource
import mihon.source.MangaDexSource
import mihon.source.ScraperConfig
import java.io.File
import java.net.URLClassLoader

class ExtensionLoader(
    private val extensionsDir: File,
    private val sourcesDir: File = File(extensionsDir.parentFile ?: extensionsDir, "sources"),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun loadExtensions(): List<ExtensionSource> {
        val extensions = mutableListOf<ExtensionSource>()
        extensions.add(
            HttpScraperSource(
                ScraperConfig(
                    name = "Manga Livre (PT-BR)",
                    baseUrl = "https://mangalivre.to",
                    language = "pt-br",
                    searchPath = "/?s={query}&post_type=wp-manga",
                    popularPath = "/?s=&post_type=wp-manga&m_orderby=trending",
                    searchItem = "div.c-tabs-item__content, div.page-item-detail",
                    searchTitle = ".post-title h3 a, .post-title a",
                    searchLink = ".post-title h3 a, .post-title a",
                    searchCover = ".tab-thumb img, .item-thumb img",
                    searchAuthor = ".mg_author .summary-content",
                    searchLatestChapter = ".latest-chap .chapter a, .archive-total-chapters",
                    detailsTitle = ".post-title h1",
                    detailsAuthor = ".author-content a",
                    detailsArtist = ".artist-content a",
                    detailsDescription = ".summary__content p",
                    detailsCover = ".summary_image img",
                    chapterItem = ".listing-chapters-wrap .chapter-box, li.wp-manga-chapter",
                    chapterLink = "a",
                    reverseChapters = true,
                    pageImage = ".reading-content img.wp-manga-chapter-img, .reading-content .page-break img",
                ),
            ),
        )
        extensions.add(MangaDexSource("pt-br"))

        // Carrega extensões locais/privadas compiladas ou opcionais dinamicamente via reflexão
        runCatching {
            val tiaClass = Class.forName("mihon.source.HentaiDaTiaSource")
            val instance = tiaClass.getDeclaredConstructor().newInstance() as ExtensionSource
            extensions.add(instance)
        }

        if (!extensionsDir.exists()) extensionsDir.mkdirs()
        if (!sourcesDir.exists()) {
            sourcesDir.mkdirs()
            writeSampleConfig()
        }

        // 1. Load JAR extensions
        extensionsDir.listFiles { f -> f.extension.equals("jar", ignoreCase = true) }
            ?.forEach { jarFile ->
                try {
                    val classLoader = URLClassLoader(arrayOf(jarFile.toURI().toURL()), ExtensionSource::class.java.classLoader)
                    val clazz = classLoader.loadClass("example.SourceExtension")
                    val extension = clazz.getDeclaredConstructor().newInstance() as ExtensionSource
                    extensions.add(extension)
                    println("Extensão JAR carregada: ${extension.name}")
                } catch (e: Exception) {
                    println("Erro ao carregar extensão ${jarFile.name}: ${e.message}")
                }
            }

        // 2. Load JSON ScraperConfig sources
        sourcesDir.listFiles { f -> f.extension.equals("json", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.forEach { jsonFile ->
                try {
                    val config = json.decodeFromString<ScraperConfig>(jsonFile.readText())
                    extensions.add(HttpScraperSource(config))
                } catch (e: Exception) {
                    println("Erro ao carregar fonte JSON ${jsonFile.name}: ${e.message}")
                }
            }

        return extensions
    }

    private fun writeSampleConfig() {
        val sample = File(sourcesDir, "exemplo-template.json.disabled")
        if (sample.exists()) return
        sample.writeText(
            """
            {
              "name": "Exemplo de Fonte CSS",
              "baseUrl": "https://exemplo.org",
              "language": "pt-BR",
              "searchPath": "/search?q={query}",
              "searchItem": "div.manga-item",
              "searchTitle": "h3.title",
              "searchLink": "a",
              "searchCover": "img",
              "detailsTitle": "h1.manga-title",
              "detailsAuthor": "span.author",
              "detailsDescription": "div.synopsis",
              "detailsCover": "div.cover img",
              "chapterItem": "ul.chapters li",
              "chapterLink": "a",
              "pageImage": "div.reader-area img"
            }
            """.trimIndent(),
        )
    }
}
