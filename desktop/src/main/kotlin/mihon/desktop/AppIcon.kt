package mihon.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.ImageIcon

/**
 * Procedural vector icon generator for Mihon Linux:
 * Features the signature geometric open-book & origami bookmark symbol inside a sleek rounded squircle.
 */
object AppIcon {

    fun createImage(size: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val s = size.toFloat()
        val corner = s * 0.24f

        // Squircle background with deep modern gradient (Indigo / Violet into Deep Obsidian)
        val bgGrad = GradientPaint(
            0f, 0f, Color(0x3B, 0x36, 0x4C),
            s, s, Color(0x18, 0x17, 0x22),
        )
        g2.paint = bgGrad
        g2.fill(RoundRectangle2D.Float(0f, 0f, s, s, corner, corner))

        // Subtle squircle border
        g2.color = Color(255, 255, 255, 45)
        g2.stroke = BasicStroke(s * 0.03f)
        g2.draw(RoundRectangle2D.Float(s * 0.015f, s * 0.015f, s * 0.97f, s * 0.97f, corner, corner))

        // Open book pages & bookmark symbol
        // Left Page
        val leftPage = Path2D.Float().apply {
            moveTo(s * 0.50f, s * 0.72f)
            quadTo(s * 0.35f, s * 0.67f, s * 0.20f, s * 0.73f)
            lineTo(s * 0.20f, s * 0.33f)
            quadTo(s * 0.35f, s * 0.27f, s * 0.50f, s * 0.33f)
            closePath()
        }
        val pageGradLeft = GradientPaint(
            s * 0.20f, s * 0.30f, Color(0xFA, 0xFA, 0xFA),
            s * 0.50f, s * 0.70f, Color(0xDE, 0xE2, 0xE6),
        )
        g2.paint = pageGradLeft
        g2.fill(leftPage)

        // Right Page
        val rightPage = Path2D.Float().apply {
            moveTo(s * 0.50f, s * 0.72f)
            quadTo(s * 0.65f, s * 0.67f, s * 0.80f, s * 0.73f)
            lineTo(s * 0.80f, s * 0.33f)
            quadTo(s * 0.65f, s * 0.27f, s * 0.50f, s * 0.33f)
            closePath()
        }
        val pageGradRight = GradientPaint(
            s * 0.50f, s * 0.30f, Color(0xFA, 0xFA, 0xFA),
            s * 0.80f, s * 0.70f, Color(0xDE, 0xE2, 0xE6),
        )
        g2.paint = pageGradRight
        g2.fill(rightPage)

        // Book spine line
        g2.color = Color(0x8A, 0x8D, 0x98)
        g2.stroke = BasicStroke(s * 0.035f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.drawLine((s * 0.50f).toInt(), (s * 0.31f).toInt(), (s * 0.50f).toInt(), (s * 0.73f).toInt())

        // Vibrant Red / Scarlet Ribbon Bookmark falling from top
        val ribbon = Path2D.Float().apply {
            moveTo(s * 0.44f, s * 0.25f)
            lineTo(s * 0.56f, s * 0.25f)
            lineTo(s * 0.56f, s * 0.55f)
            lineTo(s * 0.50f, s * 0.48f)
            lineTo(s * 0.44f, s * 0.55f)
            closePath()
        }
        g2.color = Color(0xEF, 0x44, 0x44)
        g2.fill(ribbon)

        g2.dispose()
        return img
    }

    fun getIcon(size: Int): Icon = ImageIcon(createImage(size))
}
