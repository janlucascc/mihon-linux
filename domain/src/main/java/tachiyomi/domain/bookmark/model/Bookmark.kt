package tachiyomi.domain.bookmark.model

import kotlin.time.Clock
import kotlin.time.Instant

enum class BookmarkType(val value: String) {
    BOOKMARK("BOOKMARK"),
    NOTE("NOTE"),
    HIGHLIGHT("HIGHLIGHT");

    companion object {
        fun fromValue(value: String): BookmarkType = values().firstOrNull { it.value == value } ?: BOOKMARK
    }
}

data class Bookmark(
    val id: Long,
    val chapterId: Long,
    val page: Int,
    val note: String? = null,
    val createdAt: Instant,
    val type: BookmarkType = BookmarkType.BOOKMARK,
    val color: String? = null, // hex color for highlights (e.g., "#FFFF00")
) {
    companion object {
        fun create(
            chapterId: Long,
            page: Int,
            type: BookmarkType = BookmarkType.BOOKMARK,
            note: String? = null,
            color: String? = null,
        ) = Bookmark(
            id = -1,
            chapterId = chapterId,
            page = page,
            note = note,
            createdAt = Clock.System.now(),
            type = type,
            color = color,
        )
    }

    fun isBookmark(): Boolean = type == BookmarkType.BOOKMARK
    fun isNote(): Boolean = type == BookmarkType.NOTE
    fun isHighlight(): Boolean = type == BookmarkType.HIGHLIGHT
}