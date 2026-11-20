package mihon.desktop

import mihon.desktop.ui.animation.MotionCurves
import mihon.desktop.ui.animation.MotionEngine
import mihon.desktop.ui.animation.MotionLevel
import mihon.desktop.ui.animation.MotionPreferences
import mihon.desktop.ui.animation.SpringPhysics
import mihon.desktop.ui.components.CjkLabel
import mihon.desktop.ui.components.ExpandableCjkText
import mihon.desktop.ui.components.MihonCheckbox
import mihon.desktop.ui.components.MihonDropdown
import mihon.desktop.ui.components.MihonPasswordField
import mihon.desktop.ui.components.MihonScrubberSlider
import mihon.desktop.ui.components.MihonSearchField
import mihon.desktop.ui.components.MihonSegmentedSelector
import mihon.desktop.ui.components.MihonStepper
import mihon.desktop.ui.components.MihonTextArea
import mihon.desktop.ui.components.MihonTextField
import mihon.desktop.ui.components.SkeletonGridPanel
import mihon.desktop.ui.components.ToastHost
import mihon.desktop.ui.components.ToastType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import javax.swing.JPanel
import kotlin.math.abs

class UpgradeVerificationTest {

    private val prevMotionLevel = MotionPreferences.current

    @AfterEach
    fun tearDown() {
        MotionPreferences.setLevel(prevMotionLevel)
        ReaderAction.resetToDefaults()
        Theme.applyPreset(Theme.ThemePreset.DARK)
    }

    @Test
    fun `motion engine respects OFF level with zero timers and spring physics converges`() {
        MotionPreferences.setLevel(MotionLevel.OFF)
        assertFalse(MotionPreferences.isEnabled)
        assertEquals(0L, MotionPreferences.effectiveDurationMs(180))

        var updatedValue = -1f
        var completed = false
        val handle = MotionEngine.animate(
            from = 0f,
            to = 1f,
            durationMs = 200L,
            onUpdate = { updatedValue = it },
            onComplete = { completed = true },
        )
        assertFalse(handle.isRunning, "OFF mode must not schedule any Swing Timer")
        assertEquals(1f, updatedValue)
        assertTrue(completed)

        MotionPreferences.setLevel(MotionLevel.REDUCED)
        assertTrue(MotionPreferences.isEnabled)
        assertTrue(MotionPreferences.effectiveDurationMs(240) <= 90L)

        MotionPreferences.setLevel(MotionLevel.COMPLETE)
        val spring = SpringPhysics.snappy(0.0).apply { target = 1.0 }
        var steps = 0
        while (spring.step(0.016) && steps < 300) {
            steps++
        }
        assertTrue(steps < 300, "Spring should converge within 300 frames")
        assertTrue(abs(spring.value - 1.0) < 0.002)

        assertEquals(0f, MotionCurves.easeOutCubic(0f))
        assertEquals(1f, MotionCurves.easeOutCubic(1f))
        assertEquals(0f, MotionCurves.easeOutQuint(0f))
        assertEquals(1f, MotionCurves.easeOutQuint(1f))
        assertEquals(0f, MotionCurves.easeInOutQuart(0f))
        assertEquals(1f, MotionCurves.easeInOutQuart(1f))
    }

    @Test
    fun `reader shortcuts support modifier combos and detect conflicts`() {
        val combo = KeyCombo(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)
        assertEquals("Ctrl+Shift+T", combo.toDisplayText())
        val parsed = KeyCombo.parse(combo.serialize())
        assertEquals(combo, parsed)

        ReaderAction.setKeyCombos(ReaderAction.TOGGLE_TRANSLATOR, listOf(combo))
        assertEquals("Ctrl+Shift+T", ReaderAction.getDisplayString(ReaderAction.TOGGLE_TRANSLATOR))

        val conflict = ReaderAction.findConflict(ReaderAction.TOGGLE_HUD, KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)
        assertEquals(ReaderAction.TOGGLE_TRANSLATOR, conflict)

        val noConflict = ReaderAction.findConflict(ReaderAction.TOGGLE_HUD, KeyEvent.VK_T, 0)
        assertNull(noConflict)

        val dummySource = JPanel()
        val matchingEvent = KeyEvent(
            dummySource,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK,
            KeyEvent.VK_T,
            'T',
        )
        val nonMatchingEvent = KeyEvent(
            dummySource,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_T,
            't',
        )
        assertTrue(ReaderAction.matches(ReaderAction.TOGGLE_TRANSLATOR, matchingEvent))
        assertFalse(ReaderAction.matches(ReaderAction.TOGGLE_TRANSLATOR, nonMatchingEvent))
    }

