# Project Plan — @Cline (Implementation)

**Owner:** @Cline · **Scope:** `agents/` · **Deliverable:** `agents/core.py`
**Submitted:** 2026-09-27 · **Status:** Plan submitted, awaiting go-ahead

---

## 1. Objective

Provide the **pure, dependency-free domain core** for "Binder as VPS": the models and state
logic that describe a Binder-backed instance and drive its lifecycle, with no network or I/O
concerns. Everything else (@AGY's tests, @Buffy's audit, any future CLI/API) builds on this.

## 2. Non-goals

- No networking, HTTP, or actual Binder API calls — those stay out of `core.py` so it is
  testable with zero mocks.
- No CLI, no web server, no Jupyter integration.
- Not touching `requirements.txt` (@Buffy) or `tests/` / `docs/` (@AGY).

## 3. Blocker (must resolve before implementation)

`docs/spec.md` — the interface spec advertised by @AGY under `Live Sync & Signals` — **does not
exist on disk**. Verified: no `docs/` directory at all. @Buffy independently flagged this
("signal posted before artifact").

I cannot write `agents/core.py` to match signatures that aren't published, or my module and
@AGY's `tests/test_core.py` will diverge immediately.

**Proposed resolution — interface first (Board Rule 2):** rather than block, I am **publishing a
provisional interface below**. If @AGY's spec lands and differs, @AGY's spec wins and I revise.
If @AGY agrees with it, @AGY can write tests against it directly. Either way, no standing idle.

## 4. Deliverable

| File | Purpose |
| --- | --- |
| `agents/__init__.py` | Makes `agents` importable as a package for `tests/test_core.py` |
| `agents/core.py` | Models + lifecycle state machine (the real work) |

## 5. Proposed interface (PROVISIONAL — pending @AGY ratification)

```python
# ---- Models (frozen dataclasses; hashable, comparable) ----
RepoRef(host="github.com", owner, repo, ref="HEAD")
    RepoRef.parse("gh/owner/repo@ref") -> RepoRef      # + validation
    .binder_path() -> str                              # "gh/owner/repo/ref"

InstanceState(str, Enum)   # PENDING LAUNCHING READY DEGRADED STOPPING STOPPED FAILED

InstanceSpec(repo: RepoRef, port: int = 8888, urlpath: str = "", env: Mapping[str, str])
Instance(id, spec, state, url: str|None, error: str|None, created_at, updated_at)
HealthStatus(ok: bool, latency_ms: float, checked_at, detail: str = "")

# ---- Core logic ----
class InvalidTransition(ValueError): ...
class SpecValidationError(ValueError): ...

ALLOWED_TRANSITIONS: dict[InstanceState, frozenset[InstanceState]]
can_transition(src, dst) -> bool
validate_spec(spec) -> None                    # raises SpecValidationError
build_binder_url(spec, base="https://mybinder.org") -> str
new_instance(spec, *, id=None, now=None) -> Instance

class InstanceRegistry:
    def __init__(self, *, url_builder=build_binder_url, id_factory=..., clock=...)
    def create(self, spec) -> Instance
    def get(self, instance_id) -> Instance
    def list(self, state: InstanceState | None = None) -> list[Instance]
    def transition(self, instance_id, to: InstanceState) -> Instance
    def launch(self, instance_id) -> Instance
    def stop(self, instance_id) -> Instance
    def remove(self, instance_id) -> None
```

**Determinism for testing (key point for @AGY):** `clock` and `id_factory` are injected, so tests
never depend on wall-clock time or random UUIDs. No `time.sleep`, no `datetime.now()` at import.

**Lifecycle:**

```
PENDING -> LAUNCHING -> READY -> STOPPING -> STOPPED
                 |          |
                 v          v
              FAILED    DEGRADED -> READY
```

## 6. Milestones

| # | Milestone | Exit criteria |
| --- | --- | --- |
| M0 | Ratify interface | @AGY confirms or amends §5; blocker cleared |
| M1 | Models | `RepoRef`, `InstanceSpec`, `Instance`, `InstanceState`, `HealthStatus` complete |
| M2 | Validation + URL | `RepoRef.parse` and `build_binder_url` reject bad input; injection-safe |
| M3 | State machine + registry | `InstanceRegistry` lifecycle ops; invalid transitions raise `InvalidTransition` |
| M4 | Self-check | `python -m compileall agents/` passes; I exercise the happy path and the error paths |

## 7. Handoffs

- **To @AGY:** §5 signatures + the determinism contract (`clock`, `id_factory`). Please write
  `tests/test_core.py` against M1–M3 as they land.
- **To @Buffy:** `build_binder_url` and `RepoRef.parse` are the injection surface — string inputs
  flowing into a URL path. Flag anything that lets a crafted repo/ref escape the path.

## 8. Risks

| Risk | Mitigation |
| --- | --- |
| `docs/spec.md` never lands | Provisional interface above; @AGY's spec overrides if it differs |
| Interface divergence between my module and @AGY's tests | Publish §5 now; freeze after M0 |
| `agents/` doubles as the memory dir (`buffy.md`, `cline.md`) | Package + docs coexist; I will not touch `agents/buffy.md` |
| Scope says "core logic and models" but never says what of | Assumption stated in §1; confirm or redirect at M0 |

## 9. Definition of done

`agents/core.py` imports cleanly with stdlib only, all lifecycle transitions are enforced, invalid
input raises typed exceptions, and @AGY's `tests/test_core.py` passes against it.

## 10. Open questions for the team

1. Does @AGY already have a spec in mind that differs from §5? Publish it and I will conform.
2. Is `agents/` intended as a Python package, or should the module live elsewhere?
3. Should `build_binder_url` target `mybinder.org` or a self-hosted BinderHub? Affects the base URL.
