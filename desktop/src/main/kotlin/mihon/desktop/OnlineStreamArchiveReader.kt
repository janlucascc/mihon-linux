package mihon.desktop

import mihon.core.archive.ArchivePage
import mihon.core.archive.ArchiveReader
import mihon.core.archive.ImageDecoder
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * An [ArchiveReader] implementation that streams manga pages over HTTP on demand.
 * Pages are cached temporarily in a memory LRU cache and temporary disk file
 * to avoid downloading the entire chapter upfront.
 */
class OnlineStreamArchiveReader(
    override val sourceName: String,
    val imageUrls: List<String>,
    private val client: OkHttpClient,
    private val referer: String,
    private val userAgent: String = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    private val cookie: String? = null,
) : ArchiveReader {

    @Volatile
    private var isClosed = false

    override val pages: List<ArchivePage> = imageUrls.mapIndexed { index, url ->
        val filename = "%03d".format(index + 1) + "." + (url.substringBefore('?').substringAfterLast('.', "jpg").takeIf { it.length in 3..4 } ?: "jpg")
        ArchivePage(
            index = index,
            name = filename,
            path = filename,
            size = -1L,
        )
    }

    private val diskCacheDir = File(System.getProperty("java.io.tmpdir"), "mihon_stream_cache/${sourceName.hashCode()}").apply { mkdirs() }
    private val memoryLock = Any()
    private val memoryCache = object : LinkedHashMap<Int, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>): Boolean = size > 24
    }

    override fun getPageStream(page: ArchivePage): InputStream {
        check(!isClosed) { "Reader is closed" }
        val bytes = getPageBytes(page)
        return ByteArrayInputStream(bytes)
    }

    override fun getPageBytes(page: ArchivePage): ByteArray {
        check(!isClosed) { "Reader is closed" }
        val index = page.index

        // 1. Check memory cache
        synchronized(memoryLock) { memoryCache[index] }?.let { return it }

        // 2. Check disk cache
        val cacheFile = File(diskCacheDir, "p_${index}.bin")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            val bytes = cacheFile.readBytes()
            synchronized(memoryLock) { memoryCache[index] = bytes }
            return bytes
        }

        // 3. Download page on-demand
        val url = imageUrls.getOrNull(index) ?: throw NoSuchElementException("Page index $index out of bounds")
        val cleanUrl = url.trim().replace(" ", "%20")

        val reqBuilder = Request.Builder()
            .url(cleanUrl.toHttpUrl())
            .header("User-Agent", userAgent)
            .header("Referer", referer)

        if (!cookie.isNullOrBlank()) {
            reqBuilder.header("Cookie", cookie)
        }

        val downloadedBytes = client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} ao baixar página $index de $cleanUrl")
            resp.body?.bytes() ?: throw java.io.IOException("Resposta vazia da página $index")
        }

        synchronized(memoryLock) { memoryCache[index] = downloadedBytes }
        runCatching { cacheFile.writeBytes(downloadedBytes) }
        return downloadedBytes
    }

    override fun getPageImage(page: ArchivePage): BufferedImage? {
        val bytes = getPageBytes(page)
        return ImageDecoder.decode(bytes)
    }

    override fun close() {
        isClosed = true
        synchronized(memoryLock) { memoryCache.clear() }
        runCatching { diskCacheDir.deleteRecursively() }
    }
}
