package mihon.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.manga.model.Manga
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Window
import java.util.prefs.Preferences
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel

/**
 * Serviço de Sincronização e Rastreamento (AniList GraphQL, MyAnimeList REST v2 e Rastreador Local).
 * Sincroniza automaticamente o último capítulo lido ao concluir capítulos no leitor.
 */
object TrackerService {
    private val prefs = Preferences.userRoot().node("mihon-linux-trackers")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    enum class Provider(val displayName: String) {
        ANILIST("AniList (GraphQL)"),
        MYANIMELIST("MyAnimeList (API v2)"),
        LOCAL("Rastreador Local (Offline)"),
    }

    enum class TrackStatus(val label: String, val anilistValue: String, val malValue: String) {
        READING("Lendo", "CURRENT", "reading"),
        PLANNING("Planejado", "PLANNING", "plan_to_read"),
        COMPLETED("Completo", "COMPLETED", "completed"),
        PAUSED("Pausado", "PAUSED", "on_hold"),
        DROPPED("Abandonado", "DROPPED", "dropped"),
    }

    @Serializable
    data class TrackRecord(
        val mangaId: Long,
        val provider: String = Provider.ANILIST.name,
        val remoteId: Long = 0L,
        val remoteTitle: String = "",
        val status: String = TrackStatus.READING.name,
        val lastChapterRead: Int = 0,
        val totalChapters: Int = 0,
        val score: Double = 0.0,
    )

    data class RemoteSearchResult(
        val provider: Provider,
        val remoteId: Long,
        val title: String,
        val totalChapters: Int,
        val statusText: String,
    ) {
        override fun toString(): String {
            val chInfo = if (totalChapters > 0) "$totalChapters caps" else "Em lançamento"
            return "$title  [$chInfo • $statusText] (#$remoteId)"
        }
    }

    var anilistToken: String
        get() = prefs.get("anilist_token", "").trim()
        set(value) = prefs.put("anilist_token", value.trim())

    var malToken: String
        get() = prefs.get("mal_token", "").trim()
        set(value) = prefs.put("mal_token", value.trim())

    var autoSyncOnRead: Boolean
        get() = prefs.getBoolean("auto_sync_on_read", true)
        set(value) = prefs.putBoolean("auto_sync_on_read", value)

    fun getRecord(mangaId: Long): TrackRecord? {
        val raw = prefs.get("track_$mangaId", null) ?: return null
        return runCatching { json.decodeFromString<TrackRecord>(raw) }.getOrNull()
    }

    fun saveRecord(record: TrackRecord) {
        prefs.put("track_${record.mangaId}", json.encodeToString(record))
    }

    fun removeRecord(mangaId: Long) {
        prefs.remove("track_$mangaId")
    }

