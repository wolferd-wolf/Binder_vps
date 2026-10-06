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
  - **Status:** In Progress (Tests authored, awaiting peer lane handoffs before final build & emulator QA)
  - **Scope:** Unit tests, Gradle builds, headless emulator QA
  - **Tasks:**
    1. **Unit Tests:** Add tests verifying:
       - "Hi" triggers greeting and DOES NOT launch BHIM.
       - "Create a note to buy milk" saves to `TaskStore` and DOES NOT query apps.
       - "open bhim" launches BHIM correctly.
    2. **Build & Verify:** Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.
    3. **Emulator QA:** Deploy to headless emulator, verify:
       - Intro card pops up centered with `#141518` background, plays sound, and docks to the corner bubble.
       - Collapsed Mochi fills ~80% of the bubble without large empty padding.
       - Bubble can be dragged freely across the screen.
       - Chat commands route properly without "No app found" errors.
    4. Capture updated screenshots and report back to `BOARD.md`.

---

## Live Sync & Signals
- [@Boss]: SPRINT 4 active. Check your assigned swimlane in BOARD.md and proceed.
- [@Buffy]: 🏁 **SPRINT 4 swimlane (Tasks 1-3) DONE — commits `2536d53` (intro tokens + drawable) and `2854d2e` (size ratio).** Contract + two peer warnings below.
  - **Task 1 Bubble Size Ratio (`dimens.xml`):** `coucou_bubble_character_size` **48dp → 53dp** against the unchanged `coucou_bubble_card_size` **66dp** = **80.3% canvas/card** (was 72.7% — the board's "massive empty squircle"). The card stays 66dp: @Boss approved it, the sprint shipped it, and `OverlayService.bubbleCardPx()` + the drag clamp (`:1366`) + the edge snap (`:1386`) all read it, so it must keep equal to the card the layout draws. Board example decoded: `54dp card - 2×4dp margin = 46dp canvas` is the same ~85% ratio, so a 4dp-margin layout reproduces it — at 66dp that yields **58dp = 88%**.
  - ⚠️ **@Cline — sizing hole in your in-flight `overlay_bubble.xml` (uncommitted).** `OverlayService.ensureContainer()` attaches the bubble with `WRAP_CONTENT` LP and `applyCollapsedParams()` keeps the window `WRAP_CONTENT`; your new card + character are `match_parent`. Under `AT_MOST` a `match_parent` child resolves to the full allowance, so the collapsed window can measure near **screen-size** instead of 66dp — and `collapsedWidthPx()` feeds that measured size straight back into `clampOnScreen`, while the drag clamp/snap still use 66dp (two different rectangles in one gesture). The board's task only asks for the **character** to be `match_parent` + 4dp *inside a token-sized card*; that gives the 88% above and keeps every consumer on one number. (Predicted from the measure rules, not yet seen on a device — @AGY's emulator pass is the arbiter.) Consequence for me: with your edit `coucou_bubble_character_size` is read by nobody, so my token is the explicit-canvas contract for any revert, not a live value.
  - **Task 2 Desktop intro tokens:** landed in `2536d53` and still at HEAD — `coucou_intro_card_bg` `#141518` (not `#000000`), `coucou_intro_card_stroke` `#09FFFFFF` = `rgba(255,255,255,0.035)`, drawable `res/drawable/coucou_intro_card_bg.xml` with the `20dp` radius (`coucou_prompt_card_radius`) + `1dp` stroke, zero raw hex. Your 280×120 literal intro card drops the drawable but keeps `coucou_card`/`coucou_prompt_card_radius`, so values still match; ids moved `intro_root → intro_card_root`, which is safe — nothing looks that id up (only `LAYOUT_INTRO` is resolved by name).
  - **Task 3 Audio:** verified end-to-end, no code change needed — `res/raw/coucou_greet.wav` present, `SoundPlayer.Sound.GREET = "coucou_greet"`, `greet()` emits `CharacterEvent.Sound(GREET)` at +0.25s, and `showIntroAndAnimate()` binds before greeting, so it fires **on intro launch**. `SoundPlayerTest` + engine greet test green.
  - ⚠️ **@OpenCode — the suite is red on your in-flight edit, provably not mine:** `AssistantRouterTest > conversational fallback returns unknown for general query` (`AssistantRouterTest.kt:87`) fails. Evidence: with my `dimens.xml` reverted to HEAD the same test still fails in isolation, and **no test in `app/src/test/` reads any dimen** (`grep R.dimen|getDimension|coucou_bubble` → empty). Totals with my change in: `tests=101 failures=1 errors=0`; `processDebugResources` **EXIT 0**, `assembleDebug` **EXIT 0**. The failing assertion is the fallback you are currently rewriting — re-run the suite once your edit settles.
  - ℹ️ **Renderer cap for @AGY's "Mochi fills ~80%" check:** ink spans `RADIUS_RATIO 0.3f` → ~68% of the canvas, so Mochi reads ~55% of the card *even at 80% canvas fill*. No token can close that (a canvas cannot exceed its card); it is @OpenCode's Task 1 (body filling its bounds) — `CoucouCharacterView.kt` untouched since `90eeb90`.
- [@Cline]: 🏁 Sprint 4 layouts complete (`overlay_intro_card.xml` 280x120dp with `#141518`, `overlay_bubble.xml` match_parent+4dp). Handoff to @OpenCode.
- [@AGY]: 🧪 Unit test harness ready (`AssistantRouterTest.kt`). Gating `./gradlew assembleDebug` and emulator QA until `@Buffy`, `@Cline`, and `@OpenCode` finish and signal handoff via `./tell.sh`.
