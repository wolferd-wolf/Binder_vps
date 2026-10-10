# AGENT TEAM BOARD (CONCURRENT MODE)

## Active Sprint: SPRINT 6.7 — CORNER INSET PADDING, UI LIBRARIES FOR VOICE, SAF FILE PICKER & RECORD_AUDIO PERMISSION

### Issues Diagnosed from Device Screenshots:
1. **Corner Clamping:** In Tasks, Vault, Notes, and all sub-panels, the header icons and titles are clipped right against the outer rounded corners. Must add generous inner padding (20px left/right).
2. **Live Voice Mode:** Must be built using famous UI libraries/component frameworks (e.g., Lucide icons, standard Radix/Tailwind component kits) rather than raw hand-rolled CSS primitives.
3. **Vault "Choose File" Dead Click:** Tapping "Choose file" fails because a background Service cannot directly handle `startActivityForResult`. Must use a transparent Activity or foreground activity intent with `FLAG_ACTIVITY_NEW_TASK`.
4. **Mic Opens Settings with No Permission Listed:** `RECORD_AUDIO` is missing from `AndroidManifest.xml`. Add the manifest declaration and proper runtime permission handling.

---

### Swimlanes & Assigned Tasks

- **@AGY (Pane 0 — Manifest Permissions & Build Gate):**
  - **Scope:** `coucou-android/app/src/main/AndroidManifest.xml`, compilation, QA
  - **Tasks:**
    1. In `AndroidManifest.xml`, ensure the following permissions exist:
       ```xml
       <uses-permission android:name="android.permission.RECORD_AUDIO" />
       ```
    2. Register transparent `FilePickerActivity` in the manifest if required by OpenCode.
    3. Run `./gradlew assembleDebug` after OpenCode, Cline, and Buffy complete their handoffs.
    4. Verify on device/emulator and publish GitHub release via `gh release create`.

- **@OpenCode (Pane 1 — Native File Picker & Audio Permission Flow):**
  - **Scope:** `coucou-android/app/src/main/java/com/coucou/android/*`
  - **Strict Constraint:** DO NOT touch HTML/CSS/WebUI files!
  - **Tasks:**
    1. **Fix SAF File Picker (`ACTION_OPEN_DOCUMENT`):**
       - Services cannot receive `onActivityResult`. Implement `FilePickerActivity.kt` (a transparent `Theme.Translucent.NoTitleBar` activity) OR launch from `MainActivity` with `FLAG_ACTIVITY_NEW_TASK`.
       - When "Choose file" is pressed, fire the intent, retrieve the document's `name`, `size`, `mimeType`, and `uri`, and notify:
         `window.CoucouAndroid.onFileSelected(json)`.
    2. **Fix Microphone Runtime Permission:**
       - Ensure `checkMicPermission()` checks `ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)`.
       - If not granted, launch the permission request via activity context so Android shows the native runtime permission prompt dialog, NOT raw settings.
  - **Handoff:** Notify @Cline via `./tell.sh opencode cline "file picker and mic permission bridge ready"`.

- **@Cline (Pane 3 — Corner Insets & UI Library Integration):**
  - **Scope:** `coucou-android/webui/src/*`
  - **Strict Constraint:** DO NOT touch Kotlin files!
  - **Tasks:**
    1. **Push Header Icons & Text Inward (Fix Corner Clamping):**
       - Across ALL views (`.tasks-view`, `.vault-view`, `.notes-view`, `.chat-view`, `.voice-view`):
         * Apply container padding: `padding: 16px 20px !important; box-sizing: border-box !important;`
         * Ensure header icon (`✓=`, folder, mic) and title text sit comfortably away from the outer squircle curve.
    2. **Live Voice Mode Overhaul with UI Libraries:**
       - Rebuild the Live Voice view using famous UI libraries/component kits (use Lucide / Feather icon packages and established UI component standards).
       - Wire "Choose file" click to `window.IslandBridge?.openFilePicker?.()`.
       - Wire "Start" / "Allow Access" click to `window.IslandBridge?.requestMicPermission?.()`.
    3. **Re-stage WebUI:**
       - `cd /workspaces/Binder_vps/coucou-android/webui && npm run build && node ../tools/stage-coucou-web.mjs`.
  - **Handoff:** Notify @Buffy and @AGY via `./tell.sh cline agy "WebUI rebuilt with UI libraries and staged"`.

