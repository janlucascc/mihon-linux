package mihon.desktop.translator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface TranslationProvider {
    val id: String
    val displayName: String

    suspend fun translateBatch(
        texts: List<String>,
        sourceLang: TranslatorLanguage,
        targetLang: TranslatorLanguage,
    ): List<String>
}

class TranslationException(
    message: String,
    val providerId: String? = null,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

data class TranslationProviderDescriptor(
    val id: String,
    val displayName: String,
)

object TranslationProviderRegistry {
    const val PROVIDER_GOOGLE_GTX = "google_gtx"
    const val PROVIDER_MYMEMORY = "mymemory"
    const val PROVIDER_LIBRETRANSLATE = "libretranslate"
    const val PROVIDER_COMPOSITE = "composite"

    val availableProviders: List<TranslationProviderDescriptor> = listOf(
        TranslationProviderDescriptor(PROVIDER_GOOGLE_GTX, "Google Tradutor (GTX)"),
        TranslationProviderDescriptor(PROVIDER_MYMEMORY, "MyMemory"),
        TranslationProviderDescriptor(PROVIDER_LIBRETRANSLATE, "LibreTranslate"),
        TranslationProviderDescriptor(PROVIDER_COMPOSITE, "Automático (com fallback)"),
    )

    private val sharedHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun defaultHttpClient(): OkHttpClient = sharedHttpClient

    fun create(
        config: TranslatorConfig,
        httpClient: OkHttpClient = defaultHttpClient(),
    ): TranslationProvider = when (config.providerId.trim().lowercase()) {
        PROVIDER_MYMEMORY -> MyMemoryTranslationProvider(httpClient = httpClient)
        PROVIDER_LIBRETRANSLATE -> LibreTranslateProvider(
            baseUrl = config.libreTranslateUrl,
            apiKey = config.libreTranslateApiKey,
            httpClient = httpClient,
        )
        PROVIDER_COMPOSITE -> createWithFallback(
            config = config.copy(providerId = PROVIDER_GOOGLE_GTX),
            httpClient = httpClient,
        )
        else -> GoogleTranslateGtxProvider(httpClient = httpClient)
    }

    fun createWithFallback(
        config: TranslatorConfig,
        httpClient: OkHttpClient = defaultHttpClient(),
    ): TranslationProvider {
        val normalizedId = config.providerId.trim().lowercase()
        val ordered = mutableListOf<TranslationProvider>()
        val seenIds = hashSetOf<String>()

        fun addUnique(provider: TranslationProvider) {
            if (seenIds.add(provider.id)) {
                ordered.add(provider)
            }
        }

        when (normalizedId) {
            PROVIDER_MYMEMORY -> addUnique(MyMemoryTranslationProvider(httpClient = httpClient))
            PROVIDER_LIBRETRANSLATE -> addUnique(
                LibreTranslateProvider(
                    baseUrl = config.libreTranslateUrl,
                    apiKey = config.libreTranslateApiKey,
                    httpClient = httpClient,
                ),
            )
            else -> addUnique(GoogleTranslateGtxProvider(httpClient = httpClient))
        }

        addUnique(GoogleTranslateGtxProvider(httpClient = httpClient))
        addUnique(MyMemoryTranslationProvider(httpClient = httpClient))

        return if (ordered.size == 1) ordered.first() else CompositeTranslationProvider(ordered)
    }
}

private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

private val JSON_PARSER = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private data class HttpResult(
    val code: Int,
    val isSuccessful: Boolean,
    val body: String,
)

private fun parseHttpUrl(rawUrl: String, providerId: String): HttpUrl {
    val trimmed = rawUrl.trim()
    val parsed = runCatching { trimmed.toHttpUrl() }.getOrElse { e ->
        throw TranslationException(
            message = "Invalid endpoint URL for $providerId: '$rawUrl'",
            providerId = providerId,
            cause = e,
        )
    }
    if (parsed.scheme != "http" && parsed.scheme != "https") {
        throw TranslationException(
            message = "Unsupported URL scheme '${parsed.scheme}' for $providerId (expected http or https)",
            providerId = providerId,
        )
    }
    return parsed
}

private suspend fun OkHttpClient.executeRequest(
    request: Request,
    providerId: String,
): HttpResult = suspendCancellableCoroutine { cont ->
    val call = newCall(request)
    cont.invokeOnCancellation { call.cancel() }

    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWithException(
                TranslationException(
                    message = "Network error on $providerId: ${e.message}",
                    providerId = providerId,
                    cause = e,
                ),
            )
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use { resp ->
                    HttpResult(
                        code = resp.code,
                        isSuccessful = resp.isSuccessful,
                        body = resp.body?.string().orEmpty(),
                    )
                }
                if (!cont.isCancelled) {
                    cont.resume(result)
                }
            } catch (e: IOException) {
                if (!cont.isCancelled) {
                    cont.resumeWithException(
                        TranslationException(
                            message = "I/O error reading response from $providerId: ${e.message}",
                            providerId = providerId,
                            cause = e,
                        ),
                    )
                }
            }
        }
    })
}

