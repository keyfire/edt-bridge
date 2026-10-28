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

import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.edt.FormWriteGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Move an existing managed-form item through EDT's reference-aware movement service. */
public final class MoveFormItemTool {
    private final FormWriteGateway gateway = new FormWriteGateway();
    public String name() { return "edt_move_form_item"; }
    public boolean isWrite() { return true; }
    public JsonObject descriptor() {
        JsonObject props = ToolJson.formMemberProps();
        props.add("name", ToolJson.strProp("Existing form item to move. Its name must resolve uniquely."));
        FormItemToolOptions.positionProps(props, true);
        props.add("apply", ToolJson.boolProp("false (default) validates and returns the destination without writing. true moves the existing item and serializes Form.form."));
        return ToolJson.descriptor(name(),
                "WRITE: move a managed-form item by index/beforeItem/afterItem. Position selectors are exclusive. Preserves the item identity, id, children, events and properties. Cycles, invalid anchors and moves requiring item-kind/data-binding conversion are refused. Omitted parent keeps the current parent; an empty string selects the form root. Dry-run by default.",
                "ЗАПИСЬ: перенести элемент управляемой формы по index/beforeItem/afterItem. Можно задать только один способ размещения. Сохраняет объект, id, вложенные элементы, события и свойства. Циклы, неверные соседи и переносы с преобразованием вида элемента или привязки к данным отклоняются. Без parent сохраняется текущий родитель; пустая строка выбирает корень формы. По умолчанию dry-run.",
                props, "projectName", "formFqn", "name");
    }
    public JsonObject call(JsonObject args) {
        String project = ToolJson.getStr(args, "projectName"), form = ToolJson.getStr(args, "formFqn"), name = ToolJson.getStr(args, "name");
        if (project == null || form == null || name == null) { return McpServer.toolError("projectName, formFqn and name are required"); }
        try {
            return McpServer.textResult(ToolJson.renderItem(gateway.moveFormItem(project, form, name,
                    ToolJson.getStr(args, "parent"), FormItemToolOptions.index(args), ToolJson.getStr(args, "beforeItem"),
                    ToolJson.getStr(args, "afterItem"), ToolJson.getBool(args, "apply"))));
        } catch (Exception invalid) { return McpServer.toolError("edt_move_form_item failed: " + invalid.getMessage()); }
    }
}
