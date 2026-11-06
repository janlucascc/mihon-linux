package mihon.core.archive

import java.io.File

/**
 * Factory for creating [ArchiveReader] instances based on the source file or directory.
 */
object ArchiveReaderFactory {

    val SUPPORTED_EXTENSIONS: Set<String> = setOf("cbz", "zip")

    /**
     * Checks if the given file or directory is supported by the archive reader.
     */
    fun isSupported(file: File): Boolean {
        if (!file.exists()) return false
        return file.isDirectory || (file.isFile && file.extension.lowercase() in SUPPORTED_EXTENSIONS)
    }

    /**
     * Instantiates an [ArchiveReader] suitable for the given [file].
     *
     * @param file A valid .cbz file, .zip file, or image directory.
     * @return [ZipArchiveReader] for CBZ/ZIP files, or [DirectoryArchiveReader] for directories.
     * @throws IllegalArgumentException if the file does not exist, is not a regular file or directory,
     *         or has an unsupported file extension.
     * @throws ArchiveException if the archive is corrupted or cannot be read.
     */
    fun of(file: File): ArchiveReader {
        require(file.exists()) {
            "File or directory does not exist: ${file.absolutePath}"
        }

        return when {
            file.isDirectory -> DirectoryArchiveReader(file)
            file.isFile -> {
                val ext = file.extension.lowercase()
                if (ext in SUPPORTED_EXTENSIONS) {
                    ZipArchiveReader(file)
                } else {
                    throw IllegalArgumentException(
                        "Unsupported archive format: ${file.name}. Expected .cbz, .zip, or a directory."
                    )
                }
            }
            else -> throw IllegalArgumentException(
                "Path is neither a regular file nor a directory: ${file.absolutePath}"
            )
        }
    }

    /**
     * Convenience overload accepting a path string.
     */
    fun of(path: String): ArchiveReader = of(File(path))
}
