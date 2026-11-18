package mihon.domain.track

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable as KxSerializable

interface TrackerRepository {

    suspend fun getAuth(trackerType: TrackerType): TrackerAuth?

    suspend fun saveAuth(auth: TrackerAuth)

    suspend fun deleteAuth(trackerType: TrackerType)

    suspend fun getAllAuths(): List<TrackerAuth>

    suspend fun getProgress(mangaId: Long, trackerType: TrackerType): TrackerProgress?

    suspend fun getAllProgress(trackerType: TrackerType): List<TrackerProgress>

    suspend fun getAllProgress(): List<TrackerProgress>

    suspend fun saveProgress(progress: TrackerProgress)

    suspend fun deleteProgress(mangaId: Long, trackerType: TrackerType)

    suspend fun deleteProgress(trackerType: TrackerType)

    fun getProgressAsFlow(trackerType: TrackerType): Flow<List<TrackerProgress>>

    suspend fun getSyncLog(limit: Int): List<SyncLogEntry>

    suspend fun addSyncLog(entry: SyncLogEntry)

    suspend fun clearSyncLog()

    data class SyncLogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val trackerType: TrackerType,
        val action: SyncAction,
        val mangaId: Long? = null,
        val remoteId: Long? = null,
        val message: String,
        val success: Boolean,
        val details: String = "",
    )

    enum class SyncAction {
        AUTHENTICATE,
        SEARCH,
        GET_DETAILS,
        UPDATE_PROGRESS,
        GET_PROGRESS,
        SYNC_LIBRARY,
        TEST_CONNECTION,
        REFRESH_TOKEN,
        REVOKE_ACCESS,
    }
}