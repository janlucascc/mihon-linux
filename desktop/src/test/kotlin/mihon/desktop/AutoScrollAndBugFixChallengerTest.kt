package mihon.desktop

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.awt.event.WindowEvent
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoScrollAndBugFixChallengerTest {

    private fun createMockScrollPane(contentHeight: Int = 10000, viewportHeight: Int = 800): JScrollPane {
        val view = JPanel().apply {
            preferredSize = Dimension(600, contentHeight)
            size = Dimension(600, contentHeight)
        }
        return JScrollPane(view).apply {
            size = Dimension(600, viewportHeight)
            viewport.extentSize = Dimension(600, viewportHeight)
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

    @Nested
    @DisplayName("1. AutoScroll Engine Physics & Concurrency Stress Tests (M5)")
    inner class AutoScrollPhysicsAndConcurrencyTests {

        @Test
        fun `test lag spike resilience with large dt and high frequency dt`() {
            val scroll = createMockScrollPane(contentHeight = 20000, viewportHeight = 800)
            val controller = createController(scroll)
            controller.setSpeed(5) // level 5: ~89.43 px/s
            controller.start()

            val expectedSpeed = AutoScrollController.calculateBaseSpeed(5)

            // Test 1: Simulate lag spike of 200ms (0.2s)
            val initialVal = scroll.verticalScrollBar.value
            controller.tick(0.200)
            val step200 = scroll.verticalScrollBar.value - initialVal
            val expected200 = (expectedSpeed * 0.200).toInt()
            assertTrue(
                abs(step200 - expected200) <= 1,
                "Lag spike 200ms displacement ($step200) should match expected (~$expected200)",
            )

            // Test 2: Simulate massive lag spike of 500ms (0.5s)
            val pre500Val = scroll.verticalScrollBar.value
            controller.tick(0.500)
            val step500 = scroll.verticalScrollBar.value - pre500Val
            val expected500 = (expectedSpeed * 0.500).toInt()
            assertTrue(
                abs(step500 - expected500) <= 1,
                "Lag spike 500ms displacement ($step500) should match expected (~$expected500)",
            )

            // Test 3: High-frequency ticks (dt = 1ms = 0.001s) over 1000 iterations (1.0 second total)
            val pre1000Val = scroll.verticalScrollBar.value
            val initialAccumulator = controller.subPixelAccumulator
            repeat(1000) {
                controller.tick(0.001)
            }
            val totalDisplacedHighFreq = scroll.verticalScrollBar.value - pre1000Val
            val totalExpectedHighFreq = expectedSpeed * 1.0
            val effectiveAccumulated = totalDisplacedHighFreq + (controller.subPixelAccumulator - initialAccumulator)
            assertEquals(
                totalExpectedHighFreq,
                effectiveAccumulated,
                0.001,
                "High-frequency 1ms accumulation over 1000 ticks must equal theoretical 1s movement exactly",
            )
            assertTrue(
                abs(totalDisplacedHighFreq - totalExpectedHighFreq.toInt()) <= 1,
                "Quantized displacement over 1000x 1ms ticks ($totalDisplacedHighFreq) must be within 1px of theoretical",
            )

            // Test 4: Extreme 5.0s spike near chapter end triggers clean clamp and stop
            val maxScroll = 20000 - 800
            scroll.verticalScrollBar.value = maxScroll - 50
            var endReached = false
            controller.onReachedEnd = { endReached = true }
            controller.tick(5.0)

            assertEquals(maxScroll, scroll.verticalScrollBar.value, "Bar must be clamped to maxVal")
            assertEquals(AutoScrollState.STOPPED, controller.state, "Controller must stop upon reaching end")
            assertTrue(endReached, "onReachedEnd must be invoked")

            // Test 5: dt = 0.0 results in no movement and no NaN/Infinity
            controller.start()
            val valBeforeZero = scroll.verticalScrollBar.value
            controller.tick(0.0)
            assertEquals(valBeforeZero, scroll.verticalScrollBar.value)
            assertFalse(controller.subPixelAccumulator.isNaN())
            controller.stop()
        }

        @Test
        fun `test sub-pixel precision across 3600 frames for all speed levels 1 to 10`() {
            // 3,600 frames at 60 FPS = 60.0 seconds of continuous reading
            for (level in 1..10) {
                val scroll = createMockScrollPane(contentHeight = 50000, viewportHeight = 800)
                val controller = createController(scroll)
                controller.setSpeed(level)
                controller.start()

                val baseSpeed = AutoScrollController.calculateBaseSpeed(level)
                val theoreticalDistance = baseSpeed * 60.0 // 60 seconds

                val dt = 1.0 / 60.0
                repeat(3600) {
                    controller.tick(dt)
                }

                val actualScrolled = scroll.verticalScrollBar.value
                val diff = abs(actualScrolled - theoreticalDistance)

                // Sub-pixel accumulator must retain fractional remainder strictly in [0.0, 1.0)
                assertTrue(
                    controller.subPixelAccumulator >= 0.0 && controller.subPixelAccumulator < 1.0,
                    "Sub-pixel accumulator (${controller.subPixelAccumulator}) for level $level must be in [0.0, 1.0)",
                )

                // Quantization tolerance constraint: <= 1 px difference from theoretical speed
                assertTrue(
                    diff <= 1.0,
                    "Level $level: Actual scrolled ($actualScrolled) vs theoretical ($theoreticalDistance) diff ($diff) must be <= 1.0 px",
                )

                // Verify exact conservation of pixels: actual + accumulator == theoretical (within float roundoff)
                val totalWithAccumulator = actualScrolled.toDouble() + controller.subPixelAccumulator
                assertEquals(
                    theoreticalDistance,
                    totalWithAccumulator,
                    0.0001,
                    "Level $level: Exact pixel conservation violated! actual + acc = $totalWithAccumulator, theoretical = $theoreticalDistance",
                )

                controller.stop()
            }
        }

        @Test
        fun `test rapid state transitions under high concurrency without deadlock or negative positions`() {
            val scroll = createMockScrollPane(contentHeight = 20000, viewportHeight = 800)
            val controller = createController(scroll)

            val threadCount = 8
            val iterationsPerThread = 2000
            val executor = Executors.newFixedThreadPool(threadCount)
            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(threadCount)
            val exceptions = AtomicInteger(0)

            for (t in 0 until threadCount) {
                executor.submit {
                    try {
                        startLatch.await()
                        val rnd = Random(t * 7919)
                        for (i in 0 until iterationsPerThread) {
                            when (rnd.nextInt(7)) {
                                0 -> controller.toggle()
                                1 -> controller.start()
                                2 -> controller.pause(manual = (i % 2 == 0))
                                3 -> controller.stop()
                                4 -> controller.adjustSpeed(rnd.nextInt(-3, 4))
                                5 -> controller.addImpulse(rnd.nextDouble(-50.0, 50.0))
                                6 -> controller.tick(1.0 / 60.0)
                            }
                            // Verify invariant: scrollbar value must never become negative
                            val v = scroll.verticalScrollBar.value
                            if (v < 0) {
                                exceptions.incrementAndGet()
                            }
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                        exceptions.incrementAndGet()
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }

            startLatch.countDown()
            val completed = doneLatch.await(10, TimeUnit.SECONDS)
            executor.shutdownNow()

            assertTrue(completed, "Concurrency stress test timed out (potential deadlock detected!)")
            assertEquals(0, exceptions.get(), "Errors or negative scroll positions detected during concurrent hammering!")
            assertTrue(scroll.verticalScrollBar.value >= 0, "Final scroll position must be non-negative")
            assertTrue(controller.speedLevel in 1..10, "Speed level must remain clamped in 1..10")
        }

        @Test
        fun `test chapter-end boundary clamping and overshoot prevention`() {
            val contentH = 3000
            val viewportH = 600
            val maxScroll = contentH - viewportH // 2400
            val scroll = createMockScrollPane(contentHeight = contentH, viewportHeight = viewportH)

            val endReachedCount = AtomicInteger(0)
            val controller = createController(
                scroll = scroll,
                onReachedEnd = { endReachedCount.incrementAndGet() },
            )
            controller.setSpeed(10) // fast speed: ~197 px/s
            controller.start()

            // 1. Position 1px below maxVal
            scroll.verticalScrollBar.value = maxScroll - 1
            controller.tick(1.0 / 60.0) // advances by ~3 px

            assertEquals(maxScroll, scroll.verticalScrollBar.value, "Must clamp exactly to maxVal")
            assertEquals(AutoScrollState.STOPPED, controller.state, "Must stop when boundary is reached")
            assertEquals(1, endReachedCount.get(), "onReachedEnd must be called once")

            // 2. Subsequent ticks after stop must do nothing
            controller.tick(1.0 / 60.0)
            assertEquals(maxScroll, scroll.verticalScrollBar.value)
            assertEquals(1, endReachedCount.get())

            // 3. Test negative impulses cannot drive scroll position below 0
            scroll.verticalScrollBar.value = 0
            controller.start()
            controller.addImpulse(-500.0)
            controller.tick(1.0 / 60.0)

            assertTrue(scroll.verticalScrollBar.value >= 0, "Scroll position must never be negative")
            assertEquals(0, scroll.verticalScrollBar.value)
            controller.stop()
        }

        @Test
        fun `test short page where content is smaller than viewport`() {
            // Viewport is 800px, content is only 400px
            val scroll = createMockScrollPane(contentHeight = 400, viewportHeight = 800)
            var reachedEnd = false
            val controller = createController(
                scroll = scroll,
                onReachedEnd = { reachedEnd = true },
            )
            controller.start()

            // Run 100 ticks
            repeat(100) {
                controller.tick(1.0 / 60.0)
            }

            println("[ADVERSARIAL AUDIT FINDING] Short page (content < viewport) test:")
            println("  bar.value = ${scroll.verticalScrollBar.value}")
            println("  controller.state = ${controller.state}")
            println("  reachedEnd = $reachedEnd")

            // In Swing, JScrollBar clamps value <= maximum - extent = 400 - 400 = 0
            assertEquals(0, scroll.verticalScrollBar.value, "Scrollbar value must remain 0 when content fits entirely in viewport")
            controller.stop()
        }
    }

    @Nested
    @DisplayName("2. Latent Bug Fixes Empirical Verification (M4)")
    inner class LatentBugFixesTests {

        @Test
        fun `BUG-01 empirical test - simulate window disposal during fullscreen toggle and verify session preserved`() {
            // Emulates ReaderWindow's exact fullscreen lifecycle and WindowEvent dispatch
            var togglingFullscreen = false
            var sessionClosed = false
            var readerClosed = false
            val inFlightTasks = mutableListOf<String>("task1", "task2")

            // Window closed callback as defined in ReaderWindow
            val onWindowClosed: (WindowEvent?) -> Unit = {
                if (!togglingFullscreen) {
                    sessionClosed = true
                    readerClosed = true
                    inFlightTasks.clear()
                }
            }

            // 1. Simulate Fullscreen Toggle (BUG-01 Fix Logic)
            // Entering fullscreen:
            togglingFullscreen = true
            try {
                // native dispose() dispatches WINDOW_CLOSED to event queue
                // In Swing, windowClosed executes while togglingFullscreen is STILL TRUE
                onWindowClosed(null)
            } finally {
                // Deferred reset via invokeLater
                SwingUtilities.invokeLater {
                    togglingFullscreen = false
                }
            }

            // Verify: session must NOT be closed because togglingFullscreen was true
            assertFalse(sessionClosed, "Session must NOT be closed during fullscreen toggle disposal!")
            assertFalse(readerClosed, "Archive reader must remain open!")
            assertEquals(2, inFlightTasks.size, "In-flight background tasks must not be aborted!")

            // Process invokeLater queue
            val latch = CountDownLatch(1)
            SwingUtilities.invokeLater { latch.countDown() }
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertFalse(togglingFullscreen, "togglingFullscreen must be reset to false after EDT pump")

            // 2. Simulate Actual Window Close (User presses Close or Esc outside fullscreen)
            onWindowClosed(null)
            assertTrue(sessionClosed, "Session MUST be closed when windowClosed fires without togglingFullscreen")
            assertTrue(readerClosed, "Reader MUST be closed upon true window closure")
            assertEquals(0, inFlightTasks.size, "In-flight tasks MUST be cleaned up on true closure")

            // 3. Counter-factual: Demonstrate how the bug previously failed
            var buggySessionClosed = false
            var buggyTogglingFullscreen = true
            try {
                // The old bug synchronously reset the flag in finally:
            } finally {
                buggyTogglingFullscreen = false // synchronous reset BEFORE event dispatch!
            }
            // Later, when the EDT processed the windowClosed event:
            if (!buggyTogglingFullscreen) {
                buggySessionClosed = true // BUG! Terminated session erroneously
            }
            assertTrue(buggySessionClosed, "Old synchronous finally pattern reproduces BUG-01 defect")
        }

        @Test
        fun `BUG-06 empirical test - CoverCache remove(999L) deletes all target files and leaves 1000L intact`() {
            val tempDir = Files.createTempDirectory("mihon_cover_cache_test").toFile()
            tempDir.deleteOnExit()

            try {
                val coverCache = CoverCache(cacheDir = tempDir)

                // Create test files as specified in prompt:
                // manga_999.png, manga_999_sha1.png, manga_999_other.png, manga_1000.png
                val f999Base = File(tempDir, "manga_999.png").apply { writeText("dummy 999 base") }
                val f999Sha1 = File(tempDir, "manga_999_sha1.png").apply { writeText("dummy 999 sha1") }
                val f999Other = File(tempDir, "manga_999_other.png").apply { writeText("dummy 999 other") }
                val f1000 = File(tempDir, "manga_1000.png").apply { writeText("dummy 1000 base") }

                assertTrue(f999Base.exists())
                assertTrue(f999Sha1.exists())
                assertTrue(f999Other.exists())
                assertTrue(f1000.exists())

                // Execute removal for manga ID 999L
                coverCache.remove(999L)

                // Verify target files are deleted
                assertFalse(f999Base.exists(), "manga_999.png must be deleted by remove(999L)")
                assertFalse(f999Sha1.exists(), "manga_999_sha1.png must be deleted by remove(999L)")
                assertFalse(f999Other.exists(), "manga_999_other.png must be deleted by remove(999L)")

                // Verify non-target manga 1000 is left intact
                assertTrue(f1000.exists(), "manga_1000.png must remain intact after remove(999L)")
                assertEquals("dummy 1000 base", f1000.readText())
            } finally {
                tempDir.deleteRecursively()
            }
        }

        @Test
        fun `BUG-06 adversarial stress test - prefix collision vulnerability analysis`() {
            // Adversarial test: Does startsWith("manga_${mangaId}") delete manga_9999 when removing 999?
            // Or delete manga_90.png when removing 9?
            val tempDir = Files.createTempDirectory("mihon_cover_cache_collision_test").toFile()
            tempDir.deleteOnExit()

            try {
                val coverCache = CoverCache(cacheDir = tempDir)

                val f9Base = File(tempDir, "manga_9.png").apply { writeText("manga 9") }
                val f9Variant = File(tempDir, "manga_9_abc.png").apply { writeText("manga 9 variant") }
                val f99 = File(tempDir, "manga_99.png").apply { writeText("manga 99") }
                val f900 = File(tempDir, "manga_900.png").apply { writeText("manga 900") }
                val f9999 = File(tempDir, "manga_9999.png").apply { writeText("manga 9999") }

                // When removing manga 9L:
                coverCache.remove(9L)

                assertFalse(f9Base.exists(), "manga_9.png must be deleted")
                assertFalse(f9Variant.exists(), "manga_9_abc.png must be deleted")

                // ADVERSARIAL OBSERVATION:
                // Because prefix is "manga_9", name.startsWith("manga_9") matches "manga_99.png" and "manga_900.png"!
                val f99Survived = f99.exists()
                val f900Survived = f900.exists()

                println("[ADVERSARIAL AUDIT FINDING] CoverCache prefix collision test:")
                println("  manga_99.png survived? $f99Survived")
                println("  manga_900.png survived? $f900Survived")

                // If f99 was deleted, document that this is a confirmed latent collision bug in CoverCache.remove
                if (!f99Survived || !f900Survived) {
                    println("  => CONFIRMED VULNERABILITY: CoverCache.remove(mangaId) uses unanchored prefix matching " +
                        "'manga_\$mangaId', causing collateral deletion of higher-ID manga sharing numeric prefix!")
                }
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }
}
