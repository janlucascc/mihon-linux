package mihon.desktop

import org.junit.jupiter.api.Test
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.JScrollPane
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoScrollTest {

    private fun createMockScrollPane(contentHeight: Int = 2000, viewportHeight: Int = 500): JScrollPane {
        val view = JPanel().apply {
            preferredSize = Dimension(400, contentHeight)
            size = Dimension(400, contentHeight)
        }
        return JScrollPane(view).apply {
            size = Dimension(400, viewportHeight)
            viewport.extentSize = Dimension(400, viewportHeight)
            verticalScrollBar.minimum = 0
            verticalScrollBar.maximum = contentHeight
            verticalScrollBar.visibleAmount = viewportHeight
            verticalScrollBar.value = 0
        }
    }

    private fun createController(
        scroll: JScrollPane = createMockScrollPane(),
        onStateChanged: (AutoScrollState, Int) -> Unit = { _, _ -> },
        onReachedEnd: () -> Unit = {},
    ): AutoScrollController {
        return AutoScrollController(scroll, onStateChanged, onReachedEnd).apply {
            timerEnabled = false
        }
    }

    @Test
    fun testSpeedFormulaForLevels() {
        // Verify formula: v_base(L) = 14.0 * L^1.15
        for (lvl in 1..10) {
            val expected = 14.0 * lvl.toDouble().pow(1.15)
            val actual = AutoScrollController.calculateBaseSpeed(lvl)
            assertEquals(expected, actual, 0.0001, "Speed calculation mismatch for level $lvl")
        }

        // Key benchmark levels from specification
        val speed1 = AutoScrollController.calculateBaseSpeed(1)
        val speed3 = AutoScrollController.calculateBaseSpeed(3)
        val speed5 = AutoScrollController.calculateBaseSpeed(5)
        val speed10 = AutoScrollController.calculateBaseSpeed(10)

        // Level 1: ~14 px/s
        assertEquals(14.0, speed1, 0.01)
        // Level 3: ~49.65 px/s (~50 px/s)
        assertTrue(speed3 in 49.0..51.0, "Level 3 speed ($speed3) should be ~50 px/s")
        // Level 5: ~89.0..91.0 px/s
        assertTrue(speed5 in 89.0..91.0, "Level 5 speed ($speed5) should be ~90 px/s")
        // Level 10: ~196.0..200.0 px/s (~200 px/s)
        assertTrue(speed10 in 196.0..200.0, "Level 10 speed ($speed10) should be ~200 px/s")
    }

    @Test
    fun testSpeedLevelClamping() {
        val controller = createController()

        assertEquals(3, controller.speedLevel) // Default level is 3

        // Decrement below 1 clamps to 1
        controller.adjustSpeed(-10)
        assertEquals(1, controller.speedLevel)
        assertEquals(14.0, AutoScrollController.calculateBaseSpeed(controller.speedLevel), 0.01)

        // Increment above 10 clamps to 10
        controller.adjustSpeed(20)
        assertEquals(10, controller.speedLevel)

        // setSpeed direct bounds checking
        controller.setSpeed(-5)
        assertEquals(1, controller.speedLevel)

        controller.setSpeed(99)
        assertEquals(10, controller.speedLevel)
    }

    @Test
    fun testStateTransitions() {
        var lastState: AutoScrollState? = null
        var lastSpeed: Int? = null

        val controller = createController(
            onStateChanged = { state, speed ->
                lastState = state
                lastSpeed = speed
            },
        )

        assertEquals(AutoScrollState.STOPPED, controller.state)

        // Start
        controller.start()
        assertEquals(AutoScrollState.RUNNING, controller.state)
        assertEquals(AutoScrollState.RUNNING, lastState)
        assertEquals(3, lastSpeed)

        // Manual pause
        controller.pause(manual = true)
        assertEquals(AutoScrollState.PAUSED_MANUAL, controller.state)
        assertEquals(AutoScrollState.PAUSED_MANUAL, lastState)

        // Toggle from PAUSED_MANUAL -> RUNNING
        controller.toggle()
        assertEquals(AutoScrollState.RUNNING, controller.state)

        // Toggle from RUNNING -> PAUSED_MANUAL
        controller.toggle()
        assertEquals(AutoScrollState.PAUSED_MANUAL, controller.state)

        // Stop
        controller.stop()
        assertEquals(AutoScrollState.STOPPED, controller.state)
        assertEquals(AutoScrollState.STOPPED, lastState)

        // Toggle from STOPPED -> RUNNING
        controller.toggle()
        assertEquals(AutoScrollState.RUNNING, controller.state)
        controller.stop()
    }

    @Test
    fun testHoverPauseAndResume() {
        val controller = createController()

        controller.start()
        assertEquals(AutoScrollState.RUNNING, controller.state)

        // Mouse hover enters reading area
        controller.pauseHover()
        assertEquals(AutoScrollState.PAUSED_HOVER, controller.state)

        // Mouse becomes idle -> resumes
        controller.resumeHover()
        assertEquals(AutoScrollState.RUNNING, controller.state)

        // If manually paused, hover resume should NOT resume reading
        controller.pause(manual = true)
        assertEquals(AutoScrollState.PAUSED_MANUAL, controller.state)
        controller.resumeHover()
        assertEquals(AutoScrollState.PAUSED_MANUAL, controller.state) // Remains paused
        controller.stop()
    }

    @Test
    fun testSubPixelQuantizationAccumulator() {
        val scroll = createMockScrollPane(contentHeight = 5000, viewportHeight = 500)
        val controller = createController(scroll)
        controller.setSpeed(1) // 14.0 px/s => ~0.2333 px/frame at 60 FPS
        controller.start()

        val bar = scroll.verticalScrollBar
        assertEquals(0, bar.value)

        // Tick 1: 14/60 = 0.2333. Accumulator = 0.2333, step = 0, bar remains 0
        controller.tick(1.0 / 60.0)
        assertEquals(0, bar.value)
        assertEquals(14.0 / 60.0, controller.subPixelAccumulator, 0.001)

        // Tick 2: Accumulator = 0.4666, step = 0
        controller.tick(1.0 / 60.0)
        assertEquals(0, bar.value)

        // Tick 3: Accumulator = 0.7000, step = 0
        controller.tick(1.0 / 60.0)
        assertEquals(0, bar.value)

        // Tick 4: Accumulator = 0.9333, step = 0
        controller.tick(1.0 / 60.0)
        assertEquals(0, bar.value)

        // Tick 5: Accumulator = 1.1666, step = 1, bar becomes 1, remainder = 0.1666
        controller.tick(1.0 / 60.0)
        assertEquals(1, bar.value)
        assertEquals(0.1666, controller.subPixelAccumulator, 0.01)

        // Run full 60 frames (1 second total at 60 FPS)
        // We already did 5 frames; do remaining 55 frames
        repeat(55) {
            controller.tick(1.0 / 60.0)
        }

        // In 1 second at 14 px/s, total scrolled distance must equal exactly 14 pixels
        assertEquals(14, bar.value, "After 60 ticks (1s) at Level 1, bar value should be exactly 14px")
        controller.stop()
    }

    @Test
    fun testKineticImpulseAndFrictionDamping() {
        val scroll = createMockScrollPane(contentHeight = 5000, viewportHeight = 500)
        val controller = createController(scroll)
        controller.setSpeed(1)
        controller.start()

        val initialImpulse = 20.0
        controller.addImpulse(initialImpulse)
        assertEquals(initialImpulse, controller.kineticVelocity, 0.0001)

        // On first tick, kineticVelocity should be dampened by factor 0.88
        controller.tick(1.0 / 60.0)
        val expectedVelocity1 = initialImpulse * AutoScrollController.FRICTION_DAMPING
        assertEquals(expectedVelocity1, controller.kineticVelocity, 0.0001)

        // On second tick:
        controller.tick(1.0 / 60.0)
        val expectedVelocity2 = expectedVelocity1 * AutoScrollController.FRICTION_DAMPING
        assertEquals(expectedVelocity2, controller.kineticVelocity, 0.0001)

        // Run until kinetic velocity completely dampens out (< 0.05 -> 0.0)
        repeat(60) {
            controller.tick(1.0 / 60.0)
        }
        assertEquals(0.0, controller.kineticVelocity, 0.0001)
        controller.stop()
    }

    @Test
    fun testChapterEndBoundaryReached() {
        val contentHeight = 1000
        val viewportHeight = 400
        val maxScroll = contentHeight - viewportHeight // 600
        val scroll = createMockScrollPane(contentHeight = contentHeight, viewportHeight = viewportHeight)

        var reachedEnd = false
        val controller = createController(
            scroll = scroll,
            onReachedEnd = { reachedEnd = true },
        )
        controller.setSpeed(10) // Fast speed: ~3.29 px/tick
        controller.start()

        // Place scrollbar near end (598px of 600px max) so next tick (598 + 3 = 601 >= 600) reaches end
        scroll.verticalScrollBar.value = 598

        // Tick to advance scroll beyond maxScroll
        controller.tick(1.0 / 60.0)

        // Should stop and fire onReachedEnd
        assertEquals(AutoScrollState.STOPPED, controller.state)
        assertTrue(reachedEnd, "onReachedEnd should be triggered when scrollbar reaches maximum")
        assertEquals(maxScroll, scroll.verticalScrollBar.value)
    }

    @Test
    fun testZeroMaxValSafelyHandled() {
        // Content fits entirely within viewport -> maxVal is 0
        val scroll = createMockScrollPane(contentHeight = 400, viewportHeight = 500)
        val controller = createController(scroll)
        controller.start()

        // Tick should not crash or scroll into negative space
        controller.tick(1.0 / 60.0)
        assertEquals(0, scroll.verticalScrollBar.value)
        controller.stop()
    }

    @Test
    fun testStopResetsPhysicsState() {
        val controller = createController()
        controller.start()
        controller.addImpulse(45.0)
        controller.tick(1.0 / 60.0)

        assertTrue(controller.kineticVelocity > 0)
        assertTrue(controller.subPixelAccumulator > 0)

        controller.stop()
        assertEquals(AutoScrollState.STOPPED, controller.state)
        assertEquals(0.0, controller.kineticVelocity)
        assertEquals(0.0, controller.subPixelAccumulator)
    }
}
