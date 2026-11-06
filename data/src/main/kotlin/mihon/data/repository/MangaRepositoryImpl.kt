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
import mihon.data.database.tables.MangaTable
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
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaRemoteUpdate
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import kotlin.time.Instant

class MangaRepositoryImpl(
    private val databaseManager: DatabaseManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MangaRepository {

    private val changeNotifier = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    private fun notifyChange() {
        changeNotifier.tryEmit(Unit)
    }

    override suspend fun getMangaById(id: Long): Manga? = withContext(dispatcher) {
        transaction(databaseManager.database) {
            MangaTable
                .selectAll()
                .where { MangaTable.id eq id }
                .firstOrNull()
                ?.toManga()
        }
    }

    override fun getMangaByIdAsFlow(id: Long): Flow<Manga?> {
        return changeNotifier.asSharedFlow()
            .onStart { emit(Unit) }
            .map { getMangaById(id) }
            .flowOn(dispatcher)
    }

    override suspend fun getMangaByUrlAndSource(url: String, source: Long): Manga? = withContext(dispatcher) {
        transaction(databaseManager.database) {
            MangaTable
                .selectAll()
                .where { (MangaTable.url eq url) and (MangaTable.sourceId eq source) }
                .firstOrNull()
                ?.toManga()
        }
    }

    override fun getMangaByUrlAndSourceAsFlow(url: String, source: Long): Flow<Manga?> {
        return changeNotifier.asSharedFlow()
            .onStart { emit(Unit) }
            .map { getMangaByUrlAndSource(url, source) }
            .flowOn(dispatcher)
    }

    override suspend fun getFavorites(): List<Manga> = withContext(dispatcher) {
        transaction(databaseManager.database) {
            MangaTable
                .selectAll()
                .where { MangaTable.favorite eq true }
                .orderBy(MangaTable.title, SortOrder.ASC)
                .map { it.toManga() }
        }
    }

    override fun getFavoritesAsFlow(): Flow<List<Manga>> {
        return changeNotifier.asSharedFlow()
            .onStart { emit(Unit) }
            .map { getFavorites() }
            .flowOn(dispatcher)
    }

    override suspend fun getAll(): List<Manga> = withContext(dispatcher) {
        transaction(databaseManager.database) {
            MangaTable
                .selectAll()
                .orderBy(MangaTable.title, SortOrder.ASC)
                .map { it.toManga() }
        }
    }

    override fun getAllAsFlow(): Flow<List<Manga>> {
        return changeNotifier.asSharedFlow()
            .onStart { emit(Unit) }
            .map { getAll() }
            .flowOn(dispatcher)
    }

    override suspend fun getMangasByIds(ids: List<Long>): List<Manga> = withContext(dispatcher) {
        if (ids.isEmpty()) return@withContext emptyList()
        transaction(databaseManager.database) {
            MangaTable
                .selectAll()
                .where { MangaTable.id inList ids }
                .map { it.toManga() }
        }
    }

    override fun getMangasByIdsAsFlow(ids: List<Long>): Flow<List<Manga>> {
        return changeNotifier.asSharedFlow()
            .onStart { emit(Unit) }
            .map { getMangasByIds(ids) }
            .flowOn(dispatcher)
    }

    override suspend fun insert(manga: Manga): Long = databaseManager.withWriteTransaction(dispatcher) {
        val newId = MangaTable.insertAndGetId {
            it[sourceId] = manga.source
            it[favorite] = manga.favorite
            it[lastUpdate] = manga.lastUpdate.toEpochMilliseconds()
            it[nextUpdate] = manga.nextUpdate.toEpochMilliseconds()
            it[fetchInterval] = manga.fetchInterval
            it[dateAdded] = manga.dateAdded.toEpochMilliseconds()
            it[viewerFlags] = manga.viewerFlags
            it[chapterFlags] = manga.chapterFlags
            it[coverLastModified] = manga.coverLastModified.toEpochMilliseconds()
            it[url] = manga.url
            it[title] = manga.title
            it[artist] = manga.artist
            it[author] = manga.author
            it[description] = manga.description
            it[genre] = manga.genre?.joinToString(", ")
            it[status] = manga.status
            it[thumbnailUrl] = manga.thumbnailUrl
            it[updateStrategy] = manga.updateStrategy
            it[initialized] = manga.initialized
            it[notes] = manga.notes
            it[memo] = if (manga.memo.isNotEmpty()) manga.memo.toString() else null
            it[favoriteAt] = manga.favoriteAt?.toEpochMilliseconds()
        }.value
        notifyChange()
        newId
    }

    override suspend fun insertAll(mangas: List<Manga>): List<Long> = databaseManager.withWriteTransaction(dispatcher) {
        if (mangas.isEmpty()) return@withWriteTransaction emptyList()
        val ids = mangas.map { manga ->
            MangaTable.insertAndGetId {
                it[sourceId] = manga.source
                it[favorite] = manga.favorite
                it[lastUpdate] = manga.lastUpdate.toEpochMilliseconds()
                it[nextUpdate] = manga.nextUpdate.toEpochMilliseconds()
                it[fetchInterval] = manga.fetchInterval
                it[dateAdded] = manga.dateAdded.toEpochMilliseconds()
                it[viewerFlags] = manga.viewerFlags
                it[chapterFlags] = manga.chapterFlags
                it[coverLastModified] = manga.coverLastModified.toEpochMilliseconds()
                it[url] = manga.url
                it[title] = manga.title
                it[artist] = manga.artist
                it[author] = manga.author
                it[description] = manga.description
                it[genre] = manga.genre?.joinToString(", ")
                it[status] = manga.status
                it[thumbnailUrl] = manga.thumbnailUrl
                it[updateStrategy] = manga.updateStrategy
                it[initialized] = manga.initialized
                it[notes] = manga.notes
                it[memo] = if (manga.memo.isNotEmpty()) manga.memo.toString() else null
                it[favoriteAt] = manga.favoriteAt?.toEpochMilliseconds()
            }.value
        }
        notifyChange()
        ids
    }

    override suspend fun update(manga: Manga): Unit = databaseManager.withWriteTransaction(dispatcher) {
        MangaTable.update({ MangaTable.id eq manga.id }) {
            it[sourceId] = manga.source
            it[favorite] = manga.favorite
            it[lastUpdate] = manga.lastUpdate.toEpochMilliseconds()
            it[nextUpdate] = manga.nextUpdate.toEpochMilliseconds()
            it[fetchInterval] = manga.fetchInterval
            it[dateAdded] = manga.dateAdded.toEpochMilliseconds()
            it[viewerFlags] = manga.viewerFlags
            it[chapterFlags] = manga.chapterFlags
            it[coverLastModified] = manga.coverLastModified.toEpochMilliseconds()
            it[url] = manga.url
            it[title] = manga.title
            it[artist] = manga.artist
            it[author] = manga.author
            it[description] = manga.description
            it[genre] = manga.genre?.joinToString(", ")
            it[status] = manga.status
            it[thumbnailUrl] = manga.thumbnailUrl
            it[updateStrategy] = manga.updateStrategy
            it[initialized] = manga.initialized
            it[notes] = manga.notes
            it[memo] = if (manga.memo.isNotEmpty()) manga.memo.toString() else null
            it[favoriteAt] = manga.favoriteAt?.toEpochMilliseconds()
        }
        notifyChange()
    }

    override suspend fun update(mangaUpdate: MangaUpdate): Unit = databaseManager.withWriteTransaction(dispatcher) {
        MangaTable.update({ MangaTable.id eq mangaUpdate.id }) { row ->
            if (mangaUpdate.isSet(MangaUpdate::favoriteAt)) {
                row[favoriteAt] = mangaUpdate.favoriteAt?.toEpochMilliseconds()
            }
            if (mangaUpdate.isSet(MangaUpdate::lastUpdate)) {
                row[lastUpdate] = mangaUpdate.lastUpdate?.toEpochMilliseconds() ?: 0L
            }
            if (mangaUpdate.isSet(MangaUpdate::nextUpdate)) {
                row[nextUpdate] = mangaUpdate.nextUpdate?.toEpochMilliseconds() ?: 0L
            }
            if (mangaUpdate.isSet(MangaUpdate::fetchInterval)) {
                row[fetchInterval] = mangaUpdate.fetchInterval ?: 0
            }
            if (mangaUpdate.isSet(MangaUpdate::viewerFlags)) {
                row[viewerFlags] = mangaUpdate.viewerFlags ?: 0L
            }
            if (mangaUpdate.isSet(MangaUpdate::chapterFlags)) {
                row[chapterFlags] = mangaUpdate.chapterFlags ?: 0L
            }
            if (mangaUpdate.isSet(MangaUpdate::coverLastModified)) {
                row[coverLastModified] = mangaUpdate.coverLastModified?.toEpochMilliseconds() ?: 0L
            }
            if (mangaUpdate.isSet(MangaUpdate::notes)) {
                row[notes] = mangaUpdate.notes
            }
        }
        notifyChange()
    }

    override suspend fun update(mangaRemoteUpdate: MangaRemoteUpdate): Unit = databaseManager.withWriteTransaction(dispatcher) {
        MangaTable.update({ MangaTable.id eq mangaRemoteUpdate.id }) { row ->
            mangaRemoteUpdate.title?.let { row[title] = it }
            mangaRemoteUpdate.author?.let { row[author] = it }
            mangaRemoteUpdate.artist?.let { row[artist] = it }
            mangaRemoteUpdate.description?.let { row[description] = it }
            mangaRemoteUpdate.genre?.let { row[genre] = it.joinToString(", ") }
            row[status] = mangaRemoteUpdate.status
            row[thumbnailUrl] = mangaRemoteUpdate.thumbnailUrl
            row[updateStrategy] = mangaRemoteUpdate.updateStrategy
            row[memo] = if (mangaRemoteUpdate.memo.isNotEmpty()) mangaRemoteUpdate.memo.toString() else null
            row[initialized] = mangaRemoteUpdate.initialized
            mangaRemoteUpdate.coverLastModified?.let { row[coverLastModified] = it.toEpochMilliseconds() }
        }
        notifyChange()
    }

    override suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Unit = databaseManager.withWriteTransaction(dispatcher) {
        if (mangaUpdates.isEmpty()) return@withWriteTransaction
        mangaUpdates.forEach { mangaUpdate ->
            MangaTable.update({ MangaTable.id eq mangaUpdate.id }) { row ->
                if (mangaUpdate.isSet(MangaUpdate::favoriteAt)) {
                    row[favoriteAt] = mangaUpdate.favoriteAt?.toEpochMilliseconds()
                }
                if (mangaUpdate.isSet(MangaUpdate::lastUpdate)) {
                    row[lastUpdate] = mangaUpdate.lastUpdate?.toEpochMilliseconds() ?: 0L
                }
                if (mangaUpdate.isSet(MangaUpdate::nextUpdate)) {
                    row[nextUpdate] = mangaUpdate.nextUpdate?.toEpochMilliseconds() ?: 0L
                }
                if (mangaUpdate.isSet(MangaUpdate::fetchInterval)) {
                    row[fetchInterval] = mangaUpdate.fetchInterval ?: 0
                }
                if (mangaUpdate.isSet(MangaUpdate::viewerFlags)) {
                    row[viewerFlags] = mangaUpdate.viewerFlags ?: 0L
                }
                if (mangaUpdate.isSet(MangaUpdate::chapterFlags)) {
                    row[chapterFlags] = mangaUpdate.chapterFlags ?: 0L
                }
                if (mangaUpdate.isSet(MangaUpdate::coverLastModified)) {
                    row[coverLastModified] = mangaUpdate.coverLastModified?.toEpochMilliseconds() ?: 0L
                }
                if (mangaUpdate.isSet(MangaUpdate::notes)) {
                    row[notes] = mangaUpdate.notes
                }
            }
        }
        notifyChange()
    }

    override suspend fun delete(id: Long): Unit = databaseManager.withWriteTransaction(dispatcher) {
        MangaTable.deleteWhere { MangaTable.id eq id }
        notifyChange()
    }

    override suspend fun deleteAll(): Boolean = databaseManager.withWriteTransaction(dispatcher) {
        val deleted = MangaTable.deleteAll()
        notifyChange()
        deleted > 0
    }

    override suspend fun resetViewerFlags(): Unit = databaseManager.withWriteTransaction(dispatcher) {
        MangaTable.update {
            it[viewerFlags] = 0L
        }
        notifyChange()
    }

    private fun ResultRow.toManga(): Manga = Manga(
        id = this[MangaTable.id].value,
        source = this[MangaTable.sourceId],
        favorite = this[MangaTable.favorite],
        lastUpdate = Instant.fromEpochMilliseconds(this[MangaTable.lastUpdate]),
        nextUpdate = Instant.fromEpochMilliseconds(this[MangaTable.nextUpdate]),
        fetchInterval = this[MangaTable.fetchInterval],
        dateAdded = Instant.fromEpochMilliseconds(this[MangaTable.dateAdded]),
        viewerFlags = this[MangaTable.viewerFlags],
        chapterFlags = this[MangaTable.chapterFlags],
        coverLastModified = Instant.fromEpochMilliseconds(this[MangaTable.coverLastModified]),
        url = this[MangaTable.url],
        title = this[MangaTable.title],
        artist = this[MangaTable.artist],
        author = this[MangaTable.author],
        description = this[MangaTable.description],
        genre = this[MangaTable.genre]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() },
        status = this[MangaTable.status],
        thumbnailUrl = this[MangaTable.thumbnailUrl],
        updateStrategy = this[MangaTable.updateStrategy],
        initialized = this[MangaTable.initialized],
        notes = this[MangaTable.notes],
        memo = this[MangaTable.memo]?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject.EMPTY,
        favoriteAt = this[MangaTable.favoriteAt]?.let { Instant.fromEpochMilliseconds(it) },
    )
}
