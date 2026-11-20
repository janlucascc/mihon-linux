package mihon.desktop.ui.components

import mihon.desktop.ui.animation.MotionPreferences
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Arc2D
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.Timer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Expressive 60 FPS animated emoji badge for Incognito Mode:
 * - ON (`incognito = true`): 3D-shaded emoji tilts slightly and raises an articulated hand over its face/eyes.
 * - OFF (`incognito = false`): hand lowers away and the emoji holds up a glossy magnifying glass (lupa) with a lens gleam.
 */
class IncognitoEmojiBadge(
    initialIncognito: Boolean = false,
    private val sizePx: Int = 42,
) : JComponent() {

    private var incognitoState: Boolean = initialIncognito
    private var progress: Double = if (initialIncognito) 1.0 else 0.0
    private var targetProgress: Double = progress
    private var velocity: Double = 0.0

    // Specular gleam sweep across the magnifying glass when switching OFF
    private var gleamProgress: Double = 1.0
    private var lastTickNanos: Long = 0L

    private val animTimer = Timer(16) { onTick() }.apply { isCoalesce = true }

    init {
        isOpaque = false
        val dim = Dimension(sizePx, sizePx)
        preferredSize = dim
        minimumSize = dim
        maximumSize = dim
    }

    var isIncognito: Boolean
        get() = incognitoState
        set(value) {
            if (incognitoState == value) return
            incognitoState = value
            targetProgress = if (value) 1.0 else 0.0
            if (!value) {
                gleamProgress = 0.0
            }
            if (!MotionPreferences.isEnabled || !isShowing) {
                progress = targetProgress
                velocity = 0.0
                gleamProgress = 1.0
                animTimer.stop()
                repaint()
                return
            }
            lastTickNanos = System.nanoTime()
            if (!animTimer.isRunning) {
                animTimer.start()
            }
        }

    private fun onTick() {
        val now = System.nanoTime()
        val dt = ((now - lastTickNanos) / 1_000_000_000.0).coerceIn(0.005, 0.032)
        lastTickNanos = now

        // Expressive organic spring (slight natural overshoot)
        val stiffness = 195.0
        val damping = 15.5
        val force = -stiffness * (progress - targetProgress) - damping * velocity
        velocity += force * dt
        progress += velocity * dt

        val springSettled = abs(progress - targetProgress) < 0.0018 && abs(velocity) < 0.008
        if (springSettled) {
            progress = targetProgress
            velocity = 0.0
        }

        // Trigger lens gleam as magnifying glass arrives in OFF state
        var gleaming = false
        if (!incognitoState && progress < 0.25 && gleamProgress < 1.0) {
            gleamProgress = (gleamProgress + dt * 2.4).coerceAtMost(1.0)
            gleaming = gleamProgress < 1.0
        }

        repaint()

        if (springSettled && !gleaming) {
            animTimer.stop()
        }
    }

    override fun removeNotify() {
        animTimer.stop()
        super.removeNotify()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

        val scale = minOf(width, height) / 44.0
        g2.scale(scale, scale)

        val t = progress.coerceIn(-0.08, 1.12)
        val tClamped = progress.coerceIn(0.0, 1.0)

        // Subtle head tilt & organic breathing bounce
        val headTiltRad = Math.toRadians(4.5 * (1.0 - tClamped) - 6.5 * tClamped)
        val headBobY = -1.2 * sin(tClamped * PI)

        // 1. Soft ground shadow
        g2.color = Color(0, 0, 0, 58)
        g2.fill(Ellipse2D.Double(8.0, 36.5, 26.0, 5.2))

        // 2. Head group (tilted subtly for personality)
        val headG = g2.create() as Graphics2D
        headG.translate(21.0, 21.5 + headBobY)
        headG.rotate(headTiltRad)
        headG.translate(-21.0, -21.5)

        drawEmojiHead(headG, tClamped)
        drawFaceFeatures(headG, tClamped)

        // 3. Hand covering face (rises cleanly from t = 0.18 -> 1.0)
        val handRaw = ((t - 0.16) / 0.84).coerceIn(0.0, 1.12)
        if (handRaw > 0.01) {
            drawCoveringHand(headG, handRaw)
        }

        headG.dispose()

        // 4. Magnifying glass / Lupa (appears cleanly when t = 0.55 -> 0.0)
        val lupaRaw = ((0.58 - t) / 0.58).coerceIn(0.0, 1.10)
        if (lupaRaw > 0.01) {
            drawMagnifyingGlass(g2, lupaRaw, gleamProgress)
        }

        g2.dispose()
    }

    private fun drawEmojiHead(g2: Graphics2D, t: Double) {
        val cx = 21.0
        val cy = 21.0
        val r = 16.0

        // 3D spherical radial gradient
        val faceGrad = RadialGradientPaint(
            Point2D.Double(cx - 5.2, cy - 5.8),
            (r * 1.48).toFloat(),
            floatArrayOf(0.0f, 0.45f, 0.82f, 1.0f),
            arrayOf(
                Color(0xFF, 0xF3, 0x8C),
                Color(0xFF, 0xCC, 0x33),
                Color(0xF2, 0xA6, 0x12),
                Color(0xD4, 0x7E, 0x00),
            ),
        )
        g2.paint = faceGrad
        g2.fill(Ellipse2D.Double(cx - r, cy - r, r * 2.0, r * 2.0))

        // Subtle glossy top-left specular crescent
        val gloss = GradientPaint(
            (cx - 8).toFloat(),
            (cy - 14).toFloat(),
            Color(255, 255, 255, 115),
            (cx - 2).toFloat(),
            (cy - 4).toFloat(),
            Color(255, 255, 255, 0),
        )
        g2.paint = gloss
        g2.fill(Ellipse2D.Double(cx - 10.5, cy - 14.0, 15.0, 8.5))

        // Rosy cheeks (slightly warmer when shy/incognito)
        val cheekAlpha = (55 + 45 * t).toInt().coerceIn(0, 110)
        g2.color = Color(255, 105, 92, cheekAlpha)
        g2.fill(Ellipse2D.Double(cx - 12.2, cy + 2.0, 6.2, 4.0))
        g2.fill(Ellipse2D.Double(cx + 6.0, cy + 2.0, 6.2, 4.0))

        // Crisp warm rim stroke
        g2.color = Color(0xB8, 0x6B, 0x00, 140)
        g2.stroke = BasicStroke(1.0f)
        g2.draw(Ellipse2D.Double(cx - r, cy - r, r * 2.0, r * 2.0))
    }

    private fun drawFaceFeatures(g2: Graphics2D, t: Double) {
        val leftEyeX = 15.2
        val rightEyeX = 26.8
        val eyeY = 18.8

        // Right eye magnifies subtly when inspected through the magnifying glass (t near 0)
        val rightScale = 1.0 + 0.22 * (1.0 - t)

        // Left eye: open curious eye when OFF, gently squinted/shy when ON
        drawEye(g2, leftEyeX, eyeY, scale = 1.0, squint = t * 0.85, lookX = 0.6 * (1.0 - t), lookY = -0.3 * (1.0 - t))
        // Right eye: open curious (and magnified) when OFF, tiny playful peek when ON
        drawEye(g2, rightEyeX, eyeY, scale = rightScale, squint = t * 0.35, lookX = 0.4 * (1.0 - t) - 0.5 * t, lookY = 0.0)

        // Eyebrows: inquisitive raised right brow when OFF, softened worried/shy brows when ON
        g2.color = Color(0x6B, 0x3D, 0x0A)
        g2.stroke = BasicStroke(1.55f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

        val leftBrow = Path2D.Double()
        leftBrow.moveTo(11.6, 13.8 + 0.8 * t)
        leftBrow.quadTo(15.0, 12.0 + 0.5 * t, 18.0, 13.4 - 0.4 * t)
        g2.draw(leftBrow)

        val rightBrowLift = -1.6 * (1.0 - t)
        val rightBrow = Path2D.Double()
        rightBrow.moveTo(23.8, 13.2 + rightBrowLift * 0.4)
        rightBrow.quadTo(27.0, 11.4 + rightBrowLift, 30.2, 13.4 + rightBrowLift * 0.3)
        g2.draw(rightBrow)

        // Mouth: curious smile when OFF -> tiny shy "hmm" curve when ON
        g2.color = Color(0x5E, 0x31, 0x08)
        g2.stroke = BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val mouth = Path2D.Double()
        val mouthW = 4.6 - 1.4 * t
        val mouthY = 27.6 + 0.8 * t
        val curveY = 29.9 - 1.9 * t
        mouth.moveTo(21.0 - mouthW, mouthY)
        mouth.quadTo(21.0, curveY, 21.0 + mouthW, mouthY)
        g2.draw(mouth)
    }

    private fun drawEye(
        g2: Graphics2D,
        cx: Double,
        cy: Double,
        scale: Double,
        squint: Double,
        lookX: Double,
        lookY: Double,
    ) {
        val eg = g2.create() as Graphics2D
        eg.translate(cx, cy)
        eg.scale(scale, scale)

        if (squint > 0.72) {
            // Soft closed/shy curved eyelid
            eg.color = Color(0x4A, 0x28, 0x06)
            eg.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            eg.draw(Arc2D.Double(-3.2, -1.6, 6.4, 4.4, 15.0, 150.0, Arc2D.OPEN))
        } else {
            val openH = (5.6 * (1.0 - squint * 0.42)).coerceAtLeast(2.6)
            // White sclera
            eg.color = Color.WHITE
            eg.fill(Ellipse2D.Double(-3.2, -openH / 2.0, 6.4, openH))

            // Dark iris/pupil
            val pr = 2.0
            eg.color = Color(0x28, 0x17, 0x0A)
            eg.fill(Ellipse2D.Double(-pr + lookX, -pr + lookY, pr * 2.0, pr * 2.0))

            // Specular white catchlight
            eg.color = Color.WHITE
            eg.fill(Ellipse2D.Double(-0.9 + lookX, -1.3 + lookY, 1.25, 1.25))

            // Subtle eyelid outline
            eg.color = Color(0x6B, 0x3D, 0x0A, 150)
            eg.stroke = BasicStroke(0.85f)
            eg.draw(Ellipse2D.Double(-3.2, -openH / 2.0, 6.4, openH))
        }
        eg.dispose()
    }

    private fun buildNaturalHandSilhouette(): Area {
        // Unified organic emoji hand (plump rounded palm + curved thumb + 4 soft tapered fingers, zero boxy seams)
        val hand = Area()

        // 1. Plump, softly rounded palm (oval base, narrower at wrist, fuller at knuckles)
        val palm = Path2D.Double().apply {
            moveTo(-9.2, 2.0)
            curveTo(-9.8, -2.6, -6.4, -4.6, 0.2, -4.6)
            curveTo(6.8, -4.6, 9.6, -2.0, 9.4, 2.8)
            curveTo(9.2, 8.2, 5.4, 11.4, -0.4, 11.4)
            curveTo(-6.2, 11.4, -8.8, 7.6, -9.2, 2.0)
            closePath()
        }
        hand.add(Area(palm))

        // 2. Curved organic thumb emerging smoothly from the lower-left palm
        val thumb = Path2D.Double().apply {
            moveTo(-7.2, 7.8)
            curveTo(-10.8, 6.2, -13.2, 2.8, -12.8, 0.0)
            curveTo(-12.4, -2.2, -10.2, -2.5, -8.6, -0.6)
            curveTo(-7.4, 0.8, -6.2, 2.2, -5.0, 3.2)
            closePath()
        }
        hand.add(Area(thumb))

        // 3. Four plump, naturally proportioned rounded fingers (pinky -> index, gently fanned)
        fun addFinger(cx: Double, baseY: Double, width: Double, length: Double, angleDeg: Double) {
            val rx = width / 2.0
            val finger = Path2D.Double().apply {
                // Slightly narrower base, plump rounded tip
                moveTo(-rx * 0.92, 0.5)
                curveTo(-rx * 1.05, -length * 0.45, -rx * 1.02, -length + rx, 0.0, -length)
                curveTo(rx * 1.02, -length + rx, rx * 1.05, -length * 0.45, rx * 0.92, 0.5)
                closePath()
            }
            val tx = AffineTransform.getTranslateInstance(cx, baseY)
            tx.rotate(Math.toRadians(angleDeg))
            hand.add(Area(tx.createTransformedShape(finger)))
        }

        // Index, middle (tallest), ring, pinky (shortest) — gentle organic curve across fingertips
        addFinger(cx = -6.4, baseY = -1.2, width = 4.3, length = 11.6, angleDeg = -8.5)
        addFinger(cx = -2.1, baseY = -2.2, width = 4.5, length = 13.0, angleDeg = -2.0)
        addFinger(cx = 2.6, baseY = -1.9, width = 4.4, length = 12.2, angleDeg = 4.5)
        addFinger(cx = 6.7, baseY = -0.6, width = 3.9, length = 9.8, angleDeg = 11.0)

        return hand
    }

    private val cachedHandArea: Area by lazy { buildNaturalHandSilhouette() }

    private fun drawCoveringHand(g2: Graphics2D, handP: Double) {
        val clamped = handP.coerceIn(0.0, 1.0)
        // Natural upward arc with gentle shy tilt
        val dy = (1.0 - handP) * 23.0
        val dx = (1.0 - handP) * -4.5
        val rotRad = Math.toRadians((1.0 - handP) * -28.0 - 9.0)

        val handShape = cachedHandArea

        // Soft cast shadow from the organic hand silhouette onto the emoji face
        val shadowAlpha = (64 * clamped).toInt().coerceIn(0, 68)
        if (shadowAlpha > 4) {
            val shG = g2.create() as Graphics2D
            shG.translate(21.2 + dx, 23.8 + dy + 2.0)
            shG.rotate(rotRad)
            shG.color = Color(78, 36, 0, shadowAlpha)
            shG.fill(handShape)
            shG.dispose()
        }

        val hg = g2.create() as Graphics2D
        hg.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (clamped * 1.4).coerceIn(0.0, 1.0).toFloat())
        hg.translate(21.0 + dx, 23.4 + dy)
        hg.rotate(rotRad)

        // Warm 3D radial shading across the unified hand
        val handGrad = RadialGradientPaint(
            Point2D.Double(-2.5, -5.5),
            18.5f,
            floatArrayOf(0.0f, 0.52f, 0.85f, 1.0f),
            arrayOf(
                Color(0xFF, 0xF1, 0x8A),
                Color(0xFF, 0xCF, 0x3F),
                Color(0xF0, 0xA2, 0x10),
                Color(0xD6, 0x80, 0x00),
            ),
        )
        hg.paint = handGrad
        hg.fill(handShape)

        // Subtle inner gloss on upper palm (soft plump volume)
        val prevClip = hg.clip
        hg.clip(handShape)
        hg.color = Color(255, 255, 255, 72)
        hg.fill(Ellipse2D.Double(-7.5, -3.0, 12.5, 6.2))

        // Delicate finger-valley separation lines (short organic creases, no blocky borders)
        hg.color = Color(0xB8, 0x6B, 0x00, 165)
        hg.stroke = BasicStroke(1.05f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        hg.draw(Line2D.Double(-4.4, -3.8, -4.6, -1.6))
        hg.draw(Line2D.Double(0.2, -4.3, 0.2, -2.0))
        hg.draw(Line2D.Double(4.7, -3.5, 4.4, -1.3))

        // Soft curved thumb-pad crease for natural palm anatomy
        hg.color = Color(0xB8, 0x6B, 0x00, 130)
        hg.stroke = BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        hg.draw(Arc2D.Double(-9.2, 0.6, 8.6, 8.2, -55.0, 95.0, Arc2D.OPEN))
        hg.clip = prevClip

        // Single clean organic outer contour around the entire fused hand
        hg.color = Color(0xAD, 0x61, 0x00, 210)
        hg.stroke = BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        hg.draw(handShape)

        hg.dispose()
    }

    private fun drawMagnifyingGlass(g2: Graphics2D, lupaP: Double, gleam: Double) {
        val clamped = lupaP.coerceIn(0.0, 1.0)
        val lg = g2.create() as Graphics2D
        lg.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (clamped * 1.25).coerceIn(0.0, 1.0).toFloat())

        // Swing smoothly into position over the right eye from bottom-right
        val lensX = 27.6 + (1.0 - lupaP) * 14.0
        val lensY = 19.2 + (1.0 - lupaP) * 15.0
        val swingRad = Math.toRadians((1.0 - lupaP) * 42.0)

        lg.translate(lensX, lensY)
        lg.rotate(swingRad)

        val lensR = 7.4

        // Handle angled down-right (+48 deg)
        val handleG = lg.create() as Graphics2D
        handleG.rotate(Math.toRadians(48.0))

        // Metallic collar ferrule
        handleG.color = Color(0x94, 0xA3, 0xB8)
        handleG.fill(RoundRectangle2D.Double(lensR - 0.5, -1.8, 3.2, 3.6, 1.5, 1.5))

        // Ergonomic dark handle with highlight
        val handleRect = RoundRectangle2D.Double(lensR + 1.8, -2.3, 10.5, 4.6, 4.6, 4.6)
        handleG.paint = GradientPaint(
            (lensR + 2).toFloat(),
            -2.3f,
            Color(0x47, 0x55, 0x69),
            (lensR + 2).toFloat(),
            2.3f,
            Color(0x1E, 0x29, 0x3B),
        )
        handleG.fill(handleRect)
        handleG.color = Color(255, 255, 255, 70)
        handleG.stroke = BasicStroke(0.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        handleG.draw(Line2D.Double(lensR + 3.4, -1.0, lensR + 10.4, -1.0))
        handleG.color = Color(0x0F, 0x17, 0x2A, 180)
        handleG.stroke = BasicStroke(0.9f)
        handleG.draw(handleRect)
        handleG.dispose()

        // Translucent icy-blue magnifying lens
        val lensCircle = Ellipse2D.Double(-lensR, -lensR, lensR * 2.0, lensR * 2.0)
        lg.paint = GradientPaint(
            (-lensR).toFloat(),
            (-lensR).toFloat(),
            Color(219, 244, 255, 128),
            lensR.toFloat(),
            lensR.toFloat(),
            Color(125, 211, 252, 62),
        )
        lg.fill(lensCircle)

        // Static glossy lens reflection arc
        val prevClip = lg.clip
        lg.clip(lensCircle)
        lg.color = Color(255, 255, 255, 155)
        lg.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        lg.draw(Arc2D.Double(-lensR + 2.0, -lensR + 2.0, (lensR - 2.0) * 2.0, (lensR - 2.0) * 2.0, 115.0, 52.0, Arc2D.OPEN))

        // Animated diagonal gleam sweep on arrival
        if (gleam in 0.02..0.98) {
            val sweep = -lensR * 2.2 + gleam * (lensR * 4.4)
            lg.color = Color(255, 255, 255, (185 * sin(gleam * PI)).toInt().coerceIn(0, 195))
            lg.stroke = BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            lg.draw(Line2D.Double(sweep - 5.0, -lensR - 2.0, sweep + 5.0, lensR + 2.0))
        }
        lg.clip = prevClip

        // Metallic silver bezel rim
        lg.paint = GradientPaint(
            (-lensR).toFloat(),
            (-lensR).toFloat(),
            Color(0xF1, 0xF5, 0xF9),
            lensR.toFloat(),
            lensR.toFloat(),
            Color(0x64, 0x74, 0x8B),
        )
        lg.stroke = BasicStroke(2.25f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        lg.draw(lensCircle)

        // Subtle dark outer bezel edge for contrast on light and dark themes
        lg.color = Color(0x1E, 0x29, 0x3B, 145)
        lg.stroke = BasicStroke(0.75f)
        lg.draw(Ellipse2D.Double(-lensR - 1.1, -lensR - 1.1, (lensR + 1.1) * 2.0, (lensR + 1.1) * 2.0))

        lg.dispose()
    }
}
