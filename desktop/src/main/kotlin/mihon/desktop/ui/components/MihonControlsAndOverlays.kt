package mihon.desktop.ui.components

import mihon.desktop.ModernButton
import mihon.desktop.ModernIcons
import mihon.desktop.Theme
import mihon.desktop.ui.animation.MotionCurves
import mihon.desktop.ui.animation.MotionPreferences
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ComboBoxModel
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.JSlider
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.EmptyBorder

/**
 * Custom vector-drawn animated checkbox replacing raw Swing [javax.swing.JCheckBox].
 */
class MihonCheckbox(
    text: String,
    initialSelected: Boolean = false,
    private val onToggle: ((Boolean) -> Unit)? = null,
) : JToggleButton(text, initialSelected) {

    private var checkProgress = if (initialSelected) 1f else 0f
    private var hoverProgress = 0f
    private var animTimer: Timer? = null

    init {
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = Theme.FONT_UI.deriveFont(Font.PLAIN, 13f)
        foreground = Theme.TEXT_PRIMARY
        horizontalAlignment = SwingConstants.LEFT
        iconTextGap = 10
        border = EmptyBorder(4, 2, 4, 4)

        addItemListener {
            ensureAnim()
            onToggle?.invoke(isSelected)
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = ensureAnim()
            override fun mouseExited(e: MouseEvent) = ensureAnim()
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent?) { repaint() }
            override fun focusLost(e: FocusEvent?) { repaint() }
        })
        icon = object : Icon {
            override fun getIconWidth(): Int = 18
            override fun getIconHeight(): Int = 18
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val cp = MotionCurves.easeOutCubic(checkProgress)
                val hp = MotionCurves.easeOutCubic(hoverProgress)
                val arc = 10f

                val fill = blendComponentColor(
                    blendComponentColor(Theme.BG_CARD, Theme.BG_CARD_HOVER, hp),
                    Theme.PRIMARY,
                    cp,
                )
                g2.color = fill
                g2.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), 18f, 18f, arc, arc))

                val borderCol = blendComponentColor(
                    if (hp > 0.1f) Theme.BORDER_STRONG else Theme.BORDER_SUBTLE,
                    Theme.PRIMARY,
                    cp,
                )
                g2.color = borderCol
                g2.stroke = BasicStroke(1.25f)
                g2.draw(RoundRectangle2D.Float(x + 0.5f, y + 0.5f, 17f, 17f, arc, arc))

                if (cp > 0.04f) {
                    g2.color = Color(255, 255, 255, (255 * cp).toInt().coerceIn(0, 255))
                    g2.stroke = BasicStroke(2.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    val p = Path2D.Float().apply {
                        moveTo(x + 4.5f, y + 9.2f)
                        lineTo(x + 7.6f, y + 12.4f)
                        lineTo(x + 13.6f, y + 5.8f)
                    }
                    g2.draw(p)
                }
                g2.dispose()
            }
        }
    }

    override fun removeNotify() {
        animTimer?.stop()
        animTimer = null
        super.removeNotify()
    }

    private fun ensureAnim() {
        val tc = if (isSelected) 1f else 0f
        val th = if (model.isRollover) 1f else 0f
        if (!MotionPreferences.isEnabled) {
            checkProgress = tc
            hoverProgress = th
            repaint()
            return
        }
        if (animTimer?.isRunning == true) return
        animTimer = Timer(12) { e ->
            if (!isDisplayable) {
                (e.source as? Timer)?.stop()
                return@Timer
            }
            val targetC = if (isSelected) 1f else 0f
            val targetH = if (model.isRollover) 1f else 0f
            checkProgress += (targetC - checkProgress) * 0.34f
            hoverProgress += (targetH - hoverProgress) * 0.28f
            if (kotlin.math.abs(checkProgress - targetC) < 0.02f) checkProgress = targetC
            if (kotlin.math.abs(hoverProgress - targetH) < 0.02f) hoverProgress = targetH
            repaint()
            if (checkProgress == targetC && hoverProgress == targetH) (e.source as? Timer)?.stop()
        }.also { it.start() }
    }

    override fun paintComponent(g: Graphics) {
        foreground = if (isEnabled) Theme.TEXT_PRIMARY else Theme.TEXT_MUTED
        if (isFocusOwner) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Theme.PRIMARY
            g2.stroke = BasicStroke(1.5f)
            g2.draw(RoundRectangle2D.Float(1f, 1f, width - 2f, height - 2f, 12f, 12f))
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/**
 * Standardized editorial [JComboBox] subclass preserving Swing & test API compatibility
 * while ensuring cohesive height, focus ring, dark/light popup list styling, and rounded geometry.
 */
open class MihonDropdown<E> : JComboBox<E> {
    private val compactHeight: Boolean

    constructor(items: Array<E>, compact: Boolean = false) : super(items) {
        compactHeight = compact
        configure()
    }

    constructor(model: ComboBoxModel<E>, compact: Boolean = false) : super(model) {
        compactHeight = compact
        configure()
    }

    constructor(compact: Boolean = false) : super(DefaultComboBoxModel()) {
        compactHeight = compact
        configure()
    }

    private fun configure() {
        val h = if (compactHeight) 32 else Theme.CONTROL_HEIGHT_COMPACT
        font = Theme.FONT_UI.deriveFont(if (compactHeight) 12.5f else 13f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(preferredSize.width.coerceAtLeast(110), h)
        putClientProperty("JComponent.roundRect", false)
        val base = DefaultListCellRenderer()
        setRenderer { list: JList<out E>?, value: E?, index: Int, isSelected: Boolean, cellHasFocus: Boolean ->
            val lbl = base.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
            lbl.border = EmptyBorder(6, 10, 6, 10)
            if (index >= 0) {
                lbl.background = if (isSelected) Theme.ACCENT_SOFT else Theme.BG_SURFACE
                lbl.foreground = Theme.TEXT_PRIMARY
            }
            lbl
        }
    }
}

/**
 * Unified factory for sleek [JPopupMenu] instances across all screens.
 */
object MihonPopupMenu {
    fun create(): JPopupMenu = JPopupMenu().apply {
        background = Theme.BG_SURFACE
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1),
            EmptyBorder(6, 6, 6, 6),
        )
    }

    fun header(title: String): JMenuItem = JMenuItem(title).apply {
        isEnabled = false
        font = Theme.FONT_UI.deriveFont(Font.BOLD, 10.5f)
        foreground = Theme.TEXT_MUTED
        border = EmptyBorder(6, 10, 4, 10)
    }

    fun item(
        text: String,
        icon: Icon? = null,
        danger: Boolean = false,
        active: Boolean = false,
        shortcutHint: String? = null,
        onClick: () -> Unit,
    ): JMenuItem {
        val labelText = if (shortcutHint != null) "$text    ($shortcutHint)" else text
        return JMenuItem(labelText, icon).apply {
            styleItem(this, active = active, danger = danger)
            addActionListener { onClick() }
        }
    }

    fun checkItem(
        text: String,
        selected: Boolean,
        onClick: (Boolean) -> Unit,
    ): JCheckBoxMenuItem = JCheckBoxMenuItem(text, selected).apply {
        styleItem(this, active = selected)
        addActionListener { onClick(isSelected) }
    }

    fun radioItem(
        text: String,
        selected: Boolean,
        onClick: () -> Unit,
    ): JRadioButtonMenuItem = JRadioButtonMenuItem(text, selected).apply {
        styleItem(this, active = selected)
        addActionListener { onClick() }
    }

    fun styleItem(item: JMenuItem, active: Boolean = false, danger: Boolean = false) {
        item.font = Theme.FONT_UI.deriveFont(if (active) Font.BOLD else Font.PLAIN, 12.5f)
        item.foreground = when {
            danger -> Theme.DANGER
            active -> if (Theme.isDark) Theme.ACCENT else Theme.PRIMARY
            else -> Theme.TEXT_PRIMARY
        }
        item.background = Theme.BG_SURFACE
        item.iconTextGap = 10
        item.border = EmptyBorder(7, 10, 7, 14)
    }
}

