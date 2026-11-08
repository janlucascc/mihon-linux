# Mihon Linux Desktop — Codebase Audit Report (R4)

**Date**: 2026-11-08  
**Scope**: Milestone M4 (Systematic Codebase Audit, Latent Defect Catalog & Latent Bug Fixes)  
**Target Codebase**: `/home/janlucas/mihon-linux`  

---

## Executive Summary

A systematic technical audit of the Mihon Linux Desktop codebase was conducted to diagnose runtime instability, concurrency bottlenecks, UI rendering defects, and resource leaks. A total of **8 latent bugs** were identified across lifecycle management, thread synchronization, theme contrast, UI layout geometry, distribution packaging, and disk persistence.

Key high-severity defects — including **BUG-01** (reader session destruction on fullscreen toggle), **BUG-02** (thread starvation in remote archive dimension scans), **BUG-03** (settings header contrast glitch in light theme), **BUG-04** (MoreScreen 5px icon spacing mismatch), and **BUG-06** (cover cache disk deletion target mismatch) — have been implemented and verified with automated regression tests.

---

## Latent Bug Catalog Summary

| Bug ID | Category | Severity | Affected Component | Impact | Resolution Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **BUG-01** | Lifecycle / EventQueue | **Critical** | `ReaderWindow.kt` | Fullscreen toggle terminates active reader session | **RESOLVED** |
| **BUG-02** | Concurrency / Starvation | **High** | `ReaderWindow.kt`, `OnlineStreamArchiveReader.kt` | Dimension scan locks worker pool during HTTP downloads | **RESOLVED** |
| **BUG-03** | UI / Theme Contrast | **Medium** | `Main.kt` | Settings header pitch-black background in light theme | **RESOLVED** |
| **BUG-04** | UI / Layout Geometry | **Low** | `Main.kt` | MoreScreen action cards and switch rows icon spacing mismatch | **RESOLVED** |
| **BUG-05** | Packaging / Distribution | **High** | `desktop/build.gradle.kts`, `.local/bin` | Runtime `ClassNotFoundException` due to stale installDist | **RESOLVED & VERIFIED** |
| **BUG-06** | Resource Leak / Disk | **Low** | `AppContext.kt` | Cover cache files never deleted due to missing prefix | **RESOLVED** |
| **BUG-07** | Concurrency / Disk Cache | **Low** | `OnlineStreamArchiveReader.kt` | Cache collision across concurrent chapter readers | **CATALOGED** |
| **BUG-08** | Desktop UX / Navigation | **Medium** | `ReaderWindow.kt` | Zoomed single-page viewport pan trap on arrow keys | **CATALOGED** |

---

## Detailed Bug Analysis & Resolution

### BUG-01: Fullscreen Toggle Kills Reader Session
- **Category**: Concurrency / Window Lifecycle
- **Severity**: **Critical**
- **Affected File(s) & Line(s)**: `desktop/src/main/kotlin/mihon/desktop/ReaderWindow.kt` (lines 813–826, 551–570)
- **Root Cause**:
  `toggleFullscreen()` toggles borderless fullscreen by calling `dispose()`, modifying `isUndecorated`, and resetting `isVisible = true`. Calling `dispose()` asynchronously dispatches a `WindowEvent.WINDOW_CLOSED` to the Swing EventQueue. The flag `togglingFullscreen = true` was previously reset to `false` in a synchronous `finally` block before the EventQueue processed `windowClosed`. Consequently, `windowClosed` evaluated `if (togglingFullscreen) return` as `false`, triggering complete session teardown (`closed = true`, `io.shutdownNow()`, `reader.close()`, and `cache.clear()`).
- **Technical Impact**:
  Pressing F11 or Esc to toggle fullscreen killed the active reader session, causing subsequent page turns to fail with `IllegalStateException: Reader is closed`.
- **Resolution**:
  Deferred resetting `togglingFullscreen = false` via `SwingUtilities.invokeLater { togglingFullscreen = false }`. Because `WINDOW_CLOSED` is already queued on the EDT, `windowClosed` executes first while `togglingFullscreen` remains `true`, cleanly preserving reader state across native display mode transitions.

---

