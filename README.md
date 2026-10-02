# WhaleHud · 鲸鲸余额表

An Android overlay that keeps an eye on your DeepSeek API balance.
A small character sits in the corner of your screen — when the balance drops, she reacts.

**[中文说明 →](README.zh-CN.md)**

---

## Features

**Overlay**
- Translucent balance plate, amount only — nothing else blocking your screen
- Drag anywhere; position is remembered
- **Tap the character** — gentle nudge, random line, click sound
- **Balance drops** — red flash, impact shift, floating `−¥x.xx`, hurt sound
- Two character sizes, switched live without restarting the overlay

**Main screen**
- Single home page; settings and about open from a top-right overflow menu (`⋮`)
- Settings page is rendered by a WebView (`assets/settings.html`) — form changes save on blur
- Motion is driven by shared tokens, light/dark follows the system

**Pricing helper**
- Peak / off-peak detection (Mon–Fri 09:00–12:00 and 14:00–18:00 are peak)
- Reads the system calendar to detect public holidays and make-up workdays; manual holiday list also supported
- Three peak rates plus an off-peak multiplier, all configurable

**Privacy**
- API key is stored only in the local `SharedPreferences`
- Never bundled into the APK, never uploaded, no analytics or telemetry of any kind
- Requests go only to the endpoint you configure

---

## Compatibility

| | |
|---|---|
| Min SDK | Android 10 (API 29) |
| Target SDK | Android 14 (API 34) |
| CPU | Any — pure Java, no native libraries |
| Dependencies | Android Framework only, **no AndroidX** |
| Permissions | Internet, System Alert Window (overlay), optional Calendar, optional Notifications |

> The overlay requires the "Display over other apps" permission.
> Some vendor ROMs (Funtouch OS / MIUI / EMUI) additionally require auto-start, background pop-up,
> and battery-optimization exemption — otherwise the service may be killed in the background.

---

## Install

1. Download `WhaleHud-2.1.1.apk` from the Releases page
2. Open the app and tap the permission pill on the home screen to grant overlay access
3. Enter your own DeepSeek API key on the settings page
4. Back on the home screen, tap **Start overlay**

---

## Build

```bash
bash build.sh
# output: build/WhaleHud-debug.apk
```

This project was developed on an arm64 host where Google's `aapt2` (x86_64 only) cannot run,
so the build script bypasses it and drives the pipeline directly:

1. Generate a build-time manifest (inject `package` / `uses-sdk`)
2. `aapt package -m` → `R.java`
3. `aapt package` → bundles resources and assets into `app.ap_`
4. `javac --release 8`
5. `R8/D8` → `classes.dex`
6. `aapt add` → write the dex into the apk
7. `zipalign -f 4`
8. `apksigner`

Requirements: JDK 8+, `aapt`, `zipalign`, `apksigner`, R8/D8, Python 3 (for the manifest step).

> `aapt package` **must** be given `-A <assets dir>`, otherwise the WebView settings page is not packaged.
> `apksigner` signs v3 only when `minSdkVersion >= 28` (v3 supersedes v2); Android 9+ verifies it fine.

---

## Project structure

```
.
├── app/src/main/
│   ├── assets/settings.html          # settings page (WebView content)
│   ├── java/com/example/whalehud/
│   │   ├── MainActivity.java         # home + overflow menu + JS bridge
│   │   ├── HudService.java           # overlay service, interactions, animation, sound
│   │   ├── Motion.java               # motion tokens (durations & curves)
│   │   ├── DeepSeek.java             # balance API (official format + heuristic fallback)
│   │   ├── Prefs.java                # local preferences
│   │   ├── Calc.java                 # peak / off-peak determination
│   │   └── HolidayCal.java           # system calendar holiday detection
│   └── res/
│       ├── values{,-en,-fr,-ru}/     # Chinese / English / French / Russian
│       ├── layout/  drawable/  raw/  # layouts, icons, sound effects
├── tools/                            # image scripts (cutout, resize)
├── docs/                             # development notes & changelog
├── build.sh
└── README.md
```

---

## Design notes

**Motion.** Every animation takes its duration and curve from `Motion.java`; no magic numbers in feature code.

```
duration  INSTANT 75 · FAST 120 · BASE 200 · SLOW 320 · LONG 520 (ms)
easing    enter · exit · standard · emphasis · gentle · impact
```

The settings page uses the same token names as CSS variables (`--dur-slow`, `--ease-gentle`),
so the native and web sides speak the same animation language. Only `opacity` and `transform` are animated.

**Overlay is native, settings page is web.** The overlay runs inside a foreground service and refreshes
every few seconds — a WebView instance would cost 30–80 MB of resident memory for animations that amount
to a translate and a fade. The settings page, being a form, benefits from web styling far more than it
costs.

---

## Version history

| Version | Date | Highlights |
|---|---|---|
| 1.0 | 2026-09-26 | Info-card overlay, token conversion, edge snapping |
| 2.0 | 2026-09-27 | Character-based overlay, damage feedback, MobileGlues-style UI, live restyle |
| 2.1 | 2026-10-02 | Motion token system, 4 languages, settings page moved to WebView, tap feedback redesign |
| 2.1.1 | 2026-10-02 | Stop button on home, custom picker replaces broken `<select>`, overflow menu + language switching |
| 2.1.2 | 2026-10-02 | Fix option list rendering (JSON array sent as string), fix stale lines after language switch, UI clarity |

See [`docs/CHANGELOG.md`](docs/CHANGELOG.md) for details, and [`docs/NOTES.md`](docs/NOTES.md)
for the development log — including the approaches that failed.

---

## Credits

> The following people made indelible contributions to this project — writing code, filing bugs,
> or simply being startled by the balance. Every kind counts. We list and thank them all here.

**Core**

- **Whale (鲸鲸)** — Sole on-screen talent. Duties: squatting in a screen corner, being tapped,
  being shaken, delivering lines. This quarter's KPI: stay sharp.
- **dheye62** — Requirements, testing, acceptance — and the only person on the project qualified
  to say "this looks AI-generated".
- **AI assistant** — Wrote 100% of the code and 100% of the bugs. Lost a debate about whether a
  certain product was a phishing site, and has since learned to read the source before speaking.
- **DeepSeek V4.1 Flash** — Attends every single balance refresh. Never errs, never speaks.

**Special thanks**

- **Minecraft** — for setting the industry standard for damage flashes and bone-crack sound effects.
- **The "Oh whale…" speech bubble we cut out** — you used to be the star; now we draw our own.
- **arm64 `aapt`** — among a pile of x86_64 tools that refuse to run, you were the only one willing to work.
- **1.1.1.1** — UDP port 53 never once got through, yet you stayed.

---

## License

[MIT](LICENSE)

Third-party assets:
- Character artwork copyright belongs to the original artist — **not** covered by the MIT license.
- Minecraft sound effects are © Mojang Studios, included for personal non-commercial use only —
  **not** covered by the MIT license. Replace them before any commercial distribution.