/**
 * Animated segmented pill selector shared between SearchScreen, SettingsScreen, and dialogs.
 */
class MihonSegmentedSelector<T>(
    private val options: List<Pair<T, String>>,
    initialValue: T,
    private val darkHudStyle: Boolean = false,
    private val onSelected: (T) -> Unit,
) : JComponent() {

    var selectedIndex: Int = options.indexOfFirst { it.first == initialValue }.coerceAtLeast(0)
        private set

    private var hoveredIndex: Int = -1
    private var animIndicatorX: Float = -1f
    private var animIndicatorW: Float = -1f
    private var animTimer: Timer? = null
    private var segmentBounds: Array<IntArray> = emptyArray()

    init {
        isOpaque = false
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12.5f)
        recalculatePreferredSize()

        addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                if (hoveredIndex != -1) {
                    hoveredIndex = -1
                    repaint()
                }
            }

            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                val clicked = segmentAt(e.x)
                if (clicked >= 0 && clicked != selectedIndex) {
                    selectIndex(clicked, animate = true, notify = true)
                }
            }
        })

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val idx = segmentAt(e.x)
                if (idx != hoveredIndex) {
                    hoveredIndex = idx
                    repaint()
                }
            }
        })

        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_LEFT -> {
                        val next = (selectedIndex - 1).coerceAtLeast(0)
                        if (next != selectedIndex) selectIndex(next, animate = true, notify = true)
                        e.consume()
                    }
                    KeyEvent.VK_RIGHT -> {
                        val next = (selectedIndex + 1).coerceAtMost(options.size - 1)
                        if (next != selectedIndex) selectIndex(next, animate = true, notify = true)
                        e.consume()
                    }
                }
            }
        })

        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent?) { repaint() }
            override fun focusLost(e: FocusEvent?) { repaint() }
        })
    }

    override fun removeNotify() {
        animTimer?.stop()
        animTimer = null
        super.removeNotify()
    }

    fun setSelectedValue(value: T, animate: Boolean = false) {
        val idx = options.indexOfFirst { it.first == value }
        if (idx >= 0 && idx != selectedIndex) {
            selectIndex(idx, animate = animate, notify = false)
        }
    }

    private fun selectIndex(newIndex: Int, animate: Boolean, notify: Boolean) {
        selectedIndex = newIndex
        val duration = MotionPreferences.effectiveDurationMs(180)
        if (segmentBounds.size == options.size && animate && duration > 0L && animIndicatorX >= 0f) {
            val targetX = segmentBounds[newIndex][0].toFloat()
            val targetW = segmentBounds[newIndex][1].toFloat()
            val startX = animIndicatorX
            val startW = animIndicatorW
            val startNanos = System.nanoTime()
            animTimer?.stop()
            animTimer = Timer(12) { ev ->
                if (!isDisplayable) {
                    (ev.source as? Timer)?.stop()
                    return@Timer
                }
                val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0
                val t = (elapsedMs / duration.toDouble()).toFloat().coerceIn(0f, 1f)
                val eased = MotionCurves.easeOutCubic(t)
                animIndicatorX = startX + (targetX - startX) * eased
                animIndicatorW = startW + (targetW - startW) * eased
                repaint()
                if (t >= 1f) {
                    (ev.source as? Timer)?.stop()
                    animIndicatorX = targetX
                    animIndicatorW = targetW
                    repaint()
                }
            }.also { it.start() }
        } else {
            animIndicatorX = -1f
            repaint()
        }
        if (notify) {
            onSelected(options[newIndex].first)
        }
    }

    private fun recalculatePreferredSize() {
        val fm = getFontMetrics(font.deriveFont(Font.BOLD, 12.5f))
        var totalW = 6
        for ((_, label) in options) {
            totalW += fm.stringWidth(label) + 22
        }
        val d = Dimension(totalW.coerceAtLeast(180), Theme.CONTROL_HEIGHT_COMPACT)
        preferredSize = d
        minimumSize = Dimension(140, Theme.CONTROL_HEIGHT_COMPACT)
        maximumSize = Dimension(maxOf(totalW + 80, 440), Theme.CONTROL_HEIGHT_COMPACT)
    }

    private fun computeLayout() {
        if (options.isEmpty()) {
            segmentBounds = emptyArray()
            return
        }
        val pad = 3
        val innerW = (width - pad * 2).coerceAtLeast(1)
        val segW = innerW.toFloat() / options.size.toFloat()
        segmentBounds = Array(options.size) { i ->
            val sx = (pad + i * segW).toInt()
            val ex = if (i == options.lastIndex) (width - pad) else (pad + (i + 1) * segW).toInt()
            intArrayOf(sx, (ex - sx).coerceAtLeast(24))
        }
        if (animIndicatorX < 0f && selectedIndex in segmentBounds.indices) {
            animIndicatorX = segmentBounds[selectedIndex][0].toFloat()
            animIndicatorW = segmentBounds[selectedIndex][1].toFloat()
        }
    }

    private fun segmentAt(x: Int): Int {
        computeLayout()
        for (i in segmentBounds.indices) {
            val sx = segmentBounds[i][0]
            val sw = segmentBounds[i][1]
            if (x in sx..(sx + sw)) return i
        }
        return -1
    }

    override fun paintComponent(g: Graphics) {
        computeLayout()
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        val w = width.toFloat()
        val h = height.toFloat()
        val outerArc = (Theme.CONTROL_RADIUS * 2).toFloat()

        g2.color = when {
            darkHudStyle -> Color(0x28, 0x29, 0x30)
            Theme.isDark -> Color(0x2A, 0x2A, 0x2F)
            else -> Theme.BG_SURFACE
        }
        g2.fill(RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, outerArc, outerArc))
        g2.color = if (isFocusOwner) Theme.PRIMARY else Theme.BORDER_SUBTLE
        g2.stroke = BasicStroke(if (isFocusOwner) 1.5f else 1f)
        g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, outerArc, outerArc))

        val pillArc = (outerArc - 4f).coerceAtLeast(10f)
        if (hoveredIndex >= 0 && hoveredIndex != selectedIndex && hoveredIndex in segmentBounds.indices) {
            val b = segmentBounds[hoveredIndex]
            g2.color = Theme.BG_CARD_HOVER
            g2.fill(RoundRectangle2D.Float(b[0].toFloat(), 3f, b[1].toFloat(), h - 6f, pillArc, pillArc))
        }

        val indX = if (animIndicatorX >= 0f) animIndicatorX else segmentBounds.getOrNull(selectedIndex)?.get(0)?.toFloat() ?: 3f
        val indW = if (animIndicatorW > 0f) animIndicatorW else segmentBounds.getOrNull(selectedIndex)?.get(1)?.toFloat() ?: 40f
        g2.color = when {
            darkHudStyle -> Color(0x52, 0x53, 0x5C)
            Theme.isDark -> Color(0x4A, 0x4B, 0x54)
            else -> Theme.PRIMARY
        }
        g2.fill(RoundRectangle2D.Float(indX, 3f, indW, h - 6f, pillArc, pillArc))

        val baseFont = font.deriveFont(Font.PLAIN, 12.5f)
        val boldFont = font.deriveFont(Font.BOLD, 12.5f)
        for (i in options.indices) {
            val b = segmentBounds[i]
            val active = i == selectedIndex
            g2.font = if (active) boldFont else baseFont
            g2.color = when {
                active -> Color.WHITE
                i == hoveredIndex -> Theme.TEXT_PRIMARY
                else -> Theme.TEXT_SECONDARY
            }
            val label = options[i].second
            val fm = g2.fontMetrics
            val tx = b[0] + (b[1] - fm.stringWidth(label)) / 2
            val ty = (h.toInt() - fm.height) / 2 + fm.ascent
            g2.drawString(label, tx, ty)
        }
        g2.dispose()
    }
}

