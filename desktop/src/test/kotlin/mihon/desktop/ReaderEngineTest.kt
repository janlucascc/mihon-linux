package mihon.desktop

import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderEngineTest {

    @Test
    fun testReadModeEnumAndLabels() {
        val modes = ReadMode.entries
        assertEquals(5, modes.size)

        assertEquals("Página única", ReadMode.SINGLE.label)
        assertEquals("Página dupla (mangá, D→E)", ReadMode.DOUBLE_RTL.label)
        assertEquals("Página dupla (quadrinhos, E→D)", ReadMode.DOUBLE_LTR.label)
        assertEquals("Webtoon contínuo", ReadMode.WEBTOON.label)
        assertEquals("Vertical com espaçamento", ReadMode.CONTINUOUS_VERTICAL.label)

        assertEquals(ReadMode.SINGLE, ReadMode.valueOf("SINGLE"))
        assertEquals(ReadMode.DOUBLE_RTL, ReadMode.valueOf("DOUBLE_RTL"))
        assertEquals(ReadMode.DOUBLE_LTR, ReadMode.valueOf("DOUBLE_LTR"))
        assertEquals(ReadMode.WEBTOON, ReadMode.valueOf("WEBTOON"))
        assertEquals(ReadMode.CONTINUOUS_VERTICAL, ReadMode.valueOf("CONTINUOUS_VERTICAL"))
    }

    @Test
    fun testSpreadAtCoverIsolation() {
        val pageCount = 10
        val isWideFalse = { _: Int -> false }

        // Cover page (0) must always be isolated in both DOUBLE_RTL and DOUBLE_LTR
        assertEquals(listOf(0), ReaderEngine.spreadAt(0, ReadMode.DOUBLE_RTL, pageCount, isWideFalse))
        assertEquals(listOf(0), ReaderEngine.spreadAt(0, ReadMode.DOUBLE_LTR, pageCount, isWideFalse))

        // Pairing starts at page 1
        assertEquals(listOf(1, 2), ReaderEngine.spreadAt(1, ReadMode.DOUBLE_RTL, pageCount, isWideFalse))
        assertEquals(listOf(1, 2), ReaderEngine.spreadAt(1, ReadMode.DOUBLE_LTR, pageCount, isWideFalse))

        // Subsequent pairs
        assertEquals(listOf(3, 4), ReaderEngine.spreadAt(3, ReadMode.DOUBLE_RTL, pageCount, isWideFalse))
        assertEquals(listOf(5, 6), ReaderEngine.spreadAt(5, ReadMode.DOUBLE_LTR, pageCount, isWideFalse))
    }

    @Test
    fun testSpreadAtSingleAndWebtoonModes() {
        val pageCount = 10
        val isWideFalse = { _: Int -> false }

        for (p in 0 until pageCount) {
            assertEquals(listOf(p), ReaderEngine.spreadAt(p, ReadMode.SINGLE, pageCount, isWideFalse))
            assertEquals(listOf(p), ReaderEngine.spreadAt(p, ReadMode.WEBTOON, pageCount, isWideFalse))
            assertEquals(listOf(p), ReaderEngine.spreadAt(p, ReadMode.CONTINUOUS_VERTICAL, pageCount, isWideFalse))
        }
    }

    @Test
    fun testSpreadAtWidePageHandling() {
        val pageCount = 10
        // Page 3 is a wide page (e.g. 2000x1200 spread)
        val isWide = { i: Int -> i == 3 }

        // Normal pair before wide page
        assertEquals(listOf(1, 2), ReaderEngine.spreadAt(1, ReadMode.DOUBLE_RTL, pageCount, isWide))

        // Wide page itself must be isolated as a single spread
        assertEquals(listOf(3), ReaderEngine.spreadAt(3, ReadMode.DOUBLE_RTL, pageCount, isWide))
        assertEquals(listOf(3), ReaderEngine.spreadAt(3, ReadMode.DOUBLE_LTR, pageCount, isWide))

        // Subsequent pair resumes
        assertEquals(listOf(4, 5), ReaderEngine.spreadAt(4, ReadMode.DOUBLE_RTL, pageCount, isWide))

        // What if partner is wide? E.g., page 2 is wide
        val isWide2 = { i: Int -> i == 2 }
        // Page 1 cannot pair with wide page 2
        assertEquals(listOf(1), ReaderEngine.spreadAt(1, ReadMode.DOUBLE_RTL, pageCount, isWide2))
        assertEquals(listOf(2), ReaderEngine.spreadAt(2, ReadMode.DOUBLE_RTL, pageCount, isWide2))
        assertEquals(listOf(3, 4), ReaderEngine.spreadAt(3, ReadMode.DOUBLE_RTL, pageCount, isWide2))
    }

    @Test
    fun testSpreadAtAspectRatioTolerance() {
        val pageCount = 10
        val isWideFalse = { _: Int -> false }

        // Compatible aspect ratios: 0.667 and 0.70 (delta = 0.033 <= 0.18)
        val aspectRatiosCompatible: (Int) -> Double? = { i ->
            when (i) {
                1 -> 0.667
                2 -> 0.700
                else -> 0.667
            }
        }
        assertEquals(listOf(1, 2), ReaderEngine.spreadAt(1, ReadMode.DOUBLE_RTL, pageCount, isWideFalse, aspectRatiosCompatible))

        // Incompatible aspect ratios: 0.667 and 1.0 (square vs portrait, delta = 0.333 > 0.18)
        val aspectRatiosIncompatible: (Int) -> Double? = { i ->
            when (i) {
                1 -> 0.667
                2 -> 1.000
                else -> 0.667
            }
        }
        assertEquals(listOf(1), ReaderEngine.spreadAt(1, ReadMode.DOUBLE_RTL, pageCount, isWideFalse, aspectRatiosIncompatible))
    }

    @Test
    fun testSpreadAtBoundaryConditions() {
        val isWideFalse = { _: Int -> false }

        // Out of bounds
        assertTrue(ReaderEngine.spreadAt(-1, ReadMode.DOUBLE_RTL, 5, isWideFalse).isEmpty())
        assertTrue(ReaderEngine.spreadAt(5, ReadMode.DOUBLE_RTL, 5, isWideFalse).isEmpty())

        // Last odd page in chapter (pageCount = 6: pages 0..5)
        // Spreads: [0], [1, 2], [3, 4], [5]
        assertEquals(listOf(5), ReaderEngine.spreadAt(5, ReadMode.DOUBLE_RTL, 6, isWideFalse))

        // Single page chapter
        assertEquals(listOf(0), ReaderEngine.spreadAt(0, ReadMode.DOUBLE_RTL, 1, isWideFalse))
    }

    @Test
    fun testPrevSpreadStartNavigation() {
        val pageCount = 10
        // Chapter layout:
        // Page 0: [0] (cover)
        // Page 1: [1, 2]
        // Page 3: [3] (wide)
        // Page 4: [4, 5]
        // Page 6: [6, 7]
        // Page 8: [8, 9]
        val isWide = { i: Int -> i == 3 }

        assertEquals(0, ReaderEngine.prevSpreadStart(0, ReadMode.DOUBLE_RTL, pageCount, isWide))
        assertEquals(0, ReaderEngine.prevSpreadStart(1, ReadMode.DOUBLE_RTL, pageCount, isWide))
        assertEquals(1, ReaderEngine.prevSpreadStart(3, ReadMode.DOUBLE_RTL, pageCount, isWide))
        assertEquals(3, ReaderEngine.prevSpreadStart(4, ReadMode.DOUBLE_RTL, pageCount, isWide))
        assertEquals(4, ReaderEngine.prevSpreadStart(6, ReadMode.DOUBLE_RTL, pageCount, isWide))
        assertEquals(6, ReaderEngine.prevSpreadStart(8, ReadMode.DOUBLE_RTL, pageCount, isWide))

        // Single mode simply decrements
        assertEquals(4, ReaderEngine.prevSpreadStart(5, ReadMode.SINGLE, pageCount, isWide))
        assertEquals(0, ReaderEngine.prevSpreadStart(1, ReadMode.SINGLE, pageCount, isWide))
        assertEquals(0, ReaderEngine.prevSpreadStart(0, ReadMode.SINGLE, pageCount, isWide))
    }

    @Test
    fun testWidePageGetHalfPage() {
        // Create 200x100 test image:
        // Left half (x 0..99): Red (0xFF0000)
        // Right half (x 100..199): Blue (0x0000FF)
        val testImg = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 100) {
            for (x in 0 until 100) testImg.setRGB(x, y, 0xFF0000)
            for (x in 100 until 200) testImg.setRGB(x, y, 0x0000FF)
        }

        // 1. LTR Reading (Western Comics):
        // Part 0 (first half): Left side (Red)
        val ltrPart0 = ReaderEngine.getHalfPage(testImg, isSecondHalf = false, isRtl = false)
        assertEquals(100, ltrPart0.width)
        assertEquals(100, ltrPart0.height)
        assertEquals(0xFF0000, ltrPart0.getRGB(50, 50) and 0xFFFFFF)

        // Part 1 (second half): Right side (Blue)
        val ltrPart1 = ReaderEngine.getHalfPage(testImg, isSecondHalf = true, isRtl = false)
        assertEquals(100, ltrPart1.width)
        assertEquals(100, ltrPart1.height)
        assertEquals(0x0000FF, ltrPart1.getRGB(50, 50) and 0xFFFFFF)

        // 2. RTL Reading (Japanese Manga):
        // Part 0 (first half): Right side (Blue)
        val rtlPart0 = ReaderEngine.getHalfPage(testImg, isSecondHalf = false, isRtl = true)
        assertEquals(100, rtlPart0.width)
        assertEquals(100, rtlPart0.height)
        assertEquals(0x0000FF, rtlPart0.getRGB(50, 50) and 0xFFFFFF)

        // Part 1 (second half): Left side (Red)
        val rtlPart1 = ReaderEngine.getHalfPage(testImg, isSecondHalf = true, isRtl = true)
        assertEquals(100, rtlPart1.width)
        assertEquals(100, rtlPart1.height)
        assertEquals(0xFF0000, rtlPart1.getRGB(50, 50) and 0xFFFFFF)
    }

    @Test
    fun testSplitAndMerge() {
        // Create 200x100 test image:
        // Left half (x 0..99): Green (0x00FF00)
        // Right half (x 100..199): Yellow (0xFFFF00)
        val testImg = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 100) {
            for (x in 0 until 100) testImg.setRGB(x, y, 0x00FF00)
            for (x in 100 until 200) testImg.setRGB(x, y, 0xFFFF00)
        }

        // RTL: Top is Right half (Yellow), Bottom is Left half (Green)
        val rtlMerged = ReaderEngine.splitAndMerge(testImg, isRtl = true)
        assertEquals(100, rtlMerged.width)
        assertEquals(200, rtlMerged.height)
        assertEquals(0xFFFF00, rtlMerged.getRGB(50, 50) and 0xFFFFFF) // Top half
        assertEquals(0x00FF00, rtlMerged.getRGB(50, 150) and 0xFFFFFF) // Bottom half

        // LTR: Top is Left half (Green), Bottom is Right half (Yellow)
        val ltrMerged = ReaderEngine.splitAndMerge(testImg, isRtl = false)
        assertEquals(100, ltrMerged.width)
        assertEquals(200, ltrMerged.height)
        assertEquals(0x00FF00, ltrMerged.getRGB(50, 50) and 0xFFFFFF) // Top half
        assertEquals(0xFFFF00, ltrMerged.getRGB(50, 150) and 0xFFFFFF) // Bottom half
    }

    @Test
    fun testWebtoonLayoutAndPageAt() {
        val pageCount = 5
        val pageWidth = { _: Int -> 800 }
        val pageHeight = { _: Int, _: Int -> 1000 }

        // 1. Continuous Webtoon (pageGap = 0)
        val (topsWebtoon, heightsWebtoon) = ReaderEngine.computeWebtoonLayout(
            pageCount = pageCount,
            pageWidthFn = pageWidth,
            pageHeightFn = pageHeight,
            pageGap = 0,
        )
        assertEquals(6, topsWebtoon.size)
        assertEquals(5, heightsWebtoon.size)
        assertEquals(0, topsWebtoon[0])
        assertEquals(1000, topsWebtoon[1])
        assertEquals(2000, topsWebtoon[2])
        assertEquals(3000, topsWebtoon[3])
        assertEquals(4000, topsWebtoon[4])
        assertEquals(5000, topsWebtoon[5])

        // 2. Spaced Vertical (pageGap = 16)
        val (topsSpaced, heightsSpaced) = ReaderEngine.computeWebtoonLayout(
            pageCount = pageCount,
            pageWidthFn = pageWidth,
            pageHeightFn = pageHeight,
            pageGap = 16,
        )
        assertEquals(0, topsSpaced[0])
        assertEquals(1016, topsSpaced[1])
        assertEquals(2032, topsSpaced[2])
        assertEquals(3048, topsSpaced[3])
        assertEquals(4064, topsSpaced[4])
        assertEquals(5064, topsSpaced[5])

        // 3. Binary search page index lookup (pageAt)
        assertEquals(0, ReaderEngine.pageAt(0, topsSpaced, pageCount))
        assertEquals(0, ReaderEngine.pageAt(500, topsSpaced, pageCount))
        assertEquals(0, ReaderEngine.pageAt(1015, topsSpaced, pageCount))
        assertEquals(1, ReaderEngine.pageAt(1016, topsSpaced, pageCount))
        assertEquals(1, ReaderEngine.pageAt(1500, topsSpaced, pageCount))
        assertEquals(2, ReaderEngine.pageAt(2050, topsSpaced, pageCount))
        assertEquals(4, ReaderEngine.pageAt(4500, topsSpaced, pageCount))
        assertEquals(4, ReaderEngine.pageAt(7000, topsSpaced, pageCount)) // beyond bounds clamps to last
        assertEquals(0, ReaderEngine.pageAt(-100, topsSpaced, pageCount)) // negative clamps to 0
    }

    @Test
    fun testDynamicCacheSizingAndEviction() {
        // Budget calculation test
        assertEquals(8, ReaderEngine.calculateMaxCacheEntries(maxMemoryBytes = 256L * 1024 * 1024))
        assertEquals(10, ReaderEngine.calculateMaxCacheEntries(maxMemoryBytes = 512L * 1024 * 1024))
        assertEquals(21, ReaderEngine.calculateMaxCacheEntries(maxMemoryBytes = 1024L * 1024 * 1024))
        assertEquals(32, ReaderEngine.calculateMaxCacheEntries(maxMemoryBytes = 2048L * 1024 * 1024))
        assertEquals(32, ReaderEngine.calculateMaxCacheEntries(maxMemoryBytes = 4096L * 1024 * 1024))

        // LRU Cache eviction and texture flushing
        var flushedCount = 0
        val maxEntries = 3

        class FlushableImage(w: Int, h: Int) : BufferedImage(w, h, TYPE_INT_RGB) {
            override fun flush() {
                flushedCount++
                super.flush()
            }
        }

        val testCache = object : LinkedHashMap<Int, BufferedImage>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, BufferedImage>): Boolean {
                val evict = size > maxEntries
                if (evict) {
                    eldest.value.flush()
                }
                return evict
            }
        }

        testCache[0] = FlushableImage(10, 10)
        testCache[1] = FlushableImage(10, 10)
        testCache[2] = FlushableImage(10, 10)
        assertEquals(0, flushedCount)

        // Adding 4th entry exceeds maxEntries (3) -> eldest entry (0) must be evicted and flushed
        testCache[3] = FlushableImage(10, 10)
        assertEquals(1, flushedCount)
        assertFalse(testCache.containsKey(0))
        assertTrue(testCache.containsKey(1))
        assertTrue(testCache.containsKey(2))
        assertTrue(testCache.containsKey(3))

        // Access entry 1 (makes 1 most recently used)
        testCache[1]

        // Adding 5th entry -> eldest entry is now (2) -> must be evicted and flushed
        testCache[4] = FlushableImage(10, 10)
        assertEquals(2, flushedCount)
        assertFalse(testCache.containsKey(2))
        assertTrue(testCache.containsKey(1))
    }

    @Test
    fun testColorFilterTransformations() {
        val w = 10
        val h = 10
        val src = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)

        // 1. Filter: NONE returns same image reference
        val noneResult = MangaImageEnhancer.applyColorFilter(src, MangaImageEnhancer.ColorFilterMode.NONE)
        assertEquals(src, noneResult)

        // 2. Filter: GRAYSCALE
        // Pure Red (255, 0, 0): lum = (299 * 255) / 1000 = 76
        src.setRGB(0, 0, 0xFF0000)
        val grayResult = MangaImageEnhancer.applyColorFilter(src, MangaImageEnhancer.ColorFilterMode.GRAYSCALE)
        val grayPx = grayResult.getRGB(0, 0)
        val gr = (grayPx shr 16) and 0xFF
        val gg = (grayPx shr 8) and 0xFF
        val gb = grayPx and 0xFF
        assertEquals(gr, gg)
        assertEquals(gg, gb)
        assertEquals(76, gr)

        // 3. Filter: INVERTED (Night Mode)
        // Red (255, 0, 128) -> Inverted: (0, 255, 127)
        src.setRGB(1, 1, (255 shl 16) or (0 shl 8) or 128)
        val invResult = MangaImageEnhancer.applyColorFilter(src, MangaImageEnhancer.ColorFilterMode.INVERTED)
        val invPx = invResult.getRGB(1, 1)
        val ir = (invPx shr 16) and 0xFF
        val ig = (invPx shr 8) and 0xFF
        val ib = invPx and 0xFF
        assertEquals(0, ir)
        assertEquals(255, ig)
        assertEquals(127, ib)

        // 4. Filter: SEPIA (Warm Tint)
        // Neutral gray (100, 100, 100) -> Warm sepia hue where red > green > blue
        src.setRGB(2, 2, (100 shl 16) or (100 shl 8) or 100)
        val sepiaResult = MangaImageEnhancer.applyColorFilter(src, MangaImageEnhancer.ColorFilterMode.SEPIA)
        val sepiaPx = sepiaResult.getRGB(2, 2)
        val sr = (sepiaPx shr 16) and 0xFF
        val sg = (sepiaPx shr 8) and 0xFF
        val sb = sepiaPx and 0xFF
        assertTrue(sr > sg, "Sepia red component ($sr) must be greater than green ($sg)")
        assertTrue(sg > sb, "Sepia green component ($sg) must be greater than blue ($sb)")
        assertEquals(135, sr)
        assertEquals(120, sg)
        assertEquals(93, sb)
    }
}
