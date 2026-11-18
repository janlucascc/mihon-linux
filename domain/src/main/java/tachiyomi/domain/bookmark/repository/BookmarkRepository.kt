package tachiyomi.domain.bookmark.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.bookmark.model.Bookmark

interface BookmarkRepository {

    suspend fun getBookmarkById(id: Long): Bookmark?

    suspend fun getBookmarksByChapterId(chapterId: Long): List<Bookmark>

    fun getBookmarksByChapterIdAsFlow(chapterId: Long): Flow<List<Bookmark>>

    suspend fun getAllBookmarks(): List<Bookmark>

    fun getAllBookmarksAsFlow(): Flow<List<Bookmark>>

    suspend fun upsert(bookmark: Bookmark): Bookmark

    suspend fun delete(id: Long)

    suspend fun deleteByChapterId(chapterId: Long)

    suspend fun deleteAll(): Boolean

    // Convenience methods
    suspend fun getBookmarksByChapterIds(chapterIds: List<Long>): Map<Long, List<Bookmark>>

    suspend fun getBookmarksByType(type: tachiyomi.domain.bookmark.model.BookmarkType): List<Bookmark>

    suspend fun toggleBookmark(chapterId: Long, page: Int): Boolean

    suspend fun addNote(chapterId: Long, page: Int, note: String): Bookmark

    suspend fun addHighlight(chapterId: Long, page: Int, color: String): Bookmark
}