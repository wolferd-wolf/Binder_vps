# @Buffy Memory (Freebuff Agent)
*Last consolidated: 2026-10-03 (pod re-org: @OpenCode joined as Pane 3; role roster updated; Binder_vps security queue paused pending @Boss)*

## Identity
- I am **Buffy**, the coding agent behind **Freebuff** (freebuff.com).
- Model: z-ai/glm-5.3-flash.

## Role (one-liner @Boss asked for): **Security & Audit**
- **My scope (strict isolation, mine alone):** `config/`, `requirements.txt`, security review of the codebase.
- **My output file:** `AUDIT.md` ✅ created (see §A3 below).
- I do NOT write implementation (`agents/`) or tests/docs — I review and gate them.

## Team (concurrent mode, BOARD.md governs)
- **@Cline** — Implementation, owns `agents/` → `agents/core.py` ✅ delivered (M1–M4, 20/20 tests).
- **@AGY** (Antigravity) — Architecture & Tests, owns `tests/` + `docs/` → `docs/spec.md` + `tests/test_core.py` ✅ delivered.
- **@Cline + @AGY both read-only to me.** Rule: strict file isolation; I never write into their dirs.
- **@OpenCode** — rejoined pod 2026-10-03 as Pane 3: Kotlin Data Models, Adapters & Backend Logic (absorbs adapter/Kotlin work formerly under @AGY). Full-pod roster per @Boss: AGY=Architecture/Test Suite/Emulator QA · Buffy=Themes, Colors, Tokens & Drawables · Cline=XML Layouts & Views · OpenCode=Kotlin models/adapters/backend. ⚠️ New roster omits my Binder_vps Security & Audit role → A2/A1/A4 queue PAUSED pending @Boss directive (flagged on root BOARD.md).

## Protocol Rules (MUST FOLLOW)
1. Inspect `BOARD.md` before any action.
2. Strict file isolation (my writes: `AUDIT.md`, `config/`, `agents/buffy.md` only).
3. Interface first: publish contracts on BOARD `Live Sync & Signals`.
4. Update my own workstream bullet on BOARD when a subtask finishes.
5. **Radio Silence (@Boss):** only ping AFTER finishing edits/tests; never ping a busy agent; check `.agents/inbox/buffy.txt` when idle (dir may not exist yet); non-handoff messages = don't send.

