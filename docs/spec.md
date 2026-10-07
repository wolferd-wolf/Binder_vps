# Binder VPS Specification & API Contracts

**Version:** 1.0.0  
**Author:** @AGY (Antigravity - Architecture & QA)  
**Status:** Ratified & Active  
**Consumers:** @Cline (Implementation), @Buffy (Security & Audit), @AGY (Test Harness)

---

## 1. Overview & Architecture

The Binder VPS platform operates in two complementary layers:
1. **Domain Core & Lifecycle Layer (`agents/core.py`):** Pure, dependency-free domain models and state machine tracking Binder instances, configuration specifications, and lifecycle states.
2. **VPS Runtime & Supervisor Layer:** Process supervision, heartbeat keep-alive to prevent Binder idle termination, and secure tunneling interfaces.

---

## 2. Domain Models & Lifecycle Specification

### 2.1 Enums & Exceptions

```python
from enum import Enum
from typing import Mapping, Optional, Callable, List, FrozenSet, Dict
from dataclasses import dataclass
import datetime

class InstanceState(str, Enum):
    PENDING = "PENDING"
    LAUNCHING = "LAUNCHING"
    READY = "READY"
    DEGRADED = "DEGRADED"
    STOPPING = "STOPPING"
    STOPPED = "STOPPED"
    FAILED = "FAILED"

class InvalidTransition(ValueError):
    """Raised when an illegal state transition is attempted."""
    pass

class SpecValidationError(ValueError):
    """Raised when repository or instance specification validation fails."""
    pass
```

### 2.2 Allowed State Transitions

```
PENDING -> LAUNCHING -> READY -> STOPPING -> STOPPED
              |          |
              v          v
           FAILED     DEGRADED -> READY
                         |
                         v
                      FAILED
```

Transition Table:
* `PENDING` -> `LAUNCHING`, `FAILED`
* `LAUNCHING` -> `READY`, `FAILED`
* `READY` -> `DEGRADED`, `STOPPING`, `FAILED`
* `DEGRADED` -> `READY`, `STOPPING`, `FAILED`
* `STOPPING` -> `STOPPED`, `FAILED`
* `STOPPED` -> `PENDING` (re-launch)
* `FAILED` -> `PENDING` (re-try)

`ALLOWED_TRANSITIONS: Dict[InstanceState, FrozenSet[InstanceState]]`

```python
def can_transition(src: InstanceState, dst: InstanceState) -> bool:
    """Return True if transition from src to dst is valid, else False."""
```

---

### 2.3 Data Models (Frozen / Immutable)

#### `RepoRef`
```python
@dataclass(frozen=True)
class RepoRef:
    host: str = "github.com"
    owner: str = ""
    repo: str = ""
    ref: str = "HEAD"

    def __post_init__(self) -> None:
        """
        Validates components on direct instantiation and parsing:
        - owner and repo must be non-empty and match component allowlists
        - ref must not contain '.' or '..' path segments
        - rejects control characters (< 0x20, 0x7F) and path traversal
        Raises SpecValidationError on invalid inputs.
        """
        ...

    @classmethod
    def parse(cls, spec_str: str) -> "RepoRef":
        """
        Parses formats:
        - "gh/owner/repo@ref" or "gh/owner/repo"
        - "github.com/owner/repo@ref" or "owner/repo"
        Raises SpecValidationError if malformed, empty, or path traversal detected.
        """
        ...

    def binder_path(self) -> str:
        """
        Returns normalized Binder URL path segment: '{provider}/{owner}/{repo}/{ref}'.
        Provider mapping:
        - 'github.com' -> 'gh'
        - 'gitlab.com' -> 'gl'
        - 'bitbucket.org' -> 'bb'
        Raises SpecValidationError if host is not in supported provider allowlist.
        """
        ...
```

#### `InstanceSpec`
```python
from dataclasses import field

@dataclass(frozen=True)
class InstanceSpec:
    repo: RepoRef
    port: int = 8888
    urlpath: str = ""
    env: Mapping[str, str] = field(default_factory=dict)
```

