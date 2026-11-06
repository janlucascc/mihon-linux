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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mihon.core.common.extensions.EMPTY
import mihon.data.database.DatabaseManager
import mihon.data.database.tables.ChapterTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterRemoteUpdate
import tachiyomi.domain.chapter.model.ChapterSummary
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import kotlin.time.Instant

class ChapterRepositoryImpl(
    private val databaseManager: DatabaseManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ChapterRepository {

    private val changeNotifier = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    private fun notifyChange() {
        changeNotifier.tryEmit(Unit)
    }

    override suspend fun update(chapterUpdate: ChapterUpdate): Unit = databaseManager.withWriteTransaction(dispatcher) {
        ChapterTable.update({ ChapterTable.id eq chapterUpdate.id }) { row ->
            if (chapterUpdate.isSet(ChapterUpdate::read)) {
                chapterUpdate.read?.let { row[read] = it }
            }
            if (chapterUpdate.isSet(ChapterUpdate::bookmark)) {
                chapterUpdate.bookmark?.let { row[bookmark] = it }
            }
            if (chapterUpdate.isSet(ChapterUpdate::lastPageRead)) {
                chapterUpdate.lastPageRead?.let { row[lastPageRead] = it }
            }
            if (chapterUpdate.isSet(ChapterUpdate::dateFetch)) {
                chapterUpdate.dateFetch?.let { row[dateFetch] = it.toEpochMilliseconds() }
            }
        }
        notifyChange()
    }

    override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>): Unit = databaseManager.withWriteTransaction(dispatcher) {
        if (chapterUpdates.isEmpty()) return@withWriteTransaction
        chapterUpdates.forEach { chapterUpdate ->
            ChapterTable.update({ ChapterTable.id eq chapterUpdate.id }) { row ->
                if (chapterUpdate.isSet(ChapterUpdate::read)) {
                    chapterUpdate.read?.let { row[read] = it }
                }
                if (chapterUpdate.isSet(ChapterUpdate::bookmark)) {
                    chapterUpdate.bookmark?.let { row[bookmark] = it }
                }
                if (chapterUpdate.isSet(ChapterUpdate::lastPageRead)) {
                    chapterUpdate.lastPageRead?.let { row[lastPageRead] = it }
                }
                if (chapterUpdate.isSet(ChapterUpdate::dateFetch)) {
                    chapterUpdate.dateFetch?.let { row[dateFetch] = it.toEpochMilliseconds() }
                }
            }
        }
        notifyChange()
    }

    override suspend fun updateFromRemote(
        removedIds: List<Long>,
        added: List<Chapter>,
        updated: List<ChapterRemoteUpdate>,
    ): List<Chapter> = databaseManager.withWriteTransaction(dispatcher) {
        if (removedIds.isNotEmpty()) {
            ChapterTable.deleteWhere { ChapterTable.id inList removedIds }
        }
        updated.forEach { remoteUpdate ->
            ChapterTable.update({ ChapterTable.id eq remoteUpdate.id }) { row ->
                row[name] = remoteUpdate.name
                row[scanlator] = remoteUpdate.scanlator
                row[chapterNumber] = remoteUpdate.chapterNumber
                row[dateUpload] = remoteUpdate.dateUpload?.toEpochMilliseconds()
                row[sourceOrder] = remoteUpdate.sourceOrder
                row[memo] = if (remoteUpdate.memo.isNotEmpty()) remoteUpdate.memo.toString() else null
            }
        }
        val insertedChapters = added.map { chapter ->
            val generatedId = ChapterTable.insertAndGetId { row ->
                row[mangaId] = chapter.mangaId
                row[url] = chapter.url
                row[name] = chapter.name
                row[scanlator] = chapter.scanlator
                row[read] = chapter.read
                row[bookmark] = chapter.bookmark
                row[lastPageRead] = chapter.lastPageRead
                row[chapterNumber] = chapter.chapterNumber
                row[sourceOrder] = chapter.sourceOrder
                row[dateFetch] = chapter.dateFetch.toEpochMilliseconds()
                row[dateUpload] = chapter.dateUpload?.toEpochMilliseconds()
                row[memo] = if (chapter.memo.isNotEmpty()) chapter.memo.toString() else null
            }.value
            chapter.copy(id = generatedId)
        }
        notifyChange()
        insertedChapters
    }

    /**
     * Note: [applyScanlatorFilter] will be wired to UI preferences in Milestone M4.
     */
    override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> = withContext(dispatcher) {
        // Note: applyScanlatorFilter will be wired to UI preferences in Milestone M4
        transaction(databaseManager.database) {
            ChapterTable
                .selectAll()
                .where { ChapterTable.mangaId eq mangaId }
                .orderBy(ChapterTable.sourceOrder, SortOrder.DESC)
                .map { it.toChapter() }
        }
    }

    override suspend fun getChaptersByMangaIds(mangaIds: List<Long>): Map<Long, List<Chapter>> = withContext(dispatcher) {
        if (mangaIds.isEmpty()) return@withContext emptyMap()
        transaction(databaseManager.database) {
            ChapterTable
                .selectAll()
                .where { ChapterTable.mangaId inList mangaIds }
                .orderBy(ChapterTable.sourceOrder, SortOrder.DESC)
                .map { it.toChapter() }
                .groupBy { it.mangaId }
        }
    }

    override suspend fun getChapterSummaryByMangaId(mangaId: Long): ChapterSummary = withContext(dispatcher) {
        transaction(databaseManager.database) {
            val chapters = ChapterTable
                .selectAll()
                .where { ChapterTable.mangaId eq mangaId }
                .map { it[ChapterTable.chapterNumber] }
            val count = chapters.size.toLong()
            val latest = chapters.filter { it >= 0.0 }.maxOrNull()
            ChapterSummary(chapterCount = count, latestChapterNumber = latest)
        }
    }

    override suspend fun getScanlatorsByMangaId(mangaId: Long): List<String> = withContext(dispatcher) {
        transaction(databaseManager.database) {
            ChapterTable
                .selectAll()
                .where { (ChapterTable.mangaId eq mangaId) and ChapterTable.scanlator.isNotNull() }
                .mapNotNull { it[ChapterTable.scanlator] }
                .filter { it.isNotBlank() }
                .distinct()
        }
    }

    override fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>> {
        return changeNotifier.asSharedFlow()
            .onStart { emit(Unit) }
            .map { getScanlatorsByMangaId(mangaId) }
            .flowOn(dispatcher)
    }

    override suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter> = withContext(dispatcher) {
        transaction(databaseManager.database) {
            ChapterTable
                .selectAll()
                .where { (ChapterTable.mangaId eq mangaId) and (ChapterTable.bookmark eq true) }
                .orderBy(ChapterTable.sourceOrder, SortOrder.DESC)
                .map { it.toChapter() }
        }
    }

    override suspend fun getChapterById(id: Long): Chapter? = withContext(dispatcher) {
        transaction(databaseManager.database) {
            ChapterTable
                .selectAll()
                .where { ChapterTable.id eq id }
                .firstOrNull()
                ?.toChapter()
        }
    }

    override suspend fun getChapterByMangaIdAsFlow(
        mangaId: Long,
        applyScanlatorFilter: Boolean,
    ): Flow<List<Chapter>> {
        return changeNotifier.asSharedFlow()
            .onStart { emit(Unit) }
            .map { getChapterByMangaId(mangaId, applyScanlatorFilter) }
            .flowOn(dispatcher)
    }

    override suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter? = withContext(dispatcher) {
        transaction(databaseManager.database) {
            ChapterTable
                .selectAll()
                .where { (ChapterTable.mangaId eq mangaId) and (ChapterTable.url eq url) }
                .firstOrNull()
                ?.toChapter()
        }
    }

    private fun ResultRow.toChapter(): Chapter = Chapter(
        id = this[ChapterTable.id].value,
        mangaId = this[ChapterTable.mangaId].value,
        read = this[ChapterTable.read],
        bookmark = this[ChapterTable.bookmark],
        lastPageRead = this[ChapterTable.lastPageRead],
        dateFetch = Instant.fromEpochMilliseconds(this[ChapterTable.dateFetch]),
        sourceOrder = this[ChapterTable.sourceOrder],
        url = this[ChapterTable.url],
        name = this[ChapterTable.name],
        dateUpload = this[ChapterTable.dateUpload]?.let { Instant.fromEpochMilliseconds(it) },
        chapterNumber = this[ChapterTable.chapterNumber],
        scanlator = this[ChapterTable.scanlator],
        memo = this[ChapterTable.memo]?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject.EMPTY,
    )
}
