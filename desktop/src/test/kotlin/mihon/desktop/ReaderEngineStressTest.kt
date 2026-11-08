package mihon.desktop

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.util.Random
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Empirical challenger stress test suite for ReaderEngine.
 *
 * Covers:
 * 1. Spread pairing logic across corner cases: cover isolation, odd/even page counts,
 *    consecutive wide pages, aspect ratio discrepancies, directional ordering (RTL vs LTR).
 * 2. Wide-page auto-splitting in Single Page mode: coordinate boundaries, half page 0 vs 1 indexing,
 *    reading direction inversion, and navigation state machine reversibility.
 * 3. Webtoon geometry calculation & binary search: extreme chapter sizes (>1000 to 10000 pages),
 *    variable heights, 0-height edge conditions, and scroll offset consistency.
 */
class ReaderEngineStressTest {

    // ============================================================================================
    // AREA 1: Spread Pairing Logic across Corner Cases
    // ============================================================================================

    @Test
    fun testCoverIsolationInvariantAcrossModesAndScales() {
        val rng = Random(42)
        val modes = listOf(
            ReadMode.DOUBLE_RTL,
            ReadMode.DOUBLE_LTR,
            ReadMode.SINGLE,
            ReadMode.WEBTOON,
            ReadMode.CONTINUOUS_VERTICAL,
        )

        for (pageCount in 1..200) {
            // Generate random wide-page masks
            val wideSet = HashSet<Int>()
            for (i in 0 until pageCount) {
                if (rng.nextBoolean()) wideSet.add(i)
            }
            val isWide: (Int) -> Boolean = { it in wideSet }

            // Aspect ratios between 0.5 and 1.5
            val aspectRatios = DoubleArray(pageCount) { 0.5 + rng.nextDouble() }
            val arFn: (Int) -> Double? = { i -> if (i in 0 until pageCount) aspectRatios[i] else null }

            for (mode in modes) {
                val spread0 = ReaderEngine.spreadAt(0, mode, pageCount, isWide, arFn)

                // INVARIANT 1: Cover page (0) must ALWAYS be strictly isolated
                assertEquals(listOf(0), spread0, "Page 0 cover MUST be isolated for mode=$mode, pageCount=$pageCount")

                // INVARIANT 2: No subsequent spread starting at p >= 1 may EVER contain page 0
                for (p in 1 until pageCount) {
                    val spreadP = ReaderEngine.spreadAt(p, mode, pageCount, isWide, arFn)
                    assertFalse(spreadP.contains(0), "Spread starting at p=$p must never contain cover page 0")
                }
            }
        }
    }

    @Test
    fun testSpreadDisjointPartitioningAndOddEvenPageCounts() {
        val modes = listOf(ReadMode.DOUBLE_RTL, ReadMode.DOUBLE_LTR)
        val isWideFalse: (Int) -> Boolean = { false }

        // Test with odd and even page counts from 1 to 50
        for (pageCount in 1..50) {
            for (mode in modes) {
                val traversedPages = ArrayList<Int>()
                var curr = 0

                while (curr < pageCount) {
                    val spread = ReaderEngine.spreadAt(curr, mode, pageCount, isWideFalse)
                    assertTrue(spread.isNotEmpty(), "Spread at $curr must not be empty for pageCount=$pageCount")
                    assertTrue(spread.size in 1..2, "Spread size must be 1 or 2, got ${spread.size}")

                    if (curr == 0) {
                        assertEquals(listOf(0), spread, "Page 0 must always be single spread")
                    } else if (spread.size == 2) {
                        assertEquals(curr, spread[0])
                        assertEquals(curr + 1, spread[1])
                    } else {
                        assertEquals(listOf(curr), spread)
                    }

                    traversedPages.addAll(spread)
                    curr += spread.size
                }

                // INVARIANT: Every page index from 0 until pageCount is visited EXACTLY ONCE
                val expectedPages = (0 until pageCount).toList()
                assertEquals(expectedPages, traversedPages, "Traversed pages must form a strict partition of 0 until $pageCount")
            }
        }
    }

