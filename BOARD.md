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
  - **Status:** In Progress (Standing by; gating build & QA until @Cline, @Buffy, and @OpenCode complete tasks and signal handoffs)
  - **Scope:** Gradle build pipeline, unit tests, headless emulator verification
  - **Tasks:**
    1. **Build Verification:** Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.
    2. **Emulator QA:**
       - Verify startup launches at the **TOP of the screen** showing the authentic desktop Mochi greeting from the WebUI, then cleanly collapses to the floating bubble.
       - Verify the floating bubble is dark `#141518` (NO white square) and can be dragged across the screen.
       - Verify chat does NOT print `"Opened "` before every response.
    3. Publish the new APK and record the status on `BOARD.md`.

---

## Live Sync & Signals
- [@Boss]: SPRINT 4.2 active. Stop inventing native XML cards. Use the upstream desktop WebUI code directly in the WebView anchored to the top of the screen.
- [@Buffy]: 🏁 **SPRINT 4.2 SWIMLANE DONE — commit `8435ff6`.** Handoff `./tell.sh buffy opencode "dark tokens and bubble background verified"` delivered.
  - **Task 1 white background — root cause found and killed at source:** `coucou_surface` was `#FEF7FF` (M3 baseline LIGHT, **no `values-night` variant**), so `attr/colorSurface` resolved near-white on light-mode devices. That is the white square: `OverlayService.buildFallbackView()` asks for `colorSurface` (`OverlayService.kt:1299`) and `themes.xml` routes MainActivity through the same token. Remapped in `colors.xml` to upstream's dark palette — `coucou_surface` → **`#141518`**, `coucou_on_surface` → **`#F5F6F8`** (so text keeps contrast), `coucou_surface_container` → **`#0E0F11`**. `themes.xml` untouched (it already points at these tokens), so `colorSurface` can no longer render white **anywhere**, including the fallback bubble.
  - **`res/drawable/bg_bubble_card.xml` (new):** the single canonical native bubble background — `#141518` solid + 1dp `#09FFFFFF` hairline + 20dp radius, token references only, zero raw hex. **Deleted `coucou_intro_card_bg.xml`** — an unreferenced native intro-card drawable, exactly the "invented native XML card" this sprint retires (verified zero references before removal; the `coucou_intro_card_bg` color token stays as palette documentation).
  - **`webui/src/style.css`:** `#island { background: #000 }` → **`var(--card)`** so every island container is strictly upstream `#141518`. Built into `webui/dist` as `island-BP04kL3W.css` (confirmed in the artifact). ⚠️ **`app/src/main/assets/coucou` still holds the old bundle** — @Cline's re-stage task ships it; flagged to them.
  - **Task 2 Mochi ~80% fill:** native side already lands at **58dp canvas / 66dp card = 88%** with a subtle 4dp margin (@Cline's `match_parent`+4dp over my `coucou_bubble_card_size`, which they cite in the layout comment). Ink caps at ~60% of the card because `RADIUS_RATIO = 0.3f` (ink = 68% of canvas) — **no token can close that**, it is @OpenCode's renderer task. WebUI mini-Mochi left at upstream asset sizes (29px grid / 13px cells) per "matching upstream assets" — not deviated from.
  - **Verification (exit codes captured directly):** `processDebugResources` **EXIT 0** (after I found and fixed my own defect: CSS double dashes are illegal inside XML comments), webui `npm run build` **EXIT 0**, webui `npm run verify` **EXIT 0** (bridge contract + android bridge + staged bundle, all pass).
  - 🛑 **BLOCKED (not mine): `assembleDebug` + `testDebugUnitTest` are RED on @OpenCode's in-flight `OverlayService.kt:1294` — `Unresolved reference 'Color'`** from `Color.parseColor("#141518")`, which also hardcodes a raw literal colour. Suggested fix sent: `ContextCompat.getColor(context, R.color.coucou_bubble_bg)` or the new `R.drawable.bg_bubble_card`. Every check touching my files passes; the Kotlin suite cannot run until that line compiles.
- [@AGY]: ⏳ Standing by for Sprint 4.2. Gating unit tests, assembly, and emulator QA until `@Cline`, `@Buffy`, and `@OpenCode` finish and dispatch `./tell.sh` handoffs.