class GoogleTranslateGtxProvider(
    private val httpClient: OkHttpClient = TranslationProviderRegistry.defaultHttpClient(),
    private val endpointUrl: String = DEFAULT_ENDPOINT,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val maxBatchChars: Int = 800,
    private val maxBatchItems: Int = 20,
) : TranslationProvider {

    override val id: String = TranslationProviderRegistry.PROVIDER_GOOGLE_GTX
    override val displayName: String = "Google Tradutor (GTX)"

    override suspend fun translateBatch(
        texts: List<String>,
        sourceLang: TranslatorLanguage,
        targetLang: TranslatorLanguage,
    ): List<String> {
        if (texts.isEmpty()) return emptyList()
        if (sourceLang != TranslatorLanguage.AUTO && sourceLang == targetLang) {
            return texts
        }

        val nonBlankIndices = texts.indices.filter { texts[it].isNotBlank() }
        if (nonBlankIndices.isEmpty()) return texts

        val output = texts.toMutableList()
        val sl = sourceLang.gtxCode
        val tl = if (targetLang == TranslatorLanguage.AUTO) TranslatorLanguage.PORTUGUESE_BR.gtxCode else targetLang.gtxCode

        val chunks = chunkIndices(nonBlankIndices, texts)
        for (chunkIndices in chunks) {
            val chunkTexts = chunkIndices.map { texts[it] }
            val translatedChunk = translateChunk(chunkTexts, sl, tl)
            for (i in chunkIndices.indices) {
                output[chunkIndices[i]] = translatedChunk[i]
            }
        }
        return output
    }

    private fun estimateEncodedLength(text: String): Int {
        var total = 0
        for (ch in text) {
            total += when {
                ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~' -> 1
                ch.code < 0x80 -> 3
                ch.code < 0x800 -> 6
                else -> 9
            }
        }
        return total
    }

    private fun chunkIndices(indices: List<Int>, texts: List<String>): List<List<Int>> {
        val chunks = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var currentChars = 0
        val delimCost = estimateEncodedLength(BATCH_DELIMITER) + 6

        for (idx in indices) {
            val itemLen = maxOf(texts[idx].length, estimateEncodedLength(texts[idx]))
            val projected = if (current.isEmpty()) itemLen else currentChars + delimCost + itemLen
            if (current.isNotEmpty() && (current.size >= maxBatchItems || projected > maxBatchChars)) {
                chunks.add(current)
                current = mutableListOf()
                currentChars = 0
            }
            current.add(idx)
            currentChars = if (current.size == 1) itemLen else currentChars + delimCost + itemLen
        }
        if (current.isNotEmpty()) {
            chunks.add(current)
        }
        return chunks
    }

    private suspend fun translateChunk(
        chunkTexts: List<String>,
        sl: String,
        tl: String,
    ): List<String> {
        if (chunkTexts.size == 1 || chunkTexts.any { it.contains(BATCH_DELIMITER_TOKEN) }) {
            return chunkTexts.map { translateSingle(it, sl, tl) }
        }

        val joined = chunkTexts.joinToString("\n$BATCH_DELIMITER\n")
        val rawTranslated = requestTranslation(joined, sl, tl)
        val parts = DELIMITER_SPLIT_REGEX.split(rawTranslated.trim())
        if (parts.size == chunkTexts.size && parts.none { it.isBlank() }) {
            return parts.map { it.trim() }
        }
        return chunkTexts.map { translateSingle(it, sl, tl) }
    }

    private suspend fun translateSingle(text: String, sl: String, tl: String): String =
        requestTranslation(text, sl, tl).trim()

    private suspend fun requestTranslation(queryText: String, sl: String, tl: String): String {
        val url = parseHttpUrl(endpointUrl, id).newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", sl)
            .addQueryParameter("tl", tl)
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", queryText)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json,text/plain,*/*")
            .get()
            .build()

        val response = httpClient.executeRequest(request, id)
        if (!response.isSuccessful) {
            throw TranslationException(
                message = "Google GTX returned HTTP ${response.code}",
                providerId = id,
                httpCode = response.code,
            )
        }
        return parseGtxResponse(response.body)
    }

    private fun parseGtxResponse(body: String): String {
        val root = try {
            JSON_PARSER.parseToJsonElement(body) as? JsonArray
                ?: throw TranslationException("Google GTX response root is not a JSON array", providerId = id)
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException("Malformed Google GTX JSON response: ${e.message}", providerId = id, cause = e)
        }

        val segments = root.firstOrNull() as? JsonArray
            ?: throw TranslationException("Google GTX response missing translation segments", providerId = id)

        val sb = StringBuilder()
        for (element in segments) {
            val inner = element as? JsonArray ?: continue
            if (inner.size < 2) continue
            val textPrimitive = inner.firstOrNull() as? JsonPrimitive ?: continue
            if (textPrimitive.isString) {
                sb.append(textPrimitive.content)
            }
        }
        if (sb.isEmpty()) {
            throw TranslationException("Google GTX returned empty translation payload", providerId = id)
        }
        return sb.toString()
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://translate.googleapis.com/translate_a/single"
        private const val BATCH_DELIMITER_TOKEN = "MIHON_SEG_7A3E"
        private const val BATCH_DELIMITER = "[[[$BATCH_DELIMITER_TOKEN]]]"
        private val DELIMITER_SPLIT_REGEX = Regex("""[ \t\r\n]*\[\[\[\s*MIHON_SEG_7A3E\s*\]\]\][ \t\r\n]*""")
    }
}

