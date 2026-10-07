# Graph Report - coucou  (2026-10-06)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 1552 nodes · 3512 edges · 81 communities (49 shown, 32 thin omitted)
- Extraction: 99% EXTRACTED · 1% INFERRED · 0% AMBIGUOUS · INFERRED: 25 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `64b343c2`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- CoucouCharacterView
- sequence.ts
- CoucouIslandWebView.kt
- pi
- CoucouCharacterEngineTest
- island-Bwgryo4W.js
- CoucouCharacterEngine.kt
- island.ts
- greeting.ts
- h
- IslandBridgeCommandsTest
- Ae
- ms
- h
- Island
- .execute
- .parse
- verify-bridge-contract.mjs
- BridgeAction
- settings/main.ts
- BotEngine
- ViewActions
- views/integrations.ts
- IslandBridgeCommands
- Sound
- .parseObject
- anim.ts
- verify-staged-bundle.mjs
- OverlayService
- ticker.ts
- src/main.ts
- dom-nmERa8aV.js
- Ps
- gen-icons.mjs
- IslandStateMachine
- IslandListener
- screenshot-viewports.mjs
- engine.ts
- compilerOptions
- ui
- bridge.android.ts
- hooks.ts
- E
- As
- AppState
- Reader
- state.ts
- Dt
- CoucouAndroidApi
- AppLauncher
- scripts
- lerp
- bridge.ts
- si
- verify-shim.mjs
- package.json
- verify-android-bridge.mjs
- coucou/tauri-shim.js
- MainActivity
- SoundPlayer
- stage-coucou-web.mjs
- pack.mjs
- tools/tauri-shim.js
- OverlayHostLayout
- .frame
- SoundEngine
- devDependencies
- minibots.ts
- .onCreate
- .drawEyeShape
- IntegrationCardHooks
- build.sh script

## God Nodes (most connected - your core abstractions)
1. `OverlayService` - 74 edges
2. `h()` - 58 edges
3. `CoucouCharacterView` - 52 edges
4. `Island` - 48 edges
5. `pi` - 47 edges
6. `h()` - 44 edges
7. `IslandBridgeCommandsTest` - 40 edges
8. `BotEngine` - 34 edges
9. `CoucouCharacterEngineTest` - 33 edges
10. `Ae` - 29 edges

## Surprising Connections (you probably didn't know these)
- `MiniBot` --references--> `BotEngine`  [EXTRACTED]
  webui/src/mochi/minibots.ts → webui/src/mochi/engine.ts
- `ls()` --indirect_call--> `s()`  [INFERRED]
  app/src/main/assets/coucou/assets/island-Bwgryo4W.js → app/src/main/assets/coucou/assets/dom-nmERa8aV.js
- `fs()` --indirect_call--> `s()`  [INFERRED]
  app/src/main/assets/coucou/assets/island-Bwgryo4W.js → app/src/main/assets/coucou/assets/dom-nmERa8aV.js
- `Gt()` --indirect_call--> `o()`  [INFERRED]
  app/src/main/assets/coucou/assets/island-Bwgryo4W.js → app/src/main/assets/coucou/assets/dom-nmERa8aV.js
- `ys()` --indirect_call--> `s()`  [INFERRED]
  app/src/main/assets/coucou/assets/island-Bwgryo4W.js → app/src/main/assets/coucou/assets/dom-nmERa8aV.js

## Import Cycles
- None detected.

## Communities (81 total, 32 thin omitted)

### Community 1 - "sequence.ts"
Cohesion: 0.09
Nodes (25): bodyPath(), drawDoc(), drawEye(), rr(), text(), UploadCanvas, UploadCanvasActions, eBack() (+17 more)

### Community 2 - "CoucouIslandWebView.kt"
Cohesion: 0.07
Nodes (6): CoucouIslandWebView, IslandChromeClient, IslandWebViewClient, PrefixAssetsPathHandler, RawSoundPathHandler, FilterInputStream

### Community 3 - "pi"
Cohesion: 0.10
Nodes (7): Et(), Ot(), pi, St(), Vt(), xt(), ze

### Community 5 - "island-Bwgryo4W.js"
Cohesion: 0.06
Nodes (42): at(), B, ce, d, De(), ee, ft, H (+34 more)

### Community 6 - "CoucouCharacterEngine.kt"
Cohesion: 0.06
Nodes (25): blink(), bodyOutline(), CoucouState, APPROVAL, DIZZY, ERROR, FINISHED, IDLE (+17 more)

### Community 7 - "island.ts"
Cohesion: 0.10
Nodes (31): AgentLayoutMode, botGlowColor(), botGlowOpacity(), BotPlacement, botPosition(), chatPromptHeight(), currentScreenWidth(), EXPANDED_CORNER (+23 more)

### Community 8 - "greeting.ts"
Cohesion: 0.09
Nodes (29): COMPACT_W, NOTCH_H, NOTCH_W, C0, CARD, clamp(), drawHandL(), drawHandR() (+21 more)

