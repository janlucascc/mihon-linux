package mihon.desktop

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JRootPane
import javax.swing.KeyStroke
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-FPS Vector Gooey Radial Menu (Analytical Metaball Bridges + Spring Physics).
 *
 * Performance Architecture:
 * - Localized dirty-rectangle repaints (`repaintDirtyRegion()`): only repaints the ~380x380px
 *   region around the radial menu instead of invalidating the entire window/reader canvas.
 * - Pre-rendered soft glow sprites (zero per-frame `RadialGradientPaint` software rasterization).
 * - Preallocated geometry (`Path2D.Double`, `Ellipse2D.Double`, `DoubleArray`) for zero GC churn.
 * - Frame-rate independent `System.nanoTime()` spring simulation at 60 FPS (`Timer(16)`).
 */
class GooeyContextMenu(
    private val items: List<GooeyMenuItem>,
) : JComponent() {

    data class GooeyMenuItem(
        val id: String,
        val label: String,
        val iconType: IconType,
        val isDanger: Boolean = false,
        val isActive: Boolean = false,
        val shortcutHint: String? = null,
        val onClick: () -> Unit,
    )

    enum class IconType {
        OPEN_DETAILS,
        READING,
        COMPLETED,
        PLAN_TO_READ,
        REMOVE,
        DOWNLOAD,
        UNREAD,
        MOVE_LEFT,
        MOVE_RIGHT,
        EDIT,
        TRANSLATE,
        BOOKMARK,
        NOTE,
        HIGHLIGHT,
        BOOKMARKS_LIST,
        COPY,
    }

    private class SpringValue(
        var value: Double = 0.0,
        var target: Double = 0.0,
        var velocity: Double = 0.0,
        private val stiffness: Double = 380.0,
        private val damping: Double = 20.0,
    ) {
        fun update(dt: Double): Boolean {
            val force = -stiffness * (value - target) - damping * velocity
            velocity += force * dt
            value += velocity * dt
            val settled = kotlin.math.abs(value - target) < 0.0015 && kotlin.math.abs(velocity) < 0.008
            if (settled) {
                value = target
                velocity = 0.0
            }
            return !settled
        }

        fun snap(v: Double) {
            value = v
            target = v
            velocity = 0.0
        }
    }

    private var anchorX: Double = 0.0
    private var anchorY: Double = 0.0
    private var arcCenterAngle: Double = PI / 2.0
    private var totalArcSpan: Double = Math.toRadians(170.0)
    private var fullCircleLayout: Boolean = false
    private var orbitRadiusBase: Double = 80.0

    // Tight bounding box around the radial menu for fast localized repaints
    private var dirtyX: Int = 0
    private var dirtyY: Int = 0
    private var dirtyW: Int = 0
    private var dirtyH: Int = 0

    private val hubScale = SpringValue(0.15, 1.0, stiffness = 420.0, damping = 22.0)
    private val itemSprings = items.map { SpringValue(0.0, 0.0, stiffness = 360.0, damping = 18.5) }
    private val hoverScales = items.map { SpringValue(1.0, 1.0, stiffness = 420.0, damping = 24.0) }
    private val hoverPull = items.map { SpringValue(0.0, 0.0, stiffness = 400.0, damping = 24.0) }
    private val labelAlpha = SpringValue(0.0, 0.0, stiffness = 380.0, damping = 28.0)

    private var hoveredIndex: Int = -1
    private var lastDisplayedLabelIndex: Int = -1
    private var hubHovered: Boolean = false
    private var closing: Boolean = false
    private var pendingAction: (() -> Unit)? = null
    private var openStartNanos: Long = 0L
    private var lastTickNanos: Long = 0L
    private var hostRootPane: JRootPane? = null
    private var previousGlassPane: Component? = null

    // Preallocated buffers & shapes for zero-allocation rendering
    private val centersX = DoubleArray(items.size)
    private val centersY = DoubleArray(items.size)
    private val radii = DoubleArray(items.size)
    private val reusableEllipse = Ellipse2D.Double()
    private val reusableBridge = Path2D.Double()

    private val animTimer = Timer(16) {
        onTick()
    }.apply { isCoalesce = true }

    init {
        isOpaque = false
        isFocusable = true

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                updateHover(e.point)
            }
        })

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                handleClick(e.point, e)
            }
        })

        registerKeyboardAction(
            { closeMenu(null) },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            WHEN_IN_FOCUSED_WINDOW,
        )

        addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (items.isEmpty() || closing) return
                when (e.keyCode) {
                    KeyEvent.VK_RIGHT, KeyEvent.VK_DOWN, KeyEvent.VK_TAB -> {
                        val delta = if (e.isShiftDown && e.keyCode == KeyEvent.VK_TAB) -1 else 1
                        hoveredIndex = Math.floorMod((if (hoveredIndex < 0) -1 else hoveredIndex) + delta, items.size)
                        lastDisplayedLabelIndex = hoveredIndex
                        ensureTimerRunning()
                        repaintDirtyRegion()
                        e.consume()
                    }
                    KeyEvent.VK_LEFT, KeyEvent.VK_UP -> {
                        hoveredIndex = Math.floorMod((if (hoveredIndex < 0) 0 else hoveredIndex) - 1, items.size)
                        lastDisplayedLabelIndex = hoveredIndex
                        ensureTimerRunning()
                        repaintDirtyRegion()
                        e.consume()
                    }
                    KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> {
                        if (hoveredIndex in items.indices) {
                            val selected = items[hoveredIndex]
                            closeMenu(selected.onClick)
                        } else {
                            closeMenu(null)
                        }
                        e.consume()
                    }
                }
            }
        })
    }

    fun showAt(invoker: Component, x: Int, y: Int) {
        val window = SwingUtilities.getWindowAncestor(invoker) ?: return
        val rootPane = (window as? RootPaneContainer)?.rootPane ?: return

        // Dismiss any active GooeyContextMenu cleanly so original glassPane is preserved
        (rootPane.glassPane as? GooeyContextMenu)?.forceDismiss()

        hostRootPane = rootPane
        previousGlassPane = rootPane.glassPane

        val pt = SwingUtilities.convertPoint(invoker, Point(x, y), rootPane)

        // Adaptive radial geometry: balanced full ring for 5+ items, compact arc for 2-4 items
        orbitRadiusBase = when {
            items.size >= 7 -> 90.0
            items.size >= 5 -> 82.0
            else -> 76.0
        }
        val safeMargin = orbitRadiusBase + NODE_RADIUS + 34.0
        val maxAnchorX = (rootPane.width - safeMargin).coerceAtLeast(safeMargin)
        val maxAnchorY = (rootPane.height - safeMargin - 28.0).coerceAtLeast(safeMargin)
        anchorX = pt.x.toDouble().coerceIn(safeMargin, maxAnchorX)
        anchorY = pt.y.toDouble().coerceIn(safeMargin, maxAnchorY)

        if (items.size >= 5) {
            fullCircleLayout = true
            totalArcSpan = 2.0 * PI
            arcCenterAngle = -PI / 2.0 // Starts cleanly at 12 o'clock
        } else {
            fullCircleLayout = false
            totalArcSpan = Math.toRadians(if (items.size <= 3) 136.0 else 168.0)
            arcCenterAngle = if (pt.y > rootPane.height - 175) -PI / 2.0 else PI / 2.0
        }

        // Precompute tight dirty repaint box (covers orbit, glow, shadows, and outer tooltip pill)
        val padX = (orbitRadiusBase + NODE_RADIUS + 160.0).toInt()
        val padY = (orbitRadiusBase + NODE_RADIUS + 96.0).toInt()
        dirtyX = (anchorX.toInt() - padX).coerceAtLeast(0)
        dirtyY = (anchorY.toInt() - padY).coerceAtLeast(0)
        dirtyW = (anchorX.toInt() + padX).coerceAtMost(rootPane.width) - dirtyX
        dirtyH = (anchorY.toInt() + padY).coerceAtMost(rootPane.height) - dirtyY

        closing = false
        hoveredIndex = -1
        lastDisplayedLabelIndex = -1
        hubHovered = false
        openStartNanos = System.nanoTime()
        lastTickNanos = openStartNanos

        if (mihon.desktop.ui.animation.MotionPreferences.isEnabled) {
            hubScale.snap(0.22)
            hubScale.target = 1.0
            labelAlpha.snap(0.0)
            itemSprings.forEach { it.snap(0.0) }
            hoverScales.forEach { it.snap(1.0) }
            hoverPull.forEach { it.snap(0.0) }
        } else {
            hubScale.snap(1.0)
            labelAlpha.snap(0.0)
            itemSprings.forEach { it.snap(1.0) }
            hoverScales.forEach { it.snap(1.0) }
            hoverPull.forEach { it.snap(0.0) }
        }

        // Mount on JRootPane.glassPane so Swing's RepaintManager sets isOptimizedDrawingEnabled() = false
        // and ALWAYS composites GooeyContextMenu strictly on top of underlying animations (zero flicker).
        rootPane.glassPane = this
        isVisible = true
        rootPane.revalidate()
        requestFocusInWindow()
        if (mihon.desktop.ui.animation.MotionPreferences.isEnabled) {
            animTimer.start()
        }
        repaintDirtyRegion()
    }

    private fun repaintDirtyRegion() {
        if (dirtyW > 0 && dirtyH > 0) {
            repaint(dirtyX, dirtyY, dirtyW, dirtyH)
        } else {
            repaint()
        }
    }

    private fun onTick() {
        val now = System.nanoTime()
        val dt = ((now - lastTickNanos) / 1_000_000_000.0).coerceIn(0.005, 0.030)
        lastTickNanos = now
        val elapsedMs = (now - openStartNanos) / 1_000_000L

        if (!closing) {
            // Crisp, unified liquid bloom (~14ms micro-stagger per satellite)
            items.indices.forEach { i ->
                if (elapsedMs >= i * 14L) {
                    itemSprings[i].target = 1.0
                }
            }
        } else {
            items.indices.forEach { i ->
                itemSprings[i].target = 0.0
            }
            hubScale.target = 0.0
        }

        var moving = false
        if (hubScale.update(dt)) moving = true
        itemSprings.forEach { if (it.update(dt)) moving = true }
        hoverScales.forEachIndexed { idx, spring ->
            spring.target = if (idx == hoveredIndex && !closing) 1.16 else 1.0
            if (spring.update(dt)) moving = true
        }
        hoverPull.forEachIndexed { idx, spring ->
            spring.target = if (idx == hoveredIndex && !closing) 5.5 else 0.0
            if (spring.update(dt)) moving = true
        }
        labelAlpha.target = if (hoveredIndex in items.indices && !closing) 1.0 else 0.0
        if (labelAlpha.update(dt)) moving = true

        repaintDirtyRegion()

        if (closing && (!moving || hubScale.value <= 0.16)) {
            val action = pendingAction
            forceDismiss()
            action?.invoke()
        } else if (!closing && !moving && elapsedMs > items.size * 16L + 160L) {
            animTimer.stop()
        }
    }

    private fun ensureTimerRunning() {
        if (!animTimer.isRunning) {
            lastTickNanos = System.nanoTime()
            animTimer.start()
        }
    }

    private fun closeMenu(afterClose: (() -> Unit)?) {
        if (closing) return
        closing = true
        pendingAction = afterClose
        hoveredIndex = -1
        hubHovered = false
        if (!mihon.desktop.ui.animation.MotionPreferences.isEnabled) {
            val action = pendingAction
            forceDismiss()
            action?.invoke()
            return
        }
        ensureTimerRunning()
    }

    fun forceDismiss() {
        animTimer.stop()
        isVisible = false
        val root = hostRootPane
        val prev = previousGlassPane
        hostRootPane = null
        previousGlassPane = null
        if (root != null) {
            if (root.glassPane === this) {
                if (prev != null && prev !== this) {
                    root.glassPane = prev
                    prev.isVisible = false
                } else {
                    root.glassPane = javax.swing.JPanel().apply { isOpaque = false; isVisible = false }
                }
            }
            if (dirtyW > 0 && dirtyH > 0) {
                root.repaint(dirtyX, dirtyY, dirtyW, dirtyH)
            } else {
                root.repaint()
            }
            SwingUtilities.getWindowAncestor(root)?.requestFocus()
        }
    }

    private fun nodeAngle(index: Int): Double {
        if (items.size <= 1) return arcCenterAngle
        return if (fullCircleLayout) {
            arcCenterAngle + (2.0 * PI * index) / items.size
        } else {
            val start = arcCenterAngle + totalArcSpan / 2.0
            val step = totalArcSpan / (items.size - 1)
            start - index * step
        }
    }

    private fun nodeCenter(index: Int): Pair<Double, Double> {
        val progress = itemSprings[index].value
        val orbitRadius = orbitRadiusBase * progress + hoverPull[index].value * progress.coerceIn(0.0, 1.0)
        val angle = nodeAngle(index)
        val nx = anchorX + cos(angle) * orbitRadius
        val ny = anchorY + sin(angle) * orbitRadius
        return nx to ny
    }

    private fun updateHover(pt: Point) {
        if (closing) return
        val distHub = hypot(pt.x - anchorX, pt.y - anchorY)
        val newHubHover = distHub <= HUB_RADIUS + 4

        var newHovered = -1
        if (!newHubHover) {
            for (i in items.indices) {
                val (nx, ny) = nodeCenter(i)
                val r = NODE_RADIUS * hoverScales[i].value
                if (hypot(pt.x - nx, pt.y - ny) <= r + 5) {
                    newHovered = i
                    break
                }
            }
        }

        if (newHovered != hoveredIndex || newHubHover != hubHovered) {
            hoveredIndex = newHovered
            if (newHovered >= 0) {
                lastDisplayedLabelIndex = newHovered
            }
            hubHovered = newHubHover
            cursor = if (hoveredIndex >= 0 || hubHovered) {
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            } else {
                Cursor.getDefaultCursor()
            }
            if (mihon.desktop.ui.animation.MotionPreferences.isEnabled) {
                ensureTimerRunning()
            } else {
                hoverScales.forEachIndexed { idx, s -> s.snap(if (idx == hoveredIndex) 1.14 else 1.0) }
                hoverPull.forEachIndexed { idx, s -> s.snap(if (idx == hoveredIndex) 4.0 else 0.0) }
                labelAlpha.snap(if (hoveredIndex >= 0) 1.0 else 0.0)
            }
            repaintDirtyRegion()
        }
    }

    private fun handleClick(pt: Point, originalEvent: MouseEvent? = null) {
        if (closing) return

        for (i in items.indices) {
            val (nx, ny) = nodeCenter(i)
            val r = NODE_RADIUS * hoverScales[i].value
            if (hypot(pt.x - nx, pt.y - ny) <= r + 6) {
                closeMenu(items[i].onClick)
                return
            }
        }

        val distHub = hypot(pt.x - anchorX, pt.y - anchorY)
        if (distHub <= HUB_RADIUS + 4) {
            closeMenu(null)
            return
        }

        // Clicked outside -> dismiss immediately and forward right-clicks to reopen cleanly at new position
        val root = hostRootPane
        forceDismiss()

        if (root != null && originalEvent != null && originalEvent.isPopupTrigger) {
            val screenPt = originalEvent.locationOnScreen
            val contentPt = SwingUtilities.convertPoint(root, pt, root.contentPane)
            val deep = SwingUtilities.getDeepestComponentAt(root.contentPane, contentPt.x, contentPt.y)
            if (deep != null && deep !== this) {
                val localPt = SwingUtilities.convertPoint(root.contentPane, contentPt, deep)
                deep.dispatchEvent(
                    MouseEvent(
                        deep,
                        originalEvent.id,
                        originalEvent.`when`,
                        originalEvent.modifiersEx,
                        localPt.x,
                        localPt.y,
                        screenPt.x,
                        screenPt.y,
                        originalEvent.clickCount,
                        originalEvent.isPopupTrigger,
                        originalEvent.button,
                    ),
                )
            }
        }
    }

    private data class GooeyPalette(
        val blobTop: Color,
        val blobBottom: Color,
        val blobHoverTop: Color,
        val blobHoverBottom: Color,
        val iconColor: Color,
        val hubTop: Color,
        val hubBottom: Color,
        val bridgeColor: Color,
        val dangerBg: Color,
        val dangerIcon: Color,
        val shadowAmbient: Color,
        val shadowContact: Color,
        val rimHighlight: Color,
        val outlineColor: Color,
        val glowColor: Color,
        val pillBg: Color,
        val keycapBg: Color,
    )

    private fun shiftBrightness(c: Color, delta: Int): Color = Color(
        (c.red + delta).coerceIn(0, 255),
        (c.green + delta).coerceIn(0, 255),
        (c.blue + delta).coerceIn(0, 255),
        c.alpha,
    )

    private fun resolveThemePalette(): GooeyPalette {
        val dark = Theme.isDark
        val preset = Theme.currentPreset
        val accent = Theme.PRIMARY

        return when {
            preset == Theme.ThemePreset.CUSTOM -> {
                GooeyPalette(
                    blobTop = shiftBrightness(Theme.BG_SURFACE, if (dark) 12 else 0),
                    blobBottom = Theme.BG_SURFACE,
                    blobHoverTop = shiftBrightness(Theme.BG_CARD_HOVER, if (dark) 16 else -4),
                    blobHoverBottom = Theme.BG_CARD_HOVER,
                    iconColor = Theme.TEXT_PRIMARY,
                    hubTop = shiftBrightness(accent, 18),
                    hubBottom = shiftBrightness(accent, -16),
                    bridgeColor = Theme.BG_SURFACE,
                    dangerBg = Theme.DANGER,
                    dangerIcon = Color.WHITE,
                    shadowAmbient = Color(0, 0, 0, 56),
                    shadowContact = Color(0, 0, 0, 92),
                    rimHighlight = Color(255, 255, 255, if (dark) 52 else 180),
                    outlineColor = Color(Theme.BORDER_SUBTLE.red, Theme.BORDER_SUBTLE.green, Theme.BORDER_SUBTLE.blue, 85),
                    glowColor = Color(accent.red, accent.green, accent.blue, 96),
                    pillBg = Theme.BG_SURFACE,
                    keycapBg = Color(255, 255, 255, if (dark) 24 else 18),
                )
            }
            preset == Theme.ThemePreset.MIDNIGHT_BLUE -> {
                GooeyPalette(
                    blobTop = Color(0x21, 0x31, 0x4C),
                    blobBottom = Color(0x16, 0x22, 0x36),
                    blobHoverTop = Color(0x2E, 0x44, 0x6A),
                    blobHoverBottom = Color(0x20, 0x32, 0x50),
                    iconColor = Color(0xF0, 0xF6, 0xFC),
                    hubTop = shiftBrightness(accent, 22),
                    hubBottom = shiftBrightness(accent, -18),
                    bridgeColor = Color(0x1E, 0x2D, 0x47),
                    dangerBg = Theme.DANGER,
                    dangerIcon = Color.WHITE,
                    shadowAmbient = Color(4, 8, 18, 66),
                    shadowContact = Color(4, 8, 18, 118),
                    rimHighlight = Color(190, 210, 255, 58),
                    outlineColor = Color(129, 140, 248, 72),
                    glowColor = Color(accent.red, accent.green, accent.blue, 108),
                    pillBg = Color(0x16, 0x22, 0x36, 244),
                    keycapBg = Color(255, 255, 255, 26),
                )
            }
            !dark -> {
                GooeyPalette(
                    blobTop = Color(0xFF, 0xFF, 0xFF),
                    blobBottom = Color(0xF2, 0xF3, 0xF8),
                    blobHoverTop = Color(0xF0, 0xF1, 0xFA),
                    blobHoverBottom = Color(0xE4, 0xE6, 0xF4),
                    iconColor = Color(0x18, 0x1A, 0x26),
                    hubTop = shiftBrightness(accent, 18),
                    hubBottom = shiftBrightness(accent, -14),
                    bridgeColor = Color(0xF5, 0xF6, 0xFB),
                    dangerBg = Theme.DANGER,
                    dangerIcon = Color.WHITE,
                    shadowAmbient = Color(18, 22, 38, 26),
                    shadowContact = Color(18, 22, 38, 48),
                    rimHighlight = Color(255, 255, 255, 220),
                    outlineColor = Color(24, 26, 38, 30),
                    glowColor = Color(accent.red, accent.green, accent.blue, 68),
                    pillBg = Color(0xFF, 0xFF, 0xFF, 248),
                    keycapBg = Color(24, 26, 38, 18),
                )
            }
            else -> {
                val isAmoled = preset == Theme.ThemePreset.AMOLED
                GooeyPalette(
                    blobTop = if (isAmoled) Color(0x20, 0x22, 0x2C) else Color(0x2B, 0x2D, 0x36),
                    blobBottom = if (isAmoled) Color(0x12, 0x13, 0x19) else Color(0x1D, 0x1E, 0x25),
                    blobHoverTop = Color(0x36, 0x39, 0x48),
                    blobHoverBottom = Color(0x27, 0x29, 0x35),
                    iconColor = Color(0xF2, 0xF4, 0xFA),
                    hubTop = shiftBrightness(accent, 22),
                    hubBottom = shiftBrightness(accent, -18),
                    bridgeColor = if (isAmoled) Color(0x1C, 0x1E, 0x28) else Color(0x26, 0x28, 0x33),
                    dangerBg = Theme.DANGER,
                    dangerIcon = Color.WHITE,
                    shadowAmbient = Color(0, 0, 0, 68),
                    shadowContact = Color(0, 0, 0, 124),
                    rimHighlight = Color(255, 255, 255, 52),
                    outlineColor = Color(255, 255, 255, 38),
                    glowColor = Color(accent.red, accent.green, accent.blue, 112),
                    pillBg = Color(0x18, 0x1A, 0x22, 246),
                    keycapBg = Color(255, 255, 255, 26),
                )
            }
        }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val palette = resolveThemePalette()
        val openProgress = hubScale.value.coerceIn(0.0, 1.0).toFloat()

        val currentHubR = HUB_RADIUS * hubScale.value * (if (hubHovered) 1.08 else 1.0)
        for (i in items.indices) {
            val (nx, ny) = nodeCenter(i)
            centersX[i] = nx
            centersY[i] = ny
            val p = itemSprings[i].value.coerceAtLeast(0.0)
            radii[i] = NODE_RADIUS * min(1.0, p * 1.15) * hoverScales[i].value
        }

        // 1. Pre-rendered soft ambient halo sprites (hardware-fast, zero RadialGradientPaint per frame)
        if (currentHubR > 4.0) {
            drawGlowSprite(g2, anchorX.toFloat(), anchorY.toFloat(), (currentHubR * 2.7).toFloat(), palette.glowColor, openProgress * 0.65f)
        }
        if (hoveredIndex in items.indices && !closing && radii[hoveredIndex] > 4.0) {
            val glowTint = if (items[hoveredIndex].isDanger) palette.dangerBg else palette.glowColor
            drawGlowSprite(
                g2,
                centersX[hoveredIndex].toFloat(),
                centersY[hoveredIndex].toFloat(),
                (radii[hoveredIndex] * 2.35).toFloat(),
                glowTint,
                0.55f,
            )
        }

        // 2. Dual-layer liquid drop shadow
        paintShadowPass(g2, 0.0, 6.0, 1.04, palette.shadowAmbient, currentHubR)
        paintShadowPass(g2, 0.0, 2.2, 1.01, palette.shadowContact, currentHubR)

        // 3. Analytical Vector Gooey Metaball Bridges (elastic liquid necks)
        drawMetaballBridges(g2, currentHubR, palette)

        // 4. Satellite liquid glass orbs
        for (i in items.indices) {
            val r = radii[i]
            if (r > 1.2) {
                drawSatelliteOrb(g2, i, centersX[i], centersY[i], r, palette)
            }
        }

        // 5. Central Hub Orb
        if (currentHubR > 1.2) {
            drawHubOrb(g2, anchorX, anchorY, currentHubR, palette)
        }

        // 6. Satellite Icons
        val cx = anchorX.toFloat()
        val cy = anchorY.toFloat()
        items.forEachIndexed { idx, item ->
            val progress = itemSprings[idx].value.toFloat()
            if (progress > 0.16f) {
                val nx = centersX[idx].toFloat()
                val ny = centersY[idx].toFloat()
                val alpha = ((progress - 0.16f) / 0.84f).coerceIn(0f, 1f)
                val scale = hoverScales[idx].value.toFloat() * progress.coerceAtMost(1.04f)
                val isHov = idx == hoveredIndex
                val iconColor = when {
                    item.isDanger && isHov -> palette.dangerIcon
                    item.isDanger -> Color(palette.dangerBg.red, palette.dangerBg.green, palette.dangerBg.blue)
                    item.isActive -> Color.WHITE
                    else -> palette.iconColor
                }
                drawSatelliteIcon(
                    g2 = g2,
                    cx = nx,
                    cy = ny,
                    scale = scale,
                    alpha = alpha,
                    type = item.iconType,
                    color = iconColor,
                    hovered = isHov,
                    active = item.isActive,
                )
            }
        }

        // 7. Central Hub Clean Rotating Close Icon (✕) — zero morph artifacts
        val hubS = hubScale.value.toFloat().coerceAtLeast(0f)
        if (hubS > 0.1f) {
            drawHubCloseIcon(g2, cx, cy, hubS, openProgress, Color.WHITE)
        }

        // 8. Floating Tooltip Pill with Shortcut Keycap
        val pillAlpha = labelAlpha.value.toFloat().coerceIn(0f, 1f)
        if (pillAlpha > 0.03f && lastDisplayedLabelIndex in items.indices && !closing) {
            val idx = if (hoveredIndex in items.indices) hoveredIndex else lastDisplayedLabelIndex
            val item = items[idx]
            val nx = centersX[idx].toFloat()
            val ny = centersY[idx].toFloat()
            drawFloatingLabelPill(g2, item, nx, ny, pillAlpha, palette)
        }

        g2.dispose()
    }

    private fun drawGlowSprite(g2: Graphics2D, cx: Float, cy: Float, radius: Float, tint: Color, alphaScale: Float) {
        if (radius <= 2f || alphaScale <= 0.02f) return
        val sprite = getOrCreateGlowSprite(tint)
        val prevComp = g2.composite
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alphaScale.coerceIn(0f, 1f))
        val size = (radius * 2f).toInt()
        g2.drawImage(sprite, (cx - radius).toInt(), (cy - radius).toInt(), size, size, null)
        g2.composite = prevComp
    }

    private fun paintShadowPass(
        g2: Graphics2D,
        offsetX: Double,
        offsetY: Double,
        scaleR: Double,
        color: Color,
        currentHubR: Double,
    ) {
        val sg = g2.create() as Graphics2D
        sg.translate(offsetX, offsetY)
        sg.color = color
        for (i in items.indices) {
            val r = radii[i] * scaleR
            if (r > 1.2) {
                reusableEllipse.setFrame(centersX[i] - r, centersY[i] - r, r * 2.0, r * 2.0)
                sg.fill(reusableEllipse)
            }
        }
        if (currentHubR > 1.2) {
            val hr = currentHubR * scaleR
            reusableEllipse.setFrame(anchorX - hr, anchorY - hr, hr * 2.0, hr * 2.0)
            sg.fill(reusableEllipse)
        }
        sg.dispose()
    }

    /**
     * Analytical vector metaball bridges:
     * Renders genuinely visible molten liquid necks connecting the central hub and satellites
     * during elastic bloom, retraction, and hovered magnetic tension.
     */
    private fun drawMetaballBridges(g2: Graphics2D, currentHubR: Double, palette: GooeyPalette) {
        if (currentHubR <= 2.0) return

        for (i in items.indices) {
            val r2 = radii[i]
            if (r2 <= 2.0) continue
            val isHov = i == hoveredIndex && !closing
            // Generous liquid reach so the gooey neck stretches visibly during bloom and stays subtly tethered on hover
            val extraReach = if (isHov) 54.0 else 44.0
            val maxDist = currentHubR + r2 + extraReach
            val d = hypot(centersX[i] - anchorX, centersY[i] - anchorY)
            val minDist = kotlin.math.abs(currentHubR - r2) + 1.0
            if (d > minDist && d < maxDist) {
                val tension = (1.0 - (d - minDist) / (maxDist - minDist)).coerceIn(0.0, 1.0)
                // Smooth sqrt curve keeps the bridge richly visible before snapping cleanly
                val alpha = (sqrt(tension) * (if (isHov) 210.0 else 235.0)).toInt().coerceIn(0, 235)
                if (alpha > 10) {
                    val cStart = Color(palette.hubBottom.red, palette.hubBottom.green, palette.hubBottom.blue, alpha)
                    val endBase = when {
                        items[i].isDanger && isHov -> palette.dangerBg
                        isHov -> palette.blobHoverBottom
                        else -> palette.bridgeColor
                    }
                    val cEnd = Color(endBase.red, endBase.green, endBase.blue, alpha)
                    fillMetaballBridge(
                        g2 = g2,
                        x1 = anchorX,
                        y1 = anchorY,
                        r1 = currentHubR * 0.95,
                        x2 = centersX[i],
                        y2 = centersY[i],
                        r2 = r2 * 0.95,
                        v = 0.45 + 0.30 * tension,
                        handleScale = 2.25,
                        paint = GradientPaint(
                            anchorX.toFloat(),
                            anchorY.toFloat(),
                            cStart,
                            centersX[i].toFloat(),
                            centersY[i].toFloat(),
                            cEnd,
                        ),
                    )
                }
            }
        }
    }

    private fun fillMetaballBridge(
        g2: Graphics2D,
        x1: Double,
        y1: Double,
        r1: Double,
        x2: Double,
        y2: Double,
        r2: Double,
        v: Double,
        handleScale: Double,
        paint: java.awt.Paint,
    ) {
        val d = hypot(x2 - x1, y2 - y1)
        if (d <= 0.001 || d <= kotlin.math.abs(r1 - r2)) return

        val u1: Double
        val u2: Double
        if (d < r1 + r2) {
            u1 = acos(((r1 * r1 + d * d - r2 * r2) / (2.0 * r1 * d)).coerceIn(-1.0, 1.0))
            u2 = acos(((r2 * r2 + d * d - r1 * r1) / (2.0 * r2 * d)).coerceIn(-1.0, 1.0))
        } else {
            u1 = 0.0
            u2 = 0.0
        }

        val angleBetween = atan2(y2 - y1, x2 - x1)
        val maxSpread = acos(((r1 - r2) / d).coerceIn(-1.0, 1.0))

        val angle1 = angleBetween + u1 + (maxSpread - u1) * v
        val angle2 = angleBetween - u1 - (maxSpread - u1) * v
        val angle3 = angleBetween + PI - u2 - (PI - u2 - maxSpread) * v
        val angle4 = angleBetween - PI + u2 + (PI - u2 - maxSpread) * v

        val p1x = x1 + cos(angle1) * r1
        val p1y = y1 + sin(angle1) * r1
        val p2x = x1 + cos(angle2) * r1
        val p2y = y1 + sin(angle2) * r1
        val p3x = x2 + cos(angle3) * r2
        val p3y = y2 + sin(angle3) * r2
        val p4x = x2 + cos(angle4) * r2
        val p4y = y2 + sin(angle4) * r2

        val totalRadius = r1 + r2
        val d2Base = min(v * handleScale, hypot(p1x - p3x, p1y - p3y) / totalRadius)
        val d2 = d2Base * min(1.0, d * 2.0 / totalRadius)
        val h1 = r1 * d2
        val h2 = r2 * d2

        val c1x = p1x + cos(angle1 - PI / 2.0) * h1
        val c1y = p1y + sin(angle1 - PI / 2.0) * h1
        val c3x = p3x + cos(angle3 + PI / 2.0) * h2
        val c3y = p3y + sin(angle3 + PI / 2.0) * h2
        val c4x = p4x + cos(angle4 - PI / 2.0) * h2
        val c4y = p4y + sin(angle4 - PI / 2.0) * h2
        val c2x = p2x + cos(angle2 + PI / 2.0) * h1
        val c2y = p2y + sin(angle2 + PI / 2.0) * h1

        reusableBridge.reset()
        reusableBridge.moveTo(p1x, p1y)
        reusableBridge.curveTo(c1x, c1y, c3x, c3y, p3x, p3y)
        reusableBridge.lineTo(p4x, p4y)
        reusableBridge.curveTo(c4x, c4y, c2x, c2y, p2x, p2y)
        reusableBridge.closePath()

        val prevPaint = g2.paint
        g2.paint = paint
        g2.fill(reusableBridge)
        g2.paint = prevPaint
    }

    private fun drawSatelliteOrb(
        g2: Graphics2D,
        index: Int,
        cx: Double,
        cy: Double,
        r: Double,
        palette: GooeyPalette,
    ) {
        val item = items[index]
        val isHov = index == hoveredIndex && !closing

        val topCol: Color
        val botCol: Color
        when {
            item.isDanger && isHov -> {
                topCol = shiftBrightness(palette.dangerBg, 16)
                botCol = shiftBrightness(palette.dangerBg, -14)
            }
            item.isActive -> {
                topCol = shiftBrightness(Theme.PRIMARY, if (isHov) 24 else 12)
                botCol = shiftBrightness(Theme.PRIMARY, if (isHov) -8 else -18)
            }
            isHov -> {
                topCol = palette.blobHoverTop
                botCol = palette.blobHoverBottom
            }
            else -> {
                topCol = palette.blobTop
                botCol = palette.blobBottom
            }
        }

        reusableEllipse.setFrame(cx - r, cy - r, r * 2.0, r * 2.0)
        val prevPaint = g2.paint
        g2.paint = GradientPaint(cx.toFloat(), (cy - r).toFloat(), topCol, cx.toFloat(), (cy + r).toFloat(), botCol)
        g2.fill(reusableEllipse)

        val rimStrokeColor = when {
            item.isDanger && isHov -> Color(255, 255, 255, 130)
            isHov -> Color(Theme.PRIMARY.red, Theme.PRIMARY.green, Theme.PRIMARY.blue, 215)
            item.isActive -> Color(255, 255, 255, 110)
            else -> palette.outlineColor
        }
        g2.paint = GradientPaint(
            cx.toFloat(),
            (cy - r).toFloat(),
            if (isHov) rimStrokeColor else palette.rimHighlight,
            cx.toFloat(),
            (cy + r).toFloat(),
            rimStrokeColor,
        )
        g2.stroke = BasicStroke(if (isHov) 1.75f else 1.15f)
        g2.draw(reusableEllipse)
        g2.paint = prevPaint

        if (item.isActive && !isHov) {
            val dotR = 3.5
            val dx = cx + r * 0.62
            val dy = cy - r * 0.62
            g2.color = Theme.SUCCESS
            reusableEllipse.setFrame(dx - dotR, dy - dotR, dotR * 2.0, dotR * 2.0)
            g2.fill(reusableEllipse)
            g2.color = Color.WHITE
            g2.stroke = BasicStroke(1.1f)
            g2.draw(reusableEllipse)
        }
    }

    private fun drawHubOrb(g2: Graphics2D, cx: Double, cy: Double, r: Double, palette: GooeyPalette) {
        reusableEllipse.setFrame(cx - r, cy - r, r * 2.0, r * 2.0)
        val prevPaint = g2.paint
        g2.paint = GradientPaint(cx.toFloat(), (cy - r).toFloat(), palette.hubTop, cx.toFloat(), (cy + r).toFloat(), palette.hubBottom)
        g2.fill(reusableEllipse)

        g2.paint = GradientPaint(
            cx.toFloat(),
            (cy - r).toFloat(),
            Color(255, 255, 255, 145),
            cx.toFloat(),
            (cy + r).toFloat(),
            Color(255, 255, 255, 25),
        )
        g2.stroke = BasicStroke(1.35f)
        g2.draw(reusableEllipse)
        g2.paint = prevPaint
    }

    private fun drawHubCloseIcon(g2: Graphics2D, cx: Float, cy: Float, scale: Float, openProgress: Float, color: Color) {
        val g = g2.create() as Graphics2D
        g.translate(cx.toDouble(), cy.toDouble())
        g.rotate(((1f - openProgress) * (-PI / 4.0)))
        val iconScale = (scale * (0.72f + 0.28f * openProgress)).toDouble()
        g.scale(iconScale, iconScale)
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, openProgress.coerceIn(0f, 1f))
        g.color = color
        g.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val r = 6.8
        g.draw(java.awt.geom.Line2D.Double(-r, -r, r, r))
        g.draw(java.awt.geom.Line2D.Double(r, -r, -r, r))
        g.dispose()
    }

    private fun drawSatelliteIcon(
        g2: Graphics2D,
        cx: Float,
        cy: Float,
        scale: Float,
        alpha: Float,
        type: IconType,
        color: Color,
        hovered: Boolean,
        active: Boolean = false,
    ) {
        val g = g2.create() as Graphics2D
        g.translate(cx.toDouble(), cy.toDouble())
        g.scale(scale.toDouble(), scale.toDouble())
        val aInt = (alpha * 255).toInt().coerceIn(0, 255)
        g.color = Color(color.red, color.green, color.blue, (color.alpha * aInt) / 255)
        g.stroke = BasicStroke(if (hovered) 2.3f else 2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

        when (type) {
            IconType.OPEN_DETAILS -> {
                val path = Path2D.Float()
                path.moveTo(-9f, -6f)
                path.quadTo(-4f, -8f, 0f, -5f)
                path.quadTo(4f, -8f, 9f, -6f)
                path.lineTo(9f, 7f)
                path.quadTo(4f, 5f, 0f, 8f)
                path.quadTo(-4f, 5f, -9f, 7f)
                path.closePath()
                g.draw(path)
                g.drawLine(0, -5, 0, 8)
            }
            IconType.READING -> {
                val path = Path2D.Float()
                path.moveTo(-5f, -8f)
                path.lineTo(8f, 0f)
                path.lineTo(-5f, 8f)
                path.closePath()
                g.fill(path)
            }
            IconType.COMPLETED -> {
                val path = Path2D.Float()
                path.moveTo(-8f, 0f)
                path.lineTo(-2.5f, 6f)
                path.lineTo(8.5f, -6f)
                g.draw(path)
            }
            IconType.PLAN_TO_READ, IconType.BOOKMARK -> {
                val path = Path2D.Float()
                path.moveTo(-6f, -8.5f)
                path.lineTo(6f, -8.5f)
                path.lineTo(6f, 8.5f)
                path.lineTo(0f, 3.8f)
                path.lineTo(-6f, 8.5f)
                path.closePath()
                if (active) g.fill(path) else g.draw(path)
            }
            IconType.REMOVE -> {
                g.drawLine(-8, -5, 8, -5)
                g.drawLine(-3, -8, 3, -8)
                g.drawRoundRect(-6, -5, 12, 13, 3, 3)
                g.drawLine(-2, -1, -2, 4)
                g.drawLine(2, -1, 2, 4)
            }
            IconType.DOWNLOAD -> {
                g.drawLine(0, -8, 0, 4)
                val arrow = Path2D.Float()
                arrow.moveTo(-5f, 0f)
                arrow.lineTo(0f, 5f)
                arrow.lineTo(5f, 0f)
                g.draw(arrow)
                g.drawLine(-7, 8, 7, 8)
            }
            IconType.UNREAD -> {
                g.drawOval(-6, -6, 12, 12)
                g.drawLine(-4, -4, 4, 4)
            }
            IconType.MOVE_LEFT -> {
                g.drawLine(5, 0, -5, 0)
                val arrow = Path2D.Float()
                arrow.moveTo(0f, -5f)
                arrow.lineTo(-5f, 0f)
                arrow.lineTo(0f, 5f)
                g.draw(arrow)
            }
            IconType.MOVE_RIGHT -> {
                g.drawLine(-5, 0, 5, 0)
                val arrow = Path2D.Float()
                arrow.moveTo(0f, -5f)
                arrow.lineTo(5f, 0f)
                arrow.lineTo(0f, 5f)
                g.draw(arrow)
            }
            IconType.EDIT, IconType.NOTE -> {
                val pencil = Path2D.Float()
                pencil.moveTo(-5.5f, 5.5f)
                pencil.lineTo(-2f, 5.5f)
                pencil.lineTo(5.5f, -2f)
                pencil.lineTo(2f, -5.5f)
                pencil.lineTo(-5.5f, 2f)
                pencil.closePath()
                g.draw(pencil)
                g.drawLine(0, -3, 3, 0)
            }
            IconType.TRANSLATE -> {
                g.drawLine(-8, -5, 0, -5)
                g.drawLine(-4, -8, -4, -5)
                val arc = Path2D.Float()
                arc.moveTo(-7f, 2f)
                arc.quadTo(-4f, -1f, -1f, -5f)
                g.draw(arc)
                g.drawLine(-6, -3, -1, 2)
                val aGlyph = Path2D.Float()
                aGlyph.moveTo(1.5f, 7.5f)
                aGlyph.lineTo(5f, -1.5f)
                aGlyph.lineTo(8.5f, 7.5f)
                g.draw(aGlyph)
                g.drawLine(3, 4, 7, 4)
            }
            IconType.HIGHLIGHT -> {
                val star = Path2D.Float()
                star.moveTo(0f, -9f)
                star.quadTo(1.5f, -1.5f, 9f, 0f)
                star.quadTo(1.5f, 1.5f, 0f, 9f)
                star.quadTo(-1.5f, 1.5f, -9f, 0f)
                star.quadTo(-1.5f, -1.5f, 0f, -9f)
                star.closePath()
                g.fill(star)
            }
            IconType.BOOKMARKS_LIST -> {
                g.drawRoundRect(-8, -7, 16, 14, 4, 4)
                g.drawLine(-4, -3, 4, -3)
                g.drawLine(-4, 0, 4, 0)
                g.drawLine(-4, 3, 1, 3)
            }
            IconType.COPY -> {
                g.drawRoundRect(-3, -7, 10, 11, 3, 3)
                val back = Path2D.Float()
                back.moveTo(-3f, -3f)
                back.lineTo(-6f, -3f)
                back.quadTo(-8f, -3f, -8f, -1f)
                back.lineTo(-8f, 6f)
                back.quadTo(-8f, 8f, -6f, 8f)
                back.lineTo(1f, 8f)
                back.quadTo(3f, 8f, 3f, 6f)
                back.lineTo(3f, 4f)
                g.draw(back)
            }
        }
        g.dispose()
    }

    private fun drawFloatingLabelPill(
        g2: Graphics2D,
        item: GooeyMenuItem,
        nodeX: Float,
        nodeY: Float,
        alpha: Float,
        palette: GooeyPalette,
    ) {
        val g = g2.create() as Graphics2D
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.coerceIn(0f, 1f))
        g.font = Theme.FONT_UI.deriveFont(Font.BOLD, 12.5f)
        val fm = g.fontMetrics
        val padH = 14
        val textW = fm.stringWidth(item.label)

        val hint = item.shortcutHint?.takeIf { it.isNotBlank() }
        val keyFont = Theme.FONT_MONO.deriveFont(Font.BOLD, 10.5f)
        val keyFm = g.getFontMetrics(keyFont)
        val keyW = if (hint != null) keyFm.stringWidth(hint) + 12 else 0
        val keyGap = if (hint != null) 8 else 0

        val pillW = textW + keyGap + keyW + padH * 2
        val pillH = 30
        val slideOffset = (1f - alpha) * 5f

        // Always position cleanly outside the entire radial ring so it never overlaps any satellite orb
        val ringOuter = (orbitRadiusBase + NODE_RADIUS + 18.0).toFloat()
        val px = (anchorX.toFloat() - pillW / 2f).coerceIn(12f, max(12f, (width - pillW - 12).toFloat()))
        val canPlaceBelow = anchorY.toFloat() + ringOuter + pillH + 10f <= height
        val rawPy = if (canPlaceBelow) {
            anchorY.toFloat() + ringOuter + slideOffset
        } else {
            anchorY.toFloat() - ringOuter - pillH - slideOffset
        }
        val py = rawPy.coerceIn(10f, max(10f, (height - pillH - 10).toFloat()))

        // Soft elevation shadow
        g.color = Color(0, 0, 0, 105)
        g.fill(RoundRectangle2D.Float(px, py + 3f, pillW.toFloat(), pillH.toFloat(), pillH.toFloat(), pillH.toFloat()))

        // Pill surface
        g.color = if (item.isDanger) palette.dangerBg else palette.pillBg
        g.fill(RoundRectangle2D.Float(px, py, pillW.toFloat(), pillH.toFloat(), pillH.toFloat(), pillH.toFloat()))

        // Crisp rim border
        g.color = if (item.isDanger) Color(255, 255, 255, 130) else palette.outlineColor
        g.stroke = BasicStroke(1.15f)
        g.draw(RoundRectangle2D.Float(px, py, pillW.toFloat(), pillH.toFloat(), pillH.toFloat(), pillH.toFloat()))

        // Label text
        g.color = if (item.isDanger) Color.WHITE else palette.iconColor
        g.drawString(item.label, px + padH, py + (pillH - fm.height) / 2f + fm.ascent)

        // Shortcut keycap badge
        if (hint != null) {
            val kx = px + padH + textW + keyGap
            val kh = 18f
            val ky = py + (pillH - kh) / 2f
            g.color = if (item.isDanger) Color(0, 0, 0, 55) else palette.keycapBg
            g.fill(RoundRectangle2D.Float(kx, ky, keyW.toFloat(), kh, 8f, 8f))
            g.font = keyFont
            g.color = if (item.isDanger) Color.WHITE else Theme.TEXT_SECONDARY
            g.drawString(hint, kx + 6f, ky + (kh - keyFm.height) / 2f + keyFm.ascent)
        }

        g.dispose()
    }

    enum class MenuStyle(val label: String) {
        RADIAL("Radial Animado (Padrão)"),
        CLASSIC("Menu Clássico Compacto"),
    }

    companion object {
        private const val HUB_RADIUS = 28.0
        private const val NODE_RADIUS = 24.5
        private val stylePrefs = java.util.prefs.Preferences.userRoot().node("mihon-linux")

        // Cached pre-rendered radial glow sprites keyed by ARGB to avoid RadialGradientPaint overhead
        private val glowSpriteCache = java.util.concurrent.ConcurrentHashMap<Int, BufferedImage>()

        init {
            MemoryManager.registerTrimCallback {
                glowSpriteCache.values.forEach { runCatching { it.flush() } }
                glowSpriteCache.clear()
            }
        }

        private fun getOrCreateGlowSprite(tint: Color): BufferedImage {
            return glowSpriteCache.computeIfAbsent(tint.rgb) {
                val size = 96
                val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
                val half = size / 2.0
                for (y in 0 until size) {
                    val dy = (y + 0.5 - half) / half
                    for (x in 0 until size) {
                        val dx = (x + 0.5 - half) / half
                        val dist = hypot(dx, dy)
                        if (dist < 1.0) {
                            val falloff = (1.0 - dist) * (1.0 - dist)
                            val a = (tint.alpha * falloff).toInt().coerceIn(0, 255)
                            img.setRGB(x, y, (a shl 24) or (tint.red shl 16) or (tint.green shl 8) or tint.blue)
                        }
                    }
                }
                img
            }
        }

        var preferredStyle: MenuStyle
            get() = runCatching {
                MenuStyle.valueOf(stylePrefs.get("contextMenuStyle", MenuStyle.RADIAL.name))
            }.getOrDefault(MenuStyle.RADIAL)
            set(value) = stylePrefs.put("contextMenuStyle", value.name)

        /**
         * Displays the user's preferred contextual action menu ([MenuStyle.RADIAL] or [MenuStyle.CLASSIC]).
         */
        fun showSmart(items: List<GooeyMenuItem>, invoker: Component, x: Int, y: Int) {
            if (items.isEmpty()) return
            if (preferredStyle == MenuStyle.CLASSIC) {
                val popup = mihon.desktop.ui.components.MihonPopupMenu.create()
                items.forEach { item ->
                    val icon = when (item.iconType) {
                        IconType.OPEN_DETAILS -> ModernIcons.bookOpen(15) { Theme.TEXT_PRIMARY }
                        IconType.READING -> ModernIcons.play(15) { Theme.TEXT_PRIMARY }
                        IconType.COMPLETED -> ModernIcons.checkCircle(15) { Theme.SUCCESS }
                        IconType.PLAN_TO_READ, IconType.BOOKMARK -> ModernIcons.bookmark(15) {
                            if (item.isActive) Theme.PRIMARY else Theme.TEXT_PRIMARY
                        }
                        IconType.REMOVE -> ModernIcons.trash(15) { Theme.DANGER }
                        IconType.DOWNLOAD -> ModernIcons.downloads(15) { Theme.TEXT_PRIMARY }
                        IconType.UNREAD -> ModernIcons.eyeOff(15) { Theme.TEXT_PRIMARY }
                        IconType.MOVE_LEFT -> ModernIcons.chevronLeft(15) { Theme.TEXT_PRIMARY }
                        IconType.MOVE_RIGHT -> ModernIcons.chevronRight(15) { Theme.TEXT_PRIMARY }
                        IconType.EDIT, IconType.NOTE -> ModernIcons.edit(15) { Theme.TEXT_PRIMARY }
                        IconType.TRANSLATE -> ModernIcons.translateIcon(15)
                        IconType.HIGHLIGHT -> ModernIcons.sparkles(15) { Theme.TEXT_PRIMARY }
                        IconType.BOOKMARKS_LIST -> ModernIcons.bookmark(15) { Theme.TEXT_PRIMARY }
                        IconType.COPY -> ModernIcons.copy(15) { Theme.TEXT_PRIMARY }
                    }
                    if (item.isDanger && popup.componentCount > 0) popup.addSeparator()
                    popup.add(
                        mihon.desktop.ui.components.MihonPopupMenu.item(
                            text = item.label,
                            icon = icon,
                            shortcutHint = item.shortcutHint,
                            danger = item.isDanger,
                            onClick = item.onClick,
                        ),
                    )
                }
                popup.show(invoker, x, y)
            } else {
                GooeyContextMenu(items).showAt(invoker, x, y)
            }
        }
    }
}
