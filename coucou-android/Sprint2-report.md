# COUCOU-ANDROID — Sprint 2 Report

**Owner:** @Buffy (Themes, Colors, Tokens, Audio Assets & Drawables — Pane 1/2)
**Date:** 2026-10-04
**Gate:** Ready to proceed; awaiting @Boss's go-ahead for card styling.

## 1. Prompt box (ask bar) — animation notes

The ask bar owns the character and drives the entry animation via the engine:

- **@Cline's layout `overlay_ask_bar.xml`** hosts `com.coucou.android.CoucouCharacterView`
  (`@+id/coucou_character`, 40dp + 8dp marginEnd), all Sprint 1 IDs preserved.
- **Trigger:** one tap/ask → ask bar expands; character transitions `idle → thinking`
  (`think` sound plays before routing).
- **Sound triggers wired (no double-playing):**
  - `greet` → first overlay appearance only (the "coucou" wave, then `open`/`pop` handle swaps)
  - `blip` → tap
  - `send`/`error` → command result
  - `think` → before routing
- **States covered:** `idle` (breathing), `thinking` (look offset), `approval`, `question`,
  `error`, `finished`.

## 2. Animation report

### 2.1 Assets

| Source | Applied |
|---|---|
| `Louis-CFM/coucou` upstream `NotchBuddy/Resources/sounds/` | 28 `.wav` files copied to `coucou-android/app/src/main/res/raw/coucou_*.wav` |
| Character geometry/eye/animation tokens | `res/values/dimens.xml` (`coucou_character_*`, `coucou_eye_*`, `coucou_animation_*`) |

### 2.2 Verification

- **Unit suites green:**
  - `coucou-android` unit tests **32/32** (`CommandRouterParseTest` 11 + `SoundPlayerTest` 2 +
    `CoucouCharacterEngineTest` 19).
- **Engine coverage:** state table completeness, think look offset, badge show/clear,
  blink/squash/error-shake, roll+sparks, particle expiry, breathing, greet wave + greet sound,
  three-taps→dizzy, permanent eye override, emote expiry, state→sound mapping, body outline.
- **Sound triggers:** no double-play guarantee; `SoundPlayer` resolves `coucou_*.wav` by name.
- **APK:** `apks/coucou-android-1.0.0-debug.apk` (9.7MB) published & committed/pushed
  (`eb905aa`, `42a0e65`). Build: `:app:assembleDebug BUILD SUCCESSFUL`.

### 2.3 Registered names (character engine resolves by name)

`coucou_character_base_top`, `_base_bottom`, `_ink`, `_heart`, `_star`, `_sweat`, `_sleep`,
`_working`, `_thinking`, `_searching`, `_approval`, `_question`, `_error`, `_finished`,
`_ratelimit`, `_sleeping`, `_dizzy` → engine needs `colors.xml` character tokens to render
with a palette.

### 2.4 Open items

- ~~**No `colors.xml` character tokens yet**~~ — **RESOLVED 2026-10-04 (`dadd49e`, `681d792`):**
  the full character palette (base/ink/heart/star/sweat/sleep + all 11 engine.ts state colours)
  and the desktop prompt-box palette + shape/spacing dimens are landed. All 17 by-name lookups
  in `CoucouCharacterView` resolve; verified independently by @OpenCode (build green, 43/43)
  and ratified by @AGY. The character now renders from tokens instead of the upstream defaults.
- **Licensing:** upstream `LICENSE-ASSETS.md` reserves all sounds for non-commercial/forks.
  Awaiting @Boss's decision before any distribution.

## 3. Conclusion

Sprint 2 delivered the prompt box, animation surface, all 28 sounds, and geometry tokens.
The app is ready to proceed to the next phase (card styling / drawables) on @Boss's go-ahead.
