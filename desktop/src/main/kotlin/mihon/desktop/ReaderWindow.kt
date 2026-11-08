package mihon.desktop

import mihon.core.archive.ArchiveReader
import mihon.core.archive.ArchiveReaderFactory
import mihon.core.archive.ImageDecoder
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.prefs.Preferences
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JViewport
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.max
import kotlin.math.min

enum class ReadMode(val label: String) {
    SINGLE("Página única"),
    DOUBLE_RTL("Página dupla (mangá, D→E)"),
    DOUBLE_LTR("Página dupla (quadrinhos, E→D)"),
    WEBTOON("Webtoon contínuo"),
    CONTINUOUS_VERTICAL("Vertical com espaçamento"),
}

enum class FitMode(val label: String) {
    WINDOW("Ajustar à janela"),
    WIDTH("Ajustar à largura"),
    ORIGINAL("Tamanho real"),
}

/** Fast multi-step bilinear fallback scaler. */
internal fun scaleImageFast(src: BufferedImage, targetW: Int, targetH: Int): BufferedImage {
    fun resize(img: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(img, 0, 0, w, h, Color.WHITE, null)
            dispose()
        }
        return out
    }

    var cur = src
    var w = src.width
    var h = src.height
    while (w / 2 >= targetW && h / 2 >= targetH) {
        w /= 2
        h /= 2
        cur = resize(cur, w, h)
    }
    return if (w == targetW && h == targetH) cur else resize(cur, targetW, targetH)
}

/**
 * High-precision manga scaler using Lanczos-3 resampling + Anime4K Line-Art reconstruction & CAS.
 */
internal fun scaleImage(
    src: BufferedImage,
    targetW: Int,
    targetH: Int,
    quality: MangaImageEnhancer.QualityMode = MangaImageEnhancer.QualityMode.ULTRA_HD,
): BufferedImage = MangaImageEnhancer.scale(src, targetW, targetH, quality)

/**
 * Algorithmic reader engine for double-page spreads, wide-page splitting,
 * dynamic cache memory budgeting, and webtoon strip layout calculations.
 */
object ReaderEngine {

    /**
     * Determines which pages are displayed together in a spread starting at [p].
     * Page 0 (cover) is strictly isolated and never paired.
     * Wide pages (W > H) are isolated as lone spreads.
     * For double page modes (DOUBLE_RTL, DOUBLE_LTR), pairs [p] and [p + 1] only if
     * neither is wide and their aspect ratios match within 0.18 tolerance.
     */
    fun spreadAt(
        p: Int,
        mode: ReadMode,
        pageCount: Int,
        isWide: (Int) -> Boolean,
        aspectRatio: ((Int) -> Double?)? = null,
    ): List<Int> {
        if (p !in 0 until pageCount) return emptyList()
        if (mode != ReadMode.DOUBLE_RTL && mode != ReadMode.DOUBLE_LTR) return listOf(p)
        if (p == 0) return listOf(0)
        if (isWide(p)) return listOf(p)

        val n = p + 1
        if (n >= pageCount || isWide(n)) return listOf(p)

        if (aspectRatio != null) {
            val ar1 = aspectRatio(p)
            val ar2 = aspectRatio(n)
            if (ar1 != null && ar2 != null && kotlin.math.abs(ar1 - ar2) > 0.18) {
                return listOf(p)
            }
        }
        return listOf(p, n)
    }

    /**
     * Finds the spread start index that precedes [currentPage] by iterating spreads forward.
     */
    fun prevSpreadStart(
        currentPage: Int,
        mode: ReadMode,
        pageCount: Int,
        isWide: (Int) -> Boolean,
        aspectRatio: ((Int) -> Double?)? = null,
    ): Int {
        if (currentPage <= 0) return 0
        if (mode != ReadMode.DOUBLE_RTL && mode != ReadMode.DOUBLE_LTR) {
            return (currentPage - 1).coerceAtLeast(0)
        }
        var curr = 0
        var prev = 0
        while (curr < currentPage) {
            prev = curr
            val spread = spreadAt(curr, mode, pageCount, isWide, aspectRatio)
            curr += max(1, spread.size)
        }
        return prev
    }

    /**
     * Splits a wide image into two half pages according to reading direction.
     * For RTL (manga): Part 0 (first half) is Right half, Part 1 (second half) is Left half.
     * For LTR (comics/default): Part 0 (first half) is Left half, Part 1 (second half) is Right half.
     */
    fun getHalfPage(src: BufferedImage, isSecondHalf: Boolean, isRtl: Boolean): BufferedImage {
        val halfW = src.width / 2
        val h = src.height
        if (halfW <= 0) return src
        val xOffset = when {
            !isRtl && !isSecondHalf -> 0
            !isRtl && isSecondHalf -> halfW
            isRtl && !isSecondHalf -> halfW
            else -> 0
        }
        return src.getSubimage(xOffset, 0, halfW, h)
    }

    /**
     * Splits a wide image and stacks top and bottom halves vertically for webtoon display.
     */
    fun splitAndMerge(src: BufferedImage, isRtl: Boolean): BufferedImage {
        val halfW = src.width / 2
        val h = src.height
        if (halfW <= 0) return src
        val merged = BufferedImage(halfW, h * 2, BufferedImage.TYPE_INT_RGB)
        merged.createGraphics().apply {
            val topHalf = if (isRtl) src.getSubimage(halfW, 0, halfW, h) else src.getSubimage(0, 0, halfW, h)
            val bottomHalf = if (isRtl) src.getSubimage(0, 0, halfW, h) else src.getSubimage(halfW, 0, halfW, h)
            drawImage(topHalf, 0, 0, null)
            drawImage(bottomHalf, 0, h, null)
            dispose()
        }
        return merged
    }

    /**
     * Calculates maximum memory cache entries bounded between [minEntries] and [maxEntries]
     * based on available JVM max heap (allocating ~48MB per high-resolution cached image).
     */
    fun calculateMaxCacheEntries(
        maxMemoryBytes: Long = Runtime.getRuntime().maxMemory(),
        minEntries: Int = 8,
        maxEntries: Int = 32,
    ): Int {
        val maxHeapMb = maxMemoryBytes / (1024 * 1024)
        val entries = (maxHeapMb / 48).toInt()
        return entries.coerceIn(minEntries, maxEntries)
    }

    /**
     * Computes webtoon page layout offsets (tops) and heights with optional inter-page gap.
     */
    fun computeWebtoonLayout(
        pageCount: Int,
        pageWidthFn: (Int) -> Int,
        pageHeightFn: (Int, Int) -> Int,
        pageGap: Int = 0,
    ): Pair<IntArray, IntArray> {
        val tops = IntArray(pageCount + 1)
        val heights = IntArray(pageCount)
        var y = 0
        for (i in 0 until pageCount) {
            val w = pageWidthFn(i)
            val h = pageHeightFn(i, w)
            tops[i] = y
            heights[i] = h
            y += h + if (i < pageCount - 1) pageGap else 0
        }
        tops[pageCount] = y
        return tops to heights
    }

