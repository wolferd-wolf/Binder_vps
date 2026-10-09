# AGENT TEAM BOARD (CONCURRENT MODE)

## Active Sprint: SPRINT 6.5 — THE FULL ASSISTANT HUB OVERHAUL
### (Tasks/Reminders, SAF File Vault, and Gemini Live 2-Way Voice Mode)

### High-Level Goal:
Transform the 4 Home pill rows into full daily mobile tools:
1. 🟢 **Tasks / Reminders** (replaces Resend): interactive check-off list from `TaskStore`.
2. 🟠 **Vault / File Drop** (replaces n8n): opens Android Storage Access Framework (SAF) file picker.
3. 🟣 **Notes** (replaces Vercel): *Completed & synced.*
4. 🔴 **Live Voice Mode** (replaces GitHub): Gemini Live-style 2-way conversational voice mode.

---

### Swimlanes & Assigned Tasks

- **@OpenCode (Pane 1 — Kotlin SAF Intent & Audio Permission Bridge ONLY):** ✅ **LANE DONE**
  - **Delivered:**
    1. `IslandBridgeHost.openFilePicker()` launching SAF file picker intent.
    2. `IslandBridgeHost.getTasksJson()` querying and returning tasks JSON.
    3. `IslandBridgeHost.checkMicPermission()` checking `RECORD_AUDIO` permission.
  - **Verified:** JVM unit tests and Kotlin compilation (`compileDebugKotlin`) 100% green.

- **@Cline (Pane 3 — WebUI Home Pills, Tasks Drawer, Vault & Live Voice View ONLY):** ✅ **LANE DONE**
  - **Delivered:**
    1. 4 Home Pills overhaul: Tasks / Reminders, Vault / File Drop, Notes, Live Voice Mode.
    2. Tasks checklist drawer with toggle/delete/add sync.
    3. Vault picked-file chip preview handling `window.CoucouAndroid.onFileSelected`.
    4. Live Voice 2-way room with Mochi listening/thinking/speaking reaction states.
  - **Verified:** Build, typecheck, contract verification, and asset staging green.

- **@Buffy (Pane 2 — Visual Styling, Waveforms & SVG Icons ONLY):** ✅ **LANE DONE**
  - **Scope:** `coucou-android/webui/src/style.css`, SVG icons
  - **Delivered (Sprint 6.5):**
    1. **4 hub glyph paths** in `webui/src/views/icons.ts` (24×24 grid, fill-mode like the rest of ICONS — call with plain `svg(ICONS.x, n)`, no stroke opt):
       - `ICONS.checklist` — two ticked rows + one open line (Tasks pill / drawer header)
       - `ICONS.folder` — solid folder.fill (Vault pill)
       - `ICONS.mic` — mic.fill, capsule + cradle + stand (Live Voice pill)
       - `ICONS.waveform` — 5 symmetric soundwave bars, centered on x=12 (voice room accent)
    2. **`@keyframes live-voice-pulse`** in `webui/src/style.css` (Sprint 6.5 section at file end):
       - Rides on @Cline's EXISTING markup — `.livevoice-orb::before/::after` get two staggered rings (0s / 0.9s delay), expand 0.92→1.85 + fade. **No markup change needed.**
       - Driven by the `data-live` attribute `hub-voice.ts` already toggles: `idle`=paused/dim, `listening`=fast red 1.2s, `thinking`=slow purple 2.4s ease-in-out, `speaking`=green 1.5s. Tint exposed as `--voice-ripple`.
  - **Verified:** `npm run typecheck` + `npm run build` exit 0; `live-voice-pulse` + orb ring rules confirmed in `dist/assets/island-*.css`; all 4 glyph bboxes render inside the 24×24 viewBox (`node tools/verify-hub-icons.mjs`, 4/4 PASS).
  - **Note for @Cline:** the 4 new icons are tree-shaken from the bundle until a view imports them — wire them into the pills/tiles and they ship on your next `npm run build && node ../tools/stage-coucou-web.mjs`.
  - **Handoff:** Done — notified @Cline (consume the tokens) and @AGY (`voice waveform and icon tokens verified`).

- **@AGY (Pane 0 — Build Gate, Automated QA & GitHub Release ONLY):** ✅ **SPRINT 6.5 RELEASE PUBLISHED**
  - **Scope:** Compilation & Release
  - **Delivered:**
    1. `testDebugUnitTest` 100% green (105 JVM tests passed).
    2. `assembleDebug` clean build -> `coucou-android/app/build/outputs/apk/debug/app-debug.apk` (10 MB).
    3. Headless Playwright verification pass (`scripts/verify-sprint65.mjs`) generating all 4 view screenshots:
       - `sprint65_home_overview.png`
       - `sprint65_tasks_drawer.png`
       - `sprint65_vault_chip.png`
       - `sprint65_live_voice.png`
    4. APK pushed to repository at `apks/coucou-android-debug.apk`.
    5. GitHub Release created:
       - **Release Tag:** `v20261009_183259`
       - **Release URL:** https://github.com/wolferd-wolf/Binder_vps/releases/tag/v20261009_183259

