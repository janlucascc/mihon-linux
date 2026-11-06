package mihon.core.archive

@Deprecated(
    message = "Use ArchivePage instead",
    replaceWith = ReplaceWith("ArchivePage(index = 0, name = name, path = name)"),
)
class ArchiveEntry(
    val name: String,
    val isFile: Boolean,
)

