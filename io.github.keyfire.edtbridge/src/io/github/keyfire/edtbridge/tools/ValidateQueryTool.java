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

import io.github.keyfire.edtbridge.core.QueryLiterals;
import io.github.keyfire.edtbridge.edt.BslGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * MCP tool: edt_validate_query - validate a 1C query (QL) against the live project metadata using
 * EDT's own QL validator (syntax + semantics). Uniquely live: static parsing cannot resolve tables,
 * fields and types against the project's metadata model.
 *
 * <p>Two ways to name the query. Either pass the text itself, or address a MODULE (modulePath or
 * fqn, optionally narrowed to one method): the bridge then pulls the |-framed query literals out of
 * the live module and validates each of them. The second way exists because copying a long literal
 * out of a module and back doubles the context and lets the checked text drift from the stored one.
 */
public final class ValidateQueryTool {

    private final BslGateway gateway = new BslGateway();

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
                "1C query text (language of 1C:Enterprise queries), e.g. ВЫБРАТЬ Ссылка ИЗ Справочник.Контрагенты. "
                + "Omit it to validate the query literals of a module instead (modulePath/fqn)");

        JsonObject mp = new JsonObject();
        mp.addProperty("type", "string");
        mp.addProperty("description",
                "workspace-relative .bsl path of the module whose query literals to validate, "
                + "e.g. src/Documents/Заказ/ManagerModule.bsl");

        JsonObject fq = new JsonObject();
        fq.addProperty("type", "string");
        fq.addProperty("description",
                "metadata FQN of the module's owner instead of modulePath, e.g. CommonModule.Обмен "
                + "(with moduleType when the object has several modules)");

        JsonObject mt = new JsonObject();
        mt.addProperty("type", "string");
        mt.addProperty("description",
                "module kind for fqn resolution, e.g. ObjectModule | ManagerModule | Module");

        JsonObject me = new JsonObject();
        me.addProperty("type", "string");
        me.addProperty("description",
                "validate only the query literals inside this procedure/function");

        JsonObject props = new JsonObject();
        props.add("projectName", pn);
        props.add("queryText", q);
        props.add("modulePath", mp);
        props.add("fqn", fq);
        props.add("moduleType", mt);
        props.add("method", me);

        JsonArray req = new JsonArray();
        req.add("projectName");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "Validate a 1C query against the live project metadata using EDT's own QL validator: "
                + "syntax plus semantics (unknown tables/fields, type errors). Returns issues with "
                + "severity and position. Impossible with static parsing. Takes either the query text, "
                + "or a module address (modulePath/fqn, optionally one method) - then the bridge pulls "
                + "the |-framed query literals out of the live module itself and validates each one.");
        t.addProperty("descriptionRu", "Проверка запроса 1С против живых метаданных проекта валидатором EDT: синтаксис и семантика (несуществующие таблицы/поля, ошибки типов). Возвращает проблемы с уровнем и позицией. Статическим разбором невозможно. Принимает либо текст запроса, либо адрес модуля (modulePath/fqn, при желании один метод) – тогда мост сам достаёт из живого модуля литералы запросов с обрамлением | и проверяет каждый.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = optional(args, "projectName");
        String queryText = optional(args, "queryText");
        String modulePath = optional(args, "modulePath");
        String fqn = optional(args, "fqn");
        if (project == null) {
            return McpServer.toolError("projectName is required");
        }
        if (queryText == null && modulePath == null && fqn == null) {
            return McpServer.toolError(
                    "pass queryText, or address a module with modulePath/fqn to validate its query literals");
        }
        try {
            if (queryText != null) {
                BslGateway.QueryValidation v = gateway.validateQuery(project, queryText);
                if (v.error != null) {
                    return McpServer.toolError("edt_validate_query: " + v.error);
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("valid", v.valid);
                payload.addProperty("errorCount", v.errorCount);
                payload.addProperty("warningCount", v.warningCount);
                payload.addProperty("issueCount", v.issues.size());
                payload.add("issues", issuesOf(v));
                return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(payload));
            }
            return validateModule(project, modulePath, fqn,
                    optional(args, "moduleType"), optional(args, "method"));
        } catch (Exception e) {
            return McpServer.toolError("edt_validate_query failed: " + e.getMessage());
        }
    }

    /** The module mode: pull the query literals out of the live module and validate each. */
    private JsonObject validateModule(String project, String modulePath, String fqn,
            String moduleType, String method) {
        BslGateway.ModuleTextResult mt =
                gateway.moduleText(project, fqn, moduleType, null, modulePath, true);
        if (!mt.found || mt.text == null) {
            String detail = mt.message != null ? mt.message : "module not resolved";
            if (!mt.availableModules.isEmpty()) {
                detail += "; modules of the object: " + String.join(", ", mt.availableModules);
            }
            return McpServer.toolError("edt_validate_query: " + detail);
        }
        if (method != null && mt.methods.stream().noneMatch(m -> method.equalsIgnoreCase(m.name))) {
            return McpServer.toolError("edt_validate_query: method not found in module: " + method);
        }
        List<QueryLiterals.Candidate> found = QueryLiterals.extract(mt.text);
        if (method != null) {
            found = found.stream()
                    .filter(c -> c.method != null && method.equalsIgnoreCase(c.method))
                    .toList();
        }
        JsonArray queries = new JsonArray();
        boolean allValid = true;
        int errors = 0;
        int warnings = 0;
        for (QueryLiterals.Candidate candidate : found) {
            BslGateway.QueryValidation v = gateway.validateQuery(project, candidate.text);
            if (v.error != null) {
                return McpServer.toolError("edt_validate_query: " + v.error);
            }
            JsonObject one = new JsonObject();
            if (candidate.method != null) {
                one.addProperty("method", candidate.method);
            }
            one.addProperty("line", candidate.line);
            one.addProperty("valid", v.valid);
            one.addProperty("errorCount", v.errorCount);
            one.addProperty("warningCount", v.warningCount);
            one.add("issues", issuesOf(v));
            queries.add(one);
            allValid &= v.valid;
            errors += v.errorCount;
            warnings += v.warningCount;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("modulePath", mt.modulePath);
        if (method != null) {
            payload.addProperty("method", method);
        }
        payload.addProperty("queryCount", queries.size());
        payload.addProperty("valid", allValid);
        payload.addProperty("errorCount", errors);
        payload.addProperty("warningCount", warnings);
        payload.add("queries", queries);
        if (queries.size() == 0) {
            payload.addProperty("message",
                    "no |-framed query literals found" + (method != null ? " in method " + method : "")
                    + "; a query built by concatenation cannot be validated from the module");
        }
        if (mt.textTruncated) {
            payload.addProperty("textTruncated", true);
            payload.addProperty("truncationNote",
                    "module text was capped, literals beyond the cap were not scanned");
        }
        return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(payload));
    }

    private static JsonArray issuesOf(BslGateway.QueryValidation v) {
        JsonArray arr = new JsonArray();
        for (BslGateway.QueryIssue qi : v.issues) {
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
        return arr;
    }

    private static String optional(JsonObject args, String name) {
        if (!args.has(name) || args.get(name).isJsonNull()) {
            return null;
        }
        String value = args.get(name).getAsString();
        return value.isBlank() ? null : value;
    }
}
