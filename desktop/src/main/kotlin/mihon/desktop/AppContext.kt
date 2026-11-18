package mihon.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mihon.core.archive.ArchiveReaderFactory
import mihon.core.archive.ImageDecoder
import mihon.domain.track.TrackerRepository
import mihon.source.ExtensionSource
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.domain.manga.model.Manga
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.SwingUtilities

const val COVER_W = 176
const val COVER_H = 252
const val COVER_ARC = 14

/**
 * Mihon Linux desktop visual identity palette:
 * - Signature Indigo Primary: `#5B5BD6` (`#A6A6FF` high-contrast accent on dark surfaces)
 * - Signature Cyan Accent: `#36C5F0`
 * - Calm neutral surfaces (`#202126` dark, `#000000` AMOLED, `#F7F7FA` light)
 * - Semantic colors harmonized for both dark and light surfaces
 */
object Theme {
    var isDark: Boolean = true
        private set

    val PRIMARY = Color(0x5B, 0x5B, 0xD6)
    val PRIMARY_HOVER = Color(0x49, 0x49, 0xC7)
    val PRIMARY_PRESS = Color(0x3C, 0x3C, 0xB0)
    val ACCENT_CYAN = Color(0x36, 0xC5, 0xF0)

    var BG_APP = Color(0x00, 0x00, 0x00)
    var BG_SIDEBAR = Color(0x07, 0x08, 0x0B)
    var BG_SURFACE = Color(0x0E, 0x0F, 0x14)
    var BG_CARD = Color(0x14, 0x15, 0x1B)
    var BG_CARD_HOVER = Color(0x20, 0x22, 0x2C)
    var BG_PILL_ACTIVE = Color(0x5B, 0x5B, 0xD6, 52)
    var BORDER_SUBTLE = Color(255, 255, 255, 22)
    var ACCENT = Color(0xA6, 0xA6, 0xFF)
    var ACCENT_HOVER = Color(0x8D, 0x8D, 0xE8)
    var ACCENT_SOFT = Color(0x5B, 0x5B, 0xD6, 46)
    var TERTIARY = Color(0x78, 0xB8, 0x86)
    var TEXT_PRIMARY = Color(0xF4, 0xF5, 0xFA)
    var TEXT_SECONDARY = Color(0xC2, 0xC6, 0xD6)
    var TEXT_MUTED = Color(0x90, 0x94, 0xA8)
    var SUCCESS = Color(0x78, 0xB8, 0x86)
    var WARNING = Color(0xF4, 0xC8, 0x75)
    var DANGER = Color(0xFF, 0x8B, 0x8B)

    val FONT_DISPLAY: Font by lazy {
        val candidates = listOf("Inter", "Roboto", "Cantarell", "Noto Sans", "Liberation Sans", "SansSerif")
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        val chosen = candidates.firstOrNull { it in available } ?: "SansSerif"
        Font(chosen, Font.BOLD, 22)
    }
    val FONT_UI = Font("SansSerif", Font.PLAIN, 13)
    val FONT_MONO = Font("Monospaced", Font.PLAIN, 12)

