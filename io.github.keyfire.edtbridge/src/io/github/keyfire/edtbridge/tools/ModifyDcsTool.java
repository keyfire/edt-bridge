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

import java.util.ArrayList;
import java.util.List;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.core.DcsEdit;
import io.github.keyfire.edtbridge.edt.HtmlTemplateGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Change a part of a data composition schema - a field, a parameter, a query, a fragment - without sending the whole XML. */
public final class ModifyDcsTool {
    private final HtmlTemplateGateway gateway = new HtmlTemplateGateway();

    public String name() { return "edt_modify_dcs"; }
    public boolean isWrite(JsonObject args) { return ReadHtmlTemplateTool.flag(args, "apply"); }

    public JsonObject descriptor() {
        JsonObject properties = ReadDcsTool.identity();
        JsonObject edits = new JsonObject();
        edits.addProperty("type", "array");
        edits.addProperty("description", "Changes, applied in order. addField: dataSet, dataPath, optional field "
                + "(defaults to dataPath) and titleRu - adds a field to a data set. addParameter: name, optional titleRu "
                + "and type - String(N), Number(D,F), Date, DateTime, Boolean or StandardPeriod (Russian names "
                + "accepted; default String). setQuery: dataSet, query - the whole new query text of a query data set. "
                + "replaceFragment: fragment, replacement - an exact piece of the schema text, as edt_read_dcs returns "
                + "it, replaced; refused when it matches no place or several. setField: dataSet, dataPath, titleRu "
                + "and/or format (a format string such as ЧДЦ=2 or ДФ=dd.MM.yyyy) - sets the title or the format of a "
                + "field. removeField: dataSet, dataPath. A duplicate field or parameter is refused.");
        JsonObject edit = new JsonObject();
        edit.addProperty("type", "object");
        JsonObject editProperties = new JsonObject();
        JsonObject action = ReadHtmlTemplateTool.property("string", String.join(", ", DcsEdit.ACTIONS));
        JsonArray actions = new JsonArray();
        DcsEdit.ACTIONS.forEach(actions::add);
        action.add("enum", actions);
        editProperties.add("action", action);
        for (String key : List.of("dataSet", "dataPath", "field", "name", "titleRu", "type", "query", "fragment",
                "replacement", "format")) {
            editProperties.add(key, ReadHtmlTemplateTool.property("string", key));
        }
        edit.add("properties", editProperties);
        JsonArray required = new JsonArray(); required.add("action");
        edit.add("required", required);
        edits.add("items", edit);
        properties.add("edits", edits);
        properties.add("expectedHash", ReadHtmlTemplateTool.property("string", "sourceHash of edt_read_dcs or of the "
                + "dry-run: the schema the changes are made to. Required for apply."));
        properties.add("apply", ReadHtmlTemplateTool.property("boolean", "false (default): return the plan, the changed "
                + "lines and sourceHash without writing. true: write; requires expectedHash and a configured token."));
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("name", name());
        descriptor.addProperty("description", "WRITE: change a part of an existing DATA_COMPOSITION_SCHEMA template "
                + "without sending the whole schema XML: add a field to a data set or a parameter, set the query of a "
                + "data set, replace an exact fragment, set the title or the format of a field, remove a field. The "
                + "changes are made in the saved designer XML, the rest of the file keeps its bytes, and the answer "
                + "names the changed lines and resultHash, not the schema text. Apply directly with expectedHash = "
                + "sourceHash of edt_read_dcs, no dry-run needed. The changed schema is checked as edt_replace_dcs checks "
                + "it: a text EDT cannot read is not written, problems lists a data set without a data source and a "
                + "variant without structure; the build (edt_dump_external_object) checks the rest. Dry-run by default.");
        descriptor.addProperty("descriptionRu", "ЗАПИСЬ: изменить часть существующего макета DATA_COMPOSITION_SCHEMA, "
                + "не передавая XML схемы целиком: добавить поле набора данных или параметр, задать запрос набора, "
                + "заменить точный фрагмент, задать заголовок или формат поля, удалить поле. Изменения вносятся в "
                + "сохраненный XML конфигуратора, остальная часть файла не меняется, ответ называет измененные строки и "
                + "resultHash без текста схемы. Запись сразу: expectedHash = sourceHash из edt_read_dcs, предпросмотр не "
                + "обязателен. Измененная схема проверяется так же, как в edt_replace_dcs: текст, который EDT не прочитает, "
                + "не записывается, problems называет набор без источника и вариант без структуры; остальное проверяет "
                + "сборка (edt_dump_external_object). По умолчанию предпросмотр.");
        JsonObject schema = ReadDcsTool.schema(properties, false);
        schema.getAsJsonArray("required").add("edits");
        descriptor.add("inputSchema", schema);
        return descriptor;
    }

    public JsonObject call(JsonObject arguments) {
        try {
            String project = ReadHtmlTemplateTool.value(arguments, "projectName");
            String fqn = ReadHtmlTemplateTool.value(arguments, "templateFqn");
            if (project == null || fqn == null) return McpServer.toolError("projectName and templateFqn are required");
            if (!arguments.has("edits") || !arguments.get("edits").isJsonArray()) {
                return McpServer.toolError("edits must be an array of changes");
            }
            List<DcsEdit.Operation> operations = new ArrayList<>();
            for (JsonElement element : arguments.getAsJsonArray("edits")) {
                if (!element.isJsonObject()) return McpServer.toolError("each edit must be an object");
                JsonObject edit = element.getAsJsonObject();
                operations.add(new DcsEdit.Operation(text(edit, "action"), text(edit, "dataSet"), text(edit, "dataPath"),
                        text(edit, "field"), text(edit, "name"), text(edit, "titleRu"), text(edit, "type"),
                        text(edit, "query"), text(edit, "fragment"), text(edit, "replacement"), text(edit, "format")));
            }
            var result = gateway.editSchema(project, fqn, operations, ReadHtmlTemplateTool.value(arguments, "expectedHash"),
                    ReadHtmlTemplateTool.flag(arguments, "apply"));
            return McpServer.textResult(new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(result));
        } catch (Exception exception) {
            return McpServer.toolError("edt_modify_dcs failed: " + exception.getMessage());
        }
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
    }
}