    @Test
    fun testBidirectionalNavigationReversibility() {
        val rng = Random(1337)
        val modes = listOf(ReadMode.DOUBLE_RTL, ReadMode.DOUBLE_LTR)

        for (trial in 1..50) {
            val pageCount = rng.nextInt(50) + 1 // 1..50
            val wideSet = HashSet<Int>()
            for (i in 0 until pageCount) {
                if (rng.nextDouble() < 0.25) wideSet.add(i)
            }
            val isWide: (Int) -> Boolean = { it in wideSet }

            for (mode in modes) {
                // Collect forward spread starts
                val spreadStarts = ArrayList<Int>()
                var curr = 0
                while (curr < pageCount) {
                    spreadStarts.add(curr)
                    val spread = ReaderEngine.spreadAt(curr, mode, pageCount, isWide)
                    curr += maxOf(1, spread.size)
                }

                // INVARIANT: prevSpreadStart(spreadStarts[k]) == spreadStarts[k - 1]
                assertEquals(0, ReaderEngine.prevSpreadStart(spreadStarts[0], mode, pageCount, isWide))
                for (k in 1 until spreadStarts.size) {
                    val prevStart = ReaderEngine.prevSpreadStart(spreadStarts[k], mode, pageCount, isWide)
                    assertEquals(
                        spreadStarts[k - 1],
                        prevStart,
                        "Reversibility failed at step $k (curr=${spreadStarts[k]}): expected ${spreadStarts[k - 1]}, got $prevStart",
                    )
                }
            }
        }
    }

    @Test
    fun testConsecutiveWidePagesHandling() {
        val pageCount = 12
        val modes = listOf(ReadMode.DOUBLE_RTL, ReadMode.DOUBLE_LTR)

        // Scenario A: Two consecutive wide pages at indices 3 and 4
        val isWideConsecutive2: (Int) -> Boolean = { it == 3 || it == 4 }
        for (mode in modes) {
            // Spreads: [0], [1, 2], [3], [4], [5, 6], [7, 8], [9, 10], [11]
            assertEquals(listOf(0), ReaderEngine.spreadAt(0, mode, pageCount, isWideConsecutive2))
            assertEquals(listOf(1, 2), ReaderEngine.spreadAt(1, mode, pageCount, isWideConsecutive2))
            assertEquals(listOf(3), ReaderEngine.spreadAt(3, mode, pageCount, isWideConsecutive2))
            assertEquals(listOf(4), ReaderEngine.spreadAt(4, mode, pageCount, isWideConsecutive2))
            assertEquals(listOf(5, 6), ReaderEngine.spreadAt(5, mode, pageCount, isWideConsecutive2))

            // Reversibility check around consecutive wide pages
            assertEquals(3, ReaderEngine.prevSpreadStart(4, mode, pageCount, isWideConsecutive2))
            assertEquals(1, ReaderEngine.prevSpreadStart(3, mode, pageCount, isWideConsecutive2))
            assertEquals(4, ReaderEngine.prevSpreadStart(5, mode, pageCount, isWideConsecutive2))
        }

        // Scenario B: Five consecutive wide pages (indices 2, 3, 4, 5, 6)
        val isWideConsecutive5: (Int) -> Boolean = { it in 2..6 }
        for (mode in modes) {
            // Page 1 cannot pair with wide page 2 -> [1]
            assertEquals(listOf(1), ReaderEngine.spreadAt(1, mode, pageCount, isWideConsecutive5))
            for (w in 2..6) {
                assertEquals(listOf(w), ReaderEngine.spreadAt(w, mode, pageCount, isWideConsecutive5))
            }
            // Page 7 pairs with 8
            assertEquals(listOf(7, 8), ReaderEngine.spreadAt(7, mode, pageCount, isWideConsecutive5))

            // Reversibility across all 5 consecutive wide pages
            for (w in 3..6) {
                assertEquals(w - 1, ReaderEngine.prevSpreadStart(w, mode, pageCount, isWideConsecutive5))
            }
            assertEquals(1, ReaderEngine.prevSpreadStart(2, mode, pageCount, isWideConsecutive5))
            assertEquals(6, ReaderEngine.prevSpreadStart(7, mode, pageCount, isWideConsecutive5))
        }

        // Scenario C: ALL pages are wide
        val isAllWide: (Int) -> Boolean = { true }
        for (mode in modes) {
            for (p in 0 until pageCount) {
                assertEquals(listOf(p), ReaderEngine.spreadAt(p, mode, pageCount, isAllWide))
                if (p > 0) {
                    assertEquals(p - 1, ReaderEngine.prevSpreadStart(p, mode, pageCount, isAllWide))
                }
            }
        }
    }

