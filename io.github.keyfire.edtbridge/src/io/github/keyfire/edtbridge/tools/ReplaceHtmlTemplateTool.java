/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.tools;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.edt.HtmlTemplateGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Replace the exact saved source of one existing HTML template language after a checked preview. */
public final class ReplaceHtmlTemplateTool {
    private final HtmlTemplateGateway gateway = new HtmlTemplateGateway();
    public String name() { return "edt_replace_html_template"; }
    public boolean isWrite() { return true; }

    public JsonObject descriptor() {
        JsonObject properties = ReadHtmlTemplateTool.identityProperties();
        properties.add("text", ReadHtmlTemplateTool.property("string", "Complete exact UTF-8 source for the selected locale. "
                + "An empty string intentionally empties the file. All whitespace, line endings and any leading BOM are taken literally; include the BOM to retain it."));
        properties.add("expectedHash", ReadHtmlTemplateTool.property("string", "sourceHash from a current read or dry-run: SHA-256 of exact UTF-8 source including any BOM. Required for apply."));
        properties.add("apply", ReadHtmlTemplateTool.property("boolean", "false (default): return exact oldText/newText and hashes without writing. true: apply with current expectedHash and force=true. Verify edt_support_status=EDITABLE first."));
        properties.add("force", ReadHtmlTemplateTool.property("boolean", "Required with apply=true. Does not bypass dirty editors, changed identities, locale mappings, encodings or stale-source checks."));
        JsonObject descriptor = new JsonObject(); descriptor.addProperty("name", name());
        descriptor.addProperty("description", "WRITE: replace complete source of one existing HTML_DOCUMENT locale, resolved through the live EDT model. "
                + "Dry-run returns exact oldText/newText, sourceHash and resultHash. Apply requires current expectedHash and force=true. "
                + "Preserves every supplied character, whitespace and line ending; a leading BOM is literal and must be included to retain it. "
                + "An empty string is allowed; omitted/null text is refused. Refuses dirty text/metadata editors, changed template identity or locale mapping, "
                + "stale or out-of-sync source, invalid Unicode and non-UTF-8 encoding. Writes only the existing locale file and rereads the result. "
                + "Does not create locales, rewrite other locales, format HTML, save metadata or change the editing language. Requires a configured write token "
                + "and caller verification of edt_support_status=EDITABLE.");
        descriptor.addProperty("descriptionRu", "ЗАПИСЬ: заменить полный текст одного существующего языка макета HTML_DOCUMENT через живую модель EDT. "
                + "Dry-run возвращает точные oldText/newText, sourceHash и resultHash без записи. Для apply нужны актуальный expectedHash и force=true. "
                + "Все переданные символы, пробелы и окончания строк сохраняются буквально; для сохранения BOM его нужно включить в text. "
                + "Пустая строка допустима, отсутствующий или null-текст дает отказ. Несохраненный редактор, смена макета или привязки языка, "
                + "устаревший источник, рассинхронизация, ошибочный Unicode и кодировка кроме UTF-8 дают отказ. Записывается только существующий файл языка, "
                + "результат перечитывается. Языки не создаются, HTML не форматируется, метаданные не сохраняются, язык редактирования не меняется. "
                + "Нужны токен записи и предварительная проверка вызывающим edt_support_status=EDITABLE.");
        descriptor.add("inputSchema", ReadHtmlTemplateTool.schema(properties, true));
        return descriptor;
    }

    public JsonObject call(JsonObject arguments) {
        try {
            String project = ReadHtmlTemplateTool.value(arguments, "projectName");
            String fqn = ReadHtmlTemplateTool.value(arguments, "templateFqn");
            String language = ReadHtmlTemplateTool.value(arguments, "language");
            String text = ReadHtmlTemplateTool.value(arguments, "text");
            if (project == null || fqn == null || language == null || text == null) {
                return McpServer.toolError("projectName, templateFqn, language and text are required; text may be an empty string");
            }
            var result = gateway.replace(project, fqn, language, text,
                    ReadHtmlTemplateTool.value(arguments, "expectedHash"), ReadHtmlTemplateTool.flag(arguments, "apply"),
                    ReadHtmlTemplateTool.flag(arguments, "force"));
            return McpServer.textResult(new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(result));
        } catch (Exception exception) {
            return McpServer.toolError("edt_replace_html_template failed: " + exception.getMessage());
        }
    }
}
