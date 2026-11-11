/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.tools;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.edt.SpreadsheetGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Write a spreadsheet template from column widths and named row areas of cells. */
public final class WriteSpreadsheetTool {
    private final SpreadsheetGateway gateway = new SpreadsheetGateway();
    public String name() { return "edt_write_spreadsheet"; }
    public boolean isWrite(JsonObject args) { return ReadHtmlTemplateTool.flag(args, "apply"); }

    public JsonObject descriptor() {
        JsonObject props = SpreadsheetTools.identity();
        JsonObject layout = SpreadsheetTools.layoutSchema();
        for (String key : layout.keySet()) props.add(key, layout.get(key));
        props.add("dropUnsupported", ToolJson.boolProp("true lets the write drop what the current file has and the "
                + "description does not carry (edt_read_spreadsheet lists it in unsupported). Default false: refused."));
        props.add("expectedHash", ToolJson.strProp("sourceHash of the dry-run. Required for apply."));
        props.add("apply", ToolJson.boolProp("false (default): build the file and show the change and sourceHash. "
                + "true: write; requires expectedHash and a configured token."));
        JsonObject descriptor = ToolJson.descriptor(name(),
                "WRITE: replace the whole content of a SPREADSHEET_DOCUMENT template (a print form layout) with "
                + "column widths and named row areas of cells. Start from edt_read_spreadsheet, change the areas "
                + "and pass them back; create the template with edt_add_template templateType=SpreadsheetDocument. "
                + "Fill it in code: Макет.ПолучитьОбласть(\"Name\"), Область.Параметры.Name = ..., "
                + "ТабличныйДокумент.Вывести(Область). Dry-run by default; check the result with "
                + "edt_spreadsheet_render and by building the object.",
                "ЗАПИСЬ: заменить все содержимое макета SPREADSHEET_DOCUMENT (печатная форма) ширинами колонок и "
                + "именованными областями строк из ячеек. Начинать с edt_read_spreadsheet, менять области и "
                + "передавать их обратно; макет создает edt_add_template с templateType=SpreadsheetDocument. "
                + "Заполнение в коде: Макет.ПолучитьОбласть(\"Имя\"), Область.Параметры.Имя = ..., "
                + "ТабличныйДокумент.Вывести(Область). По умолчанию предпросмотр; результат проверяют "
                + "edt_spreadsheet_render и сборка объекта.",
                props, "projectName", "templateFqn");
        descriptor.getAsJsonObject("inputSchema").getAsJsonArray("required").add("areas");
        return descriptor;
    }

    public JsonObject call(JsonObject args) {
        try {
            var result = gateway.write(ReadHtmlTemplateTool.value(args, "projectName"),
                    ReadHtmlTemplateTool.value(args, "templateFqn"), SpreadsheetTools.layout(args),
                    ReadHtmlTemplateTool.value(args, "expectedHash"), ReadHtmlTemplateTool.flag(args, "dropUnsupported"),
                    ReadHtmlTemplateTool.flag(args, "apply"));
            return McpServer.textResult(new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(result));
        } catch (RuntimeException failure) {
            return McpServer.toolError("edt_write_spreadsheet failed: " + failure.getMessage());
        }
    }
}
