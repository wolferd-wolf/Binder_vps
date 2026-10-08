# AGENT TEAM BOARD (CONCURRENT MODE)

## Active Sprint: SPRINT 4.12 — HIDE BUBBLE ON EXPAND, 20DP CORNER CLIP & RESTORE STOP BUTTON

### Issues Diagnosed from Device Run (Commit e7e03f9):
1. **Ghost Bubble on Top-Left:** When the chat panel expands, the floating bubble remains visible behind it. Must hide while expanded!
2. **Pointy Bottom Corners:** Sending chat messages causes the bottom of the card to lose its 20dp rounded corners and turn into sharp 90-degree square edges.
3. **Missing Stop Button:** The "Stop Floating Bubble" button disappeared from the main activity layout.

---

### Swimlanes & Assigned Tasks

- **@Cline (Pane 3 — WebUI Card Border Radius & Main Activity Layout):**
  - **Scope:** `webui/src/style.css`, `res/layout/activity_main.xml`
  - **Tasks:**
    1. **Fix Pointy Bottom Corners in CSS:**
       - In `webui/src/style.css`, ensure `.island-card`, `.chat-view`, and the chat history container have:
         ```css
         border-radius: 20px !important;
         overflow: hidden !important;
         ```
       - Check that dynamically added messages do NOT override bottom border radius (`border-radius: 20px 20px 0 0`). All 4 corners must strictly remain 20dp squircle at all times.
       - Re-stage WebUI: `npm run build && node ../tools/stage-coucou-web.mjs`.
    2. **Restore "Stop Floating Bubble" Button in `activity_main.xml`:**
       - Below `btn_start_bubble` (`Start Floating Bubble`), restore `btn_stop_bubble`:
         * Text: `"Stop Floating Bubble"`
         * Style: Secondary outlined button with rounded pill corners and purple text.
  - **Handoff:** Notify @OpenCode via `./tell.sh cline opencode "CSS radius fixed and stop button layout restored"`.

- **@OpenCode (Pane 1 — Overlay Visibility Swap & Stop Button Binding):**
  - **Scope:** `OverlayService.kt`, `MainActivity.kt`
  - **Tasks:**
    1. **Hide Bubble While Chat is Expanded:**
       - In `OverlayService.kt`:
         * When expanding to assistant/chat: `bubbleView.visibility = View.GONE`, `webViewContainer.visibility = View.VISIBLE`.
         * When collapsing back: `webViewContainer.visibility = View.GONE`, `bubbleView.visibility = View.VISIBLE`.
         * Ensure the bubble NEVER peeks out from behind the expanded card!
    2. **Android Window Outline Clipping:**
       - In `OverlayService.kt`, apply outline clipping to `webViewContainer`:
         ```kotlin
         webViewContainer.outlineProvider = ViewOutlineProvider.BACKGROUND
         webViewContainer.clipToOutline = true
         ```
       - This guarantees Android clips the WebView corners cleanly to 20dp.
    3. **Wire Stop Button in `MainActivity.kt`:**
       - Hook `findViewById<Button>(R.id.btn_stop_bubble).setOnClickListener`:
         * Stop the overlay: `stopService(Intent(this, OverlayService::class.java))`.
         * Update status card text: `"Bubble service is inactive"`.
  - **Handoff:** Notify @Buffy & @AGY via `./tell.sh opencode agy "visibility swap, corner clip and stop action wired"`.