/**
 * Cinema-styled page scrubber for [mihon.desktop.ReaderWindow] featuring bookmark tick indicators,
 * filled Cobalt track, hover thumb expansion, and live floating page tooltip.
 */
class MihonScrubberSlider(
    minPage: Int = 1,
    maxPage: Int = 1,
    initialValue: Int = 1,
) : JSlider(minPage, maxPage, initialValue) {

    var bookmarkPages: Set<Int> = emptySet()
        set(value) { field = value; repaint() }
    var notePages: Set<Int> = emptySet()
        set(value) { field = value; repaint() }
    var highlightPages: Map<Int, Color> = emptyMap()
        set(value) { field = value; repaint() }

    private var hovered = false
    private var hoverPagePreview: Int? = null
    private var hoverX: Int = 0
    private var thumbAnim = 0f
    private var animTimer: Timer? = null

    init {
        isOpaque = false
        isFocusable = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(240, 34)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hovered = true; ensureThumbAnim() }
            override fun mouseExited(e: MouseEvent) {
                hovered = false
                hoverPagePreview = null
                ensureThumbAnim()
            }

            override fun mousePressed(e: MouseEvent) {
                jumpToMouseX(e.x, adjusting = true)
            }

            override fun mouseReleased(e: MouseEvent) {
                jumpToMouseX(e.x, adjusting = false)
            }
        })

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                hoverX = e.x
                hoverPagePreview = pageForMouseX(e.x)
                repaint()
            }

            override fun mouseDragged(e: MouseEvent) {
                hoverX = e.x
                hoverPagePreview = pageForMouseX(e.x)
                jumpToMouseX(e.x, adjusting = true)
            }
        })
    }

    override fun removeNotify() {
        animTimer?.stop()
        animTimer = null
        super.removeNotify()
    }

    private fun trackBounds(): Rectangle {
        val padX = 12
        return Rectangle(padX, height / 2 - 3, (width - padX * 2).coerceAtLeast(1), 6)
    }

    private fun pageForMouseX(mx: Int): Int {
        val tb = trackBounds()
        val total = (maximum - minimum).coerceAtLeast(1)
        val frac = ((mx - tb.x).toFloat() / tb.width.toFloat()).coerceIn(0f, 1f)
        return (minimum + kotlin.math.round(frac * total).toInt()).coerceIn(minimum, maximum)
    }

    private fun jumpToMouseX(mx: Int, adjusting: Boolean) {
        val target = pageForMouseX(mx)
        valueIsAdjusting = adjusting
        value = target
        repaint()
    }

    private fun ensureThumbAnim() {
        val target = if (hovered) 1f else 0f
        if (!MotionPreferences.isEnabled || kotlin.math.abs(thumbAnim - target) < 0.02f) {
            thumbAnim = target
            repaint()
            return
        }
        if (animTimer?.isRunning == true) return
        animTimer = Timer(14) { e ->
            if (!isDisplayable) {
                (e.source as? Timer)?.stop()
                return@Timer
            }
            val tgt = if (hovered) 1f else 0f
            thumbAnim += (tgt - thumbAnim) * 0.3f
            if (kotlin.math.abs(thumbAnim - tgt) < 0.02f) thumbAnim = tgt
            repaint()
            if (thumbAnim == tgt) (e.source as? Timer)?.stop()
        }.also { it.start() }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        val tb = trackBounds()
        val span = (maximum - minimum).coerceAtLeast(1).toFloat()
        val valueFrac = ((value - minimum).toFloat() / span).coerceIn(0f, 1f)
        val activeW = (tb.width * valueFrac).toInt().coerceAtLeast(0)

        // Track background
        g2.color = Color(255, 255, 255, 42)
        g2.fill(RoundRectangle2D.Float(tb.x.toFloat(), tb.y.toFloat(), tb.width.toFloat(), tb.height.toFloat(), 6f, 6f))

        // Active Cobalt progress
        if (activeW > 0) {
            g2.color = Theme.PRIMARY
            g2.fill(RoundRectangle2D.Float(tb.x.toFloat(), tb.y.toFloat(), activeW.toFloat(), tb.height.toFloat(), 6f, 6f))
        }

        // Bookmark / Note / Highlight tick markers (0-based page indices mapped onto track)
        val allAnnotated = (bookmarkPages + notePages + highlightPages.keys).distinct()
        for (pageIdx in allAnnotated) {
            val oneBased = pageIdx + 1
            if (oneBased in minimum..maximum) {
                val frac = ((oneBased - minimum).toFloat() / span).coerceIn(0f, 1f)
                val mx = tb.x + (tb.width * frac).toInt()
                val markerColor = when {
                    pageIdx in highlightPages -> highlightPages[pageIdx]!!
                    pageIdx in notePages -> Theme.WARNING
                    else -> Theme.SIGNAL
                }
                g2.color = markerColor
                g2.fillOval(mx - 2, tb.y - 5, 5, 5)
            }
        }

        // Thumb
        val thumbSize = (13f + 4f * MotionCurves.easeOutCubic(thumbAnim)).toInt()
        val cx = tb.x + activeW
        val cy = tb.y + tb.height / 2
        g2.color = Color(0, 0, 0, 90)
        g2.fillOval(cx - thumbSize / 2, cy - thumbSize / 2 + 1, thumbSize, thumbSize)
        g2.color = Color.WHITE
        g2.fillOval(cx - thumbSize / 2, cy - thumbSize / 2, thumbSize, thumbSize)
        g2.color = Theme.PRIMARY
        g2.stroke = BasicStroke(1.8f)
        g2.drawOval(cx - thumbSize / 2, cy - thumbSize / 2, thumbSize, thumbSize)

        // Floating scrub preview pill
        val preview = hoverPagePreview
        if (hovered && preview != null && maximum > 1) {
            val label = "Pág. $preview"
            g2.font = Theme.FONT_MONO.deriveFont(Font.BOLD, 10.5f)
            val fm = g2.fontMetrics
            val pw = fm.stringWidth(label) + 12
            val ph = 18
            val px = (hoverX - pw / 2).coerceIn(2, (width - pw - 2).coerceAtLeast(2))
            val py = (tb.y - ph - 3).coerceAtLeast(0)
            g2.color = Color(0x16, 0x17, 0x1E, 235)
            g2.fill(RoundRectangle2D.Float(px.toFloat(), py.toFloat(), pw.toFloat(), ph.toFloat(), 10f, 10f))
            g2.color = Color(255, 255, 255, 45)
            g2.stroke = BasicStroke(1f)
            g2.draw(RoundRectangle2D.Float(px.toFloat(), py.toFloat(), pw.toFloat(), ph.toFloat(), 10f, 10f))
            g2.color = Color.WHITE
            g2.drawString(label, px + 6, py + ((ph - fm.height) / 2) + fm.ascent)
        }

        g2.dispose()
    }
}

