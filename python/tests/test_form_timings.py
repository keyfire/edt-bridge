"""The seconds of each stage of a form check (EDT-TEST-14): where the time of a run went.

The stages follow each other on one clock, so they add up to the total by construction; the parts
inside the test manager are measured by its driver, which records the marks between them.
"""
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from edt_bridge_mcp import form_run, native_form

from test_native_form import _fixture_args, _manager_setup


class Clock:
    def __init__(self, now=100.0):
        self.now = now

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


@pytest.fixture
def clock(monkeypatch):
    fake = Clock()
    monkeypatch.setattr(native_form, "time", SimpleNamespace(monotonic=fake.monotonic, sleep=fake.sleep))
    return fake


def test_stages_follow_each_other_and_add_up_to_the_total(clock):
    timeline = native_form._Timeline()
    timeline.enter("setup")
    clock.sleep(1.5)
    timeline.enter("phases")
    clock.sleep(2)
    timeline.enter("setup")
    clock.sleep(0.25)

    total, stages = timeline.close()

    assert stages == {"setup": 1.75, "phases": 2.0}
    assert total == 3.75


def test_the_marks_of_the_driver_become_the_seconds_of_each_part():
    marks = [{"name": "connect", "at": 1000}, {"name": "formLookup", "at": 2500},
             {"name": "step", "step": 1, "action": "input", "at": 3000},
             {"name": "step", "step": 2, "action": "click", "at": 3200},
             {"name": "end", "at": 6000}]

    assert native_form._driver_timings(marks) == {
        "connect": 1.5, "formLookup": 0.5,
        "steps": [{"step": 1, "action": "input", "seconds": 0.2}, {"step": 2, "action": "click", "seconds": 2.8}],
        "total": 5.0}


def test_marks_without_an_end_time_only_what_ended():
    # The progress file of a manager that never finished: the running step has no end yet.
    marks = [{"name": "connect", "at": 1000}, {"name": "formLookup", "at": 1400},
             {"name": "step", "step": 1, "action": "click", "at": 2000}]

    assert native_form._driver_timings(marks) == {"connect": 0.4, "formLookup": 0.6, "total": 1.0}


@pytest.mark.parametrize("marks", [None, [], "connect", [{"name": "connect"}], [{"at": 1}, {"at": 2}]])
def test_marks_the_driver_did_not_leave_give_nothing(marks):
    assert native_form._driver_timings(marks) is None


def _session(monkeypatch, tmp_path, clock, manager):
    """A legacy button check whose client starts in 3 s and whose manager answers through `manager`."""
    args = _fixture_args(tmp_path)
    args.output_dir.mkdir()
    args.expect_message = "Done"
    monkeypatch.setattr(native_form, "_display", lambda *a: clock.sleep(1) or {})
    monkeypatch.setattr(native_form, "_owns_listener", lambda *a: True)

    def capture(*a):
        clock.sleep(0.5)
        return {"screenshot": "screenshot.png"}

    monkeypatch.setattr(native_form, "_capture", capture)

    def spawn(_stack, argv, _env, _log):
        if "/TestManager" in argv:
            request = json.loads(Path(next(arg[2:] for arg in argv if arg.startswith("/C"))).read_text(encoding="utf-8"))
            manager(request)
        else:
            clock.sleep(2)
        return SimpleNamespace(poll=lambda: None)

    monkeypatch.setattr(native_form, "_spawn", spawn)
    return native_form._run_session(args, args.test_infobase, args.epf, args.output_dir, tmp_path / "session",
                                    tmp_path / "manager", {}, ["1cv8", "ENTERPRISE"], "Xvfb", clock.now + 60, None)


