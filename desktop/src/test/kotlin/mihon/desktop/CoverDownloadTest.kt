package mihon.desktop

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import mihon.core.archive.ImageDecoder
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull

class CoverDownloadTest {
    @Test
    fun testDownload() {
        val url = "https://uploads.mangadex.org/covers/32d76d19-8a05-4db0-9fc2-e0b0648fe9d0/e90bdc47-c8b9-4df7-b2c0-17641b645ee1.jpg.256.jpg"
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val parsed = url.toHttpUrl()
        val reqBuilder = Request.Builder()
            .url(parsed)
            .header("User-Agent", "MihonLinux/0.3 (Linux Desktop)")
            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")

        if (parsed.host.contains("mangadex.org")) {
            reqBuilder.header("Referer", "https://mangadex.org/")
        }

        println("Sending request to $url")
        val img = httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            println("Response code: ${resp.code}")
            val bytes = resp.body?.bytes()
            println("Bytes length: ${bytes?.size}")
            if (bytes != null) ImageDecoder.decode(bytes) else null
        }
        println("Decoded image: $img")
        assertNotNull(img)

        // Test Manga Livre WebP Icon
        val mlUrl = "https://mangalivre.to/wp-content/uploads/2025/07/cropped-manga-livre-192x192.webp"
        val mlReq = Request.Builder()
            .url(mlUrl.toHttpUrl())
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .build()
        val mlImg = httpClient.newCall(mlReq).execute().use { resp ->
            println("ML Icon response code: ${resp.code}")
            val b = resp.body?.bytes()
            println("ML Icon bytes: ${b?.size}")
            if (b != null) ImageDecoder.decode(b) else null
        }
        println("ML Decoded: $mlImg")
        assertNotNull(mlImg)
    }
}
