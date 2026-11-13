"""Reports of a form check for CI: JUnit XML (GitLab's artifacts:reports:junit) and Allure 2 results.

A form check answers with its own JSON report. A CI job wants the result where people already read
test results: GitLab shows a JUnit file in the merge request, Allure builds a report with steps,
times and screenshots. Both are written next to result.json from the same report, one test per
phase of the check: the scenario steps, the reopen phase, or the click of a button check.

A phase fails (JUnit failure, Allure failed) when the solution did not behave: an assertion did not
hold, a step could not run, a window nobody expected. It breaks (JUnit error, Allure broken) when the
check itself could not finish: setup, license, timeout. A phase that was not reached is skipped.
"""
from __future__ import annotations

import hashlib
import json
import shutil
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

JUNIT = "junit.xml"
ALLURE = "allure-results"
FRAMEWORK = "edt-bridge test-form"
#: Statuses that say how the solution behaved; any other status of a failed phase is the environment's.
FAILURES = frozenset({
    "assertion_failed", "message_not_found", "message_baseline_not_empty", "scenario_error", "command_error",
    "form_open_error", "unexpected_dialog", "command_observed", "form_not_found", "ambiguous_form",
    "button_not_found", "ambiguous_button", "button_unavailable", "form_not_default",
})


def _dict(value):
    return value if isinstance(value, dict) else {}


def _outcome(phase):
    if phase.get("ok") is True:
        return "passed"
    return "failed" if phase.get("status") in FAILURES else "broken"


def _assertions(phase):
    return [item for item in phase.get("assertions") or [] if isinstance(item, dict)]


def _assertion_line(item):
    line = f"step {item.get('step')} {item.get('action')}: expected {item.get('expected')!r}, actual {item.get('actual')!r}"
    return line if item.get("ok") else line + " - failed"


def _message(phase, report):
    error = phase.get("error") or _dict(phase.get("interaction")).get("error") or report.get("error") or ""
    parts = [str(phase.get("status") or ""), error.splitlines()[0] if error else ""]
    parts += [_assertion_line(item) for item in _assertions(phase) if not item.get("ok")]
    return "; ".join(part for part in parts if part)[:2000]


def _cases(report):
    """(name, outcome, phase entry of the report, its timings) of each test."""
    timings = _dict(report.get("timings"))
    phase_timings = {item.get("name"): item for item in timings.get("phases") or [] if isinstance(item, dict)}
    scenario = report.get("scenario")
    if isinstance(scenario, dict):
        phases = {item.get("name"): item for item in report.get("phases") or [] if isinstance(item, dict)}
        if phases:
            cases = []
            for name in ["steps"] + (["reopen"] if "reopen" in scenario else []):
                if name in phases:
                    cases.append((name, _outcome(phases[name]), phases[name], _dict(phase_timings.get(name))))
                else:
                    cases.append((name, "skipped", {"status": "not_run"}, {}))
            return cases
    elif report.get("phase") == "legacy":
        return [(f"click {report.get('button')}", _outcome(report), report, _dict(phase_timings.get("legacy")))]
    # The check stopped before a phase: its setup is the one result there is.
    return [("setup", "passed" if report.get("ok") else "broken", report, {})]


def _phase_steps(phase, timing, outcome):
    """(name, seconds, status, parameters) of the parts of a phase, in the order they ran."""
    assertions = {item.get("step"): item for item in _assertions(phase)}
    driver = _dict(timing.get("driver"))
    parts = [("client start", timing.get("clientStart"))]
    if driver:
        parts += [("test manager start", timing.get("managerStartup")), ("connect", driver.get("connect")),
                  ("form lookup", driver.get("formLookup")), ("command", driver.get("command"))]
    else:
        parts.append(("test manager", timing.get("manager")))
    steps = [(name, float(seconds), "passed", []) for name, seconds in parts if isinstance(seconds, (int, float))]
    for item in driver.get("steps") or []:
        assertion = assertions.get(item.get("step"))
        parameters = [] if assertion is None else [("expected", assertion.get("expected")),
                                                   ("actual", assertion.get("actual"))]
        status = "failed" if assertion is not None and not assertion.get("ok") else "passed"
        steps.append((f"step {item.get('step')}: {item.get('action')}", float(item.get("seconds") or 0),
                      status, parameters))
    if outcome in ("failed", "broken") and all(status == "passed" for _, _, status, _ in steps):
        # Nothing names the failed part: it is the last one measured, the part that was running.
        for index in range(len(steps) - 1, -1, -1):
            name, seconds, _, parameters = steps[index]
            steps[index] = (name, seconds, outcome, parameters)
            break
    if isinstance(timing.get("screenshot"), (int, float)):
        steps.append(("screenshot", float(timing["screenshot"]), "passed", []))
    return steps


