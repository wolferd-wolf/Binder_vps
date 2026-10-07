"""Binder VPS — pure domain core.

Domain models and the lifecycle state machine for a Binder-backed instance.

Design constraints (``docs/spec.md`` §1, ``buffy-proposal.md`` §2):

* **Zero I/O.** Standard library only. No network, filesystem, subprocess, or clock access
  at import time, so the module is auditable and testable without mocks.
* **Deterministic.** :class:`InstanceRegistry` receives its ``clock`` and ``id_factory`` by
  injection; nothing calls ``datetime.now()``/``uuid4()`` on a code path a test depends on.
* **Validate at construction *and* at the boundary.** ``RepoRef.__post_init__`` closes the
  direct-construction bypass that ``RepoRef.parse`` alone leaves open.
* **Allowlist, not blocklist.** Every externally-derived component is matched against an
  explicit character allowlist before it can reach a URL.

Contract authority: ``docs/spec.md``. Governing security spine: ``buffy-proposal.md``.
"""

from __future__ import annotations

import datetime
import re
import uuid
from dataclasses import dataclass, field, replace
from enum import Enum
from typing import Callable, Dict, FrozenSet, List, Mapping, Optional
from urllib.parse import quote

__all__ = [
    "ALLOWED_TRANSITIONS",
    "HealthStatus",
    "Instance",
    "InstanceRegistry",
    "InstanceState",
    "InstanceSpec",
    "InvalidTransition",
    "RepoRef",
    "SpecValidationError",
    "build_binder_url",
    "can_transition",
    "new_instance",
    "validate_spec",
]


# --------------------------------------------------------------------------------------
# 2.1 Enums & exceptions
# --------------------------------------------------------------------------------------


class InvalidTransition(ValueError):
    """Raised when an illegal state transition is attempted."""


class SpecValidationError(ValueError):
    """Raised when a repository reference or instance spec fails validation."""


class InstanceState(str, Enum):
    """Lifecycle state of a Binder instance."""

    PENDING = "PENDING"
    LAUNCHING = "LAUNCHING"
    READY = "READY"
    DEGRADED = "DEGRADED"
    STOPPING = "STOPPING"
    STOPPED = "STOPPED"
    FAILED = "FAILED"


# --------------------------------------------------------------------------------------
# 2.2 Allowed state transitions
# --------------------------------------------------------------------------------------

ALLOWED_TRANSITIONS: Dict[InstanceState, FrozenSet[InstanceState]] = {
    InstanceState.PENDING: frozenset({InstanceState.LAUNCHING, InstanceState.FAILED}),
    InstanceState.LAUNCHING: frozenset({InstanceState.READY, InstanceState.FAILED}),
    InstanceState.READY: frozenset(
        {InstanceState.DEGRADED, InstanceState.STOPPING, InstanceState.FAILED}
    ),
    InstanceState.DEGRADED: frozenset(
        {InstanceState.READY, InstanceState.STOPPING, InstanceState.FAILED}
    ),
    InstanceState.STOPPING: frozenset({InstanceState.STOPPED, InstanceState.FAILED}),
    InstanceState.STOPPED: frozenset({InstanceState.PENDING}),
    InstanceState.FAILED: frozenset({InstanceState.PENDING}),
}
"""Adjacency table. ``STOPPED``/``FAILED`` -> ``PENDING`` model re-launch and retry."""


def can_transition(src: InstanceState, dst: InstanceState) -> bool:
    """Return ``True`` if the transition ``src`` -> ``dst`` is permitted."""
    return dst in ALLOWED_TRANSITIONS.get(src, frozenset())


# --------------------------------------------------------------------------------------
# Validation helpers
# --------------------------------------------------------------------------------------

_CONTROL_CHARS = re.compile(r"[\x00-\x1f\x7f]")
"""Control characters rejected anywhere they could reach a URL or a log line."""

_HOST_TO_PROVIDER: Dict[str, str] = {
    "github.com": "gh",
    "gitlab.com": "gl",
    "bitbucket.org": "bb",
}
"""Binder provider prefixes, keyed by host. Anything else is refused, not guessed."""

_PROVIDER_TO_HOST: Dict[str, str] = {v: k for k, v in _HOST_TO_PROVIDER.items()}

_OWNER_ALLOWLIST = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
_REPO_ALLOWLIST = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
_REF_ALLOWLIST = re.compile(r"^[A-Za-z0-9._/@-]+$")

MAX_OWNER_LEN = 100
MAX_REPO_LEN = 100
MAX_REF_LEN = 255
MAX_URLPATH_LEN = 500

#: Binder expects ``provider/owner/repo/ref``; the ``gh/.../HEAD`` form is what
#: mybinder.org serves for a default-branch launch.
_BINDER_PATH_TEMPLATE = "{provider}/{owner}/{repo}/{ref}"


