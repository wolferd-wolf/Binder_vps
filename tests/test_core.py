"""Comprehensive contract test suite for agents.core adhering to docs/spec.md."""
import datetime
import pytest

# Note: agents.core will be provided by @Cline
try:
    from agents.core import (
        RepoRef,
        InstanceState,
        InstanceSpec,
        Instance,
        HealthStatus,
        InvalidTransition,
        SpecValidationError,
        ALLOWED_TRANSITIONS,
        can_transition,
        validate_spec,
        build_binder_url,
        new_instance,
        InstanceRegistry,
    )
except ImportError:
    # If Cline has not created agents/core.py yet, tests will be skipped cleanly
    RepoRef = None


@pytest.mark.skipif(RepoRef is None, reason="agents.core not yet implemented by @Cline")
class TestRepoRef:
    def test_parse_standard_gh(self):
        ref = RepoRef.parse("gh/jupyterhub/binderhub@v0.2.0")
        assert ref.host == "github.com"
        assert ref.owner == "jupyterhub"
        assert ref.repo == "binderhub"
        assert ref.ref == "v0.2.0"
        assert ref.binder_path() == "gh/jupyterhub/binderhub/v0.2.0"

    def test_parse_default_ref(self):
        ref = RepoRef.parse("owner/myrepo")
        assert ref.owner == "owner"
        assert ref.repo == "myrepo"
        assert ref.ref == "HEAD"
        assert ref.binder_path() == "gh/owner/myrepo/HEAD"

    def test_parse_rejects_path_traversal(self):
        with pytest.raises(SpecValidationError):
            RepoRef.parse("gh/../evil/repo@HEAD")

        with pytest.raises(SpecValidationError):
            RepoRef.parse("gh/owner/..@HEAD")

    def test_parse_rejects_empty(self):
        with pytest.raises(SpecValidationError):
            RepoRef.parse("")

    def test_direct_construction_rejects_traversal(self):
        with pytest.raises(SpecValidationError):
            RepoRef(owner="../../etc", repo="demo")

        with pytest.raises(SpecValidationError):
            RepoRef(owner="alice", repo="demo", ref="../secret")

        with pytest.raises(SpecValidationError):
            RepoRef(owner="alice", repo="demo", ref="refs/../heads")

    def test_direct_construction_rejects_control_chars(self):
        with pytest.raises(SpecValidationError):
            RepoRef(owner="alice\x00", repo="demo")

        with pytest.raises(SpecValidationError):
            RepoRef(owner="alice", repo="demo\n")

    def test_direct_construction_valid_branch(self):
        ref = RepoRef(owner="alice", repo="demo", ref="feature/v1.0")
        assert ref.binder_path() == "gh/alice/demo/feature/v1.0"

    def test_provider_mapping(self):
        gl_ref = RepoRef(host="gitlab.com", owner="alice", repo="demo")
        assert gl_ref.binder_path() == "gl/alice/demo/HEAD"

        bb_ref = RepoRef(host="bitbucket.org", owner="alice", repo="demo")
        assert bb_ref.binder_path() == "bb/alice/demo/HEAD"

    def test_unknown_host_rejected(self):
        with pytest.raises(SpecValidationError):
            RepoRef(host="unknown.example.org", owner="alice", repo="demo")


@pytest.mark.skipif(RepoRef is None, reason="agents.core not yet implemented by @Cline")
class TestStateTransitions:
    def test_valid_transitions(self):
        assert can_transition(InstanceState.PENDING, InstanceState.LAUNCHING)
        assert can_transition(InstanceState.LAUNCHING, InstanceState.READY)
        assert can_transition(InstanceState.READY, InstanceState.STOPPING)
        assert can_transition(InstanceState.STOPPING, InstanceState.STOPPED)
        assert can_transition(InstanceState.READY, InstanceState.DEGRADED)
        assert can_transition(InstanceState.DEGRADED, InstanceState.READY)
        assert can_transition(InstanceState.STOPPED, InstanceState.PENDING)
        assert can_transition(InstanceState.FAILED, InstanceState.PENDING)

    def test_invalid_transitions(self):
        assert not can_transition(InstanceState.PENDING, InstanceState.STOPPED)
        assert not can_transition(InstanceState.LAUNCHING, InstanceState.STOPPING)
        assert not can_transition(InstanceState.STOPPED, InstanceState.READY)


