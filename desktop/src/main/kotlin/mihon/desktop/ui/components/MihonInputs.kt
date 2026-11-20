package mihon.desktop.ui.components

import mihon.desktop.ModernButton
import mihon.desktop.ModernIcons
import mihon.desktop.Theme
import mihon.desktop.ui.animation.MotionPreferences
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.abs

internal fun blendComponentColor(a: Color, b: Color, t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    return Color(
        (a.red + (b.red - a.red) * f).toInt().coerceIn(0, 255),
        (a.green + (b.green - a.green) * f).toInt().coerceIn(0, 255),
        (a.blue + (b.blue - a.blue) * f).toInt().coerceIn(0, 255),
        (a.alpha + (b.alpha - a.alpha) * f).toInt().coerceIn(0, 255),
    )
}

/**
 * Unified Mihon Index text field with animated hover/focus border, optional leading icon,
 * optional animated clear button, inline error border, and debounced change notifications.
 */
open class MihonTextField(
    columns: Int = 20,
    placeholder: String = "",
    protected val compact: Boolean = false,
    private val leadingIconProvider: ((focused: Boolean) -> Icon)? = null,
    protected val showClearButton: Boolean = false,
    protected val shortcutBadge: String? = null,
    protected val debounceMs: Int = 120,
    protected val onEscapeEmpty: (() -> Unit)? = null,
) : JTextField(columns) {

    var validationError: String? = null
        set(value) {
            if (field != value) {
                field = value
                toolTipText = value
                repaint()
            }
        }

    var onSubmit: ((String) -> Unit)? = null
    var onDebouncedChange: ((String) -> Unit)? = null

    protected var hovered = false
    private var hoverProgress = 0f
    private var focusProgress = 0f
    private var borderAnimTimer: Timer? = null

    private val clearBounds = Rectangle()
    private var clearHovered = false
    private var clearAlpha = 0f
    private var clearAnimTimer: Timer? = null
    private var lastNonEmpty = false
    private var debounceTimer: Timer? = null

    init {
        if (placeholder.isNotBlank()) {
            putClientProperty("JTextField.placeholderText", placeholder)
        }
        font = Theme.FONT_UI.deriveFont(if (compact) 12.5f else 13f)
        foreground = Theme.TEXT_PRIMARY
        background = Theme.BG_SURFACE
        caretColor = Theme.PRIMARY
        isOpaque = false
        val targetH = if (compact) 34 else Theme.CONTROL_HEIGHT_COMPACT
        minimumSize = Dimension(110, targetH)
        preferredSize = Dimension(preferredSize.width.coerceAtLeast(160), targetH)
        updateInputPadding()

        addActionListener { onSubmit?.invoke(text.orEmpty()) }

        document.addDocumentListener(object : DocumentListener {
            private fun handleEdit() {
                val txt = text.orEmpty()
                if (validationError != null && txt.isNotEmpty()) {
                    validationError = null
                }
                if (showClearButton) {
                    val nonEmpty = txt.isNotEmpty()
                    if (nonEmpty != lastNonEmpty) {
                        lastNonEmpty = nonEmpty
                        animateClearButton(nonEmpty)
                    }
                }
                val cb = onDebouncedChange ?: return
                if (debounceMs <= 0) {
                    cb(txt)
                } else {
                    debounceTimer?.stop()
                    debounceTimer = Timer(debounceMs) {
                        cb(text.orEmpty())
                    }.apply {
                        isRepeats = false
                        start()
                    }
                }
            }
            override fun insertUpdate(e: DocumentEvent?) = handleEdit()
            override fun removeUpdate(e: DocumentEvent?) = handleEdit()
            override fun changedUpdate(e: DocumentEvent?) = handleEdit()
        })

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                if (!showClearButton) return
                val over = !text.isNullOrEmpty() && clearBounds.contains(e.point)
                if (over != clearHovered) {
                    clearHovered = over
                    cursor = if (over) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
                    repaint()
                }
            }
        })

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                startBorderAnim()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                if (clearHovered) {
                    clearHovered = false
                    cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
                }
                startBorderAnim()
            }

            override fun mousePressed(e: MouseEvent) {
                if (showClearButton && !text.isNullOrEmpty() && clearBounds.contains(e.point)) {
                    text = ""
                    debounceTimer?.stop()
                    onDebouncedChange?.invoke("")
                    requestFocusInWindow()
                    e.consume()
                }
            }
        })

        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent?) = startBorderAnim()
            override fun focusLost(e: FocusEvent?) = startBorderAnim()
        })

        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    if (!text.isNullOrEmpty()) {
                        text = ""
                        debounceTimer?.stop()
                        onDebouncedChange?.invoke("")
                    } else {
                        onEscapeEmpty?.invoke()
                        transferFocus()
                    }
                    e.consume()
                }
            }
        })
    }

    protected fun updateInputPadding() {
        val leftPad = if (leadingIconProvider != null) 34 else 12
        val rightPad = when {
            !shortcutBadge.isNullOrBlank() -> 54
            showClearButton -> 32
            else -> 12
        }
        val vertPad = if (compact) 6 else 8
        border = BorderFactory.createEmptyBorder(vertPad, leftPad, vertPad, rightPad)
    }

    fun applyThemeColors() {
        foreground = Theme.TEXT_PRIMARY
        background = Theme.BG_SURFACE
        caretColor = Theme.PRIMARY
        repaint()
    }

    private fun animateClearButton(visible: Boolean) {
        val target = if (visible) 1f else 0f
        if (!MotionPreferences.isEnabled) {
            clearAlpha = target
            repaint()
            return
        }
        clearAnimTimer?.stop()
        clearAnimTimer = Timer(14) { ev ->
            if (!isDisplayable) {
                (ev.source as? Timer)?.stop()
                return@Timer
            }
            clearAlpha += (target - clearAlpha) * 0.34f
            if (abs(target - clearAlpha) < 0.03f) {
                clearAlpha = target
                (ev.source as? Timer)?.stop()
            }
            repaint()
        }.also { it.start() }
    }

    private fun startBorderAnim() {
        val targetH = if (hovered && isEnabled) 1f else 0f
        val targetF = if (isFocusOwner && isEnabled) 1f else 0f
        if (!MotionPreferences.isEnabled) {
            hoverProgress = targetH
            focusProgress = targetF
            repaint()
            return
        }
        if (borderAnimTimer?.isRunning == true) return
        borderAnimTimer = Timer(14) { ev ->
            if (!isDisplayable) {
                (ev.source as? Timer)?.stop()
                return@Timer
            }
            val th = if (hovered && isEnabled) 1f else 0f
            val tf = if (isFocusOwner && isEnabled) 1f else 0f
            hoverProgress += (th - hoverProgress) * 0.30f
            focusProgress += (tf - focusProgress) * 0.32f
            if (abs(th - hoverProgress) < 0.02f) hoverProgress = th
            if (abs(tf - focusProgress) < 0.02f) focusProgress = tf
            repaint()
            if (hoverProgress == th && focusProgress == tf) {
                (ev.source as? Timer)?.stop()
            }
        }.also { it.start() }
    }

    override fun removeNotify() {
        borderAnimTimer?.stop()
        borderAnimTimer = null
        clearAnimTimer?.stop()
        clearAnimTimer = null
        debounceTimer?.stop()
        debounceTimer = null
        super.removeNotify()
    }

    override fun paintBorder(g: Graphics) {}

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        val arc = (Theme.CONTROL_RADIUS * 2).toFloat()
        val w = width.toFloat()
        val h = height.toFloat()

        g2.color = if (isEnabled) Theme.BG_SURFACE else blendComponentColor(Theme.BG_APP, Theme.BG_SURFACE, 0.5f)
        g2.fill(RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, arc, arc))

        val baseBorder = blendComponentColor(Theme.BORDER_SUBTLE, Theme.BORDER_STRONG, hoverProgress)
        val hasErr = !validationError.isNullOrBlank()
        val borderColor = when {
            hasErr -> Theme.DANGER
            focusProgress > 0.01f -> blendComponentColor(baseBorder, Theme.PRIMARY, focusProgress)
            else -> baseBorder
        }
        g2.color = borderColor
        g2.stroke = BasicStroke(if (hasErr || focusProgress > 0.5f) 1.5f else 1f)
        g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, arc, arc))

        leadingIconProvider?.invoke(isFocusOwner)?.let { icon ->
            icon.paintIcon(this, g2, 11, (height - icon.iconHeight) / 2)
        }

        if (showClearButton && clearAlpha > 0.02f) {
            val btnSize = 20
            val bx = width - btnSize - 7
            val by = (height - btnSize) / 2
            clearBounds.setBounds(bx - 2, by - 2, btnSize + 4, btnSize + 4)
            val alpha = clearAlpha.coerceIn(0f, 1f)
            if (clearHovered) {
                val hc = Theme.BG_CARD_HOVER
                g2.color = Color(hc.red, hc.green, hc.blue, (230 * alpha).toInt())
                g2.fillOval(bx, by, btnSize, btnSize)
            }
            val iconCol = if (clearHovered) Theme.TEXT_PRIMARY else Theme.TEXT_MUTED
            ModernIcons.close(11) { Color(iconCol.red, iconCol.green, iconCol.blue, (255 * alpha).toInt()) }
                .paintIcon(this, g2, bx + (btnSize - 11) / 2, by + (btnSize - 11) / 2)
        } else {
            clearBounds.setBounds(0, 0, 0, 0)
            if (!shortcutBadge.isNullOrBlank() && !isFocusOwner && text.isNullOrEmpty() && width >= 210) {
                g2.font = Theme.FONT_MONO.deriveFont(Font.PLAIN, 10.5f)
                val fm = g2.fontMetrics
                val pillW = fm.stringWidth(shortcutBadge) + 12
                val pillH = 20
                val px = width - pillW - 9
                val py = (height - pillH) / 2
                g2.color = Theme.BG_CARD
                g2.fill(RoundRectangle2D.Float(px.toFloat(), py.toFloat(), pillW.toFloat(), pillH.toFloat(), 10f, 10f))
                g2.color = Theme.BORDER_SUBTLE
                g2.stroke = BasicStroke(1f)
                g2.draw(RoundRectangle2D.Float(px.toFloat(), py.toFloat(), pillW - 1f, pillH - 1f, 10f, 10f))
                g2.color = Theme.TEXT_MUTED
                g2.drawString(shortcutBadge, px + 6, py + (pillH - fm.height) / 2 + fm.ascent)
            }
        }
        g2.dispose()
        super.paintComponent(g)
    }
}