def _has_control_chars(value: str) -> bool:
    """Return ``True`` if *value* contains a control character (``< 0x20`` or ``0x7F``)."""
    return _CONTROL_CHARS.search(value) is not None


def _utc_now() -> datetime.datetime:
    """Default clock: timezone-aware UTC. Never called on an injectable code path."""
    return datetime.datetime.now(datetime.timezone.utc)


# --------------------------------------------------------------------------------------
# 2.3 Data models
# --------------------------------------------------------------------------------------


@dataclass(frozen=True)
class RepoRef:
    """An immutable reference to a Git repository on a supported provider.

    ``__post_init__`` validates every component, so **direct construction is as safe as**
    :meth:`parse` — closing the bypass that a parser-only boundary leaves open.

    Charset and traversal rules live here. *Length* caps live in :func:`validate_spec`, so
    constructing a reference you only intend to inspect is never rejected for a bound that
    only matters once the spec is actually used.
    """

    host: str = "github.com"
    owner: str = ""
    repo: str = ""
    ref: str = "HEAD"

    def __post_init__(self) -> None:
        for name in ("host", "owner", "repo", "ref"):
            value = getattr(self, name)
            if not isinstance(value, str):
                raise SpecValidationError(
                    f"RepoRef.{name} must be a string, got {type(value).__name__}"
                )
            if _has_control_chars(value):
                raise SpecValidationError(
                    f"RepoRef.{name} must not contain control characters (< 0x20, 0x7F)"
                )

        if self.host.lower() not in _HOST_TO_PROVIDER:
            raise SpecValidationError(
                f"unsupported provider host {self.host!r}; known hosts: "
                + ", ".join(sorted(_HOST_TO_PROVIDER))
            )

        if not self.owner:
            raise SpecValidationError("RepoRef.owner must be non-empty")
        if not self.repo:
            raise SpecValidationError("RepoRef.repo must be non-empty")

        if not _OWNER_ALLOWLIST.match(self.owner):
            raise SpecValidationError(
                f"RepoRef.owner {self.owner!r} must match {_OWNER_ALLOWLIST.pattern}"
            )
        if not _REPO_ALLOWLIST.match(self.repo):
            raise SpecValidationError(
                f"RepoRef.repo {self.repo!r} must match {_REPO_ALLOWLIST.pattern}"
            )
        if not _REF_ALLOWLIST.match(self.ref):
            raise SpecValidationError(
                f"RepoRef.ref {self.ref!r} must match {_REF_ALLOWLIST.pattern}"
            )

        # A ref may legitimately contain '/' (feature/v1.0) but never a '.' or '..'
        # segment: those escape the segment once concatenated into a Binder URL.
        for segment in self.ref.split("/"):
            if segment in (".", ".."):
                raise SpecValidationError(
                    f"RepoRef.ref {self.ref!r} must not contain a {segment!r} path segment"
                )

    @property
    def provider(self) -> str:
        """Binder provider prefix derived from :attr:`host` (``github.com`` -> ``gh``)."""
        return _HOST_TO_PROVIDER[self.host.lower()]

    def binder_path(self) -> str:
        """Return the normalised Binder path segment, e.g. ``gh/owner/repo/HEAD``."""
        return _BINDER_PATH_TEMPLATE.format(
            provider=self.provider, owner=self.owner, repo=self.repo, ref=self.ref
        )

    @classmethod
    def parse(cls, spec_str: str) -> "RepoRef":
        """Parse ``gh/owner/repo@ref``, ``host/owner/repo@ref`` or ``owner/repo``.

        Raises:
            SpecValidationError: if the reference is malformed, empty, carries control
                characters, or contains path traversal.
        """
        if not isinstance(spec_str, str):
            raise SpecValidationError(
                f"repository reference must be a string, got {type(spec_str).__name__}"
            )
        candidate = spec_str.strip()
        if not candidate:
            raise SpecValidationError("repository reference must be non-empty")
        if _has_control_chars(candidate):
            raise SpecValidationError(
                "repository reference must not contain control characters (< 0x20, 0x7F)"
            )

        path_part, _, ref_part = candidate.rpartition("@")
        if not path_part:
            # No '@' present: rpartition yields ('', '', whole).
            path_part, ref_part = candidate, ""
        ref = ref_part or "HEAD"

        segments = path_part.split("/")
        if any(segment == "" for segment in segments):
            raise SpecValidationError(
                f"repository reference {spec_str!r} contains an empty path segment"
            )

        host = "github.com"
        if segments[0].lower() in _PROVIDER_TO_HOST:
            host = _PROVIDER_TO_HOST[segments[0].lower()]
            segments = segments[1:]
        elif segments[0].lower() in _HOST_TO_PROVIDER:
            host = segments[0].lower()
            segments = segments[1:]

        if len(segments) != 2:
            raise SpecValidationError(
                f"repository reference {spec_str!r} must be 'owner/repo', "
                "'provider/owner/repo' or 'host/owner/repo', optionally suffixed with '@ref'"
            )

        owner, repo = segments
        return cls(host=host, owner=owner, repo=repo, ref=ref)


