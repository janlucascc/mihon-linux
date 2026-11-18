package mihon.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import mihon.data.database.DatabaseManager
import mihon.data.database.tables.SourceTable
import mihon.domain.source.model.Source
import mihon.domain.source.repository.SourceRepository
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class SourceRepositoryImpl(
    private val dbManager: DatabaseManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SourceRepository {

    private val changeNotifier = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    private fun notifyChange() {
        changeNotifier.tryEmit(Unit)
    }

    override suspend fun getAllSources(): List<Source> = withContext(dispatcher) {
        transaction(dbManager.database) {
            SourceTable.selectAll().map { mapToSource(it) }
        }
    }

    override fun getAllSourcesAsFlow(): Flow<List<Source>> = changeNotifier.asSharedFlow()
        .onStart { emit(Unit) }
        .map { getAllSources() }
        .flowOn(dispatcher)

    override suspend fun getEnabledSources(): List<Source> = withContext(dispatcher) {
        transaction(dbManager.database) {
            SourceTable.selectAll().where { SourceTable.enabled eq 1 }.map { mapToSource(it) }
        }
    }

    override fun getEnabledSourcesAsFlow(): Flow<List<Source>> = changeNotifier.asSharedFlow()
        .onStart { emit(Unit) }
        .map { getEnabledSources() }
        .flowOn(dispatcher)

    override suspend fun getSourceById(id: Long): Source? = withContext(dispatcher) {
        transaction(dbManager.database) {
            SourceTable.selectAll().where { SourceTable.id eq id }.firstOrNull()?.let { mapToSource(it) }
        }
    }

    override fun getSourceByIdAsFlow(id: Long): Flow<Source?> = changeNotifier.asSharedFlow()
        .onStart { emit(Unit) }
        .map { getSourceById(id) }
        .flowOn(dispatcher)

    override suspend fun getSourceByName(name: String): Source? = withContext(dispatcher) {
        transaction(dbManager.database) {
            SourceTable.selectAll().where { SourceTable.name eq name }.firstOrNull()?.let { mapToSource(it) }
        }
    }

    override suspend fun insert(source: Source): Long = dbManager.withWriteTransaction(dispatcher) {
        SourceTable.insertAndGetId {
            it[SourceTable.name] = source.name
            it[SourceTable.baseUrl] = source.baseUrl
            it[SourceTable.type] = source.type.name
            it[SourceTable.configJson] = source.configJson ?: ""
            it[SourceTable.version] = source.version
            it[SourceTable.enabled] = if (source.enabled) 1 else 0
            it[SourceTable.lastSync] = source.lastSync
            it[SourceTable.errorCount] = source.errorCount
            it[SourceTable.lastError] = source.lastError ?: ""
        }.value
    }

    override suspend fun insertAll(sources: List<Source>): List<Long> = dbManager.withWriteTransaction(dispatcher) {
        sources.map { source ->
            SourceTable.insertAndGetId {
                it[SourceTable.name] = source.name
                it[SourceTable.baseUrl] = source.baseUrl
                it[SourceTable.type] = source.type.name
                it[SourceTable.configJson] = source.configJson ?: ""
                it[SourceTable.version] = source.version
                it[SourceTable.enabled] = if (source.enabled) 1 else 0
                it[SourceTable.lastSync] = source.lastSync
                it[SourceTable.errorCount] = source.errorCount
                it[SourceTable.lastError] = source.lastError ?: ""
            }.value
        }
    }

    override suspend fun update(source: Source) = dbManager.withWriteTransaction(dispatcher) {
        SourceTable.update({ SourceTable.id eq source.id }) {
            it[SourceTable.name] = source.name
            it[SourceTable.baseUrl] = source.baseUrl
            it[SourceTable.type] = source.type.name
            it[SourceTable.configJson] = source.configJson ?: ""
            it[SourceTable.version] = source.version
            it[SourceTable.enabled] = if (source.enabled) 1 else 0
            it[SourceTable.lastSync] = source.lastSync
            it[SourceTable.errorCount] = source.errorCount
            it[SourceTable.lastError] = source.lastError ?: ""
        }
        notifyChange()
    }

    override suspend fun updateLastSync(id: Long, timestamp: Long) = dbManager.withWriteTransaction(dispatcher) {
        SourceTable.update({ SourceTable.id eq id }) {
            it[SourceTable.lastSync] = timestamp
        }
        notifyChange()
    }

    override suspend fun incrementErrorCount(id: Long, errorMessage: String?) = dbManager.withWriteTransaction(dispatcher) {
        SourceTable.update({ SourceTable.id eq id }) {
            it[SourceTable.errorCount] = SourceTable.errorCount.plus(1)
            it[SourceTable.lastError] = errorMessage
        }
        notifyChange()
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) = dbManager.withWriteTransaction(dispatcher) {
        SourceTable.update({ SourceTable.id eq id }) {
            it[SourceTable.enabled] = if (enabled) 1 else 0
        }
        notifyChange()
    }

    override suspend fun delete(id: Long) = dbManager.withWriteTransaction(dispatcher) {
        SourceTable.deleteWhere { SourceTable.id eq id }
        notifyChange()
    }

    override suspend fun deleteAll(): Boolean = dbManager.withWriteTransaction(dispatcher) {
        SourceTable.deleteAll()
        notifyChange()
        true
    }

    private fun mapToSource(row: ResultRow): Source {
        return Source(
            id = row[SourceTable.id].value,
            name = row[SourceTable.name],
            baseUrl = row[SourceTable.baseUrl],
            type = Source.SourceType.valueOf(row[SourceTable.type]),
            configJson = row[SourceTable.configJson].takeIf { it.isNotBlank() },
            version = row[SourceTable.version],
            enabled = row[SourceTable.enabled] == 1,
            lastSync = row[SourceTable.lastSync],
            errorCount = row[SourceTable.errorCount],
            lastError = row[SourceTable.lastError]?.takeIf { it.isNotBlank() },
        )
    }
}