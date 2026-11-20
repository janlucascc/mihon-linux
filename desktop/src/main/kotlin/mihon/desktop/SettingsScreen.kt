package mihon.desktop

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.io.File
import java.util.Locale
import java.util.prefs.Preferences
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import mihon.desktop.translator.TranslationProviderRegistry
import mihon.desktop.translator.TranslatorLanguage
import mihon.desktop.translator.TranslatorPreferences
import mihon.desktop.ui.animation.MotionLevel
import mihon.desktop.ui.animation.MotionPreferences
import mihon.desktop.ui.components.MihonDropdown
import mihon.desktop.ui.components.MihonPasswordField
import mihon.desktop.ui.components.MihonSearchField
import mihon.desktop.ui.components.MihonSegmentedSelector
import mihon.desktop.ui.components.MihonTextField
import tachiyomi.i18n.MR
import kotlinx.coroutines.CoroutineScope

/**
 * AntiGravity-inspired Segmented Pill Selector (e.g., [ Queue | Send Immediately ] or [ External Browser | Built-in Browser ]).
 * Features smooth 60 FPS ease-out cubic sliding indicator pill and crisp typography.
 */
class SegmentedPillSelector<T>(
    private val options: List<Pair<T, String>>,
    initialValue: T,
    private val onSelected: (T) -> Unit,
) : JComponent() {

    var selectedIndex: Int = options.indexOfFirst { it.first == initialValue }.coerceAtLeast(0)
        private set

    private var hoveredIndex: Int = -1
    private var animIndicatorX: Float = -1f
    private var animIndicatorW: Float = -1f
    private var animTimer: javax.swing.Timer? = null
    private var segmentBounds: Array<IntArray> = emptyArray()

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12f)
        recalculatePreferredSize()

        addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                if (hoveredIndex != -1) {
                    hoveredIndex = -1
                    repaint()
                }
            }

            override fun mousePressed(e: MouseEvent) {
                val clicked = segmentAt(e.x)
                if (clicked >= 0 && clicked != selectedIndex) {
                    selectIndex(clicked, animate = true, notify = true)
                }
            }
        })

        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val idx = segmentAt(e.x)
                if (idx != hoveredIndex) {
                    hoveredIndex = idx
                    repaint()
                }
            }
        })
    }

    override fun removeNotify() {
        animTimer?.stop()
        animTimer = null
        super.removeNotify()
    }

    fun setSelectedValue(value: T, animate: Boolean = false) {
        val idx = options.indexOfFirst { it.first == value }
        if (idx >= 0 && idx != selectedIndex) {
            selectIndex(idx, animate = animate, notify = false)
        }
    }

    private fun selectIndex(newIndex: Int, animate: Boolean, notify: Boolean) {
        selectedIndex = newIndex
        if (segmentBounds.size == options.size && animate && animIndicatorX >= 0f) {
            val targetX = segmentBounds[newIndex][0].toFloat()
            val targetW = segmentBounds[newIndex][1].toFloat()
            val startX = animIndicatorX
            val startW = animIndicatorW
            val startTime = System.currentTimeMillis()
            val duration = 180L
            animTimer?.stop()
            animTimer = javax.swing.Timer(12) { ev ->
                val elapsed = System.currentTimeMillis() - startTime
                val t = (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                val inv = 1f - t
                val eased = 1f - (inv * inv * inv)
                animIndicatorX = startX + (targetX - startX) * eased
                animIndicatorW = startW + (targetW - startW) * eased
                repaint()
                if (t >= 1f) {
                    (ev.source as? javax.swing.Timer)?.stop()
                    animIndicatorX = targetX
                    animIndicatorW = targetW
                    repaint()
                }
            }.also { it.start() }
        } else {
            animIndicatorX = -1f
            repaint()
        }
        if (notify) {
            onSelected(options[newIndex].first)
        }
    }

    private fun recalculatePreferredSize() {
        val fm = getFontMetrics(font.deriveFont(Font.BOLD, 12f))
        var totalW = 6 // outer horizontal padding
        for ((_, label) in options) {
            totalW += fm.stringWidth(label) + 16
        }
        val size = Dimension(totalW, 34)
        preferredSize = size
        minimumSize = size
        maximumSize = size
    }

    private fun segmentAt(x: Int): Int {
        for (i in segmentBounds.indices) {
            val sx = segmentBounds[i][0]
            val sw = segmentBounds[i][1]
            if (x in sx..(sx + sw)) return i
        }
        return -1
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        val w = width.toFloat()
        val h = height.toFloat()
        val arc = (Theme.CONTROL_RADIUS * 2).toFloat()

        // Neutral container track
        val trackBg = Theme.BG_APP
        val trackBorder = Theme.BORDER_SUBTLE
        g2.color = trackBg
        g2.fill(RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, arc, arc))
        g2.color = trackBorder
        g2.stroke = BasicStroke(1f)
        g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, arc, arc))

        // Calculate segment bounds
        val fmBold = g2.getFontMetrics(font.deriveFont(Font.BOLD, 12f))
        val bounds = Array(options.size) { IntArray(2) }
        var curX = 3
        for (i in options.indices) {
            val segW = fmBold.stringWidth(options[i].second) + 16
            bounds[i][0] = curX
            bounds[i][1] = segW
            curX += segW
        }
        segmentBounds = bounds

        if (selectedIndex in bounds.indices) {
            if (animIndicatorX < 0f) {
                animIndicatorX = bounds[selectedIndex][0].toFloat()
                animIndicatorW = bounds[selectedIndex][1].toFloat()
            }
            val indX = animIndicatorX
            val indW = animIndicatorW
            val indY = 3f
            val indH = h - 6f
            val indArc = 12f

            // Solid Cobalt active segment
            g2.color = Theme.PRIMARY
            g2.fill(RoundRectangle2D.Float(indX, indY, indW, indH, indArc, indArc))
        }

        // Draw labels
        for (i in options.indices) {
            val isSel = i == selectedIndex
            val isHov = i == hoveredIndex
            g2.font = font.deriveFont(if (isSel) Font.BOLD else Font.PLAIN, 12f)
            val fm = g2.fontMetrics
            g2.color = when {
                isSel -> Color.WHITE
                isHov -> Theme.TEXT_PRIMARY
                else -> Theme.TEXT_SECONDARY
            }
            val sx = bounds[i][0]
            val sw = bounds[i][1]
            val text = options[i].second
            val tx = sx + (sw - fm.stringWidth(text)) / 2
            val ty = ((h.toInt() - fm.height) / 2) + fm.ascent
            g2.drawString(text, tx, ty)
        }

        g2.dispose()
    }
}

/**
 * AntiGravity Settings Group Card:
 * Rounded container (`arc = 14px`) with subtle 1px border and internal hairline dividers between rows.
 */
class SettingsGroupCard : JPanel() {
    private val rows = mutableListOf<JComponent>()
    private val dividers = mutableListOf<JPanel>()

    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
        alignmentX = 0f
    }

    fun addRow(row: JComponent) {
        if (rows.isNotEmpty()) {
            val div = object : JPanel() {
                override fun paintComponent(g: Graphics) {
                    g.color = Theme.BORDER_SUBTLE
                    g.fillRect(0, 0, width, 1)
                }
            }.apply {
                isOpaque = false
                alignmentX = 0f
                preferredSize = Dimension(10, 1)
                maximumSize = Dimension(Int.MAX_VALUE, 1)
            }
            dividers.add(div)
            add(div)
        }
        row.alignmentX = 0f
        rows.add(row)
        add(row)
    }

    fun applyFilter(queryLower: String): Int {
        var visibleCount = 0
        var seenVisible = false
        for (i in rows.indices) {
            val r = rows[i]
            val searchText = (r.getClientProperty("settingsSearchText") as? String).orEmpty()
            val matches = queryLower.isBlank() || searchText.contains(queryLower)
            r.isVisible = matches
            if (i > 0 && i - 1 < dividers.size) {
                dividers[i - 1].isVisible = matches && seenVisible
            }
            if (matches) {
                visibleCount++
                seenVisible = true
            }
        }
        isVisible = visibleCount > 0
        revalidate()
        repaint()
        return visibleCount
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val w = width.toFloat()
        val h = height.toFloat()
        val arc = (Theme.CARD_RADIUS * 2).toFloat()

        val cardBg = Theme.BG_SURFACE
        val borderColor = Theme.BORDER_SUBTLE

        g2.color = cardBg
        g2.fill(RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, arc, arc))
        g2.color = borderColor
        g2.stroke = BasicStroke(1f)
        g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, arc, arc))

        g2.dispose()
        super.paintComponent(g)
    }
}

/**
 * AntiGravity Sidebar Navigation Button for Settings Categories.
 */
