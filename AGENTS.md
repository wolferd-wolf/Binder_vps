# MULTI-AGENT POD DIRECTIVE: COLLABORATIVE CONSENSUS

## 1. Team Spatial Roster & Assigned Panes
You are part of an active 4-agent parallel engineering pod inside a 5-pane tmux workspace. You are NOT working alone:
- **@AGY** (Pane 0 / `agents:0.0`): Architecture, Test Suite, Gradle builds, APK & Emulator QA.
- **@OpenCode** (Pane 1 / `agents:0.1`): Kotlin/backend models, view bindings, app launcher, service logic.
- **@Buffy** (Pane 2 / `agents:0.2`): M3 theme tokens, typography, drawables, assets, security audits.
- **@Cline** (Pane 3 / `agents:0.3`): XML layouts, UI hierarchies, view components.
- **@Boss** (Pane 4 / `agents:0.4`): Human developer console.

---

## 2. Collaborative Consensus Rules
To work as a cohesive unit rather than isolated workers:
1. **Always Acknowledge Teammate Artifacts:**
   Before introducing a new ID, style token, class, or method, inspect what your teammates produced. Match class names, layout IDs, and token references created by your peers.
2. **Explicit Handoff Sign-off:**
   When completing a task or milestone:
   - Identify which teammate must consume or verify your changes next.
   - Summarize the interface contract or newly created IDs in `BOARD.md`.
   - Dispatch an explicit handoff using `./tell.sh <your_name> <target_agent> "<message>"`.
3. **Peer Review on Verification:**
   - If @Buffy changes styles, @Cline verifies the layout inflation.
   - If @Cline updates XML IDs, @OpenCode verifies the view bindings.
   - If @OpenCode updates logic, @AGY runs the test/build validation.
4. **Interface-First Consensus:**
   Never assume an uncommitted file structure. If an interface is ambiguous, check `.agents/vault/decisions/` or ask the responsible peer via `./tell.sh` before implementing.

---

## 3. Communication Protocol (Safe Peer Messaging)
Use terminal bash to message teammates:
`./tell.sh <your_name> <target_agent> "<message>"`

Communication Boundaries:
- **RADIO SILENCE DURING CODING:** Only ping a teammate when your task or interface is 100% complete and tested.
- **NO CHIT-CHAT OR CONVERSATIONAL FILLER:** Never reply with "Got it", "Understood", or "Acknowledged". Work quietly.
- **ONE-WAY BATON PASS:** Never ask for a reciprocal ping (no "ping me when done").
- **RATE-LIMITING RESPECT:** If `./tell.sh` flags that an agent is busy, log your status to `BOARD.md` and continue non-dependent tasks.

---

## 4. Context Ground Truth
- **Active Project Tracker:** Check `/workspaces/Binder_vps/.agents/ACTIVE_PROJECT`.
- **Sprint Board:** Read `/workspaces/Binder_vps/BOARD.md` before taking any actions.
- **Knowledge Graph / Vault:** Query `.agents/vault/` or `.agents/active_vault/decisions/`.

## GRAPHIFY ENFORCEMENT PROTOCOL
- NEVER run recursive `grep` or blind `find` across the project codebase.
- When searching for classes, view IDs, interfaces, or theme tokens:
  1. First query: `graphify query "<your question>"` (or use the `query_graph` MCP tool).
  2. For inheritance/callers: `graphify explain "<ClassName>"`.
  3. For tracing interactions: `graphify path "<ComponentA>" "<ComponentB>"`.
- Only read raw source files once Graphify pinpoints the exact file and lines needed.
