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
 * MCP tool: edt_add_attribute - WRITE (Phase 2). Adds an attribute to a metadata object.
 * Dry-run by default (apply=false): validates the planned change (owner resolvable, name free, type
 * parses, reference target exists) and returns the plan without writing. apply=true performs the real
 * write — builds the TypeDescription, creates the attribute and commits. Requires a configured token
 * (write tool, owner decision); the caller must verify bsl_support_status EDITABLE before apply.
 */
public final class AddAttributeTool {

    private final EdtModelGateway gateway = new EdtModelGateway();

    public String name() {
        return "edt_add_attribute";
    }

    /** Write tool — the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name"));
        props.add("ownerFqn", strProp("Object to add the attribute to, e.g. Catalog.Контрагенты, Document.Заказ"));
        props.add("name", strProp("New attribute name (Cyrillic), e.g. Комментарий"));
        props.add("type", strProp("Value type: Строка(150), Число(15,2), Булево, Дата/ДатаВремя/Время, "
                + "ХранилищеЗначения, ОпределяемыйТип.X, or a reference <kind>Ссылка.X, where <kind> is "
                + "Справочник, Документ, Перечисление, ПланВидовХарактеристик, ПланСчетов, ПланВидовРасчета, "
                + "БизнесПроцесс, Задача, ПланОбмена. Composite type: comma-separate parts, e.g. "
                + "\"СправочникСсылка.A, СправочникСсылка.B\" or \"Строка(10), Число(5)\""));
        props.add("synonymRu", strProp("Russian synonym (optional)"));
        props.add("comment", strProp("Comment (optional)"));
        props.add("apply", boolProp("false (default) = dry-run: validate + return the plan, write nothing. "
                + "true = perform the write (create the attribute and commit). Verify bsl_support_status "
                + "EDITABLE first."));

        JsonArray req = new JsonArray();
        req.add("projectName");
        req.add("ownerFqn");
        req.add("name");
        req.add("type");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE (Phase 2): add an attribute to a metadata object. Dry-run by default — validates "
                + "(owner exists, name free, type parses, reference target exists) and returns the plan "
                + "WITHOUT writing. apply=true performs the write (creates the attribute and commits). "
                + "Requires a configured token. Caller must verify bsl_support_status EDITABLE before "
                + "any apply.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ (Phase 2): добавить реквизит объекту метаданных. По умолчанию dry-run — проверяет "
                + "(объект есть, имя свободно, тип распознан, ссылочный тип существует) и возвращает план "
                + "БЕЗ записи. apply=true – реальная запись (создаёт реквизит и фиксирует). Требует токен. "
                + "Перед apply вызывающий обязан проверить bsl_support_status = EDITABLE.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        String ownerFqn = getStr(args, "ownerFqn");
        String name = getStr(args, "name");
        String type = getStr(args, "type");
        if (project == null || ownerFqn == null || name == null || type == null) {
            return McpServer.toolError("projectName, ownerFqn, name and type are required");
        }
        String synonymRu = getStr(args, "synonymRu");
        String comment = getStr(args, "comment");
        boolean apply = args.has("apply") && !args.get("apply").isJsonNull() && args.get("apply").getAsBoolean();
        try {
            EdtModelGateway.AddAttrResult res = gateway.addAttribute(project, ownerFqn, name, type, synonymRu, comment, apply);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            if (res.applyPending) {
                o.addProperty("applyPending", true);
            }
            o.addProperty("ownerFqn", res.ownerFqn);
            o.addProperty("ownerFound", res.ownerFound);
            if (res.ownerType != null) {
                o.addProperty("ownerType", res.ownerType);
            }
            o.addProperty("name", res.name);
            if (res.nameAvailable != null) {
                o.addProperty("nameAvailable", res.nameAvailable.booleanValue());
            }
            o.addProperty("type", res.typeInput);
            o.addProperty("typeValid", res.typeValid);
            if (res.typeParsed != null) {
                o.addProperty("typeParsed", res.typeParsed);
            }
            if (res.refFqn != null) {
                o.addProperty("refFqn", res.refFqn);
                o.addProperty("refResolved", Boolean.TRUE.equals(res.refResolved));
            }
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_add_attribute failed: " + e.getMessage());
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
