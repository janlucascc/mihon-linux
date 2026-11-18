package mihon.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.ImageIcon
import javax.swing.JPanel

const val CARD_W = COVER_W + 24
const val CARD_H = COVER_H + 74

/**
 * Editorial Tankobon Volume Poster (grounded in frontend-design principles):
 * - No generic SaaS rounded card box around the cover; the book cover itself is the physical object.
 * - Hanko-seal inspired stamp badges in the top-left (`DownloadsBadge` + `UnreadBadge`).
 * - Crisp left-aligned typography directly beneath the volume cover.
 */
class MangaPosterCard : JPanel() {
    var coverIcon: ImageIcon? = null
    var titleText: String = ""
    var subtitleText: String = ""
    var topRightBadge: String? = null
    var topRightColor: Color = Theme.ACCENT
    var topLeftBadge: String? = null
    var downloadsBadgeCount: Int = 0
    var unreadBadgeCount: Int = 0
    var compactOverlayMode: Boolean = false
    var selectedState: Boolean = false
    var hoveredState: Boolean = false
    var hoverProgress: Float = 0f

    init {
        isOpaque = false
        preferredSize = Dimension(CARD_W, CARD_H)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val hp = if (hoverProgress > 0f) hoverProgress.coerceIn(0f, 1f) else if (hoveredState) 1f else 0f
        val lift = -kotlin.math.round((if (selectedState) 3.5f else 0f).coerceAtLeast(4.5f * hp)).toInt()
        val coverX = (width - COVER_W) / 2
        val coverY = 8 + lift

        // Layered physical book elevation shadow directly beneath the cover
        val shadowAlphaOuter = (36 + 52 * hp).toInt().coerceIn(0, 140)
        val shadowAlphaInner = (68 + 65 * hp).toInt().coerceIn(0, 175)
        g2.color = Color(0, 0, 0, shadowAlphaOuter)
        g2.fillRoundRect(coverX + 1, coverY + 7 + (2f * hp).toInt(), COVER_W - 2, COVER_H, COVER_ARC + 2, COVER_ARC + 2)
        g2.color = Color(0, 0, 0, shadowAlphaInner)
        g2.fillRoundRect(coverX + 2, coverY + 4 + (1f * hp).toInt(), COVER_W, COVER_H, COVER_ARC, COVER_ARC)

        // Cover image
        coverIcon?.image?.let { img ->
            g2.drawImage(img, coverX, coverY, COVER_W, COVER_H, null)
        }

        // Top-left: Material 3 Unread Badge + crisp vector Downloads Badge
        var badgeCursorX = coverX + 8
        val badgeY = coverY + 8

        if (unreadBadgeCount > 0) {
            badgeCursorX += drawM3UnreadPill(
                g2,
                "$unreadBadgeCount",
                badgeCursorX,
                badgeY,
            ) + 4
        }
        if (downloadsBadgeCount > 0) {
            badgeCursorX += drawVectorDownloadPill(
                g2,
                downloadsBadgeCount,
                badgeCursorX,
                badgeY,
                Theme.TERTIARY,
                Color.WHITE,
            ) + 4
        }
        if (downloadsBadgeCount == 0 && unreadBadgeCount == 0) {
            topLeftBadge?.takeIf { it.isNotBlank() }?.let { badge ->
                drawStamp(g2, badge, badgeCursorX, badgeY, Color(13, 13, 15, 225), Theme.TEXT_PRIMARY, alignRight = false)
            }
        }

        // Top-right badge (Language / Source or chapter tag)
        val rightLabel = if (downloadsBadgeCount > 0 || unreadBadgeCount > 0) topLeftBadge else topRightBadge
        val rightColor = if (downloadsBadgeCount > 0 || unreadBadgeCount > 0) Color(13, 13, 15, 225) else topRightColor
        rightLabel?.takeIf { it.isNotBlank() }?.let { badge ->
            drawStamp(g2, badge, coverX + COVER_W - 8, badgeY, rightColor, Color.WHITE, alignRight = true)
        }

        // Crisp focus/hover frame & subtle edge border around the book cover
        if (selectedState) {
            g2.color = Theme.ACCENT
            g2.stroke = BasicStroke(2f)
            g2.drawRoundRect(coverX, coverY, COVER_W - 1, COVER_H - 1, COVER_ARC, COVER_ARC)
        } else if (hp > 0.02f) {
            val frameAlpha = (180 * hp).toInt().coerceIn(0, 220)
            val baseCol = Theme.TEXT_PRIMARY
            g2.color = Color(baseCol.red, baseCol.green, baseCol.blue, frameAlpha)
            g2.stroke = BasicStroke(1.3f)
            g2.drawRoundRect(coverX, coverY, COVER_W - 1, COVER_H - 1, COVER_ARC, COVER_ARC)
        } else if (Theme.isDark) {
            g2.color = Color(255, 255, 255, 22)
            g2.stroke = BasicStroke(1f)
            g2.drawRoundRect(coverX, coverY, COVER_W - 1, COVER_H - 1, COVER_ARC, COVER_ARC)
        }

        val textX = coverX + 2
        val maxTextW = COVER_W - 4

        if (compactOverlayMode) {
            val gradH = 74
            val gradY = coverY + COVER_H - gradH
            g2.paint = java.awt.GradientPaint(
                0f,
                gradY.toFloat(),
                Color(0, 0, 0, 0),
                0f,
                (coverY + COVER_H).toFloat(),
                Color(13, 13, 15, 235),
            )
            g2.fillRoundRect(coverX, gradY, COVER_W, gradH, COVER_ARC, COVER_ARC)

            g2.font = font.deriveFont(Font.BOLD, 12.5f)
            val lines = wrapTwoLines(g2, titleText, maxTextW - 6)
            var ty = coverY + COVER_H - (if (lines.size == 2) 24 else 10)
            lines.forEach { line ->
                g2.color = Color(0, 0, 0, 200)
                g2.drawString(line, textX + 5, ty + 1)
                g2.color = Theme.TEXT_PRIMARY
                g2.drawString(line, textX + 4, ty)
                ty += 15
            }
        } else {
            var textY = coverY + COVER_H + 18
            g2.font = font.deriveFont(Font.BOLD, 13f)
            g2.color = if (hoveredState) (if (Theme.isDark) Color.WHITE else Color(0x00, 0x55, 0xAA)) else Theme.TEXT_PRIMARY
            val lines = wrapTwoLines(g2, titleText, maxTextW)
            lines.forEach { line ->
                g2.drawString(line, textX, textY)
                textY += 15
            }

            if (subtitleText.isNotBlank()) {
                g2.font = font.deriveFont(Font.PLAIN, 11f)
                g2.color = Theme.TEXT_MUTED
                g2.drawString(ellipsize(g2, subtitleText, maxTextW), textX, coverY + COVER_H + 52)
            }
        }

        g2.dispose()
    }