    /**
     * Pesquisa mangás na API pública GraphQL do AniList ou na API v2 do MyAnimeList.
     */
    fun searchRemote(
        httpClient: OkHttpClient,
        provider: Provider,
        query: String,
    ): List<RemoteSearchResult> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        return when (provider) {
            Provider.ANILIST -> searchAniList(httpClient, cleanQuery)
            Provider.MYANIMELIST -> searchMal(httpClient, cleanQuery)
            Provider.LOCAL -> listOf(
                RemoteSearchResult(
                    provider = Provider.LOCAL,
                    remoteId = cleanQuery.hashCode().toLong() and 0x7FFFFFFFL,
                    title = cleanQuery,
                    totalChapters = 0,
                    statusText = "Local",
                ),
            )
        }
    }

    private fun searchAniList(httpClient: OkHttpClient, query: String): List<RemoteSearchResult> {
        val gql = """
            query (${'$'}search: String) {
              Page(page: 1, perPage: 12) {
                media(search: ${'$'}search, type: MANGA) {
                  id
                  chapters
                  status
                  title {
                    romaji
                    english
                  }
                }
              }
            }
        """.trimIndent()

        val payload = buildJsonObject {
            put("query", gql)
            put("variables", buildJsonObject { put("search", query) })
        }.toString()

        val reqBuilder = Request.Builder()
            .url("https://graphql.anilist.co")
            .post(payload.toRequestBody(jsonMediaType))
            .header("Accept", "application/json")
        if (anilistToken.isNotEmpty()) {
            reqBuilder.header("Authorization", "Bearer $anilistToken")
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) error("AniList HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val root = json.parseToJsonElement(body).jsonObject
            val mediaArr = root["data"]?.jsonObject?.get("Page")?.jsonObject?.get("media")?.jsonArray
                ?: return emptyList()

            return mediaArr.mapNotNull { item ->
                val obj = item.jsonObject
                val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val titleObj = obj["title"]?.jsonObject
                val title = titleObj?.get("english")?.jsonPrimitive?.takeIf { !it.isString || it.content != "null" }?.content
                    ?.takeIf { it.isNotBlank() }
                    ?: titleObj?.get("romaji")?.jsonPrimitive?.content
                    ?: "Mangá #$id"
                val chapters = obj["chapters"]?.jsonPrimitive?.intOrNull ?: 0
                val status = obj["status"]?.jsonPrimitive?.content ?: ""
                RemoteSearchResult(
                    provider = Provider.ANILIST,
                    remoteId = id,
                    title = title,
                    totalChapters = chapters,
                    statusText = status,
                )
            }
        }
    }

    private fun searchMal(httpClient: OkHttpClient, query: String): List<RemoteSearchResult> {
        val token = malToken
        require(token.isNotEmpty()) {
            "Configure seu Token de Acesso do MyAnimeList em Configurações → Geral → Rastreadores."
        }
        val url = "https://api.myanimelist.net/v2/manga?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=10&fields=num_chapters,status"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("MyAnimeList HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val root = json.parseToJsonElement(body).jsonObject
            val dataArr = root["data"]?.jsonArray ?: return emptyList()
            return dataArr.mapNotNull { el ->
                val node = el.jsonObject["node"]?.jsonObject ?: return@mapNotNull null
                val id = node["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val title = node["title"]?.jsonPrimitive?.content ?: "Mangá #$id"
                val chapters = node["num_chapters"]?.jsonPrimitive?.intOrNull ?: 0
                val status = node["status"]?.jsonPrimitive?.content ?: ""
                RemoteSearchResult(
                    provider = Provider.MYANIMELIST,
                    remoteId = id,
                    title = title,
                    totalChapters = chapters,
                    statusText = status,
                )
            }
        }
    }

    /**
     * Salva o registro localmente e sincroniza com a nuvem (AniList / MyAnimeList) se houver token configurado.
     */
    fun syncRecord(httpClient: OkHttpClient, record: TrackRecord): String {
        saveRecord(record)
        val provider = runCatching { Provider.valueOf(record.provider) }.getOrDefault(Provider.LOCAL)
        val status = runCatching { TrackStatus.valueOf(record.status) }.getOrDefault(TrackStatus.READING)

        return when (provider) {
            Provider.LOCAL -> "Progresso salvo no Rastreador Local (Cap. ${record.lastChapterRead})."
            Provider.ANILIST -> {
                if (anilistToken.isEmpty() || record.remoteId <= 0L) {
                    "Vinculado localmente ao AniList (adicione seu Token nas Configurações para envio à nuvem)."
                } else {
                    pushAniListUpdate(httpClient, record, status)
                    "Sincronizado com AniList: Cap. ${record.lastChapterRead} (${status.label}) ✔"
                }
            }
            Provider.MYANIMELIST -> {
                if (malToken.isEmpty() || record.remoteId <= 0L) {
                    "Vinculado localmente ao MyAnimeList (adicione seu Token nas Configurações para envio à nuvem)."
                } else {
                    pushMalUpdate(httpClient, record, status)
                    "Sincronizado com MyAnimeList: Cap. ${record.lastChapterRead} (${status.label}) ✔"
                }
            }
        }
    }

    private fun pushAniListUpdate(httpClient: OkHttpClient, record: TrackRecord, status: TrackStatus) {
        val mutation = """
            mutation (${'$'}mediaId: Int, ${'$'}status: MediaListStatus, ${'$'}progress: Int, ${'$'}scoreRaw: Int) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, status: ${'$'}status, progress: ${'$'}progress, scoreRaw: ${'$'}scoreRaw) {
                id
                progress
                status
              }
            }
        """.trimIndent()

        val payload = buildJsonObject {
            put("query", mutation)
            put(
                "variables",
                buildJsonObject {
                    put("mediaId", record.remoteId.toInt())
                    put("status", status.anilistValue)
                    put("progress", record.lastChapterRead)
                    put("scoreRaw", (record.score * 10).toInt().coerceIn(0, 100))
                },
            )
        }.toString()

        val req = Request.Builder()
            .url("https://graphql.anilist.co")
            .post(payload.toRequestBody(jsonMediaType))
            .header("Authorization", "Bearer $anilistToken")
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("Falha ao sincronizar com AniList (HTTP ${resp.code})")
            }
        }
    }

    private fun pushMalUpdate(httpClient: OkHttpClient, record: TrackRecord, status: TrackStatus) {
        val form = FormBody.Builder()
            .add("status", status.malValue)
            .add("num_chapters_read", record.lastChapterRead.toString())
            .add("score", record.score.toInt().coerceIn(0, 10).toString())
            .build()

        val req = Request.Builder()
            .url("https://api.myanimelist.net/v2/manga/${record.remoteId}/my_list_status")
            .patch(form)
            .header("Authorization", "Bearer $malToken")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("Falha ao sincronizar com MyAnimeList (HTTP ${resp.code})")
            }
        }
    }

    /**
     * Chamado automaticamente quando o usuário conclui um capítulo no leitor ou marca como lido.
     */
    fun onChapterRead(httpClient: OkHttpClient, mangaId: Long, chapterNumber: Int) {
        if (!autoSyncOnRead || chapterNumber <= 0) return
        val existing = getRecord(mangaId) ?: return
        if (chapterNumber <= existing.lastChapterRead) return

        val newStatus = if (existing.totalChapters > 0 && chapterNumber >= existing.totalChapters) {
            TrackStatus.COMPLETED.name
        } else {
            existing.status
        }
        val updated = existing.copy(
            lastChapterRead = chapterNumber,
            status = newStatus,
        )
        runCatching {
            syncRecord(httpClient, updated)
        }
    }
}