    enum class ThemePreset(
        val displayName: String,
        val isDark: Boolean,
        val bgApp: Color,
        val bgSidebar: Color,
        val bgSurface: Color,
        val bgCard: Color,
        val bgCardHover: Color,
        val bgPillActive: Color,
        val borderSubtle: Color,
        val accent: Color,
        val accentHover: Color,
        val accentSoft: Color,
        val textPrimary: Color,
        val textSecondary: Color,
        val textMuted: Color,
    ) {
        AMOLED(
            "AMOLED Puro (Preto Total)",
            true,
            Color(0x00, 0x00, 0x00),
            Color(0x07, 0x08, 0x0B),
            Color(0x0E, 0x0F, 0x14),
            Color(0x14, 0x15, 0x1B),
            Color(0x20, 0x22, 0x2C),
            Color(0x5B, 0x5B, 0xD6, 52),
            Color(255, 255, 255, 22),
            Color(0xA6, 0xA6, 0xFF),
            Color(0x8D, 0x8D, 0xE8),
            Color(0x5B, 0x5B, 0xD6, 46),
            Color(0xF4, 0xF5, 0xFA),
            Color(0xC2, 0xC6, 0xD6),
            Color(0x90, 0x94, 0xA8),
        ),
        DARK(
            "Dark Indigo (Grafite)",
            true,
            Color(0x16, 0x17, 0x1C),
            Color(0x1B, 0x1C, 0x22),
            Color(0x20, 0x21, 0x26),
            Color(0x26, 0x28, 0x2F),
            Color(0x30, 0x33, 0x3C),
            Color(0x5B, 0x5B, 0xD6, 56),
            Color(255, 255, 255, 22),
            Color(0xA6, 0xA6, 0xFF),
            Color(0x8D, 0x8D, 0xE8),
            Color(0x5B, 0x5B, 0xD6, 48),
            Color(0xEA, 0xEB, 0xF2),
            Color(0xB8, 0xBC, 0xCE),
            Color(0x8C, 0x91, 0xA4),
        ),
        MIDNIGHT_BLUE(
            "Midnight Navy (Azul Noturno)",
            true,
            Color(0x0B, 0x11, 0x1A),
            Color(0x10, 0x18, 0x26),
            Color(0x16, 0x22, 0x36),
            Color(0x1C, 0x2B, 0x42),
            Color(0x27, 0x3B, 0x59),
            Color(0x25, 0x4B, 0x77),
            Color(100, 160, 240, 26),
            Color(0x70, 0xB4, 0xFF),
            Color(0x94, 0xC8, 0xFF),
            Color(112, 180, 255, 45),
            Color(0xF0, 0xF6, 0xFC),
            Color(0xC9, 0xD1, 0xD9),
            Color(0x8B, 0x94, 0x9E),
        ),
        LIGHT(
            "Claro Indigo (Padrão)",
            false,
            Color(0xF7, 0xF7, 0xFA),
            Color(0xEE, 0xEF, 0xF5),
            Color(0xFF, 0xFF, 0xFF),
            Color(0xFF, 0xFF, 0xFF),
            Color(0xE8, 0xE9, 0xF5),
            Color(0x5B, 0x5B, 0xD6, 36),
            Color(22, 23, 34, 22),
            Color(0x5B, 0x5B, 0xD6),
            Color(0x49, 0x49, 0xC7),
            Color(0x5B, 0x5B, 0xD6, 30),
            Color(0x16, 0x17, 0x22),
            Color(0x56, 0x58, 0x6B),
            Color(0x78, 0x7B, 0x8F),
        ),
        CUSTOM(
            "Personalizado...",
            true,
            Color(0x16, 0x17, 0x1C),
            Color(0x1B, 0x1C, 0x22),
            Color(0x20, 0x21, 0x26),
            Color(0x26, 0x28, 0x2F),
            Color(0x30, 0x33, 0x3C),
            Color(0x5B, 0x5B, 0xD6, 56),
            Color(255, 255, 255, 26),
            Color(0xA6, 0xA6, 0xFF),
            Color(0x8D, 0x8D, 0xE8),
            Color(0x5B, 0x5B, 0xD6, 46),
            Color(0xEA, 0xEB, 0xF2),
            Color(0xB8, 0xBC, 0xCE),
            Color(0x8C, 0x91, 0xA4),
        ),
    }

    var currentPreset: ThemePreset = ThemePreset.AMOLED
        private set

