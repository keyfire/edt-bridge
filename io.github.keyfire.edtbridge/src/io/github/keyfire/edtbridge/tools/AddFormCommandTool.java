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

import io.github.keyfire.edtbridge.edt.FormCommandEdits;
import io.github.keyfire.edtbridge.edt.FormWriteGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_add_form_command - WRITE (Phase 2). Adds a command to a managed form and points its
 * action at a handler procedure in the form module, optionally writing that procedure's stub (creating
 * the module when the form has none yet). Dry-run by default; additive, so no force.
 */
public final class AddFormCommandTool {

    private final FormWriteGateway gateway = new FormWriteGateway();

    public String name() {
        return "edt_add_form_command";
    }

    /** Write tool - the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = ToolJson.formMemberProps();
        props.add("name", ToolJson.strProp("New form command name (a valid 1C identifier)"));
        props.add("titleRu", ToolJson.strProp("Russian title - the button caption. Optional."));
        props.add("toolTipRu", ToolJson.strProp("Russian tooltip. Optional."));
        props.add("handler", ToolJson.strProp("Handler procedure the command calls. Optional - defaults "
                + "to the command name."));
        props.add("createHandler", ToolJson.boolProp("Also write the handler procedure's stub into the "
                + "form module, creating the module file when the form has none. Optional, default "
                + "false - a command pointing at a missing procedure is flagged by EDT validation."));
        addCommandOptionProps(props, false);
        props.add("apply", ToolJson.boolProp("false (default) = dry-run: validate and return the plan, "
                + "write nothing. true = add the command and serialize Form.form."));
        return ToolJson.descriptor(name(),
                "WRITE (Phase 2): add a command to a managed form. The id comes from EDT's own form "
                + "identifier service and the action is wired to a handler procedure in the form module; "
                + "createHandler=true also writes that procedure's stub, creating the module file when "
                + "the form has none. Accepts optional actionPurpose, associatedTable, currentRowUse and "
                + "selectedRowsUse. Note the command still needs a button to be reachable in the UI. "
                + "Dry-run by default. Additive - no force needed - but requires a configured token.",
                "ЗАПИСЬ (Phase 2): добавить команду управляемой формы. Идентификатор выдает штатная "
                + "служба идентификаторов формы, действие связывается с процедурой-обработчиком модуля "
                + "формы; createHandler=true дополнительно пишет заготовку этой процедуры, создавая "
                + "файл модуля, если его у формы еще нет. Поддерживаются необязательные actionPurpose, "
                + "associatedTable, currentRowUse и selectedRowsUse. Учтите: чтобы команда была доступна в "
                + "интерфейсе, ей нужна кнопка. По умолчанию dry-run. Аддитивно - force не нужен - но "
                + "требует токен.",
                props, "projectName", "formFqn", "name");
    }

    static void addCommandOptionProps(JsonObject props, boolean allowClearTable) {
        JsonObject purpose = ToolJson.strProp("Command action purpose (Version 8.5): Usual (alias Normal), "
                + "Create or Finish. Optional.");
        JsonArray purposes = new JsonArray();
        purposes.add("Usual"); purposes.add("Normal"); purposes.add("Create"); purposes.add("Finish");
        purpose.add("enum", purposes);
        props.add("actionPurpose", purpose);
        props.add("associatedTable", ToolJson.strProp("Name of the form Table item associated with this command"
                + (allowClearTable ? "; an empty string clears it" : "") + ". Optional."));
        JsonObject rowUse = ToolJson.strProp("Current-row usage of a table command: Use, DontUse or Auto. Optional.");
        JsonArray rowModes = new JsonArray();
        rowModes.add("Use"); rowModes.add("DontUse"); rowModes.add("Auto");
        rowUse.add("enum", rowModes);
        props.add("currentRowUse", rowUse);
        JsonObject selectedUse = ToolJson.strProp("Selected-rows usage of a table command: Use, DontUse or Auto. Optional.");
        selectedUse.add("enum", rowModes.deepCopy());
        props.add("selectedRowsUse", selectedUse);
    }

    static FormCommandEdits.Options commandOptions(JsonObject args) {
        return new FormCommandEdits.Options(
                ToolJson.getStr(args, "actionPurpose"),
                ToolJson.getStr(args, "associatedTable"),
                ToolJson.getStr(args, "currentRowUse"),
                ToolJson.getStr(args, "selectedRowsUse"));
    }

    public JsonObject call(JsonObject args) {
        String project = ToolJson.getStr(args, "projectName");
        String formFqn = ToolJson.getStr(args, "formFqn");
        String name = ToolJson.getStr(args, "name");
        if (project == null || formFqn == null || name == null) {
            return McpServer.toolError("projectName, formFqn and name are required");
        }
        try {
            return McpServer.textResult(ToolJson.render(gateway.addFormCommand(project, formFqn, name,
                    ToolJson.getStr(args, "titleRu"), ToolJson.getStr(args, "toolTipRu"),
                    ToolJson.getStr(args, "handler"), ToolJson.getBool(args, "createHandler"),
                    commandOptions(args), ToolJson.getBool(args, "apply"))));
        } catch (Exception e) {
            return McpServer.toolError("edt_add_form_command failed: " + e.getMessage());
        }
    }
}
