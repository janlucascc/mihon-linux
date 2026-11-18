package mihon.desktop.translator

import java.util.prefs.Preferences

/**
 * Thread-safe persistence for [TranslatorConfig] backed by [java.util.prefs.Preferences].
 */
object TranslatorPreferences {
    const val PREF_NODE_NAME: String = "mihon-linux-translator"

    const val KEY_ENABLED_BY_DEFAULT: String = "enabledByDefault"
    const val KEY_SOURCE_LANGUAGE: String = "sourceLanguage"
    const val KEY_TARGET_LANGUAGE: String = "targetLanguage"
    const val KEY_PROVIDER_ID: String = "providerId"
    const val KEY_LIBRETRANSLATE_URL: String = "libreTranslateUrl"
    const val KEY_LIBRETRANSLATE_API_KEY: String = "libreTranslateApiKey"
    const val KEY_AUTO_DOWNLOAD_TESSDATA: String = "autoDownloadTessdata"

    private val VALID_PROVIDER_IDS: Set<String> = setOf(
        TranslationProviderRegistry.PROVIDER_GOOGLE_GTX,
        TranslationProviderRegistry.PROVIDER_MYMEMORY,
        TranslationProviderRegistry.PROVIDER_LIBRETRANSLATE,
        TranslationProviderRegistry.PROVIDER_COMPOSITE,
    )

    private val lock = Any()

    @Volatile
    private var customNode: Preferences? = null

    private val listeners = mutableListOf<(TranslatorConfig) -> Unit>()

    fun backingNode(): Preferences = customNode ?: Preferences.userRoot().node(PREF_NODE_NAME)

    internal fun setBackingNodeForTest(node: Preferences?) {
        synchronized(lock) {
            customNode = node
        }
    }

    fun addChangeListener(listener: (TranslatorConfig) -> Unit) {
        synchronized(lock) {
            listeners.add(listener)
        }
    }

    fun removeChangeListener(listener: (TranslatorConfig) -> Unit) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    fun load(): TranslatorConfig = synchronized(lock) {
        val defaults = TranslatorConfig()
        return runCatching {
            val prefs = backingNode()
            val enabledByDefault = prefs.getBoolean(KEY_ENABLED_BY_DEFAULT, defaults.enabledByDefault)

            val rawSource = prefs.get(KEY_SOURCE_LANGUAGE, defaults.sourceLanguage.name) ?: defaults.sourceLanguage.name
            val sourceLang = TranslatorLanguage.fromNameOrDefault(rawSource, defaults.sourceLanguage)

            val rawTarget = prefs.get(KEY_TARGET_LANGUAGE, defaults.targetLanguage.name) ?: defaults.targetLanguage.name
            val parsedTarget = TranslatorLanguage.fromNameOrDefault(rawTarget, defaults.targetLanguage)
            val targetLang = if (parsedTarget == TranslatorLanguage.AUTO) {
                defaults.targetLanguage
            } else {
                parsedTarget
            }

            val rawProvider = prefs.get(KEY_PROVIDER_ID, defaults.providerId)?.trim()?.lowercase().orEmpty()
            val providerId = if (rawProvider in VALID_PROVIDER_IDS) rawProvider else defaults.providerId

            val rawLibreUrl = prefs.get(KEY_LIBRETRANSLATE_URL, defaults.libreTranslateUrl)?.trim().orEmpty()
            val libreUrl = if (rawLibreUrl.isEmpty()) defaults.libreTranslateUrl else rawLibreUrl

            val libreApiKey = prefs.get(KEY_LIBRETRANSLATE_API_KEY, defaults.libreTranslateApiKey)
                ?: defaults.libreTranslateApiKey

            val autoDownload = prefs.getBoolean(KEY_AUTO_DOWNLOAD_TESSDATA, defaults.autoDownloadTessdata)

            TranslatorConfig(
                enabledByDefault = enabledByDefault,
                sourceLanguage = sourceLang,
                targetLanguage = targetLang,
                providerId = providerId,
                libreTranslateUrl = libreUrl,
                libreTranslateApiKey = libreApiKey,
                autoDownloadTessdata = autoDownload,
            )
        }.getOrDefault(defaults)
    }

    fun save(config: TranslatorConfig) {
        val normalizedConfig = normalizeConfig(config)
        val snapshotListeners: List<(TranslatorConfig) -> Unit>
        synchronized(lock) {
            val prefs = backingNode()
            prefs.putBoolean(KEY_ENABLED_BY_DEFAULT, normalizedConfig.enabledByDefault)
            prefs.put(KEY_SOURCE_LANGUAGE, normalizedConfig.sourceLanguage.name)
            prefs.put(KEY_TARGET_LANGUAGE, normalizedConfig.targetLanguage.name)
            prefs.put(KEY_PROVIDER_ID, normalizedConfig.providerId)
            prefs.put(KEY_LIBRETRANSLATE_URL, normalizedConfig.libreTranslateUrl)
            prefs.put(KEY_LIBRETRANSLATE_API_KEY, normalizedConfig.libreTranslateApiKey)
            prefs.putBoolean(KEY_AUTO_DOWNLOAD_TESSDATA, normalizedConfig.autoDownloadTessdata)
            runCatching { prefs.flush() }
            snapshotListeners = listeners.toList()
        }
        snapshotListeners.forEach { runCatching { it(normalizedConfig) } }
    }

    fun reset() {
        val snapshotListeners: List<(TranslatorConfig) -> Unit>
        val defaults = TranslatorConfig()
        synchronized(lock) {
            runCatching { backingNode().clear() }
            runCatching { backingNode().flush() }
            snapshotListeners = listeners.toList()
        }
        snapshotListeners.forEach { runCatching { it(defaults) } }
    }

    private fun normalizeConfig(config: TranslatorConfig): TranslatorConfig {
        val defaults = TranslatorConfig()
        val rawProvider = config.providerId.trim().lowercase()
        val validProvider = if (rawProvider in VALID_PROVIDER_IDS) rawProvider else defaults.providerId
        val validUrl = config.libreTranslateUrl.trim().ifEmpty { defaults.libreTranslateUrl }
        val validTarget = if (config.targetLanguage == TranslatorLanguage.AUTO) {
            defaults.targetLanguage
        } else {
            config.targetLanguage
        }
        return config.copy(
            targetLanguage = validTarget,
            providerId = validProvider,
            libreTranslateUrl = validUrl,
        )
    }
}
