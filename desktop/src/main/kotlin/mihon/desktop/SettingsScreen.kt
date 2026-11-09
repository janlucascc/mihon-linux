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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.io.File
import java.util.prefs.Preferences
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.Icon
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JToggleButton
import javax.swing.SwingConstants

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
        val size = Dimension(totalW, 30)
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
        val arc = h

        // Outer pill track (AntiGravity dark capsule)
        val trackBg = if (Theme.isDark) Color(0x14, 0x15, 0x18) else Color(0xEC, 0xEE, 0xF2)
        val trackBorder = if (Theme.isDark) Color(255, 255, 255, 24) else Color(0, 0, 0, 24)
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
            val indArc = indH

            // Active segment pill (AntiGravity elevated charcoal pill)
            val activeBg = if (Theme.isDark) Color(0x2B, 0x2D, 0x33) else Color(0xFF, 0xFF, 0xFF)
            val activeBorder = if (Theme.isDark) Color(255, 255, 255, 32) else Color(0, 0, 0, 22)
            g2.color = activeBg
            g2.fill(RoundRectangle2D.Float(indX, indY, indW, indH, indArc, indArc))
            g2.color = activeBorder
            g2.draw(RoundRectangle2D.Float(indX, indY, indW, indH, indArc, indArc))
        }

        // Draw labels
        for (i in options.indices) {
            val isSel = i == selectedIndex
            val isHov = i == hoveredIndex
            g2.font = font.deriveFont(if (isSel) Font.BOLD else Font.PLAIN, 12f)
            val fm = g2.fontMetrics
            g2.color = when {
                isSel -> Theme.TEXT_PRIMARY
                isHov -> Theme.TEXT_PRIMARY
                else -> Theme.TEXT_MUTED
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
                    g.color = if (Theme.isDark) Color(255, 255, 255, 16) else Color(0, 0, 0, 14)
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

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val w = width.toFloat()
        val h = height.toFloat()
        val arc = 14f

        val cardBg = if (Theme.isDark) Color(0x16, 0x17, 0x1B) else Theme.BG_SURFACE
        val borderColor = if (Theme.isDark) Color(255, 255, 255, 20) else Color(0, 0, 0, 22)

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

    init {
        isOpaque = false
        isFocusable = false
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        alignmentX = 0f
        preferredSize = Dimension(190, 36)
        maximumSize = Dimension(Int.MAX_VALUE, 36)
    }

    override fun paintBorder(g: Graphics) {}

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        val w = width
        val h = height
        val arc = 10

        if (isSelected) {
            g2.color = if (Theme.isDark) Color(0x28, 0x2A, 0x30) else Theme.BG_PILL_ACTIVE
            g2.fillRoundRect(2, 2, w - 4, h - 4, arc, arc)
        } else if (model.isRollover) {
            g2.color = if (Theme.isDark) Color(255, 255, 255, 12) else Theme.BG_CARD_HOVER
            g2.fillRoundRect(2, 2, w - 4, h - 4, arc, arc)
        }

        var textX = 14
        if (navIcon != null) {
            val iy = (h - navIcon.iconHeight) / 2
            navIcon.paintIcon(this, g2, 12, iy)
            textX = 12 + navIcon.iconWidth + 10
        }

        g2.font = Theme.FONT_UI.deriveFont(if (isSelected) Font.BOLD else Font.PLAIN, 13f)
        g2.color = if (isSelected) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY
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

    private lateinit var themeBox: JComboBox<String>

    init {
        background = Theme.BG_APP
        detailContainer.background = Theme.BG_APP

        val leftNav = buildLeftSidebar()
        add(leftNav, BorderLayout.WEST)

        detailContainer.add(wrapScrollable(buildGeneralPage()), "general")
        detailContainer.add(wrapScrollable(buildAppearancePage()), "appearance")
        detailContainer.add(wrapScrollable(buildReaderPage()), "reader")
        detailContainer.add(wrapScrollable(buildGraphicsPage()), "graphics")
        detailContainer.add(wrapScrollable(buildStoragePage()), "storage")
        detailContainer.add(wrapScrollable(buildAboutPage()), "about")

        add(detailContainer, BorderLayout.CENTER)

        ctx.addThemeListener {
            background = Theme.BG_APP
            detailContainer.background = Theme.BG_APP
            themeUpdaters.forEach { it.invoke() }
            if (::themeBox.isInitialized) {
                val idx = Theme.currentPreset.ordinal
                if (themeBox.selectedIndex != idx) {
                    themeBox.selectedIndex = idx
                }
            }
            repaint()
        }
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

        val settingsHeaderLbl = JLabel("Settings").apply {
            font = Theme.FONT_UI.deriveFont(Font.BOLD, 11.5f)
            foreground = Theme.TEXT_MUTED
            border = BorderFactory.createEmptyBorder(0, 12, 6, 0)
            alignmentX = 0f
        }
        navPanel.add(settingsHeaderLbl)

        val group = ButtonGroup()
        val categories = listOf(
            Triple("general", "Geral", ModernIcons.settings(16) { Theme.TEXT_PRIMARY }),
            Triple("appearance", "Aparência", ModernIcons.sparkles(16) { Theme.TEXT_PRIMARY }),
            Triple("reader", "Leitor", ModernIcons.library(16) { Theme.TEXT_PRIMARY }),
            Triple("graphics", "Gráficos & Imagem", ModernIcons.statistics(16) { Theme.TEXT_PRIMARY }),
            Triple("storage", "Dados & Backup", ModernIcons.storage(16) { Theme.TEXT_PRIMARY }),
            Triple("about", "Sobre", ModernIcons.about(16) { Theme.TEXT_PRIMARY }),
        )

        categories.forEachIndexed { idx, (cardId, label, icon) ->
            val btn = AntiGravityCategoryButton(label, icon).apply {
                if (idx == 0) isSelected = true
                addActionListener {
                    detailCards.show(detailContainer, cardId)
                }
            }
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
                val appVer = JLabel("v0.3.0 • Desktop").apply {
                    font = Theme.FONT_UI.deriveFont(Font.PLAIN, 10.5f)
                    foreground = Theme.TEXT_MUTED
                }
                add(appTitle)
                add(appVer)
                themeUpdaters.add {
                    appTitle.foreground = Theme.TEXT_PRIMARY
                    appVer.foreground = Theme.TEXT_MUTED
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
            settingsHeaderLbl.foreground = Theme.TEXT_MUTED
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
            font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 22f)
            foreground = Theme.TEXT_PRIMARY
            alignmentX = 0f
        }
        val sub = JLabel(pageSubtitle).apply {
            font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12.5f)
            foreground = Theme.TEXT_MUTED
            alignmentX = 0f
        }

        page.add(h1)
        page.add(Box.createVerticalStrut(4))
        page.add(sub)
        page.add(Box.createVerticalStrut(20))

        themeUpdaters.add {
            page.background = Theme.BG_APP
            h1.foreground = Theme.TEXT_PRIMARY
            sub.foreground = Theme.TEXT_MUTED
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
                foreground = Theme.TEXT_MUTED
                alignmentX = 0f
            }
            page.add(descLbl)
            themeUpdaters.add { descLbl.foreground = Theme.TEXT_MUTED }
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
            foreground = Theme.TEXT_MUTED
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
            border = BorderFactory.createEmptyBorder(12, 14, 12, 14)
            add(textCol, BorderLayout.CENTER)
            add(rightWrap, BorderLayout.EAST)
            themeUpdaters.add {
                titleLbl.foreground = Theme.TEXT_PRIMARY
                subLbl.foreground = Theme.TEXT_MUTED
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
        val autoPrefetchPill = SegmentedPillSelector(
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
        page.add(execCard)

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

        themeBox = JComboBox(Theme.ThemePreset.entries.map { it.displayName }.toTypedArray()).apply {
            val savedPreset = runCatching { Theme.ThemePreset.valueOf(prefs.get("theme_preset", "AMOLED")) }
                .getOrDefault(Theme.ThemePreset.AMOLED)
            selectedIndex = savedPreset.ordinal
            preferredSize = Dimension(220, 32)
            addActionListener {
                val selected = Theme.ThemePreset.entries[selectedIndex]
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
            "Navegação Lateral",
            "Configure o modo de exibição padrão do menu principal.",
        )
        val navCard = SettingsGroupCard()
        val sidebarPill = SegmentedPillSelector(
            options = listOf(false to "Expandida", true to "Compacta (Ícones)"),
            initialValue = prefs.getBoolean("sidebar_collapsed", false),
        ) { collapsed ->
            prefs.putBoolean("sidebar_collapsed", collapsed)
            onToggleSidebarDefault(collapsed)
            ctx.setStatus(if (collapsed) "Barra lateral compacta ativada" else "Barra lateral expandida ativada")
        }
        navCard.addRow(
            createSettingsRow(
                title = "Estilo da Barra Lateral",
                subtitle = "Alterna entre exibir rótulos completos ou apenas os ícones centralizados.",
                rightControl = sidebarPill,
            ),
        )
        page.add(navCard)

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
        val modeSelector = SegmentedPillSelector(
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
        val fitSelector = SegmentedPillSelector(
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
        val rtlPill = SegmentedPillSelector(
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
    // PAGE 4: GRÁFICOS & IMAGEM
    // =========================================================================
    private fun buildGraphicsPage(): JPanel {
        val page = createPageContainer(
            "Gráficos & Imagem",
            "Motor de nitidez de nanquim em tempo real e aprimoramento de arquivos baixados.",
        )

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

        val qualityPill = SegmentedPillSelector(
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

        val installEnhanceBox = JComboBox(MangaImageEnhancer.InstallEnhanceMode.entries.map { it.label }.toTypedArray()).apply {
            val saved = runCatching {
                MangaImageEnhancer.InstallEnhanceMode.valueOf(readerPrefs.get("installEnhanceMode", "OFF"))
            }.getOrDefault(MangaImageEnhancer.InstallEnhanceMode.OFF)
            selectedIndex = saved.ordinal
            preferredSize = Dimension(260, 32)
            addActionListener {
                val chosen = MangaImageEnhancer.InstallEnhanceMode.entries[selectedIndex]
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

        val installFormatBox = JComboBox(MangaImageEnhancer.InstallOutputFormat.entries.map { it.label }.toTypedArray()).apply {
            val saved = runCatching {
                MangaImageEnhancer.InstallOutputFormat.valueOf(readerPrefs.get("installOutputFormat", "JPEG_HQ"))
            }.getOrDefault(MangaImageEnhancer.InstallOutputFormat.JPEG_HQ)
            selectedIndex = saved.ordinal
            preferredSize = Dimension(260, 32)
            addActionListener {
                val chosen = MangaImageEnhancer.InstallOutputFormat.entries[selectedIndex]
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
                JOptionPane.showMessageDialog(
                    this@AntiGravitySettingsScreen,
                    "Mihon Desktop Linux v0.3.0\nKotlin + Exposed/SQLite + FlatLaf Swing\nDesign System inspirado em Google AntiGravity",
                    "Sobre o Mihon",
                    JOptionPane.INFORMATION_MESSAGE,
                )
            }
        }
        aboutCard.addRow(
            createSettingsRow(
                title = "Versão Instalada",
                subtitle = "Mihon Linux v0.3.0 — Arquitetura modular Kotlin JVM.",
                rightControl = btnInfo,
            ),
        )

        val btnExit = ModernButton(
            text = "Encerrar Aplicativo",
            variant = ModernButton.Variant.FAVORITE,
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
        page.add(Box.createVerticalStrut(24))

        addSectionHeader(
            page,
            "Atalhos do Leitor",
            "Teclas rápidas disponíveis durante a leitura de capítulos.",
        )
        val shortcutsCard = SettingsGroupCard()
        val shortcuts = listOf(
            Triple("Navegar Páginas", "Avança ou retrocede páginas no capítulo aberto", "← / →  ou  Espaço"),
            Triple("Auto-Scroll Mãos-Livres", "Ativa ou pausa a rolagem vertical contínua", "Tecla A"),
            Triple("Tela Cheia", "Alterna o modo imersivo sem bordas de janela", "F11  ou  Esc"),
            Triple("Zoom in / Zoom out", "Amplia ou reduz a escala da página atual", "Ctrl + Roda do Mouse"),
        )
        for ((title, desc, keyText) in shortcuts) {
            val keyBadge = JLabel(keyText, SwingConstants.CENTER).apply {
                font = Theme.FONT_MONO.deriveFont(Font.BOLD, 11.5f)
                foreground = Theme.TEXT_PRIMARY
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.BORDER_SUBTLE, 1, true),
                    BorderFactory.createEmptyBorder(4, 10, 4, 10),
                )
            }
            themeUpdaters.add { keyBadge.foreground = Theme.TEXT_PRIMARY }
            shortcutsCard.addRow(
                createSettingsRow(
                    title = title,
                    subtitle = desc,
                    rightControl = keyBadge,
                ),
            )
        }
        page.add(shortcutsCard)

        return page
    }
}
