package mihon.source

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.SocketTimeoutException
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
    val config: ScraperConfig,
    override val id: Long = 0,
    private val client: OkHttpClient = defaultClient(),
) : ExtensionSource {

    override val name: String get() = config.name
    override val baseUrl: String get() = config.baseUrl.trimEnd('/')
    override val language: String get() = config.language
    override val isNsfw: Boolean get() = config.isNsfw

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

    /**
     * Fetches a URL with exponential backoff retry policy.
     *
     * Retry configuration:
     * - Max 3 attempts
     * - Base delay: 1 second
     * - Max delay: 10 seconds
     * - Retries on: IOException (network errors), SocketTimeoutException, HTTP 5xx, HTTP 429
     * - Respects Retry-After header when present
     */
    private fun fetch(url: String): Document {
        val sanitizedUrl = url.trim().replace(" ", "%20")
        val request = Request.Builder()
            .url(sanitizedUrl.toHttpUrl())
            .header("User-Agent", config.userAgent)
            .header("Referer", "$baseUrl/")
            .header("Cookie", "tia_idade_confirmada=1; wpmanga-adault=1")
            .build()

        val maxAttempts = 3
        val baseDelayMs = 1000L
        val maxDelayMs = 10000L

        var attempt = 0
        var lastException: Exception? = null

        while (attempt < maxAttempts) {
            attempt++
            try {
                var retryDelayMs = -1L
                val parsedDoc = client.newCall(request).execute().use { response ->
                    val responseCode = response.code
                    if (shouldRetry(responseCode)) {
                        val retryAfterDelay = parseRetryAfter(response)
                        if (attempt < maxAttempts) {
                            retryDelayMs = if (retryAfterDelay > 0) {
                                min(retryAfterDelay, maxDelayMs)
                            } else {
                                min(baseDelayMs * (1L shl (attempt - 1)), maxDelayMs)
                            }
                            return@use null
                        }
                        throw IOException("HTTP $responseCode ao acessar $sanitizedUrl após $maxAttempts tentativas")
                    }

                    if (!response.isSuccessful) {
                        throw HttpStatusException(responseCode, "HTTP $responseCode ao acessar $sanitizedUrl")
                    }
                    val body = response.body?.string() ?: throw IOException("Resposta vazia de $sanitizedUrl")
                    Jsoup.parse(body, sanitizedUrl)
                }

                if (parsedDoc != null) return parsedDoc
                if (retryDelayMs > 0) {
                    Thread.sleep(retryDelayMs)
                }
                continue

            } catch (e: HttpStatusException) {
                // Non-retryable HTTP error - propagate immediately
                throw e
            } catch (e: SocketTimeoutException) {
                lastException = e
                if (attempt < maxAttempts) {
                    val delay = min(baseDelayMs * (1L shl (attempt - 1)), maxDelayMs)
                    Thread.sleep(delay)
                    continue
                }
            } catch (e: IOException) {
                // Network-level errors (connection refused, DNS failure, etc.) - retry
                lastException = e
                if (attempt < maxAttempts) {
                    val delay = min(baseDelayMs * (1L shl (attempt - 1)), maxDelayMs)
                    Thread.sleep(delay)
                    continue
                }
            }
        }

        throw lastException ?: IOException("Falha ao acessar $sanitizedUrl após $maxAttempts tentativas")
    }

    /**
     * Exception for non-retryable HTTP status codes.
     * This allows us to distinguish HTTP errors from network errors in catch blocks.
     */
    private class HttpStatusException(code: Int, message: String) : IOException(message) {
        val statusCode: Int = code
    }

    /**
     * Determines if a response code should trigger a retry.
     * Retries on 5xx server errors and 429 (rate limited).
     */
    private fun shouldRetry(code: Int): Boolean {
        return code >= 500 && code < 600 || code == 429
    }

    /**
     * Parses the Retry-After header value.
     * Returns delay in milliseconds, or 0 if header is absent/invalid.
     * Supports both seconds (integer) and HTTP-date formats.
     */
    private fun parseRetryAfter(response: Response): Long {
        val retryAfter = response.header("Retry-After")
        if (retryAfter.isNullOrBlank()) return 0

        return try {
            // Try parsing as seconds (integer)
            val seconds = retryAfter.toLongOrNull()
            if (seconds != null) {
                seconds * 1000
            } else {
                // Try parsing as HTTP-date (RFC 1123)
                val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                    .parse(retryAfter)
                if (date != null) {
                    max(0, date.time - System.currentTimeMillis())
                } else {
                    0
                }
            }
        } catch (e: Exception) {
            0
        }
    }

    companion object {
        val VOLUME_REGEX = Regex("""(?i)vol(?:ume)?\.?\s*(\d+)""")
        val CHAPTER_REGEX = Regex("""(?i)(?:ch(?:apter)?|cap(?:[ií]tulo)?)\.?\s*(\d+(?:\.\d+)?)""")

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(okhttp3.ConnectionPool(4, 30, TimeUnit.SECONDS))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        fun defaultClient(): OkHttpClient = sharedClient
    }
}