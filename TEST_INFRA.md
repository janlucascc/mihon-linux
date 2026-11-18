# Test Infrastructure & E2E Specification — Mihon Linux Google Lens Simultaneous Translator

> **Scope**: Opaque-box End-to-End (E2E) test suite for Features `F1`–`F10` defined in `/home/janlucas/mihon-linux/PROJECT.md` and Requirements `R1`–`R2` in `.agents/teamwork/ORIGINAL_REQUEST.md`.
> **Status**: Phase A (Infrastructure Design & Synthetic Manga Fixtures)

---

## 1. Testing Philosophy & Methodology

This test suite is strictly **opaque-box, requirement-driven, and specification-derived**: tests interact exclusively through the public interface contracts documented in `PROJECT.md` § Interface Contracts, Swing UI components / keyboard actions, `ZipArchiveReader` / `ImageIO` inputs, `MockWebServer` HTTP endpoints, and the packaged binary distribution.

We combine four classical black-box test design techniques:

1. **Category-Partition Testing (Tier 1 — Core Functional)**: Partition every feature (`F1`–`F10`) into equivalence classes across valid inputs, reading modes, languages, translation providers, and lifecycle states, ensuring >= 5 primary cases per feature.
2. **Boundary-Value Analysis & Corner Cases (Tier 2 — Edge & Error Handling)**: Probe numeric limits (`0f`, `1f`, `width=0`, `ramLimitMb=128..4096`), empty/blank/dark/high-aspect pages, corrupted/HTML/500/429 HTTP responses, missing native/model files, stale `generation` races, and `Preferences` edge values (>= 5 cases per feature).
3. **Pairwise Combinatorial Testing (Tier 3 — Cross-Feature Interactions)**: Cover orthogonal 2-way combinations across `ReadMode` × `FitMode` × `splitWidePages` × `ColorFilterMode` × `zoomFactor` × `TranslationProvider` × `TranslatorPreferences` × `MemoryManager` (>= 10 cases).
4. **Real-World Workload Scenarios (Tier 4 — Full User Journeys)**: Execute multi-page, multi-chapter reading workflows end-to-end from `.cbz` archive decoding through detection, OCR, translation, blur/overlay rendering, scrolling/paging, fullscreen/chapter transitions, and packaged binary smoke checks (>= 5 scenarios).

### Authoritative Expected-Output Oracles

Every test derives its expected output from an independent authoritative source (never by mirroring implementation internals):

- **Geometric & Fixture Oracle (`fixtures_manifest.json`)**: Exact normalized `[0..1]` speech-bubble bounding boxes, ground-truth English/Japanese dialogue strings, and page dimensions embedded during synthetic fixture generation.
- **Native Tesseract C-API Oracle (`/lib64/libtesseract.so.5.5` + `/usr/share/tesseract/tessdata/eng.traineddata`)**: Real OCR output verified directly on English synthetic fixtures without mocking OCR.
- **Deterministic HTTP Wire Oracle (`okhttp3.mockwebserver.MockWebServer`)**: Explicit HTTP request/response contracts for `GoogleTranslateGtxProvider`, `MyMemoryTranslationProvider`, and `LibreTranslateProvider`, asserting exact translated strings, HTTP status codes, headers, and fallback transitions offline without internet flakiness.
- **Pixel / Luminance Diff Oracle**: Direct pixel inspection on `BufferedImage` before and after `LensOverlayRenderer`:
  - Pixels **outside** translated bounding boxes must remain 100% identical to the un-translated render (non-destructive guarantee).
  - Pixels **inside** original text bounding boxes must exhibit reduced high-frequency edge variance (blur) plus visible overlay contrast (translated text rendered cleanly, non-identical to original unblurred glyph stroke).
  - When translator mode is disabled (`result == null` or `isEnabled == false`), output pixels must equal the original page render bit-for-bit.
- **Persistence & Memory Contract Oracle**: `java.util.prefs.Preferences.userRoot()` round-trip state (with strict `@BeforeEach`/`@AfterEach` backup & restore) and `MemoryManager.trimMemoryIfNeeded(force = true)` cache eviction verification.

---

## 2. Test Architecture & Environment

### 2.1 Directory & Package Layout

| Artifact | Path | Purpose |
|---|---|---|
| E2E Kotlin Test Package | `desktop/src/test/kotlin/mihon/desktop/e2e/` | Phase B opaque-box E2E JUnit 5 suites (`T1`–`T4`) |
| Shared E2E Test Harness | `desktop/src/test/kotlin/mihon/desktop/e2e/E2eTestHarness.kt` | Fixture loader, `fixtures_manifest.json` parser, `MockWebServer` helpers, pixel diff/blur assertions, `Preferences` snapshot/restore guard |
| Synthetic Manga Fixtures | `desktop/src/test/resources/e2e/` | Compact (< 2 MB total) `.cbz` and `.png` pages + `fixtures_manifest.json` |
| Unit Tests (M1/M2/Existing) | `desktop/src/test/kotlin/mihon/desktop/translator/` & `mihon/desktop/` | Existing & milestone unit tests |

### 2.2 Synthetic Manga Fixtures (`desktop/src/test/resources/e2e/`)

All fixtures are generated with crisp vector/TrueType typography (`Liberation Sans Bold` for English, `Noto Sans CJK JP` for Japanese), dark comic panel borders, screentone/halftone textures, and white/dark speech bubbles, verified readable by `ZipArchiveReader`, `ImageIO`, and Tesseract OCR (`eng.traineddata`):

