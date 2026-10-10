# AGENT TEAM BOARD (CONCURRENT MODE)

## Active Sprint: SPRINT 6.6 — PILL ICON ALIGNMENT, UNIFORM PANEL DIMENSIONS & POLISHED LIVE VOICE UI

### Issues Diagnosed from Device Screenshots:
1. **Cluttered Pill Icons:** In the 4 Home rows (Tasks, Vault, Notes, Voice), the secondary icons are stuck right next to the left Mochi face. Move secondary icons/badges to the FAR RIGHT edge of each pill!
2. **Inconsistent Panel Dimensions:** Opening Tasks or Vault causes the inner view to shrink down with excess black background letterboxing. All subviews must maintain the EXACT same full dimensions as the Chat panel (360px x 270px).
3. **Voice UI Polish:** Replace the dated Voice screen with clean, modern UI patterns (Lucide/Feather vector glyphs, smooth waveform ripples, and sleek pill buttons).

---

### Swimlanes & Assigned Tasks

- **@Cline (Pane 3 — WebUI Layout Dimensions & Pill Alignment ONLY):**
  - **Scope:** `coucou-android/webui/src/views/`, `coucou-android/webui/src/style.css`
  - **Tasks:**
    1. **Move Pill Icons to Far Right:**
       - In `overview.ts` and `style.css`:
         * Left: Only the colored Mochi mascot circle.
         * Center: Clean row title (`Tasks / Reminders`, `Vault / File Drop`, `Notes`, `Live Voice Mode`).
         * Far Right: Secondary indicator icon or count badge (`margin-left: auto;`).
    2. **Uniform Full-Size Panel Dimensions (Kill the Shrinking Bug):**
       - In `style.css`, force every subview (`.chat-view`, `.tasks-view`, `.notes-view`, `.vault-view`, `.voice-view`) to fill the container:
         ```css
         .subview-container, .tasks-view, .vault-view, .notes-view, .chat-view, .voice-view {
           width: 100% !important;
           height: 100% !important;
           min-height: 270px !important;
           display: flex !important;
           flex-direction: column !important;
           box-sizing: border-box !important;
         }
         ```
       - Ensure scrollable lists have `flex: 1 1 auto; overflow-y: auto;` so they fill the card without leaving black empty space.
    3. **Re-stage WebUI:**
       - `cd /workspaces/Binder_vps/coucou-android/webui && npm run build && node ../tools/stage-coucou-web.mjs`.
  - **Handoff:** Notify @Buffy and @AGY via `./tell.sh cline buffy "dimensions and pill alignment fixed"`.

- **@Buffy (Pane 2 — Live Voice Design Upgrade & Vector Tokens ONLY):**
  - **Scope:** `coucou-android/webui/src/style.css`, `webui/src/views/voice.ts`
  - **Tasks:**
    1. **Modern Live Voice Visuals (Lucide/Radix Style):**
       - Replace the raw red circle with an elegant audio orb:
         * Glowing gradient surface with subtle pulsing rings (`box-shadow: 0 0 24px rgba(239, 68, 68, 0.35)`).
       - Replace crude dot equalizer with clean CSS wave bars or SVG soundwave glyph.
       - Use clean Lucide-style SVG icons for Mic, Mic-Off, and Settings.
       - If mic is blocked, show a sleek rounded card with an M3-styled button: `[ Allow Microphone ]`.
  - **Handoff:** Notify @AGY via `./tell.sh buffy agy "voice UI tokens modernized"`.

- **@OpenCode (Pane 1 — Mic Permission Intent Hook ONLY):**
  - **Scope:** `coucou-android/app/src/main/java/com/coucou/android/IslandBridgeHost.kt`
  - **Tasks:**
    1. When the WebUI's "Allow Microphone" or "Settings" button is tapped, wire `@JavascriptInterface fun openAppSettings()`:
       - Dispatches `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)` with `package:com.coucou.android` so the user can grant microphone permissions with 1 tap.
  - **Handoff:** Notify @AGY via `./tell.sh opencode agy "app settings intent ready"`.

- **@AGY (Pane 0 — Build Gate, Automated QA & GitHub Release ONLY):**
  - **Scope:** Compilation & Release
  - **Tasks:**
    1. Run `./gradlew assembleDebug`.
    2. Verify:
       - Home overview: secondary icons sit cleanly on the far right edge of each pill.
       - Tapping Tasks, Notes, Vault, or Chat: window maintains consistent dimensions across all tabs without shrinking or black voids.
       - Live Voice view looks sleek and modern.
    3. Publish release to GitHub:
       ```bash
       TAG="v$(date +%Y%m%d_%H%M%S)"
       gh release create "$TAG" app/build/outputs/apk/debug/app-debug.apk \
         --title "Coucou Android $TAG" \
         --notes "Sprint 6.6: Right-aligned pill icons, uniform panel dimensions across all tabs, and modern Live Voice UI overhaul." \
         --latest
       ```
    4. Post the release URL and update `BOARD.md`.
       - **RELEASE PUBLISHED:** [v6.6.0](https://github.com/wolferd-wolf/Binder_vps/releases/tag/v6.6.0) (also [v20261010_051648](https://github.com/wolferd-wolf/Binder_vps/releases/tag/v20261010_051648))
       - **APK:** `coucou-android/app/build/outputs/apk/debug/app-debug.apk` & `apks/coucou-android-debug.apk`
       - **STATUS:** SPRINT 6.6 COMPLETE (All lanes verified green)
