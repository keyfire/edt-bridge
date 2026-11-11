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
import io.github.keyfire.edtbridge.core.SpreadsheetLayout;

/** The JSON form of a spreadsheet layout, shared by the read and write tools. */
final class SpreadsheetTools {
    private SpreadsheetTools() { }

    static final String CELL_HELP = "A cell is an object with exactly one of text, parameter (filled by "
            + "Область.Параметры.Name) or template (text with [Name] substitutions), and optional bold, fontSize, "
            + "align (Left, Center, Right), border (thin line on all sides), colspan and wrap.";

    /** projectName and templateFqn of a spreadsheet template. */
    static JsonObject identity() {
        JsonObject props = new JsonObject();
        props.add("projectName", ToolJson.strProp("Loaded EDT project name."));
        props.add("templateFqn", ToolJson.strProp("Existing spreadsheet template, e.g. "
                + "ExternalDataProcessor.Invoice.Template.PrintForm or Document.Order.Template.Invoice."));
        return props;
    }

    static JsonObject layoutSchema() {
        JsonObject props = new JsonObject();
        JsonObject columns = new JsonObject();
        columns.addProperty("type", "array");
        JsonObject width = new JsonObject(); width.addProperty("type", "integer"); width.addProperty("minimum", 1);
        columns.add("items", width);
        columns.addProperty("description", "Column widths, index = column. Missing columns take the default width 72.");
        props.add("columns", columns);
        JsonObject areas = new JsonObject();
        areas.addProperty("type", "array");
        areas.addProperty("description", "Named row areas in order - what ПолучитьОбласть(\"Name\") returns. Each is "
                + "{name, rows}, rows is a list of rows, a row is a list of cells from column 0. " + CELL_HELP);
        JsonObject area = new JsonObject(); area.addProperty("type", "object");
        areas.add("items", area);
        props.add("areas", areas);
        return props;
    }

    static SpreadsheetLayout.Layout layout(JsonObject args) {
        List<Integer> columns = new ArrayList<>();
        if (args.has("columns") && !args.get("columns").isJsonNull()) {
            if (!args.get("columns").isJsonArray()) throw new IllegalArgumentException("columns must be an array of widths");
            for (JsonElement width : args.getAsJsonArray("columns")) {
                columns.add(width.isJsonNull() ? null : width.getAsInt());
            }
        }
        if (!args.has("areas") || !args.get("areas").isJsonArray()) throw new IllegalArgumentException("areas must be an array");
        List<SpreadsheetLayout.Area> areas = new ArrayList<>();
        for (JsonElement element : args.getAsJsonArray("areas")) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("each area must be an object {name, rows}");
            JsonObject area = element.getAsJsonObject();
            String name = text(area, "name");
            if (!area.has("rows") || !area.get("rows").isJsonArray()) {
                throw new IllegalArgumentException("area " + name + ": rows must be an array of rows");
            }
            List<List<SpreadsheetLayout.Cell>> rows = new ArrayList<>();
            for (JsonElement row : area.getAsJsonArray("rows")) {
                if (!row.isJsonArray()) throw new IllegalArgumentException("area " + name + ": a row must be an array of cells");
                List<SpreadsheetLayout.Cell> cells = new ArrayList<>();
                for (JsonElement cell : row.getAsJsonArray()) {
                    if (cell.isJsonNull()) { cells.add(null); continue; }
                    if (!cell.isJsonObject()) throw new IllegalArgumentException("area " + name + ": a cell must be an object or null");
                    JsonObject c = cell.getAsJsonObject();
                    cells.add(new SpreadsheetLayout.Cell(text(c, "text"), text(c, "parameter"), text(c, "template"),
                            flag(c, "bold"), number(c, "fontSize"), text(c, "align"), flag(c, "border"),
                            number(c, "colspan"), flag(c, "wrap")));
                }
                rows.add(cells);
            }
            areas.add(new SpreadsheetLayout.Area(name, rows));
        }
        return new SpreadsheetLayout.Layout(columns, areas, List.of());
    }

    static JsonElement toJson(Object value) {
        return new GsonBuilder().disableHtmlEscaping().create().toJsonTree(value);
    }

    static JsonObject layoutJson(SpreadsheetLayout.Layout layout) {
        JsonObject json = new JsonObject();
        JsonArray columns = new JsonArray();
        for (Integer width : layout.columns()) columns.add(width);
        json.add("columns", columns);
        JsonArray areas = new JsonArray();
        for (SpreadsheetLayout.Area area : layout.areas()) {
            JsonObject a = new JsonObject();
            a.addProperty("name", area.name());
            JsonArray rows = new JsonArray();
            for (List<SpreadsheetLayout.Cell> row : area.rows()) {
                JsonArray cells = new JsonArray();
                for (SpreadsheetLayout.Cell cell : row) {
                    if (cell == null) { cells.add(com.google.gson.JsonNull.INSTANCE); continue; }
                    JsonObject c = new JsonObject();
                    if (cell.text() != null) c.addProperty("text", cell.text());
                    if (cell.parameter() != null) c.addProperty("parameter", cell.parameter());
                    if (cell.template() != null) c.addProperty("template", cell.template());
                    if (Boolean.TRUE.equals(cell.bold())) c.addProperty("bold", true);
                    if (cell.fontSize() != null) c.addProperty("fontSize", cell.fontSize());
                    if (cell.align() != null) c.addProperty("align", cell.align());
                    if (Boolean.TRUE.equals(cell.border())) c.addProperty("border", true);
                    if (cell.colspan() != null && cell.colspan() > 1) c.addProperty("colspan", cell.colspan());
                    if (Boolean.TRUE.equals(cell.wrap())) c.addProperty("wrap", true);
                    cells.add(c);
                }
                rows.add(cells);
            }
            a.add("rows", rows);
            areas.add(a);
        }
        json.add("areas", areas);
        JsonArray unsupported = new JsonArray();
        for (String feature : layout.unsupported()) unsupported.add(feature);
        json.add("unsupported", unsupported);
        return json;
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static Boolean flag(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsBoolean();
    }

    private static Integer number(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsInt();
    }
}