| Fixture File | Format & Dimensions | Contents & Purpose |
|---|---|---|
| `manga_en_chapter.cbz` | `.cbz` (4 PNGs: `001.png`..`004.png`, 800×1200 each) | 4-page English manga chapter with crisp white speech bubbles & halftone artwork (`"HELLO HERO!"`, `"WHERE ARE WE?"`, `"WATCH OUT!"`, `"THIS WAY!"`, `"STAY BACK!"`, `"WE MADE IT!"`). Verified directly against local `/usr/share/tesseract/tessdata/eng.traineddata` via `libtesseract.so.5.5`. |
| `manga_jp_chapter.cbz` | `.cbz` (3 PNGs: `001.png`..`003.png`, 800×1200 each) | 3-page Japanese manga chapter rendered in `Noto Sans CJK JP` containing both horizontal bubbles (`"こんにちは"`, `"大丈夫？"`) and vertical speech bubbles (`"助けて！"`, `"行こう！"`). Tests `TextOrientation.VERTICAL`/`HORIZONTAL`, CJK detection, and Japanese→PT-BR pipeline. |
| `manga_wide_double.png` | PNG (1600×1000) | Wide double-page spread with distinct speech bubbles on the left half (`"LEFT PANEL"`, `"HOLD ON!"`) and right half (`"RIGHT PANEL"`, `"ATTACK NOW!"`). Tests `splitWidePages` (`splitWideRtl = true/false`) and `NormalizedRect.clipToHalf`. |
| `manga_blank_and_dark.png` | PNG (800×1200) | Upper half: pure solid white + screentone artwork with **zero text**; lower half: dark charcoal panel (`#1E1E24`) with bright white dialogue (`"DARK PANEL TEXT"`). Tests zero-region boundary handling and light-on-dark adaptive contrast/tint. |
| `manga_webtoon_strip.png` | PNG (800×2400) | Tall vertical Webtoon strip with 3 vertically spaced speech bubbles (`"TOP BUBBLE"`, `"MIDDLE BUBBLE"`, `"BOTTOM BUBBLE"`). Tests `WEBTOON` / `CONTINUOUS_VERTICAL` viewport mapping and scroll-triggered translation. |
| `fixtures_manifest.json` | JSON | Authoritative manifest listing every fixture, image dimensions, expected normalized bubble bounding boxes `[x, y, width, height]`, `orientation`, expected source text, and reference PT-BR translation strings. |

### 2.3 Build, Test & Packaged App Verification Commands

- **Full unit + E2E test suite and packaged distribution build**:
  ```bash
  cd /home/janlucas/mihon-linux && ./gradlew test :desktop:installDist --console=plain
  ```
- **Run E2E suite only (Phase B)**:
  ```bash
  cd /home/janlucas/mihon-linux && ./gradlew :desktop:test --tests "mihon.desktop.e2e.*" --console=plain
  ```
- **Packaged application binary path**:
  ```
  /home/janlucas/mihon-linux/desktop/build/install/desktop/bin/desktop
  ```
  Launch/smoke verification runs the packaged binary with a clean temporary `HOME` / isolated test environment (or checks startup, library classpath including `jna`, Swing/FlatLaf initialization, and absence of uncaught exceptions / `UnsatisfiedLinkError` on exit).

### 2.4 Environment Constraints & Test Isolation Rules

1. **Linux Host Reality (Fedora 45)**:
   - Native library `/lib64/libtesseract.so.5.5` is present; unversioned `libtesseract.so` / `libtesseract.so.5` and CLI binary `tesseract` are **absent**. E2E tests for `F3` must verify real JNA probing succeeds on `libtesseract.so.5.5`.
   - Only `eng.traineddata` is pre-installed in `/usr/share/tesseract/tessdata/`. Offline E2E tests must run real OCR on `eng` fixtures and use `MockWebServer` / deterministic fake `OcrEngine` for CJK languages unless `jpn.traineddata` is cached locally in a test temp dir.
2. **Preferences & Filesystem Isolation**:
   - Every test touching `TranslatorPreferences`, `ReaderShortcuts`, `MemoryManager`, or `ReaderWindow` preferences **must** snapshot all keys in `@BeforeEach` and restore/delete test keys in `@AfterEach` (`try/finally`), never leaving dirty state in `Preferences.userRoot()`.
   - `TessdataManager` tests must redirect base/cache directory to `@TempDir` (never mutating user `~/.mihon/tessdata/`).
3. **Thread, Resource & Memory Hygiene**:
   - All `PageTranslationController`, `OcrEngine`, `ZipArchiveReader`, and `MockWebServer` instances created in tests must be closed in `@AfterEach` / `.use {}`.
   - Swing `ReaderWindow` / canvas tests must avoid blocking modal dialogs (`JOptionPane`) and dispose frames cleanly via `SwingUtilities.invokeAndWait` / `dispose()`.
4. **No Network Dependency in `./gradlew test`**:
   - All `TranslationProvider` and `TessdataManager` download tests must point to local `MockWebServer` URLs so the suite passes 100% deterministically offline.

---

## 3. Feature Inventory & E2E Test Matrix (F1–F10)

**Summary Counts**:
- **Tier 1 (Core Functional, F1–F10)**: `50` cases (`5` per feature)
- **Tier 2 (Boundary & Corner Cases, F1–F10)**: `50` cases (`5` per feature)
- **Tier 3 (Pairwise Cross-Feature Combinations)**: `12` cases
- **Tier 4 (Realistic End-to-End Scenarios)**: `6` scenarios
- **Total Planned E2E Cases**: **`118` cases** (exceeding the `>= 115` requirement)

---

### Tier 1 — Core Functional Partition Cases (50 Cases)

#### F1: Translator Models & Config (`T1-F1-01` .. `T1-F1-05`)
| ID | Title | Input & Setup | Expected Output / Oracle |
|---|---|---|---|
| `T1-F1-01` | `NormalizedRect.toPixelRect` scales fractional bounds accurately | `NormalizedRect(0.25f, 0.5f, 0.4f, 0.2f)`, `imgWidth=1000, imgHeight=2000` | `Rectangle(250, 1000, 400, 400)` exact match |
| `T1-F1-02` | `NormalizedRect.clipToHalf` left half (`isSecondHalf=false`) | `NormalizedRect(0.1f, 0.2f, 0.3f, 0.2f)` (strictly in left half) | Non-null `NormalizedRect(0.2f, 0.2f, 0.6f, 0.2f)` (±1e-4) renormalized to `[0..1]` |
| `T1-F1-03` | `NormalizedRect.clipToHalf` right half (`isSecondHalf=true`) | `NormalizedRect(0.6f, 0.3f, 0.2f, 0.2f)` (strictly in right half) | Non-null `NormalizedRect(0.2f, 0.3f, 0.4f, 0.2f)` (±1e-4) renormalized to `[0..1]` |
| `T1-F1-04` | `PageTranslationResult` & `TranslatedBlock` equality & status enums | Construct `PageTranslationResult` with `READY`, `HORIZONTAL`/`VERTICAL` blocks | Immutable data integrity, all 4 `TranslationStatus` states (`IDLE`, `TRANSLATING`, `READY`, `ERROR`) distinct |
| `T1-F1-05` | `TranslatorLanguage` & default `TranslatorConfig` values | Default `TranslatorConfig()` | `enabledByDefault == false`, `sourceLanguage == JAPANESE`, `targetLanguage == PORTUGUESE_BR`, `providerId == "google_gtx"`, `autoDownloadTessdata == true` |

