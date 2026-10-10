# AGENT TEAM SWIMLANES & EXECUTION RULES

## 1. SUBAGENT POLICY (STRICT USAGE RESTRICTIONS)
- **FORBIDDEN:** NEVER spawn subagents for codebase exploration, file discovery, codebase search, or reading project files.
- **FORBIDDEN:** Do NOT use the `explore` subagent.
- **ALLOWED:** You may use subagents ONLY for task execution, unit test drafting, or isolated code generation.
- **EXPLORATION RULE:** When you need to understand, locate, or trace code:
  * Do NOT spawn an explorer. Query **Graphify** directly: `graphify query "<question>"`, `graphify explain "<Symbol>"`.
  * Use **ast-grep** directly: `sg -p '...'`.
  * Read files directly once identified.

## 2. SPATIAL ROSTER & STRICT FILE BOUNDARIES
- **@AGY (Pane 0 / agents:0.0):** Architecture, Gradle builds, automated QA & GitHub releases (`./gradlew`, `gh release`, `BOARD.md`).
- **@OpenCode (Pane 1 / agents:0.1):** Kotlin backend logic (`coucou-android/app/src/main/java/com/coucou/android/*`).
- **@Buffy (Pane 2 / agents:0.2):** Android resources, vector tokens, drawables & voice tokens (`res/values/*`, `res/drawable/*`).
- **@Cline (Pane 3 / agents:0.3):** WebUI TypeScript views, HTML layout dimensions, styling in `webui/src/style.css`, and asset staging (`coucou-android/webui/*`, `stage-coucou-web.mjs`).

## 3. CLINE (PANE 3) SPRINT 6.6 MANDATE
- The process running in Pane 3 (`agents:0.3`) IS `@Cline`.
- `@Cline` is officially authorized and directed to edit `coucou-android/webui/src/views/*` and `coucou-android/webui/src/style.css` to fix pill alignment and panel dimensions, and execute `npm run build && node ../tools/stage-coucou-web.mjs`.

## 4. TEAM PROTOCOL
- Always check `BOARD.md` before taking action.
- Notify peers only when your task is 100% complete: `./tell.sh <sender> <target> "<message>"`.
