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
 * MCP tool: edt_add_form_handler - WRITE (Phase 2). Registers an event handler on a managed form or
 * on one of its items - the {@code handlers} entry in {@code Form.form} without which the platform
 * never calls the procedure. Dry-run by default; additive, so no force.
 */
public final class AddFormHandlerTool {

    private final FormWriteGateway gateway = new FormWriteGateway();

    public String name() {
        return "edt_add_form_handler";
    }

    /** Write tool - the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = ToolJson.formMemberProps();
        props.add("event", ToolJson.strProp("Event to handle, by either name: OnCreateAtServer or "
                + "ПриСозданииНаСервере. An event the target does not allow is refused with the list "
                + "of the ones it does."));
        props.add("itemName", ToolJson.strProp("Form item the event belongs to (a field's ПриИзменении). "
                + "Optional - omitted, the event is the form's own."));
        props.add("handler", ToolJson.strProp("Handler procedure name. Optional - defaults to the "
                + "event name for a form event and to itemName + event name for an item's."));
        props.add("createHandler", ToolJson.boolProp("Also write the procedure's stub into the form "
                + "module, creating the module file when the form has none. Optional, default false. "
                + "The stub carries the signature the event declares and the directive its "
                + "environments imply."));
        props.add("apply", ToolJson.boolProp("false (default) = dry-run: validate (form, item, event, "
                + "and that the event is not handled already) and return the plan, write nothing. "
                + "true = register the handler and serialize Form.form."));
        return ToolJson.descriptor(name(),
                "WRITE (Phase 2): register an event handler on a managed form or on one of its items. "
                + "The procedure alone is not a handler: without the handlers entry in Form.form the "
                + "platform never calls it, and validation stays silent about it. The events a target "
                + "allows are asked of EDT itself, so a misspelled event is refused with the allowed "
                + "list; createHandler=true also writes the stub with the signature the event declares "
                + "and the right compilation directive. Dry-run by default. Additive - no force needed "
                + "- but requires a configured token.",
                "ЗАПИСЬ (Phase 2): зарегистрировать обработчик события управляемой формы или её "
                + "элемента. Одной процедуры мало: без записи handlers в Form.form платформа её не "
                + "вызывает, а валидация об этом молчит. Список допустимых событий берётся у самой "
                + "EDT, поэтому неверно названное событие отвергается с перечнем допустимых; "
                + "createHandler=true дополнительно пишет заготовку с сигнатурой, которую объявляет "
                + "событие, и с нужной директивой компиляции. По умолчанию dry-run. Аддитивно - force "
                + "не нужен - но требует токен.",
                props, "projectName", "formFqn", "event");
    }

    public JsonObject call(JsonObject args) {
        String project = ToolJson.getStr(args, "projectName");
        String formFqn = ToolJson.getStr(args, "formFqn");
        String event = ToolJson.getStr(args, "event");
        if (project == null || formFqn == null || event == null) {
            return McpServer.toolError("projectName, formFqn and event are required");
        }
        try {
            return McpServer.textResult(ToolJson.render(gateway.addFormHandler(project, formFqn,
                    ToolJson.getStr(args, "itemName"), event, ToolJson.getStr(args, "handler"),
                    ToolJson.getBool(args, "createHandler"), ToolJson.getBool(args, "apply"))));
        } catch (Exception e) {
            return McpServer.toolError("edt_add_form_handler failed: " + e.getMessage());
        }
    }
}
