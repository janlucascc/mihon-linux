package mihon.core.archive

import java.awt.image.BufferedImage
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/**
 * Reader for reading image pages directly from a local filesystem directory.
 *
 * Implements [ArchiveReader] polymorphically alongside [ZipArchiveReader].
 * Supports flat directories containing image files as well as nested subfolders.
 * Defers filesystem walking until [pages] or [pageCount] is accessed (lazy discovery).
 * Enforces canonical path verification to prevent path traversal attacks.
 *
 * @param directory The root folder to read images from.
 * @param includeSubfolders Whether to recursively scan subdirectories for images (defaults to true).
 */
class DirectoryArchiveReader(
    private val directory: File,
    private val includeSubfolders: Boolean = true,
) : ArchiveReader {

    private val isClosed = AtomicBoolean(false)

    init {
        require(directory.exists()) { "Directory does not exist: ${directory.absolutePath}" }
        require(directory.isDirectory) { "Path is not a directory: ${directory.absolutePath}" }
    }

    override val sourceName: String
        get() = directory.name

    override val pages: List<ArchivePage> by lazy {
        discoverPages()
    }

    override val pageCount: Int
        get() = pages.size

    private fun ensureOpen() {
        if (isClosed.get()) {
            throw ArchiveClosedException("DirectoryArchiveReader for '${directory.name}' is closed")
        }
    }

    /**
     * Resolves the page file and ensures path traversal security.
     */
    private fun getPageFile(page: ArchivePage): File {
        // Reject blank or absolute paths upfront (Unix '/', Windows '\', Windows drive letters 'C:/' or 'C:\', etc.)
        if (page.path.isBlank() ||
            page.path.startsWith("/") ||
            page.path.startsWith("\\") ||
            File(page.path).isAbsolute ||
            (page.path.length >= 2 && page.path[1] == ':' && page.path[0].isLetter())
        ) {
            throw SecurityException("Path traversal attempt detected: ${page.path}")
        }

        val basePath: Path = directory.canonicalFile.toPath().toAbsolutePath().normalize()
        val candidate = File(directory, page.path).canonicalFile
        val candidatePath: Path = candidate.toPath().toAbsolutePath().normalize()

        if (candidatePath == basePath || !candidatePath.startsWith(basePath)) {
            throw SecurityException("Path traversal attempt detected: ${page.path}")
        }
        if (!candidate.exists() || !candidate.isFile) {
            throw NoSuchElementException("Page file not found for index ${page.index}: ${candidate.absolutePath}")
        }
        return candidate
    }

    @Throws(IOException::class)
    override fun getPageStream(page: ArchivePage): InputStream {
        ensureOpen()
        val file = getPageFile(page)
        return FileInputStream(file)
    }

    @Throws(IOException::class)
    override fun getPageBytes(page: ArchivePage): ByteArray {
        ensureOpen()
        val file = getPageFile(page)
        return file.readBytes()
    }

    @Throws(IOException::class)
    override fun getPageImage(page: ArchivePage): BufferedImage? {
        ensureOpen()
        val file = getPageFile(page)
        return ImageIO.read(file)
    }

    override fun close() {
        isClosed.set(true)
    }

    /**
     * Scans the directory for image files, sanitizes entries using [ArchiveFilter],
     * and sorts entries naturally by relative path using [NaturalOrderComparator].
     */
    private fun discoverPages(): List<ArchivePage> {
        if (!directory.exists() || !directory.isDirectory) {
            return emptyList()
        }

        val candidateFiles = if (includeSubfolders) {
            directory.walkTopDown()
                .onEnter { dir ->
                    !dir.name.startsWith(".") &&
                        !ArchiveFilter.isMacOsArtifact(dir.name) &&
                        dir.name.lowercase() != "__macosx"
                }
                .filter { file ->
                    file.isFile && !file.isHidden &&
                        ArchiveFilter.isValidImageEntry(file.toRelativeString(directory))
                }
                .toList()
        } else {
            directory.listFiles()
                ?.filter { file ->
                    file.isFile && !file.isHidden &&
                        ArchiveFilter.isValidImageEntry(file.name)
                }
                ?: emptyList()
        }

        // Sort naturally by normalized relative path using forward slashes
        val sortedFiles = candidateFiles.sortedWith { f1, f2 ->
            val rel1 = f1.toRelativeString(directory).replace('\\', '/')
            val rel2 = f2.toRelativeString(directory).replace('\\', '/')
            NaturalOrderComparator.compare(rel1, rel2)
        }

        return sortedFiles.mapIndexed { index, file ->
            val relPath = file.toRelativeString(directory).replace('\\', '/')
            ArchivePage(
                index = index,
                name = file.name,
                path = relPath,
                size = file.length(),
            )
        }
    }
}
