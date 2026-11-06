package mihon.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
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
import javax.swing.JLayeredPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Authentic Gooey Menu (Lucas Bebber SVG Filter / Metaball Effect) ported to 60fps Swing Graphics2D.
 *
 * Visual Mechanics matching the reference video:
 * 1. A central circular trigger button (`≡` morphing smoothly into `✕`) spawns at the click point.
 * 2. Circular action nodes fan out in a radial arc around the center with staggered spring physics
 *    (elastic overshoot & damping).
 * 3. Real-time SVG Gooey Filter (`feGaussianBlur` + `feColorMatrix` alpha contrast threshold `18 * alpha - 7`)
 *    plus smooth metaball liquid bridges fuse the central hub and satellite buttons when they are close
 *    or overlapping in the arc.
 * 4. Hovering any satellite node scales it up elastically and displays a sleek floating pill label
 *    above/below so the user always knows both the icon and the exact Portuguese action title.
 * 5. Clicking the central `✕`, clicking outside, or pressing `ESC` retracts all nodes back into the
 *    central blob with surface-tension absorption before disappearing.
 */
class GooeyContextMenu(
    private val items: List<GooeyMenuItem>,
) : JComponent() {

    data class GooeyMenuItem(
        val id: String,
        val label: String,
        val iconType: IconType,
        val isDanger: Boolean = false,
        val onClick: () -> Unit,
    )

    enum class IconType {
        OPEN_DETAILS,
        READING,
        COMPLETED,
        PLAN_TO_READ,
        REMOVE,
    }

    private class SpringValue(
        var value: Double = 0.0,
        var target: Double = 0.0,
        var velocity: Double = 0.0,
        private val stiffness: Double = 230.0,
        private val damping: Double = 15.5,
    ) {
        fun update(dt: Double): Boolean {
            val force = -stiffness * (value - target) - damping * velocity
            velocity += force * dt
            value += velocity * dt
            val settled = kotlin.math.abs(value - target) < 0.002 && kotlin.math.abs(velocity) < 0.01
            if (settled) {
                value = target
                velocity = 0.0
            }
            return !settled
        }
    }

    private var anchorX: Double = 0.0
    private var anchorY: Double = 0.0
    private var arcCenterAngle: Double = PI / 2.0 // Default: fan downward/around like the video

    private val hubScale = SpringValue(0.15, 1.0, stiffness = 280.0, damping = 16.0)
    private val crossMorph = SpringValue(0.0, 1.0, stiffness = 240.0, damping = 18.0)
    private val itemSprings = items.map { SpringValue(0.0, 0.0, stiffness = 210.0, damping = 13.5) }
    private val hoverScales = items.map { SpringValue(1.0, 1.0, stiffness = 320.0, damping = 20.0) }

    private var hoveredIndex: Int = -1
    private var hubHovered: Boolean = false
    private var closing: Boolean = false
    private var pendingAction: (() -> Unit)? = null
    private var elapsedTicks: Int = 0
    private var hostLayeredPane: JLayeredPane? = null

    // Buffer for the authentic SVG Gooey Filter (Blur + Alpha Contrast Threshold)
    private var filterBufW = 380
    private var filterBufH = 380
    private var rawMaskImg = BufferedImage(filterBufW, filterBufH, BufferedImage.TYPE_INT_ARGB)
    private var blurredImg = BufferedImage(filterBufW, filterBufH, BufferedImage.TYPE_INT_ARGB)
    private var gooeyOutImg = BufferedImage(filterBufW, filterBufH, BufferedImage.TYPE_INT_ARGB)
    private var rawPixels = IntArray(filterBufW * filterBufH)
    private var tempPixels = IntArray(filterBufW * filterBufH)
    private var outPixels = IntArray(filterBufW * filterBufH)

    // Precomputed 1D Gaussian kernel (radius = 11px, matching SVG stdDeviation="10")
    private val blurRadius = 11
    private val gaussianKernel: IntArray = run {
        val sigma = 5.4
        val weights = DoubleArray(blurRadius * 2 + 1)
        var sum = 0.0
        for (i in -blurRadius..blurRadius) {
            val w = kotlin.math.exp(-(i * i) / (2.0 * sigma * sigma))
            weights[i + blurRadius] = w
            sum += w
        }
        // Fixed-point 16-bit weights for ultra-fast integer convolution
        IntArray(weights.size) { ((weights[it] / sum) * 65536.0).toInt() }
    }

    private val animTimer = Timer(16) {
        onTick()
    }

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
                handleClick(e.point)
            }
        })

        registerKeyboardAction(
            { closeMenu(null) },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            WHEN_IN_FOCUSED_WINDOW,
        )
    }

    fun showAt(invoker: Component, x: Int, y: Int) {
        val window = SwingUtilities.getWindowAncestor(invoker) ?: return
        val layered = when (window) {
            is javax.swing.JFrame -> window.layeredPane
            is javax.swing.JDialog -> window.layeredPane
            else -> null
        } ?: return

        // Remove any previous active GooeyContextMenu
        layered.components.filterIsInstance<GooeyContextMenu>().forEach {
            it.forceDismiss()
        }

        hostLayeredPane = layered
        setBounds(0, 0, layered.width, layered.height)

        val pt = SwingUtilities.convertPoint(invoker, Point(x, y), layered)
        // Keep the radial arc comfortably inside window boundaries
        val margin = 128.0
        anchorX = pt.x.toDouble().coerceIn(margin, (layered.width - margin).coerceAtLeast(margin))
        anchorY = pt.y.toDouble().coerceIn(85.0, (layered.height - 135.0).coerceAtLeast(85.0))

        // Choose arc orientation: fan downward like Lucas Bebber's demo by default,
        // or upward if clicked very close to the bottom edge
        arcCenterAngle = if (pt.y > layered.height - 165) -PI / 2.0 else PI / 2.0

        closing = false
        elapsedTicks = 0
        hubScale.value = 0.2
        hubScale.target = 1.0
        crossMorph.value = 0.0
        crossMorph.target = 1.0
        itemSprings.forEach {
            it.value = 0.0
            it.target = 0.0
            it.velocity = 0.0
        }

        layered.add(this, JLayeredPane.POPUP_LAYER)
        layered.moveToFront(this)
        requestFocusInWindow()
        animTimer.start()
        layered.repaint()
    }

    private fun onTick() {
        elapsedTicks++
        val dt = 0.016

        if (!closing) {
            // Staggered launch of each satellite node (just like the video!)
            items.indices.forEach { i ->
                if (elapsedTicks >= i * 2 + 1) {
                    itemSprings[i].target = 1.0
                }
            }
        } else {
            items.indices.forEach { i ->
                val reverseDelay = (items.size - 1 - i) * 1
                if (elapsedTicks >= reverseDelay) {
                    itemSprings[i].target = 0.0
                }
            }
            if (itemSprings.all { it.value < 0.25 }) {
                hubScale.target = 0.0
                crossMorph.target = 0.0
            }
        }

        var moving = false
        if (hubScale.update(dt)) moving = true
        if (crossMorph.update(dt)) moving = true
        itemSprings.forEach { if (it.update(dt)) moving = true }
        hoverScales.forEachIndexed { idx, spring ->
            spring.target = if (idx == hoveredIndex && !closing) 1.18 else 1.0
            if (spring.update(dt)) moving = true
        }

        repaint()

        if (closing && !moving && hubScale.value <= 0.02) {
            val action = pendingAction
            forceDismiss()
            action?.invoke()
        } else if (!closing && !moving && elapsedTicks > 30) {
            // Keep timer running only when animated or hovered
            animTimer.stop()
        }
    }

    private fun ensureTimerRunning() {
        if (!animTimer.isRunning) {
            animTimer.start()
        }
    }

    private fun closeMenu(afterClose: (() -> Unit)?) {
        if (closing) return
        closing = true
        elapsedTicks = 0
        pendingAction = afterClose
        hoveredIndex = -1
        hubHovered = false
        ensureTimerRunning()
    }

    fun forceDismiss() {
        animTimer.stop()
        hostLayeredPane?.let {
            it.remove(this)
            it.repaint()
        }
        hostLayeredPane = null
    }

    private fun nodeAngle(index: Int): Double {
        // Fan 5 nodes across a 168-degree semicircle arc so adjacent nodes touch/bridge organically
        val totalSpan = Math.toRadians(168.0)
        val start = arcCenterAngle + totalSpan / 2.0
        val step = if (items.size > 1) totalSpan / (items.size - 1) else 0.0
        // If fanning downward (arcCenterAngle = +PI/2), go left-to-right (PI - 6deg down to 6deg)
        return start - index * step
    }

    private fun nodeCenter(index: Int): Pair<Double, Double> {
        val progress = itemSprings[index].value
        val orbitRadius = 80.0 * progress
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
                if (hypot(pt.x - nx, pt.y - ny) <= r + 4) {
                    newHovered = i
                    break
                }
            }
        }

        if (newHovered != hoveredIndex || newHubHover != hubHovered) {
            hoveredIndex = newHovered
            hubHovered = newHubHover
            cursor = if (hoveredIndex >= 0 || hubHovered) {
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            } else {
                Cursor.getDefaultCursor()
            }
            ensureTimerRunning()
            repaint()
        }
    }

    private fun handleClick(pt: Point) {
        if (closing) return

        // Check satellite nodes first
        for (i in items.indices) {
            val (nx, ny) = nodeCenter(i)
            val r = NODE_RADIUS * hoverScales[i].value
            if (hypot(pt.x - nx, pt.y - ny) <= r + 5) {
                closeMenu(items[i].onClick)
                return
            }
        }

        // Check central hub (`X`) or anywhere outside -> retract and close
        closeMenu(null)
    }

    /**
     * Resolves the vibrant Gooey color for the active theme.
     * In the reference video, the Gooey blob is a rich hot pink/magenta (`#FF3377`).
     * If the user customized a colorful accent in Theme, we honor it; if the theme accent is pure
     * monochrome white/black (like default AMOLED), we use the iconic Gooey vibrant magenta-rose
     * so the liquid metaball effect pops with high contrast over the manga covers!
     */
    private fun resolveGooeyPrimaryColor(): Color {
        val a = Theme.ACCENT
        val isMonochrome = kotlin.math.abs(a.red - a.green) < 12 && kotlin.math.abs(a.green - a.blue) < 12
        return if (isMonochrome) {
            Color(0xFF, 0x33, 0x77) // Authentic Lucas Bebber Gooey Pink-Magenta (#FF3377)
        } else {
            a
        }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        // Subtle backdrop dim around the menu so the gooey blobs stand out clearly
        val openProgress = hubScale.value.coerceIn(0.0, 1.0).toFloat()
        g2.color = Color(0, 0, 0, (65 * openProgress).toInt())
        g2.fillRect(0, 0, width, height)

        val baseColor = resolveGooeyPrimaryColor()
        val dangerColor = Color(0xFF, 0x3B, 0x4E)

        // 1. Render the authentic Gooey Liquid Field inside the local bounding box
        val boxLeft = (anchorX - filterBufW / 2.0).toInt()
        val boxTop = (anchorY - filterBufH / 2.0).toInt()

        renderGooeyMetaballField(boxLeft, boxTop, baseColor, dangerColor)
        g2.drawImage(gooeyOutImg, boxLeft, boxTop, null)

        // 2. Draw crisp white vector icons inside each satellite node and central hub
        val cx = anchorX.toFloat()
        val cy = anchorY.toFloat()

        items.forEachIndexed { idx, item ->
            val progress = itemSprings[idx].value.toFloat()
            if (progress > 0.12f) {
                val (nx, ny) = nodeCenter(idx)
                val alpha = ((progress - 0.12f) / 0.88f).coerceIn(0f, 1f)
                val scale = hoverScales[idx].value.toFloat() * progress.coerceAtMost(1.05f)
                drawSatelliteIcon(
                    g2 = g2,
                    cx = nx.toFloat(),
                    cy = ny.toFloat(),
                    scale = scale,
                    alpha = alpha,
                    type = item.iconType,
                    hovered = idx == hoveredIndex,
                )
            }
        }

        // Draw Central Hub Icon (morphing from Hamburger ≡ to Close ✕ as in the video)
        val hubS = hubScale.value.toFloat().coerceAtLeast(0f)
        if (hubS > 0.08f) {
            drawHubMorphIcon(g2, cx, cy, hubS, crossMorph.value.toFloat().coerceIn(0f, 1f))
        }

        // 3. Draw Floating Pill Tooltip for the hovered action (or active category hint)
        if (hoveredIndex in items.indices && !closing) {
            val item = items[hoveredIndex]
            val (nx, ny) = nodeCenter(hoveredIndex)
            drawFloatingLabelPill(g2, item.label, nx.toFloat(), ny.toFloat(), item.isDanger, baseColor)
        } else if (!closing && openProgress > 0.85f) {
            // Subtle hint pill above hub when nothing is hovered
            drawFloatingHintPill(g2, "Clique em uma opção", cx, (cy - HUB_RADIUS - 22).toFloat())
        }

        g2.dispose()
    }

    /**
     * Exact implementation of Lucas Bebber's SVG Gooey Filter:
     *1. Draws the central hub circle, satellite circles, and analytical Bézier liquid bridges
     *    onto `rawMaskImg`.
     * 2. Applies separable Gaussian Blur (`feGaussianBlur stdDeviation="10"`).
     * 3. Applies the alpha contrast matrix (`feColorMatrix values="1 0 0 0 0  0 1 0 0 0  0 0 1 0 0  0 0 0 18 -7"`)
     *    with subpixel anti-aliased edge smoothing and subtle bottom drop shadow (`#00000040`).
     */
    private fun renderGooeyMetaballField(
        offsetX: Int,
        offsetY: Int,
        baseColor: Color,
        dangerColor: Color,
    ) {
        val w = filterBufW
        val h = filterBufH

        // Clear raw canvas
        val mg = rawMaskImg.createGraphics()
        mg.background = Color(0, 0, 0, 0)
        mg.clearRect(0, 0, w, h)
        mg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val localHubX = anchorX - offsetX
        val localHubY = anchorY - offsetY
        val currentHubR = HUB_RADIUS * hubScale.value * (if (hubHovered) 1.07 else 1.0)

        val centersX = DoubleArray(items.size)
        val centersY = DoubleArray(items.size)
        val radii = DoubleArray(items.size)

        for (i in items.indices) {
            val (nx, ny) = nodeCenter(i)
            centersX[i] = nx - offsetX
            centersY[i] = ny - offsetY
            val p = itemSprings[i].value.coerceAtLeast(0.0)
            radii[i] = NODE_RADIUS * min(1.0, p * 1.15) * hoverScales[i].value
        }

        mg.color = baseColor

        // Draw analytical metaball bridges between hub and each satellite (for extra stretch during launch/retract)
        for (i in items.indices) {
            if (radii[i] > 4.0 && currentHubR > 4.0) {
                drawMetaballBridge(
                    g2 = mg,
                    x1 = localHubX,
                    y1 = localHubY,
                    r1 = currentHubR,
                    x2 = centersX[i],
                    y2 = centersY[i],
                    r2 = radii[i],
                    maxDist = currentHubR + radii[i] + 34.0,
                )
            }
        }

        // Draw analytical metaball bridges between adjacent satellites in the semicircle arc
        // (This produces the continuous U-shaped liquid chain seen in Frame 006 / 022 of the video!)
        for (i in 0 until items.size - 1) {
            if (radii[i] > 6.0 && radii[i + 1] > 6.0) {
                drawMetaballBridge(
                    g2 = mg,
                    x1 = centersX[i],
                    y1 = centersY[i],
                    r1 = radii[i],
                    x2 = centersX[i + 1],
                    y2 = centersY[i + 1],
                    r2 = radii[i + 1],
                    maxDist = radii[i] + radii[i + 1] + 28.0,
                )
            }
        }

        // Fill satellite circles
        for (i in items.indices) {
            val r = radii[i]
            if (r > 1.0) {
                mg.color = if (items[i].isDanger && hoveredIndex == i) dangerColor else baseColor
                mg.fill(Ellipse2D.Double(centersX[i] - r, centersY[i] - r, r * 2.0, r * 2.0))
            }
        }

        // Fill central hub circle
        if (currentHubR > 1.0) {
            mg.color = baseColor
            mg.fill(Ellipse2D.Double(localHubX - currentHubR, localHubY - currentHubR, currentHubR * 2.0, currentHubR * 2.0))
        }
        mg.dispose()

        // Extract ARGB pixels and run separable Gaussian blur + SVG feColorMatrix threshold
        rawMaskImg.getRGB(0, 0, w, h, rawPixels, 0, w)

        val rLen = blurRadius
        // Pass 1: Horizontal Gaussian Blur on Alpha & RGB
        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                var aSum = 0
                for (k in -rLen..rLen) {
                    val sx = (x + k).coerceIn(0, w - 1)
                    val px = rawPixels[rowOffset + sx]
                    val a = (px ushr 24) and 0xFF
                    if (a > 0) {
                        aSum += a * gaussianKernel[k + rLen]
                    }
                }
                tempPixels[rowOffset + x] = aSum ushr 16
            }
        }

        // Base color RGB components
        val baseR = baseColor.red
        val baseG = baseColor.green
        val baseB = baseColor.blue

        java.util.Arrays.fill(outPixels, 0)

        // Pass 2: Vertical Gaussian Blur + SVG Alpha Contrast Threshold (`19 * alpha - 7.5`) + Drop Shadow
        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                var aSum = 0
                for (k in -rLen..rLen) {
                    val sy = (y + k).coerceIn(0, h - 1)
                    val a = tempPixels[sy * w + x]
                    if (a > 0) {
                        aSum += a * gaussianKernel[k + rLen]
                    }
                }
                val blurredAlpha = aSum ushr 16 // 0..255

                // SVG feColorMatrix: alpha_out = 19 * (alpha_in / 255) - 7.2
                // Equivalent threshold around alpha ~ 96 with smooth anti-aliased ramp [88..108]
                if (blurredAlpha > 84) {
                    val sharpAlpha = ((blurredAlpha - 84) * 14).coerceIn(0, 255)

                    // Check if hovered danger node is near this pixel to tint red on hover
                    var rCol = baseR
                    var gCol = baseG
                    var bCol = baseB
                    if (hoveredIndex >= 0 && items[hoveredIndex].isDanger) {
                        val dx = x - centersX[hoveredIndex]
                        val dy = y - centersY[hoveredIndex]
                        val distSq = dx * dx + dy * dy
                        if (distSq < 2600.0) {
                            val t = (1.0 - sqrt(distSq) / 51.0).coerceIn(0.0, 1.0)
                            rCol = (baseR + (dangerColor.red - baseR) * t).toInt()
                            gCol = (baseG + (dangerColor.green - baseG) * t).toInt()
                            bCol = (baseB + (dangerColor.blue - baseB) * t).toInt()
                        }
                    }

                    // Draw subtle 3px vertical drop shadow first (just like the CSS shadow in the video)
                    val shadowY = y + 4
                    if (shadowY < h && sharpAlpha > 140) {
                        val sIdx = shadowY * w + x
                        if ((outPixels[sIdx] ushr 24) == 0) {
                            val sAlpha = (sharpAlpha * 85) / 255
                            outPixels[sIdx] = (sAlpha shl 24) or (12 shl 16) or (8 shl 8) or 24
                        }
                    }

                    outPixels[rowOffset + x] = (sharpAlpha shl 24) or (rCol shl 16) or (gCol shl 8) or bCol
                }
            }
        }

        gooeyOutImg.setRGB(0, 0, w, h, outPixels, 0, w)
    }

    /**
     * Paper.js / Hiroyuki Sato analytical Metaball tangent Bézier bridge between two circles.
     * Creates the concave liquid waist that stretches and snaps as circles move apart.
     */
    private fun drawMetaballBridge(
        g2: Graphics2D,
        x1: Double,
        y1: Double,
        r1: Double,
        x2: Double,
        y2: Double,
        r2: Double,
        maxDist: Double,
    ) {
        val d = hypot(x2 - x1, y2 - y1)
        if (d <= kotlin.math.abs(r1 - r2) || d > maxDist || r1 <= 1.0 || r2 <= 1.0) return

        val v = 0.52 // Waist curvature factor
        val handleLenRate = 2.2

        val u1: Double
        val u2: Double
        if (d < r1 + r2) {
            u1 = kotlin.math.acos(((r1 * r1 + d * d - r2 * r2) / (2.0 * r1 * d)).coerceIn(-1.0, 1.0))
            u2 = kotlin.math.acos(((r2 * r2 + d * d - r1 * r1) / (2.0 * r2 * d)).coerceIn(-1.0, 1.0))
        } else {
            u1 = 0.0
            u2 = 0.0
        }

        val angleBetween = kotlin.math.atan2(y2 - y1, x2 - x1)
        val maxSpread = kotlin.math.acos(((r1 - r2) / d).coerceIn(-1.0, 1.0))

        val angle1a = angleBetween + u1 + (maxSpread - u1) * v
        val angle1b = angleBetween - u1 - (maxSpread - u1) * v
        val angle2a = angleBetween + PI - u2 - (PI - u2 - maxSpread) * v
        val angle2b = angleBetween - PI + u2 + (PI - u2 - maxSpread) * v

        val p1aX = x1 + cos(angle1a) * r1
        val p1aY = y1 + sin(angle1a) * r1
        val p1bX = x1 + cos(angle1b) * r1
        val p1bY = y1 + sin(angle1b) * r1
        val p2aX = x2 + cos(angle2a) * r2
        val p2aY = y2 + sin(angle2a) * r2
        val p2bX = x2 + cos(angle2b) * r2
        val p2bY = y2 + sin(angle2b) * r2

        val totalRadius = r1 + r2
        var d2 = min(v * handleLenRate, hypot(p1aX - p2aX, p1aY - p2aY) / totalRadius)
        d2 *= min(1.0, (d * 2.0) / totalRadius)

        val h1 = r1 * d2
        val h2 = r2 * d2

        val path = Path2D.Double()
        path.moveTo(p1aX, p1aY)
        path.curveTo(
            p1aX + cos(angle1a - PI / 2.0) * h1,
            p1aY + sin(angle1a - PI / 2.0) * h1,
            p2aX + cos(angle2a + PI / 2.0) * h2,
            p2aY + sin(angle2a + PI / 2.0) * h2,
            p2aX,
            p2aY,
        )
        path.lineTo(p2bX, p2bY)
        path.curveTo(
            p2bX + cos(angle2b - PI / 2.0) * h2,
            p2bY + sin(angle2b - PI / 2.0) * h2,
            p1bX + cos(angle1b + PI / 2.0) * h1,
            p1bY + sin(angle1b + PI / 2.0) * h1,
            p1bX,
            p1bY,
        )
        path.closePath()
        g2.fill(path)
    }

    /**
     * Draws the central Hamburger (`≡`) morphing smoothly into a Close (`✕`) icon,
     * just like the central button in the Gooey Menu video.
     */
    private fun drawHubMorphIcon(g2: Graphics2D, cx: Float, cy: Float, scale: Float, morph: Float) {
        val g = g2.create() as Graphics2D
        g.translate(cx.toDouble(), cy.toDouble())
        g.scale(scale.toDouble(), scale.toDouble())
        g.color = Color.WHITE
        g.stroke = BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

        val halfW = 10f
        val offset = 6.5f * (1f - morph)
        val angle = (morph * (PI / 4.0)).toFloat()

        // Top line rotating to +45 deg
        val gTop = g.create() as Graphics2D
        gTop.translate(0.0, (-offset).toDouble())
        gTop.rotate(angle.toDouble())
        gTop.drawLine((-halfW).toInt(), 0, halfW.toInt(), 0)
        gTop.dispose()

        // Middle line shrinking out
        if (morph < 0.85f) {
            val midW = (halfW * (1f - morph / 0.85f)).toInt()
            if (midW > 0) {
                g.drawLine(-midW, 0, midW, 0)
            }
        }

        // Bottom line rotating to -45 deg
        val gBot = g.create() as Graphics2D
        gBot.translate(0.0, offset.toDouble())
        gBot.rotate((-angle).toDouble())
        gBot.drawLine((-halfW).toInt(), 0, halfW.toInt(), 0)
        gBot.dispose()

        g.dispose()
    }

    /**
     * Draws crisp white vector icons inside each satellite blob.
     */
    private fun drawSatelliteIcon(
        g2: Graphics2D,
        cx: Float,
        cy: Float,
        scale: Float,
        alpha: Float,
        type: IconType,
        hovered: Boolean,
    ) {
        val g = g2.create() as Graphics2D
        g.translate(cx.toDouble(), cy.toDouble())
        g.scale(scale.toDouble(), scale.toDouble())
        val aInt = (alpha * 255).toInt().coerceIn(0, 255)
        g.color = Color(255, 255, 255, aInt)
        g.stroke = BasicStroke(if (hovered) 2.4f else 2.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

        when (type) {
            IconType.OPEN_DETAILS -> {
                // Open book / details icon
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
                // Play / Reading triangle
                val path = Path2D.Float()
                path.moveTo(-5f, -8f)
                path.lineTo(8f, 0f)
                path.lineTo(-5f, 8f)
                path.closePath()
                g.fill(path)
            }
            IconType.COMPLETED -> {
                // Crisp checkmark
                val path = Path2D.Float()
                path.moveTo(-8f, 0f)
                path.lineTo(-2.5f, 6f)
                path.lineTo(8.5f, -6f)
                g.draw(path)
            }
            IconType.PLAN_TO_READ -> {
                // Bookmark ribbon
                val path = Path2D.Float()
                path.moveTo(-6f, -8.5f)
                path.lineTo(6f, -8.5f)
                path.lineTo(6f, 8.5f)
                path.lineTo(0f, 3.5f)
                path.lineTo(-6f, 8.5f)
                path.closePath()
                g.draw(path)
            }
            IconType.REMOVE -> {
                // Trash bin icon
                g.drawLine(-8, -5, 8, -5)
                g.drawLine(-3, -8, 3, -8)
                g.drawRoundRect(-6, -5, 12, 13, 3, 3)
                g.drawLine(-2, -1, -2, 4)
                g.drawLine(2, -1, 2, 4)
            }
        }
        g.dispose()
    }

    private fun drawFloatingLabelPill(
        g2: Graphics2D,
        text: String,
        nodeX: Float,
        nodeY: Float,
        isDanger: Boolean,
        accentColor: Color,
    ) {
        g2.font = Theme.FONT_UI.deriveFont(Font.BOLD, 12.5f)
        val fm = g2.fontMetrics
        val padH = 14
        val pillW = fm.stringWidth(text) + padH * 2
        val pillH = 28
        val px = (nodeX - pillW / 2f).coerceIn(12f, (width - pillW - 12).toFloat())
        val py = if (arcCenterAngle > 0) {
            nodeY + NODE_RADIUS.toFloat() + 14f
        } else {
            nodeY - NODE_RADIUS.toFloat() - pillH - 14f
        }

        // Soft shadow
        g2.color = Color(0, 0, 0, 110)
        g2.fill(RoundRectangle2D.Float(px, py + 3f, pillW.toFloat(), pillH.toFloat(), pillH.toFloat(), pillH.toFloat()))

        // Pill background
        g2.color = if (isDanger) Color(0xFF, 0x3B, 0x4E) else Color(0x18, 0x18, 0x20, 242)
        g2.fill(RoundRectangle2D.Float(px, py, pillW.toFloat(), pillH.toFloat(), pillH.toFloat(), pillH.toFloat()))

        // Crisp border
        g2.color = if (isDanger) Color(255, 255, 255, 140) else accentColor
        g2.stroke = BasicStroke(1.4f)
        g2.draw(RoundRectangle2D.Float(px, py, pillW.toFloat(), pillH.toFloat(), pillH.toFloat(), pillH.toFloat()))

        // Label text
        g2.color = Color.WHITE
        g2.drawString(text, px + padH, py + (pillH - fm.height) / 2f + fm.ascent)
    }

    private fun drawFloatingHintPill(g2: Graphics2D, text: String, cx: Float, py: Float) {
        g2.font = Theme.FONT_UI.deriveFont(Font.BOLD, 11.5f)
        val fm = g2.fontMetrics
        val padH = 12
        val pillW = fm.stringWidth(text) + padH * 2
        val pillH = 24
        val px = (cx - pillW / 2f).coerceIn(12f, (width - pillW - 12).toFloat())
        val safeY = py.coerceAtLeast(10f)

        g2.color = Color(0x14, 0x14, 0x1B, 215)
        g2.fill(RoundRectangle2D.Float(px, safeY, pillW.toFloat(), pillH.toFloat(), pillH.toFloat(), pillH.toFloat()))
        g2.color = Color(255, 255, 255, 215)
        g2.drawString(text, px + padH, safeY + (pillH - fm.height) / 2f + fm.ascent)
    }

    companion object {
        private const val HUB_RADIUS = 31.0
        private const val NODE_RADIUS = 27.0
    }
}
