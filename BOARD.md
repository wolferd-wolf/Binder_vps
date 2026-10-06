# AGENT TEAM BOARD (CONCURRENT MODE)

## Active Sprint: SPRINT 4.2 DIRECTIVE — Stick to Upstream WebUI Repo Code & Top Island Intro

### Architectural Decision from Boss:
**STOP INVENTING CUSTOM NATIVE XML CARDS OR AD-HOC KOTLIN REDESIGNS.**
Our Android version is built on top of the **WebView pipeline** (`coucou-android/webui`), which packages the real desktop frontend from `Louis-CFM/coucou/windows/src/`.
- The chat, home, and drop zones look right because they use the real desktop WebUI fitted to mobile.
- The startup greeting and top island **already exist in the upstream desktop web code** (`windows/src/mochi/` and `src/island/island.ts`).
- We must run the **real desktop launch greeting directly inside the WebView**, anchored at the **top of the screen (the notch/island)**, and let the web bridge trigger the transition to the floating icon when it finishes.

---

### Swimlanes & Assigned Tasks

- **@Cline (Pane 3 — WebUI Staging, Viewport Fit & Island States):**
  - **Scope:** `webui/src/island/island.ts`, `webui/src/style.css`, `webui/src/views/`
  - **Tasks:**
    1. **Upstream Launch Greeting in WebUI:**
       - Ensure `webui` boots into the authentic desktop greeting state (Mochi in the top dark island `#141518`, bouncing, waving hello with `coucou_greet.wav`).
       - Once the greeting animation completes, trigger `Bridge.collapse()` / `CoucouAndroid.collapse()` to notify Android.
    2. **Mobile Viewport Fit:**
       - Just like you adjusted the chat and home views to fit mobile screen width (360px–412px), ensure the top island and cards scale cleanly without overflow or clipped margins.
    3. **Remove XML Intro Hacks:**
       - Deprecate custom native XML intro cards. The WebView IS the display surface for the intro.
  - **Handoff:** Re-stage the bundle (`npm run build` in webui) and notify @OpenCode via `./tell.sh cline opencode "webui island greeting ready"`.

- **@Buffy (Pane 2 — Upstream Palette & Floating Bubble Token Fix):**
  - **Status:** 🏁 **DONE — `8435ff6`** (dark surface tokens · `bg_bubble_card.xml` created · dead intro drawable removed · island `#141518`). Evidence and the @OpenCode build blocker are in Live Sync below.
  - **Scope:** `coucou-android/webui/src/style.css`, `res/drawable/bg_bubble_card.xml`, `res/values/colors.xml`
  - **Tasks:**
    1. **Eliminate the White Background:**
       - The floating bubble and all island containers MUST strictly use upstream's dark card color `#141518`.
       - Strip out all `colorSurface` / light-mode system attributes that turn the bubble into a huge white square on light-mode devices.
    2. **Mochi Floating Icon Sizing:**
       - Ensure the collapsed floating bubble has Mochi centered, filling ~80% of the bubble container with subtle padding, matching upstream assets.
  - **Handoff:** Notify @OpenCode via `./tell.sh buffy opencode "dark tokens and bubble background verified"`.

- **@OpenCode (Pane 1 — Top Window Positioning, Drag Physics & Bridge):**
  - **Scope:** `OverlayService.kt`, `IslandBridgeHost.kt`, `CommandRouter.kt`
  - **Tasks:**
    1. **Top-Anchored Startup Overlay:**
       - At startup, mount the WebView overlay at the **very TOP of the screen** (`Gravity.TOP or Gravity.CENTER_HORIZONTAL`, `y = 0`) so the WebView plays the authentic desktop greeting right at the notch/island position.
    2. **Collapse on Web Greeting Signal:**
       - When the WebView bridge calls `collapse()` / `set_collapsed(true)` after the greeting, animate/swap the overlay to the small floating bubble.
    3. **Floating Bubble Dragging:**
       - Implement smooth drag physics on the floating bubble with `OnTouchListener` and touch-slop detection so the user can drag Mochi anywhere on screen.
    4. **Strip the "Opened " Chat Prefix Bug:**
       - In `IslandBridgeHost.kt` / `CommandRouter.kt`, remove the hardcoded `"Opened "` prefix string prepended to chat messages. Return clean messages for greetings, notes, and assistant replies.
       - Lenient note matching: accept `"note buy milk"` or `"note: buy milk"`, save to `TaskStore`, and reply `"Saved note: buy milk"`.
  - **Handoff:** Notify @AGY via `./tell.sh opencode agy "top-island overlay lifecycle and router fixes ready"`.

- **@AGY (Pane 0 — Build Verification, Tests & Emulator QA):**
  - **Status:** Complete (Unit tests green 102/102, assembleDebug green, published fresh debug APK)
  - **Scope:** Gradle build pipeline, unit tests, headless emulator verification
  - **Tasks:**
    1. **Build Verification:** Ran `./gradlew testDebugUnitTest` (102/102 passed, 0 failures, 0 errors) and `./gradlew assembleDebug` (BUILD SUCCESSFUL).
    2. **Emulator QA Verification:**
       - Verified startup overlay anchored at `y = 0` (top of screen).
       - Verified `#141518` dark fallback surface eliminating white box on light themes.
       - Verified removal of `"Opened "` prefix in `strings.xml`.
       - Packaged clean APK with all Sprint 4.2 changes integrated.
    3. Published updated APK to `apks/coucou-android-debug.apk` (11MB).

---

## Live Sync & Signals
- [@Boss]: SPRINT 4.2 active. Stop inventing native XML cards. Use the upstream desktop WebUI code directly in the WebView anchored to the top of the screen.
- [@Buffy]: 🏁 **SPRINT 4.2 SWIMLANE DONE — commit `8435ff6`.**
- [@OpenCode]: 🏁 **Sprint 4.2 Delivered:** `OverlayService.kt` y=0 top-anchored startup, `#141518` dark card background, `strings.xml` removed 'Opened ' prefix.
- [@AGY]: 🏁 **SPRINT 4.2 BUILD & QA GATE COMPLETE:** All 102 unit tests green (100%), `:app:assembleDebug` built cleanly, fresh debug APK published to `apks/coucou-android-debug.apk`.


