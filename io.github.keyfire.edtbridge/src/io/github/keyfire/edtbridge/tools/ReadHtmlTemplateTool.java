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

/** Read one explicitly selected existing HTML template locale through the live EDT model. */
public final class ReadHtmlTemplateTool {
    private final HtmlTemplateGateway gateway = new HtmlTemplateGateway();
    public String name() { return "edt_read_html_template"; }
    public boolean isWrite() { return false; }

    public JsonObject descriptor() {
        JsonObject properties = identityProperties();
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("name", name());
        descriptor.addProperty("description", "Read exact saved UTF-8 source of one existing HTML_DOCUMENT template locale. "
                + "Resolves common and nested templates through EDT's live model, returning availableLanguages, path, text and sourceHash. "
                + "The SHA-256 hash includes any BOM and original line endings. language must exactly match an existing locale. "
                + "Refuses unsupported encodings, unresolved/missing templates, dirty text or metadata editors and out-of-sync resources. "
                + "Does not create a locale, switch the editing language or save editors.");
        descriptor.addProperty("descriptionRu", "Прочитать точный сохраненный UTF-8-текст одного языка существующего макета HTML_DOCUMENT. "
                + "Общий или вложенный макет определяется по живой модели EDT. Результат содержит availableLanguages, path, text и sourceHash. "
                + "Хеш SHA-256 учитывает BOM и исходные окончания строк. language должен точно совпадать с существующим языком. "
                + "Неподдерживаемая кодировка, отсутствующий макет, несохраненный текст или метаданные и рассинхронизация файла дают отказ. "
                + "Язык не создается и не переключается, редакторы не сохраняются.");
        descriptor.add("inputSchema", schema(properties, false));
        return descriptor;
    }

    public JsonObject call(JsonObject arguments) {
        try {
            String project = value(arguments, "projectName"), fqn = value(arguments, "templateFqn");
            String language = value(arguments, "language");
            if (project == null || fqn == null || language == null) {
                return McpServer.toolError("projectName, templateFqn and language are required");
            }
            return McpServer.textResult(new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
                    .toJson(gateway.read(project, fqn, language)));
        } catch (Exception exception) {
            return McpServer.toolError("edt_read_html_template failed: " + exception.getMessage());
        }
    }

    static JsonObject identityProperties() {
        JsonObject properties = new JsonObject();
        properties.add("projectName", property("string", "Loaded EDT project name."));
        properties.add("templateFqn", property("string", "Existing CommonTemplate.Name or nested owner FQN, e.g. DataProcessor.Demo.Template.Notice."));
        properties.add("language", property("string", "Exact existing page language code, e.g. ru or en. No fallback or locale creation."));
        return properties;
    }

    static JsonObject schema(JsonObject properties, boolean textRequired) {
        JsonArray required = new JsonArray();
        required.add("projectName"); required.add("templateFqn"); required.add("language");
        if (textRequired) required.add("text");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object"); schema.add("properties", properties); schema.add("required", required);
        return schema;
    }

    static JsonObject property(String type, String description) {
        JsonObject property = new JsonObject(); property.addProperty("type", type);
        property.addProperty("description", description); return property;
    }

    static String value(JsonObject arguments, String name) {
        if (!arguments.has(name) || arguments.get(name).isJsonNull()) return null;
        var value = arguments.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return value.getAsString();
    }

    static boolean flag(JsonObject arguments, String name) {
        if (!arguments.has(name) || arguments.get(name).isJsonNull()) return false;
        var value = arguments.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return value.getAsBoolean();
    }
}
