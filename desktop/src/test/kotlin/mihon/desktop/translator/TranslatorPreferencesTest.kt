package mihon.desktop.translator

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.prefs.Preferences
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranslatorPreferencesTest {

    private lateinit var testNode: Preferences

    @BeforeEach
    fun setUp() {
        testNode = Preferences.userRoot().node("mihon-linux-translator-test-${UUID.randomUUID()}")
        TranslatorPreferences.setBackingNodeForTest(testNode)
    }

    @AfterEach
    fun tearDown() {
        TranslatorPreferences.setBackingNodeForTest(null)
        runCatching { testNode.removeNode() }
    }

    @Test
    fun `default load returns expected TranslatorConfig defaults`() {
        val cfg = TranslatorPreferences.load()
        assertFalse(cfg.enabledByDefault)
        assertEquals(TranslatorLanguage.JAPANESE, cfg.sourceLanguage)
        assertEquals(TranslatorLanguage.PORTUGUESE_BR, cfg.targetLanguage)
        assertEquals("google_gtx", cfg.providerId)
        assertEquals("https://libretranslate.com/translate", cfg.libreTranslateUrl)
        assertEquals("", cfg.libreTranslateApiKey)
        assertTrue(cfg.autoDownloadTessdata)
    }

    @Test
    fun `round-trip save and load preserves all fields`() {
        val custom = TranslatorConfig(
            enabledByDefault = true,
            sourceLanguage = TranslatorLanguage.KOREAN,
            targetLanguage = TranslatorLanguage.ENGLISH,
            providerId = "libretranslate",
            libreTranslateUrl = "http://localhost:5000/translate",
            libreTranslateApiKey = "secret_key_123&x=\"abc\"_çãõ",
            autoDownloadTessdata = false,
        )
        TranslatorPreferences.save(custom)

        val loaded = TranslatorPreferences.load()
        assertEquals(custom, loaded)
    }

    @Test
    fun `round-trip every TranslatorLanguage and providerId`() {
        for (src in TranslatorLanguage.sourceLanguages()) {
            for (dst in TranslatorLanguage.targetLanguages()) {
                val cfg = TranslatorConfig(sourceLanguage = src, targetLanguage = dst)
                TranslatorPreferences.save(cfg)
                val loaded = TranslatorPreferences.load()
                assertEquals(src, loaded.sourceLanguage)
                assertEquals(dst, loaded.targetLanguage)
            }
        }

        for (provider in listOf("google_gtx", "mymemory", "libretranslate", "composite")) {
            val cfg = TranslatorConfig(providerId = provider)
            TranslatorPreferences.save(cfg)
            assertEquals(provider, TranslatorPreferences.load().providerId)
        }
    }

    @Test
    fun `corrupt or invalid values in backing Preferences fall back safely`() {
        testNode.put(TranslatorPreferences.KEY_SOURCE_LANGUAGE, "INVALID_LANG_XYZ")
        testNode.put(TranslatorPreferences.KEY_TARGET_LANGUAGE, "AUTO")
        testNode.put(TranslatorPreferences.KEY_PROVIDER_ID, "unknown_provider_999")
        testNode.put(TranslatorPreferences.KEY_LIBRETRANSLATE_URL, "   ")

        val loaded = TranslatorPreferences.load()
        assertEquals(TranslatorLanguage.JAPANESE, loaded.sourceLanguage)
        assertEquals(TranslatorLanguage.PORTUGUESE_BR, loaded.targetLanguage)
        assertEquals("google_gtx", loaded.providerId)
        assertEquals("https://libretranslate.com/translate", loaded.libreTranslateUrl)
    }

    @Test
    fun `concurrent load and save calls are thread-safe`() {
        val pool = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(1)
        try {
            val futures = (0 until 4).map { idx ->
                pool.submit {
                    latch.await()
                    repeat(25) { step ->
                        val lang = TranslatorLanguage.targetLanguages()[(idx + step) % TranslatorLanguage.targetLanguages().size]
                        TranslatorPreferences.save(
                            TranslatorConfig(
                                enabledByDefault = (step % 2 == 0),
                                sourceLanguage = lang,
                                targetLanguage = TranslatorLanguage.PORTUGUESE_BR,
                            ),
                        )
                        val read = TranslatorPreferences.load()
                        assertTrue(read.providerId.isNotBlank())
                    }
                }
            }
            latch.countDown()
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }
}
