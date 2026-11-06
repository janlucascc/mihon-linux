package eu.kanade.tachiyomi.network

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import mihon.core.metro.AppCoroutineScope
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.time.Duration

@Inject
@SingleIn(AppScope::class)
class NetworkHelper(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val cacheDir: File,
    private val preferences: NetworkPreferences,
) {

    val cookieJar = MemoryCookieJar()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofMinutes(2))
            .cache(
                Cache(
                    directory = File(cacheDir, "network_cache"),
                    maxSize = 5L * 1024 * 1024,
                ),
            )
            .build()
    }
}
