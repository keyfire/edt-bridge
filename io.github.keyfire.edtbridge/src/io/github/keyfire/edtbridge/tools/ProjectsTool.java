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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_projects - READ. Lists the workspace projects with names, disk locations and natures,
 * and whether each is a 1C:EDT project. Lets a caller discover what is addressable (e.g. which project
 * name maps to which folder on disk) before calling the other tools.
 */
public final class ProjectsTool {

    private final ProjectGateway gateway = new ProjectGateway();

    public String name() {
        return "edt_projects";
    }

    public JsonObject descriptor() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "READ: list the workspace projects – name, disk location, natures, open state, and whether "
                + "each is a 1C:EDT project. Use it to discover what is addressable (e.g. which project name "
                + "maps to which folder) before calling the other tools.");
        t.addProperty("descriptionRu",
                "ЧТЕНИЕ: список проектов рабочей области – имя, путь на диске, natures, открыт ли, и является "
                + "ли проектом 1C:EDT. Помогает понять, что вообще можно адресовать (какое имя проекта какой "
                + "папке соответствует), до вызова остальных инструментов.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        try {
            java.util.List<ProjectGateway.ProjInfo> projects = gateway.listProjectsDetailed();
            JsonArray arr = new JsonArray();
            for (ProjectGateway.ProjInfo pi : projects) {
                JsonObject o = new JsonObject();
                o.addProperty("name", pi.name);
                if (pi.location != null) {
                    o.addProperty("location", pi.location);
                }
                o.addProperty("open", pi.open);
                o.addProperty("dtProject", pi.dtProject);
                JsonArray nats = new JsonArray();
                for (String n : pi.natures) {
                    nats.add(n);
                }
                o.add("natures", nats);
                arr.add(o);
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("count", arr.size());
            payload.add("projects", arr);
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(payload));
        } catch (Exception e) {
            return McpServer.toolError("edt_projects failed: " + e.getMessage());
        }
    }
}
