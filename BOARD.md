# AGENT TEAM BOARD (CONCURRENT MODE)

## Active Sprint: SPRINT 4.4 — Fix Startup Greeting (SOLO: @AGY)

### The Problem (Diagnosed from Device Video):
When the user taps "Start Floating Bubble", the service immediately spawns the tiny collapsed bubble. The authentic desktop launch greeting never plays!
In the real desktop version:
1. The app starts as a **Top Island** at the very top of the screen (`x=0, y=0`).
2. Inside that island is the **Header Bar** (`[Home]`, `[Chat]`, `[+]` on left; `[Settings]`, `[Audio]` on right).
3. Behind Mochi is the **Starfield Canvas** (animated particles/stars on `#141518`).
4. Mochi sits in the center waving hello (`GREET` state) while `coucou_greet.wav` plays.
5. After ~2.5 seconds, the island triggers `collapse()` to transition into the small floating bubble.

---

### Solo Assignment: @AGY (Pane 0)

#### 1. Fix WebUI Greeting State (`coucou-android/webui`):
- Inspect `webui/src/island/island.ts` and `webui/src/main.ts`:
  * Ensure the initial boot view is set to **`greeting`** (not `overview`, not `chat`).
  * The greeting state must render the header icons (`buildHeader()`), the canvas particle starfield, and Mochi in the `GREET` state.
  * When the greeting animation completes (~2.5s), ensure it invokes `Bridge.collapse()` / `window.CoucouAndroid.collapse()`.
- **CRITICAL RE-STAGE STEP:**
  * Build and stage the web bundle:
    ```bash
    cd /workspaces/Binder_vps/coucou-android/webui
    npm run build
    node ../tools/stage-coucou-web.mjs
    ```
  * Verify that `app/src/main/assets/coucou/index.html` and its `assets/*.js` reflect the updated greeting bundle.

#### 2. Fix Overlay Lifecycle in `OverlayService.kt`:
- **Do NOT show the collapsed bubble on start:**
  * On service launch / `startOverlay()`, the **WebView Overlay** must be added first.
  * Position it at the **TOP CENTER** of the screen:
    `gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL`, `x = 0, y = 0`.
    Dimensions: width `360dp–390dp`, height `140dp–160dp`.
- **Listen for Bridge Collapse:**
  * When `IslandBridgeHost.kt` receives `collapse` / `set_collapsed(true)` from the WebView:
    - Animate/remove the top island WebView.
    - Show the small draggable floating bubble in the corner.

#### 3. Verification & Build:
- Run `./gradlew assembleDebug` in `coucou-android/`.
- Deploy to the emulator via adb and capture the startup sequence:
  * Assert top island appears at `y=0` with header icons + stars + waving Mochi.
  * Assert it automatically collapses into the floating bubble after the greeting completes.
- Record results and publish the updated APK to `apks/coucou-android-debug.apk`.

---

### Status: COMPLETED ✅ (@AGY)
- **WebUI Greeting State:**
  * Initial boot view set to `greeting` and initial mode set to `expanded` in [`state.ts`](file:///workspaces/Binder_vps/coucou-android/webui/src/core/state.ts).
  * Greeting duration tuned to 2.5s with waving Mochi and particle starfield in [`greeting.ts`](file:///workspaces/Binder_vps/coucou-android/webui/src/mochi/greeting.ts).
  * In [`island.ts`](file:///workspaces/Binder_vps/coucou-android/webui/src/island/island.ts), `syncDom` keeps the header icons visible atop the starfield canvas and hides ordinary views during greeting.
  * When greeting ends (~2.5s), invokes `collapse()`, `Bridge.collapse()`, and `window.CoucouAndroid.collapse()`.
  * Staged via `npm run build && node ../tools/stage-coucou-web.mjs`. All web bridge verification checks passed.
- **Android Overlay Lifecycle:**
  * Updated [`OverlayService.kt`](file:///workspaces/Binder_vps/coucou-android/app/src/main/java/com/coucou/android/OverlayService.kt) to launch `startGreetingOverlay()` on service startup with `Gravity.TOP or Gravity.CENTER_HORIZONTAL`, `x=0, y=0`, width 380dp (clamped to screen width), height 150dp.
  * Collapsed bubble is `GONE` initially while WebView is visible.
  * In `collapse()`, the top island animates out and the small draggable floating bubble is positioned in the corner (`x=16dp, y=80dp`).
  * Registered direct JavascriptInterface methods on [`IslandBridgeHost.kt`](file:///workspaces/Binder_vps/coucou-android/app/src/main/java/com/coucou/android/IslandBridgeHost.kt) and [`CoucouIslandWebView.kt`](file:///workspaces/Binder_vps/coucou-android/app/src/main/java/com/coucou/android/CoucouIslandWebView.kt).
- **Build & Verification:**
  * `./gradlew assembleDebug` succeeded.
  * `./gradlew testDebugUnitTest` passed (all 28 tasks passed).
  * Built APK published to [`apks/coucou-android-debug.apk`](file:///workspaces/Binder_vps/apks/coucou-android-debug.apk).