@pytest.mark.skipif(RepoRef is None, reason="agents.core not yet implemented by @Cline")
class TestSpecValidationAndUrl:
    def test_valid_spec(self):
        repo = RepoRef(owner="alice", repo="demo", ref="main")
        spec = InstanceSpec(repo=repo, port=8888, urlpath="lab")
        validate_spec(spec)
        url = build_binder_url(spec)
        assert url == "https://mybinder.org/v2/gh/alice/demo/main?urlpath=lab"

    def test_env_defaults_to_dict(self):
        repo = RepoRef(owner="alice", repo="demo")
        spec = InstanceSpec(repo=repo)
        assert isinstance(spec.env, dict)
        assert spec.env == {}

    def test_urlpath_percent_encoding(self):
        repo = RepoRef(owner="alice", repo="demo", ref="main")
        spec = InstanceSpec(repo=repo, urlpath="tree/my folder/notebook.ipynb?token=123")
        validate_spec(spec)
        url = build_binder_url(spec)
        assert url == "https://mybinder.org/v2/gh/alice/demo/main?urlpath=tree/my%20folder/notebook.ipynb%3Ftoken%3D123"

    def test_spec_invalid_port(self):
        repo = RepoRef(owner="alice", repo="demo")
        spec_low = InstanceSpec(repo=repo, port=0)
        with pytest.raises(SpecValidationError):
            validate_spec(spec_low)

        spec_high = InstanceSpec(repo=repo, port=70000)
        with pytest.raises(SpecValidationError):
            validate_spec(spec_high)

    def test_spec_rejects_control_characters(self):
        repo = RepoRef(owner="alice", repo="demo")
        with pytest.raises(SpecValidationError):
            validate_spec(InstanceSpec(repo=repo, urlpath="lab\x00bad"))

        with pytest.raises(SpecValidationError):
            validate_spec(InstanceSpec(repo=repo, env={"KEY\n": "val"}))

        with pytest.raises(SpecValidationError):
            validate_spec(InstanceSpec(repo=repo, env={"KEY": "val\x7f"}))

    def test_custom_base_url(self):
        repo = RepoRef(owner="alice", repo="demo", ref="main")
        spec = InstanceSpec(repo=repo)
        url = build_binder_url(spec, base="https://hub.custom-binder.org/")
        assert url == "https://hub.custom-binder.org/v2/gh/alice/demo/main"

    def test_length_caps(self):
        repo_oversized_owner = RepoRef(owner="a" * 101, repo="demo")
        with pytest.raises(SpecValidationError):
            validate_spec(InstanceSpec(repo=repo_oversized_owner))

        repo_valid = RepoRef(owner="alice", repo="demo")
        spec_oversized_urlpath = InstanceSpec(repo=repo_valid, urlpath="a" * 501)
        with pytest.raises(SpecValidationError):
            validate_spec(spec_oversized_urlpath)

    def test_build_binder_url_rejects_non_https_base(self):
        repo = RepoRef(owner="alice", repo="demo")
        spec = InstanceSpec(repo=repo)
        with pytest.raises(SpecValidationError):
            build_binder_url(spec, base="http://insecure-hub.org")

        with pytest.raises(SpecValidationError):
            build_binder_url(spec, base="javascript:alert(1)")


@pytest.mark.skipif(RepoRef is None, reason="agents.core not yet implemented by @Cline")
class TestInstanceRegistry:
    def test_create_and_lifecycle(self, mock_clock, mock_id_factory):
        registry = InstanceRegistry(clock=mock_clock, id_factory=mock_id_factory)
        repo = RepoRef(owner="alice", repo="demo", ref="main")
        spec = InstanceSpec(repo=repo)

        inst = registry.create(spec)
        assert inst.id == "inst-0001"
        assert inst.state == InstanceState.PENDING
        assert inst.url is not None

        # Launch
        inst_launch = registry.launch(inst.id)
        assert inst_launch.state == InstanceState.LAUNCHING

        # Mark ready
        inst_ready = registry.transition(inst.id, InstanceState.READY)
        assert inst_ready.state == InstanceState.READY

        # Stop
        inst_stopping = registry.stop(inst.id)
        assert inst_stopping.state == InstanceState.STOPPING

        inst_stopped = registry.transition(inst.id, InstanceState.STOPPED)
        assert inst_stopped.state == InstanceState.STOPPED

    def test_invalid_lifecycle_transition_raises(self, mock_clock, mock_id_factory):
        registry = InstanceRegistry(clock=mock_clock, id_factory=mock_id_factory)
        repo = RepoRef(owner="alice", repo="demo", ref="main")
        spec = InstanceSpec(repo=repo)

        inst = registry.create(spec)
        with pytest.raises(InvalidTransition):
            # Cannot jump directly from PENDING to STOPPED
            registry.transition(inst.id, InstanceState.STOPPED)

    def test_get_nonexistent_raises_keyerror(self, mock_clock, mock_id_factory):
        registry = InstanceRegistry(clock=mock_clock, id_factory=mock_id_factory)
        with pytest.raises(KeyError):
            registry.get("non-existent-id")
