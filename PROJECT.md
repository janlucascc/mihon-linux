# Project: Mihon Linux — Tradutor Simultâneo Estilo Google Lens

## Architecture
Mihon Linux é uma aplicação desktop Kotlin `2.4.21` / Java `21` (Temurin) com interface Swing + FlatLaf `3.5.4` e build Gradle `9.8.2`. Os módulos existentes (`:domain`, `:core:archive`, `:core:common`, `:core:metro`, `:source-api`, `:data`, `:i18n`, `:desktop`) permanecem intactos, enquanto a funcionalidade de tradução simultânea estilo Google Lens é construída de forma modular e desacoplada dentro de `:desktop` sob `mihon.desktop.translator`:

1. **Detecção de Regiões & OCR Local (`mihon.desktop.translator`)**:
   - `TextRegionDetector`: detector puro-JVM leve de balões de fala e agrupamentos densos de texto em mangás (operando sobre cópia reduzida, retornando retângulos normalizados `[0..1]`).
   - `TesseractJnaEngine`: binding JNA (`net.java.dev.jna:jna:5.14.0`, zero transitivas) sondando `/lib64/libtesseract.so.5.5` (e nomes padrão Linux) com fallback opcional para CLI `tesseract`, instância única protegida por `Mutex` e fallback de segmentação via `TessPageIterator`.
   - `TessdataManager`: localiza modelos em `/usr/share/tesseract/tessdata` e `~/.mihon/tessdata`, baixando modelos `tessdata_fast` (`jpn`, `jpn_vert`, `kor`, `chi_sim`, `por`, `eng`) sob demanda de forma assíncrona.
2. **Tradução Plugável (`TranslationProvider`)**:
   - Interface `TranslationProvider` com implementação padrão pronta para uso `GoogleTranslateGtxProvider` (OkHttp + `kotlinx-serialization-json`, sem API key, batching por página), além de `MyMemoryTranslationProvider`, `LibreTranslateProvider` e `CompositeTranslationProvider` com fallback.
3. **Renderização Google Lens & Controlador de Sessão**:
   - `LensOverlayRenderer`: pipeline não-destrutivo que mapeia caixas normalizadas para coordenadas de tela (`SINGLE`, `splitWidePages` com `splitWideRtl`, `DOUBLE_RTL`/`DOUBLE_LTR`, `WEBTOON`/`CONTINUOUS_VERTICAL`, zoom, `FitMode`), aplica desfoque (blur) restrito às sub-regiões de texto + tint adaptativo da borda e renderiza o texto traduzido via `Graphics2D` com quebra de linha, auto-fit de fonte e alto contraste — preservando 100% a imagem original quando desativado.
   - `PageTranslationController`: coordena fila assíncrona dedicada (1–2 workers, isolada do pool `io` de decodificação de páginas), cancelamento por `generation`/distância, e cache LRU de resultados integrado a `MemoryManager.registerTrimCallback` (respeitando o teto padrão de `500 MB` RAM).
