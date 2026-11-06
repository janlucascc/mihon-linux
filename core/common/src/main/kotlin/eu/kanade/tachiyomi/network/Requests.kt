@file:Suppress("FunctionName")

package eu.kanade.tachiyomi.network

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody

private val DEFAULT_HEADERS = Headers.Builder().build()

fun GET(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
): Request {
    return GET(url.toHttpUrl(), headers)
}

fun GET(
    url: HttpUrl,
    headers: Headers = DEFAULT_HEADERS,
): Request {
    return Request.Builder()
        .url(url)
        .headers(headers)
        .build()
}

fun POST(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody,
): Request {
    return Request.Builder()
        .url(url)
        .post(body)
        .headers(headers)
        .build()
}

fun PUT(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody,
): Request {
    return Request.Builder()
        .url(url)
        .put(body)
        .headers(headers)
        .build()
}

fun DELETE(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody,
): Request {
    return Request.Builder()
        .url(url)
        .delete(body)
        .headers(headers)
        .build()
}
