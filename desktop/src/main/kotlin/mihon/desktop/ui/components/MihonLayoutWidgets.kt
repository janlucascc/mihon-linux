package mihon.desktop.ui.components

import mihon.desktop.Theme
import mihon.desktop.ui.animation.MotionCurves
import mihon.desktop.ui.animation.MotionEngine
import mihon.desktop.ui.animation.MotionPreferences
import mihon.desktop.ui.animation.SkeletonShimmerPainter
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer
import kotlin.math.max
import kotlin.math.min

/**
 * CJK-safe single or multi-line title/label component painted with [Theme.drawStringWithFallback].
 */
class CjkLabel(
    var labelText: String,
    fontStyle: Int = Font.BOLD,
    fontSize: Float = 22f,
    private val colorSupplier: () -> Color = { Theme.TEXT_PRIMARY },
    private val maxLines: Int = 2,
) : JComponent() {

    private var cachedW = -1
    private var lines: List<String> = emptyList()

    init {
        isOpaque = false
        font = Theme.FONT_DISPLAY.deriveFont(fontStyle, fontSize)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                cachedW = -1
                revalidate()
            }
        })
    }

    private fun computeLines(maxW: Int): List<String> {
        if (cachedW == maxW && lines.isNotEmpty()) return lines
        cachedW = maxW
        val fm = getFontMetrics(font)
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (word in labelText.trim().split(Regex("\\s+"))) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (fm.stringWidth(candidate) <= maxW || current.isEmpty()) {
                current = StringBuilder(candidate)
            } else {
                out.add(current.toString())
                if (out.size >= maxLines) break
                current = StringBuilder(word)
            }
        }
        if (out.size < maxLines && current.isNotEmpty()) {
            out.add(current.toString())
        }
        if (out.isEmpty()) out.add(labelText)
        lines = out
        return lines
    }

    override fun getPreferredSize(): Dimension {
        val w = (parent?.width?.takeIf { it > 160 } ?: 460)
        val computed = computeLines(w)
        val fm = getFontMetrics(font)
        return Dimension(w, computed.size * (fm.height + 2) + 4)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.font = font
        g2.color = colorSupplier()
        val fm = g2.fontMetrics
        val computed = computeLines(width.coerceAtLeast(140))
        val lineH = fm.height + 2
        computed.forEachIndexed { idx, line ->
            val clipped = if (idx == maxLines - 1 && computed.size == maxLines && fm.stringWidth(line) > width) {
                mihon.desktop.clipUiText(g2, line, width)
            } else {
                line
            }
            Theme.drawStringWithFallback(g2, clipped, 0, idx * lineH + fm.ascent)
        }
        g2.dispose()
    }
}

/**
 * CJK-safe expandable description accordion replacing Swing HTML JEditorPane in DetailsScreen.
 */
