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
package com.e1c.fresh.edtbridge.tools;

import com.e1c.fresh.edtbridge.edt.EdtModelGateway;
import com.e1c.fresh.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_go_to_definition - resolve the definition of the BSL symbol at a position in a
 * module, using EDT's live semantic model (real cross-reference resolution, not text match).
 */
public final class GoToDefinitionTool {

    private final EdtModelGateway gateway = new EdtModelGateway();

    public String name() {
        return "edt_go_to_definition";
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name"));
        props.add("modulePath", strProp("Module path, project-relative, e.g. src/CommonModules/ОбщегоНазначения/Module.bsl"));
        props.add("line", intProp("1-based line of the position (use with column); ignored if offset is given"));
        props.add("column", intProp("1-based column of the position (default 1)"));
        props.add("offset", intProp("0-based character offset of the position (overrides line/column)"));

        JsonArray req = new JsonArray();
        req.add("projectName");
        req.add("modulePath");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "Resolve the definition of the BSL symbol at a position (line+column, or offset) in a "
                + "module, using EDT's live semantic model: returns the target's kind, name, owning "
                + "object and location. Real cross-reference resolution, not a text match.");
        t.addProperty("descriptionRu",
                "Перейти к определению символа BSL в указанной позиции (строка+столбец или offset) "
                + "модуля по живой модели EDT: возвращает вид цели, имя, объект-владелец и расположение. "
                + "Настоящее разрешение ссылки, а не текстовый поиск.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        String modulePath = getStr(args, "modulePath");
        if (project == null || modulePath == null) {
            return McpServer.toolError("projectName and modulePath are required");
        }
        int line = getInt(args, "line", -1);
        int column = getInt(args, "column", 1);
        int offset = getInt(args, "offset", -1);
        if (offset < 0 && line < 1) {
            return McpServer.toolError("provide line (and column) or offset");
        }
        try {
            EdtModelGateway.DefinitionResult d = gateway.goToDefinition(project, modulePath, line, column, offset);
            JsonObject o = new JsonObject();
            o.addProperty("found", d.found);
            if (!d.found) {
                o.addProperty("message", d.message);
            } else {
                o.addProperty("targetType", d.targetType);
                o.addProperty("targetName", d.targetName);
                o.addProperty("ownerFqn", d.ownerFqn);
                o.addProperty("uri", d.uri);
                o.addProperty("line", d.line);
                o.addProperty("sameModule", d.sameModule);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_go_to_definition failed: " + e.getMessage());
        }
    }

    private static JsonObject strProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        o.addProperty("description", desc);
        return o;
    }

    private static JsonObject intProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "integer");
        o.addProperty("description", desc);
        return o;
    }

    private static String getStr(JsonObject a, String k) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? a.get(k).getAsString() : null;
    }

    private static int getInt(JsonObject a, String k, int def) {
        if (a.has(k) && !a.get(k).isJsonNull()) {
            try {
                return a.get(k).getAsInt();
            } catch (RuntimeException ignored) {
                // keep default
            }
        }
        return def;
    }
}
