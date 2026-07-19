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
 * MCP tool: edt_remove_form_item - WRITE (Phase 2), DESTRUCTIVE. Removes a form item and everything
 * nested inside it - a group takes its contents, a table its columns.
 */
public final class RemoveFormItemTool {

    private final FormWriteGateway gateway = new FormWriteGateway();

    public String name() {
        return "edt_remove_form_item";
    }

    /** Write tool - the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = ToolJson.formMemberProps();
        props.add("name", ToolJson.strProp("Form item to remove"));
        props.add("force", ToolJson.boolProp("Required for apply: removing an item is destructive and "
                + "takes everything nested inside it."));
        props.add("apply", ToolJson.boolProp("false (default) = dry-run: report the item and what is "
                + "nested inside it, remove nothing. true = remove it (force must also be true)."));
        return ToolJson.descriptor(name(),
                "WRITE (Phase 2), DESTRUCTIVE: remove an item from a managed form. Everything nested "
                + "inside goes with it - a group takes its contents, a table its columns - and the "
                + "dry-run lists exactly what that is. The form attribute or command the item was bound "
                + "to is left alone. force=true required to apply; requires a configured token.",
                "ЗАПИСЬ (Phase 2), ДЕСТРУКТИВНО: удалить элемент управляемой формы. Всё вложенное "
                + "удаляется вместе с ним - группа забирает содержимое, таблица свои колонки, - и "
                + "dry-run показывает, что именно. Реквизит или команда, с которыми элемент был связан, "
                + "не трогаются. Для применения нужен force=true и токен.",
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
            return McpServer.textResult(ToolJson.renderItem(gateway.removeFormItem(project, formFqn, name,
                    ToolJson.getBool(args, "force"), ToolJson.getBool(args, "apply"))));
        } catch (Exception e) {
            return McpServer.toolError("edt_remove_form_item failed: " + e.getMessage());
        }
    }
}
