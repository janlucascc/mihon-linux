package mihon.desktop

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.source.HttpScraperSource
import mihon.source.ScraperConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Window
import java.io.File
import java.util.prefs.Preferences
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

/**
 * Cliente de Repositórios Comunitários de Extensões:
 * Permite descobrir, instalar com 1 clique e atualizar fontes JSON (`ScraperConfig`)
 * tanto do catálogo curado embutido quanto de URLs de repositórios remotos (`index.json`).
 */
object CommunityRepoClient {
    private val prefs = Preferences.userRoot().node("mihon-linux-repos")
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }

    data class CatalogEntry(
        val config: ScraperConfig,
        val repoOrigin: String = "Catálogo Oficial Curado",
        val version: String = "1.2",
    )

    /**
     * Fontes curadas pré-configuradas com motores Madara / MangaStream / WP-Manga.
     */
    val BUILTIN_CATALOG: List<CatalogEntry> = listOf(
        CatalogEntry(
            config = ScraperConfig(
                name = "Ler Mangá (PT-BR)",
                baseUrl = "https://lermanga.org",
                language = "pt-br",
                searchPath = "/?s={query}",
                popularPath = "/mangas/?orderby=trending",
                searchItem = ".flw-item, .manga-item, div.c-tabs-item__content",
                searchTitle = "h3 a, .film-name a, .post-title a",
                searchLink = "h3 a, .film-name a, .post-title a",
                searchCover = "img",
                detailsTitle = "h1",
                detailsDescription = ".description, .summary__content p",
                detailsCover = ".manga-poster img, .summary_image img",
                chapterItem = ".single-chapter, li.wp-manga-chapter",
                chapterLink = "a",
                reverseChapters = true,
                pageImage = ".reading-content img, .chapter-content img",
            ),
            version = "1.4",
        ),
        CatalogEntry(
            config = ScraperConfig(
                name = "Central de Mangás (PT-BR)",
                baseUrl = "https://centraldemangas.to",
                language = "pt-br",
                searchPath = "/?s={query}&post_type=wp-manga",
                popularPath = "/?s=&post_type=wp-manga&m_orderby=views",
                searchItem = "div.c-tabs-item__content, div.page-item-detail",
                searchTitle = ".post-title h3 a, .post-title a",
                searchLink = ".post-title h3 a, .post-title a",
                searchCover = ".tab-thumb img, .item-thumb img",
                detailsTitle = ".post-title h1",
                detailsAuthor = ".author-content a",
                detailsDescription = ".summary__content p",
                detailsCover = ".summary_image img",
                chapterItem = "li.wp-manga-chapter",
                chapterLink = "a",
                reverseChapters = true,
                pageImage = ".reading-content img",
            ),
            version = "1.3",
        ),
        CatalogEntry(
            config = ScraperConfig(
                name = "Manhwa Club (EN / Multi)",
                baseUrl = "https://manhwaclub.net",
                language = "en",
                searchPath = "/?s={query}&post_type=wp-manga",
                popularPath = "/?s=&post_type=wp-manga&m_orderby=trending",
                searchItem = "div.c-tabs-item__content, div.page-item-detail",
                searchTitle = ".post-title h3 a, .post-title a",
                searchLink = ".post-title h3 a, .post-title a",
                searchCover = ".tab-thumb img, .item-thumb img",
                detailsTitle = ".post-title h1",
                detailsAuthor = ".author-content a",
                detailsDescription = ".summary__content p",
                detailsCover = ".summary_image img",
                chapterItem = "li.wp-manga-chapter",
                chapterLink = "a",
                reverseChapters = true,
                pageImage = ".reading-content img",
            ),
            version = "1.2",
        ),
        CatalogEntry(
            config = ScraperConfig(
                name = "MangaKakalot Mirror (EN)",
                baseUrl = "https://chapmanganato.to",
                language = "en",
                searchPath = "/search/story/{query}",
                popularPath = "/genre-all?type=topview",
                searchItem = ".search-story-item, .content-genres-item",
                searchTitle = "h3 a, .genres-item-name",
                searchLink = "h3 a, .genres-item-name",
                searchCover = "img",
                detailsTitle = ".story-info-right h1",
                detailsAuthor = ".variations-tableInfo tr:contains(Author) .table-value",
                detailsDescription = "#panel-story-info-description",
                detailsCover = ".info-image img",
                chapterItem = "ul.row-content-chapter li",
                chapterLink = "a.chapter-name",
                reverseChapters = true,
                pageImage = ".container-chapter-reader img",
            ),
            version = "1.5",
        ),
    )

    var customRepoUrls: List<String>
        get() {
            val raw = prefs.get("custom_repos", "").trim()
            if (raw.isEmpty()) return emptyList()
            return raw.split("\n").map { it.trim() }.filter { it.startsWith("http") }
        }
        set(value) {
            prefs.put("custom_repos", value.distinct().joinToString("\n"))
        }

    /**
     * Busca todas as fontes disponíveis (embutidas + repositórios remotos configurados).
     */
    fun fetchAllAvailable(httpClient: OkHttpClient): List<CatalogEntry> {
        val result = ArrayList<CatalogEntry>(BUILTIN_CATALOG)
        for (repoUrl in customRepoUrls) {
            runCatching {
                val req = Request.Builder().url(repoUrl).get().build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val configs = if (body.trimStart().startsWith("[")) {
                            json.decodeFromString<List<ScraperConfig>>(body)
                        } else {
                            listOf(json.decodeFromString<ScraperConfig>(body))
                        }
                        configs.forEach { cfg ->
                            result.add(CatalogEntry(config = cfg, repoOrigin = repoUrl, version = "Remoto"))
                        }
                    }
                }
            }
        }
        return result.distinctBy { "${it.config.name.lowercase()}::${it.config.baseUrl.lowercase()}" }
    }

    /**
     * Instala ou atualiza uma fonte no diretório `~/.mihon/sources/` e registra no [AppContext].
     */
    fun installExtension(ctx: AppContext, entry: CatalogEntry): File {
        val sourcesDir = File(System.getProperty("user.home"), ".mihon/sources").apply { mkdirs() }
        val slug = entry.config.name
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "community-source" }

        // Remove arquivo .disabled caso existisse
        val disabledFile = File(sourcesDir, "$slug.json.disabled")
        if (disabledFile.exists()) disabledFile.delete()

        val targetFile = File(sourcesDir, "$slug.json")
        targetFile.writeText(json.encodeToString(entry.config))

        val source = HttpScraperSource(entry.config)
        ctx.registerExtension(source)
        return targetFile
    }
}