### Community 9 - "h"
Cohesion: 0.14
Nodes (35): h(), R(), s(), ai(), ci(), di(), hi(), Ht() (+27 more)

### Community 11 - "Ae"
Cohesion: 0.13
Nodes (9): Ae, F(), K(), ls(), ne(), oe(), Re(), Rt() (+1 more)

### Community 12 - "ms"
Cohesion: 0.09
Nodes (16): Be(), bs(), ct(), fs(), gs(), Gt(), he(), le() (+8 more)

### Community 13 - "h"
Cohesion: 0.17
Nodes (25): washRGBA(), State, bubble(), buildPrompt(), contextChip(), typingDots(), h(), buildChoose() (+17 more)

### Community 14 - "Island"
Cohesion: 0.17
Nodes (3): IslandViewName, Island, modeOrder()

### Community 16 - ".parse"
Cohesion: 0.19
Nodes (8): Command, CommandResult, CommandRouter, DefaultCommandRouter, Failed, Success, Unknown, CommandRouterParseTest

### Community 18 - "verify-bridge-contract.mjs"
Cohesion: 0.09
Nodes (20): appRoot, BRIDGE_SOURCES, bundledCommands(), DESKTOP_ONLY, DRAG_COMMANDS, dragMissingHost, dragMissingPage, dropped (+12 more)

### Community 19 - "BridgeAction"
Cohesion: 0.11
Nodes (18): Boot, BridgeAction, ChatSend, DragBy, DragEnd, DragStart, Fail, Log (+10 more)

### Community 20 - "settings/main.ts"
Cohesion: 0.17
Nodes (18): Bridge, DEFAULT_SETTINGS, apiSection(), claudeSection(), draw(), showPreview(), generalSection(), IntegrationDef (+10 more)

### Community 21 - "BotEngine"
Cohesion: 0.24
Nodes (4): BotEmoteName, BotEngine, now(), createMiniBot()

### Community 22 - "ViewActions"
Cohesion: 0.11
Nodes (8): pruneMiniBots(), buildHeader(), go(), buildOverview(), buildPill(), buildSettings(), lighten(), ViewActions

### Community 23 - "views/integrations.ts"
Cohesion: 0.32
Nodes (21): dot(), svg(), arr(), calcomCard(), get(), githubCard(), hasIntegrationData(), header() (+13 more)

### Community 24 - "IslandBridgeCommands"
Cohesion: 0.15
Nodes (4): IslandBridgeCommands, IslandSettings, SaveSettings, Screen

### Community 25 - "Sound"
Cohesion: 0.10
Nodes (19): Sound, ANNOYED, APPROVAL, BLIP, DIZZY, ERROR, FINISH, GREET (+11 more)

### Community 27 - "anim.ts"
Cohesion: 0.11
Nodes (7): closeCurve, cubicBezier(), Ease, EaseFn, seg(), Spring, Tracked

### Community 28 - "verify-staged-bundle.mjs"
Cohesion: 0.10
Nodes (17): appRoot, BOOT_PAYLOAD, calls, collapsed, dragBy, failures, handlerId, here (+9 more)

### Community 31 - "ticker.ts"
Cohesion: 0.18
Nodes (11): clamp(), AgentTask, Attrs, Child, ICONS, EASE, makeRow(), place() (+3 more)

### Community 32 - "src/main.ts"
Cohesion: 0.18
Nodes (12): IntegrationUpdate, onEvent(), Sound, SOUND_NAMES, SoundName, registerHookHandlers(), clearTimers, handle() (+4 more)

### Community 33 - "dom-nmERa8aV.js"
Cohesion: 0.17
Nodes (16): a(), b(), C, D, I(), k(), m(), n() (+8 more)

### Community 34 - "Ps"
Cohesion: 0.17
Nodes (7): bt(), C(), Cs(), es(), j(), Ps, Zt()

### Community 35 - "gen-icons.mjs"
Cohesion: 0.16
Nodes (15): BASE_BOTTOM, BASE_TOP, chunk(), crc32(), CRC_TABLE, encodePNG(), files, ico (+7 more)

### Community 38 - "screenshot-viewports.mjs"
Cohesion: 0.15
Nodes (11): playwright, vite, checkOverflow(), DEFAULT_OUT, __dirname, ISLAND_VIEWS, ROOT, run() (+3 more)

### Community 39 - "engine.ts"
Cohesion: 0.12
Nodes (16): Badge, BadgeKind, base, BASE_BOTTOM, BASE_TOP, BOT_STATES, BotStateCfg, C (+8 more)

### Community 40 - "compilerOptions"
Cohesion: 0.12
Nodes (16): compilerOptions, isolatedModules, lib, module, moduleResolution, noEmit, noFallthroughCasesInSwitch, noImplicitOverride (+8 more)