#### F2: Pure-JVM Text Region Detector (`T1-F2-01` .. `T1-F2-05`)
| ID | Title | Input & Setup | Expected Output / Oracle |
|---|---|---|---|
| `T1-F2-01` | Detect single/multiple English speech bubbles in manga page | `manga_en_chapter.cbz` page `001.png` via `TextRegionDetector.detectRegions` | Detects >= 2 regions intersecting manifest bubbles (`IoU >= 0.3` or containment of text center) |
| `T1-F2-02` | Detect horizontal Japanese speech bubbles | `manga_jp_chapter.cbz` page `001.png` | Returns non-empty `DetectedTextRegion` list covering horizontal JP bubbles within `[0..1]` |
| `T1-F2-03` | Detect vertical Japanese speech bubbles with orientation | `manga_jp_chapter.cbz` page `002.png` (tall narrow vertical bubbles) | Detects vertical regions; bounding boxes match manifest tall aspect regions (`height > width`) |
| `T1-F2-04` | Detect separated regions on tall Webtoon strip | `manga_webtoon_strip.png` (800×2400) | Detects 3 distinct regions corresponding to top (`y~0.1`), middle (`y~0.45`), bottom (`y~0.8`) bubbles |
| `T1-F2-05` | Normalized coordinates invariant across image scale | Same page rendered at 100% and downscaled 50% | Detected normalized centroids remain within ±0.05 of manifest centroids |

#### F3: JNA Tesseract OCR & Tessdata (`T1-F3-01` .. `T1-F3-05`)
| ID | Title | Input & Setup | Expected Output / Oracle |
|---|---|---|---|
| `T1-F3-01` | JNA binds `/lib64/libtesseract.so.5.5` natively on Linux | Instantiate `TesseractJnaEngine` on Fedora host (`eng` tessdata) | `isAvailable() == true`, zero `UnsatisfiedLinkError` |
| `T1-F3-02` | Real OCR recognizes `"HELLO HERO!"` and `"WHERE ARE WE?"` | `manga_en_chapter.cbz` page `001.png` + manifest regions, `TranslatorLanguage.ENGLISH` | Returned `OcrSnippet` texts contain `"HELLO"`/`"HERO"` and `"WHERE"`/`"WE"` (case-insensitive) |
| `T1-F3-03` | Real OCR across all 4 pages of English CBZ chapter | Pages `001.png`..`004.png` decoded via `ZipArchiveReader` | Non-empty OCR matches key tokens on every page (`"WATCH OUT"`, `"THIS WAY"`, `"STAY BACK"`, `"MADE IT"`) |
| `T1-F3-04` | `TessdataManager` locates pre-installed `/usr/share/tesseract/tessdata/eng.traineddata` | Query `TessdataManager` for `TranslatorLanguage.ENGLISH` | Resolves existing `/usr/share/tesseract/tessdata` path without network download |
| `T1-F3-05` | `TessdataManager` downloads missing model from HTTP server to target dir | `MockWebServer` serving mock `.traineddata` bytes, isolated `@TempDir` | File saved under temp tessdata dir with exact byte content; `isModelAvailable` becomes `true` |

#### F4: Pluggable Translation Providers (`T1-F4-01` .. `T1-F4-05`)
| ID | Title | Input & Setup | Expected Output / Oracle |
|---|---|---|---|
| `T1-F4-01` | `GoogleTranslateGtxProvider` single & batch translation | `MockWebServer` returning GTX JSON array for `["HELLO HERO!", "WATCH OUT!"]` (`en`→`pt`) | Returns `["OLÁ HERÓI!", "CUIDADO!"]` in matching order; verifies `sl=en`, `tl=pt` query params |
| `T1-F4-02` | `MyMemoryTranslationProvider` translates text via JSON response | `MockWebServer` returning `{"responseData":{"translatedText":"Onde estamos?"},"responseStatus":200}` | Returns `["Onde estamos?"]`; verifies `langpair=en|pt` |
| `T1-F4-03` | `LibreTranslateProvider` sends POST JSON with optional API key | `MockWebServer` returning `{"translatedText":"Por aqui!"}` | Request method `POST`, JSON contains `q`, `source`, `target`, `api_key`; returns `["Por aqui!"]` |
| `T1-F4-04` | `CompositeTranslationProvider` returns primary provider result when healthy | Primary succeeds, secondary should not be called | Returns primary translations; secondary `MockWebServer` receives 0 requests |
| `T1-F4-05` | `CompositeTranslationProvider` falls back to secondary on primary failure | Primary returns HTTP 500, secondary returns HTTP 200 JSON | Succeeds transparently with secondary translations |

