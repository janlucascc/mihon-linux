package mihon.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max
import kotlin.math.min
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * Built-in source using the public MangaDex REST API v5.
 * Searches manga, fetches details and chapters (preferring pt-br, pt, en), and resolves page URLs.
 *
 * Features:
 * - Token bucket rate limiting (10 req/s burst, 5 req/s sustained)
 * - Retry on HTTP 429 with Retry-After header support
 * - Cache for at-home server URLs (TTL 15 minutes)
 */
class MangaDexSource(
    override val language: String = "pt-br",
    override val baseUrl: String = "https://api.mangadex.org",
    override val id: Long = 0,
    private val client: OkHttpClient = defaultClient(),
) : ExtensionSource {

    override val name: String = "MangaDex ($language)"
    override val isNsfw: Boolean = false

    private val json = Json { ignoreUnknownKeys = true }

    // Token bucket for rate limiting: 10 burst, 5 sustained req/s
    private val rateLimiter = TokenBucket(capacity = 10, refillRatePerSecond = 5.0)

    // Cache for at-home server URLs: chapterId -> (baseUrl, timestamp)
    // TTL: 15 minutes
    private val atHomeCache = ConcurrentHashMap<String, AtHomeCacheEntry>()
    private val AT_HOME_TTL_MS = 15 * 60 * 1000L // 15 minutes

    override fun searchManga(query: String): List<MangaResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/manga?title=$encoded&limit=25&includes[]=cover_art&includes[]=author&contentRating[]=safe&contentRating[]=suggestive"
        return parseMangaData(url)
    }

    override fun getPopularManga(): List<MangaResult> {
        val url = "$baseUrl/manga?limit=24&order[followedCount]=desc&includes[]=cover_art&includes[]=author&contentRating[]=safe&contentRating[]=suggestive"
        return parseMangaData(url)
    }

    private fun parseMangaData(url: String): List<MangaResult> {
        val root = json.parseToJsonElement(getWithRetry(url)).jsonObject
        val data = root["data"]?.jsonArray ?: return emptyList()

        return data.mapNotNull { elem ->
            val obj = elem.jsonObject
            val mangaId = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val attrs = obj["attributes"]?.jsonObject ?: return@mapNotNull null
            val titleObj = attrs["title"]?.jsonObject
            val title = titleObj?.get("pt-br")?.jsonPrimitive?.content
                ?: titleObj?.get("en")?.jsonPrimitive?.content
                ?: titleObj?.values?.firstOrNull()?.jsonPrimitive?.content
                ?: return@mapNotNull null

            val rels = obj["relationships"]?.jsonArray.orEmpty()
            var coverFile: String? = null
            var authorName: String? = null
            for (rel in rels) {
                val rObj = rel.jsonObject
                val rType = rObj["type"]?.jsonPrimitive?.content
                val rAttrs = rObj["attributes"]?.jsonObject ?: continue
                if (rType == "cover_art") {
                    coverFile = rAttrs["fileName"]?.jsonPrimitive?.content
                } else if (rType == "author" && authorName == null) {
                    authorName = rAttrs["name"]?.jsonPrimitive?.content
                }
            }
            val coverUrl = coverFile?.let { "https://uploads.mangadex.org/covers/$mangaId/$it.256.jpg" }
            val lastCh = attrs["lastChapter"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }

            MangaResult(
                title = title,
                url = mangaId,
                coverUrl = coverUrl,
                author = authorName,
                latestChapter = lastCh?.let { "Cap. $it" },
            )
        }
    }

    override fun getMangaDetails(url: String): MangaDetails {
        val mangaId = url.substringAfterLast('/').trim()
        val detailUrl = "$baseUrl/manga/$mangaId?includes[]=cover_art&includes[]=author&includes[]=artist"
        val obj = json.parseToJsonElement(getWithRetry(detailUrl)).jsonObject["data"]?.jsonObject
            ?: throw IOException("Mangá não encontrado: $mangaId")
        val attrs = obj["attributes"]?.jsonObject ?: throw IOException("Atributos ausentes")
        val titleObj = attrs["title"]?.jsonObject
        val title = titleObj?.get("pt-br")?.jsonPrimitive?.content
            ?: titleObj?.get("en")?.jsonPrimitive?.content
            ?: titleObj?.values?.firstOrNull()?.jsonPrimitive?.content
            ?: mangaId

        val descObj = attrs["description"]?.jsonObject
        val description = descObj?.get("pt-br")?.jsonPrimitive?.content
            ?: descObj?.get("en")?.jsonPrimitive?.content
            ?: descObj?.values?.firstOrNull()?.jsonPrimitive?.content

        var coverFile: String? = null
        var authorName: String? = null
        var artistName: String? = null
        obj["relationships"]?.jsonArray.orEmpty().forEach { rel ->
            val rObj = rel.jsonObject
            val rType = rObj["type"]?.jsonPrimitive?.content
            val rAttrs = rObj["attributes"]?.jsonObject ?: return@forEach
            when (rType) {
                "cover_art" -> coverFile = rAttrs["fileName"]?.jsonPrimitive?.content
                "author" -> if (authorName == null) authorName = rAttrs["name"]?.jsonPrimitive?.content
                "artist" -> if (artistName == null) artistName = rAttrs["name"]?.jsonPrimitive?.content
            }
        }
        val coverUrl = coverFile?.let { "https://uploads.mangadex.org/covers/$mangaId/$it.256.jpg" }

        // Fetch chapters feed (preferring target language, fallback to en)
        val chapters = fetchChapters(mangaId, listOf(language, "pt", "en").distinct())

        return MangaDetails(
            title = title,
            author = authorName,
            artist = artistName,
            description = description,
            coverUrl = coverUrl,
            chapters = chapters,
        )
    }

    private fun fetchChapters(mangaId: String, langs: List<String>): List<ChapterResult> {
        val langQuery = langs.joinToString("&") { "translatedLanguage[]=$it" }
        val allChapters = mutableListOf<ChapterResult>()
        val limit = 100
        var offset = 0
        var hasMore = true

        while (hasMore) {
            val feedUrl = "$baseUrl/manga/$mangaId/feed?limit=$limit&offset=$offset&$langQuery&order[chapter]=asc&contentRating[]=safe&contentRating[]=suggestive"
            val root = json.parseToJsonElement(getWithRetry(feedUrl)).jsonObject
            val data = root["data"]?.jsonArray ?: break
            if (data.isEmpty()) break

            val total = root["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: (offset + data.size)

            val parsed = data.mapNotNull { elem ->
                val obj = elem.jsonObject
                val chId = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val attrs = obj["attributes"]?.jsonObject ?: return@mapNotNull null
                // Skip external chapters that have no hosted pages on MangaDex
                val externalUrl = attrs["externalUrl"]?.jsonPrimitive?.content
                if (!externalUrl.isNullOrBlank() && externalUrl != "null") return@mapNotNull null

                val chNumStr = attrs["chapter"]?.jsonPrimitive?.content?.takeIf { it != "null" }
                val volStr = attrs["volume"]?.jsonPrimitive?.content?.takeIf { it != "null" }
                val chTitle = attrs["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
                val lang = attrs["translatedLanguage"]?.jsonPrimitive?.content.orEmpty()

                val label = buildString {
                    if (volStr != null) append("Vol. $volStr ")
                    if (chNumStr != null) append("Cap. $chNumStr") else append("Oneshot")
                    if (chTitle != null) append(" - $chTitle")
                    if (lang.isNotEmpty()) append(" [$lang]")
                }
                ChapterResult(
                    title = label,
                    url = chId,
                    volume = volStr?.toIntOrNull(),
                    chapter = chNumStr?.toFloatOrNull(),
                )
            }
            allChapters.addAll(parsed)
            offset += limit
            if (offset >= total || data.size < limit) {
                hasMore = false
            }
        }
        return allChapters
    }

    override fun getChapterImages(url: String): List<String> {
        val chapterId = url.substringAfterLast('/').trim()

        // Try to get cached at-home server info
        val cachedEntry = atHomeCache[chapterId]
        if (cachedEntry != null && !cachedEntry.isExpired()) {
            return cachedEntry.buildImageUrls()
        }

        // Fetch fresh at-home server info with retry
        val atHomeUrl = "$baseUrl/at-home/server/$chapterId"
        val root = json.parseToJsonElement(getWithRetry(atHomeUrl)).jsonObject
        val host = root["baseUrl"]?.jsonPrimitive?.content ?: throw IOException("Servidor de imagens indisponível")
        val chapter = root["chapter"]?.jsonObject ?: throw IOException("Dados do capítulo ausentes")
        val hash = chapter["hash"]?.jsonPrimitive?.content ?: throw IOException("Hash ausente")

        // Prefer data-saver or standard data
        val files = chapter["data"]?.jsonArray?.takeIf { it.isNotEmpty() }
            ?: chapter["dataSaver"]?.jsonArray
            ?: return emptyList()
        val mode = if (chapter["data"]?.jsonArray?.isNotEmpty() == true) "data" else "data-saver"
        val fileList = files.mapNotNull { it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() } }

        // Cache the at-home server data
        atHomeCache[chapterId] = AtHomeCacheEntry(host, hash, mode, fileList, System.currentTimeMillis())

        return fileList.map { "$host/$mode/$hash/$it" }
    }

    /**
     * Executes a GET request with rate limiting and retry logic.
     * Handles HTTP 429 with Retry-After header.
     * Uses exponential backoff for retries (max 3 attempts).
     */
    private fun getWithRetry(url: String): String {
        val maxAttempts = 3
        val baseDelayMs = 1000L
        val maxDelayMs = 10000L

        var attempt = 0
        var lastException: Exception? = null

        while (attempt < maxAttempts) {
            attempt++
            try {
                // Acquire token from rate limiter (blocks until available)
                rateLimiter.acquire()

                val req = Request.Builder()
                    .url(url.toHttpUrl())
                    .header("User-Agent", "MihonLinux/0.2 (Fedora Desktop)")
                    .build()

                var retryDelayMs = -1L
                val resultBody = client.newCall(req).execute().use { response ->
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
                        throw IOException("HTTP $responseCode em $url após $maxAttempts tentativas")
                    }

                    if (!response.isSuccessful) {
                        throw IOException("HTTP $responseCode em $url")
                    }
                    response.body?.string() ?: throw IOException("Resposta vazia")
                }

                if (resultBody != null) return resultBody
                if (retryDelayMs > 0) {
                    Thread.sleep(retryDelayMs)
                }
                continue

            } catch (e: IOException) {
                lastException = e
                if (attempt < maxAttempts) {
                    val delay = min(baseDelayMs * (1L shl (attempt - 1)), maxDelayMs)
                    Thread.sleep(delay)
                    continue
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrompido durante rate limiting", e)
            }
        }

        throw lastException ?: IOException("Falha ao acessar $url após $maxAttempts tentativas")
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

    /**
     * Health check for MangaDex source.
     * Attempts a lightweight API call to verify connectivity.
     */
    override fun healthCheck(): Boolean {
        return try {
            val url = "$baseUrl/manga?limit=1"
            getWithRetry(url)
            true
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Token bucket rate limiter implementation.
 * Allows burst of up to [capacity] requests, then sustains [refillRatePerSecond] requests per second.
 */
class TokenBucket(
    private val capacity: Int,
    private val refillRatePerSecond: Double,
) {
    private var tokens = capacity.toDouble()
    private val lastRefill = AtomicLong(System.nanoTime())
    private val lock = ReentrantLock()

    /**
     * Acquires one token, blocking until available.
     * Refills tokens based on elapsed time since last refill.
     */
    fun acquire() {
        var waitNanos: Long = 0
        lock.lock()
        try {
            refill()
            if (tokens >= 1.0) {
                tokens -= 1.0
                return
            }
            // Calculate wait time for next token
            val tokensNeeded = 1.0 - tokens
            waitNanos = (tokensNeeded / refillRatePerSecond * 1_000_000_000).toLong()
        } finally {
            lock.unlock()
        }

        // Wait outside the lock to avoid blocking other threads
        Thread.sleep(waitNanos / 1_000_000, (waitNanos % 1_000_000).toInt())

        lock.lock()
        try {
            refill()
            tokens -= 1.0
        } finally {
            lock.unlock()
        }
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsedNanos = now - lastRefill.getAndSet(now)
        val elapsedSeconds = elapsedNanos / 1_000_000_000.0
        val newTokens = elapsedSeconds * refillRatePerSecond
        tokens = min(capacity.toDouble(), tokens + newTokens)
    }
}

/**
 * Cache entry for at-home server data with TTL.
 */
data class AtHomeCacheEntry(
    val baseUrl: String,
    val hash: String,
    val mode: String,
    val files: List<String>,
    val timestamp: Long,
) {
    fun isExpired(): Boolean {
        return System.currentTimeMillis() - timestamp > AT_HOME_TTL_MS
    }

    fun buildImageUrls(): List<String> {
        return files.map { "$baseUrl/$mode/$hash/$it" }
    }

    companion object {
        const val AT_HOME_TTL_MS = 15 * 60 * 1000L // 15 minutes
    }
}