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
     * Copy / duplicate pages icon.
     */
    fun copy(size: Int = 18, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawRoundRect(9, 4, 11, 13, 3, 3)
                val back = Path2D.Float().apply {
                    moveTo(9f, 8f)
                    lineTo(6.5f, 8f)
                    quadTo(4.5f, 8f, 4.5f, 10f)
                    lineTo(4.5f, 18.5f)
                    quadTo(4.5f, 20.5f, 6.5f, 20.5f)
                    lineTo(14f, 20.5f)
                    quadTo(16f, 20.5f, 16f, 18.5f)
                    lineTo(16f, 17f)
                }
                g2.draw(back)
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

    fun edit(size: Int = 16, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val pencil = Path2D.Float().apply {
                    moveTo(4.0f, 20.0f)
                    lineTo(8.0f, 20.0f)
                    lineTo(20.0f, 8.0f)
                    lineTo(16.0f, 4.0f)
                    lineTo(4.0f, 16.0f)
                    closePath()
                }
                g2.draw(pencil)
                g2.drawLine(13, 7, 17, 11)
            }
        }

    fun tag(size: Int = 18, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val p = Path2D.Float().apply {
                    moveTo(4.5f, 11.5f)
                    lineTo(4.5f, 5.5f)
                    lineTo(10.5f, 5.5f)
                    lineTo(19.5f, 14.5f)
                    lineTo(14.5f, 19.5f)
                    closePath()
                }
                g2.draw(p)
                g2.draw(Ellipse2D.Float(7.0f, 7.0f, 2.5f, 2.5f))
            }
        }

    fun arrowUp(size: Int = 14, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(12, 18, 12, 6)
                g2.drawLine(12, 6, 7, 11)
                g2.drawLine(12, 6, 17, 11)
            }
        }

    fun arrowDown(size: Int = 14, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(12, 6, 12, 18)
                g2.drawLine(12, 18, 7, 13)
                g2.drawLine(12, 18, 17, 13)
            }
        }

    /**
     * Bookmark / reading ribbon icon.
     */
    fun bookmark(size: Int = 18, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val p = Path2D.Float().apply {
                    moveTo(5.5f, 4.0f)
                    lineTo(18.5f, 4.0f)
                    lineTo(18.5f, 20.0f)
                    lineTo(12.0f, 15.5f)
                    lineTo(5.5f, 20.0f)
                    closePath()
                }
                g2.draw(p)
            }
        }

    /**
     * Book open outline icon (for reading category).
     */
    fun bookOpen(size: Int = 18, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val left = Path2D.Float().apply {
                    moveTo(12f, 6.0f)
                    quadTo(7.5f, 4.5f, 3.5f, 5.5f)
                    lineTo(3.5f, 18.0f)
                    quadTo(7.5f, 17.0f, 12f, 18.5f)
                    closePath()
                }
                val right = Path2D.Float().apply {
                    moveTo(12f, 6.0f)
                    quadTo(16.5f, 4.5f, 20.5f, 5.5f)
                    lineTo(20.5f, 18.0f)
                    quadTo(16.5f, 17.0f, 12f, 18.5f)
                    closePath()
                }
                g2.draw(left)
                g2.draw(right)
                g2.drawLine(12, 6, 12, 18)
            }
        }

    /**
     * Check circle outline icon (for completed category).
     */
    fun checkCircle(size: Int = 18, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.draw(Ellipse2D.Float(3.0f, 3.0f, 18.0f, 18.0f))
                val p = Path2D.Float().apply {
                    moveTo(7.5f, 12.0f)
                    lineTo(10.5f, 15.0f)
                    lineTo(16.5f, 9.0f)
                }
                g2.draw(p)
            }
        }

    fun close(size: Int = 16, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(6, 6, 18, 18)
                g2.drawLine(18, 6, 6, 18)
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
     * Browse / Sources destination icon (target reticle / compass matching Fotos 4 & 5).
     */
    fun browse(size: Int = 20, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.draw(Ellipse2D.Float(5.5f, 5.5f, 13f, 13f))
                // 4 cardinal ticks
                g2.drawLine(12, 2, 12, 6)
                g2.drawLine(12, 18, 12, 22)
                g2.drawLine(2, 12, 6, 12)
                g2.drawLine(18, 12, 22, 12)
                // Center dot
                g2.fill(Ellipse2D.Float(10.3f, 10.3f, 3.4f, 3.4f))
            }
        }

    /**
     * Painter's palette icon for Appearance / Aparência (matching Foto 5).
     */
    fun palette(size: Int = 20, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val p = Path2D.Float().apply {
                    moveTo(12.0f, 3.0f)
                    curveTo(6.8f, 3.0f, 3.0f, 6.8f, 3.0f, 12.0f)
                    curveTo(3.0f, 17.0f, 7.0f, 21.0f, 12.0f, 21.0f)
                    curveTo(13.8f, 21.0f, 15.0f, 19.8f, 15.0f, 18.2f)
                    curveTo(15.0f, 17.0f, 14.3f, 16.2f, 14.3f, 15.2f)
                    curveTo(14.3f, 14.2f, 15.2f, 13.5f, 16.5f, 13.5f)
                    lineTo(18.0f, 13.5f)
                    curveTo(19.8f, 13.5f, 21.0f, 12.0f, 21.0f, 10.2f)
                    curveTo(21.0f, 6.0f, 17.0f, 3.0f, 12.0f, 3.0f)
                    closePath()
                }
                g2.draw(p)
                g2.fill(Ellipse2D.Float(6.8f, 9.5f, 2.2f, 2.2f))
                g2.fill(Ellipse2D.Float(10.0f, 6.5f, 2.2f, 2.2f))
                g2.fill(Ellipse2D.Float(14.2f, 7.2f, 2.2f, 2.2f))
            }
        }

    /**
     * Stacked pages icon for Reader / Leitor (matching Foto 5).
     */
    fun readerPages(size: Int = 20, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.draw(RoundRectangle2D.Float(7.0f, 3.5f, 12.5f, 14.5f, 3f, 3f))
                val back = Path2D.Float().apply {
                    moveTo(4.5f, 7.0f)
                    lineTo(4.5f, 18.5f)
                    quadTo(4.5f, 20.5f, 6.5f, 20.5f)
                    lineTo(16.0f, 20.5f)
                }
                g2.draw(back)
            }
        }

    /**
     * Filled tag / label icon for Library / More (matching Fotos 4 & 5).
     */
    fun libraryTag(size: Int = 20, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                val tag = Path2D.Float().apply {
                    moveTo(4.0f, 6.5f)
                    quadTo(4.0f, 5.0f, 5.5f, 5.0f)
                    lineTo(15.5f, 5.0f)
                    lineTo(20.5f, 12.0f)
                    lineTo(15.5f, 19.0f)
                    lineTo(5.5f, 19.0f)
                    quadTo(4.0f, 19.0f, 4.0f, 17.5f)
                    closePath()
                }
                g2.fill(tag)
            }
        }

    /**
     * Two opposite horizontal arrows (⇄) for Tracking / Rastreamento (matching Foto 5).
     */
    fun trackingArrows(size: Int = 20, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                // Top arrow pointing right
                g2.drawLine(4, 8, 19, 8)
                g2.drawLine(15, 4, 19, 8)
                g2.drawLine(15, 12, 19, 8)
                // Bottom arrow pointing left
                g2.drawLine(20, 16, 5, 16)
                g2.drawLine(9, 12, 5, 16)
                g2.drawLine(9, 20, 5, 16)
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

    /**
     * Google Lens / Translate icon: stylized '文' / 'A' translation glyph with Lens corner brackets.
     */
    fun translateIcon(size: Int = 18, color: Color): Icon =
        translateIcon(size) { color }

    fun translateIcon(size: Int = 18, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size.coerceAtLeast(0), color) {
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                if (size <= 0) return
                super.paintIcon(c, g, x, y)
            }

            override fun paintVector(g2: Graphics2D, color: Color) {
                val stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val area = java.awt.geom.Area()

                // Left/top CJK '文' glyph
                val cjkPath = Path2D.Float().apply {
                    moveTo(6.5f, 3.5f)
                    lineTo(6.5f, 5.5f)
                    moveTo(3.0f, 5.8f)
                    lineTo(10.5f, 5.8f)
                    moveTo(9.0f, 5.8f)
                    quadTo(7.5f, 10.0f, 3.5f, 12.5f)
                    moveTo(4.5f, 7.2f)
                    quadTo(7.0f, 10.2f, 10.2f, 12.5f)
                }
                area.add(java.awt.geom.Area(stroke.createStrokedShape(cjkPath)))

                // Right/bottom Latin 'A' glyph
                val latinPath = Path2D.Float().apply {
                    moveTo(13.0f, 20.0f)
                    lineTo(16.5f, 11.2f)
                    lineTo(20.0f, 20.0f)
                    moveTo(14.2f, 17.5f)
                    lineTo(18.8f, 17.5f)
                }
                area.add(java.awt.geom.Area(stroke.createStrokedShape(latinPath)))

                // Lens viewfinder corner accents (top-right and bottom-left)
                val cornerPath = Path2D.Float().apply {
                    moveTo(16.5f, 3.5f)
                    lineTo(20.5f, 3.5f)
                    lineTo(20.5f, 7.5f)
                    moveTo(3.5f, 16.5f)
                    lineTo(3.5f, 20.5f)
                    lineTo(7.5f, 20.5f)
                }
                area.add(java.awt.geom.Area(stroke.createStrokedShape(cornerPath)))

                g2.color = color
                g2.fill(area)
            }
        }

    /** Pin / pushpin icon (filled or outline) for pinning sources. */
    fun pin(size: Int = 18, filled: Boolean = false, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val head = Path2D.Float().apply {
                    moveTo(12f, 17f)
                    lineTo(12f, 21f)
                }
                g2.draw(head)
                val body = Path2D.Float().apply {
                    moveTo(9f, 4f)
                    lineTo(15f, 4f)
                    lineTo(14f, 10f)
                    lineTo(17.5f, 14f)
                    lineTo(17.5f, 16.5f)
                    lineTo(6.5f, 16.5f)
                    lineTo(6.5f, 14f)
                    lineTo(10f, 10f)
                    closePath()
                }
                if (filled) g2.fill(body) else g2.draw(body)
            }
        }

    /** Folder icon for local manga sources. */
    fun folder(size: Int = 20, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val p = Path2D.Float().apply {
                    moveTo(3f, 6.5f)
                    quadTo(3f, 5f, 4.5f, 5f)
                    lineTo(9.2f, 5f)
                    lineTo(11.2f, 7.5f)
                    lineTo(19.5f, 7.5f)
                    quadTo(21f, 7.5f, 21f, 9f)
                    lineTo(21f, 17.5f)
                    quadTo(21f, 19f, 19.5f, 19f)
                    lineTo(4.5f, 19f)
                    quadTo(3f, 19f, 3f, 17.5f)
                    closePath()
                }
                g2.draw(p)
            }
        }

    /** Flame icon for popular / trending catalog items. */
    fun flame(size: Int = 16, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val p = Path2D.Float().apply {
                    moveTo(12f, 3f)
                    quadTo(17.5f, 8.5f, 17.5f, 14f)
                    curveTo(17.5f, 17.8f, 15f, 20.5f, 12f, 20.5f)
                    curveTo(9f, 20.5f, 6.5f, 17.8f, 6.5f, 14f)
                    curveTo(6.5f, 11.4f, 8.0f, 9.2f, 9.2f, 8.0f)
                    quadTo(9.8f, 10.2f, 11.2f, 10.6f)
                    quadTo(11.0f, 6.5f, 12f, 3f)
                    closePath()
                }
                g2.draw(p)
            }
        }

    /** Lightning bolt icon for instant extension generator. */
    fun bolt(size: Int = 18, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val p = Path2D.Float().apply {
                    moveTo(13.2f, 2.8f)
                    lineTo(5.2f, 13.0f)
                    lineTo(11.4f, 13.0f)
                    lineTo(10.5f, 21.2f)
                    lineTo(18.8f, 10.8f)
                    lineTo(12.6f, 10.8f)
                    closePath()
                }
                g2.draw(p)
            }
        }

    /** Plug / puzzle icon for extensions tab. */
    fun plug(size: Int = 16, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(9, 4, 9, 8)
                g2.drawLine(15, 4, 15, 8)
                val body = Path2D.Float().apply {
                    moveTo(6.5f, 8f)
                    lineTo(17.5f, 8f)
                    lineTo(17.5f, 12.2f)
                    curveTo(17.5f, 15.2f, 15.0f, 17.5f, 12f, 17.5f)
                    curveTo(9.0f, 17.5f, 6.5f, 15.2f, 6.5f, 12.2f)
                    closePath()
                }
                g2.draw(body)
                g2.drawLine(12, 17, 12, 21)
            }
        }

    /** Zoom-in magnifying glass icon. */
    fun zoomIn(size: Int = 16, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.draw(Ellipse2D.Float(3.5f, 3.5f, 13f, 13f))
                g2.drawLine(15, 15, 20, 20)
                g2.drawLine(10, 7, 10, 13)
                g2.drawLine(7, 10, 13, 10)
            }
        }

    /** Zoom-out magnifying glass icon. */
    fun zoomOut(size: Int = 16, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.draw(Ellipse2D.Float(3.5f, 3.5f, 13f, 13f))
                g2.drawLine(15, 15, 20, 20)
                g2.drawLine(7, 10, 13, 10)
            }
        }

    /** Fullscreen maximize corner icon. */
    fun maximize(size: Int = 16, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.draw(Path2D.Float().apply {
                    moveTo(4f, 8.5f); lineTo(4f, 4f); lineTo(8.5f, 4f)
                    moveTo(15.5f, 4f); lineTo(20f, 4f); lineTo(20f, 8.5f)
                    moveTo(20f, 15.5f); lineTo(20f, 20f); lineTo(15.5f, 20f)
                    moveTo(8.5f, 20f); lineTo(4f, 20f); lineTo(4f, 15.5f)
                })
            }
        }

    /** Eye icon (open) for password / token visibility toggles. */
    fun eye(size: Int = 16, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val eye = Path2D.Float().apply {
                    moveTo(2.5f, 12f)
                    quadTo(12f, 4.5f, 21.5f, 12f)
                    quadTo(12f, 19.5f, 2.5f, 12f)
                }
                g2.draw(eye)
                g2.draw(Ellipse2D.Float(9.2f, 9.2f, 5.6f, 5.6f))
            }
        }

    /** Eye-off icon for hiding reader HUD or secrets. */
    fun eyeOff(size: Int = 16, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val eye = Path2D.Float().apply {
                    moveTo(2.5f, 12f)
                    quadTo(12f, 4.5f, 21.5f, 12f)
                    quadTo(12f, 19.5f, 2.5f, 12f)
                }
                g2.draw(eye)
                g2.draw(Ellipse2D.Float(9.5f, 9.5f, 5f, 5f))
                g2.drawLine(4, 4, 20, 20)
            }
        }

    /** Solid filled bookmark ribbon icon for active page indicators. */
    fun bookmarkFilled(size: Int = 18, color: () -> Color = { Theme.PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                val p = Path2D.Float().apply {
                    moveTo(6f, 3.5f)
                    lineTo(18f, 3.5f)
                    lineTo(18f, 20.5f)
                    lineTo(12f, 16f)
                    lineTo(6f, 20.5f)
                    closePath()
                }
                g2.fill(p)
            }
        }

    /** Triangle exclamation warning icon. */
    fun alertTriangle(size: Int = 16, color: () -> Color = { Theme.WARNING }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val tri = Path2D.Float().apply {
                    moveTo(12f, 3.5f)
                    lineTo(21f, 19.5f)
                    lineTo(3f, 19.5f)
                    closePath()
                }
                g2.draw(tri)
                g2.drawLine(12, 9, 12, 14)
                g2.fillOval(11, 16, 2, 2)
            }
        }

    /** Hyperlink chain icon for URL input fields. */
    fun link(size: Int = 16, color: () -> Color = { Theme.TEXT_MUTED }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.draw(RoundRectangle2D.Float(3f, 8.5f, 9f, 7f, 7f, 7f))
                g2.draw(RoundRectangle2D.Float(12f, 8.5f, 9f, 7f, 7f, 7f))
                g2.drawLine(9, 12, 15, 12)
            }
        }

    /** Compact downward chevron for dropdowns and accordions. */
    fun chevronDown(size: Int = 14, color: () -> Color = { Theme.TEXT_SECONDARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val p = Path2D.Float().apply {
                    moveTo(6f, 9f)
                    lineTo(12f, 15f)
                    lineTo(18f, 9f)
                }
                g2.draw(p)
            }
        }

    /** Compact upward chevron for accordions and reverse sort. */
    fun chevronUp(size: Int = 14, color: () -> Color = { Theme.TEXT_SECONDARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val p = Path2D.Float().apply {
                    moveTo(6f, 15f)
                    lineTo(12f, 9f)
                    lineTo(18f, 15f)
                }
                g2.draw(p)
            }
        }

    /** Previous chapter skip icon. */
    fun skipBack(size: Int = 15, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(5, 5, 5, 19)
                val tri = Path2D.Float().apply {
                    moveTo(18.5f, 5.5f); lineTo(9.5f, 12f); lineTo(18.5f, 18.5f); closePath()
                }
                g2.fill(tri)
            }
        }

    /** Next chapter skip icon. */
    fun skipForward(size: Int = 15, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(19, 5, 19, 19)
                val tri = Path2D.Float().apply {
                    moveTo(5.5f, 5.5f); lineTo(14.5f, 12f); lineTo(5.5f, 18.5f); closePath()
                }
                g2.fill(tri)
            }
        }

    /** Crisp two-bar pause icon for reader auto-scroll HUD. */
    fun pause(size: Int = 14, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.fillRoundRect(6, 5, 4, 14, 2, 2)
                g2.fillRoundRect(14, 5, 4, 14, 2, 2)
            }
        }

    /** Crisp 2x2 grid icon for display mode toggles. */
    fun grid(size: Int = 18, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawRoundRect(3, 3, 7, 7, 2, 2)
                g2.drawRoundRect(14, 3, 7, 7, 2, 2)
                g2.drawRoundRect(3, 14, 7, 7, 2, 2)
                g2.drawRoundRect(14, 14, 7, 7, 2, 2)
            }
        }

    /** Batch / list selection icon matching GTK edit-select-all-symbolic. */
    fun batchSelect(size: Int = 18, color: () -> Color = { Theme.TEXT_PRIMARY }): Icon =
        object : BaseVectorIcon(size, color) {
            override fun paintVector(g2: Graphics2D, color: Color) {
                g2.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawRoundRect(4, 4, 16, 16, 3, 3)
                g2.drawLine(8, 9, 16, 9)
                g2.drawLine(8, 12, 16, 12)
                g2.drawLine(8, 15, 13, 15)
            }
        }
}

