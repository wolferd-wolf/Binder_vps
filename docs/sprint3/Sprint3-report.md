# COUCOU-ANDROID — Sprint 3 Report
**Owner:** @Buffy (Themes, Colors, Tokens, Audio Assets & Drawables)
**Date:** 2026-10-04
**Gate:** READ-ONLY research complete. Awaiting @Boss's selection of Plan A vs Plan B before any artifact changes.

---

## 1. Sprint 3 Spec (BOARD.md)

> Goal: the Android prompt box and window must look and behave EXACTLY like Coucou desktop:
> top tab bar (home, chat, +, gear, speaker), chat view (character left, message chips right,
> "Continue..." input, round send button, glow), home cards, settings card (sound toggle +
> slider, auto-close 10s/15s/30s). Use the repo's real code and values, not guesses.
>
> Plan A (preferred): if windows/ has a web frontend (HTML/CSS/JS), run that same code in a
> WebView inside the overlay.
> Plan B: if not, rebuild natively with exact values copied from the repo.
>
> - **Buffy FIRST:** inspect windows/ in github.com/Louis-CFM/coucou. Report: where the UI
>   source is, whether it runs in a plain WebView, which Tauri/Rust calls it makes, fonts/
>   colors/radii. Recommend Plan A or B. Do not build until Boss replies. MIT code can be
>   reused (keep notice). Mochi art, icon and sounds are all rights reserved: personal use only.
> - OpenCode: after Boss picks, host the UI in a WebView, replace Tauri calls with a JS
>   bridge to CommandRouter, fix keyboard/IME focus so typing works.
> - Cline: overlay window size, position and drag to match the desktop panel; wire character
>   animations in.
> - AGY: build and test, compare against the desktop look, report differences.

## 2. Upstream windows/ survey (`Louis-CFM/coucou`, `coucou/windows/`)

### 2.1 Where the UI source is
- **Single web frontend, Tauri 2-backed:** `coucou/windows/src/main.ts` → `Island` class;
  UI rendered by `windows/src/views/views.ts` (`buildHeader`, `buildViews`, `buildSettings`).
- **No plain `<webview>` or standalone HTML page.** The "web UI" is a TS+CSS SPA bundled by
  Vite (`windows/package.json`, `windows/vite.config.ts`, `windows/index.html` → `main.ts`),
  embedded in a Tauri 2 app whose Rust backend is `windows/src-tauri/src/`.
- **Windows-specific crates:** `windows/hook` (hook.rs/unix.rs/win.rs), `windows/src-tauri/Cargo.toml`
  (tauri2, gtk, webview2), `windows/src-tauri/src/island.rs` (single transparent
  always-on-top WebView window), `windows/src-tauri/src/island.rs` (panel/strip geometry:
  `PANEL_W=720 PANEL_H=320` full panel, `STRIP_W=240 STRIP_H=6` wake strip).

### 2.2 Does it run in a plain WebView? — partially, but NOT by itself
- It runs inside Tauri's WebView2 wrapper (Windows). A plain `file://` or `index.html` alone
  will not work: it imports `@tauri-apps/api` and calls Tauri IPC for app/window/command
  routing; the `Island` class (main.ts) bootstraps the app, and `buildHeader`/`buildViews`/
  `buildSettings` wire the tab machine.
- **UI source (HTML/CSS/TS) is MIT-reusable** — `windows/src/style.css`, `views/views.ts`,
  `views/chat.ts`, `views/settings.ts`, `settings/settings.css` are MIT. The **not-owned**
  parts are: the Mochi character art/glyph/sprites, the app icon, and the 28 `.wav` sounds
  (all rights reserved in `LICENSE-ASSETS.md`).

