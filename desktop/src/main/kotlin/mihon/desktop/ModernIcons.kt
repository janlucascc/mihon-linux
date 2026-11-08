package mihon.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Modern, crisp vector icons rendered via Graphics2D in Material 3 / Lucide style.
 * Replaces low-resolution / inconsistent Unicode emojis across the desktop application.
 */
object ModernIcons {

    abstract class BaseVectorIcon(
        val size: Int = 22,
        val colorSupplier: () -> Color = { Theme.TEXT_PRIMARY },
    ) : Icon {
        override fun getIconWidth(): Int = size
        override fun getIconHeight(): Int = size

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.translate(x, y)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            val scale = size.toDouble() / 24.0
            g2.scale(scale, scale)

            val color = colorSupplier()
            g2.color = color

            paintVector(g2, color)
            g2.dispose()
        }

        abstract fun paintVector(g2: Graphics2D, color: Color)
    }

    /**
     * Offline icon: cloud with an intentional diagonal strike-through.
     */
    fun offline(size: Int = 22, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

                // Cloud body
                val cloud = Path2D.Float().apply {
                    moveTo(4.5f, 16.5f)
                    quadTo(2.5f, 16.5f, 2.5f, 14.5f)
                    quadTo(2.5f, 12.5f, 4.5f, 12.0f)
                    quadTo(4.5f, 8.5f, 8.5f, 8.0f)
                    quadTo(10.5f, 5.0f, 15.0f, 6.5f)
                    quadTo(18.5f, 6.5f, 19.5f, 10.0f)
                    quadTo(21.5f, 10.5f, 21.5f, 13.5f)
                    quadTo(21.5f, 16.5f, 19.0f, 16.5f)
                    lineTo(4.5f, 16.5f)
                }
                g2.draw(cloud)

                // Diagonal slash
                g2.stroke = BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(3, 4, 21, 20)
            }
        }

    /**
     * Incognito / Anonymous mode icon: detective fedora hat and spectacles.
     */
    fun incognito(size: Int = 22, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

                // Fedora brim
                val brim = Path2D.Float().apply {
                    moveTo(3.0f, 10.5f)
                    quadTo(12.0f, 12.0f, 21.0f, 10.5f)
                }
                g2.draw(brim)

                // Fedora crown
                val crown = Path2D.Float().apply {
                    moveTo(6.5f, 10.5f)
                    lineTo(8.5f, 4.5f)
                    quadTo(12.0f, 6.0f, 15.5f, 4.5f)
                    lineTo(17.5f, 10.5f)
                }
                g2.draw(crown)

                // Glasses
                g2.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.draw(Ellipse2D.Float(5.0f, 13.5f, 5.5f, 5.5f))
                g2.draw(Ellipse2D.Float(13.5f, 13.5f, 5.5f, 5.5f))
                // Bridge between lenses
                g2.drawLine(10, 16, 14, 16)
            }
        }

    /**
     * Downloads Queue icon: inbox tray with a clean downward arrow.
     */
    fun downloads(size: Int = 22, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

                // Tray base
                val tray = Path2D.Float().apply {
                    moveTo(3.5f, 13.5f)
                    lineTo(3.5f, 19.0f)
                    quadTo(3.5f, 20.5f, 5.0f, 20.5f)
                    lineTo(19.0f, 20.5f)
                    quadTo(20.5f, 20.5f, 20.5f, 19.0f)
                    lineTo(20.5f, 13.5f)
                }
                g2.draw(tray)

                // Tray cutout lip
                val lip = Path2D.Float().apply {
                    moveTo(3.5f, 14.5f)
                    lineTo(8.5f, 14.5f)
                    lineTo(10.0f, 17.0f)
                    lineTo(14.0f, 17.0f)
                    lineTo(15.5f, 14.5f)
                    lineTo(20.5f, 14.5f)
                }
                g2.draw(lip)

                // Downward arrow
                g2.drawLine(12, 3, 12, 12)
                val arrowHead = Path2D.Float().apply {
                    moveTo(8.5f, 8.5f)
                    lineTo(12.0f, 12.0f)
                    lineTo(15.5f, 8.5f)
                }
                g2.draw(arrowHead)
            }
        }

    /**
     * Categories icon: modern folder outline.
     */
    fun categories(size: Int = 22, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val folder = Path2D.Float().apply {
                    moveTo(3.5f, 6.5f)
                    lineTo(9.0f, 6.5f)
                    lineTo(11.0f, 9.0f)
                    lineTo(20.5f, 9.0f)
                    lineTo(20.5f, 18.5f)
                    lineTo(3.5f, 18.5f)
                    closePath()
                }
                g2.draw(folder)
            }
        }

    /**
     * Statistics icon: modern trend chart with rising columns and an indicator line.
     */
    fun statistics(size: Int = 22, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

                // Horizontal ground base
                g2.drawLine(3, 20, 21, 20)

                // 3 Rising bars
                g2.stroke = BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(6, 15, 6, 18)
                g2.drawLine(12, 10, 12, 18)
                g2.drawLine(18, 5, 18, 18)

                // Trend line connecting tops with arrow
                g2.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val trend = Path2D.Float().apply {
                    moveTo(4.0f, 14.0f)
                    lineTo(9.0f, 12.0f)
                    lineTo(13.0f, 8.0f)
                    lineTo(19.0f, 4.0f)
                }
                g2.draw(trend)
                // Small arrow head
                g2.drawLine(16, 4, 19, 4)
                g2.drawLine(19, 4, 19, 7)
            }
        }

    /**
     * Data and storage icon: database stack / storage drives.
     */
    fun storage(size: Int = 22, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

                // Top disc
                g2.draw(Ellipse2D.Float(4.0f, 4.0f, 16.0f, 5.5f))

                // Mid segment
                val mid = Path2D.Float().apply {
                    moveTo(4.0f, 6.75f)
                    lineTo(4.0f, 12.0f)
                    quadTo(12.0f, 15.0f, 20.0f, 12.0f)
                    lineTo(20.0f, 6.75f)
                }
                g2.draw(mid)

                // Bottom segment
                val bottom = Path2D.Float().apply {
                    moveTo(4.0f, 12.0f)
                    lineTo(4.0f, 17.5f)
                    quadTo(12.0f, 20.5f, 20.0f, 17.5f)
                    lineTo(20.0f, 12.0f)
                }
                g2.draw(bottom)

                // LED status dots
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(8, 11, 8, 11)
                g2.drawLine(8, 16, 8, 16)
            }
        }

    /**
     * Settings icon: precision Material 3 gear.
     */
    fun settings(size: Int = 22, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

                val cx = 12.0
                val cy = 12.0
                val rInner = 3.6
                val rBase = 6.2
                val rTeeth = 8.8

                // Center hole
                g2.draw(Ellipse2D.Double(cx - rInner, cy - rInner, rInner * 2, rInner * 2))

                // 6-tooth gear perimeter
                val teeth = 6
                val path = Path2D.Double()
                val step = (2.0 * PI) / teeth

                for (i in 0 until teeth) {
                    val a0 = i * step - step * 0.22
                    val a1 = i * step - step * 0.12
                    val a2 = i * step + step * 0.12
                    val a3 = i * step + step * 0.22

                    val x0 = cx + cos(a0) * rBase
                    val y0 = cy + sin(a0) * rBase
                    val x1 = cx + cos(a1) * rTeeth
                    val y1 = cy + sin(a1) * rTeeth
                    val x2 = cx + cos(a2) * rTeeth
                    val y2 = cy + sin(a2) * rTeeth
                    val x3 = cx + cos(a3) * rBase
                    val y3 = cy + sin(a3) * rBase

                    if (i == 0) path.moveTo(x0, y0) else path.lineTo(x0, y0)
                    path.lineTo(x1, y1)
                    path.lineTo(x2, y2)
                    path.lineTo(x3, y3)
                }
                path.closePath()
                g2.draw(path)
            }
        }

    /**
     * About icon: circle with information 'i' glyph.
     */
    fun about(size: Int = 22, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

                // Circle boundary
                g2.draw(Ellipse2D.Float(2.5f, 2.5f, 19.0f, 19.0f))

                // Dot on 'i'
                g2.stroke = BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(12, 7, 12, 7)

                // Stem of 'i'
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(12, 11, 12, 17)
                g2.drawLine(10, 11, 12, 11) // serif
                g2.drawLine(10, 17, 14, 17) // base
            }
        }

    /**
     * Chevron right navigation arrow (›) rendered smoothly.
     */
    fun chevronRight(size: Int = 18, color: () -> Color = { Theme.TEXT_MUTED }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val chev = Path2D.Float().apply {
                    moveTo(9.0f, 6.0f)
                    lineTo(15.0f, 12.0f)
                    lineTo(9.0f, 18.0f)
                }
                g2.draw(chev)
            }
        }

    /**
     * Chevron left back arrow (‹) rendered smoothly.
     */
    fun chevronLeft(size: Int = 18, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val chev = Path2D.Float().apply {
                    moveTo(15.0f, 6.0f)
                    lineTo(9.0f, 12.0f)
                    lineTo(15.0f, 18.0f)
                }
                g2.draw(chev)
            }
        }

    /**
     * Heart icon for favorites.
     */
    fun heart(size: Int = 18, filled: Boolean = false, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val h = Path2D.Float().apply {
                    moveTo(12.0f, 20.0f)
                    curveTo(4.5f, 14.5f, 2.5f, 9.5f, 5.0f, 5.5f)
                    curveTo(7.0f, 2.5f, 10.5f, 3.5f, 12.0f, 6.5f)
                    curveTo(13.5f, 3.5f, 17.0f, 2.5f, 19.0f, 5.5f)
                    curveTo(21.5f, 9.5f, 19.5f, 14.5f, 12.0f, 20.0f)
                    closePath()
                }
                if (filled) g2.fill(h) else g2.draw(h)
            }
        }

    /**
     * Play triangle for reading / continue button.
     */
    fun play(size: Int = 16, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                val p = Path2D.Float().apply {
                    moveTo(8.0f, 5.5f)
                    lineTo(18.5f, 12.0f)
                    lineTo(8.0f, 18.5f)
                    closePath()
                }
                g2.fill(p)
            }
        }

    /**
     * Sync / Refresh circular arrows icon.
     */
    fun sync(size: Int = 18, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawArc(4, 4, 16, 16, 45, 135)
                g2.drawLine(4, 7, 7, 10)
                g2.drawLine(4, 7, 7, 4)
                g2.drawArc(4, 4, 16, 16, 225, 135)
                g2.drawLine(20, 17, 17, 14)
                g2.drawLine(20, 17, 17, 20)
            }
        }

    /**
     * Checkmark icon.
     */
    fun check(size: Int = 18, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val p = Path2D.Float().apply {
                    moveTo(4.5f, 12.5f)
                    lineTo(9.5f, 17.5f)
                    lineTo(19.5f, 6.5f)
                }
                g2.draw(p)
            }
        }

    /**
     * Trash bin icon.
     */
    fun trash(size: Int = 18, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(4, 6, 20, 6)
                g2.drawLine(9, 4, 15, 4)
                val body = Path2D.Float().apply {
                    moveTo(6.0f, 6.0f)
                    lineTo(7.0f, 19.0f)
                    quadTo(7.0f, 20.5f, 8.5f, 20.5f)
                    lineTo(15.5f, 20.5f)
                    quadTo(17.0f, 20.5f, 17.0f, 19.0f)
                    lineTo(18.0f, 6.0f)
                }
                g2.draw(body)
                g2.drawLine(10, 10, 10, 17)
                g2.drawLine(14, 10, 14, 17)
            }
        }

    /**
     * Search magnifying glass.
     */
    fun search(size: Int = 20, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.draw(Ellipse2D.Float(3.5f, 3.5f, 12.0f, 12.0f))
                g2.drawLine(13, 13, 20, 20)
            }
        }

    /**
     * Filter / Display controls icon.
     */
    fun filter(size: Int = 20, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                // Filter sliders
                g2.drawLine(3, 7, 21, 7)
                g2.drawLine(3, 17, 21, 17)
                g2.draw(RoundRectangle2D.Float(7.0f, 4.5f, 4.0f, 5.0f, 2.0f, 2.0f))
                g2.draw(RoundRectangle2D.Float(13.0f, 14.5f, 4.0f, 5.0f, 2.0f, 2.0f))
            }
        }

    /**
     * Vertical 3-dots overflow menu icon (⋮).
     */
    fun moreVert(size: Int = 20, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(3.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(12, 6, 12, 6)
                g2.drawLine(12, 12, 12, 12)
                g2.drawLine(12, 18, 12, 18)
            }
        }

    /**
     * Sort order icon: antiparallel vertical arrows (↑↓).
     */
    fun sort(size: Int = 16, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                // Left arrow pointing up
                g2.drawLine(7, 18, 7, 6)
                g2.drawLine(7, 6, 4, 9)
                g2.drawLine(7, 6, 10, 9)
                // Right arrow pointing down
                g2.drawLine(17, 6, 17, 18)
                g2.drawLine(17, 18, 14, 15)
                g2.drawLine(17, 18, 20, 15)
            }
        }

    /**
     * Sparkles / Graphic enhancement icon.
     */
    fun sparkles(size: Int = 18, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                // Main 4-point star
                val s = Path2D.Float().apply {
                    moveTo(12f, 2f)
                    quadTo(12f, 10f, 20f, 10f)
                    quadTo(12f, 10f, 12f, 18f)
                    quadTo(12f, 10f, 4f, 10f)
                    quadTo(12f, 10f, 12f, 2f)
                    closePath()
                }
                g2.fill(s)
                // Small secondary star
                val s2 = Path2D.Float().apply {
                    moveTo(19f, 15f)
                    quadTo(19f, 18.5f, 22.5f, 18.5f)
                    quadTo(19f, 18.5f, 19f, 22f)
                    quadTo(19f, 18.5f, 15.5f, 18.5f)
                    quadTo(19f, 18.5f, 19f, 15f)
                    closePath()
                }
                g2.fill(s2)
            }
        }

    /**
     * Library navigation destination icon (open book / reading).
     */
    fun library(size: Int = 20, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val left = Path2D.Float().apply {
                    moveTo(12f, 6.5f)
                    quadTo(7.5f, 5.0f, 3f, 6.0f)
                    lineTo(3f, 18.5f)
                    quadTo(7.5f, 17.5f, 12f, 19f)
                    closePath()
                }
                val right = Path2D.Float().apply {
                    moveTo(12f, 6.5f)
                    quadTo(16.5f, 5.0f, 21f, 6.0f)
                    lineTo(21f, 18.5f)
                    quadTo(16.5f, 17.5f, 12f, 19f)
                    closePath()
                }
                g2.draw(left)
                g2.draw(right)
                g2.drawLine(12, 7, 12, 19)
            }
        }

    /**
     * Updates destination icon (notification bell).
     */
    fun updates(size: Int = 20, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val bell = Path2D.Float().apply {
                    moveTo(12f, 3.5f)
                    quadTo(12f, 3f, 12f, 2.5f)
                    moveTo(7f, 16.5f)
                    lineTo(5f, 16.5f)
                    quadTo(6.5f, 15f, 6.5f, 10.5f)
                    curveTo(6.5f, 7.5f, 8.5f, 5f, 12f, 5f)
                    curveTo(15.5f, 5f, 17.5f, 7.5f, 17.5f, 10.5f)
                    quadTo(17.5f, 15f, 19f, 16.5f)
                    lineTo(17f, 16.5f)
                }
                g2.draw(bell)
                g2.drawLine(4, 16, 20, 16)
                g2.drawArc(10, 2, 4, 3, 0, 180)
                g2.drawArc(10, 17, 4, 3, 180, 180)
            }
        }

    /**
     * History destination icon (clock with counter-clockwise arrow).
     */
    fun history(size: Int = 20, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawArc(4, 4, 16, 16, 60, 290)
                g2.drawLine(10, 3, 13, 5)
                g2.drawLine(10, 7, 13, 5)
                g2.drawLine(12, 8, 12, 12)
                g2.drawLine(12, 12, 15, 14)
            }
        }

    /**
     * Browse / Explore destination icon (compass).
     */
    fun browse(size: Int = 20, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.draw(Ellipse2D.Float(3.5f, 3.5f, 17f, 17f))
                val needle = Path2D.Float().apply {
                    moveTo(15.5f, 8.5f)
                    lineTo(12f, 11f)
                    lineTo(8.5f, 15.5f)
                    lineTo(11f, 12f)
                    closePath()
                }
                g2.fill(needle)
            }
        }

    /**
     * More destination icon (three horizontal dots ⋯).
     */
    fun more(size: Int = 20, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(3.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(6, 12, 6, 12)
                g2.drawLine(12, 12, 12, 12)
                g2.drawLine(18, 12, 18, 12)
            }
        }

    /**
     * Sidebar collapse/expand toggle icon.
     */
    fun sidebarToggle(size: Int = 18, collapsed: Boolean = false, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.draw(RoundRectangle2D.Float(3.5f, 4f, 17f, 16f, 3f, 3f))
                g2.drawLine(9, 4, 9, 20)
                if (collapsed) {
                    val arr = Path2D.Float().apply {
                        moveTo(13f, 9f)
                        lineTo(16f, 12f)
                        lineTo(13f, 15f)
                    }
                    g2.draw(arr)
                } else {
                    val arr = Path2D.Float().apply {
                        moveTo(16f, 9f)
                        lineTo(13f, 12f)
                        lineTo(16f, 15f)
                    }
                    g2.draw(arr)
                }
            }
        }
}
