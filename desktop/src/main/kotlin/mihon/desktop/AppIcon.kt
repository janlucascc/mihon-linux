package mihon.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.ImageIcon

/**
 * Procedural vector icon generator for Mihon Linux:
 * Renders the indigo rounded-squircle open book mark with dual-tone leaves and cyan completion badge.
 */
object AppIcon {

    private const val ICON_VERSION = "v2-indigo-book"

    val CANONICAL_SVG: String = """
        <svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="0 0 128 128">
          <rect x="8" y="8" width="112" height="112" rx="26" fill="#5B5BD6"/>
          <path d="M28 38c0-5 4-9 9-9h24c7 0 12 5 12 12v52c-6-5-12-7-20-7H28V38z" fill="#F7F7FA"/>
          <path d="M100 38c0-5-4-9-9-9H67c-7 0-12 5-12 12v52c6-5 12-7 20-7h25V38z" fill="#E8E9FF"/>
          <path d="M64 41v45" fill="none" stroke="#5B5BD6" stroke-width="5" stroke-linecap="round"/>
          <path d="M37 46h18M37 58h18M73 46h18M73 58h18" fill="none" stroke="#8D8DE8" stroke-width="4" stroke-linecap="round"/>
          <circle cx="94" cy="94" r="18" fill="#36C5F0"/>
          <path d="M86 94l6 6 11-13" fill="none" stroke="#FFFFFF" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
    """.trimIndent() + "\n"

    fun createImage(size: Int): BufferedImage {
        val safeSize = size.coerceAtLeast(1)
        val img = BufferedImage(safeSize, safeSize, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val scale = safeSize / 128.0
        g2.scale(scale, scale)

        // Rounded squircle container (#5B5BD6, rx=26 -> arc diameter 52 in 128x128 viewBox)
        g2.color = Color(0x5B, 0x5B, 0xD6)
        g2.fill(RoundRectangle2D.Float(8f, 8f, 112f, 112f, 52f, 52f))

        // Left page (#F7F7FA)
        val leftPage = Path2D.Float().apply {
            moveTo(28f, 38f)
            curveTo(28f, 33f, 32f, 29f, 37f, 29f)
            lineTo(61f, 29f)
            curveTo(68f, 29f, 73f, 34f, 73f, 41f)
            lineTo(73f, 93f)
            curveTo(67f, 88f, 61f, 86f, 53f, 86f)
            lineTo(28f, 86f)
            closePath()
        }
        g2.color = Color(0xF7, 0xF7, 0xFA)
        g2.fill(leftPage)

        // Right page (#E8E9FF)
        val rightPage = Path2D.Float().apply {
            moveTo(100f, 38f)
            curveTo(100f, 33f, 96f, 29f, 91f, 29f)
            lineTo(67f, 29f)
            curveTo(60f, 29f, 55f, 34f, 55f, 41f)
            lineTo(55f, 93f)
            curveTo(61f, 88f, 67f, 86f, 75f, 86f)
            lineTo(100f, 86f)
            closePath()
        }
        g2.color = Color(0xE8, 0xE9, 0xFF)
        g2.fill(rightPage)

        // Center spine (#5B5BD6)
        g2.color = Color(0x5B, 0x5B, 0xD6)
        g2.stroke = BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(Line2D.Float(64f, 41f, 64f, 86f))

        // Page line rules (#8D8DE8)
        g2.color = Color(0x8D, 0x8D, 0xE8)
        g2.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(Line2D.Float(37f, 46f, 55f, 46f))
        g2.draw(Line2D.Float(37f, 58f, 55f, 58f))
        g2.draw(Line2D.Float(73f, 46f, 91f, 46f))
        g2.draw(Line2D.Float(73f, 58f, 91f, 58f))

        // Cyan completion badge (#36C5F0)
        g2.color = Color(0x36, 0xC5, 0xF0)
        g2.fill(Ellipse2D.Float(76f, 76f, 36f, 36f))

        // White checkmark (#FFFFFF)
        val check = Path2D.Float().apply {
            moveTo(86f, 94f)
            lineTo(92f, 100f)
            lineTo(103f, 87f)
        }
        g2.color = Color.WHITE
        g2.stroke = BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(check)

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
                val versionMarker = java.io.File(hicolor, ".mihon-linux-icon-version")
                val needsRefresh = !versionMarker.exists() ||
                    runCatching { versionMarker.readText().trim() != ICON_VERSION }.getOrDefault(true)
                val sizes = listOf(16, 24, 32, 48, 64, 128, 256, 512)
                for (sz in sizes) {
                    val dir = java.io.File(hicolor, "${sz}x${sz}/apps")
                    if (!dir.exists()) dir.mkdirs()
                    val rendered by lazy { createImage(sz) }
                    val pngFile = java.io.File(dir, "mihon-linux.png")
                    if (needsRefresh || !pngFile.exists() || pngFile.length() < 100L) {
                        javax.imageio.ImageIO.write(rendered, "PNG", pngFile)
                    }
                    // Also write alias matching StartupWMClass for Wayland compositors
                    val wmClassPng = java.io.File(dir, "mihon-desktop-MainKt.png")
                    if (needsRefresh || !wmClassPng.exists() || wmClassPng.length() < 100L) {
                        javax.imageio.ImageIO.write(rendered, "PNG", wmClassPng)
                    }
                }

                val scalableDir = java.io.File(hicolor, "scalable/apps").apply { mkdirs() }
                val svgFile = java.io.File(scalableDir, "mihon-linux.svg")
                svgFile.writeText(CANONICAL_SVG)
                if (needsRefresh) {
                    runCatching { versionMarker.writeText(ICON_VERSION) }
                }

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
