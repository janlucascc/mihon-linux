package tachiyomi.domain.history.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate

interface HistoryRepository {

    suspend fun getHistoryById(id: Long): History?

    suspend fun getHistoryByChapterId(chapterId: Long): History?

    fun getHistoryByChapterIdAsFlow(chapterId: Long): Flow<History?>

    suspend fun getHistoryByMangaId(mangaId: Long): List<History>

    fun getHistoryByMangaIdAsFlow(mangaId: Long): Flow<List<History>>

    suspend fun getAllHistory(): List<History>

    fun getAllHistoryAsFlow(): Flow<List<History>>

    suspend fun upsert(historyUpdate: HistoryUpdate): History

    suspend fun upsert(history: History): History

    suspend fun delete(id: Long)

    suspend fun deleteByChapterId(chapterId: Long)

    suspend fun deleteByMangaId(mangaId: Long)

    suspend fun deleteAll(): Boolean
}