#### F5: Google Lens Blur & Overlay Renderer (`T1-F5-01` .. `T1-F5-05`)
| ID | Title | Input & Setup | Expected Output / Oracle |
|---|---|---|---|
| `T1-F5-01` | Non-destructive guarantee when `result == null` | `LensOverlayRenderer.renderPageWithOverlay(img, null)` | Output image pixels equal input `img` 100% bit-for-bit; input `img` unmutated |
| `T1-F5-02` | Blur & overlay strictly scoped to translated `bounds` | `manga_en_chapter.cbz` `001.png` with 2 `TranslatedBlock`s | Pixels outside padded `bounds` are 100% identical to original; pixels inside `bounds` differ and source pixels remain unmutated |
| `T1-F5-03` | Word-wrap & auto-fit renders long Portuguese translation inside narrow bubble | Narrow bubble `width=0.18f` with `"Cuidado com a armadilha escondida logo ali!"` | Completes without clipping crash; ink pixels appear inside target box across multiple lines |
| `T1-F5-04` | `halfSlice = false` / `halfSlice = true` maps wide spread halves correctly | `manga_wide_double.png` rendered with `halfSlice=false` vs `halfSlice=true` | Left-half render only overlays left bubbles; right-half render only overlays right bubbles |
| `T1-F5-05` | `renderOverlayOnGraphics` scales correctly under zoom/offset (`destX/Y/W/H`) | `Graphics2D` on 1200×1600 canvas with `destX=100, destY=50, destW=600, destH=900` | Overlay pixels strictly contained within `[100..700, 50..950]`, margins `[0..100)` untouched |

#### F6: Session Controller & Memory Cache (`T1-F6-01` .. `T1-F6-05`)
| ID | Title | Input & Setup | Expected Output / Oracle |
|---|---|---|---|
| `T1-F6-01` | Disabled controller triggers zero OCR/translation work | `setEnabled(false, ...)` + `onVisiblePagesChanged(listOf(0, 1))` | `OcrEngine` & `TranslationProvider` call counts == `0`; `getCachedResult(0) == null` |
| `T1-F6-02` | Enabling controller immediately translates visible pages | `setEnabled(true, listOf(0), imageProvider)` | `onPageResultReady` fires with `pageIndex=0`, `status=READY`, non-empty `blocks`; cached via `getCachedResult(0)` |
| `T1-F6-03` | `onVisiblePagesChanged` auto-translates newly visible spread `[2, 3]` | Controller enabled, navigate from `[0]` to `[2, 3]` | Both page `2` and page `3` are OCR'd/translated and cached |
| `T1-F6-04` | Repeated visit serves LRU cached result without duplicate OCR/API calls | Revisit `pageIndex=0` after initial translation | `getCachedResult(0)` returned immediately; provider call count stays 1 |
| `T1-F6-05` | `trimMemory()` clears cached results & obeys memory pressure | Populate cache for multiple pages, invoke `controller.trimMemory()` | Previously cached pages evicted (`getCachedResult(...) == null`) without throwing |

#### F7: Translator Preferences (`T1-F7-01` .. `T1-F7-05`)
| ID | Title | Input & Setup | Expected Output / Oracle |
|---|---|---|---|
| `T1-F7-01` | Default `TranslatorPreferences.load()` when keys unset | Clean `Preferences` node (snapshot & cleared) | Matches default `TranslatorConfig` (`enabledByDefault=false`, `JAPANESE`, `PORTUGUESE_BR`, `"google_gtx"`) |
| `T1-F7-02` | Round-trip save & load of all `TranslatorConfig` fields | Save custom config (`KOREAN`, `ENGLISH`, `"libretranslate"`, custom URL & key, `enabledByDefault=true`, `autoDownloadTessdata=false`) | `TranslatorPreferences.load()` equals saved `TranslatorConfig` field-for-field |
| `T1-F7-03` | Round-trip every `TranslatorLanguage` enum constant | Save & load each supported `TranslatorLanguage` | Exact enum preserved across reload |
| `T1-F7-04` | Round-trip every `providerId` (`"google_gtx"`, `"mymemory"`, `"libretranslate"`) | Save & load each `providerId` | Exact `providerId` persisted and restored |
| `T1-F7-05` | Overwriting existing config updates only latest values cleanly | Save config A, then save config B | `load()` returns config B with no stale fields from A |

#### F8: Shortcut & Vector Icon (`T1-F8-01` .. `T1-F8-05`)
| ID | Title | Input & Setup | Expected Output / Oracle |
|---|---|---|---|
| `T1-F8-01` | `ReaderAction.TOGGLE_TRANSLATOR` exists in `ReaderAction.entries` | Inspect `ReaderAction.TOGGLE_TRANSLATOR` | Enum present, default key == `KeyEvent.VK_T`, label non-blank |
| `T1-F8-02` | `ReaderAction.TOGGLE_TRANSLATOR.matches(KeyEvent)` triggers on `T` key | Synthetic `KeyEvent(..., VK_T)` | `matches` returns `true` for `VK_T` and `false` for `VK_H`, `VK_A`, `VK_B` |
| `T1-F8-03` | Custom rebinding & reset of `TOGGLE_TRANSLATOR` shortcut | Rebind to `VK_G`, verify persistence, reset to default | Matches `VK_G` while customized; reverts to `VK_T` on reset |
| `T1-F8-04` | `ModernIcons.translateIcon(...)` renders visible non-empty vector icon | Paint `ModernIcons.translateIcon(18, Color.WHITE)` onto 18×18 ARGB `BufferedImage` | Non-zero alpha/color pixels drawn; `iconWidth == 18`, `iconHeight == 18` |
| `T1-F8-05` | `ModernIcons.translateIcon(...)` respects distinct sizes & tint colors | Paint at sizes `16` and `24` with distinct colors (`Color.CYAN`, `Color.ORANGE`) | Rendered pixel bounding sizes scale and dominant RGB matches requested tint |

#### F9: SettingsScreen Translator Section (`T1-F9-01` .. `T1-F9-05`)
| ID | Title | Input & Setup | Expected Output / Oracle |
|---|---|---|---|
| `T1-F9-01` | Reader settings page displays `"Tradutor Simultâneo (Google Lens)"` section | Instantiate `AntiGravitySettingsScreen` / `buildReaderPage()` on EDT | Component tree contains translator section header & controls |
| `T1-F9-02` | Shortcut customization list includes `TOGGLE_TRANSLATOR` | Inspect `"reader"` settings page components | Label `"Alternar tradutor simultâneo"` (or `TOGGLE_TRANSLATOR.label`) and key badge `"T"` present |
| `T1-F9-03` | Changing source/target language control updates `TranslatorPreferences` | Programmatically select `ENGLISH` source & `PORTUGUESE_BR` target in UI control | `TranslatorPreferences.load()` reflects updated languages immediately |
| `T1-F9-04` | Changing translation provider selector persists `providerId` | Select `"mymemory"` / `"libretranslate"` in provider selector | `TranslatorPreferences.load().providerId` matches selection |
| `T1-F9-05` | Toggling `"Ativar tradutor por padrão"` switch updates `enabledByDefault` | Toggle `M3Switch` in settings UI | `TranslatorPreferences.load().enabledByDefault` toggles accordingly |

