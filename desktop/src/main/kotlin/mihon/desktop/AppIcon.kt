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

    fun getWindowIcons(): List<BufferedImage> =
        listOf(16, 24, 32, 48, 64, 128, 256).map { createImage(it) }

    /**
     * Ensures the Linux desktop environment (GNOME/KDE/Wayland/X11) has high-resolution PNG and SVG
     * icons registered in `~/.local/share/icons/hicolor/` and a matching `.desktop` entry with
     * the proper `StartupWMClass` so the dock/taskbar never shows a generic gear icon.
     */
    fun installLinuxDesktopIntegration() {
        val os = System.getProperty("os.name", "").lowercase()
        if (!os.contains("linux")) return

        // 1. Set AWT X11 WM_CLASS & Taskbar icon
        runCatching {
            val toolkit = java.awt.Toolkit.getDefaultToolkit()
            val awtAppClassNameField = toolkit.javaClass.getDeclaredField("awtAppClassName")
            awtAppClassNameField.isAccessible = true
            awtAppClassNameField.set(toolkit, "mihon-linux")
        }
        runCatching {
            if (java.awt.Taskbar.isTaskbarSupported()) {
                val taskbar = java.awt.Taskbar.getTaskbar()
                if (taskbar.isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.iconImage = createImage(256)
                }
            }
        }

        // 2. Write hicolor PNG icons & desktop entry asynchronously
        Thread({
            runCatching {
                val home = java.io.File(System.getProperty("user.home"))
                val hicolor = java.io.File(home, ".local/share/icons/hicolor")
                val sizes = listOf(16, 24, 32, 48, 64, 128, 256, 512)
                for (sz in sizes) {
                    val dir = java.io.File(hicolor, "${sz}x${sz}/apps")
                    if (!dir.exists()) dir.mkdirs()
                    val pngFile = java.io.File(dir, "mihon-linux.png")
                    if (!pngFile.exists() || pngFile.length() < 100L) {
                        javax.imageio.ImageIO.write(createImage(sz), "PNG", pngFile)
                    }
                    // Also write alias matching StartupWMClass for Wayland compositors
                    val wmClassPng = java.io.File(dir, "mihon-desktop-MainKt.png")
                    if (!wmClassPng.exists()) {
                        javax.imageio.ImageIO.write(createImage(sz), "PNG", wmClassPng)
                    }
                }

                val scalableDir = java.io.File(hicolor, "scalable/apps").apply { mkdirs() }
                val svgFile = java.io.File(scalableDir, "mihon-linux.svg")
                svgFile.writeText(
                    """
                    <svg xmlns="http://www.w3.org/2000/svg" width="256" height="256" viewBox="0 0 256 256">
                      <defs>
                        <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
                          <stop offset="0%" stop-color="#3B364C"/>
                          <stop offset="100%" stop-color="#181722"/>
                        </linearGradient>
                        <linearGradient id="pg" x1="0" y1="0" x2="1" y2="1">
                          <stop offset="0%" stop-color="#FAFAFA"/>
                          <stop offset="100%" stop-color="#DEE2E6"/>
                        </linearGradient>
                      </defs>
                      <rect width="256" height="256" rx="60" fill="url(#bg)" stroke="#FFFFFF" stroke-opacity="0.18" stroke-width="6"/>
                      <path d="M128 184 Q90 171 51 187 L51 84 Q90 69 128 84 Z" fill="url(#pg)"/>
                      <path d="M128 184 Q166 171 205 187 L205 84 Q166 69 128 84 Z" fill="url(#pg)"/>
                      <line x1="128" y1="79" x2="128" y2="187" stroke="#8A8D98" stroke-width="9" stroke-linecap="round"/>
                      <path d="M113 64 L143 64 L143 141 L128 123 L113 141 Z" fill="#EF4444"/>
                    </svg>
                    """.trimIndent(),
                )

                val mainIconFile = java.io.File(hicolor, "256x256/apps/mihon-linux.png")
                val appsDir = java.io.File(home, ".local/share/applications").apply { mkdirs() }
                val launcherBin = java.io.File(home, ".local/bin/mihon-linux")
                val execPath = if (launcherBin.exists()) launcherBin.absolutePath else "mihon-linux"

                val desktopContent = """
                    [Desktop Entry]
                    Type=Application
                    Name=Mihon Linux
                    Comment=Leitor de mangá (CBZ, ZIP e pastas)
                    Exec=$execPath
                    Icon=${mainIconFile.absolutePath}
                    Categories=Graphics;Viewer;
                    Terminal=false
                    StartupNotify=true
                    StartupWMClass=mihon-linux
                """.trimIndent() + "\n"

                java.io.File(appsDir, "mihon-linux.desktop").writeText(desktopContent)

                // Secondary .desktop entry with NoDisplay=true for default JVM StartupWMClass on Wayland
                val waylandFallbackContent = """
                    [Desktop Entry]
                    Type=Application
                    Name=Mihon Linux
                    Exec=$execPath
                    Icon=${mainIconFile.absolutePath}
                    Terminal=false
                    NoDisplay=true
                    StartupWMClass=mihon.desktop.MainKt
                """.trimIndent() + "\n"
                java.io.File(appsDir, "mihon.desktop.MainKt.desktop").writeText(waylandFallbackContent)

                runCatching {
                    ProcessBuilder("gtk-update-icon-cache", "-f", "-t", hicolor.absolutePath)
                        .start()
                        .waitFor()
                }
            }
        }, "mihon-desktop-icon-installer").apply { isDaemon = true }.start()
    }
}
