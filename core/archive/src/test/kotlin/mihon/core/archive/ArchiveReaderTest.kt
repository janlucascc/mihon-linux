package mihon.core.archive

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchiveReaderTest {

    @TempDir
    lateinit var tempDir: File

    @Nested
    @DisplayName("NaturalOrderComparator Tests")
    inner class NaturalOrderComparatorTests {

        @Test
        fun `compares simple numeric page names correctly`() {
            assertTrue(NaturalOrderComparator.compare("page 2.jpg", "page 10.jpg") < 0)
            assertTrue(NaturalOrderComparator.compare("page 10.jpg", "page 2.jpg") > 0)
            assertTrue(NaturalOrderComparator.compare("01.png", "2.png") < 0)
            assertTrue(NaturalOrderComparator.compare("2.png", "01.png") > 0)
        }

        @Test
        fun `breaks ties for identical numeric value using shorter representation first`() {
            assertTrue(NaturalOrderComparator.compare("1.png", "01.png") < 0)
            assertTrue(NaturalOrderComparator.compare("01.png", "001.png") < 0)
            assertTrue(NaturalOrderComparator.compare("001.png", "1.png") > 0)
        }

        @Test
        fun `text chunks compare case-insensitively with exact tie-breaker`() {
            assertTrue(NaturalOrderComparator.compare("PAGE 2.jpg", "page 10.jpg") < 0)
            assertEquals(0, NaturalOrderComparator.compare("page 2.jpg", "page 2.jpg"))
            // Case difference tie-breaker
            assertTrue(NaturalOrderComparator.compare("Page 2.jpg", "page 2.jpg") != 0)
        }

        @Test
        fun `handles numbers exceeding 64-bit Long without overflow`() {
            val large1 = "page_9999999999999999999999999999999999999998.jpg"
            val large2 = "page_9999999999999999999999999999999999999999.jpg"
            val huge = "page_10000000000000000000000000000000000000000.jpg"

            assertTrue(NaturalOrderComparator.compare(large1, large2) < 0)
            assertTrue(NaturalOrderComparator.compare(large2, huge) < 0)
        }

        @Test
        fun `sorts full list naturally`() {
            val input = listOf("p10", "p2", "p1", "p20", "p01", "p100")
            val sorted = input.sortedWith(NaturalOrderComparator)
            val expected = listOf("p1", "p01", "p2", "p10", "p20", "p100")
            assertEquals(expected, sorted)
        }
    }

    @Nested
    @DisplayName("ArchiveFilter Tests")
    inner class ArchiveFilterTests {

        @Test
        fun `whitelists supported image extensions`() {
            assertTrue(ArchiveFilter.isSupportedExtension("jpg"))
            assertTrue(ArchiveFilter.isSupportedExtension(".jpeg"))
            assertTrue(ArchiveFilter.isSupportedExtension("PNG"))
            assertTrue(ArchiveFilter.isSupportedExtension("gif"))
            assertTrue(ArchiveFilter.isSupportedExtension("bmp"))
            assertTrue(ArchiveFilter.isSupportedExtension("webp"))
        }

        @Test
        fun `rejects unsupported non-image extensions`() {
            assertFalse(ArchiveFilter.isSupportedExtension("xml"))
            assertFalse(ArchiveFilter.isSupportedExtension("json"))
            assertFalse(ArchiveFilter.isSupportedExtension("txt"))
            assertFalse(ArchiveFilter.isSupportedExtension("db"))
            assertFalse(ArchiveFilter.isSupportedExtension("nfo"))
        }

        @Test
        fun `identifies macOS resource forks and AppleDouble files`() {
            assertTrue(ArchiveFilter.isMacOsArtifact("__MACOSX/01.jpg"))
            assertTrue(ArchiveFilter.isMacOsArtifact("manga/__MACOSX/01.jpg"))
            assertTrue(ArchiveFilter.isMacOsArtifact("._page1.png"))
            assertTrue(ArchiveFilter.isMacOsArtifact("chapter1/._page1.png"))
            assertFalse(ArchiveFilter.isMacOsArtifact("page1.png"))
        }

        @Test
        fun `identifies hidden and OS metadata files`() {
            assertTrue(ArchiveFilter.isHiddenOrSystemFile(".DS_Store"))
            assertTrue(ArchiveFilter.isHiddenOrSystemFile("manga/.DS_Store"))
            assertTrue(ArchiveFilter.isHiddenOrSystemFile("Thumbs.db"))
            assertTrue(ArchiveFilter.isHiddenOrSystemFile("desktop.ini"))
            assertTrue(ArchiveFilter.isHiddenOrSystemFile("subfolder/ehthumbs.db"))
            assertFalse(ArchiveFilter.isHiddenOrSystemFile("01.jpg"))
            assertFalse(ArchiveFilter.isHiddenOrSystemFile("chapter1/01.jpg"))
        }

        @Test
        fun `validates image entries properly`() {
            assertTrue(ArchiveFilter.isValidImageEntry("01.jpg"))
            assertTrue(ArchiveFilter.isValidImageEntry("chapter1/01.png"))
            assertFalse(ArchiveFilter.isValidImageEntry("chapter1/", isDirectory = true))
            assertFalse(ArchiveFilter.isValidImageEntry(""))
            assertFalse(ArchiveFilter.isValidImageEntry("   "))
            assertFalse(ArchiveFilter.isValidImageEntry("/root.png"))
            assertFalse(ArchiveFilter.isValidImageEntry("../parent.png"))
            assertFalse(ArchiveFilter.isValidImageEntry("__MACOSX/._01.jpg"))
            assertFalse(ArchiveFilter.isValidImageEntry(".DS_Store"))
            assertFalse(ArchiveFilter.isValidImageEntry("ComicInfo.xml"))
        }
    }

    @Nested
    @DisplayName("ArchiveReaderFactory Resolution Tests")
    inner class FactoryTests {

        @Test
        fun `of returns ZipArchiveReader for valid cbz file`() {
            val file = ArchiveTestUtils.createValidArchive(File(tempDir, "test.cbz"))
            val reader = ArchiveReaderFactory.of(file)
            reader.use {
                assertIs<ZipArchiveReader>(it)
                assertEquals(3, it.pageCount)
                assertEquals("test.cbz", it.sourceName)
            }
        }

        @Test
        fun `of returns ZipArchiveReader for valid zip file`() {
            val file = ArchiveTestUtils.createValidArchive(File(tempDir, "test.zip"))
            val reader = ArchiveReaderFactory.of(file)
            reader.use {
                assertIs<ZipArchiveReader>(it)
                assertEquals(3, it.pageCount)
                assertEquals("test.zip", it.sourceName)
            }
        }

        @Test
        fun `of handles uppercase extensions case-insensitively`() {
            val fileUpper = ArchiveTestUtils.createValidArchive(File(tempDir, "test_upper.CBZ"))
            val reader = ArchiveReaderFactory.of(fileUpper)
            reader.use {
                assertIs<ZipArchiveReader>(it)
                assertEquals(3, it.pageCount)
            }
        }

        @Test
        fun `of returns DirectoryArchiveReader for directory fixture`() {
            val dir = ArchiveTestUtils.createValidDirectoryFixture(File(tempDir, "manga_chapter"))
            val reader = ArchiveReaderFactory.of(dir)
            reader.use {
                assertIs<DirectoryArchiveReader>(it)
                assertEquals(3, it.pageCount)
                assertEquals("manga_chapter", it.sourceName)
            }
        }

        @Test
        fun `of throws IllegalArgumentException for non-existent file`() {
            val missingFile = File(tempDir, "does_not_exist.cbz")
            val ex = assertFailsWith<IllegalArgumentException> {
                ArchiveReaderFactory.of(missingFile)
            }
            assertTrue(ex.message!!.contains("does not exist"))
        }

        @Test
        fun `of throws IllegalArgumentException for unsupported file formats`() {
            val rarFile = File(tempDir, "test.rar").apply { writeText("dummy") }
            val pdfFile = File(tempDir, "test.pdf").apply { writeText("dummy") }
            val txtFile = File(tempDir, "test.txt").apply { writeText("dummy") }

            assertFailsWith<IllegalArgumentException> { ArchiveReaderFactory.of(rarFile) }
            assertFailsWith<IllegalArgumentException> { ArchiveReaderFactory.of(pdfFile) }
            assertFailsWith<IllegalArgumentException> { ArchiveReaderFactory.of(txtFile) }
        }

        @Test
        fun `isSupported returns true for supported formats and false for unsupported`() {
            val cbz = ArchiveTestUtils.createValidArchive(File(tempDir, "test.cbz"))
            val zip = ArchiveTestUtils.createValidArchive(File(tempDir, "test.zip"))
            val dir = ArchiveTestUtils.createValidDirectoryFixture(File(tempDir, "dir"))
            val rar = File(tempDir, "test.rar").apply { writeText("dummy") }
            val missing = File(tempDir, "missing.cbz")

            assertTrue(ArchiveReaderFactory.isSupported(cbz))
            assertTrue(ArchiveReaderFactory.isSupported(zip))
            assertTrue(ArchiveReaderFactory.isSupported(dir))
            assertFalse(ArchiveReaderFactory.isSupported(rar))
            assertFalse(ArchiveReaderFactory.isSupported(missing))
        }

        @Test
        fun `of throws CorruptedArchiveException for corrupted or zero-byte archives`() {
            val corruptHeader = ArchiveTestUtils.createCorruptedArchive(
                File(tempDir, "corrupt_hdr.cbz"),
                ArchiveTestUtils.CorruptionType.INVALID_HEADER,
            )
            assertFailsWith<CorruptedArchiveException> {
                ArchiveReaderFactory.of(corruptHeader)
            }

            val truncated = ArchiveTestUtils.createCorruptedArchive(
                File(tempDir, "truncated.zip"),
                ArchiveTestUtils.CorruptionType.TRUNCATED_ZIP,
            )
            assertFailsWith<CorruptedArchiveException> {
                ArchiveReaderFactory.of(truncated)
            }

            val zeroBytes = ArchiveTestUtils.createCorruptedArchive(
                File(tempDir, "empty.cbz"),
                ArchiveTestUtils.CorruptionType.ZERO_BYTES,
            )
            assertFailsWith<CorruptedArchiveException> {
                ArchiveReaderFactory.of(zeroBytes)
            }
        }
    }

    @Nested
    @DisplayName("Page Extraction and Streaming Tests")
    inner class ExtractionTests {

        @Test
        fun `ZipArchiveReader reads page stream and bytes correctly`() {
            val file = ArchiveTestUtils.createValidArchive(File(tempDir, "pages.cbz"), pageCount = 3)
            ArchiveReaderFactory.of(file).use { reader ->
                assertEquals(3, reader.pageCount)
                for (page in reader.pages) {
                    val streamBytes = reader.getPageStream(page).use { it.readBytes() }
                    val directBytes = reader.getPageBytes(page)
                    assertTrue(streamBytes.isNotEmpty())
                    assertEquals(streamBytes.size, directBytes.size)
                    assertTrue(streamBytes.contentEquals(directBytes))
                }
            }
        }

        @Test
        fun `DirectoryArchiveReader reads page stream and bytes correctly`() {
            val dir = ArchiveTestUtils.createValidDirectoryFixture(File(tempDir, "folder_pages"), pageCount = 3)
            ArchiveReaderFactory.of(dir).use { reader ->
                assertEquals(3, reader.pageCount)
                for (page in reader.pages) {
                    val streamBytes = reader.getPageStream(page).use { it.readBytes() }
                    val directBytes = reader.getPageBytes(page)
                    assertTrue(streamBytes.isNotEmpty())
                    assertEquals(streamBytes.size, directBytes.size)
                    assertTrue(streamBytes.contentEquals(directBytes))
                }
            }
        }

        @Test
        fun `getPageImage loads valid BufferedImage with dimensions`() {
            val file = ArchiveTestUtils.createValidArchive(
                File(tempDir, "img.cbz"),
                pageCount = 2,
                width = 24,
                height = 36,
            )
            ArchiveReaderFactory.of(file).use { reader ->
                val firstPage = reader.pages.first()
                val image = reader.getPageImage(firstPage)
                assertNotNull(image)
                assertEquals(24, image.width)
                assertEquals(36, image.height)
            }
        }

        @Test
        fun `getPageImage returns null for corrupted image data without crashing`() {
            val corruptImgArchive = ArchiveTestUtils.createCorruptedArchive(
                File(tempDir, "bad_image.cbz"),
                ArchiveTestUtils.CorruptionType.CORRUPTED_IMAGE_ENTRY,
            )
            ArchiveReaderFactory.of(corruptImgArchive).use { reader ->
                val page = reader.pages.first()
                val image = reader.getPageImage(page)
                assertNull(image)
            }
        }
    }

    @Nested
    @DisplayName("Natural Alphanumeric Sorting in Readers")
    inner class SortingTests {

        @Test
        fun `ZipArchiveReader sorts pages in natural order`() {
            val dirtyArchive = ArchiveTestUtils.createDirtyArchive(File(tempDir, "unordered.cbz"))
            ArchiveReaderFactory.of(dirtyArchive).use { reader ->
                val pageNames = reader.pages.map { it.name }
                val expected = listOf("page_1.png", "page_2.png", "page_10.png", "page_20.png")
                assertEquals(expected, pageNames)
                assertEquals(listOf(0, 1, 2, 3), reader.pages.map { it.index })
            }
        }

        @Test
        fun `DirectoryArchiveReader sorts pages in natural order`() {
            val dirtyDir = ArchiveTestUtils.createDirtyDirectoryFixture(File(tempDir, "unordered_dir"))
            ArchiveReaderFactory.of(dirtyDir).use { reader ->
                val pageNames = reader.pages.map { it.name }
                val expected = listOf("page_1.png", "page_2.png", "page_10.png", "page_20.png")
                assertEquals(expected, pageNames)
                assertEquals(listOf(0, 1, 2, 3), reader.pages.map { it.index })
            }
        }

        @Test
        fun `DirectoryArchiveReader orders nested subdirectories naturally`() {
            val dir = File(tempDir, "nested_manga")
            val files = mapOf(
                "Chapter 10/01.png" to ArchiveTestUtils.createImageBytes(),
                "Chapter 2/01.png" to ArchiveTestUtils.createImageBytes(),
                "Chapter 1/01.png" to ArchiveTestUtils.createImageBytes(),
            )
            ArchiveTestUtils.createDirectoryFixture(dir, files)

            ArchiveReaderFactory.of(dir).use { reader ->
                val paths = reader.pages.map { it.path }
                val expected = listOf(
                    "Chapter 1/01.png",
                    "Chapter 2/01.png",
                    "Chapter 10/01.png",
                )
                assertEquals(expected, paths)
            }
        }
    }

    @Nested
    @DisplayName("Filtering macOS Artifacts and Non-Image Files")
    inner class FilteringTests {

        @Test
        fun `ZipArchiveReader excludes __MACOSX, hidden files, xml, and text`() {
            val dirtyArchive = ArchiveTestUtils.createDirtyArchive(File(tempDir, "filtered.cbz"))
            ArchiveReaderFactory.of(dirtyArchive).use { reader ->
                assertEquals(4, reader.pageCount)
                for (page in reader.pages) {
                    assertFalse(page.path.startsWith("__MACOSX"))
                    assertFalse(page.name.startsWith("."))
                    assertFalse(page.name.endsWith(".xml", ignoreCase = true))
                    assertFalse(page.name.endsWith(".txt", ignoreCase = true))
                    assertFalse(page.name.endsWith(".db", ignoreCase = true))
                }
            }
        }

        @Test
        fun `DirectoryArchiveReader excludes hidden files, xml, and text`() {
            val dirtyDir = ArchiveTestUtils.createDirtyDirectoryFixture(File(tempDir, "filtered_dir"))
            ArchiveReaderFactory.of(dirtyDir).use { reader ->
                assertEquals(4, reader.pageCount)
                for (page in reader.pages) {
                    assertFalse(page.name.startsWith("."))
                    assertFalse(page.name.endsWith(".xml", ignoreCase = true))
                    assertFalse(page.name.endsWith(".txt", ignoreCase = true))
                    assertFalse(page.name.endsWith(".db", ignoreCase = true))
                }
            }
        }
    }

    @Nested
    @DisplayName("Lifecycle, Security, and Concurrency Tests")
    inner class LifecycleSecurityAndConcurrencyTests {

        @Test
        fun `close releases ZipFile and reading after close throws ArchiveClosedException and IllegalStateException`() {
            val file = ArchiveTestUtils.createValidArchive(File(tempDir, "lifecycle.cbz"))
            val reader = ArchiveReaderFactory.of(file)
            val firstPage = reader.pages.first()

            val bytes = reader.getPageBytes(firstPage)
            assertTrue(bytes.isNotEmpty())

            reader.close()
            // Idempotent close
            reader.close()

            assertFailsWith<ArchiveClosedException> {
                reader.getPageStream(firstPage)
            }
            assertFailsWith<IllegalStateException> {
                reader.getPageStream(firstPage)
            }
            assertFailsWith<ArchiveClosedException> {
                reader.getPageBytes(firstPage)
            }
            assertFailsWith<ArchiveClosedException> {
                reader.getPageImage(firstPage)
            }
        }

        @Test
        fun `DirectoryArchiveReader close is idempotent and throws ArchiveClosedException after close`() {
            val dir = ArchiveTestUtils.createValidDirectoryFixture(File(tempDir, "dir_lifecycle"))
            val reader = ArchiveReaderFactory.of(dir)
            val firstPage = reader.pages.first()

            val bytes = reader.getPageBytes(firstPage)
            assertTrue(bytes.isNotEmpty())

            reader.close()
            reader.close()

            assertFailsWith<ArchiveClosedException> {
                reader.getPageStream(firstPage)
            }
            assertFailsWith<IllegalStateException> {
                reader.getPageStream(firstPage)
            }
            assertFailsWith<ArchiveClosedException> {
                reader.getPageBytes(firstPage)
            }
            assertFailsWith<ArchiveClosedException> {
                reader.getPageImage(firstPage)
            }
        }

        @Test
        fun `DirectoryArchiveReader throws SecurityException on path traversal attempt`() {
            val dir = ArchiveTestUtils.createValidDirectoryFixture(File(tempDir, "dir_security"))
            val reader = ArchiveReaderFactory.of(dir)
            reader.use {
                val maliciousPage = ArchivePage(
                    index = 999,
                    name = "secret.png",
                    path = "../../outside.png",
                )
                assertFailsWith<SecurityException> {
                    reader.getPageStream(maliciousPage)
                }
                assertFailsWith<SecurityException> {
                    reader.getPageBytes(maliciousPage)
                }
                assertFailsWith<SecurityException> {
                    reader.getPageImage(maliciousPage)
                }
            }
        }

        @Test
        fun `concurrent page streaming succeeds without data race in ZipArchiveReader`() {
            val file = ArchiveTestUtils.createValidArchive(File(tempDir, "concurrent.cbz"), pageCount = 5)
            ArchiveReaderFactory.of(file).use { reader ->
                val executor = Executors.newFixedThreadPool(8)
                val errors = mutableListOf<Throwable>()

                for (i in 0 until 50) {
                    val page = reader.pages[i % reader.pages.size]
                    executor.submit {
                        try {
                            val stream = reader.getPageStream(page)
                            val bytes = stream.use { it.readBytes() }
                            assertTrue(bytes.isNotEmpty())
                            val directBytes = reader.getPageBytes(page)
                            assertEquals(bytes.size, directBytes.size)
                        } catch (t: Throwable) {
                            synchronized(errors) { errors.add(t) }
                        }
                    }
                }

                executor.shutdown()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
                assertTrue(errors.isEmpty(), "Concurrent page reads produced errors: $errors")
            }
        }

        @Test
        fun `concurrent page streaming succeeds without data race in DirectoryArchiveReader`() {
            val dir = ArchiveTestUtils.createValidDirectoryFixture(File(tempDir, "concurrent_dir"), pageCount = 5)
            ArchiveReaderFactory.of(dir).use { reader ->
                val executor = Executors.newFixedThreadPool(8)
                val errors = mutableListOf<Throwable>()

                for (i in 0 until 50) {
                    val page = reader.pages[i % reader.pages.size]
                    executor.submit {
                        try {
                            val stream = reader.getPageStream(page)
                            val bytes = stream.use { it.readBytes() }
                            assertTrue(bytes.isNotEmpty())
                            val directBytes = reader.getPageBytes(page)
                            assertEquals(bytes.size, directBytes.size)
                        } catch (t: Throwable) {
                            synchronized(errors) { errors.add(t) }
                        }
                    }
                }

                executor.shutdown()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
                assertTrue(errors.isEmpty(), "Concurrent page reads produced errors: $errors")
            }
        }
    }
}
