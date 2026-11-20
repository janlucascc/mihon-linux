# Mihon Linux Visual Identity (Mihon Index)

This document defines the **Mihon Index** editorial visual system for **Mihon Linux** (Kotlin / Swing / FlatLaf desktop client) so the application icon, navigation shell, manga library cards, controls, and reader HUD share one calm, structured aesthetic.

## 1. Brand Direction

- **Personality**: Editorial reading catalog — focused, neutral, scannable, and cover-first.
- **UI Philosophy**: Neutral layered surfaces, hairline `1px` rules, compact `38px` controls (`8px` radius), `48px` primary reading CTAs, dominant artwork covers (`10px` radius), and small `7px`-radius status badges without spectral gradients, glassmorphism, or decorative boxes.

## 2. Brand Mark & Vector Icon

- **Concept**: A Cobalt rounded squircle containing an open book with subtly shaded facing pages, minimal text rules, and a circular Signal Orange completion checkmark at the lower-right corner.
- **Canonical Vector Source**: Bundled at `desktop/src/main/resources/icons/mihon-linux.svg` and generated procedurally by [`AppIcon`](../desktop/src/main/kotlin/mihon/desktop/AppIcon.kt) for window frames, taskbar integration, and XDG `hicolor` PNG/SVG installation.
- **Core Icon Tokens**:
  - Container fill: `#4F46E5` (`rx="26"` in `128×128` viewBox)
  - Left / Right book leaves: `#F7F7FA` and `#E8E9FF`
  - Spine & page rule accents: `#4F46E5` and `#818CF8`
  - Completion badge: `#F97316` circle with `#FFFFFF` checkmark

## 3. Color Palette

| Token | Light | Dark | AMOLED | Role |
| :--- | :--- | :--- | :--- | :--- |
| **Brand Primary / CTA** | `#4F46E5` | `#4F46E5` (`#818CF8` text accent) | `#4F46E5` (`#818CF8` text accent) | Primary CTAs, active category, focus, selection |
| **Primary Hover / Press** | `#4338CA` / `#3730A3` | `#4338CA` / `#3730A3` | `#4338CA` / `#3730A3` | Button hover and pressed states |
| **Signal / Unread** | `#F97316` (hover `#EA580C`) | `#F97316` (hover `#EA580C`) | `#F97316` (hover `#EA580C`) | Unread count badges and new-chapter markers |
| **App Background** | `#F7F7FA` | `#16171C` | `#000000` | Main canvas & scroll viewports |
| **Sidebar / Nav** | `#EEEFF5` | `#1B1C22` | `#07080B` | Left navigation rail & bottom status bar |
| **Surface / Popups** | `#FFFFFF` | `#202126` | `#0E0F14` | Header containers, menus, dialogs |
| **Card / Hover** | `#FFFFFF` / `#E8E9F5` | `#26282F` / `#30333C` | `#14151B` / `#20222C` | Row cards, placeholders, list items |
| **Primary Text** | `#161722` | `#EAEBF2` | `#F4F5FA` | Headings & titles |
| **Secondary / Muted Text** | `#56586B` / `#787B8F` | `#B8BCCE` / `#8C91A4` | `#C2C6D6` / `#9094A8` | Subtitles, metadata, placeholders |
| **Success** | `#2FB344` | `#78B886` | `#78B886` | Downloads & up-to-date states |
| **Warning** | `#E5A50A` | `#F4C875` | `#F4C875` | Alerts & source warnings |
| **Danger** | `#E01B24` | `#FF8B8B` | `#FF8B8B` | Errors & destructive actions |

## 4. Typography & Surface Geometry

- **Cards & Covers**: `10px` radius (`COVER_ARC = 10`, `CARD_RADIUS = 10`), cover-only shadow, `easeOutCubic` `-3px` hover lift, `2px` `#4F46E5` selection ring, and neutral `BG_CARD` placeholder with centered book vector mark.
- **Controls & Badges**:
  - Compact buttons, inputs, and segmented control track: `38px` height, `8px` radius (`CONTROL_RADIUS = 8`).
  - Primary reading CTA: `48px` height (`CONTROL_HEIGHT_PRIMARY = 48`), `8px` radius.
  - Unread badge: `7px` radius (`BADGE_RADIUS = 7`) filled with `#F97316` and bold white numerals.
  - Downloaded badge: `7px` radius using semantic `SUCCESS`.

## 5. Cinematic Reader HUD

- **Always-Dark Floating Overlays**: `#17171C` at `0.92` alpha (`rgba(23, 23, 28, 235)`), bordered by `1px` `rgba(255, 255, 255, 0.14)` with `10px` corners on the bottom sheet (`72–88px`) and crisp white icon controls on the `56–64px` top bar.
- **Page Indicator Pill**: `6px` rounded `rgba(255, 255, 255, 0.10)` badge with bold tabular numerals so light and dark themes never disturb page artwork.
