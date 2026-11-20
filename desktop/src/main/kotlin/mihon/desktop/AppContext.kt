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

const val COVER_W = 152
const val COVER_H = 224
const val COVER_ARC = 10

fun easeOutCubic(t: Float): Float {
    val clamped = t.coerceIn(0f, 1f)
    val inv = 1f - clamped
    return 1f - inv * inv * inv
}

/**
 * Mihon Index desktop visual identity palette:
 * - Cobalt Primary: `#4F46E5` (hover `#4338CA`, press `#3730A3`; `#818CF8` high-contrast text accent on dark)
 * - Signal Orange: `#F97316` (hover `#EA580C`) for unread badges and reading progress
 * - Calm neutral editorial surfaces (`#F7F7FA` light, `#16171C` dark, `#000000` AMOLED)
 * - Semantic colors harmonized for both dark and light surfaces
 */
object Theme {
    var isDark: Boolean = true
        private set

    val PRIMARY = Color(0x4F, 0x46, 0xE5)
    val PRIMARY_HOVER = Color(0x43, 0x38, 0xCA)
    val PRIMARY_PRESS = Color(0x37, 0x30, 0xA3)
    var SIGNAL = Color(0xF9, 0x73, 0x16)
    var SIGNAL_HOVER = Color(0xEA, 0x58, 0x0C)
    val ACCENT_CYAN = Color(0x36, 0xC5, 0xF0)

    const val CONTROL_HEIGHT_SMALL = 32
    const val CONTROL_HEIGHT_COMPACT = 38
    const val CONTROL_HEIGHT_PRIMARY = 48
    const val PAGE_GUTTER = 16
    const val PANEL_GUTTER = 24
    const val CONTROL_RADIUS = 8
    const val CARD_RADIUS = 10
    const val BADGE_RADIUS = 7
    const val MOTION_FAST_MS = 120
    const val MOTION_NORMAL_MS = 180
    const val MOTION_SLOW_MS = 240

    var BG_APP = Color(0x22, 0x22, 0x26)
    var BG_SIDEBAR = Color(0x2E, 0x2E, 0x32)
    var BG_SURFACE = Color(0x33, 0x33, 0x37)
    var BG_CARD = Color(0x38, 0x38, 0x3B)
    var BG_CARD_HOVER = Color(0x44, 0x44, 0x49)
    var BG_PILL_ACTIVE = Color(0x42, 0x42, 0x46)
    var BORDER_SUBTLE = Color(255, 255, 255, 22)
    var BORDER_STRONG = Color(255, 255, 255, 48)
    var ACCENT = Color(0x81, 0x8C, 0xF8)
    var ACCENT_HOVER = Color(0x63, 0x66, 0xF1)
    var ACCENT_SOFT = Color(0x4F, 0x46, 0xE5, 44)
    var TERTIARY = Color(0x78, 0xB8, 0x86)
    var TEXT_PRIMARY = Color(0xF6, 0xF5, 0xF4)
    var TEXT_SECONDARY = Color(0xB8, 0xB7, 0xBC)
    var TEXT_MUTED = Color(0x9A, 0x99, 0x9E)
    var SUCCESS = Color(0x78, 0xB8, 0x86)
    var WARNING = Color(0xF4, 0xC8, 0x75)
    var DANGER = Color(0xFF, 0x8B, 0x8B)

    val FONT_DISPLAY: Font by lazy {
        val candidates = listOf("Cantarell", "Inter", "Roboto", "Noto Sans", "Liberation Sans", Font.DIALOG)
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        val chosen = candidates.firstOrNull { it in available } ?: Font.DIALOG
        Font(chosen, Font.BOLD, 22)
    }
    val FONT_UI = Font(Font.DIALOG, Font.PLAIN, 13)
    val FONT_MONO = Font("Monospaced", Font.PLAIN, 12)
    val FONT_CJK: Font by lazy {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val available = ge.availableFontFamilyNames.toSet()
        val preferred = listOf("Noto Sans CJK KR", "Noto Sans CJK JP", "Noto Sans CJK SC", "Droid Sans Fallback", "UnDotum")
        val chosen = preferred.firstOrNull { it in available }
            ?: ge.allFonts.firstOrNull { it.canDisplay('하') || it.canDisplay('日') }?.family
            ?: Font.DIALOG
        Font(chosen, Font.PLAIN, 13)
    }

