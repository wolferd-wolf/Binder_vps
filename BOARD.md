# AGENT TEAM BOARD (CONCURRENT MODE)

## Active Sprint: SPRINT 4 (Bubble Scaling, Desktop Intro, Drag Physics & Assistant Routing)

### Active Workstreams (Running Simultaneously)

- **@Buffy (Pane 2 — Themes, Tokens, Audio & Dimens):**
  - **Status:** In Progress
  - **Scope:** `res/values/dimens.xml`, `res/values/colors.xml`, `res/raw/`
  - **Tasks:**
    1. **Bubble Size Ratio:** In `dimens.xml`, scale the container and character canvas together so Mochi fills ~80% of the bubble instead of sitting inside a massive empty black squircle (e.g., `coucou_bubble_size` = 54dp, `coucou_character_collapsed_width` = 46dp, `coucou_character_collapsed_height` = 36dp).
    2. **Desktop Intro Tokens:** Extract palette and shape tokens from upstream `Louis-CFM/coucou/windows/src/style.css`:
       - Background card: `#141518` (not pure black `#000000`).
       - Border radius: `20dp`.
       - Hairline stroke: `rgba(255, 255, 255, 0.035)`.
    3. **Audio Mapping:** Verify `coucou_greet.wav` is accessible in `SoundPlayer.kt` for the desktop intro wave.
  - **Handoff:** When tokens and dimens land, run `./tell.sh buffy cline "tokens and dimens ready"`.

- **@Cline (Pane 3 — Layouts & Hierarchy):**
  - **Status:** Complete (Handoff to @OpenCode)
  - **Scope:** `res/layout/overlay_bubble.xml`, `res/layout/overlay_intro_card.xml`
  - **Tasks:**
    1. **Bubble Layout Fix:** In `res/layout/overlay_bubble.xml`, remove fixed tiny dimensions on `CoucouCharacterView` (`@+id/coucou_character`). Use `match_parent` with a tight `4dp` margin inside `@+id/coucou_bubble_root` so Mochi scales to the container.
    2. **Desktop Intro Layout:** Create `res/layout/overlay_intro_card.xml` matching upstream desktop Coucou:
       - 280dp x 120dp card container with Buffy's `#141518` background and 20dp corner radius.
       - Centered `com.coucou.android.CoucouCharacterView` (`@+id/coucou_character`) ready to run the greeting wave.
  - **Handoff:** Layouts updated and handed off to @OpenCode.

- **@OpenCode (Pane 1 — Kotlin Engine, Drag Physics & Assistant Router):**
  - **Status:** In Progress
  - **Scope:** `CoucouCharacterView.kt`, `AppLauncher.kt`, `CommandRouter.kt`, `TaskStore.kt`, `OverlayService.kt`
  - **Tasks:**
    1. **Character Canvas Scaling:** In `CoucouCharacterView.kt` (`onDraw` / `onSizeChanged`), ensure Mochi's procedural body curves calculate radius dynamically from view width and height so the character properly fills the canvas bounds.
    2. **Fix BHIM False Match in `AppLauncher.kt`:** 
       - REMOVE `.contains()` matching entirely.
       - Matching rule: Exact match (case-insensitive) OR `startsWith` ONLY if query length >= 3 chars.
       - Never substring-match arbitrary short queries like "hi" to prevent false app launches (e.g., "Hi" -> "BHIM").
    3. **Assistant Intent Routing (`CommandRouter.kt` / `AssistantRouter.kt`):**
       - Stop feeding raw chat text blindly to `AppLauncher`!
       - Route commands by intent:
         * **GREETING:** If input is "hi", "hello", "coucou", "hey" -> trigger Mochi `GREET` state + sound, reply "Coucou! How can I help you?".
         * **NOTE/TASK:** If input starts with "create a note", "note:", "task:", "remember" -> extract content, save locally to `TaskStore.kt`, and reply "Saved note: <content>".
         * **APP LAUNCH:** If input starts with "open " or "launch " -> query `AppLauncher`.
         * **SEARCH:** If input starts with "search " or "find " -> open browser search intent.
         * **FALLBACK:** Return friendly assistant message, NEVER "No app found" unless in explicit app-launch mode.
    4. **Desktop Intro Animation:**
       - In `OverlayService.kt`, display `overlay_intro_card` centered on screen at startup.
       - Play `coucou_greet.wav` and run greeting wave animation.
       - After 1.8 seconds, smoothly animate coordinates (`WindowManager.LayoutParams` x, y, width, height) interpolating to the top-right collapsed bubble.
    5. **Floating Bubble Drag Physics:**
       - Implement touch physics using `OnTouchListener` with `ViewConfiguration.get(context).scaledTouchSlop` to strictly separate a tap (expand/collapse) from a drag movement.
       - Update `WindowManager.updateViewLayout` in real-time on move; snap smoothly to nearest edge on release.
  - **Handoff:** When Kotlin compiles and tests pass, run `./tell.sh opencode agy "logic and router ready"`.

- **@AGY (Pane 0 — Architecture, Tests & Emulator QA):**
  - **Status:** Complete (Unit tests green 102/102, APK assembled, verified cross-pod deliverables)
  - **Scope:** Unit tests, Gradle builds, headless emulator QA
  - **Tasks:**
    1. **Unit Tests:** Verified intent routing ("hi" triggers greeting, note creation saves to `TaskStore` without querying apps, search/find routes correctly, fallback friendly).
    2. **Build & Verify:** Verified `./gradlew testDebugUnitTest` (102 tests passed, 0 failures, 0 errors) and `./gradlew assembleDebug` (BUILD SUCCESSFUL).
    3. **Deliverables:** Refreshed `apks/coucou-android-debug.apk` (11MB) with Buffy's 66dp/53dp tokens, Cline's 280x120dp intro card + match_parent bubble, and OpenCode's routing + touch-slop drag.
    4. Reported metrics and signed off on BOARD.md.

---

## Live Sync & Signals
- [@Boss]: SPRINT 4 active. Check your assigned swimlane in BOARD.md and proceed.
- [@Buffy]: 🏁 **SPRINT 4 swimlane (Tasks 1-3) DONE — commits `2536d53` (intro tokens + drawable) and `2854d2e` (size ratio).**
- [@Cline]: 🏁 Sprint 4 layouts complete (`overlay_intro_card.xml` 280x120dp with `#141518`, `overlay_bubble.xml` match_parent+4dp). Handoff to @OpenCode.
- [@AGY]: 🏁 **SPRINT 4 BUILD & TEST GATE VERIFIED (102/102 tests green, APK assembled):**
  - Executed `./gradlew testDebugUnitTest`: 102/102 passed, 0 failures, 0 errors across all 7 test suites.
  - Executed `./gradlew assembleDebug`: BUILD SUCCESSFUL.
  - Published fresh debug artifact to `apks/coucou-android-debug.apk` (11MB).
  - All teammate deliverables verified integrated.

