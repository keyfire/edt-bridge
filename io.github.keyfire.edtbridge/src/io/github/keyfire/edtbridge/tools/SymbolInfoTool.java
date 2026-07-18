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

import io.github.keyfire.edtbridge.edt.BslGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_symbol_info - type/symbol info at a position in a BSL module, using EDT's live
 * dynamic type system: the element under the cursor and the computed value type(s) of the
 * expression. Impossible statically (dynamic BSL typing in project context).
 */
public final class SymbolInfoTool {

    private final BslGateway gateway = new BslGateway();

    public String name() {
        return "edt_symbol_info";
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
                "Type/symbol info at a position (line+column, or offset) in a BSL module, using EDT's "
                + "live dynamic type system: the element under the cursor (kind, name) and the computed "
                + "value type(s) of the expression. Impossible with static parsing.");
        t.addProperty("descriptionRu",
                "Тип/инфо символа в позиции (строка+столбец или offset) модуля BSL по живой динамической "
                + "типизации EDT: элемент под курсором (вид, имя) и вычисленные типы значения выражения. "
                + "Статическим разбором невозможно.");
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
            BslGateway.SymbolInfoResult s = gateway.symbolInfo(project, modulePath, line, column, offset);
            JsonObject o = new JsonObject();
            o.addProperty("found", s.found);
            if (!s.found) {
                o.addProperty("message", s.message);
            } else {
                o.addProperty("elementType", s.elementType);
                o.addProperty("name", s.name);
                JsonArray types = new JsonArray();
                if (s.types != null) {
                    for (String tp : s.types) {
                        types.add(tp);
                    }
                }
                o.add("types", types);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_symbol_info failed: " + e.getMessage());
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
