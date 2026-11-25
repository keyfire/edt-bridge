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

import io.github.keyfire.edtbridge.edt.ProjectGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_clean_project - WRITE (Phase 2). Discards a project's build results so validation runs
 * again, the programmatic equivalent of EDT's "Clean" dialog. Waits for the problem count to stop
 * moving, so what comes back has settled instead of being a mid-validation snapshot.
 */
public final class CleanProjectTool {

    private final ProjectGateway gateway = new ProjectGateway();

    public String name() {
        return "edt_clean_project";
    }

    /** Write tool - the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", ToolJson.strProp("Project to clean"));
        props.add("rebuild", ToolJson.boolProp("Also run a full build after cleaning - what the Clean "
                + "dialog does when auto-build is off. Default true; without it a project with "
                + "auto-build off is left unbuilt and reports no problems at all."));
        props.add("waitSeconds", ToolJson.intProp("Time budget in seconds for project services, build jobs "
                + "and validation to settle. Default 120. A synchronous build can exceed this budget."));
        props.add("apply", ToolJson.boolProp("false (default) reports the current problem count and auto-build state. "
                + "true waits for project services, cleans the project, and waits for validation when a build runs."));
        return ToolJson.descriptor(name(),
                "WRITE (Phase 2): discard a project's build results so EDT can run validation again. "
                + "Waits for project services to restart before cleaning and after the build. Returns the "
                + "problem counts before and after. Treat the final count as settled only when settled=true. "
                + "A failure includes its stage, completed stages, and job diagnostics. applied=true means "
                + "the clean finished, even if a later rebuild or wait failed. Requires a configured token.",
                "ЗАПИСЬ (Phase 2): сбросить результаты сборки проекта, чтобы EDT запустила проверки заново. "
                + "Ожидает перезапуска служб проекта перед очисткой и после сборки. Возвращает число "
                + "замечаний до и после. Итоговый счет стабилен при settled=true. При сбое возвращает "
                + "текущий этап, завершенные этапы и диагностику фоновых заданий. applied=true означает, "
                + "что очистка выполнена, даже если пересборка или ожидание завершились ошибкой. Нужен токен.",
                props, "projectName");
    }

    public JsonObject call(JsonObject args) {
        String project = ToolJson.getStr(args, "projectName");
        if (project == null) {
            return McpServer.toolError("projectName is required");
        }
        // Rebuilding is the useful default: cleaning alone can leave a project with nothing built and
        // therefore nothing validated.
        boolean rebuild = !args.has("rebuild") || args.get("rebuild").isJsonNull()
                || args.get("rebuild").getAsBoolean();
        int waitSeconds = (args.has("waitSeconds") && !args.get("waitSeconds").isJsonNull())
                ? args.get("waitSeconds").getAsInt() : 120;
        try {
            ProjectGateway.CleanProjectResult res =
                    gateway.cleanProject(project, rebuild, waitSeconds, ToolJson.getBool(args, "apply"));
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            o.addProperty("name", res.name);
            o.addProperty("exists", res.exists);
            o.addProperty("open", res.open);
            o.addProperty("rebuild", res.rebuild);
            o.addProperty("autoBuilding", res.autoBuilding);
            o.addProperty("modelReady", res.modelReady);
            o.addProperty("attempted", res.attempted);
            o.addProperty("stateMayHaveChanged", res.stateMayHaveChanged);
            o.addProperty("stage", res.stage);
            o.addProperty("canceled", res.canceled);
            o.addProperty("monitorCanceled", res.monitorCanceled);
            o.addProperty("threadInterrupted", res.threadInterrupted);
            o.addProperty("settled", res.settled);
            o.addProperty("validationCompleted", res.validationCompleted);
            o.addProperty("sawValidation", res.sawValidation);
            o.addProperty("elapsedMs", res.elapsedMs);
            var gson = new GsonBuilder().create();
            o.add("completedStages", gson.toJsonTree(res.completedStages));
            o.add("jobs", gson.toJsonTree(res.jobs));
            o.addProperty("jobsTruncated", res.jobsTruncated);
            if (res.status != null) o.add("status", gson.toJsonTree(res.status));
            if (res.cancelCause != null) o.addProperty("cancelCause", res.cancelCause);
            if (res.failureType != null) o.addProperty("failureType", res.failureType);
            if (res.failureOrigin != null) o.addProperty("failureOrigin", res.failureOrigin);
            if (res.nextStep != null) o.addProperty("nextStep", res.nextStep);
            if (res.problemsBefore >= 0) {
                o.addProperty("problemsBefore", res.problemsBefore);
            }
            if (res.problemsAfter >= 0) {
                o.addProperty("problemsAfter", res.problemsAfter);
            }
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.warning != null) {
                o.addProperty("warning", res.warning);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_clean_project failed: " + e.getMessage());
        }
    }
}