#### F10: ReaderWindow Auto-Translate Integration (`T1-F10-01` .. `T1-F10-05`)
| ID | Title | Input & Setup | Expected Output / Oracle |
|---|---|---|---|
| `T1-F10-01` | `ReaderWindow` exposes translator toggle button in `topHud` & menu item in `contextMenu` | Open `ReaderWindow` with `manga_en_chapter.cbz` (offscreen/non-blocking) | `topHud` contains translator toggle button (`isFocusable == false`) and `contextMenu` contains translator item |
| `T1-F10-02` | Pressing `T` (`TOGGLE_TRANSLATOR`) toggles translator state & shows feedback | Dispatch `VK_T` key event to `ReaderWindow` | `translatorEnabled` flips `false -> true -> false` and status pill/toast updates |
| `T1-F10-03` | Disabling translator renders 100% original page without blur/overlay | Render `PagedView` with `translatorEnabled=false` | Painted output matches unmodified page render; controller does not translate |
| `T1-F10-04` | Enabling translator auto-translates visible page on `showSpread()` / page next | Enable translator, advance `next()` from page 0 to page 1 | Page 1 triggers translation automatically and displays overlay when ready |
| `T1-F10-05` | `WebtoonView` auto-translates visible pages when scrolled with translator enabled | Switch `ReadMode.WEBTOON` on `manga_webtoon_strip.png` / CBZ, scroll viewport | `WebtoonView.updateVisible()` triggers translation for visible pages and paints overlay |

---

### Tier 2 — Boundary-Value Analysis & Corner Cases (50 Cases)

#### F1 Boundaries (`T2-F1-01` .. `T2-F1-05`)
| ID | Title | Boundary / Corner Condition | Expected Output / Oracle |
|---|---|---|---|
| `T2-F1-01` | `toPixelRect` clamps out-of-range coordinates (`< 0f`, `> 1f`) | `NormalizedRect(-0.2f, 1.2f, 1.5f, 0.5f)`, `1000×1000` | Clamped safely inside `[0..1000, 0..1000]`, non-negative width/height |
| `T2-F1-02` | `toPixelRect` on zero or 1×1 image dimensions | `imgWidth=1, imgHeight=1` and `imgWidth=0, imgHeight=0` | Zero-dimension returns empty rect (`w=0, h=0`) without `IllegalArgumentException` |
| `T2-F1-03` | `clipToHalf` returns `null` when rect is entirely on opposite half | Rect `[0.6f..0.9f]` with `isSecondHalf=false`; rect `[0.1f..0.4f]` with `isSecondHalf=true` | Both return `null` |
| `T2-F1-04` | `clipToHalf` splits box straddling the 0.5 centerline | `NormalizedRect(0.4f, 0.2f, 0.2f, 0.2f)` (`0.4..0.6`) | Left half clips to `[0.8f..1.0f]` (`width~0.2f`); right half clips to `[0.0f..0.2f]` (`width~0.2f`) |
| `T2-F1-05` | `clipToHalf` exact boundary at `x = 0.5f` (`width = 0f` overlap) | `NormalizedRect(0.5f, 0.2f, 0.2f, 0.2f)` with `isSecondHalf=false` | Returns `null` (zero overlap width) |

#### F2 Boundaries (`T2-F2-01` .. `T2-F2-05`)
| ID | Title | Boundary / Corner Condition | Expected Output / Oracle |
|---|---|---|---|
| `T2-F2-01` | Pure blank white image yields zero regions | 800×1200 solid white `BufferedImage` | Empty list (`emptyList()`), zero false positives |
| `T2-F2-02` | Pure solid black image yields zero regions | 800×1200 solid black `BufferedImage` | Empty list, zero exceptions |
| `T2-F2-03` | Tiny 1×1 and 8×8 images handled gracefully | 1×1 and 8×8 `BufferedImage` | Empty list, no division by zero or `RasterFormatException` |
| `T2-F2-04` | Blank top + dark panel light text fixture | `manga_blank_and_dark.png` | Top blank half produces 0 text blocks; bottom dark panel bubble detected or fallback-handled without crash |
| `T2-F2-05` | Extreme aspect ratio strip (100×4000) & non-ARGB (`TYPE_BYTE_GRAY`) image | Grayscale `TYPE_BYTE_GRAY` 100×4000 strip | Completes within budget with valid `[0..1]` normalized bounds |

#### F3 Boundaries (`T2-F3-01` .. `T2-F3-05`)
| ID | Title | Boundary / Corner Condition | Expected Output / Oracle |
|---|---|---|---|
| `T2-F3-01` | Empty `regions` list passed to `recognizePage` | `regions = emptyList()` | Returns `emptyList()` (or safe fallback) without crashing native API |
| `T2-F3-02` | Degenerate zero-area or edge-touching `NormalizedRect` | `NormalizedRect(0.999f, 0.999f, 0.0001f, 0.0001f)` | Safely skipped or clamped without Leptonica native crash |
| `T2-F3-03` | Concurrent calls to `TesseractJnaEngine` from 4 coroutines | 4 parallel coroutines calling `recognizePage` simultaneously | `Mutex` serializes native access; all 4 complete with 0 segfaults / 0 corrupted strings |
| `T2-F3-04` | `TessdataManager` handles HTTP 404 / truncated download atomically | `MockWebServer` returns HTTP 404 / connection drop mid-stream | No corrupt `.traineddata` file left at final destination path; returns `false` / clean failure |
| `T2-F3-05` | `TesseractJnaEngine.close()` is idempotent & subsequent calls safe | Call `close()` twice, then check `isAvailable()` / `recognizePage()` | No double-free native crash; throws `IllegalStateException` or returns empty cleanly |