    /**
     * Performs binary search to find which webtoon page contains vertical position [y].
     */
    fun pageAt(y: Int, tops: IntArray, pageCount: Int): Int {
        if (pageCount <= 0 || tops.isEmpty()) return 0
        if (y <= tops[0]) return 0
        if (y >= tops[pageCount - 1]) return pageCount - 1
        var lo = 0
        var hi = pageCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (tops[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }
}

enum class AutoScrollState {
    STOPPED,
    RUNNING,
    PAUSED_HOVER,
    PAUSED_MANUAL,
}

class AutoScrollController(
    private val scroll: JScrollPane,
    var onStateChanged: (AutoScrollState, Int) -> Unit = { _, _ -> },
    var onReachedEnd: () -> Unit = {},
) {
    companion object {
        const val FRICTION_DAMPING: Double = 0.88
        const val MIN_SPEED_LEVEL: Int = 1
        const val MAX_SPEED_LEVEL: Int = 10
        const val DEFAULT_SPEED_LEVEL: Int = 3

        /** Calculates base pixels/second: v_base(L) = 14.0 * L^1.15 */
        fun calculateBaseSpeed(level: Int): Double {
            val clamped = level.coerceIn(MIN_SPEED_LEVEL, MAX_SPEED_LEVEL)
            return 14.0 * Math.pow(clamped.toDouble(), 1.15)
        }
    }

    var state: AutoScrollState = AutoScrollState.STOPPED
        internal set

    var speedLevel: Int = DEFAULT_SPEED_LEVEL
        internal set

    var subPixelAccumulator: Double = 0.0
        internal set

    var kineticVelocity: Double = 0.0
        internal set

    var pauseOnHover: Boolean = true
    var timerEnabled: Boolean = true

    private val timer = Timer(16) {
        tick(1.0 / 60.0)
    }

    fun tick(dt: Double = 1.0 / 60.0) {
        if (state != AutoScrollState.RUNNING) return
        val basePxPerSec = calculateBaseSpeed(speedLevel)
        val frameVelocity = (basePxPerSec * dt) + kineticVelocity
        kineticVelocity *= FRICTION_DAMPING
        if (kotlin.math.abs(kineticVelocity) < 0.05) kineticVelocity = 0.0

        subPixelAccumulator += frameVelocity
        val step = subPixelAccumulator.toInt()
        if (step != 0) {
            subPixelAccumulator -= step
            val bar = scroll.verticalScrollBar
            val viewportH = if (scroll.viewport.height > 0) scroll.viewport.height else scroll.height
            val maxVal = if (bar.maximum > viewportH) bar.maximum - viewportH else bar.maximum
            if (maxVal <= 0) return

            val nextVal = bar.value + step
            if (nextVal >= maxVal && step > 0) {
                bar.value = maxVal
                stop()
                onReachedEnd()
            } else {
                bar.value = nextVal.coerceIn(0, maxVal)
            }
        }
    }

    fun toggle() {
        when (state) {
            AutoScrollState.RUNNING -> pause(manual = true)
            AutoScrollState.PAUSED_MANUAL, AutoScrollState.PAUSED_HOVER, AutoScrollState.STOPPED -> start()
        }
    }

    fun start() {
        state = AutoScrollState.RUNNING
        if (timerEnabled && !timer.isRunning) timer.start()
        onStateChanged(state, speedLevel)
    }

    fun pause(manual: Boolean = true) {
        state = if (manual) AutoScrollState.PAUSED_MANUAL else AutoScrollState.PAUSED_HOVER
        timer.stop()
        onStateChanged(state, speedLevel)
    }

    fun pauseHover() {
        if (state == AutoScrollState.RUNNING) {
            pause(manual = false)
        }
    }

    fun resumeHover() {
        if (state == AutoScrollState.PAUSED_HOVER) {
            start()
        }
    }

    fun stop() {
        state = AutoScrollState.STOPPED
        timer.stop()
        kineticVelocity = 0.0
        subPixelAccumulator = 0.0
        onStateChanged(state, speedLevel)
    }

    fun adjustSpeed(delta: Int) {
        speedLevel = (speedLevel + delta).coerceIn(MIN_SPEED_LEVEL, MAX_SPEED_LEVEL)
        onStateChanged(state, speedLevel)
    }

    fun setSpeed(level: Int) {
        speedLevel = level.coerceIn(MIN_SPEED_LEVEL, MAX_SPEED_LEVEL)
        onStateChanged(state, speedLevel)
    }

    fun addImpulse(deltaY: Double) {
        kineticVelocity += deltaY
    }
}

/**
 * Floating HUD Speed Pill widget displaying speed indicator, pause/play toggle,
 * speed up/down buttons, and hotkey hints.
 */
class FloatingSpeedPill(
    private val controller: AutoScrollController,
) : JPanel() {
    private val playPauseBtn = JButton("⏸").apply {
        isFocusable = false
        font = font.deriveFont(Font.BOLD, 12f)
        toolTipText = "Pausar / Retomar rolagem automática (Espaço / A)"
        addActionListener { controller.toggle() }
    }
    private val minusBtn = JButton("−").apply {
        isFocusable = false
        font = font.deriveFont(Font.BOLD, 12f)
        toolTipText = "Diminuir velocidade ([ ou ↑)"
        addActionListener { controller.adjustSpeed(-1) }
    }
    private val speedLbl = JLabel(" ").apply {
        font = font.deriveFont(Font.BOLD, 12f)
        foreground = Color(245, 245, 245)
    }
    private val plusBtn = JButton("+").apply {
        isFocusable = false
        font = font.deriveFont(Font.BOLD, 12f)
        toolTipText = "Aumentar velocidade (] ou ↓)"
        addActionListener { controller.adjustSpeed(1) }
    }
    private val hintLbl = JLabel("[Espaço] Pausar • [↑/↓] Vel • [Esc] Parar").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = Color(175, 180, 190)
    }
    private val stopBtn = JButton("✕").apply {
        isFocusable = false
        font = font.deriveFont(Font.BOLD, 11f)
        toolTipText = "Parar rolagem automática (Esc)"
        addActionListener { controller.stop() }
    }

    init {
        isOpaque = false
        layout = FlowLayout(FlowLayout.CENTER, 8, 4)
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
        add(playPauseBtn)
        add(minusBtn)
        add(speedLbl)
        add(plusBtn)
        add(Box.createHorizontalStrut(6))
        add(hintLbl)
        add(Box.createHorizontalStrut(4))
        add(stopBtn)

        updateUi(controller.state, controller.speedLevel)
    }

    fun updateUi(state: AutoScrollState, speedLevel: Int) {
        val pxPerSec = AutoScrollController.calculateBaseSpeed(speedLevel).toInt()
        val isRunning = state == AutoScrollState.RUNNING
        playPauseBtn.text = if (isRunning) "⏸" else "▶"
        val statePrefix = when (state) {
            AutoScrollState.RUNNING -> "Rolando"
            AutoScrollState.PAUSED_HOVER -> "Pausado (Cursor)"
            AutoScrollState.PAUSED_MANUAL -> "Pausado"
            AutoScrollState.STOPPED -> "Parado"
        }
        speedLbl.text = "$statePrefix: ${speedLevel}x (~$pxPerSec px/s)"
        isVisible = (state != AutoScrollState.STOPPED)
        revalidate()
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = Color(20, 22, 28, 230)
        g2.fillRoundRect(0, 0, width, height, 22, 22)
        g2.color = Theme.BORDER_SUBTLE
        g2.drawRoundRect(0, 0, width - 1, height - 1, 22, 22)
        g2.dispose()
        super.paintComponent(g)
    }
}

/**
 * Reads a .cbz/.zip file or an image folder.
 *
 * Modes: single page, double page (right-to-left) and webtoon (continuous scroll), each with
 * fit-to-window / fit-to-width / original size. Choices are remembered between sessions.
 *
 * Keys: ←/→ or PgUp/PgDn/Space navigate (webtoon: scroll), ↑/↓ scroll, Home/End jump,
 * F11 fullscreen, Esc leaves fullscreen or closes.
 *
 * [onProgress] receives the last page reached and the page count (debounced while reading,
 * and once more when the window closes).
 */
class ReaderWindow(
    title: String,
    source: Any, // File or ArchiveReader
    startPage: Int = 0,
    private val onPrevChapter: (() -> Unit)? = null,
    private val onNextChapter: (() -> Unit)? = null,
    private val onProgress: (page: Int, pageCount: Int) -> Unit = { _, _ -> },
) : JFrame("Mihon Linux - $title") {

    private val reader: ArchiveReader = when (source) {
        is ArchiveReader -> source
        is File -> ArchiveReaderFactory.of(source)
        else -> throw IllegalArgumentException("Fonte não suportada: $source")
    }
    private val pageCount = reader.pageCount
    private val prefs = Preferences.userRoot().node("mihon-linux-reader")

    private var mode = runCatching { ReadMode.valueOf(prefs.get("mode", "SINGLE")) }.getOrDefault(ReadMode.SINGLE)
    private var fit = runCatching { FitMode.valueOf(prefs.get("fit", "WINDOW")) }.getOrDefault(FitMode.WINDOW)
    private var quality = runCatching {
        val qStr = prefs.get("quality", "ULTRA_HD")
        if (qStr in setOf("FAST", "BILINEAR")) MangaImageEnhancer.QualityMode.BILINEAR else MangaImageEnhancer.QualityMode.ULTRA_HD
    }.getOrDefault(MangaImageEnhancer.QualityMode.ULTRA_HD)
    private var colorFilter = runCatching {
        MangaImageEnhancer.ColorFilterMode.valueOf(prefs.get("colorFilter", "NONE"))
    }.getOrDefault(MangaImageEnhancer.ColorFilterMode.NONE)
    private var splitWidePages = prefs.getBoolean("splitWidePages", true)
    private var splitWideRtl = prefs.getBoolean("splitWideRtl", true)
    private var currentSubPage = 0
    private var webtoonSideMarginPercent = prefs.getInt("webtoonSideMarginPercent", 0).coerceIn(0, 35)

    private val isWebtoon get() = mode == ReadMode.WEBTOON || mode == ReadMode.CONTINUOUS_VERTICAL
    private val isDouble get() = mode == ReadMode.DOUBLE_RTL || mode == ReadMode.DOUBLE_LTR

    /** Natural size of each page; filled in the background (webtoon layout and wide-page detection). */
    private val sizes = arrayOfNulls<Dimension>(pageCount)
    private var currentPage = startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    private var reportedPage = currentPage

    /** Bounded thread pool for parallel page decoding and scaling. */
    private val io = Executors.newFixedThreadPool(3) { r -> Thread(r, "reader-io").apply { isDaemon = true } }
    private val readerLock = Any()

    @Volatile private var closed = false
    private var generation = 0
    private var fullscreen = false
    private var togglingFullscreen = false
    private var zoomFactor = 1.0

    /** In-flight decoding, scaling, and prefetch jobs for responsive cancellation on rapid navigation. */
    private val inFlightTasks = ConcurrentHashMap<Int, Future<*>>()

    /** Dynamic memory cache bounded between 8 and 32 images according to available JVM heap. */
    private val maxCacheEntries = ReaderEngine.calculateMaxCacheEntries()
    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<Int, BufferedImage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, BufferedImage>): Boolean {
            val evict = size > maxCacheEntries
            if (evict) {
                runCatching { eldest.value.flush() }
            }
            return evict
        }
    }

    private fun getCachedImage(index: Int): BufferedImage? = synchronized(cacheLock) { cache[index] }
    private fun putCachedImage(index: Int, image: BufferedImage) { synchronized(cacheLock) { cache[index] = image } }
    private fun isPageCached(index: Int): Boolean = synchronized(cacheLock) { cache.containsKey(index) }

    private fun cancelDistantTasks(centerPage: Int, radiusAhead: Int = 4, radiusBehind: Int = 2) {
        val minP = (centerPage - radiusBehind).coerceAtLeast(0)
        val maxP = (centerPage + radiusAhead).coerceAtMost(pageCount - 1)
        val it = inFlightTasks.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val p = entry.key
            if (p < minP || p > maxP) {
                entry.value.cancel(true)
                it.remove()
            }
        }
    }

    private val pageLabel = JLabel(" ")
    private var updatingSlider = false
    private val pageSlider = JSlider(1, max(pageCount, 1), (currentPage + 1).coerceIn(1, max(pageCount, 1))).apply {
        isFocusable = false
        isOpaque = false
        addChangeListener {
            if (!updatingSlider && !valueIsAdjusting) {
                goTo(value - 1)
            }
        }
    }
    private val topHud: JPanel
    private val bottomHud: JPanel
    private val paged = PagedView()
    private val webtoon = WebtoonView()
    private val scroll = JScrollPane(paged)

    private val autoScrollBtn: JButton = JButton("Auto-Scroll (A)").apply {
        isFocusable = false
        toolTipText = "Rolagem suave contínua mãos-livres (Espaço ou A)"
    }
    private val autoScroll: AutoScrollController = AutoScrollController(
        scroll = scroll,
        onStateChanged = { state, speed ->
            speedPill.updateUi(state, speed)
            autoScrollBtn.text = if (state == AutoScrollState.RUNNING) "Auto-Scroll: ${speed}x" else "Auto-Scroll (A)"
        },
        onReachedEnd = {
            if (onNextChapter != null) {
                val timer = Timer(1200) {
                    dispose()
                    onNextChapter.invoke()
                }.apply { isRepeats = false; start() }
            }
        },
    )
    private val speedPill: FloatingSpeedPill = FloatingSpeedPill(autoScroll)
    private val hoverResumeTimer: Timer = Timer(1200) {
        if (autoScroll.state == AutoScrollState.PAUSED_HOVER) {
            autoScroll.resumeHover()
        }
    }.apply { isRepeats = false }

    private val progressTimer = Timer(700) { onProgress(reportedPage, pageCount) }.apply { isRepeats = false }

    private fun syncPageUi(pageIdx: Int, labelText: String) {
        pageLabel.text = labelText
        if (pageCount > 0) {
            updatingSlider = true
            pageSlider.maximum = pageCount
            pageSlider.value = (pageIdx + 1).coerceIn(1, pageCount)
            updatingSlider = false
        }
    }

    private fun toggleHud() {
        val visible = !topHud.isVisible
        topHud.isVisible = visible
        bottomHud.isVisible = visible
        contentPane.revalidate()
        contentPane.repaint()
    }

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        setSize(1000, 1000)
        setLocationRelativeTo(null)

        scroll.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS
        scroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        scroll.verticalScrollBar.unitIncrement = 32
        scroll.border = null
        scroll.isFocusable = false
        scroll.viewport.isFocusable = false
        scroll.verticalScrollBar.isFocusable = false
        scroll.horizontalScrollBar.isFocusable = false

        val modeBox = JComboBox(ReadMode.entries.map { it.label }.toTypedArray()).apply {
            selectedIndex = mode.ordinal
            isFocusable = false
        }
        val fitBox = JComboBox(FitMode.entries.map { it.label }.toTypedArray()).apply {
            selectedIndex = fit.ordinal
            isFocusable = false
        }
        val qualityBox = JComboBox(MangaImageEnhancer.QualityMode.entries.map { it.label }.toTypedArray()).apply {
            selectedIndex = quality.ordinal
            isFocusable = false
            toolTipText = "Algoritmo de redimensionamento e reconstrução de traços (Lanczos-3 + Anime4K Line-Art)"
        }
        val filterBox = JComboBox(MangaImageEnhancer.ColorFilterMode.entries.map { it.label }.toTypedArray()).apply {
            selectedIndex = colorFilter.ordinal
            isFocusable = false
            toolTipText = "Filtro de leitura (Grayscale, Noturno Invertido, Sépia)"
        }
        fun button(text: String, action: () -> Unit) = JButton(text).apply {
            isFocusable = false
            addActionListener { action() }
        }
        val marginBtn = JButton("Margem: ${webtoonSideMarginPercent}%").apply {
            isFocusable = false
            toolTipText = "Margem lateral em Webtoon para monitores ultrawide (0%, 15%, 25%, 35%)"
            isVisible = isWebtoon
            addActionListener {
                webtoonSideMarginPercent = when (webtoonSideMarginPercent) {
                    0 -> 15
                    15 -> 25
                    25 -> 35
                    else -> 0
                }
                text = "Margem: ${webtoonSideMarginPercent}%"
                prefs.putInt("webtoonSideMarginPercent", webtoonSideMarginPercent)
                if (isWebtoon) webtoon.relayout()
            }
        }
        val splitBtn = JButton(if (splitWidePages) "Divisão: Ligada" else "Divisão: Desligada").apply {
            isFocusable = false
            toolTipText = "Divisão automática de páginas duplas em páginas normais sequenciais"
            isVisible = !isWebtoon
            addActionListener {
                splitWidePages = !splitWidePages
                text = if (splitWidePages) "Divisão: Ligada" else "Divisão: Desligada"
                prefs.putBoolean("splitWidePages", splitWidePages)
                if (!isWebtoon) showSpread()
            }
        }
        autoScrollBtn.addActionListener {
            if (!isWebtoon) {
                mode = ReadMode.WEBTOON
                modeBox.selectedIndex = mode.ordinal
                prefs.put("mode", mode.name)
                marginBtn.isVisible = isWebtoon
                splitBtn.isVisible = !isWebtoon
                applyView()
            }
            autoScroll.toggle()
        }

        modeBox.addActionListener {
            mode = ReadMode.entries[modeBox.selectedIndex]
            prefs.put("mode", mode.name)
            marginBtn.isVisible = isWebtoon
            splitBtn.isVisible = !isWebtoon
            if (!isWebtoon && autoScroll.state != AutoScrollState.STOPPED) {
                autoScroll.stop()
            }
            applyView()
        }
        fitBox.addActionListener {
            fit = FitMode.entries[fitBox.selectedIndex]
            prefs.put("fit", fit.name)
            applyView()
        }
        qualityBox.addActionListener {
            quality = MangaImageEnhancer.QualityMode.entries[qualityBox.selectedIndex]
            prefs.put("quality", quality.name)
            webtoon.invalidateScaledCache()
            applyView()
        }
        filterBox.addActionListener {
            colorFilter = MangaImageEnhancer.ColorFilterMode.entries[filterBox.selectedIndex]
            prefs.put("colorFilter", colorFilter.name)
            paged.invalidateCache()
            webtoon.invalidateScaledCache()
            applyView()
        }
        topHud = JPanel(FlowLayout(FlowLayout.CENTER, 8, 6)).apply {
            background = Color(18, 19, 24, 240)
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SUBTLE)
            add(modeBox)
            add(fitBox)
            add(qualityBox)
            add(filterBox)
            add(marginBtn)
            add(splitBtn)
            add(autoScrollBtn)
            add(button("−") { adjustZoom(0.85) })
            add(button("100%") { resetZoom() })
            add(button("+") { adjustZoom(1.18) })
            add(button("Tela cheia (F11)") { toggleFullscreen() })
            add(button("Ocultar HUD (H)") { toggleHud() })
        }

        // Mihon BottomReaderBar + ReaderPageIndicator + Slider
        bottomHud = JPanel(BorderLayout(12, 0)).apply {
            background = Color(18, 19, 24, 240)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(6, 16, 6, 16),
            )
            val leftNav = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                if (onPrevChapter != null) {
                    add(button("⏮ Cap. Ant.") { dispose(); onPrevChapter.invoke() })
                }
                add(button("◀") { prev() })
            }
            val rightNav = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(pageLabel)
                add(button("▶") { next() })
                if (onNextChapter != null) {
                    add(button("Próx. Cap. ⏭") { dispose(); onNextChapter.invoke() })
                }
            }
            add(leftNav, BorderLayout.WEST)
            add(pageSlider, BorderLayout.CENTER)
            add(rightNav, BorderLayout.EAST)
        }

        val centerLayered = JLayeredPane().apply {
            add(scroll, JLayeredPane.DEFAULT_LAYER)
            add(speedPill, JLayeredPane.POPUP_LAYER)
        }
        centerLayered.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val w = centerLayered.width
                val h = centerLayered.height
                scroll.setBounds(0, 0, w, h)
                val pillW = speedPill.preferredSize.width.coerceAtLeast(360)
                val pillH = speedPill.preferredSize.height.coerceAtLeast(36)
                speedPill.setBounds(
                    (w - pillW) / 2,
                    (h - pillH - 18).coerceAtLeast(10),
                    pillW,
                    pillH,
                )
            }
        })

        contentPane.add(topHud, BorderLayout.NORTH)
        contentPane.add(centerLayered, BorderLayout.CENTER)
        contentPane.add(bottomHud, BorderLayout.SOUTH)

        val hoverMotion = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                if (autoScroll.pauseOnHover) {
                    if (autoScroll.state == AutoScrollState.RUNNING) {
                        autoScroll.pauseHover()
                    }
                    if (autoScroll.state == AutoScrollState.PAUSED_HOVER) {
                        hoverResumeTimer.restart()
                    }
                }
            }
        }
        scroll.viewport.addMouseMotionListener(hoverMotion)
        paged.addMouseMotionListener(hoverMotion)
        webtoon.addMouseMotionListener(hoverMotion)

        scroll.addMouseWheelListener { e ->
            if (e.isControlDown) {
                e.consume()
                adjustZoom(if (e.wheelRotation < 0) 1.12 else 0.89)
            } else if (autoScroll.state != AutoScrollState.STOPPED) {
                autoScroll.addImpulse(e.preciseWheelRotation * 18.0)
            }
        }

        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_A -> {
                        if (!isWebtoon) {
                            mode = ReadMode.WEBTOON
                            modeBox.selectedIndex = mode.ordinal
                            prefs.put("mode", mode.name)
                            marginBtn.isVisible = isWebtoon
                            splitBtn.isVisible = !isWebtoon
                            applyView()
                        }
                        autoScroll.toggle()
                    }
                    KeyEvent.VK_SPACE -> {
                        if (isWebtoon || autoScroll.state != AutoScrollState.STOPPED) {
                            autoScroll.toggle()
                        } else {
                            next()
                        }
                    }
                    KeyEvent.VK_RIGHT, KeyEvent.VK_PAGE_DOWN -> next()
                    KeyEvent.VK_LEFT, KeyEvent.VK_PAGE_UP -> prev()
                    KeyEvent.VK_DOWN -> {
                        if (autoScroll.state != AutoScrollState.STOPPED) {
                            autoScroll.adjustSpeed(1)
                        } else {
                            scrollBy(64)
                        }
                    }
                    KeyEvent.VK_UP -> {
                        if (autoScroll.state != AutoScrollState.STOPPED) {
                            autoScroll.adjustSpeed(-1)
                        } else {
                            scrollBy(-64)
                        }
                    }
                    KeyEvent.VK_HOME -> goTo(0)
                    KeyEvent.VK_END -> goTo(pageCount - 1)
                    KeyEvent.VK_H, KeyEvent.VK_M -> toggleHud()
                    KeyEvent.VK_PLUS, KeyEvent.VK_EQUALS, KeyEvent.VK_ADD -> if (e.isControlDown) adjustZoom(1.15)
                    KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> if (e.isControlDown) adjustZoom(0.87)
                    KeyEvent.VK_0 -> if (e.isControlDown) resetZoom()
                    KeyEvent.VK_OPEN_BRACKET -> {
                        if (autoScroll.state != AutoScrollState.STOPPED) {
                            autoScroll.adjustSpeed(-1)
                        } else if (onPrevChapter != null) {
                            dispose()
                            onPrevChapter.invoke()
                        }
                    }
                    KeyEvent.VK_CLOSE_BRACKET -> {
                        if (autoScroll.state != AutoScrollState.STOPPED) {
                            autoScroll.adjustSpeed(1)
                        } else if (onNextChapter != null) {
                            dispose()
                            onNextChapter.invoke()
                        }
                    }
                    KeyEvent.VK_F11 -> toggleFullscreen()
                    KeyEvent.VK_ESCAPE -> {
                        if (autoScroll.state != AutoScrollState.STOPPED) {
                            autoScroll.stop()
                        } else if (fullscreen) {
                            toggleFullscreen()
                        } else {
                            dispose()
                        }
                    }
                }
            }
        })
        scroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val vw = scroll.viewport.width
                if (isWebtoon) {
                    if (vw > 0 && vw != webtoon.layoutWidth) webtoon.relayout()
                } else {
                    paged.revalidate()
                    paged.repaint()
                }
            }
        })
        scroll.verticalScrollBar.addAdjustmentListener { if (isWebtoon) webtoon.updateVisible() }
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                if (togglingFullscreen) return
                closed = true
                autoScroll.stop()
                hoverResumeTimer.stop()
                progressTimer.stop()
                onProgress(reportedPage, pageCount)
                inFlightTasks.values.forEach { runCatching { it.cancel(true) } }
                inFlightTasks.clear()
                io.shutdownNow()
                reader.close()
                synchronized(cacheLock) {
                    cache.values.forEach { runCatching { it.flush() } }
                    cache.clear()
                }
                webtoon.invalidateScaledCache()
                paged.invalidateCache()
                paged.setContent(emptyList(), null)
                System.gc()
            }
        })

        if (pageCount == 0) {
            paged.setContent(emptyList(), "Nenhuma imagem encontrada")
            pageLabel.text = "Página 0/0"
        } else {
            applyView()
            SwingUtilities.invokeLater { readSizes() }
        }
        isVisible = true
        requestFocus()
    }

    // --- Navigation ---------------------------------------------------------------------------

    private fun isWide(i: Int) = sizes[i]?.let { it.width > it.height } ?: false
    private fun aspectRatio(i: Int) = sizes[i]?.let { if (it.height > 0) it.width.toDouble() / it.height else null }

    /** Pages shown together when a spread starts at [p]. */
    private fun spreadAt(p: Int): List<Int> =
        ReaderEngine.spreadAt(p, mode, pageCount, ::isWide, ::aspectRatio)

    private fun prevSpreadStart(): Int =
        ReaderEngine.prevSpreadStart(currentPage, mode, pageCount, ::isWide, ::aspectRatio)

    private fun next() {
        if (isWebtoon) {
            scrollBy(scroll.viewport.height * 9 / 10)
        } else if (mode == ReadMode.SINGLE && splitWidePages && isWide(currentPage) && currentSubPage == 0) {
            currentSubPage = 1
            showSpread()
        } else {
            currentSubPage = 0
            goTo(currentPage + spreadAt(currentPage).size)
        }
    }

    private fun prev() {
        if (isWebtoon) {
            scrollBy(-scroll.viewport.height * 9 / 10)
        } else if (mode == ReadMode.SINGLE && splitWidePages && isWide(currentPage) && currentSubPage == 1) {
            currentSubPage = 0
            showSpread()
        } else {
            val prevIdx = prevSpreadStart()
            if (mode == ReadMode.SINGLE && splitWidePages && isWide(prevIdx) && prevIdx != currentPage) {
                currentSubPage = 1
                currentPage = prevIdx
                showSpread()
            } else {
                currentSubPage = 0
                goTo(prevIdx)
            }
        }
    }

    private fun scrollBy(dy: Int) {
        scroll.verticalScrollBar.value += dy
    }

    private fun adjustZoom(factor: Double) {
        zoomFactor = (zoomFactor * factor).coerceIn(0.4, 3.5)
        if (isWebtoon) webtoon.relayout() else { paged.revalidate(); paged.repaint() }
    }

    private fun resetZoom() {
        zoomFactor = 1.0
        if (isWebtoon) webtoon.relayout() else { paged.revalidate(); paged.repaint() }
    }

    private fun goTo(page: Int) {
        if (pageCount == 0) return
        val target = page.coerceIn(0, pageCount - 1)
        if (isWebtoon) {
            webtoon.scrollToPage(target)
            return
        }
        currentSubPage = 0
        if (target == currentPage) {
            showSpread()
            return
        }
        currentPage = target
        cancelDistantTasks(currentPage, radiusAhead = 4, radiusBehind = 2)
        showSpread()
    }

    private fun reportProgress(page: Int) {
        reportedPage = page
        progressTimer.restart()
    }

    private fun applyView() {
        generation++
        currentSubPage = 0
        if (isWebtoon) {
            scroll.setViewportView(webtoon)
            webtoon.relayout(currentPage)
        } else {
            scroll.setViewportView(paged)
            showSpread()
        }
    }

    // --- Single / double page ------------------------------------------------------------------

    private fun readPageFromArchive(index: Int): BufferedImage? =
        runCatching { synchronized(readerLock) { reader.getPageImage(reader.pages[index]) } }.getOrNull()

    private fun showSpread() {
        val myGeneration = ++generation
        val indexes = spreadAt(currentPage)
        val isWideSingle = mode == ReadMode.SINGLE && splitWidePages && isWide(currentPage)
        val lbl = when {
            indexes.size == 2 -> "Páginas ${indexes[0] + 1}-${indexes[1] + 1}/$pageCount"
            isWideSingle -> "Página ${currentPage + 1} (${currentSubPage + 1}/2)/$pageCount"
            else -> "Página ${indexes.firstOrNull()?.let { it + 1 } ?: (currentPage + 1)}/$pageCount"
        }
        syncPageUi(indexes.firstOrNull() ?: currentPage, lbl)
        reportProgress(indexes.lastOrNull() ?: currentPage)

        fun finish() {
            if (myGeneration != generation || closed) return
            val images = indexes.map { getCachedImage(it) }
            if (images.any { it == null }) {
                paged.setContent(emptyList(), "Não foi possível decodificar a página")
            } else {
                val contentImages = if (isWideSingle && images.firstOrNull() != null) {
                    val half = ReaderEngine.getHalfPage(
                        images.first()!!,
                        isSecondHalf = (currentSubPage == 1),
                        isRtl = splitWideRtl,
                    )
                    listOf(half)
                } else if (mode == ReadMode.DOUBLE_RTL) {
                    images.filterNotNull().reversed()
                } else {
                    images.filterNotNull()
                }
                paged.setContent(contentImages, null)
                scroll.verticalScrollBar.value = 0
                scroll.horizontalScrollBar.value = 0
            }
            cancelDistantTasks(indexes.lastOrNull() ?: currentPage, radiusAhead = 4, radiusBehind = 2)
            for (ahead in 1..4) {
                prefetch((indexes.lastOrNull() ?: currentPage) + ahead)
            }
            for (behind in 1..2) {
                prefetch((indexes.firstOrNull() ?: currentPage) - behind)
            }
        }

        val missing = indexes.filter { !isPageCached(it) }
        if (missing.isEmpty()) {
            finish()
            return
        }
        paged.setContent(emptyList(), "Carregando…")
        val future = io.submit {
            try {
                if (closed || Thread.currentThread().isInterrupted) return@submit
                val loaded = missing.associateWith { readPageFromArchive(it) }
                SwingUtilities.invokeLater {
                    if (closed) return@invokeLater
                    loaded.forEach { (index, image) ->
                        if (image != null) {
                            putCachedImage(index, image)
                            if (sizes[index] == null) sizes[index] = Dimension(image.width, image.height)
                        }
                    }
                    finish()
                }
            } catch (_: InterruptedException) {
                // Cancelled
            } finally {
                missing.forEach { inFlightTasks.remove(it) }
            }
        }
        missing.forEach { inFlightTasks[it] = future }
    }

    private fun prefetch(index: Int) {
        if (index !in 0 until pageCount || isPageCached(index) || inFlightTasks.containsKey(index)) return
        val future = io.submit {
            try {
                if (closed || Thread.currentThread().isInterrupted) return@submit
                val image = readPageFromArchive(index) ?: return@submit
                SwingUtilities.invokeLater {
                    if (!closed) {
                        putCachedImage(index, image)
                        if (sizes[index] == null) sizes[index] = Dimension(image.width, image.height)
                    }
                }
            } catch (_: InterruptedException) {
                // Cancelled
            } finally {
                inFlightTasks.remove(index)
            }
        }
        inFlightTasks[index] = future
    }

    /**
     * Reads image headers starting from [currentPage] outwards (visible pages first!),
     * and only runs after initial visible pages are already queued for decoding.
     */
    private fun readSizes() {
        io.execute {
            val order = (0 until pageCount).sortedBy { kotlin.math.abs(it - currentPage) }
            val found = arrayOfNulls<Dimension>(pageCount)
            for (i in order) {
                if (closed) return@execute
                if (sizes[i] != null) continue
                found[i] = runCatching {
                    val stream = if (reader is OnlineStreamArchiveReader) {
                        reader.getPageStream(reader.pages[i])
                    } else {
                        synchronized(readerLock) {
                            reader.getPageStream(reader.pages[i])
                        }
                    }
                    stream.use { ImageDecoder.readDimensions(it) }
                }.getOrNull()
            }
            SwingUtilities.invokeLater {
                if (closed) return@invokeLater
                var anyChanged = false
                found.forEachIndexed { i, d ->
                    if (d != null && sizes[i] == null) {
                        sizes[i] = d
                        anyChanged = true
                    }
                }
                if (anyChanged && isWebtoon) {
                    webtoon.recomputeGeometryPreserveScroll()
                }
                if (anyChanged && mode == ReadMode.SINGLE && splitWidePages && isWide(currentPage)) {
                    showSpread()
                }
            }
        }
    }

    // --- Fullscreen ----------------------------------------------------------------------------

    /** Toggles borderless fullscreen (F11). Esc leaves fullscreen before closing the window. */
    private fun toggleFullscreen() {
        val device = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        fullscreen = !fullscreen
        togglingFullscreen = true
        try {
            dispose() // decoration state can only change while the window is not displayable
            isUndecorated = fullscreen
            device.fullScreenWindow = if (fullscreen) this else null
            isVisible = true
        } finally {
            SwingUtilities.invokeLater {
                togglingFullscreen = false
            }
        }
        requestFocus()
    }

    // --- Views ---------------------------------------------------------------------------------

    /** One page or a two-page spread, painted scaled according to [fit]. */
    private inner class PagedView : JPanel(), Scrollable {
        private var images: List<BufferedImage> = emptyList()
        private var message: String? = null
        private var scaled: List<BufferedImage> = emptyList()
        private var scaledKey = ""
        private var pendingKey = ""
        private var scalingFuture: Future<*>? = null

        init {
            background = Color.BLACK
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    requestFocusInWindow()
                    // Mihon 3-zone ReaderNavigationOverlay (left third, center third, right third)
                    val ratio = e.x.toDouble() / max(width, 1)
                    if (ratio in 0.35..0.65) {
                        toggleHud()
                        return
                    }
                    val leftSide = ratio < 0.35
                    val forward = if (mode == ReadMode.DOUBLE_RTL || (mode == ReadMode.SINGLE && splitWideRtl)) leftSide else !leftSide
                    if (forward) next() else prev()
                }
            })
        }

        fun invalidateCache() {
            scaledKey = ""
            pendingKey = ""
            scalingFuture?.cancel(true)
            scalingFuture = null
            scaled.forEach { runCatching { it.flush() } }
            scaled = emptyList()
            repaint()
        }

        fun setContent(images: List<BufferedImage>, message: String?) {
            this.images = images
            this.message = message
            scaledKey = ""
            pendingKey = ""
            scalingFuture?.cancel(true)
            scalingFuture = null
            revalidate()
            repaint()
        }

        private fun natural() = Dimension(images.sumOf { it.width }, images.maxOfOrNull { it.height } ?: 0)

        private fun extent(): Dimension =
            (parent as? JViewport)?.extentSize ?: Dimension(max(width, 1), max(height, 1))

        private fun scaleFor(vw: Int, vh: Int): Double {
            val n = natural()
            if (n.width == 0 || n.height == 0) return 1.0
            val base = when (fit) {
                FitMode.WINDOW -> min(vw.toDouble() / n.width, vh.toDouble() / n.height)
                FitMode.WIDTH -> vw.toDouble() / n.width
                FitMode.ORIGINAL -> 1.0
            }
            return base * zoomFactor
        }

        override fun getPreferredSize(): Dimension {
            val e = extent()
            val n = natural()
            val s = scaleFor(e.width, e.height)
            return Dimension(max(e.width, (n.width * s).toInt()), max(e.height, (n.height * s).toInt()))
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (images.isEmpty()) {
                message?.let {
                    g.color = Color.LIGHT_GRAY
                    val w = g.fontMetrics.stringWidth(it)
                    g.drawString(it, (width - w) / 2, height / 2)
                }
                return
            }
            val e = extent()
            val s = scaleFor(e.width, e.height)
            val n = natural()
            val totalW = (n.width * s).toInt()
            val totalH = (n.height * s).toInt()
            val key = images.joinToString(",") { System.identityHashCode(it).toString() } + "@$s@${quality.name}@${colorFilter.name}"

            if (key != scaledKey && key != pendingKey) {
                scalingFuture?.cancel(true)
                pendingKey = key
                val snapImages = images
                val snapQuality = quality
                val snapFilter = colorFilter
                val snapS = s

                scalingFuture = io.submit {
                    try {
                        if (Thread.currentThread().isInterrupted) return@submit
                        val hq = snapImages.map { img ->
                            val w = (img.width * snapS).toInt().coerceAtLeast(1)
                            val h = (img.height * snapS).toInt().coerceAtLeast(1)
                            var res = if (snapQuality == MangaImageEnhancer.QualityMode.BILINEAR) {
                                scaleImageFast(img, w, h)
                            } else {
                                scaleImage(img, w, h, snapQuality)
                            }
                            if (snapFilter != MangaImageEnhancer.ColorFilterMode.NONE) {
                                res = MangaImageEnhancer.applyColorFilter(res, snapFilter)
                            }
                            res
                        }
                        SwingUtilities.invokeLater {
                            if (!closed && pendingKey == key) {
                                scaled.forEach { runCatching { it.flush() } }
                                scaled = hq
                                scaledKey = key
                                repaint()
                            }
                        }
                    } catch (_: InterruptedException) {
                        // Task was cancelled
                    }
                }
            }

            var x = ((width - totalW) / 2).coerceAtLeast(0)
            val top = ((height - totalH) / 2).coerceAtLeast(0)
            if (key == scaledKey && scaled.isNotEmpty()) {
                scaled.forEach { img ->
                    g.drawImage(img, x, top + (totalH - img.height) / 2, null)
                    x += img.width
                }
            } else {
                // Instant bilinear preview on EDT while background scaling/filtering finishes
                val g2 = g as java.awt.Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                images.forEach { img ->
                    val w = (img.width * s).toInt().coerceAtLeast(1)
                    val h = (img.height * s).toInt().coerceAtLeast(1)
                    g2.drawImage(img, x, top + (totalH - h) / 2, w, h, null)
                    x += w
                }
            }
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 48
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
            (if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width) * 9 / 10
        override fun getScrollableTracksViewportWidth() = fit != FitMode.ORIGINAL && zoomFactor <= 1.0
        override fun getScrollableTracksViewportHeight() = fit == FitMode.WINDOW && zoomFactor <= 1.0
    }

    /**
     * Continuous vertical strip. Only pages near the viewport are kept scaled to the layout width,
     * while raw decoded originals are cached in [cache] so resizing or scrolling never re-decodes
     * from disk or restarts loading.
     */
    private inner class WebtoonView : JPanel(), Scrollable {
        private val images = HashMap<Int, BufferedImage>()
        private val requested = HashSet<Int>()
        private val failed = HashSet<Int>()
        private var heights = IntArray(pageCount)
        private var tops = IntArray(pageCount + 1)
        private var maxWidth = 0
        private var layoutGeneration = 0
        private var positioning = false

        /** Width the strip was laid out for; 0 until the first layout. */
        var layoutWidth = 0
            private set

        fun invalidateScaledCache() {
            images.values.forEach { runCatching { it.flush() } }
            images.clear()
            requested.clear()
            failed.clear()
        }

        init {
            background = Color.BLACK
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    requestFocusInWindow()
                    val ratio = e.x.toDouble() / max(width, 1)
                    if (ratio in 0.35..0.65) {
                        toggleHud()
                    }
                }
            })
        }

        private fun pageWidth(i: Int): Int {
            val availableWidth = (layoutWidth * (1.0 - (webtoonSideMarginPercent * 2 / 100.0))).toInt().coerceAtLeast(200)
            val base = if (fit == FitMode.ORIGINAL) sizes[i]?.width ?: availableWidth else availableWidth
            return (base * zoomFactor).toInt().coerceAtLeast(64)
        }

        private fun computeHeight(i: Int, w: Int): Int {
            val d = sizes[i]
            return if (d != null && d.width > 0) {
                (d.height.toDouble() * w / d.width).toInt().coerceAtLeast(1)
            } else {
                (w * 1.4).toInt()
            }
        }

        fun relayout(anchorPage: Int = currentPage) {
            val vw = (parent as? JViewport)?.width ?: 0
            if (vw <= 0 || pageCount == 0) return
            layoutWidth = vw
            layoutGeneration++
            requested.clear()
            failed.clear()
            positioning = true

            val pageGap = if (mode == ReadMode.CONTINUOUS_VERTICAL) 16 else 0
            val (newTops, newHeights) = ReaderEngine.computeWebtoonLayout(
                pageCount = pageCount,
                pageWidthFn = { pageWidth(it) },
                pageHeightFn = { idx, w -> computeHeight(idx, w) },
                pageGap = pageGap,
            )
            tops = newTops
            heights = newHeights
            maxWidth = (0 until pageCount).maxOfOrNull { pageWidth(it) } ?: 0

            revalidate()
            repaint()
            SwingUtilities.invokeLater {
                try {
                    scrollToPage(anchorPage)
                } finally {
                    positioning = false
                }
                updateVisible()
            }
        }

        /**
         * Recomputes page heights and vertical offsets when new natural dimensions are discovered
         * WITHOUT clearing decoded images, cancelling in-flight loads, or jumping the scrollbar.
         */
        fun recomputeGeometryPreserveScroll(changedPage: Int = -1) {
            if (pageCount == 0 || layoutWidth <= 0) return
            val viewport = parent as? JViewport
            val oldScrollY = viewport?.viewPosition?.y ?: 0
            val anchorIdx = if (positioning) currentPage else pageAt(oldScrollY)
            val offsetInAnchor = if (positioning) 0 else (oldScrollY - tops[anchorIdx]).coerceAtLeast(0)

            val pageGap = if (mode == ReadMode.CONTINUOUS_VERTICAL) 16 else 0
            val (newTops, newHeights) = ReaderEngine.computeWebtoonLayout(
                pageCount = pageCount,
                pageWidthFn = { pageWidth(it) },
                pageHeightFn = { idx, w -> computeHeight(idx, w) },
                pageGap = pageGap,
            )
            tops = newTops
            heights = newHeights
            maxWidth = (0 until pageCount).maxOfOrNull { pageWidth(it) } ?: 0

            positioning = true
            try {
                revalidate()
                if (changedPage == -1 || changedPage in 0 until anchorIdx) {
                    val targetY = (tops[anchorIdx] + offsetInAnchor).coerceAtLeast(0)
                    if (scroll.verticalScrollBar.value != targetY) {
                        scroll.verticalScrollBar.value = targetY
                    }
                }
            } finally {
                positioning = false
            }
            repaint()
        }

        fun scrollToPage(page: Int) {
            if (page !in 0 until pageCount) return
            positioning = true
            try {
                scroll.verticalScrollBar.value = tops[page]
                viewport?.viewPosition = java.awt.Point(0, tops[page])
            } finally {
                positioning = false
            }
        }

        private val viewport: JViewport? get() = parent as? JViewport

        private fun pageAt(y: Int): Int = ReaderEngine.pageAt(y, tops, pageCount)

        fun updateVisible() {
            if (positioning) return
            val vp = viewport ?: return
            if (pageCount == 0 || layoutWidth == 0) return
            val top = vp.viewPosition.y
            val first = pageAt(top)
            val last = pageAt(top + vp.height)
            if (first != currentPage) {
                currentPage = first
                syncPageUi(first, "Página ${first + 1}/$pageCount")
            }
            reportProgress(last)

            // Cancel tasks for pages that scrolled out of active window
            cancelDistantTasks(first, (last - first) + 6, 4)

            // Keep a tight window in memory (4 behind, 6 ahead) so RAM stays minimal while reading smoothly
            images.keys.filter { it < first - 4 || it > last + 6 }.toList().forEach { idx ->
                images.remove(idx)?.let { runCatching { it.flush() } }
            }
            requested.filter { it < first - 4 || it > last + 6 }.toList().forEach { idx ->
                requested.remove(idx)
            }
            // Request visible pages first, then prefetch 3 pages ahead and 1 behind
            for (i in first..min(last, pageCount - 1)) request(i)
            for (ahead in 1..3) {
                val idx = last + ahead
                if (idx < pageCount) request(idx)
            }
            for (behind in 1..1) {
                val idx = first - behind
                if (idx >= 0) request(idx)
            }
        }

        private fun request(i: Int) {
            val targetW = pageWidth(i)
            val existing = images[i]
            if ((existing != null && existing.width == targetW) || !requested.add(i)) return
            val gen = layoutGeneration
            val cachedOriginal = getCachedImage(i)
            val snapQuality = quality
            val snapFilter = colorFilter

            if (closed || io.isShutdown || io.isTerminated) {
                requested.remove(i)
                return
            }
            val future = try {
                io.submit {
                    try {
                        if (closed || Thread.currentThread().isInterrupted) {
                            requested.remove(i)
                            return@submit
                        }
                        val original = cachedOriginal ?: readPageFromArchive(i)
                        val naturalSize = original?.let { Dimension(it.width, it.height) }
                        val result = original?.let {
                            val targetH = (it.height.toDouble() * targetW / it.width).toInt().coerceAtLeast(1)
                            var res = if (snapQuality == MangaImageEnhancer.QualityMode.BILINEAR) {
                                scaleImageFast(it, targetW, targetH)
                            } else {
                                scaleImage(it, targetW, targetH, snapQuality)
                            }
                            if (snapFilter != MangaImageEnhancer.ColorFilterMode.NONE) {
                                res = MangaImageEnhancer.applyColorFilter(res, snapFilter)
                            }
                            res
                        }
                        SwingUtilities.invokeLater {
                            if (closed || gen != layoutGeneration) {
                                result?.let { runCatching { it.flush() } }
                                requested.remove(i)
                                return@invokeLater
                            }
                            if (original != null && !isPageCached(i)) {
                                putCachedImage(i, original)
                            }
                            if (result == null) {
                                failed.add(i)
                                requested.remove(i)
                                repaint(0, tops[i], width, heights[i])
                            } else {
                                images[i]?.let { runCatching { it.flush() } }
                                images[i] = result
                                if (sizes[i] == null && naturalSize != null) {
                                    sizes[i] = naturalSize
                                    recomputeGeometryPreserveScroll(changedPage = i)
                                } else {
                                    repaint(0, tops[i], width, heights[i])
                                }
                            }
                        }
                    } catch (_: InterruptedException) {
                        requested.remove(i)
                    } catch (_: Throwable) {
                        requested.remove(i)
                    } finally {
                        inFlightTasks.remove(i)
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                requested.remove(i)
                null
            }
            if (future != null) {
                inFlightTasks[i] = future
            }
        }

        override fun getPreferredSize(): Dimension {
            val vw = (parent as? JViewport)?.width ?: 0
            return Dimension(max(vw, maxWidth), max(tops[pageCount], 1))
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val clip = g.clipBounds ?: return
            if (pageCount == 0 || layoutWidth == 0) return
            var i = pageAt(clip.y)
            while (i < pageCount && tops[i] < clip.y + clip.height) {
                val w = pageWidth(i)
                val x = ((width - w) / 2).coerceAtLeast(0)
                val image = images[i] ?: getCachedImage(i)
                if (image != null) {
                    val g2 = g as java.awt.Graphics2D
                    if (image.width != w) {
                        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    }
                    g2.drawImage(image, x, tops[i], w, heights[i], null)
                } else {
                    g.color = Color(40, 40, 40)
                    g.fillRect(x, tops[i], w, heights[i])
                    g.color = Color.LIGHT_GRAY
                    g.drawString(
                        if (i in failed) "Erro ao carregar a página ${i + 1}" else "Carregando página ${i + 1}…",
                        x + 20,
                        tops[i] + 30,
                    )
                }
                i++
            }
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 48
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
            (if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width) * 9 / 10
        override fun getScrollableTracksViewportWidth() = fit != FitMode.ORIGINAL
        override fun getScrollableTracksViewportHeight() = false
    }
}
