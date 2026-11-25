/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.tools;

import static io.github.keyfire.edtbridge.tools.ToolJson.boolProp;
import static io.github.keyfire.edtbridge.tools.ToolJson.getBool;
import static io.github.keyfire.edtbridge.tools.ToolJson.getStr;
import static io.github.keyfire.edtbridge.tools.ToolJson.strProp;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.edt.TemplateWriteGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** MCP tool: edt_add_template - WRITE. Create a data composition schema template on an object. */
public final class AddTemplateTool {
    private final TemplateWriteGateway gateway = new TemplateWriteGateway();
    public String name() { return "edt_add_template"; }
    public boolean isWrite() { return true; }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("Project that owns the object"));
        props.add("ownerFqn", strProp("Object that receives the template, e.g. ExternalReport.Demo or Report.Sales"));
        props.add("name", strProp("Template name - a 1C identifier, e.g. MainSchema"));
        props.add("templateType", strProp("DataCompositionSchema (default; alias СхемаКомпоновкиДанных), "
                + "SpreadsheetDocument (alias ТабличныйДокумент) - the layout of a print form, HTMLDocument (alias "
                + "HTMLДокумент) - one page in the language EDT edits the project in, or TextDocument (alias "
                + "ТекстовыйДокумент) - Template.txt. Other template types are not created by this tool yet."));
        props.add("text", strProp("Starting text of an HTML page or of a text template, written exactly as given; "
                + "empty when omitted. Refused for a schema or a spreadsheet."));
        props.add("setMainSchema", boolProp("true makes the new schema the report's main data composition "
                + "schema. A report with a main schema and no main form opens with the configuration's common "
                + "report form."));
        props.add("synonymRu", strProp("Optional Russian synonym"));
        props.add("apply", boolProp("false (default) = dry-run: validate and return the plan. true = create."));
        JsonArray required = new JsonArray();
        required.add("projectName");
        required.add("ownerFqn");
        required.add("name");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", required);
        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description", "WRITE: create a template on a metadata object (report, external report, "
                + "data processor, document, catalog): a DATA_COMPOSITION_SCHEMA with an empty schema, optionally "
                + "the report's main one, a SPREADSHEET_DOCUMENT - an empty print form layout, an HTML_DOCUMENT with "
                + "one page in the editing language, or a TEXT_DOCUMENT; the last two take their starting text from "
                + "text. Fill a schema with edt_replace_dcs or edt_modify_dcs, a spreadsheet with "
                + "edt_write_spreadsheet, an HTML page with edt_replace_html_template. EDT validation does not "
                + "check the XML of a schema or a spreadsheet; the build (edt_dump_external_object) does. Dry-run by default; "
                + "apply=true creates, waits until EDT has written the content file and reads it back: "
                + "contentReadable, contentPath and sourceHash, which is the expectedHash of the first write. "
                + "Token-gated.");
        t.addProperty("descriptionRu", "ЗАПИСЬ: создать макет у объекта метаданных (отчет, внешний отчет, обработка, "
                + "документ, справочник): DATA_COMPOSITION_SCHEMA с пустой схемой, при необходимости основной схемой "
                + "отчета, SPREADSHEET_DOCUMENT – пустой макет печатной формы, HTML_DOCUMENT с одной страницей на "
                + "языке редактирования или TEXT_DOCUMENT; два последних берут начальный текст из text. Схему "
                + "заполняют edt_replace_dcs или edt_modify_dcs, табличный макет – edt_write_spreadsheet, страницу "
                + "HTML – edt_replace_html_template. XML схемы и табличного макета проверяет не проверка EDT, а "
                + "сборка (edt_dump_external_object). По умолчанию dry-run; apply=true создает макет, ждет, пока EDT "
                + "запишет файл содержимого, и читает его: contentReadable, contentPath и sourceHash, который служит "
                + "expectedHash первой записи. Требует токен.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        String owner = getStr(args, "ownerFqn");
        String name = getStr(args, "name");
        if (project == null || owner == null || name == null) {
            return McpServer.toolError("projectName, ownerFqn and name are required");
        }
        try {
            var result = gateway.addTemplate(project, owner, name, getStr(args, "templateType"),
                    getStr(args, "synonymRu"), getBool(args, "setMainSchema"), getStr(args, "text"),
                    getBool(args, "apply"));
            return McpServer.textResult(new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(result));
        } catch (Exception exception) {
            return McpServer.toolError("edt_add_template failed: " + exception.getMessage());
        }
    }
}
