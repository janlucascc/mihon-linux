# Project: Mihon Linux Core Features Implementation

## Architecture
Mihon Linux is a Kotlin 2.4 / Java 21 desktop application for Linux providing a desktop port of Tachiyomi/Mihon. It uses a modular multi-project Gradle layout with Swing frontend and Exposed/SQLite persistence:

- `:domain` — Pure Kotlin domain models (`Manga`, `Chapter`, `History`) and repository interfaces (`MangaRepository`, `ChapterRepository`, `HistoryRepository`).
- `:core:archive` — Archive extraction and streaming engine (`ArchiveReader`, `ZipArchiveReader`, `DirectoryArchiveReader`, `NaturalOrderComparator`) supporting `.cbz`, `.zip`, and local image directories.
- `:core:common` — Cross-cutting utilities, network models, language/sorting extensions.
- `:source-api` — Manga catalog and HTTP source abstractions (`CatalogueSource`, `HttpSource`, `ParsedHttpSource`, `Page`, `FilterList`, `JsoupExtensions`) and concrete scraping implementations using OkHttp and jsoup.
- `:data` — Database persistence via JetBrains Exposed DSL and SQLite (`~/.mihon/mihon.db`), table schemas (`MangaTable`, `ChapterTable`, `HistoryTable`), and repository implementations.
- `:desktop` — Java Swing desktop user interface (`MihonApp`, `MangaGridPanel`, `MangaDetailsPanel`, `ChapterListPanel`, `ReaderWindow`, `CoverCache`, async `SwingWorker` thread scheduling).

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | F2.1 Exposed JDBC Dependency | Add `exposed-jdbc:0.48.0` to `data/build.gradle.kts` | M1 | Survey Exp 2 |
| 2 | F2.2 Manga Domain Entity | Create `Manga` model with timestamps, flags, and `UpdateStrategy` in `:domain` | M1 | Survey Exp 2 |
| 3 | F2.3 MangaRepository Interface | Define `MangaRepository` CRUD and flow queries in `:domain` | M1 | Survey Exp 2 |
| 4 | F2.4 HistoryRepository Interface | Define `HistoryRepository` CRUD and query contracts in `:domain` | M1 | Survey Exp 2 |
| 5 | F2.5 Exposed Table Schemas | Implement `MangaTable`, `ChapterTable`, `HistoryTable` in `:data` | M1 | Survey Exp 2 |
| 6 | F2.6 DatabaseManager Refactoring | Add Exposed database connection, WAL mode, foreign keys, legacy migration | M1 | Survey Exp 2 |
| 7 | F2.7 MangaRepositoryImpl | Implement `MangaRepository` via Exposed in `:data` | M1 | Survey Exp 2 |
| 8 | F2.8 ChapterRepositoryImpl | Implement all 12 `ChapterRepository` methods via Exposed in `:data` | M1 | Survey Exp 2 |
| 9 | F2.9 HistoryRepositoryImpl | Implement `HistoryRepository` via Exposed in `:data` | M1 | Survey Exp 2 |
| 10 | F2.10 Persistence Test Setup | Add JUnit 5 and coroutines-test to `:data` and `:domain` | M1 | Survey Exp 2 |
| 11 | F2.11 Persistence CRUD Tests | Automated integration test suite with isolated SQLite database | M1 | Survey Exp 2 |
| 12 | F1.1 ArchiveReader Core Abstraction | `ArchiveReader` interface and `ArchivePage` data class in `:core:archive` | M2 | Survey Exp 1 |
| 13 | F1.2 ZipArchiveReader | High-performance stream-based `.cbz` and `.zip` reader in `:core:archive` | M2 | Survey Exp 1 |
| 14 | F1.3 DirectoryArchiveReader | Local filesystem directory image reader in `:core:archive` | M2 | Survey Exp 1 |
| 15 | F1.4 NaturalOrderComparator | Alphanumeric comparator ordering numbers naturally (`page 2` < `page 10`) | M2 | Survey Exp 1 |
| 16 | F1.5 File Filtering & Sanitation | Filters out non-images, macOS resource forks (`__MACOSX`), and OS metadata | M2 | Survey Exp 1 |
| 17 | F1.6 ArchiveReaderFactory | Factory instantiating `ZipArchiveReader` or `DirectoryArchiveReader` by file | M2 | Survey Exp 1 |
| 18 | F1.7 Synthetic Archive Test Fixtures | Programmatic test archive generator & unit tests in `:core:archive` | M2 | Survey Exp 1 |
| 19 | R3-F1 Page Model | `Page` data class in `:source-api` | M3 | Survey Exp 3 |
| 20 | R3-F2 FilterList Collection | `FilterList` wrapping list of filters in `:source-api` | M3 | Survey Exp 3 |
| 21 | R3-F3 JsoupExtensions | `Response.asJsoup()` helper using OkHttp Response and Jsoup parser | M3 | Survey Exp 3 |
| 22 | R3-F4 CatalogueSource Interface | Core catalog contract for search, popular, details, chapters, pages | M3 | Survey Exp 3 |
| 23 | R3-F5 HttpSource Base Class | Base class with OkHttp request/parse template hooks | M3 | Survey Exp 3 |
| 24 | R3-F6 ParsedHttpSource Base Class | Jsoup HTML selector scraping implementation of `HttpSource` | M3 | Survey Exp 3 |
| 25 | R3-F7 Concrete HTTP Scraping Source | Source implementation parsing real manga search, details, chapters, pages | M3 | Survey Exp 3 |
| 26 | R3-F8 Source Bridge / Adapter | Adapter connecting `HttpSource` with `ExtensionSource` and UI consumers | M3 | Survey Exp 3 |
| 27 | R3-F9 MockWebServer Test Suite | Comprehensive tests verifying requests, HTML parsing, pagination offline | M3 | Survey Exp 3 |
| 28 | F1.8 ReaderWindow Archive Integration | Support `ArchiveReader` / local files in `ReaderWindow` in `:desktop` | M4 | Survey Exp 1 |
| 29 | F1.9 Aspect-Preserving Image Panel | Centered rendering preserving aspect ratio in `ReaderWindow` | M4 | Survey Exp 1 |
| 30 | F1.10 Open Archive Menu & FileChooser | `JFileChooser` dialog for opening `.cbz`, `.zip`, and folders in `Main.kt` | M4 | Survey Exp 1 |
| 31 | R4-F1 Async SwingWorker Execution | Offload database, network, and archive I/O from Swing EDT | M4 | Survey Exp 3 |
| 32 | R4-F2 CoverCache & Image Scaler | In-memory LRU thumbnail cache and smooth asynchronous image loader | M4 | Survey Exp 3 |
| 33 | R4-F3 Cover Grid View (MangaGridPanel) | Responsive card grid displaying cover art, title, unread badges | M4 | Survey Exp 3 |
| 34 | R4-F4 Manga Details Screen | Detailed view with cover art, metadata, synopsis, action buttons | M4 | Survey Exp 3 |
| 35 | R4-F5 Chapter List Component | Chapter list with unread markers, sort toggle, and read launch | M4 | Survey Exp 3 |
| 36 | R4-F6 CardLayout Navigation | Seamless transitions between Library, Details, Search, and Reader | M4 | Survey Exp 3 |
| 37 | R4-F7 UI Persistence Integration | Connect Cover Grid and Details to `MangaRepository` / `ChapterRepository` | M4 | Survey Exp 3 |
| 38 | R4-F8 Reading History Callback | Update read status and `History` in SQLite when chapter is read | M4 | Survey Exp 3 |
| 39 | E2E-T1 Feature Coverage Suite | ≥5 tests per feature covering all R1-R4 requirements | E2E | Requirement |
| 40 | E2E-T2 Boundary & Corner Suite | Edge cases: corrupted archives, zero-size DB, malformed HTML, empty query | E2E | Requirement |
| 41 | E2E-T3 Cross-Feature Combination | Pairwise interactions: Search -> Add to Library -> Read Archive -> History | E2E | Requirement |
| 42 | E2E-T4 Real-World Workload Suite | End-to-end user journeys from cold start to reading archives | E2E | Requirement |
| 43 | FINAL-1 100% E2E Test Pass | Verification that all Tier 1-4 tests pass via `gradle build` | FINAL | Acceptance |
| 44 | FINAL-2 Tier 5 Adversarial Hardening | White-box source audit and stress-testing exposed code paths | FINAL | Project Pattern |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Domain Entities & Exposed Persistence | `:domain` and `:data` Exposed SQLite persistence, models, tables, repositories, CRUD tests | none | DONE |
| M2 | Local Archive and Directory Reader | `:core:archive` streaming reader for `.cbz`, `.zip`, folders, natural sorting, test fixtures | none | PLANNED |
| M3 | HTTP Sources and Manga Search | `:source-api` OkHttp + jsoup scraping engine, `HttpSource`, `ParsedHttpSource`, MockWebServer tests | none | PLANNED |
| M4 | Desktop Swing UI Enhancements & Reader Integration | `:desktop` Cover Grid, Details Screen, Chapter List, async loader, ReaderWindow archive integration | M1, M2, M3 | PLANNED |
| E2E | E2E Testing Suite Track | Dual-track comprehensive opaque-box test suite (Tiers 1-4) publishing `TEST_READY.md` | none | PLANNED |
| FINAL | E2E Test Pass & Coverage Hardening | 100% pass of E2E suite (Tiers 1-4) + Tier 5 adversarial testing & hardening | M4, E2E | PLANNED |

