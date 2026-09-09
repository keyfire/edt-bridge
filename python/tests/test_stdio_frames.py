"""Regression tests for the stdio frame encoding.

0.3.0 shipped broken on Windows and nobody noticed for a release: `_send` wrote
`json.dumps(..., ensure_ascii=False)` to a stdout the OS had opened in the ANSI code page, so a
single character that page cannot represent (the "->" arrow that lived in a tool description under
cp1251) aborted the WHOLE frame. `tools/list` then failed with a charmap codec error and the client
registered NO tools - while the handshake still looked healthy, so the failure was only visible in
the MCP log. `force_utf8_streams()` is the fix; these tests keep it honest.
"""
import io
import json
import sys
import threading

from edt_bridge_mcp.server import Backend, StdioServer, force_utf8_streams


def _ansi_stdout():
    """A stdout like Windows hands out: a text wrapper pinned to a single-byte code page."""
    return io.TextIOWrapper(io.BytesIO(), encoding="cp1251", newline="")


def test_force_utf8_streams_repins_an_ansi_stdout(monkeypatch):
    stream = _ansi_stdout()
    monkeypatch.setattr(sys, "stdout", stream)
    assert stream.encoding.lower() == "cp1251"

    force_utf8_streams()

    assert sys.stdout.encoding.lower() == "utf-8"


def test_force_utf8_streams_tolerates_a_stream_without_reconfigure(monkeypatch):
    """An embedded host (or a test harness) may hand over a plain object - must not blow up."""
    monkeypatch.setattr(sys, "stdout", io.StringIO())
    force_utf8_streams()


def test_frame_with_non_ansi_characters_survives(monkeypatch):
    """The exact 0.3.0 failure: an arrow and Cyrillic in a frame written to a cp1251 stdout."""
    stream = _ansi_stdout()
    monkeypatch.setattr(sys, "stdout", stream)
    force_utf8_streams()
    message = {
        "jsonrpc": "2.0",
        "id": 1,
        "result": {"description": "имя -> тип → значение", "text": "Ответ"},
    }

    StdioServer(Backend())._send(message)  # must not raise UnicodeEncodeError

    sys.stdout.flush()
    written = stream.buffer.getvalue().decode("utf-8")
    assert json.loads(written) == message


def test_frame_is_one_newline_terminated_line(monkeypatch):
    """The protocol is newline-delimited JSON: one frame, one line, nothing split across lines."""
    stream = _ansi_stdout()
    monkeypatch.setattr(sys, "stdout", stream)
    force_utf8_streams()

    StdioServer(Backend())._send({"jsonrpc": "2.0", "id": 7, "result": {"text": "две\nстроки"}})

    sys.stdout.flush()
    written = stream.buffer.getvalue().decode("utf-8")
    assert written.endswith("\n")
    assert written.count("\n") == 1, "an embedded newline must stay escaped inside the JSON string"


def test_characters_are_sent_as_themselves(monkeypatch):
    """ensure_ascii=False is deliberate - frames carry real characters, not \\uXXXX escapes."""
    stream = _ansi_stdout()
    monkeypatch.setattr(sys, "stdout", stream)
    force_utf8_streams()

    StdioServer(Backend())._send({"result": {"text": "Ответ"}})

    sys.stdout.flush()
    assert "Ответ" in stream.buffer.getvalue().decode("utf-8")


def test_a_long_call_does_not_hold_the_ones_behind_it(monkeypatch):
    """The reading loop used to run each request to the end before reading the next line.

    edt_project_errors refreshes a project, builds it and waits for validation to settle -
    minutes on a large configuration - and everything the client sent meanwhile sat unread on
    stdin until it returned, timing out, while the bridge itself answered other calls in under a
    second. Here the slow request only answers once the one behind it has been answered, so the
    ORDER of the two frames is the evidence: served one after another, the slow one comes first.
    """
    stream = _ansi_stdout()
    monkeypatch.setattr(sys, "stdout", stream)
    force_utf8_streams()
    server = StdioServer(Backend())

    second_answered = threading.Event()
    slow_done = threading.Event()

    def handle(message):
        if message["id"] == 1:
            second_answered.wait(5)  # a serial loop waits this out and answers anyway
            server._send({"jsonrpc": "2.0", "id": 1, "result": {}})
            slow_done.set()
        else:
            server._send({"jsonrpc": "2.0", "id": 2, "result": {}})
            second_answered.set()

    monkeypatch.setattr(server, "handle", handle)
    monkeypatch.setattr(sys, "stdin", io.StringIO(
        '{"jsonrpc": "2.0", "id": 1, "method": "tools/call"}\n'
        '{"jsonrpc": "2.0", "id": 2, "method": "ping"}\n'
    ))

    server.run()
    assert slow_done.wait(10), "the slow request never answered"
    sys.stdout.flush()
    written = stream.buffer.getvalue().decode("utf-8")
    assert '"id": 2' in written and '"id": 1' in written
    assert written.index('"id": 2') < written.index('"id": 1'), (
        "the request behind the slow one waited for it"
    )
