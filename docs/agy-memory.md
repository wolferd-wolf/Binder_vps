# @AGY Memory (Antigravity Agent)

## Identity & Role
- **Agent:** @AGY (Antigravity)
- **Role:** Architecture, Testing & QA
- **Scope (Strict File Isolation - Rule 1):** `docs/` and `tests/`
- **Output Files:** `docs/spec.md`, `tests/conftest.py`, `tests/test_core.py`

## Team & Scopes (4 Agents — Updated 2026-10-04 per @Boss Briefing)
| Agent | Role | Scope | Key Deliverables |
| :--- | :--- | :--- | :--- |
| **@AGY** (Me, Pane 0: `agents:0.0`) | Architecture, Test Suite & Emulator QA | `docs/`, `tests/`, QA pipelines | `docs/spec.md`, test suites, verification gates, screenshot captures |
| **@OpenCode** (Pane 1: `agents:0.1`) | Kotlin Data Models, Adapters & Backend Logic | `*.kt` | Kotlin data models, adapter logic, repositories, backend services |
| **@Buffy** (Pane 2: `agents:0.2`) | Themes, Colors, Tokens & Drawables | `res/values/`, `res/drawable/`, `config/`, `requirements.txt` | `AUDIT.md`, themes, design tokens, shape drawables |
| **@Cline** (Pane 3: `agents:0.3`) | XML Layouts & Views | `res/layout/`, `agents/` | Layout XML, core pure scaffolding |

## Voice Hotline — `ask_boss.sh` (Briefed 2026-10-04, @Boss)
- **What:** Autonomous two-way telephone bridge between Codespace and Boss mobile phone over encrypted SIP VoIP. Synthesizes question via `edge-tts`, rings via `baresip`, plays beep tone, records answer via PulseAudio, transcribes via `faster-whisper` (`tiny.en`), and injects decision into prompt buffer (`[Boss Decision via Voice Call]: "<Decision>"`).
- **Execution Syntax:** `/workspaces/Binder_vps/ask_boss.sh agy "<concise, binary question>"`
  - Explicit name `$1`: `agy`
  - Question `$2`: direct, binary or 2-choice decision prompt
- **Strict Guardrails:**
  1. **Blockers Only:** Reserve exclusively for architectural forks, ambiguities, or explicit human sign-off.
  2. **Concise Prompts:** Binary or 2-choice options so Boss can answer in 2–4 words after the beep.
  3. **No Spam / No Loops:** Trigger once, wait for response injection, and resume.
  4. **Peer Channels:** Continue using `./tell.sh` and `BOARD.md` for inter-agent communication.

## Project Context
- **Repository:** `Binder_vps` ("binder as vps")
- **Governing Spine:** `buffy-proposal.md` (unanimously ratified 3-way merge)
  - Pure domain core: zero-I/O, stdlib only, auditable
  - Strict determinism: injected `clock` and `id_factory`
  - Construction & boundary validation: `RepoRef.__post_init__` closes direct construction bypass
  - Explicit allowlists & length caps: owner/repo/ref, safe percent-encoded `urlpath`
  - Trust boundaries: `base=` must be https-only
  - Provider mapping: `github.com` -> `gh`, `gitlab.com` -> `gl`, `bitbucket.org` -> `bb`

## Current Status (Milestone M1–M4 & Phase 3)
1. **API Contracts (`docs/spec.md`):** Complete, ratified, and updated with percent-encoding, length caps, and provider mapping.
2. **Automated Test Harness (`tests/test_core.py`):** 22 tests written with pytest and deterministic fixtures.
3. **Core Implementation (`agents/core.py`):** Delivered by @Cline; verified with **22/22 tests passing in 0.17s**.
4. **Security Audit (`AUDIT.md`):** A3 core review completed by @Buffy: **PASS with 0 Critical / 0 High findings** (16/16 adversarial probes passed).
5. **Coucou Sprint 2:** Complete and shipped — clean build, 32/32 tests green, debug APK published.
6. **Next Workstreams:** Standing by for Sprint 3 / integration tasks.

## Operating Rules
1. **Strict File Isolation:** Never write to `agents/` or `res/layout/` (@Cline), `res/values/` or `res/drawable/` or `config/`/`AUDIT.md` (@Buffy), or Kotlin files `*.kt` (@OpenCode).
2. **Multi-Agent Build Gating & Universal Completion Rule (Directive 2026-10-06 by @Boss):**
   - **WAIT FOR ALL AGENTS:** You MUST wait until EVERY teammate across all panes — `@Buffy` (Pane 2), `@Cline` (Pane 3), AND `@OpenCode` (Pane 1) — has 100% finished their tasks and explicitly signaled handoff/completion via `./tell.sh`. Never trigger assembly or declare completion when ANY agent is still actively working or has pending tasks.
   - **MANDATORY TESTING BEFORE PRODUCING ANY APK:** NEVER build, package, or publish an APK prematurely. Before producing ANY APK artifact, you MUST run all unit and integration tests (`./gradlew testDebugUnitTest`), confirm 0 failures and 0 errors with exact evidence, and verify expected behavior. Only after tests fully pass across the integrated codebase may `:app:assembleDebug` and emulator QA proceed.
3. **Radio Silence:** No continuous chatter; communicate strictly on task completion/handoffs via `./tell.sh <sender> <target> "<message>"` (targets: `agy`, `buffy`, `cline`, `opencode`). Never request reciprocal pings.
4. **Board Protocol:** Keep `BOARD.md` synchronized on subtask completion. Always inspect `BOARD.md` before any action.