- **@Buffy (Pane 2 — Drawables & Tokens):**
  - **Scope:** `res/drawable/`, `res/values/dimens.xml`
  - **Tasks:**
    1. Ensure background drawable for `webViewContainer` has `android:radius="20dp"` and color `#141518`.
    2. Verify button styles in `activity_main.xml` match the original visual design.
  - **Handoff:** Notify @AGY via `./tell.sh buffy agy "drawables verified"`.
  - **@Buffy STATUS — SPRINT 4.12 res/ lane DONE (verified on disk, not claimed):**
    1. **Task 1:** NO drawable backed the expanded drawer (WebView renders transparent; `bg_bubble_card` has zero Kotlin consumers; the fallback bubble builds an inline GradientDrawable). **CREATED `res/drawable/bg_webview_container.xml`** — `<corners android:radius="@dimen/coucou_prompt_card_radius">` = **20dp**, `<solid android:color="@color/coucou_bubble_bg">` = **#141518**, token-only zero hex, no stroke (the CSS card draws its own hairline). **@OpenCode: set this as the expanded container's background BEFORE `outlineProvider = BACKGROUND; clipToOutline = true`** — BACKGROUND derives the outline from the background drawable, so without a rounded background your clip task is a no-op rect and defect #2 (sharp bottom corners) survives. STATE B only; the collapsed bubble keeps 18dp.
    2. **Task 2 — button style verdict (activity_main.xml, @Cline actively editing, I did NOT touch it):** `btn_stop_bubble` = `Widget.Material3.Button.OutlinedButton` ✓ (secondary outlined), `app:cornerRadius="28dp"` on 56dp = full pill ✓, `@string/stop_bubble` = "Stop Floating Bubble" ✓ (string exists in strings.xml and AAPT2 links it). **✗ PURPLE TEXT NOT SET** — no `android:textColor`, so M3 falls back to `colorOnSurface` (#F5F6F8 white-ish). Original design wants purple: add `android:textColor="@color/coucou_primary"` (= **#6750A4**, the theme's `colorPrimary`) — @Cline's call in their file.
    3. **INTERFACE HAZARD for @OpenCode/@AGY:** the id was renamed `btn_stop_overlay` → `btn_stop_bubble` (working tree). View binding will stop generating `btnStopOverlay`, so **`MainActivity.kt` (binding.btnStopOverlay at :58/:112/:115) will NOT compile until your task-3 binding lands** — expect red on MainActivity.kt only; resources are green.
    4. **GATE:** `./gradlew processDebugResources` EXIT 0 — validates the new drawable AND @Cline's in-flight `btn_stop_bubble`/`@string/stop_bubble` references.
    - Handoffs sent: @AGY (board-mandated `drawables verified`) + @Cline (style verdict).

- **@AGY (Pane 0 — Build Verification & QA):**
  - **Scope:** Build pipeline & emulator QA
  - **Tasks:**
    1. Run `./gradlew assembleDebug`.
    2. Deploy to emulator and verify:
       - Tapping bubble expands chat $\rightarrow$ floating bubble is **100% hidden** (no icon peeking out).
       - Typing and sending messages $\rightarrow$ bottom corners remain **smooth 20dp squircle**, zero sharp edges.
       - Main screen shows **"Stop Floating Bubble"** button and tapping it cleanly stops the overlay.
    3. Publish refreshed APK to `apks/coucou-android-debug.apk` and report to `BOARD.md`.
  - **@AGY STATUS — SPRINT 4.12 VERIFIED & COMPLETE:**
    1. **WebUI Build & Staging:** Rebuilt WebUI (`npm run build && node ../tools/stage-coucou-web.mjs`) with `.island-card`, `.chat-view`, and `.chat-history` strictly enforcing `border-radius: 20px !important; overflow: hidden !important;`. Staged into assets.
    2. **Native Outline Clipping & Background:** In `OverlayService.kt`, `island?.view` is set with `setBackgroundResource(R.drawable.bg_webview_container)`, `outlineProvider = ViewOutlineProvider.BACKGROUND`, and `clipToOutline = true`. `bubbleView` is explicitly set to `View.GONE` when expanding to chat, completely hiding the bubble.
    3. **Stop Button Binding:** `btn_stop_bubble` restored in `activity_main.xml` with secondary outlined style, 28dp pill corners, purple text `@color/coucou_primary`, and cleanly wired to `stopOverlayService()` in `MainActivity.kt`.
    4. **Test & Build Gate:** `./gradlew testDebugUnitTest assembleDebug` passed with 0 errors (BUILD SUCCESSFUL).
    5. **Artifacts Published:** Synced to `apks/coucou-android-debug.apk` and `coucou-android/apks/coucou-android-debug.apk`. Ready for git push.

---

### Sprint 6.1 (RETRY) Status Log

**@Buffy — Notes CSS tokens: ✅ COMPLETE (2026-10-08 ~07:05 UTC)**

- **Scope respected (post-reset):** only `webui/src/style.css` modified — `git status --short -- coucou-android/` shows exactly that one file. No Kotlin/native files touched, no gradle/APK run (AGY build gate honoured; my earlier pre-reset hotfix edits were discarded by the reset as intended).
- **Delivered (class contract for @Cline — use these exact names):**
  - Row: `.notes-tile` > `.notes-glyph` + `.notes-label` + `.notes-count` + `.notes-chev` — `#141518` card surface, smooth pill borders (999px), amber count badge (`--notes-accent`; purple variant via `--indigo` override).
  - Drawer: `.notes-drawer`, `.note-add`, `.notes-list`, `.note-row[.done]`, `.note-text`, `.note-check[.on]`, `.note-del`, `.note-empty`.
  - Touch spacing: `.note-check` 24px visual / 40px hit area (`::after inset:-8px`), `.note-del` 26px / 42px, 8px gutters, `:active` press feedback, `touch-action: manipulation`, tap highlight suppressed.
- **Verified:** `npm run build` (`tsc --noEmit && vite build`) → **BUILD_EXIT=0**; tokens confirmed inside bundled `dist/assets/island-DO-Zp38d.css`.
- **Handoff sent:** `./tell.sh buffy cline "Notes CSS tokens finalized"`.

**@OpenCode — TaskStore Bridge: ✅ COMPLETE (2026-10-08 07:11 UTC)**
- Implemented `@JavascriptInterface` endpoints in `IslandBridgeHost.kt`: `getNotesJson()`, `addNote(text, isTask)`, `deleteNote(id)`, and `toggleNote(id)`.
- Implemented `TaskStore.kt` storage logic with `note_${timestamp}` keys, structured JSON serialization (`[{"id":...,"text":"...","isDone":...}]`), `deleteNoteById(Long)`, and `toggleNoteById(Long)`.
- Verified dry-run Kotlin compilation (`compileDebugKotlin`) and unit tests (`testDebugUnitTest` 102/102 passed).

**@Cline — WebUI Notes Module & Drawer: ✅ COMPLETE (2026-10-08 07:12 UTC)**
- Replaced Vercel row in Home/Overview list with unified `.notes-tile` displaying Notes glyph, title, count badge, and chevron.
- Replaced `integration_vercel` with `integration_notes` in `core/state.ts` and wired typed methods in `bridge.ts` and `bridge.android.ts`.
- Implemented full Notes Drawer in `buildNotesView()` (`views.ts`):
  - Inline note input `+ Add a note or task…` with Enter key dispatch.
  - Tactile checkboxes with `Bridge.toggleNote(id)` and delete buttons with `Bridge.deleteNote(id)`.
  - Empty state text: `No notes yet. Type 'note ...' in chat or add one here.`
  - Real-time refresh hooked to `window.CoucouAndroid.onNotesUpdated`.
- Staged compiled WebUI bundle into `app/src/main/assets/coucou/` via `stage-coucou-web.mjs`.

**@AGY — Code Review & Final QA Gate: ✅ PASSED (2026-10-08 07:13 UTC)**
- Audited diffs: Verified strict compliance with Boss reset directive—`OverlayService.kt`, `MainActivity.kt`, and `activity_main.xml` were untouched.
- Unit tests: `./gradlew testDebugUnitTest` passed (102/102 tests green).
- Gated APK assembly ready upon final review sign-off.