@dataclass(frozen=True)
class InstanceSpec:
    """Desired configuration for a Binder instance."""

    repo: RepoRef
    port: int = 8888
    urlpath: str = ""
    env: Mapping[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class HealthStatus:
    """Result of a single liveness probe against an instance."""

    ok: bool
    latency_ms: float
    checked_at: datetime.datetime
    detail: str = ""


@dataclass(frozen=True)
class Instance:
    """A tracked Binder instance and its current lifecycle state."""

    id: str
    spec: InstanceSpec
    state: InstanceState
    url: Optional[str]
    error: Optional[str]
    created_at: datetime.datetime
    updated_at: datetime.datetime


# --------------------------------------------------------------------------------------
# 2.4 Core functions & validation
# --------------------------------------------------------------------------------------


def validate_spec(spec: InstanceSpec) -> None:
    """Validate *spec* at the boundary.

    Charset and traversal rules were already enforced by :meth:`RepoRef.__post_init__`;
    what remains here are the bounds that only matter once the spec is actually used.

    Raises:
        SpecValidationError: on an out-of-range port, an oversize component, or a control
            character in ``urlpath`` or ``env``.
    """
    if not isinstance(spec, InstanceSpec):
        raise SpecValidationError(f"spec must be an InstanceSpec, got {type(spec).__name__}")

    port = spec.port
    if isinstance(port, bool) or not isinstance(port, int):
        raise SpecValidationError(f"spec.port must be an integer, got {type(port).__name__}")
    if not 1 <= port <= 65535:
        raise SpecValidationError(f"spec.port must be 1 <= port <= 65535, got {port}")

    repo = spec.repo
    if not isinstance(repo, RepoRef):
        raise SpecValidationError(f"spec.repo must be a RepoRef, got {type(repo).__name__}")
    if len(repo.owner) > MAX_OWNER_LEN:
        raise SpecValidationError(
            f"spec.repo.owner must be at most {MAX_OWNER_LEN} characters, got {len(repo.owner)}"
        )
    if len(repo.repo) > MAX_REPO_LEN:
        raise SpecValidationError(
            f"spec.repo.repo must be at most {MAX_REPO_LEN} characters, got {len(repo.repo)}"
        )
    if len(repo.ref) > MAX_REF_LEN:
        raise SpecValidationError(
            f"spec.repo.ref must be at most {MAX_REF_LEN} characters, got {len(repo.ref)}"
        )

    if not isinstance(spec.urlpath, str):
        raise SpecValidationError(
            f"spec.urlpath must be a string, got {type(spec.urlpath).__name__}"
        )
    if _has_control_chars(spec.urlpath):
        raise SpecValidationError("spec.urlpath must not contain control characters (< 0x20, 0x7F)")
    if len(spec.urlpath) > MAX_URLPATH_LEN:
        raise SpecValidationError(
            f"spec.urlpath must be at most {MAX_URLPATH_LEN} characters, got {len(spec.urlpath)}"
        )

    if not isinstance(spec.env, Mapping):
        raise SpecValidationError("spec.env must be a mapping of str to str")
    for key, value in spec.env.items():
        if not isinstance(key, str) or not isinstance(value, str):
            raise SpecValidationError("spec.env keys and values must be strings")
        if _has_control_chars(key):
            raise SpecValidationError(
                f"spec.env key {key!r} must not contain control characters (< 0x20, 0x7F)"
            )
        if _has_control_chars(value):
            raise SpecValidationError(
                f"spec.env value for {key!r} must not contain control characters (< 0x20, 0x7F)"
            )


def build_binder_url(spec: InstanceSpec, base: str = "https://mybinder.org") -> str:
    """Build the launch URL for *spec*.

    ``base`` is a trust boundary: a typo or an injected value silently redirects every
    launch elsewhere, so its scheme is pinned to ``https``. Only trusted config should
    supply it.

    Raises:
        SpecValidationError: if ``base`` is not an https origin, or *spec* is invalid.
    """
    if not isinstance(base, str):
        raise SpecValidationError(f"base must be a string, got {type(base).__name__}")
    if _has_control_chars(base):
        raise SpecValidationError("base must not contain control characters (< 0x20, 0x7F)")

    origin = base.strip().rstrip("/")
    if not origin.lower().startswith("https://"):
        raise SpecValidationError(f"base must use the https:// scheme, got {base!r}")

    validate_spec(spec)

    url = f"{origin}/v2/{spec.repo.binder_path()}"
    if spec.urlpath:
        # Percent-encoded but '/'-safe, so nested Jupyter paths still resolve.
        url = f"{url}?urlpath={quote(spec.urlpath, safe='/')}"
    return url


def new_instance(
    spec: InstanceSpec,
    *,
    id: Optional[str] = None,
    now: Optional[datetime.datetime] = None,
) -> Instance:
    """Create a new :class:`Instance` in ``PENDING`` state.

    ``id`` and ``now`` default to a random hex id and the current UTC time. Both are
    injectable so callers needing determinism — notably :class:`InstanceRegistry` — can pin
    them rather than reaching for ambient state.
    """
    validate_spec(spec)
    instance_id = uuid.uuid4().hex if id is None else id
    timestamp = _utc_now() if now is None else now
    return Instance(
        id=instance_id,
        spec=spec,
        state=InstanceState.PENDING,
        url=None,
        error=None,
        created_at=timestamp,
        updated_at=timestamp,
    )


# --------------------------------------------------------------------------------------
# 2.5 InstanceRegistry
# --------------------------------------------------------------------------------------


class InstanceRegistry:
    """In-memory registry of :class:`Instance` objects and their lifecycle transitions.

    All ambient state is injected. ``clock`` and ``id_factory`` default to real
    implementations, but tests supply deterministic ones so that no test depends on
    wall-clock time or random UUIDs.

    Deliberately in-memory and not thread-safe: persistence, locking, and any I/O belong to
    an outer layer, which keeps this module pure.
    """

    def __init__(
        self,
        *,
        url_builder: Callable[[InstanceSpec], str] = build_binder_url,
        id_factory: Optional[Callable[[], str]] = None,
        clock: Optional[Callable[[], datetime.datetime]] = None,
    ) -> None:
        if not callable(url_builder):
            raise TypeError("url_builder must be callable")
        if id_factory is not None and not callable(id_factory):
            raise TypeError("id_factory must be callable")
        if clock is not None and not callable(clock):
            raise TypeError("clock must be callable")

        self._url_builder = url_builder
        self._id_factory: Callable[[], str] = (
            id_factory if id_factory is not None else lambda: uuid.uuid4().hex
        )
        self._clock: Callable[[], datetime.datetime] = clock if clock is not None else _utc_now
        self._instances: Dict[str, Instance] = {}

    # -- reads -------------------------------------------------------------------------

    def get(self, instance_id: str) -> Instance:
        """Return the instance registered under *instance_id*.

        Raises:
            KeyError: if no such instance is registered.
        """
        return self._instances[instance_id]

    def list(self, state: Optional[InstanceState] = None) -> List[Instance]:
        """Return instances in creation order, optionally filtered by *state*."""
        instances = list(self._instances.values())
        if state is not None:
            instances = [instance for instance in instances if instance.state == state]
        return instances

    # -- writes ------------------------------------------------------------------------

    def create(self, spec: InstanceSpec) -> Instance:
        """Register a new ``PENDING`` instance, validating *spec* first."""
        instance = new_instance(spec, id=self._id_factory(), now=self._clock())
        instance = replace(instance, url=self._url_builder(spec))
        self._instances[instance.id] = instance
        return instance

    def transition(
        self,
        instance_id: str,
        to: InstanceState,
        *,
        error: Optional[str] = None,
    ) -> Instance:
        """Move an instance to *to*, rejecting illegal transitions.

        Raises:
            KeyError: if no such instance is registered.
            InvalidTransition: if the transition is not permitted from the current state.
        """
        current = self.get(instance_id)

        try:
            target = to if isinstance(to, InstanceState) else InstanceState(to)
        except ValueError as exc:
            raise InvalidTransition(f"{to!r} is not a valid instance state") from exc

        if not can_transition(current.state, target):
            raise InvalidTransition(
                f"cannot transition instance {instance_id!r} from "
                f"{current.state.value} to {target.value}"
            )

        updated = replace(current, state=target, error=error, updated_at=self._clock())
        self._instances[instance_id] = updated
        return updated

    def launch(self, instance_id: str) -> Instance:
        """Move an instance to ``LAUNCHING``."""
        return self.transition(instance_id, InstanceState.LAUNCHING)

    def stop(self, instance_id: str) -> Instance:
        """Move an instance to ``STOPPING``."""
        return self.transition(instance_id, InstanceState.STOPPING)

    def remove(self, instance_id: str) -> None:
        """Deregister an instance.

        Raises:
            KeyError: if no such instance is registered.
        """
        self.get(instance_id)
        del self._instances[instance_id]
