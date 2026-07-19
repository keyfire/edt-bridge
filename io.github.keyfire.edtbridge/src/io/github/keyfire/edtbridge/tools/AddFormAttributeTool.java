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
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_add_form_attribute - WRITE (Phase 2). Adds an attribute to a managed form, with the
 * id allocated by EDT's own form identifier service and the value type parsed by the same grammar
 * {@code edt_add_attribute} uses. Dry-run by default; additive, so no force.
 */
public final class AddFormAttributeTool {

    private final FormWriteGateway gateway = new FormWriteGateway();

    public String name() {
        return "edt_add_form_attribute";
    }

    /** Write tool - the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = ToolJson.formMemberProps();
        props.add("name", ToolJson.strProp("New form attribute name (a valid 1C identifier)"));
        props.add("type", ToolJson.strProp("Value type: Строка(150), Число(15, 2), Булево, Дата, "
                + "ТаблицаЗначений, or a reference such as СправочникСсылка.Контрагенты. "
                + "Comma-separate for a composite type."));
        props.add("titleRu", ToolJson.strProp("Russian title. Optional."));
        props.add("columnOf", ToolJson.strProp("Work on a COLUMN of this value-table form attribute instead of a form attribute, e.g. columnOf=Курсы addresses the columns of the Курсы attribute. Optional."));
        props.add("apply", ToolJson.boolProp("false (default) = dry-run: validate (form resolves, name "
                + "free, type parses) and return the plan, write nothing. true = add the attribute and "
                + "serialize Form.form."));
        return ToolJson.descriptor(name(),
                "WRITE (Phase 2): add an attribute to a managed form. The id comes from EDT's own form "
                + "identifier service and the type from the same grammar edt_add_attribute uses, so the "
                + "attribute is indistinguishable from one added in the form editor. Dry-run by default: "
                + "validates and returns the plan WITHOUT writing. apply=true adds it and serializes "
                + "Form.form. Additive - no force needed - but requires a configured token.",
                "ЗАПИСЬ (Phase 2): добавить реквизит управляемой формы. Идентификатор выдаёт штатная "
                + "служба идентификаторов формы, тип разбирается той же грамматикой, что у "
                + "edt_add_attribute, поэтому реквизит неотличим от добавленного в редакторе формы. "
                + "По умолчанию dry-run: проверяет и возвращает план БЕЗ записи. apply=true добавляет "
                + "реквизит и сериализует Form.form. Аддитивно - force не нужен - но требует токен.",
                props, "projectName", "formFqn", "name", "type");
    }

    public JsonObject call(JsonObject args) {
        String project = ToolJson.getStr(args, "projectName");
        String formFqn = ToolJson.getStr(args, "formFqn");
        String name = ToolJson.getStr(args, "name");
        String type = ToolJson.getStr(args, "type");
        if (project == null || formFqn == null || name == null || type == null) {
            return McpServer.toolError("projectName, formFqn, name and type are required");
        }
        try {
            return McpServer.textResult(ToolJson.render(gateway.addFormAttribute(project, formFqn, name,
                    type, ToolJson.getStr(args, "titleRu"), ToolJson.getStr(args, "columnOf"),
                    ToolJson.getBool(args, "apply"))));
        } catch (Exception e) {
            return McpServer.toolError("edt_add_form_attribute failed: " + e.getMessage());
        }
    }
}
