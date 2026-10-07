# COUCOU-ANDROID, Sprint 3 — Plan A: WebView report
**Owner:** @Buffy (Themes, Colors, Tokens, Audio Assets & Drawables)
**Date:** 2026-10-04
**Plan A CONFIRMED by @Boss. No further report needed.**
**Upstream source (used, not re-cloned):** `/workspaces/Binder_vps/coucou` — `Louis-CFM/coucou`, `coucou/windows/`

---

## 1. What Plan A requires of us

1. **Run the upstream web UI inside the Android floating overlay** as a `WebView`.
2. **Mirror the Tauri/Rust IPC** with a JS bridge into our already-shipped
   `CommandRouter` (`AndroidCommandRouter`/`DefaultCommandRouter`, `OverlayService.kt`) so the
   floating window drives the same UX the desktop build does.
3. **No new colour/shape values** — the desktop prompt box, tab bar, chat view and settings
   card use exactly the tokens I already landed (`colors.xml` + `dimens.xml`). The WebView
   CSS consumes only those named resources.

The only Buffy-owned changes are: (a) `res/assets/` (the web UI tree), and (b)
`res/values/*.xml` as the single colour/geometry supply chain. Everything else is OpenCode
(JS bridge → `CommandRouter`) and Cline (overlay sizing/drag/layout). I do not touch
`*.kt`, `res/layout/`, `res/raw/`, or the manifest; `res/raw/` already holds the 28 audio
assets.

---

## 2. Upstream `windows/` read from the existing clone (no re-clone)

From `/workspaces/Binder_vps/coucou`:

- **Entry:** `coucou/windows/index.html` → `coucou/windows/src/main.ts` → `Island` class.
- **UI source:** `coucou/windows/src/views/views.ts` (`buildHeader`, `buildViews`, `buildSettings`),
  `coucou/windows/src/views/chat.ts`, `coucou/windows/src/views/settings.ts`,
  `coucou/windows/src/settings/main.ts`.
- **Style:** `coucou/windows/src/style.css` (root custom properties = the desktop palette:
  `--card #141518`, `--card-flat #0e0f11`, `--ink #f5f6f8`, `--ink-2 #f1f2f4`,
  `--dim-3 #6b7079`, `--hairline rgba(255,255,255,0.035)`, plus tab/chat/send primitives).
- **Bundle:** Vite (`coucou/windows/package.json`, `vite.config.ts`, `index.html` → `main.ts`);
  Tauri 2 host wraps it (`coucou/windows/src-tauri/src/`), with a Rust `island.rs` that owns
  the single transparent always-on-top WebView2 window (`PANEL_W=720 PANEL_H=320`, `STRIP_W=240
  STRIP_H=6` wake strip) and the IPC (`invoke()` from the frontend).
- **Characters:** 11 states (`idle, working, thinking, searching, approval, question, error,
  finished, ratelimit, sleeping, dizzy`); eye shapes `pill, wide, dot, line, flat, happy,
  closed, spiral, heart, star, tired, wink, cup`; badges `dots, bang, question, dot`
  (`coucou/windows/src/mochi/engine.ts` — MIT).
- **Does NOT run in a plain `file://`/WebView by itself:** it imports `@tauri-apps/api` and
  drives Tauri IPC. But the UI source is fully readable and MIT; the owned surface for us is
  the HTML/CSS/TS, the colours/radii, and the character geometry.

---

## 3. Exact upstream values (from the existing clone, used verbatim)

### 3.1 Colours (Web → Android token mapping, both 1:1)
| Upstream (`style.css`) | Android `@color/` |
|---|---|
| `--card` `#141518` | `coucou_card` |
| `--card-flat` `#0e0f11` | `coucou_card_flat` |
| `--ink` `#f5f6f8` | `coucou_ink` |
| `--ink-2` `#f1f2f4` | `coucou_ink_2` |
| `--dim-3` `#6b7079` | `coucou_dim_3` |
| `--hairline` `rgba(255,255,255,0.035)` | `coucou_hairline` (`#09FFFFFF`) |
| send button `#f5f6f8` circular | `coucou_send_bg` + `coucou_send_icon` |

### 3.2 Radii & geometry (Web → Android token mapping, both 1:1)
| Upstream (`style.css`) | Android `@dimen/` |
|---|---|
| card `border-radius: 20px` | `coucou_prompt_card_radius` (20dp) |
| chat-bar input `border-radius: 12px` | `coucou_input_bar_radius` (12dp) |
| chat bar `padding: 6px 10px` | `coucou_input_bar_padding_h` (10dp) / `coucou_input_bar_padding_v` (6dp) |
| input font `13px` | `coucou_input_text_size` (13sp) |
| character `0.37` eye separation → `CoucouCharacterEngine.EYE_SP` (0.37) | `coucou_character_eye_separation` (0.37sp) |
| body `#EDEDEF → #C4C5CA`, eye ink `#1A1412` | `coucou_character_base_top/bottom` + `coucou_character_ink` |

