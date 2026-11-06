package mihon.core.archive

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchiveChallengerStressTest {

    @TempDir
    lateinit var tempDir: File

    @Nested
    @DisplayName("NaturalOrderComparator Stress & Property Tests")
    inner class NaturalOrderComparatorPropertyTests {

        @Test
        fun `test extreme number lengths with 100+ and 500+ digits`() {
            // Extreme length numbers: 100 digits vs 100 digits
            val base100A = "page_" + "9".repeat(100) + "_suffix.jpg"
            val base100B = "page_" + "9".repeat(99) + "8_suffix.jpg"
            val base101 = "page_" + "1" + "0".repeat(100) + "_suffix.jpg"
            val base500A = "5" + "0".repeat(500)
            val base500B = "5" + "0".repeat(499) + "1"

            assertTrue(NaturalOrderComparator.compare(base100B, base100A) < 0, "99..8 must sort before 99..9")
            assertTrue(NaturalOrderComparator.compare(base100A, base100B) > 0, "99..9 must sort after 99..8")
            assertTrue(NaturalOrderComparator.compare(base100A, base101) < 0, "100 digits must sort before 101 digits")
            assertTrue(NaturalOrderComparator.compare(base101, base100A) > 0, "101 digits must sort after 100 digits")

            assertTrue(NaturalOrderComparator.compare(base500A, base500B) < 0, "500-digit number comparison")
            assertTrue(NaturalOrderComparator.compare(base500B, base500A) > 0, "500-digit number comparison reverse")

            // Extreme leading zeros
            val zero100 = "0".repeat(100)
            val zero101 = "0".repeat(101)
            val zero100With1 = "0".repeat(100) + "1"
            val zero99With2 = "0".repeat(99) + "2"

            // 100 zeros < 101 zeros (both value 0, but 100 zeros has fewer leading zeros)
            assertTrue(NaturalOrderComparator.compare(zero100, zero101) < 0)
            // 100 zeros < 100 zeros with 1 (0 < 1)
            assertTrue(NaturalOrderComparator.compare(zero100, zero100With1) < 0)
            // Value 1 < Value 2 regardless of zero counts
            assertTrue(NaturalOrderComparator.compare(zero100With1, zero99With2) < 0)
        }

        @Test
        fun `test reflexivity, anti-symmetry, and transitivity on filename permutations`() {
            val filenames = listOf(
                "01",
                "1",
                "001",
                "page_1",
                "page_01",
                "page_2",
                "page_10",
                "page_100",
                "PAGE_1",
                "page_001",
                "page_02",
                "page_20",
                "page_1000",
            )

            // 1. Reflexivity: compare(x, x) == 0
            for (x in filenames) {
                assertEquals(0, NaturalOrderComparator.compare(x, x), "Reflexivity failed for $x")
            }

            // 2. Anti-symmetry: sgn(cmp(x, y)) == -sgn(cmp(y, x))
            for (i in filenames.indices) {
                for (j in filenames.indices) {
                    val x = filenames[i]
                    val y = filenames[j]
                    val c1 = NaturalOrderComparator.compare(x, y)
                    val c2 = NaturalOrderComparator.compare(y, x)
                    val sgn1 = c1.compareTo(0)
                    val sgn2 = c2.compareTo(0)
                    assertEquals(sgn1, -sgn2, "Anti-symmetry failed for ($x, $y): c1=$c1, c2=$c2")
                }
            }

            // 3. Transitivity: if cmp(x, y) <= 0 and cmp(y, z) <= 0, then cmp(x, z) <= 0
            for (x in filenames) {
                for (y in filenames) {
                    for (z in filenames) {
                        val cXY = NaturalOrderComparator.compare(x, y)
                        val cYZ = NaturalOrderComparator.compare(y, z)
                        val cXZ = NaturalOrderComparator.compare(x, z)

                        if (cXY <= 0 && cYZ <= 0) {
                            assertTrue(
                                cXZ <= 0,
                                "Transitivity failed: cmp($x, $y)=$cXY, cmp($y, $z)=$cYZ, but cmp($x, $z)=$cXZ",
                            )
                        }
                    }
                }
            }
        }

        @Test
        fun `test TimSort contract across 2000 random alphanumeric filenames`() {
            val random = java.util.Random(42)
            val prefixes = listOf("page", "PAGE", "ch", "chapter", "img", "00", "")
            val separators = listOf("_", "-", ".", " ", "")
            val numbers = (0..200).map { it.toString() } + listOf("01", "001", "0001", "99999999999999999999")
            val extensions = listOf(".png", ".jpg", ".PNG", ".jpeg", "")

            val items = (1..2000).map {
                val p = prefixes[random.nextInt(prefixes.size)]
                val s = separators[random.nextInt(separators.size)]
                val n = numbers[random.nextInt(numbers.size)]
                val ext = extensions[random.nextInt(extensions.size)]
                "$p$s$n$ext"
            }

            // TimSort will throw IllegalArgumentException: Comparison method violates its general contract!
            // if the comparator violates transitivity, symmetry, or reflexivity
            val sorted = items.sortedWith(NaturalOrderComparator)
            assertEquals(items.size, sorted.size)

            // Verify monotonicity
            for (i in 0 until sorted.size - 1) {
                val c = NaturalOrderComparator.compare(sorted[i], sorted[i + 1])
                assertTrue(c <= 0, "Monotonicity violated between ${sorted[i]} and ${sorted[i + 1]}")
            }
        }
    }

    @Nested
    @DisplayName("Malicious and Boundary Archive Tests")
    inner class MaliciousArchiveTests {

        @Test
        fun `malformed archives - zero-length file`() {
            val zeroFile = File(tempDir, "zero.cbz").apply { writeBytes(byteArrayOf()) }
            assertFailsWith<CorruptedArchiveException> {
                ArchiveReaderFactory.of(zeroFile)
            }
        }

        @Test
        fun `malformed archives - random garbage bytes of various lengths`() {
            val rng = SecureRandom()
            val sizes = listOf(1, 15, 64, 512, 4096, 65536)

            for (size in sizes) {
                val garbage = ByteArray(size).also { rng.nextBytes(it) }
                val garbageFile = File(tempDir, "garbage_$size.zip").apply { writeBytes(garbage) }

                assertFailsWith<CorruptedArchiveException> {
                    ArchiveReaderFactory.of(garbageFile)
                }
            }
        }

        @Test
        fun `malformed archives - truncated central directory at multiple cutoff points`() {
            val validTemp = File(tempDir, "template.zip")
            ArchiveTestUtils.createValidArchive(validTemp, pageCount = 10)
            val fullBytes = validTemp.readBytes()

            val cutoffs = listOf(0.1, 0.25, 0.5, 0.75, 0.9, 0.98)
            for (cutoff in cutoffs) {
                val truncatedBytes = fullBytes.copyOf((fullBytes.size * cutoff).toInt())
                val truncFile = File(tempDir, "truncated_${(cutoff * 100).toInt()}.zip").apply {
                    writeBytes(truncatedBytes)
                }
                assertFailsWith<CorruptedArchiveException> {
                    ArchiveReaderFactory.of(truncFile)
                }
            }
        }

        @Test
        fun `empty zip archive with valid headers but zero entries returns zero pages without error`() {
            val emptyZip = File(tempDir, "empty_valid.cbz")
            ZipOutputStream(FileOutputStream(emptyZip)).use { /* no entries */ }

            ArchiveReaderFactory.of(emptyZip).use { reader ->
                assertEquals(0, reader.pageCount)
                assertTrue(reader.pages.isEmpty())
            }
        }

        @Test
        fun `zip archive with only non-image entries returns zero pages`() {
            val nonImageZip = File(tempDir, "non_images.zip")
            val entries = mapOf(
                "notes.txt" to "Manga notes".toByteArray(),
                "info.nfo" to "Release NFO".toByteArray(),
                "metadata.xml" to "<xml></xml>".toByteArray(),
                "database.db" to byteArrayOf(0, 1, 2),
                "script.sh" to "#!/bin/sh\necho hi".toByteArray(),
            )
            ArchiveTestUtils.createZipArchive(nonImageZip, entries)

            ArchiveReaderFactory.of(nonImageZip).use { reader ->
                assertEquals(0, reader.pageCount)
                assertTrue(reader.pages.isEmpty())
            }
        }

        @Test
        fun `directory traversal attempts inside zip archives are filtered out`() {
            val maliciousZip = File(tempDir, "malicious_traversal.cbz")
            val dummyImg = ArchiveTestUtils.createImageBytes()

            val entries = mapOf(
                "../../etc/passwd" to "root:x:0:0:...".toByteArray(),
                "..\\..\\windows\\win.ini" to "[fonts]".toByteArray(),
                "/etc/shadow.png" to dummyImg,
                "subdir/../../../secret.png" to dummyImg,
                "normal_page.png" to dummyImg,
            )
            ArchiveTestUtils.createZipArchive(maliciousZip, entries)

            ArchiveReaderFactory.of(maliciousZip).use { reader ->
                assertEquals(1, reader.pageCount, "Only valid non-traversal images should be included")
                assertEquals("normal_page.png", reader.pages[0].name)
                assertEquals("normal_page.png", reader.pages[0].path)
            }
        }

        @Test
        fun `DirectoryArchiveReader directory traversal attempt throws SecurityException`() {
            val dir = ArchiveTestUtils.createValidDirectoryFixture(File(tempDir, "reader_dir"))
            ArchiveReaderFactory.of(dir).use { reader ->
                val traversalPages = listOf(
                    ArchivePage(0, "passwd", "../../etc/passwd"),
                    ArchivePage(1, "outside.png", "../outside.png"),
                    ArchivePage(2, "escape.png", "../../../escape.png"),
                )

                for (page in traversalPages) {
                    assertFailsWith<SecurityException> {
                        reader.getPageStream(page)
                    }
                    assertFailsWith<SecurityException> {
                        reader.getPageBytes(page)
                    }
                    assertFailsWith<SecurityException> {
                        reader.getPageImage(page)
                    }
                }
            }
        }

        @Test
        fun `DirectoryArchiveReader sibling directory prefix traversal throws SecurityException`() {
            // Target directory is "manga"
            val targetDir = File(tempDir, "manga")
            ArchiveTestUtils.createValidDirectoryFixture(targetDir, pageCount = 2)

            // Sibling directory "manga_secret" shares the prefix "manga"
            val siblingDir = File(tempDir, "manga_secret")
            ArchiveTestUtils.createValidDirectoryFixture(siblingDir, pageCount = 1)
            val secretFile = File(siblingDir, "page_01.png")
            assertTrue(secretFile.exists())

            ArchiveReaderFactory.of(targetDir).use { reader ->
                val attackPage = ArchivePage(
                    index = 999,
                    name = "secret.png",
                    path = "../manga_secret/page_01.png",
                )

                assertFailsWith<SecurityException>("Expected SecurityException for sibling directory traversal in getPageBytes") {
                    reader.getPageBytes(attackPage)
                }
                assertFailsWith<SecurityException>("Expected SecurityException for sibling directory traversal in getPageStream") {
                    reader.getPageStream(attackPage)
                }
                assertFailsWith<SecurityException>("Expected SecurityException for sibling directory traversal in getPageImage") {
                    reader.getPageImage(attackPage)
                }
            }
        }
    }

    @Nested
    @DisplayName("Dirty Archives and Unicode Testing")
    inner class DirtyAndUnicodeArchiveTests {

        @Test
        fun `deeply nested subdirectories up to 5 levels are discovered and naturally ordered`() {
            val deepDir = File(tempDir, "deep_manga")
            val img = ArchiveTestUtils.createImageBytes()
            val files = mapOf(
                "Vol 1/Arc 1/Season 1/Ch 10/01.png" to img,
                "Vol 1/Arc 1/Season 1/Ch 2/01.png" to img,
                "Vol 1/Arc 1/Season 1/Ch 1/01.png" to img,
                "Vol 2/Arc 1/Season 1/Ch 1/01.png" to img,
            )
            ArchiveTestUtils.createDirectoryFixture(deepDir, files)

            ArchiveReaderFactory.of(deepDir).use { reader ->
                assertEquals(4, reader.pageCount)
                val paths = reader.pages.map { it.path }
                val expected = listOf(
                    "Vol 1/Arc 1/Season 1/Ch 1/01.png",
                    "Vol 1/Arc 1/Season 1/Ch 2/01.png",
                    "Vol 1/Arc 1/Season 1/Ch 10/01.png",
                    "Vol 2/Arc 1/Season 1/Ch 1/01.png",
                )
                assertEquals(expected, paths)
            }
        }

        @Test
        fun `dirty archive with weird AppleDouble files, hidden files, and desktop metadata`() {
            val dirtyZip = File(tempDir, "ultra_dirty.cbz")
            val img = ArchiveTestUtils.createImageBytes()
            val dummy = byteArrayOf(0x01, 0x02)

            val entries = mapOf(
                "__MACOSX/._page_01.png" to dummy,
                "subfolder/__MACOSX/._page_02.png" to dummy,
                "subfolder/._page_02.png" to dummy,
                "._page_00.png" to dummy,
                ".DS_Store" to dummy,
                "subfolder/.DS_Store" to dummy,
                "Thumbs.db" to dummy,
                "desktop.ini" to dummy,
                "ehthumbs.db" to dummy,
                "subfolder/Thumbs.db" to dummy,
                "ComicInfo.xml" to dummy,
                "page_01.png" to img,
                "page_02.png" to img,
                "subfolder/page_03.png" to img,
            )
            ArchiveTestUtils.createZipArchive(dirtyZip, entries)

            ArchiveReaderFactory.of(dirtyZip).use { reader ->
                assertEquals(3, reader.pageCount)
                val paths = reader.pages.map { it.path }
                val expected = listOf("page_01.png", "page_02.png", "subfolder/page_03.png")
                assertEquals(expected, paths)
            }
        }

        @Test
        fun `Unicode filenames - Japanese, emojis, accents, fullwidth digits`() {
            val unicodeDir = File(tempDir, "unicode_manga")
            val img = ArchiveTestUtils.createImageBytes()
            val files = mapOf(
                "第10話.png" to img,
                "第2話.png" to img,
                "第1話.png" to img,
                "第01話.png" to img,
                "chapître_2.png" to img,
                "chapître_10.png" to img,
                "chapître_1.png" to img,
                "📖_2.png" to img,
                "📖_1.png" to img,
                "📖_10.png" to img,
                "１０.png" to img,
                "２.png" to img,
                "１.png" to img,
            )
            ArchiveTestUtils.createDirectoryFixture(unicodeDir, files)

            ArchiveReaderFactory.of(unicodeDir).use { reader ->
                assertEquals(13, reader.pageCount)
                val names = reader.pages.map { it.name }

                // Check Japanese natural ordering
                val japanese = names.filter { it.startsWith("第") }
                assertEquals(listOf("第1話.png", "第01話.png", "第2話.png", "第10話.png"), japanese)

                // Check French accent natural ordering
                val french = names.filter { it.startsWith("chapître") }
                assertEquals(listOf("chapître_1.png", "chapître_2.png", "chapître_10.png"), french)

                // Check Emoji natural ordering
                val emoji = names.filter { it.startsWith("📖") }
                assertEquals(listOf("📖_1.png", "📖_2.png", "📖_10.png"), emoji)

                // Check fullwidth digit ordering
                val fullwidth = names.filter { it in listOf("１.png", "２.png", "１０.png") }
                assertEquals(listOf("１.png", "２.png", "１０.png"), fullwidth)
            }
        }
    }

    @Nested
    @DisplayName("DirectoryArchiveReader Fuzzing and Path Security")
    inner class DirectoryArchiveReaderFuzzTests {

        @Test
        fun `fuzz double slashes and complex slash sequences for traversal attempts`() {
            val rootDir = File(tempDir, "fuzz_slash_root").apply { mkdirs() }
            val subDir = File(rootDir, "sub").apply { mkdirs() }
            val validImg = ArchiveTestUtils.createImageBytes(width = 10, height = 10)
            File(rootDir, "root_page.png").writeBytes(validImg)
            File(subDir, "sub_page.png").writeBytes(validImg)

            val secretDir = File(tempDir, "secret_slashes").apply { mkdirs() }
            val secretFile = File(secretDir, "secret.png")
            secretFile.writeBytes(validImg)

            DirectoryArchiveReader(rootDir).use { reader ->
                // Legitimate intra-directory paths with redundant slashes should resolve safely
                val doubleSlashIntra = ArchivePage(0, "sub_page.png", "sub//sub_page.png")
                val tripleSlashIntra = ArchivePage(1, "sub_page.png", "sub///sub_page.png")
                val dotSlashIntra = ArchivePage(2, "root_page.png", "./root_page.png")

                assertEquals(validImg.size, reader.getPageBytes(doubleSlashIntra).size)
                assertEquals(validImg.size, reader.getPageBytes(tripleSlashIntra).size)
                assertEquals(validImg.size, reader.getPageBytes(dotSlashIntra).size)

                // Malicious traversal attempts with redundant slashes must throw SecurityException
                val maliciousSlashPaths = listOf(
                    "//etc/passwd",
                    "///etc/shadow",
                    "////secret.png",
                    "sub//..//..//secret_slashes/secret.png",
                    "..//..//secret_slashes/secret.png",
                    "..///secret_slashes/secret.png",
                    "sub/..//..//secret.png",
                    "\\\\server\\share\\secret.png",
                )

                for (path in maliciousSlashPaths) {
                    val attackPage = ArchivePage(999, "attack.png", path)
                    assertFailsWith<SecurityException>("Expected SecurityException for path: $path") {
                        reader.getPageBytes(attackPage)
                    }
                }

                // On POSIX systems, backslash is not a directory separator; verify it cannot escape
                val posixBackslashPage = ArchivePage(999, "attack.png", "sub\\\\..\\\\..\\\\secret.png")
                val isSafePosix = try {
                    reader.getPageBytes(posixBackslashPage)
                    false
                } catch (e: SecurityException) {
                    true
                } catch (e: NoSuchElementException) {
                    true
                }
                assertTrue(isSafePosix, "POSIX backslash traversal must not access outside files")
            }
        }

        @Test
        fun `fuzz encoded path separators and url-like sequences`() {
            val rootDir = File(tempDir, "fuzz_encoded_root").apply { mkdirs() }
            val validImg = ArchiveTestUtils.createImageBytes(width = 10, height = 10)
            File(rootDir, "01.png").writeBytes(validImg)

            val secretDir = File(tempDir, "fuzz_encoded_secret").apply { mkdirs() }
            File(secretDir, "leaked.png").writeBytes(validImg)

            DirectoryArchiveReader(rootDir).use { reader ->
                val encodedAttacks = listOf(
                    "..%2f..%2ffuzz_encoded_secret/leaked.png",
                    "%2e%2e%2ffuzz_encoded_secret/leaked.png",
                    "%2e%2e/%2e%2e/fuzz_encoded_secret/leaked.png",
                    "..%5c..%5cfuzz_encoded_secret/leaked.png",
                    "%2e%2e%5c",
                    "..%252f..%252ffuzz_encoded_secret/leaked.png",
                    "sub/%2e%2e/%2e%2e/leaked.png",
                )

                for (encoded in encodedAttacks) {
                    val page = ArchivePage(999, "encoded.png", encoded)
                    val isSafe = try {
                        reader.getPageBytes(page)
                        false
                    } catch (e: SecurityException) {
                        true
                    } catch (e: NoSuchElementException) {
                        true
                    }
                    assertTrue(isSafe, "Encoded traversal '$encoded' must not access outside files")
                }
            }
        }

        @Test
        fun `fuzz trailing dots and navigation dot segments`() {
            val rootDir = File(tempDir, "fuzz_dots_root").apply { mkdirs() }
            val subDir = File(rootDir, "sub").apply { mkdirs() }
            val validImg = ArchiveTestUtils.createImageBytes(width = 10, height = 10)
            File(rootDir, "01.png").writeBytes(validImg)
            File(subDir, "02.png").writeBytes(validImg)

            DirectoryArchiveReader(rootDir).use { reader ->
                // Exact directory or parent escapes must throw SecurityException
                val securityDotEscapes = listOf(
                    ".",
                    "..",
                    "sub/..",
                    "sub/../..",
                    "sub/../../..",
                )
                for (dotPath in securityDotEscapes) {
                    val page = ArchivePage(999, "dots", dotPath)
                    assertFailsWith<SecurityException>("Expected SecurityException for: '$dotPath'") {
                        reader.getPageBytes(page)
                    }
                }

                // Blank paths are rejected by ArchivePage constructor with IllegalArgumentException
                val blankPaths = listOf("   ", "\t", "\n", "")
                for (blank in blankPaths) {
                    assertFailsWith<IllegalArgumentException>("ArchivePage constructor must reject blank path") {
                        ArchivePage(999, "blank", blank)
                    }
                }

                // Subdirectory reference pointing to directory rather than file must throw NoSuchElementException
                val dirRefPage = ArchivePage(999, "sub", "sub/.")
                assertFailsWith<NoSuchElementException> {
                    reader.getPageBytes(dirRefPage)
                }

                // Trailing dot files that do not exist must throw NoSuchElementException safely
                val nonExistentTrailingDots = listOf(
                    "01.png.",
                    "01.png..",
                    "01.png...",
                    "sub/02.png.",
                )
                for (trailingDotPath in nonExistentTrailingDots) {
                    val page = ArchivePage(999, "dotfile", trailingDotPath)
                    assertFailsWith<NoSuchElementException> {
                        reader.getPageBytes(page)
                    }
                }
            }
        }

        @Test
        fun `fuzz intra-directory and external symlinks`() {
            val rootDir = File(tempDir, "fuzz_symlink_root").apply { mkdirs() }
            val subDir = File(rootDir, "sub").apply { mkdirs() }
            val validImg = ArchiveTestUtils.createImageBytes(width = 10, height = 10)
            val imgFile = File(rootDir, "real_page.png").apply { writeBytes(validImg) }
            val subImgFile = File(subDir, "sub_real.png").apply { writeBytes(validImg) }

            val externalSecretDir = File(tempDir, "external_secret_dir").apply { mkdirs() }
            val secretFile = File(externalSecretDir, "secret_outside.png").apply { writeBytes(validImg) }

            // 1. Intra-directory file symlink (symlink within root pointing to file within root)
            val intraLink = rootDir.toPath().resolve("intra_link.png")
            Files.createSymbolicLink(intraLink, imgFile.toPath())

            // 2. Intra-directory subdirectory symlink pointing to parent file inside root
            val intraSubLink = subDir.toPath().resolve("intra_sub_link.png")
            Files.createSymbolicLink(intraSubLink, Paths.get("../real_page.png"))

            // 3. Intra-directory folder symlink (symlink to sub directory inside root)
            val intraDirLink = rootDir.toPath().resolve("sub_alias")
            Files.createSymbolicLink(intraDirLink, Paths.get("sub"))

            // 4. External file symlink (symlink inside root pointing to file OUTSIDE root)
            val evilFileLink = rootDir.toPath().resolve("evil_file_link.png")
            Files.createSymbolicLink(evilFileLink, secretFile.toPath())

            // 5. External directory symlink (symlink inside root pointing to directory OUTSIDE root)
            val evilDirLink = rootDir.toPath().resolve("evil_dir_link")
            Files.createSymbolicLink(evilDirLink, externalSecretDir.toPath())

            // 6. Broken symlink
            val brokenLink = rootDir.toPath().resolve("broken_link.png")
            Files.createSymbolicLink(brokenLink, Paths.get("non_existent_target.png"))

            DirectoryArchiveReader(rootDir).use { reader ->
                // Legitimate intra-directory symlinks must succeed
                val intraPage = ArchivePage(0, "intra_link.png", "intra_link.png")
                val intraBytes = reader.getPageBytes(intraPage)
                assertEquals(validImg.size, intraBytes.size, "Intra-directory file symlink must be readable")

                val intraSubPage = ArchivePage(1, "intra_sub_link.png", "sub/intra_sub_link.png")
                val intraSubBytes = reader.getPageBytes(intraSubPage)
                assertEquals(validImg.size, intraSubBytes.size, "Intra-directory relative symlink must be readable")

                val intraDirAliasPage = ArchivePage(2, "sub_real.png", "sub_alias/sub_real.png")
                val intraDirBytes = reader.getPageBytes(intraDirAliasPage)
                assertEquals(validImg.size, intraDirBytes.size, "Intra-directory folder symlink alias must be readable")

                // External symlinks escaping root directory MUST throw SecurityException
                val evilFilePage = ArchivePage(99, "evil_file_link.png", "evil_file_link.png")
                assertFailsWith<SecurityException>("External file symlink must throw SecurityException") {
                    reader.getPageBytes(evilFilePage)
                }

                val evilDirPage = ArchivePage(100, "secret_outside.png", "evil_dir_link/secret_outside.png")
                assertFailsWith<SecurityException>("External directory symlink must throw SecurityException") {
                    reader.getPageBytes(evilDirPage)
                }

                // Broken symlink must throw NoSuchElementException
                val brokenPage = ArchivePage(101, "broken_link.png", "broken_link.png")
                assertFailsWith<NoSuchElementException>("Broken symlink must throw NoSuchElementException") {
                    reader.getPageBytes(brokenPage)
                }
            }
        }

        @Test
        fun `fuzz Windows drive letters and UNC partition paths`() {
            val rootDir = File(tempDir, "fuzz_windows_root").apply { mkdirs() }
            DirectoryArchiveReader(rootDir).use { reader ->
                val winPaths = listOf(
                    "C:/Windows/System32/drivers/etc/hosts",
                    "C:\\Windows\\System32\\calc.exe",
                    "D:/data/secrets.txt",
                    "c:relative_drive_path.png",
                    "Z:\\share\\image.png",
                    "\\\\localhost\\c$\\test.png",
                )

                for (winPath in winPaths) {
                    val page = ArchivePage(999, "win", winPath)
                    assertFailsWith<SecurityException>("Expected SecurityException for Windows path: '$winPath'") {
                        reader.getPageBytes(page)
                    }
                }
            }
        }

        @Test
        fun `fuzz null bytes and control characters`() {
            val rootDir = File(tempDir, "fuzz_null_root").apply { mkdirs() }
            val validImg = ArchiveTestUtils.createImageBytes(width = 10, height = 10)
            File(rootDir, "valid.png").writeBytes(validImg)

            DirectoryArchiveReader(rootDir).use { reader ->
                val nullPaths = listOf(
                    "valid.png\u0000.evil",
                    "\u0000valid.png",
                    "sub/\u0000/valid.png",
                )

                for (np in nullPaths) {
                    val page = ArchivePage(999, "nullbyte", np)
                    val threw = try {
                        reader.getPageBytes(page)
                        false
                    } catch (e: Exception) {
                        // Null bytes in file paths throw IllegalArgumentException or NoSuchElementException or SecurityException
                        true
                    }
                    assertTrue(threw, "Null byte path '$np' must not succeed")
                }
            }
        }

        @Test
        fun `cyclic symlinks fail gracefully without infinite loop`() {
            val rootDir = File(tempDir, "fuzz_cyclic_root").apply { mkdirs() }
            val linkA = rootDir.toPath().resolve("link_a.png")
            val linkB = rootDir.toPath().resolve("link_b.png")
            Files.createSymbolicLink(linkA, Paths.get("link_b.png"))
            Files.createSymbolicLink(linkB, Paths.get("link_a.png"))

            DirectoryArchiveReader(rootDir).use { reader ->
                val cyclicPage = ArchivePage(999, "cyclic", "link_a.png")
                val threwExpected = try {
                    reader.getPageBytes(cyclicPage)
                    false
                } catch (e: Exception) {
                    // java.io.IOException ("Too many levels of symbolic links") or NoSuchElementException or SecurityException
                    true
                }
                assertTrue(threwExpected, "Cyclic symlink must throw an exception and not hang")
            }
        }
    }
}
