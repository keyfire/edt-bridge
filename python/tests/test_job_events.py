import json
import queue
import threading

import pytest

from edt_bridge_mcp import catalog, job_events, jobs
from edt_bridge_mcp.server import Backend, StdioServer


EXTENSION = "io.modelcontextprotocol/events"
SUBSCRIPTION_ID = "io.modelcontextprotocol/subscriptionId"
JOB_ID = "job-test-run-1"
TOOL = "edt_dump_external_object"
BRIDGE_JOB_TOOLS = [tool["name"] for tool in catalog.bridge_tools()
                    if "async" in tool["inputSchema"].get("properties", {})]


class JobBackend(Backend):
    def __init__(self, name=TOOL):
        super().__init__()
        self.job_tool = name
        self.finished = threading.Event()
        self.waiting = threading.Event()
        self.status = "succeeded"
        self.next_status_error = None

    def ensure(self, **kwargs):
        return True, ""

    def is_up(self):
        return True

    def forward(self, message, **kwargs):
        name = message["params"]["name"]
        if name == self.job_tool:
            answer = {"jobId": JOB_ID, "tool": self.job_tool, "status": "running",
                      "message": "started as a background job"}
        else:
            assert name == "edt_job_status"
            failure, self.next_status_error = self.next_status_error, None
            self.waiting.set()
            self.finished.wait(2)
            if failure is not None:
                raise failure
            if self.status is None:
                return {"result": {"isError": True, "content": [
                    {"type": "text", "text": f"no job {JOB_ID} in this run of EDT"}]}}
            answer = {"jobId": JOB_ID, "tool": self.job_tool, "status": self.status,
                      "result": {"path": "local.epf"}}
        result = {"content": [{"type": "text", "text": json.dumps(answer)}]}
        if name == "edt_job_status" and self.status == "failed":
            result["isError"] = True
        return {"result": result}


class ProgressBackend(JobBackend):
    def __init__(self, status_updates, name=TOOL):
        super().__init__(name)
        self.status_updates = iter(status_updates)

    def forward(self, message, **kwargs):
        if message["params"]["name"] != "edt_job_status":
            return super().forward(message, **kwargs)
        status = next(self.status_updates, {"status": "succeeded"})
        answer = {"jobId": JOB_ID, **status}
        return {"result": {"content": [{"type": "text", "text": json.dumps(answer)}]}}


class Client:
    def __init__(self, backend, monkeypatch, *, offers_events=True):
        self.frames = queue.Queue()
        self.server = StdioServer(backend)
        monkeypatch.setattr(self.server, "_send", self.frames.put)
        capabilities = {"extensions": {EXTENSION: {}}} if offers_events else {}
        self.request("initialize", 1, capabilities=capabilities)
        self.capabilities = self.receive()["result"]["capabilities"]

    def request(self, method, request_id, **params):
        self.server.handle({"jsonrpc": "2.0", "method": method, "id": request_id, "params": params})

    def receive(self):
        return self.frames.get(timeout=3)

    def start_job(self, name=TOOL):
        self.request("tools/call", 2, name=name, arguments={"async": True})
        return self.receive()

    def watch(self, request_id=3, job_id=JOB_ID, *, event="job.finished", **params):
        self.request("events/stream", request_id, name=event, arguments={"jobId": job_id}, **params)


def test_events_are_negotiated_and_list_both_job_events(monkeypatch):
    client = Client(JobBackend(), monkeypatch)
    assert EXTENSION in client.capabilities["extensions"]
    client.request("events/list", 2)
    events = client.receive()["result"]["events"]
    assert [event["name"] for event in events] == ["job.finished", "job.progress"]
    for event in events:
        assert event["delivery"] == ["push"]
        assert event["inputSchema"]["required"] == ["jobId"]


def test_existing_clients_use_the_same_job_call_without_events(monkeypatch):
    client = Client(JobBackend(), monkeypatch, offers_events=False)
    assert "extensions" not in client.capabilities
    assert client.start_job()["id"] == 2
    client.request("events/list", 3)
    assert client.receive()["error"]["code"] == -32601