/**
 * Semantic category for toast notifications replacing brittle string matching.
 */
enum class ToastType {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

/**
 * Unified animated floating toast stack mounted cleanly inside [JLayeredPane.POPUP_LAYER].
 */
class ToastHost(private val maxVisible: Int = 3) : JComponent() {

    private data class ToastItem(
        val id: Long,
        val message: String,
        val type: ToastType,
        val durationMs: Long,
        val createdAtMs: Long = System.currentTimeMillis(),
        var pausedAtMs: Long? = null,
        var totalPausedMs: Long = 0L,
        var progress: Float = 0f, // 0f..1f fade/slide-in
        var dismissing: Boolean = false,
        val closeBounds: Rectangle = Rectangle(),
        val pillBounds: Rectangle = Rectangle(),
    )

    private val items = mutableListOf<ToastItem>()
    private var nextId = 1L
    private var hoveredId: Long = -1L
    private var ticker: Timer? = null

    init {
        isOpaque = false
        isVisible = false

        addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                unhoverAll()
            }

            override fun mousePressed(e: MouseEvent) {
                val hit = items.firstOrNull { it.closeBounds.contains(e.point) }
                if (hit != null) {
                    hit.dismissing = true
                    ensureTicker()
                    e.consume()
                }
            }
        })

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val now = System.currentTimeMillis()
                val hit = items.lastOrNull { it.pillBounds.contains(e.point) }
                val newHoverId = hit?.id ?: -1L
                if (newHoverId != hoveredId) {
                    val prev = items.firstOrNull { it.id == hoveredId }
                    prev?.let {
                        val pAt = it.pausedAtMs
                        if (pAt != null) {
                            it.totalPausedMs += (now - pAt)
                            it.pausedAtMs = null
                        }
                    }
                    hit?.let {
                        if (it.pausedAtMs == null) it.pausedAtMs = now
                    }
                    hoveredId = newHoverId
                    cursor = if (hit?.closeBounds?.contains(e.point) == true) {
                        Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    } else {
                        Cursor.getDefaultCursor()
                    }
                    repaint()
                }
            }
        })
    }

    private fun unhoverAll() {
        val now = System.currentTimeMillis()
        items.forEach { item ->
            val pAt = item.pausedAtMs
            if (pAt != null) {
                item.totalPausedMs += (now - pAt)
                item.pausedAtMs = null
            }
        }
        hoveredId = -1L
        cursor = Cursor.getDefaultCursor()
    }

    fun show(message: String, type: ToastType = ToastType.INFO, durationMs: Long = 2800L) {
        val clean = message.trim()
        if (clean.isEmpty()) return
        // Coalesce identical active message
        items.removeAll { it.message == clean && !it.dismissing }
        while (items.size >= maxVisible) {
            items.removeAt(0)
        }
        val initialProg = if (MotionPreferences.isEnabled) 0.01f else 1f
        items.add(ToastItem(id = nextId++, message = clean, type = type, durationMs = durationMs, progress = initialProg))
        isVisible = true
        ensureTicker()
        repaint()
    }

    override fun removeNotify() {
        ticker?.stop()
        ticker = null
        super.removeNotify()
    }

    private fun ensureTicker() {
        if (ticker?.isRunning == true) return
        ticker = Timer(16) {
            if (!isDisplayable) {
                ticker?.stop()
                ticker = null
                return@Timer
            }
            val now = System.currentTimeMillis()
            val it = items.iterator()
            while (it.hasNext()) {
                val item = it.next()
                if (!item.dismissing) {
                    if (MotionPreferences.isEnabled) {
                        item.progress = (item.progress + 0.14f).coerceAtMost(1f)
                    } else {
                        item.progress = 1f
                    }
                    val activeElapsed = if (item.pausedAtMs != null) {
                        (item.pausedAtMs!! - item.createdAtMs) - item.totalPausedMs
                    } else {
                        (now - item.createdAtMs) - item.totalPausedMs
                    }
                    if (activeElapsed >= item.durationMs) {
                        item.dismissing = true
                    }
                } else {
                    item.progress = if (MotionPreferences.isEnabled) (item.progress - 0.11f).coerceAtLeast(0f) else 0f
                    if (item.progress <= 0f) {
                        it.remove()
                    }
                }
            }
            if (items.isEmpty()) {
                ticker?.stop()
                ticker = null
                isVisible = false
            }
            repaint()
        }.also { it.start() }
    }

    override fun contains(x: Int, y: Int): Boolean {
        return items.any { it.pillBounds.contains(x, y) }
    }

    override fun paintComponent(g: Graphics) {
        if (items.isEmpty()) return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        val now = System.currentTimeMillis()
        var currentBottomY = height - 22

        for (idx in items.indices.reversed()) {
            val item = items[idx]
            val eased = MotionCurves.easeOutCubic(item.progress)
            val slideOffset = ((1f - eased) * 16f).toInt()

            g2.font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 12.5f)
            val fm = g2.fontMetrics
            val textW = fm.stringWidth(item.message)
            val pillH = 40
            val pillW = (38 + textW + 40).coerceAtMost((width - 40).coerceAtLeast(180))
            val pillX = (width - pillW) / 2
            val pillY = (currentBottomY - pillH + slideOffset).coerceAtLeast(12)
            item.pillBounds.setBounds(pillX, pillY, pillW, pillH)
            val closeX = pillX + pillW - 28
            val closeY = pillY + (pillH - 20) / 2
            item.closeBounds.setBounds(closeX, closeY, 20, 20)

            val prevComp = g2.composite
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, eased.coerceIn(0f, 1f))

            // Shadow
            g2.color = Color(0, 0, 0, 95)
            g2.fill(RoundRectangle2D.Float(pillX.toFloat(), (pillY + 2).toFloat(), pillW.toFloat(), pillH.toFloat(), pillH.toFloat(), pillH.toFloat()))

            // Pill fill
            g2.color = if (Theme.isDark) Color(0x2E, 0x2F, 0x36, 245) else Color(0x22, 0x23, 0x2A, 245)
            g2.fill(RoundRectangle2D.Float(pillX.toFloat(), pillY.toFloat(), pillW.toFloat(), pillH.toFloat(), pillH.toFloat(), pillH.toFloat()))

            val accent = when (item.type) {
                ToastType.SUCCESS -> Theme.SUCCESS
                ToastType.WARNING -> Theme.WARNING
                ToastType.ERROR -> Theme.DANGER
                ToastType.INFO -> Theme.ACCENT
            }
            g2.color = Color(accent.red, accent.green, accent.blue, 145)
            g2.stroke = BasicStroke(1.2f)
            g2.draw(RoundRectangle2D.Float(pillX + 0.5f, pillY + 0.5f, pillW - 1f, pillH - 1f, pillH.toFloat(), pillH.toFloat()))

            val icon = when (item.type) {
                ToastType.SUCCESS -> ModernIcons.checkCircle(15) { accent }
                ToastType.WARNING -> ModernIcons.alertTriangle(15) { accent }
                ToastType.ERROR -> ModernIcons.close(15) { accent }
                ToastType.INFO -> ModernIcons.bolt(15) { accent }
            }
            icon.paintIcon(this, g2, pillX + 14, pillY + (pillH - 15) / 2)

            g2.color = Color(244, 246, 250)
            g2.drawString(
                mihon.desktop.clipUiText(g2, item.message, pillW - 78),
                pillX + 36,
                pillY + ((pillH - fm.height) / 2) + fm.ascent,
            )

            ModernIcons.close(12) { Color(200, 204, 214) }.paintIcon(this, g2, closeX + 4, closeY + 4)

            // Remaining duration thin progress bar
            if (!item.dismissing && item.durationMs > 0L) {
                val activeElapsed = if (item.pausedAtMs != null) {
                    (item.pausedAtMs!! - item.createdAtMs) - item.totalPausedMs
                } else {
                    (now - item.createdAtMs) - item.totalPausedMs
                }
                val remFrac = (1f - (activeElapsed.toFloat() / item.durationMs.toFloat())).coerceIn(0f, 1f)
                val barW = ((pillW - 36) * remFrac).toInt().coerceAtLeast(0)
                if (barW > 0) {
                    g2.color = Color(accent.red, accent.green, accent.blue, 180)
                    g2.fill(RoundRectangle2D.Float((pillX + 18).toFloat(), (pillY + pillH - 3).toFloat(), barW.toFloat(), 2f, 2f, 2f))
                }
            }

            g2.composite = prevComp
            currentBottomY -= (pillH + 8)
        }
        g2.dispose()
    }

    companion object {
        fun inferType(message: String): ToastType {
            val l = message.lowercase()
            return when {
                l.startsWith("erro") || l.startsWith("falha") || l.contains("corrompido") -> ToastType.ERROR
                l.contains("sucesso") || l.contains("salvo") || l.contains("concluíd") || l.contains("otimizado") || l.contains("criada") -> ToastType.SUCCESS
                l.contains("atenção") || l.contains("aviso") || l.contains("anônimo") -> ToastType.WARNING
                else -> ToastType.INFO
            }
        }
    }
}

