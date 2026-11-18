package mihon.desktop.translator

import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * Normalized rectangle in [0f..1f] page coordinate space.
 */
data class NormalizedRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
    val centerX: Float get() = x + width * 0.5f
    val centerY: Float get() = y + height * 0.5f
    val area: Float get() = width.coerceAtLeast(0f) * height.coerceAtLeast(0f)
    val isEmpty: Boolean get() = !x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f

    fun clamped(): NormalizedRect {
        if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite()) {
            return NormalizedRect(0f, 0f, 0f, 0f)
        }
        val nx1 = x.coerceIn(0f, 1f)
        val ny1 = y.coerceIn(0f, 1f)
        val nx2 = (x + width).coerceIn(nx1, 1f)
        val ny2 = (y + height).coerceIn(ny1, 1f)
        return NormalizedRect(nx1, ny1, nx2 - nx1, ny2 - ny1)
    }

    fun padded(padX: Float, padY: Float = padX): NormalizedRect {
        if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite()) {
            return NormalizedRect(0f, 0f, 0f, 0f)
        }
        val px = if (padX.isFinite()) padX else 0f
        val py = if (padY.isFinite()) padY else 0f
        val nx1 = (x - px).coerceIn(0f, 1f)
        val ny1 = (y - py).coerceIn(0f, 1f)
        val nx2 = (x + width + px).coerceIn(nx1, 1f)
        val ny2 = (y + height + py).coerceIn(ny1, 1f)
        return NormalizedRect(nx1, ny1, nx2 - nx1, ny2 - ny1)
    }

    fun intersectionArea(other: NormalizedRect): Float {
        if (isEmpty || other.isEmpty) return 0f
        val ix1 = maxOf(x, other.x)
        val iy1 = maxOf(y, other.y)
        val ix2 = minOf(right, other.right)
        val iy2 = minOf(bottom, other.bottom)
        if (ix2 <= ix1 || iy2 <= iy1) return 0f
        return (ix2 - ix1) * (iy2 - iy1)
    }

    fun iou(other: NormalizedRect): Float {
        val inter = intersectionArea(other)
        if (inter <= 0f) return 0f
        val union = area + other.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun union(other: NormalizedRect): NormalizedRect {
        val a = clamped()
        val b = other.clamped()
        if (a.isEmpty) return if (b.isEmpty) NormalizedRect(0f, 0f, 0f, 0f) else b
        if (b.isEmpty) return a
        val nx1 = minOf(a.x, b.x)
        val ny1 = minOf(a.y, b.y)
        val nx2 = maxOf(a.right, b.right)
        val ny2 = maxOf(a.bottom, b.bottom)
        return NormalizedRect(nx1, ny1, (nx2 - nx1).coerceAtLeast(0f), (ny2 - ny1).coerceAtLeast(0f)).clamped()
    }

    fun toPixelRect(imgWidth: Int, imgHeight: Int): Rectangle {
        if (imgWidth <= 0 || imgHeight <= 0 || isEmpty) {
            return Rectangle(0, 0, 0, 0)
        }
        val nx1 = x.coerceIn(0f, 1f)
        val ny1 = y.coerceIn(0f, 1f)
        val nx2 = (x + width).coerceIn(0f, 1f)
        val ny2 = (y + height).coerceIn(0f, 1f)
        if (nx2 <= nx1 || ny2 <= ny1) {
            return Rectangle(0, 0, 0, 0)
        }
        val px1 = (nx1 * imgWidth).roundToInt().coerceIn(0, imgWidth)
        val py1 = (ny1 * imgHeight).roundToInt().coerceIn(0, imgHeight)
        val rawPx2 = (nx2 * imgWidth).roundToInt().coerceIn(0, imgWidth)
        val rawPy2 = (ny2 * imgHeight).roundToInt().coerceIn(0, imgHeight)
        val px2 = if (px1 < imgWidth) maxOf(rawPx2, px1 + 1).coerceAtMost(imgWidth) else px1
        val py2 = if (py1 < imgHeight) maxOf(rawPy2, py1 + 1).coerceAtMost(imgHeight) else py1
        val pw = (px2 - px1).coerceAtLeast(0)
        val ph = (py2 - py1).coerceAtLeast(0)
        return if (pw <= 0 || ph <= 0) Rectangle(0, 0, 0, 0) else Rectangle(px1, py1, pw, ph)
    }

    /**
     * Clips to horizontal half [0.0..0.5] (isSecondHalf = false) or [0.5..1.0] (isSecondHalf = true)
     * and renormalizes x/width to [0..1] within that half.
     */
    fun clipToHalf(isSecondHalf: Boolean): NormalizedRect? {
        if (isEmpty) return null
        val halfStart = if (isSecondHalf) 0.5f else 0f
        val halfEnd = if (isSecondHalf) 1.0f else 0.5f
        val nx1 = x.coerceIn(0f, 1f)
        val nx2 = (x + width).coerceIn(0f, 1f)
        val ny1 = y.coerceIn(0f, 1f)
        val ny2 = (y + height).coerceIn(0f, 1f)
        val ix1 = maxOf(nx1, halfStart)
        val ix2 = minOf(nx2, halfEnd)
        val newH = ny2 - ny1
        if (ix2 - ix1 <= 1e-5f || newH <= 1e-5f) return null
        val reX = ((ix1 - halfStart) * 2f).coerceIn(0f, 1f)
        val reW = ((ix2 - ix1) * 2f).coerceIn(0f, 1f - reX)
        if (reW <= 1e-5f) return null
        return NormalizedRect(reX, ny1, reW, newH)
    }

    /**
     * Clips to reading half matching ReaderEngine.getHalfPage(src, isSecondHalf, isRtl):
     * - RTL: subPage 0 (isSecondHalf=false) shows right half [0.5..1]; subPage 1 shows left half [0..0.5].
     * - LTR: subPage 0 (isSecondHalf=false) shows left half [0..0.5]; subPage 1 shows right half [0.5..1].
     */
    fun clipToHalf(isSecondHalf: Boolean, isRtl: Boolean): NormalizedRect? {
        val physicalRightHalf = if (isRtl) !isSecondHalf else isSecondHalf
        return clipToHalf(physicalRightHalf)
    }
}

