package mihon.domain.track

import kotlinx.serialization.Serializable as KxSerializable

@KxSerializable
enum class TrackerStatus(val anilistValue: String, val malValue: String, val kitsuValue: String, val shikimoriValue: String, val bangumiValue: String) {
    READING("CURRENT", "reading", "current", "reading", "1"),
    PLANNING("PLANNING", "plan_to_read", "planned", "planned", "2"),
    COMPLETED("COMPLETED", "completed", "completed", "completed", "3"),
    PAUSED("PAUSED", "on_hold", "on_hold", "on_hold", "4"),
    DROPPED("DROPPED", "dropped", "dropped", "dropped", "5"),
    REPEATING("REPEATING", "rereading", "reconsuming", "reconsuming", "6"),
}

@KxSerializable
data class TrackerProgress(
    val mangaId: Long,
    val trackerType: TrackerType,
    val remoteId: Long = 0,
    val remoteTitle: String = "",
    val status: TrackerStatus = TrackerStatus.READING,
    val lastChapterRead: Int = 0,
    val lastVolumeRead: Int = 0,
    val totalChapters: Int = 0,
    val totalVolumes: Int = 0,
    val score: Double = 0.0,
    val startDate: Long? = null,
    val finishDate: Long? = null,
    val isPrivate: Boolean = false,
    val notes: String = "",
    val lastSyncTimestamp: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.PENDING,
) {
    enum class SyncStatus {
        SYNC_OK,
        CONFLICT,
        ERROR,
        PENDING,
        NEVER_SYNCED,
    }

    fun copyWith(
        status: TrackerStatus? = null,
        lastChapterRead: Int? = null,
        lastVolumeRead: Int? = null,
        totalChapters: Int? = null,
        totalVolumes: Int? = null,
        score: Double? = null,
        finishDate: Long? = null,
        isPrivate: Boolean? = null,
        notes: String? = null,
        syncStatus: SyncStatus? = null,
    ): TrackerProgress = copy(
        status = status ?: this.status,
        lastChapterRead = lastChapterRead ?: this.lastChapterRead,
        lastVolumeRead = lastVolumeRead ?: this.lastVolumeRead,
        totalChapters = totalChapters ?: this.totalChapters,
        totalVolumes = totalVolumes ?: this.totalVolumes,
        score = score ?: this.score,
        finishDate = finishDate ?: this.finishDate,
        isPrivate = isPrivate ?: this.isPrivate,
        notes = notes ?: this.notes,
        syncStatus = syncStatus ?: this.syncStatus,
        lastSyncTimestamp = System.currentTimeMillis(),
    )
}