/** Smoothly blends two ARGB colors by [ratio] in `[0f, 1f]`. */
internal fun blendUiColor(c1: Color, c2: Color, ratio: Float): Color {
    val r = ratio.coerceIn(0f, 1f)
    val inv = 1f - r
    return Color(
        (c1.red * inv + c2.red * r).toInt().coerceIn(0, 255),
        (c1.green * inv + c2.green * r).toInt().coerceIn(0, 255),
        (c1.blue * inv + c2.blue * r).toInt().coerceIn(0, 255),
        (c1.alpha * inv + c2.alpha * r).toInt().coerceIn(0, 255),
    )
}

/**
 * Modern custom-painted editorial button for Mihon Index.
 * Features calm neutral/Cobalt flat states, 120ms/180ms hover/press transitions, 8px corners,
 * visible keyboard focus ring, and readable disabled states.
 */
class ModernButton(
    text: String = "",
    icon: Icon? = null,
    variant: Variant = Variant.SURFACE,
    private val compact: Boolean = false,
) : javax.swing.JButton(text, icon) {

    var variant: Variant = variant
        set(value) {
            field = value
            repaint()
        }

    var minHeightOverride: Int = 0
        set(value) {
            field = value
            revalidate()
            repaint()
        }

    var minWidthOverride: Int = 0
        set(value) {
            field = value
            revalidate()
            repaint()
        }

    enum class Variant {
        PRIMARY,    // Solid Cobalt (#4F46E5 -> #4338CA -> #3730A3) CTA with white text
        FAVORITE,   // Active Danger/Ruby tonal surface with 1px semantic border
        DANGER,     // Solid destructive crimson/red CTA for dangerous actions
        SURFACE,    // Calm neutral card surface with 1px hairline border
        GHOST,      // Transparent at rest, soft card hover fill on rollover
        HUD,        // Always-dark (#17171C) button for dark cinema reader HUD
        HUD_ACCENT, // Active Cobalt accent button for dark cinema reader HUD
    }

    var isLoading: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startLoadingSpinner() else stopLoadingSpinner()
            repaint()
        }

    private var spinnerAngle = 0f
    private var spinnerTimer: javax.swing.Timer? = null
    private var hoverProgress = 0f
    private var pressProgress = 0f
    private var animTimer: javax.swing.Timer? = null

    private fun startLoadingSpinner() {
        spinnerTimer?.stop()
        if (!mihon.desktop.ui.animation.MotionPreferences.isEnabled) return
        spinnerTimer = javax.swing.Timer(16) {
            if (!isDisplayable || !isLoading) {
                spinnerTimer?.stop()
                spinnerTimer = null
                return@Timer
            }
            spinnerAngle = (spinnerAngle + 14f) % 360f
            repaint()
        }.also { it.start() }
    }

    private fun stopLoadingSpinner() {
        spinnerTimer?.stop()
        spinnerTimer = null
    }

    init {
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        font = Theme.FONT_DISPLAY.deriveFont(java.awt.Font.BOLD, if (compact) 12.5f else 13f)
        iconTextGap = if (compact) 6 else 8
        updatePadding()

        addChangeListener {
            ensureAnimationRunning()
        }
        addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent?) { repaint() }
            override fun focusLost(e: java.awt.event.FocusEvent?) { repaint() }
        })
    }

    override fun removeNotify() {
        animTimer?.stop()
        animTimer = null
        spinnerTimer?.stop()
        spinnerTimer = null
        super.removeNotify()
    }

    override fun getPreferredSize(): java.awt.Dimension {
        val d = super.getPreferredSize()
        if (isPreferredSizeSet) return d
        val targetH = when {
            minHeightOverride > 0 -> minHeightOverride
            compact -> Theme.CONTROL_HEIGHT_SMALL
            else -> Theme.CONTROL_HEIGHT_COMPACT
        }
        val targetW = if (minWidthOverride > 0) maxOf(d.width, minWidthOverride) else d.width
        return java.awt.Dimension(targetW, maxOf(d.height, targetH))
    }

    private fun ensureAnimationRunning() {
        val targetH = if (model.isRollover && isEnabled) 1f else 0f
        val targetP = if (model.isPressed && isEnabled) 1f else 0f
        if (!mihon.desktop.ui.animation.MotionPreferences.isEnabled ||
            (kotlin.math.abs(hoverProgress - targetH) < 0.02f && kotlin.math.abs(pressProgress - targetP) < 0.02f)
        ) {
            hoverProgress = targetH
            pressProgress = targetP
            repaint()
            return
        }
        if (animTimer?.isRunning == true) return
        animTimer = javax.swing.Timer(14) { e ->
            val th = if (model.isRollover && isEnabled) 1f else 0f
            val tp = if (model.isPressed && isEnabled) 1f else 0f
            hoverProgress += (th - hoverProgress) * 0.28f
            pressProgress += (tp - pressProgress) * 0.36f
            if (kotlin.math.abs(hoverProgress - th) < 0.02f) hoverProgress = th
            if (kotlin.math.abs(pressProgress - tp) < 0.02f) pressProgress = tp
            repaint()
            if (hoverProgress == th && pressProgress == tp) {
                (e.source as? javax.swing.Timer)?.stop()
            }
        }.also { it.start() }
    }

    private fun updatePadding() {
        val hasText = !text.isNullOrBlank()
        border = when {
            compact && hasText -> javax.swing.BorderFactory.createEmptyBorder(5, 11, 5, 11)
            compact -> javax.swing.BorderFactory.createEmptyBorder(5, 7, 5, 7)
            hasText -> javax.swing.BorderFactory.createEmptyBorder(8, 15, 8, 15)
            else -> javax.swing.BorderFactory.createEmptyBorder(7, 9, 7, 9)
        }
    }

    override fun setText(text: String?) {
        super.setText(text)
        updatePadding()
    }

    override fun paintBorder(g: Graphics) {
        // Suppressed: border and focus ring are drawn with sub-pixel anti-aliasing inside paintComponent
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val w = width.toFloat()
        val h = height.toFloat()
        val arc = (Theme.CONTROL_RADIUS * 2).toFloat()
        val hp = if (isEnabled) easeOutCubic(hoverProgress) else 0f
        val pp = if (isEnabled) pressProgress else 0f

        if (pp > 0.01f && mihon.desktop.ui.animation.MotionPreferences.isEnabled) {
            val scale = 1f - 0.022f * pp
            g2.translate(w / 2.0, h / 2.0)
            g2.scale(scale.toDouble(), scale.toDouble())
            g2.translate(-w / 2.0, -h / 2.0)
        }

        val shape = RoundRectangle2D.Float(1f, 1f, w - 2f, h - 2f, arc, arc)

        when (variant) {
            Variant.PRIMARY -> {
                val fill = if (!isEnabled) {
                    blendUiColor(Theme.BG_SURFACE, Theme.PRIMARY, 0.36f)
                } else {
                    blendUiColor(blendUiColor(Theme.PRIMARY, Theme.PRIMARY_HOVER, hp), Theme.PRIMARY_PRESS, pp)
                }
                g2.color = fill
                g2.fill(shape)
                foreground = if (!isEnabled) {
                    if (Theme.isDark) Color(0xC2, 0xC6, 0xD6) else Color(0xF7, 0xF7, 0xFA)
                } else {
                    Color.WHITE
                }
            }

            Variant.FAVORITE -> {
                val base = if (Theme.isDark) Color(0xFF, 0x6B, 0x7A) else Color(0xE0, 0x1B, 0x24)
                val bgAlpha = if (!isEnabled) 18 else (28 + 16 * hp + 14 * pp).toInt().coerceIn(0, 90)
                g2.color = Color(base.red, base.green, base.blue, bgAlpha)
                g2.fill(shape)

                val borderAlpha = if (!isEnabled) 45 else (95 + 50 * hp).toInt().coerceIn(0, 185)
                g2.color = Color(base.red, base.green, base.blue, borderAlpha)
                g2.stroke = BasicStroke(1f)
                g2.draw(shape)

                foreground = if (!isEnabled) Theme.TEXT_MUTED else if (Theme.isDark) Color(0xFF, 0x8B, 0x8B) else Color(0xB9, 0x1C, 0x1C)
            }

            Variant.DANGER -> {
                val base = Color(0xDC, 0x26, 0x26)
                val hover = Color(0xB9, 0x1C, 0x1C)
                val press = Color(0x99, 0x1B, 0x1B)
                val fill = if (!isEnabled) {
                    blendUiColor(Theme.BG_SURFACE, base, 0.36f)
                } else {
                    blendUiColor(blendUiColor(base, hover, hp), press, pp)
                }
                g2.color = fill
                g2.fill(shape)
                foreground = Color.WHITE
            }

            Variant.SURFACE -> {
                val rest = Theme.BG_CARD
                val hov = Theme.BG_CARD_HOVER
                val prs = Theme.BG_SURFACE
                val bg = if (!isEnabled) Theme.BG_SURFACE else blendUiColor(blendUiColor(rest, hov, hp), prs, pp)
                g2.color = bg
                g2.fill(shape)

                g2.color = if (!isEnabled) {
                    Theme.BORDER_SUBTLE
                } else {
                    blendUiColor(Theme.BORDER_SUBTLE, Theme.BORDER_STRONG, hp)
                }
                g2.stroke = BasicStroke(1f)
                g2.draw(shape)

                foreground = if (!isEnabled) Theme.TEXT_MUTED else Theme.TEXT_PRIMARY
            }

            Variant.GHOST -> {
                if (isEnabled && (hp > 0.01f || pp > 0.01f)) {
                    val alpha = ((24f * hp) + (18f * pp)).toInt().coerceIn(0, 72)
                    g2.color = if (Theme.isDark) Color(255, 255, 255, alpha) else Color(22, 23, 34, alpha)
                    g2.fill(shape)
                }
                foreground = if (!isEnabled) Theme.TEXT_MUTED else blendUiColor(Theme.TEXT_SECONDARY, Theme.TEXT_PRIMARY, hp)
            }

            Variant.HUD -> {
                val bg = blendUiColor(
                    blendUiColor(Color(0x1E, 0x1F, 0x26, 226), Color(0x2D, 0x2F, 0x3B, 240), hp),
                    Color(0x14, 0x15, 0x1A, 248),
                    pp,
                )
                g2.color = bg
                g2.fill(shape)
                g2.color = Color(255, 255, 255, (30 + 28 * hp).toInt().coerceIn(0, 88))
                g2.stroke = BasicStroke(1f)
                g2.draw(shape)
                foreground = if (!isEnabled) Color(165, 168, 182) else blendUiColor(Color(232, 234, 242), Color.WHITE, hp)
            }

            Variant.HUD_ACCENT -> {
                val fill = blendUiColor(blendUiColor(Theme.PRIMARY, Theme.PRIMARY_HOVER, hp), Theme.PRIMARY_PRESS, pp)
                g2.color = Color(fill.red, fill.green, fill.blue, (215 + 35 * hp).toInt().coerceIn(0, 255))
                g2.fill(shape)
                g2.color = Color(255, 255, 255, 55)
                g2.stroke = BasicStroke(1f)
                g2.draw(shape)
                foreground = Color.WHITE
            }
        }

        if (isEnabled && isFocusOwner) {
            g2.color = if (variant == Variant.PRIMARY || variant == Variant.DANGER || variant == Variant.HUD_ACCENT) {
                Color(255, 255, 255, 200)
            } else {
                Theme.PRIMARY
            }
            g2.stroke = BasicStroke(2f)
            g2.draw(RoundRectangle2D.Float(1.5f, 1.5f, w - 3f, h - 3f, arc, arc))
        }

        if (isLoading) {
            val s = 12f
            val sx = if (text.isNullOrBlank()) (w - s) / 2f else 9f
            val sy = (h - s) / 2f
            g2.color = Color(foreground.red, foreground.green, foreground.blue, 220)
            g2.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.drawArc(sx.toInt(), sy.toInt(), s.toInt(), s.toInt(), spinnerAngle.toInt(), 250)
        }

        g2.dispose()
        val gc = g.create() as Graphics2D
        if (pp > 0.01f && mihon.desktop.ui.animation.MotionPreferences.isEnabled) {
            val scale = 1f - 0.022f * pp
            gc.translate(w / 2.0, h / 2.0)
            gc.scale(scale.toDouble(), scale.toDouble())
            gc.translate(-w / 2.0, -h / 2.0)
        }
        super.paintComponent(gc)
        gc.dispose()
    }
}

