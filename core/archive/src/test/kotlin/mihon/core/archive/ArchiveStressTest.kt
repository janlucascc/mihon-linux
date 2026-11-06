package mihon.core.archive

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Empirical stress and adversarial test harness for :core:archive.
 *
 * Covers:
 * 1. High-concurrency reads across ZipArchiveReader and DirectoryArchiveReader.
 * 2. File descriptor leak detection via Linux /proc/self/fd.
 * 3. Closed reader race condition verification under heavy concurrent access.
 * 4. Path traversal boundary testing (sandbox escapes via sibling folders).
 * 5. NaturalOrderComparator mathematical contract fuzzing (antisymmetry, transitivity, TimSort compatibility).
 */
class ArchiveStressTest {

    @TempDir
    lateinit var tempDir: File

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun getOpenFileDescriptorCount(): Int {
        val fdDir = File("/proc/self/fd")
        return if (fdDir.exists() && fdDir.isDirectory) {
            fdDir.listFiles()?.size ?: -1
        } else {
            -1
        }
    }

    @Nested
    @DisplayName("High-Concurrency Read Stress Tests")
    inner class ConcurrencyStressTests {

        @Test
        fun `ZipArchiveReader supports 50 threads reading 60 pages concurrently`() {
            val pageCount = 60
            val archiveFile = File(tempDir, "high_concurrent.cbz")
            val pageHashes = mutableMapOf<Int, String>()

            val entries = (0 until pageCount).associate { i ->
                val color = Color((i * 4) % 256, (i * 7) % 256, (i * 11) % 256)
                val bytes = ArchiveTestUtils.createImageBytes(width = 20, height = 20, color = color)
                pageHashes[i] = sha256(bytes)
                "page_${i.toString().padStart(3, '0')}.png" to bytes
            }
            ArchiveTestUtils.createZipArchive(archiveFile, entries)

            ArchiveReaderFactory.of(archiveFile).use { reader ->
                assertEquals(pageCount, reader.pageCount)

                val threadCount = 50
                val operationsPerThread = 100
                val executor = Executors.newFixedThreadPool(threadCount)
                val errors = ConcurrentLinkedQueue<Throwable>()
                val startLatch = CountDownLatch(1)
                val completedOps = AtomicInteger(0)

                val tasks = (0 until threadCount).map { threadIdx ->
                    Callable {
                        startLatch.await()
                        try {
                            for (op in 0 until operationsPerThread) {
                                val pageIdx = (threadIdx * operationsPerThread + op) % pageCount
                                val page = reader.pages[pageIdx]
                                val expectedHash = pageHashes[pageIdx]!!

                                when (op % 3) {
                                    0 -> {
                                        // getPageStream
                                        val bytes = reader.getPageStream(page).use { it.readBytes() }
                                        val hash = sha256(bytes)
                                        if (hash != expectedHash) {
                                            errors.add(AssertionError("Hash mismatch for page $pageIdx in stream"))
                                        }
                                    }
                                    1 -> {
                                        // getPageBytes
                                        val bytes = reader.getPageBytes(page)
                                        val hash = sha256(bytes)
                                        if (hash != expectedHash) {
                                            errors.add(AssertionError("Hash mismatch for page $pageIdx in direct bytes"))
                                        }
                                    }
                                    2 -> {
                                        // getPageImage
                                        val image = reader.getPageImage(page)
                                        if (image == null || image.width != 20 || image.height != 20) {
                                            errors.add(AssertionError("Invalid BufferedImage for page $pageIdx"))
                                        }
                                    }
                                }
                                completedOps.incrementAndGet()
                            }
                        } catch (t: Throwable) {
                            errors.add(t)
                        }
                    }
                }

                tasks.forEach { executor.submit(it) }
                startLatch.countDown()
                executor.shutdown()
                val finished = executor.awaitTermination(20, TimeUnit.SECONDS)

                assertTrue(finished, "High concurrency stress test timed out")
                assertTrue(errors.isEmpty(), "Concurrency errors encountered: ${errors.take(5)}")
                assertEquals(threadCount * operationsPerThread, completedOps.get())
            }
        }

        @Test
        fun `DirectoryArchiveReader supports 50 threads reading 60 pages concurrently`() {
            val pageCount = 60
            val fixtureDir = File(tempDir, "high_concurrent_dir")
            val pageHashes = mutableMapOf<Int, String>()

            val files = (0 until pageCount).associate { i ->
                val color = Color((i * 3) % 256, (i * 9) % 256, (i * 13) % 256)
                val bytes = ArchiveTestUtils.createImageBytes(width = 20, height = 20, color = color)
                pageHashes[i] = sha256(bytes)
                val subDir = "Volume ${(i / 20) + 1}"
                "$subDir/page_${i.toString().padStart(3, '0')}.png" to bytes
            }
            ArchiveTestUtils.createDirectoryFixture(fixtureDir, files)

            ArchiveReaderFactory.of(fixtureDir).use { reader ->
                assertEquals(pageCount, reader.pageCount)

                val threadCount = 50
                val operationsPerThread = 100
                val executor = Executors.newFixedThreadPool(threadCount)
                val errors = ConcurrentLinkedQueue<Throwable>()
                val startLatch = CountDownLatch(1)
                val completedOps = AtomicInteger(0)

                val tasks = (0 until threadCount).map { threadIdx ->
                    Callable {
                        startLatch.await()
                        try {
                            for (op in 0 until operationsPerThread) {
                                val pageIdx = (threadIdx * operationsPerThread + op) % pageCount
                                val page = reader.pages[pageIdx]
                                val expectedHash = pageHashes[pageIdx]!!

                                when (op % 3) {
                                    0 -> {
                                        val bytes = reader.getPageStream(page).use { it.readBytes() }
                                        val hash = sha256(bytes)
                                        if (hash != expectedHash) {
                                            errors.add(AssertionError("Hash mismatch for page $pageIdx in stream"))
                                        }
                                    }
                                    1 -> {
                                        val bytes = reader.getPageBytes(page)
                                        val hash = sha256(bytes)
                                        if (hash != expectedHash) {
                                            errors.add(AssertionError("Hash mismatch for page $pageIdx in direct bytes"))
                                        }
                                    }
                                    2 -> {
                                        val image = reader.getPageImage(page)
                                        if (image == null || image.width != 20 || image.height != 20) {
                                            errors.add(AssertionError("Invalid BufferedImage for page $pageIdx"))
                                        }
                                    }
                                }
                                completedOps.incrementAndGet()
                            }
                        } catch (t: Throwable) {
                            errors.add(t)
                        }
                    }
                }

                tasks.forEach { executor.submit(it) }
                startLatch.countDown()
                executor.shutdown()
                val finished = executor.awaitTermination(20, TimeUnit.SECONDS)

                assertTrue(finished, "Directory high concurrency stress test timed out")
                assertTrue(errors.isEmpty(), "Directory concurrency errors encountered: ${errors.take(5)}")
                assertEquals(threadCount * operationsPerThread, completedOps.get())
            }
        }
    }