#### F4 Boundaries (`T2-F4-01` .. `T2-F4-05`)
| ID | Title | Boundary / Corner Condition | Expected Output / Oracle |
|---|---|---|---|
| `T2-F4-01` | Empty input list `emptyList()` | `translateBatch(emptyList(), ENGLISH, PORTUGUESE_BR)` | Returns `emptyList()` immediately with 0 HTTP requests sent |
| `T2-F4-02` | Whitespace-only & special characters (`"...\n!@#&?="`, `"<html>&amp;"`) | Strings with newlines, URL meta-chars, quotes, emojis | Returned list preserves size and properly URL/JSON-escapes characters |
| `T2-F4-03` | HTTP 429 Rate Limit / 503 / HTML error page response | `MockWebServer` returns HTTP 429 or `200 text/html` `"<html>Error</html>"` | Throws typed exception / returns failure without `JsonDecodingException` crash; triggers fallback in `CompositeTranslationProvider` |
| `T2-F4-04` | Source language == Target language short-circuit | `translateBatch(["HELLO"], ENGLISH, ENGLISH)` | Returns original `["HELLO"]` cleanly without corrupting text |
| `T2-F4-05` | Large batch (> 25 blocks) splitting / delimiter preservation | 30 distinct strings `"Line 0"`..`"Line 29"` | Returned list has exact `size == 30` with 1-to-1 positional alignment |

#### F5 Boundaries (`T2-F5-01` .. `T2-F5-05`)
| ID | Title | Boundary / Corner Condition | Expected Output / Oracle |
|---|---|---|---|
| `T2-F5-01` | Empty `blocks` list in `PageTranslationResult` | `PageTranslationResult(..., blocks = emptyList(), status = READY)` | Rendered image pixels match original 100% (no spurious blur patches) |
| `T2-F5-02` | `status = TRANSLATING` or `ERROR` with no blocks | Render while status is `TRANSLATING` or `ERROR` | Image remains intact & uncorrupted |
| `T2-F5-03` | Overlapping & edge-touching bounding boxes | Two overlapping `TranslatedBlock`s touching `(0f, 0f)` and `(1f, 1f)` | Clamped to image bounds without `RasterFormatException` / `ArrayIndexOutOfBoundsException` |
| `T2-F5-04` | Dark background bubble renders high-contrast light text | `manga_blank_and_dark.png` bottom dark panel (`#1E1E24`) | Overlay text color luminance contrasts strongly against dark blurred background (contrast ratio >= 3.0) |
| `T2-F5-05` | Very tiny bounding box (`0.02f × 0.02f`) & empty `translatedText` | Blank `translatedText = ""` and tiny 16×24 box | Skips blank block cleanly; tiny box clamps minimum readable font size without negative coordinates |

#### F6 Boundaries (`T2-F6-01` .. `T2-F6-05`)
| ID | Title | Boundary / Corner Condition | Expected Output / Oracle |
|---|---|---|---|
| `T2-F6-01` | Stale `generation` result discarded after `onChapterChanged` | Slow OCR/translation finishes after `onChapterChanged(generation + 1)` | Old result NOT emitted to `onPageResultReady` and NOT stored in cache |
| `T2-F6-02` | Rapid page scrubbing cancels distant in-flight page jobs | Scrub visible pages `0 -> 1 -> 2 -> ... -> 10` rapidly | Page 10 completes cleanly; superseded intermediate jobs do not overwrite active status |
| `T2-F6-03` | `pageImageProvider` returns `null` or throws exception | `pageImageProvider` returns `null` on page 0 | Controller transitions cleanly out of `TRANSLATING` without crashing worker loop |
| `T2-F6-04` | `OcrEngine.isAvailable() == false` or `TranslationProvider` throws | Provider throws `IOException("timeout")` | `onStatusChanged` receives `TranslationStatus.ERROR` with non-blank message; controller remains reusable |
| `T2-F6-05` | `close()` during active translation cancels coroutine scope & unregisters trim callback | Start slow job, call `controller.close()` | Cancels pending work within bounded timeout; subsequent calls are no-op |

#### F7 Boundaries (`T2-F7-01` .. `T2-F7-05`)
| ID | Title | Boundary / Corner Condition | Expected Output / Oracle |
|---|---|---|---|
| `T2-F7-01` | Corrupt/unknown `sourceLanguage` string in raw `Preferences` | Write `"INVALID_LANG"` directly to backing `Preferences` node | `TranslatorPreferences.load()` falls back safely to default `JAPANESE` without throwing |
| `T2-F7-02` | Corrupt/unknown `providerId` or blank `libreTranslateUrl` | Write blank `libreTranslateUrl` or unknown `providerId` | Safe fallback or clean config load without `NullPointerException` |
| `T2-F7-03` | API key containing special chars (`=`, `&`, quotes, UTF-8) | Save `libreTranslateApiKey = "k=1&x=\"abc\"_çãõ"` | Exact round-trip equality |
| `T2-F7-04` | Concurrent `load()` / `save()` calls | 4 threads calling `load()`/`save()` | Zero `ConcurrentModificationException` or corrupted config state |
| `T2-F7-05` | `Preferences` node cleared externally mid-session | Clear backing node, call `load()` | Cleanly returns valid default `TranslatorConfig` |

