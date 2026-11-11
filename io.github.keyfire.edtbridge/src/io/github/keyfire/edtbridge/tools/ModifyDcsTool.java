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

/** Add a data set field or a parameter to a data composition schema without sending the whole XML. */
public final class ModifyDcsTool {
    private final HtmlTemplateGateway gateway = new HtmlTemplateGateway();

    public String name() { return "edt_modify_dcs"; }
    public boolean isWrite(JsonObject args) { return ReadHtmlTemplateTool.flag(args, "apply"); }

    public JsonObject descriptor() {
        JsonObject properties = ReadDcsTool.identity();
        JsonObject edits = new JsonObject();
        edits.addProperty("type", "array");
        edits.addProperty("description", "Additions, applied in order. addField: dataSet, dataPath, optional field "
                + "(defaults to dataPath) and titleRu - adds a field to a data set. addParameter: name, optional titleRu "
                + "and type - String(N), Number(D,F), Date, DateTime, Boolean or StandardPeriod (Russian names "
                + "accepted; default String). A duplicate field or parameter is refused.");
        JsonObject edit = new JsonObject();
        edit.addProperty("type", "object");
        JsonObject editProperties = new JsonObject();
        JsonObject action = ReadHtmlTemplateTool.property("string", "addField or addParameter");
        JsonArray actions = new JsonArray(); actions.add("addField"); actions.add("addParameter");
        action.add("enum", actions);
        editProperties.add("action", action);
        for (String key : List.of("dataSet", "dataPath", "field", "name", "titleRu", "type")) {
            editProperties.add(key, ReadHtmlTemplateTool.property("string", key));
        }
        edit.add("properties", editProperties);
        JsonArray required = new JsonArray(); required.add("action");
        edit.add("required", required);
        edits.add("items", edit);
        properties.add("edits", edits);
        properties.add("expectedHash", ReadHtmlTemplateTool.property("string", "sourceHash from the dry-run. Required for apply."));
        properties.add("apply", ReadHtmlTemplateTool.property("boolean", "false (default): return the plan, the added "
                + "lines and sourceHash without writing. true: write; requires expectedHash and a configured token."));
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("name", name());
        descriptor.addProperty("description", "WRITE: add fields to a data set and parameters to an existing "
                + "DATA_COMPOSITION_SCHEMA template without sending the whole schema XML. The additions are inserted "
                + "into the saved designer XML, the rest of the file keeps its bytes, and the dry-run shows the changed "
                + "region. EDT validation does not check the schema XML: build the report or data processor with "
                + "edt_dump_external_object to check the result. Dry-run by default.");
        descriptor.addProperty("descriptionRu", "ЗАПИСЬ: добавить поля набора данных и параметры в существующий макет "
                + "DATA_COMPOSITION_SCHEMA, не передавая XML схемы целиком. Добавления вставляются в сохраненный XML "
                + "конфигуратора, остальная часть файла не меняется, предпросмотр показывает измененный участок. "
                + "Проверка EDT не сверяет XML схемы с форматом платформы: результат проверяет сборка отчета или "
                + "обработки (edt_dump_external_object). По умолчанию предпросмотр.");
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
                return McpServer.toolError("edits must be an array of additions");
            }
            List<DcsEdit.Operation> operations = new ArrayList<>();
            for (JsonElement element : arguments.getAsJsonArray("edits")) {
                if (!element.isJsonObject()) return McpServer.toolError("each edit must be an object");
                JsonObject edit = element.getAsJsonObject();
                operations.add(new DcsEdit.Operation(text(edit, "action"), text(edit, "dataSet"), text(edit, "dataPath"),
                        text(edit, "field"), text(edit, "name"), text(edit, "titleRu"), text(edit, "type")));
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