4. **Integração UI, Atalhos e Configurações (`:desktop`)**:
   - `TranslatorPreferences`, `ReaderAction.TOGGLE_TRANSLATOR` (`T`), `ModernIcons`, `SettingsScreen` (seção Leitor) e `ReaderWindow` (`topHud`, `contextMenu`, pílula de status, disparo automático em `showSpread()` e `WebtoonView.updateVisible()`, composição em `PagedView.paintComponent` e `WebtoonView.paintComponent`, ciclo de vida seguro em `loadChapter()` e `togglingFullscreen`).

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | F1 Translator Models & Config | `NormalizedRect`, `TextOrientation`, `DetectedTextRegion`, `TranslatedBlock`, `PageTranslationResult`, `TranslationStatus`, `TranslatorConfig`, `TranslatorLanguage` | M1 | Survey 1, 2, 3 |
| 2 | F2 Pure-JVM Text Region Detector | `TextRegionDetector` para detecção de balões/regiões de texto e helpers de mapeamento/recorte normalizado | M1 | Survey 3 |
| 3 | F3 JNA Tesseract OCR & Tessdata | `TesseractJnaEngine` (`libtesseract.so.5.5` + fallback CLI + `Mutex`) e `TessdataManager` (resolução local + download assíncrono `tessdata_fast`) | M1 | Survey 3 |
| 4 | F4 Pluggable Translation Providers | `TranslationProvider`, `GoogleTranslateGtxProvider`, `MyMemoryTranslationProvider`, `LibreTranslateProvider`, `CompositeTranslationProvider` + testes `MockWebServer` | M1 | Survey 2, 3 |
| 5 | F5 Google Lens Blur & Overlay Renderer | `LensOverlayRenderer`: blur não-destrutivo em ROI, tint adaptativo, word-wrap + auto-fit vetorial, suporte a todos `ReadMode`/`FitMode`/`splitWidePages`/zoom | M2 | Survey 1, 3 |
| 6 | F6 Session Controller & Memory Cache | `PageTranslationController`: worker dedicado, cancelamento por geração, disparo on-page/scroll, cache LRU + `MemoryManager.registerTrimCallback` | M2 | Survey 1, 3 |
| 7 | F7 Translator Preferences | `TranslatorPreferences` persistindo idioma origem/destino, provedor, endpoint/key e estado padrão via `Preferences.userRoot()` | M2 | Survey 2 |
| 8 | F8 Shortcut & Vector Icon | `ReaderAction.TOGGLE_TRANSLATOR` (`VK_T`) em `ReaderShortcuts.kt` e ícone vetorial em `ModernIcons.kt` | M3 | Survey 1, 2 |
| 9 | F9 SettingsScreen Translator Section | Configurações na aba Leitor de `SettingsScreen.kt` (idiomas, provedor, URL/chave LibreTranslate, toggle padrão) | M3 | Survey 2 |
| 10 | F10 ReaderWindow Auto-Translate Integration | Botão `topHud`, menu de contexto, pílula de status, disparo automático em `showSpread`/`WebtoonView.updateVisible`, pintura em `PagedView`/`WebtoonView`, segurança em `loadChapter`/`togglingFullscreen` | M3 | Survey 1 |
| 11 | F11 E2E Test Suite (Tiers 1-4) | Suíte E2E opaque-box (`TEST_INFRA.md`, fixtures sintéticos de mangá, Tiers 1-4 cobrindo F1–F10, `TEST_READY.md`) | E2E | Dual Track |
| 12 | F12 Final Verification & Tier 5 Hardening | Passagem 100% em `./gradlew test :desktop:installDist`, verificação funcional no app empacotado e auditoria adversarial Tier 5 | FINAL | Acceptance |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Text Detection, OCR Engine & Translation Providers | Dependências Gradle (`jna:5.14.0`, `mockwebserver:4.12.0`), `TranslatorModels`, `TextRegionDetector`, `TesseractJnaEngine`, `TessdataManager`, `TranslationProvider` + testes unitários | none | COMPLETED |
| M2 | Google Lens Renderer, Session Controller & Preferences | `LensOverlayRenderer`, `PageTranslationController`, `TranslatorPreferences` + testes unitários completos de blur, mapeamento, disparo e memória | M1 | COMPLETED |
| M3 | ReaderWindow, Shortcuts, Icons & Settings Integration | `ModernIcons`, `ReaderShortcuts`, `SettingsScreen`, `ReaderWindow` + testes de integração do leitor | M1, M2 | COMPLETED |
| E2E | E2E Testing Suite Track | `TEST_INFRA.md`, fixtures sintéticos de mangá, suíte E2E Tiers 1-4 cobrindo F1–F10, publicação de `TEST_READY.md` | none (Phase A infra/fixtures in parallel; Phase B `.kt` suite after M3) | COMPLETED |
| FINAL | E2E Test Pass & Tier 5 Adversarial Hardening | Phase 1: 100% E2E Tiers 1-4 + verificação da distribuição `:desktop:installDist`; Phase 2: Tier 5 white-box adversarial hardening | M3, E2E | COMPLETED |

