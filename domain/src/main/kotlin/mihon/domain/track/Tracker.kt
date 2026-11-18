package mihon.domain.track

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable as KxSerializable

interface Tracker {

    val type: TrackerType
    val auth: TrackerAuth

    suspend fun authenticate(): Result<TrackerAuth>

    suspend fun refreshToken(): Result<TrackerAuth>

    suspend fun revokeAccess(): Result<Unit>

    suspend fun searchManga(query: String, limit: Int = 20): Result<List<TrackerSearchResult>>

    suspend fun getMangaDetails(remoteId: Long): Result<TrackerMangaDetails>

    suspend fun updateProgress(progress: TrackerProgress): Result<TrackerProgress>

    suspend fun getProgress(remoteId: Long): Result<TrackerProgress?>

    suspend fun getAllProgress(): Result<List<TrackerProgress>>

    suspend fun syncLibrary(
        localProgress: List<TrackerProgress>,
        conflictStrategy: ConflictStrategy = ConflictStrategy.LOCAL_WINS,
    ): Result<SyncResult>

    suspend fun testConnection(): Result<Boolean>

    fun getAuthUrl(): String?

    fun supportsFeature(feature: TrackerFeature): Boolean

    enum class TrackerFeature {
        SEARCH,
        DETAILS,
        PROGRESS_UPDATE,
        PROGRESS_READ,
        FULL_SYNC,
        OAUTH,
        WEBHOOKS,
    }

    enum class ConflictStrategy {
        LOCAL_WINS,
        REMOTE_WINS,
        NEWEST_WINS,
        HIGHEST_PROGRESS_WINS,
        MANUAL,
    }

    @KxSerializable
    data class SyncResult(
        val syncedCount: Int,
        val conflictCount: Int,
        val errorCount: Int,
        val conflicts: List<SyncConflict> = emptyList(),
        val errors: List<SyncError> = emptyList(),
    )

    @KxSerializable
    data class SyncConflict(
        val mangaId: Long,
        val remoteId: Long,
        val localProgress: TrackerProgress,
        val remoteProgress: TrackerProgress,
        val resolution: ConflictResolution? = null,
    )

    @KxSerializable
    data class SyncError(
        val mangaId: Long,
        val remoteId: Long,
        val error: String,
        val localProgress: TrackerProgress? = null,
    )

    @KxSerializable
    enum class ConflictResolution {
        LOCAL,
        REMOTE,
        MERGED,
        SKIPPED,
    }
}