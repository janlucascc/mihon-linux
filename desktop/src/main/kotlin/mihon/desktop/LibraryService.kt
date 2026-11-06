package mihon.desktop

import mihon.core.archive.ArchiveReaderFactory
import mihon.core.archive.NaturalOrderComparator
import mihon.source.ExtensionSource
import mihon.source.MangaDetails
import mihon.source.MangaResult
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterRemoteUpdate
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Clock
import kotlin.time.Instant

/** Source id used for manga imported from local files/folders. */
const val LOCAL_SOURCE_ID = 0L

/** Status values stored in [Manga.status] for category filtering. */
object LibraryCategory {
    const val ALL = -1L
    const val READING = 0L
    const val COMPLETED = 2L
    const val PLAN_TO_READ = 6L

    val entries = listOf(
        ALL to "Todos",
        READING to "Lendo",
        COMPLETED to "Completos",
        PLAN_TO_READ to "Quero ler",
    )

    fun labelOf(status: Long): String = entries.firstOrNull { it.first == status }?.second ?: "Lendo"
}

data class HistoryEntry(
    val history: History,
    val manga: Manga,
    val chapter: Chapter,
)

/**
 * Non-UI logic of the desktop app: imports local/online manga into the database,
 * downloads remote chapters as `.cbz`, manages categories, and tracks reading history.
 */