## Interface Contracts

### Domain & Data ↔ Desktop UI (`:domain` & `:data` ↔ `:desktop`)
- `DatabaseManager`:
  ```kotlin
  class DatabaseManager(dbPath: File) {
      val database: Database
      fun initialize()
  }
  ```
- `MangaRepository`:
  ```kotlin
  suspend fun getMangaById(id: Long): Manga?
  suspend fun getMangaByUrlAndSource(url: String, source: Long): Manga?
  suspend fun getFavorites(): List<Manga>
  fun getFavoritesAsFlow(): Flow<List<Manga>>
  suspend fun insert(manga: Manga): Long
  suspend fun update(manga: Manga)
  suspend fun delete(id: Long)
  ```
- `ChapterRepository`:
  ```kotlin
  suspend fun getChapterById(id: Long): Chapter?
  suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean = false): List<Chapter>
  suspend fun update(chapterUpdate: ChapterUpdate)
  suspend fun updateFromRemote(removedIds: List<Long>, added: List<Chapter>, updated: List<ChapterRemoteUpdate>): List<Chapter>
  ```
- `HistoryRepository`:
  ```kotlin
  suspend fun getHistoryByMangaId(mangaId: Long): List<History>
  suspend fun upsert(historyUpdate: HistoryUpdate): History
  suspend fun upsert(history: History): History
  ```

