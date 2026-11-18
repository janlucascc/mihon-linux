package mihon.data.extension

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.data.database.DatabaseManager
import mihon.data.repository.SourceRepositoryImpl
import mihon.domain.source.model.Source
import mihon.domain.source.repository.SourceRepository
import mihon.source.ExtensionSource
import mihon.source.HttpScraperSource
import mihon.source.MangaDexSource
import mihon.source.ScraperConfig
import java.io.File
import java.net.URLClassLoader

@Serializable
private data class JarConfig(val jarFile: String)

class ExtensionLoader(
    private val dbManager: DatabaseManager,
    private val extensionsDir: File,
    private val sourcesDir: File = File(extensionsDir.parentFile ?: extensionsDir, "sources"),
) {
    constructor(extensionsDir: File, sourcesDir: File = File(extensionsDir.parentFile ?: extensionsDir, "sources")) : this(
        DatabaseManager(File(extensionsDir.parentFile ?: extensionsDir, "mihon.db")),
        extensionsDir,
        sourcesDir,
    )

    private val sourceRepository: SourceRepository = SourceRepositoryImpl(dbManager)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun loadExtensionsBlocking(): List<ExtensionSource> = kotlinx.coroutines.runBlocking { loadExtensions() }
    fun loadDisabledExtensionsBlocking(): List<ExtensionSource> = kotlinx.coroutines.runBlocking { loadDisabledExtensions() }

    /**
     * Loads all enabled sources from the database, syncing with filesystem first.
     * Returns a list of ExtensionSource instances with stable IDs from SourceTable.
     */
    suspend fun loadExtensions(): List<ExtensionSource> = withContext(Dispatchers.IO) {
        // 1. Sync filesystem -> DB (new JARs, JSON configs)
        syncFilesystemToDatabase()

        // 2. Load enabled sources from DB
        val sources = sourceRepository.getEnabledSources()
            .filter { it.id != 0L && !it.name.equals("Unknown (Legacy)", ignoreCase = true) }
        sources.mapNotNull { source -> createExtensionSource(source) }
    }

    /**
     * Loads disabled sources from the database (those with enabled=false).
     */
    suspend fun loadDisabledExtensions(): List<ExtensionSource> = withContext(Dispatchers.IO) {
        val sources = sourceRepository.getAllSources()
            .filter { !it.enabled && it.id != 0L && !it.name.equals("Unknown (Legacy)", ignoreCase = true) }
        sources.mapNotNull { source -> createExtensionSource(source) }
    }

    /**
     * Syncs filesystem extensions to the database:
     * - Creates new entries for JAR files and JSON configs not in DB
     * - Updates existing entries if config changed
     * - Marks DB entries as disabled if corresponding file was removed/renamed
     */
    private suspend fun syncFilesystemToDatabase() {
        // 1. Ensure built-in sources exist
        ensureBuiltinSources()

        // 2. Sync JAR extensions
        syncJarExtensions()

        // 3. Sync JSON ScraperConfig sources
        syncJsonSources()

        // 4. Disable DB entries whose files no longer exist
        disableMissingFiles()
    }

    private suspend fun ensureBuiltinSources() {
        val builtins = listOf(
            Source(
                name = "Manga Livre (PT-BR)",
                baseUrl = "https://mangalivre.to",
                type = Source.SourceType.BUILTIN,
                configJson = json.encodeToString(ScraperConfig.serializer(), ScraperConfig(
                    name = "Manga Livre (PT-BR)",
                    baseUrl = "https://mangalivre.to",
                    language = "pt-br",
                    searchPath = "/?s={query}&post_type=wp-manga",
                    popularPath = "/?s=&post_type=wp-manga&m_orderby=trending",
                    searchItem = "div.c-tabs-item__content, div.page-item-detail",
                    searchTitle = ".post-title h3 a, .post-title a",
                    searchLink = ".post-title h3 a, .post-title a",
                    searchCover = ".tab-thumb img, .item-thumb img",
                    searchAuthor = ".mg_author .summary-content",
                    searchLatestChapter = ".latest-chap .chapter a, .archive-total-chapters",
                    detailsTitle = ".post-title h1",
                    detailsAuthor = ".author-content a",
                    detailsArtist = ".artist-content a",
                    detailsDescription = ".summary__content p",
                    detailsCover = ".summary_image img",
                    chapterItem = ".listing-chapters-wrap .chapter-box, li.wp-manga-chapter",
                    chapterLink = "a",
                    reverseChapters = true,
                    pageImage = ".reading-content img.wp-manga-chapter-img, .reading-content .page-break img",
                )),
                version = "1.0.0",
                enabled = true,
            ),
            Source(
                name = "MangaDex (pt-br)",
                baseUrl = "https://api.mangadex.org",
                type = Source.SourceType.BUILTIN,
                configJson = null,
                version = "1.0.0",
                enabled = true,
            ),
        )

        builtins.forEach { builtin ->
            val existing = sourceRepository.getSourceByName(builtin.name)
            if (existing == null) {
                sourceRepository.insert(builtin)
            } else if (!existing.enabled) {
                // Re-enable if was previously disabled
                sourceRepository.setEnabled(existing.id, true)
            }
        }
    }

    private suspend fun syncJarExtensions() {
        if (!extensionsDir.exists()) {
            extensionsDir.mkdirs()
            return
        }

        val jarFiles = extensionsDir.listFiles { f -> f.extension.equals("jar", ignoreCase = true) } ?: emptyArray()
        for (jarFile in jarFiles) {
            val name = jarFile.nameWithoutExtension
            val existing = sourceRepository.getSourceByName(name)

            if (existing == null) {
                // New JAR extension - insert with placeholder config
                val source = Source(
                    name = name,
                    baseUrl = "jar://$name",
                    type = Source.SourceType.JAR,
                    configJson = json.encodeToString(JarConfig.serializer(), JarConfig(jarFile.name)),
                    version = "1.0.0",
                    enabled = true,
                )
                sourceRepository.insert(source)
            }
            // Note: We don't update version here automatically; user should manage JAR versions
        }
    }

    private suspend fun syncJsonSources() {
        if (!sourcesDir.exists()) {
            sourcesDir.mkdirs()
            writeSampleConfig()
            return
        }

        val jsonFiles = sourcesDir.listFiles { f -> f.extension.equals("json", ignoreCase = true) } ?: emptyArray()
        for (jsonFile in jsonFiles) {
            if (jsonFile.name == "exemplo-template.json.disabled") continue

            val config: ScraperConfig
            try {
                config = json.decodeFromString<ScraperConfig>(jsonFile.readText())
            } catch (e: Exception) {
                println("Erro ao decodificar config JSON ${jsonFile.name}: ${e.message}")
                continue
            }

            val name = config.name
            val existing = sourceRepository.getSourceByName(name)

            val configJson = json.encodeToString(ScraperConfig.serializer(), config)
            if (existing == null) {
                val source = Source(
                    name = name,
                    baseUrl = config.baseUrl,
                    type = Source.SourceType.JSON_SCRAPER,
                    configJson = configJson,
                    version = "1.0.0",
                    enabled = true,
                )
                sourceRepository.insert(source)
            } else if (existing.configJson != configJson || existing.baseUrl != config.baseUrl) {
                // Config changed - update
                val updated = existing.copy(
                    baseUrl = config.baseUrl,
                    configJson = configJson,
                    version = incrementVersion(existing.version),
                )
                sourceRepository.update(updated)
            }
            // If file is .json.disabled but DB says enabled, we don't auto-disable
            // User must explicitly disable via UI
        }
    }

    private suspend fun disableMissingFiles() {
        val allSources = sourceRepository.getAllSources()
        val jsonFiles = sourcesDir.listFiles { f -> f.extension.equals("json", ignoreCase = true) }?.toList().orEmpty()
        for (source in allSources) {
            val fileExists = when (source.type) {
                Source.SourceType.JAR -> File(extensionsDir, "${source.name}.jar").exists()
                Source.SourceType.JSON_SCRAPER -> {
                    File(sourcesDir, "${source.name}.json").exists() ||
                        jsonFiles.any { f ->
                            f.nameWithoutExtension.equals(source.name, ignoreCase = true) ||
                            runCatching {
                                val cfg = json.decodeFromString<ScraperConfig>(f.readText())
                                cfg.name.equals(source.name, ignoreCase = true) ||
                                    cfg.baseUrl.trimEnd('/').equals(source.baseUrl.trimEnd('/'), ignoreCase = true)
                            }.getOrDefault(false)
                        }
                }
                Source.SourceType.BUILTIN -> true // Built-ins never disappear
            }
            if (!fileExists && source.enabled) {
                sourceRepository.setEnabled(source.id, false)
            }
        }
    }

    private fun createExtensionSource(source: Source): ExtensionSource? {
        if (source.id == 0L || source.name.equals("Unknown (Legacy)", ignoreCase = true)) {
            return null
        }
        return runCatching {
            when (source.type) {
                Source.SourceType.BUILTIN -> {
                    if (source.name.startsWith("MangaDex")) {
                        val lang = source.name.substringAfterLast("(").removeSuffix(")")
                        MangaDexSource(language = lang, id = source.id)
                    } else {
                        val configJson = source.configJson
                        if (configJson.isNullOrBlank() || configJson == "{}") return@runCatching null
                        val config = json.decodeFromString<ScraperConfig>(configJson)
                        HttpScraperSource(config = config, id = source.id)
                    }
                }
                Source.SourceType.JAR -> {
                    loadJarExtension(source)
                }
                Source.SourceType.JSON_SCRAPER -> {
                    val configJson = source.configJson
                    if (configJson.isNullOrBlank() || configJson == "{}") return@runCatching null
                    val config = json.decodeFromString<ScraperConfig>(configJson)
                    HttpScraperSource(config = config, id = source.id)
                }
            }
        }.getOrNull()
    }

    private fun loadJarExtension(source: Source): ExtensionSource {
        val jarFile = File(extensionsDir, "${source.name}.jar")
        if (!jarFile.exists()) {
            throw IllegalStateException("JAR file not found: ${jarFile.absolutePath}")
        }
        val classLoader = URLClassLoader(arrayOf(jarFile.toURI().toURL()), ExtensionSource::class.java.classLoader)
        val clazz = classLoader.loadClass("example.SourceExtension")
        val extension = clazz.getDeclaredConstructor().newInstance() as ExtensionSource
        // Override ID with stable DB ID
        return object : ExtensionSource by extension {
            override val id: Long = source.id
        }
    }

    private fun writeSampleConfig() {
        val sample = File(sourcesDir, "exemplo-template.json.disabled")
        if (sample.exists()) return
        sample.writeText(
            """
            {
              "name": "Exemplo de Fonte CSS",
              "baseUrl": "https://exemplo.org",
              "language": "pt-BR",
              "searchPath": "/search?q={query}",
              "searchItem": "div.manga-item",
              "searchTitle": "h3.title",
              "searchLink": "a",
              "searchCover": "img",
              "detailsTitle": "h1.manga-title",
              "detailsAuthor": "span.author",
              "detailsDescription": "div.synopsis",
              "detailsCover": "div.cover img",
              "chapterItem": "ul.chapters li",
              "chapterLink": "a",
              "pageImage": "div.reader-area img"
            }
            """.trimIndent(),
        )
    }

    private fun incrementVersion(version: String): String {
        return try {
            val parts = version.split(".").map { it.toInt() }
            val newParts = parts.dropLast(1) + (parts.last() + 1)
            newParts.joinToString(".")
        } catch (e: Exception) {
            "1.0.1"
        }
    }
}