### Community 42 - "bridge.android.ts"
Cohesion: 0.20
Nodes (12): Bridge, call(), callOrThrow(), coucouNativeInvoke(), Envelope, facadeOverNative(), getApi(), IS_TAURI (+4 more)

### Community 43 - "hooks.ts"
Cohesion: 0.20
Nodes (14): agentColor(), aliasProjectName(), APPROVAL_FIELDS, approvalTarget(), clearSession(), FALLBACK_COLORS, handleHook(), HookPayload (+6 more)

### Community 46 - "As"
Cohesion: 0.23
Nodes (6): As, G(), Is(), Pe(), Ss(), ue()

### Community 49 - "state.ts"
Cohesion: 0.14
Nodes (12): IslandMode, AgentSource, ApprovalInfo, ChatMessage, INTEGRATION_AGENTS, IntegrationInfo, Listener, PillBadge (+4 more)

### Community 52 - "AppLauncher"
Cohesion: 0.32
Nodes (3): AppEntry, AppLauncher, LaunchAppCommandRouter

### Community 54 - "scripts"
Cohesion: 0.17
Nodes (12): scripts, build, build:android, dev, icons, preview, screenshots, typecheck (+4 more)

### Community 55 - "lerp"
Cohesion: 0.24
Nodes (5): lerp(), mix3(), rgba(), roundRectPath(), rrPoint()

### Community 56 - "bridge.ts"
Cohesion: 0.18
Nodes (9): BootInfo, BridgeEvent, DragDropPayload, DroppedFile, HookPreview, HookStatus, IS_TAURI, onDragDrop() (+1 more)

### Community 57 - "si"
Cohesion: 0.22
Nodes (9): g, p(), bi(), ei(), fi(), gi(), qt(), si() (+1 more)

### Community 58 - "verify-shim.mjs"
Cohesion: 0.18
Nodes (8): calls, failures, handlerId, here, received, sandbox, shim, window

### Community 59 - "package.json"
Cohesion: 0.18
Nodes (10): @playwright/test, @tauri-apps/api, @tauri-apps/cli, typescript, dependencies, @tauri-apps/api, name, private (+2 more)

### Community 60 - "verify-android-bridge.mjs"
Cohesion: 0.20
Nodes (4): failures, here, outFile, webuiRoot

### Community 61 - "coucou/tauri-shim.js"
Cohesion: 0.42
Nodes (7): deliverCallback(), dropCallback(), emitEvent(), invoke(), listenersFor(), registerCallback(), trackEventListener()

### Community 64 - "stage-coucou-web.mjs"
Cohesion: 0.22
Nodes (7): dist, here, index, outDir, patched, repoRoot, upstream

### Community 65 - "pack.mjs"
Cohesion: 0.22
Nodes (6): bundleRoot, outDir, PACKAGES, root, { version }, written

### Community 66 - "tools/tauri-shim.js"
Cohesion: 0.42
Nodes (7): deliverCallback(), dropCallback(), emitEvent(), invoke(), listenersFor(), registerCallback(), trackEventListener()

### Community 69 - ".frame"
Cohesion: 0.29
Nodes (3): hexToRGB(), syncMiniBotStates(), tickMiniBots()

### Community 72 - "devDependencies"
Cohesion: 0.33
Nodes (6): devDependencies, playwright, @playwright/test, @tauri-apps/cli, typescript, vite

## Knowledge Gaps
- **249 isolated node(s):** `MouthRect`, `Failed`, `Success`, `Boot`, `DragEnd` (+244 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 456 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **32 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `OverlayService` connect `OverlayService` to `CoucouCharacterView`, `CoucouIslandWebView.kt`, `OverlayHostLayout`, `View`, `IslandListener`, `.collapse`, `.onCreate`, `.execute`, `.parse`, `OverlayService.kt`, `AppLauncher`, `.onDestroy`, `.applyWindowState`, `SoundPlayer`?**
  _High betweenness centrality (0.089) - this node is a cross-community bridge._
- **What connects `MouthRect`, `Failed`, `Success` to the rest of the system?**
  _249 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `CoucouCharacterView` be split into smaller, more focused modules?**
  _Cohesion score 0.06861239119303636 - nodes in this community are weakly interconnected._
- **Why does `CoucouCharacterView` connect `CoucouCharacterView` to `.applyWindowState`, `CoucouCharacterEngine.kt`, `OverlayService`?**
  _High betweenness centrality (0.052) - this node is a cross-community bridge._
- **Should `sequence.ts` be split into smaller, more focused modules?**
  _Cohesion score 0.08506493506493507 - nodes in this community are weakly interconnected._
- **Why does `@tauri-apps/api` connect `package.json` to `bridge.ts`, `bridge.android.ts`?**
  _High betweenness centrality (0.048) - this node is a cross-community bridge._
- **Should `CoucouIslandWebView.kt` be split into smaller, more focused modules?**
  _Cohesion score 0.07265306122448979 - nodes in this community are weakly interconnected._