def _allure_steps(steps, start):
    timed, moment = [], start
    for name, seconds, status, parameters, *children in steps:
        step = {"name": name, "status": status, "stage": "finished", "start": int(moment * 1000),
                "stop": int((moment + seconds) * 1000),
                "parameters": [{"name": key, "value": json.dumps(value, ensure_ascii=False)}
                               for key, value in parameters]}
        if children and children[0]:
            step["steps"] = _allure_steps(children[0], moment)
        timed.append(step)
        moment += seconds
    return timed


def _details(phase, steps):
    lines = [f"{name}: {seconds:.2f} s" + ("" if status == "passed" else f" - {status}")
             for name, seconds, status, *_ in steps]
    lines += [_assertion_line(item) for item in _assertions(phase)]
    if isinstance(phase.get("dialog"), dict):
        lines.append("dialog: " + json.dumps(phase["dialog"], ensure_ascii=False))
    if phase.get("screenshot"):
        lines.append(f"screenshot: {phase['screenshot']}")
    return "\n".join(lines)


def write_form_reports(report, output, *, finished=None):
    """Write junit.xml and allure-results/ of a form check report into `output`; returns their paths."""
    output = Path(output)
    timings = _dict(report.get("timings"))
    total = float(timings.get("totalSeconds") or 0.0)
    started = (time.time() if finished is None else finished) - total
    # The stages before the phases are the setup, timed in the order they ran.
    setup = []
    for name, seconds in _dict(timings.get("stages")).items():
        if name == "phases":
            break
        setup.append((name, float(seconds), "passed", []))
    form = str(report.get("form") or "form")
    target = Path(str(report.get("epf") or report.get("erf") or report.get("navigationLink") or form)).name
    cases = _cases(report)
    allure = output / ALLURE
    allure.mkdir(parents=True, exist_ok=True)

    suites = ET.Element("testsuites", name=FRAMEWORK)
    suite = ET.SubElement(suites, "testsuite", name=form,
                          timestamp=time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(started)))
    properties = ET.SubElement(suite, "properties")
    for name, value in (("target", target), ("status", report.get("status"))):
        ET.SubElement(properties, "property", name=name, value=str(value or ""))
    counts = {"failed": 0, "broken": 0, "skipped": 0}
    moment = started
    for index, (name, outcome, phase, timing) in enumerate(cases):
        if name == "setup":
            steps, seconds = [], total
        else:
            steps, seconds = _phase_steps(phase, timing, outcome), float(timing.get("seconds") or 0.0)
        if index == 0 and setup:
            # The first test carries the setup the whole check paid for.
            steps = [("setup", sum(item[1] for item in setup), "passed", [], setup), *steps]
            if name != "setup":
                seconds += steps[0][1]
        counts[outcome] = counts.get(outcome, 0) + 1
        details = _details(phase, steps)
        message = _message(phase, report)

        case = ET.SubElement(suite, "testcase", name=name, classname=form, time=f"{seconds:.3f}")
        if outcome == "failed":
            ET.SubElement(case, "failure", message=message, type=str(phase.get("status"))).text = details
        elif outcome == "broken":
            ET.SubElement(case, "error", message=message, type=str(phase.get("status"))).text = details
        elif outcome == "skipped":
            ET.SubElement(case, "skipped", message="the previous phase did not pass")
        if details:
            ET.SubElement(case, "system-out").text = details

        identifier = str(uuid.uuid4())
        history = hashlib.md5(f"{form}|{name}".encode("utf-8")).hexdigest()
        result = {
            "uuid": identifier, "historyId": history, "testCaseId": history,
            "name": name, "fullName": f"{form}.{name}", "status": outcome, "stage": "finished",
            "start": int(moment * 1000), "stop": int((moment + seconds) * 1000),
            "labels": [{"name": "suite", "value": form}, {"name": "parentSuite", "value": target},
                       {"name": "framework", "value": FRAMEWORK}],
            "parameters": [{"name": "target", "value": target}],
            "steps": _allure_steps(steps, moment),
            "attachments": [],
        }
        if outcome != "passed":
            result["statusDetails"] = {"message": message, "trace": details}
        screenshot = Path(str(phase["screenshot"])) if phase.get("screenshot") else None
        if screenshot is not None and screenshot.is_file():
            source = f"{identifier}-attachment.png"
            shutil.copyfile(screenshot, allure / source)
            result["attachments"].append({"name": "screenshot", "source": source, "type": "image/png"})
        (allure / f"{identifier}-result.json").write_text(json.dumps(result, ensure_ascii=False, indent=1),
                                                          encoding="utf-8", newline="")
        moment += seconds

    for element in (suites, suite):
        element.set("tests", str(len(cases)))
        element.set("failures", str(counts["failed"]))
        element.set("errors", str(counts["broken"]))
        element.set("skipped", str(counts["skipped"]))
        element.set("time", f"{total:.3f}")
    ET.indent(suites)
    ET.ElementTree(suites).write(output / JUNIT, encoding="utf-8", xml_declaration=True)
    return {"junit": str(output / JUNIT), "allure": str(allure)}
