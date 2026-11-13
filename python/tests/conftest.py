"""Settings every test of the wrapper runs with."""
import pytest

from edt_bridge_mcp import native_cache, pii


@pytest.fixture(autouse=True)
def _no_manager_cache(monkeypatch):
    # A form check keeps its manager infobase in the user's cache directory; a test that does not ask for
    # the cache must neither read the developer's entries nor leave its own behind.
    monkeypatch.setenv(native_cache.ENV_CACHE, "off")
    # The personal data filter of the developer's shell would change every answer a test compares.
    for name in (pii.ENV_MODE, pii.ENV_SALT, pii.ENV_RULES):
        monkeypatch.delenv(name, raising=False)
