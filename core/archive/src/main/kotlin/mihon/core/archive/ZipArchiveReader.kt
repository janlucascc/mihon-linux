package mihon.core.archive

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Stream-based reader for `.cbz` and `.zip` manga archives.
 * Decompresses entries on-demand without writing temporary files to disk.
 */
class ZipArchiveReader(
    private val file: File,
) : ArchiveReader {

    private val zipFile: ZipFile
    private val isClosed = AtomicBoolean(false)

    override val sourceName: String = file.name
    override val pages: List<ArchivePage>
    override val pageCount: Int get() = pages.size

    init {
        require(file.exists()) { "Archive file does not exist: ${file.absolutePath}" }
        require(file.isFile) { "Archive path is not a file: ${file.absolutePath}" }

        val openedZip = try {
            ZipFile(file)
        } catch (e: ZipException) {
            throw CorruptedArchiveException("Corrupted or invalid zip archive: ${file.absolutePath} (${e.message})", e)
        } catch (e: IOException) {
            throw ArchiveException("Failed to read archive file: ${file.absolutePath} (${e.message})", e)
        }

        try {
            val validEntries = openedZip.entries().asSequence()
                .filter { !it.isDirectory && !it.name.endsWith("/") }
                .filter { ArchiveFilter.isValidImageEntry(it.name) }
                .sortedWith { e1, e2 -> NaturalOrderComparator.compare(e1.name, e2.name) }
                .toList()

            pages = validEntries.mapIndexed { index, entry ->
                ArchivePage(
                    index = index,
                    name = entry.name.substringAfterLast('/'),
                    path = entry.name,
                    size = entry.size,
                )
            }
            zipFile = openedZip
        } catch (t: Throwable) {
            try {
                openedZip.close()
            } catch (_: Throwable) {
                // Ignore secondary close error to preserve original cause
            }
            throw t
        }
    }

    private fun ensureOpen() {
        if (isClosed.get()) {
            throw ArchiveClosedException("ZipArchiveReader is closed: $sourceName")
        }
    }

    @Throws(IOException::class)
    override fun getPageStream(page: ArchivePage): InputStream {
        ensureOpen()
        val entry = zipFile.getEntry(page.path)
            ?: throw NoSuchElementException("Page entry not found in archive: ${page.path}")
        return zipFile.getInputStream(entry)
            ?: throw IOException("Failed to obtain input stream for entry: ${page.path}")
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            zipFile.close()
        }
    }
}