    @Test
    fun `unified input components enforce height masking stepper bounds and clean painting`() {
        val textField = MihonTextField(columns = 20, placeholder = "URL…", showClearButton = true)
        assertEquals(Theme.CONTROL_HEIGHT_COMPACT, textField.preferredSize.height)
        textField.validationError = "Erro de teste"
        assertEquals("Erro de teste", textField.validationError)

        val searchField = MihonSearchField(placeholder = "Buscar…")
        assertEquals(Theme.CONTROL_HEIGHT_COMPACT, searchField.preferredSize.height)

        val passwordField = MihonPasswordField(initialValue = "secret-token-123")
        assertFalse(passwordField.isRevealed)
        assertEquals("secret-token-123", passwordField.plainText)
        assertTrue(passwordField.echoChar != 0.toChar())
        passwordField.setRevealed(true)
        assertTrue(passwordField.isRevealed)
        assertEquals(0.toChar(), passwordField.echoChar)

        val stepper = MihonStepper(initialValue = 10, minValue = 0, maxValue = 50)
        assertEquals(10, stepper.value)
        stepper.value = 999
        assertEquals(50, stepper.value)
        stepper.value = -5
        assertEquals(0, stepper.value)

        val area = MihonTextArea(initialText = "Anotação inicial", maxChars = 100)
        assertEquals("Anotação inicial", area.textArea.text)
        assertDoesNotThrow { area.showError("Campo inválido") }

        val canvas = BufferedImage(320, 60, BufferedImage.TYPE_INT_ARGB)
        val g = canvas.createGraphics()
        try {
            listOf(textField, searchField, passwordField, stepper).forEach { comp ->
                comp.setSize(260, 38)
                assertDoesNotThrow { comp.paint(g) }
            }
        } finally {
            g.dispose()
        }
    }

    @Test
    fun `custom controls scrubber toast host cjk text and skeleton render cleanly`() {
        MotionPreferences.setLevel(MotionLevel.OFF)

        val checkbox = MihonCheckbox("Modo e-Ink", initialSelected = true)
        assertTrue(checkbox.isSelected)

        val dropdown = MihonDropdown(arrayOf("Item A", "Item B"))
        assertEquals(Theme.CONTROL_HEIGHT_COMPACT, dropdown.preferredSize.height)

        var selectedSeg = "A"
        val seg = MihonSegmentedSelector(listOf("A" to "Alfa", "B" to "Beta"), initialValue = "A") { selectedSeg = it }
        seg.setSelectedValue("B", animate = false)
        assertEquals(1, seg.selectedIndex)
        assertEquals("A", selectedSeg) // programmatic setSelectedValue does not fire user callback

        val scrubber = MihonScrubberSlider(1, 30, 12).apply {
            bookmarkPages = setOf(2, 11)
            notePages = setOf(15)
            highlightPages = mapOf(20 to Color.YELLOW)
        }
        assertEquals(12, scrubber.value)

        val toastHost = ToastHost(maxVisible = 3)
        assertEquals(ToastType.ERROR, ToastHost.inferType("Erro ao conectar"))
        assertEquals(ToastType.SUCCESS, ToastHost.inferType("Backup salvo com sucesso"))
        assertEquals(ToastType.WARNING, ToastHost.inferType("Modo anônimo ativado"))
        assertEquals(ToastType.INFO, ToastHost.inferType("Exibindo resultados"))
        toastHost.show("Toast de teste", ToastType.SUCCESS)
        assertTrue(toastHost.isVisible)

        val cjkTitle = CjkLabel("나 혼자만 레벨업 • 進撃の巨人 • 斗罗大陆")
        val expandable = ExpandableCjkText("Linha 1 ".repeat(40) + "\n日本語テスト 한국어 테스트", collapsedMaxLines = 2)
        val skeleton = SkeletonGridPanel(count = 4)
        val loadingBtn = ModernButton("Sincronizando", variant = ModernButton.Variant.PRIMARY).apply {
            isLoading = true
        }
        val badgedIconBtn = ModernIconButton(
            icon = ModernIcons.filter(16) { Theme.TEXT_PRIMARY },
            surfaceStyled = true,
        ).apply {
            badgeCount = 3
            activeHighlight = true
        }

        val canvas = BufferedImage(480, 260, BufferedImage.TYPE_INT_ARGB)
        val g = canvas.createGraphics()
        try {
            listOf(checkbox, seg, scrubber, toastHost, cjkTitle, expandable, skeleton, loadingBtn, badgedIconBtn).forEach { comp ->
                comp.setSize(420, 120)
                assertDoesNotThrow { comp.paint(g) }
            }
            expandable.toggleExpanded()
            assertDoesNotThrow { expandable.paint(g) }
        } finally {
            g.dispose()
        }
        assertNotNull(cjkTitle.preferredSize)
    }