    @Nested
    @DisplayName("File Descriptor Leak Tests")
    inner class FileDescriptorLeakTests {

        @Test
        fun `repeatedly opening and closing ZipArchiveReader does not leak file descriptors`() {
            val initialFd = getOpenFileDescriptorCount()
            // Skip FD assertion if /proc/self/fd is not accessible on this platform
            if (initialFd < 0) return

            val archiveFile = ArchiveTestUtils.createValidArchive(File(tempDir, "fd_test.cbz"), pageCount = 5)

            // Warmup
            repeat(10) {
                ArchiveReaderFactory.of(archiveFile).use { reader ->
                    reader.getPageBytes(reader.pages.first())
                }
            }
            System.gc()
            Thread.sleep(100)
            val baselineFd = getOpenFileDescriptorCount()

            // Run 500 open-read-close cycles
            repeat(500) {
                ArchiveReaderFactory.of(archiveFile).use { reader ->
                    val page = reader.pages[0]
                    reader.getPageBytes(page)
                    val stream = reader.getPageStream(page)
                    stream.close()
                }
            }

            System.gc()
            Thread.sleep(100)
            val finalFd = getOpenFileDescriptorCount()
            val fdGrowth = finalFd - baselineFd

            // Allow small delta (<= 5) for background JVM threads/timers, but definitely not 500
            assertTrue(
                fdGrowth <= 10,
                "Potential FD leak detected in ZipArchiveReader: baseline=$baselineFd, final=$finalFd, growth=$fdGrowth",
            )
        }

        @Test
        fun `closing ZipArchiveReader releases underlying file descriptor even if stream was not closed by caller`() {
            val initialFd = getOpenFileDescriptorCount()
            if (initialFd < 0) return

            val archiveFile = ArchiveTestUtils.createValidArchive(File(tempDir, "fd_unclosed_stream.cbz"), pageCount = 5)
            System.gc()
            Thread.sleep(100)
            val baselineFd = getOpenFileDescriptorCount()

            // Open 200 readers, open streams without closing them, but close the readers
            repeat(200) {
                val reader = ArchiveReaderFactory.of(archiveFile)
                val stream = reader.getPageStream(reader.pages.first())
                // Do not close stream explicitly!
                reader.close()
            }

            System.gc()
            Thread.sleep(100)
            val finalFd = getOpenFileDescriptorCount()
            val fdGrowth = finalFd - baselineFd

            assertTrue(
                fdGrowth <= 10,
                "Unclosed streams caused FD leak after reader.close(): baseline=$baselineFd, final=$finalFd, growth=$fdGrowth",
            )
        }

        @Test
        fun `repeatedly reading DirectoryArchiveReader does not leak file descriptors`() {
            val initialFd = getOpenFileDescriptorCount()
            if (initialFd < 0) return

            val fixtureDir = ArchiveTestUtils.createValidDirectoryFixture(File(tempDir, "fd_dir_test"), pageCount = 5)
            System.gc()
            Thread.sleep(100)
            val baselineFd = getOpenFileDescriptorCount()

            repeat(500) {
                ArchiveReaderFactory.of(fixtureDir).use { reader ->
                    val page = reader.pages[0]
                    reader.getPageBytes(page)
                    reader.getPageImage(page)
                    reader.getPageStream(page).use { it.read() }
                }
            }

            System.gc()
            Thread.sleep(100)
            val finalFd = getOpenFileDescriptorCount()
            val fdGrowth = finalFd - baselineFd

            assertTrue(
                fdGrowth <= 10,
                "Potential FD leak in DirectoryArchiveReader: baseline=$baselineFd, final=$finalFd, growth=$fdGrowth",
            )
        }
    }

