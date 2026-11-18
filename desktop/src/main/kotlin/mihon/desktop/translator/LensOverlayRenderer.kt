package mihon.desktop.translator

import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import mihon.desktop.MemoryManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Google Lens-style non-destructive blur and translated text overlay renderer.
 *
 * Guarantees:
 * - Never mutates the input [BufferedImage].
 * - Pixels outside translated block bounding boxes remain 100% bit-for-bit identical to the original image.
 * - Applies multi-pass separable Gaussian-approximation box blur strictly within each block's bounding box,
 *   blends an adaptive background tint for readability, and renders word-wrapped auto-fitted vector text.
 * - Caches rendered patches in a MemoryManager-aware bounded LRU so Swing repaints stay smooth (zero EDT blur lag).
 */
object LensOverlayRenderer {

    private val BASE_FONT: Font by lazy { selectComicFont() }

    private fun selectComicFont(): Font {
        val probeText = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzÁÉÍÓÚÂÊÔÃÕÇáéíóúâêôãõçÀàÜü!?.,;:-'\"0123456789"
        val installed = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
        }.getOrDefault(emptyArray())
        val byLower = installed.associateBy { it.lowercase() }
        val preferred = listOf(
            "Anime Ace",
            "Anime Ace 2.0 BB",
            "Comic Neue",
            "Comfortaa",
            "Montserrat Medium",
            "Montserrat",
            "Open Sans",
            "Cantarell",
            "Noto Sans",
            Font.SANS_SERIF,
        )
        for (family in preferred) {
            val exact = byLower[family.lowercase()] ?: if (family == Font.SANS_SERIF) family else continue
            val candidate = Font(exact, Font.BOLD, 14)
            if (candidate.canDisplayUpTo(probeText) == -1) {
                return candidate
            }
        }
        return Font(Font.SANS_SERIF, Font.BOLD, 14)
    }

    private data class PatchCacheKey(
        val imageId: Int,
        val srcW: Int,
        val srcH: Int,
        val fingerprint: Long,
        val srcX: Int,
        val srcY: Int,
        val srcRectW: Int,
        val srcRectH: Int,
        val targetW: Int,
        val targetH: Int,
        val textHash: Int,
        val origHash: Int,
        val textLen: Int,
    )

    private const val MAX_PATCH_CACHE_ENTRIES = 72
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val patchCacheLock = Any()
    private val patchCache = object : LinkedHashMap<PatchCacheKey, BufferedImage>(96, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PatchCacheKey, BufferedImage>): Boolean {
            val evict = size > MAX_PATCH_CACHE_ENTRIES
            if (evict) {
                runCatching { eldest.value.flush() }
            }
            return evict
        }
    }

    init {
        MemoryManager.registerTrimCallback { clearPatchCache() }
    }

    fun clearPatchCache() {
        synchronized(patchCacheLock) {
            patchCache.values.forEach { runCatching { it.flush() } }
            patchCache.clear()
        }
    }

    private fun computeQuickFingerprint(img: BufferedImage): Long {
        val w = img.width.coerceAtLeast(1)
        val h = img.height.coerceAtLeast(1)
        var fp = (w.toLong() shl 32) xor h.toLong()
        val coords = intArrayOf(
            0, 0,
            w - 1, 0,
            w / 2, h / 2,
            0, h - 1,
            w - 1, h - 1,
            w / 4, h / 4,
            (w * 3) / 4, (h * 3) / 4,
        )
        for (k in coords.indices step 2) {
            val rgb = runCatching { img.getRGB(coords[k], coords[k + 1]) }.getOrDefault(0)
            fp = fp * 31L + rgb.toLong()
        }
        return fp
    }

    /**
     * Maps subPage index and RTL setting to the physical horizontal half of a wide page:
     * - `false` = left half `[0.0 .. 0.5]`
     * - `true` = right half `[0.5 .. 1.0]`
     */
    fun physicalHalfSlice(isSecondHalf: Boolean, isRtl: Boolean): Boolean =
        if (isRtl) !isSecondHalf else isSecondHalf

    /**
     * Returns a new [BufferedImage] with Google Lens blur and translated text overlays rendered.
     * When [result] is `null`, not [TranslationStatus.READY], or has no valid blocks, returns an exact
     * pixel-for-pixel copy of [image] without mutating [image].
     */
    fun renderPageWithOverlay(
        image: BufferedImage,
        result: PageTranslationResult?,
        halfSlice: Boolean? = null,
    ): BufferedImage {
        val copy = copyToArgb(image)
        if (result == null || result.status != TranslationStatus.READY || result.blocks.isEmpty()) {
            return copy
        }
        val g2 = copy.createGraphics()
        try {
            renderOverlayOnGraphics(
                g2 = g2,
                sourceOrDisplayImage = image,
                destX = 0,
                destY = 0,
                destW = copy.width,
                destH = copy.height,
                result = result,
                halfSlice = halfSlice,
            )
        } finally {
            g2.dispose()
        }
        return copy
    }

    /**
     * Renders blurred patches and translated text overlays for [result] onto [g2] within
     * destination rectangle `(destX, destY, destW, destH)`.
     *
     * @param halfSlice `null` for full page `[0..1]`, `false` for physical left half `[0..0.5]`,
     *                  `true` for physical right half `[0.5..1]`.
     */
    fun renderOverlayOnGraphics(
        g2: Graphics2D,
        sourceOrDisplayImage: BufferedImage,
        destX: Int,
        destY: Int,
        destW: Int,
        destH: Int,
        result: PageTranslationResult,
        halfSlice: Boolean? = null,
    ) {
        if (result.status != TranslationStatus.READY || result.blocks.isEmpty()) return
        if (destW <= 1 || destH <= 1 || sourceOrDisplayImage.width <= 0 || sourceOrDisplayImage.height <= 0) return

        val destBounds = Rectangle(destX, destY, destW, destH)
        val oldClip = g2.clip
        val clipBounds = oldClip?.bounds
        val oldFont = g2.font
        val oldColor = g2.color
        val imgId = System.identityHashCode(sourceOrDisplayImage)
        val imgFingerprint = computeQuickFingerprint(sourceOrDisplayImage)

        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            for (block in result.blocks) {
                val text = block.translatedText.trim()
                if (text.isEmpty() || block.bounds.isEmpty) continue

                val normRect = if (halfSlice == null) {
                    block.bounds.clamped()
                } else {
                    block.bounds.clipToHalf(halfSlice) ?: continue
                }
                if (normRect.isEmpty) continue

                val relDestRect = normRect.toPixelRect(destW, destH)
                if (relDestRect.width <= 1 || relDestRect.height <= 1) continue

                val targetRect = Rectangle(
                    destX + relDestRect.x,
                    destY + relDestRect.y,
                    relDestRect.width,
                    relDestRect.height,
                ).intersection(destBounds)
                if (targetRect.isEmpty || targetRect.width <= 1 || targetRect.height <= 1) continue
                if (clipBounds != null && !targetRect.intersects(clipBounds)) continue

                // Extract corresponding patch from sourceOrDisplayImage
                val srcNormRect = if (halfSlice != null && sourceOrDisplayImage.width >= destW * 1.7) {
                    val halfStart = if (halfSlice) 0.5f else 0f
                    val halfEnd = if (halfSlice) 1.0f else 0.5f
                    val c = block.bounds.clamped()
                    val ix1 = max(c.x, halfStart)
                    val ix2 = min(c.right, halfEnd)
                    NormalizedRect(ix1, c.y, (ix2 - ix1).coerceAtLeast(0f), c.height)
                } else {
                    normRect
                }

                val srcRect = srcNormRect.toPixelRect(sourceOrDisplayImage.width, sourceOrDisplayImage.height)
                    .intersection(Rectangle(0, 0, sourceOrDisplayImage.width, sourceOrDisplayImage.height))
                if (srcRect.isEmpty || srcRect.width <= 0 || srcRect.height <= 0) continue

                val key = PatchCacheKey(
                    imageId = imgId,
                    srcW = sourceOrDisplayImage.width,
                    srcH = sourceOrDisplayImage.height,
                    fingerprint = imgFingerprint,
                    srcX = srcRect.x,
                    srcY = srcRect.y,
                    srcRectW = srcRect.width,
                    srcRectH = srcRect.height,
                    targetW = targetRect.width,
                    targetH = targetRect.height,
                    textHash = text.hashCode(),
                    origHash = block.originalText.hashCode(),
                    textLen = text.length,
                )

                val cachedPatch = synchronized(patchCacheLock) { patchCache[key] }
                val finalPatch = if (cachedPatch != null) {
                    cachedPatch
                } else {
                    val blurredPatch = createBlurredAndTintedPatch(
                        sourceImage = sourceOrDisplayImage,
                        srcRect = srcRect,
                        targetW = targetRect.width,
                        targetH = targetRect.height,
                    )
                    val pg = blurredPatch.image.createGraphics()
                    try {
                        pg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        pg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                        pg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                        drawAutoFittedText(
                            g2 = pg,
                            text = text,
                            originalText = block.originalText,
                            targetRect = Rectangle(0, 0, targetRect.width, targetRect.height),
                            isDarkBackground = blurredPatch.isDarkBackground,
                            isOvalBubble = blurredPatch.isOvalBubble,
                            colorProfile = blurredPatch.colorProfile,
                        )
                    } finally {
                        pg.dispose()
                    }
                    synchronized(patchCacheLock) {
                        patchCache[key] = blurredPatch.image
                    }
                    blurredPatch.image
                }

                // Strictly clip to targetRect so zero pixels outside targetRect are ever modified
                g2.clip = if (clipBounds != null) clipBounds.intersection(targetRect) else targetRect
                g2.drawImage(finalPatch, targetRect.x, targetRect.y, targetRect.width, targetRect.height, null)
            }
        } finally {
            g2.clip = oldClip
            g2.font = oldFont
            g2.color = oldColor
        }
    }

    private data class AccentSpan(
        val startFrac: Float,
        val endFrac: Float,
        val color: Color,
    )

    private class TextColorProfile(
        val baseColor: Color,
        val accentSpans: List<AccentSpan>,
    )

    private class BlurredPatchResult(
        val image: BufferedImage,
        val isDarkBackground: Boolean,
        val isOvalBubble: Boolean,
        val colorProfile: TextColorProfile,
    )

    private fun createBlurredAndTintedPatch(
        sourceImage: BufferedImage,
        srcRect: Rectangle,
        targetW: Int,
        targetH: Int,
    ): BlurredPatchResult {
        val patch = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB)
        val pg = patch.createGraphics()
        try {
            pg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            pg.drawImage(
                sourceImage,
                0,
                0,
                targetW,
                targetH,
                srcRect.x,
                srcRect.y,
                srcRect.x + srcRect.width,
                srcRect.y + srcRect.height,
                null,
            )
        } finally {
            pg.dispose()
        }

        val pixels = IntArray(targetW * targetH)
        patch.getRGB(0, 0, targetW, targetH, pixels, 0, targetW)

        // Compute background color cleanly: sample bright pixels when background is light (avoiding
        // dark text strokes turning white bubbles into muddy gray) and dark pixels when dark.
        var lumSum = 0.0
        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            lumSum += 0.299 * r + 0.587 * g + 0.114 * b
        }
        val avgLum = if (pixels.isNotEmpty()) lumSum / pixels.size else 240.0
        val isDarkBg = avgLum < 120.0

        var bgSumR = 0L
        var bgSumG = 0L
        var bgSumB = 0L
        var bgSamples = 0
        var pureWhiteCount = 0
        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val lum = 0.299 * r + 0.587 * g + 0.114 * b
            if (!isDarkBg && lum >= 235.0) pureWhiteCount++
        }
        val lightSampleMin = if (!isDarkBg && pureWhiteCount >= pixels.size * 0.10) 235.0 else 175.0
        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val lum = 0.299 * r + 0.587 * g + 0.114 * b
            val matchBg = if (isDarkBg) lum <= 115.0 else lum >= lightSampleMin
            if (matchBg) {
                bgSumR += r
                bgSumG += g
                bgSumB += b
                bgSamples++
            }
        }
        val tintR = if (bgSamples > 0) (bgSumR / bgSamples).toInt().coerceIn(0, 255) else (if (isDarkBg) 24 else 252)
        val tintG = if (bgSamples > 0) (bgSumG / bgSamples).toInt().coerceIn(0, 255) else (if (isDarkBg) 25 else 252)
        val tintB = if (bgSamples > 0) (bgSumB / bgSamples).toInt().coerceIn(0, 255) else (if (isDarkBg) 31 else 250)
        val tintLum = 0.299f * tintR + 0.587f * tintG + 0.114f * tintB

        // Detect curved speech bubble outline per quadrant so curved borders/hair are preserved
        // while open composite bubble lobes still cover corner glyphs completely.
        val cx = targetW * 0.5f
        val cy = targetH * 0.5f
        val rx = max(1f, cx)
        val ry = max(1f, cy)
        val cornerTotal = IntArray(4)
        val cornerDark = IntArray(4)
        if (!isDarkBg && tintLum >= 195f && targetW >= 36 && targetH >= 28) {
            for (y in 0 until targetH) {
                val dyNorm = abs((y + 0.5f - cy) / ry).pow(2.65f)
                val rowOff = y * targetW
                val bottomBit = if (y >= cy) 2 else 0
                for (x in 0 until targetW) {
                    val dist = (abs((x + 0.5f - cx) / rx).pow(2.65f) + dyNorm).pow(1f / 2.65f)
                    if (dist >= 0.94f) {
                        val quad = bottomBit or (if (x >= cx) 1 else 0)
                        val px = pixels[rowOff + x]
                        val lum = 0.299f * ((px shr 16) and 0xFF) +
                            0.587f * ((px shr 8) and 0xFF) +
                            0.114f * (px and 0xFF)
                        cornerTotal[quad]++
                        if (lum < tintLum - 38f) cornerDark[quad]++
                    }
                }
            }
        }
        val quadOval = BooleanArray(4) { q ->
            cornerTotal[q] >= 4 && cornerDark[q].toFloat() / cornerTotal[q] >= 0.22f
        }
        val isOvalBubble = quadOval.any { it }

        val origPixels = pixels.copyOf()
        val colorProfile = extractTextColorProfile(
            origPixels = origPixels,
            w = targetW,
            h = targetH,
            tintR = tintR,
            tintG = tintG,
            tintB = tintB,
            tintLum = tintLum,
            isDarkBg = isDarkBg,
            quadOval = quadOval,
        )

        // 3-pass separable box blur (approximates Gaussian blur) to obliterate original glyph edges
        val radius = max(4, min(targetW, targetH) / 5).coerceIn(4, 20)
        var current = pixels
        var temp = IntArray(pixels.size)
        repeat(3) {
            boxBlurHorizontal(current, temp, targetW, targetH, radius)
            boxBlurVertical(temp, current, targetW, targetH, radius)
        }

        // Blend blurred pixels cleanly with sampled background tint and per-quadrant oval/rounded masking
        val tintW = when {
            !isDarkBg && tintLum >= 232f -> 0.96f
            !isDarkBg && tintLum >= 205f -> 0.90f
            else -> 0.86f
        }
        val blurW = 1f - tintW

        val cornerR = min(min(targetW, targetH) * 0.16f, 12f)
        val featherPx = if (min(targetW, targetH) > 10) (1.8f / min(cx, cy)).coerceIn(0.03f, 0.14f) else 0.10f

        for (y in 0 until targetH) {
            val dyPow = if (isOvalBubble) abs((y + 0.5f - cy) / ry).pow(2.65f) else 0f
            val bottomBit = if (y >= cy) 2 else 0
            for (x in 0 until targetW) {
                val i = y * targetW + x
                val px = current[i]
                val br = ((px shr 16) and 0xFF) * blurW + tintR * tintW
                val bg = ((px shr 8) and 0xFF) * blurW + tintG * tintW
                val bb = (px and 0xFF) * blurW + tintB * tintW

                val quad = bottomBit or (if (x >= cx) 1 else 0)
                val maskAlpha = if (quadOval[quad]) {
                    val normDist = (abs((x + 0.5f - cx) / rx).pow(2.65f) + dyPow).pow(1f / 2.65f)
                    ((0.97f - normDist) / featherPx).coerceIn(0f, 1f)
                } else {
                    roundedRectCoverage(x + 0.5f, y + 0.5f, targetW.toFloat(), targetH.toFloat(), cornerR)
                }
                val opx = origPixels[i]
                val or = (opx shr 16) and 0xFF
                val og = (opx shr 8) and 0xFF
                val ob = opx and 0xFF

                val r = br * maskAlpha + or * (1f - maskAlpha)
                val g = bg * maskAlpha + og * (1f - maskAlpha)
                val b = bb * maskAlpha + ob * (1f - maskAlpha)

                val ir = r.roundToInt().coerceIn(0, 255)
                val ig = g.roundToInt().coerceIn(0, 255)
                val ib = b.roundToInt().coerceIn(0, 255)
                current[i] = (0xFF shl 24) or (ir shl 16) or (ig shl 8) or ib
            }
        }

        patch.setRGB(0, 0, targetW, targetH, current, 0, targetW)
        return BlurredPatchResult(patch, isDarkBg, isOvalBubble, colorProfile)
    }

    private fun extractTextColorProfile(
        origPixels: IntArray,
        w: Int,
        h: Int,
        tintR: Int,
        tintG: Int,
        tintB: Int,
        tintLum: Float,
        isDarkBg: Boolean,
        quadOval: BooleanArray,
    ): TextColorProfile {
        val defaultBase = if (isDarkBg) Color(255, 255, 255) else Color(16, 17, 24)
        if (w < 8 || h < 8 || origPixels.size < w * h) {
            return TextColorProfile(defaultBase, emptyList())
        }

        val y1 = if (h >= 28) max(2, (h * 0.10f).roundToInt()) else 0
        val y2 = if (h >= 28) min(h - 3, h - 1 - (h * 0.10f).roundToInt()) else h - 1
        val x1 = if (w >= 48) max(2, (w * 0.025f).roundToInt()) else 0
        val x2 = if (w >= 48) min(w - 3, w - 1 - (w * 0.025f).roundToInt()) else w - 1
        if (x1 > x2 || y1 > y2) {
            return TextColorProfile(defaultBase, emptyList())
        }

        val cx = w * 0.5f
        val cy = h * 0.5f
        val rx = max(1f, cx)
        val ry = max(1f, cy)
        val anyOval = quadOval.any { it }

        val rowHasFg = BooleanArray(h)
        val rowDist = FloatArray(w * h)

        for (y in y1..y2) {
            val dyNorm = if (anyOval) abs((y + 0.5f - cy) / ry).pow(2.65f) else 0f
            val bottomBit = if (y >= cy) 2 else 0
            val off = y * w
            for (x in x1..x2) {
                if (anyOval) {
                    val quad = bottomBit or (if (x >= cx) 1 else 0)
                    if (quadOval[quad]) {
                        val normDist = (abs((x + 0.5f - cx) / rx).pow(2.65f) + dyNorm).pow(1f / 2.65f)
                        if (normDist >= 0.86f) continue
                    }
                }
                val px = origPixels[off + x]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                val chroma = max(r, max(g, b)) - min(r, min(g, b))
                val dr = (r - tintR).toFloat()
                val dg = (g - tintG).toFloat()
                val db = (b - tintB).toFloat()
                val dist = sqrt(dr * dr + dg * dg + db * db)

                val isFg = if (isDarkBg) {
                    (lum >= tintLum + 48f && dist >= 55f) || (chroma >= 60 && dist >= 85f && lum >= tintLum + 18f)
                } else {
                    (lum <= tintLum - 38f && dist >= 55f) || (chroma >= 60 && dist >= 85f && lum <= tintLum - 12f)
                }
                if (isFg) {
                    rowDist[off + x] = dist
                    rowHasFg[y] = true
                }
            }
        }

        // Segment active horizontal text line bands top-to-bottom so multi-line blocks preserve reading order
        val rowBands = ArrayList<IntRange>(4)
        var curBandStart = -1
        var gapRows = 0
        for (y in y1..y2) {
            if (rowHasFg[y]) {
                if (curBandStart < 0) curBandStart = y
                gapRows = 0
            } else if (curBandStart >= 0) {
                gapRows++
                if (gapRows >= 2) {
                    rowBands.add(curBandStart..(y - gapRows))
                    curBandStart = -1
                    gapRows = 0
                }
            }
        }
        if (curBandStart >= 0) {
            rowBands.add(curBandStart..y2)
        }
        if (rowBands.isEmpty()) {
            return TextColorProfile(defaultBase, emptyList())
        }

        val sampleW = x2 - x1 + 1
        val binW = (sampleW / 56).coerceIn(2, 6)
        val fgBins = ArrayList<Int>(64)

        // Pick the deepest-contrast core pixel in each (band, column bin) so antialiased edge halos never wash out hues
        for (band in rowBands) {
            var bx = x1
            while (bx <= x2) {
                val bxEnd = min(x2, bx + binW - 1)
                var bestDist = 0f
                var bestPx = 0
                for (y in band) {
                    val off = y * w
                    for (x in bx..bxEnd) {
                        val d = rowDist[off + x]
                        if (d > bestDist) {
                            bestDist = d
                            bestPx = origPixels[off + x]
                        }
                    }
                }
                if (bestDist > 0f) {
                    fgBins.add(bestPx)
                }
                bx += binW
            }
        }
        if (fgBins.size < 3) {
            return TextColorProfile(defaultBase, emptyList())
        }

        // Quantized RGB histogram to locate the dominant base ink color across active bins
        val bucketCounts = HashMap<Int, Int>()
        val bucketSumR = HashMap<Int, Long>()
        val bucketSumG = HashMap<Int, Long>()
        val bucketSumB = HashMap<Int, Long>()
        for (px in fgBins) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val qKey = ((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)
            bucketCounts[qKey] = (bucketCounts[qKey] ?: 0) + 1
            bucketSumR[qKey] = (bucketSumR[qKey] ?: 0L) + r
            bucketSumG[qKey] = (bucketSumG[qKey] ?: 0L) + g
            bucketSumB[qKey] = (bucketSumB[qKey] ?: 0L) + b
        }
        val winKey = bucketCounts.maxByOrNull { it.value }?.key ?: 0
        val winCount = bucketCounts[winKey]?.coerceAtLeast(1) ?: 1
        val domR = ((bucketSumR[winKey] ?: 0L) / winCount).toInt().coerceIn(0, 255)
        val domG = ((bucketSumG[winKey] ?: 0L) / winCount).toInt().coerceIn(0, 255)
        val domB = ((bucketSumB[winKey] ?: 0L) / winCount).toInt().coerceIn(0, 255)
        val domChroma = max(domR, max(domG, domB)) - min(domR, min(domG, domB))

        val tintRgb = (0xFF shl 24) or (tintR shl 16) or (tintG shl 8) or tintB
        val baseColor = if (domChroma < 30) {
            defaultBase
        } else {
            ensureReadableColor(Color(domR, domG, domB), tintRgb, isDarkBg, minContrast = 4.2)
        }

        // Detect distinct chromatic accent runs (e.g. highlighted red words inside a black sentence)
        val totalBins = fgBins.size
        val isAccentBin = BooleanArray(totalBins)
        for (i in 0 until totalBins) {
            val px = fgBins[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val chroma = max(r, max(g, b)) - min(r, min(g, b))
            val dr = (r - domR).toFloat()
            val dg = (g - domG).toFloat()
            val db = (b - domB).toFloat()
            val distFromDom = sqrt(dr * dr + dg * dg + db * db)
            isAccentBin[i] = distFromDom >= 72f && (chroma >= 38 || domChroma >= 38)
        }

        // Bridge isolated 1-bin gaps between accent letters/words
        for (i in 1 until (totalBins - 1)) {
            if (!isAccentBin[i] && isAccentBin[i - 1] && isAccentBin[i + 1]) {
                val p1 = fgBins[i - 1]
                val p2 = fgBins[i + 1]
                if (rgbDistance(p1, p2) < 60f) {
                    isAccentBin[i] = true
                }
            }
        }

        val minRunBins = max(2, totalBins / 30)
        val accents = mutableListOf<AccentSpan>()
        var idx = 0
        while (idx < totalBins) {
            if (!isAccentBin[idx]) {
                idx++
                continue
            }
            val startIdx = idx
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            while (idx < totalBins && isAccentBin[idx]) {
                val px = fgBins[idx]
                sumR += (px shr 16) and 0xFF
                sumG += (px shr 8) and 0xFF
                sumB += px and 0xFF
                idx++
            }
            val count = idx - startIdx
            if (count >= minRunBins && count < (totalBins * 0.68f).toInt()) {
                val ar = (sumR / count).toInt().coerceIn(0, 255)
                val ag = (sumG / count).toInt().coerceIn(0, 255)
                val ab = (sumB / count).toInt().coerceIn(0, 255)
                val accentChroma = max(ar, max(ag, ab)) - min(ar, min(ag, ab))
                val readableAccent = if (accentChroma < 28) {
                    defaultBase
                } else {
                    ensureReadableColor(Color(ar, ag, ab), tintRgb, isDarkBg, minContrast = 3.8)
                }
                accents.add(
                    AccentSpan(
                        startFrac = startIdx.toFloat() / totalBins,
                        endFrac = idx.toFloat() / totalBins,
                        color = readableAccent,
                    ),
                )
            }
        }

        return TextColorProfile(baseColor, accents)
    }

    private fun rgbDistance(c1: Int, c2: Int): Float {
        val dr = ((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF)
        val dg = ((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF)
        val db = (c1 and 0xFF) - (c2 and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }

    private fun ensureReadableColor(fg: Color, bgRgb: Int, isDarkBg: Boolean, minContrast: Double): Color {
        if (contrastRatio(fg.rgb, bgRgb) >= minContrast) return fg
        var r = fg.red.toFloat()
        var g = fg.green.toFloat()
        var b = fg.blue.toFloat()
        for (step in 1..14) {
            val t = step / 14f
            val nr: Int
            val ng: Int
            val nb: Int
            if (isDarkBg) {
                nr = (r + (255f - r) * t).roundToInt().coerceIn(0, 255)
                ng = (g + (255f - g) * t).roundToInt().coerceIn(0, 255)
                nb = (b + (255f - b) * t).roundToInt().coerceIn(0, 255)
            } else {
                val scale = 1f - t * 0.85f
                nr = (r * scale).roundToInt().coerceIn(0, 255)
                ng = (g * scale).roundToInt().coerceIn(0, 255)
                nb = (b * scale).roundToInt().coerceIn(0, 255)
            }
            val cand = Color(nr, ng, nb)
            if (contrastRatio(cand.rgb, bgRgb) >= minContrast) return cand
        }
        return if (isDarkBg) Color(255, 255, 255) else Color(16, 17, 24)
    }

    private fun roundedRectCoverage(px: Float, py: Float, w: Float, h: Float, radius: Float): Float {
        if (radius <= 1.5f) return 1f
        val dx = when {
            px < radius -> radius - px
            px > w - radius -> px - (w - radius)
            else -> 0f
        }
        val dy = when {
            py < radius -> radius - py
            py > h - radius -> py - (h - radius)
            else -> 0f
        }
        if (dx <= 0f || dy <= 0f) return 1f
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        return (radius + 1f - dist).coerceIn(0f, 1f)
    }

    private fun boxBlurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
        if (w <= 1 || radius <= 0) {
            System.arraycopy(src, 0, dst, 0, src.size)
            return
        }
        val effR = min(radius, w - 1)
        val window = 2 * effR + 1
        for (y in 0 until h) {
            val rowOffset = y * w
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (k in -effR..effR) {
                val x = k.coerceIn(0, w - 1)
                val rgb = src[rowOffset + x]
                sumR += (rgb shr 16) and 0xFF
                sumG += (rgb shr 8) and 0xFF
                sumB += rgb and 0xFF
            }
            for (x in 0 until w) {
                val r = sumR / window
                val g = sumG / window
                val b = sumB / window
                dst[rowOffset + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

                val outIdx = (x - effR).coerceIn(0, w - 1)
                val inIdx = (x + effR + 1).coerceIn(0, w - 1)
                val outRgb = src[rowOffset + outIdx]
                val inRgb = src[rowOffset + inIdx]
                sumR += ((inRgb shr 16) and 0xFF) - ((outRgb shr 16) and 0xFF)
                sumG += ((inRgb shr 8) and 0xFF) - ((outRgb shr 8) and 0xFF)
                sumB += (inRgb and 0xFF) - (outRgb and 0xFF)
            }
        }
    }

    private fun boxBlurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
        if (h <= 1 || radius <= 0) {
            System.arraycopy(src, 0, dst, 0, src.size)
            return
        }
        val effR = min(radius, h - 1)
        val window = 2 * effR + 1
        for (x in 0 until w) {
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (k in -effR..effR) {
                val y = k.coerceIn(0, h - 1)
                val rgb = src[y * w + x]
                sumR += (rgb shr 16) and 0xFF
                sumG += (rgb shr 8) and 0xFF
                sumB += rgb and 0xFF
            }
            for (y in 0 until h) {
                val r = sumR / window
                val g = sumG / window
                val b = sumB / window
                dst[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

                val outIdx = (y - effR).coerceIn(0, h - 1)
                val inIdx = (y + effR + 1).coerceIn(0, h - 1)
                val outRgb = src[outIdx * w + x]
                val inRgb = src[inIdx * w + x]
                sumR += ((inRgb shr 16) and 0xFF) - ((outRgb shr 16) and 0xFF)
                sumG += ((inRgb shr 8) and 0xFF) - ((outRgb shr 8) and 0xFF)
                sumB += (inRgb and 0xFF) - (outRgb and 0xFF)
            }
        }
    }

    private fun drawAutoFittedText(
        g2: Graphics2D,
        text: String,
        originalText: String,
        targetRect: Rectangle,
        isDarkBackground: Boolean,
        isOvalBubble: Boolean = false,
        colorProfile: TextColorProfile,
    ) {
        val padFracX = if (isOvalBubble) 0.10f else 0.05f
        val padFracY = if (isOvalBubble) 0.09f else 0.05f
        val padX = (targetRect.width * padFracX).roundToInt().coerceIn(2, 18)
        val padY = (targetRect.height * padFracY).roundToInt().coerceIn(2, 18)
        val innerW = (targetRect.width - padX * 2).coerceAtLeast(4)
        val innerH = (targetRect.height - padY * 2).coerceAtLeast(4)

        val maxFont = min(innerW, innerH).coerceIn(6, 44)
        val minFont = 6

        val cleanedText = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (cleanedText.isEmpty()) return
        val wordParagraphs: List<List<String>> = cleanedText.split('\n')
            .map { para -> para.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() } }
            .filter { it.isNotEmpty() }
        if (wordParagraphs.isEmpty()) return

        val paraWordColors = resolveWordColorsForBlock(originalText, wordParagraphs, colorProfile)

        fun ovalAllowedLineWidth(lineIdx: Int, lineCount: Int, lineH: Int): Int {
            if (!isOvalBubble || lineCount <= 1) return innerW
            val totalBlockH = lineCount * lineH
            val blockTop = targetRect.y + padY + ((innerH - totalBlockH) / 2).coerceAtLeast(0)
            val lineCenterY = blockTop + lineIdx * lineH + lineH * 0.5f
            val boxCenterY = targetRect.y + targetRect.height * 0.5f
            val halfH = max(1f, targetRect.height * 0.5f)
            val ny = ((lineCenterY - boxCenterY) / halfH).coerceIn(-0.96f, 0.96f)
            val chordScale = (1f - abs(ny).pow(2.2f)).coerceAtLeast(0.18f).pow(0.45f)
            val boxChordW = (targetRect.width * chordScale - padX * 1.8f).roundToInt()
            return min(innerW, boxChordW).coerceAtLeast(4)
        }

        data class WrappedResult(
            val lines: List<String>,
            val lineColors: List<List<Color>>?,
            val usedWrapW: Int,
        )

        fun fitBalancedLines(fm: FontMetrics, allowWordBreak: Boolean): WrappedResult {
            val greedy = wrapWordParagraphsToWidth(wordParagraphs, fm, innerW, allowWordBreak = allowWordBreak)
            if (greedy.size <= 1) {
                val cols = if (paraWordColors != null) ArrayList<List<Color>>(greedy.size) else null
                val finalLines = wrapWordParagraphsToWidth(wordParagraphs, fm, innerW, allowWordBreak, paraWordColors, cols)
                return WrappedResult(finalLines, cols, innerW)
            }
            var lo = (wordParagraphs.maxOfOrNull { p -> p.maxOfOrNull { fm.stringWidth(it) } ?: 0 } ?: 4)
                .coerceIn(4, innerW)
            var hi = innerW
            var bestW = innerW
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                val candidate = wrapWordParagraphsToWidth(wordParagraphs, fm, mid, allowWordBreak = allowWordBreak)
                if (candidate.isNotEmpty() && candidate.size <= greedy.size) {
                    bestW = mid
                    hi = mid - 1
                } else {
                    lo = mid + 1
                }
            }
            val cols = if (paraWordColors != null) ArrayList<List<Color>>(greedy.size) else null
            val finalLines = wrapWordParagraphsToWidth(wordParagraphs, fm, bestW, allowWordBreak, paraWordColors, cols)
            return WrappedResult(finalLines, cols, bestW)
        }

        data class LayoutFit(
            val font: Font,
            val wrapped: WrappedResult,
            val fm: FontMetrics,
            val fitsCleanly: Boolean,
        )

        fun tryLayoutSize(size: Int): LayoutFit {
            val candidateFont = BASE_FONT.deriveFont(Font.BOLD, size.toFloat())
            val fm = g2.getFontMetrics(candidateFont)
            val lineHeight = max(fm.ascent + 1, (fm.height * 0.86f).roundToInt())
            val wrapped = fitBalancedLines(fm, allowWordBreak = size == minFont)
            val lines = wrapped.lines
            val totalH = lines.size * lineHeight
            val fitsHorizontal = lines.isNotEmpty() && lines.indices.all { idx ->
                val maxWForLine = ovalAllowedLineWidth(idx, lines.size, lineHeight)
                fm.stringWidth(lines[idx]) <= maxWForLine
            }
            val fits = lines.isNotEmpty() && totalH <= innerH && fitsHorizontal
            return LayoutFit(candidateFont, wrapped, fm, fits)
        }

        var loSize = minFont
        var hiSize = maxFont
        var bestFit = tryLayoutSize(minFont)
        while (loSize <= hiSize) {
            val mid = (loSize + hiSize) / 2
            val attempt = tryLayoutSize(mid)
            if (attempt.fitsCleanly) {
                bestFit = attempt
                loSize = mid + 1
            } else {
                hiSize = mid - 1
            }
        }

        val chosenFont = bestFit.font
        val chosenLines = bestFit.wrapped.lines
        val chosenLineColors = bestFit.wrapped.lineColors
        val chosenFm = bestFit.fm
        if (chosenLines.isEmpty()) return

        g2.font = chosenFont
        val lineHeight = max(chosenFm.ascent + 1, (chosenFm.height * 0.86f).roundToInt())
        val maxVisibleLines = max(1, innerH / lineHeight)
        val visibleLines = if (chosenLines.size > maxVisibleLines) {
            chosenLines.take(maxVisibleLines)
        } else {
            chosenLines
        }

        val totalBlockH = visibleLines.size * lineHeight
        val visualOffset = ((chosenFm.ascent - chosenFm.descent) / 2f).roundToInt()
        val rawStartY = targetRect.y + padY + ((innerH - totalBlockH) / 2).coerceAtLeast(0) +
            ((lineHeight / 2) + visualOffset).coerceIn(chosenFm.ascent - 2, chosenFm.ascent + 4)
        val maxSafeStartY = targetRect.y + targetRect.height - chosenFm.descent - 1 - (visibleLines.size - 1) * lineHeight
        val startY = rawStartY.coerceIn(targetRect.y + chosenFm.ascent, maxOf(targetRect.y + chosenFm.ascent, maxSafeStartY))

        val baseColor = colorProfile.baseColor
        val haloColor = if (isDarkBackground) Color(14, 15, 22, 220) else Color(255, 255, 255, 230)
        val drawHalo = targetRect.width >= 20 && targetRect.height >= 14

        for ((idx, line) in visibleLines.withIndex()) {
            val lineW = chosenFm.stringWidth(line)
            val drawX = targetRect.x + ((targetRect.width - lineW) / 2)
            val drawY = startY + idx * lineHeight
            val tokenColors = chosenLineColors?.getOrNull(idx)
            val lineTokens = if (tokenColors != null) line.split(' ') else emptyList()

            if (tokenColors != null && tokenColors.size == lineTokens.size && tokenColors.distinct().size > 1) {
                var curX = drawX
                for (wIdx in lineTokens.indices) {
                    val tok = lineTokens[wIdx]
                    val tokColor = tokenColors[wIdx]
                    val seg = if (wIdx + 1 < lineTokens.size) "$tok " else tok
                    if (drawHalo) {
                        g2.color = haloColor
                        g2.drawString(tok, curX - 1, drawY)
                        g2.drawString(tok, curX + 1, drawY)
                        g2.drawString(tok, curX, drawY - 1)
                        g2.drawString(tok, curX, drawY + 1)
                    }
                    g2.color = tokColor
                    g2.drawString(tok, curX, drawY)
                    curX += chosenFm.stringWidth(seg)
                }
            } else {
                val singleColor = tokenColors?.firstOrNull() ?: baseColor
                if (drawHalo) {
                    g2.color = haloColor
                    g2.drawString(line, drawX - 1, drawY)
                    g2.drawString(line, drawX + 1, drawY)
                    g2.drawString(line, drawX, drawY - 1)
                    g2.drawString(line, drawX, drawY + 1)
                }
                g2.color = singleColor
                g2.drawString(line, drawX, drawY)
            }
        }
    }

    private fun resolveWordColorsForBlock(
        originalText: String,
        translatedParagraphs: List<List<String>>,
        profile: TextColorProfile,
    ): List<List<Color>>? {
        if (profile.accentSpans.isEmpty()) return null
        val flatTranslated = translatedParagraphs.flatten()
        if (flatTranslated.isEmpty()) return null

        val origWords = originalText.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        val flatColors = MutableList(flatTranslated.size) { profile.baseColor }

        fun normalizeToken(tok: String): String =
            tok.trim { !it.isLetterOrDigit() && it != '.' && it != '-' }.lowercase()

        // Compute normalized character midpoint fraction [0..1] for each word in original and translated text
        val origNormMidFracs = if (origWords.isNotEmpty()) {
            val totalOrig = origWords.sumOf { it.length }.coerceAtLeast(1)
            var cursor = 0
            origWords.map { w ->
                val mid = (cursor + w.length * 0.5f) / totalOrig
                cursor += w.length
                mid
            }
        } else {
            emptyList()
        }

        val totalTrans = flatTranslated.sumOf { it.length }.coerceAtLeast(1)
        val transMidFracs = FloatArray(flatTranslated.size).also { arr ->
            var cursor = 0
            for (i in flatTranslated.indices) {
                val w = flatTranslated[i]
                arr[i] = (cursor + w.length * 0.5f) / totalTrans
                cursor += w.length
            }
        }
        val transNormalized = flatTranslated.map(::normalizeToken)

        for (span in profile.accentSpans) {
            val spanCenter = (span.startFrac + span.endFrac) * 0.5f
            val lo = (span.startFrac - 0.045f).coerceIn(0f, 1f)
            val hi = (span.endFrac + 0.045f).coerceIn(0f, 1f)

            val highlightedOrigTokens = origWords.indices
                .filter { i -> origNormMidFracs[i] in lo..hi }
                .map { i -> normalizeToken(origWords[i]) }
                .filter { it.length >= 2 }
                .toSet()

            var matchedAnyLiteral = false
            if (highlightedOrigTokens.isNotEmpty()) {
                // Match verbatim/cognate tokens (e.g. URLs, numbers, names) regardless of translation reordering
                for (origNorm in highlightedOrigTokens) {
                    val candidateIndices = transNormalized.indices.filter { tIdx ->
                        transNormalized[tIdx] == origNorm
                    }
                    val bestMatch = candidateIndices.minByOrNull { tIdx ->
                        abs(transMidFracs[tIdx] - spanCenter)
                    }
                    if (bestMatch != null) {
                        flatColors[bestMatch] = span.color
                        matchedAnyLiteral = true
                    }
                }
            }

            if (!matchedAnyLiteral) {
                for (tIdx in flatTranslated.indices) {
                    if (transMidFracs[tIdx] in lo..hi) {
                        flatColors[tIdx] = span.color
                    }
                }
            }
        }

        var cursor = 0
        return translatedParagraphs.map { para ->
            List(para.size) { flatColors[cursor++] }
        }
    }

    internal fun wrapTextToWidth(
        text: String,
        fm: FontMetrics,
        maxWidth: Int,
        allowWordBreak: Boolean = true,
    ): List<String> {
        val cleaned = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (cleaned.isEmpty()) return emptyList()
        val wordParagraphs = cleaned.split('\n')
            .map { para -> para.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() } }
            .filter { it.isNotEmpty() }
        return wrapWordParagraphsToWidth(wordParagraphs, fm, maxWidth, allowWordBreak)
    }

    private fun wrapWordParagraphsToWidth(
        wordParagraphs: List<List<String>>,
        fm: FontMetrics,
        maxWidth: Int,
        allowWordBreak: Boolean,
        wordColorParagraphs: List<List<Color>>? = null,
        outLineColors: MutableList<List<Color>>? = null,
    ): List<String> {
        if (wordParagraphs.isEmpty()) return emptyList()
        val limit = maxWidth.coerceAtLeast(4)
        val result = mutableListOf<String>()

        for (pIdx in wordParagraphs.indices) {
            val words = wordParagraphs[pIdx]
            if (words.isEmpty()) continue
            val wordColors = wordColorParagraphs?.getOrNull(pIdx)
            var currentLine = StringBuilder()
            var currentColors = if (outLineColors != null) mutableListOf<Color>() else null

            fun flushCurrent() {
                if (currentLine.isNotEmpty()) {
                    result.add(currentLine.toString())
                    if (outLineColors != null) {
                        currentColors?.let {
                            outLineColors.add(it)
                            currentColors = mutableListOf()
                        }
                    }
                    currentLine = StringBuilder()
                }
            }

            for (wIdx in words.indices) {
                val word = words[wIdx]
                val wColor = wordColors?.getOrNull(wIdx) ?: Color.BLACK
                if (currentLine.isEmpty()) {
                    if (fm.stringWidth(word) <= limit) {
                        currentLine.append(word)
                        currentColors?.add(wColor)
                    } else if (!allowWordBreak) {
                        return emptyList()
                    } else {
                        val beforeLines = result.size
                        val remainder = splitLongToken(word, fm, limit, result)
                        if (outLineColors != null) {
                            repeat(result.size - beforeLines) { outLineColors.add(listOf(wColor)) }
                        }
                        if (remainder.isNotEmpty()) {
                            currentLine.append(remainder)
                            currentColors?.add(wColor)
                        }
                    }
                } else {
                    val candidate = "$currentLine $word"
                    if (fm.stringWidth(candidate) <= limit) {
                        currentLine.append(' ').append(word)
                        currentColors?.add(wColor)
                    } else {
                        flushCurrent()
                        if (fm.stringWidth(word) <= limit) {
                            currentLine.append(word)
                            currentColors?.add(wColor)
                        } else if (!allowWordBreak) {
                            return emptyList()
                        } else {
                            val beforeLines = result.size
                            val remainder = splitLongToken(word, fm, limit, result)
                            if (outLineColors != null) {
                                repeat(result.size - beforeLines) { outLineColors.add(listOf(wColor)) }
                            }
                            if (remainder.isNotEmpty()) {
                                currentLine.append(remainder)
                                currentColors?.add(wColor)
                            }
                        }
                    }
                }
            }
            flushCurrent()
        }

        return result
    }

    private fun splitLongToken(
        token: String,
        fm: FontMetrics,
        limit: Int,
        outLines: MutableList<String>,
    ): String {
        val sb = StringBuilder()
        for (ch in token) {
            sb.append(ch)
            if (fm.stringWidth(sb.toString()) > limit && sb.length > 1) {
                val lastChar = sb.last()
                sb.setLength(sb.length - 1)
                outLines.add(sb.toString())
                sb.setLength(0)
                sb.append(lastChar)
            }
        }
        return sb.toString()
    }

    private fun copyToArgb(src: BufferedImage): BufferedImage {
        val w = src.width.coerceAtLeast(1)
        val h = src.height.coerceAtLeast(1)
        val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val pixels = IntArray(w * h)
        src.getRGB(0, 0, w, h, pixels, 0, w)
        dst.setRGB(0, 0, w, h, pixels, 0, w)
        return dst
    }

    /**
     * Computes WCAG 2.1 relative luminance in `[0.0 .. 1.0]` for an RGB color.
     */
    fun relativeLuminance(rgb: Int): Double {
        fun channel(c: Int): Double {
            val s = (c and 0xFF) / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = channel(rgb shr 16)
        val g = channel(rgb shr 8)
        val b = channel(rgb)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * Computes WCAG contrast ratio in `[1.0 .. 21.0]` between two RGB colors.
     */
    fun contrastRatio(rgb1: Int, rgb2: Int): Double {
        val l1 = relativeLuminance(rgb1)
        val l2 = relativeLuminance(rgb2)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
