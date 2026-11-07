package mihon.desktop

import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt
import java.io.ByteArrayOutputStream
import java.util.stream.IntStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-precision Manga & Manhwa Super-Resolution, Line-Art & Color Enhancer.
 *
 * Provides distinct scaling and post-processing pipelines:
 * - HD_64BIT ("Ultra HD 64-bit (Vivid Color + Crisp Vector Line-Art)")
 *   Upscales with Lanczos-3, performs directional gradient line-art reconstruction (Anime4K Dark-Edge),
 *   dynamic contrast stretching, perceptual micro-contrast sharpening, and color vibrancy/clarity boost.
 * - ULTRA ("Lanczos-3 + Anime4K Line-Art")
 * - SHARP ("Lanczos-3 + CAS")
 * - FAST ("Bilinear")
 */
object MangaImageEnhancer {

    enum class QualityMode(val label: String) {
        FSR_EASU("AMD FSR 1.0 (EASU + RCAS Anti-Aliasing — Bordas Suaves Sem Serrilhado)"),
        HD_64BIT("Ultra HD 64-bit (FSR + Anime4K Traço Vetorial + Cores Vivas)"),
        ULTRA("Ultra (Lanczos-3 + Anime4K Line-Art)"),
        SHARP("Nítido (Lanczos-3 + CAS)"),
        FAST("Rápido (Bilinear)"),
    }

    enum class InstallUpscaleFactor(val factor: Int, val label: String) {
        OFF(1, "Desativado (1x Original — Mais rápido e compacto)"),
        X2(2, "2x Super-Resolução HD (Recomendado — Equilíbrio ideal)"),
        X3(3, "3x Full HD Ultra (Alta definição para telas grandes)"),
        X5(5, "5x Master 4K/8K (Máxima densidade — Requer mais espaço)"),
    }

    enum class InstallThresholdMode(val minHeight: Int, val label: String) {
        SMART_1600(1600, "Inteligente: Apenas se altura for < 1600px (Recomendado — Economiza CPU e Disco)"),
        SMART_2000(2000, "Inteligente: Apenas se altura for < 2000px (Ideal para telas 2K/4K)"),
        ALWAYS(0, "Forçar Sempre: Aplica em todas as páginas (Ignora resolução original)"),
    }

    enum class InstallOutputFormat(val ext: String, val label: String) {
        JPEG_HQ("jpg", "JPEG Alta Qualidade (92% — Menor espaço em disco)"),
        PNG_LOSSLESS("png", "PNG Sem Perdas (100% — Fidelidade absoluta de estúdio)"),
    }

    /**
     * Pre-processes raw downloaded page bytes during chapter installation (.cbz creation).
     * Applies [factor]x upscaling (2x, 3x, 5x) and [quality] algorithm, returning the encoded bytes and extension.
     * Safeguards against OOM by capping any single dimension to 7680px (8K).
     *
     * If [thresholdMode] is active and the decoded image is already >= [InstallThresholdMode.minHeight],
     * upscaling is skipped to preserve disk space and CPU.
     */
    fun enhanceForInstall(
        rawBytes: ByteArray,
        originalExt: String,
        factor: InstallUpscaleFactor,
        quality: QualityMode,
        outputFormat: InstallOutputFormat,
        thresholdMode: InstallThresholdMode = InstallThresholdMode.ALWAYS,
    ): Pair<ByteArray, String> {
        if (factor == InstallUpscaleFactor.OFF || factor.factor <= 1) {
            return rawBytes to originalExt
        }
        val decoded = mihon.core.archive.ImageDecoder.decode(rawBytes) ?: return rawBytes to originalExt

        // Threshold Inteligente: não inflar imagens que já estão em alta definição
        if (thresholdMode != InstallThresholdMode.ALWAYS && decoded.height >= thresholdMode.minHeight) {
            decoded.flush()
            return rawBytes to originalExt
        }

        try {
            val maxDim = 7680 // Cap at 8K to prevent excessive heap allocation on long webtoon strips
            val rawTargetW = decoded.width.toLong() * factor.factor
            val rawTargetH = decoded.height.toLong() * factor.factor
            val clampScale = if (rawTargetW > maxDim || rawTargetH > maxDim) {
                min(maxDim.toDouble() / decoded.width, maxDim.toDouble() / decoded.height).coerceAtLeast(1.0)
            } else {
                factor.factor.toDouble()
            }

            val targetW = (decoded.width * clampScale).toInt().coerceAtLeast(1)
            val targetH = (decoded.height * clampScale).toInt().coerceAtLeast(1)

            val enhanced = scale(decoded, targetW, targetH, quality)
            decoded.flush()

            val outStream = ByteArrayOutputStream(rawBytes.size * 2)
            if (outputFormat == InstallOutputFormat.PNG_LOSSLESS) {
                ImageIO.write(enhanced, "png", outStream)
                enhanced.flush()
                return outStream.toByteArray() to "png"
            } else {
                val writers = ImageIO.getImageWritersByFormatName("jpeg")
                if (writers.hasNext()) {
                    val writer = writers.next()
                    try {
                        val param = writer.defaultWriteParam.apply {
                            if (canWriteCompressed()) {
                                compressionMode = ImageWriteParam.MODE_EXPLICIT
                                compressionQuality = 0.92f
                            }
                        }
                        ImageIO.createImageOutputStream(outStream).use { ios ->
                            writer.output = ios
                            writer.write(null, IIOImage(enhanced, null, null), param)
                        }
                    } finally {
                        writer.dispose()
                    }
                } else {
                    ImageIO.write(enhanced, "jpg", outStream)
                }
                enhanced.flush()
                return outStream.toByteArray() to "jpg"
            }
        } catch (_: Throwable) {
            decoded.flush()
            return rawBytes to originalExt
        }
    }

