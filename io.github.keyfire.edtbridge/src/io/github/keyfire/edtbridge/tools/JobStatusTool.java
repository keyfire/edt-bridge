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
import io.github.keyfire.edtbridge.mcp.PersonalDataFilter;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * MCP tool: edt_job_status - the state of a job a call with {@code async=true} started, and its result
 * once it ends. Without a job id, the jobs this run of EDT keeps.
 */
public final class JobStatusTool {

    /** How long a status call waits for the job when the caller does not say. */
    static final int DEFAULT_WAIT_SECONDS = 30;

    private final Jobs<JsonObject> jobs;

    public JobStatusTool(Jobs<JsonObject> jobs) {
        this.jobs = jobs;
    }

    public String name() {
        return "edt_job_status";
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("jobId", ToolJson.strProp("The jobId a call with async=true answered. Without it: the jobs "
                + "of this run of EDT, the newest first."));
        props.add("waitSeconds", ToolJson.intProp("How long to wait for the job to end before answering, "
                + "0-" + Jobs.MAX_WAIT_MILLIS / 1000 + ". Default " + DEFAULT_WAIT_SECONDS + ": a job that ends "
                + "sooner answers sooner."));
        return ToolJson.descriptor(name(),
                "The state of a background job started by a call with async=true: queued, running, succeeded, "
                + "failed or cancelled, with its current step, elapsed seconds and the steps it has already "
                + "applied. Once the job ends, result holds what the call returns when run directly. Waits up "
                + "to waitSeconds for the end, so a loop of these calls needs no pauses of its own. A job "
                + "keeps running when the client disconnects, and its result is kept for an hour. A jobId of "
                + "an earlier run of EDT is refused with that reason: the job ended with that run.",
                "Состояние фонового задания, запущенного вызовом с async=true: queued, running, succeeded, "
                + "failed или cancelled, текущий шаг, прошедшие секунды и шаги, которые уже применены. Когда "
                + "задание закончилось, в result лежит то же, что вернул бы прямой вызов. Ждет конца до "
                + "waitSeconds, поэтому опросу пауза не нужна. Задание продолжается, если клиент отключился, "
                + "а его итог хранится час. jobId прежнего запуска EDT отклоняется с этой причиной: задание "
                + "закончилось вместе с тем запуском.",
                props);
    }

    public JsonObject call(JsonObject args) {
        String id = ToolJson.getStr(args, "jobId");
        if (id == null || id.isBlank()) {
            return list();
        }
        int wait = DEFAULT_WAIT_SECONDS;
        if (args.has("waitSeconds") && !args.get("waitSeconds").isJsonNull()) {
            wait = args.get("waitSeconds").getAsInt();
        }
        JsonObject refused = refusal(jobs, id);
        if (refused != null) {
            return refused;
        }
        try {
            return render(jobs.await(id, Math.max(0, wait) * 1000L), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpServer.toolError("the wait for " + id + " was interrupted");
        }
    }

    private JsonObject list() {
        JsonObject o = new JsonObject();
        o.addProperty("run", jobs.run());
        JsonArray all = new JsonArray();
        long now = System.currentTimeMillis();
        for (Jobs.Snapshot<JsonObject> job : jobs.list()) {
            JsonObject row = new JsonObject();
            row.addProperty("jobId", job.id());
            row.addProperty("tool", job.tool());
            row.addProperty("status", job.state().wire());
            if (!job.stage().isEmpty()) {
                row.addProperty("stage", job.stage());
            }
            row.addProperty("elapsedSeconds", job.elapsedSeconds(now));
            all.add(row);
        }
        o.add("jobs", all);
        return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
    }

    /** The refusal for an id that names no job of this run, or {@code null} when it names one. */
    static JsonObject refusal(Jobs<JsonObject> jobs, String id) {
        return switch (jobs.lookup(id)) {
            case FOUND -> null;
            case MALFORMED -> McpServer.toolError("'" + id + "' is not a job id: a call with async=true answers "
                    + "one like job-" + jobs.run() + "-1");
            case OTHER_RUN -> McpServer.toolError(id.trim() + " was started in an earlier run of EDT, and this run is "
                    + jobs.run() + ": EDT has restarted since, the job ended with it and its result is lost. "
                    + "Run the call again.");
            case UNKNOWN -> McpServer.toolError("no job " + id.trim() + " in this run of EDT: it was never started, "
                    + "or its result is gone - a result is kept for an hour, and only the last "
                    + Jobs.KEEP_FINISHED + " finished jobs are kept.");
        };
    }

    /**
     * A job as a tool result: its state, and once it ends the result of the call under the personal data
     * filter of the tool that ran. A failed job is an error result, so a client shows it as one.
     */
    public static JsonObject render(Jobs.Snapshot<JsonObject> job, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("jobId", job.id());
        o.addProperty("tool", job.tool());
        o.addProperty("status", job.state().wire());
        if (!job.stage().isEmpty()) {
            o.addProperty("stage", job.stage());
        }
        o.addProperty("elapsedSeconds", job.elapsedSeconds(System.currentTimeMillis()));
        if (job.cancelRequested()) {
            o.addProperty("cancelRequested", true);
        }
        if (!job.applied().isEmpty()) {
            JsonArray applied = new JsonArray();
            job.applied().forEach(applied::add);
            o.add("applied", applied);
        }
        if (message != null) {
            o.addProperty("message", message);
        }
        if (job.state().finished()) {
            if (job.result() != null) {
                o.add("result", resultBody(PersonalDataFilter.apply(job.tool(), job.result())));
            }
            if (job.error() != null) {
                o.addProperty("error", job.error());
            }
        } else if (!job.cancelRequested()) {
            o.addProperty("next", "call " + JobTools.STATUS + " with this jobId for the state and the result; "
                    + JobTools.CANCEL + " stops the job");
        }
        JsonObject r = McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        if (job.state() == Jobs.State.FAILED) {
            r.addProperty("isError", true);
        }
        return r;
    }

    /** The text of a tool result, as JSON when it is JSON. */
    private static JsonElement resultBody(JsonObject result) {
        StringBuilder text = new StringBuilder();
        if (result.has("content") && result.get("content").isJsonArray()) {
            for (JsonElement part : result.getAsJsonArray("content")) {
                if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(part.getAsJsonObject().get("text").getAsString());
                }
            }
        }
        String body = text.toString();
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonObject() || parsed.isJsonArray()) {
                return parsed;
            }
        } catch (RuntimeException notJson) {
            // a plain message: kept as text below
        }
        return new com.google.gson.JsonPrimitive(body);
    }
}
