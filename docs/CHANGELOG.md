# Changelog

All notable changes to WhaleHud. Versions follow `major.minor.patch`;
each release also ships a self-contained source snapshot as a GitHub Release asset.

---

## 2.1.2 — 2026-10-02

**Fixed: option lists rendered one character per line**

*Symptom* — opening "Peak rule", "Refresh interval" or "Appearance" in settings showed the options
stacked vertically, one character per row (`[`, `"`, `自`, `动`, …).

*Root cause* — not a CSS or layout issue, a **type** issue. The Java side built the option list
by string concatenation and put the result into the JSON as a `String`, not an array:

```java
p.put("peakModes", arrayToList(R.array.peak_modes));  // → "[\"a\",\"b\"]"  (a String)
```

The web side then did `list[i]` on a string and got single characters back.
2.1.1 had replaced the native `<select>` with a custom picker — **the control was changed but the
data source was still broken**, which is why the earlier fix did not take.

*Fix* — return a real `JSONArray` from Java; on the JS side, defensively parse strings that look
like arrays and fall back to an empty list rather than ever indexing a string.

**Fixed: lines stayed in the old language after switching language**

The overlay is a long-running `Service`; `attachBaseContext` runs only once, so its `Resources`
remain pinned to the language that was active when it was created. The service no longer caches any
text array — it fetches on demand through `Prefs.wrap(this)`. The notification is rebuilt on the same
trigger, so the notification shade follows too.

**Overflow menu behaviour** — the `⋮` button is fixed at the top-right (it lives in the root
`FrameLayout` overlay, so it never scrolls with content). It fades out on scroll-down and back in on
scroll-up. A scrim layer (15% black) sits behind the menu so tapping anywhere outside closes it.

**UI clarity**
- Removed the duplicate system title bar (`windowNoTitle` was `false`, so the system showed
  "鲸鲸余额表" while the page drew its own heading)
- Home is grouped into **Status** (endpoint / refresh / key) and **Controls** (start / stop / size)
- Settings rows separated by a 1px rule indented 16px from each side
- Current value of each setting rendered in the accent colour

---

## 2.1.1 — 2026-10-02

**Stop button moved to the home screen** — it used to live under a "Danger zone" heading on the About
page. Start and stop now sit together:

```
Start overlay   Park the whale in a corner of your screen   ›
Stop overlay    Send the whale off your screen              ›   (red)
Whale size      Current: large                              ›
```

**Fixed: settings dropdowns could not be opened** — they were native `<select>` elements inside a
WebView, styled with `-webkit-appearance: none`. More importantly, their options were injected over
the JS bridge: if the bridge or parsing failed, the `<select>` had **no options at all** — an empty
control that opens to nothing. Replaced with a self-drawn picker (tap row → bottom sheet with a scrim),
which depends on no system widget and opens regardless of bridge state.

**Bottom navigation replaced with a top-right overflow menu** — `nav_home` / `nav_settings` /
`nav_info` removed entirely. The `⋮` button opens a panel with **Settings / About / Language**.
Secondary pages get a back arrow; the system back button works too.

**In-app language switching** — Follow system / 简体中文 / English / Français / Русский.
The language code is persisted and applied in `attachBaseContext` via `createConfigurationContext`,
so `recreate()` takes effect immediately — no app restart required.

---

## 2.1 — 2026-10-02

**Motion system rebuilt.** Previous durations were chosen by feel (260 / 300 / 420 / 620 / 2400 ms,
with enter and exit sharing a single curve). Now centralised in `Motion.java`:

| Token | Duration | Used for |
|---|---|---|
| `INSTANT` | 75 ms | Immediate feedback (press, ripple) |
| `FAST` | 120 ms | State changes, elements leaving |
| `BASE` | 200 ms | Small elements entering/leaving |
| `SLOW` | 320 ms | Structural movement (page switch, staggered entrance) |
| `LONG` | 520 ms | Themed effects (full damage feedback) |

Easing is chosen by direction: `enter` (fast-out, slow-in), `exit` (slow-out, fast-in), plus
`standard`, `emphasis`, `gentle` and `impact`. **No animation duration appears as a literal in feature code.**

**Four languages** — Chinese (default), English, French, Russian. All strings moved to `strings.xml`,
including the credits and the character's lines.

**Settings page moved to a WebView** — `assets/settings.html` plus a JS bridge. CSS variables mirror
the native tokens (`--dur-slow` ⇄ `Motion.SLOW`). The home screen and overlay stay native.

**Tap feedback redesigned** — the old left-right shake read as "shaking the head in denial".
Replaced with `tapNudge()`: 4 dp displacement plus a slight squash, no overshoot, no rotation.

**Damage feedback kept as-is** — `impactShake()` (top-right toward bottom-left), red flash fade-out,
hurt sound.

**Open-source preparation** — MIT LICENSE added, including explicit third-party asset notices.

---

## 2.0 — 2026-10-01

**Overlay became a character.** The info-card底板 was removed entirely; only the character and a
translucent black balance plate remain.

**Damage feedback** — red flash driven by `ValueAnimator` over `setColorFilter(..., SRC_ATOP)`,
an impact shift with a 16% rebound, and a sound effect.

**Main screen redesigned** after MobileGlues 2.0: pure black background, dark rounded cards,
a single accent colour, group titles outside the cards, rows as *title / value / chevron*.

**Live restyle** — an `ACTION_RESTYLE` path lets the overlay change size without being restarted.

**Removed** — token conversion, today/session consumption, peak-rate display and status text.
`Calc` now answers exactly one question: is this peak or off-peak?

---

## 1.0 — 2026-09-26

First working version: an info-card overlay with balance, token estimate, daily consumption,
status line, edge snapping on release, and tap-to-refresh.

> The 1.0 APK was lost — its build output shared a path with 2.0 and was overwritten by
> `rm -rf build` before version snapshots existed. See `ARCHIVE-1.0.md`.
> This loss is the direct reason the project now has automated pre-build snapshots and a git history.
