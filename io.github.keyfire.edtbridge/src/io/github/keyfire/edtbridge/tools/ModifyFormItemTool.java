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
 * MCP tool: edt_modify_form_item - WRITE (Phase 2). Changes a form item's title, visibility or enabled
 * state. These are the design-time values; runtime BSL may still override them.
 */
public final class ModifyFormItemTool {

    private final FormWriteGateway gateway = new FormWriteGateway();

    public String name() {
        return "edt_modify_form_item";
    }

    /** Write tool - the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = ToolJson.formMemberProps();
        props.add("name", ToolJson.strProp("Form item to change"));
        props.add("titleRu", ToolJson.strProp("New Russian title; an empty string clears it. Optional."));
        props.add("visible", ToolJson.boolProp("Design-time visibility. Optional."));
        props.add("enabled", ToolJson.boolProp("Design-time enabled state. Optional."));
        props.add("apply", ToolJson.boolProp("false (default) = dry-run: validate and return the plan, "
                + "write nothing. true = apply the change and serialize Form.form."));
        return ToolJson.descriptor(name(),
                "WRITE (Phase 2): change a form item's title, visibility or enabled state. These are the "
                + "DESIGN values stored in Form.form - runtime BSL (ПриСозданииНаСервере and friends) can "
                + "still override them. Dry-run by default; pass at least one of titleRu / visible / "
                + "enabled. Requires a configured token.",
                "ЗАПИСЬ (Phase 2): изменить у элемента формы заголовок, видимость или доступность. Это "
                + "ПРОЕКТНЫЕ значения, хранящиеся в Form.form, - код (ПриСозданииНаСервере и подобные) "
                + "может переопределить их в рантайме. По умолчанию dry-run; задать нужно хотя бы одно "
                + "из titleRu / visible / enabled. Требует токен.",
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
            return McpServer.textResult(ToolJson.renderItem(gateway.modifyFormItem(project, formFqn, name,
                    ToolJson.getStr(args, "titleRu"), ToolJson.getBoolOrNull(args, "visible"),
                    ToolJson.getBoolOrNull(args, "enabled"), ToolJson.getBool(args, "apply"))));
        } catch (Exception e) {
            return McpServer.toolError("edt_modify_form_item failed: " + e.getMessage());
        }
    }
}