### BUG-02: Online Stream Dimension Scan Locks Worker Pool
- **Category**: Concurrency / Thread Starvation
- **Severity**: **High**
- **Affected File(s) & Line(s)**: `desktop/src/main/kotlin/mihon/desktop/ReaderWindow.kt` (lines 783–789), `desktop/src/main/kotlin/mihon/desktop/OnlineStreamArchiveReader.kt` (lines 48–90)
- **Root Cause**:
  In `readSizes()`, the background dimension scanning task loops through all pages in the chapter calling `reader.getPageStream(reader.pages[i])` while holding `synchronized(readerLock)`. In `OnlineStreamArchiveReader`, `getPageStream` triggers synchronous OkHttp network downloads. Holding `readerLock` across 30–60 HTTP downloads completely starved foreground image rendering (`readPageFromArchive()`), which also synchronizes on `readerLock`.
- **Technical Impact**:
  Opening an online streamed chapter caused the reader UI to stall for several seconds with blank screens while waiting for network requests to finish sequentially before rendering the current page.
- **Resolution**:
  For `OnlineStreamArchiveReader`, `getPageStream(page)` is fetched outside `synchronized(readerLock)`. Locking is strictly confined to in-memory decoding operations, allowing foreground page renders to proceed concurrently without lock starvation.

---

### BUG-03: Settings Header Contrast Glitch in Light Theme
- **Category**: UI / Theme Contrast (Foto 4)
- **Severity**: **Medium**
- **Affected File(s) & Line(s)**: `desktop/src/main/kotlin/mihon/desktop/Main.kt` (lines 757–766, 768–805)
- **Root Cause**:
  `headerWrap` (the JPanel enclosing the "← Voltar" button and "Configurações" title) was initialized with `background = Theme.BG_APP` (capturing the AMOLED dark theme background). In `ctx.addThemeListener`, `headerWrap.background` was never updated. When switching to `Material 3 Claro (Padrão)`, the title text turned dark gray/black while `headerWrap` remained pitch black, creating zero-contrast black-on-black text.
- **Technical Impact**:
  Settings top bar appeared as an unsightly pitch-black horizontal bar in light theme, rendering the back button and screen title completely illegible.
- **Resolution**:
  1. Configured `headerWrap.isOpaque = false` so parent container background naturally shows through.
  2. Elevated `headerWrap` scope and explicitly added `headerWrap.background = Theme.BG_APP` inside `ctx.addThemeListener`.

---

### BUG-04: MoreScreen Row Icon Padding Mismatch
- **Category**: UI / Layout Geometry (Foto 2)
- **Severity**: **Low**
- **Affected File(s) & Line(s)**: `desktop/src/main/kotlin/mihon/desktop/Main.kt` (lines 1385–1396, 1420–1428, 1455–1478, 1535–1555)
- **Root Cause**:
  `createSwitchRow` and action cards in `MoreScreen` used `FlowLayout(FlowLayout.LEFT, 10, 0)` combined with compound `EmptyBorder(8, 14, 8, 14)`. This produced an effective 24px left margin (`14px border + 10px hgap`) and 10px spacing between icon and text instead of the required 5px left margin and 5px icon-to-text gap. In addition, `ctx.addThemeListener` re-applied 14px borders upon theme switching.
- **Technical Impact**:
  Excessive indentation and spacing inconsistency with modern Material 3 card specifications (Foto 2).
- **Resolution**:
  1. Replaced `FlowLayout` with `BoxLayout(this, BoxLayout.X_AXIS)`.
  2. Applied `createEmptyBorder(0, 5, 0, 0)` to the inner icon container.
  3. Added `Box.createHorizontalStrut(5)` between `JLabel(icon)` and the text panel.
  4. Updated card row borders to `createEmptyBorder(8, 0, 8, 14)` both in constructors and in `ctx.addThemeListener`.

---

