# Development Notes

This file records how WhaleHud was built: **what was decided, why, and what had to be thrown away.**
Failed approaches are kept deliberately — they are the reason the working parts work.

---

## 0. What this project is

An Android overlay widget that polls the DeepSeek balance API and parks a small character in the
corner of the screen. When the balance drops, she reacts.

Constraints imposed by the environment:

- Target device: vivo V1829A / Android 10 / API 29, 1080×2340 @480dpi (i.e. 360×780 dp)
- The development host is an **arm64 proot container running on the phone itself**, with **no `aapt2`**
  (Google ships it for x86_64 only)
- The whole build chain is therefore hand-wired: `aapt` (arm64) + `javac` + `R8/D8` + `zipalign` + `apksigner`
- No AndroidX, to avoid pulling in dependencies — **framework Java only**
- The user's API key is never baked into the APK; it is entered at runtime

---

## 1. Image cutout: why white fringes appear, and how to remove them

### The problem

The source art is a 1026×1026 image on a white background. The 1.0 cutout did this:

1. Flood-fill from the four edges to mark the background
2. Set background pixels to alpha 0, everything else to alpha 255
3. Feather pixels adjacent to transparency

Result: a **visible white halo** around the character on dark backgrounds.

### Measuring it

Compositing both versions onto a dark background and counting semi-transparent pixels:

| Version | Semi-transparent pixels | of which "whitish" (`min(R,G,B) > 170`) |
|---|---|---|
| 1.0 | 2,254 | **2,047 (90%)** |
| 2.0 | 2,009 | **0 (0%)** |

90% of the edge pixels were white. That *is* the halo.

### Why

In an anti-aliased edge over white, a pixel is not "character colour with partial opacity" — it is

```
observed = foreground × α + white × (1 − α)
```

1.0 only assigned alpha; it never **subtracted the white**. So the white stayed in the pixel.

### What 2.0 does (`tools/cutout2.py`)

1. **Flood-fill from the four corners** to mark the outside. Only white *connected to the canvas edge*
   is removed, so white details inside the character (lace, collar) survive.
2. **Rebuild alpha along the boundary ring** using `mn = min(R,G,B)`, `α = clamp((255 − mn) / (255 − A0))`.
3. **Unpremultiply** — the key step:

   ```
   foreground = (observed − 255 × (1 − α)) / α
   ```

   Remove the white contribution scaled by α, and the edge becomes the character's own colour again.
4. **Largest connected component** = the character; the original speech bubble is discarded (bubbles
   are drawn by code now).
5. **Light sharpening** (`UnsharpMask(radius=1, percent=45)`).

### A trap worth remembering

`PIL.ImageDraw.floodfill` compares each candidate against the *seed colour* using `thresh`.
If the fill value itself is within `thresh` of the background, the function decides
"already the target colour" and **returns immediately, doing nothing** — silently.

The first attempt failed exactly this way: the flood fill did nothing and the whole image was background.

Fix: fill with 128, seed is 255, so `thresh` must satisfy `127 < thresh < 255`; 100 works.

---

## 2. UI: why the whole thing was rebuilt

### 1.0 and why it was rejected

1.0 used a gradient header card, a coloured accent bar, pill buttons and rounded cards everywhere.

The user's words: **"too much AI flavour"** — and the judgement was fair. Gradient + accent bar +
saturated accent colour is the signature of template-driven design: it looks *effortful* but not
like a real project.

### Where 2.0's direction came from

The reference was **MobileGlues 2.0**. The first instinct was to read its source, and the answer was:

- `MobileGL-Dev/MobileGlues-release` — README and an icon, no UI
- `MobileGL-Dev/MobileGlues` — 249 files, all C++ rendering code (`MobileGlues-cpp/`);
  configuration is JSON read/written by `config/settings.cpp`
- **It has no app UI at all** — it is a pure GL translation layer

The design language therefore had to be read off screenshots the user supplied:

- pure black background
- dark rounded cards with **no border, no shadow, no gradient**
- group titles *outside* the cards
- rows as *title · value · chevron*
- a single accent colour (`#3B82F6`) reserved for state
- bottom navigation with three pages

The conclusion: **removing "AI flavour" is subtraction.** Drop the gradient, drop the extra accent
colours, drop decorative elements, and let spacing and grey levels carry the hierarchy.

### Motion

"Smooth" is not about longer durations; it is about **proportion in the curve**:

- `DecelerateInterpolator` everywhere by default (fast start, slow settle)
- Page switch 260 ms, entrance stagger 300 ms, ripple immediate
- The home list uses `setStartDelay(40 + i * 36)` to cascade, so content *lands* rather than blinking in

2.1 later formalised all of this into `Motion.java` — see `CHANGELOG.md`.

---

## 3. From shake to *duang*

1.0's shake was a symmetric left-right wobble, which reads as **shaking one's head in denial**.

2.0 replaced it with a single impact:

```
translationX:  +9dp → −6.5dp → +1.4dp → 0
translationY:  −9dp → +6.5dp → −1.4dp → 0
```