enum class TextOrientation {
    HORIZONTAL,
    VERTICAL,
}

enum class TranslationStatus {
    IDLE,
    TRANSLATING,
    READY,
    ERROR,
}

enum class TranslatorLanguage(
    val isoCode: String,
    val gtxCode: String,
    val libreCode: String,
    val myMemoryCode: String,
    val tessCode: String,
    val tessVerticalCode: String?,
    val displayName: String,
) {
    AUTO(
        isoCode = "auto",
        gtxCode = "auto",
        libreCode = "auto",
        myMemoryCode = "autodetect",
        tessCode = "jpn+eng",
        tessVerticalCode = "jpn_vert",
        displayName = "Detectar idioma",
    ),
    JAPANESE(
        isoCode = "ja",
        gtxCode = "ja",
        libreCode = "ja",
        myMemoryCode = "ja",
        tessCode = "jpn",
        tessVerticalCode = "jpn_vert",
        displayName = "Japonês",
    ),
    ENGLISH(
        isoCode = "en",
        gtxCode = "en",
        libreCode = "en",
        myMemoryCode = "en",
        tessCode = "eng",
        tessVerticalCode = null,
        displayName = "Inglês",
    ),
    KOREAN(
        isoCode = "ko",
        gtxCode = "ko",
        libreCode = "ko",
        myMemoryCode = "ko",
        tessCode = "kor",
        tessVerticalCode = null,
        displayName = "Coreano",
    ),
    CHINESE_SIMPLIFIED(
        isoCode = "zh-CN",
        gtxCode = "zh-CN",
        libreCode = "zh-Hans",
        myMemoryCode = "zh-CN",
        tessCode = "chi_sim",
        tessVerticalCode = null,
        displayName = "Chinês (Simplificado)",
    ),
    PORTUGUESE_BR(
        isoCode = "pt",
        gtxCode = "pt",
        libreCode = "pt-BR",
        myMemoryCode = "pt-BR",
        tessCode = "por",
        tessVerticalCode = null,
        displayName = "Português (BR)",
    ),
    SPANISH(
        isoCode = "es",
        gtxCode = "es",
        libreCode = "es",
        myMemoryCode = "es",
        tessCode = "spa",
        tessVerticalCode = null,
        displayName = "Espanhol",
    );

    fun tessCodeFor(orientation: TextOrientation): String =
        if (orientation == TextOrientation.VERTICAL && tessVerticalCode != null) {
            tessVerticalCode
        } else {
            tessCode
        }

    fun requiredTessModels(): List<String> {
        val baseParts = tessCode.split('+').map { it.trim() }.filter { it.isNotEmpty() }
        return (baseParts + listOfNotNull(tessVerticalCode)).distinct()
    }

    companion object {
        fun sourceLanguages(): List<TranslatorLanguage> = entries.toList()

        fun targetLanguages(): List<TranslatorLanguage> = entries.filter { it != AUTO }

        fun fromIsoCode(code: String): TranslatorLanguage? {
            val normalized = code.trim()
            if (normalized.isEmpty()) return null
            return entries.firstOrNull { lang ->
                lang.isoCode.equals(normalized, ignoreCase = true) ||
                    lang.gtxCode.equals(normalized, ignoreCase = true) ||
                    lang.libreCode.equals(normalized, ignoreCase = true) ||
                    lang.myMemoryCode.equals(normalized, ignoreCase = true) ||
                    lang.tessCode.equals(normalized, ignoreCase = true) ||
                    lang.name.equals(normalized, ignoreCase = true)
            }
        }

        fun fromNameOrDefault(name: String, default: TranslatorLanguage): TranslatorLanguage {
            val normalized = name.trim()
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
                ?: fromIsoCode(normalized)
                ?: default
        }
    }
}

