"""A busy bridge is not a stopped one.

Under several heavy calls a bridge can take longer than the probe allows to answer /status, while its
port still accepts connections. The wrapper used to report such a bridge as "not running", and a
stand with autostart off took that for a lost environment. These tests run a real /status endpoint
on the loopback that answers late, so the timeout the wrapper sees is a real one.
"""
import http.server
import json
import socket
import threading
import time

import pytest

from edt_bridge_mcp import scope, server

_ENV = (
    "EDT_BRIDGE_PORT", "EDT_BRIDGE_TOKEN", "EDT_BRIDGE_AUTOSTART", "EDT_BRIDGE_PORT_SCAN",
    "EDT_BRIDGE_WORKSPACE", "EDT_BRIDGE_EDT_DIR", "EDT_BRIDGE_START_TIMEOUT",
)


class _Bridge:
    """A /status endpoint whose answer comes `delay` seconds late."""

    def __init__(self, workspace: str):
        self.delay = 0.0
        self.code = 200
        self.workspace = workspace
        self.released = threading.Event()
        owner = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802 - the name the base class calls
                if owner.delay:
                    owner.released.wait(owner.delay)
                body = json.dumps({"name": "edt-bridge", "version": "0.46.0",
                                   "workspace": owner.workspace, "openProjects": ["APP"]}).encode("utf-8")
                try:
                    self.send_response(owner.code)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                except OSError:
                    pass  # the wrapper stopped waiting and closed the connection

            def log_message(self, *_args):
                pass

        class Listener(http.server.ThreadingHTTPServer):
            request_queue_size = 5

        self.http = Listener(("127.0.0.1", 0), Handler)
        self.http.daemon_threads = True
        self.port = self.http.server_address[1]
        threading.Thread(target=self.http.serve_forever, daemon=True).start()

    def close(self):
        self.released.set()
        self.http.shutdown()
        self.http.server_close()


@pytest.fixture
def bridge(tmp_path, monkeypatch):
    for name in _ENV:
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setattr(server, "STATUS_TIMEOUT", 0.3)
    monkeypatch.setattr(server, "BUSY_STATUS_TIMEOUT", 2.0)
    fake = _Bridge(str(tmp_path))
    monkeypatch.setenv("EDT_BRIDGE_PORT", str(fake.port))
    monkeypatch.setenv("EDT_BRIDGE_PORT_SCAN", "0")
    monkeypatch.setenv("EDT_BRIDGE_AUTOSTART", "0")
    monkeypatch.setenv("EDT_BRIDGE_WORKSPACE", str(tmp_path))
    yield fake
    fake.close()


def test_a_bridge_heard_before_takes_the_call_while_it_is_slow_to_answer(bridge):
    backend = server.Backend()
    assert backend.ensure(wait=True) == (True, "up")

    bridge.delay = 30
    started = time.monotonic()
    ready, why = backend.ensure(wait=True)

    assert ready is True, why
    assert time.monotonic() - started < server.BUSY_STATUS_TIMEOUT, \
        "a bridge this wrapper talked to is not asked again: the call waits its turn on the bridge"
    assert backend.is_up() is True


def _refusing(backend, monkeypatch, refused):
    """Make the first `refused` connection probes fail, as a full queue of new connections does."""
    real = backend._listening
    calls = []
    lock = threading.Lock()

    def listening(port, timeout=server.PROBE_CONNECT_TIMEOUT):
        with lock:
            calls.append(port)
            failing = len(calls) <= refused
        return False if failing else real(port, timeout)

    monkeypatch.setattr(backend, "_listening", listening)
    return calls


def test_a_refused_connection_to_a_bridge_heard_before_is_tried_again(bridge, monkeypatch):
    backend = server.Backend()
    assert backend.ensure(wait=True)[0] is True
    bridge.delay = 30
    calls = _refusing(backend, monkeypatch, refused=3)

    ready, why = backend.ensure(wait=True)

    assert ready is True, why
    assert len(calls) > 3