def test_a_phase_says_where_its_time_went(monkeypatch, tmp_path, clock):
    def manager(request):
        clock.sleep(7)
        Path(request["result"]).write_text(json.dumps({
            "status": "command_completed", "stage": "after_observation", "clickAttempted": True,
            "clickReturned": True, "messages": ["Done"],
            "timingMarks": [{"name": "connect", "at": 1000}, {"name": "formLookup", "at": 2500},
                            {"name": "command", "at": 3000}, {"name": "end", "at": 6000}]}), encoding="utf-8")

    report = _session(monkeypatch, tmp_path, clock, manager)

    assert report["ok"] is True
    assert report["timings"] == {
        "seconds": 10.5, "clientStart": 3.0, "manager": 7.0, "screenshot": 0.5, "cleanup": 0.0,
        "managerStartup": 2.0,
        "driver": {"connect": 1.5, "formLookup": 0.5, "command": 3.0, "total": 5.0}}
    # The marks are the driver's working data, not part of what it observed.
    assert "timingMarks" not in report["interaction"]


def test_a_phase_that_times_out_keeps_what_the_driver_measured(monkeypatch, tmp_path, clock):
    def manager(request):
        Path(request["progress"]).write_text(json.dumps({
            "status": "scenario_error", "stage": "click", "step": 1,
            "timingMarks": [{"name": "connect", "at": 1000}, {"name": "formLookup", "at": 1400},
                            {"name": "step", "step": 1, "action": "click", "at": 2000}]}), encoding="utf-8")

    report = _session(monkeypatch, tmp_path, clock, manager)

    assert report["status"] == "timeout"
    timings = report["timings"]
    assert timings["driver"] == {"connect": 0.4, "formLookup": 0.6, "total": 1.0}
    assert timings["manager"] == pytest.approx(57.0)
    assert timings["seconds"] == pytest.approx(sum(value for key, value in timings.items()
                                                    if key in ("clientStart", "manager", "screenshot", "cleanup")))
    assert "timingMarks" not in report["lastInteraction"]


def test_a_run_reports_its_stages_and_phases_and_they_add_up(monkeypatch, tmp_path):
    monkeypatch.setattr(native_form.sys, "platform", "linux")
    args, _, _ = _manager_setup(monkeypatch, tmp_path, ["Unable to connect to the display", None])
    args.output_dir.rmdir()

    def session(*session_args):
        return {"ok": True, "status": "command_completed", "screenshot": "screenshot.png", "cleanupComplete": True,
                "timings": {"seconds": 4.0, "clientStart": 4.0}}

    monkeypatch.setattr(native_form, "_run_session", session)
    report = native_form.run(args)

    assert report["ok"] is True
    timings = report["timings"]
    assert set(timings["stages"]) == {"preparation", "managerDisplay", "managerInfobase", "targetMetadata",
                                      "phases", "cleanup", "eventLog"}
    assert sum(timings["stages"].values()) == pytest.approx(timings["totalSeconds"], abs=0.05)
    assert timings["phases"] == [{"name": "legacy", "seconds": 4.0, "clientStart": 4.0}]
    # The failed first preparation of the manager infobase carries its own time.
    assert isinstance(report["setupRetries"][0]["seconds"], float)
    assert json.loads((args.output_dir / "result.json").read_text(encoding="utf-8"))["timings"] == timings


def test_the_copy_of_the_test_infobase_is_a_stage_of_the_tool(tmp_path):
    infobase = tmp_path / "test-ib"
    infobase.mkdir()
    (infobase / "1Cv8.1CD").write_bytes(b"original")
    artifact = tmp_path / "Check.epf"
    artifact.write_bytes(b"epf")
    environ = {form_run.ENV_INFOBASE: str(infobase), form_run.ENV_PLATFORM: str(tmp_path / "bin")}

    def runner(args):
        return {"ok": True, "status": "command_completed",
                "timings": {"totalSeconds": 2.0, "stages": {"preparation": 2.0}, "phases": []}}

    report = form_run.run_tool({"path": str(artifact), "form": "ExternalDataProcessor.Check.Form.Form",
                                "button": "Run"}, runner=runner, environ=environ)

    stages = report["timings"]["stages"]
    assert list(stages) == ["infobaseCopy", "preparation"]
    assert report["timings"]["totalSeconds"] == pytest.approx(2.0 + stages["infobaseCopy"])
