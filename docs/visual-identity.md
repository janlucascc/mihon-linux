# Mihon Linux Visual Identity

This document defines the brand system for **Mihon Linux** (Kotlin / Swing / FlatLaf desktop client) so the application icon, navigation shell, manga library cards, controls, and reader HUD share one cohesive aesthetic.

## 1. Brand Direction

- **Personality**: Focused, modern, calm, tactile, and manga-first.
- **UI Philosophy**: Let manga covers and chapters carry the visual color; frame them with clean, neutral surfaces, hairline borders, and disciplined indigo + cyan brand accents.

## 2. Brand Mark & Vector Icon

- **Concept**: A rounded indigo squircle containing an open book with subtly shaded facing pages, minimal text rules, and a circular cyan-accented completion checkmark at the lower-right corner.
- **Canonical Vector Source**: Bundled at `desktop/src/main/resources/icons/mihon-linux.svg` and generated procedurally by [`AppIcon`](../desktop/src/main/kotlin/mihon/desktop/AppIcon.kt) for window frames, taskbar integration, and XDG `hicolor` PNG/SVG installation.
- **Core Icon Tokens**:
  - Container fill: `#5B5BD6` (`rx="26"` in `128×128` viewBox)
  - Left / Right book leaves: `#F7F7FA` and `#E8E9FF`
  - Spine & page rule accents: `#5B5BD6` and `#8D8DE8`
  - Completion badge: `#36C5F0` circle with `#FFFFFF` checkmark

## 3. Color Palette

| Token | Light | Dark | AMOLED | Role |
| :--- | :--- | :--- | :--- | :--- |
| **Brand Primary / CTA** | `#5B5BD6` | `#5B5BD6` (`#A6A6FF` text/icon accent) | `#5B5BD6` (`#A6A6FF` text/icon accent) | Primary CTAs, active navigation, unread badges |
| **Primary Hover** | `#4949C7` | `#8D8DE8` | `#8D8DE8` | Button & interactive highlight hover |
| **Secondary Accent** | `#36C5F0` | `#36C5F0` | `#36C5F0` | Brand checkmark, subtle status & telemetry highlights |
| **App Background** | `#F7F7FA` | `#16171C` | `#000000` | Main canvas & scroll viewports |
| **Sidebar / Nav** | `#EEEFF5` | `#1B1C22` | `#07080B` | Left navigation rail |
| **Surface / Dialogs** | `#FFFFFF` | `#202126` | `#0E0F14` | Header bars, cards, menus, dialogs |
| **Card Surface** | `#FFFFFF` | `#26282F` | `#14151B` | Elevated containers & chips |
| **Primary Text** | `#161722` | `#EAEBF2` | `#F4F5FA` | Headings & titles |
| **Secondary Text** | `#56586B` | `#9CA0B2` | `#969AAB` | Subtitles, chapters, hints |
| **Success** | `#2FB344` | `#78B886` | `#78B886` | Up-to-date & completion states |
| **Warning** | `#E5A50A` | `#F4C875` | `#F4C875` | Alerts & source warnings |
| **Danger** | `#E01B24` | `#FF8B8B` | `#FF8B8B` | Errors & destructive actions |

## 4. Typography & Surface Geometry

- **Cards & Covers**: `14px` corner radius (`COVER_ARC = 14`), soft bottom vignette scrim, `1px` neutral hairline border (`rgba(..., 0.08–0.10)`), and smooth hover border shift toward `alpha(#5B5BD6, 0.48)` with subtle upward lift.
- **Pills & Badges**:
  - Unread count badge: pill (`999px`) filled with `#5B5BD6` and bold crisp white numerals.
  - Downloaded count / format badge: pill (`999px`) in dark translucent `#17171C` (`alpha 0.82`).
  - Search bars & action chips: rounded pill outlines with `#5B5BD6` focus rings.
- **Placeholders**: Soft `145deg` diagonal gradient blending `alpha(#5B5BD6, 0.24)` into `alpha(#36C5F0, 0.12)` inside a `14px` rounded card container.

## 5. Cinematic Reader HUD

- **Floating Top & Bottom Overlays**: `#17171C` at `0.92` alpha (`rgba(23, 23, 28, 235)`), bordered by `1px` hairline `rgba(255, 255, 255, 0.08)` with `12px` corner radius on floating overlays.
- **Page Indicator Pill**: `rgba(255, 255, 255, 0.10)` rounded pill with tabular bold numerals so dark/light themes keep clear legibility over manga artwork.
