package mihon.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.ImageIcon
import javax.swing.JPanel

const val CARD_W = COVER_W + 14
const val CARD_H = COVER_H + 50

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
    var focusedState: Boolean = false
    var hoveredState: Boolean = false
    var hoverProgress: Float = 0f
    var coverFadeAlpha: Float = 1f
    var isSkeleton: Boolean = false

    init {
        isOpaque = false
        preferredSize = Dimension(CARD_W, CARD_H)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val rawHp = if (hoverProgress > 0f) hoverProgress.coerceIn(0f, 1f) else if (hoveredState) 1f else 0f
        val hp = easeOutCubic(rawHp)
        val lift = when {
            selectedState -> -3
            hoveredState || rawHp > 0f -> (-3f * hp).toInt()
            else -> 0
        }
        val coverX = (width - COVER_W) / 2
        val coverY = 6 + lift
        val arcInt = Theme.CARD_RADIUS * 2
        val arc = arcInt.toFloat()

        // Cover-only restrained shadow (Normal: alpha ~44, offset 2px; Hover: alpha ~88, offset 5px)
        val shadowAlphaOuter = (20 + 24 * hp).toInt().coerceIn(0, 56)
        val shadowAlphaInner = (44 + 44 * hp).toInt().coerceIn(0, 100)
        val shadowOffset = 2 + (3f * hp).toInt()
        g2.color = Color(0, 0, 0, shadowAlphaOuter)
        g2.fillRoundRect(coverX + 1, coverY + shadowOffset + 2, COVER_W - 2, COVER_H, arcInt + 2, arcInt + 2)
        g2.color = Color(0, 0, 0, shadowAlphaInner)
        g2.fillRoundRect(coverX + 1, coverY + shadowOffset, COVER_W - 2, COVER_H, arcInt, arcInt)

        // Cover clipped cleanly to 10px radius (arc diameter 20), or neutral placeholder if cover absent
        val coverShape = RoundRectangle2D.Float(
            coverX.toFloat(),
            coverY.toFloat(),
            COVER_W.toFloat(),
            COVER_H.toFloat(),
            arc,
            arc,
        )
        val img = coverIcon?.image
        if (isSkeleton) {
            mihon.desktop.ui.animation.SkeletonShimmerPainter.paintRect(
                g2 = g2,
                x = coverX.toFloat(),
                y = coverY.toFloat(),
                w = COVER_W.toFloat(),
                h = COVER_H.toFloat(),
                arc = arc,
                baseColor = Theme.BG_CARD,
                highlightColor = Color(Theme.TEXT_MUTED.red, Theme.TEXT_MUTED.green, Theme.TEXT_MUTED.blue, 34),
            )
        } else if (img != null) {
            val prevClip = g2.clip
            g2.clip = coverShape
            val fadeAlpha = coverFadeAlpha.coerceIn(0f, 1f)
            if (fadeAlpha < 0.99f) {
                g2.color = Theme.BG_CARD
                g2.fill(coverShape)
            }
            val prevComp = g2.composite
            if (fadeAlpha < 0.99f) {
                g2.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, fadeAlpha)
            }
            if (hp > 0.01f && mihon.desktop.ui.animation.MotionPreferences.isEnabled) {
                val zoomScale = 1f + 0.025f * hp
                val zw = (COVER_W * zoomScale).toInt()
                val zh = (COVER_H * zoomScale).toInt()
                val zx = coverX - (zw - COVER_W) / 2
                val zy = coverY - (zh - COVER_H) / 2
                g2.drawImage(img, zx, zy, zw, zh, null)
            } else {
                g2.drawImage(img, coverX, coverY, COVER_W, COVER_H, null)
            }
            g2.composite = prevComp
            g2.clip = prevClip
        } else {
            g2.color = Theme.BG_CARD
            g2.fill(coverShape)
            val bookIcon = ModernIcons.library(36) { Theme.TEXT_MUTED }
            bookIcon.paintIcon(this, g2, coverX + (COVER_W - 36) / 2, coverY + (COVER_H - 36) / 2)
        }

        // Priority badges: Unread (#F97316 Signal), Downloads (semantic Success), optional corner stamp
        var badgeCursorX = coverX + 6
        val badgeY = coverY + 6

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
                Theme.SUCCESS,
                badgeForegroundFor(Theme.SUCCESS),
            ) + 4
        }
        if (downloadsBadgeCount == 0 && unreadBadgeCount == 0) {
            topLeftBadge?.takeIf { it.isNotBlank() }?.let { badge ->
                drawStamp(g2, badge, badgeCursorX, badgeY, Color(0x17, 0x17, 0x1C, 220), Color.WHITE, alignRight = false)
            }
        }

        val rightLabel = if (downloadsBadgeCount > 0 || unreadBadgeCount > 0) topLeftBadge else topRightBadge
        val rightColor = if (downloadsBadgeCount > 0 || unreadBadgeCount > 0) Color(0x17, 0x17, 0x1C, 220) else topRightColor
        rightLabel?.takeIf { it.isNotBlank() }?.let { badge ->
            drawStamp(g2, badge, coverX + COVER_W - 6, badgeY, rightColor, Color.WHITE, alignRight = true)
        }

        // Selection, keyboard focus, hover, or neutral border around the cover
        when {
            selectedState -> {
                g2.color = Theme.PRIMARY
                g2.stroke = BasicStroke(2f)
                g2.drawRoundRect(coverX, coverY, COVER_W - 1, COVER_H - 1, arcInt, arcInt)
            }
            focusedState -> {
                g2.color = Theme.BORDER_SUBTLE
                g2.stroke = BasicStroke(1f)
                g2.drawRoundRect(coverX, coverY, COVER_W - 1, COVER_H - 1, arcInt, arcInt)
                g2.color = Theme.PRIMARY
                g2.stroke = BasicStroke(2f)
                g2.drawRoundRect(coverX - 2, coverY - 2, COVER_W + 3, COVER_H + 3, arcInt + 2, arcInt + 2)
            }
            hp > 0.02f -> {
                val baseCol = blendUiColor(
                    Theme.BORDER_SUBTLE,
                    Color(Theme.PRIMARY.red, Theme.PRIMARY.green, Theme.PRIMARY.blue, 175),
                    hp,
                )
                g2.color = baseCol
                g2.stroke = BasicStroke(1.25f)
                g2.drawRoundRect(coverX, coverY, COVER_W - 1, COVER_H - 1, arcInt, arcInt)
            }
            else -> {
                g2.color = Color(255, 255, 255, if (Theme.isDark) 24 else 36)
                g2.stroke = BasicStroke(1f)
                g2.drawRoundRect(coverX, coverY, COVER_W - 1, COVER_H - 1, arcInt, arcInt)
            }
        }

        val textX = coverX + 3
        val maxTextW = COVER_W - 6

        if (compactOverlayMode) {
            val gradH = 68
            val gradY = coverY + COVER_H - gradH
            val prevClip = g2.clip
            g2.clip = coverShape
            g2.paint = java.awt.GradientPaint(
                0f,
                gradY.toFloat(),
                Color(0x17, 0x17, 0x1C, 0),
                0f,
                (coverY + COVER_H).toFloat(),
                Color(0x17, 0x17, 0x1C, 235),
            )
            g2.fillRect(coverX, gradY, COVER_W, gradH)
            g2.clip = prevClip

            g2.font = Theme.FONT_UI.deriveFont(Font.BOLD, 12.5f)
            val lines = wrapTwoLines(g2, titleText, maxTextW - 10)
            var ty = coverY + COVER_H - (if (lines.size == 2) 24 else 10)
            lines.forEach { line ->
                g2.color = Color.WHITE
                Theme.drawStringWithFallback(g2, line, coverX + 8, ty)
                ty += 15
            }
        } else {
            var textY = coverY + COVER_H + 18
            g2.font = Theme.FONT_UI.deriveFont(Font.BOLD, 13f)
            g2.color = if (selectedState) Theme.ACCENT else Theme.TEXT_PRIMARY
            val lines = wrapTwoLines(g2, titleText, maxTextW)
            lines.forEach { line ->
                Theme.drawStringWithFallback(g2, line, textX, textY)
                textY += 15
            }

            if (subtitleText.isNotBlank()) {
                g2.font = Theme.FONT_UI.deriveFont(Font.PLAIN, 11f)
                g2.color = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
                val subY = if (lines.size == 1) coverY + COVER_H + 34 else coverY + COVER_H + 47
                Theme.drawStringWithFallback(g2, ellipsize(g2, subtitleText, maxTextW), textX, subY)
            }
        }

        g2.dispose()
    }

    /**
     * Signal Unread Badge (`#F97316` with crisp bold white tabular numbers and 7px radius).
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
        val w = (textW + 14).coerceAtLeast(h)
        val arc = Theme.BADGE_RADIUS * 2

        g2.color = Color(0, 0, 0, 95)
        g2.fillRoundRect(x, y + 1, w, h, arc, arc)

        g2.color = Theme.SIGNAL
        g2.fillRoundRect(x, y, w, h, arc, arc)

        g2.color = Color.WHITE
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
        g2.font = Theme.FONT_UI.deriveFont(Font.BOLD, 10.5f)
        val fm = g2.fontMetrics
        val w = fm.stringWidth(text) + 14
        val h = 20
        val arc = Theme.BADGE_RADIUS * 2
        val x = if (alignRight) anchorX - w else anchorX
        g2.color = bg
        g2.fillRoundRect(x, y, w, h, arc, arc)
        g2.color = Color(255, 255, 255, 36)
        g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc)
        g2.color = fg
        g2.drawString(text, x + 7, y + ((h - fm.height) / 2) + fm.ascent)
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

/** Picks crisp high-contrast foreground text/icon color for any semantic badge background. */
internal fun badgeForegroundFor(bg: Color): Color {
    val lum = (0.2126 * bg.red + 0.7152 * bg.green + 0.0722 * bg.blue) / 255.0
    return if (lum > 0.36) Color(0x12, 0x16, 0x14) else Color.WHITE
}

/** Draws a rounded download-count badge (7px radius) with a crisp vector downward arrow inside [g2]. */
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
    val arc = Theme.BADGE_RADIUS * 2
    val x = if (alignRight) anchorX - w else anchorX
    g2.color = bg
    g2.fillRoundRect(x, y, w, h, arc, arc)
    g2.color = Color(255, 255, 255, 36)
    g2.stroke = BasicStroke(1f)
    g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc)

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
        if (!mihon.desktop.ui.animation.MotionPreferences.isEnabled) {
            progressMap.clear()
            if (index >= 0) progressMap[index] = 1f
            if (prev >= 0) list.getCellBounds(prev, prev)?.let { list.repaint(it) }
            if (index >= 0) list.getCellBounds(index, index)?.let { list.repaint(it) }
            return
        }
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