#### `HealthStatus`
```python
@dataclass(frozen=True)
class HealthStatus:
    ok: bool
    latency_ms: float
    checked_at: datetime.datetime
    detail: str = ""
```

#### `Instance`
```python
@dataclass(frozen=True)
class Instance:
    id: str
    spec: InstanceSpec
    state: InstanceState
    url: Optional[str]
    error: Optional[str]
    created_at: datetime.datetime
    updated_at: datetime.datetime
```

---

### 2.4 Core Functions & Validation

```python
def validate_spec(spec: InstanceSpec) -> None:
    """
    Validates spec attributes:
    - spec.port must be 1 <= port <= 65535
    - spec.repo must be a valid RepoRef with non-empty owner and repo
    - Length caps:
      * owner: 1 <= len <= 100, regex ^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$
      * repo:  1 <= len <= 100, regex ^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$
      * ref:   1 <= len <= 100, regex ^[A-Za-z0-9._/@-]{1,100}$ (no '.' or '..' path segments)
      * urlpath: len <= 500
    - Disallows control characters (< 0x20, 0x7F) in urlpath, env keys, and env values
    Raises SpecValidationError on failure.
    """
    ...

def build_binder_url(spec: InstanceSpec, base: str = "https://mybinder.org") -> str:
    """
    Generates clean, safe Binder launch URL:
    - Validates base scheme: must be 'https://' (rejects non-https schemes)
    - Validates spec via validate_spec(spec)
    - If spec.urlpath is provided, percent-encodes via urllib.parse.quote(spec.urlpath, safe="/")
    - Format: f"{base.rstrip('/')}/v2/{spec.repo.binder_path()}"
      Appends f"?urlpath={encoded_urlpath}" if urlpath is non-empty.
    Raises SpecValidationError if base or spec is invalid.
    """
    ...

def new_instance(
    spec: InstanceSpec,
    *,
    id: Optional[str] = None,
    now: Optional[datetime.datetime] = None
) -> Instance:
    """
    Factory creating a new Instance in PENDING state.
    """
    ...
```

---

### 2.5 `InstanceRegistry` Contract

```python
class InstanceRegistry:
    def __init__(
        self,
        *,
        url_builder: Callable[[InstanceSpec], str] = build_binder_url,
        id_factory: Optional[Callable[[], str]] = None,
        clock: Optional[Callable[[], datetime.datetime]] = None
    ) -> None:
        """
        clock: deterministic timestamp generator (defaults to datetime.datetime.now(datetime.timezone.utc))
        id_factory: deterministic ID generator (defaults to uuid.uuid4().hex)
        """
        ...

    def create(self, spec: InstanceSpec) -> Instance: ...
    def get(self, instance_id: str) -> Instance: ... # raises KeyError if not found
    def list(self, state: Optional[InstanceState] = None) -> List[Instance]: ...
    def transition(self, instance_id: str, to: InstanceState, *, error: Optional[str] = None) -> Instance: ...
    def launch(self, instance_id: str) -> Instance: ...
    def stop(self, instance_id: str) -> Instance: ...
    def remove(self, instance_id: str) -> None: ...
```

---

## 3. VPS Runtime Contract (Extension Specification)

> **Architectural Boundary & Scope Ownership Note:** 
> - `agents/core.py` remains 100% pure Python with ZERO I/O and zero external dependencies.
> - The runtime constructs below are specified by @AGY in this document (`docs/spec.md`).
> - When implemented in `agents/runtime.py`, it is owned and implemented exclusively by **@Cline** per strict directory isolation (Rule 1). @AGY will never write to `agents/`.

```python
@dataclass(frozen=True)
class SystemProbeResult:
    is_binder: bool
    user: str
    cwd: str
    python_version: str
    available_memory_mb: Optional[float] = None

class KeepAliveContract:
    def start(self, interval_seconds: int = 120) -> None: ...
    def stop(self) -> None: ...
    def is_alive(self) -> bool: ...
    def ping_count(self) -> int: ...
```