/**
 * Unified search input with built-in search icon, animated clear `×` button,
 * optional shortcut badge, Esc clear-then-blur, and debounced search callback.
 */
class MihonSearchField(
    columns: Int = 22,
    placeholder: String = "Pesquisar…",
    compact: Boolean = false,
    shortcutBadge: String? = "Ctrl+F",
    debounceMs: Int = 120,
    onEscapeEmpty: (() -> Unit)? = null,
) : MihonTextField(
    columns = columns,
    placeholder = placeholder,
    compact = compact,
    leadingIconProvider = { focused ->
        ModernIcons.search(15) {
            if (focused) (if (Theme.isDark) Theme.ACCENT else Theme.PRIMARY)
            else if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
        }
    },
    showClearButton = true,
    shortcutBadge = shortcutBadge,
    debounceMs = debounceMs,
    onEscapeEmpty = onEscapeEmpty,
)

/**
 * Masked password / OAuth token input with animated focus border and interactive show/hide eye toggle.
 */
class MihonPasswordField(
    initialValue: String = "",
    columns: Int = 22,
    placeholder: String = "",
    private val compact: Boolean = false,
) : JPasswordField(initialValue, columns) {

    var isRevealed: Boolean = false
        private set

    val plainText: String get() = String(password)

    private val defaultEchoChar: Char = if (echoChar != 0.toChar()) echoChar else '•'
    private val toggleBounds = Rectangle()
    private var hovered = false
    private var toggleHovered = false

    init {
        if (placeholder.isNotBlank()) {
            putClientProperty("JTextField.placeholderText", placeholder)
        }
        echoChar = defaultEchoChar
        font = Theme.FONT_UI.deriveFont(if (compact) 12.5f else 13f)
        foreground = Theme.TEXT_PRIMARY
        background = Theme.BG_SURFACE
        caretColor = Theme.PRIMARY
        isOpaque = false
        val targetH = if (compact) 34 else Theme.CONTROL_HEIGHT_COMPACT
        minimumSize = Dimension(140, targetH)
        preferredSize = Dimension(preferredSize.width.coerceAtLeast(200), targetH)
        border = BorderFactory.createEmptyBorder(if (compact) 6 else 8, 12, if (compact) 6 else 8, 36)

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val over = toggleBounds.contains(e.point)
                if (over != toggleHovered) {
                    toggleHovered = over
                    cursor = if (over) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
                    repaint()
                }
            }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
            override fun mouseExited(e: MouseEvent) {
                hovered = false
                toggleHovered = false
                cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
                repaint()
            }

            override fun mousePressed(e: MouseEvent) {
                if (toggleBounds.contains(e.point)) {
                    setRevealed(!isRevealed)
                    e.consume()
                }
            }
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent?) { repaint() }
            override fun focusLost(e: FocusEvent?) { repaint() }
        })
    }

    fun setRevealed(show: Boolean) {
        isRevealed = show
        echoChar = if (show) 0.toChar() else defaultEchoChar
        repaint()
    }

    override fun paintBorder(g: Graphics) {}

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = (Theme.CONTROL_RADIUS * 2).toFloat()
        val w = width.toFloat()
        val h = height.toFloat()

        g2.color = Theme.BG_SURFACE
        g2.fill(RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, arc, arc))
        g2.color = when {
            isFocusOwner -> Theme.PRIMARY
            hovered -> Theme.BORDER_STRONG
            else -> Theme.BORDER_SUBTLE
        }
        g2.stroke = BasicStroke(if (isFocusOwner) 1.5f else 1f)
        g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, arc, arc))

        val btnSize = 22
        val bx = width - btnSize - 7
        val by = (height - btnSize) / 2
        toggleBounds.setBounds(bx - 2, by - 2, btnSize + 4, btnSize + 4)
        if (toggleHovered) {
            g2.color = Theme.BG_CARD_HOVER
            g2.fillOval(bx, by, btnSize, btnSize)
        }
        val iconColor = if (isRevealed) (if (Theme.isDark) Theme.ACCENT else Theme.PRIMARY) else Theme.TEXT_MUTED
        paintEyeIcon(g2, bx + 3, by + 3, 16, iconColor, slashed = !isRevealed)
        g2.dispose()
        super.paintComponent(g)
    }

    private fun paintEyeIcon(g2: Graphics2D, x: Int, y: Int, size: Int, color: Color, slashed: Boolean) {
        val g = g2.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = color
        g.stroke = BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val cy = y + size / 2.0
        g.drawOval(x + 1, (cy - 4.0).toInt(), size - 2, 8)
        g.fillOval(x + size / 2 - 2, (cy - 2.0).toInt(), 4, 4)
        if (slashed) {
            g.drawLine(x + 2, y + size - 2, x + size - 2, y + 2)
        }
        g.dispose()
    }
}

