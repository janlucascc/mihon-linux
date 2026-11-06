package tachiyomi.domain.manga.model

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonObject
import mihon.core.common.extensions.EMPTY
import java.io.Serializable
import kotlin.time.Instant

data class Manga(
    val id: Long,
    val source: Long,
    val favorite: Boolean = false,
    val lastUpdate: Instant = Instant.fromEpochMilliseconds(0),
    val nextUpdate: Instant = Instant.fromEpochMilliseconds(0),
    val fetchInterval: Int = 0,
    val dateAdded: Instant = Instant.fromEpochMilliseconds(0),
    val viewerFlags: Long = 0L,
    val chapterFlags: Long = 0L,
    val coverLastModified: Instant = Instant.fromEpochMilliseconds(0),
    val url: String,
    val title: String,
    val artist: String? = null,
    val author: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val status: Long = 0L,
    val thumbnailUrl: String? = null,
    val updateStrategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE,
    val initialized: Boolean = false,
    val notes: String? = null,
    val memo: JsonObject = JsonObject.EMPTY,
    val favoriteAt: Instant? = null,
) : Serializable {

    val genres: List<String>
        get() = genre.orEmpty()

    val isLocal: Boolean
        get() = source == 0L

    fun copyFrom(other: MangaRemoteUpdate): Manga {
        return copy(
            title = other.title ?: title,
            author = other.author ?: author,
            artist = other.artist ?: artist,
            description = other.description ?: description,
            genre = other.genre ?: genre,
            status = other.status,
            thumbnailUrl = other.thumbnailUrl ?: thumbnailUrl,
            updateStrategy = other.updateStrategy,
            memo = other.memo,
            initialized = initialized || other.initialized,
            coverLastModified = other.coverLastModified ?: coverLastModified,
        )
    }

    fun copyFrom(other: Manga): Manga {
        return copy(
            title = other.title.ifBlank { title },
            author = other.author ?: author,
            artist = other.artist ?: artist,
            description = other.description ?: description,
            genre = other.genre ?: genre,
            thumbnailUrl = other.thumbnailUrl ?: thumbnailUrl,
            status = if (other.status != 0L) other.status else status,
            updateStrategy = other.updateStrategy,
            initialized = initialized || other.initialized,
        )
    }

    companion object {
        fun create() = Manga(
            id = -1L,
            source = -1L,
            favorite = false,
            lastUpdate = Instant.fromEpochMilliseconds(0),
            nextUpdate = Instant.fromEpochMilliseconds(0),
            fetchInterval = 0,
            dateAdded = Instant.fromEpochMilliseconds(0),
            viewerFlags = 0L,
            chapterFlags = 0L,
            coverLastModified = Instant.fromEpochMilliseconds(0),
            url = "",
            title = "",
            artist = null,
            author = null,
            description = null,
            genre = null,
            status = 0L,
            thumbnailUrl = null,
            updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
            initialized = false,
            notes = null,
            memo = JsonObject.EMPTY,
            favoriteAt = null,
        )
    }
}
