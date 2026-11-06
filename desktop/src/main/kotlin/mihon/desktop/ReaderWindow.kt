package mihon.desktop

import mihon.core.archive.ArchiveReader
import mihon.core.archive.ArchiveReaderFactory
import mihon.core.archive.ImageDecoder
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
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
import java.util.prefs.Preferences
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
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
    WEBTOON("Webtoon (rolagem)"),
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
    quality: MangaImageEnhancer.QualityMode = MangaImageEnhancer.QualityMode.ULTRA,
): BufferedImage = MangaImageEnhancer.scale(src, targetW, targetH, quality)

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
    source: File,
    startPage: Int = 0,
    private val onPrevChapter: (() -> Unit)? = null,
    private val onNextChapter: (() -> Unit)? = null,
    private val onProgress: (page: Int, pageCount: Int) -> Unit = { _, _ -> },
) : JFrame("Mihon Linux - $title") {

    private val reader: ArchiveReader = ArchiveReaderFactory.of(source)
    private val pageCount = reader.pageCount
    private val prefs = Preferences.userRoot().node("mihon-linux-reader")

    private var mode = runCatching { ReadMode.valueOf(prefs.get("mode", "SINGLE")) }.getOrDefault(ReadMode.SINGLE)
    private var fit = runCatching { FitMode.valueOf(prefs.get("fit", "WINDOW")) }.getOrDefault(FitMode.WINDOW)
    private var quality = runCatching { MangaImageEnhancer.QualityMode.valueOf(prefs.get("quality", "ULTRA")) }
        .getOrDefault(MangaImageEnhancer.QualityMode.ULTRA)

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

    /** Shared LRU cache of decoded original images across all modes (EDT only, up to 24 pages). */
    private val cache = object : LinkedHashMap<Int, BufferedImage>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, BufferedImage>) = size > 24
    }
    private val prefetching = ConcurrentHashMap.newKeySet<Int>()

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
        modeBox.addActionListener {
            mode = ReadMode.entries[modeBox.selectedIndex]
            prefs.put("mode", mode.name)
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
        fun button(text: String, action: () -> Unit) = JButton(text).apply {
            isFocusable = false
            addActionListener { action() }
        }
        topHud = JPanel(FlowLayout(FlowLayout.CENTER, 8, 6)).apply {
            background = Color(18, 19, 24, 240)
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SUBTLE)
            add(modeBox)
            add(fitBox)
            add(qualityBox)
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

        contentPane.add(topHud, BorderLayout.NORTH)
        contentPane.add(scroll, BorderLayout.CENTER)
        contentPane.add(bottomHud, BorderLayout.SOUTH)

        scroll.addMouseWheelListener { e ->
            if (e.isControlDown) {
                e.consume()
                adjustZoom(if (e.wheelRotation < 0) 1.12 else 0.89)
            }
        }

        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_RIGHT, KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_SPACE -> next()
                    KeyEvent.VK_LEFT, KeyEvent.VK_PAGE_UP -> prev()
                    KeyEvent.VK_DOWN -> scrollBy(64)
                    KeyEvent.VK_UP -> scrollBy(-64)
                    KeyEvent.VK_HOME -> goTo(0)
                    KeyEvent.VK_END -> goTo(pageCount - 1)
                    KeyEvent.VK_H, KeyEvent.VK_M -> toggleHud()
                    KeyEvent.VK_PLUS, KeyEvent.VK_EQUALS, KeyEvent.VK_ADD -> if (e.isControlDown) adjustZoom(1.15)
                    KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> if (e.isControlDown) adjustZoom(0.87)
                    KeyEvent.VK_0 -> if (e.isControlDown) resetZoom()
                    KeyEvent.VK_OPEN_BRACKET -> if (onPrevChapter != null) { dispose(); onPrevChapter.invoke() }
                    KeyEvent.VK_CLOSE_BRACKET -> if (onNextChapter != null) { dispose(); onNextChapter.invoke() }
                    KeyEvent.VK_F11 -> toggleFullscreen()
                    KeyEvent.VK_ESCAPE -> if (fullscreen) toggleFullscreen() else dispose()
                }
            }
        })
        scroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val vw = scroll.viewport.width
                if (mode == ReadMode.WEBTOON) {
                    if (vw > 0 && vw != webtoon.layoutWidth) webtoon.relayout()
                } else {
                    paged.revalidate()
                    paged.repaint()
                }
            }
        })
        scroll.verticalScrollBar.addAdjustmentListener { if (mode == ReadMode.WEBTOON) webtoon.updateVisible() }
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                if (togglingFullscreen) return
                closed = true
                progressTimer.stop()
                onProgress(reportedPage, pageCount)
                io.shutdownNow()
                reader.close()
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

    /** Pages shown together when a spread starts at [p] (double mode keeps the cover and wide pages alone). */
    private fun spreadAt(p: Int): List<Int> {
        if (mode != ReadMode.DOUBLE_RTL || p == 0 || isWide(p)) return listOf(p)
        val n = p + 1
        return if (n < pageCount && !isWide(n)) listOf(p, n) else listOf(p)
    }

    private fun prevSpreadStart(): Int {
        if (currentPage <= 0) return 0
        if (mode == ReadMode.DOUBLE_RTL) {
            val c = currentPage - 2
            if (c >= 0 && spreadAt(c).size == 2) return c
        }
        return currentPage - 1
    }

    private fun next() {
        if (mode == ReadMode.WEBTOON) scrollBy(scroll.viewport.height * 9 / 10) else goTo(currentPage + spreadAt(currentPage).size)
    }

    private fun prev() {
        if (mode == ReadMode.WEBTOON) scrollBy(-scroll.viewport.height * 9 / 10) else goTo(prevSpreadStart())
    }

    private fun scrollBy(dy: Int) {
        scroll.verticalScrollBar.value += dy
    }

    private fun adjustZoom(factor: Double) {
        zoomFactor = (zoomFactor * factor).coerceIn(0.4, 3.5)
        if (mode == ReadMode.WEBTOON) webtoon.relayout() else { paged.revalidate(); paged.repaint() }
    }

    private fun resetZoom() {
        zoomFactor = 1.0
        if (mode == ReadMode.WEBTOON) webtoon.relayout() else { paged.revalidate(); paged.repaint() }
    }

    private fun goTo(page: Int) {
        if (pageCount == 0) return
        val target = page.coerceIn(0, pageCount - 1)
        if (mode == ReadMode.WEBTOON) {
            webtoon.scrollToPage(target)
            return
        }
        if (target == currentPage) return
        currentPage = target
        showSpread()
    }

    private fun reportProgress(page: Int) {
        reportedPage = page
        progressTimer.restart()
    }

    private fun applyView() {
        generation++
        if (mode == ReadMode.WEBTOON) {
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
        val lbl = if (indexes.size == 2) {
            "Páginas ${indexes[0] + 1}-${indexes[1] + 1}/$pageCount"
        } else {
            "Página ${indexes[0] + 1}/$pageCount"
        }
        syncPageUi(indexes.first(), lbl)
        reportProgress(indexes.last())

        fun finish() {
            if (myGeneration != generation || closed) return
            val images = indexes.map { cache[it] }
            if (images.any { it == null }) {
                paged.setContent(emptyList(), "Não foi possível decodificar a página")
            } else {
                val ordered = images.filterNotNull().let { if (mode == ReadMode.DOUBLE_RTL) it.reversed() else it }
                paged.setContent(ordered, null)
                scroll.verticalScrollBar.value = 0
                scroll.horizontalScrollBar.value = 0
            }
            for (ahead in 1..6) {
                prefetch(indexes.last() + ahead)
            }
        }

        val missing = indexes.filter { !cache.containsKey(it) }
        if (missing.isEmpty()) {
            finish()
            return
        }
        paged.setContent(emptyList(), "Carregando…")
        io.execute {
            val loaded = missing.associateWith { readPageFromArchive(it) }
            SwingUtilities.invokeLater {
                if (closed) return@invokeLater
                loaded.forEach { (index, image) ->
                    if (image != null) {
                        cache[index] = image
                        if (sizes[index] == null) sizes[index] = Dimension(image.width, image.height)
                    }
                }
                finish()
            }
        }
    }

    private fun prefetch(index: Int) {
        if (index !in 0 until pageCount || cache.containsKey(index) || !prefetching.add(index)) return
        io.execute {
            try {
                if (closed) return@execute
                val image = readPageFromArchive(index) ?: return@execute
                SwingUtilities.invokeLater {
                    if (!closed) {
                        cache[index] = image
                        if (sizes[index] == null) sizes[index] = Dimension(image.width, image.height)
                    }
                }
            } finally {
                prefetching.remove(index)
            }
        }
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
                        synchronized(readerLock) {
                            reader.getPageStream(reader.pages[i]).use { stream ->
                                ImageDecoder.readDimensions(stream)
                            }
                        }
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
                    if (anyChanged && mode == ReadMode.WEBTOON) {
                        webtoon.recomputeGeometryPreserveScroll()
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
            togglingFullscreen = false
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
                    val forward = if (mode == ReadMode.DOUBLE_RTL) leftSide else !leftSide
                    if (forward) next() else prev()
                }
            })
        }

        fun setContent(images: List<BufferedImage>, message: String?) {
            this.images = images
            this.message = message
            scaledKey = ""
            pendingKey = ""
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
            val key = images.joinToString(",") { System.identityHashCode(it).toString() } + "@$s@${quality.name}"
            if (key != scaledKey && key != pendingKey) {
                if (quality == MangaImageEnhancer.QualityMode.FAST) {
                    scaled = images.map { img ->
                        val w = (img.width * s).toInt().coerceAtLeast(1)
                        val h = (img.height * s).toInt().coerceAtLeast(1)
                        scaleImageFast(img, w, h)
                    }
                    scaledKey = key
                } else {
                    pendingKey = key
                    val snapImages = images
                    val snapQuality = quality
                    io.execute {
                        val hq = snapImages.map { img ->
                            val w = (img.width * s).toInt().coerceAtLeast(1)
                            val h = (img.height * s).toInt().coerceAtLeast(1)
                            scaleImage(img, w, h, snapQuality)
                        }
                        SwingUtilities.invokeLater {
                            if (!closed && pendingKey == key) {
                                scaled = hq
                                scaledKey = key
                                repaint()
                            }
                        }
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
                // Instant bilinear preview while Lanczos-3 + Anime4K finishes in background (~80ms)
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
            images.clear()
            requested.clear()
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
            val base = if (fit == FitMode.ORIGINAL) sizes[i]?.width ?: layoutWidth else layoutWidth
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
            // Do NOT clear `images` here! Keep existing images so zooming/resizing scales smoothly
            // in `paintComponent` until the newly requested HQ Lanczos-3 + Anime4K versions finish.
            requested.clear()
            failed.clear()
            positioning = true
            var y = 0
            maxWidth = 0
            for (i in 0 until pageCount) {
                val w = pageWidth(i)
                tops[i] = y
                heights[i] = computeHeight(i, w)
                maxWidth = max(maxWidth, w)
                y += heights[i]
            }
            tops[pageCount] = y
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

            var y = 0
            maxWidth = 0
            for (i in 0 until pageCount) {
                val w = pageWidth(i)
                tops[i] = y
                heights[i] = computeHeight(i, w)
                maxWidth = max(maxWidth, w)
                y += heights[i]
            }
            tops[pageCount] = y

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

        private fun pageAt(y: Int): Int {
            var lo = 0
            var hi = pageCount - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (tops[mid] <= y) lo = mid else hi = mid - 1
            }
            return lo
        }

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
            // Keep a wide window in memory so scrolling up/down never triggers reloads
            images.keys.filter { it < first - 15 || it > last + 20 }.toList().forEach {
                images.remove(it)
                requested.remove(it)
            }
            // Request visible pages first, then prefetch 8 pages ahead and 3 behind
            for (i in first..min(last, pageCount - 1)) request(i)
            for (ahead in 1..8) {
                val idx = last + ahead
                if (idx < pageCount) request(idx)
            }
            for (behind in 1..3) {
                val idx = first - behind
                if (idx >= 0) request(idx)
            }
        }

        private fun request(i: Int) {
            val targetW = pageWidth(i)
            val existing = images[i]
            if ((existing != null && existing.width == targetW) || !requested.add(i)) return
            val gen = layoutGeneration
            val cachedOriginal = cache[i]
            io.execute {
                val original = cachedOriginal ?: readPageFromArchive(i)
                val naturalSize = original?.let { Dimension(it.width, it.height) }
                val result = original?.let {
                    val targetH = (it.height.toDouble() * targetW / it.width).toInt().coerceAtLeast(1)
                    scaleImage(it, targetW, targetH, quality)
                }
                SwingUtilities.invokeLater {
                    if (closed || gen != layoutGeneration) {
                        requested.remove(i)
                        return@invokeLater
                    }
                    if (original != null && !cache.containsKey(i)) {
                        cache[i] = original
                    }
                    if (result == null) {
                        failed.add(i)
                        repaint(0, tops[i], width, heights[i])
                    } else {
                        images[i] = result
                        if (sizes[i] == null && naturalSize != null) {
                            sizes[i] = naturalSize
                            recomputeGeometryPreserveScroll(changedPage = i)
                        } else {
                            repaint(0, tops[i], width, heights[i])
                        }
                    }
                }
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
                val image = images[i] ?: cache[i]
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