    private val listeners = mutableListOf<(Boolean) -> Unit>()

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    fun applyPreset(preset: ThemePreset) {
        currentPreset = preset
        isDark = preset.isDark
        BG_APP = preset.bgApp
        BG_SIDEBAR = preset.bgSidebar
        BG_SURFACE = preset.bgSurface
        BG_CARD = preset.bgCard
        BG_CARD_HOVER = preset.bgCardHover
        BG_PILL_ACTIVE = preset.bgPillActive
        BORDER_SUBTLE = preset.borderSubtle
        ACCENT = preset.accent
        ACCENT_HOVER = preset.accentHover
        ACCENT_SOFT = preset.accentSoft
        TEXT_PRIMARY = preset.textPrimary
        TEXT_SECONDARY = preset.textSecondary
        TEXT_MUTED = preset.textMuted
        applySemanticPalette(isDark)
        listeners.toList().forEach { runCatching { it(isDark) } }
    }

    private fun applySemanticPalette(dark: Boolean) {
        if (dark) {
            SUCCESS = Color(0x78, 0xB8, 0x86)
            WARNING = Color(0xF4, 0xC8, 0x75)
            DANGER = Color(0xFF, 0x8B, 0x8B)
            TERTIARY = SUCCESS
        } else {
            SUCCESS = Color(0x2F, 0xB3, 0x44)
            WARNING = Color(0xE5, 0xA5, 0x0A)
            DANGER = Color(0xE0, 0x1B, 0x24)
            TERTIARY = SUCCESS
        }
    }

    fun setCustomColors(
        bgApp: Color,
        bgSidebar: Color,
        bgSurface: Color,
        accent: Color,
        textPrimary: Color,
    ) {
        currentPreset = ThemePreset.CUSTOM
        // Determine whether light or dark based on background luminance
        val lum = (0.299 * bgApp.red + 0.587 * bgApp.green + 0.114 * bgApp.blue) / 255
        isDark = lum < 0.5

        BG_APP = bgApp
        BG_SIDEBAR = bgSidebar
        BG_SURFACE = bgSurface
        BG_CARD = bgSurface
        BG_CARD_HOVER = if (isDark) Color((bgSurface.red + 20).coerceAtMost(255), (bgSurface.green + 20).coerceAtMost(255), (bgSurface.blue + 20).coerceAtMost(255))
                        else Color((bgSurface.red - 20).coerceAtLeast(0), (bgSurface.green - 20).coerceAtLeast(0), (bgSurface.blue - 20).coerceAtLeast(0))
        BG_PILL_ACTIVE = if (isDark) Color((bgSurface.red + 45).coerceAtMost(255), (bgSurface.green + 45).coerceAtMost(255), (bgSurface.blue + 45).coerceAtMost(255))
                         else Color((bgSurface.red - 40).coerceAtLeast(0), (bgSurface.green - 40).coerceAtLeast(0), (bgSurface.blue - 40).coerceAtLeast(0))
        BORDER_SUBTLE = if (isDark) Color(255, 255, 255, 28) else Color(0, 0, 0, 24)
        ACCENT = accent
        ACCENT_HOVER = if (isDark) {
            Color(
                (accent.red + 24).coerceAtMost(255),
                (accent.green + 24).coerceAtMost(255),
                (accent.blue + 24).coerceAtMost(255),
            )
        } else {
            Color(
                (accent.red - 20).coerceAtLeast(0),
                (accent.green - 20).coerceAtLeast(0),
                (accent.blue - 20).coerceAtLeast(0),
            )
        }
        ACCENT_SOFT = if (isDark) Color(accent.red, accent.green, accent.blue, 46) else Color(accent.red, accent.green, accent.blue, 30)
        TEXT_PRIMARY = textPrimary
        TEXT_SECONDARY = if (isDark) Color(200, 200, 200) else Color(70, 70, 70)
        TEXT_MUTED = if (isDark) Color(150, 150, 150) else Color(110, 110, 110)
        applySemanticPalette(isDark)

        listeners.toList().forEach { runCatching { it(isDark) } }
    }

