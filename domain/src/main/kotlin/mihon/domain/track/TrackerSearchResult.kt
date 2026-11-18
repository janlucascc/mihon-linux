package mihon.domain.track

import kotlinx.serialization.Serializable

@Serializable
data class TrackerSearchResult(
    val trackerType: TrackerType,
    val remoteId: Long,
    val title: String,
    val alternativeTitles: List<String> = emptyList(),
    val coverUrl: String? = null,
    val totalChapters: Int = 0,
    val totalVolumes: Int = 0,
    val status: String = "",
    val year: Int? = null,
    val description: String = "",
    val averageScore: Double = 0.0,
    val genres: List<String> = emptyList(),
    val url: String = "",
) {
    val displayTitle: String
        get() = title.takeIf { it.isNotBlank() } ?: alternativeTitles.firstOrNull { it.isNotBlank() } ?: "Unknown #$remoteId"

    override fun toString(): String {
        val chInfo = if (totalChapters > 0) "$totalChapters caps" else if (totalVolumes > 0) "$totalVolumes vols" else "Em lançamento"
        val statusInfo = status.takeIf { it.isNotBlank() } ?: "Desconhecido"
        return "$displayTitle  [$chInfo • $statusInfo] (#$remoteId)"
    }
}

@Serializable
data class TrackerMangaDetails(
    val trackerType: TrackerType,
    val remoteId: Long,
    val title: String,
    val alternativeTitles: List<String> = emptyList(),
    val coverUrl: String? = null,
    val bannerUrl: String? = null,
    val totalChapters: Int = 0,
    val totalVolumes: Int = 0,
    val status: String = "",
    val year: Int? = null,
    val season: String = "",
    val description: String = "",
    val averageScore: Double = 0.0,
    val genres: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val url: String = "",
    val relatedManga: List<TrackerSearchResult> = emptyList(),
    val characters: List<TrackerCharacter> = emptyList(),
) {
    val displayTitle: String
        get() = title.takeIf { it.isNotBlank() } ?: alternativeTitles.firstOrNull { it.isNotBlank() } ?: "Unknown #$remoteId"
}

@Serializable
data class TrackerCharacter(
    val name: String,
    val role: String,
    val imageUrl: String? = null,
    val description: String = "",
)