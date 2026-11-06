package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

class MemoryCookieJar : CookieJar {

    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val urlString = url.toString()
        cookieStore[urlString] = cookies.toMutableList()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return cookieStore[url.toString()] ?: emptyList()
    }
}
