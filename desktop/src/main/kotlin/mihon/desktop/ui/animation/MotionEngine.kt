package mihon.desktop.ui.animation

import java.awt.Color
import java.awt.Component
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.util.prefs.Preferences
import javax.swing.Timer
import kotlin.math.abs
import kotlin.math.pow

/**
 * User-configurable animation intensity preference.
 */
enum class MotionLevel(val label: String) {
    COMPLETE("Completa"),
    REDUCED("Reduzida"),
    OFF("Desligada"),
}

/**
 * Persisted global motion preference used across buttons, cards, overlays, and reader HUD.
 */
object MotionPreferences {
    private val prefs: Preferences = Preferences.userRoot().node("mihon-linux")
    private const val PREF_KEY = "motionLevel"

    @Volatile
    var current: MotionLevel = loadInitial()
        private set

    private fun loadInitial(): MotionLevel {
        val raw = runCatching { prefs.get(PREF_KEY, MotionLevel.COMPLETE.name) }.getOrNull()
        return runCatching { MotionLevel.valueOf(raw ?: MotionLevel.COMPLETE.name) }
            .getOrDefault(MotionLevel.COMPLETE)
    }

    fun setLevel(level: MotionLevel) {
        current = level
        runCatching { prefs.put(PREF_KEY, level.name) }
    }

    val isEnabled: Boolean get() = current != MotionLevel.OFF
    val isFull: Boolean get() = current == MotionLevel.COMPLETE

    fun effectiveDurationMs(requestedMs: Long): Long = when (current) {
        MotionLevel.COMPLETE -> requestedMs.coerceAtLeast(0L)
        MotionLevel.REDUCED -> (requestedMs / 2L).coerceIn(0L, 90L)
        MotionLevel.OFF -> 0L
    }

    fun effectiveDurationMs(requestedMs: Int): Long = effectiveDurationMs(requestedMs.toLong())
}

/**
 * Standard easing curves for 60 FPS desktop transitions.
 */
object MotionCurves {
    fun easeOutCubic(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        val inv = 1f - clamped
        return 1f - inv * inv * inv
    }

    fun easeOutQuint(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        val inv = 1f - clamped
        return 1f - inv * inv * inv * inv * inv
    }

    fun easeInOutQuart(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) {
            8f * x * x * x * x
        } else {
            1f - (-2f * x + 2f).pow(4) / 2f
        }
    }
}

/**
 * Frame-rate-independent time-based Swing animator using System.nanoTime().
 */
object MotionEngine {
    const val FAST_MS: Long = 120L
    const val NORMAL_MS: Long = 180L
    const val SLOW_MS: Long = 240L

    fun animate(
        from: Float,
        to: Float,
        durationMs: Long = NORMAL_MS,
        easing: (Float) -> Float = MotionCurves::easeOutCubic,
        owner: Component? = null,
        onUpdate: (Float) -> Unit,
        onComplete: (() -> Unit)? = null,
    ): AnimationHandle {
        val effMs = MotionPreferences.effectiveDurationMs(durationMs)
        if (effMs <= 0L || abs(to - from) < 0.0005f) {
            onUpdate(to)
            onComplete?.invoke()
            return AnimationHandle(null)
        }
        val startNanos = System.nanoTime()
        val durationNanos = effMs * 1_000_000.0
        lateinit var timer: Timer
        timer = Timer(12) {
            if (owner != null && !owner.isDisplayable) {
                timer.stop()
                return@Timer
            }
            val raw = ((System.nanoTime() - startNanos) / durationNanos).toFloat().coerceIn(0f, 1f)
            val value = from + (to - from) * easing(raw)
            onUpdate(value)
            if (raw >= 1f) {
                timer.stop()
                onUpdate(to)
                onComplete?.invoke()
            }
        }
        timer.isRepeats = true
        timer.start()
        return AnimationHandle(timer)
    }

    class AnimationHandle(private val timer: Timer?) {
        fun cancel() {
            timer?.stop()
        }

        val isRunning: Boolean get() = timer?.isRunning == true
    }
}

/**
 * Reusable 2nd-order damped harmonic spring for organic UI micro-interactions.
 */
class SpringPhysics(
    var value: Double = 0.0,
    var target: Double = 0.0,
    var velocity: Double = 0.0,
    val stiffness: Double = 320.0,
    val damping: Double = 24.0,
) {
    fun snapTo(newValue: Double) {
        value = newValue
        target = newValue
        velocity = 0.0
    }

    fun step(dtSeconds: Double): Boolean {
        if (!MotionPreferences.isEnabled) {
            snapTo(target)
            return false
        }
        val dt = dtSeconds.coerceIn(0.001, 0.04)
        val force = -stiffness * (value - target) - damping * velocity
        velocity += force * dt
        value += velocity * dt
        val settled = abs(value - target) < 0.0015 && abs(velocity) < 0.008
        if (settled) {
            value = target
            velocity = 0.0
        }
        return !settled
    }

    companion object {
        fun snappy(initial: Double = 0.0) = SpringPhysics(initial, initial, stiffness = 420.0, damping = 28.0)
        fun smooth(initial: Double = 0.0) = SpringPhysics(initial, initial, stiffness = 280.0, damping = 24.0)
        fun bouncy(initial: Double = 0.0) = SpringPhysics(initial, initial, stiffness = 320.0, damping = 18.0)
    }
}

/**
 * Diagonal gradient shimmer painter for loading skeletons.
 */
object SkeletonShimmerPainter {
    fun currentPhase(): Float {
        if (!MotionPreferences.isFull) return -1f
        val cycleMs = 1250L
        val elapsed = (System.nanoTime() / 1_000_000L) % cycleMs
        return elapsed.toFloat() / cycleMs.toFloat()
    }

    fun paintRect(
        g2: Graphics2D,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        arc: Float,
        baseColor: Color,
        highlightColor: Color,
    ) {
        if (w <= 0f || h <= 0f) return
        val g = g2.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val shape = RoundRectangle2D.Float(x, y, w, h, arc, arc)
        g.color = baseColor
        g.fill(shape)
        val phase = currentPhase()
        if (phase >= 0f) {
            val sweepW = (w * 0.55f).coerceAtLeast(48f)
            val startX = x - sweepW + (w + sweepW * 2f) * phase
            g.clip(shape)
            g.paint = GradientPaint(
                startX,
                y,
                Color(highlightColor.red, highlightColor.green, highlightColor.blue, 0),
                startX + sweepW * 0.5f,
                y + h * 0.35f,
                highlightColor,
                true,
            )
            g.fillRect(x.toInt(), y.toInt(), w.toInt() + 1, h.toInt() + 1)
        }
        g.dispose()
    }
}
