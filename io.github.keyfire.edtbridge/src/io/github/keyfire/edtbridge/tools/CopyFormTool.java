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

import io.github.keyfire.edtbridge.edt.FormCopyGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool {@code edt_copy_form}: copy a managed form, with its items, attributes, commands and module,
 * to another object of the same project - configuration or extension.
 */
public final class CopyFormTool {

    private final FormCopyGateway gateway = new FormCopyGateway();

    public String name() {
        return "edt_copy_form";
    }

    /** Write tool - the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name: the configuration or the extension that holds both "
                + "the source form and the target object."));
        props.add("sourceFormFqn", strProp("The form to copy, with an English type prefix: "
                + "<Type>.<Owner>.Form.<Name>, e.g. DataProcessor.ПодачаЗаявки.Form.Форма, or CommonForm.<Name>. "
                + "A managed form; an adopted form of an extension is refused."));
        props.add("targetOwnerFqn", strProp("The object that gets the copy, e.g. DataProcessor.Заявки, "
                + "Catalog.Контрагенты. It may be the source form's own object when the copy takes another name."));
        props.add("name", strProp("Name of the copy (a valid 1C identifier). Optional - omitted keeps the source "
                + "form's name. In an extension, a form added to an adopted object gets the extension prefix."));
        props.add("synonymRu", strProp("Russian synonym of the copy. Optional - omitted keeps the source "
                + "synonyms."));
        props.add("apply", boolProp("false (default) = dry-run: validate, copy the content in memory and return "
                + "the plan with the references that move to the target and the ones that keep pointing into "
                + "the source object; nothing is written. true = write the copy (owner .mdo gains the form, "
                + "Form.form and Module.bsl are written). Verify edt_support_status EDITABLE first."));

        JsonArray req = new JsonArray();
        req.add("projectName");
        req.add("sourceFormFqn");
        req.add("targetOwnerFqn");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE: copy a managed form - items, attributes, commands, handlers and its module - to another "
                + "object of the same project, configuration or extension, or to the same object under another "
                + "name. Into an object of the same kind (catalog to catalog, data processor to data processor), "
                + "references to the source object's types and attributes move to the target's namesakes, so "
                + "the main attribute becomes the target's object. References the target cannot take, and the "
                + "main table of a dynamic list copied to another object, are listed in the plan. Data paths, "
                + "handler names and the module text are copied as they are. Dry-run by default; apply=true "
                + "writes the copy. Additive - no force needed - but requires a configured token. To bring a "
                + "base configuration form into an extension, adopt it with edt_adopt_object instead.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ: скопировать управляемую форму - элементы, реквизиты, команды, обработчики и модуль - в "
                + "другой объект того же проекта, конфигурации или расширения, либо в тот же объект под другим "
                + "именем. При копии в объект того же вида (справочник в справочник, обработка в обработку) "
                + "ссылки на типы и реквизиты исходного объекта переходят на одноименные у целевого, и основной "
                + "реквизит становится объектом целевого. Ссылки, которым у целевого объекта нет пары, и основная "
                + "таблица динамического списка при копии в другой объект перечисляются в плане. Пути к данным, "
                + "имена обработчиков и текст модуля копируются как есть. По умолчанию dry-run; apply=true "
                + "записывает копию. Аддитивно - force не нужен - но требует токен. Чтобы перенести форму основной "
                + "конфигурации в расширение, заимствуйте ее через edt_adopt_object.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        String source = getStr(args, "sourceFormFqn");
        String target = getStr(args, "targetOwnerFqn");
        if (project == null || source == null || target == null) {
            return McpServer.toolError("projectName, sourceFormFqn and targetOwnerFqn are required");
        }
        try {
            FormCopyGateway.CopyFormResult res = gateway.copyForm(project, source, target, getStr(args, "name"),
                    getStr(args, "synonymRu"), getBool(args, "apply"));
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            o.addProperty("sourceFormFqn", res.sourceFormFqn);
            o.addProperty("targetOwnerFqn", res.targetOwnerFqn);
            if (res.name != null) o.addProperty("name", res.name);
            if (res.requestedName != null) o.addProperty("requestedName", res.requestedName);
            if (res.namePrefix != null) o.addProperty("namePrefix", res.namePrefix);
            if (res.formFqn != null) o.addProperty("formFqn", res.formFqn);
            if (res.modulePath != null) o.addProperty("modulePath", res.modulePath);
            o.addProperty("sourceFound", res.sourceFound);
            o.addProperty("targetFound", res.targetFound);
            if (res.nameAvailable != null) o.addProperty("nameAvailable", res.nameAvailable.booleanValue());
            o.addProperty("sourceModule", res.sourceModule);
            o.addProperty("remappedReferences", res.remapped);
            o.addProperty("leftoverReferences", res.leftoverCount);
            if (!res.leftovers.isEmpty()) {
                JsonArray left = new JsonArray();
                res.leftovers.forEach(left::add);
                o.add("leftovers", left);
            }
            if (!res.existingForms.isEmpty()) {
                JsonArray forms = new JsonArray();
                res.existingForms.forEach(forms::add);
                o.add("existingForms", forms);
            }
            if (res.plan != null) o.addProperty("plan", res.plan);
            if (res.warning != null) o.addProperty("warning", res.warning);
            if (res.message != null) o.addProperty("message", res.message);
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_copy_form failed: " + e.getMessage());
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

    private static boolean getBool(JsonObject a, String k) {
        return a.has(k) && !a.get(k).isJsonNull() && a.get(k).getAsBoolean();
    }
}
