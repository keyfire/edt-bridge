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
 * MCP tool: edt_add_form_item - WRITE (Phase 2). Adds a visual item (field, table, button, group,
 * decoration) to a managed form through EDT's own form item management service - the one the form
 * editor calls - so naming, ids, a field's actual type and a table's columns come from EDT.
 */
public final class AddFormItemTool {

    private final FormWriteGateway gateway = new FormWriteGateway();

    public String name() {
        return "edt_add_form_item";
    }

    /** Write tool - the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = ToolJson.formMemberProps();
        props.add("kind", ToolJson.strProp("What to add: field, table, button, group or decoration"));
        props.add("name", ToolJson.strProp("Item name. Optional - omitted lets EDT pick a unique one."));
        props.add("parent", ToolJson.strProp("Container item to place it in (a group or table). "
                + "Optional - omitted puts it at the form root."));
        props.add("dataPath", ToolJson.strProp("What a field or table binds to: a form attribute, or "
                + "attribute.column for a table column, e.g. ДатаКурсов or Курсы.Курс. Required for "
                + "field and table."));
        props.add("command", ToolJson.strProp("Form command a button runs. Required for button."));
        props.add("groupType", ToolJson.strProp("Group kind: usual group, page, pages, command bar, "
                + "button group, column group, popup (Russian names accepted). Optional, defaults to a "
                + "usual group."));
        props.add("titleRu", ToolJson.strProp("Russian title. Optional."));
        props.add("apply", ToolJson.boolProp("false (default) = dry-run: validate (form resolves, parent "
                + "exists, name free, data path and command resolve) and return the plan, add nothing. "
                + "true = add the item and serialize Form.form."));
        return ToolJson.descriptor(name(),
                "WRITE (Phase 2): add a visual item to a managed form - field, table, button, group or "
                + "decoration - through EDT's own IFormItemManagementService, the service the form editor "
                + "itself calls. Naming, ids, a field's actual type and a table's auto-filled columns are "
                + "decided by EDT, not assembled here. A table bound to a value-table attribute gets its "
                + "columns filled in. Dry-run by default. Additive - no force - but requires a token.",
                "ЗАПИСЬ (Phase 2): добавить элемент управляемой формы - поле, таблицу, кнопку, группу "
                + "или декорацию - через штатный IFormItemManagementService, тот самый, который вызывает "
                + "редактор форм. Имена, идентификаторы, фактический вид поля и автозаполнение колонок "
                + "таблицы определяет EDT, а не сборка здесь. Таблица, связанная с реквизитом типа "
                + "ТаблицаЗначений, получает колонки автоматически. По умолчанию dry-run. Аддитивно - "
                + "force не нужен - но требует токен.",
                props, "projectName", "formFqn", "kind");
    }

    public JsonObject call(JsonObject args) {
        String project = ToolJson.getStr(args, "projectName");
        String formFqn = ToolJson.getStr(args, "formFqn");
        String kind = ToolJson.getStr(args, "kind");
        if (project == null || formFqn == null || kind == null) {
            return McpServer.toolError("projectName, formFqn and kind are required");
        }
        try {
            return McpServer.textResult(ToolJson.renderItem(gateway.addFormItem(project, formFqn, kind,
                    ToolJson.getStr(args, "name"), ToolJson.getStr(args, "parent"),
                    ToolJson.getStr(args, "dataPath"), ToolJson.getStr(args, "command"),
                    ToolJson.getStr(args, "groupType"), ToolJson.getStr(args, "titleRu"),
                    ToolJson.getBool(args, "apply"))));
        } catch (Exception e) {
            return McpServer.toolError("edt_add_form_item failed: " + e.getMessage());
        }
    }
}
