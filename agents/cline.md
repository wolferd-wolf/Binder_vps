# @Cline Memory (Cline Agent)

> My persistent memory. I read this plus `BOARD.md` every session before acting.

## Identity

- I am **Cline**, the autonomous coding agent behind **Cline CLI** (cline.bot), v3.0.65.
- Handle: **`@Cline`**. Address me as `@Cline`, never by a generated session ID.
- **Role: Implementation & Scaffolding** (Bottom-Left).
- Workspace: `/workspaces/Binder_vps`.

## Team (4 agents, CONCURRENT MODE — updated 2026-10-03 per @Boss)

| Agent | Role | Scope | Output |
| --- | --- | --- | --- |
| **@Cline** (me) | XML Layouts & Views (Pane 2) | `ntfy-android/app/src/main/res/layout/` | layout XML |
| **@AGY** (Antigravity) | Architecture, Test Suite & Emulator QA (Pane 0) | `tests/`, `docs/`, emulator QA | `tests/test_core.py`, `docs/spec.md` |
| **@Buffy** (Freebuff) | Themes, Colors, Tokens & Drawables (Pane 1) | `config/`, `requirements.txt`, `res/values/`, `res/drawable/` | `AUDIT.md`, tokens |
| **@OpenCode** | Kotlin Data Models, Adapters & Backend Logic (Pane 3) | `*.kt` (models, adapters, backend) | Kotlin sources |

> Previous Binder_vps assignment (me: `agents/` → `agents/core.py`, M1–M4 COMPLETE, 22/22 tests) is preserved in history below. Current pod focus is ntfy-android UI modernization.

## My workstream — current status

- **Scope:** `agents/` (core logic and models)
- **Artifacts I built:**
  - `agents/core.py` — **524 lines**, 19,770 B (18:10:46)
  - `agents/__init__.py` — 8 lines (18:10:04)
- **Status:** ✅ **M1–M4 COMPLETE** — **22/22 tests pass**, 0 skipped, 0 failed.
- **Current gate:** @Buffy's **A3** core security review.
- **Scope discipline verified:** I have touched no other agent's file. `docs/` + `tests/` are
  @AGY's; `AUDIT.md` is @Buffy's; `requirements.txt` untouched.

## Board protocol (CONCURRENT MODE)

`BOARD.md` is the source of truth. **Inspect it before every action.**

1. **Strict file isolation.** Never write into a directory owned by another active agent.
2. **Interface first.** Publish new interfaces under `## Live Sync & Signals`.
3. **Status check.** Update my own bullet under `## Active Workstreams` per finished subtask.
4. **Append, never rewrite.** Never edit another agent's entry or `## Rules for Parallel Work`.

## Radio silence (standing order from @Boss)

1. **Message only after completely finished** editing files and running tests.
2. **Never ping an agent while they are actively running tasks** — check their pane first.
3. **When idle, read `.agents/inbox/cline.txt`** for deferred messages.
4. **If a message is not an explicit handoff of work, do not send it.** No thanks, no status
   chatter, no FYIs, no "already done" replies.

**Consequence:** finish → update `BOARD.md` → *wait*. Only a genuine handoff warrants
`tell.sh` — approval to start, work I'm picking up, or a blocking defect a peer must fix.

## Communication tooling

```bash
./tell.sh <sender> <target> "<message>"      # targets: cline | agy | buffy | opencode
```

- Panes (per @Boss 2026-10-04 briefing — AUTHORITATIVE, supersedes older maps):
  `agy`=0 (`agents:0.0`), `opencode`/`oc`=1 (`agents:0.1`),
  `buffy`=2 (`agents:0.2`), `cline`=3 (`agents:0.3`, me).
  NOTE: `tell.sh` source of truth — verified 2026-10-04 it implements exactly
  this 0/AGY-1/OpenCode-2/Buffy-3/Cline map (earlier memory had stale 0/1/2/3
  = agy/buffy/cline/opencode; DO NOT USE that).
- It busy-waits ~15s before injecting, but its word list
  (`thinking|executing|running|waiting|progress`) misses `Reading` / `Editing`, so it **can**
  inject mid-turn. Always verify with `tmux capture-pane -t 0 -p` after sending.

## Voice hotline — `ask_boss.sh` (briefed 2026-10-04, @Boss)

- **What:** real-time SIP/VoIP bridge Codespace → Boss mobile. TTS (`edge-tts`)
  speaks my question, 880Hz beep, PulseAudio records reply, local STT
  (`faster-whisper tiny.en`, CPU) injects `[Boss Decision via Voice Call]: "..."`
  into my prompt + auto-Enter.
- **My syntax:** `/workspaces/Binder_vps/ask_boss.sh cline "<concise binary question>"`
  (pane auto-detected, but always pass `cline` explicitly as $1).
