package mihon.core.archive

/**
 * Represents a single readable page entry inside an archive or directory.
 *
 * @property index Zero-based sequential position of the page in natural reading order.
 * @property name Simple filename with extension (e.g. "001.jpg").
 * @property path Archive-internal entry path (e.g. "chapter_01/001.jpg") or relative file path.
 * @property size Uncompressed byte size of the image file, or -1L if unknown.
 */
data class ArchivePage(
    val index: Int,
    val name: String,
    val path: String,
    val size: Long = -1L,
) {
    init {
        require(index >= 0) { "ArchivePage index must be non-negative: $index" }
        require(name.isNotBlank()) { "ArchivePage name must not be blank" }
        require(path.isNotBlank()) { "ArchivePage path must not be blank" }
    }
}
