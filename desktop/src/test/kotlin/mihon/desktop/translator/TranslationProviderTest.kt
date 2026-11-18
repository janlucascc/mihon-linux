package mihon.desktop.translator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TranslationProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient()
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `google gtx batches non-blank texts and parses response`() {
        runBlocking {
            val responseJson = """[[["Olá mundo\n[[[MIHON_SEG_7A3E]]]\nTudo bem?","Hello world\n[[[MIHON_SEG_7A3E]]]\nHow are you?",null,null,1]],null,"ja"]"""
            server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

            val provider = GoogleTranslateGtxProvider(
                httpClient = httpClient,
                endpointUrl = server.url("/translate_a/single").toString(),
            )

            val result = provider.translateBatch(
                listOf("こんにちは", "元気ですか？"),
                TranslatorLanguage.JAPANESE,
                TranslatorLanguage.PORTUGUESE_BR,
            )

            assertEquals(listOf("Olá mundo", "Tudo bem?"), result)
            assertEquals(1, server.requestCount)
            val req = server.takeRequest()
            val url = req.requestUrl!!
            assertEquals("gtx", url.queryParameter("client"))
            assertEquals("ja", url.queryParameter("sl"))
            assertEquals("pt", url.queryParameter("tl"))
            assertEquals("t", url.queryParameter("dt"))
            assertTrue(url.queryParameter("q")!!.contains("[[[MIHON_SEG_7A3E]]]"))
            assertFalse(req.getHeader("User-Agent").isNullOrBlank())
        }
    }

    @Test
    fun `google gtx concatenates multi-sentence segments and tolerates variable inner arrays`() {
        runBlocking {
            val responseJson = """[[["Primeira frase. ","First sentence. ",null,null,1],["Segunda frase!", "Second sentence!",null],["ignored_single_element"],"ignored_scalar",[null]],null,"en"]"""
            server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

            val provider = GoogleTranslateGtxProvider(
                httpClient = httpClient,
                endpointUrl = server.url("/translate_a/single").toString(),
            )

            val result = provider.translateBatch(
                listOf("First sentence. Second sentence!"),
                TranslatorLanguage.ENGLISH,
                TranslatorLanguage.PORTUGUESE_BR,
            )
            assertEquals(listOf("Primeira frase. Segunda frase!"), result)
        }
    }

    @Test
    fun `google gtx preserves blank items and skips network when all blank or same language`() {
        runBlocking {
            val provider = GoogleTranslateGtxProvider(
                httpClient = httpClient,
                endpointUrl = server.url("/translate_a/single").toString(),
            )

            assertEquals(emptyList(), provider.translateBatch(emptyList(), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR))
            assertEquals(listOf("", "   "), provider.translateBatch(listOf("", "   "), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR))
            assertEquals(listOf("Hello", "World"), provider.translateBatch(listOf("Hello", "World"), TranslatorLanguage.ENGLISH, TranslatorLanguage.ENGLISH))
            assertEquals(0, server.requestCount)

            server.enqueue(MockResponse().setResponseCode(200).setBody("""[[["Olá","Hello"]]]"""))
            val mixed = provider.translateBatch(listOf("", "Hello", "  "), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR)
            assertEquals(listOf("", "Olá", "  "), mixed)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `google gtx falls back to per-item translation when batch delimiter is altered`() {
        runBlocking {
            // First batch response omits/alters delimiter
            server.enqueue(MockResponse().setResponseCode(200).setBody("""[[["Um Dois","One Two"]]]"""))
            // Fallback sends 2 individual requests
            server.enqueue(MockResponse().setResponseCode(200).setBody("""[[["Um","One"]]]"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""[[["Dois","Two"]]]"""))

            val provider = GoogleTranslateGtxProvider(
                httpClient = httpClient,
                endpointUrl = server.url("/translate_a/single").toString(),
            )

            val result = provider.translateBatch(
                listOf("One", "Two"),
                TranslatorLanguage.ENGLISH,
                TranslatorLanguage.PORTUGUESE_BR,
            )
            assertEquals(listOf("Um", "Dois"), result)
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `google gtx chunks batches exceeding maxBatchItems or maxBatchChars`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""[[["Um\n[[[MIHON_SEG_7A3E]]]\nDois","One\n[[[MIHON_SEG_7A3E]]]\nTwo"]]]"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""[[["Três","Three"]]]"""))

            val provider = GoogleTranslateGtxProvider(
                httpClient = httpClient,
                endpointUrl = server.url("/translate_a/single").toString(),
                maxBatchItems = 2,
            )

            val result = provider.translateBatch(
                listOf("One", "Two", "Three"),
                TranslatorLanguage.ENGLISH,
                TranslatorLanguage.PORTUGUESE_BR,
            )
            assertEquals(listOf("Um", "Dois", "Três"), result)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `google gtx throws TranslationException on HTTP 429 HTTP 500 and malformed JSON`() {
        runBlocking {
            val provider = GoogleTranslateGtxProvider(
                httpClient = httpClient,
                endpointUrl = server.url("/translate_a/single").toString(),
            )

            server.enqueue(MockResponse().setResponseCode(429).setBody("Too Many Requests"))
            val err429 = assertThrows<TranslationException> {
                provider.translateBatch(listOf("Hello"), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR)
            }
            assertEquals(429, err429.httpCode)

            server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
            val err500 = assertThrows<TranslationException> {
                provider.translateBatch(listOf("Hello"), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR)
            }
            assertEquals(500, err500.httpCode)

            server.enqueue(MockResponse().setResponseCode(200).setBody("{not valid json"))
            assertThrows<TranslationException> {
                provider.translateBatch(listOf("Hello"), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR)
            }
        }
    }

    @Test
    fun `mymemory translates with langpair handles string responseStatus 403 and detects auto script`() {
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"responseData":{"translatedText":"Bom dia"},"quotaFinished":false,"responseStatus":200}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"responseData":{"translatedText":"ERR"},"responseDetails":"INVALID","responseStatus":"403"}""",
                ),
            )

            val provider = MyMemoryTranslationProvider(
                httpClient = httpClient,
                endpointUrl = server.url("/get").toString(),
            )

            val ok = provider.translateBatch(
                listOf("おはよう"),
                TranslatorLanguage.AUTO,
                TranslatorLanguage.PORTUGUESE_BR,
            )
            assertEquals(listOf("Bom dia"), ok)
            val req1 = server.takeRequest()
            assertEquals("ja|pt-BR", req1.requestUrl!!.queryParameter("langpair"))

            val err = assertThrows<TranslationException> {
                provider.translateBatch(
                    listOf("Hello"),
                    TranslatorLanguage.ENGLISH,
                    TranslatorLanguage.PORTUGUESE_BR,
                )
            }
            assertEquals(403, err.httpCode)
        }
    }

    @Test
    fun `libretranslate posts json with optional api_key normalizes base url and parses errors`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"translatedText":"Olá"}"""))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"Invalid API key"}"""))

            val baseWithoutSuffix = server.url("/api").toString().trimEnd('/')
            val provider = LibreTranslateProvider(
                baseUrl = baseWithoutSuffix,
                apiKey = "secret-key",
                httpClient = httpClient,
            )

            val res = provider.translateBatch(
                listOf("你好"),
                TranslatorLanguage.CHINESE_SIMPLIFIED,
                TranslatorLanguage.PORTUGUESE_BR,
            )
            assertEquals(listOf("Olá"), res)

            val req = server.takeRequest()
            assertEquals("/api/translate", req.path)
            val bodyJson = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
            assertEquals("zh-Hans", bodyJson["source"]!!.jsonPrimitive.content)
            assertEquals("pt-BR", bodyJson["target"]!!.jsonPrimitive.content)
            assertEquals("secret-key", bodyJson["api_key"]!!.jsonPrimitive.content)

            val err = assertThrows<TranslationException> {
                provider.translateBatch(listOf("Hi"), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR)
            }
            assertEquals(400, err.httpCode)
            assertTrue(err.message!!.contains("Invalid API key"))
        }
    }

    @Test
    fun `composite provider falls back on failure aggregates errors and rethrows CancellationException`() {
        runBlocking {
            val failing = object : TranslationProvider {
                override val id = "fail1"
                override val displayName = "Fail 1"
                override suspend fun translateBatch(
                    texts: List<String>,
                    sourceLang: TranslatorLanguage,
                    targetLang: TranslatorLanguage,
                ): List<String> = throw TranslationException("Primary down", id, 503)
            }
            val succeeding = object : TranslationProvider {
                override val id = "ok2"
                override val displayName = "OK 2"
                override suspend fun translateBatch(
                    texts: List<String>,
                    sourceLang: TranslatorLanguage,
                    targetLang: TranslatorLanguage,
                ): List<String> = texts.map { "[$it]" }
            }
            val cancelling = object : TranslationProvider {
                override val id = "cancel"
                override val displayName = "Cancel"
                override suspend fun translateBatch(
                    texts: List<String>,
                    sourceLang: TranslatorLanguage,
                    targetLang: TranslatorLanguage,
                ): List<String> = throw CancellationException("Cancelled")
            }

            val fallback = CompositeTranslationProvider(listOf(failing, succeeding))
            assertEquals(listOf("[Hello]"), fallback.translateBatch(listOf("Hello"), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR))

            val allFail = CompositeTranslationProvider(listOf(failing, failing))
            val aggErr = assertThrows<TranslationException> {
                allFail.translateBatch(listOf("Hello"), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR)
            }
            assertTrue(aggErr.suppressedExceptions.isNotEmpty())

            val cancelComposite = CompositeTranslationProvider(listOf(cancelling, succeeding))
            assertThrows<CancellationException> {
                cancelComposite.translateBatch(listOf("Hello"), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR)
            }
        }
    }

    @Test
    fun `invalid or schemeless endpoint URLs throw TranslationException across providers`() {
        runBlocking {
            val invalidUrls = listOf("localhost:5000", "libretranslate.com/translate", "not a url", "ftp://example.com", "")

            for (badUrl in invalidUrls) {
                val gtx = GoogleTranslateGtxProvider(httpClient = httpClient, endpointUrl = badUrl)
                assertThrows<TranslationException> {
                    gtx.translateBatch(listOf("Hello"), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR)
                }

                val myMemory = MyMemoryTranslationProvider(httpClient = httpClient, endpointUrl = badUrl)
                assertThrows<TranslationException> {
                    myMemory.translateBatch(listOf("Hello"), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR)
                }
            }

            for (badUrl in listOf("localhost:5000", "libretranslate.com/translate", "not a url", "ftp://example.com")) {
                val libreDirect = LibreTranslateProvider(baseUrl = badUrl, httpClient = httpClient)
                assertThrows<TranslationException> {
                    libreDirect.translateBatch(listOf("Hello"), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR)
                }

                val libreFromRegistry = TranslationProviderRegistry.create(
                    TranslatorConfig(providerId = "libretranslate", libreTranslateUrl = badUrl),
                    httpClient,
                )
                assertThrows<TranslationException> {
                    libreFromRegistry.translateBatch(listOf("Hello"), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR)
                }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `mymemory and libretranslate throw TranslationException on non-primitive JSON fields`() {
        runBlocking {
            val myMemory = MyMemoryTranslationProvider(
                httpClient = httpClient,
                endpointUrl = server.url("/get").toString(),
            )

            val myMemoryBadPayloads = listOf(
                """{"responseData":{"translatedText":"ERR"},"responseStatus":{"code":403}}""",
                """{"responseData":{"translatedText":"ERR"},"responseStatus":[403]}""",
                """{"responseData":{"translatedText":"ERR"},"quotaFinished":{"flag":true},"responseStatus":200}""",
                """{"responseData":{"translatedText":{"text":"Olá"}},"responseStatus":200}""",
                """{"responseData":null,"responseStatus":200}""",
                """{"responseData":[],"responseStatus":200}""",
            )
            for (payload in myMemoryBadPayloads) {
                server.enqueue(MockResponse().setResponseCode(200).setBody(payload))
                assertThrows<TranslationException> {
                    myMemory.translateBatch(listOf("Hello"), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR)
                }
            }

            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"responseData":{"translatedText":"ERR"},"responseDetails":{"msg":"bad"},"responseStatus":403}""",
                ),
            )
            val status403Err = assertThrows<TranslationException> {
                myMemory.translateBatch(listOf("Hello"), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR)
            }
            assertEquals(403, status403Err.httpCode)

            val libre = LibreTranslateProvider(
                baseUrl = server.url("/").toString(),
                httpClient = httpClient,
            )
            val libreBadPayloads = listOf(
                """{"translatedText":{"nested":"Olá"}}""",
                """{"translatedText":["Olá"]}""",
                """["array_root"]""",
            )
            for (payload in libreBadPayloads) {
                server.enqueue(MockResponse().setResponseCode(200).setBody(payload))
                assertThrows<TranslationException> {
                    libre.translateBatch(listOf("Hello"), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR)
                }
            }

            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"nested":"bad"}}"""))
            val libreErr = assertThrows<TranslationException> {
                libre.translateBatch(listOf("Hello"), TranslatorLanguage.ENGLISH, TranslatorLanguage.PORTUGUESE_BR)
            }
            assertEquals(400, libreErr.httpCode)
        }
    }

    @Test
    fun `provider registry resolves all known provider ids and defaults unknown id to google_gtx`() {
        assertIs<GoogleTranslateGtxProvider>(
            TranslationProviderRegistry.create(TranslatorConfig(providerId = "google_gtx"), httpClient),
        )
        assertIs<MyMemoryTranslationProvider>(
            TranslationProviderRegistry.create(TranslatorConfig(providerId = "mymemory"), httpClient),
        )
        assertIs<LibreTranslateProvider>(
            TranslationProviderRegistry.create(TranslatorConfig(providerId = "libretranslate"), httpClient),
        )
        assertIs<CompositeTranslationProvider>(
            TranslationProviderRegistry.create(TranslatorConfig(providerId = "composite"), httpClient),
        )
        assertIs<GoogleTranslateGtxProvider>(
            TranslationProviderRegistry.create(TranslatorConfig(providerId = "unknown_provider"), httpClient),
        )
        assertIs<CompositeTranslationProvider>(
            TranslationProviderRegistry.createWithFallback(TranslatorConfig(providerId = "mymemory"), httpClient),
        )
        assertTrue(TranslationProviderRegistry.availableProviders.size >= 4)
    }
}