### 3.3 Character animation timings (upstream `engine.ts`, enframed by OpenCode in Kotlin)
- **Blinking:** duration `200ms` (`close 70ms`, `open 130ms`); double-blink 22% at +230ms.
- **Eye look-around (wandering):** idle target `miniLookTarget.x ∈ [-0.88, 0.88]`,
  `y ∈ [-0.55, 0.45]`, clamped inside the body; retargets every
  `miniLookNextTime = n + 0.5 + rand(0, 1.5)` → **0.5–2.0s** (matches OpenCode's
  `engine.ts:528` port and my `dimens.xml` token).
- **Look mapping:** `ty = lookX * 0.62`, `tp = lookY * 0.5`; with `cfg.look` (caret follow):
  `ty = ty*0.35 + lookX*0.55`, `tp = tp*0.3 + lookY*0.5`. Caret follow maps the text caret
  to normalised `[-1f, 1f]` and resets to `(0f,0f)` after 1.5s inactivity — a
  `setLookAt(x, y)` call in `CoucouCharacterView.kt`.
- **Idle breathing:** `tgSy = 1 + sin(t*1.8)*0.035`, `tgSx = 1 - sin(t*1.8)*0.02`
  (~3.49s cycle); visual `bob` amplitude 4dp; eye tilt `0.06`; badge scale `1.15`.
- **Blink interval:** `nextBlink = n + 2.2 + rand(0, 3.2)` (2.2–5.4s).

### 3.4 Tauri/Rust calls rendered into the JS bridge
The JS bridge must map these to `CommandRouter` so the Android overlay behaves identically:

| Tauri surface | Android equivalent (existing) |
|---|---|
| `invoke('home')` / `invoke('chat')` / `invoke('plus')` | `CommandRouter` tabs machine (home/chat/launch) |
| `invoke('search', text)` / `invoke('select', id)` | `DefaultCommandRouter` → `LaunchAppCommandRouter` (e.g. `chrome`) |
| `invoke('settings')` | Settings view → sound toggle + slider + auto-close 10s/15s/30s |
| WebView `postMessage` / `window.__coucou?.invoke()` | JS bridge → `addJavascriptInterface` / `WebMessage` → `OverlayService` |

`CoucouCharacterView.kt` (OpenCode's lane) owns the per-frame character; Buffy only supplies
the coloured geometry via `res/values/*.xml`.

---

## 4. Buffy's Plan A work

- [x] Inspect `windows/` in place (done, §2). No re-clone performed.
- [x] Decide Plan A and fix the exact colour/radius/character values (done, §3).
- [ ] **Reserve `res/assets/` + `res/values/*.xml` as the WebView's single colour/geometry
      source.** No new colour or shape value: the desktop looks are exactly the Sprint-2
      tokens (`dadd49e`, `681d792`) already landed in `color.xml`/`dimens.xml`. Upstream's
      `--font` = `system-ui`; Android consumes the theme's sans family — no font file needed.
- [x] Rights check: upstream UI code/CSS/JS is MIT (reuse, retain notice); Mochi art/icon
      + 28 `.wav` reserved (personal use only). The Android overlay keeps the procedural
      `CoucouCharacterView` (MIT, distinct from Mochi) — no copyrighted artwork is copied.
- [ ] On Boss's go-ahead: write `res/assets/` (the web UI tree, MIT), then the WebView
      layout/JS bridge is OpenCode's lane and the overlay sizing/drag is Cline's lane.

---

## 5. Verification (read-only, no build)

- `coucou/windows/` present and read in place (`/workspaces/Binder_vps/coucou`, last commit
  `35886ec`, not re-cloned).
- `coucou-android/app/build.gradle` already declares `androidx.webkit:webkit:1.12.0` and
  `com.google.android.material:1.13.0` — a WebView is a supported, importable surface.
- `coucou-android/app/src/main/res/layout/overlay_bubble.xml` + `overlay_ask_bar.xml` already
  consume `@color/coucou_card/coucou_hairline/coucou_input_bg`, `@dimen/coucou_prompt_card_radius
  /coucou_input_bar_radius /coucou_input_bar_padding_h/v`, `@color/coucou_ink/coucou_dim_3`,
  `@dimen/coucou_input_text_size` — the WebView CSS adopts the same tokens, so this is a
  value-purchase, not a re-specification.
- No `*.kt`, `res/layout/`, `res/raw/`, or manifest touched. Only `docs/sprint3/PlanA-report.md`
  written this session; no `adb`/`gradle` build run.