    /**
     * Material 3 Unread Pill Badge (Solid white rounded container with bold black tabular numbers,
     * matching the official Mihon Android UI seen on the screenshots).
     */
    private fun drawM3UnreadPill(
        g2: Graphics2D,
        text: String,
        x: Int,
        y: Int,
    ): Int {
        g2.font = Theme.FONT_MONO.deriveFont(Font.BOLD, 11f)
        val fm = g2.fontMetrics
        val textW = fm.stringWidth(text)
        val h = 20
        val w = (textW + 10).coerceAtLeast(h)
        val arc = 10

        // Subtle drop shadow
        g2.color = Color(0, 0, 0, 95)
        g2.fillRoundRect(x, y + 1, w, h, arc, arc)

        // White solid pill container
        g2.color = Color.WHITE
        g2.fillRoundRect(x, y, w, h, arc, arc)

        // High-contrast black digits
        g2.color = Color.BLACK
        val textX = x + (w - textW) / 2
        val textY = y + ((h - fm.height) / 2) + fm.ascent
        g2.drawString(text, textX, textY)
        return w
    }

    private fun drawStamp(
        g2: Graphics2D,
        text: String,
        anchorX: Int,
        y: Int,
        bg: Color,
        fg: Color,
        alignRight: Boolean,
    ): Int {
        g2.font = Theme.FONT_MONO.deriveFont(Font.BOLD, 10.5f)
        val fm = g2.fontMetrics
        val w = fm.stringWidth(text) + 12
        val h = 20
        val x = if (alignRight) anchorX - w else anchorX
        g2.color = bg
        g2.fillRoundRect(x, y, w, h, 5, 5)
        g2.color = Color(255, 255, 255, 42)
        g2.drawRoundRect(x, y, w - 1, h - 1, 5, 5)
        g2.color = fg
        g2.drawString(text, x + 6, y + ((h - fm.height) / 2) + fm.ascent)
        return w
    }

    private var cachedWrapKey: String? = null
    private var cachedWrapLines: List<String> = emptyList()
    private var cachedSubtitleKey: String? = null
    private var cachedSubtitleStr: String = ""

