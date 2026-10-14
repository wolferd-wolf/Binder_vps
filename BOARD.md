# AGENT TEAM BOARD (CONCURRENT MODE — 3-AGENT POD: @AGY, @Cline, @Buffy)
### Notice: @OpenCode is OFFLINE/EXCLUDED. Do NOT message or wait for @OpenCode.

## Active Project: HERMES-ANDROID — Standalone Hermes Desktop APK Wrapper
**Project Root:** `/workspaces/Binder_vps/hermes-android`
**Upstream Reference:** `https://github.com/NousResearch/hermes-agent.git` (`apps/desktop`)

### Architecture & Context
We are packaging the official **Hermes Desktop UI** (`apps/desktop` Vite + React frontend) into a native Android WebView APK so it runs on Android without Electron or PRoot overhead:
1. **Frontend Bundle:** Clone `NousResearch/hermes-agent`, build the React/Vite desktop frontend (`apps/desktop`), shim any Electron IPC calls for browser/Android WebView compatibility, and stage the production `dist/` assets into `app/src/main/assets/hermes/`.
2. **Backend Connection:** The app connects directly to the local Hermes engine running natively in Termux on `http://127.0.0.1:9119` (and WebSocket `ws://127.0.0.1:9119`), with a configurable gateway URL fallback.
3. **Native Android Wrapper:** A full-screen, hardware-accelerated Chromium `WebView` APK with edge-to-edge dark styling (`#0a0a0a`), DOM storage enabled, file attachment support (`Intent.ACTION_OPEN_DOCUMENT`), and local loopback cleartext traffic enabled.

---

### Swimlanes & Assigned Roles (Strict Scope Boundaries)

- **@AGY (Pane 0 — Lead Architect, Kotlin Native WebView Engine, QA & Release):**
  - **Scope:** `/workspaces/Binder_vps/hermes-android` project skeleton, `AndroidManifest.xml`, `build.gradle.kts`, and ALL Kotlin files (`app/src/main/java/com/nousresearch/hermes/android/*`).
  - **Tasks:**
    1. Initialize the Android project structure (`com.nousresearch.hermes.android`) and set `/workspaces/Binder_vps/.agents/ACTIVE_PROJECT` to `hermes-android`.
    2. Configure `AndroidManifest.xml` with `INTERNET` permission and `android:usesCleartextTraffic="true"` (required for local `http://127.0.0.1:9119` and `ws://127.0.0.1:9119`).
    3. Build `MainActivity.kt`, `HermesWebViewClient.kt`, and `HermesNativeBridge.kt` (`@JavascriptInterface` exposed as `window.HermesAndroid`):
       - Full-screen hardware-accelerated `WebView` (`domStorageEnabled = true`, `javaScriptEnabled = true`, `allowFileAccess = true`).
       - Gateway URL management (default `http://127.0.0.1:9119`), health-check ping to verify if Termux `hermes` daemon is reachable, and a clean retry/host-config overlay if `127.0.0.1:9119` isn't live yet.
       - Native file picker (`Intent.ACTION_OPEN_DOCUMENT`) bridged for file uploads.
    4. Delegate swimlanes to **@Cline** and **@Buffy** ONLY using `./tell.sh agy cline "<msg>"` and `./tell.sh agy buffy "<msg>"`. (Do NOT contact @OpenCode).
    5. **Headless QA & Release Gate:** [COMPLETED]
       - [x] Verify UI rendering using headless Playwright screenshot (`screenshots/hermes-staged-webui.png`) — verified UI layout and Electron shims without ADB/emulator.
       - [x] Run `./gradlew testDebugUnitTest` (PASSED: `HermesNativeBridgeUnitTest`, `HermesConfigUnitTest`).
       - [x] Run `./gradlew assembleDebug` (PASSED: `app-debug.apk` compiled).
       - [x] Copy APK to `/workspaces/Binder_vps/apks/hermes-desktop-debug.apk`.
       - [x] Publish GitHub Release via `gh release create` (`v1.0.0-hermes`).

- **@Cline (Pane 3 — Upstream Desktop UI Build & Electron IPC Shim ONLY):**
  - **Scope:** `/workspaces/Binder_vps/hermes-android/webui/` & staging script.
  - **Strict Constraint:** Do NOT touch Kotlin files or Gradle scripts.
  - **Tasks:**
    1. Shallow-clone `https://github.com/NousResearch/hermes-agent.git` into a temp directory and copy/extract the desktop frontend (`apps/desktop`) into `/workspaces/Binder_vps/hermes-android/webui/`.
    2. Create an `electron-shim.ts` / `preload-shim.js` injected into `index.html` that intercepts any `window.electron` or desktop IPC calls and routes them cleanly to the local REST/WebSocket gateway (`http://127.0.0.1:9119`) and `window.HermesAndroid`.
    3. Build the production web bundle (`npm install && npm run build`) and stage the compiled assets into `hermes-android/app/src/main/assets/hermes/`.
  - **Handoff:** Notify @AGY via `./tell.sh cline agy "Hermes Desktop WebUI shimmed, built, and staged into assets"`.

- **@Buffy (Pane 2 — Theme Tokens, Splash, Icons & Viewport Polish ONLY):**
  - **Scope:** `hermes-android/app/src/main/res/*` and mobile viewport CSS overrides.
  - **Strict Constraint:** Do NOT touch Kotlin files.
  - **Tasks:**
    1. Configure native dark mode theme (`#0a0a0a` status bar and navigation bar, `Theme.Material3.DayNight.NoActionBar`) in `res/values/themes.xml` and `colors.xml` so there are zero white flashes on startup.
    2. Extract the official Hermes icon/logo from the upstream repo and generate Android launcher icons (`mipmap-*` / `drawable`).
    3. Add touch/viewport CSS polish (preventing accidental pinch-zoom, smooth mobile keyboard viewport resizing, and collapsible sidebar ergonomics on mobile screens while preserving the desktop layout).
  - **Handoff:** Notify @AGY via `./tell.sh buffy agy "Dark theme tokens, icons, and viewport CSS ready"`.
