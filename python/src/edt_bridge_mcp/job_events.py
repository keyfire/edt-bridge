"""Experimental push delivery for one terminal status per background job."""
from __future__ import annotations

import json
import threading
import time
from datetime import datetime, timezone


EXTENSION = "io.modelcontextprotocol/events"
SUBSCRIPTION_ID = "io.modelcontextprotocol/subscriptionId"
EVENT = "job.finished"
PENDING = ("queued", "running")
TERMINAL = ("succeeded", "failed", "cancelled")
MAX_TRACKED_JOBS = 512
MAX_STREAMS = 4
POLL_SECONDS = 2
HEARTBEAT_SECONDS = 25


def tool_payload(reply: dict, *, allow_error: bool = False) -> dict:
    """Read the JSON object inside a bridge tools/call response."""
    result = reply.get("result")
    if not isinstance(result, dict) or (result.get("isError") and not allow_error):
        return {}
    try:
        answer = json.loads(result["content"][0]["text"])
    except (ValueError, KeyError, IndexError, TypeError):
        return {}
    return answer if isinstance(answer, dict) else {}


class JobEvents:
    def __init__(self, send, read_status):
        self.enabled = False
        self._send = send
        self._read_status = read_status
        self._jobs: dict[str, None] = {}
        self._streams: dict[str | int, threading.Event] = {}
        self._lock = threading.Lock()

    def negotiate(self, client_capabilities: dict) -> dict:
        extensions = client_capabilities.get("extensions")
        self.enabled = isinstance(extensions, dict) and EXTENSION in extensions
        return {"extensions": {EXTENSION: {}}} if self.enabled else {}

    def remember(self, answer: dict) -> None:
        job_id = answer.get("jobId")
        if self.enabled and isinstance(job_id, str) and job_id:
            with self._lock:
                self._jobs[job_id] = None
                if len(self._jobs) > MAX_TRACKED_JOBS:
                    del self._jobs[next(iter(self._jobs))]

    @staticmethod
    def catalog() -> dict:
        return {"events": [{
            "name": EVENT,
            "description": "Fires when a background job started on this connection finishes. "
                           "Call edt_job_status with the jobId to get its result.",
            "delivery": ["push"],
            "inputSchema": {
                "type": "object", "required": ["jobId"],
                "properties": {"jobId": {"type": "string"}},
            },
            "payloadSchema": {
                "type": "object", "required": ["jobId", "status"],
                "properties": {
                    "jobId": {"type": "string"},
                    "status": {"type": "string", "enum": list(TERMINAL)},
                },
            },
        }]}

    def subscribe(self, request_id: str | int, params: dict) -> tuple[int, str] | None:
        arguments = params.get("arguments")
        job_id = arguments.get("jobId") if isinstance(arguments, dict) else None
        cursor = params.get("cursor")
        if params.get("name") != EVENT or not isinstance(job_id, str) or not job_id:
            return -32602, "Expected job.finished with a jobId"
        if cursor is not None and cursor != job_id:
            return -32602, "The cursor does not belong to this job"
        with self._lock:
            if job_id not in self._jobs:
                return -32602, "The job was not started on this connection"
            if request_id in self._streams:
                return -32602, "This request ID already has an event stream"
            if len(self._streams) >= MAX_STREAMS:
                return -32000, "Too many active event streams"
            stop = threading.Event()
            self._streams[request_id] = stop
        threading.Thread(target=self._watch, args=(request_id, stop, job_id, cursor),
                         daemon=True, name="edt-job-events").start()
        return None

    def cancel(self, request_id) -> None:
        if type(request_id) in (str, int):
            with self._lock:
                stop = self._streams.get(request_id)
                if stop is not None:
                    stop.set()

    def close(self) -> None:
        with self._lock:
            for stop in self._streams.values():
                stop.set()

    def _notify(self, request_id: str | int, stop: threading.Event, method: str, **params) -> None:
        with self._lock:
            if not stop.is_set():
                params["_meta"] = {SUBSCRIPTION_ID: request_id}
                self._send({"jsonrpc": "2.0", "method": f"notifications/events/{method}", "params": params})

    def _watch(self, request_id: str | int, stop: threading.Event, job_id: str, cursor: str | None) -> None:
        try:
            self._notify(request_id, stop, "active", cursor=cursor, truncated=False)
            # A job has one terminal event. Its ID also serves as its replay cursor.
            if cursor == job_id:
                return
            heartbeat_at = time.monotonic() + HEARTBEAT_SECONDS
            while not stop.is_set():
                status = self._read_status(job_id)
                if status in TERMINAL:
                    self._notify(request_id, stop, "event", name=EVENT, eventId=job_id, cursor=job_id,
                                 timestamp=datetime.now(timezone.utc).isoformat(),
                                 data={"jobId": job_id, "status": status})
                    return
                if status not in PENDING:
                    self._notify(request_id, stop, "terminated",
                                 error={"code": -32000, "message": "Job status is unavailable"})
                    return
                if time.monotonic() >= heartbeat_at:
                    self._notify(request_id, stop, "heartbeat", cursor=cursor)
                    heartbeat_at = time.monotonic() + HEARTBEAT_SECONDS
                stop.wait(POLL_SECONDS)
        finally:
            with self._lock:
                del self._streams[request_id]
                if not stop.is_set():
                    self._send({"jsonrpc": "2.0", "id": request_id, "result": {}})
