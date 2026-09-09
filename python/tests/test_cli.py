"""CLI argument handling and the pipe contract.

Two things here have bitten before: arguments with non-ASCII text (which is why --json-file exists
and is documented as the reliable route on Windows), and piping a big result into `head`, which used
to end in a BrokenPipeError traceback instead of a clean stop.
"""
import io
import json
import sys

import pytest

from edt_bridge_mcp import cli, server


def _call(argv):
    return cli._parse("call", argv)


# -- arguments ----------------------------------------------------------------


def test_json_arguments_are_parsed():
    args = _call(["edt_projects", "--json", '{"projectName": "APP"}'])
    assert cli._tool_arguments(args) == {"projectName": "APP"}


def test_absent_arguments_default_to_an_empty_object():
    assert cli._tool_arguments(_call(["edt_projects"])) == {}


def test_blank_arguments_default_to_an_empty_object():
    assert cli._tool_arguments(_call(["edt_projects", "--json", "   "])) == {}


def test_invalid_json_is_reported_as_invalid_json():
    with pytest.raises(ValueError, match="not valid JSON"):
        cli._tool_arguments(_call(["edt_projects", "--json", "{oops"]))


def test_a_json_array_is_refused():
    """The MCP arguments slot is an object; an array would fail confusingly further down."""
    with pytest.raises(ValueError, match="must be a JSON object"):
        cli._tool_arguments(_call(["edt_projects", "--json", "[1, 2]"]))


def test_json_file_is_read_as_utf8(tmp_path):
    """--json-file is the documented route for non-ASCII arguments on Windows."""
    path = tmp_path / "args.json"
    path.write_text(json.dumps({"fqn": "Справочник.Товары"}, ensure_ascii=False), encoding="utf-8")

    assert cli._tool_arguments(_call(["edt_add_route", "--json-file", str(path)])) == {
        "fqn": "Справочник.Товары"
    }


def test_json_file_written_by_a_windows_shell_is_read(tmp_path):
    """PowerShell writes a byte order mark by default, and the file is otherwise valid JSON -
    refusing it sends the person hunting for an encoding flag instead of calling the tool."""
    path = tmp_path / "args.json"
    path.write_text(json.dumps({"fqn": "Справочник.Товары"}, ensure_ascii=False),
                    encoding="utf-8-sig")

    assert cli._tool_arguments(_call(["edt_add_route", "--json-file", str(path)])) == {
        "fqn": "Справочник.Товары"
    }


def test_stdin_arguments(monkeypatch):
    monkeypatch.setattr(sys, "stdin", io.StringIO('{"projectName": "APP"}'))
    assert cli._tool_arguments(_call(["edt_projects", "--stdin"])) == {"projectName": "APP"}


def test_argument_sources_are_mutually_exclusive():
    with pytest.raises(SystemExit):
        _call(["edt_projects", "--json", "{}", "--stdin"])


def test_every_command_parses_the_connection_flags():
    for command in cli.COMMANDS:
        argv = ["edt_projects"] if command == "call" else []
        args = cli._parse(command, argv + ["--port", "8899", "--no-autostart"])
        assert args.port == 8899
        assert args.no_autostart is True


# -- the jar on disk next to the running bridge --------------------------------


def test_status_names_the_jar_that_would_load_next(monkeypatch, tmp_path):
    """A headless session started before the jar was replaced keeps answering the old version."""
    from edt_bridge_mcp import update

    jar = tmp_path / (update.JAR_PREFIX + "0.24.0.202609090627.jar")
    jar.write_bytes(b"")
    monkeypatch.setattr(update, "_find_dropins", lambda: tmp_path)

    same = cli._with_jar_on_disk({"name": "edt-bridge", "version": "0.24.0"})
    assert same["jarOnDisk"]["version"] == "0.24.0.202609090627"
    assert same["jarOnDisk"]["current"] is True

    behind = cli._with_jar_on_disk({"name": "edt-bridge", "version": "0.23.0"})
    assert behind["jarOnDisk"]["current"] is False  # the sign to restart EDT


def test_status_says_nothing_about_a_jar_it_cannot_find(monkeypatch, tmp_path):
    """No dropins (EDT installed elsewhere, a foreign machine) is not a finding to report."""
    from edt_bridge_mcp import update

    monkeypatch.setattr(update, "_find_dropins", lambda: tmp_path)
    assert cli._with_jar_on_disk({"version": "0.24.0"}) == {"version": "0.24.0"}


# -- where the command may stand ----------------------------------------------


def test_the_shared_options_are_accepted_before_the_command():
    """The help promises the connection options belong to every command; order is not a rule.

    `edt-bridge-mcp --port 8770 status` used to answer "unrecognized arguments: status" - the
    command was looked for in the first position only.
    """
    assert server.split_command(["--port", "8770", "status"]) == ("status", ["--port", "8770"])
    assert server.split_command(["--port=8770", "status"]) == ("status", ["--port=8770"])
    assert server.split_command(["--no-autostart", "tools"]) == ("tools", ["--no-autostart"])


def test_the_command_still_takes_its_own_arguments_after_it():
    assert server.split_command(["call", "edt_projects", "--raw"]) == (
        "call", ["edt_projects", "--raw"])
    assert server.split_command(["--port", "1", "self-update"]) == ("self-update", ["--port", "1"])


def test_a_port_number_is_not_mistaken_for_a_command():
    """Every command name would do as a value; the option is skipped with its value, not scanned."""
    assert server.split_command(["--workspace", "status"]) == (None, ["--workspace", "status"])


def test_no_command_leaves_the_server_parser_to_speak():
    assert server.split_command([]) == (None, [])
    assert server.split_command(["--version"]) == (None, ["--version"])
    assert server.split_command(["stat"]) == (None, ["stat"])  # a typo, not a command


# -- output and the pipe contract ---------------------------------------------


class _ClosedPipe(io.StringIO):
    def write(self, *_args):
        raise BrokenPipeError


def test_emit_turns_a_closed_pipe_into_a_quiet_stop(monkeypatch):
    monkeypatch.setattr(sys, "stdout", _ClosedPipe())
    with pytest.raises(cli._PipeClosed):
        cli._emit("anything")


def test_run_exits_zero_when_the_consumer_stopped_reading(monkeypatch):
    """`edt-bridge-mcp tools | head` is normal use - it must not look like a failure."""
    monkeypatch.setattr(sys, "stdout", io.StringIO())

    def _boom(*_args):
        raise cli._PipeClosed

    monkeypatch.setattr(cli, "_run", _boom)
    assert cli.run("tools", []) == 0


def test_print_result_prints_the_text_content(monkeypatch):
    out = io.StringIO()
    monkeypatch.setattr(sys, "stdout", out)

    cli._print_result({"content": [{"type": "text", "text": "первый"},
                                   {"type": "text", "text": "второй"}]}, raw=False)

    assert out.getvalue().splitlines() == ["первый", "второй"]


def test_print_result_raw_keeps_the_whole_envelope(monkeypatch):
    out = io.StringIO()
    monkeypatch.setattr(sys, "stdout", out)
    result = {"content": [{"type": "text", "text": "Ответ"}], "isError": True}

    cli._print_result(result, raw=True)

    assert json.loads(out.getvalue()) == result


def test_non_text_content_is_skipped(monkeypatch):
    out = io.StringIO()
    monkeypatch.setattr(sys, "stdout", out)

    cli._print_result({"content": [{"type": "image", "data": "..."}]}, raw=False)

    assert out.getvalue() == ""