    @Test
    fun `MemoryManager enforces dynamic byte budgets and triggers trim callbacks`() {
        val originalLimit = MemoryManager.ramLimitMb
        var trimCount = 0
        val cb: () -> Unit = { trimCount++ }
        MemoryManager.registerTrimCallback(cb)
        try {
            MemoryManager.ramLimitMb = 256
            assertTrue(MemoryManager.readerImageBudgetBytes(isWebtoon = true) <= 40L * 1024L * 1024L)
            assertEquals(3, MemoryManager.streamRawCacheMaxEntries())
            assertEquals(24, MemoryManager.coverCacheMaxEntries())
            assertEquals(2, MemoryManager.readerDecodeThreads())

            MemoryManager.ramLimitMb = 768
            assertTrue(MemoryManager.readerImageBudgetBytes(isWebtoon = true) >= 150L * 1024L * 1024L)
            assertEquals(12, MemoryManager.streamRawCacheMaxEntries())
            assertTrue(trimCount >= 2, "Changing RAM limit should invoke registered trim callbacks")

            val imgRgb = BufferedImage(100, 200, BufferedImage.TYPE_INT_RGB)
            assertEquals(100L * 200L * 4L, MemoryManager.estimateImageBytes(imgRgb))
            assertTrue(MemoryManager.processRssMb >= 0L)
        } finally {
            MemoryManager.unregisterTrimCallback(cb)
            MemoryManager.ramLimitMb = originalLimit
        }
    }

    @Test
    fun `IncognitoEmojiBadge renders both incognito hand and magnifying glass cleanly`() {
        val badge = mihon.desktop.ui.components.IncognitoEmojiBadge(initialIncognito = false, sizePx = 42)
        badge.setSize(42, 42)
        val canvas = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB)
        val g = canvas.createGraphics()
        try {
            assertDoesNotThrow { badge.paint(g) }
            badge.isIncognito = true
            assertTrue(badge.isIncognito)
            assertDoesNotThrow { badge.paint(g) }
            badge.isIncognito = false
            assertFalse(badge.isIncognito)
            assertDoesNotThrow { badge.paint(g) }
        } finally {
            g.dispose()
        }
    }

    @Test
    fun `GooeyContextMenu renders all reader and library icons without exceptions`() {
        val items = GooeyContextMenu.IconType.entries.take(6).mapIndexed { idx, type ->
            GooeyContextMenu.GooeyMenuItem(
                id = "item_$idx",
                label = "Ação $idx",
                iconType = type,
                isActive = idx == 0,
                shortcutHint = if (idx == 0) "T" else null,
                onClick = {},
            )
        }
        val menu = GooeyContextMenu(items)
        menu.setSize(480, 480)
        val canvas = BufferedImage(480, 480, BufferedImage.TYPE_INT_ARGB)
        val g = canvas.createGraphics()
        try {
            assertDoesNotThrow { menu.paint(g) }
        } finally {
            g.dispose()
        }
    }

    @Test
    fun `MangaImageEnhancer NITIDO in-place clarity and HttpScraperSource shared client work`() {
        val src = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        val out = MangaImageEnhancer.scale(src, 96, 96, MangaImageEnhancer.QualityMode.NITIDO)
        assertEquals(96, out.width)
        assertEquals(96, out.height)
        assertTrue(
            mihon.source.HttpScraperSource.defaultClient() === mihon.source.HttpScraperSource.defaultClient(),
            "HttpScraperSource should share a single OkHttpClient across extensions",
        )
    }
}
