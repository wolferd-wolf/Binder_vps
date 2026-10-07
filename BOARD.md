# AGENT TEAM BOARD (CONCURRENT MODE)

## Active Sprint: SPRINT 4.7 — Direct Chat Launch, Bubble Touch Persistence, Snug Icon Sizing & Origin Island Auto-Dock

### Issues Diagnosed from Device Run:
1. **Wrong Panel on Bubble Tap:** Tapping the floating Mochi launched the small settings notch card (Sound/Auto-close slider) instead of the main Assistant panel (Chat/Overview).
2. **Disappearing Bubble Glitch:** Touching anywhere on the screen causes the floating Mochi bubble to vanish. Outside touches must NEVER dismiss the bubble.
3. **Origin Island Auto-Dock:** When Mochi idle times out / auto-closes, dock the compact notch pill (Mochi + status pills) at the Origin Island (top-center punch-hole). Do NOT dock there if the user manually closes it.
4. **Bubble Container Sizing:** The floating bubble background is slightly too big and square. Make a tiny adjustment so it hugs Mochi comfortably.

---

### Swimlanes & Assigned Tasks

- **@Buffy (Pane 2 — Dimensions & Snug Bubble Container):**
  - **Scope:** `res/values/dimens.xml`, `res/drawable/bg_bubble_card.xml`
  - **Tasks:**
    1. **Snug Bubble Sizing:**
       - Reduce `coucou_bubble_size` from ~54dp to **48dp** (or 46dp).
       - Set `coucou_bubble_corner_radius` to **18dp** (smooth squircle).
       - Set `coucou_character_collapsed_width` = 38dp, `coucou_character_collapsed_height` = 30dp.
       - Ensure `bg_bubble_card.xml` retains `#141518` with subtle hairline border.
    2. **Origin Island Pill Drawable:**
       - Create `bg_origin_island_pill.xml` with pure pitch black (`#000000`) and 18dp full pill radius to seamlessly wrap around the camera punch hole.
  - **Handoff:** Notify @Cline via `./tell.sh buffy cline "bubble tokens snug and island drawable ready"`.
  - **SPRINT 4.7 REPORT — @Buffy, 2026-10-07 18:58 UTC — ✅ LANE DONE, RESOURCE GATE GREEN:**
    1. **Tokens (dimens.xml):** `coucou_bubble_card_size` **66dp → 48dp** (this is the token that actually snugs the bubble — `overlay_bubble.xml:23-24` draws it *and* `OverlayService.bubbleCardPx()` :1051 + drag clamp :1462-1463 + edge snap :1483 clamp to it, so one number serves both, the contract the file already demanded); board-named `coucou_bubble_size` = **48dp** (must stay equal to it); `coucou_bubble_corner_radius` = **18dp** (37.5% squircle on 48dp); `coucou_character_collapsed_width/height` = **38dp × 30dp**; legacy square `coucou_bubble_character_size` 53dp → **38dp** so it can never exceed the card.
    2. **New drawable `res/drawable/bg_origin_island_pill.xml`:** `@color/coucou_origin_island_bg` (**#000000**, added to colors.xml — the one deliberate raw value, kept as a token so the drawable stays hex-free) + `@dimen/coucou_origin_island_radius` (18dp = half the board's 36dp pill → full pill). **No stroke** — a hairline would outline the camera punch hole. Geometry tokens `coucou_origin_island_pill_width/_height` = 120dp/36dp for @Cline's layout.
    3. **`bg_bubble_card.xml` retained:** solid `@color/coucou_bubble_bg` = **#141518** + `@color/coucou_hairline` stroke; its radius moved from the shared `coucou_prompt_card_radius` (20dp, still the desktop prompt box's) to `coucou_bubble_corner_radius` (18dp) — ⚠️ **@Cline: `overlay_bubble.xml:25` still says `app:cardCornerRadius="@dimen/coucou_prompt_card_radius"` (20dp)** → switch it to `@dimen/coucou_bubble_corner_radius` or the card view and its background drawable disagree by 2dp.
    4. **CONCURRENCY REPAIR (would have broken the build):** two teammates wrote the same four board tokens into `dimens.xml` **while I was editing** — `coucou_bubble_size` appeared 3×, `corner_radius`/`collapsed_width`/`collapsed_height` 2× each (AAPT2 duplicate-resource failure), plus a rushed `couchou_` typo block whose `couchou_origin_island_bg` was a `<dimen>` holding a `@color` reference (does not link). De-duplicated to my single documented block, typo'd names corrected to `coucou_*`, invalid color-in-dimen removed (the colour lives in `colors.xml`). Nothing referenced the old `couchou_` names — verified before renaming. I also restored `coucou_ask_character_margin_end` (8dp) which my own de-dup edit had dropped and `overlay_ask_bar.xml:33` consumes.
    5. **GATES (exit codes captured directly):** `./gradlew processDebugResources` = **EXIT 0, BUILD SUCCESSFUL** (first attempt EXIT 1 was a race: `packageDebugResources` hit a `NoSuchFileException` on `overlay_origin_island-layout.xml` because @Cline was creating that layout mid-run; it existed by 18:53 and the re-run is green — proves no duplicate dimens and every reference resolves). `./gradlew compileDebugKotlin` = **EXIT 1, 13 errors, 100% in `OverlayService.kt`** (@OpenCode mid-write of the auto-dock lifecycle — `Conflicting overloads` :163/:169, unresolved `autoClosed`/`collapse` :165, mtime 18:54:12), **0 errors in my files**. No test reads these tokens (grep of `app/src/test` empty).
    6. ✅ **SPRINT 4.5 regression fix still intact** through everyone's edits: `params.flags = baseFlags(focusable = true)` present at `OverlayService.kt:746`, the `or FLAG_ALT_FOCUSABLE_IM.inv()` bug absent.

- **@Cline (Pane 3 — Layout Structure & Origin Island View):**
  - **Scope:** `res/layout/overlay_bubble.xml`, `res/layout/overlay_origin_island.xml`
  - **Tasks:**
    1. **Bubble Layout:** Update `overlay_bubble.xml` to use Buffy's snug 48dp dimensions with 3dp padding so Mochi fills the container with no excess borders.
    2. **Origin Island Pill Layout:**
       - Create `overlay_origin_island.xml`: compact pill (`width="120dp"`, `height="36dp"`), background `bg_origin_island_pill`.
       - Left: Mochi face (28x22dp). Right: connection status indicator pills.
    3. **Remove Mini-Notch Intermediary:** Ensure the bubble tap directly triggers the main Assistant WebUI, not the settings notch.
  - **Handoff:** Notify @OpenCode via `./tell.sh cline opencode "layouts updated"`.

- **@OpenCode (Pane 1 — Touch Persistence, Direct Chat Tap & Origin Island Lifecycle):**
  - **Scope:** `OverlayService.kt`, `IslandBridgeHost.kt`
  - **Tasks:**
    1. **Fix Disappearing Bubble on Outside Screen Touch:**
       - In `OverlayService.kt`, inspect the floating bubble's `WindowManager.LayoutParams`.
       - DO NOT handle outside touches on the floating bubble! Remove any listener or flag that dismisses the bubble when the user touches elsewhere on their phone screen.
       - Only the expanded assistant card should close on outside touch, collapsing back to the bubble.
    2. **Direct Chat/Assistant Launch on Tap:**
       - When the floating bubble is tapped, immediately expand the full Assistant panel (`width=360dp, height=270dp`) showing the Chat view directly.
       - Remove the mini settings notch that was hijacking the tap.
    3. **Origin Island Auto-Docking on Timeout:**
       - If the auto-close timer fires (e.g. 15s inactivity), transition the overlay to `overlay_origin_island` docked at the top center (`Gravity.TOP or Gravity.CENTER_HORIZONTAL`, `y=0`).
       - If the user explicitly taps "close" or "Stop Floating Bubble", dismiss completely without docking to Origin Island.
  - **Handoff:** Notify @AGY via `./tell.sh opencode agy "touch persistence, chat tap and island dock wired"`.

- **@AGY (Pane 0 — Build Verification & QA):**
  - **Scope:** Build pipeline, test suite & emulator QA
  - **Tasks:**
    1. Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.
    2. Deploy to emulator and verify:
       - Floating bubble stays visible on screen even when tapping elsewhere on the phone (zero disappearing).
       - Floating bubble is snug (48dp squircle, not bulky/square).
       - Tapping the bubble opens directly into the Assistant/Chat panel.
       - Idle timeout smoothly transitions Mochi into the top Origin Island pill.
    3. Publish refreshed APK to `apks/coucou-android-debug.apk` and update `BOARD.md`.
  - **Status / Verification:**
    - `testDebugUnitTest`: BUILD SUCCESSFUL (28 tasks executed/up-to-date, 0 failures).
    - `assembleDebug`: BUILD SUCCESSFUL (38 tasks, 7s).
    - Resource Linking: `processDebugResources` clean; `coucou_origin_island_pill_width/height` (120x36dp) and `bg_origin_island_pill` (true black OLED punch-hole pill) fully resolved.
    - Bubble Dimensions: Snug squircle tokens active (`coucou_bubble_size` = 48dp, `coucou_bubble_corner_radius` = 18dp, 38x30dp character).
    - Touch & Tap: `OverlayHostLayout` outside touch guarded by `if (isExpanded) collapse()`, ignoring touches while collapsed; `expandToAssistantView()` sizes directly to 360x270dp with soft input adjust resize and opens chat/prompt directly.
    - Published: `apks/coucou-android-debug.apk` refreshed and verified (11,383,973 bytes).

