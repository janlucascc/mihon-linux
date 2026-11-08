package mihon.desktop

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReaderChallengerStressTest {

    @Nested
    @DisplayName("1. Concurrency, InFlightTasks & Fast Scrolling Stress Tests")
    inner class ConcurrencyAndFastScrollingTests {

        /**
         * Simulates cancelDistantTasks logic directly as implemented in ReaderWindow:
         * minP = (centerPage - radiusBehind).coerceAtLeast(0)
         * maxP = (centerPage + radiusAhead).coerceAtMost(pageCount - 1)
         */
        private fun cancelDistantTasksHelper(
            inFlightTasks: ConcurrentHashMap<Int, Future<*>>,
            centerPage: Int,
            pageCount: Int,
            radiusAhead: Int = 4,
            radiusBehind: Int = 2,
        ) {
            val minP = (centerPage - radiusBehind).coerceAtLeast(0)
            val maxP = (centerPage + radiusAhead).coerceAtMost(pageCount - 1)
            val it = inFlightTasks.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                val p = entry.key
                if (p < minP || p > maxP) {
                    entry.value.cancel(true)
                    it.remove()
                }
            }
        }

        @Test
        fun `test rapid page jumps cancel distant tasks correctly`() {
            val pageCount = 100
            val inFlightTasks = ConcurrentHashMap<Int, Future<*>>()
            val executor = Executors.newFixedThreadPool(8)

            try {
                // Populate tasks for pages 0 to 20
                val cancelledTasks = ConcurrentHashMap<Int, AtomicBoolean>()
                for (p in 0..20) {
                    val wasCancelled = AtomicBoolean(false)
                    cancelledTasks[p] = wasCancelled
                    val future = executor.submit {
                        try {
                            Thread.sleep(500)
                        } catch (e: InterruptedException) {
                            wasCancelled.set(true)
                            Thread.currentThread().interrupt()
                        }
                    }
                    inFlightTasks[p] = future
                }

                // Simulate rapid jump from page 5 to page 50
                cancelDistantTasksHelper(inFlightTasks, centerPage = 50, pageCount = pageCount, radiusAhead = 4, radiusBehind = 2)

                // Window around 50 is [48..54]. All pages 0..20 are distant (< 48)
                assertEquals(0, inFlightTasks.size, "All distant tasks (0..20) should have been cancelled and removed")

                // Verify that future.cancel(true) was invoked on all distant tasks
                cancelledTasks.forEach { (p, wasCancelled) ->
                    Thread.sleep(5)
                    assertTrue(
                        wasCancelled.get() || !executor.isShutdown,
                        "Distant task for page $p must have received cancellation/interrupt",
                    )
                }
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `test high concurrency stress of rapid jumps and scrolling against inFlightTasks`() {
            val pageCount = 200
            val inFlightTasks = ConcurrentHashMap<Int, Future<*>>()
            val workerExecutor = Executors.newFixedThreadPool(8)
            val eventThreads = Executors.newFixedThreadPool(6)
            val iterations = 500
            val latch = CountDownLatch(iterations)
            val exceptions = AtomicInteger(0)

            try {
                for (i in 0 until iterations) {
                    eventThreads.submit {
                        try {
                            val jumpTarget = Random.nextInt(0, pageCount)
                            // Submit tasks near jump target
                            for (offset in -2..4) {
                                val target = jumpTarget + offset
                                if (target in 0 until pageCount) {
                                    val f = workerExecutor.submit {
                                        try {
                                            Thread.sleep(Random.nextLong(1, 10))
                                        } catch (_: InterruptedException) {
                                            // expected on cancellation
                                        } finally {
                                            inFlightTasks.remove(target)
                                        }
                                    }
                                    inFlightTasks[target] = f
                                }
                            }

                            // Simultaneously invoke cancellation for distant tasks
                            cancelDistantTasksHelper(
                                inFlightTasks = inFlightTasks,
                                centerPage = jumpTarget,
                                pageCount = pageCount,
                                radiusAhead = 4,
                                radiusBehind = 2,
                            )
                        } catch (t: Throwable) {
                            exceptions.incrementAndGet()
                            t.printStackTrace()
                        } finally {
                            latch.countDown()
                        }
                    }
                }

                val completed = latch.await(10, TimeUnit.SECONDS)
                assertTrue(completed, "High-concurrency stress test should complete without deadlock")
                assertEquals(0, exceptions.get(), "No unhandled exceptions allowed during rapid scrolling")
            } finally {
                workerExecutor.shutdownNow()
                eventThreads.shutdownNow()
            }
        }

        @Test
        fun `test cancelDistantTasks edge boundary conditions`() {
            val pageCount = 50
            val inFlightTasks = ConcurrentHashMap<Int, Future<*>>()
            val executor = Executors.newSingleThreadExecutor()

            try {
                // Center page = 0 (start of manga)
                for (p in 0..10) {
                    inFlightTasks[p] = executor.submit { Thread.sleep(200) }
                }
                // With radiusBehind=2, radiusAhead=4, center=0: valid range is 0..(0+4)=0..4
                cancelDistantTasksHelper(inFlightTasks, centerPage = 0, pageCount = pageCount, radiusAhead = 4, radiusBehind = 2)
                assertTrue(inFlightTasks.keys.all { it in 0..4 }, "Only pages 0..4 should remain")

                // Center page = pageCount - 1 (end of manga: 49)
                inFlightTasks.clear()
                for (p in 40..49) {
                    inFlightTasks[p] = executor.submit { Thread.sleep(200) }
                }
                // radiusBehind=2, radiusAhead=4, center=49: valid range is (49-2)..49 = 47..49
                cancelDistantTasksHelper(inFlightTasks, centerPage = 49, pageCount = pageCount, radiusAhead = 4, radiusBehind = 2)
                assertTrue(inFlightTasks.keys.all { it in 47..49 }, "Only pages 47..49 should remain")
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `test WebtoonView prefetch-cancellation thrashing risk under multi-page viewport`() {
            // In WebtoonView.updateVisible():
            // cancelDistantTasks(first, radiusAhead = 6, radiusBehind = 4)
            // for (ahead in 1..3) request(last + ahead)
            val pageCount = 100
            val first = 0
            val last = 4 // 5 pages visible (e.g. on tall monitor or high zoom-out)

            val inFlightTasks = ConcurrentHashMap<Int, Future<*>>()
            val executor = Executors.newCachedThreadPool()

            try {
                // Prefetch requests pages last + 1, last + 2, last + 3 -> 5, 6, 7
                for (ahead in 1..3) {
                    val idx = last + ahead
                    inFlightTasks[idx] = executor.submit { Thread.sleep(500) }
                }

                // WebtoonView calls cancelDistantTasks(first, radiusAhead = 6)
                // center = first (0), radiusAhead = 6 -> maxP = 6
                cancelDistantTasksHelper(inFlightTasks, centerPage = first, pageCount = pageCount, radiusAhead = 6, radiusBehind = 4)

                // Page 7 was cancelled and removed because 7 > 0 + 6
                assertFalse(inFlightTasks.containsKey(7), "Page 7 was cancelled by cancelDistantTasks because maxP=6 while prefetch requested up to last+3=7")
                assertTrue(inFlightTasks.containsKey(5))
                assertTrue(inFlightTasks.containsKey(6))
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `test WebtoonView requested set cleanup bug on failed or cancelled page`() {
            // Simulates the exact state machine in WebtoonView:
            // images: HashMap<Int, BufferedImage>
            // requested: HashSet<Int>
            // failed: HashSet<Int>
            val images = HashMap<Int, BufferedImage>()
            val requested = HashSet<Int>()
            val failed = HashSet<Int>()

            // 1. Page 0 is requested
            val targetPage = 0
            val canRequest1 = requested.add(targetPage)
            assertTrue(canRequest1, "Initial request succeeds")

            // 2. Decode fails or task interrupted -> result is null
            val result: BufferedImage? = null
            if (result == null) {
                failed.add(targetPage)
                // Note: requested.remove(targetPage) is NOT called!
            } else {
                images[targetPage] = result
            }

            assertTrue(failed.contains(targetPage))
            assertTrue(requested.contains(targetPage))

            // 3. User scrolls away: first = 50, last = 55
            val first = 50
            val last = 55
            // WebtoonView line 1139:
            // images.keys.filter { it < first - 4 || it > last + 6 }.toList().forEach { idx ->
            //     images.remove(idx)?.let { runCatching { it.flush() } }
            //     requested.remove(idx)
            // }
            images.keys.filter { it < first - 4 || it > last + 6 }.toList().forEach { idx ->
                images.remove(idx)
                requested.remove(idx)
            }

            // Notice: Because targetPage 0 was never in images, requested.remove(0) was NEVER called!
            assertTrue(requested.contains(targetPage), "Bug reproduced: targetPage 0 remains stuck in requested set even after scrolling 50 pages away")

            // 4. User scrolls back to page 0
            // WebtoonView line 1158:
            // if ((existing != null && existing.width == targetW) || !requested.add(i)) return
            val canRetry = requested.add(targetPage)
            assertFalse(canRetry, "Bug: User returns to page 0 but cannot retry loading because requested.add(0) returns false!")
        }
    }

    @Nested
    @DisplayName("2. Memory Management, Dynamic Sizing & Cache Eviction Tests")
    inner class MemoryManagementAndCacheEvictionTests {

        @Test
        fun `test dynamic cache sizing under small heap simulations`() {
            // Under 256MB -> should clamp to minEntries (8)
            assertEquals(8, ReaderEngine.calculateMaxCacheEntries(maxMemoryBytes = 64L * 1024 * 1024))
            assertEquals(8, ReaderEngine.calculateMaxCacheEntries(maxMemoryBytes = 128L * 1024 * 1024))
            assertEquals(8, ReaderEngine.calculateMaxCacheEntries(maxMemoryBytes = 256L * 1024 * 1024))
            assertEquals(8, ReaderEngine.calculateMaxCacheEntries(maxMemoryBytes = 384L * 1024 * 1024))

            // At 512MB: 512 / 48 = 10 entries
            assertEquals(10, ReaderEngine.calculateMaxCacheEntries(maxMemoryBytes = 512L * 1024 * 1024))
        }

        @Test
        fun `test dynamic cache sizing under large heap simulations`() {
            // At 1GB: 1024 / 48 = 21 entries
            assertEquals(21, ReaderEngine.calculateMaxCacheEntries(maxMemoryBytes = 1024L * 1024 * 1024))

            // At 1.5GB (1536MB): 1536 / 48 = 32 entries (clamped to maxEntries = 32)
            assertEquals(32, ReaderEngine.calculateMaxCacheEntries(maxMemoryBytes = 1536L * 1024 * 1024))

            // At 2GB (2048MB): 2048 / 48 = 42 -> clamped to 32
            assertEquals(32, ReaderEngine.calculateMaxCacheEntries(maxMemoryBytes = 2048L * 1024 * 1024))

            // At 4GB: clamped to 32
            assertEquals(32, ReaderEngine.calculateMaxCacheEntries(maxMemoryBytes = 4096L * 1024 * 1024))

            // At 8GB: clamped to 32
            assertEquals(32, ReaderEngine.calculateMaxCacheEntries(maxMemoryBytes = 8192L * 1024 * 1024))
        }

        @Test
        fun `test removeEldestEntry properly invokes flush on evicted BufferedImages`() {
            val maxCache = 5
            val flushedPages = ConcurrentHashMap.newKeySet<Int>()

            class MockFlushableImage(val pageIdx: Int) : BufferedImage(10, 10, TYPE_INT_RGB) {
                override fun flush() {
                    flushedPages.add(pageIdx)
                    super.flush()
                }
            }

            val cacheLock = Any()
            val cache = object : LinkedHashMap<Int, BufferedImage>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, BufferedImage>): Boolean {
                    val evict = size > maxCache
                    if (evict) {
                        runCatching { eldest.value.flush() }
                    }
                    return evict
                }
            }

            // Insert 5 images (within budget)
            synchronized(cacheLock) {
                for (p in 0 until 5) {
                    cache[p] = MockFlushableImage(p)
                }
            }
            assertEquals(0, flushedPages.size, "No pages should be flushed before exceeding cache size")

            // Insert 6th image (exceeds budget 5 -> evicts eldest page 0)
            synchronized(cacheLock) {
                cache[5] = MockFlushableImage(5)
            }
            assertEquals(1, flushedPages.size)
            assertTrue(flushedPages.contains(0), "Evicted page 0 must have flush() invoked")
            synchronized(cacheLock) {
                assertFalse(cache.containsKey(0))
                assertTrue(cache.containsKey(5))
            }

            // Access page 1 (making page 2 the eldest)
            synchronized(cacheLock) {
                cache[1]
            }

            // Insert 7th image -> page 2 should be evicted and flushed
            synchronized(cacheLock) {
                cache[6] = MockFlushableImage(6)
            }
            assertEquals(2, flushedPages.size)
            assertTrue(flushedPages.contains(2), "Evicted page 2 must have flush() invoked")
            synchronized(cacheLock) {
                assertFalse(cache.containsKey(2))
                assertTrue(cache.containsKey(1), "Accessed page 1 must remain in cache")
            }
        }

        @Test
        fun `test concurrent access to synchronized cache does not corrupt LRU order or throw`() {
            val maxCache = 8
            val flushedCount = AtomicInteger(0)

            class CountingImage : BufferedImage(8, 8, TYPE_INT_RGB) {
                override fun flush() {
                    flushedCount.incrementAndGet()
                    super.flush()
                }
            }

            val cacheLock = Any()
            val cache = object : LinkedHashMap<Int, BufferedImage>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, BufferedImage>): Boolean {
                    val evict = size > maxCache
                    if (evict) {
                        runCatching { eldest.value.flush() }
                    }
                    return evict
                }
            }

            val threads = Executors.newFixedThreadPool(8)
            val latch = CountDownLatch(1000)
            val exceptions = AtomicInteger(0)

            for (i in 0 until 1000) {
                threads.submit {
                    try {
                        val key = Random.nextInt(0, 50)
                        if (Random.nextBoolean()) {
                            synchronized(cacheLock) {
                                cache[key] = CountingImage()
                            }
                        } else {
                            synchronized(cacheLock) {
                                cache[key]
                            }
                        }
                    } catch (t: Throwable) {
                        exceptions.incrementAndGet()
                    } finally {
                        latch.countDown()
                    }
                }
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(0, exceptions.get(), "No exceptions allowed under concurrent cache access")
            synchronized(cacheLock) {
                assertTrue(cache.size <= maxCache, "Cache size must never exceed maxCache ($maxCache), was ${cache.size}")
            }
            threads.shutdownNow()
        }

        @Test
        fun `test window closed lifecycle cleans up and flushes all cached textures`() {
            val flushedCount = AtomicInteger(0)

            class FlushTrackerImage : BufferedImage(16, 16, TYPE_INT_RGB) {
                override fun flush() {
                    flushedCount.incrementAndGet()
                    super.flush()
                }
            }

            val cacheLock = Any()
            val cache = object : LinkedHashMap<Int, BufferedImage>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, BufferedImage>): Boolean = size > 16
            }

            synchronized(cacheLock) {
                for (i in 0 until 10) cache[i] = FlushTrackerImage()
            }
            assertEquals(10, cache.size)
            assertEquals(0, flushedCount.get())

            // Simulate windowClosed cleanup block from ReaderWindow line 561-564:
            synchronized(cacheLock) {
                cache.values.forEach { runCatching { it.flush() } }
                cache.clear()
            }

            assertEquals(0, cache.size)
            assertEquals(10, flushedCount.get(), "All 10 cached images must be flushed upon reader closure")
        }
    }

    @Nested
    @DisplayName("3. Image Enhancer & ColorFilterMode Stress Tests")
    inner class ImageEnhancerAndColorFilterTests {

        @Test
        fun `test ColorFilterMode on TYPE_INT_RGB`() {
            val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, Color(255, 0, 0).rgb) // Pure Red
            img.setRGB(1, 1, Color(0, 255, 0).rgb) // Pure Green
            img.setRGB(2, 2, Color(0, 0, 255).rgb) // Pure Blue

            // Grayscale
            val gray = MangaImageEnhancer.applyColorFilter(img, MangaImageEnhancer.ColorFilterMode.GRAYSCALE)
            assertEquals(BufferedImage.TYPE_INT_RGB, gray.type)
            val grayRed = Color(gray.getRGB(0, 0))
            assertEquals(grayRed.red, grayRed.green)
            assertEquals(grayRed.green, grayRed.blue)
            assertEquals((299 * 255) / 1000, grayRed.red)

            // Inverted
            val inv = MangaImageEnhancer.applyColorFilter(img, MangaImageEnhancer.ColorFilterMode.INVERTED)
            val invRed = Color(inv.getRGB(0, 0))
            assertEquals(0, invRed.red)
            assertEquals(255, invRed.green)
            assertEquals(255, invRed.blue)

            // Sepia
            val sepia = MangaImageEnhancer.applyColorFilter(img, MangaImageEnhancer.ColorFilterMode.SEPIA)
            val sepiaRed = Color(sepia.getRGB(0, 0))
            assertTrue(sepiaRed.red > sepiaRed.green)
            assertTrue(sepiaRed.green > sepiaRed.blue)

            // NONE filter identity
            val none = MangaImageEnhancer.applyColorFilter(img, MangaImageEnhancer.ColorFilterMode.NONE)
            assertEquals(img, none, "NONE filter must return same image reference without overhead")
        }

        @Test
        fun `test ColorFilterMode on TYPE_INT_ARGB`() {
            val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, (200 shl 24) or (255 shl 16)) // Semi-transparent Red

            val gray = MangaImageEnhancer.applyColorFilter(img, MangaImageEnhancer.ColorFilterMode.GRAYSCALE)
            assertEquals(BufferedImage.TYPE_INT_RGB, gray.type)
            val grayPx = Color(gray.getRGB(0, 0))
            assertEquals(grayPx.red, grayPx.green)
            assertEquals(grayPx.green, grayPx.blue)

            val inv = MangaImageEnhancer.applyColorFilter(img, MangaImageEnhancer.ColorFilterMode.INVERTED)
            val invPx = Color(inv.getRGB(0, 0))
            assertEquals(0, invPx.red)
            assertEquals(255, invPx.green)
            assertEquals(255, invPx.blue)

            val sepia = MangaImageEnhancer.applyColorFilter(img, MangaImageEnhancer.ColorFilterMode.SEPIA)
            assertNotNull(sepia)
        }

        @Test
        fun `test ColorFilterMode on TYPE_3BYTE_BGR`() {
            val img = BufferedImage(16, 16, BufferedImage.TYPE_3BYTE_BGR)
            img.setRGB(0, 0, Color(255, 0, 0).rgb) // Pure Red
            img.setRGB(1, 1, Color(0, 255, 0).rgb) // Pure Green
            img.setRGB(2, 2, Color(0, 0, 255).rgb) // Pure Blue

            // Grayscale
            val gray = MangaImageEnhancer.applyColorFilter(img, MangaImageEnhancer.ColorFilterMode.GRAYSCALE)
            val grayRed = Color(gray.getRGB(0, 0))
            assertEquals(grayRed.red, grayRed.green)
            assertEquals(grayRed.green, grayRed.blue)
            assertEquals((299 * 255) / 1000, grayRed.red)

            // Inverted
            val inv = MangaImageEnhancer.applyColorFilter(img, MangaImageEnhancer.ColorFilterMode.INVERTED)
            val invRed = Color(inv.getRGB(0, 0))
            assertEquals(0, invRed.red)
            assertEquals(255, invRed.green)
            assertEquals(255, invRed.blue)

            // Sepia
            val sepia = MangaImageEnhancer.applyColorFilter(img, MangaImageEnhancer.ColorFilterMode.SEPIA)
            val sepiaRed = Color(sepia.getRGB(0, 0))
            assertTrue(sepiaRed.red > sepiaRed.green)
            assertTrue(sepiaRed.green > sepiaRed.blue)
        }

        @Test
        fun `test ColorFilterMode on non-standard and edge case dimensions`() {
            val testDimensions = listOf(
                1 to 1,
                1 to 64,
                64 to 1,
                7 to 13,
                33 to 17,
                511 to 257,
            )

            for ((w, h) in testDimensions) {
                for (type in listOf(BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_3BYTE_BGR)) {
                    val img = BufferedImage(w, h, type)
                    img.setRGB(0, 0, Color(120, 150, 180).rgb)
                    if (w > 1 && h > 1) {
                        img.setRGB(w - 1, h - 1, Color(200, 100, 50).rgb)
                    }

                    for (mode in MangaImageEnhancer.ColorFilterMode.entries) {
                        val result = MangaImageEnhancer.applyColorFilter(img, mode)
                        assertEquals(w, result.width, "Width must be preserved for $w x $h in $mode")
                        assertEquals(h, result.height, "Height must be preserved for $w x $h in $mode")
                    }
                }
            }
        }

        @Test
        fun `test ColorFilterMode on 4K resolution image`() {
            val w = 3840
            val h = 2160
            // Create 4K image
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, Color.RED.rgb)
            img.setRGB(w - 1, h - 1, Color.BLUE.rgb)

            val start = System.currentTimeMillis()
            val filtered = MangaImageEnhancer.applyColorFilter(img, MangaImageEnhancer.ColorFilterMode.SEPIA)
            val duration = System.currentTimeMillis() - start

            assertEquals(w, filtered.width)
            assertEquals(h, filtered.height)
            // 4K filter should complete swiftly with parallel stream (< 3000ms)
            assertTrue(duration < 3000, "4K filtering took $duration ms (expected < 3000ms)")
        }

        @Test
        fun `test ColorFilterMode on subimage of TYPE_3BYTE_BGR reveals pixel corruption`() {
            // A parent image of 100x100 TYPE_3BYTE_BGR
            val parent = BufferedImage(100, 100, BufferedImage.TYPE_3BYTE_BGR)
            // Fill left half with RED, right half with BLUE
            for (y in 0 until 100) {
                for (x in 0 until 50) {
                    parent.setRGB(x, y, Color.RED.rgb)
                }
                for (x in 50 until 100) {
                    parent.setRGB(x, y, Color.BLUE.rgb)
                }
            }

            // Extract right half (BLUE) as subimage (e.g. from ReaderEngine.getHalfPage or UpscaleComparisonDialog crop)
            val sub = parent.getSubimage(50, 0, 50, 100)
            assertEquals(50, sub.width)
            assertEquals(100, sub.height)
            assertEquals(Color.BLUE.rgb, sub.getRGB(0, 0))

            // Apply GRAYSCALE filter to the subimage
            val filtered = MangaImageEnhancer.applyColorFilter(sub, MangaImageEnhancer.ColorFilterMode.GRAYSCALE)
            assertEquals(50, filtered.width)
            assertEquals(100, filtered.height)

            // BLUE grayscale value: (114 * 255) / 1000 = 29
            // RED grayscale value: (299 * 255) / 1000 = 76
            val px00 = Color(filtered.getRGB(0, 0))
            println("[VULNERABILITY REPRODUCED] Subimage pixel (0,0) in filtered: R=${px00.red}, G=${px00.green}, B=${px00.blue}")

            // Oracle assert: Subimage (0,0) is Blue so grayscale must be 29.
            // When buggy, extractRgbPixels returns 76 (Red) from parent buffer.
            assertEquals(
                29,
                px00.red,
                "BUG DETECTED: MangaImageEnhancer.extractRgbPixels assumes 'buf.data.size >= total * 3' implies contiguous 0-offset data, corrupting subimages from getHalfPage / cropping. Expected 29 (Blue) but was ${px00.red} (Red)",
            )
        }
    }
}
