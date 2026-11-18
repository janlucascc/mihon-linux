package mihon.desktop.translator

import org.junit.jupiter.api.Test
import java.awt.Rectangle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslatorModelsTest {

    @Test
    fun `toPixelRect maps exact normalized coordinates`() {
        val rect = NormalizedRect(0.25f, 0.5f, 0.5f, 0.25f)
        assertEquals(Rectangle(200, 600, 400, 300), rect.toPixelRect(800, 1200))
    }

    @Test
    fun `toPixelRect avoids float rounding off-by-one and clamps invalid inputs`() {
        val rect = NormalizedRect(0f, 0f, 0.3f, 0.7f)
        assertEquals(Rectangle(0, 0, 300, 700), rect.toPixelRect(1000, 1000))

        val outOfBounds = NormalizedRect(-0.2f, 0.8f, 1.5f, 0.5f)
        assertEquals(Rectangle(0, 800, 1000, 200), outOfBounds.toPixelRect(1000, 1000))

        assertEquals(Rectangle(0, 0, 0, 0), NormalizedRect(0.1f, 0.1f, 0f, 0.2f).toPixelRect(800, 1200))
        assertEquals(Rectangle(0, 0, 0, 0), NormalizedRect(Float.NaN, 0.1f, 0.2f, 0.2f).toPixelRect(800, 1200))
        assertEquals(Rectangle(0, 0, 0, 0), rect.toPixelRect(0, 1200))
        assertEquals(Rectangle(0, 0, 0, 0), NormalizedRect(1.2f, 0.2f, 0.3f, 0.3f).toPixelRect(800, 1200))
    }

    @Test
    fun `clipToHalf matches PROJECT halfSlice contract for left right and straddling boxes`() {
        val leftOnly = NormalizedRect(0.1f, 0.2f, 0.25f, 0.3f)
        val clippedLeft = leftOnly.clipToHalf(isSecondHalf = false)
        assertNotNull(clippedLeft)
        assertEquals(0.2f, clippedLeft.x, 1e-4f)
        assertEquals(0.2f, clippedLeft.y, 1e-4f)
        assertEquals(0.5f, clippedLeft.width, 1e-4f)
        assertEquals(0.3f, clippedLeft.height, 1e-4f)
        assertNull(leftOnly.clipToHalf(isSecondHalf = true))

        val straddling = NormalizedRect(0.4f, 0.1f, 0.2f, 0.4f)
        val straddleLeft = straddling.clipToHalf(isSecondHalf = false)
        val straddleRight = straddling.clipToHalf(isSecondHalf = true)
        assertNotNull(straddleLeft)
        assertNotNull(straddleRight)
        assertEquals(0.8f, straddleLeft.x, 1e-4f)
        assertEquals(0.2f, straddleLeft.width, 1e-4f)
        assertEquals(0.0f, straddleRight.x, 1e-4f)
        assertEquals(0.2f, straddleRight.width, 1e-4f)
    }

    @Test
    fun `clipToHalf with isRtl matches ReaderEngine getHalfPage semantics`() {
        val rightBubble = NormalizedRect(0.6f, 0.2f, 0.3f, 0.2f)
        val leftBubble = NormalizedRect(0.1f, 0.2f, 0.3f, 0.2f)

        // RTL: subPage 0 (isSecondHalf=false) shows right half [0.5..1.0]
        val rtlSub0Right = rightBubble.clipToHalf(isSecondHalf = false, isRtl = true)
        assertNotNull(rtlSub0Right)
        assertEquals(0.2f, rtlSub0Right.x, 1e-4f)
        assertEquals(0.6f, rtlSub0Right.width, 1e-4f)
        assertNull(leftBubble.clipToHalf(isSecondHalf = false, isRtl = true))

        // RTL: subPage 1 (isSecondHalf=true) shows left half [0.0..0.5]
        val rtlSub1Left = leftBubble.clipToHalf(isSecondHalf = true, isRtl = true)
        assertNotNull(rtlSub1Left)
        assertEquals(0.2f, rtlSub1Left.x, 1e-4f)
        assertEquals(0.6f, rtlSub1Left.width, 1e-4f)
        assertNull(rightBubble.clipToHalf(isSecondHalf = true, isRtl = true))

        // LTR: subPage 0 shows left half, subPage 1 shows right half
        assertNotNull(leftBubble.clipToHalf(isSecondHalf = false, isRtl = false))
        assertNull(rightBubble.clipToHalf(isSecondHalf = false, isRtl = false))
    }

    @Test
    fun `NormalizedRect iou union and padded work accurately`() {
        val a = NormalizedRect(0.1f, 0.1f, 0.2f, 0.2f)
        val b = NormalizedRect(0.2f, 0.1f, 0.2f, 0.2f)
        assertEquals(0.02f, a.intersectionArea(b), 1e-5f)
        assertEquals(0.02f / 0.06f, a.iou(b), 1e-4f)

        val u = a.union(b)
        assertEquals(0.1f, u.x, 1e-5f)
        assertEquals(0.3f, u.width, 1e-5f)

        val padded = NormalizedRect(0.02f, 0.02f, 0.1f, 0.1f).padded(0.05f)
        assertEquals(0f, padded.x, 1e-5f)
        assertEquals(0f, padded.y, 1e-5f)
        assertEquals(0.17f, padded.width, 1e-5f)
        assertEquals(0.17f, padded.height, 1e-5f)

        val negOob = NormalizedRect(-0.8f, 0.5f, 0.3f, 0.2f)
        val posOob = NormalizedRect(1.2f, 0.4f, 0.3f, 0.2f)
        for (res in listOf(a.union(negOob), negOob.union(a), a.union(posOob), posOob.union(a))) {
            assertEquals(a.x, res.x, 1e-5f)
            assertEquals(a.y, res.y, 1e-5f)
            assertEquals(a.width, res.width, 1e-5f)
            assertEquals(a.height, res.height, 1e-5f)
        }
        assertTrue(negOob.union(posOob).isEmpty)
    }

    @Test
    fun `TranslatorLanguage exposes expected codes and vertical model selection`() {
        assertEquals("ja", TranslatorLanguage.JAPANESE.isoCode)
        assertEquals("ja", TranslatorLanguage.JAPANESE.gtxCode)
        assertEquals("ja", TranslatorLanguage.JAPANESE.libreCode)
        assertEquals("ja", TranslatorLanguage.JAPANESE.myMemoryCode)
        assertEquals("jpn", TranslatorLanguage.JAPANESE.tessCode)
        assertEquals("jpn_vert", TranslatorLanguage.JAPANESE.tessVerticalCode)
        assertEquals("jpn_vert", TranslatorLanguage.JAPANESE.tessCodeFor(TextOrientation.VERTICAL))
        assertEquals("jpn", TranslatorLanguage.JAPANESE.tessCodeFor(TextOrientation.HORIZONTAL))
        assertEquals(listOf("jpn", "jpn_vert"), TranslatorLanguage.JAPANESE.requiredTessModels())

        assertEquals("zh-Hans", TranslatorLanguage.CHINESE_SIMPLIFIED.libreCode)
        assertEquals("zh-CN", TranslatorLanguage.CHINESE_SIMPLIFIED.gtxCode)
        assertEquals("pt-BR", TranslatorLanguage.PORTUGUESE_BR.libreCode)
        assertEquals("pt-BR", TranslatorLanguage.PORTUGUESE_BR.myMemoryCode)
        assertEquals("pt", TranslatorLanguage.PORTUGUESE_BR.gtxCode)
        assertEquals("eng", TranslatorLanguage.ENGLISH.tessCodeFor(TextOrientation.VERTICAL))

        assertTrue(TranslatorLanguage.sourceLanguages().contains(TranslatorLanguage.AUTO))
        assertFalse(TranslatorLanguage.targetLanguages().contains(TranslatorLanguage.AUTO))
        assertEquals(listOf("jpn", "eng", "jpn_vert"), TranslatorLanguage.AUTO.requiredTessModels())

        assertEquals(TranslatorLanguage.CHINESE_SIMPLIFIED, TranslatorLanguage.fromIsoCode("zh-Hans"))
        assertEquals(TranslatorLanguage.PORTUGUESE_BR, TranslatorLanguage.fromIsoCode("pt-BR"))
        assertEquals(TranslatorLanguage.ENGLISH, TranslatorLanguage.fromNameOrDefault("invalid", TranslatorLanguage.ENGLISH))
    }

    @Test
    fun `TranslatorConfig defaults match PROJECT specification`() {
        val config = TranslatorConfig()
        assertFalse(config.enabledByDefault)
        assertEquals(TranslatorLanguage.JAPANESE, config.sourceLanguage)
        assertEquals(TranslatorLanguage.PORTUGUESE_BR, config.targetLanguage)
        assertEquals("google_gtx", config.providerId)
        assertEquals("https://libretranslate.com/translate", config.libreTranslateUrl)
        assertEquals("", config.libreTranslateApiKey)
        assertTrue(config.autoDownloadTessdata)
    }
}
