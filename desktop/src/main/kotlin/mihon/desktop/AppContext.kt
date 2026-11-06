package mihon.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mihon.core.archive.ArchiveReaderFactory
import mihon.core.archive.ImageDecoder
import mihon.source.ExtensionSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.domain.manga.model.Manga
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
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
const val COVER_ARC = 10

/**
 * Curated Sumi-Ink Editorial Dark palette (grounded in frontend-design principles):
 * - Sumi Ink (`#0D0D0F`): Deep reading-room obsidian
 * -Charcoal Paper (`#151519`): Elevated Bindery Surface
 * - Graphite (`#1D1E24`): Subtle interactive row/surface
 * - Washi Ivory (`#F2EFE9`): High-contrast warm editorial ink
 * - Hanko Vermilion (`#E04F3E`): Japanese seal stamp primary accent
 * - Matcha Sage (`#2E9E6B`): Offline / archived indicator
 */
object Theme {
    val BG_APP = Color(0x0D, 0x0D, 0x0F)
    val BG_SIDEBAR = Color(0x12, 0x12, 0x16)
    val BG_SURFACE = Color(0x15, 0x15, 0x19)
    val BG_CARD = Color(0x1A, 0x1A, 0x20)
    val BG_CARD_HOVER = Color(0x23, 0x24, 0x2C)
    val BORDER_SUBTLE = Color(242, 239, 233, 24)
    val ACCENT = Color(0xE0, 0x4F, 0x3E) // Hanko Vermilion
    val ACCENT_HOVER = Color(0xF0, 0x6A, 0x59)
    val ACCENT_SOFT = Color(0xE0, 0x4F, 0x3E, 38)
    val TERTIARY = Color(0x2E, 0x9E, 0x6B) // Matcha Sage
    val TEXT_PRIMARY = Color(0xF2, 0xEF, 0xE9) // Washi Ivory
    val TEXT_SECONDARY = Color(0xC8, 0xC4, 0xBC)
    val TEXT_MUTED = Color(0x8E, 0x8B, 0x82) // Stone Ash
    val SUCCESS = Color(0x2E, 0x9E, 0x6B)
    val DANGER = Color(0xD9, 0x38, 0x3A)

    val FONT_DISPLAY = Font("Serif", Font.BOLD, 22)
    val FONT_UI = Font("SansSerif", Font.PLAIN, 13)
    val FONT_MONO = Font("Monospaced", Font.PLAIN, 12)
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

/** Navigation between the screens of the main window. */
interface Navigator {
    fun showLibrary()
    fun showDetails(manga: Manga)
    fun showHistory()
}

/** Shared state handed to every screen. */
class AppContext(
    val dbFile: File,
    val service: LibraryService,
    val extensions: List<ExtensionSource>,
    val covers: CoverCache,
    val setStatus: (String) -> Unit,
) {
    lateinit var navigator: Navigator

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
    private val icons = ConcurrentHashMap<Long, ImageIcon>()
    private val urlIcons = ConcurrentHashMap<String, ImageIcon>()
    private val placeholders = ConcurrentHashMap<String, ImageIcon>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val coverPool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "cover-loader").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val placeholder: ImageIcon = placeholderFor("M")

    fun get(mangaId: Long, title: String = ""): ImageIcon =
        icons[mangaId] ?: placeholderFor(title)

    fun getByUrl(url: String?, title: String = ""): ImageIcon {
        val clean = url?.trim()
        if (!clean.isNullOrEmpty()) {
            urlIcons[clean]?.let { return it }
        }
        return placeholderFor(title)
    }

    fun has(mangaId: Long): Boolean = icons.containsKey(mangaId)

    fun remove(mangaId: Long) {
        icons.remove(mangaId)
        File(cacheDir, "$mangaId.png").delete()
    }

    private fun placeholderFor(title: String): ImageIcon {
        val initials = title.trim().split(Regex("\\s+")).take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
            .ifEmpty { "M" }
        return placeholders.computeIfAbsent(initials) { ImageIcon(createPlaceholder(it)) }
    }

