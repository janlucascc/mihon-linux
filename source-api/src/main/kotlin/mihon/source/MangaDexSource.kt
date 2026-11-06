package mihon.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Built-in source using the public MangaDex REST API v5.
 * Searches manga, fetches details and chapters (preferring pt-br, pt, en), and resolves page URLs.
 */
class MangaDexSource(
    override val language: String = "pt-br",
    private val client: OkHttpClient = defaultClient(),
) : ExtensionSource {

    override val name: String = "MangaDex ($language)"
    override val baseUrl: String = "https://api.mangadex.org"
    override val id: Long = 2499283573021220255L + language.hashCode()
    override val isNsfw: Boolean = false

    private val json = Json { ignoreUnknownKeys = true }

    override fun searchManga(query: String): List<MangaResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/manga?title=$encoded&limit=25&includes[]=cover_art&includes[]=author&contentRating[]=safe&contentRating[]=suggestive"
        return parseMangaData(url)
    }

    override fun getPopularManga(): List<MangaResult> {
        val url = "$baseUrl/manga?limit=24&order[followedCount]=desc&includes[]=cover_art&includes[]=author&contentRating[]=safe&contentRating[]=suggestive"
        return parseMangaData(url)
    }

    private fun parseMangaData(url: String): List<MangaResult> {
        val root = json.parseToJsonElement(get(url)).jsonObject
        val data = root["data"]?.jsonArray ?: return emptyList()

        return data.mapNotNull { elem ->
            val obj = elem.jsonObject
            val mangaId = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val attrs = obj["attributes"]?.jsonObject ?: return@mapNotNull null
            val titleObj = attrs["title"]?.jsonObject
            val title = titleObj?.get("pt-br")?.jsonPrimitive?.content
                ?: titleObj?.get("en")?.jsonPrimitive?.content
                ?: titleObj?.values?.firstOrNull()?.jsonPrimitive?.content
                ?: return@mapNotNull null

            val rels = obj["relationships"]?.jsonArray.orEmpty()
            var coverFile: String? = null
            var authorName: String? = null
            for (rel in rels) {
                val rObj = rel.jsonObject
                val rType = rObj["type"]?.jsonPrimitive?.content
                val rAttrs = rObj["attributes"]?.jsonObject ?: continue
                if (rType == "cover_art") {
                    coverFile = rAttrs["fileName"]?.jsonPrimitive?.content
                } else if (rType == "author" && authorName == null) {
                    authorName = rAttrs["name"]?.jsonPrimitive?.content
                }
            }
            val coverUrl = coverFile?.let { "https://uploads.mangadex.org/covers/$mangaId/$it.256.jpg" }
            val lastCh = attrs["lastChapter"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }

            MangaResult(
                title = title,
                url = mangaId,
                coverUrl = coverUrl,
                author = authorName,
                latestChapter = lastCh?.let { "Cap. $it" },
            )
        }
    }

    override fun getMangaDetails(url: String): MangaDetails {
        val mangaId = url.substringAfterLast('/').trim()
        val detailUrl = "$baseUrl/manga/$mangaId?includes[]=cover_art&includes[]=author&includes[]=artist"
        val obj = json.parseToJsonElement(get(detailUrl)).jsonObject["data"]?.jsonObject
            ?: throw IOException("Mangá não encontrado: $mangaId")
        val attrs = obj["attributes"]?.jsonObject ?: throw IOException("Atributos ausentes")
        val titleObj = attrs["title"]?.jsonObject
        val title = titleObj?.get("pt-br")?.jsonPrimitive?.content
            ?: titleObj?.get("en")?.jsonPrimitive?.content
            ?: titleObj?.values?.firstOrNull()?.jsonPrimitive?.content
            ?: mangaId

        val descObj = attrs["description"]?.jsonObject
        val description = descObj?.get("pt-br")?.jsonPrimitive?.content
            ?: descObj?.get("en")?.jsonPrimitive?.content
            ?: descObj?.values?.firstOrNull()?.jsonPrimitive?.content

        var coverFile: String? = null
        var authorName: String? = null
        var artistName: String? = null
        obj["relationships"]?.jsonArray.orEmpty().forEach { rel ->
            val rObj = rel.jsonObject
            val rType = rObj["type"]?.jsonPrimitive?.content
            val rAttrs = rObj["attributes"]?.jsonObject ?: return@forEach
            when (rType) {
                "cover_art" -> coverFile = rAttrs["fileName"]?.jsonPrimitive?.content
                "author" -> if (authorName == null) authorName = rAttrs["name"]?.jsonPrimitive?.content
                "artist" -> if (artistName == null) artistName = rAttrs["name"]?.jsonPrimitive?.content
            }
        }
        val coverUrl = coverFile?.let { "https://uploads.mangadex.org/covers/$mangaId/$it.256.jpg" }

        // Fetch chapters feed (preferring target language, fallback to en)
        val chapters = fetchChapters(mangaId, listOf(language, "pt", "en").distinct())

        return MangaDetails(
            title = title,
            author = authorName,
            artist = artistName,
            description = description,
            coverUrl = coverUrl,
            chapters = chapters,
        )
    }

    private fun fetchChapters(mangaId: String, langs: List<String>): List<ChapterResult> {
        val langQuery = langs.joinToString("&") { "translatedLanguage[]=$it" }
        val allChapters = mutableListOf<ChapterResult>()
        val limit = 100
        var offset = 0
        var hasMore = true

        while (hasMore) {
            val feedUrl = "$baseUrl/manga/$mangaId/feed?limit=$limit&offset=$offset&$langQuery&order[chapter]=asc&contentRating[]=safe&contentRating[]=suggestive"
            val root = json.parseToJsonElement(get(feedUrl)).jsonObject
            val data = root["data"]?.jsonArray ?: break
            if (data.isEmpty()) break

            val total = root["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: (offset + data.size)

            val parsed = data.mapNotNull { elem ->
                val obj = elem.jsonObject
                val chId = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val attrs = obj["attributes"]?.jsonObject ?: return@mapNotNull null
                // Skip external chapters that have no hosted pages on MangaDex
                val externalUrl = attrs["externalUrl"]?.jsonPrimitive?.content
                if (!externalUrl.isNullOrBlank() && externalUrl != "null") return@mapNotNull null

                val chNumStr = attrs["chapter"]?.jsonPrimitive?.content?.takeIf { it != "null" }
                val volStr = attrs["volume"]?.jsonPrimitive?.content?.takeIf { it != "null" }
                val chTitle = attrs["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
                val lang = attrs["translatedLanguage"]?.jsonPrimitive?.content.orEmpty()

                val label = buildString {
                    if (volStr != null) append("Vol. $volStr ")
                    if (chNumStr != null) append("Cap. $chNumStr") else append("Oneshot")
                    if (chTitle != null) append(" - $chTitle")
                    if (lang.isNotEmpty()) append(" [$lang]")
                }
                ChapterResult(
                    title = label,
                    url = chId,
                    volume = volStr?.toIntOrNull(),
                    chapter = chNumStr?.toFloatOrNull(),
                )
            }
            allChapters.addAll(parsed)
            offset += limit
            if (offset >= total || data.size < limit) {
                hasMore = false
            }
        }
        return allChapters
    }

    override fun getChapterImages(url: String): List<String> {
        val chapterId = url.substringAfterLast('/').trim()
        val atHomeUrl = "$baseUrl/at-home/server/$chapterId"
        val root = json.parseToJsonElement(get(atHomeUrl)).jsonObject
        val host = root["baseUrl"]?.jsonPrimitive?.content ?: throw IOException("Servidor de imagens indisponível")
        val chapter = root["chapter"]?.jsonObject ?: throw IOException("Dados do capítulo ausentes")
        val hash = chapter["hash"]?.jsonPrimitive?.content ?: throw IOException("Hash ausente")
        // Prefer data-saver or standard data
        val files = chapter["data"]?.jsonArray?.takeIf { it.isNotEmpty() }
            ?: chapter["dataSaver"]?.jsonArray
            ?: return emptyList()
        val mode = if (chapter["data"]?.jsonArray?.isNotEmpty() == true) "data" else "data-saver"
        return files.mapNotNull { it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() } }
            .map { "$host/$mode/$hash/$it" }
    }

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url.toHttpUrl())
            .header("User-Agent", "MihonLinux/0.2 (Fedora Desktop)")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} em $url")
            return resp.body?.string() ?: throw IOException("Resposta vazia")
        }
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
