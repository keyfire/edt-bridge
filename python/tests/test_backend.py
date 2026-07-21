"""The HTTP side of the wrapper: configuration, port discovery and request shape.

None of this touches the network - the probe and urlopen are substituted. What matters is that a
second EDT (which binds the next free port) is still found, that the port which answered is the one
used afterwards, and that the token really travels as a bearer header: without it every call against
a token-protected bridge comes back 401.
"""
import json

import pytest

from edt_bridge_mcp import server

_ENV = (
    "EDT_BRIDGE_PORT", "EDT_BRIDGE_TOKEN", "EDT_BRIDGE_AUTOSTART", "EDT_BRIDGE_PORT_SCAN",
    "EDT_BRIDGE_WORKSPACE", "EDT_BRIDGE_EDT_DIR", "EDT_BRIDGE_START_TIMEOUT",
)


@pytest.fixture(autouse=True)
def _clean_environment(monkeypatch):
    for name in _ENV:
        monkeypatch.delenv(name, raising=False)


def test_defaults():
    backend = server.Backend()
    assert backend.port == 8770
    assert backend.token is None
    assert backend.autostart is True


def test_environment_configures_the_backend(monkeypatch):
    monkeypatch.setenv("EDT_BRIDGE_PORT", "9001")
    monkeypatch.setenv("EDT_BRIDGE_TOKEN", "  secret  ")
    monkeypatch.setenv("EDT_BRIDGE_AUTOSTART", "0")

    backend = server.Backend()

    assert backend.port == 9001
    assert backend.token == "secret", "a token pasted with spaces must still work"
    assert backend.autostart is False


@pytest.mark.parametrize("value, expected", [("0", False), ("false", False), ("no", False),
                                             ("1", True), ("yes", True)])
def test_autostart_flag_spellings(monkeypatch, value, expected):
    monkeypatch.setenv("EDT_BRIDGE_AUTOSTART", value)
    assert server.Backend().autostart is expected


def test_status_scans_upward_and_pins_the_port_that_answered(monkeypatch):
    """A second EDT instance binds the next free port, so the wrapper scans past the default."""
    backend = server.Backend()
    probed = []

    def _probe(port):
        probed.append(port)
        return {"openProjects": ["APP"]} if port == 8773 else None

    monkeypatch.setattr(backend, "_status_on", _probe)

    assert backend.status() == {"openProjects": ["APP"]}
    assert backend._active_port == 8773, "the live port must be remembered"
    assert probed[0] == 8770, "the configured port is probed first"

    probed.clear()
    backend.status()
    assert probed == [8773], "afterwards the pinned port is probed on its own"


def test_status_is_none_when_nothing_answers(monkeypatch):
    backend = server.Backend()
    monkeypatch.setattr(backend, "_status_on", lambda _port: None)
    assert backend.status() is None
    assert backend.is_up() is False


def test_ready_requires_an_open_project(monkeypatch):
    """A bridge whose model holds no project is up but useless - tools would resolve nothing."""
    backend = server.Backend()
    monkeypatch.setattr(backend, "status", lambda: {"openProjects": []})
    assert backend.is_ready() is False

    monkeypatch.setattr(backend, "status", lambda: {"openProjects": ["APP"]})
    assert backend.is_ready() is True


class _Response:
    def __init__(self, payload):
        self._payload = json.dumps(payload).encode("utf-8")

    def read(self):
        return self._payload

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        return False


def test_forward_sends_the_token_as_a_bearer_header(monkeypatch):
    monkeypatch.setenv("EDT_BRIDGE_TOKEN", "t0ken")
    backend = server.Backend()
    seen = {}

    def _urlopen(request, timeout=None):
        seen["url"] = request.full_url
        seen["headers"] = dict(request.headers)
        seen["body"] = json.loads(request.data.decode("utf-8"))
        return _Response({"result": {"ok": True}})

    monkeypatch.setattr(server.urllib.request, "urlopen", _urlopen)

    answer = backend.forward({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})

    assert answer == {"result": {"ok": True}}
    assert seen["headers"]["Authorization"] == "Bearer t0ken"
    assert seen["url"].endswith("/mcp")
    assert seen["body"]["method"] == "tools/list"


def test_forward_without_a_token_sends_no_authorization(monkeypatch):
    backend = server.Backend()
    seen = {}

    def _urlopen(request, timeout=None):
        seen["headers"] = dict(request.headers)
        return _Response({"result": {}})

    monkeypatch.setattr(server.urllib.request, "urlopen", _urlopen)
    backend.forward({"jsonrpc": "2.0"})

    assert "Authorization" not in seen["headers"]
