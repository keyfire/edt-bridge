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

import java.util.List;

import io.github.keyfire.edtbridge.edt.ProjectGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_project_errors - list EDT validation problems for a project (or all open
 * projects) from the live workspace. The M0 proof tool: plugin in EDT -> MCP -> EDT state.
 */
public final class ProjectErrorsTool {

    private final ProjectGateway gateway = new ProjectGateway();

    public String name() {
        return "edt_project_errors";
    }

    /** MCP tool descriptor for tools/list. */
    public JsonObject descriptor() {
        JsonObject projectName = new JsonObject();
        projectName.addProperty("type", "string");
        projectName.addProperty("description", "EDT project name; omit for all open projects");

        JsonObject props = new JsonObject();
        props.add("projectName", projectName);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", new JsonArray());

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "List EDT validation problems for a project from the live workspace: both standard "
                + "Eclipse markers (syntax/build) and EDT check results (Standards, e.g. com.e1c.v8codestyle) "
                + "read from EDT's own marker store. Each problem carries source (eclipse|edt-check), "
                + "severity, message, resource, line, and for EDT checks the checkId and EDT grade.");
        t.addProperty("descriptionRu", "Список проблем валидации EDT по проекту из живой рабочей области: "
                + "и стандартные маркеры Eclipse (синтаксис/сборка), и результаты проверок EDT (Стандарты, "
                + "напр. com.e1c.v8codestyle) из собственного хранилища маркеров EDT. У каждой проблемы – "
                + "источник (eclipse|edt-check), важность, сообщение, ресурс, строка, а у проверок EDT – checkId и класс важности.");
        t.add("inputSchema", schema);
        return t;
    }

    /** Execute the tool; returns an MCP tool result (content[] / isError). */
    public JsonObject call(JsonObject args) {
        String project = (args.has("projectName") && !args.get("projectName").isJsonNull())
                ? args.get("projectName").getAsString()
                : null;
        try {
            List<ProjectGateway.Problem> problems = gateway.getProjectErrors(project);
            JsonArray arr = new JsonArray();
            for (ProjectGateway.Problem p : problems) {
                JsonObject o = new JsonObject();
                o.addProperty("project", p.project);
                o.addProperty("severity", p.severity);
                o.addProperty("message", p.message);
                o.addProperty("resource", p.resource);
                o.addProperty("line", p.line);
                o.addProperty("source", p.source);
                if (p.checkId != null) {
                    o.addProperty("checkId", p.checkId);
                }
                if (p.edtSeverity != null) {
                    o.addProperty("edtSeverity", p.edtSeverity);
                }
                if (p.location != null) {
                    o.addProperty("location", p.location);
                }
                if (p.markerType != null) {
                    o.addProperty("markerType", p.markerType);
                }
                arr.add(o);
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("count", problems.size());
            payload.add("problems", arr);
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(payload));
        } catch (Exception e) {
            return McpServer.toolError("edt_project_errors failed: " + e.getMessage());
        }
    }
}
