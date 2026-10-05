"""Finding the bridge must not stall the client.

With no bridge up, a scan of the default range probes 21 ports. On Windows a refused connection to a
closed loopback port is reported only after about two seconds, so probing port after port took
~42 s, and tools/list - which scanned twice - answered after ~85 s: the MCP client gave up on the
server at startup. These tests hold the three parts of the fix: a probe gives up on a closed port
quickly, the rest of the range is probed at once, and tools/list does not wait for the background
start.
"""
import http.client
import os
import socket
import subprocess
import sys
import textwrap
import threading
import time
from pathlib import Path

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


def test_a_found_bridge_does_not_wait_for_a_higher_port(monkeypatch):
    """An unrelated higher port must not hold a bridge already found below it."""
    backend = server.Backend()
    backend.scan_range = 2
    higher_started = threading.Event()
    release_higher = threading.Event()
    answered = threading.Event()
    seen = {}

    def _probe(port):
        if port == backend.port + 1:
            assert higher_started.wait(5)
            return {"openProjects": ["APP"]}
        if port == backend.port + 2:
            higher_started.set()
            release_higher.wait(10)
        return None

    def _lookup():
        try:
            seen["status"] = backend.status()
        except Exception as exc:
            seen["error"] = exc
        finally:
            answered.set()

    monkeypatch.setattr(backend, "_status_on", _probe)
    worker = threading.Thread(target=_lookup, daemon=True)
    worker.start()
    try:
        assert higher_started.wait(5)
        assert answered.wait(5), "the higher port held an already found bridge"
        assert seen == {"status": {"openProjects": ["APP"]}}
        assert backend._active_port == backend.port + 1
    finally:
        release_higher.set()
        worker.join(5)


def test_a_higher_answer_cannot_overtake_a_pending_lower_port(monkeypatch):
    """A fast higher answer must wait for lower ports to preserve the upward scan."""
    backend = server.Backend()
    backend.scan_range = 2
    lower_started = threading.Event()
    higher_answered = threading.Event()
    release_lower = threading.Event()
    answered = threading.Event()
    seen = {}

    def _probe(port):
        if port == backend.port + 1:
            lower_started.set()
            release_lower.wait(10)
            return {"openProjects": ["LOWER"]}
        if port == backend.port + 2:
            higher_answered.set()
            return {"openProjects": ["HIGHER"]}
        return None

    def _lookup():
        seen["status"] = backend.status()
        answered.set()

    monkeypatch.setattr(backend, "_status_on", _probe)
    worker = threading.Thread(target=_lookup, daemon=True)
    worker.start()
    try:
        assert lower_started.wait(5)
        assert higher_answered.wait(5)
        assert not answered.wait(.1), "the higher bridge overtook a pending lower one"
        release_lower.set()
        assert answered.wait(5)
        assert seen == {"status": {"openProjects": ["LOWER"]}}
        assert backend._active_port == backend.port + 1
    finally:
        release_lower.set()
        worker.join(5)


@pytest.mark.parametrize("error", [
    http.client.BadStatusLine("SSH-2.0-test"),
    http.client.IncompleteRead(b'{"openProjects":'),
])
def test_an_invalid_http_response_is_a_failed_probe(monkeypatch, error):
    """An open TCP port may serve a different protocol or an incomplete HTTP body."""
    backend = server.Backend()
    monkeypatch.setattr(backend, "_listening", lambda _port: True)

    def _urlopen(*_args, **_kwargs):
        raise error

    monkeypatch.setattr(server.urllib.request, "urlopen", _urlopen)
    assert backend._status_on(8771) is None


def test_an_unused_probe_does_not_hold_the_cli_process():
    """Finding a bridge must let a short-lived CLI exit while a higher probe is running."""
    code = textwrap.dedent("""
        import threading
        import time
        from edt_bridge_mcp.server import Backend

        backend = Backend()
        backend.scan_range = 2
        higher_started = threading.Event()

        def probe(port):
            if port == backend.port + 1:
                assert higher_started.wait(5)
                return {"openProjects": ["APP"]}
            if port == backend.port + 2:
                higher_started.set()
                time.sleep(10)
            return None

        backend._status_on = probe
        assert backend.status() == {"openProjects": ["APP"]}
        print("found")
    """)
    env = dict(os.environ, PYTHONPATH=str(Path(server.__file__).parents[1]))
    result = subprocess.run(
        [sys.executable, "-c", code], env=env, stdin=subprocess.DEVNULL,
        capture_output=True, text=True, encoding="utf-8", timeout=3,
    )
    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == "found"
