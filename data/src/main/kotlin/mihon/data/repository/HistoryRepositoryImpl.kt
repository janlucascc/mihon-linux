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
import mihon.data.database.DatabaseManager
import mihon.data.database.tables.ChapterTable
import mihon.data.database.tables.HistoryTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.repository.HistoryRepository
import kotlin.time.Instant

class HistoryRepositoryImpl(
    private val databaseManager: DatabaseManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HistoryRepository {

    private val writeMutex = databaseManager.writeMutex
    private val changeNotifier = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    private fun notifyChange() {
        changeNotifier.tryEmit(Unit)
    }

    override suspend fun getHistoryById(id: Long): History? = withContext(dispatcher) {
        transaction(databaseManager.database) {
            HistoryTable
                .selectAll()
                .where { HistoryTable.id eq id }
                .firstOrNull()
                ?.toHistory()
        }
    }

    override suspend fun getHistoryByChapterId(chapterId: Long): History? = withContext(dispatcher) {
        transaction(databaseManager.database) {
            HistoryTable
                .selectAll()
                .where { HistoryTable.chapterId eq chapterId }
                .firstOrNull()
                ?.toHistory()
        }
    }

    override fun getHistoryByChapterIdAsFlow(chapterId: Long): Flow<History?> {
        return changeNotifier.asSharedFlow()
            .onStart { emit(Unit) }
            .map { getHistoryByChapterId(chapterId) }
            .flowOn(dispatcher)
    }

    override suspend fun getHistoryByMangaId(mangaId: Long): List<History> = withContext(dispatcher) {
        transaction(databaseManager.database) {
            (HistoryTable innerJoin ChapterTable)
                .selectAll()
                .where { ChapterTable.mangaId eq mangaId }
                .orderBy(HistoryTable.readAt, SortOrder.DESC)
                .map { it.toHistory() }
        }
    }

    override fun getHistoryByMangaIdAsFlow(mangaId: Long): Flow<List<History>> {
        return changeNotifier.asSharedFlow()
            .onStart { emit(Unit) }
            .map { getHistoryByMangaId(mangaId) }
            .flowOn(dispatcher)
    }

    override suspend fun getAllHistory(): List<History> = withContext(dispatcher) {
        transaction(databaseManager.database) {
            HistoryTable
                .selectAll()
                .orderBy(HistoryTable.readAt, SortOrder.DESC)
                .map { it.toHistory() }
        }
    }

    override fun getAllHistoryAsFlow(): Flow<List<History>> {
        return changeNotifier.asSharedFlow()
            .onStart { emit(Unit) }
            .map { getAllHistory() }
            .flowOn(dispatcher)
    }

    override suspend fun upsert(historyUpdate: HistoryUpdate): History = writeMutex.withLock {
        val result = withContext(dispatcher) {
            transaction(databaseManager.database) {
                val existing = HistoryTable
                    .selectAll()
                    .where { HistoryTable.chapterId eq historyUpdate.chapterId }
                    .firstOrNull()

                if (existing != null) {
                    val existingHistory = existing.toHistory()
                    val newDuration = maxOf(0L, existingHistory.readDuration) + historyUpdate.sessionReadDuration
                    HistoryTable.update({ HistoryTable.id eq existingHistory.id }) { row ->
                        row[readAt] = historyUpdate.readAt.toEpochMilliseconds()
                        row[readDuration] = newDuration
                    }
                    History(
                        id = existingHistory.id,
                        chapterId = historyUpdate.chapterId,
                        readAt = historyUpdate.readAt,
                        readDuration = newDuration,
                    )
                } else {
                    val newId = HistoryTable.insertAndGetId { row ->
                        row[chapterId] = historyUpdate.chapterId
                        row[readAt] = historyUpdate.readAt.toEpochMilliseconds()
                        row[readDuration] = historyUpdate.sessionReadDuration
                    }.value
                    History(
                        id = newId,
                        chapterId = historyUpdate.chapterId,
                        readAt = historyUpdate.readAt,
                        readDuration = historyUpdate.sessionReadDuration,
                    )
                }
            }
        }
        notifyChange()
        result
    }

    override suspend fun upsert(history: History): History = writeMutex.withLock {
        val result = withContext(dispatcher) {
            transaction(databaseManager.database) {
                val existing = if (history.id > 0) {
                    HistoryTable.selectAll().where { HistoryTable.id eq history.id }.firstOrNull()
                } else {
                    HistoryTable.selectAll().where { HistoryTable.chapterId eq history.chapterId }.firstOrNull()
                }

                if (existing != null) {
                    val existingId = existing[HistoryTable.id].value
                    HistoryTable.update({ HistoryTable.id eq existingId }) { row ->
                        row[chapterId] = history.chapterId
                        row[readAt] = history.readAt?.toEpochMilliseconds()
                        row[readDuration] = history.readDuration
                    }
                    history.copy(id = existingId)
                } else {
                    val newId = HistoryTable.insertAndGetId { row ->
                        row[chapterId] = history.chapterId
                        row[readAt] = history.readAt?.toEpochMilliseconds()
                        row[readDuration] = history.readDuration
                    }.value
                    history.copy(id = newId)
                }
            }
        }
        notifyChange()
        result
    }

    override suspend fun delete(id: Long): Unit = writeMutex.withLock {
        withContext(dispatcher) {
            transaction(databaseManager.database) {
                HistoryTable.deleteWhere { HistoryTable.id eq id }
            }
        }
        notifyChange()
    }

    override suspend fun deleteByChapterId(chapterId: Long): Unit = writeMutex.withLock {
        withContext(dispatcher) {
            transaction(databaseManager.database) {
                HistoryTable.deleteWhere { HistoryTable.chapterId eq chapterId }
            }
        }
        notifyChange()
    }

    override suspend fun deleteByMangaId(mangaId: Long): Unit = writeMutex.withLock {
        withContext(dispatcher) {
            transaction(databaseManager.database) {
                val chapterIds = ChapterTable
                    .selectAll()
                    .where { ChapterTable.mangaId eq mangaId }
                    .map { it[ChapterTable.id].value }

                if (chapterIds.isNotEmpty()) {
                    HistoryTable.deleteWhere { HistoryTable.chapterId inList chapterIds }
                }
            }
        }
        notifyChange()
    }

    override suspend fun deleteAll(): Boolean = writeMutex.withLock {
        val deleted = withContext(dispatcher) {
            transaction(databaseManager.database) {
                HistoryTable.deleteAll()
            }
        }
        notifyChange()
        deleted > 0
    }

    private fun ResultRow.toHistory(): History = History(
        id = this[HistoryTable.id].value,
        chapterId = this[HistoryTable.chapterId].value,
        readAt = this[HistoryTable.readAt]?.let { Instant.fromEpochMilliseconds(it) },
        readDuration = this[HistoryTable.readDuration],
    )
}