data class DetectedTextRegion(
    val bounds: NormalizedRect,
    val orientation: TextOrientation = TextOrientation.HORIZONTAL,
    val confidence: Float = 1f,
)

data class TranslatedBlock(
    val bounds: NormalizedRect,
    val originalText: String,
    val translatedText: String,
    val orientation: TextOrientation = TextOrientation.HORIZONTAL,
    val confidence: Float = 1f,
)

data class PageTranslationResult(
    val pageIndex: Int,
    val generation: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val blocks: List<TranslatedBlock>,
    val status: TranslationStatus,
    val errorMessage: String? = null,
)

data class TranslatorConfig(
    val enabledByDefault: Boolean = false,
    val sourceLanguage: TranslatorLanguage = TranslatorLanguage.JAPANESE,
    val targetLanguage: TranslatorLanguage = TranslatorLanguage.PORTUGUESE_BR,
    val providerId: String = "google_gtx",
    val libreTranslateUrl: String = "https://libretranslate.com/translate",
    val libreTranslateApiKey: String = "",
    val autoDownloadTessdata: Boolean = true,
)

interface TextDetector {
    fun detectRegions(image: BufferedImage): List<DetectedTextRegion>
}

data class OcrSnippet(
    val text: String,
    val bounds: NormalizedRect,
    val confidence: Float,
)

interface OcrEngine : AutoCloseable {
    fun isAvailable(): Boolean

    fun recognizePage(
        image: BufferedImage,
        regions: List<DetectedTextRegion>,
        sourceLang: TranslatorLanguage,
    ): List<OcrSnippet>
}
