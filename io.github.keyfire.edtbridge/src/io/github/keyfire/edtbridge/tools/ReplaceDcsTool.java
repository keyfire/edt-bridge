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
                + "(root DataCompositionSchema, as in a designer dump), taken literally. Start from edt_read_dcs "
                + "and edit it."));
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
                + "writes only the schema file and rereads it. EDT validation does not check the schema XML against the "
                + "platform format: build the report or data processor with edt_dump_external_object to check it. "
                + "To add a field or a parameter, edt_modify_dcs takes just the addition. Create the template with "
                + "edt_add_template; when its schema file is not on disk yet, the call waits for it up to 15 s. "
                + "Requires a configured write token.");
        descriptor.addProperty("descriptionRu", "ЗАПИСЬ: заменить полный текст существующего макета "
                + "DATA_COMPOSITION_SCHEMA на XML схемы в формате конфигуратора; EDT перечитывает схему из файла. "
                + "Dry-run возвращает oldText/newText, sourceHash и resultHash. Для apply нужны актуальный expectedHash и "
                + "force=true; записывается только файл схемы, результат перечитывается. Проверка EDT не сверяет XML "
                + "схемы с форматом платформы: результат проверяет сборка отчета или обработки "
                + "(edt_dump_external_object). Поле или параметр добавляет edt_modify_dcs без передачи всей схемы. "
                + "Макет создает edt_add_template; если файла схемы еще нет на диске, вызов ждет его до 15 с. "
                + "Нужен токен записи.");
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
