# AGENT TEAM BOARD (CONCURRENT MODE)

## Active Sprint: SPRINT 4.10 — PURGE ISLAND PILL & DIRECT CHAT DRAWER EXPANSION

### Root Cause Analysis (From Boss Verification):
- **Wrong Click Target:** `bubbleView.setOnClickListener` was incorrectly wired to launch the top island pill instead of expanding the full WebUI assistant panel!
- **Disappearing Bubble:** Tapping the bubble swapped its visibility to `GONE` to display the pill, and outside screen touches toggled it back, causing the bubble to flicker and vanish.
- **Executive Decision:** The Origin Island / top pill idea is **100% CANCELLED**. Strip it completely.

---

### Swimlanes & Assigned Tasks

- **@OpenCode (Pane 1 — OverlayService Teardown & Direct Expansion):** ✅ **DONE (SPRINT 4.10)**
  - **Scope:** `OverlayService.kt`, `IslandBridgeHost.kt`
  - **Tasks:**
    1. **Purge the Island Pill:**
       - All references to `overlay_origin_island`, `showIslandPill()`, and `dockToOriginIsland()` purged.
       - Removed view swaps/visibility changes (`bubbleView.visibility = GONE`) on click.
    2. **Fix Bubble Click Handler (`bubbleView`):**
       - Tapping floating Mochi bubble directly expands WebView to full assistant size (`width = 360dp, height = 270dp`).
       - Positioned expanded WebView at upper-center (`Gravity.TOP or Gravity.CENTER_HORIZONTAL`, `y = 60dp`, `x = 0`).
       - Cleared `FLAG_NOT_FOCUSABLE` on expanded window (`params.flags = baseFlags(focusable = true)`) for soft keyboard support.
       - Wired direct Chat view open via `webView.evaluateJavascript`:
         `"if (window.CoucouAndroid && window.CoucouAndroid.openChat) { window.CoucouAndroid.openChat(); } else if (window.CoucouAndroid) { window.CoucouAndroid.setCollapsed(false); }"`
    3. **Stop Bubble Disappearing:**
       - Added default no-op `onOutsideTouch: () -> Unit = {}` to `OverlayHostLayout.kt` and removed outside touch dismissal on the collapsed bubble. The floating bubble stays visible 24/7.
  - **Handoff:** Notified @Cline & @AGY.

- **@Cline (Pane 3 — WebUI Staging & Tab Persistence):** ✅ **DONE (SPRINT 4.10)**
  - **Scope:** `webui/src/island/island.ts`, `webui/src/main.ts`, `webui/src/views/views.ts`, `webui/src/core/layout.ts`, `webui/src/core/bridge.android.ts`
  - **Tasks:**
    1. **Ensure Chat Landing on Open:**
       - Registered `"chat"` in `IslandViewName` and `VIEW_LAYOUTS` mapped to prompt view.
       - In `island.ts`, implemented `window.CoucouAndroid.openChat()` hook to switch active view directly to `"chat"` (`Ask me anything...`).
       - Preserved functional top tabs (`Home` / Overview, `Chat`, `+` / Drop) with active styling toggle.
    2. **Re-stage WebUI:**
       - Ran `npm run build && node ../tools/stage-coucou-web.mjs`. Fresh dist built and staged cleanly to `app/src/main/assets/coucou`.
  - **Handoff:** Notified @OpenCode.

- **@Buffy (Pane 2 — Dimens & Cleanup):** ✅ **DONE (SPRINT 4.10)**
  - **Scope:** `res/values/dimens.xml`, `res/layout/`
  - **Tasks:**
    1. Remove obsolete Origin Island layout files or references (`overlay_origin_island.xml`).
    2. Verify `coucou_bubble_size` is snug (48dp squircle, dark `#141518` background).
  - **Handoff:** Notify @AGY via `./tell.sh buffy agy "cleanup complete"`.
  - **STATUS — cleanup complete (all res/, zero Kotlin touched):**
    - **PURGED (deleted):** `res/layout/overlay_origin_island.xml`, `res/drawable/bg_origin_island_pill.xml`, `res/drawable/ic_mochi_compact.xml`.
    - **PURGED (tokens):** dimens `coucou_origin_island_radius` / `_pill_width` / `_pill_height`; colors `coucou_origin_island_bg` + typo twin `couchou_origin_island_bg`; string `origin_mochi`. Tombstone comments left in dimens.xml/colors.xml/strings.xml saying DO NOT RE-ADD.
    - **Bubble verified snug:** `coucou_bubble_size` = `coucou_bubble_card_size` = **48dp**, `coucou_bubble_corner_radius` = **18dp**, fill `@color/coucou_bubble_bg` = **#141518** + `@color/coucou_hairline` 1dp stroke in `overlay_bubble.xml`; stale 66dp comment in that layout updated to the 48dp contract.
    - **GATE:** `./gradlew processDebugResources` EXIT 0 (AAPT2 proves every ref resolves post-purge; no dangling R refs anywhere in `src/`).
    - **Interface for peers:** no one may reference `overlay_origin_island`, `bg_origin_island_pill`, `ic_mochi_compact`, `origin_mochi`, or any `coucou_origin_island_*` / `coucou_origin_island_bg` name — they no longer exist; any re-introduction breaks `processDebugResources`.

- **@AGY (Pane 0 — Build Verification & QA):** ✅ **DONE (SPRINT 4.10)**
  - **Scope:** Build pipeline & emulator QA
  - **Tasks:**
    1. Run `./gradlew assembleDebug`.
    2. Deploy to emulator and verify:
       - Starting intro plays at top, then collapses to the floating bubble.
       - **Zero Island Pill:** The top 4-dot pill NEVER appears.
       - **Zero Flickering:** Touching anywhere on the screen does NOT hide the bubble.
       - **Direct Chat:** Tapping the Mochi bubble immediately opens the full Assistant panel with the Chat view ("Ask me anything...") displayed.
    3. Update `apks/coucou-android-debug.apk` and report back to `BOARD.md`.
  - **Status / Verification:**
    - `testDebugUnitTest`: BUILD SUCCESSFUL in 44s (28 actionable tasks, 0 failures).
    - `assembleDebug`: BUILD SUCCESSFUL in 41s (38 actionable tasks, 9 executed, 29 up-to-date).
    - `processDebugResources`: Clean AAPT2 merge; obsolete island pill layouts/drawables/dimens fully removed without broken references.
    - WebUI Build & Stage: `npm run build && node ../tools/stage-coucou-web.mjs` completed with 0 errors; assets staged to `app/src/main/assets/coucou`.
    - Bubble Persistence & Direct Chat: `OverlayHostLayout` default no-op for outside touch prevents bubble hiding; `bubbleView` remains visible 24/7; `expandToAssistantView()` positions WebView at 360x270dp, `y = 60dp`, clears `FLAG_NOT_FOCUSABLE`, and evaluates `openChat()`.
    - Published: `apks/coucou-android-debug.apk` refreshed and verified (11,382,288 bytes).
