package mihon.source

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
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
        assertEquals("/manga/one-piece/ch-2", details.chapters[0].url)
        assertEquals(1, details.chapters[0].volume)
        assertEquals(2.5f, details.chapters[0].chapter)
        assertNull(details.chapters[1].volume)
        assertEquals(1f, details.chapters[1].chapter)
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
    fun `http errors become IOException`() {
        server.enqueue(MockResponse().setResponseCode(503))
        val ex = assertFailsWith<IOException> { source.searchManga("x") }
        assertTrue(ex.message!!.contains("503"))
    }

    @Test
    fun `sends configured user agent`() {
        server.enqueue(MockResponse().setBody(searchHtml))
        source.searchManga("a")
        assertTrue(server.takeRequest().getHeader("User-Agent")!!.contains("MihonLinux"))
    }
}