/**
 * Diálogo modal de Rastreamento (AniList / MyAnimeList / Local) para um mangá específico.
 */
class TrackerDialog(
    owner: Window?,
    private val ctx: AppContext,
    private val manga: Manga,
    private val highestReadChapter: Int,
    private val onUpdated: () -> Unit = {},
) : JDialog(owner, "Rastreamento & Sincronização — ${manga.title}", ModalityType.APPLICATION_MODAL) {

    private val currentRecord = TrackerService.getRecord(manga.id)

    private val providerCombo = JComboBox(
        TrackerService.Provider.entries.map { it.displayName }.toTypedArray(),
    ).apply {
        val initialProvider = currentRecord?.let {
            runCatching { TrackerService.Provider.valueOf(it.provider) }.getOrNull()
        } ?: TrackerService.Provider.ANILIST
        selectedIndex = initialProvider.ordinal
    }

    private val searchField = JTextField(currentRecord?.remoteTitle?.ifBlank { manga.title } ?: manga.title)
    private val resultsModel = DefaultListModel<TrackerService.RemoteSearchResult>()
    private val resultsList = JList(resultsModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        background = Theme.BG_APP
        foreground = Theme.TEXT_PRIMARY
    }

    private val statusCombo = JComboBox(
        TrackerService.TrackStatus.entries.map { it.label }.toTypedArray(),
    ).apply {
        val initialStatus = currentRecord?.let {
            runCatching { TrackerService.TrackStatus.valueOf(it.status) }.getOrNull()
        } ?: TrackerService.TrackStatus.READING
        selectedIndex = initialStatus.ordinal
    }

    private val chapterSpinner = JSpinner(
        SpinnerNumberModel(
            (currentRecord?.lastChapterRead ?: highestReadChapter).coerceAtLeast(0),
            0,
            99999,
            1,
        ),
    )

    private val scoreSpinner = JSpinner(
        SpinnerNumberModel(
            (currentRecord?.score ?: 0.0).coerceIn(0.0, 10.0),
            0.0,
            10.0,
            0.5,
        ),
    )

    private var selectedRemoteId: Long = currentRecord?.remoteId ?: 0L
    private var selectedRemoteTitle: String = currentRecord?.remoteTitle?.ifBlank { manga.title } ?: manga.title
    private var selectedTotalChapters: Int = currentRecord?.totalChapters ?: 0

    private val linkedLabel = JLabel().apply {
        font = Theme.FONT_MONO.deriveFont(Font.BOLD, 11.5f)
        foreground = Theme.ACCENT
    }

    init {
        setSize(580, 500)
        setLocationRelativeTo(owner)
        layout = BorderLayout()

        updateLinkedLabel()

        val root = JPanel().apply {
            background = Theme.BG_SURFACE
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(18, 22, 18, 22)
        }

        val topRow = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false
            alignmentX = 0f
            maximumSize = Dimension(Int.MAX_VALUE, 34)
            add(JLabel("Serviço de Rastreamento:").apply {
                foreground = Theme.TEXT_PRIMARY
                font = Theme.FONT_UI.deriveFont(Font.BOLD, 13f)
            }, BorderLayout.WEST)
            add(providerCombo, BorderLayout.CENTER)
        }
        root.add(topRow)
        root.add(Box.createVerticalStrut(10))

        val btnSearch = ModernButton(
            text = "Buscar no Catálogo",
            icon = ModernIcons.search(14) { Color.WHITE },
            variant = ModernButton.Variant.PRIMARY,
        ).apply {
            addActionListener { performSearch() }
        }
        searchField.addActionListener { performSearch() }

        val searchRow = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            alignmentX = 0f
            maximumSize = Dimension(Int.MAX_VALUE, 34)
            add(searchField, BorderLayout.CENTER)
            add(btnSearch, BorderLayout.EAST)
        }
        root.add(searchRow)
        root.add(Box.createVerticalStrut(8))

        linkedLabel.alignmentX = 0f
        root.add(linkedLabel)
        root.add(Box.createVerticalStrut(6))

        resultsList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val chosen = resultsList.selectedValue ?: return@addListSelectionListener
                selectedRemoteId = chosen.remoteId
                selectedRemoteTitle = chosen.title
                selectedTotalChapters = chosen.totalChapters
                updateLinkedLabel()
            }
        }

        val scrollResults = JScrollPane(resultsList).apply {
            alignmentX = 0f
            preferredSize = Dimension(520, 140)
            border = BorderFactory.createLineBorder(Theme.BORDER_SUBTLE)
        }
        root.add(scrollResults)
        root.add(Box.createVerticalStrut(14))

        val formPanel = JPanel( java.awt.GridLayout(3, 2, 12, 10)).apply {
            isOpaque = false
            alignmentX = 0f
            maximumSize = Dimension(Int.MAX_VALUE, 110)
            add(JLabel("Status de Leitura:").apply { foreground = Theme.TEXT_PRIMARY })
            add(statusCombo)
            add(JLabel("Capítulos Lidos:").apply { foreground = Theme.TEXT_PRIMARY })
            add(chapterSpinner)
            add(JLabel("Nota (0 a 10):").apply { foreground = Theme.TEXT_PRIMARY })
            add(scoreSpinner)
        }
        root.add(formPanel)
        root.add(Box.createVerticalGlue())

        val btnUnlink = ModernButton(
            text = "Desvincular",
            variant = ModernButton.Variant.SURFACE,
        ).apply {
            isVisible = currentRecord != null
            addActionListener {
                TrackerService.removeRecord(manga.id)
                ctx.setStatus("Rastreamento removido para '${manga.title}'")
                onUpdated()
                dispose()
            }
        }

        val btnCancel = ModernButton(
            text = "Cancelar",
            variant = ModernButton.Variant.SURFACE,
        ).apply {
            addActionListener { dispose() }
        }

        val btnSave = ModernButton(
            text = "Salvar & Sincronizar",
            icon = ModernIcons.check(14) { Color.WHITE },
            variant = ModernButton.Variant.PRIMARY,
        ).apply {
            addActionListener { saveAndSync() }
        }

        val footer = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            alignmentX = 0f
            add(btnUnlink)
            add(btnCancel)
            add(btnSave)
        }
        root.add(footer)

        add(root, BorderLayout.CENTER)
    }

    private fun updateLinkedLabel() {
        linkedLabel.text = if (selectedRemoteId > 0L) {
            "✔ Vinculado a: $selectedRemoteTitle (#$selectedRemoteId)"
        } else {
            "Pesquise acima ou clique em Salvar para rastrear localmente."
        }
    }

    private fun performSearch() {
        val provider = TrackerService.Provider.entries[providerCombo.selectedIndex]
        val q = searchField.text.trim()
        if (q.isEmpty()) return
        linkedLabel.text = "Buscando '$q' em ${provider.displayName}…"
        resultsModel.clear()

        background({
            TrackerService.searchRemote(ctx.service.httpClient, provider, q)
        }) { result ->
            result.onSuccess { list ->
                resultsModel.clear()
                list.forEach { resultsModel.addElement(it) }
                if (list.isNotEmpty()) {
                    resultsList.selectedIndex = 0
                } else {
                    linkedLabel.text = "Nenhum resultado encontrado para '$q'."
                }
            }.onFailure { err ->
                linkedLabel.text = "Erro na busca: ${err.message}"
            }
        }
    }

    private fun saveAndSync() {
        val provider = TrackerService.Provider.entries[providerCombo.selectedIndex]
        val status = TrackerService.TrackStatus.entries[statusCombo.selectedIndex]
        val chRead = (chapterSpinner.value as? Number)?.toInt() ?: 0
        val score = (scoreSpinner.value as? Number)?.toDouble() ?: 0.0

        val record = TrackerService.TrackRecord(
            mangaId = manga.id,
            provider = provider.name,
            remoteId = selectedRemoteId,
            remoteTitle = selectedRemoteTitle.ifBlank { manga.title },
            status = status.name,
            lastChapterRead = chRead,
            totalChapters = selectedTotalChapters,
            score = score,
        )

        background({
            TrackerService.syncRecord(ctx.service.httpClient, record)
        }) { result ->
            result.onSuccess { msg ->
                ctx.setStatus(msg)
                onUpdated()
                dispose()
            }.onFailure { err ->
                ctx.setStatus("Salvo localmente (erro na nuvem: ${err.message})")
                onUpdated()
                JOptionPane.showMessageDialog(
                    this,
                    "Progresso salvo localmente, mas houve um aviso ao enviar para a nuvem:\n${err.message}",
                    "Aviso de Sincronização",
                    JOptionPane.WARNING_MESSAGE,
                )
                dispose()
            }
        }
    }
}