    fun setDarkMode(dark: Boolean) {
        applyPreset(if (dark) ThemePreset.AMOLED else ThemePreset.LIGHT)
    }
}

/** Shared application coroutine scope on Dispatchers.IO for non-blocking background work. */
internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Runs a suspend or blocking lambda on [appScope] and delivers the result on the Swing EDT. */
internal fun <T> background(work: suspend () -> T, onDone: (Result<T>) -> Unit) {
    appScope.launch {
        val res = runCatching { work() }
        SwingUtilities.invokeLater { onDone(res) }
    }
}

internal fun escapeHtml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

internal fun Color.toHex(): String = String.format("#%02x%02x%02x", red, green, blue)

/** Navigation between the screens of the main window. */
interface Navigator {
    fun showLibrary()
    fun showDetails(manga: Manga)
    fun showHistory()
    fun openSourceCatalog(sourceName: String)
}

/** Shared state handed to every screen. */
class AppContext(
    val dbFile: File,
    val service: LibraryService,
    extensions: List<ExtensionSource>,
    val covers: CoverCache,
    val setStatus: (String) -> Unit,
    val trackerRepository: TrackerRepository? = null,
    val coroutineScope: CoroutineScope? = null,
) {
    val extensions: MutableList<ExtensionSource> = extensions.toMutableList()
    lateinit var navigator: Navigator
    var incognitoMode: Boolean = false
    var onlyOfflineFilter: Boolean = false
    private val offlineFilterListeners = mutableListOf<(Boolean) -> Unit>()
    private val themeListeners = mutableListOf<(Boolean) -> Unit>()
    private val extensionListeners = mutableListOf<() -> Unit>()

    fun addOfflineFilterListener(listener: (Boolean) -> Unit) {
        offlineFilterListeners.add(listener)
    }

    fun removeOfflineFilterListener(listener: (Boolean) -> Unit) {
        offlineFilterListeners.remove(listener)
    }

    fun setOfflineFilter(value: Boolean) {
        if (onlyOfflineFilter != value) {
            onlyOfflineFilter = value
            offlineFilterListeners.toList().forEach { it(value) }
        }
    }

    fun addThemeListener(listener: (Boolean) -> Unit) {
        themeListeners.add(listener)
        Theme.addListener(listener)
    }

    fun removeThemeListener(listener: (Boolean) -> Unit) {
        themeListeners.remove(listener)
        Theme.removeListener(listener)
    }

    fun notifyThemeChanged(isDark: Boolean) {
        Theme.setDarkMode(isDark)
    }

    fun addExtensionListener(listener: () -> Unit) {
        extensionListeners.add(listener)
    }

    fun removeExtensionListener(listener: () -> Unit) {
        extensionListeners.remove(listener)
    }

    fun registerExtension(ext: ExtensionSource) {
        val existingIdx = extensions.indexOfFirst { it.id == ext.id || it.baseUrl.equals(ext.baseUrl, ignoreCase = true) }
        if (existingIdx >= 0) {
            extensions[existingIdx] = ext
        } else {
            extensions.add(ext)
        }
        extensionListeners.toList().forEach { it.invoke() }
    }

    fun unregisterExtension(ext: ExtensionSource) {
        extensions.removeIf { it.id == ext.id || it.baseUrl.equals(ext.baseUrl, ignoreCase = true) }
        extensionListeners.toList().forEach { it.invoke() }
    }

    fun sourceFor(sourceId: Long): ExtensionSource? =
        extensions.firstOrNull { it.id == sourceId }
}

/**
 * Bounded, high-performance cover thumbnail cache.
 * - Pre-caches default placeholders by initial so painting never allocates images on the EDT.
 * - Uses a bounded 4-thread pool for decoding/downloading so scrolling a large grid stays 60fps.
 */
