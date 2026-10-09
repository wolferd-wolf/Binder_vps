# AGENT TEAM BOARD (CONCURRENT MODE)

## Active Sprint: SPRINT 6.3 — DESKTOP IDLE ANIMATION, TOUCH TRACKING, CHAT PINNING & BADGE RESET
### Notice: @AGY is BACK. Full 4-agent concurrent execution restored.

---

### Sprint Objectives:
1. **Desktop-Accurate Idle Animation for Floating Icon:** Port upstream `mochi/engine.ts` physics into `CoucouCharacterEngine.kt`. Replace twitchy/confused eye darting with gentle breathing, 4s blink intervals, and smooth eye-look damping.
2. **Touch-Tracking in Chat:** Make Mochi's eyes smoothly track user touches on screen inside the chat drawer.
3. **Pin Prompt Box:** Lock the chat input bar (`flex-shrink: 0`) so it NEVER cuts in half when messages expand.
4. **Clear '?' Badge:** Automatically clear the cyan question mark badge from the floating bubble after message completion, returning to IDLE.

---

### Swimlanes & Assigned Tasks

- **@OpenCode (Pane 1 — Desktop Idle Animation Physics & Character Reset):**
  - **Scope:** `coucou-android/app/src/main/java/com/coucou/android/CoucouCharacterEngine.kt`, `CoucouCharacterView.kt`, `IslandBridgeHost.kt`
  - **Tasks:**
    1. **Port Authentic Desktop Idle Animation:**
       - Inspect `coucou/windows/src/mochi/engine.ts` (or `coucou-android/webui/src/mochi/engine.ts`).
       - Apply desktop idle constants to `CoucouCharacterEngine.kt`:
         * Breathing: smooth sine oscillation (`sin(time * 1.5f) * 0.03f`) subtly squashing/stretching body height.
         * Blinking: natural blink interval every 3.5s–5.0s (lasting ~120ms).
         * Look direction: clamp look target offset and apply spring damping (`currentLook += (targetLook - currentLook) * 0.08f`). Stop random high-speed darting!
    2. **Auto-Reset Confused '?' Badge:**
       - Ensure `CoucouCharacterView.kt` does NOT get permanently stuck on `CoucouState.QUESTION`.
       - Whenever a chat response or note action finishes, run `postDelayed({ setState(CoucouState.IDLE) }, 1200)` to restore normal calm eyes.
  - **Handoff:** Notify @Cline & @AGY via `./tell.sh opencode cline "engine idle physics ported and badge auto-reset wired"`.

- **@Cline (Pane 3 — Touch Tracking & Pinned Chat Input Bar):**
  - **Scope:** `coucou-android/webui/src/views/chat.ts`, `coucou-android/webui/src/style.css`, `coucou-android/webui/src/mochi/`
  - **Tasks:**
    1. **Restore Touch Tracking in Chat:**
       - Attach active `touchstart` and `touchmove` listeners in `webui/src/views/chat.ts` (or `main.ts`):
         ```typescript
         window.addEventListener('touchmove', (e) => {
           if (e.touches.length > 0) {
             const t = e.touches[0];
             window.CoucouEngine?.setTargetLook?.(t.clientX, t.clientY);
           }
         }, { passive: true });
         ```
       - Ensure Mochi's pupils smoothly watch the user's finger as they interact.
    2. **Fix Clipped Prompt Box (CSS Flexbox Pin):**
       - In `style.css`, ensure `.chat-view` or container uses:
         ```css
         .chat-view {
           display: flex !important;
           flex-direction: column !important;
           height: 100% !important;
           box-sizing: border-box !important;
           padding-bottom: 12px !important;
         }
         .chat-messages {
           flex: 1 1 auto !important;
           overflow-y: auto !important;
           min-height: 0 !important;
         }
         .chat-input-bar, .chat-form {
           flex-shrink: 0 !important;
           margin-top: auto !important;
         }
         ```
       - The input pill and buttons (`+`, mic, submit) must remain 100% visible at all times!
    3. **Re-stage WebUI:**
       - `cd /workspaces/Binder_vps/coucou-android/webui && npm run build && node ../tools/stage-coucou-web.mjs`.
  - **Handoff:** Notify @Buffy & @AGY via `./tell.sh cline agy "chat flexbox pinned and webui staged"`.

- **@Buffy (Pane 2 — Layout & Drawing Inspection):**
  - **Scope:** `res/layout/overlay_bubble.xml`, `webui/src/style.css`
  - **Tasks:**
    1. Verify `overlay_bubble.xml` layout constraints do not crop the top badge or character outline.
    2. Ensure chat input container has adequate bottom padding (12dp) above the navigation bar / squircle corner curve.
  - **Handoff:** Notify @AGY via `./tell.sh buffy agy "styling and layout tokens verified"`.

- **@AGY (Pane 0 — Build Verification, QA & Device Testing):**
  - **Scope:** Unit tests, Gradle compilation & verification
  - **Tasks:**
    1. Run `./gradlew testDebugUnitTest`. (PASSED: 105/105 unit tests green)
    2. Run `./gradlew assembleDebug`. (PASSED: BUILD SUCCESSFUL in 13s)
    3. Verify headless WebUI & physics:
       - Floating Mochi icon breathes calmly (`sin(time * 1.5f) * 0.03f`) and blinks naturally every 3.5s–5.0s (~120ms duration) with 0.08f spring damping.
       - Chat touch tracking: `window.CoucouEngine.setTargetLook` verified via headless Playwright (`verify-sprint63.mjs`).
       - Pinned prompt bar: CSS flexbox locked (`flex-shrink: 0`, `flex: 1 1 auto` scrollable log), verified via Playwright screenshot.
       - '?' badge auto-reset: `CoucouCharacterView.kt` schedules auto-reset to IDLE after 1200ms; `OverlayService.kt` resets transient state and collapsed bubble to IDLE.
    4. Copy fresh APK to `apks/coucou-android-debug.apk` (PASSED: `apks/coucou-android-debug.apk` updated, sha256: d87400775e08fe7319b9ad6fa47a5404e5abcc3a1b59502a468af5162f34409c).

---

### Sprint 6.3 Status: COMPLETE & VERIFIED
- **Unit Tests:** 105/105 passing (`./gradlew testDebugUnitTest`)
- **Staged Bundle:** Verified (`verify-staged-bundle.mjs` all passed)
- **Playwright Verification:** Verified touch tracking & pinned chat box (`verify-sprint63.mjs`)
- **APK Published:** `apks/coucou-android-debug.apk` (10,383,009 bytes)
