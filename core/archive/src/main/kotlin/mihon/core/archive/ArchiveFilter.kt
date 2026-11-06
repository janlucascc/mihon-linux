package mihon.core.archive

import java.io.File

/**
 * Filter and sanitization utility for archive entries and filesystem files.
 *
 * Whitelists supported image extensions, rejects non-image files,
 * excludes macOS resource forks (__MACOSX, AppleDouble ._*), and eliminates
 * OS metadata files (Thumbs.db, desktop.ini, .DS_Store).
 */
object ArchiveFilter {

    /**
     * Set of supported image file extensions (lowercase, without leading dot).
     */
    val SUPPORTED_IMAGE_EXTENSIONS: Set<String> = setOf(
        "jpg",
        "jpeg",
        "png",
        "gif",
        "bmp",
        "webp",
    )

    /**
     * Set of rejected OS metadata filenames (lowercase).
     */
    val IGNORED_SYSTEM_FILENAMES: Set<String> = setOf(
        "thumbs.db",
        "desktop.ini",
        "ehthumbs.db",
    )

    /**
     * Checks if a given file extension is a supported image extension.
     * @param extension file extension with or without leading dot.
     */
    fun isSupportedExtension(extension: String): Boolean {
        val clean = extension.lowercase().removePrefix(".")
        return clean in SUPPORTED_IMAGE_EXTENSIONS
    }

    /**
     * Checks if a path or entry name corresponds to a macOS resource fork or AppleDouble file.
     * Matches '__MACOSX/' folder prefix and '._*' AppleDouble filenames.
     */
    fun isMacOsArtifact(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        val fileName = normalized.substringAfterLast('/')
        return normalized.startsWith("__MACOSX/") ||
            normalized.contains("/__MACOSX/") ||
            fileName.startsWith("._")
    }

    /**
     * Checks if a path or entry represents a hidden file, dotfile, or OS metadata file.
     */
    fun isHiddenOrSystemFile(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        val segments = normalized.split('/')
        return segments.any { segment ->
            segment.startsWith(".") || segment.lowercase() in IGNORED_SYSTEM_FILENAMES
        }
    }

    /**
     * Validates whether an archive entry path (or filesystem relative path) is a valid image entry.
     *
     * @param entryName The relative entry path (e.g. "chapter1/01.jpg" or "cover.png").
     * @param isDirectory Whether the entry is a directory.
     * @return true if the entry is a valid, readable image file.
     */
    fun isValidImageEntry(entryName: String, isDirectory: Boolean = false): Boolean {
        if (isDirectory || entryName.isBlank() || entryName.endsWith("/")) return false
        val normalized = entryName.replace('\\', '/')
        // Reject absolute paths and path traversal attempts
        if (normalized.startsWith("/") || normalized.contains("../")) return false
        if (isMacOsArtifact(normalized)) return false
        if (isHiddenOrSystemFile(normalized)) return false

        val ext = normalized.substringAfterLast('.', missingDelimiterValue = "")
        if (ext.isEmpty()) return false

        return isSupportedExtension(ext)
    }

    /**
     * Validates whether a filesystem [File] is a valid image file.
     */
    fun isValidImageFile(file: File): Boolean {
        if (!file.isFile || file.isHidden) return false
        return isValidImageEntry(file.name, isDirectory = false)
    }
}