/**
 * Diálogo da Loja / Catálogo Comunitário de Extensões (Instalação em 1 Clique + Adição de Repositórios JSON).
 */
class CommunityRepoDialog(
    owner: Window?,
    private val ctx: AppContext,
    private val onInstalled: () -> Unit = {},
) : JDialog(owner, "Catálogo da Comunidade — Instalar Extensões em 1 Clique", ModalityType.APPLICATION_MODAL) {

    private val model = DefaultListModel<CommunityRepoClient.CatalogEntry>()
    private val list = JList(model)
    private val repoUrlField = JTextField().apply {
        putClientProperty("JTextField.placeholderText", "Adicionar URL de repositório JSON (ex: https://.../index.json)…")
    }

    init {
        setSize(680, 520)
        setLocationRelativeTo(owner)
        layout = BorderLayout()

        val topPanel = JPanel().apply {
            background = Theme.BG_SURFACE
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(16, 20, 14, 20),
            )
        }

        val title = JLabel(
            "Loja & Repositórios da Comunidade",
            ModernIcons.browse(18) { Theme.ACCENT },
            javax.swing.SwingConstants.LEFT,
        ).apply {
            iconTextGap = 8
            font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 17f)
            foreground = Theme.TEXT_PRIMARY
            alignmentX = 0f
        }
        val sub = JLabel("Selecione uma fonte abaixo e clique em 'Instalar / Atualizar Selecionada' ou adicione um repositório JSON externo.").apply {
            font = Theme.FONT_UI.deriveFont(Font.PLAIN, 12f)
            foreground = Theme.TEXT_MUTED
            alignmentX = 0f
        }
        topPanel.add(title)
        topPanel.add(Box.createVerticalStrut(4))
        topPanel.add(sub)
        topPanel.add(Box.createVerticalStrut(12))

        val btnAddRepo = ModernButton(
            text = "+ Adicionar Repositório",
            variant = ModernButton.Variant.SURFACE,
        ).apply {
            addActionListener {
                val url = repoUrlField.text.trim()
                if (!url.startsWith("http")) {
                    JOptionPane.showMessageDialog(
                        this@CommunityRepoDialog,
                        "Informe uma URL HTTP/HTTPS válida apontando para um arquivo .json de extensões.",
                        "URL Inválida",
                        JOptionPane.WARNING_MESSAGE,
                    )
                    return@addActionListener
                }
                CommunityRepoClient.customRepoUrls = CommunityRepoClient.customRepoUrls + url
                repoUrlField.text = ""
                reloadCatalog()
            }
        }

        val repoRow = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            alignmentX = 0f
            maximumSize = Dimension(Int.MAX_VALUE, 34)
            add(repoUrlField, BorderLayout.CENTER)
            add(btnAddRepo, BorderLayout.EAST)
        }
        topPanel.add(repoRow)

        list.background = Theme.BG_APP
        list.fixedCellHeight = 64
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.border = BorderFactory.createEmptyBorder(10, 16, 10, 16)
        list.cellRenderer = ListCellRenderer { _, entry, _, selected, _ ->
            val installed = ctx.extensions.any {
                it.name.equals(entry.config.name, ignoreCase = true) ||
                    it.baseUrl.trimEnd('/').equals(entry.config.baseUrl.trimEnd('/'), ignoreCase = true)
            }
            object : JPanel() {
                init { isOpaque = false }
                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
                    val x = 6
                    val w = width - 12
                    val h = height
                    if (selected) {
                        g2.color = Theme.BG_CARD_HOVER
                        g2.fillRoundRect(x, 2, w, h - 4, 8, 8)
                    }
                    g2.color = Theme.BORDER_SUBTLE
                    g2.drawLine(x + 8, h - 1, x + w - 8, h - 1)

                    g2.font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 14.5f)
                    g2.color = Theme.TEXT_PRIMARY
                    g2.drawString(entry.config.name, x + 16, 26)

                    g2.font = Theme.FONT_MONO.deriveFont(Font.PLAIN, 11.5f)
                    g2.color = Theme.TEXT_MUTED
                    g2.drawString("${entry.config.baseUrl}  •  ${entry.config.language.uppercase()}  •  v${entry.version}", x + 16, 46)

                    val badge = if (installed) "✔ Instalada" else "⬇ Disponível"
                    g2.font = Theme.FONT_MONO.deriveFont(Font.BOLD, 11.5f)
                    g2.color = if (installed) Theme.SUCCESS else Theme.ACCENT_HOVER
                    val bw = g2.fontMetrics.stringWidth(badge)
                    g2.drawString(badge, x + w - bw - 18, (h / 2) + 5)
                    g2.dispose()
                }
            }
        }

        val scroll = JScrollPane(list).apply {
            border = null
            background = Theme.BG_APP
            viewport.background = Theme.BG_APP
        }

        val btnInstallSelected = ModernButton(
            text = "Instalar / Atualizar Selecionada",
            icon = ModernIcons.downloads(14) { Color.WHITE },
            variant = ModernButton.Variant.PRIMARY,
        ).apply {
            addActionListener {
                val selected = list.selectedValue
                if (selected == null) {
                    JOptionPane.showMessageDialog(this@CommunityRepoDialog, "Selecione uma fonte na lista acima.")
                    return@addActionListener
                }
                val file = CommunityRepoClient.installExtension(ctx, selected)
                ctx.setStatus("Fonte '${selected.config.name}' instalada em ${file.name}!")
                onInstalled()
                list.repaint()
            }
        }

        val btnInstallAll = ModernButton(
            text = "Instalar Todas",
            variant = ModernButton.Variant.SURFACE,
        ).apply {
            addActionListener {
                val catalogModel = this@CommunityRepoDialog.model
                for (i in 0 until catalogModel.size()) {
                    CommunityRepoClient.installExtension(ctx, catalogModel.getElementAt(i))
                }
                ctx.setStatus("${catalogModel.size()} fontes comunitárias instaladas/atualizadas!")
                onInstalled()
                list.repaint()
            }
        }

        val btnClose = ModernButton(
            text = "Fechar",
            variant = ModernButton.Variant.SURFACE,
        ).apply {
            addActionListener { dispose() }
        }

        val footer = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 10)).apply {
            background = Theme.BG_SURFACE
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER_SUBTLE)
            add(btnInstallAll)
            add(btnInstallSelected)
            add(btnClose)
        }

        add(topPanel, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)

        reloadCatalog()
    }

    private fun reloadCatalog() {
        background({
            CommunityRepoClient.fetchAllAvailable(ctx.service.httpClient)
        }) { result ->
            result.onSuccess { entries ->
                model.clear()
                entries.forEach { model.addElement(it) }
                if (entries.isNotEmpty()) list.selectedIndex = 0
            }
        }
    }
}
