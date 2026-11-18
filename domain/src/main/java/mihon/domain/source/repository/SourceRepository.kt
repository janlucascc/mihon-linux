package mihon.domain.source.repository

import kotlinx.coroutines.flow.Flow
import mihon.domain.source.model.Source

interface SourceRepository {

    suspend fun getAllSources(): List<Source>

    fun getAllSourcesAsFlow(): Flow<List<Source>>

    suspend fun getEnabledSources(): List<Source>

    fun getEnabledSourcesAsFlow(): Flow<List<Source>>

    suspend fun getSourceById(id: Long): Source?

    fun getSourceByIdAsFlow(id: Long): Flow<Source?>

    suspend fun getSourceByName(name: String): Source?

    suspend fun insert(source: Source): Long

    suspend fun insertAll(sources: List<Source>): List<Long>

    suspend fun update(source: Source)

    suspend fun updateLastSync(id: Long, timestamp: Long)

    suspend fun incrementErrorCount(id: Long, errorMessage: String?)

    suspend fun setEnabled(id: Long, enabled: Boolean)

    suspend fun delete(id: Long)

    suspend fun deleteAll(): Boolean
}