## Messaging
- `./tell.sh buffy <target> "<msg>"` → targets `cline` / `agy` / `opencode` (also `oc`/`code`/`3`; I am pane 1). Busy targets auto-queue to their inbox. Log: `.agents/chat.log`. Never request reciprocal pings.
- Pings sent so far: AGY (land spec fixes), Cline (green light after verifying), AGY (role/artifact confirmation for @Boss's overlap check — queued, AGY was busy), OpenCode (2026-10-03 onboarding: design-token contract handoff for Kotlin scope).
- History note: OpenCode was previously REMOVED from the box by @Cline per @Boss (binary + PATH cleaned 2026-09-28), then re-added by @Boss on 2026-10-03 — removal records on boards are stale, treat OpenCode as an active peer.

## Project: Binder_vps ("binder as vps")
- Runtime: `requirements.txt` = jupyterlab>=4,<5, notebook>=7,<8.
- Key files: `index.ipynb`, `BOARD.md`, `PROJECT_PLANS.md`, `docs/spec.md` (interface authority), `agents/core.py` (implementation), `tests/test_core.py` (gate), `AUDIT.md` (my ledger), `buffy-proposal.md` (governing security spine, FROZEN as reviewed per user instruction).

## Review Round (complete 2026-09-27)
- Final proposal scores: **mine 9.05 avg** (Cline 8.8, AGY 9.3) ← WINNER/governing spine · Cline 8.85 (Buffy 8.5, AGY 9.2) · AGY 7.65 (Buffy 7.5, Cline 7.8).
- **Vote:** Cline + Buffy for the 3-way merge with my proposal as spine; @Boss confirmed everyone voted. Adopted.
- Cline's blocking finding on my proposal (ref allowlist permits `../`) ACCEPTED — but per user instruction `buffy-proposal.md` stays unchanged; fixes went downstream (spec + core.py implement them).
- Lesson: my own allowlist had the very traversal hole it was meant to stop. Always test the security control against its own attack.

## Spec encoding deadlock — RESOLVED
- Was: spec/tests pinned raw `urlpath`; AGY's proposal claimed encoding. Decision: **ENCODE** — `urllib.parse.quote(urlpath, safe="/")`.
- AGY landed 4 spec fixes; I VERIFIED in artifacts before unblocking Cline: quote() @ spec L168 + test L110-115; `__post_init__` traversal gate @ L87-95 + direct-construction test L55; control-char rejection L157/L130; https-only base L166 + tests L144-151 (rejects `http://`, `javascript:`).

## My Audit Status (AUDIT.md)
- **A1 dependencies:** 🟡 PENDING — static view OK (bounded majors); needs `pip-audit`/`safety` run, NOT installed (ask user before pip install). Open rec: A1-LOW-01 pin/lockfile.
- **A3 core review of `agents/core.py`:** ✅ **PASS** — my own 16-probe adversarial suite (stdlib harness, no pytest needed): traversal (direct + parse), https-only base, scheme injection, encoding byte-exact, caps (500 urlpath), control chars (urlpath/env), bool-port footgun, transition table, zero-I/O token scan (docstring "subprocess" = false positive, L7). **0 Critical/High · 2 Low advisories** (A3-LOW-01 length caps only at validate_spec not construction; A3-LOW-02 runtime layer must sanitize `error` strings before logging) · 2 Info.
- **A2 config contract:** 🟡 NEXT — create `config/defaults.json` + `config/schema.md`: env-var-driven, https-only base allowlist, caps mirrored from ratified spec (owner/repo ≤100, ref ≤255, urlpath ≤500), resource limits (max instances, launch timeout), no secrets.
- **A4 notebook review:** scheduled after M4 integration wires core into `index.ipynb` (secrets in outputs, unsafe magics, shell escapes).
- **A5 ledger:** running in AUDIT.md §5. Gate for "done": A1 tool scan clean + A4 done + zero open Crit/High.

## Env Notes
- `pytest` NOT installed in this codespace (my probe suite used plain stdlib asserts instead). Don't install packages without user approval.
- `.agents/inbox/` did not exist as of consolidation; tell.sh creates/queues as needed.

## Project: COUCOU-ANDROID Sprint 1 (floating bubble overlay app, standalone `coucou-android/`)
- **My lane:** reported upstream asset reusability → **HOLDING for @Boss reply before porting/theme tokens** (2026-10-03, report on root BOARD.md sprint section, committed).
- **License verdict (critical):** upstream `Louis-CFM/coucou` @ `c767db9` — code MIT, but `LICENSE-ASSETS.md` reserves names "Coucou"/"Mochi", the Mochi character (design/expressions/animations), app+menu-bar icons, ALL 28 sounds (`NotchBuddy/Resources/sounds/*.wav`, ≈3.1MB), design/docs media. Fork rule: own name/icon/character/sounds. Written permission: raillelouis@gmail.com. ⚠️ `com.coucou.android` name must change before ANY distribution.
- **Animation has NO files** — 100% procedural (BotEngine.swift 63KB + BotCanvasView/GreetingCanvasView/IslandStateMachine; Windows=Tauri+TS). Port = Kotlin Canvas/ValueAnimator mechanics (MIT, attribute © 2026 Louis Raillé) with a visually distinct character. Sprint-1 rec: own blob character + AVD bob/blink + CC0 SFX (Kenney).
- **Sound inventory (if permission granted):** 28 wav, 13.8–222.6KB; sprint set pop/open/peek/blip/tick/send/error/greet ≈686KB; drop into `res/raw/` + SoundPool.
- **Queued after @Boss reply:** theme tokens → `coucou-android/app/src/main/res/values/` (scaffold: minSdk 26, compileSdk 35, namespace com.coucou.android, AGY manifest+permission screen, OpenCode OverlayService/CommandRouter/AppLauncher, Cline layouts currently in ntfy-android — repo question flagged to Boss). Local scaffold already has own launcher mipmaps (upstream icons reserved — never copy).
- **Comms gotcha (observed 2026-10-03):** messages addressed to OpenCode (incl. my own tell.sh ping) surfaced in MY pane — tell.sh pane mapping vs actual panes unreliable mid-reorg; treat BOARD.md as authoritative channel and confirm critical handoffs as board signals, not just pings.

## Project: ntfy-android M3 Modernization (REOPENED 2026-09-28 — @Boss M3 overhaul directive)
- **Pod update 2026-10-03:** @OpenCode (Pane 3) now owns `app/src/main/java/**` — the design-token contract (zero literal colors in Kotlin; theme attrs or alias layer; `@dimen` tokens for geometry; token-file edits only via me) was handed to OpenCode via board signal + direct ping. Verified before handoff: alias layer intact in `values/colors.xml` (`action_bar`, `battery_alert_container`, `detail_activity_background`, `chip_*`), 5 `bg_*` shape drawables present, Kotlin consumers at `ui/Colors.kt` + `ui/MainAdapter.kt` + `ui/DetailAdapter.kt`.
- **New directive:** baseline screenshot confirmed; item 1 (me): themes.xml — set M3 DayNight parent + map colorPrimary/colorSurface/colorContainer/dynamic tokens. Item 2 @Cline: activity_main + fragment_main overhaul (MaterialToolbar/LargeTopAppBar, battery banner → MaterialCardView 16dp + colorErrorContainer, FAB → M3 shape). Item 3 @AGY: verify MainActivity.kt bindings, `./gradlew assemblePlayDebug`, new screenshot.
- **Item 1 verdict: ALREADY SATISFIED, zero edits made.** themes.xml already has M3 DayNight parent, colorPrimary/colorSurface, full container family, all 5 colorSurfaceContainer* levels. XML views have no bare `colorContainer` attr — container family is the M3 equivalent. Dynamic color = runtime `DynamicColors.applyToActivitiesIfAvailable()` (Application.kt:29) behind `DynamicColors` setting — do NOT hardcode dynamic tokens in XML; it fights the overlay.
- **Intel handed to @Cline on ntfy-android/BOARD.md:** toolbar already MaterialToolbar (app_bar_drawer.xml:12) → re-style not swap; battery banner already MaterialCardView w/ BannerCardStyle but corners forced 0dp by BannerShapeAppearance (my file — per-layout `app:cardCornerRadius` override is cleanest; websocket banner shares the style), no backgroundTint yet (needs colorErrorContainer), 3 banner buttons still `Widget.MaterialComponents.Button.TextButton` (M2-namespace → M3 TextButton); FAB tokens already clean; AGY compile gate will fail (no Android SDK on box) — flagged on board.
- **My role:** Design Tokens & Theming (`res/values/` + `values-night/`) + anti-slop gatekeeper. Team: @Cline (card/list layouts), @AGY (adapters/binding). Board: `ntfy-android/BOARD.md`.
- **Baseline finding:** app was already `Theme.Material3.DayNight.NoActionBar` + full `md_theme_*` mapping + material 1.13.0 — my work was verify/refine + debt, NOT greenfield. Lesson: inspect before planning a migration.
- **My deliverables:** legacy raw-hex colors → `md_theme_*` alias layer (`action_bar`, `detail_activity_background`, `chip_*` in both palettes); dark `detail_activity_background` deliberately moved #121212 → surfaceContainerLow (tonal elevation for cards); M3 shape/spacing/card dimens (`corner_*`, `spacing_*`, `card_corner_radius`=16dp, `card_stroke_width`=1dp); themes.xml de-hardcoded (ActionMode close tint white→`?attr/colorOnSurface` — was invisible on dark surface ActionMode; corners → dimen tokens).
- **Contract (Interface First, on board):** zero raw hex outside token files; drawables' hex icon fills = ACCEPTED EXCEPTIONS (icon artwork, ~105 hits); Kotlin reads go through theme attrs or my alias layer.
- **Gate process that worked:** inspect → signal contract → sweep read-only → assign exact one-line fixes to owning agent → verify each fix at artifact level in the FILE (never trust board claims alone — caught the detail dot half-fix that way: main-list badge tinted, detail twin forgotten) → declare MET only after repo-wide re-scan.
- **Final state:** criterion MET declared 🏁; zero raw hex in all layouts + res/color; zero literal colors in Kotlin. Remaining: @AGY on-device visual QA (dot tint dark mode; red-max-priority pill on colorSecondaryContainer) — no Android SDK in this env, no Gradle build possible.
- **Previous completion record (2026-09-27):** token layer + anti-slop gate done (see board 🔁 closure; criterion MET). Remaining: on-device visual QA (dot tint dark mode; red-max-priority pill on colorSecondaryContainer). Next-phase candidate: Material You dynamic color rollout (setting key `DynamicColors` already exists); full Gradle/lint verification when SDK available.
