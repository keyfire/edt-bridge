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
 * MCP tool: edt_outgoing_structures - READ. Companion to edt_outgoing_calls (which gives the call
 * targets): for each outgoing (qualified) call in a method/module, the top-level keys of the
 * Структура passed as its argument, collected from {@code <var>.Вставить("key", ...)} inserts on the
 * argument variable, expanding a seed/template helper ({@code <var> = Helper(...)}) one level when it
 * lives in the same module. Heuristic – literal keys only, flow-insensitive, no external helpers or
 * {@code Новый Структура("a,b")}; {@code partial} flags an incomplete result. An optional qualifier
 * filter scopes to one layer (e.g. ПрограммныйИнтерфейсСервиса for an ExtAPI wrapper).
 */
public final class OutgoingStructuresTool {

    private final BslGateway gateway = new BslGateway();

    public String name() {
        return "edt_outgoing_structures";
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
        props.add("qualifier", strProp("Only report calls whose qualifier starts with this prefix – a module/"
                + "object name (e.g. ПрограммныйИнтерфейсСервиса to scope to an ExtAPI layer, or ФоновыеЗадания). "
                + "Omit = all qualified outgoing calls that pass a structure. Optional."));

        JsonArray req = new JsonArray();
        req.add("projectName");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "READ (best-effort): the top-level keys of the Структура passed as an argument to each "
                + "outgoing (qualified) call – what data goes into the call, the companion to "
                + "edt_outgoing_calls (which gives the call targets). Collects <var>.Вставить(\"key\", ...) on "
                + "the argument variable and expands a same-module seed/template helper (<var> = Helper(...)) "
                + "one level. Heuristic: literal keys only, flow-insensitive; partial=true flags an "
                + "incomplete result. Optional qualifier filter scopes to one layer (e.g. "
                + "ПрограммныйИнтерфейсСервиса for an ExtAPI wrapper). Resolve by fqn or modulePath.");
        t.addProperty("descriptionRu",
                "ЧТЕНИЕ (best-effort): ключи верхнего уровня Структуры, передаваемой аргументом в каждый "
                + "исходящий (квалифицированный) вызов – какие данные уходят в вызов, пара к edt_outgoing_calls "
                + "(тот даёт цели вызовов). Собирает <Перем>.Вставить(\"ключ\", ...) по переменной-аргументу и "
                + "раскрывает хелпер-шаблон того же модуля (<Перем> = Хелпер(...)) на один уровень. Эвристика: "
                + "только литеральные ключи, без учёта ветвлений; partial=true – результат неполный. "
                + "Необязательный фильтр qualifier ограничивает одним слоем (например ПрограммныйИнтерфейсСервиса "
                + "для обвязки ExtAPI). Адресация по fqn или modulePath.");
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
        String qualifier = getStr(args, "qualifier");
        if (fqn == null && modulePath == null) {
            return McpServer.toolError("provide fqn or modulePath");
        }
        try {
            BslGateway.OutgoingStructuresResult res =
                    gateway.outgoingStructures(project, fqn, moduleType, method, modulePath, qualifier);
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
            JsonArray structures = new JsonArray();
            for (BslGateway.OutgoingStructureSite s : res.structures) {
                JsonObject so = new JsonObject();
                if (s.qualifier != null) {
                    so.addProperty("qualifier", s.qualifier);
                }
                so.addProperty("method", s.method);
                so.addProperty("line", s.line);
                if (s.arg != null) {
                    so.addProperty("arg", s.arg);
                }
                JsonArray keys = new JsonArray();
                for (String k : s.keys) {
                    keys.add(k);
                }
                so.add("keys", keys);
                if (s.viaHelper != null) {
                    so.addProperty("viaHelper", s.viaHelper);
                }
                if (s.partial) {
                    so.addProperty("partial", true);
                }
                structures.add(so);
            }
            o.add("structures", structures);
            o.addProperty("structureCount", res.structures.size());
            o.addProperty("truncated", res.truncated);
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_outgoing_structures failed: " + e.getMessage());
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
