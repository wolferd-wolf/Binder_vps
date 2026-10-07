# AGENT TEAM BOARD (CONCURRENT MODE)

## Active Sprint: SPRINT 4.5 — Fix Aspect Ratio Stretch, Restore Bubble Tap & Wire Assistant Bridge

### Defects Diagnosed from Device Run (Commit 36b1b3e):
1. **Stretched Top Island:** The intro animation is vertically stretched. Top island notch dimensions must be fixed to desktop proportions: width ~340dp–360dp, height ~92dp (aspect ratio ~3.7:1), with Canvas/SVG preserving aspect ratio (`object-fit: contain`).
2. **Dead Bubble Tap:** Tapping the collapsed floating Mochi does nothing because `OnTouchListener` drag logic consumed all touches and broke the click/expand handler.
3. **Dead Assistant Pipeline:** Once expanded, chat inputs must trigger the actual assistant router (open apps, save notes, web search) and post responses back to the WebUI.

---

### Swimlanes & Assigned Tasks

- **@Cline (Pane 3 — WebUI Island Proportions & CSS Fix):**
  - **Scope:** `coucou-android/webui/src/style.css`, `webui/src/island/island.ts`
  - **Tasks:**
    1. **Fix Island Dimensions & Stop Vertical Stretch:**
       - The top greeting island must NOT stretch vertically. Set explicit compact dimensions:
         `width: 350px; max-width: 92vw; height: 92px; border-radius: 20px;`
       - Ensure the canvas container behind Mochi preserves aspect ratio (`width: 100%; height: 100%; object-fit: contain;`).
       - Do NOT let the island container expand to 150px+ during greeting.
    2. **Re-stage WebUI:**
       - Rebuild webui bundle: `npm run build && node ../tools/stage-coucou-web.mjs`.
  - **Handoff:** Notify @OpenCode via `./tell.sh cline opencode "island proportions fixed"`.

- **@OpenCode (Pane 1 — Bubble Tap/Drag Separation & Assistant Router):**
  - **Scope:** `OverlayService.kt`, `IslandBridgeHost.kt`, `AssistantRouter.kt`
  - **Tasks:**
    1. **Fix Dead Tap on Collapsed Bubble:**
       - In `OverlayService.kt` `attachDragPhysics`:
         ```kotlin
         val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
         // On ACTION_UP:
         if (!isDragging && Math.hypot((event.rawX - startX).toDouble(), (event.rawY - startY).toDouble()) < touchSlop) {
             view.performClick()
             expandToAssistantView() // Must un-hide/re-attach expanded WebView!
         }
         ```
       - Ensure `expandToAssistantView()` re-attaches the expanded WebView overlay, centers it, and **clears `FLAG_NOT_FOCUSABLE`** so the soft keyboard can pop up when typing in the chat!
    2. **Wire Assistant Router in `IslandBridgeHost.kt`:**
       - Connect `chatSend(query)`:
         * `"open <app>"` / `"launch <app>"` -> call fast `AppLauncher` (prefix >= 3 chars).
         * `"note <text>"` / `"task <text>"` -> save in `TaskStore.kt`, reply `"Saved note: <text>"`.
         * `"search <query>"` -> dispatch web browser Intent.
         * Default/conversational -> return clean assistant response.
       - Send response back into the WebView:
         `webView.post { webView.evaluateJavascript("window.CoucouAndroid.onChatResponse('$cleanResponse')", null) }`
  - **Handoff:** Notify @AGY via `./tell.sh opencode agy "tap gesture and assistant router wired"`.

- **@Buffy (Pane 2 — Dimensions & Token Verification):**
  - **Scope:** `res/values/dimens.xml`, `bg_bubble_card.xml`
  - **Tasks:**
    1. Verify `coucou_island_intro_width` = 350dp, `coucou_island_intro_height` = 92dp.
    2. Ensure `bg_bubble_card.xml` retains the dark `#141518` card background.
  - **Handoff:** Notify @AGY via `./tell.sh buffy agy "tokens verified"`.
  - **VERIFICATION REPORT — @Buffy, SPRINT 4.5, 2026-10-07 17:55 UTC — ✅ BOTH TASKS PASS, NO FILES CHANGED BY ME:**
    1. **`coucou_island_intro_width` = 350dp (dimens.xml:77), `coucou_island_intro_height` = 92dp (dimens.xml:78)** ✅ — 350/92 = **3.80:1**, inside the board's "340–360dp × 92dp (~3.7:1)" window. `dimens.xml` parses clean (XML well-formedness OK). The tokens were already present in the working tree (mtime 17:49, uncommitted — @Cline's in-flight lane wrote them; I verified, did not re-author).
    2. **`bg_bubble_card.xml` retains the dark card** ✅ — `<solid android:color="@color/coucou_bubble_bg"/>` and `coucou_bubble_bg = #141518` (colors.xml:43); hairline `@color/coucou_hairline = #09FFFFFF` (colors.xml:36); radius `@dimen/coucou_prompt_card_radius` = 20dp. **Zero raw hex in the shape body** (the two `#141518`/`#09FFFFFF` hits are documentation-comment only). File parses clean.
    3. Regression check: `coucou_bubble_card_size` still **66dp** (dimens.xml:51) — the value `OverlayService.bubbleCardPx()` (993), the drag clamp (1404-1405) and the edge snap (1423) all read, so drag geometry is untouched by this sprint.
    4. ⚠️ **FINDING (non-blocking, outside my lane to patch):** `coucou_island_intro_width/height` currently have **zero consumers**. The native greeting window still hardcodes its rect — `OverlayService.kt:419-420` (`params.width = dp(380)`, `params.height = dp(150)`, `startGreetingOverlay`) and again at `OverlayService.kt:848-849` (`onIslandSized`). Once @Cline's page island is 350×92, the native window will sit 30dp wider and **58dp taller than its content** unless those two sites read the new tokens (or let the page rect drive it). The `380×150` comment on line 418 is now stale too. Flagged for @OpenCode (tap/expand lane owns `OverlayService.kt`) and @AGY's device pass.
    5. **Scope note:** @Boss answered a lane-arbitration question with "Only my Buffy lane" — webui aspect ratio stays with @Cline (files were being edited live at 17:51–17:52), dead tap + router stay with @OpenCode. I touched no source files; this entry is the only write.

- **@AGY (Pane 0 — Build Verification, QA & APK):**
  - **Scope:** Gradle build pipeline, emulator QA & APK publication
  - **Tasks:**
    1. Run `./gradlew assembleDebug`.
    2. Deploy to emulator via adb and test:
       - Top island starts with normal, un-stretched aspect ratio (350x92dp) with starry background and waving Mochi.
       - After collapsing, **tapping the bubble reliably expands the assistant window**.
       - Typing `"note buy milk"` or `"open settings"` works and gives clean output.
    3. Update APK in `apks/coucou-android-debug.apk` and report back.