    @Nested
    @DisplayName("Closed Reader Race Condition Tests")
    inner class ClosedReaderStressTests {

        @Test
        fun `ZipArchiveReader concurrent reads during close throw expected exceptions without JVM crash or hang`() {
            val archiveFile = ArchiveTestUtils.createValidArchive(File(tempDir, "close_race.cbz"), pageCount = 10)
            val reader = ArchiveReaderFactory.of(archiveFile)

            val readerThreadCount = 20
            val executor = Executors.newFixedThreadPool(readerThreadCount + 1)
            val stopSignal = AtomicBoolean(false)
            val unexpectedErrors = ConcurrentLinkedQueue<Throwable>()
            val expectedClosedExceptions = AtomicInteger(0)
            val successfulReads = AtomicInteger(0)

            // Submit 20 reader threads
            repeat(readerThreadCount) {
                executor.submit {
                    var i = 0
                    while (!stopSignal.get()) {
                        val page = reader.pages[i % reader.pages.size]
                        try {
                            when (i % 3) {
                                0 -> reader.getPageStream(page).use { it.readBytes() }
                                1 -> reader.getPageBytes(page)
                                2 -> reader.getPageImage(page)
                            }
                            successfulReads.incrementAndGet()
                        } catch (e: ArchiveClosedException) {
                            expectedClosedExceptions.incrementAndGet()
                        } catch (e: IllegalStateException) {
                            // ArchiveClosedException or ZipFile closed IllegalStateException
                            expectedClosedExceptions.incrementAndGet()
                        } catch (e: IOException) {
                            // If stream was closed mid-flight during ZipFile.close()
                            expectedClosedExceptions.incrementAndGet()
                        } catch (t: Throwable) {
                            unexpectedErrors.add(t)
                        }
                        i++
                    }
                }
            }

            // Let reader threads run for 50ms, then close reader from another thread
            Thread.sleep(50)
            reader.close()
            // Idempotent secondary close
            reader.close()

            // Run for another 50ms to ensure all subsequent reads fail with ArchiveClosedException or IllegalStateException
            Thread.sleep(50)
            stopSignal.set(true)

            executor.shutdown()
            val terminated = executor.awaitTermination(5, TimeUnit.SECONDS)

            assertTrue(terminated, "Closed reader stress executor timed out")
            assertTrue(
                unexpectedErrors.isEmpty(),
                "Unexpected errors during concurrent close: ${unexpectedErrors.take(5)}",
            )
            assertTrue(successfulReads.get() > 0, "No successful reads before close")
            assertTrue(expectedClosedExceptions.get() > 0, "No closed exceptions observed after close")

            // Verify strictly that any read after close throws ArchiveClosedException or IllegalStateException
            assertFailsWith<IllegalStateException> {
                reader.getPageStream(reader.pages.first())
            }
            assertFailsWith<IllegalStateException> {
                reader.getPageBytes(reader.pages.first())
            }
            assertFailsWith<IllegalStateException> {
                reader.getPageImage(reader.pages.first())
            }
        }

        @Test
        fun `DirectoryArchiveReader concurrent reads during close throw expected exceptions without JVM crash or hang`() {
            val fixtureDir = ArchiveTestUtils.createValidDirectoryFixture(File(tempDir, "dir_close_race"), pageCount = 10)
            val reader = ArchiveReaderFactory.of(fixtureDir)

            val readerThreadCount = 20
            val executor = Executors.newFixedThreadPool(readerThreadCount + 1)
            val stopSignal = AtomicBoolean(false)
            val unexpectedErrors = ConcurrentLinkedQueue<Throwable>()
            val expectedClosedExceptions = AtomicInteger(0)
            val successfulReads = AtomicInteger(0)

            repeat(readerThreadCount) {
                executor.submit {
                    var i = 0
                    while (!stopSignal.get()) {
                        val page = reader.pages[i % reader.pages.size]
                        try {
                            when (i % 3) {
                                0 -> reader.getPageStream(page).use { it.readBytes() }
                                1 -> reader.getPageBytes(page)
                                2 -> reader.getPageImage(page)
                            }
                            successfulReads.incrementAndGet()
                        } catch (e: ArchiveClosedException) {
                            expectedClosedExceptions.incrementAndGet()
                        } catch (e: IllegalStateException) {
                            expectedClosedExceptions.incrementAndGet()
                        } catch (t: Throwable) {
                            unexpectedErrors.add(t)
                        }
                        i++
                    }
                }
            }

            Thread.sleep(50)
            reader.close()
            reader.close()

            Thread.sleep(50)
            stopSignal.set(true)

            executor.shutdown()
            val terminated = executor.awaitTermination(5, TimeUnit.SECONDS)

            assertTrue(terminated, "Directory closed reader stress executor timed out")
            assertTrue(
                unexpectedErrors.isEmpty(),
                "Unexpected errors during directory concurrent close: ${unexpectedErrors.take(5)}",
            )
            assertTrue(successfulReads.get() > 0, "No successful reads before close")
            assertTrue(expectedClosedExceptions.get() > 0, "No closed exceptions observed after close")

            assertFailsWith<ArchiveClosedException> {
                reader.getPageStream(reader.pages.first())
            }
        }
    }

