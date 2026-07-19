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
 * MCP tool: edt_modify_form_attribute - WRITE (Phase 2). Changes an existing form attribute's value
 * type, title or main flag. Renaming is not offered: item bindings address an attribute by name, so a
 * rename belongs to the refactoring engine, not a property edit.
 */
public final class ModifyFormAttributeTool {

    private final FormWriteGateway gateway = new FormWriteGateway();

    public String name() {
        return "edt_modify_form_attribute";
    }

    /** Write tool - the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = ToolJson.formMemberProps();
        props.add("name", ToolJson.strProp("Form attribute to change"));
        props.add("type", ToolJson.strProp("New value type, same grammar as edt_add_form_attribute. "
                + "Optional - omit to keep the current one."));
        props.add("titleRu", ToolJson.strProp("New Russian title; an empty string clears it. Optional."));
        props.add("main", ToolJson.boolProp("Whether this is the form's main attribute. Optional."));
        props.add("columnOf", ToolJson.strProp("Work on a COLUMN of this value-table form attribute instead of a form attribute, e.g. columnOf=Курсы addresses the columns of the Курсы attribute. Optional."));
        props.add("apply", ToolJson.boolProp("false (default) = dry-run: validate and return the plan, "
                + "write nothing. true = apply the change and serialize Form.form."));
        return ToolJson.descriptor(name(),
                "WRITE (Phase 2): change an existing form attribute - value type, title or main flag. "
                + "Renaming is deliberately not offered: form items address an attribute by name, so a "
                + "rename is a refactoring, not a property edit. Dry-run by default; pass at least one "
                + "of type / titleRu / main. Requires a configured token.",
                "ЗАПИСЬ (Phase 2): изменить существующий реквизит формы - тип значения, заголовок или "
                + "признак основного. Переименование намеренно не предлагается: элементы формы "
                + "адресуют реквизит по имени, поэтому переименование - это рефакторинг, а не правка "
                + "свойства. По умолчанию dry-run; задать нужно хотя бы одно из type / titleRu / main. "
                + "Требует токен.",
                props, "projectName", "formFqn", "name");
    }

    public JsonObject call(JsonObject args) {
        String project = ToolJson.getStr(args, "projectName");
        String formFqn = ToolJson.getStr(args, "formFqn");
        String name = ToolJson.getStr(args, "name");
        if (project == null || formFqn == null || name == null) {
            return McpServer.toolError("projectName, formFqn and name are required");
        }
        try {
            return McpServer.textResult(ToolJson.render(gateway.modifyFormAttribute(project, formFqn,
                    name, ToolJson.getStr(args, "type"), ToolJson.getStr(args, "titleRu"),
                    ToolJson.getBoolOrNull(args, "main"), ToolJson.getStr(args, "columnOf"),
                    ToolJson.getBool(args, "apply"))));
        } catch (Exception e) {
            return McpServer.toolError("edt_modify_form_attribute failed: " + e.getMessage());
        }
    }
}