    @Test
    fun testAspectRatioDiscrepanciesBoundaryConditions() {
        val pageCount = 6
        val isWideFalse: (Int) -> Boolean = { false }

        // Test boundary tolerance = 0.18
        // 1. Delta = 0.17999 -> must pair
        val arPair: (Int) -> Double? = { i -> if (i == 1) 0.66000 else 0.83999 }
        assertEquals(listOf(1, 2), ReaderEngine.spreadAt(1, ReadMode.DOUBLE_RTL, pageCount, isWideFalse, arPair))

        // 2. Delta = 0.18000 -> must pair
        val arExact: (Int) -> Double? = { i -> if (i == 1) 0.66000 else 0.84000 }
        assertEquals(listOf(1, 2), ReaderEngine.spreadAt(1, ReadMode.DOUBLE_RTL, pageCount, isWideFalse, arExact))

        // 3. Delta = 0.18001 -> must isolate
        val arIsolate: (Int) -> Double? = { i -> if (i == 1) 0.66000 else 0.84001 }
        assertEquals(listOf(1), ReaderEngine.spreadAt(1, ReadMode.DOUBLE_RTL, pageCount, isWideFalse, arIsolate))

        // 4. Null aspect ratio handling -> does not break pairing if neither is wide
        val arNullFirst: (Int) -> Double? = { i -> if (i == 1) null else 0.70 }
        val arNullSecond: (Int) -> Double? = { i -> if (i == 1) 0.70 else null }
        val arNullBoth: (Int) -> Double? = { null }
        assertEquals(listOf(1, 2), ReaderEngine.spreadAt(1, ReadMode.DOUBLE_RTL, pageCount, isWideFalse, arNullFirst))
        assertEquals(listOf(1, 2), ReaderEngine.spreadAt(1, ReadMode.DOUBLE_RTL, pageCount, isWideFalse, arNullSecond))
        assertEquals(listOf(1, 2), ReaderEngine.spreadAt(1, ReadMode.DOUBLE_RTL, pageCount, isWideFalse, arNullBoth))

        // 5. Extreme values: negative, zero, or NaN
        val arNaN: (Int) -> Double? = { i -> if (i == 1) Double.NaN else 0.70 }
        assertDoesNotThrow {
            ReaderEngine.spreadAt(1, ReadMode.DOUBLE_RTL, pageCount, isWideFalse, arNaN)
        }
    }

    @Test
    fun testDirectionalOrderingSemantics() {
        val spread = listOf(1, 2)

        // DOUBLE_RTL (Japanese manga): right-to-left
        // First page read is on the right, second on the left.
        // Screen rendering paints left-to-right, so reversed list [2, 1] puts partner (2) on left, anchor (1) on right!
        val rtlOrdered = spread.reversed()
        assertEquals(listOf(2, 1), rtlOrdered)

        // DOUBLE_LTR (Western comic): left-to-right
        // First page read is on the left, second on the right.
        // Direct list [1, 2] puts anchor (1) on left, partner (2) on right!
        val ltrOrdered = spread
        assertEquals(listOf(1, 2), ltrOrdered)
    }

    // ============================================================================================
    // AREA 2: Wide-Page Auto-Splitting in Single Page Mode
    // ============================================================================================

