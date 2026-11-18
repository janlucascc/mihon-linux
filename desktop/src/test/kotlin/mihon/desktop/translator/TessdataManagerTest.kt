package mihon.desktop.translator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TessdataManagerTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `resolveDatapath finds system eng traineddata on Linux host`() {
        val manager = TessdataManager()
        val datapath = manager.resolveDatapath("eng")
        assertNotNull(datapath, "Expected system eng.traineddata to be resolved")
        assertTrue(File(datapath, "eng.traineddata").isFile)
        assertTrue(manager.isLanguageAvailable("eng"))
    }

    @Test
    fun `ensureLanguage downloads traineddata atomically and caches locally`(@TempDir tempDir: Path) {
        runBlocking {
            val userDir = tempDir.resolve("user-tessdata").toFile()
            val payload = ByteArray(16_384) { (it % 251).toByte() }
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(Buffer().write(payload)),
            )

            val manager = TessdataManager(
                userTessdataDir = userDir,
                searchDirs = listOf(userDir),
                baseUrl = server.url("/").toString(),
            )

            val first = manager.ensureLanguage("jpn", allowDownload = true)
            assertTrue(first.isSuccess)
            assertEquals(userDir.absolutePath, first.getOrThrow().absolutePath)
            assertEquals(16_384L, File(userDir, "jpn.traineddata").length())
            assertTrue(
                userDir.listFiles().orEmpty().none { it.name.contains(".tmp.") },
                "No temporary .tmp files should remain after atomic move",
            )
            assertEquals(1, server.requestCount)

            // Second call must hit local cache with zero additional HTTP calls
            val second = manager.ensureLanguage("jpn", allowDownload = true)
            assertTrue(second.isSuccess)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `ensureLanguage deduplicates concurrent downloads for same language`(@TempDir tempDir: Path) {
        runBlocking {
            val userDir = tempDir.resolve("user-tessdata").toFile()
            val payload = ByteArray(12_000) { 42 }
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))

            val manager = TessdataManager(
                userTessdataDir = userDir,
                searchDirs = listOf(userDir),
                baseUrl = server.url("/").toString(),
            )

            val results = listOf(
                async { manager.ensureLanguage("kor", allowDownload = true) },
                async { manager.ensureLanguage("kor", allowDownload = true) },
                async { manager.ensureLanguage("kor", allowDownload = true) },
            ).awaitAll()

            assertTrue(results.all { it.isSuccess })
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `ensureLanguage fails cleanly on HTTP 404 or truncated payload without leaving temp files`(@TempDir tempDir: Path) {
        runBlocking {
            val userDir = tempDir.resolve("user-tessdata").toFile()
            server.enqueue(MockResponse().setResponseCode(404).setBody("Not found"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("<html>tiny</html>"))

            val manager = TessdataManager(
                userTessdataDir = userDir,
                searchDirs = listOf(userDir),
                baseUrl = server.url("/").toString(),
            )

            val err404 = manager.ensureLanguage("por", allowDownload = true)
            assertTrue(err404.isFailure)
            assertFalse(File(userDir, "por.traineddata").exists())

            val errTruncated = manager.ensureLanguage("spa", allowDownload = true)
            assertTrue(errTruncated.isFailure)
            assertFalse(File(userDir, "spa.traineddata").exists())
            assertTrue(userDir.listFiles().orEmpty().none { it.name.contains(".tmp.") })
        }
    }

    @Test
    fun `resolveDatapath materializes compound language split across directories`(@TempDir tempDir: Path) {
        val sysDir = tempDir.resolve("sys").toFile().apply { mkdirs() }
        val extraDir = tempDir.resolve("extra").toFile().apply { mkdirs() }
        val userDir = tempDir.resolve("user").toFile()

        File(sysDir, "eng.traineddata").writeBytes(ByteArray(12_000) { 1 })
        File(extraDir, "por.traineddata").writeBytes(ByteArray(12_000) { 2 })

        val manager = TessdataManager(
            userTessdataDir = userDir,
            searchDirs = listOf(sysDir, extraDir, userDir),
        )

        val datapath = manager.resolveDatapath("eng+por")
        assertNotNull(datapath)
        assertEquals(userDir.absolutePath, datapath.absolutePath)
        assertTrue(File(userDir, "eng.traineddata").exists())
        assertTrue(File(userDir, "por.traineddata").exists())
        assertTrue(manager.listAvailableLanguages().containsAll(setOf("eng", "por")))
    }

    @Test
    fun `parseLanguageComponents rejects path traversal and ensureLanguage respects allowDownload false`(@TempDir tempDir: Path) {
        runBlocking {
            val userDir = tempDir.resolve("user").toFile()
            val manager = TessdataManager(userTessdataDir = userDir, searchDirs = listOf(userDir))

            assertThrows<IllegalArgumentException> { manager.parseLanguageComponents("../evil") }
            assertThrows<IllegalArgumentException> { manager.parseLanguageComponents("jpn/../../x") }
            assertThrows<IllegalArgumentException> { manager.parseLanguageComponents("   ") }

            val offlineFail = manager.ensureLanguage("jpn", allowDownload = false)
            assertTrue(offlineFail.isFailure)
            assertNull(manager.resolveDatapath("jpn"))
        }
    }

    @Test
    fun `resolveDatapath handles 20 rounds of 8 concurrent composite calls on fresh userDir`(@TempDir tempDir: Path) {
        runBlocking {
            for (round in 0 until 20) {
                val roundDir = tempDir.resolve("round_$round").toFile()
                val dirA = File(roundDir, "dirA").apply { mkdirs() }
                val dirB = File(roundDir, "dirB").apply { mkdirs() }
                val userDir = File(roundDir, "user")

                File(dirA, "alpha.traineddata").writeBytes(ByteArray(12_000) { 11 })
                File(dirB, "beta.traineddata").writeBytes(ByteArray(12_000) { 22 })

                val manager = TessdataManager(
                    userTessdataDir = userDir,
                    searchDirs = listOf(dirA, dirB, userDir),
                )

                val results = (0 until 8).map {
                    async(Dispatchers.IO) {
                        manager.resolveDatapath("alpha+beta")
                    }
                }.awaitAll()

                assertTrue(
                    results.all { it?.absolutePath == userDir.absolutePath },
                    "Round $round had null or unexpected datapath: $results",
                )
                assertTrue(File(userDir, "alpha.traineddata").length() >= 12_000L)
                assertTrue(File(userDir, "beta.traineddata").length() >= 12_000L)
                assertTrue(userDir.listFiles().orEmpty().none { it.name.contains(".tmp.") })
            }
        }
    }

    @Test
    fun `concurrent overlapping composite ensureLanguage calls succeed and deduplicate downloads`(@TempDir tempDir: Path) {
        runBlocking {
            val sysDir = tempDir.resolve("sys").toFile().apply { mkdirs() }
            val userDir = tempDir.resolve("user").toFile()
            File(sysDir, "eng.traineddata").writeBytes(ByteArray(12_000) { 7 })

            val jpnRequests = AtomicInteger(0)
            val korRequests = AtomicInteger(0)
            val engRequests = AtomicInteger(0)
            val modelBytes = ByteArray(24_576) { (it % 199).toByte() }

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    Thread.sleep(60)
                    return when (request.path) {
                        "/jpn.traineddata" -> {
                            jpnRequests.incrementAndGet()
                            MockResponse().setResponseCode(200).setBody(Buffer().write(modelBytes))
                        }
                        "/kor.traineddata" -> {
                            korRequests.incrementAndGet()
                            MockResponse().setResponseCode(200).setBody(Buffer().write(modelBytes))
                        }
                        "/eng.traineddata" -> {
                            engRequests.incrementAndGet()
                            MockResponse().setResponseCode(404)
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            val manager = TessdataManager(
                userTessdataDir = userDir,
                searchDirs = listOf(sysDir, userDir),
                baseUrl = server.url("/").toString(),
            )

            val jobs = (0 until 4).map {
                async(Dispatchers.IO) { manager.ensureLanguage("jpn+eng", allowDownload = true) }
            } + (0 until 4).map {
                async(Dispatchers.IO) { manager.ensureLanguage("jpn+kor", allowDownload = true) }
            }
            val results = jobs.awaitAll()

            assertTrue(results.all { it.isSuccess }, "Expected all 8 concurrent calls to succeed: $results")
            assertTrue(results.all { it.getOrThrow().absolutePath == userDir.absolutePath })
            assertEquals(1, jpnRequests.get())
            assertEquals(1, korRequests.get())
            assertEquals(0, engRequests.get())
            assertEquals(24_576L, File(userDir, "jpn.traineddata").length())
            assertEquals(24_576L, File(userDir, "kor.traineddata").length())
            assertTrue(File(userDir, "eng.traineddata").length() >= 12_000L)
            assertTrue(userDir.listFiles().orEmpty().none { it.name.contains(".tmp.") })
        }
    }

    @Test
    fun `resolveDatapath preserves valid symlink and replaces broken symlink or corrupt file`(@TempDir tempDir: Path) {
        val sysDir = tempDir.resolve("sys").toFile().apply { mkdirs() }
        val extraDir = tempDir.resolve("extra").toFile().apply { mkdirs() }
        val userDir = tempDir.resolve("user").toFile().apply { mkdirs() }

        val sysEng = File(sysDir, "eng.traineddata").apply { writeBytes(ByteArray(12_000) { 1 }) }
        val extraPor = File(extraDir, "por.traineddata").apply { writeBytes(ByteArray(12_000) { 2 }) }
        val extraSpa = File(extraDir, "spa.traineddata").apply { writeBytes(ByteArray(12_000) { 3 }) }

        val userEngPath = File(userDir, "eng.traineddata").toPath()
        Files.createSymbolicLink(userEngPath, sysEng.toPath().toAbsolutePath())

        val userPorPath = File(userDir, "por.traineddata").toPath()
        Files.createSymbolicLink(userPorPath, tempDir.resolve("nonexistent.traineddata").toAbsolutePath())

        val userSpa = File(userDir, "spa.traineddata").apply { writeBytes(ByteArray(123) { 9 }) }

        val manager = TessdataManager(
            userTessdataDir = userDir,
            searchDirs = listOf(sysDir, extraDir, userDir),
        )

        val resolved = manager.resolveDatapath("eng+por+spa")
        assertNotNull(resolved)
        assertEquals(userDir.absolutePath, resolved.absolutePath)
        assertTrue(Files.isSymbolicLink(userEngPath))
        assertEquals(sysEng.toPath().toAbsolutePath(), Files.readSymbolicLink(userEngPath))
        assertTrue(File(userDir, "por.traineddata").length() >= extraPor.length())
        assertTrue(userSpa.length() >= extraSpa.length())
        assertTrue(userDir.listFiles().orEmpty().none { it.name.contains(".tmp.") })
    }
}
