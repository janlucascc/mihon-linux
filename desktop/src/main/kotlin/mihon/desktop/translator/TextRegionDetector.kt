package mihon.desktop.translator

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.ComponentSampleModel
import java.awt.image.DataBufferByte
import kotlin.math.roundToInt

/**
 * Pure-JVM manga speech bubble and text cluster detector.
 * Operates on a downscaled grayscale buffer to stay well within MemoryManager budgets.
 */
class TextRegionDetector(
    private val maxWorkDimension: Int = 1024,
    private val brightThreshold: Int = 228,
) : TextDetector {

    private data class CharComponent(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val pixelCount: Int,
    ) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
        val bboxArea: Int get() = width * height
    }

    private data class CandidateBox(
        val bounds: NormalizedRect,
        val confidence: Float,
        val fromBubblePass: Boolean,
    )

    override fun detectRegions(image: BufferedImage): List<DetectedTextRegion> {
        if (image.width < 8 || image.height < 8) return emptyList()

        if (image.height > 1900 && image.height.toDouble() > image.width * 1.8) {
            return detectRegionsTiledTallStrip(image)
        }

        val (gray, workW, workH, maxChroma) = downscaleToGrayAndChroma(
            image = image,
            srcY = 0,
            srcH = image.height,
        )
        if (workW < 8 || workH < 8) return emptyList()

        val candidates = detectCandidatesInWorkBuffer(gray, maxChroma, workW, workH)
        val combined = candidates.map { candidate ->
            val orientation = inferOrientation(gray, workW, workH, candidate.bounds)
            DetectedTextRegion(
                bounds = candidate.bounds,
                orientation = orientation,
                confidence = candidate.confidence,
            )
        }
        return sortInMangaReadingOrder(combined)
    }

    private fun detectRegionsTiledTallStrip(image: BufferedImage): List<DetectedTextRegion> {
        val fullW = image.width
        val fullH = image.height
        val sliceH = 2000.coerceAtMost(fullH)
        val stepY = 1400 // 600px overlap prevents slicing normal bubbles at slice seams

        val allBubbles = mutableListOf<CandidateBox>()
        val allClusters = mutableListOf<CandidateBox>()
        val orientationByBox = ArrayList<Pair<NormalizedRect, TextOrientation>>()

        var y = 0
        while (y < fullH) {
            val endY = (y + sliceH).coerceAtMost(fullH)
            val curH = endY - y
            if (curH < 16) break

            val (gray, workW, workH, maxChroma) = downscaleToGrayAndChroma(
                image = image,
                srcY = y,
                srcH = curH,
                forcedMaxWidth = 760,
            )
            if (workW >= 8 && workH >= 8 && hasContrast(gray)) {
                val (sliceBubbles, sliceClusters) = detectBubblesAndClusters(gray, maxChroma, workW, workH)

                fun remapToFull(local: CandidateBox): CandidateBox {
                    val globalY = (y + local.bounds.y * curH) / fullH.toFloat()
                    val globalH = (local.bounds.height * curH) / fullH.toFloat()
                    val globalBounds = NormalizedRect(
                        x = local.bounds.x,
                        y = globalY,
                        width = local.bounds.width,
                        height = globalH,
                    ).clamped()
                    val orient = inferOrientation(gray, workW, workH, local.bounds)
                    orientationByBox.add(globalBounds to orient)
                    return local.copy(bounds = globalBounds)
                }

                for (b in sliceBubbles) {
                    val touchesCutTop = y > 0 && b.bounds.y <= (4f / workH)
                    val touchesCutBottom = endY < fullH && b.bounds.bottom >= 1f - (4f / workH)
                    if (!touchesCutTop && !touchesCutBottom) {
                        allBubbles.add(remapToFull(b))
                    } else if (endY == fullH || y == 0 || b.bounds.height < 0.25f) {
                        allBubbles.add(remapToFull(b))
                    }
                }
                for (c in sliceClusters) {
                    val touchesCutTop = y > 0 && c.bounds.y <= (4f / workH)
                    val touchesCutBottom = endY < fullH && c.bounds.bottom >= 1f - (4f / workH)
                    if ((!touchesCutTop && !touchesCutBottom) || endY == fullH || y == 0 || c.bounds.height >= 0.28f) {
                        allClusters.add(remapToFull(c))
                    }
                }
            }

            if (endY >= fullH) break
            y += stepY
        }

        val mergedBubbles = mergeOverlapping(allBubbles)
        val mergedClusters = mergeOverlapping(allClusters)
        val filteredClusters = filterClustersAgainstBubbles(mergedClusters, mergedBubbles)

        val combined = (mergedBubbles + filteredClusters).map { candidate ->
            val bestOrient = orientationByBox
                .maxByOrNull { (rect, _) -> candidate.bounds.iou(rect) }
                ?.takeIf { candidate.bounds.iou(it.first) > 0.25f }
                ?.second
                ?: if (candidate.bounds.height * fullH > candidate.bounds.width * fullW * 1.35f) {
                    TextOrientation.VERTICAL
                } else {
                    TextOrientation.HORIZONTAL
                }
            DetectedTextRegion(
                bounds = candidate.bounds,
                orientation = bestOrient,
                confidence = candidate.confidence,
            )
        }
        return sortInMangaReadingOrder(combined)
    }

    private fun hasContrast(gray: ByteArray): Boolean {
        var minLum = 255
        var maxLum = 0
        for (b in gray) {
            val lum = b.toInt() and 0xFF
            if (lum < minLum) minLum = lum
            if (lum > maxLum) maxLum = lum
        }
        return maxLum - minLum >= 25
    }

    private fun detectCandidatesInWorkBuffer(
        gray: ByteArray,
        maxChroma: ByteArray,
        workW: Int,
        workH: Int,
    ): List<CandidateBox> {
        if (!hasContrast(gray)) return emptyList()
        val (bubbles, clusters) = detectBubblesAndClusters(gray, maxChroma, workW, workH)
        return bubbles + clusters
    }

    private fun detectBubblesAndClusters(
        gray: ByteArray,
        maxChroma: ByteArray,
        workW: Int,
        workH: Int,
    ): Pair<List<CandidateBox>, List<CandidateBox>> {
        val bubbleCandidates = detectClosedBubbles(gray, maxChroma, workW, workH)
        val mergedBubbles = mergeOverlapping(bubbleCandidates)

        val clusterCandidates = detectStrokeClusters(gray, maxChroma, workW, workH, mergedBubbles)
        val nonSwallowingClusters = filterClustersAgainstBubbles(mergeOverlapping(clusterCandidates), mergedBubbles)
        val mergedClusters = mergeInlineClusters(nonSwallowingClusters, mergedBubbles, workW, workH)
        val filteredClusters = filterClustersAgainstBubbles(mergedClusters, mergedBubbles)

        return mergedBubbles to filteredClusters
    }

    private fun filterClustersAgainstBubbles(
        clusters: List<CandidateBox>,
        bubbles: List<CandidateBox>,
    ): List<CandidateBox> {
        if (bubbles.isEmpty() || clusters.isEmpty()) return clusters
        return clusters.filter { cluster ->
            val clusterArea = cluster.bounds.area
            bubbles.none { bubble ->
                val inter = bubble.bounds.intersectionArea(cluster.bounds)
                if (inter <= 0f) return@none false
                val bubbleArea = bubble.bounds.area
                (clusterArea > 0f && inter / clusterArea > 0.40f) ||
                    (bubbleArea > 0f && inter / bubbleArea > 0.35f) ||
                    bubble.bounds.iou(cluster.bounds) > 0.25f
            }
        }
    }

    private data class WorkBuffers(
        val gray: ByteArray,
        val width: Int,
        val height: Int,
        val maxChroma: ByteArray,
    )

    private fun downscaleToGrayAndChroma(
        image: BufferedImage,
        srcY: Int,
        srcH: Int,
        forcedMaxWidth: Int? = null,
    ): WorkBuffers {
        val w = image.width
        val h = srcH
        val scale = if (forcedMaxWidth != null) {
            minOf(1.0, forcedMaxWidth.toDouble() / w)
        } else {
            when {
                h > w * 2.5 -> minOf(1.0, minOf(720.0 / w, 3072.0 / h))
                w > h * 2.5 -> minOf(1.0, minOf(3072.0 / w, 720.0 / h))
                else -> minOf(1.0, maxWorkDimension.toDouble() / maxOf(w, h))
            }
        }
        val workW = (w * scale).roundToInt().coerceAtLeast(1)
        val workH = (h * scale).roundToInt().coerceAtLeast(1)

        val workRgb = BufferedImage(workW, workH, BufferedImage.TYPE_INT_RGB)
        val g2 = workRgb.createGraphics()
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.color = Color.WHITE
            g2.fillRect(0, 0, workW, workH)
            g2.drawImage(image, 0, 0, workW, workH, 0, srcY, w, srcY + srcH, null)
        } finally {
            g2.dispose()
        }

        val total = workW * workH
        val pixels = IntArray(total)
        workRgb.getRGB(0, 0, workW, workH, pixels, 0, workW)
        workRgb.flush()

        val gray = ByteArray(total)
        val chroma = ByteArray(total)
        for (i in 0 until total) {
            val rgb = pixels[i]
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            val lum = ((r * 299 + g * 587 + b * 114) / 1000).coerceIn(0, 255)
            val maxC = maxOf(r, maxOf(g, b))
            val minC = minOf(r, minOf(g, b))
            gray[i] = lum.toByte()
            chroma[i] = (maxC - minC).coerceIn(0, 255).toByte()
        }
        return WorkBuffers(gray, workW, workH, chroma)
    }

    private fun detectClosedBubbles(
        gray: ByteArray,
        maxChroma: ByteArray,
        workW: Int,
        workH: Int,
    ): List<CandidateBox> {
        val total = workW * workH
        val visited = BooleanArray(total)
        val queue = IntArray(total)
        val results = mutableListOf<CandidateBox>()

        val minBubbleW = maxOf(28, (workW * 0.035f).toInt())
        val minBubbleH = maxOf(34, (workH * 0.035f).toInt())

        for (startY in 0 until workH) {
            val rowOff = startY * workW
            for (startX in 0 until workW) {
                val startIdx = rowOff + startX
                if (visited[startIdx]) continue
                val startLum = gray[startIdx].toInt() and 0xFF
                if (startLum < brightThreshold) continue

                visited[startIdx] = true
                var qHead = 0
                var qTail = 0
                queue[qTail++] = startIdx

                var minX = startX
                var maxX = startX
                var minY = startY
                var maxY = startY
                var brightSum = 0L
                var brightChromaSum = 0L
                var borderFlags = 0

                while (qHead < qTail) {
                    val idx = queue[qHead++]
                    val cx = idx % workW
                    val cy = idx / workW
                    val lum = gray[idx].toInt() and 0xFF
                    brightSum += lum
                    brightChromaSum += (maxChroma[idx].toInt() and 0xFF)

                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    if (cx == 0) borderFlags = borderFlags or 1
                    if (cx == workW - 1) borderFlags = borderFlags or 2
                    if (cy == 0) borderFlags = borderFlags or 4
                    if (cy == workH - 1) borderFlags = borderFlags or 8

                    if (cx > 0) {
                        val n = idx - 1
                        if (!visited[n] && (gray[n].toInt() and 0xFF) >= brightThreshold) {
                            visited[n] = true
                            queue[qTail++] = n
                        }
                    }
                    if (cx + 1 < workW) {
                        val n = idx + 1
                        if (!visited[n] && (gray[n].toInt() and 0xFF) >= brightThreshold) {
                            visited[n] = true
                            queue[qTail++] = n
                        }
                    }
                    if (cy > 0) {
                        val n = idx - workW
                        if (!visited[n] && (gray[n].toInt() and 0xFF) >= brightThreshold) {
                            visited[n] = true
                            queue[qTail++] = n
                        }
                    }
                    if (cy + 1 < workH) {
                        val n = idx + workW
                        if (!visited[n] && (gray[n].toInt() and 0xFF) >= brightThreshold) {
                            visited[n] = true
                            queue[qTail++] = n
                        }
                    }
                }

                val pixelCount = qTail
                val bw = maxX - minX + 1
                val bh = maxY - minY + 1
                if (bw < minBubbleW || bh < minBubbleH) continue

                val bboxArea = bw * bh
                val areaFraction = bboxArea.toFloat() / total
                if (areaFraction < 0.0018f) continue

                val touchCount = Integer.bitCount(borderFlags)
                if (touchCount > 0) {
                    if (bw > workW * 0.65f || bh > workH * 0.65f || areaFraction > 0.25f || touchCount >= 3) {
                        continue
                    }
                } else if (bw > workW * 0.85f && bh > workH * 0.85f) {
                    continue
                }

                val fillRatio = pixelCount.toFloat() / bboxArea
                if (fillRatio < 0.28f) continue

                // Record scanline chord bounds of this bright component so corner art outside the oval is excluded.
                val rowMinX = IntArray(bh) { Int.MAX_VALUE }
                val rowMaxX = IntArray(bh) { Int.MIN_VALUE }
                val colMinY = IntArray(bw) { Int.MAX_VALUE }
                val colMaxY = IntArray(bw) { Int.MIN_VALUE }

                for (i in 0 until pixelCount) {
                    val idx = queue[i]
                    val px = idx % workW
                    val py = idx / workW
                    val ry = py - minY
                    val rx = px - minX
                    if (px < rowMinX[ry]) rowMinX[ry] = px
                    if (px > rowMaxX[ry]) rowMaxX[ry] = px
                    if (py < colMinY[rx]) colMinY[rx] = py
                    if (py > colMaxY[rx]) colMaxY[rx] = py
                }

                val brightMean = (brightSum / pixelCount).toInt()
                val brightChromaMean = (brightChromaSum / pixelCount).toInt()
                if (brightChromaMean > 52) continue

                val darkThresh = minOf(135, brightMean - 65)
                val minChordW = (bw * 0.14f).toInt().coerceAtLeast(4)
                val minChordH = (bh * 0.14f).toInt().coerceAtLeast(4)

                // Reject tiny jagged white-letter interior pockets (unlike smooth oval speech bubbles)
                if (bh < 44 && bw < 165 && fillRatio < 0.54f) continue

                var interiorArea = 0
                var darkInteriorCount = 0
                var strokeTransitions = 0
                var textMinX = Int.MAX_VALUE
                var textMaxX = Int.MIN_VALUE
                var textMinY = Int.MAX_VALUE
                var textMaxY = Int.MIN_VALUE

                for (py in (minY + 1) until maxY) {
                    val ry = py - minY
                    val rMin = rowMinX[ry]
                    val rMax = rowMaxX[ry]
                    if (rMin >= rMax || (rMax - rMin + 1) < minChordW) continue
                    val xStart = maxOf(minX + 1, rMin + 2)
                    val xEnd = minOf(maxX - 1, rMax - 2)
                    if (xStart > xEnd) continue

                    val pyOff = py * workW
                    var prevDark = false
                    for (px in xStart..xEnd) {
                        val rx = px - minX
                        if (py <= colMinY[rx] + 1 || py >= colMaxY[rx] - 1 || (colMaxY[rx] - colMinY[rx] + 1) < minChordH) {
                            prevDark = false
                            continue
                        }
                        interiorArea++
                        val lum = gray[pyOff + px].toInt() and 0xFF
                        val isDark = lum <= darkThresh
                        if (isDark) {
                            darkInteriorCount++
                            if (!prevDark) strokeTransitions++
                            if (px < textMinX) textMinX = px
                            if (px > textMaxX) textMaxX = px
                            if (py < textMinY) textMinY = py
                            if (py > textMaxY) textMaxY = py
                        }
                        prevDark = isDark
                    }
                }

                if (interiorArea < 240 || darkInteriorCount < 16 || strokeTransitions < 6) continue
                // Warm/skin-tinted artwork regions (e.g. pale faces with eyes/mouth) have high chroma and sparse strokes
                if (brightChromaMean > 22 && strokeTransitions < 55) continue
                if (brightChromaMean > 18 && (strokeTransitions < 16 || darkInteriorCount < 32)) continue
                val darkRatio = darkInteriorCount.toFloat() / interiorArea
                if (darkRatio !in 0.003f..0.60f) continue
                if (textMaxX - textMinX + 1 < 4 || textMaxY - textMinY + 1 < 4) continue

                val textSpanW = (textMaxX - textMinX + 1).coerceAtLeast(1)
                val textSpanH = (textMaxY - textMinY + 1).coerceAtLeast(1)
                val minBodyChordW = maxOf(minChordW, (textSpanW * 0.72f).toInt())

                var chordMinY = textMinY
                var chordMaxY = textMaxY
                for (py in minY..maxY) {
                    val ry = py - minY
                    if (rowMaxX[ry] >= rowMinX[ry] && (rowMaxX[ry] - rowMinX[ry] + 1) >= minChordW) {
                        if (py < chordMinY) chordMinY = py
                        if (py > chordMaxY) chordMaxY = py
                    }
                }
                var chordMinX = textMinX
                var chordMaxX = textMaxX
                for (px in minX..maxX) {
                    val rx = px - minX
                    if (colMaxY[rx] >= colMinY[rx] && (colMaxY[rx] - colMinY[rx] + 1) >= minChordH) {
                        if (px < chordMinX) chordMinX = px
                        if (px > chordMaxX) chordMaxX = px
                    }
                }

                // Expand smoothly within the verified clean oval cavity around the text so short/off-center
                // dialogue centers naturally inside the speech bubble without entering tails or hair leaks.
                val edgeGuard = maxOf(3, minOf(bw, bh) / 28).coerceAtMost(7)
                val largeLeakRisk = touchCount >= 2 || areaFraction > 0.07f || (touchCount == 1 && fillRatio < 0.55f)
                val maxExpandY = if (largeLeakRisk) {
                    maxOf(8, (textSpanH * 0.25f).toInt()).coerceAtMost(24)
                } else {
                    maxOf(18, (bh * 0.28f).toInt()).coerceAtMost(96)
                }
                val maxExpandX = if (largeLeakRisk) {
                    maxOf(8, (textSpanW * 0.25f).toInt()).coerceAtMost(28)
                } else {
                    maxOf(18, (bw * 0.25f).toInt()).coerceAtMost(96)
                }

                fun isCleanWhiteRow(py: Int): Boolean {
                    val ry = py - minY
                    if (ry !in 1 until (bh - 1)) return false
                    val rMin = rowMinX[ry]
                    val rMax = rowMaxX[ry]
                    if (rMax - rMin + 1 < minBodyChordW) return false
                    if (rMin > textMaxX || rMax < textMinX) return false
                    val xS = maxOf(minX + 1, rMin + edgeGuard)
                    val xE = minOf(maxX - 1, rMax - edgeGuard)
                    if (xS > xE) return false
                    val pyOff = py * workW
                    var darkCount = 0
                    for (px in xS..xE) {
                        if ((gray[pyOff + px].toInt() and 0xFF) <= darkThresh) {
                            darkCount++
                            if (darkCount > 2) return false
                        }
                    }
                    return true
                }

                var cavMinY = textMinY
                var stepUp = 0
                while (stepUp < maxExpandY && cavMinY - 1 > minY + edgeGuard && isCleanWhiteRow(cavMinY - 1)) {
                    cavMinY--
                    stepUp++
                }
                var cavMaxY = textMaxY
                var stepDown = 0
                while (stepDown < maxExpandY && cavMaxY + 1 < maxY - edgeGuard && isCleanWhiteRow(cavMaxY + 1)) {
                    cavMaxY++
                    stepDown++
                }

                // Measure the inner horizontal chord envelope across the expanded vertical band
                var bandMinX = minX + edgeGuard
                var bandMaxX = maxX - edgeGuard
                for (py in cavMinY..cavMaxY) {
                    val ry = py - minY
                    val rMin = (rowMinX[ry] + edgeGuard).coerceAtMost(textMinX)
                    val rMax = (rowMaxX[ry] - edgeGuard).coerceAtLeast(textMaxX)
                    if (rMin > bandMinX) bandMinX = rMin
                    if (rMax < bandMaxX) bandMaxX = rMax
                }

                val padX = maxOf(4, (bw * 0.06f).toInt()).coerceAtMost(10)
                val padY = maxOf(4, (bh * 0.06f).toInt()).coerceAtMost(10)
                val targetMinX = maxOf(textMinX - maxExpandX, bandMinX)
                val targetMaxX = minOf(textMaxX + maxExpandX, bandMaxX)
                val cropMinX = minOf(textMinX - padX, targetMinX)
                    .coerceAtLeast(minOf(textMinX, chordMinX))
                    .coerceAtLeast(minX)
                val cropMaxX = maxOf(textMaxX + padX, targetMaxX)
                    .coerceAtMost(maxOf(textMaxX, chordMaxX))
                    .coerceAtMost(maxX)
                val cropMinY = minOf(textMinY - padY, cavMinY)
                    .coerceAtLeast(minOf(textMinY, chordMinY))
                    .coerceAtLeast(minY)
                val cropMaxY = maxOf(textMaxY + padY, cavMaxY)
                    .coerceAtMost(maxOf(textMaxY, chordMaxY))
                    .coerceAtMost(maxY)

                if (cropMaxX <= cropMinX || cropMaxY <= cropMinY) continue

                val rect = NormalizedRect(
                    x = cropMinX.toFloat() / workW,
                    y = cropMinY.toFloat() / workH,
                    width = (cropMaxX - cropMinX + 1).toFloat() / workW,
                    height = (cropMaxY - cropMinY + 1).toFloat() / workH,
                ).clamped()
                if (rect.isEmpty) continue

                val conf = (0.85f + 0.13f * ((fillRatio - 0.30f) / 0.70f).coerceIn(0f, 1f)).coerceIn(0.85f, 0.98f)
                results.add(CandidateBox(rect, conf, fromBubblePass = true))
            }
        }
        return results
    }

    private fun detectStrokeClusters(
        gray: ByteArray,
        maxChroma: ByteArray,
        workW: Int,
        workH: Int,
        bubbles: List<CandidateBox>,
    ): List<CandidateBox> {
        val total = workW * workH
        val excluded = BooleanArray(total)
        val rimPad = 3
        for (b in bubbles) {
            val px = b.bounds.toPixelRect(workW, workH)
            val xStart = (px.x - rimPad).coerceAtLeast(0)
            val yStart = (px.y - rimPad).coerceAtLeast(0)
            val xEnd = (px.x + px.width + rimPad).coerceAtMost(workW)
            val yEnd = (px.y + px.height + rimPad).coerceAtMost(workH)
            for (y in yStart until yEnd) {
                val off = y * workW
                for (x in xStart until xEnd) {
                    excluded[off + x] = true
                }
            }
        }

        // Integral image (width+1) x (height+1)
        val stride = workW + 1
        val integral = LongArray(stride * (workH + 1))
        for (y in 0 until workH) {
            var rowSum = 0L
            val srcOff = y * workW
            val prevOff = y * stride
            val curOff = (y + 1) * stride
            for (x in 0 until workW) {
                rowSum += (gray[srcOff + x].toInt() and 0xFF)
                integral[curOff + x + 1] = integral[prevOff + x + 1] + rowSum
            }
        }

        val r = (workW / 40).coerceIn(8, 22)
        val darkMask = BooleanArray(total)
        val lightMask = BooleanArray(total)

        for (y in 0 until workH) {
            val y1 = (y - r).coerceAtLeast(0)
            val y2 = (y + r).coerceAtMost(workH - 1)
            val rowOff = y * workW
            val iTop = y1 * stride
            val iBot = (y2 + 1) * stride
            for (x in 0 until workW) {
                val idx = rowOff + x
                if (excluded[idx]) continue
                val x1 = (x - r).coerceAtLeast(0)
                val x2 = (x + r).coerceAtMost(workW - 1)
                val area = (x2 - x1 + 1) * (y2 - y1 + 1)
                val sum = integral[iBot + x2 + 1] - integral[iTop + x2 + 1] - integral[iBot + x1] + integral[iTop + x1]
                val localMean = (sum / area).toInt()
                val lum = gray[idx].toInt() and 0xFF

                if (lum < localMean - 34 && lum < 145 && localMean > 120) {
                    darkMask[idx] = true
                } else if (lum > localMean + 34 && lum > 176 && localMean < 185) {
                    lightMask[idx] = true
                }
            }
        }

        val darkClusters = clusterMask(gray, maxChroma, darkMask, workW, workH, isDarkOnLight = true)
        val lightClusters = clusterMask(gray, maxChroma, lightMask, workW, workH, isDarkOnLight = false)
        return darkClusters + lightClusters
    }

    private fun clusterMask(
        gray: ByteArray,
        maxChroma: ByteArray,
        mask: BooleanArray,
        workW: Int,
        workH: Int,
        isDarkOnLight: Boolean,
    ): List<CandidateBox> {
        val total = workW * workH
        val visited = BooleanArray(total)
        val queue = IntArray(total)
        val chars = mutableListOf<CharComponent>()

        val maxCharW = (workW * 0.30f).toInt().coerceAtLeast(24)
        val maxCharH = (workH * 0.18f).toInt().coerceAtLeast(16)
        val maxCharBboxArea = (total * 0.045f).toInt().coerceAtLeast(64)

        for (sy in 0 until workH) {
            val sOff = sy * workW
            for (sx in 0 until workW) {
                val startIdx = sOff + sx
                if (!mask[startIdx] || visited[startIdx]) continue

                visited[startIdx] = true
                var qHead = 0
                var qTail = 0
                queue[qTail++] = startIdx

                var minX = sx
                var maxX = sx
                var minY = sy
                var maxY = sy

                while (qHead < qTail) {
                    val idx = queue[qHead++]
                    val cx = idx % workW
                    val cy = idx / workW
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    val nyStart = (cy - 1).coerceAtLeast(0)
                    val nyEnd = (cy + 1).coerceAtMost(workH - 1)
                    val nxStart = (cx - 1).coerceAtLeast(0)
                    val nxEnd = (cx + 1).coerceAtMost(workW - 1)
                    for (ny in nyStart..nyEnd) {
                        val nOff = ny * workW
                        for (nx in nxStart..nxEnd) {
                            val nIdx = nOff + nx
                            if (mask[nIdx] && !visited[nIdx]) {
                                visited[nIdx] = true
                                queue[qTail++] = nIdx
                            }
                        }
                    }
                }

                val pixelCount = qTail
                val w = maxX - minX + 1
                val h = maxY - minY + 1
                if (w < 2 || h < 5 || pixelCount < 8) continue
                if (w > maxCharW || h > maxCharH) continue
                val bboxArea = w * h
                if (bboxArea > maxCharBboxArea) continue
                val longSide = maxOf(w, h)
                val shortSide = minOf(w, h)
                if (longSide > workW * 0.05f && longSide.toFloat() / shortSide > 8.0f) continue
                // Skip thin straight radial burst spikes / panel lines
                if (longSide >= 24 && shortSide <= 6 && longSide.toFloat() / shortSide > 6.2f) continue
                val fill = pixelCount.toFloat() / bboxArea
                if (fill !in 0.08f..0.92f) continue
                // Skip thin diagonal radial burst rays / speedlines inside large bounding boxes
                if (w >= 18 && h >= 18 && fill < 0.19f) continue

                chars.add(CharComponent(minX, minY, maxX, maxY, pixelCount))
            }
        }

        if (chars.isEmpty()) return emptyList()
        chars.sortBy { it.minX }

        val n = chars.size
        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) {
                parent[r] = parent[parent[r]]
                r = parent[r]
            }
            return r
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        val baseTolUnit = minOf(workW, workH)
        val factorX = if (isDarkOnLight) 1.24f else 1.26f
        val factorY = 0.72f
        val maxPossibleTolX = maxOf(maxCharH * factorX, baseTolUnit * 0.018f)

        for (i in 0 until n) {
            val ci = chars[i]
            val limitX = ci.maxX + maxPossibleTolX
            for (j in i + 1 until n) {
                val cj = chars[j]
                if (cj.minX > limitX) break
                val refH = maxOf(ci.height, cj.height)
                val pairMinH = minOf(ci.height, cj.height).coerceAtLeast(1)
                val vOverlap = minOf(ci.maxY, cj.maxY) - maxOf(ci.minY, cj.minY) + 1
                // Keep vertically stacked scripts of different scales (e.g. small English caption over large SFX) separate
                if (vOverlap <= 0 && refH.toFloat() / pairMinH > 1.75f) continue
                // Bound vertical linking by pair height so tall stray spikes never bridge text to borders
                val effectiveH = minOf(refH, (pairMinH * 2.2f).roundToInt())
                val tolX = maxOf(effectiveH * factorX, baseTolUnit * 0.012f)
                val tolY = maxOf(effectiveH * factorY, baseTolUnit * 0.008f)
                if (cj.minX <= ci.maxX + tolX &&
                    ci.minX <= cj.maxX + tolX &&
                    cj.minY <= ci.maxY + tolY &&
                    ci.minY <= cj.maxY + tolY
                ) {
                    union(i, j)
                }
            }
        }

        val groups = HashMap<Int, MutableList<CharComponent>>()
        for (i in 0 until n) {
            groups.getOrPut(find(i)) { mutableListOf() }.add(chars[i])
        }

        val minComponents = 2
        val minClusterW = maxOf(10, (workW * 0.014f).toInt())
        val minClusterH = maxOf(7, (minOf(workW, workH) * 0.008f).toInt())
        val out = mutableListOf<CandidateBox>()

        for (group in groups.values) {
            if (group.size < minComponents) continue
            if (!isDarkOnLight && group.size == 2) {
                val gSpanW = group.maxOf { it.maxX } - group.minOf { it.minX } + 1
                if (gSpanW < minClusterW * 1.4f) continue
            }

            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            var sumPixels = 0
            val heights = IntArray(group.size)
            val areas = IntArray(group.size)
            val fills = FloatArray(group.size)

            for (idx in group.indices) {
                val c = group[idx]
                if (c.minX < minX) minX = c.minX
                if (c.minY < minY) minY = c.minY
                if (c.maxX > maxX) maxX = c.maxX
                if (c.maxY > maxY) maxY = c.maxY
                sumPixels += c.pixelCount
                heights[idx] = c.height
                areas[idx] = c.bboxArea
                fills[idx] = c.pixelCount.toFloat() / c.bboxArea
            }

            val cw = maxX - minX + 1
            val ch = maxY - minY + 1
            if (cw < minClusterW || ch < minClusterH) continue
            if (ch > workH * 0.55f) continue
            if (cw > workW * 0.96f || (cw > workW * 0.65f && (group.size < 6 || ch > workH * 0.34f))) continue

            val clusterArea = cw * ch
            val areaFrac = clusterArea.toFloat() / total
            if (areaFrac !in 0.0004f..0.32f) continue

            val density = sumPixels.toFloat() / clusterArea
            if (density !in 0.06f..0.75f) continue
            if (group.size <= 3 && density < 0.18f) continue

            heights.sort()
            areas.sort()
            fills.sort()
            val medianH = heights[heights.size / 2]
            val minH = heights.first().coerceAtLeast(1)
            val maxH = heights.last()
            val medianArea = areas[areas.size / 2].coerceAtLeast(1)
            val maxArea = areas.last()
            val medianFill = if (fills.size % 2 == 0) {
                (fills[fills.size / 2 - 1] + fills[fills.size / 2]) * 0.5f
            } else {
                fills[fills.size / 2]
            }
            if (medianH < 5 || medianFill < 0.22f) continue
            val maxAreaRatio = if (group.size >= 8) 120 else 55
            if (maxArea / medianArea > maxAreaRatio) continue
            if (group.size == 2 && maxH.toFloat() / minH > 2.6f) continue

            var unmaskedSolidCount = 0
            var bgSampleCount = 0
            var bgHighChromaCount = 0
            var bgRoughPairs = 0
            var bgPairCount = 0

            for (py in minY..maxY) {
                val rowOff = py * workW
                for (px in minX..maxX) {
                    val idx = rowOff + px
                    if (!mask[idx]) {
                        bgSampleCount++
                        if ((maxChroma[idx].toInt() and 0xFF) > 46) {
                            bgHighChromaCount++
                        }
                        if (px >= minX + 1 && px + 3 <= maxX &&
                            !mask[idx - 1] && !mask[idx + 1] && !mask[idx + 2] && !mask[idx + 3] &&
                            (py == minY || !mask[idx - workW]) && (py == maxY || !mask[idx + workW])
                        ) {
                            bgPairCount++
                            val lumA = gray[idx].toInt() and 0xFF
                            val lumB = gray[idx + 2].toInt() and 0xFF
                            if (kotlin.math.abs(lumA - lumB) > 28) {
                                bgRoughPairs++
                            }
                        }
                        val lum = gray[idx].toInt() and 0xFF
                        val isSolidInterior = if (isDarkOnLight) lum < 115 else lum > 192
                        if (isSolidInterior) unmaskedSolidCount++
                    }
                }
            }
            if (unmaskedSolidCount > sumPixels && unmaskedSolidCount > (clusterArea * 0.15f).toInt()) continue

            val bgChromaRatio = if (bgSampleCount > 0) bgHighChromaCount.toFloat() / bgSampleCount else 0f
            val bgRoughRatio = if (bgPairCount >= 20) bgRoughPairs.toFloat() / bgPairCount else 0f
            val maxRoughRatio = if (!isDarkOnLight && group.size >= 4) 0.42f else 0.28f
            if (bgRoughRatio > maxRoughRatio) continue
            if (isDarkOnLight && bgChromaRatio > 0.25f && bgRoughRatio > (if (group.size >= 5) 0.16f else 0.09f)) continue
            if (group.size <= 3 && bgChromaRatio > 0.48f && bgRoughRatio > 0.07f) continue
            // Reject small sparse clusters over strongly colored artwork (skin, hair) with few stroke groups
            if (isDarkOnLight && group.size <= 4 && bgChromaRatio > 0.35f) continue

            val pad = maxOf(4, (medianH * 0.45f).roundToInt())
            val px1 = (minX - pad).coerceAtLeast(0)
            val py1 = (minY - pad).coerceAtLeast(0)
            val px2 = (maxX + pad).coerceAtMost(workW - 1)
            val py2 = (maxY + pad).coerceAtMost(workH - 1)

            val rect = NormalizedRect(
                x = px1.toFloat() / workW,
                y = py1.toFloat() / workH,
                width = (px2 - px1 + 1).toFloat() / workW,
                height = (py2 - py1 + 1).toFloat() / workH,
            ).clamped()
            if (rect.isEmpty) continue

            val conf = (0.65f + (group.size.coerceAtMost(12) / 12f) * 0.20f).coerceIn(0.65f, 0.85f)
            out.add(CandidateBox(rect, conf, fromBubblePass = false))
        }

        return out
    }

    private fun mergeInlineClusters(
        clusters: List<CandidateBox>,
        bubbles: List<CandidateBox>,
        workW: Int,
        workH: Int,
    ): List<CandidateBox> {
        if (clusters.size <= 1) return clusters
        val remaining = clusters.sortedWith(compareBy({ it.bounds.y }, { it.bounds.x })).toMutableList()
        val merged = mutableListOf<CandidateBox>()

        while (remaining.isNotEmpty()) {
            var current = remaining.removeAt(0)
            var changed = true
            while (changed) {
                changed = false
                val iter = remaining.iterator()
                while (iter.hasNext()) {
                    val other = iter.next()
                    val cRect = current.bounds.toPixelRect(workW, workH)
                    val oRect = other.bounds.toPixelRect(workW, workH)
                    val minH = minOf(cRect.height, oRect.height).coerceAtLeast(1)
                    val maxH = maxOf(cRect.height, oRect.height).coerceAtLeast(1)

                    // Same horizontal line (small vertical shift, small horizontal gap)
                    val dyCenters = kotlin.math.abs(
                        (cRect.y + cRect.height * 0.5) - (oRect.y + oRect.height * 0.5),
                    )
                    val horizGap = maxOf(oRect.x - (cRect.x + cRect.width), cRect.x - (oRect.x + oRect.width))
                    val vertGap = maxOf(oRect.y - (cRect.y + cRect.height), cRect.y - (oRect.y + oRect.height))
                    val horizOverlap = minOf(cRect.x + cRect.width, oRect.x + oRect.width) - maxOf(cRect.x, oRect.x)

                    val sameLine = maxH.toFloat() / minH <= 2.4f &&
                        dyCenters <= minH * 0.70 &&
                        horizGap <= maxH * 2.2
                    val maxStackOverlapOffset = minOf(maxH, (workW * 0.06f).roundToInt())
                    val maxStackVertGap = minOf(minH * 0.80, workH * 0.042)
                    val stackedParagraph = maxH.toFloat() / minH <= 1.65f &&
                        horizOverlap > -maxStackOverlapOffset * 0.75 &&
                        vertGap <= maxStackVertGap &&
                        maxOf(cRect.height, oRect.height) <= workH * 0.16f

                    if (sameLine || stackedParagraph) {
                        val candidateUnion = current.bounds.union(other.bounds)
                        val crossesClosedBubble = bubbles.any { b ->
                            val bArea = b.bounds.area
                            val inter = candidateUnion.intersectionArea(b.bounds)
                            inter > 0f && (
                                (bArea > 0f && inter / bArea > 0.25f) ||
                                    (candidateUnion.area > 0f && inter / candidateUnion.area > 0.30f)
                                )
                        }
                        if (!crossesClosedBubble && candidateUnion.width <= 0.96f && candidateUnion.height <= 0.24f) {
                            current = CandidateBox(
                                bounds = candidateUnion,
                                confidence = maxOf(current.confidence, other.confidence),
                                fromBubblePass = false,
                            )
                            iter.remove()
                            changed = true
                        }
                    }
                }
            }
            merged.add(current)
        }
        return merged
    }

    private fun mergeOverlapping(candidates: List<CandidateBox>): List<CandidateBox> {
        if (candidates.size <= 1) return candidates
        val remaining = candidates.sortedByDescending { it.confidence }.toMutableList()
        val merged = mutableListOf<CandidateBox>()

        while (remaining.isNotEmpty()) {
            var current = remaining.removeAt(0)
            var changed = true
            while (changed) {
                changed = false
                val iter = remaining.iterator()
                while (iter.hasNext()) {
                    val other = iter.next()
                    val minArea = minOf(current.bounds.area, other.bounds.area)
                    if (minArea > 0f && current.bounds.intersectionArea(other.bounds) / minArea > 0.55f) {
                        current = CandidateBox(
                            bounds = current.bounds.union(other.bounds),
                            confidence = maxOf(current.confidence, other.confidence),
                            fromBubblePass = current.fromBubblePass || other.fromBubblePass,
                        )
                        iter.remove()
                        changed = true
                    }
                }
            }
            merged.add(current)
        }
        return merged
    }

    private fun inferOrientation(
        gray: ByteArray,
        workW: Int,
        workH: Int,
        bounds: NormalizedRect,
    ): TextOrientation {
        val rect = bounds.toPixelRect(workW, workH)
        if (rect.width <= 2 || rect.height <= 2) {
            return if (bounds.height > bounds.width * 1.35f) TextOrientation.VERTICAL else TextOrientation.HORIZONTAL
        }

        // Estimate background luminance along rect perimeter so dark-on-light and light-on-dark both work
        var borderSum = 0L
        var borderCount = 0
        val xEnd = (rect.x + rect.width - 1).coerceAtMost(workW - 1)
        val yEnd = (rect.y + rect.height - 1).coerceAtMost(workH - 1)
        for (x in rect.x..xEnd) {
            borderSum += (gray[rect.y * workW + x].toInt() and 0xFF)
            borderSum += (gray[yEnd * workW + x].toInt() and 0xFF)
            borderCount += 2
        }
        for (y in (rect.y + 1) until yEnd) {
            borderSum += (gray[y * workW + rect.x].toInt() and 0xFF)
            borderSum += (gray[y * workW + xEnd].toInt() and 0xFF)
            borderCount += 2
        }
        val bgLum = if (borderCount > 0) (borderSum / borderCount).toInt() else 255
        val darkInk = bgLum >= 128

        val rowInk = IntArray(rect.height)
        val colInk = IntArray(rect.width)
        for (dy in 0 until rect.height) {
            val py = rect.y + dy
            val off = py * workW
            for (dx in 0 until rect.width) {
                val px = rect.x + dx
                val lum = gray[off + px].toInt() and 0xFF
                val isInk = if (darkInk) lum < bgLum - 35 else lum > bgLum + 35
                if (isInk) {
                    rowInk[dy]++
                    colInk[dx]++
                }
            }
        }

        val rowGaps = countInkGaps(rowInk)
        val colGaps = countInkGaps(colInk)

        val pixW = rect.width.toFloat()
        val pixH = rect.height.toFloat()
        return when {
            pixH > pixW * 1.35f -> TextOrientation.VERTICAL
            pixW >= pixH * 1.05f -> TextOrientation.HORIZONTAL
            pixH > pixW * 1.10f && colGaps >= rowGaps -> TextOrientation.VERTICAL
            else -> TextOrientation.HORIZONTAL
        }
    }

    private fun countInkGaps(profile: IntArray): Int {
        var gaps = 0
        var inInk = false
        for (count in profile) {
            val hasInk = count > 1
            if (inInk && !hasInk) {
                gaps++
            }
            inInk = hasInk
        }
        return gaps
    }

    private fun sortInMangaReadingOrder(regions: List<DetectedTextRegion>): List<DetectedTextRegion> {
        if (regions.size <= 1) return regions
        val ySorted = regions.sortedBy { it.bounds.y }
        val result = ArrayList<DetectedTextRegion>(regions.size)
        val band = mutableListOf<DetectedTextRegion>()

        var bandAnchorCenterY = ySorted[0].bounds.centerY
        var bandMaxBottom = ySorted[0].bounds.bottom

        for (region in ySorted) {
            if (band.isEmpty()) {
                band.add(region)
                bandAnchorCenterY = region.bounds.centerY
                bandMaxBottom = region.bounds.bottom
            } else {
                val sameBand = (region.bounds.y < bandMaxBottom - 0.02f && (region.bounds.centerY - bandAnchorCenterY) <= 0.12f) ||
                    (region.bounds.centerY - bandAnchorCenterY) <= 0.08f
                if (sameBand) {
                    band.add(region)
                    if (region.bounds.bottom > bandMaxBottom) {
                        bandMaxBottom = region.bounds.bottom
                    }
                } else {
                    band.sortByDescending { it.bounds.centerX }
                    result.addAll(band)
                    band.clear()
                    band.add(region)
                    bandAnchorCenterY = region.bounds.centerY
                    bandMaxBottom = region.bounds.bottom
                }
            }
        }
        if (band.isNotEmpty()) {
            band.sortByDescending { it.bounds.centerX }
            result.addAll(band)
        }
        return result
    }
}
