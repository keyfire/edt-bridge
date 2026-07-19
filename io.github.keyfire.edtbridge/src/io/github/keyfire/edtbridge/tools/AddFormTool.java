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

import io.github.keyfire.edtbridge.edt.FormWriteGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_add_form - WRITE (Phase 2). Creates a managed form on an existing metadata object using
 * EDT's own form generator (the engine behind the "New form" wizard), so the form, its items and its
 * module are born valid rather than hand-assembled as XML. Dry-run by default; adding a form is
 * additive, so no force is needed, but a configured token is.
 */
public final class AddFormTool {

    private final FormWriteGateway gateway = new FormWriteGateway();

    public String name() {
        return "edt_add_form";
    }

    /** Write tool - the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name"));
        props.add("ownerFqn", strProp("Object that will carry the form, with an English type prefix, e.g. "
                + "Catalog.Контрагенты, Document.Заказ, DataProcessor.X, ExternalDataProcessor.Y, "
                + "InformationRegister.Z, Report.R"));
        props.add("name", strProp("New form name (a valid 1C identifier, Cyrillic allowed), e.g. Форма, "
                + "ФормаЭлемента, ФормаСписка"));
        props.add("formType", strProp("Form kind. A FormType name - GENERIC, OBJECT, FOLDER, LIST, CHOICE, "
                + "FOLDER_CHOICE, RECORD, RECORD_SET, REPORT, REPORT_SETTINGS, REPORT_VARIANT, CONSTANTS, "
                + "SEARCH, SAVE, LOAD, DYNAMIC_LIST - or a Russian alias (ФормаЭлемента, ФормаСписка, "
                + "ФормаВыбора, ФормаЗаписи, ФормаНабораЗаписей, ФормаОтчета, ПроизвольнаяФорма). "
                + "Optional - omitted picks the owner's usual kind (Catalog/Document -> OBJECT, "
                + "InformationRegister -> RECORD, Report -> REPORT, DataProcessor -> GENERIC)."));
        props.add("synonymRu", strProp("Russian synonym. Optional."));
        props.add("defaultForm", boolProp("Also make it the owner's default form. Optional, default false."));
        props.add("columnCount", intProp("Column count passed to the generator (EDT's wizard default is 1). "
                + "Optional."));
        props.add("apply", boolProp("false (default) = dry-run: validate (owner exists, carries forms, name "
                + "free, form kind known) and return the plan, create nothing. true = create the form "
                + "(BasicForm + generated Form content + Module.bsl). Verify bsl_support_status EDITABLE "
                + "first."));

        JsonArray req = new JsonArray();
        req.add("projectName");
        req.add("ownerFqn");
        req.add("name");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE (Phase 2): create a managed form on an existing metadata object via EDT's own form "
                + "generator - the engine behind the \"New form\" wizard - so the form, its items and its "
                + "module are generated the way EDT would, not hand-written as XML. Dry-run by default: "
                + "validates and returns the plan WITHOUT creating. apply=true creates it (owner .mdo gains "
                + "the form entry, Form.form and Module.bsl are written). Additive - no force needed - but "
                + "requires a configured token. Verify bsl_support_status EDITABLE before apply.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ (Phase 2): создать управляемую форму у существующего объекта метаданных штатным "
                + "генератором EDT - тем же, что стоит за мастером \"Новая форма\", - поэтому форма, её "
                + "элементы и модуль получаются такими, какими их сделала бы EDT, а не собранными руками из "
                + "XML. По умолчанию dry-run: проверяет и возвращает план БЕЗ создания. apply=true создаёт "
                + "(в .mdo владельца появляется форма, пишутся Form.form и Module.bsl). Аддитивно - force не "
                + "нужен - но требует токен. Перед apply проверить bsl_support_status = EDITABLE.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        String ownerFqn = getStr(args, "ownerFqn");
        String name = getStr(args, "name");
        if (project == null || ownerFqn == null || name == null) {
            return McpServer.toolError("projectName, ownerFqn and name are required");
        }
        String formType = getStr(args, "formType");
        String synonymRu = getStr(args, "synonymRu");
        boolean defaultForm = getBool(args, "defaultForm");
        Integer columnCount = getInt(args, "columnCount");
        boolean apply = getBool(args, "apply");
        try {
            FormWriteGateway.AddFormResult res = gateway.addForm(project, ownerFqn, name, formType,
                    synonymRu, defaultForm, columnCount, apply);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            o.addProperty("ownerFqn", res.ownerFqn);
            if (res.ownerType != null) {
                o.addProperty("ownerType", res.ownerType);
            }
            o.addProperty("name", res.name);
            if (res.formType != null) {
                o.addProperty("formType", res.formType);
            }
            if (res.formFqn != null) {
                o.addProperty("formFqn", res.formFqn);
            }
            if (res.modulePath != null) {
                o.addProperty("modulePath", res.modulePath);
            }
            o.addProperty("nameValid", res.nameValid);
            o.addProperty("ownerFound", res.ownerFound);
            if (res.nameAvailable != null) {
                o.addProperty("nameAvailable", res.nameAvailable.booleanValue());
            }
            o.addProperty("defaultForm", res.defaultForm);
            if (!res.existingForms.isEmpty()) {
                JsonArray forms = new JsonArray();
                res.existingForms.forEach(forms::add);
                o.add("existingForms", forms);
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
            return McpServer.toolError("edt_add_form failed: " + e.getMessage());
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

    private static JsonObject intProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "integer");
        o.addProperty("description", desc);
        return o;
    }

    private static String getStr(JsonObject a, String k) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? a.get(k).getAsString() : null;
    }

    private static boolean getBool(JsonObject a, String k) {
        return a.has(k) && !a.get(k).isJsonNull() && a.get(k).getAsBoolean();
    }

    private static Integer getInt(JsonObject a, String k) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? Integer.valueOf(a.get(k).getAsInt()) : null;
    }
}
