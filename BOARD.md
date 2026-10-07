# AGENT TEAM BOARD (CONCURRENT MODE)

## Active Sprint: SPRINT 4.3 — Authentic Desktop Launch Greeting in WebView (SOLO: @OpenCode)

### The Boss Directive:
We are NOT inventing a native Android XML card or drawing Mochi on a blank black native box!
Our app runs on the **WebView pipeline (`webui`)**. The chat, drop zone, and overview already work because they use the real desktop code from `Louis-CFM/coucou/windows/src/`.
The **desktop launch greeting already exists in that exact web code**:
1. **The Header Bar:** `[Home]` `[Chat]` `[+]` on the left, `[Settings]` `[Audio]` on the right.
2. **The Island Box:** Anchored at the **top** of the screen with `#141518` background.
3. **The Starry Background:** The animated Canvas starfield/particles orbiting behind Mochi.
4. **Mochi in the Center:** Waving hello (`GREET` state) with `coucou_greet.wav`.
5. **The Collapse:** Once the web greeting sequence finishes (~2.5s), it calls `Bridge.collapse()` / `CoucouAndroid.collapse()` to signal Android to switch to the small floating bubble.

---

### Solo Assignment: @OpenCode (Pane 1)

#### 1. Android Overlay Lifecycle (`OverlayService.kt`):
- **Delete/Bypass the Native Intro Hacks:** Remove `overlay_intro_card.xml` or custom native `CoucouCharacterView` intro views. The WebView is our ONLY presentation surface for the island!
- **Launch WebView at the Top:** On `OverlayService` start:
  * Mount the WebView overlay anchored at the **top center of the screen** (`Gravity.TOP or Gravity.CENTER_HORIZONTAL`, `y = 0`), with width ~360dp–390dp and height matching the island (~140dp–160dp).
  * Load `index.html` (the staged webui bundle).
- **Listen for Web Collapse:**
  * In `IslandBridgeHost.kt`, when the WebView triggers `collapse()` / `set_collapsed(true)`, smoothly hide the top WebView island and show the small draggable floating bubble.

#### 2. WebUI Startup Greeting (`coucou-android/webui`):
- Look at upstream `coucou/windows/src/` (`island/`, `views/greeting.ts` or greeting state in `island.ts`):
  * Ensure the web app boots into the **authentic desktop greeting state**:
    - Top header rendered with SVG icons: `Home`, `Chat`, `+` on the left, `Settings`, `Audio` on the right.
    - The starry particle canvas running behind Mochi.
    - Mochi centered, performing the wave animation.
  * When the greeting animation finishes (after ~2.5s), trigger `window.CoucouAndroid.collapse()`.
- Rebuild the web bundle:
  * Run `cd /workspaces/Binder_vps/coucou-android/webui && npm run build` (or staging script) so the updated HTML/JS bundle is copied to `app/src/main/assets/coucou/`.

#### 3. Compile & Verify:
- Build the APK: `cd /workspaces/Binder_vps/coucou-android && ./gradlew assembleDebug`.
- Deploy and verify on emulator / device:
  * Tapping "Start Floating Bubble" displays the authentic desktop island at the top of the screen with its header icons, starry canvas background, and waving Mochi.
  * After the wave, it transitions to the small floating bubble.
- Report completion on `BOARD.md`.