class MyMemoryTranslationProvider(
    private val httpClient: OkHttpClient = TranslationProviderRegistry.defaultHttpClient(),
    private val endpointUrl: String = DEFAULT_ENDPOINT,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val email: String = "",
) : TranslationProvider {

    override val id: String = TranslationProviderRegistry.PROVIDER_MYMEMORY
    override val displayName: String = "MyMemory"

    override suspend fun translateBatch(
        texts: List<String>,
        sourceLang: TranslatorLanguage,
        targetLang: TranslatorLanguage,
    ): List<String> {
        if (texts.isEmpty()) return emptyList()
        if (sourceLang != TranslatorLanguage.AUTO && sourceLang == targetLang) {
            return texts
        }
        val dstCode = if (targetLang == TranslatorLanguage.AUTO) {
            TranslatorLanguage.PORTUGUESE_BR.myMemoryCode
        } else {
            targetLang.myMemoryCode
        }

        val semaphore = Semaphore(4)
        return coroutineScope {
            texts.map { item ->
                async {
                    if (item.isBlank()) {
                        item
                    } else {
                        val srcCode = if (sourceLang == TranslatorLanguage.AUTO) {
                            inferLanguageFromScript(item)
                        } else {
                            sourceLang.myMemoryCode
                        }
                        if (srcCode.equals(dstCode, ignoreCase = true)) {
                            item
                        } else {
                            semaphore.withPermit {
                                translateChunkedIfLong(item, srcCode, dstCode)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun translateChunkedIfLong(text: String, srcCode: String, dstCode: String): String {
        val trimmed = text.trim()
        if (trimmed.length <= 450) return translateSingle(trimmed, srcCode, dstCode)
        val parts = mutableListOf<String>()
        var start = 0
        while (start < trimmed.length) {
            var end = minOf(trimmed.length, start + 450)
            if (end < trimmed.length) {
                val breakAt = trimmed.lastIndexOf(' ', end).coerceAtLeast(start + 100)
                if (breakAt in (start + 1)..end) end = breakAt
            }
            parts.add(translateSingle(trimmed.substring(start, end).trim(), srcCode, dstCode))
            start = if (end < trimmed.length && trimmed[end] == ' ') end + 1 else end
        }
        return parts.joinToString(" ")
    }

    private suspend fun translateSingle(text: String, srcCode: String, dstCode: String): String {
        val urlBuilder = parseHttpUrl(endpointUrl, id).newBuilder()
            .addQueryParameter("q", text)
            .addQueryParameter("langpair", "$srcCode|$dstCode")
        if (email.isNotBlank()) {
            urlBuilder.addQueryParameter("de", email.trim())
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .get()
            .build()

        val response = httpClient.executeRequest(request, id)
        if (!response.isSuccessful) {
            throw TranslationException(
                message = "MyMemory returned HTTP ${response.code}",
                providerId = id,
                httpCode = response.code,
            )
        }

        val root = try {
            JSON_PARSER.parseToJsonElement(response.body) as? JsonObject
                ?: throw TranslationException("MyMemory response root is not a JSON object", providerId = id)
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException("Malformed MyMemory JSON response: ${e.message}", providerId = id, cause = e)
        }

        val quotaElem = root["quotaFinished"]
        if (quotaElem != null && quotaElem !is JsonPrimitive) {
            throw TranslationException("Malformed MyMemory quotaFinished field", providerId = id)
        }
        val quotaFinished = quotaElem?.booleanOrNull ?: false
        if (quotaFinished) {
            throw TranslationException("MyMemory quota exhausted", providerId = id, httpCode = 429)
        }

        val statusElem = root["responseStatus"]
        if (statusElem != null && statusElem !is JsonPrimitive) {
            throw TranslationException("Malformed MyMemory responseStatus field", providerId = id)
        }
        val responseStatus = statusElem?.contentOrNull?.toIntOrNull()
        val responseDetails = (root["responseDetails"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (responseStatus != null && responseStatus != 200) {
            throw TranslationException(
                message = "MyMemory error ($responseStatus): $responseDetails",
                providerId = id,
                httpCode = responseStatus,
            )
        }

        val dataObj = root["responseData"] as? JsonObject
            ?: throw TranslationException("MyMemory response missing responseData", providerId = id)
        val translated = (dataObj["translatedText"] as? JsonPrimitive)?.contentOrNull
            ?: throw TranslationException("MyMemory response missing translatedText", providerId = id)

        return translated.trim()
    }

    private fun inferLanguageFromScript(text: String): String {
        for (ch in text) {
            when (ch.code) {
                in 0x3040..0x30FF -> return TranslatorLanguage.JAPANESE.myMemoryCode
                in 0xAC00..0xD7AF, in 0x1100..0x11FF -> return TranslatorLanguage.KOREAN.myMemoryCode
            }
        }
        for (ch in text) {
            if (ch.code in 0x4E00..0x9FFF) {
                return TranslatorLanguage.CHINESE_SIMPLIFIED.myMemoryCode
            }
        }
        return TranslatorLanguage.ENGLISH.myMemoryCode
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.mymemory.translated.net/get"
    }
}

class LibreTranslateProvider(
    private val baseUrl: String = DEFAULT_ENDPOINT,
    private val apiKey: String = "",
    private val httpClient: OkHttpClient = TranslationProviderRegistry.defaultHttpClient(),
    private val userAgent: String = DEFAULT_USER_AGENT,
) : TranslationProvider {

    override val id: String = TranslationProviderRegistry.PROVIDER_LIBRETRANSLATE
    override val displayName: String = "LibreTranslate"

    private val endpointUrl: String = normalizeEndpoint(baseUrl)

    override suspend fun translateBatch(
        texts: List<String>,
        sourceLang: TranslatorLanguage,
        targetLang: TranslatorLanguage,
    ): List<String> {
        if (texts.isEmpty()) return emptyList()
        if (sourceLang != TranslatorLanguage.AUTO && sourceLang == targetLang) {
            return texts
        }
        val srcCode = sourceLang.libreCode
        val dstCode = if (targetLang == TranslatorLanguage.AUTO) {
            TranslatorLanguage.PORTUGUESE_BR.libreCode
        } else {
            targetLang.libreCode
        }

        val semaphore = Semaphore(4)
        return coroutineScope {
            texts.map { item ->
                async {
                    if (item.isBlank()) {
                        item
                    } else {
                        semaphore.withPermit { translateSingle(item, srcCode, dstCode) }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun translateSingle(text: String, srcCode: String, dstCode: String): String {
        val payload = buildJsonObject {
            put("q", text)
            put("source", srcCode)
            put("target", dstCode)
            put("format", "text")
            if (apiKey.isNotBlank()) {
                put("api_key", apiKey.trim())
            }
        }.toString()

        val url = parseHttpUrl(endpointUrl, id)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = httpClient.executeRequest(request, id)
        if (!response.isSuccessful) {
            val apiErr = runCatching {
                val errRoot = JSON_PARSER.parseToJsonElement(response.body) as? JsonObject
                (errRoot?.get("error") as? JsonPrimitive)?.contentOrNull
            }.getOrNull()
            throw TranslationException(
                message = "LibreTranslate HTTP ${response.code}${if (!apiErr.isNullOrBlank()) ": $apiErr" else ""}",
                providerId = id,
                httpCode = response.code,
            )
        }

        val root = try {
            JSON_PARSER.parseToJsonElement(response.body) as? JsonObject
                ?: throw TranslationException("LibreTranslate response root is not a JSON object", providerId = id)
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException("Malformed LibreTranslate JSON response: ${e.message}", providerId = id, cause = e)
        }

        return (root["translatedText"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?: throw TranslationException("LibreTranslate response missing translatedText", providerId = id)
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://libretranslate.com/translate"

        fun normalizeEndpoint(rawUrl: String): String {
            val trimmed = rawUrl.trim().trimEnd('/')
            if (trimmed.isEmpty()) return DEFAULT_ENDPOINT
            return if (trimmed.endsWith("/translate")) trimmed else "$trimmed/translate"
        }
    }
}

class CompositeTranslationProvider(
    private val providers: List<TranslationProvider>,
    override val id: String = TranslationProviderRegistry.PROVIDER_COMPOSITE,
    override val displayName: String = "Automático (com fallback)",
) : TranslationProvider {

    init {
        require(providers.isNotEmpty()) { "CompositeTranslationProvider requires at least one provider" }
    }

    override suspend fun translateBatch(
        texts: List<String>,
        sourceLang: TranslatorLanguage,
        targetLang: TranslatorLanguage,
    ): List<String> {
        if (texts.isEmpty()) return emptyList()
        val failures = mutableListOf<Pair<String, Throwable>>()

        for (provider in providers) {
            try {
                val result = provider.translateBatch(texts, sourceLang, targetLang)
                if (result.size == texts.size) {
                    return result
                }
                failures.add(
                    provider.id to TranslationException(
                        "Provider ${provider.id} returned ${result.size} items instead of ${texts.size}",
                        providerId = provider.id,
                    ),
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                failures.add(provider.id to t)
            }
        }

        val primary = failures.first()
        val combinedMessage = failures.joinToString("; ") { (pid, err) -> "$pid: ${err.message}" }
        val exception = TranslationException(
            message = "All translation providers failed ($combinedMessage)",
            providerId = id,
            httpCode = (primary.second as? TranslationException)?.httpCode,
            cause = primary.second,
        )
        for (i in 1 until failures.size) {
            exception.addSuppressed(failures[i].second)
        }
        throw exception
    }
}
