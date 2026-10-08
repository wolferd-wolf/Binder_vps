# AGENT TEAM BOARD (CONCURRENT MODE)

## Active Sprint: SPRINT 6.1 POLISH & PURGE (VERCEL-TO-NOTES ROW, KILL MOCHI OVERLAP & TOTAL PILL REMOVAL)

### Issues Diagnosed from Device Screenshots:
1. **Ugly Separate Icon:** Instead of adapting the existing Vercel row, an out-of-proportion clipboard row was added. We want the exact Vercel pill style (purple theme), labeled "Notes", which opens the Notes drawer when tapped.
2. **Mochi Icon Hovering in Notes View:** The floating Mochi character canvas is visible inside/over the Notes panel text field.
3. **Ghost Island Pill (5s Timeout):** The black notch pill with 4 colored dots STILL appears when Mochi auto-shuts down after 5 seconds. This pill must be 100% deleted.

---

### Swimlanes & Assigned Tasks

- **@Cline (Pane 3 — WebUI Vercel-to-Notes Replacement & Clean Views):**
  - **Scope:** `coucou-android/webui/src/views/overview.ts`, `coucou-android/webui/src/style.css`, `webui/src/island/island.ts`
  - **Tasks:**
    1. **Use Exact Vercel Pill for Notes:**
       - In `overview.ts`, do NOT create an ugly custom icon.
       - Take the 3rd row (the purple Vercel row) and replace its text with `"Notes"`.
       - Keep the original purple styling and pill layout intact.
       - Tapping this purple "Notes" row transitions cleanly to the Notes panel.
    2. **Clean Notes Panel (No Embedded Mochi):**
       - Ensure the Notes sub-panel does NOT render any canvas Mochi mascot over the input area.
       - Clean layout: Back arrow (`<`), `"Notes & Tasks"`, the `+ Add note` input field, and the list of notes.
    3. **Re-stage WebUI:**
       - `cd /workspaces/Binder_vps/coucou-android/webui && npm run build && node ../tools/stage-coucou-web.mjs`
  - **Handoff:** Notify @OpenCode via `./tell.sh cline opencode "Notes row styled like Vercel and staged"`.

- **@OpenCode (Pane 1 — 100% Island Pill Purge & Bubble Visibility Lock):**
  - **Scope:** `coucou-android/app/src/main/java/com/coucou/android/OverlayService.kt`
  - **Tasks:**
    1. **TOTAL PURGE of 5-Second Island Pill Timeout:**
       - Search `OverlayService.kt` for any 5000ms / 5s `postDelayed` timer, `autoShutdown`, `showOriginIsland`, or `islandView`.
       - COMPLETELY DELETE the timer and the view inflation.
       - The 4-dot pill MUST NEVER BE ADDED TO WINDOWMANAGER UNDER ANY CIRCUMSTANCES.
    2. **Hide Floating Bubble When Notes Panel is Open:**
       - In `OverlayService.kt`, verify that whenever `webViewContainer` is visible/expanded (whether Chat, Overview, or Notes), `bubbleView.visibility = View.GONE`.
       - The native bubble must NEVER overlap the WebView!
  - **Handoff:** Notify @Buffy & @AGY via `./tell.sh opencode agy "island pill purged and bubble visibility locked"`.

- **@Buffy (Pane 2 — Layout & Resource Cleanup):**
  - **Scope:** `res/layout/`, `res/values/dimens.xml`
  - **Tasks:**
    1. Verify `overlay_origin_island.xml` is deleted from disk.
    2. Ensure no obsolete drawable or layout references remain for the 4-dot pill.
  - **Handoff:** Notify @AGY via `./tell.sh buffy agy "resource cleanup verified"`.

- **@AGY (Pane 0 — Build Gate, Bug Hunter & QA):**
  - **Status:** COMPLETED & VERIFIED
  - **Results:**
    1. **Grep Assertion:** `grep -ri "origin_island" app/src/` returned exit 1 (0 references).
    2. **WebUI & Overlay Verification:**
       - Purple Notes pill rendered via standard `.pill` with `#7C5CFF` accent matching Vercel.
       - Tapping Notes transitions cleanly to the Notes panel.
       - Mochi canvas suppressed (`botDiameter: 0`, `#bot-canvas` hidden in note view).
       - Top 4-dot compact notch pill permanently deleted (`#mini-grid` purged, transitions collapse directly to hidden).
    3. **Tests & Build:**
       - `npm run build && node ../tools/stage-coucou-web.mjs`: EXIT 0
       - `./gradlew testDebugUnitTest assembleDebug`: 102/102 tests green, EXIT 0
       - Fresh APK published to `apks/coucou-android-debug.apk` (11MB).

