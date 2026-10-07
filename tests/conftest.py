"""Pytest configuration and deterministic fixtures for testing agents.core."""
import datetime
import pytest

@pytest.fixture
def mock_clock():
    """Provides an injectable deterministic clock advancing on demand."""
    current_time = datetime.datetime(2026, 9, 27, 12, 0, 0, tzinfo=datetime.timezone.utc)

    def _clock():
        nonlocal current_time
        t = current_time
        current_time += datetime.timedelta(seconds=1)
        return t

    return _clock

@pytest.fixture
def mock_id_factory():
    """Provides a deterministic sequential ID generator."""
    counter = 0

    def _id_factory():
        nonlocal counter
        counter += 1
        return f"inst-{counter:04d}"

    return _id_factory