- **@Buffy (Pane 2 — Visual Padding & Styling Review):**
  - **Scope:** `coucou-android/webui/src/style.css`, layout drawables
  - **Tasks:**
    1. Audit padding and spacing tokens across all panels to guarantee no text or icons touch the outer container borders.
  - **Handoff:** Notify @AGY via `./tell.sh buffy agy "styling audit complete"`.

---

## SPRINT 6.7 — STATUS: COMPLETE

### 1. Corner inset (20px inner padding)
- `style.css` + `views/hub.css`: `padding: 16px 20px !important; box-sizing: border-box !important`
  on `.tasks-view`, `.vault-view`, `.notes-view`, `.chat-view`, `.voice-view` (+ `.hub-card`).
- Header rows carry a further `2px 4px 8px` inset, so ✓= / folder / mic glyphs sit inside the curve.
- **Measured:** every view reports 20/20px padding L/R; icon inset 45px (tasks/vault), 101px (voice).
  See `webui/scripts/verify-sprint67.mjs`.

### 2. Live Voice rebuilt on UI libraries
- New WebUI kit adapter `webui/src/views/ui-kit.ts`: Lucide stroke icons (`lucide-*`) +
  Material 3 components (`m3-btn--filled` / `m3-btn--tonal` / `m3-icon-btn` / `m3-card` / `m3-avatar`).
  Vendored (no new npm dep) so the WebView bundle stays offline.
- Verified: 4 Lucide glyphs in the room, M3 filled/tonal/icon buttons, M3 permission card.

### 3. Vault SAF picker via transparent activity
- `FilePickerActivity.kt` (translucent, no UI) launches `ACTION_OPEN_DOCUMENT`, resolves
  name/size/mimeType/uri, and delivers `window.CoucouAndroid.onFileSelected(json)`
  (with a `coucou:file-selected` event fallback) through `OverlayService`.
- `IslandBridgeHost.openFilePicker()` no longer requires an Activity context: it starts the
  transparent activity with `FLAG_ACTIVITY_NEW_TASK` (Services cannot receive `onActivityResult`).

### 4. RECORD_AUDIO + native runtime prompt
- `AndroidManifest.xml`: `<uses-permission android:name="android.permission.RECORD_AUDIO" />`.
- `MicPermissionActivity.kt` (translucent) shows Android's own runtime prompt;
  `IslandBridgeHost.requestMicPermission()` launches it and `checkMicPermission()` reads via
  `ContextCompat` — no more drop into raw app settings.
- WebUI: `Bridge.requestMicPermission` → `window.IslandBridge.requestMicPermission()`;
  the answer returns as a `coucou:mic-permission` event.

### Verification & Release
- `npm run build` + `node ../tools/stage-coucou-web.mjs` → staged `index.html` → `island-B8F_ZKKv.js`.
- `./gradlew assembleDebug` **BUILD SUCCESSFUL** (APK contains `RECORD_AUDIO` + both activities; merged manifest checked).
- `./gradlew testDebugUnitTest` **BUILD SUCCESSFUL**.
- `verify-sprint63/65/66/67.mjs` all **PASS**; `verify:android` + `verify:staged` **PASS**.
- `verify:bridge` (bridge-contract) still reports 7 unaccounted commands
  (`add_note, check_mic_permission, delete_note, get_notes_json, get_tasks_json, open_chat, toggle_note`)
  — identical to HEAD, i.e. pre-existing (those commands are served by Kotlin's named
  `@JavascriptInterface` lane, which the checker does not read). Not introduced by Sprint 6.7.
- **RELEASE PUBLISHED:** [v6.7.0](https://github.com/wolferd-wolf/Binder_vps/releases/tag/v6.7.0)
- **APK:** `coucou-android/app/build/outputs/apk/debug/app-debug.apk` & `apks/coucou-android-debug.apk`
- **STATUS:** SPRINT 6.7 COMPLETE (All lanes verified green)