class AntiGravityCategoryButton(
    val titleText: String,
    private val navIcon: Icon? = null,
) : JToggleButton() {

    private var hoverProgress = 0f
    private var activeProgress = 0f
    private val animTimer = javax.swing.Timer(14) {
        val targetHover = if (model.isRollover && !isSelected) 1f else 0f
        val targetActive = if (isSelected) 1f else 0f
        hoverProgress += (targetHover - hoverProgress) * 0.25f
        activeProgress += (targetActive - activeProgress) * 0.28f
        if (kotlin.math.abs(targetHover - hoverProgress) < 0.01f &&
            kotlin.math.abs(targetActive - activeProgress) < 0.01f
        ) {
            hoverProgress = targetHover
            activeProgress = targetActive
            (it.source as javax.swing.Timer).stop()
        }
        repaint()
    }

    init {
        isOpaque = false
        isFocusable = false
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        alignmentX = 0f
        preferredSize = Dimension(190, 40)
        maximumSize = Dimension(Int.MAX_VALUE, 40)
        model.addChangeListener {
            if (!isDisplayable) {
                hoverProgress = if (model.isRollover && !isSelected) 1f else 0f
                activeProgress = if (isSelected) 1f else 0f
                repaint()
            } else if (!animTimer.isRunning) {
                animTimer.start()
            }
        }
    }

    override fun addNotify() {
        super.addNotify()
        hoverProgress = if (model.isRollover && !isSelected) 1f else 0f
        activeProgress = if (isSelected) 1f else 0f
    }

    override fun removeNotify() {
        animTimer.stop()
        super.removeNotify()
    }

    override fun paintBorder(g: Graphics) {}

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        val w = width
        val h = height
        val arc = Theme.CONTROL_RADIUS

        if (hoverProgress > 0.01f && activeProgress < 0.99f) {
            val baseHover = Theme.BG_CARD_HOVER
            g2.color = Color(
                baseHover.red,
                baseHover.green,
                baseHover.blue,
                (255 * hoverProgress * (1f - activeProgress)).toInt().coerceIn(0, 255),
            )
            g2.fillRoundRect(2, 2, w - 4, h - 4, arc, arc)
        }
        if (activeProgress > 0.01f) {
            val activeBg = Theme.BG_PILL_ACTIVE
            g2.color = Color(activeBg.red, activeBg.green, activeBg.blue, (activeBg.alpha * activeProgress).toInt().coerceIn(0, 255))
            g2.fillRoundRect(2, 2, w - 4, h - 4, arc, arc)
            val barH = ((h - 16) * activeProgress).toInt().coerceAtLeast(4)
            g2.color = Color(Theme.PRIMARY.red, Theme.PRIMARY.green, Theme.PRIMARY.blue, (255 * activeProgress).toInt().coerceIn(0, 255))
            g2.fillRoundRect(2, (h - barH) / 2, 3, barH, 3, 3)
        }

        var textX = 14
        if (navIcon != null) {
            val iy = (h - navIcon.iconHeight) / 2
            navIcon.paintIcon(this, g2, 12, iy)
            textX = 12 + navIcon.iconWidth + 10
        }

        g2.font = Theme.FONT_UI.deriveFont(if (isSelected) Font.BOLD else Font.PLAIN, 13f)
        val activeColor = if (Theme.isDark) Theme.ACCENT else Theme.PRIMARY
        g2.color = when {
            isSelected -> blendUiColor(Theme.TEXT_SECONDARY, activeColor, activeProgress)
            hoverProgress > 0.01f -> blendUiColor(Theme.TEXT_SECONDARY, Theme.TEXT_PRIMARY, hoverProgress)
            else -> Theme.TEXT_SECONDARY
        }
        val fm = g2.fontMetrics
        val ty = ((h - fm.height) / 2) + fm.ascent
        g2.drawString(titleText, textX, ty)

        g2.dispose()
    }
}

/**
 * Full AntiGravity-styled Settings Screen with Master-Detail Split View, Section Headers,
 * Grouped Cards with internal dividers, Segmented Pill Selectors, and animated Switches.
 */