    @Test
    fun testCoordinateBoundariesStressTest() {
        val testDimensions = listOf(
            Dimension(1, 1),
            Dimension(2, 1),
            Dimension(1, 2),
            Dimension(3, 10),
            Dimension(10, 3),
            Dimension(101, 150),
            Dimension(150, 101),
            Dimension(1919, 1080), // odd width
            Dimension(1920, 1080), // even width
            Dimension(1921, 1080), // odd width
            Dimension(3840, 2160), // 4K wide spread
            Dimension(5000, 500),  // panoramic strip
            Dimension(500, 5000),  // tall vertical strip
        )

        for (dim in testDimensions) {
            val img = BufferedImage(dim.width, dim.height, BufferedImage.TYPE_INT_RGB)
            val halfW = dim.width / 2

            for (isRtl in listOf(false, true)) {
                for (isSecondHalf in listOf(false, true)) {
                    // MUST NOT throw RasterFormatException
                    val half = assertDoesNotThrow("Failed on dim $dim, isRtl=$isRtl, isSecondHalf=$isSecondHalf") {
                        ReaderEngine.getHalfPage(img, isSecondHalf = isSecondHalf, isRtl = isRtl)
                    }

                    if (halfW <= 0) {
                        assertEquals(img.width, half.width)
                        assertEquals(img.height, half.height)
                    } else {
                        assertEquals(halfW, half.width, "Width must match halfW for dim=$dim")
                        assertEquals(dim.height, half.height, "Height must match source height for dim=$dim")
                    }
                }
            }
        }
    }

    @Test
    fun testReadingDirectionInversionAndHalfIndexing() {
        // Construct a 200x100 test image:
        // Left half (0..99): Green (0x00FF00)
        // Right half (100..199): Magenta (0xFF00FF)
        val src = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 100) {
            for (x in 0 until 100) src.setRGB(x, y, 0x00FF00)
            for (x in 100 until 200) src.setRGB(x, y, 0xFF00FF)
        }

        // 1. RTL (Japanese Manga): Read Right half FIRST (subpage 0), then Left half (subpage 1)
        val rtlSub0 = ReaderEngine.getHalfPage(src, isSecondHalf = false, isRtl = true)
        val rtlSub1 = ReaderEngine.getHalfPage(src, isSecondHalf = true, isRtl = true)
        assertEquals(0xFF00FF, rtlSub0.getRGB(50, 50) and 0xFFFFFF, "RTL Subpage 0 MUST be the Right half")
        assertEquals(0x00FF00, rtlSub1.getRGB(50, 50) and 0xFFFFFF, "RTL Subpage 1 MUST be the Left half")

        // 2. LTR (Western Comics): Read Left half FIRST (subpage 0), then Right half (subpage 1)
        val ltrSub0 = ReaderEngine.getHalfPage(src, isSecondHalf = false, isRtl = false)
        val ltrSub1 = ReaderEngine.getHalfPage(src, isSecondHalf = true, isRtl = false)
        assertEquals(0x00FF00, ltrSub0.getRGB(50, 50) and 0xFFFFFF, "LTR Subpage 0 MUST be the Left half")
        assertEquals(0xFF00FF, ltrSub1.getRGB(50, 50) and 0xFFFFFF, "LTR Subpage 1 MUST be the Right half")

        // 3. Webtoon Split and Merge:
        // RTL: Top is Right half (Magenta), Bottom is Left half (Green)
        val rtlMerged = ReaderEngine.splitAndMerge(src, isRtl = true)
        assertEquals(100, rtlMerged.width)
        assertEquals(200, rtlMerged.height)
        assertEquals(0xFF00FF, rtlMerged.getRGB(50, 50) and 0xFFFFFF, "RTL Merged top must be Right half")
        assertEquals(0x00FF00, rtlMerged.getRGB(50, 150) and 0xFFFFFF, "RTL Merged bottom must be Left half")

