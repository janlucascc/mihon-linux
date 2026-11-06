package tachiyomi.domain.manga.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaRemoteUpdate
import tachiyomi.domain.manga.model.MangaUpdate

interface MangaRepository {

    suspend fun getMangaById(id: Long): Manga?

    fun getMangaByIdAsFlow(id: Long): Flow<Manga?>

    suspend fun getMangaByUrlAndSource(url: String, source: Long): Manga?

    fun getMangaByUrlAndSourceAsFlow(url: String, source: Long): Flow<Manga?>

    suspend fun getFavorites(): List<Manga>

    fun getFavoritesAsFlow(): Flow<List<Manga>>

    suspend fun getAll(): List<Manga>

    fun getAllAsFlow(): Flow<List<Manga>>

    suspend fun getMangasByIds(ids: List<Long>): List<Manga>

    fun getMangasByIdsAsFlow(ids: List<Long>): Flow<List<Manga>>

    suspend fun insert(manga: Manga): Long

    suspend fun insertAll(mangas: List<Manga>): List<Long>

    suspend fun update(manga: Manga)

    suspend fun update(mangaUpdate: MangaUpdate)

    suspend fun update(mangaRemoteUpdate: MangaRemoteUpdate)

    suspend fun updateAll(mangaUpdates: List<MangaUpdate>)

    suspend fun delete(id: Long)

    suspend fun deleteAll(): Boolean

    suspend fun resetViewerFlags(): Unit
}
