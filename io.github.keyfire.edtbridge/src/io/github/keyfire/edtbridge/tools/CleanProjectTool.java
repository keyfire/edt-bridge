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
        props.add("waitSeconds", ToolJson.intProp("How long to wait for validation to settle. Default "
                + "120. Big projects need more."));
        props.add("apply", ToolJson.boolProp("false (default) = dry-run: report the current problem "
                + "count and whether auto-build is on, change nothing. true = clean, rebuild and wait "
                + "for the problem count to settle."));
        return ToolJson.descriptor(name(),
                "WRITE (Phase 2): discard a project's build results and let them be recomputed - the "
                + "programmatic equivalent of EDT's \"Clean\" dialog. EDT hangs its checks off the "
                + "build, so this is what makes validation actually run again: a marker can otherwise "
                + "survive long after the code that caused it was fixed, and reading a stale marker is "
                + "worse than reading none. Reports the problem count before and after, waiting until "
                + "it stops changing. Sources are untouched, so no force is needed - but it does need a "
                + "configured token. Use it before trusting edt_project_errors after an edit.",
                "ЗАПИСЬ (Phase 2): сбросить результаты сборки проекта и дать пересчитать их заново - "
                + "программный аналог диалога \"Очистить\" в EDT. Проверки EDT висят на сборке, поэтому "
                + "именно это заставляет валидацию отработать снова: иначе замечание может пережить "
                + "исправление кода, а читать устаревший маркер хуже, чем не читать вовсе. Возвращает "
                + "число замечаний до и после, дождавшись, пока оно перестанет меняться. Исходники не "
                + "трогаются, force не нужен - но нужен токен. Вызывать перед тем, как доверять "
                + "edt_project_errors после правок.",
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
            if (res.problemsBefore >= 0) {
                o.addProperty("problemsBefore", res.problemsBefore);
            }
            if (res.problemsAfter >= 0) {
                o.addProperty("problemsAfter", res.problemsAfter);
            }
            if (res.applied) {
                o.addProperty("settled", res.settled);
                o.addProperty("elapsedMs", res.elapsedMs);
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
