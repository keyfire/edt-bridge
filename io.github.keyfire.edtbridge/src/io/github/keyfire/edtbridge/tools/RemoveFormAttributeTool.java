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
 * MCP tool: edt_remove_form_attribute - WRITE (Phase 2), DESTRUCTIVE. Removes an attribute from a
 * managed form. Items whose data path starts at that attribute are reported first, since dropping it
 * leaves them dangling - hence the required force.
 */
public final class RemoveFormAttributeTool {

    private final FormWriteGateway gateway = new FormWriteGateway();

    public String name() {
        return "edt_remove_form_attribute";
    }

    /** Write tool - the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = ToolJson.formMemberProps();
        props.add("name", ToolJson.strProp("Form attribute to remove"));
        props.add("force", ToolJson.boolProp("Required for apply: removing an attribute is destructive "
                + "and any item bound to it is left dangling."));
        props.add("columnOf", ToolJson.strProp("Work on a COLUMN of this value-table form attribute instead of a form attribute, e.g. columnOf=Курсы addresses the columns of the Курсы attribute. Optional."));
        props.add("apply", ToolJson.boolProp("false (default) = dry-run: report the attribute and the "
                + "items bound to it, remove nothing. true = remove it (force must also be true)."));
        return ToolJson.descriptor(name(),
                "WRITE (Phase 2), DESTRUCTIVE: remove an attribute from a managed form. The dry-run "
                + "lists every form item whose data path starts at that attribute - those bindings break "
                + "when it goes. force=true required to apply; requires a configured token.",
                "ЗАПИСЬ (Phase 2), ДЕСТРУКТИВНО: удалить реквизит управляемой формы. Dry-run "
                + "перечисляет все элементы формы, чей путь к данным начинается с этого реквизита, - "
                + "их связи сломаются при удалении. Для применения нужен force=true и токен.",
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
            return McpServer.textResult(ToolJson.render(gateway.removeFormAttribute(project, formFqn,
                    name, ToolJson.getStr(args, "columnOf"), ToolJson.getBool(args, "force"),
                    ToolJson.getBool(args, "apply"))));
        } catch (Exception e) {
            return McpServer.toolError("edt_remove_form_attribute failed: " + e.getMessage());
        }
    }
}
