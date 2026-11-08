package mihon.source

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Beta URL-to-Extension Generator:
 * Given a manga website URL, inspects the HTML structure (WordPress Madara, MangaStream/Themesia,
 * or Generic Manga CMS), generates a [ScraperConfig], writes it to `~/.mihon/sources/<domain>.json`,
 * and returns a ready-to-use [HttpScraperSource].
 */
object UrlExtensionGenerator {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class GeneratedExtensionResult(
        val source: HttpScraperSource,
        val config: ScraperConfig,
        val jsonFile: File,
        val detectedEngine: String,
    )

    fun generateFromUrl(
        rawUrl: String,
        customName: String? = null,
        sourcesDir: File = File(System.getProperty("user.home"), ".mihon/sources"),
    ): GeneratedExtensionResult {
        val normalized = normalizeBaseUrl(rawUrl)
        val uri = URI(normalized)
        val host = (uri.host ?: throw IllegalArgumentException("URL inválida: $rawUrl"))
            .removePrefix("www.")
        val baseUrl = "${uri.scheme}://${uri.authority}"

        val doc = runCatching { fetchDocument(normalized) }.getOrNull()
        val detectedName = customName?.trim()?.takeIf { it.isNotEmpty() }
            ?: extractSiteName(doc, host)
        val detectedLang = detectLanguage(doc, host)
        val isNsfw = detectNsfw(doc, host, detectedName)
        val engine = detectEngine(doc)

        val config = when (engine) {
            "Madara (WordPress)" -> buildMadaraConfig(detectedName, baseUrl, detectedLang, isNsfw)
            "MangaStream / Themesia" -> buildMangaStreamConfig(detectedName, baseUrl, detectedLang, isNsfw)
            else -> buildUniversalConfig(detectedName, baseUrl, detectedLang, isNsfw)
        }

        if (!sourcesDir.exists()) sourcesDir.mkdirs()
        val safeFileName = host.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val jsonFile = File(sourcesDir, "$safeFileName.json")
        jsonFile.writeText(json.encodeToString(config))

        return GeneratedExtensionResult(
            source = HttpScraperSource(config),
            config = config,
            jsonFile = jsonFile,
            detectedEngine = engine,
        )
    }

    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Informe o link do site de mangá." }
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return withScheme.trimEnd('/')
    }

    private fun fetchDocument(url: String): Document {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            return Jsoup.parse(body, url)
        }
    }

    private fun extractSiteName(doc: Document?, host: String): String {
        if (doc != null) {
            val ogSiteName = doc.selectFirst("meta[property=og:site_name]")?.attr("content")?.trim()
            if (!ogSiteName.isNullOrBlank() && ogSiteName.length <= 36) return ogSiteName

            val rawTitle = doc.title().trim()
            if (rawTitle.isNotBlank()) {
                val cleaned = rawTitle.split(Regex("\\s+[-–—|•:]\\s+")).firstOrNull()?.trim().orEmpty()
                if (cleaned.isNotBlank() && cleaned.length <= 36) return cleaned
            }
        }
        val domainCore = host.substringBeforeLast('.').replace('-', ' ').replace('.', ' ')
        return domainCore.split(' ').filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
            .ifBlank { host }
    }

    private fun detectLanguage(doc: Document?, host: String): String {
        val htmlLang = doc?.selectFirst("html")?.attr("lang")?.trim()?.lowercase().orEmpty()
        if (htmlLang.startsWith("pt")) return "pt-br"
        if (htmlLang.startsWith("es")) return "es"
        if (htmlLang.startsWith("en")) return "en"
        if (host.endsWith(".br") || host.contains("manga") || host.contains("ler") || host.contains("scan") || host.contains("br")) {
            return "pt-br"
        }
        return "pt-br"
    }

    private fun detectNsfw(doc: Document?, host: String, name: String): Boolean {
        val combined = "${host.lowercase()} ${name.lowercase()} ${doc?.title()?.lowercase().orEmpty()}"
        return listOf("hentai", "18+", "+18", "adult", "nsfw", "doujin", "ecchi", "xxx").any { combined.contains(it) }
    }

    private fun detectEngine(doc: Document?): String {
        if (doc == null) return "Universal / Híbrido (Madara + MangaStream)"
        val html = doc.outerHtml()
        return when {
            html.contains("wp-manga") ||
                doc.selectFirst("div.c-tabs-item__content, div.page-item-detail, li.wp-manga-chapter") != null ->
                "Madara (WordPress)"
            html.contains("ts-main-image") ||
                doc.selectFirst("div.bsx, div.listupd, div.eph-num, #readerarea") != null ->
                "MangaStream / Themesia"
            else -> "Universal / Híbrido (Madara + MangaStream)"
        }
    }

    private fun buildMadaraConfig(name: String, baseUrl: String, lang: String, isNsfw: Boolean) = ScraperConfig(
        name = name,
        baseUrl = baseUrl,
        language = lang,
        isNsfw = isNsfw,
        searchPath = "/?s={query}&post_type=wp-manga",
        popularPath = "/?s=&post_type=wp-manga&m_orderby=trending",
        searchItem = "div.c-tabs-item__content, div.page-item-detail, div.manga__item",
        searchTitle = ".post-title h3 a, .post-title h4 a, .post-title a, h3 a",
        searchLink = ".post-title h3 a, .post-title a, a",
        searchCover = ".tab-thumb img, .item-thumb img, img",
        searchAuthor = ".mg_author .summary-content",
        searchLatestChapter = ".latest-chap .chapter a, .chapter-item .chapter a",
        detailsTitle = ".post-title h1, h1.entry-title, h1",
        detailsAuthor = ".author-content a",
        detailsArtist = ".artist-content a",
        detailsDescription = ".summary__content p, .description-summary p, .manga-excerpt",
        detailsCover = ".summary_image img",
        chapterItem = ".listing-chapters-wrap .chapter-box, li.wp-manga-chapter, .version-chap li",
        chapterLink = "a",
        reverseChapters = true,
        pageImage = ".reading-content img.wp-manga-chapter-img, .reading-content .page-break img, .reading-content img",
    )

    private fun buildMangaStreamConfig(name: String, baseUrl: String, lang: String, isNsfw: Boolean) = ScraperConfig(
        name = name,
        baseUrl = baseUrl,
        language = lang,
        isNsfw = isNsfw,
        searchPath = "/?s={query}",
        popularPath = "/manga/?order=popular",
        searchItem = "div.bs .bsx, div.listupd .bsx, div.utao .uta, article.bs",
        searchTitle = "a[title], .tt, h2, h3",
        searchLink = "a",
        searchCover = "img",
        searchLatestChapter = ".epxs, .egc",
        detailsTitle = "h1.entry-title, .infox h1, h1",
        detailsAuthor = ".fmed b:contains(Autor)+span, .imptdt:contains(Author) i, .author",
        detailsDescription = ".entry-content.entry-content-single p, .desc p, .synopsis",
        detailsCover = ".thumb img, .infomanga img",
        chapterItem = "#chapterlist li, .eplister ul li, .bxcl ul li",
        chapterLink = ".eph-num a, a",
        reverseChapters = true,
        pageImage = "#readerarea img, .rdminimal img, .reader-area img",
    )

    /**
     * Multi-selector hybrid configuration that works across Madara, MangaStream/Themesia,
     * and common Brazilian/International manga portals out of the box.
     */
    private fun buildUniversalConfig(name: String, baseUrl: String, lang: String, isNsfw: Boolean) = ScraperConfig(
        name = name,
        baseUrl = baseUrl,
        language = lang,
        isNsfw = isNsfw,
        searchPath = "/?s={query}&post_type=wp-manga",
        popularPath = "/?s=&post_type=wp-manga&m_orderby=trending",
        searchItem = "div.c-tabs-item__content, div.page-item-detail, div.bs .bsx, div.listupd .bsx, div.manga-item, article.item",
        searchTitle = ".post-title h3 a, .post-title a, .tt, h3.title, h3 a, h2 a, a[title]",
        searchLink = ".post-title a, a",
        searchCover = ".tab-thumb img, .item-thumb img, img",
        searchAuthor = ".mg_author .summary-content, .author",
        searchLatestChapter = ".latest-chap .chapter a, .epxs",
        detailsTitle = ".post-title h1, h1.entry-title, h1.manga-title, h1",
        detailsAuthor = ".author-content a, span.author",
        detailsArtist = ".artist-content a",
        detailsDescription = ".summary__content p, .entry-content-single p, div.synopsis, .description-summary",
        detailsCover = ".summary_image img, .thumb img, div.cover img",
        chapterItem = ".listing-chapters-wrap .chapter-box, li.wp-manga-chapter, #chapterlist li, .eplister ul li, ul.chapters li",
        chapterLink = "a",
        reverseChapters = true,
        pageImage = ".reading-content img.wp-manga-chapter-img, .reading-content .page-break img, #readerarea img, div.reader-area img, .reading-content img",
    )
}
