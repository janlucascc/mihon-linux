package mihon.data.database.tables

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption

object MangaTable : LongIdTable("manga") {
    // Note: Property named 'sourceId' maps to SQLite column 'source' to avoid
    // collision with Exposed ColumnSet.source property.
    val sourceId = long("source").default(0L)
    val url = text("url")
    val title = text("title")
    val artist = text("artist").nullable()
    val author = text("author").nullable()
    val description = text("description").nullable()
    val genre = text("genre").nullable()
    val status = long("status").default(0L)
    val thumbnailUrl = text("thumbnail_url").nullable()
    val updateStrategy = enumerationByName<UpdateStrategy>("update_strategy", 32).default(UpdateStrategy.ALWAYS_UPDATE)
    val initialized = bool("initialized").default(false)
    val favorite = bool("favorite").default(false)
    val lastUpdate = long("last_update").default(0L)
    val nextUpdate = long("next_update").default(0L)
    val fetchInterval = integer("fetch_interval").default(0)
    val dateAdded = long("date_added").default(0L)
    val viewerFlags = long("viewer_flags").default(0L)
    val chapterFlags = long("chapter_flags").default(0L)
    val coverLastModified = long("cover_last_modified").default(0L)
    val notes = text("notes").nullable()
    val memo = text("memo").nullable()
    val favoriteAt = long("favorite_at").nullable()

    init {
        index(isUnique = false, sourceId, url)
        index(isUnique = false, favorite)
    }
}

object ChapterTable : LongIdTable("chapter") {
    val mangaId = reference("manga_id", MangaTable, onDelete = ReferenceOption.CASCADE)
    val url = text("url")
    val name = text("name")
    val scanlator = text("scanlator").nullable()
    val read = bool("read").default(false)
    val bookmark = bool("bookmark").default(false)
    val lastPageRead = integer("last_page_read").default(0)
    val chapterNumber = double("chapter_number").default(-1.0)
    val sourceOrder = long("source_order").default(0L)
    val dateFetch = long("date_fetch").default(0L)
    val dateUpload = long("date_upload").nullable()
    val memo = text("memo").nullable()

    init {
        index(isUnique = false, mangaId)
        index(isUnique = false, mangaId, url)
    }
}

object HistoryTable : LongIdTable("history") {
    val chapterId = reference("chapter_id", ChapterTable, onDelete = ReferenceOption.CASCADE)
    val readAt = long("read_at").nullable()
    val readDuration = long("read_duration").default(0L)

    init {
        index(isUnique = false, chapterId)
        index(isUnique = false, readAt)
    }
}
