/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.tools;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.edt.SpreadsheetGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** A spreadsheet template drawn to a PNG, the way the template editor shows it. */
public final class SpreadsheetRenderTool {
    private final SpreadsheetGateway gateway = new SpreadsheetGateway();
    public String name() { return "edt_spreadsheet_render"; }

    public JsonObject descriptor() {
        JsonObject props = SpreadsheetTools.identity();
        props.add("outPath", ToolJson.strProp("Absolute PNG output path. Optional - a temporary file by default."));
        return ToolJson.descriptor(name(),
                "READ: draw a SPREADSHEET_DOCUMENT template to a PNG the way the template editor shows it: column "
                + "widths, cell text, <Parameter> for a parameter, bold, alignment, borders, merged cells and the "
                + "names of the row areas on the left. Returns the PNG path and size; unsupported names what the "
                + "picture leaves out (pictures, colors, drawings). Needs no platform and no display.",
                "ЧТЕНИЕ: нарисовать макет SPREADSHEET_DOCUMENT в PNG так, как его показывает редактор макетов: "
                + "ширины колонок, текст ячеек, <Параметр> для параметра, жирность, выравнивание, рамки, "
                + "объединения и имена областей строк слева. Возвращает путь к PNG и размер; unsupported называет "
                + "то, чего на картинке нет (картинки, цвета, рисунки). Платформа и экран не нужны.",
                props, "projectName", "templateFqn");
    }

    public JsonObject call(JsonObject args) {
        try {
            var result = gateway.render(ReadHtmlTemplateTool.value(args, "projectName"),
                    ReadHtmlTemplateTool.value(args, "templateFqn"), ToolJson.getStr(args, "outPath"));
            return McpServer.textResult(new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(result));
        } catch (RuntimeException failure) {
            return McpServer.toolError("edt_spreadsheet_render failed: " + failure.getMessage());
        }
    }
}
