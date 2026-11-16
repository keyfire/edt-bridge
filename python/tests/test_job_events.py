import json
import queue
import threading

import pytest

from edt_bridge_mcp import job_events, jobs
from edt_bridge_mcp.server import Backend, StdioServer


EXTENSION = "io.modelcontextprotocol/events"
SUBSCRIPTION_ID = "io.modelcontextprotocol/subscriptionId"
JOB_ID = "job-test-run-1"
TOOL = "edt_dump_external_object"


class JobBackend(Backend):
    def __init__(self):
        super().__init__()
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
        if name == TOOL:
            answer = {"jobId": JOB_ID, "tool": TOOL, "status": "running",
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
            answer = {"jobId": JOB_ID, "tool": TOOL, "status": self.status,
                      "result": {"path": "local.epf"}}
        result = {"content": [{"type": "text", "text": json.dumps(answer)}]}
        if name == "edt_job_status" and self.status == "failed":
            result["isError"] = True
        return {"result": result}


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

    def start_job(self):
        self.request("tools/call", 2, name=TOOL, arguments={"async": True})
        return self.receive()

    def watch(self, request_id=3, job_id=JOB_ID, **params):
        self.request("events/stream", request_id, name="job.finished", arguments={"jobId": job_id}, **params)


def test_events_are_negotiated_and_list_one_job_event(monkeypatch):
    client = Client(JobBackend(), monkeypatch)
    assert EXTENSION in client.capabilities["extensions"]
    client.request("events/list", 2)
    events = client.receive()["result"]["events"]
    assert [event["name"] for event in events] == ["job.finished"]
    assert events[0]["delivery"] == ["push"]
    assert events[0]["inputSchema"]["required"] == ["jobId"]


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


def test_a_second_stdio_connection_cannot_watch_an_existing_job(monkeypatch):
    backend = JobBackend()
    Client(backend, monkeypatch).start_job()
    other = Client(backend, monkeypatch)
    other.watch()
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
