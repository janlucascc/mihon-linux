package mihon.source

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * CSS selectors describing how to scrape a manga website.
 *
 * Selectors for `title`/`url`/`cover` are evaluated relative to each element matched by [searchItem].
 */
@Serializable
data class ScraperConfig(
    val name: String,
    val baseUrl: String,
    val language: String = "en",
    val isNsfw: Boolean = false,
    /** Path template for search, `{query}` is replaced with the URL-encoded query. */
    val searchPath: String = "/search?q={query}",
    val popularPath: String = "/?s=&post_type=wp-manga&m_orderby=trending",
    val searchItem: String,
    val searchTitle: String = "a",
    val searchLink: String = "a",
    val searchCover: String? = "img",
    val searchAuthor: String? = null,
    val searchLatestChapter: String? = null,
    val detailsTitle: String,
    val detailsAuthor: String? = null,
    val detailsArtist: String? = null,
    val detailsDescription: String? = null,
    val detailsCover: String? = null,
    val chapterItem: String,
    val chapterLink: String = "a",
    val reverseChapters: Boolean = false,
    val pageImage: String,
    val userAgent: String = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
)

/** Generic [ExtensionSource] that scrapes HTML with OkHttp + jsoup according to a [ScraperConfig]. */
class HttpScraperSource(
    private val config: ScraperConfig,
    private val client: OkHttpClient = defaultClient(),
) : ExtensionSource {

    override val name: String get() = config.name
    override val baseUrl: String get() = config.baseUrl.trimEnd('/')
    override val language: String get() = config.language
    override val isNsfw: Boolean get() = config.isNsfw
    override val id: Long get() = (name.lowercase() + "/" + language).hashCode().toLong() and 0x7fffffffL

    override fun searchManga(query: String): List<MangaResult> {
        val path = config.searchPath.replace("{query}", java.net.URLEncoder.encode(query, "UTF-8"))
        return parseMangaList(fetch(absolute(path)))
    }

    override fun getPopularManga(): List<MangaResult> =
        parseMangaList(fetch(absolute(config.popularPath)))

    private fun parseMangaList(doc: Document): List<MangaResult> {
        return doc.select(config.searchItem).mapNotNull { item ->
            val link = item.selectFirst(config.searchLink) ?: return@mapNotNull null
            val url = link.attr("href").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val titleEl = item.selectFirst(config.searchTitle) ?: link
            val title = titleEl.cleanTitleText().ifBlank { return@mapNotNull null }
            MangaResult(
                title = title,
                url = toRelative(link.absUrl("href").trim().ifBlank { absolute(url) }),
                coverUrl = config.searchCover?.let { item.selectFirst(it) }?.imageUrl(),
                author = config.searchAuthor?.let { item.selectFirst(it) }?.text()?.trim()?.ifBlank { null },
                latestChapter = config.searchLatestChapter?.let { item.selectFirst(it) }?.text()?.trim()?.ifBlank { null },
            )
        }
    }

    override fun getMangaDetails(url: String): MangaDetails {
        val doc = fetch(absolute(url))
        val rawChapters = doc.select(config.chapterItem).mapNotNull { item ->
            val link = (if (item.`is`("a")) item else item.selectFirst(config.chapterLink)) ?: return@mapNotNull null
            val href = link.absUrl("href").trim().ifBlank { link.attr("href").trim() }.ifBlank { return@mapNotNull null }
            val title = link.text().trim().ifBlank { item.text().trim() }
            ChapterResult(
                title = title,
                url = toRelative(absolute(href)),
                volume = VOLUME_REGEX.find(title)?.groupValues?.get(1)?.toIntOrNull(),
                chapter = CHAPTER_REGEX.find(title)?.groupValues?.get(1)?.toFloatOrNull(),
            )
        }
        val chapters = orderChaptersAscending(rawChapters, config.reverseChapters)
        val desc = config.detailsDescription?.let { sel ->
            doc.select(sel).mapNotNull { el -> el.text().trim().takeIf { it.isNotBlank() } }
                .joinToString("\n\n")
                .ifBlank { null }
        }
        return MangaDetails(
            title = doc.selectFirst(config.detailsTitle)?.cleanTitleText().orEmpty(),
            author = config.detailsAuthor?.let { doc.selectFirst(it) }?.text()?.trim()?.ifBlank { null },
            artist = config.detailsArtist?.let { doc.selectFirst(it) }?.text()?.trim()?.ifBlank { null },
            description = desc,
            coverUrl = config.detailsCover?.let { doc.selectFirst(it) }?.imageUrl(),
            chapters = chapters,
        )
    }

    override fun getChapterImages(url: String): List<String> {
        val doc = fetch(absolute(url))
        return doc.select(config.pageImage).mapNotNull { it.imageUrl() }.distinct()
    }

    /**
     * Strips Madara badge elements (e.g. `.manga-title-badges` containing "18+", "Completo", "Novo")
     * before extracting the title text.
     */
    private fun Element.cleanTitleText(): String {
        val clone = this.clone()
        clone.select(".manga-title-badges, span.badge").remove()
        return clone.text().trim().ifBlank { this.ownText().trim() }.ifBlank { this.text().trim() }
    }

    /**
     * Ensures chapters are ordered from first to last (Cap. 1 -> Cap. N), regardless of whether a
     * Madara site lists newest-first or oldest-first.
     */
    private fun orderChaptersAscending(list: List<ChapterResult>, reverseByDefault: Boolean): List<ChapterResult> {
        if (list.size <= 1) return list
        val firstNum = list.firstOrNull { it.chapter != null }?.chapter
        val lastNum = list.lastOrNull { it.chapter != null }?.chapter
        if (firstNum != null && lastNum != null && firstNum != lastNum) {
            return if (firstNum > lastNum) list.reversed() else list
        }
        return if (reverseByDefault) list.reversed() else list
    }

    private fun Element.imageUrl(): String? {
        val attrs = listOf("data-src", "data-lazy-src", "data-cfsrc", "data-srcset", "srcset", "src")
        for (attrName in attrs) {
            val rawAttr = attr(attrName).trim()
            if (rawAttr.isBlank() || rawAttr.startsWith("data:", ignoreCase = true)) continue
            val candidate = if (attrName.endsWith("srcset")) {
                rawAttr.substringBefore(',').trim().substringBefore(' ').trim()
            } else {
                rawAttr
            }
            if (candidate.isBlank() || isPlaceholderImage(candidate)) continue
            val resolved = if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                candidate
            } else if (candidate.startsWith("//")) {
                "https:$candidate"
            } else {
                absUrl(attrName).trim().takeIf { it.isNotBlank() && !isPlaceholderImage(it) } ?: absolute(candidate)
            }
            return resolved.trim().replace(" ", "%20")
        }
        return null
    }

    private fun isPlaceholderImage(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("dflazy.") ||
            lower.endsWith("/blank.gif") ||
            lower.endsWith("/lazy.png") ||
            lower.endsWith("/placeholder.png") ||
            lower.startsWith("data:")
    }

    private fun absolute(pathOrUrl: String): String {
        val clean = pathOrUrl.trim()
        return if (clean.startsWith("http://") || clean.startsWith("https://")) {
            clean
        } else {
            baseUrl + "/" + clean.trimStart('/')
        }
    }

    private fun toRelative(url: String): String {
        val clean = url.trim()
        return if (clean.startsWith(baseUrl)) clean.removePrefix(baseUrl).ifEmpty { "/" } else clean
    }

    private fun fetch(url: String): Document {
        val sanitizedUrl = url.trim().replace(" ", "%20")
        val request = Request.Builder()
            .url(sanitizedUrl.toHttpUrl())
            .header("User-Agent", config.userAgent)
            .header("Referer", "$baseUrl/")
            .header("Cookie", "tia_idade_confirmada=1; wpmanga-adault=1")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} ao acessar $sanitizedUrl")
            val body = response.body?.string() ?: throw IOException("Resposta vazia de $sanitizedUrl")
            return Jsoup.parse(body, sanitizedUrl)
        }
    }

    private companion object {
        val VOLUME_REGEX = Regex("""(?i)vol(?:ume)?\.?\s*(\d+)""")
        val CHAPTER_REGEX = Regex("""(?i)(?:ch(?:apter)?|cap(?:[ií]tulo)?)\.?\s*(\d+(?:\.\d+)?)""")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