class CoverCache(
    private val cacheDir: File = File(System.getProperty("user.home"), ".mihon/cache/covers").apply { mkdirs() },
) {
    private val iconsLock = Any()
    private val icons = object : LinkedHashMap<Long, ImageIcon>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ImageIcon>): Boolean = size > 80
    }
    private val urlIconsLock = Any()
    private val urlIcons = object : LinkedHashMap<String, ImageIcon>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageIcon>): Boolean = size > 80
    }
    private val placeholders = ConcurrentHashMap<String, ImageIcon>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val httpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        })
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val placeholder: ImageIcon = placeholderFor("M")

    fun get(mangaId: Long, title: String = ""): ImageIcon =
        synchronized(iconsLock) { icons[mangaId] } ?: placeholderFor(title)

    /**
     * Synchronously resolves a cover from memory or disk cache (`manga_<id>_*.png` or `url_<sha1>.png`)
     * before falling back to the initials placeholder.
     */
    fun getOrReadDisk(manga: Manga): ImageIcon {
        synchronized(iconsLock) { icons[manga.id] }?.let { return it }
        val thumbUrl = manga.thumbnailUrl?.trim()
        val remoteThumb = thumbUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (remoteThumb != null) {
            synchronized(urlIconsLock) { urlIcons[remoteThumb] }?.let { icon ->
                synchronized(iconsLock) { icons[manga.id] = icon }
                return icon
            }
        }
        val candidates = buildList {
            if (remoteThumb != null) {
                add(File(cacheDir, "manga_${manga.id}_${sha1(remoteThumb)}.png"))
                add(File(cacheDir, "url_${sha1(remoteThumb)}.png"))
            }
            add(File(cacheDir, "manga_${manga.id}.png"))
            cacheDir.listFiles { _, name -> name.startsWith("manga_${manga.id}_") && name.endsWith(".png") }
                ?.forEach { add(it) }
        }
        for (file in candidates) {
            if (file.exists() && file.isFile) {
                val img = runCatching { ImageIO.read(file) }.getOrNull()
                if (img != null && img.width > 0 && img.height > 0) {
                    val icon = ImageIcon(img)
                    synchronized(iconsLock) { icons[manga.id] = icon }
                    return icon
                }
            }
        }
        return placeholderFor(manga.title)
    }

    fun getByUrl(url: String?, title: String = ""): ImageIcon {
        val clean = url?.trim()
        if (!clean.isNullOrEmpty()) {
            synchronized(urlIconsLock) { urlIcons[clean] }?.let { return it }
        }
        return placeholderFor(title)
    }

    fun has(mangaId: Long): Boolean = synchronized(iconsLock) { icons.containsKey(mangaId) }

    fun hasUrl(url: String?): Boolean {
        val clean = url?.trim() ?: return false
        return synchronized(urlIconsLock) { urlIcons.containsKey(clean) }
    }

    fun remove(mangaId: Long) {
        synchronized(iconsLock) { icons.remove(mangaId) }
        val prefix = "manga_${mangaId}"
        cacheDir.listFiles { _, name -> name.startsWith(prefix) }?.forEach { it.delete() }
    }

    fun clearDiskCache(): Long {
        var totalFreed = 0L
        cacheDir.listFiles()?.forEach { f ->
            totalFreed += f.length()
            f.delete()
        }
        synchronized(iconsLock) { icons.clear() }
        synchronized(urlIconsLock) { urlIcons.clear() }
        return totalFreed
    }

    fun getCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private val sourceIcons = ConcurrentHashMap<String, ImageIcon>()
    private val iconCacheDir = File(System.getProperty("user.home"), ".mihon/cache/icons").apply { mkdirs() }

    fun getSourceIcon(ext: ExtensionSource, onLoaded: () -> Unit = {}): ImageIcon? {
        val key = ext.name
        sourceIcons[key]?.let { return it }

        val hash = sha1(key)
        val cachedFile = File(iconCacheDir, "$hash.png")
        if (cachedFile.exists()) {
            val img = runCatching { ImageIO.read(cachedFile) }.getOrNull()
            if (img != null) {
                val icon = ImageIcon(img)
                sourceIcons[key] = icon
                return icon
            }
        }

        // Asynchronously fetch icon
        val iconUrl = when {
            ext.name.contains("Manga Livre", ignoreCase = true) ->
                "https://mangalivre.to/wp-content/uploads/2025/07/cropped-manga-livre-192x192.webp"
            ext.name.contains("MangaDex", ignoreCase = true) ->
                "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://mangadex.org&size=64"
            else -> {
                val host = ext.baseUrl.substringAfter("://").substringBefore('/')
                "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://$host&size=64"
            }
        }

        if (inFlight.add("icon_$key")) {
            appScope.launch {
                try {
                    val downloaded = downloadCover(iconUrl)
                    if (downloaded != null) {
                        val scaled = BufferedImage(38, 38, BufferedImage.TYPE_INT_ARGB).apply {
                            val g = createGraphics()
                            setupQuality(g)
                            g.clip = RoundRectangle2D.Float(0f, 0f, 38f, 38f, 8f, 8f)
                            g.drawImage(downloaded, 0, 0, 38, 38, null)
                            g.dispose()
                        }
                        runCatching { ImageIO.write(scaled, "png", cachedFile) }
                        val icon = ImageIcon(scaled)
                        sourceIcons[key] = icon
                        SwingUtilities.invokeLater(onLoaded)
                    }
                } finally {
                    inFlight.remove("icon_$key")
                }
            }
        }
        return null
    }

    private fun placeholderFor(title: String): ImageIcon {
        val initials = title.trim().split(Regex("\\s+")).take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
            .ifEmpty { "M" }
        return placeholders.computeIfAbsent(initials) { ImageIcon(createPlaceholder(it)) }
    }

    private val onLoadedCallbacks = ConcurrentHashMap<String, MutableList<() -> Unit>>()

    /** Loads or renders a library manga cover asynchronously on appScope. */
    fun load(manga: Manga, firstChapterPath: suspend () -> String?, onLoaded: () -> Unit) {
        if (has(manga.id)) {
            SwingUtilities.invokeLater(onLoaded)
            return
        }
        val key = "manga_${manga.id}"
        synchronized(onLoadedCallbacks) {
            onLoadedCallbacks.getOrPut(key) { mutableListOf() }.add(onLoaded)
        }
        if (!inFlight.add(key)) return
        appScope.launch {
            try {
                val thumbUrl = manga.thumbnailUrl
                val hasRemoteThumb = !thumbUrl.isNullOrBlank() &&
                    (thumbUrl.startsWith("http://") || thumbUrl.startsWith("https://"))
                val cacheKey = if (hasRemoteThumb) "manga_${manga.id}_" + sha1(thumbUrl) else "manga_${manga.id}"
                val cachedFile = File(cacheDir, "$cacheKey.png")
                val urlCachedFile = if (hasRemoteThumb) File(cacheDir, "url_" + sha1(thumbUrl) + ".png") else null

                val cachedImg = when {
                    cachedFile.exists() -> runCatching { readCachedImageFile(cachedFile) }.getOrNull()
                        ?.takeIf { it.width == COVER_W && it.height == COVER_H }
                    urlCachedFile != null && urlCachedFile.exists() -> runCatching { readCachedImageFile(urlCachedFile) }.getOrNull()
                        ?.takeIf { it.width == COVER_W && it.height == COVER_H }
                    else -> null
                }
                val icon = if (cachedImg != null) {
                    ImageIcon(cachedImg)
                } else {
                    val chPath = runCatching { firstChapterPath() }.getOrNull()
                    renderForManga(manga, chPath)?.also { thumb ->
                        runCatching { ImageIO.write(thumb, "png", cachedFile) }
                    }?.let { ImageIcon(it) }
                }
                if (icon != null) {
                    synchronized(iconsLock) { icons[manga.id] = icon }
                    val callbacks = synchronized(onLoadedCallbacks) { onLoadedCallbacks.remove(key).orEmpty() }
                    SwingUtilities.invokeLater { callbacks.forEach { it.invoke() } }
                }
            } finally {
                synchronized(onLoadedCallbacks) { onLoadedCallbacks.remove(key) }
                inFlight.remove(key)
            }
        }
    }

    /** Downloads and caches a cover by URL asynchronously on appScope. */
    fun loadUrl(url: String?, onLoaded: () -> Unit) {
        val cleanUrl = url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
        if (hasUrl(cleanUrl) || !inFlight.add(cleanUrl)) return
        appScope.launch {
            try {
                val hash = sha1(cleanUrl)
                val cachedFile = File(cacheDir, "url_$hash.png")
                val cachedImg = if (cachedFile.exists()) {
                    runCatching { readCachedImageFile(cachedFile) }.getOrNull()
                        ?.takeIf { it.width == COVER_W && it.height == COVER_H }
                } else {
                    null
                }
                val icon = if (cachedImg != null) {
                    ImageIcon(cachedImg)
                } else {
                    downloadCover(cleanUrl)?.let { scaleAndRound(it) }?.also { rounded ->
                        runCatching { ImageIO.write(rounded, "png", cachedFile) }
                    }?.let { ImageIcon(it) }
                }
                if (icon != null) {
                    synchronized(urlIconsLock) { urlIcons[cleanUrl] = icon }
                    SwingUtilities.invokeLater(onLoaded)
                }
            } finally {
                inFlight.remove(cleanUrl)
            }
        }
    }

    private fun renderForManga(manga: Manga, chapterPath: String?): BufferedImage? {
        // Priority 1: Official manga cover URL (thumbnailUrl)
        val thumbUrl = manga.thumbnailUrl?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (thumbUrl != null) {
            val downloaded = downloadCover(thumbUrl)
            if (downloaded != null) return scaleAndRound(downloaded)
        }

        // Priority 2: Dedicated local cover image in the manga's directory (cover.jpg, folder.png, etc.)
        val mangaFile = File(manga.url)
        val candidateDir = when {
            mangaFile.isDirectory -> mangaFile
            mangaFile.isFile -> mangaFile.parentFile
            else -> null
        }
        if (candidateDir != null && candidateDir.isDirectory) {
            val names = listOf("cover.jpg", "cover.png", "cover.jpeg", "folder.jpg", "folder.png", "poster.jpg")
            for (name in names) {
                val f = File(candidateDir, name)
                if (f.exists() && f.isFile) {
                    val img = runCatching { ImageIO.read(f) }.getOrNull()
                    if (img != null) return scaleAndRound(img)
                }
            }
        }

        // Priority 3: Fallback only for local archives without dedicated cover (extract page 1)
        val candidatePath = manga.url.takeIf { File(it).exists() } ?: chapterPath
        if (candidatePath != null) {
            val localFile = File(candidatePath)
            if (localFile.exists() && ArchiveReaderFactory.isSupported(localFile)) {
                runCatching {
                    ArchiveReaderFactory.of(localFile).use { reader ->
                        val first = reader.pages.firstOrNull() ?: return@use null
                        reader.getPageImage(first)?.let { return scaleAndRound(it) }
                    }
                }
            }
        }
        return null
    }

    private fun downloadCover(url: String): BufferedImage? = runCatching {
        val clean = url.trim().replace(" ", "%20")
        val parsed = clean.toHttpUrl()
        val isMangaDex = parsed.host.contains("mangadex.org")
        val reqBuilder = Request.Builder()
            .url(parsed)
            .header(
                "User-Agent",
                if (isMangaDex) "MihonLinux/0.3 (Linux Desktop)"
                else "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            )
            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")

        // Specialized referer headers:
        // MangaDex CDN requires 'https://mangadex.org/' or clean Referer to avoid edge challenge
        if (isMangaDex) {
            reqBuilder.header("Referer", "https://mangadex.org/")
        } else if (!parsed.host.startsWith("uploads.") && !parsed.host.startsWith("cdn.")) {
            reqBuilder.header("Referer", "${parsed.scheme}://${parsed.host}/")
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            ImageDecoder.decode(bytes)
        }
    }.getOrNull()

    /**
     * Reads a cached image file bypassing global ImageIO provider SPI synchronization lock contention.
     */
    private fun readCachedImageFile(file: File): BufferedImage? = runCatching {
        javax.imageio.ImageIO.createImageInputStream(file).use { iis ->
            val readers = javax.imageio.ImageIO.getImageReaders(iis)
            if (!readers.hasNext()) return@use null
            val reader = readers.next()
            try {
                reader.setInput(iis, true, true)
                reader.read(0)
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

    /** Center-crops to COVER_W x COVER_H with crisp Tankobon book spine highlight and hairline border. */
    private fun scaleAndRound(src: BufferedImage): BufferedImage {
        val scale = maxOf(COVER_W.toDouble() / src.width, COVER_H.toDouble() / src.height)
        val sw = (src.width * scale).toInt().coerceAtLeast(COVER_W)
        val sh = (src.height * scale).toInt().coerceAtLeast(COVER_H)
        val dx = (COVER_W - sw) / 2
        val dy = (COVER_H - sh) / 2

        val out = BufferedImage(COVER_W, COVER_H, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        setupQuality(g)
        g.clip = RoundRectangle2D.Float(0f, 0f, COVER_W.toFloat(), COVER_H.toFloat(), COVER_ARC.toFloat(), COVER_ARC.toFloat())
        g.drawImage(src, dx, dy, sw, sh, null)
        // Subtle bottom vignette for legibility
        g.paint = GradientPaint(0f, (COVER_H - 56).toFloat(), Color(0, 0, 0, 0), 0f, COVER_H.toFloat(), Color(0, 0, 0, 145))
        g.fillRect(0, COVER_H - 56, COVER_W, 56)

        g.clip = null
        g.color = Color(242, 239, 233, 36)
        g.drawRoundRect(0, 0, COVER_W - 1, COVER_H - 1, COVER_ARC, COVER_ARC)
        g.dispose()
        return out
    }

    private fun createPlaceholder(initials: String): BufferedImage {
        val out = BufferedImage(COVER_W, COVER_H, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        setupQuality(g)
        // Soft 145deg gradient combining Mihon indigo (#5B5BD6 @ 24%) and cyan (#36C5F0 @ 12%) over calm dark slate
        g.paint = GradientPaint(
            0f, 0f, Color(0x28, 0x2A, 0x48),
            COVER_W.toFloat(), COVER_H.toFloat(), Color(0x1B, 0x27, 0x33),
        )
        g.fillRoundRect(0, 0, COVER_W, COVER_H, COVER_ARC, COVER_ARC)

        // Subtle decorative inner ring
        g.color = Color(0xA6, 0xA6, 0xFF, 42)
        g.drawOval(COVER_W / 2 - 36, COVER_H / 2 - 42, 72, 72)

        g.color = Color(0xEA, 0xEB, 0xF2, 165)
        g.font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 34f)
        val fm = g.fontMetrics
        g.drawString(initials, (COVER_W - fm.stringWidth(initials)) / 2, (COVER_H + fm.ascent) / 2 - 8)

        g.color = Color(0xA6, 0xA6, 0xFF, 52)
        g.drawRoundRect(0, 0, COVER_W - 1, COVER_H - 1, COVER_ARC, COVER_ARC)
        g.dispose()
        return out
    }

    private fun setupQuality(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.composite = AlphaComposite.SrcOver
    }

    private fun sha1(input: String): String =
        MessageDigest.getInstance("SHA-1").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}