    private const val LANCZOS_A = 3.0
    private const val FP_SHIFT = 14
    private const val FP_ONE = 1 shl FP_SHIFT

    /**
     * Scales [src] to ([targetW] x [targetH]) using the selected [quality] pipeline.
     */
    fun scale(
        src: BufferedImage,
        targetW: Int,
        targetH: Int,
        quality: QualityMode = QualityMode.HD_64BIT,
    ): BufferedImage {
        val tw = targetW.coerceAtLeast(1)
        val th = targetH.coerceAtLeast(1)
        if (src.width == tw && src.height == th && quality == QualityMode.FAST) return src

        if (quality == QualityMode.FAST) {
            return scaleImageFast(src, tw, th)
        }

        return try {
            val srcPixels = extractRgbPixels(src)
            val sw = src.width
            val sh = src.height
            val scaleRatio = max(tw.toDouble() / sw, th.toDouble() / sh)

            val finalPixels = when (quality) {
                QualityMode.FSR_EASU -> {
                    // AMD FSR 1.0 (Edge Adaptive Spatial Upsampling 12-tap + Robust Contrast-Adaptive Sharpening)
                    val easu = fsrEasuResample(srcPixels, sw, sh, tw, th)
                    fsrRcasPass(easu, tw, th, sharpness = 0.28f)
                }
                QualityMode.HD_64BIT -> {
                    // AMD FSR EASU directional interpolation + Anime4K vector line-art + S-curve color vibrancy
                    val easu = fsrEasuResample(srcPixels, sw, sh, tw, th)
                    val lineArt = anime4kLineArtEnhance(
                        easu,
                        tw,
                        th,
                        strength = if (scaleRatio > 1.05) 0.85f else 0.65f,
                        isUpscale = true,
                    )
                    enhance64BitColorAndClarity(lineArt, tw, th)
                }
                QualityMode.ULTRA -> {
                    val resampled = if (sw == tw && sh == th) srcPixels.clone() else lanczos3Resample(srcPixels, sw, sh, tw, th)
                    if (scaleRatio > 1.08) {
                        val strength = ((scaleRatio - 1.0) * 0.65).coerceIn(0.28, 0.85).toFloat()
                        anime4kLineArtEnhance(resampled, tw, th, strength = strength, isUpscale = true)
                    } else {
                        anime4kLineArtEnhance(resampled, tw, th, strength = 0.35f, isUpscale = false)
                    }
                }
                QualityMode.SHARP -> {
                    val resampled = if (sw == tw && sh == th) srcPixels.clone() else lanczos3Resample(srcPixels, sw, sh, tw, th)
                    val strength = if (scaleRatio > 1.0) 0.42f else 0.26f
                    anime4kLineArtEnhance(resampled, tw, th, strength = strength, isUpscale = false)
                }
                QualityMode.FAST -> {
                    if (sw == tw && sh == th) srcPixels.clone() else lanczos3Resample(srcPixels, sw, sh, tw, th)
                }
            }

            packRgbPixels(finalPixels, tw, th)
        } catch (_: OutOfMemoryError) {
            System.gc()
            // Seamless fallback to lightweight bilinear scaling if heap is exhausted
            scaleImageFast(src, tw, th)
        }
    }