#### F8 Boundaries (`T2-F8-01` .. `T2-F8-05`)
| ID | Title | Boundary / Corner Condition | Expected Output / Oracle |
|---|---|---|---|
| `T2-F8-01` | Shortcut does NOT fire when modifier mismatch (`Ctrl+T` vs `T`) | KeyEvent with `CTRL_DOWN_MASK` vs no modifiers | `TOGGLE_TRANSLATOR.matches` distinguishes modified vs unmodified key properly |
| `T2-F8-02` | Conflict check with all existing `ReaderAction` default keys | Compare `TOGGLE_TRANSLATOR` default key against all other `ReaderAction`s | Zero duplicate default key codes across `ReaderAction.entries` |
| `T2-F8-03` | Corrupt/invalid keycode stored in shortcuts `Preferences` | Store `-999` for `TOGGLE_TRANSLATOR` in shortcut preferences | Safely resolves or matches cleanly without crash |
| `T2-F8-04` | `ModernIcons.translateIcon(size = 1, ...)` minimum icon size | Size `1` and size `0` (or smallest valid size `8`) | Paints without `IllegalArgumentException` or division-by-zero |
| `T2-F8-05` | `ModernIcons.translateIcon` with semi-transparent color (`alpha = 128`) | `Color(255, 255, 255, 128)` | Drawn pixels never exceed alpha `128` (respects tint alpha) |

#### F9 Boundaries (`T2-F9-01` .. `T2-F9-05`)
| ID | Title | Boundary / Corner Condition | Expected Output / Oracle |
|---|---|---|---|
| `T2-F9-01` | Entering empty LibreTranslate URL does not crash UI or prefs | Empty input in URL field / dialog | Handled gracefully (validation or fallback) |
| `T2-F9-02` | Selecting `SOURCE == TARGET` in SettingsScreen | Pick same source & target language | Persists cleanly without UI freeze or exception |
| `T2-F9-03` | Re-opening SettingsScreen reflects externally saved `TranslatorPreferences` | Save config via API, construct fresh `AntiGravitySettingsScreen` | UI widgets initialize to match persisted `TranslatorConfig` |
| `T2-F9-04` | Switching away and back to `"reader"` card preserves translator widget state | Switch CardLayout `"general" -> "reader" -> "appearance" -> "reader"` | Selections remain intact |
| `T2-F9-05` | Shortcut conflict badge/handling when rebinding `TOGGLE_TRANSLATOR` | Rebind via settings state | Persisted shortcut reflects change across UI rebuild |

#### F10 Boundaries (`T2-F10-01` .. `T2-F10-05`)
| ID | Title | Boundary / Corner Condition | Expected Output / Oracle |
|---|---|---|---|
| `T2-F10-01` | `loadChapter()` resets page translation cache while preserving `translatorEnabled` | Enable translator on Chapter 1, call `loadChapter(chapter2)` | `translatorEnabled` remains `true`; Chapter 1 blocks never bleed onto Chapter 2 pages; Chapter 2 page 0 translates |
| `T2-F10-02` | `toggleFullscreen()` preserves translator state & active controller | Enable translator, invoke fullscreen toggle (`togglingFullscreen = true`) | Translator stays enabled and continues functioning after fullscreen transition |
| `T2-F10-03` | Clicking translator toggle on 1-page archive (`pageCount == 1`) | Archive with 1 PNG page, press `T` and try `next()`/`prev()` | No out-of-bounds crash; page 0 translates and renders cleanly |
| `T2-F10-04` | Rapid toggle ON → OFF → ON during active `TRANSLATING` state | Toggle `VK_T` 3 times before async job finishes | Final state matches odd toggle count (ON); when toggled OFF mid-flight, no stale overlay appears |
| `T2-F10-05` | `windowClosed()` disposes controller & unregisters `MemoryManager` trim callback | Close `ReaderWindow` (`togglingFullscreen = false`) | Controller closed cleanly, background worker stopped, `MemoryManager` trim callback unregistered |

---

### Tier 3 — Pairwise Feature Combinations (12 Cases)

| ID | Pairwise Features Combined | Test Description & Setup | Expected Output / Oracle |
|---|---|---|---|
| `T3-01` | `F5 × F10` (`DOUBLE_RTL` + `PagedView`) | 2-page spread `[0, 1]` in `ReadMode.DOUBLE_RTL` (right page = `0`, left page = `1`) | Page `0` blocks appear ONLY on right half of canvas; page `1` blocks ONLY on left half |
| `T3-02` | `F5 × F10` (`DOUBLE_LTR` + `PagedView`) | 2-page spread `[0, 1]` in `ReadMode.DOUBLE_LTR` (left page = `0`, right page = `1`) | Page `0` blocks appear ONLY on left half; page `1` blocks ONLY on right half |
| `T3-03` | `F1 × F5 × F10` (`SINGLE` + `splitWidePages` + `splitWideRtl=true/false`) | `manga_wide_double.png` in `SINGLE` mode with `splitWidePages=true` | `splitWideRtl=true` shows right half on subpage 0 and left half on subpage 1 with exact matching half-overlays; `false` flips order |
| `T3-04` | `F5 × F10` (`FitMode` = `WINDOW` / `WIDTH` / `ORIGINAL` + `zoomFactor = 2.0`) | Render translated page across all 3 `FitMode`s and 200% zoom | Overlay boxes scale proportionally with `destW`/`destH`, zero misalignment outside page rect |
| `T3-05` | `F5 × F10` (`ColorFilterMode.INVERTED` / `GRAYSCALE` / `SEPIA` + Overlay) | Apply `ColorFilterMode.INVERTED` on page with active `LensOverlayRenderer` | Page artwork is inverted/filtered, while translated overlay remains readable and legible |
| `T3-06` | `F2 × F3 × F4` (Real Detector + Real JNA Tesseract `eng` + `MockWebServer` GTX) | Run full `F2 -> F3 -> F4` pipeline on `manga_en_chapter.cbz` `001.png` | Real JNA OCR reads English bubbles, GTX mock translates to PT-BR, `TranslatedBlock`s match manifest |
| `T3-07` | `F4 × F6` (`CompositeTranslationProvider` fallback + `PageTranslationController`) | Primary provider returns HTTP 503; secondary returns valid PT-BR | Controller completes with `READY` status and caches fallback translations |
| `T3-08` | `F6 × MemoryManager` (Low RAM limit `128 MB` + trim pressure) | Set `MemoryManager` low limit, translate 6 pages, call `trimMemoryIfNeeded(force=true)` | Cache shrinks via `trimCallback` cleanly; no `OutOfMemoryError` |
| `T3-09` | `F7 × F9` (`SettingsScreen` updates → `TranslatorPreferences` → fresh `ReaderWindow`) | Change `enabledByDefault=true`, `sourceLang=ENGLISH`, `provider="mymemory"` in Settings UI, open `ReaderWindow` | `ReaderWindow` initializes with translator ON and uses `ENGLISH` + `"mymemory"` |
| `T3-10` | `F8 × F10` (Custom rebound shortcut key `VK_G` in `ReaderWindow`) | Rebind `TOGGLE_TRANSLATOR` to `VK_G` via `ReaderShortcuts`, press `VK_G` then `VK_T` in `ReaderWindow` | `VK_G` toggles translator; old `VK_T` no longer toggles |
| `T3-11` | `F5 × F6 × F10` (`WEBTOON` + `CONTINUOUS_VERTICAL` scroll across 3-page CBZ) | Open 3-page CBZ in `WEBTOON` and `CONTINUOUS_VERTICAL`, scroll from top to page 2 | Visible pages translate automatically; scrolled-in page 2 renders translated overlays aligned to `tops[2]` |
| `T3-12` | `F1 × F3 × F5` (Vertical Japanese `TextOrientation.VERTICAL` end-to-end) | `manga_jp_chapter.cbz` `002.png` with fake/local CJK OCR + GTX mock → `LensOverlayRenderer` | Vertical Japanese bubbles blurred & overwritten with horizontally readable PT-BR text centered cleanly in vertical bubble |