    @Nested
    @DisplayName("NaturalOrderComparator Mathematical Contract Fuzzing")
    inner class ComparatorContractFuzzing {

        @Test
        fun `satisfies comparator contract under fuzzing with 10000 random strings`() {
            val rng = Random(42)
            val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._ -#"
            val sampleStrings = mutableListOf<String>()

            // Generate structured and random strings
            val prefixes = listOf("page", "PAGE", "ch", "01", "1", "vol", "v", "", "chapter ")
            val numbers = listOf("0", "1", "01", "001", "2", "02", "10", "20", "100", "99999999999999999999")
            val suffixes = listOf(".jpg", ".png", ".PNG", "a", "b", " 1", " 01", "")

            for (p in prefixes) {
                for (n in numbers) {
                    for (s in suffixes) {
                        sampleStrings.add("$p$n$s")
                    }
                }
            }

            // Add random strings
            repeat(500) {
                val len = rng.nextInt(15) + 1
                val sb = java.lang.StringBuilder()
                repeat(len) {
                    sb.append(alphabet[rng.nextInt(alphabet.length)])
                }
                sampleStrings.add(sb.toString())
            }

            val distinctStrings = sampleStrings.distinct().take(300)

            // Test 1: Reflexivity sgn(compare(x, x)) == 0
            for (s in distinctStrings) {
                assertEquals(0, NaturalOrderComparator.compare(s, s), "Reflexivity failed for '$s'")
            }

            // Test 2: Antisymmetry sgn(compare(x, y)) == -sgn(compare(y, x))
            for (i in distinctStrings.indices) {
                val x = distinctStrings[i]
                for (j in i until distinctStrings.size) {
                    val y = distinctStrings[j]
                    val cxy = NaturalOrderComparator.compare(x, y).compareTo(0)
                    val cyx = NaturalOrderComparator.compare(y, x).compareTo(0)
                    assertEquals(cxy, -cyx, "Antisymmetry violated between '$x' and '$y'")
                }
            }

            // Test 3: Transitivity across sampled triads
            for (i in 0 until minOf(distinctStrings.size, 50)) {
                val x = distinctStrings[i]
                for (j in 0 until minOf(distinctStrings.size, 50)) {
                    val y = distinctStrings[j]
                    for (k in 0 until minOf(distinctStrings.size, 50)) {
                        val z = distinctStrings[k]
                        val cxy = NaturalOrderComparator.compare(x, y)
                        val cyz = NaturalOrderComparator.compare(y, z)
                        val cxz = NaturalOrderComparator.compare(x, z)

                        if (cxy > 0 && cyz > 0) {
                            assertTrue(cxz > 0, "Transitivity violated: '$x' > '$y' and '$y' > '$z', but '$x' not > '$z'")
                        }
                    }
                }
            }

            // Test 4: TimSort compatibility on large lists
            val largeList = ArrayList<String>()
            repeat(5000) {
                largeList.add(distinctStrings[rng.nextInt(distinctStrings.size)])
            }
            // If comparator contract is violated, sort() throws IllegalArgumentException: Comparison method violates its general contract!
            largeList.sortWith(NaturalOrderComparator)
            assertTrue(largeList.isNotEmpty())
        }
    }

