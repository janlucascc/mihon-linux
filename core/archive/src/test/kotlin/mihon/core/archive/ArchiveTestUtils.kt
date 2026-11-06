package mihon.core.archive

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * Programmatic test fixture generator for local archive and directory unit tests.
 */
object ArchiveTestUtils {

    enum class CorruptionType {
        INVALID_HEADER,
        TRUNCATED_ZIP,
        ZERO_BYTES,
        CORRUPTED_IMAGE_ENTRY,
    }

    /**
     * Programmatically generates valid image bytes (PNG or JPEG) headlessly in memory.
     */
    fun createImageBytes(
        width: Int = 10,
        height: Int = 10,
        format: String = "png",
        color: Color = Color.RED,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = color
            g.fillRect(0, 0, width, height)
        } finally {
            g.dispose()
        }
        val baos = ByteArrayOutputStream()
        val success = ImageIO.write(image, format, baos)
        check(success) { "ImageIO failed to write format: $format" }
        return baos.toByteArray()
    }

    /**
     * Programmatically writes a ZIP file containing the specified map of entry names to byte payloads.
     */
    fun createZipArchive(
        targetFile: File,
        entries: Map<String, ByteArray>,
    ): File {
        targetFile.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(targetFile))).use { zos ->
            for ((name, data) in entries) {
                val entry = ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return targetFile
    }

    /**
     * Programmatically writes a CBZ file containing the specified map of entry names to byte payloads.
     */
    fun createCbzArchive(
        targetFile: File,
        entries: Map<String, ByteArray>,
    ): File = createZipArchive(targetFile, entries)

    /**
     * Creates a valid .cbz or .zip archive containing [pageCount] valid numbered image pages.
     */
    fun createValidArchive(
        targetFile: File,
        pageCount: Int = 3,
        format: String = "png",
        width: Int = 10,
        height: Int = 10,
    ): File {
        val entries = (1..pageCount).associate { i ->
            val pageName = "page_${i.toString().padStart(2, '0')}.$format"
            pageName to createImageBytes(width = width, height = height, format = format)
        }
        return createZipArchive(targetFile, entries)
    }

    /**
     * Programmatically creates a local directory containing specified relative file paths and byte payloads.
     */
    fun createDirectoryFixture(
        targetDir: File,
        files: Map<String, ByteArray>,
    ): File {
        targetDir.mkdirs()
        for ((relativePath, bytes) in files) {
            val file = File(targetDir, relativePath)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
        return targetDir
    }

    /**
     * Creates a valid directory fixture containing [pageCount] valid numbered image pages.
     */
    fun createValidDirectoryFixture(
        targetDir: File,
        pageCount: Int = 3,
        format: String = "png",
        width: Int = 10,
        height: Int = 10,
    ): File {
        val entries = (1..pageCount).associate { i ->
            val pageName = "page_${i.toString().padStart(2, '0')}.$format"
            pageName to createImageBytes(width = width, height = height, format = format)
        }
        return createDirectoryFixture(targetDir, entries)
    }

    /**
     * Creates a corrupted archive file based on [corruptionType].
     */
    fun createCorruptedArchive(
        targetFile: File,
        corruptionType: CorruptionType,
    ): File {
        targetFile.parentFile?.mkdirs()
        when (corruptionType) {
            CorruptionType.INVALID_HEADER -> {
                targetFile.writeText("THIS IS NOT A VALID ZIP ARCHIVE HEADER DATA")
            }
            CorruptionType.TRUNCATED_ZIP -> {
                val tempValid = File.createTempFile("temp-valid-archive", ".zip")
                try {
                    createValidArchive(tempValid, pageCount = 4)
                    val fullBytes = tempValid.readBytes()
                    // Truncate to first 40% of the byte array, omitting central directory
                    val truncated = fullBytes.copyOf(fullBytes.size / 2)
                    targetFile.writeBytes(truncated)
                } finally {
                    tempValid.delete()
                }
            }
            CorruptionType.ZERO_BYTES -> {
                targetFile.writeBytes(byteArrayOf())
            }
            CorruptionType.CORRUPTED_IMAGE_ENTRY -> {
                // A valid ZIP structure whose image entry has corrupt/garbage bytes
                val entries = mapOf(
                    "page_01.png" to byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05)
                )
                createZipArchive(targetFile, entries)
            }
        }
        return targetFile
    }

    /**
     * Creates an archive containing dirty files (macOS __MACOSX, .DS_Store, XML metadata, non-images)
     * and unordered page filenames (page_10.png, page_2.png, page_1.png, page_20.png).
     */
    fun createDirtyArchive(
        targetFile: File,
        format: String = "png",
    ): File {
        val validImg = createImageBytes(10, 10, format)
        val dummyBytes = byteArrayOf(0x10, 0x20, 0x30)
        val xmlContent = """<?xml version="1.0"?><ComicInfo><Title>Test Comic</Title></ComicInfo>""".toByteArray()
        val textContent = "Scanlation metadata notes".toByteArray()

        val entries = linkedMapOf(
            "page_10.png" to validImg,
            "__MACOSX/._page_10.png" to dummyBytes,
            "page_2.png" to validImg,
            ".DS_Store" to dummyBytes,
            "ComicInfo.xml" to xmlContent,
            "page_1.png" to validImg,
            "__MACOSX/._page_1.png" to dummyBytes,
            "notes.txt" to textContent,
            "page_20.png" to validImg,
            "Thumbs.db" to dummyBytes,
        )
        return createZipArchive(targetFile, entries)
    }

    /**
     * Creates a directory fixture containing dirty files and unordered page filenames.
     */
    fun createDirtyDirectoryFixture(
        targetDir: File,
        format: String = "png",
    ): File {
        val validImg = createImageBytes(10, 10, format)
        val dummyBytes = byteArrayOf(0x10, 0x20, 0x30)
        val xmlContent = """<?xml version="1.0"?><ComicInfo><Title>Test Comic</Title></ComicInfo>""".toByteArray()
        val textContent = "Scanlation metadata notes".toByteArray()

        val files = mapOf(
            "page_10.png" to validImg,
            ".DS_Store" to dummyBytes,
            "ComicInfo.xml" to xmlContent,
            "page_2.png" to validImg,
            "page_1.png" to validImg,
            "notes.txt" to textContent,
            "page_20.png" to validImg,
            "Thumbs.db" to dummyBytes,
        )
        return createDirectoryFixture(targetDir, files)
    }
}
