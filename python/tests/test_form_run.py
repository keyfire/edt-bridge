"""edt_test_form: validation, the infobase copy and the hand-off to the native check."""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from edt_bridge_mcp import form_run, server


@pytest.fixture
def setup(tmp_path):
    infobase = tmp_path / "test-ib"
    infobase.mkdir()
    (infobase / "1Cv8.1CD").write_bytes(b"original")
    artifact = tmp_path / "Check.epf"
    artifact.write_bytes(b"epf")
    environ = {form_run.ENV_INFOBASE: str(infobase), form_run.ENV_PLATFORM: str(tmp_path / "bin")}
    return infobase, artifact, environ


def call(arguments, environ, seen=None):
    def runner(args):
        if seen is not None:
            seen.append(args)
            # The run works on a copy: changing it must not reach the configured infobase.
            (args.test_infobase / "1Cv8.1CD").write_bytes(b"changed by the run")
            if args.scenario:
                seen.append(json.loads(Path(args.scenario).read_text(encoding="utf-8")))
        return {"ok": True, "status": "scenario_completed", "testInfobase": str(args.test_infobase)}
    return form_run.run_tool(arguments, runner=runner, environ=environ)


def test_unconfigured_host_is_refused(setup):
    _, artifact, _ = setup
    report = call({"path": str(artifact), "form": "F", "formTitle": "T", "button": "B"}, {})
    assert report["status"] == "not_configured"


def test_button_run_uses_a_copy_and_leaves_the_infobase_alone(setup):
    infobase, artifact, environ = setup
    seen = []
    report = call({"path": str(artifact), "form": "ExternalDataProcessor.Check.Form.Form",
                   "formTitle": "Check", "button": "Run"}, environ, seen)
    assert report["ok"] is True
    assert "testInfobase" not in report
    args = seen[0]
    assert args.epf == artifact and args.erf is None and args.button == "Run"
    assert args.test_infobase != infobase
    assert (infobase / "1Cv8.1CD").read_bytes() == b"original"


def test_steps_become_a_scenario_file(setup):
    _, artifact, environ = setup
    steps = [{"action": "click", "button": "Run"},
             {"action": "assert_messages", "text": "Done", "count": 1}]
    seen = []
    call({"path": str(artifact), "form": "ExternalDataProcessor.Check.Form.Form",
          "formTitle": "Check", "steps": steps}, environ, seen)
    assert seen[1] == {"steps": steps}


@pytest.mark.parametrize("arguments, status", [
    ({"path": "missing.epf", "form": "F", "formTitle": "T", "button": "B"}, "invalid_input"),
    ({"form": "F", "formTitle": "T"}, "invalid_input"),
])
def test_bad_calls_are_refused_before_any_run(setup, arguments, status):
    _, artifact, environ = setup
    if arguments.get("path") is None and "button" not in arguments:
        arguments = {**arguments, "path": str(artifact)}  # neither button nor steps
    assert call(arguments, environ)["status"] == status


def test_the_wrapper_lists_the_tool():
    assert "edt_test_form" in server.LOCAL_TOOL_NAMES
