"""Background jobs of the wrapper tools (EDT-JOB-01): the job process, its directory, status, cancel."""
import json
import os
import subprocess
import sys
import threading
import time

import pytest

from edt_bridge_mcp import form_run, jobs, server


@pytest.fixture(autouse=True)
def cache(tmp_path, monkeypatch):
    monkeypatch.setenv("XDG_CACHE_HOME", str(tmp_path / "cache"))
    return tmp_path / "cache"


class _Process:
    def __init__(self, pid):
        self.pid = pid


def _in_thread(runners):
    """A spawn that runs the job in a thread of this process: the job logic without a new interpreter."""
    def spawn(folder):
        threading.Thread(target=jobs.run, args=(folder,), kwargs={"runners": runners}, daemon=True).start()
        return _Process(os.getpid())
    return spawn


def test_a_job_runs_the_call_and_keeps_its_answer(monkeypatch):
    seen = {}

    def form(arguments):
        seen.update(arguments)
        return {"ok": True, "status": "scenario_completed"}

    job_id = jobs.start("edt_test_form", {"path": "a.epf", "infobaseUser": "Admin"},
                        spawn=_in_thread({"edt_test_form": form}))
    answer, failed = jobs.status(job_id, 10)

    assert not failed
    assert answer["status"] == "succeeded" and answer["tool"] == "edt_test_form"
    assert answer["result"] == {"ok": True, "status": "scenario_completed"}
    assert seen == {"path": "a.epf", "infobaseUser": "Admin"}
    # The arguments may carry a password: they leave the disk once the job has read them.
    assert not (jobs.root() / job_id / "request.json").exists()


def test_a_call_that_raises_is_a_failed_job_with_the_answer_the_call_gives():
    def broken(arguments):
        raise RuntimeError("no platform")

    job_id = jobs.start("edt_unit_tests", {}, spawn=_in_thread({"edt_unit_tests": broken}))
    answer, failed = jobs.status(job_id, 10)

    assert failed and answer["status"] == "failed"
    assert answer["error"] == "edt_unit_tests failed: no platform"


def test_a_running_job_says_so_and_how_to_follow_it():
    release = threading.Event()

    def slow(arguments):
        release.wait(30)
        return {"ok": True}

    job_id = jobs.start("edt_test_form", {}, spawn=_in_thread({"edt_test_form": slow}))
    try:
        started = time.monotonic()
        answer, failed = jobs.status(job_id, 0.5)
        assert time.monotonic() - started >= 0.4
        assert answer["status"] in ("queued", "running") and not failed
        assert "edt_job_status" in answer["next"]
    finally:
        release.set()
    assert jobs.status(job_id, 10)[0]["status"] == "succeeded"


def _dead_pid():
    process = subprocess.Popen([sys.executable, "-c", "pass"])
    process.wait()
    return process.pid


def test_a_job_whose_process_died_without_an_answer_is_failed():
    job_id = jobs.start("edt_test_form", {}, spawn=lambda folder: _Process(_dead_pid()))
    answer, failed = jobs.status(job_id, 0)

    assert failed and answer["status"] == "failed"
    assert "ended without an answer" in answer["error"]