class AntiGravitySettingsScreen(
    private val ctx: AppContext,
    private val prefs: Preferences,
    private val readerPrefs: Preferences,
    private val dbFile: File,
    private val homeDir: File,
    private val onNavigateBack: () -> Unit,
    private val onApplyTheme: (Theme.ThemePreset) -> Unit,
    private val onOpenCustomThemeDialog: () -> Unit,
    private val onExportBackup: () -> Unit,
    private val onImportBackup: () -> Unit,
    private val onOpenTestLab: () -> Unit,
    private val onToggleSidebarDefault: (Boolean) -> Unit = {},
    private val onExitApp: () -> Unit,
) : JPanel(BorderLayout()) {

    private val detailCards = CardLayout()
    private val detailContainer = JPanel(detailCards)
    private val themeUpdaters = mutableListOf<() -> Unit>()

    private lateinit var themeBox: MihonDropdown<String>
    private var updatingThemeBox = false
    private val categoryButtons = mutableMapOf<String, AntiGravityCategoryButton>()
    private var activeCardId: String = "general"

    private data class IndexedSettingsSection(
        val cardId: String,
        val leadingViews: List<Component>,
        val card: SettingsGroupCard,
        val trailingSpacer: Component?,
    )

    private val indexedSections = mutableListOf<IndexedSettingsSection>()
    private lateinit var sidebarSearchField: MihonSearchField

    fun openCategory(cardId: String) {
        activeCardId = cardId
        categoryButtons[cardId]?.isSelected = true
        detailCards.show(detailContainer, cardId)
    }

    private fun indexPageForSearch(cardId: String, page: JPanel) {
        val comps = page.components
        // Skip first 4 components (h1, strut, subtitle, strut)
        val pendingHeader = mutableListOf<Component>()
        var i = 4
        while (i < comps.size) {
            val comp = comps[i]
            if (comp is SettingsGroupCard) {
                val trailing = comps.getOrNull(i + 1)?.takeIf { it !is SettingsGroupCard && it is javax.swing.Box.Filler }
                indexedSections.add(IndexedSettingsSection(cardId, pendingHeader.toList(), comp, trailing))
                pendingHeader.clear()
                if (trailing != null) i++
            } else {
                pendingHeader.add(comp)
            }
            i++
        }
    }

    private fun filterSettings(rawQuery: String) {
        val q = rawQuery.trim().lowercase()
        val matchCounts = mutableMapOf<String, Int>()
        for (section in indexedSections) {
            val count = section.card.applyFilter(q)
            val cardVisible = q.isBlank() || count > 0
            section.leadingViews.forEach { it.isVisible = cardVisible }
            section.trailingSpacer?.isVisible = cardVisible
            matchCounts[section.cardId] = (matchCounts[section.cardId] ?: 0) + count
        }
        categoryButtons.forEach { (id, btn) ->
            val count = matchCounts[id] ?: 0
            btn.isEnabled = q.isBlank() || count > 0
            btn.toolTipText = if (q.isNotBlank() && count > 0) "$count opção(ões) encontrada(s)" else null
        }
        if (q.isNotBlank() && (matchCounts[activeCardId] ?: 0) == 0) {
            val firstMatch = listOf("general", "appearance", "reader", "performance", "storage", "about")
                .firstOrNull { (matchCounts[it] ?: 0) > 0 }
            if (firstMatch != null) {
                openCategory(firstMatch)
            }
        }
        detailContainer.revalidate()
        detailContainer.repaint()
    }

    private val themeListener = { isDark: Boolean ->
        background = Theme.BG_APP
        detailContainer.background = Theme.BG_APP
        themeUpdaters.forEach { it.invoke() }
        if (::themeBox.isInitialized) {
            val idx = Theme.currentPreset.ordinal
            if (themeBox.selectedIndex != idx) {
                updatingThemeBox = true
                try {
                    themeBox.selectedIndex = idx
                } finally {
                    updatingThemeBox = false
                }
            }
        }
        repaint()
    }

    init {
        background = Theme.BG_APP
        detailContainer.background = Theme.BG_APP

        val leftNav = buildLeftSidebar()
        add(leftNav, BorderLayout.WEST)

        val pages = listOf(
            "general" to buildGeneralPage(),
            "appearance" to buildAppearancePage(),
            "reader" to buildReaderPage(),
            "translator" to buildTranslatorPage(),
            "shortcuts" to buildShortcutsPage(),
            "performance" to buildGraphicsPage(),
            "storage" to buildStoragePage(),
            "about" to buildAboutPage(),
        )
        pages.forEach { (id, pagePanel) ->
            indexPageForSearch(id, pagePanel)
            detailContainer.add(wrapScrollable(pagePanel), id)
        }

        add(detailContainer, BorderLayout.CENTER)

        ctx.addThemeListener(themeListener)
    }

    fun dispose() {
        ctx.removeThemeListener(themeListener)
    }

    private fun buildLeftSidebar(): JPanel {
        val navPanel = JPanel().apply {
            background = Theme.BG_SIDEBAR
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            preferredSize = Dimension(215, 0)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(18, 14, 18, 14),
            )
        }

        val backBtn = ModernButton(
            text = "Voltar",
            icon = ModernIcons.chevronLeft(14) { Theme.TEXT_PRIMARY },
            variant = ModernButton.Variant.GHOST,
        ).apply {
            alignmentX = 0f
            margin = Insets(4, 8, 4, 10)
            toolTipText = "Voltar para a aba Mais"
            addActionListener { onNavigateBack() }
        }
        navPanel.add(backBtn)
        navPanel.add(Box.createVerticalStrut(16))

        val settingsHeaderLbl = JLabel("Configurações").apply {
            font = Theme.FONT_UI.deriveFont(Font.BOLD, 11.5f)
            foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
            border = BorderFactory.createEmptyBorder(0, 12, 6, 0)
            alignmentX = 0f
        }
        navPanel.add(settingsHeaderLbl)

        sidebarSearchField = MihonSearchField(
            columns = 12,
            placeholder = "Filtrar opções…",
            compact = true,
            shortcutBadge = null,
            debounceMs = 90,
            onEscapeEmpty = { filterSettings("") },
        ).apply {
            alignmentX = 0f
            maximumSize = Dimension(Int.MAX_VALUE, 34)
            onDebouncedChange = { filterSettings(it) }
            onSubmit = { filterSettings(it) }
        }
        navPanel.add(sidebarSearchField)
        navPanel.add(Box.createVerticalStrut(10))

        val group = ButtonGroup()
        val categories = listOf(
            Triple("general", "Geral & Biblioteca", ModernIcons.settings(16) { Theme.TEXT_PRIMARY }),
            Triple("appearance", "Aparência", ModernIcons.palette(16) { Theme.TEXT_PRIMARY }),
            Triple("reader", "Leitor", ModernIcons.readerPages(16) { Theme.TEXT_PRIMARY }),
            Triple("translator", "Tradutor", ModernIcons.translateIcon(16)),
            Triple("shortcuts", "Atalhos", ModernIcons.edit(16) { Theme.TEXT_PRIMARY }),
            Triple("performance", "Desempenho", ModernIcons.statistics(16) { Theme.TEXT_PRIMARY }),
            Triple("storage", "Backup & Dados", ModernIcons.storage(16) { Theme.TEXT_PRIMARY }),
            Triple("about", "Sobre", ModernIcons.about(16) { Theme.TEXT_PRIMARY }),
        )

        categories.forEachIndexed { idx, (cardId, label, icon) ->
            val btn = AntiGravityCategoryButton(label, icon).apply {
                if (idx == 0) isSelected = true
                addActionListener {
                    openCategory(cardId)
                }
            }
            categoryButtons[cardId] = btn
            group.add(btn)
            navPanel.add(btn)
            navPanel.add(Box.createVerticalStrut(3))
        }

        navPanel.add(Box.createVerticalGlue())

        // Bottom user/version badge in AntiGravity style
        val footerPanel = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false
            alignmentX = 0f
            maximumSize = Dimension(Int.MAX_VALUE, 44)
            border = BorderFactory.createEmptyBorder(8, 8, 0, 8)
            val iconLbl = JLabel(AppIcon.getIcon(26))
            val textCol = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                val appTitle = JLabel("Mihon Linux").apply {
                    font = Theme.FONT_UI.deriveFont(Font.BOLD, 12f)
                    foreground = Theme.TEXT_PRIMARY
                }
                val appVer = JLabel("Edição Index • Desktop").apply {
                    font = Theme.FONT_UI.deriveFont(Font.PLAIN, 10.5f)
                    foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
                }
                add(appTitle)
                add(appVer)
                themeUpdaters.add {
                    appTitle.foreground = Theme.TEXT_PRIMARY
                    appVer.foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
                }
            }
            add(iconLbl, BorderLayout.WEST)
            add(textCol, BorderLayout.CENTER)
        }
        navPanel.add(footerPanel)

        themeUpdaters.add {
            navPanel.background = Theme.BG_SIDEBAR
            navPanel.border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(18, 14, 18, 14),
            )
            settingsHeaderLbl.foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
        }

        return navPanel
    }

    private fun wrapScrollable(page: JPanel): JScrollPane {
        val wrapper = JPanel(BorderLayout()).apply {
            background = Theme.BG_APP
            add(page, BorderLayout.NORTH)
        }
        return JScrollPane(wrapper).apply {
            border = null
            background = Theme.BG_APP
            viewport.background = Theme.BG_APP
            verticalScrollBar.unitIncrement = 24
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            themeUpdaters.add {
                wrapper.background = Theme.BG_APP
                background = Theme.BG_APP
                viewport.background = Theme.BG_APP
            }
        }
    }

    private fun createPageContainer(pageTitle: String, pageSubtitle: String): JPanel {
        val page = JPanel().apply {
            background = Theme.BG_APP
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(24, 24, 28, 24)
        }

        val h1 = JLabel(pageTitle).apply {
            font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 25f)
            foreground = Theme.TEXT_PRIMARY
            alignmentX = 0f
        }
        val sub = JLabel(pageSubtitle).apply {
            font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12.5f)
            foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
            alignmentX = 0f
        }

        page.add(h1)
        page.add(Box.createVerticalStrut(4))
        page.add(sub)
        page.add(Box.createVerticalStrut(20))

        themeUpdaters.add {
            page.background = Theme.BG_APP
            h1.foreground = Theme.TEXT_PRIMARY
            sub.foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
        }

        return page
    }

    private fun addSectionHeader(page: JPanel, title: String, description: String? = null) {
        val titleLbl = JLabel(title).apply {
            font = Theme.FONT_UI.deriveFont(Font.BOLD, 14f)
            foreground = Theme.TEXT_PRIMARY
            alignmentX = 0f
        }
        page.add(titleLbl)
        themeUpdaters.add { titleLbl.foreground = Theme.TEXT_PRIMARY }

        if (!description.isNullOrBlank()) {
            page.add(Box.createVerticalStrut(3))
            val descLbl = JLabel(description).apply {
                font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12f)
                foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
                alignmentX = 0f
            }
            page.add(descLbl)
            themeUpdaters.add { descLbl.foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY }
        }
        page.add(Box.createVerticalStrut(10))
    }

    private fun createSettingsRow(
        title: String,
        subtitle: String,
        monoDetail: String? = null,
        rightControl: JComponent,
    ): JPanel {
        val titleLbl = JLabel(title).apply {
            font = Theme.FONT_UI.deriveFont(Font.BOLD, 13f)
            foreground = Theme.TEXT_PRIMARY
            alignmentX = 0f
        }
        val subLbl = JLabel(subtitle).apply {
            font = Theme.FONT_UI.deriveFont(Font.PLAIN, 11.5f)
            foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
            alignmentX = 0f
        }
        val textCol = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(titleLbl)
            add(Box.createVerticalStrut(3))
            add(subLbl)
        }

        if (!monoDetail.isNullOrBlank()) {
            val monoLbl = JLabel(monoDetail).apply {
                font = Theme.FONT_MONO.deriveFont(Font.PLAIN, 11f)
                foreground = Theme.TEXT_SECONDARY
                alignmentX = 0f
            }
            textCol.add(Box.createVerticalStrut(5))
            textCol.add(monoLbl)
            themeUpdaters.add { monoLbl.foreground = Theme.TEXT_SECONDARY }
        }

        val rightWrap = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(rightControl)
        }

        return JPanel(BorderLayout(12, 0)).apply {
            isOpaque = false
            minimumSize = Dimension(0, 48)
            border = BorderFactory.createEmptyBorder(12, 14, 12, 14)
            putClientProperty(
                "settingsSearchText",
                listOfNotNull(title, subtitle, monoDetail).joinToString(" ").lowercase(),
            )
            add(textCol, BorderLayout.CENTER)
            add(rightWrap, BorderLayout.EAST)
            themeUpdaters.add {
                titleLbl.foreground = Theme.TEXT_PRIMARY
                subLbl.foreground = if (Theme.isDark) Theme.TEXT_MUTED else Theme.TEXT_SECONDARY
            }
        }
    }

    // =========================================================================
    // PAGE 1: GERAL
    // =========================================================================
    private fun buildGeneralPage(): JPanel {
        val page = createPageContainer(
            "Geral",
            "Configure privacidade, comportamento de execução, filtros globais e sincronização.",
        )

        addSectionHeader(
            page,
            "Privacidade & Histórico",
            "Controle como o aplicativo registra sua atividade de leitura local.",
        )
        val privacyCard = SettingsGroupCard()
        val incognitoSwitch = M3Switch(ctx.incognitoMode) { enabled ->
            ctx.incognitoMode = enabled
            ctx.setStatus(if (enabled) "Modo anônimo ativado (histórico pausado)" else "Modo anônimo desativado")
        }
        privacyCard.addRow(
            createSettingsRow(
                title = "Modo Anônimo",
                subtitle = "Pausa o histórico de leitura e impede gravação de progresso enquanto estiver ativo.",
                rightControl = incognitoSwitch,
            ),
        )
        val offlineSwitch = M3Switch(ctx.onlyOfflineFilter) { enabled ->
            ctx.setOfflineFilter(enabled)
            ctx.setStatus(if (enabled) "Filtro: Somente disponíveis offline ativado" else "Filtro offline desativado")
        }
        ctx.addOfflineFilterListener { offlineSwitch.isSelected = it }
        privacyCard.addRow(
            createSettingsRow(
                title = "Somente Disponíveis Offline",
                subtitle = "Filtra automaticamente a biblioteca para exibir apenas mangás com capítulos baixados.",
                rightControl = offlineSwitch,
            ),
        )
        page.add(privacyCard)
        page.add(Box.createVerticalStrut(24))

        addSectionHeader(
            page,
            "Execução & Rede",
            "Preferências de pré-carregamento em segundo plano durante a leitura online.",
        )
        val execCard = SettingsGroupCard()
        val autoPrefetchPill = MihonSegmentedSelector(
            options = listOf(false to "Sob Demanda", true to "Pré-baixar (2 Cap.)"),
            initialValue = readerPrefs.getBoolean("autoPrefetch", true),
        ) { enabled ->
            readerPrefs.putBoolean("autoPrefetch", enabled)
            ctx.setStatus(if (enabled) "Pré-download automático ativado" else "Leitura sob demanda ativada")
        }
        execCard.addRow(
            createSettingsRow(
                title = "Política de Cache de Capítulos",
                subtitle = "Define se os próximos 2 capítulos devem ser baixados automaticamente enquanto você lê.",
                rightControl = autoPrefetchPill,
            ),
        )
        val traySwitch = M3Switch(SystemTrayManager.minimizeToTray) { enabled ->
            SystemTrayManager.minimizeToTray = enabled
            ctx.setStatus(if (enabled) "Minimizar para a bandeja ativado" else "Minimizar para a bandeja desativado")
        }
        execCard.addRow(
            createSettingsRow(
                title = "Minimizar para a Bandeja do Sistema",
                subtitle = "Mantém o Mihon Linux em segundo plano na barra do sistema ao fechar a janela principal.",
                rightControl = traySwitch,
            ),
        )
        val notificationsSwitch = M3Switch(SystemTrayManager.desktopNotificationsEnabled) { enabled ->
            SystemTrayManager.desktopNotificationsEnabled = enabled
            ctx.setStatus(if (enabled) "Notificações de desktop ativadas" else "Notificações de desktop desativadas")
        }
        execCard.addRow(
            createSettingsRow(
                title = "Notificações de Desktop",
                subtitle = "Exibe notificações nativas do sistema (notify-send) para novos capítulos, downloads e erros.",
                rightControl = notificationsSwitch,
            ),
        )
        val intervalCombo = MihonDropdown(
            LibraryUpdateScheduler.INTERVAL_OPTIONS.map { it.second }.toTypedArray(),
        ).apply {
            val cur = LibraryUpdateScheduler.intervalHours
            selectedIndex = LibraryUpdateScheduler.INTERVAL_OPTIONS.indexOfFirst { it.first == cur }.coerceAtLeast(0)
            preferredSize = Dimension(210, Theme.CONTROL_HEIGHT_COMPACT)
            addActionListener {
                val chosen = LibraryUpdateScheduler.INTERVAL_OPTIONS.getOrNull(selectedIndex) ?: return@addActionListener
                LibraryUpdateScheduler.intervalHours = chosen.first
                ctx.setStatus("Frequência de atualização: ${chosen.second}")
            }
        }
        execCard.addRow(
            createSettingsRow(
                title = "Verificação Periódica de Novos Capítulos",
                subtitle = "Busca automaticamente novos lançamentos da biblioteca e envia notificações nativas.",
                rightControl = intervalCombo,
            ),
        )
        page.add(execCard)
        page.add(Box.createVerticalStrut(24))

        addSectionHeader(
            page,
            "Sincronização & Rastreamento (AniList / MyAnimeList)",
            "Sincronize automaticamente seu progresso de capítulos lidos com sua conta online.",
        )
        val trackerCard = SettingsGroupCard()
        val autoSyncSwitch = M3Switch(TrackerService.autoSyncOnRead) { enabled ->
            TrackerService.autoSyncOnRead = enabled
            ctx.setStatus(if (enabled) "Sincronização automática de capítulos ativada" else "Sincronização automática pausada")
        }
        trackerCard.addRow(
            createSettingsRow(
                title = "Sincronizar Progresso ao Terminar Capítulo",
                subtitle = "Atualiza automaticamente o último capítulo lido no AniList / MyAnimeList ao concluir a leitura.",
                rightControl = autoSyncSwitch,
            ),
        )
        val btnConfigTokens = ModernButton(
            text = "Configurar Tokens de API…",
            variant = ModernButton.Variant.SURFACE,
        ).apply {
            addActionListener {
                val owner = SwingUtilities.getWindowAncestor(this@AntiGravitySettingsScreen)
                val dlg = JDialog(owner, "Credenciais de Rastreamento", java.awt.Dialog.ModalityType.APPLICATION_MODAL).apply {
                    setSize(470, 290)
                    isResizable = false
                    setLocationRelativeTo(this@AntiGravitySettingsScreen)
                }
                val root = JPanel(BorderLayout(0, 14)).apply {
                    background = Theme.BG_SURFACE
                    border = BorderFactory.createEmptyBorder(20, 24, 18, 24)
                }
                val aniField = MihonPasswordField(
                    initialValue = TrackerService.anilistToken,
                    columns = 28,
                    placeholder = "Cole o Bearer Token OAuth do AniList…",
                )
                val malField = MihonPasswordField(
                    initialValue = TrackerService.malToken,
                    columns = 28,
                    placeholder = "Cole o Bearer Token API v2 do MyAnimeList…",
                )
                val form = JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(JLabel("Credenciais de Rastreamento (AniList & MyAnimeList)").apply {
                        font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 16f)
                        foreground = Theme.TEXT_PRIMARY
                        alignmentX = 0f
                    })
                    add(Box.createVerticalStrut(12))
                    add(JLabel("Token de Acesso AniList (Bearer Token OAuth):").apply {
                        font = Theme.FONT_UI.deriveFont(Font.BOLD, 12f)
                        foreground = Theme.TEXT_SECONDARY
                        alignmentX = 0f
                    })
                    add(Box.createVerticalStrut(4))
                    aniField.alignmentX = 0f
                    add(aniField)
                    add(Box.createVerticalStrut(12))
                    add(JLabel("Token de Acesso MyAnimeList (Bearer Token API v2):").apply {
                        font = Theme.FONT_UI.deriveFont(Font.BOLD, 12f)
                        foreground = Theme.TEXT_SECONDARY
                        alignmentX = 0f
                    })
                    add(Box.createVerticalStrut(4))
                    malField.alignmentX = 0f
                    add(malField)
                }
                val btnCancel = ModernButton(text = "Cancelar", variant = ModernButton.Variant.GHOST).apply {
                    addActionListener { dlg.dispose() }
                }
                val btnSave = ModernButton(text = "Salvar Tokens", variant = ModernButton.Variant.PRIMARY).apply {
                    addActionListener {
                        TrackerService.anilistToken = aniField.plainText.trim()
                        TrackerService.malToken = malField.plainText.trim()
                        ctx.setStatus("Tokens de rastreamento salvos com sucesso!")
                        dlg.dispose()
                    }
                }
                val bar = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                    isOpaque = false
                    add(btnCancel)
                    add(btnSave)
                }
                root.add(form, BorderLayout.CENTER)
                root.add(bar, BorderLayout.SOUTH)
                dlg.rootPane.defaultButton = btnSave
                dlg.contentPane = root
                dlg.isVisible = true
            }
        }
        trackerCard.addRow(
            createSettingsRow(
                title = "Contas Conectadas (AniList / MyAnimeList)",
                subtitle = "Configure seus tokens pessoais para sincronização bidirecional na nuvem.",
                rightControl = btnConfigTokens,
            ),
        )
        
        page.add(trackerCard)

        return page
    }

    // =========================================================================
    // PAGE 2: APARÊNCIA
    // =========================================================================
    private fun buildAppearancePage(): JPanel {
        val page = createPageContainer(
            "Aparência",
            "Personalize o tema de cores, contraste de superfície e layout da grade.",
        )

        addSectionHeader(
            page,
            "Tema da Interface",
            "Selecione uma paleta predefinida ou crie seu próprio esquema de cores.",
        )
        val themeCard = SettingsGroupCard()

        themeBox = MihonDropdown(Theme.ThemePreset.entries.map { it.displayName }.toTypedArray()).apply {
            val savedPreset = runCatching { Theme.ThemePreset.valueOf(prefs.get("themePresetIndex", Theme.currentPreset.name)) }
                .getOrDefault(Theme.currentPreset)
            selectedIndex = savedPreset.ordinal
            preferredSize = Dimension(245, Theme.CONTROL_HEIGHT_COMPACT)
            val baseRenderer = javax.swing.DefaultListCellRenderer()
            renderer = javax.swing.ListCellRenderer { list, value, index, isSelected, cellHasFocus ->
                val lbl = baseRenderer.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                val preset = Theme.ThemePreset.entries.firstOrNull { it.displayName == value }
                if (preset != null) {
                    lbl.iconTextGap = 10
                    lbl.icon = object : Icon {
                        override fun getIconWidth(): Int = 20
                        override fun getIconHeight(): Int = 14
                        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                            val g2 = g.create() as Graphics2D
                            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                            g2.color = preset.bgApp
                            g2.fillRoundRect(x, y, 20, 14, 4, 4)
                            g2.color = preset.bgSidebar
                            g2.fillRoundRect(x, y, 6, 14, 4, 4)
                            g2.color = if (preset == Theme.ThemePreset.CUSTOM) Theme.PRIMARY else Theme.PRIMARY
                            g2.fillOval(x + 11, y + 4, 6, 6)
                            g2.color = Theme.BORDER_SUBTLE
                            g2.drawRoundRect(x, y, 19, 13, 4, 4)
                            g2.dispose()
                        }
                    }
                }
                lbl
            }
            addActionListener {
                if (updatingThemeBox) return@addActionListener
                val selected = Theme.ThemePreset.entries.getOrNull(selectedIndex) ?: return@addActionListener
                if (selected == Theme.ThemePreset.CUSTOM) {
                    onOpenCustomThemeDialog()
                } else {
                    onApplyTheme(selected)
                }
            }
        }
        themeCard.addRow(
            createSettingsRow(
                title = "Predefinição de Tema",
                subtitle = "Altera instantaneamente as cores de fundo, cartões e destaques.",
                rightControl = themeBox,
            ),
        )
        page.add(themeCard)
        page.add(Box.createVerticalStrut(24))

        addSectionHeader(
            page,
            "Movimento & Interação",
            "Personalize a intensidade das animações e o estilo do menu contextual dos cartões.",
        )
        val motionCard = SettingsGroupCard()
        val motionSelector = MihonSegmentedSelector(
            options = listOf(
                MotionLevel.COMPLETE to "Completa",
                MotionLevel.REDUCED to "Reduzida",
                MotionLevel.OFF to "Desligada",
            ),
            initialValue = MotionPreferences.current,
        ) { level ->
            MotionPreferences.setLevel(level)
            ctx.setStatus("Animações: ${level.label}")
        }
        motionCard.addRow(
            createSettingsRow(
                title = "Intensidade das Animações",
                subtitle = "Reduza ou desative transições e molas de movimento mantendo clareza visual.",
                rightControl = motionSelector,
            ),
        )
        val menuStyleSelector = MihonSegmentedSelector(
            options = listOf(
                GooeyContextMenu.MenuStyle.RADIAL to "Radial Animado",
                GooeyContextMenu.MenuStyle.CLASSIC to "Menu Clássico",
            ),
            initialValue = GooeyContextMenu.preferredStyle,
        ) { style ->
            GooeyContextMenu.preferredStyle = style
            ctx.setStatus("Menu contextual: ${style.label}")
        }
        motionCard.addRow(
            createSettingsRow(
                title = "Estilo do Menu de Ações (Clique Direito)",
                subtitle = "Escolha entre o menu radial elástico ou o menu popup compacto clássico.",
                rightControl = menuStyleSelector,
            ),
        )
        page.add(motionCard)
        page.add(Box.createVerticalStrut(24))

        // Language selector
        addSectionHeader(
            page,
            "Idioma",
            "Selecione o idioma da interface. Requer reinício do aplicativo para ter efeito.",
        )
        val langCard = SettingsGroupCard()
        val availableLocales = MR.getAvailableLocales()
        val localeNames = availableLocales.map { locale ->
            locale to MR.getLocaleDisplayName(locale)
        }
        val currentLocaleTag = prefs.get("app_language", "system")
        val currentLocale = if (currentLocaleTag == "system") {
            Locale.getDefault()
        } else {
            Locale.forLanguageTag(currentLocaleTag.replace('_', '-'))
        }
        val currentDisplayName = MR.getLocaleDisplayName(currentLocale)
        
        val langCombo = MihonDropdown(localeNames.map { it.second }.toTypedArray()).apply {
            selectedItem = currentDisplayName
            preferredSize = Dimension(245, Theme.CONTROL_HEIGHT_COMPACT)
            addActionListener {
                val selectedName = selectedItem as String
                val selectedLocale = localeNames.first { it.second == selectedName }.first
                val localeTag = if (selectedLocale == Locale.getDefault()) "system" else selectedLocale.toLanguageTag()
                prefs.put("app_language", localeTag)
                MR.locale = selectedLocale
                ctx.setStatus("Idioma alterado para ${selectedName}. ${MR["requires_app_restart"]}")
            }
        }
        langCard.addRow(
            createSettingsRow(
                title = "Idioma do Aplicativo",
                subtitle = "Selecione o idioma da interface. Requer reinício para aplicar completamente.",
                rightControl = langCombo,
            ),
        )
        page.add(langCard)

        return page
    }

    // =========================================================================
    // PAGE 3: LEITOR
    // =========================================================================
    private fun buildReaderPage(): JPanel {
        val page = createPageContainer(
            "Leitor",
            "Configure o modo de paginação, enquadramento de página e divisão inteligente.",
        )

        addSectionHeader(
            page,
            "Comportamento de Paginação",
            "Escolha como os capítulos são apresentados ao abrir a janela do leitor.",
        )
        val readerCard = SettingsGroupCard()

        val savedMode = runCatching { ReadMode.valueOf(readerPrefs.get("mode", "SINGLE")) }.getOrDefault(ReadMode.SINGLE)
        val modeSelector = MihonSegmentedSelector(
            options = listOf(
                ReadMode.SINGLE to "Página Única",
                ReadMode.DOUBLE_RTL to "Dupla (Mangá)",
                ReadMode.WEBTOON to "Webtoon",
            ),
            initialValue = savedMode,
        ) { chosen ->
            readerPrefs.put("mode", chosen.name)
            ctx.setStatus("Modo de leitura padrão: ${chosen.label}")
        }
        readerCard.addRow(
            createSettingsRow(
                title = "Modo de Leitura Padrão",
                subtitle = "Define o fluxo de navegação para mangás tradicionais ou manhwas verticais.",
                rightControl = modeSelector,
            ),
        )

        val savedFit = runCatching { FitMode.valueOf(readerPrefs.get("fit", "WINDOW")) }.getOrDefault(FitMode.WINDOW)
        val fitSelector = MihonSegmentedSelector(
            options = listOf(
                FitMode.WINDOW to "Ajustar Janela",
                FitMode.WIDTH to "Ajustar Largura",
                FitMode.ORIGINAL to "Tamanho Real",
            ),
            initialValue = savedFit,
        ) { chosen ->
            readerPrefs.put("fit", chosen.name)
            ctx.setStatus("Ajuste de página padrão: ${chosen.label}")
        }
        readerCard.addRow(
            createSettingsRow(
                title = "Enquadramento de Página",
                subtitle = "Controla como as imagens são dimensionadas dentro da área visível do leitor.",
                rightControl = fitSelector,
            ),
        )

        val savedBg = readerPrefs.get("readerBackground", "BLACK")
        val bgSelector = MihonSegmentedSelector(
            options = listOf(
                "BLACK" to "Preto AMOLED",
                "DARK_GRAPHITE" to "Grafite",
                "LIGHT_PAPER" to "Papel Claro",
            ),
            initialValue = savedBg,
        ) { chosenBg ->
            readerPrefs.put("readerBackground", chosenBg)
            ctx.setStatus("Plano de fundo do leitor atualizado")
        }
        readerCard.addRow(
            createSettingsRow(
                title = "Plano de Fundo das Páginas",
                subtitle = "Define a cor da moldura ao redor das páginas abertas no leitor.",
                rightControl = bgSelector,
            ),
        )
        page.add(readerCard)
        page.add(Box.createVerticalStrut(24))

        addSectionHeader(
            page,
            "Páginas Duplas (Spreads)",
            "Tratamento automático para páginas horizontais largas no modo de página única.",
        )
        val spreadCard = SettingsGroupCard()
        val splitSwitch = M3Switch(readerPrefs.getBoolean("splitWidePages", true)) { enabled ->
            readerPrefs.putBoolean("splitWidePages", enabled)
        }
        spreadCard.addRow(
            createSettingsRow(
                title = "Dividir Páginas Duplas Automaticamente",
                subtitle = "Divide imagens panorâmicas em duas metades sequenciais para leitura confortável.",
                rightControl = splitSwitch,
            ),
        )
        val rtlPill = MihonSegmentedSelector(
            options = listOf(true to "Direita → Esquerda (Mangá)", false to "Esquerda → Direita"),
            initialValue = readerPrefs.getBoolean("splitWideRtl", true),
        ) { rtl ->
            readerPrefs.putBoolean("splitWideRtl", rtl)
        }
        spreadCard.addRow(
            createSettingsRow(
                title = "Ordem de Leitura da Divisão",
                subtitle = "Define qual metade da página dupla é exibida primeiro.",
                rightControl = rtlPill,
            ),
        )
        page.add(spreadCard)
        return page
    }

    // =========================================================================
    // PAGE 3B: TRADUTOR SIMULTÂNEO (GOOGLE LENS)
    // =========================================================================
    private fun buildTranslatorPage(): JPanel {
        val page = createPageContainer(
            "Tradutor",
            "Detecção automática de texto (OCR), idiomas e provedor de tradução sobreposta no leitor.",
        )

        addSectionHeader(
            page,
            "Tradutor Simultâneo (Google Lens)",
            "Configure como os balões de fala são reconhecidos e traduzidos em tempo real.",
        )
        val translatorCard = SettingsGroupCard()
        val initialTranslatorCfg = TranslatorPreferences.load()

        val translatorDefaultSwitch = M3Switch(initialTranslatorCfg.enabledByDefault) { enabled ->
            val updated = TranslatorPreferences.load().copy(enabledByDefault = enabled)
            TranslatorPreferences.save(updated)
            ctx.setStatus(
                if (enabled) "Tradutor simultâneo ativado por padrão" else "Tradutor simultâneo desativado por padrão",
            )
        }.apply {
            name = "translatorEnabledByDefaultSwitch"
        }
        translatorCard.addRow(
            createSettingsRow(
                title = "Ativar tradutor por padrão",
                subtitle = "Inicia automaticamente o modo Google Lens ao abrir capítulos no leitor.",
                rightControl = translatorDefaultSwitch,
            ),
        )

        val sourceLangs = TranslatorLanguage.sourceLanguages().toTypedArray()
        val sourceLangCombo = object : MihonDropdown<TranslatorLanguage>(sourceLangs) {
            override fun setSelectedItem(anObject: Any?) {
                val resolved = when (anObject) {
                    is TranslatorLanguage -> anObject
                    is String -> sourceLangs.firstOrNull {
                        it.displayName.equals(anObject, ignoreCase = true) ||
                            it.name.equals(anObject, ignoreCase = true) ||
                            it.isoCode.equals(anObject, ignoreCase = true)
                    }
                    else -> null
                } ?: anObject
                super.setSelectedItem(resolved)
            }
        }.apply {
            name = "translatorSourceLangCombo"
            preferredSize = Dimension(210, Theme.CONTROL_HEIGHT_COMPACT)
            renderer = javax.swing.DefaultListCellRenderer().let { base ->
                javax.swing.ListCellRenderer { list, value, index, isSelected, cellHasFocus ->
                    val comp = base.getListCellRendererComponent(
                        list,
                        value?.displayName ?: "",
                        index,
                        isSelected,
                        cellHasFocus,
                    )
                    if (comp is JLabel && index >= 0) {
                        comp.border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
                        comp.background = if (isSelected) Theme.ACCENT_SOFT else Theme.BG_SURFACE
                        comp.foreground = Theme.TEXT_PRIMARY
                    }
                    comp
                }
            }
            selectedItem = initialTranslatorCfg.sourceLanguage
            addActionListener {
                val chosen = selectedItem as? TranslatorLanguage ?: return@addActionListener
                val updated = TranslatorPreferences.load().copy(sourceLanguage = chosen)
                TranslatorPreferences.save(updated)
                ctx.setStatus("Idioma de origem do tradutor: ${chosen.displayName}")
            }
        }
        translatorCard.addRow(
            createSettingsRow(
                title = "Idioma de Origem",
                subtitle = "Idioma do texto original nas páginas do mangá para reconhecimento OCR.",
                rightControl = sourceLangCombo,
            ),
        )

        val targetLangs = TranslatorLanguage.targetLanguages().toTypedArray()
        val targetLangCombo = object : MihonDropdown<TranslatorLanguage>(targetLangs) {
            override fun setSelectedItem(anObject: Any?) {
                val resolved = when (anObject) {
                    is TranslatorLanguage -> if (anObject == TranslatorLanguage.AUTO) TranslatorLanguage.PORTUGUESE_BR else anObject
                    is String -> targetLangs.firstOrNull {
                        it.displayName.equals(anObject, ignoreCase = true) ||
                            it.name.equals(anObject, ignoreCase = true) ||
                            it.isoCode.equals(anObject, ignoreCase = true)
                    }
                    else -> null
                } ?: anObject
                super.setSelectedItem(resolved)
            }
        }.apply {
            name = "translatorTargetLangCombo"
            preferredSize = Dimension(210, Theme.CONTROL_HEIGHT_COMPACT)
            renderer = javax.swing.DefaultListCellRenderer().let { base ->
                javax.swing.ListCellRenderer { list, value, index, isSelected, cellHasFocus ->
                    val comp = base.getListCellRendererComponent(
                        list,
                        value?.displayName ?: "",
                        index,
                        isSelected,
                        cellHasFocus,
                    )
                    if (comp is JLabel && index >= 0) {
                        comp.border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
                        comp.background = if (isSelected) Theme.ACCENT_SOFT else Theme.BG_SURFACE
                        comp.foreground = Theme.TEXT_PRIMARY
                    }
                    comp
                }
            }
            selectedItem = initialTranslatorCfg.targetLanguage
            addActionListener {
                val chosen = selectedItem as? TranslatorLanguage ?: return@addActionListener
                val updated = TranslatorPreferences.load().copy(targetLanguage = chosen)
                TranslatorPreferences.save(updated)
                ctx.setStatus("Idioma de destino do tradutor: ${chosen.displayName}")
            }
        }
        translatorCard.addRow(
            createSettingsRow(
                title = "Idioma de Destino",
                subtitle = "Idioma para o qual os balões de fala serão traduzidos e sobrepostos.",
                rightControl = targetLangCombo,
            ),
        )

        val providerDescriptors = TranslationProviderRegistry.availableProviders
        val providerIds = providerDescriptors.map { it.id }.toTypedArray()
        val providerCombo = object : MihonDropdown<String>(providerIds) {
            override fun setSelectedItem(anObject: Any?) {
                val resolved = when (anObject) {
                    is mihon.desktop.translator.TranslationProviderDescriptor -> anObject.id
                    is String -> {
                        val trimmed = anObject.trim()
                        providerDescriptors.firstOrNull {
                            it.id.equals(trimmed, ignoreCase = true) ||
                                it.displayName.equals(trimmed, ignoreCase = true)
                        }?.id ?: trimmed
                    }
                    else -> anObject
                }
                super.setSelectedItem(resolved)
            }
        }.apply {
            name = "translatorProviderCombo"
            preferredSize = Dimension(225, Theme.CONTROL_HEIGHT_COMPACT)
            renderer = javax.swing.DefaultListCellRenderer().let { base ->
                javax.swing.ListCellRenderer { list, value, index, isSelected, cellHasFocus ->
                    val label = providerDescriptors.firstOrNull { it.id == value }?.displayName ?: (value ?: "")
                    val comp = base.getListCellRendererComponent(list, label, index, isSelected, cellHasFocus)
                    if (comp is JLabel && index >= 0) {
                        comp.border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
                        comp.background = if (isSelected) Theme.ACCENT_SOFT else Theme.BG_SURFACE
                        comp.foreground = Theme.TEXT_PRIMARY
                    }
                    comp
                }
            }
            selectedItem = initialTranslatorCfg.providerId
            addActionListener {
                val chosenId = (selectedItem as? String)?.trim().orEmpty()
                if (chosenId.isNotEmpty()) {
                    val updated = TranslatorPreferences.load().copy(providerId = chosenId)
                    TranslatorPreferences.save(updated)
                    val display = providerDescriptors.firstOrNull { it.id == updated.providerId }?.displayName ?: updated.providerId
                    ctx.setStatus("Provedor de tradução: $display")
                }
            }
        }
        translatorCard.addRow(
            createSettingsRow(
                title = "Provedor de Tradução",
                subtitle = "Serviço utilizado para traduzir o texto extraído das páginas.",
                rightControl = providerCombo,
            ),
        )

        val autoDownloadSwitch = M3Switch(initialTranslatorCfg.autoDownloadTessdata) { enabled ->
            val updated = TranslatorPreferences.load().copy(autoDownloadTessdata = enabled)
            TranslatorPreferences.save(updated)
            ctx.setStatus(
                if (enabled) "Download automático de modelos OCR ativado" else "Download automático de modelos OCR desativado",
            )
        }.apply {
            name = "translatorAutoDownloadSwitch"
        }
        translatorCard.addRow(
            createSettingsRow(
                title = "Baixar Modelos OCR Automaticamente",
                subtitle = "Faz o download sob demanda dos pacotes de idioma Tesseract (tessdata_fast) em ~/.mihon/tessdata.",
                rightControl = autoDownloadSwitch,
            ),
        )

        val libreUrlField = MihonTextField(
            columns = 18,
            placeholder = "https://libretranslate.com/translate",
            compact = true,
            leadingIconProvider = { ModernIcons.link(14) { Theme.TEXT_MUTED } },
            showClearButton = true,
        ).apply {
            text = initialTranslatorCfg.libreTranslateUrl
            name = "libreTranslateUrlField"
            preferredSize = Dimension(250, 34)
            var urlSaveTimer: Timer? = null
            fun persistUrlNow() {
                urlSaveTimer?.stop()
                val raw = text?.trim().orEmpty()
                validationError = if (raw.isNotEmpty() && !raw.startsWith("http://") && !raw.startsWith("https://")) {
                    "URL inválida (use http:// ou https://)"
                } else {
                    null
                }
                val current = TranslatorPreferences.load()
                TranslatorPreferences.save(current.copy(libreTranslateUrl = raw))
            }
            addActionListener { persistUrlNow() }
            addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent?) = persistUrlNow()
            })
            document.addDocumentListener(object : DocumentListener {
                fun onDocEdit() {
                    val raw = text?.trim().orEmpty()
                    if (raw.isEmpty()) {
                        persistUrlNow()
                    } else {
                        validationError = if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
                            "URL inválida (use http:// ou https://)"
                        } else {
                            null
                        }
                        urlSaveTimer?.stop()
                        urlSaveTimer = Timer(350) { persistUrlNow() }.apply {
                            isRepeats = false
                            start()
                        }
                    }
                }
                override fun insertUpdate(e: DocumentEvent?) = onDocEdit()
                override fun removeUpdate(e: DocumentEvent?) = onDocEdit()
                override fun changedUpdate(e: DocumentEvent?) = onDocEdit()
            })
        }
        val libreUrlRow = createSettingsRow(
            title = "Endpoint LibreTranslate",
            subtitle = "URL do servidor LibreTranslate (usado apenas quando LibreTranslate está selecionado).",
            rightControl = libreUrlField,
        )
        translatorCard.addRow(libreUrlRow)

        val libreKeyField = MihonPasswordField(
            initialValue = initialTranslatorCfg.libreTranslateApiKey,
            columns = 16,
            placeholder = "Opcional (chave de API)",
            compact = true,
        ).apply {
            name = "libreTranslateApiKeyField"
            preferredSize = Dimension(250, 34)
            var keySaveTimer: Timer? = null
            fun persistKeyNow() {
                keySaveTimer?.stop()
                val raw = plainText
                val current = TranslatorPreferences.load()
                TranslatorPreferences.save(current.copy(libreTranslateApiKey = raw))
            }
            addActionListener { persistKeyNow() }
            addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent?) = persistKeyNow()
            })
            document.addDocumentListener(object : DocumentListener {
                fun onDocEdit() {
                    val raw = plainText
                    if (raw.isEmpty()) {
                        persistKeyNow()
                    } else {
                        keySaveTimer?.stop()
                        keySaveTimer = Timer(350) { persistKeyNow() }.apply {
                            isRepeats = false
                            start()
                        }
                    }
                }
                override fun insertUpdate(e: DocumentEvent?) = onDocEdit()
                override fun removeUpdate(e: DocumentEvent?) = onDocEdit()
                override fun changedUpdate(e: DocumentEvent?) = onDocEdit()
            })
        }
        val libreKeyRow = createSettingsRow(
            title = "Chave de API LibreTranslate",
            subtitle = "Chave de autenticação opcional para instâncias protegidas do LibreTranslate.",
            rightControl = libreKeyField,
        )
        translatorCard.addRow(libreKeyRow)

        fun syncLibreRowsVisibility(providerId: String) {
            val isLibre = providerId.equals("libretranslate", ignoreCase = true)
            libreUrlRow.isVisible = isLibre
            libreKeyRow.isVisible = isLibre
            translatorCard.revalidate()
            translatorCard.repaint()
        }
        syncLibreRowsVisibility(initialTranslatorCfg.providerId)
        providerCombo.addActionListener {
            val chosenId = (providerCombo.selectedItem as? String)?.trim().orEmpty()
            if (chosenId.isNotEmpty()) syncLibreRowsVisibility(chosenId)
        }

        page.add(translatorCard)
        return page
    }

    // =========================================================================
    // PAGE 3C: ATALHOS DE TECLADO
    // =========================================================================
    private fun buildShortcutsPage(): JPanel {
        val page = createPageContainer(
            "Atalhos",
            "Personalize todas as teclas e combinações rápidas usadas durante a leitura.",
        )

        addSectionHeader(
            page,
            "Atalhos de Teclado Personalizáveis",
            "Clique sobre qualquer atalho para definir uma nova tecla ou combinação (ex.: Ctrl+T).",
        )
        val shortcutsCard = SettingsGroupCard()

        fun showKeyBindingDialog(action: ReaderAction, onUpdated: () -> Unit) {
            val currentKeys = ReaderAction.getDisplayString(action)
            val parentWindow = SwingUtilities.getWindowAncestor(this)
            val dialog = if (parentWindow is java.awt.Frame) {
                JDialog(parentWindow, "Definir Atalho: ${action.title}", true)
            } else if (parentWindow is java.awt.Dialog) {
                JDialog(parentWindow, "Definir Atalho: ${action.title}", true)
            } else {
                JDialog()
            }
            dialog.title = "Definir Atalho: ${action.title}"
            dialog.isModal = true
            dialog.setSize(450, 230)
            dialog.setLocationRelativeTo(this)

            val p = JPanel(BorderLayout(16, 16)).apply {
                background = Theme.BG_SURFACE
                border = BorderFactory.createEmptyBorder(20, 24, 20, 24)
            }
            val lblPrompt = JLabel("Pressione a tecla desejada para '${action.title}'...").apply {
                font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 14f)
                foreground = Theme.TEXT_PRIMARY
            }
            val lblCurrent = JLabel("Atalho atual: $currentKeys").apply {
                font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12.5f)
                foreground = Theme.TEXT_MUTED
            }
            val lblConflict = JLabel(" ").apply {
                font = Theme.FONT_UI.deriveFont(Font.BOLD, 11.5f)
                foreground = Theme.WARNING
            }
            val center = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(lblPrompt)
                add(Box.createVerticalStrut(6))
                add(lblCurrent)
                add(Box.createVerticalStrut(8))
                add(lblConflict)
            }
            p.add(center, BorderLayout.CENTER)

            val btnReset = ModernButton(
                text = "Restaurar Padrão",
                icon = ModernIcons.sync(13) { Theme.TEXT_PRIMARY },
                variant = ModernButton.Variant.SURFACE,
            ).apply {
                isFocusable = false
                addActionListener {
                    ReaderAction.setKeyCodes(action, action.defaultKeyCodes)
                    onUpdated()
                    dialog.dispose()
                }
            }
            val btnCancel = ModernButton(
                text = "Cancelar",
                variant = ModernButton.Variant.GHOST,
            ).apply {
                isFocusable = false
                addActionListener { dialog.dispose() }
            }
            val btnBar = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                isOpaque = false
                add(btnReset)
                add(btnCancel)
            }
            p.add(btnBar, BorderLayout.SOUTH)

            var pendingConflictCombo: KeyCombo? = null
            dialog.addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ESCAPE) {
                        dialog.dispose()
                        return
                    }
                    // Ignore bare modifier presses until an actual key is pressed
                    if (e.keyCode in setOf(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_ALT, KeyEvent.VK_META, KeyEvent.VK_ALT_GRAPH)) {
                        return
                    }
                    val combo = KeyCombo(e.keyCode, e.modifiersEx).let { KeyCombo(it.keyCode, it.normalizedModifiers()) }
                    val conflicting = ReaderAction.findConflict(action, combo.keyCode, combo.modifiers)
                    if (conflicting != null && pendingConflictCombo != combo) {
                        pendingConflictCombo = combo
                        lblConflict.text = "O atalho '${combo.toDisplayText()}' já está em '${conflicting.title}'. Pressione novamente para substituir."
                        return
                    }
                    if (conflicting != null && pendingConflictCombo == combo) {
                        val cleaned = ReaderAction.getKeyCombos(conflicting).filter {
                            !(it.keyCode == combo.keyCode && it.normalizedModifiers() == combo.modifiers)
                        }
                        ReaderAction.setKeyCombos(conflicting, cleaned)
                    }
                    ReaderAction.setKeyCombos(action, listOf(combo))
                    onUpdated()
                    dialog.dispose()
                }
            })
            dialog.isFocusable = true
            dialog.add(p)
            dialog.isVisible = true
        }

        ReaderAction.entries.forEach { action ->
            val btnKey = ModernButton(
                text = ReaderAction.getDisplayString(action),
                variant = ModernButton.Variant.SURFACE,
            ).apply {
                font = Theme.FONT_MONO.deriveFont(Font.BOLD, 12f)
                addActionListener {
                    showKeyBindingDialog(action) {
                        text = ReaderAction.getDisplayString(action)
                    }
                }
            }
            themeUpdaters.add {
                btnKey.repaint()
            }
            shortcutsCard.addRow(
                createSettingsRow(
                    title = action.title,
                    subtitle = action.description,
                    rightControl = btnKey,
                ),
            )
        }
        page.add(shortcutsCard)

        return page
    }

    // =========================================================================
    // PAGE 4: DESEMPENHO & GRÁFICOS
    // =========================================================================
    private fun buildGraphicsPage(): JPanel {
        val page = createPageContainer(
            "Desempenho",
            "Ajustes de consumo de memória RAM, renderização e aprimoramento de arquivos.",
        )

        addSectionHeader(
            page,
            "Gerenciamento de Memória RAM",
            "Defina o teto máximo de consumo de memória RAM alocado para o aplicativo e leitor de capítulos.",
        )
        val ramCard = SettingsGroupCard()

        val ramLimitOptions = listOf(
            256 to "256 MB (Ultra Econômico)",
            384 to "384 MB (Econômico)",
            500 to "500 MB (Padrão Recomendado)",
            768 to "768 MB (Alto Desempenho)",
            1024 to "1024 MB (Sem Restrições)",
        )
        val currentLimit = MemoryManager.ramLimitMb
        val ramLimitBox = MihonDropdown(ramLimitOptions.map { it.second }.toTypedArray()).apply {
            val selectedIdx = ramLimitOptions.indexOfFirst { it.first == currentLimit }.let { if (it >= 0) it else 2 } // 2 is 500MB
            selectedIndex = selectedIdx
            preferredSize = Dimension(280, Theme.CONTROL_HEIGHT_COMPACT)
        }

        fun formatMemoryStatus(limit: Int = MemoryManager.ramLimitMb): String =
            "Heap: ${MemoryManager.usedMemoryMb} MB • RAM: ${MemoryManager.processRssMb} MB / $limit MB"

        val memStatusLbl = JLabel(formatMemoryStatus()).apply {
            font = Theme.FONT_UI.deriveFont(Font.BOLD, 12f)
            foreground = if (Theme.isDark) Theme.ACCENT else Theme.PRIMARY
        }

        ramLimitBox.addActionListener {
            val idx = ramLimitBox.selectedIndex
            if (idx !in ramLimitOptions.indices) return@addActionListener
            val chosen = ramLimitOptions[idx].first
            MemoryManager.ramLimitMb = chosen
            memStatusLbl.text = formatMemoryStatus(chosen)
            ctx.setStatus("Limite de RAM configurado para ${chosen} MB (caches ajustados; teto JVM total aplica ao reiniciar)")
        }

        ramCard.addRow(
            createSettingsRow(
                title = "Limite de Consumo de RAM",
                subtitle = "Ajusta em tempo real os orçamentos de cache em MB e grava o teto -Xmx para a próxima inicialização.",
                rightControl = ramLimitBox,
            ),
        )

        val btnCleanRam = ModernButton(
            text = "Otimizar / Liberar RAM",
            icon = ModernIcons.storage(14) { Theme.TEXT_PRIMARY },
            variant = ModernButton.Variant.SURFACE,
        ).apply {
            addActionListener {
                MemoryManager.trimMemoryIfNeeded(force = true)
                memStatusLbl.text = formatMemoryStatus()
                ctx.setStatus("Caches liberados e coleta de memória acionada.")
            }
        }

        val monitorControlPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply {
            isOpaque = false
            add(memStatusLbl)
            add(btnCleanRam)
        }

        ramCard.addRow(
            createSettingsRow(
                title = "Monitor de Memória Ativa",
                subtitle = "Exibe o consumo de memória heap atual em relação ao limite estipulado.",
                rightControl = monitorControlPanel,
            ),
        )

        page.add(ramCard)
        page.add(Box.createVerticalStrut(24))

        addSectionHeader(
            page,
            "Renderização em Tempo Real",
            "Processamento aplicado instantaneamente enquanto você lê capítulos online ou locais.",
        )
        val realtimeCard = SettingsGroupCard()

        val savedQuality = runCatching {
            val qStr = readerPrefs.get("quality", "NITIDO")
            if (qStr in setOf("FAST", "BILINEAR")) MangaImageEnhancer.QualityMode.BILINEAR else MangaImageEnhancer.QualityMode.NITIDO
        }.getOrDefault(MangaImageEnhancer.QualityMode.NITIDO)

        val qualityPill = MihonSegmentedSelector(
            options = listOf(
                MangaImageEnhancer.QualityMode.BILINEAR to "Bilinear",
                MangaImageEnhancer.QualityMode.NITIDO to "Traço Nitido",
            ),
            initialValue = savedQuality,
        ) { chosen ->
            readerPrefs.put("quality", chosen.name)
            ctx.setStatus("Qualidade do leitor: ${chosen.label}")
        }
        realtimeCard.addRow(
            createSettingsRow(
                title = "Qualidade de Renderização",
                subtitle = "O modo Traço Nitido refina o traço escuro de nanquim e remove artefatos de compressão JPEG.",
                rightControl = qualityPill,
            ),
        )

        val btnTestLab = ModernButton(
            text = "Abrir Menu Teste...",
            icon = ModernIcons.sparkles(14) { Color.WHITE },
            variant = ModernButton.Variant.PRIMARY,
        ).apply {
            addActionListener { onOpenTestLab() }
        }
        realtimeCard.addRow(
            createSettingsRow(
                title = "Laboratório de Comparação Visual",
                subtitle = "Compare lado a lado o resultado Original, Bilinear e Traço Nitido em uma página real.",
                rightControl = btnTestLab,
            ),
        )
        page.add(realtimeCard)
        page.add(Box.createVerticalStrut(24))

        addSectionHeader(
            page,
            "Pós-Processamento de Downloads (.cbz)",
            "Configure o aprimoramento aplicado automaticamente ao salvar capítulos no disco.",
        )
        val installCard = SettingsGroupCard()

        val installEnhanceBox = MihonDropdown(MangaImageEnhancer.InstallEnhanceMode.entries.map { it.label }.toTypedArray()).apply {
            val saved = runCatching {
                MangaImageEnhancer.InstallEnhanceMode.valueOf(readerPrefs.get("installEnhanceMode", "OFF"))
            }.getOrDefault(MangaImageEnhancer.InstallEnhanceMode.OFF)
            selectedIndex = saved.ordinal
            preferredSize = Dimension(260, Theme.CONTROL_HEIGHT_COMPACT)
            addActionListener {
                val chosen = MangaImageEnhancer.InstallEnhanceMode.entries.getOrNull(selectedIndex) ?: return@addActionListener
                readerPrefs.put("installEnhanceMode", chosen.name)
                ctx.setStatus("Melhoria visual nos downloads: ${chosen.label}")
            }
        }
        installCard.addRow(
            createSettingsRow(
                title = "Filtro de Melhoria na Instalação",
                subtitle = "Processa cada página baixada antes de empacotar o arquivo .cbz local.",
                rightControl = installEnhanceBox,
            ),
        )

        val installFormatBox = MihonDropdown(MangaImageEnhancer.InstallOutputFormat.entries.map { it.label }.toTypedArray()).apply {
            val saved = runCatching {
                MangaImageEnhancer.InstallOutputFormat.valueOf(readerPrefs.get("installOutputFormat", "JPEG_HQ"))
            }.getOrDefault(MangaImageEnhancer.InstallOutputFormat.JPEG_HQ)
            selectedIndex = saved.ordinal
            preferredSize = Dimension(260, Theme.CONTROL_HEIGHT_COMPACT)
            addActionListener {
                val chosen = MangaImageEnhancer.InstallOutputFormat.entries.getOrNull(selectedIndex) ?: return@addActionListener
                readerPrefs.put("installOutputFormat", chosen.name)
            }
        }
        installCard.addRow(
            createSettingsRow(
                title = "Formato de Saída das Imagens",
                subtitle = "Escolha entre JPEG de alta qualidade (menor espaço) ou PNG sem perdas.",
                rightControl = installFormatBox,
            ),
        )
        page.add(installCard)

        return page
    }

    // =========================================================================
    // PAGE 5: DADOS & BACKUP
    // =========================================================================
    private fun buildStoragePage(): JPanel {
        val page = createPageContainer(
            "Dados & Backup",
            "Gerencie cópias de segurança da sua biblioteca e inspecione os diretórios locais.",
        )

        addSectionHeader(
            page,
            "Cópia de Segurança (Backup)",
            "Exporte ou restaure seu banco de dados de mangás favoritos, categorias e histórico.",
        )
        val backupCard = SettingsGroupCard()

        val btnExport = ModernButton(
            text = "Exportar Backup (.zip)",
            icon = ModernIcons.downloads(14) { Theme.TEXT_PRIMARY },
            variant = ModernButton.Variant.SURFACE,
        ).apply {
            addActionListener { onExportBackup() }
        }
        backupCard.addRow(
            createSettingsRow(
                title = "Exportar Biblioteca e Histórico",
                subtitle = "Gera um pacote .zip contendo o banco SQLite e todas as fontes configuradas.",
                rightControl = btnExport,
            ),
        )

        val btnRestore = ModernButton(
            text = "Restaurar Backup...",
            icon = ModernIcons.sync(14) { Theme.TEXT_PRIMARY },
            variant = ModernButton.Variant.SURFACE,
        ).apply {
            addActionListener { onImportBackup() }
        }
        backupCard.addRow(
            createSettingsRow(
                title = "Restaurar de um Arquivo",
                subtitle = "Substitui ou mescla os dados atuais a partir de um backup salvo anteriormente.",
                rightControl = btnRestore,
            ),
        )
        page.add(backupCard)
        page.add(Box.createVerticalStrut(24))

        addSectionHeader(
            page,
            "Diretórios do Sistema",
            "Locais onde os arquivos do Mihon Linux são armazenados em seu computador.",
        )
        val pathsCard = SettingsGroupCard()

        fun openFolderButton(dir: File): ModernButton = ModernButton(
            text = "Abrir Pasta",
            variant = ModernButton.Variant.SURFACE,
        ).apply {
            addActionListener {
                runCatching {
                    val target = if (dir.isDirectory) dir else dir.parentFile
                    if (target != null && target.exists() && Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().open(target)
                    }
                }
            }
        }

        pathsCard.addRow(
            createSettingsRow(
                title = "Local de Downloads (.cbz)",
                subtitle = "Pasta onde os capítulos baixados para leitura offline são salvos.",
                monoDetail = ctx.service.downloadsDir.absolutePath,
                rightControl = openFolderButton(ctx.service.downloadsDir),
            ),
        )
        pathsCard.addRow(
            createSettingsRow(
                title = "Banco de Dados SQLite",
                subtitle = "Arquivo principal que armazena sua biblioteca, capítulos e progresso.",
                monoDetail = dbFile.absolutePath,
                rightControl = openFolderButton(dbFile),
            ),
        )
        val sourcesDir = File(homeDir, ".mihon/sources")
        pathsCard.addRow(
            createSettingsRow(
                title = "Catálogo de Fontes JSON",
                subtitle = "Diretório de configuração de extensões e raspadores customizados.",
                monoDetail = sourcesDir.absolutePath,
                rightControl = openFolderButton(sourcesDir),
            ),
        )
        val logFile = File(homeDir, ".mihon/logs/mihon.log")
        pathsCard.addRow(
            createSettingsRow(
                title = "Registro de Logs",
                subtitle = "Histórico técnico de eventos e diagnósticos de erros da aplicação.",
                monoDetail = logFile.absolutePath,
                rightControl = openFolderButton(logFile),
            ),
        )
        page.add(pathsCard)

        return page
    }

    // =========================================================================
    // PAGE 6: SOBRE
    // =========================================================================
    private fun buildAboutPage(): JPanel {
        val page = createPageContainer(
            "Sobre",
            "Informações da versão, atalhos de teclado do leitor e controle de sessão.",
        )

        addSectionHeader(
            page,
            "Mihon Desktop Linux",
            "Leitor de mangá e manhwa nativo de alta performance.",
        )
        val aboutCard = SettingsGroupCard()
        val btnInfo = ModernButton(
            text = "Ver Detalhes",
            icon = ModernIcons.about(14) { Theme.TEXT_PRIMARY },
            variant = ModernButton.Variant.SURFACE,
        ).apply {
            addActionListener {
                val owner = SwingUtilities.getWindowAncestor(this@AntiGravitySettingsScreen)
                val dlg = JDialog(owner, "Sobre o Mihon Linux", java.awt.Dialog.ModalityType.APPLICATION_MODAL).apply {
                    setSize(420, 230)
                    isResizable = false
                    setLocationRelativeTo(this@AntiGravitySettingsScreen)
                }
                val root = JPanel(BorderLayout(14, 14)).apply {
                    background = Theme.BG_SURFACE
                    border = BorderFactory.createEmptyBorder(20, 24, 18, 24)
                }
                val body = JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(JLabel("Mihon Desktop Linux v0.4.0").apply {
                        font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 17f)
                        foreground = Theme.TEXT_PRIMARY
                        alignmentX = 0f
                    })
                    add(Box.createVerticalStrut(6))
                    add(JLabel("Kotlin JVM + Exposed/SQLite + FlatLaf Swing").apply {
                        font = Theme.FONT_UI.deriveFont(Font.PLAIN, 13f)
                        foreground = Theme.TEXT_SECONDARY
                        alignmentX = 0f
                    })
                    add(Box.createVerticalStrut(4))
                    add(JLabel("Design System Mihon Index + Motion Engine 60 FPS").apply {
                        font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12.5f)
                        foreground = Theme.TEXT_MUTED
                        alignmentX = 0f
                    })
                }
                val btnOk = ModernButton(text = "Fechar", variant = ModernButton.Variant.PRIMARY).apply {
                    addActionListener { dlg.dispose() }
                }
                val bar = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    isOpaque = false
                    add(btnOk)
                }
                root.add(JLabel(AppIcon.getIcon(40)), BorderLayout.WEST)
                root.add(body, BorderLayout.CENTER)
                root.add(bar, BorderLayout.SOUTH)
                dlg.rootPane.defaultButton = btnOk
                dlg.contentPane = root
                dlg.isVisible = true
            }
        }
        aboutCard.addRow(
            createSettingsRow(
                title = "Versão Instalada",
                subtitle = "Mihon Linux v0.4.0 — Arquitetura modular Kotlin JVM.",
                rightControl = btnInfo,
            ),
        )

        val btnExit = ModernButton(
            text = "Encerrar Aplicativo",
            variant = ModernButton.Variant.DANGER,
        ).apply {
            addActionListener { onExitApp() }
        }
        aboutCard.addRow(
            createSettingsRow(
                title = "Sessão do Aplicativo",
                subtitle = "Fecha todas as janelas abertas e encerra o processo com segurança.",
                rightControl = btnExit,
            ),
        )
        page.add(aboutCard)

        return page
    }
}