    fun drawStringWithFallback(g2: Graphics2D, text: String, x: Int, y: Int) {
        if (text.isEmpty()) return
        val primary = g2.font
        if (primary.canDisplayUpTo(text) == -1) {
            g2.drawString(text, x, y)
            return
        }
        val fallback = FONT_CJK.deriveFont(primary.style, primary.size2D)
        val attr = java.text.AttributedString(text)
        attr.addAttribute(java.awt.font.TextAttribute.FONT, primary, 0, text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val charCount = Character.charCount(cp)
            if (!primary.canDisplay(cp) && fallback.canDisplay(cp)) {
                attr.addAttribute(java.awt.font.TextAttribute.FONT, fallback, i, i + charCount)
            }
            i += charCount
        }
        g2.drawString(attr.iterator, x, y)
    }

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
            Color(0x4F, 0x46, 0xE5, 44),
            Color(255, 255, 255, 56),
            Color(0x81, 0x8C, 0xF8),
            Color(0x63, 0x66, 0xF1),
            Color(0x4F, 0x46, 0xE5, 44),
            Color(0xF4, 0xF5, 0xFA),
            Color(0xC2, 0xC6, 0xD6),
            Color(0x90, 0x94, 0xA8),
        ),
        DARK(
            "Escuro (Grafite)",
            true,
            Color(0x22, 0x22, 0x26),
            Color(0x2E, 0x2E, 0x32),
            Color(0x33, 0x33, 0x37),
            Color(0x38, 0x38, 0x3B),
            Color(0x44, 0x44, 0x49),
            Color(0x42, 0x42, 0x46),
            Color(255, 255, 255, 22),
            Color(0x81, 0x8C, 0xF8),
            Color(0x63, 0x66, 0xF1),
            Color(0x4F, 0x46, 0xE5, 44),
            Color(0xF6, 0xF5, 0xF4),
            Color(0xB8, 0xB7, 0xBC),
            Color(0x9A, 0x99, 0x9E),
        ),
        MIDNIGHT_BLUE(
            "Midnight Navy (Azul Noturno)",
            true,
            Color(0x0B, 0x11, 0x1A),
            Color(0x10, 0x18, 0x26),
            Color(0x16, 0x22, 0x36),
            Color(0x1C, 0x2B, 0x42),
            Color(0x27, 0x3B, 0x59),
            Color(0x4F, 0x46, 0xE5, 52),
            Color(138, 176, 232, 56),
            Color(0x81, 0x8C, 0xF8),
            Color(0x63, 0x66, 0xF1),
            Color(0x4F, 0x46, 0xE5, 44),
            Color(0xF0, 0xF6, 0xFC),
            Color(0xC9, 0xD1, 0xD9),
            Color(0x8B, 0x94, 0x9E),
        ),
        LIGHT(
            "Claro (Editorial)",
            false,
            Color(0xF7, 0xF7, 0xFA),
            Color(0xEE, 0xEF, 0xF5),
            Color(0xFF, 0xFF, 0xFF),
            Color(0xFF, 0xFF, 0xFF),
            Color(0xE8, 0xE9, 0xF5),
            Color(0x4F, 0x46, 0xE5, 36),
            Color(22, 23, 34, 56),
            Color(0x4F, 0x46, 0xE5),
            Color(0x43, 0x38, 0xCA),
            Color(0x4F, 0x46, 0xE5, 32),
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
            Color(0x4F, 0x46, 0xE5, 44),
            Color(255, 255, 255, 56),
            Color(0x81, 0x8C, 0xF8),
            Color(0x63, 0x66, 0xF1),
            Color(0x4F, 0x46, 0xE5, 44),
            Color(0xEA, 0xEB, 0xF2),
            Color(0xB8, 0xBC, 0xCE),
            Color(0x8C, 0x91, 0xA4),
        ),
    }

    var currentPreset: ThemePreset = ThemePreset.DARK
        private set

    private val listeners = mutableListOf<(Boolean) -> Unit>()

    fun addListener(listener: (Boolean) -> Unit) {
        if (listener !in listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    fun applyPreset(preset: ThemePreset) {
        currentPreset = preset
        isDark = preset.isDark
        SIGNAL = Color(0xF9, 0x73, 0x16)
        SIGNAL_HOVER = Color(0xEA, 0x58, 0x0C)
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
            BORDER_STRONG = Color(255, 255, 255, 70)
            TERTIARY = SUCCESS
        } else {
            SUCCESS = Color(0x2F, 0xB3, 0x44)
            WARNING = Color(0xE5, 0xA5, 0x0A)
            DANGER = Color(0xE0, 0x1B, 0x24)
            BORDER_STRONG = Color(22, 23, 34, 70)
            TERTIARY = SUCCESS
        }
    }

    fun setCustomColors(
        bgApp: Color,
        bgSidebar: Color,
        bgSurface: Color,
        accent: Color,
        textPrimary: Color,
        signal: Color = Color(0xF9, 0x73, 0x16),
    ) {
        currentPreset = ThemePreset.CUSTOM
        // Determine whether light or dark based on background luminance
        val lum = (0.299 * bgApp.red + 0.587 * bgApp.green + 0.114 * bgApp.blue) / 255
        isDark = lum < 0.5

        SIGNAL = signal
        SIGNAL_HOVER = Color(
            (signal.red - 15).coerceAtLeast(0),
            (signal.green - 27).coerceAtLeast(0),
            (signal.blue - 10).coerceAtLeast(0),
        )
        BG_APP = bgApp
        BG_SIDEBAR = bgSidebar
        BG_SURFACE = bgSurface
        BG_CARD = bgSurface
        BG_CARD_HOVER = if (isDark) Color((bgSurface.red + 16).coerceAtMost(255), (bgSurface.green + 16).coerceAtMost(255), (bgSurface.blue + 16).coerceAtMost(255))
                        else Color((bgSurface.red - 18).coerceAtLeast(0), (bgSurface.green - 18).coerceAtLeast(0), (bgSurface.blue - 18).coerceAtLeast(0))
        BG_PILL_ACTIVE = Color(accent.red, accent.green, accent.blue, if (isDark) 44 else 36)
        BORDER_SUBTLE = if (isDark) Color(255, 255, 255, 56) else Color(22, 23, 34, 56)
        ACCENT = accent
        ACCENT_HOVER = if (isDark) {
            Color(
                (accent.red + 24).coerceAtMost(255),
                (accent.green + 24).coerceAtMost(255),
                (accent.blue + 24).coerceAtMost(255),
            )
        } else {
            Color(
                (accent.red - 18).coerceAtLeast(0),
                (accent.green - 18).coerceAtLeast(0),
                (accent.blue - 18).coerceAtLeast(0),
            )
        }
        ACCENT_SOFT = if (isDark) Color(accent.red, accent.green, accent.blue, 44) else Color(accent.red, accent.green, accent.blue, 32)
        TEXT_PRIMARY = textPrimary
        TEXT_SECONDARY = if (isDark) Color(0xB8, 0xBC, 0xCE) else Color(0x56, 0x58, 0x6B)
        TEXT_MUTED = if (isDark) Color(0x8C, 0x91, 0xA4) else Color(0x78, 0x7B, 0x8F)
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
    private val icons = object : LinkedHashMap<Long, ImageIcon>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ImageIcon>): Boolean {
            if (size > MemoryManager.coverCacheMaxEntries()) {
                runCatching { (eldest.value.image as? BufferedImage)?.flush() }
                return true
            }
            return false
        }
    }
    private val urlIconsLock = Any()
    private val urlIcons = object : LinkedHashMap<String, ImageIcon>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageIcon>): Boolean {
            if (size > MemoryManager.coverCacheMaxEntries()) {
                runCatching { (eldest.value.image as? BufferedImage)?.flush() }
                return true
            }
            return false
        }
    }
    private val placeholders = ConcurrentHashMap<String, ImageIcon>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val fadeStartMs = ConcurrentHashMap<String, Long>()
    private val writesSinceTrim = java.util.concurrent.atomic.AtomicInteger(0)
    private val maxDiskCacheBytes = 250L * 1024L * 1024L

    init {
        MemoryManager.registerTrimCallback { trimMemoryCache() }
    }

    fun trimMemoryCache() {
        val target = (MemoryManager.coverCacheMaxEntries() / 2).coerceAtLeast(8)
        synchronized(iconsLock) {
            while (icons.size > target) {
                val firstKey = icons.keys.firstOrNull() ?: break
                val removed = icons.remove(firstKey)
                runCatching { (removed?.image as? BufferedImage)?.flush() }
            }
        }
        synchronized(urlIconsLock) {
            while (urlIcons.size > target) {
                val firstKey = urlIcons.keys.firstOrNull() ?: break
                val removed = urlIcons.remove(firstKey)
                runCatching { (removed?.image as? BufferedImage)?.flush() }
            }
        }
        if (fadeStartMs.size > 64) fadeStartMs.clear()
    }

    fun fadeAlphaForManga(mangaId: Long): Float = fadeAlphaForKey("m_$mangaId")
    fun fadeAlphaForUrl(url: String?): Float {
        val clean = url?.trim()?.takeIf { it.isNotEmpty() } ?: return 1f
        return fadeAlphaForKey("u_$clean")
    }

    private fun fadeAlphaForKey(key: String): Float {
        if (!mihon.desktop.ui.animation.MotionPreferences.isEnabled) return 1f
        val start = fadeStartMs[key] ?: return 1f
        val elapsed = (System.currentTimeMillis() - start).toFloat()
        val duration = mihon.desktop.ui.animation.MotionPreferences.effectiveDurationMs(200).toFloat().coerceAtLeast(1f)
        if (elapsed >= duration) {
            fadeStartMs.remove(key)
            return 1f
        }
        return easeOutCubic(elapsed / duration)
    }

    private fun recordFreshLoad(key: String, loadedFromDisk: Boolean) {
        if (!loadedFromDisk && mihon.desktop.ui.animation.MotionPreferences.isEnabled) {
            fadeStartMs[key] = System.currentTimeMillis()
        }
    }

    private fun maybeTrimDiskCache() {
        if (writesSinceTrim.incrementAndGet() % 30 != 0) return
        val files = cacheDir.listFiles { f -> f.isFile && f.extension.equals("png", ignoreCase = true) } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxDiskCacheBytes) return
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= maxDiskCacheBytes * 80 / 100) return@forEach
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 16
            maxRequestsPerHost = 6
        })
        .connectionPool(okhttp3.ConnectionPool(4, 30, TimeUnit.SECONDS))
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
        val key = "${Theme.BG_CARD.rgb}_${Theme.BORDER_SUBTLE.rgb}_${Theme.TEXT_MUTED.rgb}_$initials"
        return placeholders.computeIfAbsent(key) { ImageIcon(createPlaceholder(initials)) }
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
                        maybeTrimDiskCache()
                    }?.let { ImageIcon(it) }
                }
                if (icon != null) {
                    synchronized(iconsLock) { icons[manga.id] = icon }
                    recordFreshLoad("m_${manga.id}", cachedImg != null)
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
                        maybeTrimDiskCache()
                    }?.let { ImageIcon(it) }
                }
                if (icon != null) {
                    synchronized(urlIconsLock) { urlIcons[cleanUrl] = icon }
                    recordFreshLoad("u_$cleanUrl", cachedImg != null)
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
                if (isMangaDex) "MihonLinux/0.4 (Linux Desktop)"
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

    /** Center-crops to COVER_W x COVER_H with 10px rounded corners and clean hairline border. */
    private fun scaleAndRound(src: BufferedImage): BufferedImage {
        val scale = maxOf(COVER_W.toDouble() / src.width, COVER_H.toDouble() / src.height)
        val sw = (src.width * scale).toInt().coerceAtLeast(COVER_W)
        val sh = (src.height * scale).toInt().coerceAtLeast(COVER_H)
        val dx = (COVER_W - sw) / 2
        val dy = (COVER_H - sh) / 2
        val arc = (Theme.CARD_RADIUS * 2).toFloat()

        val out = BufferedImage(COVER_W, COVER_H, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        setupQuality(g)
        g.clip = RoundRectangle2D.Float(0f, 0f, COVER_W.toFloat(), COVER_H.toFloat(), arc, arc)
        g.drawImage(src, dx, dy, sw, sh, null)

        g.clip = null
        g.color = Color(255, 255, 255, 28)
        g.drawRoundRect(0, 0, COVER_W - 1, COVER_H - 1, arc.toInt(), arc.toInt())
        g.dispose()
        return out
    }

    private fun createPlaceholder(initials: String): BufferedImage {
        val out = BufferedImage(COVER_W, COVER_H, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        setupQuality(g)
        val arc = Theme.CARD_RADIUS * 2

        g.color = Theme.BG_CARD
        g.fillRoundRect(0, 0, COVER_W, COVER_H, arc, arc)

        val bookIcon = ModernIcons.library(36) { Theme.TEXT_MUTED }
        bookIcon.paintIcon(null, g, (COVER_W - 36) / 2, COVER_H / 2 - 34)

        g.color = Theme.TEXT_MUTED
        g.font = Theme.FONT_UI.deriveFont(Font.BOLD, 14f)
        val fm = g.fontMetrics
        g.drawString(initials, (COVER_W - fm.stringWidth(initials)) / 2, COVER_H / 2 + 24)

        g.color = Theme.BORDER_SUBTLE
        g.drawRoundRect(0, 0, COVER_W - 1, COVER_H - 1, arc, arc)
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
