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
 * MCP tool: edt_outgoing_calls - READ. The reverse of edt_find_references: which methods a module /
 * method / form module CALLS (one level out), aggregated as distinct qualifier.method targets with a
 * call-site count, flagging calls through the ExtAPI layer (default prefix ПрограммныйИнтерфейсСервиса).
 * Resolve by fqn (CommonModule.X, a form FQN, or object+moduleType) or modulePath; optional method.
 */
public final class OutgoingCallsTool {

    private final EdtModelGateway gateway = new EdtModelGateway();

    public String name() {
        return "edt_outgoing_calls";
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name"));
        props.add("fqn", strProp("Module FQN: CommonModule.X, a form (DataProcessor.X.Form.Y / "
                + "CommonForm.Y), or an object (Catalog.X) + moduleType. Omit if modulePath is given."));
        props.add("moduleType", strProp("For object FQNs: ObjectModule / ManagerModule / ... Optional."));
        props.add("method", strProp("Restrict the analysis to this procedure/function. Optional (default: "
                + "the whole module)."));
        props.add("modulePath", strProp("Workspace-relative .bsl path – an alternative to fqn. Optional."));
        props.add("extApiPrefix", strProp("Module-name prefix treated as the ExtAPI layer for the extApi "
                + "flag (default: ПрограммныйИнтерфейсСервиса). Optional."));

        JsonArray req = new JsonArray();
        req.add("projectName");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "READ: which methods a module / method / form module CALLS (one level out) – the reverse of "
                + "edt_find_references. Returns distinct qualifier.method targets with a call-site count and "
                + "an extApi flag (calls through the ExtAPI layer, default prefix ПрограммныйИнтерфейсСервиса). "
                + "Resolve by fqn or modulePath; optional method to scope to one procedure/function.");
        t.addProperty("descriptionRu",
                "ЧТЕНИЕ: какие методы вызывает модуль / метод / модуль формы (на один уровень наружу) – "
                + "обратное к edt_find_references. Возвращает различные цели вида квалификатор.метод с числом "
                + "мест вызова и флагом extApi (вызовы через слой ExtAPI, префикс по умолчанию "
                + "ПрограммныйИнтерфейсСервиса). Адресация по fqn или modulePath; method – ограничить одной "
                + "процедурой/функцией.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        if (project == null) {
            return McpServer.toolError("projectName is required");
        }
        String fqn = getStr(args, "fqn");
        String moduleType = getStr(args, "moduleType");
        String method = getStr(args, "method");
        String modulePath = getStr(args, "modulePath");
        String extApiPrefix = getStr(args, "extApiPrefix");
        if (fqn == null && modulePath == null) {
            return McpServer.toolError("provide fqn or modulePath");
        }
        try {
            EdtModelGateway.OutgoingCallsResult res =
                    gateway.outgoingCalls(project, fqn, moduleType, method, modulePath, extApiPrefix);
            JsonObject o = new JsonObject();
            o.addProperty("found", res.found);
            if (res.fqn != null) {
                o.addProperty("fqn", res.fqn);
            }
            if (res.modulePath != null) {
                o.addProperty("modulePath", res.modulePath);
            }
            if (res.scope != null) {
                o.addProperty("scope", res.scope);
            }
            JsonArray calls = new JsonArray();
            int extApiCount = 0;
            for (EdtModelGateway.OutgoingCall c : res.calls) {
                JsonObject co = new JsonObject();
                if (c.qualifier != null) {
                    co.addProperty("qualifier", c.qualifier);
                }
                co.addProperty("method", c.method);
                co.addProperty("count", c.count);
                co.addProperty("firstLine", c.firstLine);
                if (c.extApi) {
                    co.addProperty("extApi", true);
                    extApiCount++;
                }
                calls.add(co);
            }
            o.add("calls", calls);
            o.addProperty("callCount", res.calls.size());
            o.addProperty("extApiCount", extApiCount);
            o.addProperty("truncated", res.truncated);
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_outgoing_calls failed: " + e.getMessage());
        }
    }

    private static JsonObject strProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        o.addProperty("description", desc);
        return o;
    }

    private static String getStr(JsonObject a, String k) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? a.get(k).getAsString() : null;
    }
}