class ExpandableCjkText(
    private val fullText: String,
    private val collapsedMaxLines: Int = 3,
) : JPanel(BorderLayout()) {

    private var expanded = false
    private var expandProgress = 0f
    private var animHandle: MotionEngine.AnimationHandle? = null
    private var cachedWidth = -1
    private var wrappedLines: List<String> = emptyList()
    private val lineHeight = 20

    private val textCanvas = object : JComponent() {
        init {
            isOpaque = false
            font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12.5f)
        }

        override fun getPreferredSize(): Dimension {
            val w = (parent?.width ?: 460).coerceAtLeast(240)
            ensureWrapped(w)
            val collapsedLines = min(wrappedLines.size, collapsedMaxLines).coerceAtLeast(1)
            val fullLines = wrappedLines.size.coerceAtLeast(1)
            val currentLines = collapsedLines + (fullLines - collapsedLines) * expandProgress
            val h = (currentLines * lineHeight + 6).toInt()
            return Dimension(w, h)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
            g2.font = font
            ensureWrapped(width.coerceAtLeast(160))
            val fm = g2.fontMetrics
            val visibleMax = if (expanded || expandProgress > 0.05f) wrappedLines.size else min(wrappedLines.size, collapsedMaxLines)
            g2.color = Theme.TEXT_SECONDARY
            for (i in 0 until visibleMax) {
                var line = wrappedLines[i]
                if (!expanded && expandProgress < 0.05f && i == collapsedMaxLines - 1 && wrappedLines.size > collapsedMaxLines) {
                    line = line.trimEnd() + "…"
                }
                val y = i * lineHeight + fm.ascent + 2
                if (y - fm.ascent > height) break
                Theme.drawStringWithFallback(g2, line, 0, y)
            }
            g2.dispose()
        }
    }

    private val toggleBtn = object : JComponent() {
        private var hovered = false

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(110, 24)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
                override fun mousePressed(e: MouseEvent) { toggleExpanded() }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
            g2.font = Theme.FONT_UI.deriveFont(Font.BOLD, 12f)
            g2.color = if (hovered) Theme.PRIMARY_HOVER else (if (Theme.isDark) Theme.ACCENT else Theme.PRIMARY)
            val txt = if (expanded) "Ver menos" else "Ver mais"
            val fm = g2.fontMetrics
            g2.drawString(txt, 0, (height - fm.height) / 2 + fm.ascent)

            val cx = fm.stringWidth(txt) + 10.0
            val cy = height / 2.0
            g2.translate(cx, cy)
            g2.rotate(Math.PI * expandProgress)
            g2.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.drawLine(-4, -2, 0, 2)
            g2.drawLine(0, 2, 4, -2)
            g2.dispose()
        }
    }

    init {
        isOpaque = false
        add(textCanvas, BorderLayout.CENTER)
        add(toggleBtn, BorderLayout.SOUTH)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                cachedWidth = -1
                updateToggleVisibility()
            }
        })
        updateToggleVisibility()
    }

    private fun updateToggleVisibility() {
        ensureWrapped(width.takeIf { it > 160 } ?: 460)
        toggleBtn.isVisible = wrappedLines.size > collapsedMaxLines
    }

    private fun ensureWrapped(maxW: Int) {
        if (cachedWidth == maxW && wrappedLines.isNotEmpty()) return
        cachedWidth = maxW
        val fm = textCanvas.getFontMetrics(textCanvas.font)
        val out = mutableListOf<String>()
        for (para in fullText.trim().split('\n')) {
            val trimmed = para.trim()
            if (trimmed.isEmpty()) continue
            var line = StringBuilder()
            for (word in trimmed.split(Regex("\\s+"))) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (fm.stringWidth(candidate) <= maxW || line.isEmpty()) {
                    line = StringBuilder(candidate)
                } else {
                    out.add(line.toString())
                    line = StringBuilder(word)
                }
            }
            if (line.isNotEmpty()) out.add(line.toString())
        }
        wrappedLines = out
        toggleBtn.isVisible = wrappedLines.size > collapsedMaxLines
    }

    fun toggleExpanded() {
        expanded = !expanded
        animHandle?.cancel()
        val target = if (expanded) 1f else 0f
        animHandle = MotionEngine.animate(
            from = expandProgress,
            to = target,
            durationMs = MotionEngine.NORMAL_MS,
            easing = MotionCurves::easeInOutQuart,
            owner = this,
            onUpdate = { v ->
                expandProgress = v
                textCanvas.revalidate()
                revalidate()
                parent?.revalidate()
                repaint()
            },
        )
    }

    override fun removeNotify() {
        animHandle?.cancel()
        super.removeNotify()
    }
}

/**
 * Animated Skeleton Shimmer grid panel displayed while loading online catalogs.
 */
class SkeletonGridPanel(
    private val cardW: Int = 152,
    private val cardH: Int = 224,
    private val gap: Int = 16,
    private val count: Int = 12,
) : JPanel() {

    private var timer: Timer? = null

    init {
        isOpaque = true
        background = Theme.BG_APP
        border = BorderFactory.createEmptyBorder(16, Theme.PAGE_GUTTER, 16, Theme.PAGE_GUTTER)
    }

    fun startShimmer() {
        if (!MotionPreferences.isFull) return
        if (timer?.isRunning == true) return
        timer = Timer(24) {
            if (!isShowing) {
                stopShimmer()
                return@Timer
            }
            repaint()
        }.also { it.start() }
    }

    fun stopShimmer() {
        timer?.stop()
        timer = null
    }

    override fun addNotify() {
        super.addNotify()
        if (isVisible) startShimmer()
    }

    override fun setVisible(aFlag: Boolean) {
        super.setVisible(aFlag)
        if (aFlag) startShimmer() else stopShimmer()
    }

    override fun removeNotify() {
        stopShimmer()
        super.removeNotify()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        val pad = Theme.PAGE_GUTTER
        val usableW = (width - pad * 2).coerceAtLeast(cardW)
        val cols = max(1, (usableW + gap) / (cardW + gap))
        val cellH = cardH + 46
        val arc = (Theme.CARD_RADIUS * 2).toFloat()
        val hi = Color(Theme.TEXT_MUTED.red, Theme.TEXT_MUTED.green, Theme.TEXT_MUTED.blue, 36)

        for (i in 0 until count) {
            val col = i % cols
            val row = i / cols
            val x = (pad + col * (cardW + gap)).toFloat()
            val y = (pad + row * (cellH + 8)).toFloat()
            if (y > height) break
            SkeletonShimmerPainter.paintRect(g2, x, y, cardW.toFloat(), cardH.toFloat(), arc, Theme.BG_CARD, hi)
            SkeletonShimmerPainter.paintRect(g2, x, y + cardH + 10f, cardW * 0.82f, 12f, 8f, Theme.BG_CARD, hi)
            SkeletonShimmerPainter.paintRect(g2, x, y + cardH + 28f, cardW * 0.50f, 10f, 8f, Theme.BG_CARD, hi)
        }
        g2.dispose()
    }
}