/**
 * Rounded styled multi-line text area with placeholder, live character counter,
 * inline error message, and Ctrl+Enter submission shortcut.
 */
class MihonTextArea(
    initialText: String = "",
    private val placeholder: String = "Escreva sua nota aqui… (Ctrl+Enter para salvar)",
    private val maxChars: Int = 2000,
) : JPanel(BorderLayout(0, 6)) {

    var onSubmitShortcut: (() -> Unit)? = null

    val textArea = object : JTextArea(initialText) {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (text.isEmpty() && placeholder.isNotBlank()) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
                g2.font = font
                g2.color = Theme.TEXT_MUTED
                val ins = insets
                g2.drawString(placeholder, ins.left + 2, ins.top + g2.fontMetrics.ascent)
                g2.dispose()
            }
        }
    }.apply {
        lineWrap = true
        wrapStyleWord = true
        font = Theme.FONT_UI.deriveFont(13f)
        background = Theme.BG_SURFACE
        foreground = Theme.TEXT_PRIMARY
        caretColor = Theme.PRIMARY
        margin = Insets(10, 12, 10, 12)
    }

    private val errorLabel = JLabel("").apply {
        font = Theme.FONT_UI.deriveFont(Font.BOLD, 11.5f)
        foreground = Theme.DANGER
        isVisible = false
    }

    private val counterLabel = JLabel("0 / $maxChars").apply {
        font = Theme.FONT_MONO.deriveFont(Font.PLAIN, 11f)
        foreground = Theme.TEXT_MUTED
    }

    private var focused = false
    private var hasError = false

    init {
        isOpaque = false
        val scroll = JScrollPane(textArea).apply {
            border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
            isOpaque = false
            viewport.background = Theme.BG_SURFACE
            verticalScrollBar.unitIncrement = 16
        }
        val frame = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = (Theme.CONTROL_RADIUS * 2).toFloat()
                g2.color = Theme.BG_SURFACE
                g2.fill(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc))
                g2.color = when {
                    hasError -> Theme.DANGER
                    focused -> Theme.PRIMARY
                    else -> Theme.BORDER_SUBTLE
                }
                g2.stroke = BasicStroke(if (focused || hasError) 1.5f else 1f)
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc))
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(3, 3, 3, 3)
            add(scroll, BorderLayout.CENTER)
        }

        val footer = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(errorLabel, BorderLayout.WEST)
            add(counterLabel, BorderLayout.EAST)
        }

        add(frame, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)

        textArea.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent?) { focused = true; frame.repaint() }
            override fun focusLost(e: FocusEvent?) { focused = false; frame.repaint() }
        })
        textArea.document.addDocumentListener(object : DocumentListener {
            private fun updateCount() {
                val len = textArea.text.length
                counterLabel.text = "$len / $maxChars"
                counterLabel.foreground = if (len > maxChars) Theme.DANGER else Theme.TEXT_MUTED
                if (hasError && len in 1..maxChars) {
                    showError(null)
                }
            }
            override fun insertUpdate(e: DocumentEvent?) = updateCount()
            override fun removeUpdate(e: DocumentEvent?) = updateCount()
            override fun changedUpdate(e: DocumentEvent?) = updateCount()
        })
        textArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && e.isControlDown) {
                    onSubmitShortcut?.invoke()
                    e.consume()
                }
            }
        })
        counterLabel.text = "${textArea.text.length} / $maxChars"
    }

    fun showError(message: String?) {
        hasError = !message.isNullOrBlank()
        errorLabel.text = message.orEmpty()
        errorLabel.isVisible = hasError
        repaint()
    }
}