    /** Loads or renders a library manga cover on the bounded cover pool. */
    fun load(manga: Manga, firstChapterPath: suspend () -> String?, onLoaded: () -> Unit) {
        val key = "manga_${manga.id}"
        if (icons.containsKey(manga.id) || !inFlight.add(key)) return
        coverPool.submit {
            try {
                val cachedFile = File(cacheDir, "${manga.id}.png")
                val cachedImg = if (cachedFile.exists()) {
                    runCatching { ImageIO.read(cachedFile) }.getOrNull()
                        ?.takeIf { it.width == COVER_W && it.height == COVER_H }
                } else {
                    null
                }
                val icon = if (cachedImg != null) {
                    ImageIcon(cachedImg)
                } else {
                    val chPath = kotlinx.coroutines.runBlocking { runCatching { firstChapterPath() }.getOrNull() }
                    renderForManga(manga, chPath)?.also { thumb ->
                        runCatching { ImageIO.write(thumb, "png", cachedFile) }
                    }?.let { ImageIcon(it) }
                }
                if (icon != null) {
                    icons[manga.id] = icon
                    SwingUtilities.invokeLater(onLoaded)
                }
            } finally {
                inFlight.remove(key)
            }
        }
    }

    /** Downloads and caches a cover by URL on the bounded cover pool. */
    fun loadUrl(url: String?, onLoaded: () -> Unit) {
        val cleanUrl = url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
        if (urlIcons.containsKey(cleanUrl) || !inFlight.add(cleanUrl)) return
        coverPool.submit {
            try {
                val hash = sha1(cleanUrl)
                val cachedFile = File(cacheDir, "url_$hash.png")
                val cachedImg = if (cachedFile.exists()) {
                    runCatching { ImageIO.read(cachedFile) }.getOrNull()
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
                    urlIcons[cleanUrl] = icon
                    SwingUtilities.invokeLater(onLoaded)
                }
            } finally {
                inFlight.remove(cleanUrl)
            }
        }
    }

    private fun renderForManga(manga: Manga, chapterPath: String?): BufferedImage? {
        val candidatePath = chapterPath ?: manga.url
        val localFile = File(candidatePath)
        if (localFile.exists() && ArchiveReaderFactory.isSupported(localFile)) {
            runCatching {
                ArchiveReaderFactory.of(localFile).use { reader ->
                    val first = reader.pages.firstOrNull() ?: return@use null
                    reader.getPageImage(first)?.let { return scaleAndRound(it) }
                }
            }
        }
        val thumbUrl = manga.thumbnailUrl?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return null
        return downloadCover(thumbUrl)?.let { scaleAndRound(it) }
    }

    private fun downloadCover(url: String): BufferedImage? = runCatching {
        val parsed = url.trim().replace(" ", "%20").toHttpUrl()
        val req = Request.Builder()
            .url(parsed)
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .header("Referer", "${parsed.scheme}://${parsed.host}/")
            .header("Cookie", "tia_idade_confirmada=1; wpmanga-adault=1")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            ImageDecoder.decode(bytes)
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

        // Left book-spine crease effect (authentic Tankobon volume feel)
        g.color = Color(255, 255, 255, 38)
        g.drawLine(4, 0, 4, COVER_H)
        g.color = Color(0, 0, 0, 110)
        g.drawLine(5, 0, 5, COVER_H)

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
        g.color = Color(0x18, 0x18, 0x1E)
        g.fillRoundRect(0, 0, COVER_W, COVER_H, COVER_ARC, COVER_ARC)

        g.color = Color(0xE0, 0x4F, 0x3E, 45)
        g.fillRect(0, 0, 6, COVER_H)

        g.color = Color(242, 239, 233, 55)
        g.font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 38f)
        val fm = g.fontMetrics
        g.drawString(initials, (COVER_W - fm.stringWidth(initials)) / 2, (COVER_H + fm.ascent) / 2 - 8)

        g.color = Color(242, 239, 233, 28)
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
