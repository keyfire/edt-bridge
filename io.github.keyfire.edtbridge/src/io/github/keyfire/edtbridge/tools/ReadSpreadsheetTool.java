/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.tools;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.edt.SpreadsheetGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** A spreadsheet template as column widths and named row areas of cells. */
public final class ReadSpreadsheetTool {
    private final SpreadsheetGateway gateway = new SpreadsheetGateway();
    public String name() { return "edt_read_spreadsheet"; }

    public JsonObject descriptor() {
        JsonObject props = SpreadsheetTools.identity();
        return ToolJson.descriptor(name(),
                "READ: a SPREADSHEET_DOCUMENT template (a print form layout) as column widths and named row areas "
                + "of cells - text, parameter or template, with bold, font size, alignment, border, colspan and "
                + "wrap. The answer is the input of edt_write_spreadsheet, plus sourceHash for its apply; "
                + "unsupported names what the file also has and the description does not carry (pictures, colors, "
                + "drawings). See it with edt_spreadsheet_render.",
                "ЧТЕНИЕ: макет SPREADSHEET_DOCUMENT (печатная форма) как ширины колонок и именованные области строк "
                + "из ячеек – текст, параметр или шаблон, с жирностью, размером шрифта, выравниванием, рамкой, "
                + "объединением и переносом. Ответ – это вход edt_write_spreadsheet и sourceHash для записи; "
                + "unsupported называет то, что в файле есть, но в описание не входит (картинки, цвета, рисунки). "
                + "Увидеть макет – edt_spreadsheet_render.",
                props, "projectName", "templateFqn");
    }

    public JsonObject call(JsonObject args) {
        try {
            var result = gateway.read(ReadHtmlTemplateTool.value(args, "projectName"),
                    ReadHtmlTemplateTool.value(args, "templateFqn"));
            JsonObject answer = new JsonObject();
            answer.addProperty("ok", result.ok);
            answer.addProperty("templateFqn", result.templateFqn);
            answer.addProperty("path", result.path);
            answer.addProperty("sourceHash", result.sourceHash);
            if (result.layout != null) {
                JsonObject layout = SpreadsheetTools.layoutJson(result.layout);
                answer.add("columns", layout.get("columns"));
                answer.add("areas", layout.get("areas"));
                answer.add("unsupported", layout.get("unsupported"));
            }
            answer.addProperty("message", result.message);
            return McpServer.textResult(new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(answer));
        } catch (RuntimeException failure) {
            return McpServer.toolError("edt_read_spreadsheet failed: " + failure.getMessage());
        }
    }
}
