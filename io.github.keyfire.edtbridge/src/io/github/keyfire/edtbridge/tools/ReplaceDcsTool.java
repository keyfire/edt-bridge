/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.tools;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.edt.HtmlTemplateGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Replace the complete source of one data composition schema template, exactly as supplied. */
public final class ReplaceDcsTool {
    private final HtmlTemplateGateway gateway = new HtmlTemplateGateway();
    public String name() { return "edt_replace_dcs"; }
    public boolean isWrite() { return true; }

    public JsonObject descriptor() {
        JsonObject properties = ReadDcsTool.identity();
        properties.add("text", ReadHtmlTemplateTool.property("string", "Complete designer XML of the schema "
                + "(root DataCompositionSchema in the http://v8.1c.ru/8.1/data-composition-system/schema namespace), "
                + "taken literally. Start from edt_read_dcs and edit it."));
        properties.add("expectedHash", ReadHtmlTemplateTool.property("string", "sourceHash from a current "
                + "edt_read_dcs or dry-run. Required for apply."));
        properties.add("apply", ReadHtmlTemplateTool.property("boolean", "false (default): return oldText/newText "
                + "and hashes without writing. true: write with the current expectedHash and force=true."));
        properties.add("force", ReadHtmlTemplateTool.property("boolean", "Required with apply=true. Does not bypass "
                + "dirty editors, a changed template or stale-source checks."));
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("name", name());
        descriptor.addProperty("description", "WRITE: replace the complete source of one existing DATA_COMPOSITION_SCHEMA "
                + "template with designer XML of the schema; EDT reloads the schema from the file. Dry-run returns "
                + "oldText/newText, sourceHash and resultHash. Apply requires the current expectedHash and force=true, "
                + "writes only the schema file and rereads it. Run edt_project_errors afterwards: a malformed schema "
                + "shows up there. Create the template with edt_add_template. Requires a configured write token.");
        descriptor.addProperty("descriptionRu", "ЗАПИСЬ: заменить полный текст существующего макета "
                + "DATA_COMPOSITION_SCHEMA на XML схемы в формате конфигуратора; EDT перечитывает схему из файла. "
                + "Dry-run возвращает oldText/newText, sourceHash и resultHash. Для apply нужны актуальный expectedHash и "
                + "force=true; записывается только файл схемы, результат перечитывается. После записи запустить "
                + "edt_project_errors: ошибки схемы видны там. Макет создает edt_add_template. Нужен токен записи.");
        descriptor.add("inputSchema", ReadDcsTool.schema(properties, true));
        return descriptor;
    }

    public JsonObject call(JsonObject arguments) {
        try {
            String project = ReadHtmlTemplateTool.value(arguments, "projectName");
            String fqn = ReadHtmlTemplateTool.value(arguments, "templateFqn");
            String text = ReadHtmlTemplateTool.value(arguments, "text");
            if (project == null || fqn == null || text == null) {
                return McpServer.toolError("projectName, templateFqn and text are required");
            }
            var result = gateway.replaceSchema(project, fqn, text, ReadHtmlTemplateTool.value(arguments, "expectedHash"),
                    ReadHtmlTemplateTool.flag(arguments, "apply"), ReadHtmlTemplateTool.flag(arguments, "force"));
            return McpServer.textResult(new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(result));
        } catch (Exception exception) {
            return McpServer.toolError("edt_replace_dcs failed: " + exception.getMessage());
        }
    }
}