### Archive Reader ↔ Desktop Reader (`:core:archive` ↔ `:desktop`)
- `ArchivePage`:
  ```kotlin
  data class ArchivePage(val index: Int, val name: String, val path: String, val size: Long = -1L)
  ```
- `ArchiveReader`:
  ```kotlin
  interface ArchiveReader : AutoCloseable {
      val sourceName: String
      val pages: List<ArchivePage>
      val pageCount: Int get() = pages.size
      fun getPageStream(page: ArchivePage): InputStream
      fun getPageBytes(page: ArchivePage): ByteArray
      fun getPageImage(page: ArchivePage): BufferedImage?
      override fun close()
  }
  ```
- `ArchiveReaderFactory`:
  ```kotlin
  object ArchiveReaderFactory {
      fun of(file: File): ArchiveReader
  }
  ```

### Source API ↔ Desktop UI (`:source-api` ↔ `:desktop`)
- `CatalogueSource` / `HttpSource` / `ParsedHttpSource`:
  ```kotlin
  fun fetchPopularManga(page: Int): MangasPage
  fun fetchSearchManga(page: Int, query: String, filters: FilterList): MangasPage
  fun fetchMangaDetails(manga: SManga): SManga
  fun fetchChapterList(manga: SManga): List<SChapter>
  fun fetchPageList(chapter: SChapter): List<Page>
  ```
- `ExtensionSource` bridge (for UI consumption):
  ```kotlin
  interface ExtensionSource {
      val name: String
      val baseUrl: String
      fun searchManga(query: String): List<MangaResult>
      fun getMangaDetails(url: String): MangaDetails
      fun getChapterImages(url: String): List<String>
  }
  ```

## Code Layout
- `domain/src/main/java/tachiyomi/domain/` — Domain models and repository interfaces.
- `domain/src/test/java/tachiyomi/domain/` — Domain unit tests.
- `core/archive/src/main/kotlin/mihon/core/archive/` — Archive readers, comparators, factories.
- `core/archive/src/test/kotlin/mihon/core/archive/` — Archive unit tests and synthetic fixtures.
- `source-api/src/main/kotlin/eu/kanade/tachiyomi/source/` — Source abstractions and models.
- `source-api/src/main/kotlin/eu/kanade/tachiyomi/util/` — Network and Jsoup utilities.
- `source-api/src/main/kotlin/mihon/source/` — Extension sources and concrete scrapers.
- `source-api/src/test/kotlin/` — MockWebServer tests and HTML test fixtures.
- `data/src/main/kotlin/mihon/data/database/` — Exposed tables, DatabaseManager.
- `data/src/main/kotlin/mihon/data/repository/` — Repository implementations.
- `data/src/test/kotlin/mihon/data/` — Persistence integration tests.
- `desktop/src/main/kotlin/mihon/desktop/` — Swing UI components, ReaderWindow, Main entry.
- `desktop/src/test/kotlin/mihon/desktop/` — Desktop UI component tests.