@pytest.mark.parametrize("status", ["succeeded", "failed", "cancelled"])
def test_job_completion_wakes_the_subscriber_and_keeps_result_in_job_status(monkeypatch, status):
    backend = JobBackend()
    backend.status = status
    client = Client(backend, monkeypatch)
    client.start_job()
    client.watch()
    active = client.receive()
    assert active["method"] == "notifications/events/active"
    assert active["params"]["_meta"][SUBSCRIPTION_ID] == 3
    assert backend.waiting.wait(2)
    client.request("ping", 4)
    assert client.receive()["id"] == 4

    backend.finished.set()
    event = client.receive()
    assert event["method"] == "notifications/events/event"
    assert event["params"]["_meta"][SUBSCRIPTION_ID] == 3
    assert event["params"]["data"] == {"jobId": JOB_ID, "status": status}
    assert client.receive()["id"] == 3

    client.request("tools/call", 5, name="edt_job_status", arguments={"jobId": JOB_ID, "waitSeconds": 0})
    assert json.loads(client.receive()["result"]["content"][0]["text"])["result"] == {"path": "local.epf"}


@pytest.mark.parametrize("error", [OSError, TimeoutError])
def test_subscriber_receives_completion_after_a_temporary_status_failure(monkeypatch, error):
    monkeypatch.setattr(job_events, "POLL_SECONDS", 0.01)
    backend = JobBackend()
    backend.next_status_error = error("temporary status failure")
    backend.finished.set()
    client = Client(backend, monkeypatch)
    client.start_job()
    client.watch()

    assert client.receive()["method"] == "notifications/events/active"
    event = client.receive()
    assert event["method"] == "notifications/events/event"
    assert event["params"]["_meta"][SUBSCRIPTION_ID] == 3
    assert event["params"]["data"] == {"jobId": JOB_ID, "status": "succeeded"}
    assert client.receive()["id"] == 3


def test_backend_response_for_an_unknown_job_ends_the_stream(monkeypatch):
    backend = JobBackend()
    backend.status = None
    backend.finished.set()
    client = Client(backend, monkeypatch)
    client.start_job()
    client.watch()

    assert client.receive()["method"] == "notifications/events/active"
    assert client.receive()["method"] == "notifications/events/terminated"
    assert client.receive()["id"] == 3


def test_progress_reports_only_pending_state_and_stage_changes(monkeypatch):
    monkeypatch.setattr(job_events, "POLL_SECONDS", 0.01)
    backend = ProgressBackend([
        {"status": "queued"},
        {"status": "running", "stage": "checking syntax", "elapsedSeconds": 2},
        {"status": "running", "stage": "checking syntax", "elapsedSeconds": 4},
        {"status": "running", "stage": "reading markers", "elapsedSeconds": 6},
        {"status": "succeeded", "result": {"problems": []}},
    ])
    client = Client(backend, monkeypatch)
    client.start_job()
    client.watch(event="job.progress")

    assert client.receive()["method"] == "notifications/events/active"
    pending = [
        {"jobId": JOB_ID, "status": "queued"},
        {"jobId": JOB_ID, "status": "running", "stage": "checking syntax"},
        {"jobId": JOB_ID, "status": "running", "stage": "reading markers"},
    ]
    for data in pending:
        frame = client.receive()
        assert frame["method"] == "notifications/events/event"
        assert frame["params"]["name"] == "job.progress"
        assert frame["params"]["data"] == data
    assert client.receive()["id"] == 3

    client.watch(request_id=4)
    assert client.receive()["method"] == "notifications/events/active"
    assert client.receive()["params"]["data"] == {"jobId": JOB_ID, "status": "succeeded"}
    assert client.receive()["id"] == 4


def test_resuming_progress_skips_the_snapshot_at_the_cursor(monkeypatch):
    monkeypatch.setattr(job_events, "POLL_SECONDS", 0.01)
    first = {"status": "running", "stage": "exporting project"}
    later = {"status": "running", "stage": "building"}
    backend = ProgressBackend([first])
    client = Client(backend, monkeypatch)
    client.start_job()
    client.watch(event="job.progress")
    assert client.receive()["method"] == "notifications/events/active"
    cursor = client.receive()["params"]["cursor"]
    assert client.receive()["id"] == 3

    backend.status_updates = iter([first, later])
    client.watch(request_id=4, event="job.progress", cursor=cursor)
    assert client.receive()["params"]["cursor"] == cursor
    progress = client.receive()["params"]
    assert progress["data"]["stage"] == "building"
    assert progress["cursor"] != cursor
    assert client.receive()["id"] == 4


