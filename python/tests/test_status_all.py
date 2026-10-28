"""Multi-instance probes preserve workspace identity and never start EDT."""
import contextlib
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import pytest
from edt_bridge_mcp import cli, server

@contextlib.contextmanager
def listener(payload):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            body = json.dumps(payload).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        def log_message(self, *args): pass
    http = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=http.serve_forever, daemon=True)
    thread.start()
    try: yield http.server_address[1]
    finally:
        http.shutdown()
        http.server_close()
        thread.join()

def test_all_includes_other_workspaces_and_project_readiness():
    first = {"name": "edt-bridge", "version": "0.28.0", "workspace": "/work/a",
             "projects": [{"name": "A", "dtProject": True, "modelReady": False}]}
    second = {"name": "edt-bridge", "version": "0.29.0", "workspace": "/work/b",
              "projects": [{"name": "B", "dtProject": True, "modelReady": True}]}
    with listener(first) as a, listener(second) as b:
        backend = server.Backend()
        backend.workspace = "/work/a"
        active = backend._active_port
        rows = backend.status_all([b, a, b])
        assert [r["port"] for r in rows] == sorted([a, b])
        by_workspace = {r["workspace"]: r for r in rows}
        assert by_workspace["/work/a"]["projects"][0]["modelReady"] is False
        assert by_workspace["/work/b"]["projects"][0]["modelReady"] is True
        assert all(r["state"] == "bridge" for r in rows)
        assert backend._active_port == active

def test_all_separates_foreign_http_and_unavailable_port():
    with listener({"name": "another-service", "version": "1"}) as foreign:
        with listener({}) as closed: pass
        states = {r["port"]: r["state"] for r in server.Backend().status_all([foreign, closed])}
        assert states == {foreign: "foreign_http", closed: "unavailable"}

def test_cli_all_never_ensures_or_starts_edt(monkeypatch, capsys):
    monkeypatch.setattr(server.Backend, "ensure", lambda *a, **k: pytest.fail("status must not start EDT"))
    payload = {"name": "edt-bridge", "version": "0.28.0", "workspace": "/work/a", "projects": []}
    with listener(payload) as port:
        assert cli.run("status", ["--all", "--ports", str(port)]) == 0
    result = json.loads(capsys.readouterr().out)
    assert result["bridges"][0]["workspace"] == "/work/a"
    assert result["bridges"][0]["port"] == port
    assert result["otherServices"] == []
    assert result["unavailable"] == []

@pytest.mark.parametrize("text", ["0", "65536", "x", "8771-8770", "1-1000"])
def test_ports_reject_invalid_or_excessive_scan(text):
    with pytest.raises(SystemExit): cli._parse("status", ["--all", "--ports", text])

def test_ports_require_all():
    assert cli.run("status", ["--ports", "8770"]) == 1


def test_old_bridge_keeps_unknown_model_readiness_explicit():
    payload = {"name": "edt-bridge", "version": "0.27.0", "workspace": "/work/old", "openProjects": ["Demo"]}
    with listener(payload) as port:
        row = server.Backend().status_all([port])[0]
    assert row["projects"] == [{"name": "Demo", "modelReady": None}]
    assert row["edtVersion"] is None
    assert row["bridgeBundleVersion"] is None


def test_non_http_listener_is_not_a_bridge():
    import socketserver
    class Handler(socketserver.BaseRequestHandler):
        def handle(self):
            # Closing with unread request bytes can produce a Windows TCP reset instead of the bad status line.
            self.request.settimeout(3)
            request = bytearray()
            while b"\r\n\r\n" not in request:
                chunk = self.request.recv(4096)
                if not chunk or len(request) + len(chunk) > 16384:
                    return
                request.extend(chunk)
            self.request.sendall(b"not HTTP\r\n")
    tcp = socketserver.ThreadingTCPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=tcp.serve_forever, daemon=True)
    thread.start()
    try:
        row = server.Backend().status_all([tcp.server_address[1]])[0]
        assert row["state"] == "non_http"
    finally:
        tcp.shutdown()
        tcp.server_close()
        thread.join()


def test_ports_accept_ranges_and_deduplicate():
    args = cli._parse("status", ["--all", "--ports", "8772-8774,8770,8773"])
    assert args.ports == [8770, 8772, 8773, 8774]
