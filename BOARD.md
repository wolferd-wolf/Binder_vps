# AGENT TEAM BOARD (CONCURRENT MODE)

## Active Sprint: SPRINT 6.4 — ORGANIC IDLE EYE DRIFT & WEBUI NOTES DRAWER SYNC

### Issues Under Hotfix:
1. **Floating Mochi Only Blinks (No Natural Glancing):** Mochi's eyes are locked straight ahead. We want calm, organic glances around at random intervals (3.0s to 6.5s), NOT rapid 1-second twitching!
2. **Saved Notes Missing in Floating Overlay:** Notes appear in MainActivity, but opening the floating Notes drawer shows "Failed to load notes" or an empty list. The bridge retrieval between `TaskStore` and WebUI `views/notes.ts` is broken.

---

### Swimlanes & Assigned Tasks

- **@OpenCode (Pane 1 — Random Glancing Physics & Bridge Serialization):**
  - **Scope:** `coucou-android/app/src/main/java/com/coucou/android/CoucouCharacterEngine.kt`, `IslandBridgeHost.kt`
  - **Tasks:**
    1. **Organic Random Look-Around in `CoucouCharacterEngine.kt`:**
       - Do NOT use a 1-second timer or constant jitter!
       - Implement natural glance scheduling:
         * Schedule next glance at a random interval between `3000ms` and `6500ms`.
         * When glancing, pick a calm, subtle look offset (`x in [-0.25f, 0.25f], y in [-0.15f, 0.15f]`).
         * Hold the glance for ~1200ms–1600ms, then smoothly spring back to center `(0f, 0f)`.
         * Apply soft spring damping: `currentLookX += (targetLookX - currentLookX) * 0.06f`.
    2. **Guarantee Notes Bridge Response (`IslandBridgeHost.kt`):**
       - Expose both `@JavascriptInterface fun getNotesJson(): String` AND handle `"get_notes"` in `invoke()`:
         ```kotlin
         @JavascriptInterface
         fun getNotesJson(): String {
             val list = taskStore.getAll()
             val arr = org.json.JSONArray()
             list.forEach {
                 arr.put(org.json.JSONObject().apply {
                     put("id", it.id)
                     put("text", it.text)
                     put("isTask", it.isTask)
                     put("isDone", it.isDone)
                     put("createdAt", it.createdAt)
                 })
             }
             return arr.toString()
         }
         ```
       - Return a valid JSON array string `[]` if empty, never null or unhandled exception.
  - **Handoff:** Notify @Cline via `./tell.sh opencode cline "glance physics and getNotesJson ready"`.

- **@Cline (Pane 3 — WebUI Notes View Data Binding & Auto-Refresh):**
  - **Scope:** `coucou-android/webui/src/views/notes.ts`, `coucou-android/webui/src/island/island.ts`
  - **Tasks:**
    1. **Fetch & Render Saved Notes in WebUI:**
       - In `notes.ts`, on component mount/show:
         * Call `window.IslandBridge?.getNotesJson?.()` or `window.CoucouNative?.invoke?.('get_notes')`.
         * Parse JSON array safely (`try { JSON.parse(...) } catch { [] }`).
         * Render each note item with its text and delete button.
       - Ensure `window.CoucouAndroid.onNotesUpdated = () => loadNotes()` re-fetches the list immediately when a note is added via chat or input bar.
    2. **Re-stage WebUI:**
       - `cd /workspaces/Binder_vps/coucou-android/webui && npm run build && node ../tools/stage-coucou-web.mjs`.
  - **Handoff:** Notify @Buffy & @AGY via `./tell.sh cline agy "webui notes sync staged"`.

- **@Buffy (Pane 2 — Visual Polish & Notes Empty State):**
  - **Scope:** `coucou-android/webui/src/style.css`
  - **Tasks:**
    1. Ensure the notes list container has smooth scrolling and clean padding.
    2. Ensure empty state ("No saved notes yet") and list items match the `#141518` card aesthetic.
  - **Handoff:** Notify @AGY via `./tell.sh buffy agy "styling verified"`.

- **@AGY (Pane 0 — Build Gate & GitHub Release):**
  - **Scope:** Build, verification & GitHub Release
  - **Status:** COMPLETED & VERIFIED
    * 105/105 JVM Unit tests green (`./gradlew testDebugUnitTest`).
    * Assembly verified (`./gradlew assembleDebug`).
    * Organic random idle glancing (3-6.5s interval, subtle offset, 1.2-1.6s hold, 0.06f soft spring damping) confirmed in `CoucouCharacterEngine.kt`.
    * `IslandBridgeHost.kt` notes bridge returns JSON array directly for `getNotesJson()` and handles `get_notes` in `invoke()`.
    * WebUI notes drawer synchronizes with `TaskStore`, supporting auto-refresh on updates and empty state fallback.
    * Fresh debug APK copied to `apks/coucou-android-debug.apk`.
    * Published GitHub Release: https://github.com/wolferd-wolf/Binder_vps/releases/tag/v20261009_082042