/**
 * Custom editorial modal prompt dialog replacing unstyled Swing [javax.swing.JOptionPane.showInputDialog].
 */
class MihonPromptDialog(
    owner: Window?,
    dialogTitle: String,
    promptLabel: String,
    initialValue: String = "",
    placeholder: String = "",
    confirmText: String = "Confirmar",
    private val validator: (String) -> String? = { if (it.isBlank()) "Este campo não pode ficar em branco." else null },
    private val onConfirm: (String) -> Unit,
) : JDialog(owner, dialogTitle, Dialog.ModalityType.APPLICATION_MODAL) {

    private val inputField = MihonTextField(columns = 26, placeholder = placeholder, showClearButton = true).apply {
        text = initialValue
    }

    private val errorLabel = JLabel(" ").apply {
        font = Theme.FONT_UI.deriveFont(Font.BOLD, 11.5f)
        foreground = Theme.DANGER
        isVisible = false
        alignmentX = 0f
    }

    init {
        setSize(440, 215)
        isResizable = false
        setLocationRelativeTo(owner)

        val root = JPanel(BorderLayout(0, 12)).apply {
            background = Theme.BG_SURFACE
            border = EmptyBorder(20, 24, 18, 24)
        }

        val body = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel(dialogTitle).apply {
                font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 16f)
                foreground = Theme.TEXT_PRIMARY
                alignmentX = 0f
            })
            add(Box.createVerticalStrut(4))
            add(JLabel(promptLabel).apply {
                font = Theme.FONT_UI.deriveFont(12.5f)
                foreground = Theme.TEXT_SECONDARY
                alignmentX = 0f
            })
            add(Box.createVerticalStrut(12))
            inputField.alignmentX = 0f
            inputField.maximumSize = Dimension(Int.MAX_VALUE, Theme.CONTROL_HEIGHT_COMPACT)
            add(inputField)
            add(Box.createVerticalStrut(6))
            add(errorLabel)
        }
        root.add(body, BorderLayout.CENTER)

        fun trySubmit() {
            val value = inputField.text.trim()
            val err = validator(value)
            if (err != null) {
                inputField.validationError = err
                errorLabel.text = err
                errorLabel.isVisible = true
                return
            }
            onConfirm(value)
            dispose()
        }

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(ModernButton("Cancelar", variant = ModernButton.Variant.SURFACE).apply {
                addActionListener { dispose() }
            })
            add(ModernButton(confirmText, variant = ModernButton.Variant.PRIMARY).apply {
                addActionListener { trySubmit() }
            })
        }
        root.add(buttons, BorderLayout.SOUTH)
        add(root)

        inputField.onSubmit = { trySubmit() }
        rootPane.registerKeyboardAction(
            { dispose() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW,
        )

        SwingUtilities.invokeLater {
            inputField.requestFocusInWindow()
            inputField.selectAll()
        }
    }
}
