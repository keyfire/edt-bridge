/*
 * edt-bridge - a 1C:EDT bridge that exposes the live EDT model over MCP.
 * Copyright 2026 edt-bridge contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.keyfire.edtbridge.tools;

import io.github.keyfire.edtbridge.core.JobTools;
import io.github.keyfire.edtbridge.core.Jobs;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_cancel_job - stop a background job. A queued job never runs; a running one stops at its
 * next step, and the answer lists the steps it had already applied.
 */
public final class CancelJobTool {

    /** How long a cancel waits for the job to reach its next check before answering. */
    static final long WAIT_MILLIS = 10_000;

    private final Jobs<JsonObject> jobs;

    public CancelJobTool(Jobs<JsonObject> jobs) {
        this.jobs = jobs;
    }

    public String name() {
        return "edt_cancel_job";
    }

    /** Cancelling stops a change on its way, so it takes the token a write takes. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("jobId", ToolJson.strProp("The jobId a call with async=true answered"));
        return ToolJson.descriptor(name(),
                "Cancel a background job started by a call with async=true. A queued job never runs. A running "
                + "one stops at its next step: a platform process (ibcmd, the designer) is stopped with its "
                + "children, an EDT operation at its next progress check. What the job had already done stays "
                + "done and is listed in applied - a configuration loaded into the infobase stays loaded. A job "
                + "that does not reach its next check within 10 seconds is still running with cancelRequested; "
                + "edt_job_status shows when it ends. A finished job is answered as it is.",
                "Отменить фоновое задание, запущенное вызовом с async=true. Задание в очереди не запустится. "
                + "Идущее остановится на следующем шаге: процесс платформы (ibcmd, конфигуратор) снимается "
                + "вместе с дочерними, операция EDT - на следующей проверке прогресса. Сделанное заданием "
                + "остается сделанным и перечислено в applied: загруженная в базу конфигурация остается "
                + "загруженной. Если задание не дошло до проверки за 10 секунд, оно еще идет с "
                + "cancelRequested, а конец покажет edt_job_status. Законченное задание возвращается как есть.",
                props, "jobId");
    }

    public JsonObject call(JsonObject args) {
        String id = ToolJson.getStr(args, "jobId");
        if (id == null || id.isBlank()) {
            return McpServer.toolError("jobId is required");
        }
        JsonObject refused = JobStatusTool.refusal(jobs, id);
        if (refused != null) {
            return refused;
        }
        try {
            boolean wasFinished = jobs.await(id, 0).state().finished();
            Jobs.Snapshot<JsonObject> job = jobs.cancel(id, WAIT_MILLIS);
            String message;
            if (wasFinished) {
                message = "the job had already ended: nothing to cancel";
            } else if (job.state() == Jobs.State.CANCELLED) {
                message = job.applied().isEmpty()
                        ? "cancelled; the job had applied nothing"
                        : "cancelled; the steps in applied stay done";
            } else if (job.state().finished()) {
                message = "the job ended before it reached a point where it could stop";
            } else {
                message = "cancellation requested: the job stops at its next step; " + JobTools.STATUS
                        + " shows when it does";
            }
            return JobStatusTool.render(job, message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpServer.toolError("the cancel of " + id + " was interrupted");
        }
    }
}
