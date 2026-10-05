"""Finding the bridge must not stall the client.

With no bridge up, a scan of the default range probes 21 ports. On Windows a refused connection to a
closed loopback port is reported only after about two seconds, so probing port after port took
~42 s, and tools/list - which scanned twice - answered after ~85 s: the MCP client gave up on the
server at startup. These tests hold the three parts of the fix: a probe gives up on a closed port
quickly, the rest of the range is probed at once, and tools/list does not wait for the background
start.
"""
import socket
import threading
import time

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


def _closed_port() -> int:
    """A loopback port nothing listens on: bound once to learn a free number, then released."""
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


# -- one port -----------------------------------------------------------------


def test_a_closed_port_is_given_up_quickly():
    port = _closed_port()
    started = time.monotonic()
    assert server.Backend()._listening(port) is False
    assert time.monotonic() - started < 1.5, "a refusal must not cost the two seconds of Windows"


def test_a_listening_port_is_seen():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        assert server.Backend()._listening(listener.getsockname()[1]) is True


def test_a_port_nobody_listens_on_is_not_asked_over_http(monkeypatch):
    backend = server.Backend()
    monkeypatch.setattr(backend, "_listening", lambda _port: False)

    def _urlopen(*_args, **_kwargs):
        raise AssertionError("urlopen waits out the refusal - the probe must stop before it")

    monkeypatch.setattr(server.urllib.request, "urlopen", _urlopen)
    assert backend._status_on(8770) is None


# -- the range ----------------------------------------------------------------


def test_the_rest_of_the_range_is_probed_at_once(monkeypatch):
    """Every probe past the configured port waits on one barrier. Probed one after another, the
    first of them would wait alone, and the barrier would break."""
    backend = server.Backend()
    barrier = threading.Barrier(backend.scan_range, timeout=10)

    def _probe(port):
        if port != backend.port:
            barrier.wait()
        return None

    monkeypatch.setattr(backend, "_status_on", _probe)
    assert backend.status() is None


def test_of_several_answering_ports_the_lowest_wins(monkeypatch):
    """The upward scan stopped at the first port that answered; probed at once, the lowest of
    those that answered is the same port."""
    backend = server.Backend()
    answers = {8773: {"openProjects": ["A"]}, 8775: {"openProjects": ["B"]}}
    monkeypatch.setattr(backend, "_status_on", answers.get)

    assert backend.status() == {"openProjects": ["A"]}
    assert backend._active_port == 8773


def test_a_scan_range_of_zero_probes_the_configured_port_alone(monkeypatch):
    monkeypatch.setenv("EDT_BRIDGE_PORT_SCAN", "0")
    backend = server.Backend()
    probed = []

    def _probe(port):
        probed.append(port)

    monkeypatch.setattr(backend, "_status_on", _probe)
    assert backend.status() is None
    assert probed == [8770]


# -- tools/list ---------------------------------------------------------------


class _Recorder(server.StdioServer):
    """A stdio server that collects frames and says when a result has gone out."""

    def __init__(self, backend):
        super().__init__(backend)
        self.sent = []
        self.answered = threading.Event()

    def _send(self, message):
        self.sent.append(message)
        if "result" in message:
            self.answered.set()


def test_tools_list_does_not_wait_for_the_background_start(monkeypatch):
    """ensure() probes the ports once more and may launch a headless EDT. The listing the client
    waits for needs neither, so it has to be out while the start is still running."""
    backend = server.Backend()
    monkeypatch.setattr(backend, "is_up", lambda: False)
    stdio = _Recorder(backend)
    seen = {}
    started = threading.Event()

    def _start():
        seen["answered first"] = stdio.answered.wait(5)
        started.set()

    monkeypatch.setattr(stdio, "_kick_background_start", _start)
    stdio.handle({"jsonrpc": "2.0", "id": 5, "method": "tools/list", "params": {}})

    assert started.wait(10), "the background start must still be kicked off"
    assert seen["answered first"] is True
    assert stdio.sent[0]["id"] == 5
