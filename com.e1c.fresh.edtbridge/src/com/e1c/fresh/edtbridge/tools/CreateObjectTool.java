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
 * MCP tool: edt_create_object - WRITE (Phase 2). Creates a new top-level metadata object
 * (Catalog/Document/Enum/InformationRegister/…) using EDT's own object factory + per-type initializer
 * (so it is born valid), registers it as a BM top object and links it into the Configuration. Dry-run
 * by default. Adding an object is additive (non-breaking) → no force; still requires a
 * configured token, and the caller must verify bsl_support_status EDITABLE before apply.
 */
public final class CreateObjectTool {

    private final EdtModelGateway gateway = new EdtModelGateway();

    public String name() {
        return "edt_create_object";
    }

    /** Write tool — the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name"));
        props.add("objectType", strProp("Object kind (Russian): Справочник, Документ, Перечисление, "
                + "РегистрСведений, РегистрНакопления, РегистрБухгалтерии, РегистрРасчета, Отчет, Обработка, "
                + "ПланВидовХарактеристик, ПланСчетов, ПланВидовРасчета, БизнесПроцесс, Задача, ПланОбмена, "
                + "Константа, ОбщийМодуль"));
        props.add("name", strProp("New object name (a valid 1C identifier, Cyrillic allowed)"));
        props.add("synonymRu", strProp("Russian synonym. Optional."));
        props.add("comment", strProp("Comment. Optional."));
        props.add("apply", boolProp("false (default) = dry-run: validate (type, name free, config feature) "
                + "and return the plan, create nothing. true = create the object (factory + attach + link + "
                + "serialize). Verify bsl_support_status EDITABLE first."));

        JsonArray req = new JsonArray();
        req.add("projectName");
        req.add("objectType");
        req.add("name");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE (Phase 2): create a new top metadata object (Catalog/Document/Enum/"
                + "InformationRegister/…) via EDT's own factory + per-type initializer, registered in the "
                + "Configuration. Dry-run by default: validates and returns the plan WITHOUT creating. "
                + "apply=true creates it (object .mdo + Configuration.mdo). Additive — no force needed — but "
                + "requires a configured token. Verify bsl_support_status EDITABLE before apply.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ (Phase 2): создать новый объект метаданных (Справочник/Документ/Перечисление/"
                + "РегистрСведений/…) через штатную фабрику EDT + инициализатор типа, с регистрацией в "
                + "Configuration. По умолчанию dry-run: проверяет и возвращает план БЕЗ создания. apply=true "
                + "создаёт (object .mdo + Configuration.mdo). Аддитивно – force не нужен – но требует токен. "
                + "Перед apply проверить bsl_support_status = EDITABLE.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        String objectType = getStr(args, "objectType");
        String name = getStr(args, "name");
        if (project == null || objectType == null || name == null) {
            return McpServer.toolError("projectName, objectType and name are required");
        }
        String synonymRu = getStr(args, "synonymRu");
        String comment = getStr(args, "comment");
        boolean apply = args.has("apply") && !args.get("apply").isJsonNull() && args.get("apply").getAsBoolean();
        try {
            EdtModelGateway.CreateObjectResult res =
                    gateway.createObject(project, objectType, name, synonymRu, comment, apply);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            o.addProperty("objectType", res.objectType);
            if (res.eClass != null) {
                o.addProperty("eClass", res.eClass);
            }
            o.addProperty("name", res.name);
            if (res.fqn != null) {
                o.addProperty("fqn", res.fqn);
            }
            o.addProperty("nameValid", res.nameValid);
            o.addProperty("typeKnown", res.typeKnown);
            if (res.nameAvailable != null) {
                o.addProperty("nameAvailable", res.nameAvailable.booleanValue());
            }
            o.addProperty("configFound", res.configFound);
            if (res.feature != null) {
                o.addProperty("feature", res.feature);
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
            return McpServer.toolError("edt_create_object failed: " + e.getMessage());
        }
    }

    private static JsonObject strProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        o.addProperty("description", desc);
        return o;
    }

    private static JsonObject boolProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "boolean");
        o.addProperty("description", desc);
        return o;
    }

    private static String getStr(JsonObject a, String k) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? a.get(k).getAsString() : null;
    }
}