Starting top-right, ending bottom-left, **with only 16% rebound**. That ratio is deliberate: more
rebound turns it into a spring toy, zero makes it stiff. A `rotation` keyframe adds a hint of being
knocked off balance. Total 420 ms.

2.1 softened the *tap* feedback separately (`tapNudge()`: 4 dp, no overshoot) while **deliberately
keeping the damage shake untouched** — that one is supposed to feel like being hit.

---

## 4. Damage feedback: two attempts

### The abandoned idea

The first plan was to synthesise a "bone crack" in Python with `wave`: noise bursts, band-pass,
low-frequency thump, soft clipping.

**The user rejected it**: "don't hand-roll another pile of crap, go search for it."

That rejection was correct. Programmatically synthesised foley has no chance against Minecraft's
instantly recognisable hurt sound, and it would have burned time for nothing.

### What shipped

- **Sound**: the user's own `hit.mp3` (Minecraft hurt sound), with a 14 KB ID3 cover stripped,
  33,271 → 18,935 bytes, placed in `res/raw/`
- **Red flash**: `ValueAnimator` driving `setColorFilter(Color.argb(a,255,32,32), SRC_ATOP)`,
  alpha 165 → 0 over 620 ms (a miniature of Minecraft's hurt overlay).
  `SRC_ATOP` ensures only the character's own pixels are tinted, not the transparent area.
- **Impact shift**: the keyframes above

All three fire on the same frame; the floating `−¥x.xx` appears 170 ms later. First you get hit,
then you see the number.

---

## 5. Two build-chain traps

### 5.1 `apksigner` writes only one scheme

With `minSdkVersion >= 28`, `apksigner` defaults to **v3 only**. Passing both
`--v2-signing-enabled true --v3-signing-enabled true` still results in:

```
v2 scheme: false
v3 scheme: true
```

— because **v3 supersedes v2 and the tool skips it**. Only disabling v3 produces v2.
Android 10 verifies v3 (introduced in Android 9), so the default is fine. This is noted in `build.sh`.

### 5.2 Why live restyle did not work

`applyStyle()` originally sat inside `show()`'s `if (view == null)` branch. Once the view had been
created, `view` was never null again, so `applyStyle()` never ran again — changing the size required
restarting the overlay. 2.0 added an `ACTION_RESTYLE` path.

---

## 6. Two traps from the 2.1 cycle

### 6.1 `aapt package` needs `-A`

Without `-A <assets dir>`, the `assets/` folder is **not packaged**, and the WebView settings page
opens blank. Easy to miss because the build succeeds silently.

### 6.2 A `%` in a string breaks aapt

The credits contain "100% of the code". Two `%` characters make aapt treat the string as a format
string and fail with:

```
Multiple substitutions specified in non-positional format
```

Fix: declare `formatted="false"` on that string.

---

## 7. The type bug that survived a "fix"

2.1.1 fixed the settings dropdowns by replacing the native `<select>` with a custom picker.
The user reported it **still didn't work** — options were rendered one character per line:

```
[
"
自
动
判
定
```

The picker was never the problem. The option list was sent from Java as a **string** rather than
an array:

```java
p.put("peakModes", arrayToList(...));   // → "[\"a\",\"b\"]"
```

so JavaScript did `list[i]` on a string and received single characters.
2.1.2 returns a real `JSONArray`, and the web side now parses strings defensively.

**Lesson** — when a control misbehaves, check the *data* before blaming the *widget*.
The fix had to reach the source of the data, not just the thing rendering it.

---

## 8. How "correct" was verified without a device

| What | How |
|---|---|
| Cutout quality | Composite onto dark, count "whitish" semi-transparent pixels (2,047 → 0), plus 3× zoom comparisons |
| Layout | Rendered the layout with PIL at 3× density (Chinese glyphs shown as colour blocks; geometry real) |
| Resources packaged | `unzip -l` on the APK for `res/raw/*.mp3`, `res/layout/*.xml`, `classes.dex` |
| Compile-time correctness | `aapt` validates every resource reference; `javac` validates every `R.id.*` |
| Version | `aapt dump badging` |
| Signature | `apksigner verify --verbose` |
| Archive integrity | Rebuild from the snapshot and compare md5 with the shipped APK — byte-for-byte identical |

---

## 9. Open items

- [ ] Real-device visual verification (no device available in the build environment)
- [ ] 1.0's APK was lost and is unrecoverable
- [ ] The character's lines are only genuinely funny in the original Chinese; translations approximate
- [ ] No screenshot in the repository yet

---

## 10. One methodology

Every step of this project did the same thing: **replace a second-hand conclusion with first-hand evidence.**

- The cutout was not judged by "looks fine" but by counting whitish edge pixels
- The UI was not judged by "feels right" but against screenshots the user supplied
- The sound was not "good enough to synthesise" but taken from the original
- The signature was not trusted from documentation but checked with `apksigner verify`

And the oldest lesson of all, learned the hard way: **"I couldn't find it" is not the same as
"it doesn't exist" — read the primary source before drawing a conclusion.**
