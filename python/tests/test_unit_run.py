"""edt_unit_tests: validation of the call and the hand-off to the YAXUnit run."""
from __future__ import annotations

import pytest

from edt_bridge_mcp import form_run, server, unit_run


@pytest.fixture
def setup(tmp_path):
    infobase = tmp_path / "test-ib"
    infobase.mkdir()
    (infobase / "1Cv8.1CD").write_bytes(b"original")
    tests = tmp_path / "tests.cfe"
    tests.write_bytes(b"cfe")
    environ = {form_run.ENV_INFOBASE: str(infobase), form_run.ENV_PLATFORM: str(tmp_path / "bin"),
               form_run.ENV_USER: "Тестировщик"}
    return infobase, tests, environ


def call(arguments, environ, seen=None):
    def runner(args):
        if seen is not None:
            seen.append(args)
        return {"ok": True, "status": "passed", "testInfobase": str(args.test_infobase),
                "screenshot": str(args.output_dir / "screenshot.png"), "summary": {"tests": 1}}
    return unit_run.run_tool(arguments, runner=runner, environ=environ)


def test_an_unconfigured_host_is_refused(setup):
    _, tests, _ = setup
    report = call({"extensions": [{"path": str(tests), "name": "tests"}]}, {})
    assert report["status"] == "not_configured"


def test_the_call_reaches_the_run_as_the_command_would_get_it(setup):
    infobase, tests, environ = setup
    seen = []
    report = call({"extensions": [{"path": str(tests), "name": "tests"}], "modules": ["ОМ_Заказы"],
                   "tests": ["ОМ_Заказы.Сумма"], "timeoutSeconds": 120}, environ, seen)

    args = seen[0]
    assert args.extension == [f"tests={tests}"]
    assert args.module == ["ОМ_Заказы"] and args.test == ["ОМ_Заказы.Сумма"]
    assert args.test_infobase == infobase and args.user == "Тестировщик" and args.timeout == 120
    # The paths into the run's own directory are gone with it.
    assert report == {"ok": True, "status": "passed", "summary": {"tests": 1}}


@pytest.mark.parametrize("arguments", [
    {},
    {"extensions": []},
    {"extensions": [{"path": "missing.cfe", "name": "tests"}]},
    {"extensions": [{"name": "tests"}]},
    {"extensions": ["tests.cfe"]},
    {"extensions": "TESTS", "modules": "ОМ_Заказы"},
    {"extensions": "TESTS", "tests": [1]},
    {"extensions": "TESTS", "timeoutSeconds": 30},
])
def test_bad_calls_are_refused_before_any_run(setup, arguments):
    _, tests, environ = setup
    if arguments.get("extensions") == "TESTS":
        arguments = {**arguments, "extensions": [{"path": str(tests), "name": "tests"}]}
    seen = []
    assert call(arguments, environ, seen)["status"] == "invalid_input"
    assert seen == []


def test_the_wrapper_lists_the_tool_for_the_extension_jobs():
    assert "edt_unit_tests" in server.LOCAL_TOOL_NAMES
    by_name = {tool["name"]: tool["toolsets"] for tool in server.LOCAL_TOOLS}
    assert by_name["edt_unit_tests"] == ["extension"]