---

### Tier 4 — Realistic Manga Reading Scenarios (6 Scenarios)

| ID | Scenario Name | End-to-End Flow | Acceptance Oracle |
|---|---|---|---|
| `T4-01` | **First-Time English Manga Reading Session (CBZ → Lens Overlay → Toggle Off)** | Open `manga_en_chapter.cbz` via `ZipArchiveReader` in `ReaderWindow` (`SINGLE`), toggle translator ON (`VK_T`), read pages `0 -> 1 -> 2 -> 3` with real `TextRegionDetector` + real `TesseractJnaEngine` (`eng`) + `MockWebServer` GTX, verify blur + PT-BR overlay on each page, then toggle OFF (`VK_T`). | Every page auto-translates on navigation; original text pixels are blurred, PT-BR overlay rendered inside bubble regions, outside pixels untouched; toggling OFF immediately restores 100% unmodified original page render. |
| `T4-02` | **Japanese Chapter Reading with Vertical & Horizontal Bubbles + Chapter Transition** | Open `manga_jp_chapter.cbz` in `DOUBLE_RTL` with translator ON, navigate spreads, then call `loadChapter(manga_en_chapter.cbz)` switching config to `ENGLISH`. | Spread 1 renders RTL-ordered overlays; chapter switch clears old chapter translations, keeps translator enabled, and auto-translates new chapter page 0 without bleed-through. |
| `T4-03` | **Webtoon Long-Strip Continuous Scrolling Session** | Open `manga_webtoon_strip.png` (800×2400) + multi-page chapter in `ReadMode.WEBTOON`, enable translator, scroll incrementally from top to bottom. | `WebtoonView.updateVisible()` triggers translation for visible strip/pages; top, middle, and bottom speech bubbles render crisp overlays aligned at scrolled Y offsets. |
| `T4-04` | **Wide Double-Page Spread Split Reading (`splitWidePages`)** | Open `manga_wide_double.png` (1600×1000) in `SINGLE` mode with `splitWidePages = true`, enable translator, view subpage 0 and advance `next()` to subpage 1 (both `splitWideRtl = true` and `false`). | Subpage 0 blurs & translates only its half's bubbles (`LEFT PANEL` or `RIGHT PANEL` depending on RTL); subpage 1 displays the opposite half's blurred & translated bubbles. |
| `T4-05` | **High-Volume Memory-Constrained Navigation (500 MB Budget Stress)** | Open 20-page synthetic CBZ chapter under `MemoryManager` limit, rapidly navigate/scrub across all pages with translator active, trigger `MemoryManager.trimMemoryIfNeeded(force = true)`, and inspect JVM heap delta. | Zero OOM / native crash; stale page jobs cancelled; heap growth stays well within `MemoryManager` budget (`< 150 MB` delta) and trim callback frees cached entries. |
| `T4-06` | **Packaged Distribution Binary Smoke & Launch Verification (`:desktop:installDist`)** | Execute `/home/janlucas/mihon-linux/desktop/build/install/desktop/bin/desktop` with isolated temp `HOME` / environment, verifying JNA native loading (`libtesseract.so.5.5`), Swing/FlatLaf startup without uncaught exceptions or `UnsatisfiedLinkError`, and clean exit/cleanup. | Packaged binary launches cleanly with all required JARs (`jna-5.14.0`, `okhttp`, `flatlaf`, etc.) and zero missing-class or native linking errors. |

---

## 4. Phase B Implementation Roadmap

When Milestones M1, M2, and M3 are complete:
1. Create `E2eTestHarness.kt` under `desktop/src/test/kotlin/mihon/desktop/e2e/` providing manifest deserialization, `Preferences` snapshot/restore JUnit5 extensions, pixel-diff / blur-variance / luminance-contrast assertions, and `MockWebServer` fixture dispatchers.
2. Implement E2E test classes grouped cleanly by tier/feature:
   - `TranslatorCoreE2eTest.kt` (`T1-F1`..`T1-F4`, `T2-F1`..`T2-F4`)
   - `TranslatorRendererAndControllerE2eTest.kt` (`T1-F5`..`T1-F7`, `T2-F5`..`T2-F7`)
   - `ReaderTranslatorUiE2eTest.kt` (`T1-F8`..`T1-F10`, `T2-F8`..`T2-F10`)
   - `TranslatorPairwiseAndWorkloadsE2eTest.kt` (`T3-01`..`T3-12`, `T4-01`..`T4-06`)
3. Run `./gradlew test :desktop:installDist --console=plain` and verify 100% pass rate and packaged app verification before publishing `TEST_READY.md`.