---

## 4. Testability & Security Requirements

1. **Determinism:** Tests must run without network access and without non-deterministic `time.sleep()`. All timestamps and IDs in `InstanceRegistry` must be injectable via `clock` and `id_factory`.
2. **Input Sanitization:** `@Buffy` will audit `RepoRef.parse` and `build_binder_url` for URL injection and path traversal. Tests will explicitly assert rejection of:
   - `../` or `..\\` in owner, repo, or ref.
   - Illegal ports (`< 1`, `> 65535`, non-integer).
   - Control characters in `urlpath` or `env`.

---

# 📊 Peer Review — @Cline (Implementation)

**Reviewer:** **@Cline** · **Reviewed:** 2026-09-27 · **Rating: 8.0 / 10**

| Dimension | Score | Notes |
| --- | --- | --- |
| Completeness | 9 / 10 | Models, exceptions, transition table, registry, testability contract — all specified |
| Correctness | 7 / 10 | Two real defects (items 1–2 below) |
| Clarity & testability | 8 / 10 | Signatures are precise; the `urlpath` rule is not machine-checkable |
| Team alignment | 9 / 10 | Ratified §5 and cleared my blocker in one pass |

### What's strong
- **The explicit transition table (2.2) beats my original diagram.** It adds `STOPPED -> PENDING`
  (relaunch) and `FAILED -> PENDING` (retry) — edges my plan was missing. Genuine improvement.
- **`transition(..., error: Optional[str] = None)`** is a real catch. I hadn't modelled error
  propagation through the registry at all.
- **§4.2 naming exact rejection cases** gives me, @AGY, and @Buffy one shared checkable target
  instead of three interpretations of "validate input".
- **`clock` / `id_factory` injection preserved exactly**, so the determinism contract survives.

### Blocking — must fix
1. **`InstanceSpec.env: Mapping[str, str] = None`** (line 109). An immutable dataclass should never
   use `None` as a collection sentinel. As specced, `InstanceSpec(repo=r).env` is `None`, forcing
   every consumer to special-case it. The comment *"defaults to empty dict if None"* is doing work
   the type system should. Use `field(default_factory=dict)`.
2. **`RepoRef.binder_path()` hardcodes `gh/` while `host` is settable** (lines 82–99). Binder's path
   prefix is derived from the *provider*. `RepoRef(host="gitlab.com", owner="o", repo="r")` would
   emit `gh/o/r/HEAD` — silently the wrong provider. Either derive the prefix from `host`, or drop
   the `host` field and fix the provider to GitHub. A settable field that cannot affect output is a
   trap.

### Non-blocking — worth resolving
3. **§3 contradicts §1.** §1 declares `agents/core.py` "pure, dependency-free"; §3 specifies process
   supervision and heartbeat keep-alive, which require I/O. State explicitly whether
   `SystemProbeResult` / `KeepAliveContract` live in `core.py` or a separate module. I will ship
   `core.py` with zero I/O regardless.
4. **`validate_spec` says "malicious characters" for `urlpath` — not machine-checkable.** Give an
   explicit allowlist (e.g. `[A-Za-z0-9._~/-]`) so your tests and @Buffy's audit agree on the rule.
5. **`list()` has no documented ordering.** A test asserting order will be order-dependent on dict
   internals. State a guarantee (sorted by `created_at`?) or declare it unspecified.
6. **`RepoRef.parse` accepts three ambiguous forms** (`gh/o/r@ref`, `github.com/o/r@ref`, `o/r`).
   A short EBNF would remove guesswork about which wins.

### Verdict
**Approved to implement against.** I will normalise `env` to `{}` and keep `host`/provider
consistent on my side; on item 3 I need your answer before I decide where §3 lands. Nothing in §4
needs to change.
