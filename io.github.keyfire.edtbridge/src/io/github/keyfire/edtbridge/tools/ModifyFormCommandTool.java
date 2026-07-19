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
 * MCP tool: edt_modify_form_command - WRITE (Phase 2). Changes an existing form command's title,
 * tooltip or the handler procedure its action points at. The procedure itself is not renamed - the
 * command is only re-pointed.
 */
public final class ModifyFormCommandTool {

    private final FormWriteGateway gateway = new FormWriteGateway();

    public String name() {
        return "edt_modify_form_command";
    }

    /** Write tool - the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = ToolJson.formMemberProps();
        props.add("name", ToolJson.strProp("Form command to change"));
        props.add("titleRu", ToolJson.strProp("New Russian title; an empty string clears it. Optional."));
        props.add("toolTipRu", ToolJson.strProp("New Russian tooltip; an empty string clears it. "
                + "Optional."));
        props.add("handler", ToolJson.strProp("Handler procedure the command should call. Optional. The "
                + "procedure itself is not renamed - point it at one that exists in the form module."));
        props.add("apply", ToolJson.boolProp("false (default) = dry-run: validate and return the plan, "
                + "write nothing. true = apply the change and serialize Form.form."));
        return ToolJson.descriptor(name(),
                "WRITE (Phase 2): change an existing form command - title, tooltip or the handler "
                + "procedure its action points at. Re-pointing the handler does not rename the procedure: "
                + "aim it at one that exists in the form module, or add it with edt_add_method. Dry-run "
                + "by default; pass at least one of titleRu / toolTipRu / handler. Requires a token.",
                "ЗАПИСЬ (Phase 2): изменить существующую команду формы - заголовок, подсказку или "
                + "процедуру-обработчик, на которую указывает действие. Перенацеливание обработчика не "
                + "переименовывает процедуру: указывайте на существующую в модуле формы либо добавьте "
                + "её через edt_add_method. По умолчанию dry-run; задать нужно хотя бы одно из "
                + "titleRu / toolTipRu / handler. Требует токен.",
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
            return McpServer.textResult(ToolJson.render(gateway.modifyFormCommand(project, formFqn, name,
                    ToolJson.getStr(args, "titleRu"), ToolJson.getStr(args, "toolTipRu"),
                    ToolJson.getStr(args, "handler"), ToolJson.getBool(args, "apply"))));
        } catch (Exception e) {
            return McpServer.toolError("edt_modify_form_command failed: " + e.getMessage());
        }
    }
}
