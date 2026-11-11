/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.tools;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.edt.HtmlTemplateGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Read the exact saved source of one data composition schema template through the live EDT model. */
public final class ReadDcsTool {
    private final HtmlTemplateGateway gateway = new HtmlTemplateGateway();
    public String name() { return "edt_read_dcs"; }
    public boolean isWrite() { return false; }

    public JsonObject descriptor() {
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("name", name());
        descriptor.addProperty("description", "Read the exact saved UTF-8 source of one existing DATA_COMPOSITION_SCHEMA "
                + "template: the designer XML of the schema (root DataCompositionSchema - data sources, data sets with "
                + "their query, fields, parameters, resources and settings variants). Resolves common and nested templates "
                + "through EDT's live model and returns path, text and sourceHash for edt_replace_dcs. Refuses an "
                + "unresolved template, another template type, dirty editors and out-of-sync files.");
        descriptor.addProperty("descriptionRu", "Прочитать точный сохраненный UTF-8-текст существующего макета "
                + "DATA_COMPOSITION_SCHEMA: XML схемы в формате конфигуратора (корень DataCompositionSchema - источники, "
                + "наборы данных с запросом, поля, параметры, ресурсы, варианты настроек). Общий или вложенный макет "
                + "определяется по живой модели EDT. Результат содержит path, text и sourceHash для edt_replace_dcs. "
                + "Ненайденный макет, другой тип макета, несохраненный редактор и рассинхронизация файла дают отказ.");
        descriptor.add("inputSchema", schema(identity(), false));
        return descriptor;
    }

    public JsonObject call(JsonObject arguments) {
        try {
            String project = ReadHtmlTemplateTool.value(arguments, "projectName");
            String fqn = ReadHtmlTemplateTool.value(arguments, "templateFqn");
            if (project == null || fqn == null) {
                return McpServer.toolError("projectName and templateFqn are required");
            }
            return McpServer.textResult(new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
                    .toJson(gateway.readSchema(project, fqn)));
        } catch (Exception exception) {
            return McpServer.toolError("edt_read_dcs failed: " + exception.getMessage());
        }
    }

    static JsonObject identity() {
        JsonObject properties = new JsonObject();
        properties.add("projectName", ReadHtmlTemplateTool.property("string", "Loaded EDT project name."));
        properties.add("templateFqn", ReadHtmlTemplateTool.property("string", "Existing schema template, e.g. "
                + "ExternalReport.Demo.Template.MainSchema or Report.Sales.Template.MainSchema."));
        return properties;
    }

    static JsonObject schema(JsonObject properties, boolean textRequired) {
        JsonArray required = new JsonArray();
        required.add("projectName");
        required.add("templateFqn");
        if (textRequired) required.add("text");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }
}