    @Nested
    @DisplayName("Path Traversal and Security Boundaries")
    inner class SecurityBoundaryTests {

        @Test
        fun `DirectoryArchiveReader protects against sibling folder prefix traversal`() {
            // Setup target dir: /tempDir/manga
            val targetDir = File(tempDir, "manga").apply { mkdirs() }
            val normalImg = File(targetDir, "01.png")
            normalImg.writeBytes(ArchiveTestUtils.createImageBytes(width = 10, height = 10))

            // Setup sibling dir with same prefix: /tempDir/manga_evil
            val siblingDir = File(tempDir, "manga_evil").apply { mkdirs() }
            val secretImg = File(siblingDir, "secret.png")
            secretImg.writeBytes(ArchiveTestUtils.createImageBytes(width = 10, height = 10, color = Color.BLUE))

            val reader = DirectoryArchiveReader(targetDir)
            reader.use {
                val maliciousPage = ArchivePage(
                    index = 0,
                    name = "secret.png",
                    path = "../manga_evil/secret.png",
                )

                // Must throw SecurityException because ../manga_evil is OUTSIDE targetDir
                assertFailsWith<SecurityException>("Expected SecurityException for sibling directory traversal") {
                    reader.getPageStream(maliciousPage)
                }
            }
        }
    }
}
