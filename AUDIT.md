# AUDIT.md — Security & Audit Ledger

**Auditor:** **@Buffy** (Freebuff) · **Started:** 2026-09-27 · **Severity scale (single):** Critical / High / Medium / Low — "blocking" ≡ Critical or High.
**Governing spine:** `buffy-proposal.md` §2 · **Contract authority:** `docs/spec.md` · **Implementation:** `agents/core.py`

---

## §1 · A1 — Dependency Audit (`requirements.txt`)

**Status: 🟡 PENDING tool scan** (verdict promised pending `pip-audit`/`safety`; neither is installed in this environment and I will not install packages without user approval).

| Package | Pin | Static assessment | Verdict |
|---|---|---|---|
| `jupyterlab` | `>=4,<5` | Major 4.x line; bounded range prevents surprise major bumps; no known unpatched Critical at time of writing | Provisional OK — **PENDING CVE scan** |
| `notebook` | `>=7,<8` | Major 7.x line (single-server architecture); bounded range | Provisional OK — **PENDING CVE scan** |

- **A1-LOW-01 (open):** No upper-lock (`==`) pins — Binder resolves at build time; a freshly-published broken release inside the minor range could break builds. Recommend a pinned lockfile for reproducible builds. *(Owner: @Buffy + user decision.)*
- **A1-INFO-01:** `pytest` used by `tests/` is absent from `requirements.txt` — fine for Binder runtime (tests are dev-only) but the team cannot run the gate in this environment. Needs `pip install pytest` (user approval required) or a `requirements-dev.txt`.

**Recommended command once tooling is available:** `pip install pip-audit && pip-audit -r requirements.txt`

---

## §2 · A3 — Core Security Review (`agents/core.py`, @Cline)

**Method:** full source read + 16-probe adversarial suite executed against the live module (stdlib harness, no pytest required) + `python -m compileall` (clean).

### Probes executed — ALL PASS ✅

| # | Attack vector probed | Result |
|---|---|---|
| 1 | `ref="../etc"` direct construction | ✅ Rejected (`SpecValidationError`) — **my §2.3 hole, confirmed closed** (`core.py` `__post_init__` segment check) |
| 2 | `ref="x/../../y"` (mid-path traversal) | ✅ Rejected |
| 3 | `ref="."` (bare dot segment) | ✅ Rejected |
| 4 | `ref="feature/x"` (legit slash ref) | ✅ Accepted — allowlist not overzealous |
| 5 | `owner="../../etc"` (direct-construction bypass) | ✅ Rejected — `__post_init__` closes the parse()-only gap |
| 6 | `base="http://…"` (downgrade) | ✅ Rejected — https-only enforced in `build_binder_url` |
| 7 | `base="javascript:alert(1)"` (scheme injection) | ✅ Rejected |
| 8 | `base="https://…/"` (legit, trailing slash) | ✅ Accepted, slash normalized |
| 9 | URL encoding: `urlpath="tree/my folder/n.ipynb?token=1"` | ✅ Output byte-exact: `?urlpath=tree/my%20folder/n.ipynb%3Ftoken%3D1` (`quote(safe="/")` per ratified spec) |
| 10 | `urlpath` 501 chars | ✅ Rejected (cap 500) |
| 11 | `urlpath` with `\x00` | ✅ Rejected |
| 12 | `env` value with `\x1b` (ANSI escape → log injection) | ✅ Rejected |
| 13 | `port=0` / `port=70000` | ✅ Rejected |
| 14 | `port=True` (bool-is-int footgun) | ✅ Rejected — explicit bool guard, nice touch |
| 15 | Registry `PENDING → STOPPED` | ✅ `InvalidTransition` raised |
| 16 | Zero-I/O token scan (`subprocess`, `socket`, `requests`, `os.system`, `eval(`, `exec(`, `open(`) | ✅ No imports/usages — single docstring *mention* of "subprocess" is the ban list itself (false positive, verified at line 7) |

### Review verdict

**🟢 PASS — zero open Critical/High findings.** The implementation matches the governing spine point-for-point, including the two fixes I demanded of my own proposal (per-component allowlists, `.`/`..` segment rejection in `ref`) and the spec corrections @Cline filed upstream (`env` factory default, provider/`host` consistency — hosts are allowlisted to `github.com`/`gitlab.com`/`bitbucket.org`, never guessed). Construction-time and boundary-time validation are both real: `RepoRef.__post_init__` (`core.py`) enforces charsets/traversal, `validate_spec` enforces bounds — defense in depth as specified.

### Advisory findings (non-blocking)

| ID | Severity | Finding | Recommendation |
|---|---|---|---|
| A3-LOW-01 | Low | Length caps (owner/repo 100, ref 255, urlpath 500) live only in `validate_spec`, not `__post_init__` — a `RepoRef` with a 10k-char owner constructs fine and fails only at URL-build time. Documented tradeoff in the source; the URL surface **is** protected (`build_binder_url` calls `validate_spec`). | Acceptable as-is. If `RepoRef` is ever serialized outside a spec, add caps at construction. |
| A3-LOW-02 | Low | `transition(error=…)` stores caller-supplied error strings verbatim. Core never logs (zero I/O holds), but a runtime layer that logs `instance.error` could ingest attacker-flavored text. | Outer supervisor layer must sanitize/error-code-ify before logging. Noted for the VPS runtime contract (`KeepAliveContract` consumer). |
| A3-INFO-01 | Info | `new_instance` uses real `uuid4`/UTC defaults when un-injected — correct and documented; registry injects both. No nondeterminism on tested paths. | None. |
| A3-INFO-02 | Info | Caps are 500/255 here vs the 512/200 sketched in `buffy-proposal.md`; ratified `docs/spec.md` values (500/255) govern. | Aligned to spec — no action. |

---

## §3 · A4 — Notebook Review (`index.ipynb`)

**Status: 🟡 SCHEDULED** — runs after the team wires the core entry point (M4 integration), so I review the final notebook state once, not a moving target.

## §4 · A2 — Config Contract (`config/`)

**Status: 🟡 NEXT** — `config/defaults.json` + `config/schema.md` (env-var-driven, https-only base allowlist, caps mirrored from ratified spec). Not yet created.

## §5 · A5 — Continuous Audit Ledger

| Date | Event | Open Crit/High |
|---|---|---|
| 2026-09-27 | A3 core review complete: **PASS**, 0 Critical/High, 2 Low advisories, 2 Info | **0** |
| 2026-09-27 | A1 dependency verdict PENDING `pip-audit`/`safety` (A1-LOW-01 lockfile rec open) | 0 |

**Current gate status:** @Cline's `agents/core.py` is **cleared** by security review. Project cannot be declared "done" until A1 tool scan runs (pending user-approved tooling) and A4 notebook review completes.
