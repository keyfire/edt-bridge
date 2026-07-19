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
 * MCP tool: edt_remove_form_command - WRITE (Phase 2), DESTRUCTIVE. Removes a command from a managed
 * form. Buttons wired to it are reported first, since they are left dangling - hence the required
 * force. The handler procedure in the form module is left alone.
 */
public final class RemoveFormCommandTool {

    private final FormWriteGateway gateway = new FormWriteGateway();

    public String name() {
        return "edt_remove_form_command";
    }

    /** Write tool - the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = ToolJson.formMemberProps();
        props.add("name", ToolJson.strProp("Form command to remove"));
        props.add("force", ToolJson.boolProp("Required for apply: removing a command is destructive and "
                + "any button wired to it is left dangling."));
        props.add("apply", ToolJson.boolProp("false (default) = dry-run: report the command and the "
                + "buttons using it, remove nothing. true = remove it (force must also be true)."));
        return ToolJson.descriptor(name(),
                "WRITE (Phase 2), DESTRUCTIVE: remove a command from a managed form. The dry-run lists "
                + "the buttons wired to it - those references break when it goes. The handler procedure "
                + "in the form module is left untouched; delete it separately with edt_delete_method if "
                + "it is no longer needed. force=true required to apply; requires a configured token.",
                "ЗАПИСЬ (Phase 2), ДЕСТРУКТИВНО: удалить команду управляемой формы. Dry-run "
                + "перечисляет кнопки, связанные с ней, - эти ссылки сломаются при удалении. "
                + "Процедура-обработчик в модуле формы не трогается; если она больше не нужна, удалите "
                + "её отдельно через edt_delete_method. Для применения нужен force=true и токен.",
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
            return McpServer.textResult(ToolJson.render(gateway.removeFormCommand(project, formFqn, name,
                    ToolJson.getBool(args, "force"), ToolJson.getBool(args, "apply"))));
        } catch (Exception e) {
            return McpServer.toolError("edt_remove_form_command failed: " + e.getMessage());
        }
    }
}