- **When:** BLOCKERS ONLY needing human sign-off on an architectural fork/ambiguity.
  Peers via `tell.sh`/`BOARD.md`. Questions SHORT + BINARY (2 options, 2–4 word
  answer). NEVER loop/spam — call once, wait for injection, proceed.
- **My identity:** @Cline, Pane 3 (`agents:0.3`), Layout Hierarchy / XML Views.

## Proposal round — settled (2026-09-27)

**Vote: UNANIMOUS 3–0.** @Buffy's `buffy-proposal.md` is the **governing spine**.

| Proposal | Scores received | Avg |
| --- | --- | --- |
| `buffy-proposal.md` (@Buffy) | 8.8 (me), 9.3 (@AGY) | **9.05 — adopted** |
| `agents/cline-proposal.md` (mine) | 8.5 (@Buffy), 9.2 (@AGY) | 8.85 |
| `PROJECT_PLANS.md` (@Buffy) | 7.5 (me), 8.5 (@AGY) | 8.0 |
| `agy-proposal.md` (@AGY) | 7.8 (me), 7.5 (@Buffy) | 7.65 |

**Adopted merge:** @Buffy's §2 rules bind the core · `docs/spec.md` is interface authority ·
`agents/core.py` is the implementation target · @AGY's tests + @Buffy's `AUDIT.md` are gates.

## Design decisions in `core.py` — WHY, not just what

**Do not "fix" these.** Each encodes a resolved review finding.

1. **Length caps live in `validate_spec`, NOT `__post_init__`.** `test_length_caps` requires
   `RepoRef(owner="a"*101, ...)` to *construct* successfully and only fail when used.
2. **`ref` is rejected by path *segment*, not by charset.** `ref="feature/v1.0"` is valid;
   `ref="../secret"` and `ref="refs/../heads"` are not. A charset allowlist cannot express
   this — `.` and `/` are both legal characters.
3. **`binder_path()` derives the provider from `host`** (`github.com`→`gh`, `gitlab.com`→`gl`,
   `bitbucket.org`→`bb`) instead of hardcoding `gh/`. This was my review **correction #2**,
   which @AGY left unfixed in the spec, so I resolved it in code.
4. **Unknown host raises `SpecValidationError`** rather than silently emitting `gh/`.
   *Pending @AGY's confirmation* — the spec is authority if it prefers another policy.
5. **`__post_init__` closes the direct-construction bypass** (`RepoRef(owner="../../etc")`),
   not just `parse()`. @Buffy's §2.2 finding.
6. **`quote(urlpath, safe="/")`** percent-encoding; **https-only `base=`** as a trust
   boundary; **`env` defaults to `{}`** via `field(default_factory=dict)` (my correction #1).
7. **`clock` / `id_factory` injected** — no wall-clock time or random UUID on a testable path.

## Open items

- ⚠️ **`agents/runtime.py` ownership conflict — UNRESOLVED.** @AGY said it placed
  `SystemProbeResult` / `KeepAliveContract` in `agents/runtime.py`, but `agents/` is **my**
  scope and the file does **not** exist. I asked @AGY to choose **OPTION A** (move it into a
  directory it owns) or **OPTION B** (amend `BOARD.md` for file-level ownership) and record the
  choice on the board. **Told it not to create the file until settled.**
- @Buffy's **A3** core security review — not started.
- **Nothing is committed** — `git status` shows every directory as untracked (`??`).

## Project context

- Repo: **Binder_vps** — "binder as vps"; Binder (`mybinder.org`) used as a VPS.
- Files: `index.ipynb`, `requirements.txt` (jupyterlab 4.x, notebook >=7,<8), `README.md`,
  `BOARD.md`, `PROJECT_PLANS.md`, `tell.sh`.
- **`pytest` runs only via `/usr/local/py-utils/bin/pytest`** — the default `python3` has no
  pytest. Use: `/usr/local/py-utils/bin/pytest tests/test_core.py -q`
- Team chat: `.agents/chat.log` · inboxes: `.agents/inbox/<name>.txt`

## Known gotchas

- **Two "agents" dirs, one character apart.** `agents/` (my code scope + shared memory:
  `buffy.md`, `cline.md`, `cline-plan.md`, `cline-proposal.md`) vs `.agents/` (dot) where
  `tell.sh` writes `chat.log` and where Cline, Antigravity and Freebuff all auto-scan for
  `skills/`, `rules/`, `workflows/`. Be precise about which you mean.
- **Never modify or move `agents/buffy.md`** — that is @Buffy's file.
- **`requirements.txt` belongs to @Buffy.** I do not edit it.
- Lines in `BOARD.md` attributed to `[@Cline]` were seeded by another party before I started —
  verify claims about my own status before trusting them.

## My build checklist

- Read `BOARD.md`, then confirm the task is mine before editing anything.
- Read `docs/spec.md` before writing code; match published signatures exactly.
- Match existing conventions; minimal diffs.
- **Validate by running the tests, never by assuming.**
- Report failures and unverified assumptions explicitly.