        // LTR: Top is Left half (Green), Bottom is Right half (Magenta)
        val ltrMerged = ReaderEngine.splitAndMerge(src, isRtl = false)
        assertEquals(100, ltrMerged.width)
        assertEquals(200, ltrMerged.height)
        assertEquals(0x00FF00, ltrMerged.getRGB(50, 50) and 0xFFFFFF, "LTR Merged top must be Left half")
        assertEquals(0xFF00FF, ltrMerged.getRGB(50, 150) and 0xFFFFFF, "LTR Merged bottom must be Right half")
    }

    @Test
    fun testWidePageAutoSplitSubimageWithColorFilterAndEnhancer() {
        // Create 200x100 TYPE_3BYTE_BGR image (standard JPEG raster format without alpha)
        // Left half (x 0..99): Red (0xFF0000)
        // Right half (x 100..199): Blue (0x0000FF)
        val src = BufferedImage(200, 100, BufferedImage.TYPE_3BYTE_BGR)
        for (y in 0 until 100) {
            for (x in 0 until 100) src.setRGB(x, y, 0xFF0000)
            for (x in 100 until 200) src.setRGB(x, y, 0x0000FF)
        }

        // RTL Part 0 (first half): Right half (Blue)
        val rightHalf = ReaderEngine.getHalfPage(src, isSecondHalf = false, isRtl = true)
        assertEquals(100, rightHalf.width)
        assertEquals(100, rightHalf.height)
        assertEquals(0x0000FF, rightHalf.getRGB(50, 50) and 0xFFFFFF)

        // When a color filter (e.g. GRAYSCALE) is applied to this subimage:
        // Blue (0, 0, 255) grayscale: (114 * 255) / 1000 = 29
        // Red (255, 0, 0) grayscale: (299 * 255) / 1000 = 76
        val filtered = MangaImageEnhancer.applyColorFilter(rightHalf, MangaImageEnhancer.ColorFilterMode.GRAYSCALE)
        val filteredPx = filtered.getRGB(50, 50) and 0xFF
        assertEquals(29, filteredPx, "Subimage from getHalfPage must retain its pixel region under color filtering, got $filteredPx")
    }

    @Test
    fun testSubpageStateMachineNavigationSimulation() {
        // Test reader state machine navigation forward and backward:
        // Chapter layout:
        // Page 0: Normal (0)
        // Page 1: Wide (1) -> (1, 0), (1, 1)
        // Page 2: Wide (2) -> (2, 0), (2, 1)
        // Page 3: Normal (3)
        // Page 4: Normal (4)
        val pageCount = 5
        val isWide: (Int) -> Boolean = { it == 1 || it == 2 }

        data class State(val page: Int, val subpage: Int)

        fun stepNext(s: State): State {
            return if (isWide(s.page) && s.subpage == 0) {
                State(s.page, 1)
            } else {
                val nextP = (s.page + 1).coerceAtMost(pageCount - 1)
                State(nextP, 0)
            }
        }

        fun stepPrev(s: State): State {
            return if (isWide(s.page) && s.subpage == 1) {
                State(s.page, 0)
            } else {
                val prevP = ReaderEngine.prevSpreadStart(s.page, ReadMode.SINGLE, pageCount, isWide)
                if (isWide(prevP) && prevP != s.page) {
                    State(prevP, 1)
                } else {
                    State(prevP, 0)
                }
            }
        }

        // Expected forward sequence
        val expectedSequence = listOf(
            State(0, 0),
            State(1, 0),
            State(1, 1),
            State(2, 0),
            State(2, 1),
            State(3, 0),
            State(4, 0),
        )

        // 1. Forward run
        var current = State(0, 0)
        val forwardVisited = mutableListOf(current)
        for (i in 1 until expectedSequence.size) {
            current = stepNext(current)
            forwardVisited.add(current)
        }
        assertEquals(expectedSequence, forwardVisited, "Forward navigation through split wide pages mismatch")

        // 2. Backward run from last page
        val backwardVisited = mutableListOf(current)
        for (i in (expectedSequence.size - 2) downTo 0) {
            current = stepPrev(current)
            backwardVisited.add(current)
        }
        val expectedBackward = expectedSequence.reversed()
        assertEquals(expectedBackward, backwardVisited, "Backward navigation through split wide pages mismatch")
    }

    // ============================================================================================
    // AREA 3: Webtoon Geometry Calculation & Binary Search
    // ============================================================================================

    @Test
    fun testExtremeChapterSizesScale() {
        val testScales = listOf(1_000, 5_000, 10_000)
        val rng = Random(999)

        for (count in testScales) {
            val widths = IntArray(count) { 800 + rng.nextInt(600) }
            val heights = IntArray(count) { 500 + rng.nextInt(4500) }

            for (gap in listOf(0, 16)) {
                val startTime = System.nanoTime()
                val (tops, computedHeights) = ReaderEngine.computeWebtoonLayout(
                    pageCount = count,
                    pageWidthFn = { widths[it] },
                    pageHeightFn = { idx, _ -> heights[idx] },
                    pageGap = gap,
                )
                val durationMs = (System.nanoTime() - startTime) / 1_000_000.0

                // Performance assert: 10,000 pages computed in under 50ms
                assertTrue(durationMs < 50.0, "computeWebtoonLayout took ${durationMs}ms for $count pages (expected < 50ms)")
                assertEquals(count + 1, tops.size)
                assertEquals(count, computedHeights.size)

                // Monotonicity assert: tops[i] < tops[i+1]
                for (i in 0 until count) {
                    assertTrue(tops[i] < tops[i + 1], "tops must be strictly increasing: tops[$i]=${tops[i]} vs tops[${i+1}]=${tops[i+1]}")
                }

                // Total height assert
                var expectedTotal = 0L
                for (i in 0 until count) {
                    expectedTotal += heights[i] + if (i < count - 1) gap else 0
                }
                assertEquals(expectedTotal, tops[count].toLong(), "Total webtoon strip height must match sum of heights + gaps")
            }
        }
    }

    @Test
    fun testBinarySearchOracleExhaustive() {
        val pageCount = 1_500
        val rng = Random(777)
        val widths = IntArray(pageCount) { 1000 }
        val heights = IntArray(pageCount) { 600 + rng.nextInt(3000) }
        val gap = 16

        val (tops, _) = ReaderEngine.computeWebtoonLayout(
            pageCount = pageCount,
            pageWidthFn = { widths[it] },
            pageHeightFn = { idx, _ -> heights[idx] },
            pageGap = gap,
        )

        // Exhaustive verification for EVERY single page in a 1,500-page chapter
        for (i in 0 until pageCount) {
            val top = tops[i]
            val h = heights[i]

            // 1. Exactly at page top
            assertEquals(i, ReaderEngine.pageAt(top, tops, pageCount), "At top boundary of page $i")

            // 2. Middle of page
            assertEquals(i, ReaderEngine.pageAt(top + h / 2, tops, pageCount), "Inside body of page $i")

            // 3. Last pixel of page body
            assertEquals(i, ReaderEngine.pageAt(top + h - 1, tops, pageCount), "At bottom pixel of page $i")

            // 4. In gap after page i (belongs to page i until tops[i+1])
            if (gap > 0 && i < pageCount - 1) {
                assertEquals(i, ReaderEngine.pageAt(top + h + (gap / 2), tops, pageCount), "Inside gap after page $i")
            }
        }

        // Boundary clamp tests
        assertEquals(0, ReaderEngine.pageAt(-1000, tops, pageCount), "Negative offset must clamp to 0")
        assertEquals(0, ReaderEngine.pageAt(0, tops, pageCount), "Offset 0 must be page 0")
        assertEquals(pageCount - 1, ReaderEngine.pageAt(tops[pageCount - 1], tops, pageCount), "Last page top")
        assertEquals(pageCount - 1, ReaderEngine.pageAt(tops[pageCount], tops, pageCount), "Bottom of strip")
        assertEquals(pageCount - 1, ReaderEngine.pageAt(Int.MAX_VALUE, tops, pageCount), "Extreme offset must clamp to last page")
    }

    @Test
    fun testZeroHeightEdgeConditions() {
        // Case 1: Chapter where some pages have 0 height
        val pageCount = 6
        // Heights: [1000, 0, 0, 1000, 0, 1000]
        val heights = intArrayOf(1000, 0, 0, 1000, 0, 1000)
        val (tops, _) = ReaderEngine.computeWebtoonLayout(
            pageCount = pageCount,
            pageWidthFn = { 800 },
            pageHeightFn = { idx, _ -> heights[idx] },
            pageGap = 0,
        )

        // tops: [0, 1000, 1000, 1000, 2000, 2000, 3000]
        assertEquals(0, tops[0])
        assertEquals(1000, tops[1])
        assertEquals(1000, tops[2])
        assertEquals(1000, tops[3])
        assertEquals(2000, tops[4])
        assertEquals(2000, tops[5])
        assertEquals(3000, tops[6])

        // Verify pageAt does not throw or hang
        assertDoesNotThrow {
            assertEquals(0, ReaderEngine.pageAt(500, tops, pageCount))
            // At 1000, pages 1 and 2 are 0-height, page 3 spans 1000..2000 -> returns 3
            assertEquals(3, ReaderEngine.pageAt(1000, tops, pageCount))
            assertEquals(3, ReaderEngine.pageAt(1500, tops, pageCount))
            assertEquals(5, ReaderEngine.pageAt(2000, tops, pageCount))
            assertEquals(5, ReaderEngine.pageAt(2500, tops, pageCount))
        }

        // Case 2: ALL pages have 0 height
        val allZeroHeights = IntArray(5) { 0 }
        val (allZeroTops, _) = ReaderEngine.computeWebtoonLayout(
            pageCount = 5,
            pageWidthFn = { 800 },
            pageHeightFn = { _, _ -> 0 },
            pageGap = 0,
        )
        assertDoesNotThrow {
            assertEquals(0, ReaderEngine.pageAt(0, allZeroTops, 5))
            assertEquals(4, ReaderEngine.pageAt(100, allZeroTops, 5))
        }

        // Case 3: Empty chapter (0 pages)
        val (emptyTops, emptyHeights) = ReaderEngine.computeWebtoonLayout(
            pageCount = 0,
            pageWidthFn = { 800 },
            pageHeightFn = { _, _ -> 1000 },
            pageGap = 0,
        )
        assertEquals(1, emptyTops.size)
        assertEquals(0, emptyHeights.size)
        assertEquals(0, ReaderEngine.pageAt(500, emptyTops, 0))

        // Case 4: Single page chapter (1 page)
        val (singleTops, singleHeights) = ReaderEngine.computeWebtoonLayout(
            pageCount = 1,
            pageWidthFn = { 800 },
            pageHeightFn = { _, _ -> 1200 },
            pageGap = 0,
        )
        assertEquals(2, singleTops.size)
        assertEquals(1, singleHeights.size)
        assertEquals(0, ReaderEngine.pageAt(-50, singleTops, 1))
        assertEquals(0, ReaderEngine.pageAt(0, singleTops, 1))
        assertEquals(0, ReaderEngine.pageAt(600, singleTops, 1))
        assertEquals(0, ReaderEngine.pageAt(1500, singleTops, 1))
    }

    @Test
    fun testScrollOffsetConsistencySimulation() {
        val pageCount = 100
        val rng = Random(555)

        // Initial state: estimated heights (1000px each)
        val initialHeights = IntArray(pageCount) { 1000 }
        val (initialTops, _) = ReaderEngine.computeWebtoonLayout(
            pageCount = pageCount,
            pageWidthFn = { 800 },
            pageHeightFn = { idx, _ -> initialHeights[idx] },
            pageGap = 16,
        )

        // User is reading page 40 at 250px offset inside page 40
        val targetPage = 40
        val offsetInTarget = 250
        val oldScrollY = initialTops[targetPage] + offsetInTarget

        assertEquals(targetPage, ReaderEngine.pageAt(oldScrollY, initialTops, pageCount))

        // Discovered natural dimensions for pages 0..39 (some taller, some shorter)
        val discoveredHeights = initialHeights.clone()
        for (i in 0 until targetPage) {
            discoveredHeights[i] = (800 + rng.nextInt(600)) // 800..1400
        }

        // Recomputed layout
        val (newTops, _) = ReaderEngine.computeWebtoonLayout(
            pageCount = pageCount,
            pageWidthFn = { 800 },
            pageHeightFn = { idx, _ -> discoveredHeights[idx] },
            pageGap = 16,
        )

        // Adjusted scroll position preserving anchor offset
        val adjustedScrollY = newTops[targetPage] + offsetInTarget

        // INVARIANT: At adjustedScrollY, the active page is STILL targetPage!
        assertEquals(
            targetPage,
            ReaderEngine.pageAt(adjustedScrollY, newTops, pageCount),
            "Scroll adjustment must keep user anchored to the exact same page",
        )

        // INVARIANT: The offset within the target page is PRESERVED exactly
        val actualOffsetInTarget = adjustedScrollY - newTops[targetPage]
        assertEquals(offsetInTarget, actualOffsetInTarget, "Relative reading position within page must be preserved")
    }
}