## Interface Contracts

### M1: Models, OCR & Translation (`desktop/src/main/kotlin/mihon/desktop/translator/`)
- `NormalizedRect`:
  ```kotlin
  data class NormalizedRect(val x: Float, val y: Float, val width: Float, val height: Float) {
      fun toPixelRect(imgWidth: Int, imgHeight: Int): java.awt.Rectangle
      fun clipToHalf(isSecondHalf: Boolean): NormalizedRect?
  }
  ```
- `DetectedTextRegion` & `TranslatedBlock` & `PageTranslationResult`:
  ```kotlin
  enum class TextOrientation { HORIZONTAL, VERTICAL }
  enum class TranslationStatus { IDLE, TRANSLATING, READY, ERROR }
  data class DetectedTextRegion(val bounds: NormalizedRect, val orientation: TextOrientation = TextOrientation.HORIZONTAL, val confidence: Float = 1f)
  data class TranslatedBlock(val bounds: NormalizedRect, val originalText: String, val translatedText: String, val orientation: TextOrientation = TextOrientation.HORIZONTAL, val confidence: Float = 1f)
  data class PageTranslationResult(val pageIndex: Int, val generation: Long, val sourceWidth: Int, val sourceHeight: Int, val blocks: List<TranslatedBlock>, val status: TranslationStatus, val errorMessage: String? = null)
  ```
- `OcrEngine` & `TextDetector` & `TranslationProvider`:
  ```kotlin
  interface TextDetector {
      fun detectRegions(image: java.awt.image.BufferedImage): List<DetectedTextRegion>
  }
  data class OcrSnippet(val text: String, val bounds: NormalizedRect, val confidence: Float)
  interface OcrEngine : AutoCloseable {
      fun isAvailable(): Boolean
      fun recognizePage(image: java.awt.image.BufferedImage, regions: List<DetectedTextRegion>, sourceLang: TranslatorLanguage): List<OcrSnippet>
  }
  interface TranslationProvider {
      val id: String
      val displayName: String
      suspend fun translateBatch(texts: List<String>, sourceLang: TranslatorLanguage, targetLang: TranslatorLanguage): List<String>
  }
  ```

### M2: Renderer, Controller & Preferences (`desktop/src/main/kotlin/mihon/desktop/translator/`)
- `TranslatorPreferences`:
  ```kotlin
  data class TranslatorConfig(
      val enabledByDefault: Boolean = false,
      val sourceLanguage: TranslatorLanguage = TranslatorLanguage.JAPANESE,
      val targetLanguage: TranslatorLanguage = TranslatorLanguage.PORTUGUESE_BR,
      val providerId: String = "google_gtx",
      val libreTranslateUrl: String = "https://libretranslate.com/translate",
      val libreTranslateApiKey: String = "",
      val autoDownloadTessdata: Boolean = true,
  )
  object TranslatorPreferences {
      fun load(): TranslatorConfig
      fun save(config: TranslatorConfig)
  }
  ```
- `LensOverlayRenderer`:
  ```kotlin
  object LensOverlayRenderer {
      fun renderOverlayOnGraphics(
          g2: java.awt.Graphics2D,
          sourceOrDisplayImage: java.awt.image.BufferedImage,
          destX: Int, destY: Int, destW: Int, destH: Int,
          result: PageTranslationResult,
          halfSlice: Boolean? = null, // null = full page, false = left half [0..0.5], true = right half [0.5..1]
      )
      fun renderPageWithOverlay(
          image: java.awt.image.BufferedImage,
          result: PageTranslationResult?,
          halfSlice: Boolean? = null,
      ): java.awt.image.BufferedImage
  }
  ```