/**
 * Clean desktop numeric integer stepper (`−  [value]  +`) replacing raw Swing JSpinner.
 */
class MihonStepper(
    initialValue: Int = 0,
    private val minValue: Int = 0,
    private val maxValue: Int = 99999,
) : JPanel(BorderLayout(6, 0)) {

    private var currentValue: Int = initialValue.coerceIn(minValue, maxValue)

    private val valueField = MihonTextField(columns = 5, compact = true).apply {
        horizontalAlignment = SwingConstants.CENTER
        preferredSize = Dimension(74, 34)
        minimumSize = Dimension(64, 34)
        text = currentValue.toString()
        addActionListener { parseField() }
        addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) = parseField()
        })
    }

    private val minusBtn = ModernButton("−", variant = ModernButton.Variant.SURFACE, compact = true).apply {
        minWidthOverride = 34
        minHeightOverride = 34
        addActionListener { value = currentValue - 1 }
    }

    private val plusBtn = ModernButton("+", variant = ModernButton.Variant.SURFACE, compact = true).apply {
        minWidthOverride = 34
        minHeightOverride = 34
        addActionListener { value = currentValue + 1 }
    }

    var value: Int
        get() {
            parseField()
            return currentValue
        }
        set(newVal) {
            currentValue = newVal.coerceIn(minValue, maxValue)
            valueField.text = currentValue.toString()
        }

    init {
        isOpaque = false
        preferredSize = Dimension(154, 34)
        maximumSize = Dimension(180, 34)
        add(minusBtn, BorderLayout.WEST)
        add(valueField, BorderLayout.CENTER)
        add(plusBtn, BorderLayout.EAST)
    }

    private fun parseField() {
        val parsed = valueField.text.trim().toIntOrNull()
        if (parsed != null) {
            currentValue = parsed.coerceIn(minValue, maxValue)
            valueField.text = currentValue.toString()
        } else {
            valueField.text = currentValue.toString()
        }
    }
}