    private fun wrapTwoLines(g2: Graphics2D, text: String, maxW: Int): List<String> {
        val key = "$text@$maxW"
        if (key == cachedWrapKey) return cachedWrapLines
        val fm = g2.fontMetrics
        if (fm.stringWidth(text) <= maxW) {
            val res = listOf(text)
            cachedWrapKey = key
            cachedWrapLines = res
            return res
        }
        val words = text.split(Regex("\\s+"))
        val line1 = StringBuilder()
        var idx = 0
        while (idx < words.size) {
            val candidate = if (line1.isEmpty()) words[idx] else "$line1 ${words[idx]}"
            if (fm.stringWidth(candidate) <= maxW) {
                line1.clear().append(candidate)
                idx++
            } else {
                break
            }
        }
        val res = if (line1.isEmpty()) {
            listOf(ellipsize(g2, text, maxW))
        } else {
            val rest = words.drop(idx).joinToString(" ")
            if (rest.isEmpty()) listOf(line1.toString()) else listOf(line1.toString(), ellipsize(g2, rest, maxW))
        }
        cachedWrapKey = key
        cachedWrapLines = res
        return res
    }

    private fun ellipsize(g2: Graphics2D, text: String, maxW: Int): String {
        val key = "$text@$maxW"
        if (key == cachedSubtitleKey) return cachedSubtitleStr
        val fm = g2.fontMetrics
        if (fm.stringWidth(text) <= maxW) {
            cachedSubtitleKey = key
            cachedSubtitleStr = text
            return text
        }
        var s = text
        while (s.length > 1 && fm.stringWidth("$s…") > maxW) {
            s = s.dropLast(1)
        }
        val res = "$s…"
        cachedSubtitleKey = key
        cachedSubtitleStr = res
        return res
    }
}

/** Draws a rounded download-count pill with a crisp vector downward arrow inside [g2]. */
internal fun drawVectorDownloadPill(
    g2: Graphics2D,
    count: Int,
    anchorX: Int,
    y: Int,
    bg: Color,
    fg: Color,
    alignRight: Boolean = false,
): Int {
    val label = "$count"
    g2.font = Theme.FONT_MONO.deriveFont(Font.BOLD, 10.5f)
    val fm = g2.fontMetrics
    val iconSpace = 12
    val w = iconSpace + fm.stringWidth(label) + 11
    val h = 20
    val x = if (alignRight) anchorX - w else anchorX
    g2.color = bg
    g2.fillRoundRect(x, y, w, h, 6, 6)
    g2.color = Color(255, 255, 255, 42)
    g2.stroke = BasicStroke(1f)
    g2.drawRoundRect(x, y, w - 1, h - 1, 6, 6)

    val ix = x + 6
    val iy = y + 5
    g2.color = fg
    g2.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    g2.drawLine(ix + 4, iy, ix + 4, iy + 6)
    g2.drawLine(ix + 1, iy + 4, ix + 4, iy + 7)
    g2.drawLine(ix + 7, iy + 4, ix + 4, iy + 7)
    g2.drawLine(ix + 1, iy + 9, ix + 7, iy + 9)

    g2.drawString(label, ix + iconSpace, y + ((h - fm.height) / 2) + fm.ascent)
    return w
}

/** Smooth 60fps per-cell hover animator for JList grids and lists. */
internal class GridHoverAnimator(
    private val list: javax.swing.JList<*>,
) {
    private val progressMap = HashMap<Int, Float>()
    private var targetIndex: Int = -1
    private var timer: javax.swing.Timer? = null

    fun progressFor(index: Int): Float = progressMap[index] ?: if (index == targetIndex) 1f else 0f

    fun setHovered(index: Int) {
        if (index == targetIndex) return
        val prev = targetIndex
        targetIndex = index
        if (prev >= 0 && prev !in progressMap) progressMap[prev] = 1f
        if (index >= 0 && index !in progressMap) progressMap[index] = 0f
        if (timer?.isRunning != true) {
            timer = javax.swing.Timer(14) { step() }.also { it.start() }
        }
    }

    fun stop() {
        timer?.stop()
        timer = null
        progressMap.clear()
        targetIndex = -1
    }

    private fun step() {
        if (!list.isDisplayable) {
            stop()
            return
        }
        val it = progressMap.entries.iterator()
        var anyActive = false
        while (it.hasNext()) {
            val entry = it.next()
            val idx = entry.key
            val target = if (idx == targetIndex) 1f else 0f
            val next = entry.value + (target - entry.value) * 0.28f
            val settled = kotlin.math.abs(next - target) < 0.025f
            if (settled) {
                if (target == 0f) it.remove() else entry.setValue(1f)
            } else {
                entry.setValue(next)
                anyActive = true
            }
            list.getCellBounds(idx, idx)?.let { list.repaint(it) }
        }
        if (!anyActive) {
            timer?.stop()
            timer = null
        }
    }
}

/** Ellipsizes [text] to fit within [maxW] pixels using [g2]'s current font metrics. */
internal fun clipUiText(g2: Graphics2D, text: String, maxW: Int): String {
    if (maxW <= 12) return "…"
    val fm = g2.fontMetrics
    if (fm.stringWidth(text) <= maxW) return text
    var s = text
    while (s.length > 1 && fm.stringWidth("$s…") > maxW) s = s.dropLast(1)
    return "$s…"
}