class LibraryService(
    private val mangas: MangaRepository,
    private val chapters: ChapterRepository,
    private val history: HistoryRepository,
    val downloadsDir: File = File(System.getProperty("user.home"), ".mihon/downloads"),
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    /** Per-chapter lock so foreground downloads and background prefetches never collide on `.part` files. */
    private val chapterLocks = ConcurrentHashMap<Long, Any>()
    private val activeDownloads = ConcurrentHashMap.newKeySet<Long>()

    data class DownloadQueueItem(
        val chapterId: Long,
        val mangaTitle: String,
        val chapterName: String,
        var progress: String = "Iniciando...",
        var isCompleted: Boolean = false,
        var hasError: Boolean = false,
    )

    val downloadQueue = java.util.concurrent.CopyOnWriteArrayList<DownloadQueueItem>()

    fun clearCompletedDownloads() {
        downloadQueue.removeIf { it.isCompleted || it.hasError }
    }

    fun isChapterDownloading(chapterId: Long): Boolean = chapterId in activeDownloads

    suspend fun library(): List<Manga> = mangas.getFavorites().sortedBy { it.title.lowercase() }

    suspend fun chaptersOf(mangaId: Long): List<Chapter> =
        chapters.getChapterByMangaId(mangaId).sortedBy { it.sourceOrder }

    /**
     * Finds the chapter where the user should resume reading for [mangaId]:
     * 1. Most recently read chapter in History (if it wasn't completed yet, or its next chapter if it was completed)
     * 2. Any chapter with `lastPageRead > 0` and `!read`
     * 3. The first unread chapter after the highest read/partially-read chapter
     * 4. The first unread chapter overall
     */
    suspend fun nextChapterToRead(mangaId: Long): Chapter? {
        val list = chaptersOf(mangaId)
        if (list.isEmpty()) return null

        val lastHist = history.getHistoryByMangaId(mangaId)
            .maxByOrNull { it.readAt ?: Instant.fromEpochMilliseconds(0) }
        if (lastHist != null) {
            val histIdx = list.indexOfFirst { it.id == lastHist.chapterId }
            if (histIdx >= 0) {
                val histChap = list[histIdx]
                if (!histChap.read) return histChap
                // If the most recently read chapter was finished, resume on the next chapter after it
                return list.drop(histIdx + 1).firstOrNull { !it.read } ?: list.getOrNull(histIdx + 1) ?: histChap
            }
        }

        list.lastOrNull { !it.read && it.lastPageRead > 0 }?.let { return it }
        val lastTouchedIdx = list.indexOfLast { it.read || it.lastPageRead > 0 }
        if (lastTouchedIdx >= 0 && lastTouchedIdx + 1 < list.size) {
            return list[lastTouchedIdx + 1]
        }
        return list.firstOrNull { !it.read } ?: list.firstOrNull()
    }

    suspend fun unreadCounts(): Map<Long, Int> {
        val favIds = mangas.getFavorites().map { it.id }
        if (favIds.isEmpty()) return emptyMap()
        return chapters.getChaptersByMangaIds(favIds).mapValues { (_, list) -> list.count { !it.read } }
    }

    suspend fun downloadedCounts(): Map<Long, Int> {
        val favs = mangas.getFavorites()
        if (favs.isEmpty()) return emptyMap()
        val byMangaId = favs.associateBy { it.id }
        return chapters.getChaptersByMangaIds(favs.map { it.id }).mapValues { (mangaId, list) ->
            val m = byMangaId[mangaId] ?: return@mapValues 0
            if (m.isLocal) list.size else list.count { isChapterDownloaded(m, it) }
        }
    }

    /**
     * Imports a .cbz/.zip file or a folder as a manga.
     */
    suspend fun addLocal(file: File): Manga {
        require(ArchiveReaderFactory.isSupported(file)) {
            "Formato não suportado: ${file.name} (use .cbz, .zip ou uma pasta de imagens)"
        }
        val path = file.absolutePath
        mangas.getMangaByUrlAndSource(path, LOCAL_SOURCE_ID)?.let { return it }

        val chapterFiles = chapterFilesOf(file)
        chapterFiles.forEach {
            ArchiveReaderFactory.of(it).use { reader ->
                require(reader.pageCount > 0) { "${it.name} não contém imagens" }
            }
        }

        val now = Clock.System.now()
        val id = mangas.insert(
            Manga.create().copy(
                source = LOCAL_SOURCE_ID,
                url = path,
                title = if (file.isDirectory) file.name else file.nameWithoutExtension,
                favorite = true,
                favoriteAt = now,
                dateAdded = now,
                initialized = true,
            ),
        )
        chapters.updateFromRemote(
            removedIds = emptyList(),
            added = chapterFiles.mapIndexed { index, chapterFile ->
                Chapter.create().copy(
                    mangaId = id,
                    url = chapterFile.absolutePath,
                    name = if (chapterFile.isDirectory) chapterFile.name else chapterFile.nameWithoutExtension,
                    chapterNumber = (index + 1).toDouble(),
                    sourceOrder = index.toLong(),
                    dateFetch = now,
                )
            },
            updated = emptyList(),
        )
        return mangas.getMangaById(id)!!
    }

    /**
     * Adds or updates a manga from an [ExtensionSource] into the library, fetching its details and chapters.
     * If the manga is already in the library and initialized with chapters, returns it immediately without
     * making any network requests.
     */
    suspend fun addOnlineManga(source: ExtensionSource, item: MangaResult, forceRefresh: Boolean = false): Manga {
        val existing = mangas.getMangaByUrlAndSource(item.url, source.id)
            ?: mangas.getFavorites().firstOrNull { it.source == source.id && it.title.equals(item.title, ignoreCase = true) }
        if (existing != null && !forceRefresh) {
            val localChaps = chapters.getChapterByMangaId(existing.id)
            if (localChaps.isNotEmpty()) return existing
        }

        val details: MangaDetails = source.getMangaDetails(item.url)
        val now = Clock.System.now()

        val mangaId = if (existing != null) {
            mangas.update(
                existing.copy(
                    title = details.title.ifBlank { item.title },
                    author = details.author ?: item.author,
                    artist = details.artist,
                    description = details.description,
                    thumbnailUrl = details.coverUrl ?: item.coverUrl,
                    favorite = true,
                    favoriteAt = existing.favoriteAt ?: now,
                    lastUpdate = now,
                    initialized = true,
                ),
            )
            existing.id
        } else {
            mangas.insert(
                Manga.create().copy(
                    source = source.id,
                    url = item.url,
                    title = details.title.ifBlank { item.title },
                    author = details.author ?: item.author,
                    artist = details.artist,
                    description = details.description,
                    thumbnailUrl = details.coverUrl ?: item.coverUrl,
                    favorite = true,
                    favoriteAt = now,
                    dateAdded = now,
                    lastUpdate = now,
                    initialized = true,
                ),
            )
        }

        syncRemoteChapters(mangaId, details, now)
        return mangas.getMangaById(mangaId)!!
    }

    /** Refreshes chapter list from the remote source without losing local read/downloaded state. */
    suspend fun refreshOnlineChapters(manga: Manga, source: ExtensionSource): List<Chapter> {
        val details = source.getMangaDetails(manga.url)
        val now = Clock.System.now()
        syncRemoteChapters(manga.id, details, now)
        return chaptersOf(manga.id)
    }

    private suspend fun syncRemoteChapters(mangaId: Long, details: MangaDetails, now: Instant) {
        val existingByUrl = chapters.getChapterByMangaId(mangaId).associateBy { it.url }
        val newChapters = mutableListOf<Chapter>()
        val updatedChapters = mutableListOf<ChapterRemoteUpdate>()

        details.chapters.forEachIndexed { index, ch ->
            val order = index.toLong()
            val num = ch.chapter?.toDouble() ?: (index + 1).toDouble()
            val existing = existingByUrl[ch.url]
            if (existing == null) {
                newChapters.add(
                    Chapter.create().copy(
                        mangaId = mangaId,
                        url = ch.url,
                        name = ch.title,
                        chapterNumber = num,
                        sourceOrder = order,
                        dateFetch = now,
                    ),
                )
            } else if (existing.sourceOrder != order || existing.name != ch.title || existing.chapterNumber != num) {
                updatedChapters.add(
                    ChapterRemoteUpdate(
                        id = existing.id,
                        name = ch.title,
                        scanlator = existing.scanlator,
                        chapterNumber = num,
                        dateUpload = existing.dateUpload,
                        sourceOrder = order,
                        memo = existing.memo,
                    ),
                )
            }
        }
        if (newChapters.isNotEmpty() || updatedChapters.isNotEmpty()) {
            chapters.updateFromRemote(removedIds = emptyList(), added = newChapters, updated = updatedChapters)
        }
    }

    /**
     * Returns the local `.cbz` file where a downloaded chapter is (or will be) stored.
     * Also matches existing `.cbz` files by chapter name suffix if chapter IDs changed across re-imports.
     */
    fun chapterCbzFile(manga: Manga, chapter: Chapter): File {
        if (manga.isLocal) return File(chapter.url)
        val current = File(chapter.url)
        if (current.isAbsolute && current.exists()) return current
        val safeManga = sanitizeFileName(manga.title)
        val mangaDir = File(downloadsDir, safeManga)
        val safeNameOnly = sanitizeFileName(chapter.name)
        val canonical = File(mangaDir, "${ sanitizeFileName("${chapter.id}_${chapter.name}") }.cbz")
        if (canonical.exists() && canonical.length() > 0) return canonical
        // Fallback: if the chapter was already downloaded under a previous DB id, reuse that .cbz file
        val existingMatch = mangaDir.listFiles { f ->
            f.isFile && f.extension.equals("cbz", ignoreCase = true) && f.length() > 0 &&
                (f.nameWithoutExtension.equals(safeNameOnly, ignoreCase = true) ||
                    f.nameWithoutExtension.endsWith("_$safeNameOnly", ignoreCase = true))
        }?.firstOrNull()
        return existingMatch ?: canonical
    }

    fun isChapterDownloaded(manga: Manga, chapter: Chapter): Boolean =
        manga.isLocal || chapterCbzFile(manga, chapter).let { it.exists() && it.length() > 0 }

    /**
     * Downloads all page images of [chapter] from [source] into a `.cbz` archive atomically.
     * Thread-safe and deduplicated per chapter ID.
     */
    fun downloadChapter(
        manga: Manga,
        chapter: Chapter,
        source: ExtensionSource,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): File {
        val target = chapterCbzFile(manga, chapter)
        if (target.exists() && target.length() > 0) return target
        val lock = chapterLocks.computeIfAbsent(chapter.id) { Any() }
        synchronized(lock) {
            if (target.exists() && target.length() > 0) return target
            activeDownloads.add(chapter.id)
            target.parentFile?.mkdirs()

            val queueItem = DownloadQueueItem(chapter.id, manga.title, chapter.name, "Conectando...")
            downloadQueue.add(queueItem)

            val imageUrls = source.getChapterImages(chapter.url)
            require(imageUrls.isNotEmpty()) { "Nenhuma imagem encontrada para o capítulo '${chapter.name}'" }

            val partFile = File(target.parentFile, "${target.name}.part")
            try {
                ZipOutputStream(partFile.outputStream().buffered()).use { zip ->
                    imageUrls.forEachIndexed { idx, imgUrl ->
                        onProgress(idx + 1, imageUrls.size)
                        queueItem.progress = "${idx + 1} / ${imageUrls.size} págs"
                        val cleanImgUrl = imgUrl.trim().replace(" ", "%20")
                        val ext = cleanImgUrl.substringBefore('?').substringAfterLast('.', "jpg")
                            .lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif") } ?: "jpg"
                        val req = Request.Builder()
                            .url(cleanImgUrl.toHttpUrl())
                            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                            .header("Referer", "${source.baseUrl}/")
                            .build()
                        val bytes = httpClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) throw IOException("Falha HTTP ${resp.code} na página ${idx + 1}")
                            resp.body?.bytes() ?: throw IOException("Página ${idx + 1} vazia")
                        }
                        zip.putNextEntry(ZipEntry("%04d.%s".format(idx + 1, ext)))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
                if (target.exists()) target.delete()
                if (!partFile.renameTo(target)) {
                    partFile.copyTo(target, overwrite = true)
                    partFile.delete()
                }
                queueItem.progress = "Concluído"
                queueItem.isCompleted = true
                return target
            } catch (e: Exception) {
                partFile.delete()
                queueItem.progress = "Falha: ${e.message}"
                queueItem.hasError = true
                throw e
            } finally {
                activeDownloads.remove(chapter.id)
                chapterLocks.remove(chapter.id, lock)
            }
        }
    }

    /**
     * Silently pre-downloads upcoming chapters into `.cbz` cache so switching to the next chapter is instant.
     */
    fun prefetchChapters(
        manga: Manga,
        upcoming: List<Chapter>,
        source: ExtensionSource,
        onChapterCached: (Chapter) -> Unit = {},
    ) {
        if (manga.isLocal || upcoming.isEmpty()) return
        for (ch in upcoming) {
            if (isChapterDownloaded(manga, ch) || isChapterDownloading(ch.id)) continue
            runCatching {
                downloadChapter(manga, ch, source)
                onChapterCached(ch)
            }
        }
    }

    suspend fun removeFromLibrary(mangaId: Long) = mangas.delete(mangaId)

    suspend fun setCategory(mangaId: Long, categoryStatus: Long) {
        val current = mangas.getMangaById(mangaId) ?: return
        mangas.update(current.copy(status = categoryStatus))
    }

    suspend fun markChaptersRead(chaptersToUpdate: List<Chapter>, readState: Boolean) {
        if (chaptersToUpdate.isEmpty()) return
        chapters.updateAll(
            chaptersToUpdate.map { ch ->
                ChapterUpdate(ch.id) {
                    read = readState
                    if (!readState) lastPageRead = 0
                }
            },
        )
    }

    /** Records reading progress; marks the chapter as read when the last page was reached. */
    suspend fun saveProgress(chapter: Chapter, page: Int, pageCount: Int, sessionMillis: Long = 0, incognitoMode: Boolean = false) {
        chapters.update(
            ChapterUpdate(chapter.id) {
                lastPageRead = page
                if (pageCount > 0 && page >= pageCount - 1) read = true
            },
        )
        if (!incognitoMode) {
            history.upsert(HistoryUpdate(chapter.id, Clock.System.now(), sessionMillis))
        }
    }

    suspend fun recentHistory(): List<HistoryEntry> {
        val allHistory = history.getAllHistory().sortedByDescending { it.readAt ?: Instant.fromEpochMilliseconds(0) }
        if (allHistory.isEmpty()) return emptyList()
        val entries = mutableListOf<HistoryEntry>()
        for (h in allHistory) {
            val ch = chapters.getChapterById(h.chapterId) ?: continue
            val m = mangas.getMangaById(ch.mangaId) ?: continue
            entries.add(HistoryEntry(h, m, ch))
        }
        return entries
    }

    suspend fun clearHistory() {
        history.deleteAll()
    }

    suspend fun deleteHistoryEntry(chapterId: Long) {
        history.deleteByChapterId(chapterId)
    }

    suspend fun toggleFavorite(mangaId: Long): Boolean {
        val current = mangas.getMangaById(mangaId) ?: return false
        val newFav = !current.favorite
        mangas.update(current.copy(favorite = newFav, favoriteAt = if (newFav) Clock.System.now() else null))
        return newFav
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(100).ifBlank { "item" }

    private fun chapterFilesOf(file: File): List<File> {
        if (!file.isDirectory) return listOf(file)
        val archives = file.listFiles { f ->
            f.isFile && f.extension.lowercase() in ArchiveReaderFactory.SUPPORTED_EXTENSIONS
        }.orEmpty()
        if (archives.isEmpty()) return listOf(file)
        return archives.sortedWith { a, b -> NaturalOrderComparator.compare(a.name, b.name) }
    }

    data class ReadingStats(
        val totalManga: Int,
        val totalChaptersRead: Int,
        val totalChaptersDownloaded: Int,
        val historyCount: Int,
        val totalReadingTimeMs: Long,
    )

    suspend fun getReadingStatistics(): ReadingStats {
        val favs = mangas.getFavorites()
        val totalManga = favs.size
        var readChapters = 0
        var downloadedChapters = 0
        for (m in favs) {
            val chaps = chapters.getChapterByMangaId(m.id)
            readChapters += chaps.count { it.read }
            downloadedChapters += chaps.count { isChapterDownloaded(m, it) }
        }
        val hist = recentHistory()
        val totalDuration = hist.sumOf { it.history.readDuration }
        return ReadingStats(
            totalManga = totalManga,
            totalChaptersRead = readChapters,
            totalChaptersDownloaded = downloadedChapters,
            historyCount = hist.size,
            totalReadingTimeMs = totalDuration,
        )
    }
}