@pytest.mark.parametrize("tool", BRIDGE_JOB_TOOLS)
def test_each_java_async_tool_can_deliver_job_progress(monkeypatch, tool):
    monkeypatch.setattr(job_events, "POLL_SECONDS", 0.01)
    client = Client(ProgressBackend([{"status": "running"}], name=tool), monkeypatch)
    response = client.start_job(tool)
    assert json.loads(response["result"]["content"][0]["text"])["jobId"] == JOB_ID
    client.watch(event="job.progress")
    assert client.receive()["method"] == "notifications/events/active"
    assert client.receive()["params"]["data"] == {"jobId": JOB_ID, "status": "running"}
    assert client.receive()["id"] == 3


@pytest.mark.parametrize("event", ["job.finished", "job.progress"])
def test_a_second_stdio_connection_cannot_watch_an_existing_job(monkeypatch, event):
    backend = JobBackend()
    Client(backend, monkeypatch).start_job()
    other = Client(backend, monkeypatch)
    other.watch(event=event)
    assert "error" in other.receive()
    assert not backend.waiting.is_set()


def test_late_subscription_delivers_an_already_finished_wrapper_job(monkeypatch):
    backend = JobBackend()
    client = Client(backend, monkeypatch)
    job_id = "wjob-0000cafe"
    monkeypatch.setattr(jobs, "start", lambda *args: job_id)

    def local_status(job_id, wait):
        return {"jobId": job_id, "tool": "edt_unit_tests",
                "status": "succeeded" if backend.finished.is_set() else "running"}, False

    monkeypatch.setattr(jobs, "status", local_status)
    client.request("tools/call", 2, name="edt_unit_tests", arguments={"async": True})
    assert "result" in client.receive()
    backend.finished.set()
    client.watch(job_id=job_id)
    assert client.receive()["method"] == "notifications/events/active"
    event = client.receive()["params"]
    assert event["data"] == {"jobId": job_id, "status": "succeeded"}
    assert client.receive()["id"] == 3

    client.watch(request_id=4, job_id=job_id, cursor=event["cursor"])
    assert client.receive()["method"] == "notifications/events/active"
    assert client.receive()["id"] == 4


@pytest.mark.parametrize("tool", ["edt_test_form", "edt_unit_tests"])
def test_each_python_async_tool_can_deliver_job_progress(monkeypatch, tool):
    monkeypatch.setattr(job_events, "POLL_SECONDS", 0.01)
    job_id = "wjob-0000cafe"
    monkeypatch.setattr(jobs, "start", lambda *args: job_id)
    states = iter([
        {"status": "queued"},
        {"status": "running", "stage": "preparing the test"},
        {"status": "succeeded"},
    ])

    def local_status(job_id, wait):
        return {"jobId": job_id, "tool": tool, **next(states)}, False

    monkeypatch.setattr(jobs, "status", local_status)
    client = Client(JobBackend(), monkeypatch)
    submission = client.start_job(tool)
    assert json.loads(submission["result"]["content"][0]["text"])["jobId"] == job_id
    client.watch(job_id=job_id, event="job.progress")
    assert client.receive()["method"] == "notifications/events/active"
    assert client.receive()["params"]["data"] == {
        "jobId": job_id, "status": "running", "stage": "preparing the test",
    }
    assert client.receive()["id"] == 3


@pytest.mark.parametrize("read_fails", [False, True])
def test_cancelling_subscription_does_not_cancel_or_emit_the_job(monkeypatch, read_fails):
    backend = JobBackend()
    if read_fails:
        backend.next_status_error = TimeoutError("temporary status failure")
    client = Client(backend, monkeypatch)
    client.start_job()
    client.watch()
    assert client.receive()["method"] == "notifications/events/active"
    assert backend.waiting.wait(2)

    client.server.handle({"method": "notifications/cancelled", "params": {"requestId": 3}})
    backend.finished.set()
    client.watch(request_id=4)
    assert client.receive()["params"]["_meta"][SUBSCRIPTION_ID] == 4
    assert client.receive()["params"]["_meta"][SUBSCRIPTION_ID] == 4
    assert client.receive()["id"] == 4
    assert client.frames.empty()