/**
 * Compact square/squircle icon button with smooth hover halo and 8px radius for toolbars & reader HUDs.
 */
open class ModernIconButton(
    icon: Icon,
    tip: String = "",
    sizePx: Int = Theme.CONTROL_HEIGHT_COMPACT,
    private val cornerRadius: Float = (Theme.CONTROL_RADIUS * 2).toFloat(),
    private val darkOverlay: Boolean = false,
    private val surfaceStyled: Boolean = false,
) : javax.swing.JButton(icon) {

    var activeHighlight: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                repaint()
            }
        }

    var badgeCount: Int = 0
        set(value) {
            if (field != value) {
                field = value
                repaint()
            }
        }

    private var hoverProgress = 0f
    private var pressProgress = 0f
    private var animTimer: javax.swing.Timer? = null

    init {
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isFocusable = !darkOverlay
        toolTipText = tip.ifBlank { null }
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        preferredSize = java.awt.Dimension(sizePx, sizePx)
        minimumSize = java.awt.Dimension(sizePx, sizePx)
        addChangeListener { ensureAnim() }
        if (!darkOverlay) {
            addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusGained(e: java.awt.event.FocusEvent?) { repaint() }
                override fun focusLost(e: java.awt.event.FocusEvent?) { repaint() }
            })
        }
    }

    override fun removeNotify() {
        animTimer?.stop()
        animTimer = null
        super.removeNotify()
    }

    private fun ensureAnim() {
        val th = if (model.isRollover && isEnabled) 1f else 0f
        val tp = if (model.isPressed && isEnabled) 1f else 0f
        if (!mihon.desktop.ui.animation.MotionPreferences.isEnabled ||
            (kotlin.math.abs(hoverProgress - th) < 0.02f && kotlin.math.abs(pressProgress - tp) < 0.02f)
        ) {
            hoverProgress = th
            pressProgress = tp
            repaint()
            return
        }
        if (animTimer?.isRunning == true) return
        animTimer = javax.swing.Timer(14) { e ->
            val tH = if (model.isRollover && isEnabled) 1f else 0f
            val tP = if (model.isPressed && isEnabled) 1f else 0f
            hoverProgress += (tH - hoverProgress) * 0.28f
            pressProgress += (tP - pressProgress) * 0.36f
            if (kotlin.math.abs(hoverProgress - tH) < 0.02f) hoverProgress = tH
            if (kotlin.math.abs(pressProgress - tP) < 0.02f) pressProgress = tP
            repaint()
            if (hoverProgress == tH && pressProgress == tP) (e.source as? javax.swing.Timer)?.stop()
        }.also { it.start() }
    }

    override fun paintBorder(g: Graphics) {}

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = width.toFloat()
        val h = height.toFloat()
        val arc = if (cornerRadius > 900f) minOf(w, h) else cornerRadius
        if (pressProgress > 0.01f && mihon.desktop.ui.animation.MotionPreferences.isEnabled) {
            val scale = 1f - 0.03f * pressProgress
            g2.translate(w / 2.0, h / 2.0)
            g2.scale(scale.toDouble(), scale.toDouble())
            g2.translate(-w / 2.0, -h / 2.0)
        }
        if (surfaceStyled) {
            val fill = if (activeHighlight) {
                Theme.ACCENT_SOFT
            } else {
                blendUiColor(Theme.BG_CARD, Theme.BG_CARD_HOVER, easeOutCubic(hoverProgress))
            }
            g2.color = fill
            g2.fill(RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, arc, arc))
            g2.color = if (activeHighlight) Theme.PRIMARY else Theme.BORDER_SUBTLE
            g2.stroke = BasicStroke(1f)
            g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, arc, arc))
        } else {
            val bgAlpha = ((24f * hoverProgress) + (18f * pressProgress)).toInt().coerceIn(0, 70)
            if (bgAlpha > 0) {
                g2.color = if (darkOverlay || Theme.isDark) Color(255, 255, 255, bgAlpha) else Color(0, 0, 0, bgAlpha)
                g2.fill(RoundRectangle2D.Float(2f, 2f, w - 4f, h - 4f, arc, arc))
            }
        }
        if (isFocusable && isFocusOwner) {
            g2.color = Theme.PRIMARY
            g2.stroke = BasicStroke(1.5f)
            g2.draw(RoundRectangle2D.Float(1.5f, 1.5f, w - 3f, h - 3f, arc, arc))
        }
        g2.dispose()
        val gc = g.create() as Graphics2D
        if (pressProgress > 0.01f && mihon.desktop.ui.animation.MotionPreferences.isEnabled) {
            val scale = 1f - 0.03f * pressProgress
            gc.translate(w / 2.0, h / 2.0)
            gc.scale(scale.toDouble(), scale.toDouble())
            gc.translate(-w / 2.0, -h / 2.0)
        }
        super.paintComponent(gc)
        gc.dispose()

        if (badgeCount > 0) {
            val bg2 = g.create() as Graphics2D
            bg2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            bg2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
            val badgeText = if (badgeCount > 99) "99+" else badgeCount.toString()
            bg2.font = Theme.FONT_UI.deriveFont(java.awt.Font.BOLD, 9.5f)
            val fm = bg2.fontMetrics
            val bw = maxOf(15, fm.stringWidth(badgeText) + 7)
            val bh = 15
            val bx = (width - bw - 1).coerceAtLeast(0)
            val by = 1
            bg2.color = Theme.PRIMARY
            bg2.fill(RoundRectangle2D.Float(bx.toFloat(), by.toFloat(), bw.toFloat(), bh.toFloat(), bh.toFloat(), bh.toFloat()))
            bg2.color = Color.WHITE
            bg2.drawString(badgeText, bx + (bw - fm.stringWidth(badgeText)) / 2, by + ((bh - fm.height) / 2) + fm.ascent)
            bg2.dispose()
        }
    }
}
