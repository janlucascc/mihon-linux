package mihon.desktop.translator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves local Tesseract `.traineddata` models and downloads `tessdata_fast` models on demand.
 */
class TessdataManager(
    private val userTessdataDir: File = File(System.getProperty("user.home"), ".mihon/tessdata"),
    private val searchDirs: List<File> = defaultSearchDirs(userTessdataDir),
    private val baseUrl: String = TESSDATA_FAST_BASE_URL,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val minModelBytes: Long = MIN_TRAINEDDATA_BYTES,
) {
    private val inFlightDownloads = ConcurrentHashMap<String, CompletableFuture<Result<File>>>()
    private val materializationLock = Any()

    fun parseLanguageComponents(language: String): List<String> {
        val trimmed = language.trim()
        require(trimmed.isNotEmpty()) { "OCR language code cannot be blank" }
        val parts = trimmed.split('+').map { it.trim() }.filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "OCR language code cannot be empty: '$language'" }
        for (part in parts) {
            require(VALID_LANG_REGEX.matches(part)) {
                "Invalid OCR language component '$part' in '$language'"
            }
        }
        return parts.distinct()
    }

    fun findLocalModelFile(langCode: String): File? {
        require(VALID_LANG_REGEX.matches(langCode)) {
            "Invalid OCR language code: '$langCode'"
        }
        val fileName = "$langCode.traineddata"
        for (dir in searchDirs) {
            val candidate = File(dir, fileName)
            if (candidate.isFile && candidate.length() >= minModelBytes) {
                return candidate
            }
        }
        return null
    }

    fun resolveDatapath(language: String): File? {
        val parts = runCatching { parseLanguageComponents(language) }.getOrNull() ?: return null
        if (parts.isEmpty()) return null

        synchronized(materializationLock) {
            // 1. Prefer a single directory that already contains all components
            for (dir in searchDirs) {
                if (!dir.isDirectory) continue
                val allPresent = parts.all { part ->
                    val f = File(dir, "$part.traineddata")
                    f.isFile && f.length() >= minModelBytes
                }
                if (allPresent) return dir
            }

            // 2. If components are split across searchDirs (e.g. system eng + user jpn), materialize in userTessdataDir
            val resolvedFiles = parts.associateWith { part -> findLocalModelFile(part) ?: return null }
            if (!ensureUserTessdataDir()) {
                return null
            }

            for ((part, sourceFile) in resolvedFiles) {
                val target = File(userTessdataDir, "$part.traineddata")
                if (target.isFile && target.length() >= minModelBytes) continue

                val targetPath = target.toPath()
                val sourcePath = sourceFile.toPath().toAbsolutePath()
                if (targetPath.normalize() == sourcePath.normalize()) continue

                if (Files.isSymbolicLink(targetPath) || Files.isRegularFile(targetPath, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(targetPath)
                }

                try {
                    Files.createSymbolicLink(targetPath, sourcePath)
                } catch (_: Throwable) {
                    val tmpFile = File(userTessdataDir, "$part.traineddata.tmp.${UUID.randomUUID()}")
                    try {
                        Files.copy(sourcePath, tmpFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        moveFileAtomically(tmpFile.toPath(), targetPath)
                    } catch (_: Throwable) {
                        return null
                    } finally {
                        tmpFile.delete()
                    }
                }
            }
            return userTessdataDir
        }
    }

    fun isLanguageAvailable(language: String): Boolean = resolveDatapath(language) != null

    fun listAvailableLanguages(): Set<String> {
        val result = linkedSetOf<String>()
        for (dir in searchDirs) {
            if (!dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (file.isFile && file.name.endsWith(".traineddata") && file.length() >= minModelBytes) {
                    val code = file.name.removeSuffix(".traineddata")
                    if (VALID_LANG_REGEX.matches(code)) {
                        result.add(code)
                    }
                }
            }
        }
        return result
    }

    suspend fun ensureLanguage(language: String, allowDownload: Boolean = true): Result<File> =
        withContext(Dispatchers.IO) {
            ensureLanguageBlocking(language, allowDownload)
        }

    fun ensureLanguageBlocking(language: String, allowDownload: Boolean = true): Result<File> {
        val parts = try {
            parseLanguageComponents(language)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }

        resolveDatapath(language)?.let { return Result.success(it) }

        if (!allowDownload) {
            return Result.failure(
                IllegalStateException(
                    "Modelo OCR '$language' não encontrado localmente e download automático está desativado.",
                ),
            )
        }

        for (part in parts) {
            if (findLocalModelFile(part) != null) continue
            val downloadResult = downloadLanguageSync(part)
            if (downloadResult.isFailure) {
                return Result.failure(
                    downloadResult.exceptionOrNull()
                        ?: IOException("Falha ao baixar modelo OCR '$part'"),
                )
            }
        }

        val datapath = resolveDatapath(language)
        return if (datapath != null) {
            Result.success(datapath)
        } else {
            Result.failure(IllegalStateException("Modelo OCR '$language' indisponível após download"))
        }
    }

    private fun downloadLanguageSync(langCode: String): Result<File> {
        val existing = inFlightDownloads[langCode]
        if (existing != null) {
            return existing.get()
        }

        val promise = CompletableFuture<Result<File>>()
        val race = inFlightDownloads.putIfAbsent(langCode, promise)
        if (race != null) {
            return race.get()
        }

        return try {
            val result = performDownload(langCode)
            promise.complete(result)
            result
        } catch (t: Throwable) {
            val failure = Result.failure<File>(t)
            promise.complete(failure)
            failure
        } finally {
            inFlightDownloads.remove(langCode, promise)
        }
    }

    private fun performDownload(langCode: String): Result<File> {
        if (!ensureUserTessdataDir()) {
            return Result.failure(IOException("Não foi possível criar diretório tessdata: $userTessdataDir"))
        }

        val targetFile = File(userTessdataDir, "$langCode.traineddata")
        if (targetFile.isFile && targetFile.length() >= minModelBytes) {
            return Result.success(targetFile)
        }

        val tmpFile = File(userTessdataDir, "$langCode.traineddata.tmp.${UUID.randomUUID()}")
        val url = "${baseUrl.trimEnd('/')}/$langCode.traineddata"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MihonLinuxDesktop/0.1")
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(
                        IOException("HTTP ${response.code} ao baixar $url"),
                    )
                }
                val body = response.body
                    ?: return Result.failure(IOException("Resposta vazia ao baixar $url"))

                var bytesWritten = 0L
                body.byteStream().use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            bytesWritten += read
                        }
                        output.fd.sync()
                    }
                }

                if (bytesWritten < minModelBytes) {
                    return Result.failure(
                        IOException("Arquivo traineddata incompleto ($bytesWritten bytes < $minModelBytes)"),
                    )
                }

                moveFileAtomically(tmpFile.toPath(), targetFile.toPath())

                Result.success(targetFile)
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    private fun ensureUserTessdataDir(): Boolean {
        if (userTessdataDir.isDirectory) return true
        userTessdataDir.mkdirs()
        return userTessdataDir.isDirectory
    }

    private fun moveFileAtomically(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    companion object {
        const val TESSDATA_FAST_BASE_URL = "https://github.com/tesseract-ocr/tessdata_fast/raw/main"
        const val MIN_TRAINEDDATA_BYTES = 10_000L
        private val VALID_LANG_REGEX = Regex("^[a-z0-9_]+$")

        fun defaultSearchDirs(
            userTessdataDir: File = File(System.getProperty("user.home"), ".mihon/tessdata"),
        ): List<File> {
            val envDir = System.getenv("TESSDATA_PREFIX")
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
            return (
                listOf(
                    File("/usr/share/tesseract/tessdata"),
                    File("/usr/share/tessdata"),
                    File("/usr/local/share/tessdata"),
                    userTessdataDir,
                ) + listOfNotNull(envDir)
                ).distinctBy { it.absolutePath }
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