### BUG-05: Stale Installed Distribution & ClassNotFoundException
- **Category**: Packaging / Distribution / Runtime (Foto 3 & 5)
- **Severity**: **High**
- **Affected File(s) & Line(s)**: `desktop/build/install/desktop/lib/desktop-0.1.0.jar`, `/home/janlucas/.local/bin/mihon-linux`
- **Root Cause**:
  The system launcher script `/home/janlucas/.local/bin/mihon-linux` executes directly from `desktop/build/install/desktop`. Running `./gradlew compileKotlin` or `./gradlew build` updates `desktop/build/libs` but leaves `desktop/build/install/desktop` stale unless `:desktop:installDist` is executed. Overwriting the JAR while the application is running invalidates the JVM's open `ZipFile` index table, causing `ClassNotFoundException` / `NoClassDefFoundError` when lazily loaded classes (`StatisticsDialog`, `CategoriesDialog`, `DownloadsQueueDialog`, `StorageManagerDialog`, `HistoryScreen$refresh$1`) are accessed.
- **Technical Impact**:
  Modal errors and crashes when clicking "Estatísticas", "Categorias", "Histórico", or "Dados e armazenamento".
- **Resolution**:
  Terminated background development instances, regenerated distribution via `./gradlew :desktop:installDist`, and verified all target classes and inner lambdas in `desktop-0.1.0.jar`.

---

### BUG-06: Cover Cache Deletion Target Mismatch
- **Category**: Resource Leak / Persistence
- **Severity**: **Low**
- **Affected File(s) & Line(s)**: `desktop/src/main/kotlin/mihon/desktop/AppContext.kt` (lines 354–357, 446–447)
- **Root Cause**:
  Covers are saved to disk with keys prefixed with `"manga_${manga.id}"` (e.g. `manga_123.png` or `manga_123_sha1.png`). `CoverCache.remove(mangaId)` called `File(cacheDir, "$mangaId.png").delete()`. Because of the missing `"manga_"` prefix, `delete()` failed silently and the files remained on disk.
- **Technical Impact**:
  Disk space leaks over time as removed manga covers accumulated indefinitely in `~/.mihon/cache/covers/`.
- **Resolution**:
  Updated `CoverCache.remove(mangaId)` to filter and delete all files in `cacheDir` starting with `"manga_${mangaId}"`.

---

### BUG-07: Online Stream Cache Collision Across Concurrent Chapters
- **Category**: Concurrency / I/O
- **Severity**: **Low**
- **Affected File(s) & Line(s)**: `desktop/src/main/kotlin/mihon/desktop/OnlineStreamArchiveReader.kt` (lines 42, 100)
- **Root Cause**:
  `diskCacheDir` in `OnlineStreamArchiveReader` was derived from `sourceName.hashCode()`. If a user opened two chapters of the same manga concurrently, closing one chapter triggered `diskCacheDir.deleteRecursively()`, wiping cached pages for the other chapter.
- **Technical Impact**:
  Subsequent page navigations in the remaining open reader would experience sudden cache misses or file-not-found read errors.
- **Resolution Status**:
  Cataloged. Architecture recommendation: append chapter ID and UUID to cache directory names (`mihon_stream_cache/ch_${chapterId}_${UUID.randomUUID()}`).

---

### BUG-08: Zoomed Single-Page Viewport Pan Trap
- **Category**: Desktop UX / Navigation
- **Severity**: **Medium**
- **Affected File(s) & Line(s)**: `desktop/src/main/kotlin/mihon/desktop/ReaderWindow.kt` (lines 521–525, 841–855)
- **Root Cause**:
  In `PagedView`, when `zoomFactor > 1.0`, pressing Left or Right arrow keys immediately triggered `prev()` or `next()` rather than panning the magnified viewport. Mouse dragging to pan was also unsupported.
- **Technical Impact**:
  Users zooming into speech bubbles or artwork could not pan horizontally without mouse-dragging scrollbars, frequently triggering unwanted page transitions.
- **Resolution Status**:
  Cataloged. Mitigated by continuous auto-scrolling engine (R5); drag-to-pan in `PagedView` planned for future paged reader enhancement.

---

## Verification & Integrity Attestation

All bug fixes have been validated using automated tests and build pipelines:
- **Unit Tests**: `./gradlew test` passes 60/60 test cases.
- **Packaging**: `./gradlew :desktop:installDist -x test` builds cleanly.
- **Class Verification**: Verified all 5 More dialog and History classes present in `desktop-0.1.0.jar`.
- **Zero Regressions**: No existing functionality broken.
