package tachiyomi.i18n

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Locale

class MRTest {

    @Test
    fun testDefaultLocale() {
        // Test that default locale works
        MR.setLocaleByTag("en") // Ensure clean state
        assertNotNull(MR.locale)
        assertEquals("Mihon", MR["app_name"])
        assertEquals("Mihon", MR.strings.app_name)
    }

    @Test
    fun testEnglishLocale() {
        MR.setLocaleByTag("en")
        assertEquals("Mihon", MR.strings.app_name)
        assertEquals("Settings", MR.strings.settings)
        assertEquals("Library", MR.strings.label_library)
        assertEquals("Cancel", MR.strings.cancel)
        assertEquals("OK", MR.strings.ok)
    }

    @Test
    fun testPortugueseLocale() {
        MR.resetToSystemLocale()
        MR.setLocaleByTag("pt_BR")
        assertEquals("Mihon", MR.strings.app_name)
        assertEquals("Configurações", MR.strings.settings)
        assertEquals("Biblioteca", MR.strings.label_library)
        assertEquals("Cancelar", MR.strings.cancel)
        assertEquals("OK", MR.strings.ok)
    }

    @Test
    fun testSpanishLocale() {
        MR.resetToSystemLocale()
        MR.setLocaleByTag("es")
        assertEquals("Mihon", MR.strings.app_name)
        assertEquals("Ajustes", MR.strings.settings)
        assertEquals("Biblioteca", MR.strings.label_library)
        assertEquals("Cancelar", MR.strings.cancel)
        assertEquals("OK", MR.strings.ok)
    }

    @Test
    fun testJapaneseLocale() {
        MR.resetToSystemLocale()
        MR.setLocaleByTag("ja")
        assertEquals("Mihon", MR.strings.app_name)
        assertEquals("設定", MR.strings.settings)
        assertEquals("ライブラリ", MR.strings.label_library)
        assertEquals("キャンセル", MR.strings.cancel)
        assertEquals("OK", MR.strings.ok)
    }

    @Test
    fun testChineseLocale() {
        MR.resetToSystemLocale()
        MR.setLocaleByTag("zh_CN")
        assertEquals("Mihon", MR.strings.app_name)
        assertEquals("设置", MR.strings.settings)
        assertEquals("书架", MR.strings.label_library)
        assertEquals("取消", MR.strings.cancel)
        assertEquals("确定", MR.strings.ok)
    }

    @Test
    fun testFormatters() {
        MR.resetToSystemLocale()
        MR.setLocaleByTag("en")
        val dateFormatter = MR.format.dateFormatter()
        val timeFormatter = MR.format.timeFormatter()
        val numberFormatter = MR.format.numberFormatter()
        
        assertNotNull(dateFormatter)
        assertNotNull(timeFormatter)
        assertNotNull(numberFormatter)
        
        // Test with a known date
        val testDate = java.time.LocalDate.of(2024, 1, 15)
        val formatted = dateFormatter.format(testDate)
        assertTrue(formatted.isNotEmpty())
        
        // Test number formatting
        val formattedNumber = numberFormatter.format(1234567.89)
        assertTrue(formattedNumber.contains("1") || formattedNumber.contains("2") || formattedNumber.contains("3"))
    }

    @Test
    fun testRTLSupport() {
        MR.resetToSystemLocale()
        MR.setLocaleByTag("ar")
        assertTrue(MR.orientation.isRTL)
        
        // Test RTL detection for Hebrew
        MR.resetToSystemLocale()
        MR.setLocaleByTag("he")
        assertTrue(MR.orientation.isRTL)
        
        // Test LTR for English
        MR.resetToSystemLocale()
        MR.setLocaleByTag("en")
        assertFalse(MR.orientation.isRTL)
        
        // Test component orientation
        val rtlOrientation = MR.orientation.componentOrientation
        assertNotNull(rtlOrientation)
        
        MR.resetToSystemLocale()
        MR.setLocaleByTag("en")
        val ltrOrientation = MR.orientation.componentOrientation
        assertEquals(java.awt.ComponentOrientation.LEFT_TO_RIGHT, ltrOrientation)
    }

    @Test
    fun testMissingKeyFallback() {
        MR.resetToSystemLocale()
        MR.setLocaleByTag("en")
        val missing = MR["non_existent_key_12345"]
        assertEquals("non_existent_key_12345", missing)
    }

    @Test
    fun testStringFormatting() {
        MR.resetToSystemLocale()
        MR.setLocaleByTag("en")
        // Test with format args (if any keys support it)
        val formatted = MR.getString("delete_category_confirmation", "Test Category")
        assertTrue(formatted.contains("Test Category"))
    }

    @Test
    fun testAvailableLocales() {
        val locales = MR.getAvailableLocales()
        assertEquals(5, locales.size)
        assertTrue(locales.contains(Locale.ENGLISH))
        assertTrue(locales.contains(Locale("pt", "BR")))
        assertTrue(locales.contains(Locale("es")))
        assertTrue(locales.contains(Locale("ja")))
        assertTrue(locales.contains(Locale("zh", "CN")))
    }

    @Test
    fun testLocaleDisplayName() {
        MR.resetToSystemLocale()
        MR.setLocaleByTag("en")
        val ptBR = MR.getLocaleDisplayName(Locale("pt", "BR"))
        assertTrue(ptBR.isNotEmpty())
        
        MR.resetToSystemLocale()
        MR.setLocaleByTag("pt_BR")
        val english = MR.getLocaleDisplayName(Locale.ENGLISH)
        assertTrue(english.isNotEmpty())
    }
}