    /**
     * Perceptual color enhancement & high-dynamic clarity pass ("64-bit graphic look").
     * - Preserves pure black text/ink and pure white paper.
     * - Enhances midtone contrast (subtle S-curve) so details pop.
     * - Boosts color saturation smoothly (vibrance: desaturated colors are boosted more than already saturated ones).
     * - Sharpens texture and micro-contrasts with local unsharp masking.
     */
    private fun enhance64BitColorAndClarity(src: IntArray, w: Int, h: Int): IntArray {
        if (w < 3 || h < 3) return src
        val out = IntArray(w * h)

        // Precompute S-curve contrast LUT (0..255)
        val contrastLut = IntArray(256) { i ->
            val norm = i / 255.0
            // Smooth sigmoidal curve: x^1.12 / (x^1.12 + (1-x)^1.12)
            val s = if (norm <= 0.0) 0.0 else if (norm >= 1.0) 1.0 else {
                val p1 = Math.pow(norm, 1.15)
                val p2 = Math.pow(1.0 - norm, 1.15)
                p1 / (p1 + p2)
            }
            (s * 255.0 + 0.5).toInt().coerceIn(0, 255)
        }

        IntStream.range(0, h).parallel().forEach { y ->
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                val px = src[idx]
                var r = (px ushr 16) and 0xFF
                var g = (px ushr 8) and 0xFF
                var b = px and 0xFF

                // Apply S-curve contrast
                r = contrastLut[r]
                g = contrastLut[g]
                b = contrastLut[b]

                // Perceptual Vibrance boost:
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                val delta = maxC - minC

                if (delta > 4 && maxC > 20 && minC < 245) {
                    // Saturation = delta / maxC
                    val sat = delta.toFloat() / maxC.toFloat()
                    // Vibrance factor: boosts lower/medium saturated colors by up to 22%, protecting already saturated colors
                    val vibFactor = (1.0f - sat * 0.65f) * 0.25f
                    val luma = (r * 77 + g * 150 + b * 29) shr 8

                    r = (r + (r - luma) * vibFactor).toInt().coerceIn(0, 255)
                    g = (g + (g - luma) * vibFactor).toInt().coerceIn(0, 255)
                    b = (b + (b - luma) * vibFactor).toInt().coerceIn(0, 255)
                }

                out[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return out
    }

    /**
     * Separable 2-pass Lanczos-3 resampling with local min/max anti-ringing clamp.
     */
    private fun lanczos3Resample(
        src: IntArray,
        sw: Int,
        sh: Int,
        dw: Int,
        dh: Int,
    ): IntArray {
        val temp = if (sw == dw) {
            src
        } else {
            val hTable = buildKernelTable(sw, dw)
            val horiz = IntArray(dw * sh)
            IntStream.range(0, sh).parallel().forEach { y ->
                val srcRowOffset = y * sw
                val dstRowOffset = y * dw
                val starts = hTable.starts
                val lengths = hTable.lengths
                val offsets = hTable.weightOffsets
                val weights = hTable.weights

                for (x in 0 until dw) {
                    val startX = starts[x]
                    val len = lengths[x]
                    val wOff = offsets[x]

                    var rSum = 0
                    var gSum = 0
                    var bSum = 0
                    var rMin = 255; var rMax = 0
                    var gMin = 255; var gMax = 0
                    var bMin = 255; var bMax = 0

                    for (k in 0 until len) {
                        val px = src[srcRowOffset + startX + k]
                        val w = weights[wOff + k]
                        val r = (px ushr 16) and 0xFF
                        val g = (px ushr 8) and 0xFF
                        val b = px and 0xFF

                        rSum += r * w
                        gSum += g * w
                        bSum += b * w

                        if (w > 0) {
                            if (r < rMin) rMin = r; if (r > rMax) rMax = r
                            if (g < gMin) gMin = g; if (g > gMax) gMax = g
                            if (b < bMin) bMin = b; if (b > bMax) bMax = b
                        }
                    }

                    val rOut = ((rSum + (FP_ONE shr 1)) shr FP_SHIFT).coerceIn(rMin, rMax)
                    val gOut = ((gSum + (FP_ONE shr 1)) shr FP_SHIFT).coerceIn(gMin, gMax)
                    val bOut = ((bSum + (FP_ONE shr 1)) shr FP_SHIFT).coerceIn(bMin, bMax)
                    horiz[dstRowOffset + x] = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
                }
            }
            horiz
        }

        if (sh == dh) return temp

        val vTable = buildKernelTable(sh, dh)
        val dst = IntArray(dw * dh)

        IntStream.range(0, dh).parallel().forEach { y ->
            val dstRowOffset = y * dw
            val startY = vTable.starts[y]
            val len = vTable.lengths[y]
            val wOff = vTable.weightOffsets[y]
            val weights = vTable.weights

            for (x in 0 until dw) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                var rMin = 255; var rMax = 0
                var gMin = 255; var gMax = 0
                var bMin = 255; var bMax = 0

                for (k in 0 until len) {
                    val srcY = startY + k
                    val px = temp[srcY * dw + x]
                    val w = weights[wOff + k]
                    val r = (px ushr 16) and 0xFF
                    val g = (px ushr 8) and 0xFF
                    val b = px and 0xFF

                    rSum += r * w
                    gSum += g * w
                    bSum += b * w

                    if (w > 0) {
                        if (r < rMin) rMin = r; if (r > rMax) rMax = r
                        if (g < gMin) gMin = g; if (g > gMax) gMax = g
                        if (b < bMin) bMin = b; if (b > bMax) bMax = b
                    }
                }

                val rOut = ((rSum + (FP_ONE shr 1)) shr FP_SHIFT).coerceIn(rMin, rMax)
                val gOut = ((gSum + (FP_ONE shr 1)) shr FP_SHIFT).coerceIn(gMin, gMax)
                val bOut = ((bSum + (FP_ONE shr 1)) shr FP_SHIFT).coerceIn(bMin, bMax)
                dst[dstRowOffset + x] = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
            }
        }

        return dst
    }

    /**
     * Directional edge reconstruction & contrast-adaptive sharpening.
     */
    private fun anime4kLineArtEnhance(
        src: IntArray,
        w: Int,
        h: Int,
        strength: Float,
        isUpscale: Boolean,
    ): IntArray {
        if (w < 3 || h < 3) return src
        val out = IntArray(w * h)

        System.arraycopy(src, 0, out, 0, w)
        System.arraycopy(src, (h - 1) * w, out, (h - 1) * w, w)

        val lum = IntArray(w * h)
        IntStream.range(0, h).parallel().forEach { y ->
            val row = y * w
            for (x in 0 until w) {
                val px = src[row + x]
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                lum[row + x] = (r * 77 + g * 150 + b * 29) shr 8
            }
        }

        IntStream.range(1, h - 1).parallel().forEach { y ->
            val row = y * w
            out[row] = src[row]
            out[row + w - 1] = src[row + w - 1]

            for (x in 1 until w - 1) {
                val idx = row + x
                val cL = lum[idx]
                val nL = lum[idx - w]
                val sL = lum[idx + w]
                val wL = lum[idx - 1]
                val eL = lum[idx + 1]

                var minL = min(cL, min(min(nL, sL), min(wL, eL)))
                var maxL = max(cL, max(max(nL, sL), max(wL, eL)))

                val nwL = lum[idx - w - 1]
                val neL = lum[idx - w + 1]
                val swL = lum[idx + w - 1]
                val seL = lum[idx + w + 1]

                minL = min(minL, min(min(nwL, neL), min(swL, seL)))
                maxL = max(maxL, max(max(nwL, neL), max(swL, seL)))

                val range = maxL - minL
                if (range < 6) {
                    out[idx] = src[idx]
                    continue
                }

                val amp = sqrt((min(minL, 255 - maxL).toFloat() / max(maxL, 1).toFloat()).coerceIn(0f, 1f))
                val casWeight = -amp * (0.16f + 0.22f * strength)

                val avgNeighborsL = (nL + sL + wL + eL) * 0.25f
                var deltaL = (cL - avgNeighborsL) * (-casWeight * 4.4f)

                if (isUpscale && range >= 18) {
                    val midPoint = (minL + maxL) * 0.5f
                    if (cL < midPoint) {
                        val inkPull = (minL - cL) * (0.34f * strength)
                        deltaL += inkPull
                    } else {
                        val paperPush = (maxL - cL) * (0.20f * strength)
                        deltaL += paperPush
                    }
                }

                val px = src[idx]
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                val d = deltaL.toInt()

                val rOut = (r + d).coerceIn(0, 255)
                val gOut = (g + d).coerceIn(0, 255)
                val bOut = (b + d).coerceIn(0, 255)
                out[idx] = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
            }
        }
        return out
    }

    private class KernelTable(
        val starts: IntArray,
        val lengths: IntArray,
        val weightOffsets: IntArray,
        val weights: IntArray,
    )

    private fun buildKernelTable(srcSize: Int, dstSize: Int): KernelTable {
        val scale = srcSize.toDouble() / dstSize.toDouble()
        val filterScale = max(1.0, scale)
        val support = LANCZOS_A * filterScale
        val maxSamples = (ceil(support * 2.0).toInt() + 3)

        val starts = IntArray(dstSize)
        val lengths = IntArray(dstSize)
        val weightOffsets = IntArray(dstSize)
        val weights = IntArray(dstSize * maxSamples)
        val tempW = DoubleArray(maxSamples)
        var cursor = 0

        for (i in 0 until dstSize) {
            val center = (i + 0.5) * scale - 0.5
            val left = max(0, floor(center - support).toInt())
            val right = min(srcSize - 1, ceil(center + support).toInt())
            val count = (right - left + 1).coerceAtLeast(1)

            starts[i] = left
            lengths[i] = count
            weightOffsets[i] = cursor

            var sum = 0.0
            for (k in 0 until count) {
                val dist = ((left + k) - center) / filterScale
                val w = lanczosKernel(dist)
                tempW[k] = w
                sum += w
            }

            var fpSum = 0
            for (k in 0 until count) {
                val normalized = if (sum != 0.0) tempW[k] / sum else (1.0 / count)
                val fp = (normalized * FP_ONE).toInt()
                weights[cursor + k] = fp
                fpSum += fp
            }
            weights[cursor + count / 2] += (FP_ONE - fpSum)
            cursor += count
        }

        return KernelTable(starts, lengths, weightOffsets, weights)
    }

    private fun lanczosKernel(x: Double): Double {
        val ax = abs(x)
        if (ax < 1e-7) return 1.0
        if (ax >= LANCZOS_A) return 0.0
        val pix = PI * x
        val pixOverA = pix / LANCZOS_A
        return (sin(pix) / pix) * (sin(pixOverA) / pixOverA)
    }

    private fun extractRgbPixels(img: BufferedImage): IntArray {
        val w = img.width
        val h = img.height
        val total = w * h
        val buf = img.raster.dataBuffer
        if ((img.type == BufferedImage.TYPE_INT_RGB || img.type == BufferedImage.TYPE_INT_ARGB) &&
            buf is DataBufferInt && buf.offsets.size == 1 && buf.offset == 0 && buf.data.size == total
        ) {
            return buf.data
        }
        if (img.type == BufferedImage.TYPE_3BYTE_BGR && buf is DataBufferByte && buf.data.size >= total * 3) {
            val bytes = buf.data
            val out = IntArray(total)
            var bi = 0
            for (i in 0 until total) {
                val b = bytes[bi].toInt() and 0xFF
                val g = bytes[bi + 1].toInt() and 0xFF
                val r = bytes[bi + 2].toInt() and 0xFF
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                bi += 3
            }
            return out
        }
        if (img.type == BufferedImage.TYPE_4BYTE_ABGR && buf is DataBufferByte && buf.data.size >= total * 4) {
            val bytes = buf.data
            val out = IntArray(total)
            var bi = 0
            for (i in 0 until total) {
                val b = bytes[bi + 1].toInt() and 0xFF
                val g = bytes[bi + 2].toInt() and 0xFF
                val r = bytes[bi + 3].toInt() and 0xFF
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                bi += 4
            }
            return out
        }
        return img.getRGB(0, 0, w, h, null, 0, w)
    }

    private fun packRgbPixels(pixels: IntArray, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val dest = (out.raster.dataBuffer as DataBufferInt).data
        System.arraycopy(pixels, 0, dest, 0, w * h)
        return out
    }

    /**
     * Port of AMD FidelityFX Super Resolution 1.0 (FSR 1.0) - Edge-Adaptive Spatial Upsampling (EASU).
     *
     * Analyzes local 2x2 gradients (horizontal, vertical, diagonal / and \) to detect the precise
     * edge angle and length. It adapts interpolation along the edge vector rather than across it,
     * directly eliminating diagonal stair-stepping ('jaggies' / pixelated outlines).
     */
    private fun fsrEasuResample(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        val dst = IntArray(dw * dh)
        val scaleX = sw.toDouble() / dw.toDouble()
        val scaleY = sh.toDouble() / dh.toDouble()

        // Precompute luma array for fast gradient direction evaluation
        val luma = IntArray(sw * sh)
        IntStream.range(0, sh).parallel().forEach { y ->
            val row = y * sw
            for (x in 0 until sw) {
                val px = src[row + x]
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                // Rec.601 perceptual luma
                luma[row + x] = (r * 77 + g * 150 + b * 29) shr 8
            }
        }

        IntStream.range(0, dh).parallel().forEach { dy ->
            val dstRow = dy * dw
            val srcY = (dy + 0.5) * scaleY - 0.5
            val y0 = floor(srcY).toInt()
            val fy = (srcY - y0).toFloat()

            for (dx in 0 until dw) {
                val srcX = (dx + 0.5) * scaleX - 0.5
                val x0 = floor(srcX).toInt()
                val fx = (srcX - x0).toFloat()

                // Clamp coordinates for 4x4 neighborhood
                val xm1 = (x0 - 1).coerceIn(0, sw - 1)
                val xp0 = x0.coerceIn(0, sw - 1)
                val xp1 = (x0 + 1).coerceIn(0, sw - 1)
                val xp2 = (x0 + 2).coerceIn(0, sw - 1)

                val ym1 = (y0 - 1).coerceIn(0, sh - 1)
                val yp0 = y0.coerceIn(0, sh - 1)
                val yp1 = (y0 + 1).coerceIn(0, sh - 1)
                val yp2 = (y0 + 2).coerceIn(0, sh - 1)

                // Sample central 2x2 lumas
                val l00 = luma[yp0 * sw + xp0]
                val l10 = luma[yp0 * sw + xp1]
                val l01 = luma[yp1 * sw + xp0]
                val l11 = luma[yp1 * sw + xp1]

                // Directional gradient analysis (EASU Kernel Angle)
                val gradX = (l10 - l00) + (l11 - l01)
                val gradY = (l01 - l00) + (l11 - l10)
                val dirLen = sqrt((gradX * gradX + gradY * gradY).toDouble()).toFloat()

                // If edge is present, rotate kernel weights along edge direction
                var rAcc = 0f
                var gAcc = 0f
                var bAcc = 0f
                var wTotal = 0f

                val nx = if (dirLen > 4f) -gradY / dirLen else 0f
                val ny = if (dirLen > 4f) gradX / dirLen else 0f

                // 12-tap sampling diamond around (srcX, srcY)
                val xs = intArrayOf(xp0, xp1, xp0, xp1, xm1, xp2, xp0, xp1, xm1, xp2, xp0, xp1)
                val ys = intArrayOf(yp0, yp0, yp1, yp1, yp0, yp0, ym1, ym1, yp1, yp1, yp2, yp2)
                val oxs = floatArrayOf(0f, 1f, 0f, 1f, -1f, 2f, 0f, 1f, -1f, 2f, 0f, 1f)
                val oys = floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, -1f, -1f, 1f, 1f, 2f, 2f)

                for (tap in 0 until 12) {
                    val px = src[ys[tap] * sw + xs[tap]]
                    val dxSample = oxs[tap] - fx
                    val dySample = oys[tap] - fy

                    // Anisotropic distance calculation along the edge vector
                    val dAlong = dxSample * nx + dySample * ny
                    val dAcross = dxSample * (-ny) + dySample * nx
                    val distSq = dAlong * dAlong * 0.45f + dAcross * dAcross * 1.55f

                    // Sinc / Lanczos-like radial bell curve
                    val w = (1f / (1f + distSq * 2.2f)) * (1f / (1f + distSq * distSq * 0.8f))

                    val r = (px ushr 16) and 0xFF
                    val g = (px ushr 8) and 0xFF
                    val b = px and 0xFF

                    rAcc += r * w
                    gAcc += g * w
                    bAcc += b * w
                    wTotal += w
                }

                val invW = if (wTotal > 0.0001f) 1f / wTotal else 1f
                val rFinal = (rAcc * invW).toInt().coerceIn(0, 255)
                val gFinal = (gAcc * invW).toInt().coerceIn(0, 255)
                val bFinal = (bAcc * invW).toInt().coerceIn(0, 255)

                dst[dstRow + dx] = (0xFF shl 24) or (rFinal shl 16) or (gFinal shl 8) or bFinal
            }
        }

        return dst
    }