- `PageTranslationController`:
  ```kotlin
  class PageTranslationController(
      private val textDetector: TextDetector,
      private val ocrEngine: OcrEngine,
      private val translationProvider: TranslationProvider,
      private val configProvider: () -> TranslatorConfig = { TranslatorPreferences.load() },
      private val isWebtoonProvider: () -> Boolean = { false },
      private val onPageResultReady: (PageTranslationResult) -> Unit = {},
      private val onStatusChanged: (TranslationStatus, String?) -> Unit = { _, _ -> },
  ) : AutoCloseable {
      var isEnabled: Boolean
      fun currentGeneration(): Long
      fun setEnabled(enabled: Boolean, visiblePages: List<Int>, pageImageProvider: suspend (Int) -> java.awt.image.BufferedImage?)
      fun onVisiblePagesChanged(visiblePages: List<Int>, pageImageProvider: suspend (Int) -> java.awt.image.BufferedImage?)
      fun onChapterChanged(newGeneration: Long)
      fun getCachedResult(pageIndex: Int): PageTranslationResult?
      fun trimMemory()
      override fun close()
  }
  ```

### M3: UI & Reader Integration (`desktop/src/main/kotlin/mihon/desktop/`)
- `ReaderAction.TOGGLE_TRANSLATOR` (`KeyEvent.VK_T`, `"Alternar tradutor simultâneo"`) em `ReaderShortcuts.kt`.
- `ModernIcons.translateIcon(...)` em `ModernIcons.kt`.
- `SettingsScreen.kt` (`buildReaderPage()`): seção "Tradutor Simultâneo (Google Lens)".
- `ReaderWindow.kt`:
  - Estado `translatorEnabled` + botão toggle no `topHud` + item no `contextMenu` + status pill no `centerLayered` + atalho `TOGGLE_TRANSLATOR`.
  - Disparo automático em `showSpread()` / `finish()` e `WebtoonView.updateVisible()` e ao ativar.
  - Composição não-destrutiva em `PagedView.paintComponent` e `WebtoonView.paintComponent` respeitando `DOUBLE_RTL` e `splitWidePages` (`splitWideRtl`).

## Code Layout
- `desktop/build.gradle.kts` — Dependências de `:desktop` (incluindo `net.java.dev.jna:jna:5.14.0` e `mockwebserver:4.12.0` para testes).
- `desktop/src/main/kotlin/mihon/desktop/` — UI Swing existente (`ReaderWindow.kt`, `ReaderShortcuts.kt`, `SettingsScreen.kt`, `ModernIcons.kt`, `MemoryManager.kt`, `Main.kt`, `AppContext.kt`).
- `desktop/src/main/kotlin/mihon/desktop/translator/` — Pipeline do tradutor simultâneo (`TranslatorModels.kt`, `TextRegionDetector.kt`, `TesseractJnaEngine.kt`, `TessdataManager.kt`, `TranslationProvider.kt`, `LensOverlayRenderer.kt`, `PageTranslationController.kt`, `TranslatorPreferences.kt`).
- `desktop/src/test/kotlin/mihon/desktop/` — Testes unitários existentes e de integração do leitor.
- `desktop/src/test/kotlin/mihon/desktop/translator/` — Testes unitários de M1 e M2.
- `desktop/src/test/kotlin/mihon/desktop/e2e/` — Suíte E2E opaque-box (Tiers 1-4).
- `desktop/src/test/resources/e2e/` — Fixtures sintéticos de páginas/arquivos `.cbz` para E2E.
- `core/archive/src/main/kotlin/mihon/core/archive/` — Leitura e decodificação de arquivos/imagens (sem alterações previstas).
- `domain/`, `data/`, `source-api/`, `core/common/`, `i18n/` — Módulos existentes preservados.
