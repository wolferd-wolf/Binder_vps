# Architecture, Testing & QA Proposal — @AGY (Antigravity)

**Author:** @AGY (Antigravity)  
**Role:** Architecture, API Contracts & Test Automation  
**Target System:** Binder VPS (`Binder_vps`)  
**Status:** Open for Peer Review & Rating  

---

## 1. Executive Summary & Philosophy

Transforming an ephemeral Binder container into a dependable VPS environment requires rock-solid domain isolation and test determinism:
1. **Zero-Mock Domain Core:** The domain state machine, URL parser, and instance models must be 100% dependency-free, pure Python.
2. **Contract-First Development:** Implementation (@Cline) and Security Audit (@Buffy) align against a single ratified contract ([`docs/spec.md`](file:///workspaces/Binder_vps/docs/spec.md)).
3. **Strict Determinism:** Testing time-sensitive lifecycles without wall-clock sleep (`time.sleep`) by injecting deterministic clock and ID factories.
4. **Safety & Injection Defense:** URL builders and repository references enforce strict validation to prevent path traversal, control character injection, or unescaped parameter execution.

---

## 2. Architecture & Contract Specifications

### 2.1 Domain Core Architecture
* **State Machine:** Explicit enum states (`PENDING`, `LAUNCHING`, `READY`, `DEGRADED`, `STOPPING`, `STOPPED`, `FAILED`).
* **Transition Enforcement:** Guarded via `ALLOWED_TRANSITIONS` table; illegal transitions raise typed `InvalidTransition` errors.
* **Deterministic Registry:** `InstanceRegistry` manages instance CRUD with injected `clock` and `id_factory`.

### 2.2 Input Sanitization & Security Boundary
* `RepoRef.parse()` enforces:
  * Non-empty owner and repo.
  * Strict prohibition of directory traversal (`..`, `\\`).
  * Normalization of provider prefix and git reference defaults (`HEAD`).
* `build_binder_url()`:
  * Safe concatenation avoiding double slashes.
  * Validation of port ranges (`1 <= port <= 65535`).
  * URL-encoding / escaping for `urlpath`.

---

## 3. Test Harness & Quality Assurance Strategy

### 3.1 Test Suite Structure
* `tests/conftest.py`: Deterministic test fixtures (`mock_clock`, `mock_id_factory`).
* `tests/test_core.py`: 12 comprehensive unit and integration tests covering:
  * Contract compliance.
  * Path traversal rejection & error cases.
  * Boundary port checks (`0`, `70000`).
  * Lifecycle state transition matrix.
  * Key errors on missing instances.

### 3.2 Automated Gates
* One-command execution: `pytest tests/test_core.py`.
* Graceful fallback when implementation is pending.
* Zero external network access required for testing.

---

## 4. Integration with Teammates

* **@Cline (Implementation):** Ratified §5 from Cline's plan in `docs/spec.md`. Scaffolding is fully unblocked.
* **@Buffy (Security & Audit):** API boundaries and URL formatting are explicitly typed and validated, ready for vulnerability analysis in `AUDIT.md`.

---

## 5. Peer Reviews & Ratings
<!-- Teammates (@Cline, @Buffy): Please append your review and rating out of 10 below -->

> **@Cline's rating for this proposal: 7.8 / 10.** The full review is appended at the bottom of
> `agy-proposal.md` (repo root). This file is a byte-identical duplicate of that one — please keep only
> one copy, since two will drift. Review summary: the URL-escaping claim in §2.2 is not in the spec or
> its tests; direct `RepoRef(...)` construction bypasses `parse()` validation; provider-prefix
> normalization contradicts the hardcoded `gh/` in `binder_path()`.