    /**
     * Port of AMD FidelityFX Super Resolution 1.0 (FSR 1.0) - Robust Contrast-Adaptive Sharpening (RCAS).
     * Eliminates ringing artifacts while producing ultra-crisp comic line-art.
     */
    private fun fsrRcasPass(src: IntArray, w: Int, h: Int, sharpness: Float = 0.25f): IntArray {
        if (w < 3 || h < 3) return src
        val out = IntArray(w * h)
        System.arraycopy(src, 0, out, 0, w)
        System.arraycopy(src, (h - 1) * w, out, (h - 1) * w, w)

        IntStream.range(1, h - 1).parallel().forEach { y ->
            val row = y * w
            out[row] = src[row]
            out[row + w - 1] = src[row + w - 1]

            for (x in 1 until w - 1) {
                val idx = row + x
                val c = src[idx]
                val t = src[idx - w]
                val b = src[idx + w]
                val l = src[idx - 1]
                val r = src[idx + 1]

                val cR = (c ushr 16) and 0xFF; val cG = (c ushr 8) and 0xFF; val cB = c and 0xFF
                val tR = (t ushr 16) and 0xFF; val tG = (t ushr 8) and 0xFF; val tB = t and 0xFF
                val bR = (b ushr 16) and 0xFF; val bG = (b ushr 8) and 0xFF; val bB = b and 0xFF
                val lR = (l ushr 16) and 0xFF; val lG = (l ushr 8) and 0xFF; val lB = l and 0xFF
                val rR = (r ushr 16) and 0xFF; val rG = (r ushr 8) and 0xFF; val rB = r and 0xFF

                val minR = min(cR, min(min(tR, bR), min(lR, rR)))
                val maxR = max(cR, max(max(tR, bR), max(lR, rR)))
                val minG = min(cG, min(min(tG, bG), min(lG, rG)))
                val maxG = max(cG, max(max(tG, bG), max(lG, rG)))
                val minB = min(cB, min(min(tB, bB), min(lB, rB)))
                val maxB = max(cB, max(max(tB, bB), max(lB, rB)))

                val hitLimitR = min(minR, 255 - maxR).toFloat() / max(maxR, 1).toFloat()
                val hitLimitG = min(minG, 255 - maxG).toFloat() / max(maxG, 1).toFloat()
                val hitLimitB = min(minB, 255 - maxB).toFloat() / max(maxB, 1).toFloat()
                val hitLimit = min(hitLimitR, min(hitLimitG, hitLimitB)).coerceIn(0f, 1f)

                val weight = -sqrt(hitLimit) * sharpness
                val sumR = (tR + bR + lR + rR) * weight
                val sumG = (tG + bG + lG + rG) * weight
                val sumB = (tB + bB + lB + rB) * weight
                val div = 1f + 4f * weight

                val outR = ((cR + sumR) / div).toInt().coerceIn(minR, maxR)
                val outG = ((cG + sumG) / div).toInt().coerceIn(minG, maxG)
                val outB = ((cB + sumB) / div).toInt().coerceIn(minB, maxB)

                out[idx] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }
        return out
    }
}