def test_a_cancel_stops_the_process_of_the_job():
    holder = {}

    def spawn(folder):
        options = ({"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt"
                   else {"start_new_session": True})
        holder["process"] = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"], **options)
        return holder["process"]

    job_id = jobs.start("edt_test_form", {}, spawn=spawn)
    answer, failed = jobs.cancel(job_id)

    assert not failed and answer["status"] == "cancelled"
    assert "nothing it did stays applied" in answer["message"]
    assert holder["process"].wait(timeout=15) is not None
    # A second cancel finds the job ended.
    again, _ = jobs.cancel(job_id)
    assert "already ended" in again["message"]


@pytest.mark.skipif(os.name == "nt", reason="Windows has no SIGTERM to unwind on")
def test_on_sigterm_the_job_unwinds_and_writes_that_it_was_cancelled(tmp_path):
    folder = jobs.root() / "wjob-0000beef"
    folder.mkdir(parents=True)
    (folder / "state.json").write_text(json.dumps({"jobId": folder.name, "tool": "edt_test_form",
                                                   "status": "queued", "submittedAt": time.time()}))
    (folder / "request.json").write_text(json.dumps({"tool": "edt_test_form", "arguments": {}}))
    cleaned = []

    def form(arguments):
        try:
            os.kill(os.getpid(), 15)
            time.sleep(5)
        finally:
            cleaned.append(True)

    jobs.run(folder, runners={"edt_test_form": form})
    state = json.loads((folder / "state.json").read_text())
    assert state["status"] == "cancelled" and cleaned == [True]


def test_ids_that_name_nothing_are_refused_with_their_reason():
    malformed, failed = jobs.status("42")
    assert failed and "is not a job id" in malformed
    unknown, failed = jobs.cancel("wjob-0123abcd")
    assert failed and "no job wjob-0123abcd" in unknown
    assert jobs.is_local(" wjob-0123abcd") and not jobs.is_local("job-abc234-1") and not jobs.is_local(None)


def test_the_list_puts_the_newest_first_and_old_jobs_are_removed(monkeypatch):
    done = {"edt_test_form": lambda arguments: {"ok": True}}
    first = jobs.start("edt_test_form", {}, spawn=_in_thread(done))
    jobs.status(first, 10)
    time.sleep(0.05)
    second = jobs.start("edt_test_form", {}, spawn=_in_thread(done))
    jobs.status(second, 10)
    assert [row["jobId"] for row in jobs.listing()] == [second, first]

    later = time.time() + jobs.RETENTION_SECONDS + 60
    monkeypatch.setattr(jobs.time, "time", lambda: later)
    assert jobs.listing() == []
    assert not (jobs.root() / first).exists()


def test_a_real_job_process_answers_like_the_call(monkeypatch):
    """The detached interpreter imports the package and runs the tool: a refusal comes back as itself."""
    monkeypatch.delenv(form_run.ENV_INFOBASE, raising=False)
    job_id = jobs.start("edt_test_form", {"path": "missing.epf", "form": "ExternalDataProcessor.X.Form.F",
                                          "button": "Go"})
    answer, failed = jobs.status(job_id, 40)

    assert not failed, (jobs.root() / job_id / "log.txt").read_text(errors="replace")
    assert answer["status"] == "succeeded"
    assert answer["result"] == form_run.run_tool({"path": "missing.epf", "form": "ExternalDataProcessor.X.Form.F",
                                                  "button": "Go"})


def test_the_pages_name_exactly_the_tools_that_take_async():
    """The list on the tools page is a hand copy: it is held to the descriptors in both languages."""
    import re
    from pathlib import Path

    root = Path(__file__).resolve().parents[2]
    catalog = json.loads((root / "python/src/edt_bridge_mcp/tool_catalog.json").read_text(encoding="utf-8"))
    tools = catalog["tools"] if isinstance(catalog, dict) else catalog
    expected = {tool["name"] for tool in tools + server.LOCAL_TOOLS
                if "async" in tool.get("inputSchema", {}).get("properties", {})}
    assert {"edt_build_extension", "edt_test_form", "edt_unit_tests"} <= expected
    for page in ("docs/tools/index.md", "docs/ru/tools/index.md"):
        text = (root / page).read_text(encoding="utf-8")
        block = re.search(r"<!-- async-tools:start -->(.*?)<!-- async-tools:end -->", text, re.S)
        assert block, page
        assert set(re.findall(r"`(edt_[a-z_]+)`", block.group(1))) == expected, page


# ---------------------------------------------------------------- the wrapper server


def _server(monkeypatch, up=False):
    srv = server.StdioServer(server.Backend())
    sent, forwarded = [], []
    monkeypatch.setattr(srv, "_send", sent.append)
    monkeypatch.setattr(srv.backend, "is_up", lambda: up)
    monkeypatch.setattr(srv.backend, "ensure", lambda wait: (True, "up"))
    monkeypatch.setattr(srv, "_forward_or_error", lambda message, req_id: forwarded.append(message))
    return srv, sent, forwarded


def _call(srv, name, arguments):
    message = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": name, "arguments": arguments}}
    srv.handle(message)
    return message


def _text(sent):
    return sent[-1]["result"]["content"][0]["text"]


def test_an_async_form_check_answers_with_a_job_and_the_job_tools_follow_it(monkeypatch):
    srv, sent, forwarded = _server(monkeypatch)
    release = threading.Event()

    def slow(arguments):
        release.wait(30)
        return {"ok": True, "status": "command_completed", "echo": sorted(arguments)}

    real_start = jobs.start
    monkeypatch.setattr(jobs, "start", lambda tool, arguments: real_start(
        tool, arguments, spawn=_in_thread({"edt_test_form": slow})))
    monkeypatch.setattr(jobs, "INLINE_SECONDS", 0.2)
    _call(srv, "edt_test_form", {"path": "a.epf", "form": "ExternalDataProcessor.X.Form.F", "button": "Go",
                                 "async": True})
    started = json.loads(_text(sent))
    assert started["jobId"].startswith("wjob-") and started["message"] == "started as a background job"

    release.set()
    _call(srv, "edt_job_status", {"jobId": started["jobId"], "waitSeconds": 10})
    done = json.loads(_text(sent))
    assert done["status"] == "succeeded"
    assert done["result"]["echo"] == ["button", "form", "path"]  # async itself is not passed on
    assert not forwarded


def test_the_list_joins_the_jobs_of_the_wrapper_and_says_when_no_edt_runs(monkeypatch):
    srv, sent, _ = _server(monkeypatch)
    _call(srv, "edt_job_status", {})
    answer = json.loads(_text(sent))
    assert answer["jobs"] == [] and "no EDT is running" in answer["bridgeJobs"]

    srv, sent, _ = _server(monkeypatch, up=True)
    monkeypatch.setattr(srv.backend, "call_tool", lambda name, arguments: json.dumps(
        {"run": "abc234", "jobs": [{"jobId": "job-abc234-1", "tool": "edt_build_extension"}]}))
    _call(srv, "edt_job_status", {})
    answer = json.loads(_text(sent))
    assert answer["run"] == "abc234" and answer["jobs"][0]["jobId"] == "job-abc234-1"


def test_a_bridge_job_goes_to_the_bridge_and_without_edt_is_lost(monkeypatch):
    srv, sent, forwarded = _server(monkeypatch, up=True)
    call = _call(srv, "edt_cancel_job", {"jobId": "job-abc234-1"})
    assert forwarded == [call]

    srv, sent, forwarded = _server(monkeypatch, up=False)
    _call(srv, "edt_job_status", {"jobId": "job-abc234-1"})
    assert not forwarded
    assert sent[-1]["result"]["isError"] is True
    assert "ended with the EDT that ran it" in _text(sent)
