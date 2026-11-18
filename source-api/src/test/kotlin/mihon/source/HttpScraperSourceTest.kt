package mihon.source

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpScraperSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: HttpScraperSource

    private val searchHtml = """
        <html><body>
        <div class="item"><a href="/manga/one-piece"><h3>One Piece</h3></a>
          <img data-src="/covers/op.jpg" src="/placeholder.png"><span class="author">Oda</span><span class="latest">Chapter 1100</span></div>
        <div class="item"><a href="/manga/naruto"><h3>Naruto</h3></a><img src="/covers/naruto.jpg"></div>
        <div class="item"><span>sem link</span></div>
        </body></html>
    """.trimIndent()

    private val detailsHtml = """
        <html><body>
        <h1 class="title">One Piece</h1><span class="author">Oda</span><p class="desc">Piratas.</p>
        <ul><li class="ch"><a href="/manga/one-piece/ch-2">Vol.1 Chapter 2.5</a></li>
        <li class="ch"><a href="/manga/one-piece/ch-1">Chapter 1</a></li></ul>
        </body></html>
    """.trimIndent()

    private val chapterHtml = """
        <html><body><div class="reader">
        <img src="/img/1.jpg"><img data-src="/img/2.jpg" src="/loading.gif"><img src="https://cdn.example/3.jpg">
        </div></body></html>
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        source = HttpScraperSource(
            ScraperConfig(
                name = "Test",
                baseUrl = server.url("/").toString(),
                searchItem = "div.item",
                searchTitle = "h3",
                searchAuthor = "span.author",
                searchLatestChapter = "span.latest",
                detailsTitle = "h1.title",
                detailsAuthor = "span.author",
                detailsDescription = "p.desc",
                chapterItem = "li.ch",
                pageImage = "div.reader img",
                userAgent = "MihonLinux/1.0 (X11; Linux x86_64)",
            ),
        )
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    @Test
    fun `search parses results and skips items without link`() {
        server.enqueue(MockResponse().setBody(searchHtml))
        val results = source.searchManga("one piece")

        assertEquals(2, results.size)
        assertEquals("One Piece", results[0].title)
        assertEquals("/manga/one-piece", results[0].url)
        assertEquals(server.url("/covers/op.jpg").toString(), results[0].coverUrl)
        assertEquals("Oda", results[0].author)
        assertEquals("Chapter 1100", results[0].latestChapter)
        assertNull(results[1].author)
        assertEquals("/search?q=one+piece", server.takeRequest().path)
    }

    @Test
    fun `details parses metadata and chapters`() {
        server.enqueue(MockResponse().setBody(detailsHtml))
        val details = source.getMangaDetails("/manga/one-piece")

        assertEquals("One Piece", details.title)
        assertEquals("Oda", details.author)
        assertEquals("Piratas.", details.description)
        assertEquals(2, details.chapters.size)
        assertEquals("/manga/one-piece/ch-1", details.chapters[0].url)
        assertNull(details.chapters[0].volume)
        assertEquals(1f, details.chapters[0].chapter)
        assertEquals("/manga/one-piece/ch-2", details.chapters[1].url)
        assertEquals(1, details.chapters[1].volume)
        assertEquals(2.5f, details.chapters[1].chapter)
    }

    @Test
    fun `chapter images prefer lazy attributes and keep absolute urls`() {
        server.enqueue(MockResponse().setBody(chapterHtml))
        val images = source.getChapterImages("/manga/one-piece/ch-1")

        assertEquals(
            listOf(server.url("/img/1.jpg").toString(), server.url("/img/2.jpg").toString(), "https://cdn.example/3.jpg"),
            images,
        )
    }

    @Test
    fun `http errors become IOException after retries exhausted`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        val ex = assertFailsWith<IOException> { source.searchManga("x") }
        assertTrue(ex.message!!.contains("503"))
        assertTrue(ex.message!!.contains("3 tentativas"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `sends configured user agent`() {
        server.enqueue(MockResponse().setBody(searchHtml))
        source.searchManga("a")
        assertTrue(server.takeRequest().getHeader("User-Agent")!!.contains("MihonLinux"))
    }

    @Test
    fun `retry on HTTP 500 then success`() {
        // First two requests return 500, third succeeds
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody(searchHtml))

        val results = source.searchManga("test")

        assertEquals(2, results.size)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `retry on HTTP 503 then success`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(searchHtml))

        val results = source.searchManga("test")

        assertEquals(2, results.size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `retry on HTTP 429 then success`() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody(searchHtml))

        val results = source.searchManga("test")

        assertEquals(2, results.size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `respects Retry-After header in seconds`() {
        val retryAfterSeconds = 1
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", retryAfterSeconds.toString())
        )
        server.enqueue(MockResponse().setBody(searchHtml))

        val startTime = System.currentTimeMillis()
        val results = source.searchManga("test")
        val elapsed = System.currentTimeMillis() - startTime

        assertEquals(2, results.size)
        assertEquals(2, server.requestCount)
        // Should wait at least retryAfterSeconds * 1000 ms (with some tolerance)
        assertTrue(elapsed >= retryAfterSeconds * 1000 - 100, "Expected wait of at least ${retryAfterSeconds}s, got ${elapsed}ms")
    }

    @Test
    fun `retry on IOException with body then success`() {
        server.enqueue(MockResponse().setBody("fail").setResponseCode(500))
        server.enqueue(MockResponse().setBody(searchHtml))

        val results = source.searchManga("test")

        assertEquals(2, results.size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `exhausts retries then throws IOException`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))

        val ex = assertFailsWith<IOException> { source.searchManga("test") }
        assertTrue(ex.message!!.contains("500"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `exhausts retries on 429 then throws IOException`() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(429))

        val ex = assertFailsWith<IOException> { source.searchManga("test") }
        assertTrue(ex.message!!.contains("429"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `exponential backoff increases delay`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody(searchHtml))

        val startTime = System.currentTimeMillis()
        source.searchManga("test")
        val elapsed = System.currentTimeMillis() - startTime

        // First retry: ~1s, second retry: ~2s = ~3s total minimum
        assertTrue(elapsed >= 2500, "Expected at least ~3s for exponential backoff, got ${elapsed}ms")
    }

    @Test
    fun `healthCheck returns true by default`() {
        assertTrue(source.healthCheck())
    }

    @Test
    fun `does not retry on HTTP 404`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val ex = assertFailsWith<IOException> { source.searchManga("test") }
        assertTrue(ex.message!!.contains("404"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `does not retry on HTTP 400`() {
        server.enqueue(MockResponse().setResponseCode(400))

        val ex = assertFailsWith<IOException> { source.searchManga("test") }
        assertTrue(ex.message!!.contains("400"))
        assertEquals(1, server.requestCount)
    }
}

class MangaDexSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaDexSource

    private val searchResponse = """
        {
            "data": [
                {
                    "id": "manga1",
                    "attributes": {
                        "title": {"pt-br": "One Piece", "en": "One Piece"},
                        "description": {"pt-br": "Piratas", "en": "Pirates"},
                        "lastChapter": "1100"
                    },
                    "relationships": [
                        {"type": "cover_art", "attributes": {"fileName": "cover1.jpg"}},
                        {"type": "author", "attributes": {"name": "Oda"}}
                    ]
                }
            ]
        }
    """.trimIndent()

    private val detailsResponse = """
        {
            "data": {
                "id": "manga1",
                "attributes": {
                    "title": {"pt-br": "One Piece", "en": "One Piece"},
                    "description": {"pt-br": "Piratas", "en": "Pirates"}
                },
                "relationships": [
                    {"type": "cover_art", "attributes": {"fileName": "cover1.jpg"}},
                    {"type": "author", "attributes": {"name": "Oda"}},
                    {"type": "artist", "attributes": {"name": "Oda"}}
                ]
            }
        }
    """.trimIndent()

    private val feedResponse = """
        {
            "data": [
                {
                    "id": "ch1",
                    "attributes": {
                        "chapter": "1",
                        "volume": "1",
                        "title": "Romance Dawn",
                        "translatedLanguage": "pt-br",
                        "externalUrl": null
                    }
                },
                {
                    "id": "ch2",
                    "attributes": {
                        "chapter": "2",
                        "volume": "1",
                        "title": "Buggy",
                        "translatedLanguage": "pt-br",
                        "externalUrl": null
                    }
                }
            ],
            "total": 2
        }
    """.trimIndent()

    private val atHomeResponse = """
        {
            "baseUrl": "https://uploads.mangadex.org",
            "chapter": {
                "hash": "abc123",
                "data": ["page1.jpg", "page2.jpg"],
                "dataSaver": ["page1-small.jpg", "page2-small.jpg"]
            }
        }
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        source = MangaDexSource(
            language = "pt-br",
            baseUrl = server.url("/").toString(),
            client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
        )
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    @Test
    fun `searchManga returns parsed results`() {
        server.enqueue(MockResponse().setBody(searchResponse))

        val results = source.searchManga("one piece")

        assertEquals(1, results.size)
        assertEquals("One Piece", results[0].title)
        assertEquals("manga1", results[0].url)
        assertEquals("Oda", results[0].author)
    }

    @Test
    fun `getMangaDetails returns details with chapters`() {
        server.enqueue(MockResponse().setBody(detailsResponse))
        server.enqueue(MockResponse().setBody(feedResponse))

        val details = source.getMangaDetails("manga1")

        assertEquals("One Piece", details.title)
        assertEquals("Oda", details.author)
        assertEquals("Oda", details.artist)
        assertEquals(2, details.chapters.size)
        assertEquals("Vol. 1 Cap. 1 - Romance Dawn [pt-br]", details.chapters[0].title)
    }

    @Test
    fun `getChapterImages returns image URLs from at-home server`() {
        server.enqueue(MockResponse().setBody(atHomeResponse))

        val images = source.getChapterImages("ch1")

        assertEquals(2, images.size)
        assertTrue(images[0].contains("page1.jpg"))
        assertTrue(images[1].contains("page2.jpg"))
    }

    @Test
    fun `caches at-home server URL for 15 minutes`() {
        server.enqueue(MockResponse().setBody(atHomeResponse))
        source.getChapterImages("ch1")

        // Second call should use cache, no new request
        server.enqueue(MockResponse().setBody(atHomeResponse))
        source.getChapterImages("ch1")

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `respects Retry-After header on 429`() {
        val retryAfterSeconds = 1
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", retryAfterSeconds.toString())
        )
        server.enqueue(MockResponse().setBody(searchResponse))

        val startTime = System.currentTimeMillis()
        source.searchManga("test")
        val elapsed = System.currentTimeMillis() - startTime

        assertEquals(2, server.requestCount)
        assertTrue(elapsed >= retryAfterSeconds * 1000 - 100)
    }

    @Test
    fun `retries on HTTP 500`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody(searchResponse))

        val results = source.searchManga("test")

        assertEquals(1, results.size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `retries on HTTP 503`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(searchResponse))

        val results = source.searchManga("test")

        assertEquals(1, results.size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `exhausts retries then throws`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))

        assertFailsWith<IOException> { source.searchManga("test") }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `healthCheck returns true when API is reachable`() {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))

        assertTrue(source.healthCheck())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `healthCheck returns false when API is unreachable`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))

        assertFalse(source.healthCheck())
    }

    @Test
    fun `rate limiter allows burst of 10 requests`() {
        // This test verifies the token bucket is configured correctly
        // We can't easily test timing without flakiness, but we can verify
        // the rate limiter doesn't block indefinitely for small bursts
        val rateLimiter = TokenBucket(capacity = 10, refillRatePerSecond = 5.0)

        // Should allow 10 immediate requests (burst)
        repeat(10) {
            rateLimiter.acquire()
        }
        // 11th should block briefly until refill
        val start = System.currentTimeMillis()
        rateLimiter.acquire()
        val elapsed = System.currentTimeMillis() - start
        // At 5 req/s, 1 token takes ~200ms to refill
        assertTrue(elapsed >= 150, "Expected ~200ms wait for refill, got ${elapsed}ms")
    }

    @Test
    fun `skips external chapters in feed`() {
        val feedWithExternal = """
            {
                "data": [
                    {
                        "id": "ch1",
                        "attributes": {
                            "chapter": "1",
                            "volume": "1",
                            "title": "Internal",
                            "translatedLanguage": "pt-br",
                            "externalUrl": null
                        }
                    },
                    {
                        "id": "ch2",
                        "attributes": {
                            "chapter": "2",
                            "volume": "1",
                            "title": "External",
                            "translatedLanguage": "pt-br",
                            "externalUrl": "https://example.com/ch2"
                        }
                    }
                ],
                "total": 2
            }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(detailsResponse))
        server.enqueue(MockResponse().setBody(feedWithExternal))

        val details = source.getMangaDetails("manga1")

        assertEquals(1, details.chapters.size)
        assertEquals("ch1", details.chapters[0].url)
    }
}

class TokenBucketTest {

    @Test
    fun `allows burst up to capacity`() {
        val bucket = TokenBucket(capacity = 5, refillRatePerSecond = 1.0)

        repeat(5) {
            bucket.acquire() // Should not block
        }
    }

    @Test
    fun `blocks when exhausted and refills over time`() {
        val bucket = TokenBucket(capacity = 2, refillRatePerSecond = 10.0) // Fast refill for test

        bucket.acquire()
        bucket.acquire()

        val start = System.currentTimeMillis()
        bucket.acquire() // Should wait for refill
        val elapsed = System.currentTimeMillis() - start

        // At 10 req/s, 1 token takes ~100ms
        assertTrue(elapsed >= 80, "Expected ~100ms wait, got ${elapsed}ms")
    }

    @Test
    fun `refills tokens over time`() {
        val bucket = TokenBucket(capacity = 3, refillRatePerSecond = 100.0) // Very fast

        bucket.acquire()
        bucket.acquire()
        bucket.acquire()

        // Wait for refill
        Thread.sleep(50)

        // Should be able to acquire again without blocking
        val start = System.currentTimeMillis()
        bucket.acquire()
        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed < 50, "Expected no wait after refill, got ${elapsed}ms")
    }
}

class AtHomeCacheEntryTest {

    @Test
    fun `isExpired returns false for fresh entry`() {
        val entry = AtHomeCacheEntry("https://example.com", "hash", "data", listOf("file1.jpg"), System.currentTimeMillis())
        assertFalse(entry.isExpired())
    }

    @Test
    fun `isExpired returns true for old entry`() {
        val oldTimestamp = System.currentTimeMillis() - 20 * 60 * 1000 // 20 minutes ago
        val entry = AtHomeCacheEntry("https://example.com", "hash", "data", listOf("file1.jpg"), oldTimestamp)
        assertTrue(entry.isExpired())
    }

    @Test
    fun `isExpired returns false for 14 minute old entry`() {
        val timestamp = System.currentTimeMillis() - 14 * 60 * 1000 // 14 minutes ago
        val entry = AtHomeCacheEntry("https://example.com", "hash", "data", listOf("file1.jpg"), timestamp)
        assertFalse(entry.isExpired())
    }
}