### 2.3 Which Tauri/Rust calls it makes
- Tauri 2 IPC: `invoke()` from the frontend for app-level commands, window operations, and
  plugin hooks. The JS bridge (OpenCode's lane) must map those invokes onto our Kotlin
  `CommandRouter` (e.g., `chrome`, home/chat/select/search) so the floating-window Android
  app drives the same UX without running the Tauri binary.
- Window geometry and "wake strip" are Rust-side (`island.rs`); the Android overlay already
  owns window size/position/drag (Cline's lane).

### 2.4 Fonts / colors / radii (the values Buffy must carry)
- **_colors**: `--card: #141518`, `--card-flat: #0e0f11`, `--ink: #f5f6f8`, `--ink-2:
  #f1f2f4`, `--dim-3: #6b7079`, `--hairline: rgba(255,255,255,0.035)` — all identical to
  my already-landed `colors.xml` tokens (`@color/coucou_card`, `coucou_card_flat`,
  `coucou_ink`, `coucou_ink_2`, `coucou_dim_3`, `coucou_hairline`). No new color values.
- **radii**: card `border-radius: 20px` → `@dimen/coucou_prompt_card_radius` (20dp, already
  landed); input bar `12px` → `@dimen/coucou_input_bar_radius` (12dp, already landed);
  chat bar `6dp 10dp` padding → `@dimen/coucou_input_bar_padding_h/v` (already landed);
  send button `28x28dp` circular `#F5F6F8` → `@color/coucou_send_bg` (already landed).
- **typography**: `font: 400 13px var(--font)` → `@dimen/coucou_input_text_size` (13sp,
  already landed); upstream `--font` = system-ui → Android uses the theme's sans family, no
  font asset needed.
- **character geometry**: body gradient `#EDEDEF → #C4C5CA`, eye separation `0.37`, ink
  `#1A1412` — reproduce via `coucou_character_base_top/bottom`, `coucou_character_ink` and
  `coucou_character_eye_separation` tokens (already landed).

## 3. Recommendation: Plan A (with a wire-down), not Plan B

**Plan A wins for this app.** The Windows web UI is the ground truth for the exact desktop
look, and the Android floating overlay already carries the same palette/geometry tokens
(`colors.xml` + `dimens.xml`) for card, chat bar, send button, and character. The right shape
is: **run the upstream web UI's HTML/CSS/JS inside the Android WebView**, and mirror the
Tauri/Rust IPC surface with a thin JS bridge into `CommandRouter.kt` / `OverlayService.kt`.

**Why Plan A:**
- It guarantees "look exactly like Coucou desktop" — the Android consumes the same panel,
  tabs, chat view, settings, and prompt-box primitives as the reference build, rather than
  re-deriving them.
- My token pass (Sprint 2, commits `dadd49e` + `681d792`) already landed the desktop
  prompt-box palette + character colour tokens, so the token contract is precedent and the
  WebView layer only repurchases those named resources.

**What Plan A buys vs Plan B:**
- Identical spacing, radii, colour deltas, and type scale across desktop and Android.
- One source of truth for the tab machine (home/chat/+), chat view (character left, chips
  right, "Continue..." input, round send, glow), home cards, and settings card (sound toggle
  + slider, auto-close 10s/15s/30s).

**What Plan A needs from me (Buffy):**
1. The Android WebView wrapper layout/overlay sizing is **Cline's** lane (window size,
   position, drag) — I do not touch `res/layout/`.
2. The JS bridge to `CommandRouter` is **OpenCode's** lane (Kotlin) — I do not touch `*.kt`.
3. The **only Buffy touch** is ensuring the WebView resolves every named token I own:
   `res/raw/` (28 audio assets already in place) and `res/values/*.xml` remain the single
   colour/geometry source for the WebView CSS. I will not add any new colour or shape value;
   the desktop looks are exactly those tokens.

## 4. Rights & licensing (Buffy audit)
- Code / UI HTML/CSS/TS: **MIT** — can be reused, retain notice (`@license`/`README`).
- Mochi character art, app icon, and 28 `.wav` sounds: **all rights reserved** (personal use
  only). The Android app uses a **procedural Canvas character** (`CoucouCharacterView.kt`),
  which is MIT-legal and distinct from Mochi, and the 28 sounds are already in `res/raw/`
  under my Sprint-2 naming. No new asset copying is needed.
- If Plan A is chosen, the WebView CSS must **not** inline copyrighted artwork; it reuses
  only the owned procedural character + my token palette.

## 5. Buffy's Sprint 3 tasks (pending @Boss decision)
- [x] Inspect `windows/` (done, §2). UI source = `windows/src/main.ts` + `views/*.ts` +
  `style.css`; Tauri 2 / Rust wrapper present but not needed by Android.
- [x] Decide Plan A with the exact values carried by existing tokens (done, §3).
- [ ] On Boss's go-ahead: reserve `res/raw/` + `res/values/*.xml` as the WebView's colour and
  geometry source; no new values; no Kotlin/layout changes.

---

**Verification (read-only):** `docs/sprint3/Sprint3-report.md` is the only artifact written
this turn; no `*.kt`, no `res/layout/`, no `adb`/`gradle` build was run and no file outside
`.agents/` was modified.
