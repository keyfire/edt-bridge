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

import io.github.keyfire.edtbridge.edt.EdtModelGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_validate_query - validate a 1C query (QL) against the live project metadata using
 * EDT's own QL validator (syntax + semantics). Uniquely live: static parsing cannot resolve tables,
 * fields and types against the project's metadata model.
 */
public final class ValidateQueryTool {

    private final EdtModelGateway gateway = new EdtModelGateway();

    public String name() {
        return "edt_validate_query";
    }

    public JsonObject descriptor() {
        JsonObject pn = new JsonObject();
        pn.addProperty("type", "string");
        pn.addProperty("description", "EDT project name – supplies the metadata scope for the query");

        JsonObject q = new JsonObject();
        q.addProperty("type", "string");
        q.addProperty("description",
                "1C query text (language of 1C:Enterprise queries), e.g. ВЫБРАТЬ Ссылка ИЗ Справочник.Контрагенты");

        JsonObject props = new JsonObject();
        props.add("projectName", pn);
        props.add("queryText", q);

        JsonArray req = new JsonArray();
        req.add("projectName");
        req.add("queryText");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "Validate a 1C query against the live project metadata using EDT's own QL validator: "
                + "syntax plus semantics (unknown tables/fields, type errors). Returns issues with "
                + "severity and position. Impossible with static parsing.");
        t.addProperty("descriptionRu", "Проверка запроса 1С против живых метаданных проекта валидатором EDT: синтаксис и семантика (несуществующие таблицы/поля, ошибки типов). Возвращает проблемы с уровнем и позицией. Статическим разбором невозможно.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = (args.has("projectName") && !args.get("projectName").isJsonNull())
                ? args.get("projectName").getAsString() : null;
        String queryText = (args.has("queryText") && !args.get("queryText").isJsonNull())
                ? args.get("queryText").getAsString() : null;
        if (project == null || queryText == null) {
            return McpServer.toolError("projectName and queryText are required");
        }
        try {
            EdtModelGateway.QueryValidation v = gateway.validateQuery(project, queryText);
            if (v.error != null) {
                return McpServer.toolError("edt_validate_query: " + v.error);
            }
            JsonArray arr = new JsonArray();
            for (EdtModelGateway.QueryIssue qi : v.issues) {
                JsonObject o = new JsonObject();
                o.addProperty("severity", qi.severity);
                o.addProperty("message", qi.message);
                if (qi.code != null) {
                    o.addProperty("code", qi.code);
                }
                if (qi.line != null) {
                    o.addProperty("line", qi.line);
                }
                if (qi.column != null) {
                    o.addProperty("column", qi.column);
                }
                if (qi.offset != null) {
                    o.addProperty("offset", qi.offset);
                }
                if (qi.length != null) {
                    o.addProperty("length", qi.length);
                }
                arr.add(o);
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("valid", v.valid);
            payload.addProperty("errorCount", v.errorCount);
            payload.addProperty("warningCount", v.warningCount);
            payload.addProperty("issueCount", arr.size());
            payload.add("issues", arr);
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(payload));
        } catch (Exception e) {
            return McpServer.toolError("edt_validate_query failed: " + e.getMessage());
        }
    }
}