def test_a_burst_of_calls_keeps_a_busy_bridge(bridge, monkeypatch):
    """Sixteen calls at once against a busy bridge whose port drops every other connection."""
    # Keep the production retry window here. With a full accept queue on Linux,
    # a real connect can time out, using up a shortened window in one attempt.
    backend = server.Backend()
    assert backend.ensure(wait=True)[0] is True
    bridge.delay = 30
    real = backend._listening
    count = []

    def flaky(port, timeout=server.PROBE_CONNECT_TIMEOUT):
        count.append(port)
        return len(count) % 2 == 0 and real(port, timeout)

    monkeypatch.setattr(backend, "_listening", flaky)
    answers = []
    workers = [threading.Thread(target=lambda: answers.append(backend.ensure(wait=True))) for _ in range(16)]
    [worker.start() for worker in workers]
    [worker.join() for worker in workers]

    assert [ready for ready, _ in answers] == [True] * 16, [why for ready, why in answers if not ready]


def test_an_error_answer_is_not_taken_for_a_busy_bridge(bridge):
    backend = server.Backend()
    assert backend.ensure(wait=True)[0] is True
    bridge.code = 502  # a proxy in front of a bridge that is gone

    ready, why = backend.ensure(wait=True)

    assert ready is False
    assert why.startswith("bridge is not running and autostart is disabled")


def test_a_bridge_not_heard_before_gets_longer_to_answer(bridge):
    bridge.delay = 0.8  # longer than STATUS_TIMEOUT, within BUSY_STATUS_TIMEOUT
    backend = server.Backend()
    assert backend.ensure(wait=True)[0] is True


def test_a_silent_port_never_heard_from_is_not_called_a_stopped_bridge(bridge, monkeypatch):
    monkeypatch.setattr(server, "BUSY_STATUS_TIMEOUT", 0.6)
    bridge.delay = 30
    backend = server.Backend()

    ready, why = backend.ensure(wait=True)

    assert ready is False
    assert "not running" not in why
    assert f"port {bridge.port} accepts connections" in why
    with pytest.raises(RuntimeError, match="accepts connections"):
        backend.call_tool("edt_projects", {})


def test_a_closed_port_is_a_stopped_bridge(bridge, monkeypatch):
    monkeypatch.setattr(server, "CONFIRMED_CONNECT_SECONDS", 1.0)
    backend = server.Backend()
    assert backend.ensure(wait=True)[0] is True
    bridge.close()

    ready, why = backend.ensure(wait=True)

    assert ready is False
    assert why.startswith("bridge is not running and autostart is disabled")


def test_a_free_port_is_a_stopped_bridge(bridge, monkeypatch):
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        free = probe.getsockname()[1]
    monkeypatch.setenv("EDT_BRIDGE_PORT", str(free))

    ready, why = server.Backend().ensure(wait=True)

    assert ready is False
    assert why.startswith("bridge is not running and autostart is disabled")


def test_a_bridge_of_another_workspace_is_not_taken_for_a_busy_one(bridge):
    backend = server.Backend()
    assert backend.ensure(wait=True)[0] is True
    bridge.workspace = bridge.workspace + "-other"

    assert backend.ensure(wait=True)[0] is False, \
        "a bridge that answers names its workspace: only silence counts as busy"


def test_a_busy_bridge_does_not_fail_the_scope_check(bridge, monkeypatch, tmp_path):
    """A missing /status is no evidence of another write scope: the last verdict stands."""
    monkeypatch.setenv(scope.WRITE_ENV, "Own")
    monkeypatch.setattr(server, "load_plugin_tools", lambda: ({}, None))
    backend = server.Backend()
    srv = server.StdioServer(backend)
    sent, forwarded = [], []
    monkeypatch.setattr(srv, "_send", sent.append)
    monkeypatch.setattr(srv, "_forward_or_error", lambda message, req_id: forwarded.append(message))
    assert backend.ensure(wait=True)[0] is True
    srv._scope_checked = (time.monotonic() - 3600, None)

    bridge.delay = 30
    call = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
            "params": {"name": "edt_metadata_details", "arguments": {"projectName": "Own", "fqn": "Catalog.X"}}}
    srv.handle(call)

    assert forwarded == [call], sent
