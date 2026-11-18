package mihon.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant
import mihon.data.database.DatabaseManager
import mihon.data.database.tables.BookmarkTable
import mihon.data.database.tables.ChapterTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import tachiyomi.domain.bookmark.model.Bookmark
import tachiyomi.domain.bookmark.model.BookmarkType
import tachiyomi.domain.bookmark.repository.BookmarkRepository

class BookmarkRepositoryImpl(
    private val databaseManager: DatabaseManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BookmarkRepository {

    private val writeMutex = databaseManager.writeMutex
    private val changeNotifier = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    private fun notifyChange() {
        changeNotifier.tryEmit(Unit)
    }

    override suspend fun getBookmarkById(id: Long): Bookmark? = withContext(dispatcher) {
        transaction(databaseManager.database) {
            BookmarkTable
                .selectAll()
                .where { BookmarkTable.id eq id }
                .firstOrNull()
                ?.toBookmark()
        }
    }

    override suspend fun getBookmarksByChapterId(chapterId: Long): List<Bookmark> = withContext(dispatcher) {
        transaction(databaseManager.database) {
            @Suppress("UNCHECKED_CAST")
            BookmarkTable
                .selectAll()
                .where { BookmarkTable.chapterId eq chapterId }
                .orderBy(BookmarkTable.page to SortOrder.ASC, BookmarkTable.createdAt to SortOrder.DESC)
                .map { it.toBookmark() } as List<Bookmark>
        }
    }

    override fun getBookmarksByChapterIdAsFlow(chapterId: Long): Flow<List<Bookmark>> {
        return changeNotifier.asSharedFlow()
            .onStart { emit(Unit) }
            .map { getBookmarksByChapterId(chapterId) }
            .flowOn(dispatcher)
    }

    override suspend fun getAllBookmarks(): List<Bookmark> = withContext(dispatcher) {
        transaction(databaseManager.database) {
            @Suppress("UNCHECKED_CAST")
            BookmarkTable
                .selectAll()
                .orderBy(BookmarkTable.createdAt, SortOrder.DESC)
                .map { it.toBookmark() } as List<Bookmark>
        }
    }

    override fun getAllBookmarksAsFlow(): Flow<List<Bookmark>> {
        return changeNotifier.asSharedFlow()
            .onStart { emit(Unit) }
            .map { getAllBookmarks() }
            .flowOn(dispatcher)
    }

    override suspend fun upsert(bookmark: Bookmark): Bookmark = writeMutex.withLock {
        val result = withContext(dispatcher) {
            transaction(databaseManager.database) {
                val existing = if (bookmark.id > 0) {
                    BookmarkTable.selectAll().where { BookmarkTable.id eq bookmark.id }.firstOrNull()
                } else {
                    null
                }

                if (existing != null) {
                    val existingId = existing[BookmarkTable.id].value
                    BookmarkTable.update({ BookmarkTable.id eq existingId }) { row ->
                        row[BookmarkTable.chapterId] = bookmark.chapterId
                        row[BookmarkTable.page] = bookmark.page
                        row[BookmarkTable.note] = bookmark.note
                        row[BookmarkTable.createdAt] = bookmark.createdAt.toEpochMilliseconds()
                        row[BookmarkTable.type] = bookmark.type.value
                        row[BookmarkTable.color] = bookmark.color
                    }
                    bookmark.copy(id = existingId)
                } else {
                    val newId = BookmarkTable.insertAndGetId { row ->
                        row[BookmarkTable.chapterId] = bookmark.chapterId
                        row[BookmarkTable.page] = bookmark.page
                        row[BookmarkTable.note] = bookmark.note
                        row[BookmarkTable.createdAt] = bookmark.createdAt.toEpochMilliseconds()
                        row[BookmarkTable.type] = bookmark.type.value
                        row[BookmarkTable.color] = bookmark.color
                    }.value
                    bookmark.copy(id = newId)
                }
            }
        }
        notifyChange()
        result
    }

    override suspend fun delete(id: Long): Unit = writeMutex.withLock {
        withContext(dispatcher) {
            transaction(databaseManager.database) {
                BookmarkTable.deleteWhere { BookmarkTable.id eq id }
            }
        }
        notifyChange()
    }

    override suspend fun deleteByChapterId(chapterId: Long): Unit = writeMutex.withLock {
        withContext(dispatcher) {
            transaction(databaseManager.database) {
                BookmarkTable.deleteWhere { BookmarkTable.chapterId eq chapterId }
            }
        }
        notifyChange()
    }

    override suspend fun deleteAll(): Boolean = writeMutex.withLock {
        val deleted = withContext(dispatcher) {
            transaction(databaseManager.database) {
                BookmarkTable.deleteAll()
            }
        }
        notifyChange()
        deleted > 0
    }

    override suspend fun getBookmarksByChapterIds(chapterIds: List<Long>): Map<Long, List<Bookmark>> = withContext(dispatcher) {
        if (chapterIds.isEmpty()) return@withContext emptyMap()
        transaction(databaseManager.database) {
            @Suppress("UNCHECKED_CAST")
            val bookmarks = BookmarkTable
                .selectAll()
                .where { BookmarkTable.chapterId inList chapterIds }
                .orderBy(BookmarkTable.page to SortOrder.ASC, BookmarkTable.createdAt to SortOrder.DESC)
                .map { it.toBookmark() } as List<Bookmark>
            bookmarks.groupBy { it.chapterId }
        }
    }

    override suspend fun getBookmarksByType(type: BookmarkType): List<Bookmark> = withContext(dispatcher) {
        transaction(databaseManager.database) {
            @Suppress("UNCHECKED_CAST")
            BookmarkTable
                .selectAll()
                .where { BookmarkTable.type eq type.value }
                .orderBy(BookmarkTable.createdAt, SortOrder.DESC)
                .map { it.toBookmark() } as List<Bookmark>
        }
    }

    override suspend fun toggleBookmark(chapterId: Long, page: Int): Boolean = writeMutex.withLock {
        val result = withContext(dispatcher) {
            transaction(databaseManager.database) {
                val existing = BookmarkTable
                    .selectAll()
                    .where { (BookmarkTable.chapterId eq chapterId) and (BookmarkTable.page eq page) and (BookmarkTable.type eq BookmarkType.BOOKMARK.value) }
                    .firstOrNull()

                if (existing != null) {
                    BookmarkTable.deleteWhere { BookmarkTable.id eq existing[BookmarkTable.id].value }
                    false // removed
                } else {
                    BookmarkTable.insertAndGetId { row ->
                        row[BookmarkTable.chapterId] = chapterId
                        row[BookmarkTable.page] = page
                        row[BookmarkTable.type] = BookmarkType.BOOKMARK.value
                        row[BookmarkTable.createdAt] = Clock.System.now().toEpochMilliseconds()
                    }
                    true // added
                }
            }
        }
        notifyChange()
        result
    }

    override suspend fun addNote(chapterId: Long, page: Int, note: String): Bookmark = writeMutex.withLock {
        val result = withContext(dispatcher) {
            transaction(databaseManager.database) {
                val newId = BookmarkTable.insertAndGetId { row ->
                    row[BookmarkTable.chapterId] = chapterId
                    row[BookmarkTable.page] = page
                    row[BookmarkTable.note] = note
                    row[BookmarkTable.type] = BookmarkType.NOTE.value
                    row[BookmarkTable.createdAt] = Clock.System.now().toEpochMilliseconds()
                }.value
                Bookmark(
                    id = newId,
                    chapterId = chapterId,
                    page = page,
                    note = note,
                    createdAt = Clock.System.now(),
                    type = BookmarkType.NOTE,
                )
            }
        }
        notifyChange()
        result
    }

    override suspend fun addHighlight(chapterId: Long, page: Int, color: String): Bookmark = writeMutex.withLock {
        val result = withContext(dispatcher) {
            transaction(databaseManager.database) {
                val newId = BookmarkTable.insertAndGetId { row ->
                    row[BookmarkTable.chapterId] = chapterId
                    row[BookmarkTable.page] = page
                    row[BookmarkTable.type] = BookmarkType.HIGHLIGHT.value
                    row[BookmarkTable.color] = color
                    row[BookmarkTable.createdAt] = Clock.System.now().toEpochMilliseconds()
                }.value
                Bookmark(
                    id = newId,
                    chapterId = chapterId,
                    page = page,
                    createdAt = Clock.System.now(),
                    type = BookmarkType.HIGHLIGHT,
                    color = color,
                )
            }
        }
        notifyChange()
        result
    }

    private fun ResultRow.toBookmark(): Bookmark = Bookmark(
        id = this[BookmarkTable.id].value,
        chapterId = this[BookmarkTable.chapterId].value,
        page = this[BookmarkTable.page],
        note = this[BookmarkTable.note],
        createdAt = Instant.fromEpochMilliseconds(this[BookmarkTable.createdAt]),
        type = BookmarkType.fromValue(this[BookmarkTable.type]),
        color = this[BookmarkTable.color],
    )
}