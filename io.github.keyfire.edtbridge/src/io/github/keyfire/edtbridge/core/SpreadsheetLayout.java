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
package io.github.keyfire.edtbridge.core;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/**
 * A print form template - the spreadsheet document EDT keeps as {@code Template.mxlx} - in a compact
 * form: column widths and named row areas made of cells, each cell with the few properties a print
 * form needs. {@link #write} turns the compact form into the complete file, {@link #parse} reads an
 * existing file back and lists what the compact form leaves behind.
 *
 * <p>Rows are lists of cells from column 0. Every cell takes {@code colspan} columns, so the next cell
 * of the list starts after it, as in an HTML table; a {@code null} entry leaves one column empty. An
 * area named {@code ""} holds rows outside any named area: {@link #parse} produces it, and
 * {@link #write} writes its rows without a name.
 *
 * <p>The file layout follows what EDT writes: CRLF line ends, one tab per level, no final line end.
 * Format numbers start at 1 (0 is "no format"), font and line numbers start at 0.
 */
public final class SpreadsheetLayout {
    /**
     * One cell. Exactly one of text, parameter, template is set; template is a text with [Name]
     * substitutions. An empty text is an empty cell that still carries its border or merge. A
     * {@code null} property is the default: regular Arial 10, automatic alignment, no border, one
     * column, no wrap.
     */
    public record Cell(String text, String parameter, String template, Boolean bold, Integer fontSize,
            String align, Boolean border, Integer colspan, Boolean wrap) { }

    /** A named row area: what ПолучитьОбласть("Name") returns. Rows are lists of cells from column 0. */
    public record Area(String name, List<List<Cell>> rows) { }

    /**
     * columns: widths in the file's units, index = column; null = the default 72. A non-empty list
     * fixes the number of columns, and every row has to fit in it; without it the widest row sets the
     * number of columns. unsupported: what {@link #parse} found and the compact form cannot carry, one
     * entry per kind; {@link #write} ignores it.
     */
    public record Layout(List<Integer> columns, List<Area> areas, List<String> unsupported) { }

    public static final int MAX_COLUMNS = 200;
    public static final int MAX_ROWS = 2000;
    public static final int DEFAULT_WIDTH = 72;
    public static final int DEFAULT_FONT_SIZE = 10;
    public static final int MAX_WIDTH = 2000;
    public static final int MAX_FONT_SIZE = 100;
    public static final List<String> ALIGNMENTS = List.of("Left", "Center", "Right");

    /** A cell placed on the grid: its first column and the cell itself. */
    record Placed(int column, Cell cell) {
        int span() {
            return cell.colspan() == null ? 1 : cell.colspan();
        }
    }

    /** One row of the document: the number of its area and its cells in column order. */
    record GridRow(int area, List<Placed> cells) { }

    /** The checked layout on a grid: the width of every column and every row of the document. */
    record Grid(int[] widths, List<GridRow> rows) { }

    static final String NS = "http://v8.1c.ru/8.2/data/spreadsheet";
    private static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String NL = "\r\n";
    /** The declaration, the root element and the language settings every file starts with. */
    static final String HEAD ="<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + NL
            + "<document xmlns=\"http://v8.1c.ru/8.2/data/spreadsheet\""
            + " xmlns:pal=\"http://v8.1c.ru/8.1/data/ui/colors/palette\""
            + " xmlns:style=\"http://v8.1c.ru/8.1/data/ui/style\""
            + " xmlns:v8=\"http://v8.1c.ru/8.1/data/core\""
            + " xmlns:v8ui=\"http://v8.1c.ru/8.1/data/ui\""
            + " xmlns:xs=\"http://www.w3.org/2001/XMLSchema\""
            + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" + NL
            + "\t<languageSettings>" + NL
            + "\t\t<currentLanguage>ru</currentLanguage>" + NL
            + "\t\t<defaultLanguage>ru</defaultLanguage>" + NL
            + "\t\t<languageInfo>" + NL
            + "\t\t\t<id>ru</id>" + NL
            + "\t\t\t<code>Русский</code>" + NL
            + "\t\t\t<description>Русский</description>" + NL
            + "\t\t</languageInfo>" + NL
            + "\t</languageSettings>" + NL;
    private static final Pattern IDENT = Pattern.compile("[\\p{L}_][\\p{L}\\p{Nd}_]*");
    private static final List<String> SIDES = List.of("leftBorder", "topBorder", "rightBorder", "bottomBorder");
    /** Cell format properties that describe the cell's own size; the column widths carry the size here. */
    private static final Set<String> CELL_SIZE = Set.of("width", "height", "bySelectedColumns", "columnSizeChange");
    /** Cell format properties written with their default value, which the compact form drops silently. */
    private static final Map<String, String> CELL_DEFAULTS = Map.of("hyperLink", "false", "textOrientation", "0",
            "markNegatives", "false", "protection", "false");

    private SpreadsheetLayout() { }

    // ---------------------------------------------------------------------------------------- write

    /** The complete file text EDT can load; refuses an invalid layout with the reason and the fix. */
    public static String write(Layout layout) {
        Grid grid = grid(layout);
        Formats formats = new Formats();
        int columns = grid.widths().length;
        int[] columnFormat = new int[columns];
        for (int column = 0; column < columns; column++) {
            int width = grid.widths()[column];
            if (width != DEFAULT_WIDTH) columnFormat[column] = formats.index(List.of("<width>" + width + "</width>"));
        }
        StringBuilder out = new StringBuilder(HEAD);
        out.append("\t<columns>").append(NL);
        out.append("\t\t<size>").append(columns).append("</size>").append(NL);
        for (int column = 0; column < columns; column++) {
            if (columnFormat[column] == 0) continue;
            out.append("\t\t<columnsItem>").append(NL);
            out.append("\t\t\t<index>").append(column).append("</index>").append(NL);
            out.append("\t\t\t<column>").append(NL);
            out.append("\t\t\t\t<formatIndex>").append(columnFormat[column]).append("</formatIndex>").append(NL);
            out.append("\t\t\t</column>").append(NL);
            out.append("\t\t</columnsItem>").append(NL);
        }
        out.append("\t</columns>").append(NL);
        StringBuilder merges = new StringBuilder();
        List<GridRow> rows = grid.rows();
        for (int index = 0; index < rows.size(); index++) {
            out.append("\t<rowsItem>").append(NL);
            out.append("\t\t<index>").append(index).append("</index>").append(NL);
            out.append("\t\t<row>").append(NL);
            List<Placed> cells = rows.get(index).cells();
            if (cells.isEmpty()) out.append("\t\t\t<empty>true</empty>").append(NL);
            int previous = -1;
            for (Placed placed : cells) {
                Cell cell = placed.cell();
                out.append("\t\t\t<c>").append(NL);
                if (placed.column() != previous + 1) {
                    out.append("\t\t\t\t<i>").append(placed.column()).append("</i>").append(NL);
                }
                out.append("\t\t\t\t<c>").append(NL);
                out.append("\t\t\t\t\t<f>").append(formats.cell(cell)).append("</f>").append(NL);
                if (cell.parameter() != null) {
                    out.append("\t\t\t\t\t<parameter>").append(escape(cell.parameter())).append("</parameter>").append(NL);
                } else {
                    String text = cell.template() != null ? cell.template() : cell.text();
                    if (!text.isEmpty()) appendText(out, text);
                }
                out.append("\t\t\t\t</c>").append(NL);
                out.append("\t\t\t</c>").append(NL);
                previous = placed.column();
                if (placed.span() > 1) {
                    merges.append("\t<merge>").append(NL);
                    merges.append("\t\t<r>").append(index).append("</r>").append(NL);
                    merges.append("\t\t<c>").append(placed.column()).append("</c>").append(NL);
                    merges.append("\t\t<w>").append(placed.span() - 1).append("</w>").append(NL);
                    merges.append("\t</merge>").append(NL);
                }
            }
            out.append("\t\t</row>").append(NL);
            out.append("\t</rowsItem>").append(NL);
        }
        int defaultFormat = formats.index(List.of("<width>" + DEFAULT_WIDTH + "</width>"));
        out.append("\t<templateMode>true</templateMode>").append(NL);
        out.append("\t<defaultFormatIndex>").append(defaultFormat).append("</defaultFormatIndex>").append(NL);
        out.append("\t<height>").append(rows.size()).append("</height>").append(NL);
        out.append("\t<vgRows>").append(rows.size()).append("</vgRows>").append(NL);
        out.append(merges);
        appendNamedAreas(out, layout.areas());
        if (formats.border) {
            out.append("\t<line width=\"1\" gap=\"false\">").append(NL);
            out.append("\t\t<v8ui:style xsi:type=\"v8ui:SpreadsheetDocumentCellLineType\">Solid</v8ui:style>").append(NL);
            out.append("\t</line>").append(NL);
        }
        for (String font : formats.fonts.keySet()) out.append("\t").append(font).append(NL);
        for (List<String> format : formats.formats.keySet()) {
            out.append("\t<format>").append(NL);
            for (String line : format) out.append("\t\t").append(line).append(NL);
            out.append("\t</format>").append(NL);
        }
        out.append("</document>");
        return out.toString();
    }

    private static void appendText(StringBuilder out, String text) {
        out.append("\t\t\t\t\t<tl>").append(NL);
        out.append("\t\t\t\t\t\t<v8:item>").append(NL);
        out.append("\t\t\t\t\t\t\t<v8:lang>ru</v8:lang>").append(NL);
        out.append("\t\t\t\t\t\t\t<v8:content>").append(escape(text).replaceAll("\r\n|\r|\n", NL))
                .append("</v8:content>").append(NL);
        out.append("\t\t\t\t\t\t</v8:item>").append(NL);
        out.append("\t\t\t\t\t</tl>").append(NL);
    }

    /** Named areas in the order EDT keeps them: by name, ignoring case. */
    private static void appendNamedAreas(StringBuilder out, List<Area> areas) {
        Map<String, int[]> spans = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int row = 0;
        for (Area area : areas) {
            int size = area.rows().size();
            if (!area.name().isEmpty()) spans.put(area.name(), new int[] {row, row + size - 1});
            row += size;
        }
        spans.forEach((name, span) -> {
            out.append("\t<namedItem xsi:type=\"NamedItemCells\">").append(NL);
            out.append("\t\t<name>").append(escape(name)).append("</name>").append(NL);
            out.append("\t\t<area>").append(NL);
            out.append("\t\t\t<type>Rows</type>").append(NL);
            out.append("\t\t\t<beginRow>").append(span[0]).append("</beginRow>").append(NL);
            out.append("\t\t\t<endRow>").append(span[1]).append("</endRow>").append(NL);
            out.append("\t\t\t<beginColumn>-1</beginColumn>").append(NL);
            out.append("\t\t\t<endColumn>-1</endColumn>").append(NL);
            out.append("\t\t</area>").append(NL);
            out.append("\t</namedItem>").append(NL);
        });
    }

    /** The formats, fonts and the line of a file being written, each written once and shared by number. */
    private static final class Formats {
        final Map<List<String>, Integer> formats = new LinkedHashMap<>();
        final Map<String, Integer> fonts = new LinkedHashMap<>();
        boolean border;

        int index(List<String> format) {
            return formats.computeIfAbsent(format, key -> formats.size() + 1);
        }

        /** The format number of a cell, 0 for a cell without any format of its own. */
        int cell(Cell cell) {
            List<String> format = new ArrayList<>();
            boolean bold = Boolean.TRUE.equals(cell.bold());
            int size = cell.fontSize() == null ? DEFAULT_FONT_SIZE : cell.fontSize();
            if (bold || size != DEFAULT_FONT_SIZE) {
                String font = "<font faceName=\"Arial\" height=\"" + size + "\" bold=\"" + bold
                        + "\" italic=\"false\" underline=\"false\" strikeout=\"false\" kind=\"Absolute\" scale=\"100\"/>";
                format.add("<font>" + fonts.computeIfAbsent(font, key -> fonts.size()) + "</font>");
            }
            if (Boolean.TRUE.equals(cell.border())) {
                border = true;
                format.add("<border>0</border>");
            }
            String align = alignment(cell.align());
            if (align != null) format.add("<horizontalAlignment>" + align + "</horizontalAlignment>");
            if (Boolean.TRUE.equals(cell.wrap())) format.add("<textPlacement>Wrap</textPlacement>");
            if (cell.parameter() != null) format.add("<fillType>Parameter</fillType>");
            else if (cell.template() != null) format.add("<fillType>Template</fillType>");
            return format.isEmpty() ? 0 : index(format);
        }
    }

    // ----------------------------------------------------------------------------------------- check

    /** Checks a layout and puts its cells on the grid; the refusal names the place and the fix. */
    static Grid grid(Layout layout) {
        if (layout == null) throw new IllegalArgumentException("the layout is missing");
        List<Area> areas = layout.areas();
        if (areas == null || areas.isEmpty()) {
            throw new IllegalArgumentException("the layout has no areas: give at least one area with at least one row");
        }
        List<Integer> declared = layout.columns() == null ? List.of() : layout.columns();
        if (declared.size() > MAX_COLUMNS) throw tooManyColumns(declared.size());
        for (int column = 0; column < declared.size(); column++) {
            Integer width = declared.get(column);
            if (width != null && (width < 1 || width > MAX_WIDTH)) {
                throw new IllegalArgumentException("columns[" + column + "]: width " + width + " is out of range; give 1 to "
                        + MAX_WIDTH + ", or null for the default " + DEFAULT_WIDTH);
            }
        }
        int total = 0;
        for (Area area : areas) total += area == null || area.rows() == null ? 0 : area.rows().size();
        if (total > MAX_ROWS) throw tooManyRows(total);
        Map<String, String> names = new HashMap<>();
        List<GridRow> rows = new ArrayList<>();
        int extent = 1;
        for (int number = 0; number < areas.size(); number++) {
            Area area = areas.get(number);
            if (area == null) throw new IllegalArgumentException("areas[" + number + "] is missing");
            String name = area.name();
            if (name == null) {
                throw new IllegalArgumentException("areas[" + number + "] has no name; give a 1C identifier, "
                        + "or \"\" for rows outside any named area");
            }
            if (!name.isEmpty()) {
                if (!IDENT.matcher(name).matches()) {
                    throw new IllegalArgumentException("area name is not a 1C identifier: \"" + name
                            + "\"; use letters, digits and _, not starting with a digit");
                }
                String earlier = names.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
                if (earlier != null) {
                    throw new IllegalArgumentException("area name repeats: " + name + " (" + earlier
                            + " is already there; 1C names ignore case)");
                }
            }
            String where = name.isEmpty() ? "areas[" + number + "]" : "area " + name;
            if (area.rows() == null || area.rows().isEmpty()) {
                throw new IllegalArgumentException(where + " has no rows; give at least one row, [] for an empty one");
            }
            for (int index = 0; index < area.rows().size(); index++) {
                List<Cell> row = area.rows().get(index);
                List<Placed> placed = new ArrayList<>();
                int column = 0;
                int end = 0;
                for (int position = 0; row != null && position < row.size(); position++) {
                    Cell cell = row.get(position);
                    if (cell == null) {
                        column++;
                        continue;
                    }
                    check(cell, where + ", rows[" + index + "][" + position + "]");
                    Placed one = new Placed(column, cell);
                    placed.add(one);
                    column += one.span();
                    end = column;
                }
                if (!declared.isEmpty() && end > declared.size()) {
                    throw new IllegalArgumentException(where + ", rows[" + index + "]: the cells take " + end
                            + " columns, the layout has " + declared.size()
                            + "; add columns or reduce colspan");
                }
                if (end > MAX_COLUMNS) throw tooManyColumns(end);
                extent = Math.max(extent, end);
                rows.add(new GridRow(number, List.copyOf(placed)));
            }
        }
        int count = declared.isEmpty() ? extent : declared.size();
        int[] widths = new int[count];
        for (int column = 0; column < count; column++) {
            Integer width = column < declared.size() ? declared.get(column) : null;
            widths[column] = width == null ? DEFAULT_WIDTH : width;
        }
        return new Grid(widths, List.copyOf(rows));
    }

    private static void check(Cell cell, String where) {
        int set = (cell.text() != null ? 1 : 0) + (cell.parameter() != null ? 1 : 0) + (cell.template() != null ? 1 : 0);
        if (set != 1) {
            throw new IllegalArgumentException(where + ": set exactly one of text, parameter, template (" + set
                    + " set); use text \"\" for an empty cell");
        }
        if (cell.parameter() != null && !IDENT.matcher(cell.parameter()).matches()) {
            throw new IllegalArgumentException(where + ": parameter is not a 1C identifier: \"" + cell.parameter()
                    + "\"; for a text with substitutions use template, as in \"Total: [Sum]\"");
        }
        String content = cell.text() != null ? cell.text() : cell.template() != null ? cell.template() : "";
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                throw new IllegalArgumentException(where + ": the text has the control character U+"
                        + String.format(Locale.ROOT, "%04X", (int) c) + ", which XML cannot hold");
            }
        }
        if (cell.align() != null && alignment(cell.align()) == null) {
            throw new IllegalArgumentException(where + ": " + AllowedValues.unknownValue("align", cell.align(), ALIGNMENTS));
        }
        if (cell.colspan() != null && cell.colspan() < 1) {
            throw new IllegalArgumentException(where + ": colspan " + cell.colspan() + " is less than 1");
        }
        if (cell.fontSize() != null && (cell.fontSize() < 1 || cell.fontSize() > MAX_FONT_SIZE)) {
            throw new IllegalArgumentException(where + ": fontSize " + cell.fontSize() + " is out of range; give 1 to "
                    + MAX_FONT_SIZE + ", or null for the default " + DEFAULT_FONT_SIZE);
        }
    }

    /** The spelling the file uses for an alignment, or null when it is not one of {@link #ALIGNMENTS}. */
    static String alignment(String align) {
        if (align == null) return null;
        for (String allowed : ALIGNMENTS) if (allowed.equalsIgnoreCase(align.trim())) return allowed;
        return null;
    }

    private static IllegalArgumentException tooManyColumns(int columns) {
        return new IllegalArgumentException("the layout has " + columns + " columns; at most " + MAX_COLUMNS
                + " are supported");
    }

    private static IllegalArgumentException tooManyRows(int rows) {
        return new IllegalArgumentException("the layout has " + rows + " rows; at most " + MAX_ROWS + " are supported");
    }

    // ----------------------------------------------------------------------------------------- parse

    /**
     * The compact form of an existing template; what it cannot carry is listed in unsupported, one entry
     * per kind. A format property is named as the file names it ({@code backColor}, {@code textColor},
     * {@code verticalAlignment}, ...); the other kinds are {@code picture}, {@code drawing},
     * {@code detailParameter}, {@code partialBorder} and {@code borderStyle} (a border that is not a thin
     * solid line on all four sides), {@code verticalMerge}, {@code rowHeight}, {@code rowFormat},
     * {@code columnFormat}, {@code defaultFormat}, {@code columnSets}, {@code rectangleArea},
     * {@code columnArea}, {@code overlappingArea} (of two overlapping row areas the smaller one is kept),
     * {@code fontFace}, {@code fontScale}, {@code italic}, {@code underline}, {@code strikeout},
     * {@code languages}, {@code formattedText} and {@code valueFormat}.
     */
    public static Layout parse(String mxlx) {
        if (mxlx == null || mxlx.isBlank()) throw new IllegalArgumentException("the template text is missing");
        Element root = document(mxlx).getDocumentElement();
        Set<String> unsupported = new TreeSet<>();
        Source source = new Source(children(root, "format"), children(root, "font"), children(root, "line"), unsupported);

        Element defaultFormat = source.format(number(childText(root, "defaultFormatIndex"), 0));
        int defaultWidth = DEFAULT_WIDTH;
        if (defaultFormat != null) {
            for (Element property : elements(defaultFormat)) {
                if ("width".equals(property.getLocalName())) defaultWidth = number(property.getTextContent(), DEFAULT_WIDTH);
                else unsupported.add("defaultFormat");
            }
        }

        List<Element> columnSets = children(root, "columns");
        Element columnSet = null;
        for (Element set : columnSets) if (columnSet == null && children(set, "id").isEmpty()) columnSet = set;
        if (columnSet == null && !columnSets.isEmpty()) columnSet = columnSets.get(0);
        if (columnSets.size() > 1 || columnSet != null && !children(columnSet, "id").isEmpty()) unsupported.add("columnSets");
        int columnCount = 0;
        Map<Integer, Integer> widths = new HashMap<>();
        if (columnSet != null) {
            columnCount = number(childText(columnSet, "size"), 0);
            for (Element item : children(columnSet, "columnsItem")) {
                int index = number(childText(item, "index"), -1);
                Element column = first(item, "column");
                Element format = column == null ? null : source.format(number(childText(column, "formatIndex"), 0));
                if (index < 0 || format == null) continue;
                for (Element property : elements(format)) {
                    if ("width".equals(property.getLocalName())) widths.put(index, number(property.getTextContent(), defaultWidth));
                    else unsupported.add("columnFormat");
                }
                columnCount = Math.max(columnCount, index + 1);
            }
        }

        Map<Long, int[]> merges = new HashMap<>();
        int height = number(childText(root, "height"), 0);
        for (Element merge : children(root, "merge")) {
            int row = number(childText(merge, "r"), -1);
            int column = number(childText(merge, "c"), -1);
            int extra = number(childText(merge, "w"), 0);
            if (number(childText(merge, "h"), 0) > 0) unsupported.add("verticalMerge");
            if (row < 0 || column < 0) continue;
            merges.put(key(row, column), new int[] {extra});
            columnCount = Math.max(columnCount, column + extra + 1);
            height = Math.max(height, row + 1);
        }

        Map<Integer, TreeMap<Integer, Cell>> cells = new HashMap<>();
        for (Element item : children(root, "rowsItem")) {
            int index = number(childText(item, "index"), -1);
            int to = Math.max(index, number(childText(item, "indexTo"), index));
            Element row = first(item, "row");
            if (index < 0 || row == null) continue;
            if (to >= MAX_ROWS) throw tooManyRows(to + 1);
            height = Math.max(height, to + 1);
            TreeMap<Integer, Cell> parsed = parseRow(row, source);
            if (!parsed.isEmpty()) {
                Map.Entry<Integer, Cell> last = parsed.lastEntry();
                columnCount = Math.max(columnCount, last.getKey() + 1);
            }
            for (int r = index; r <= to; r++) cells.put(r, parsed);
        }

        List<int[]> spans = new ArrayList<>();
        List<String> spanNames = new ArrayList<>();
        for (Element item : children(root, "namedItem")) {
            String type = localType(item.getAttributeNS(XSI, "type"));
            Element area = first(item, "area");
            if (!"NamedItemCells".equals(type) || area == null) {
                unsupported.add("NamedItemDrawing".equals(type) ? "drawing" : "namedItem");
                continue;
            }
            String areaType = childText(area, "type");
            if (!"Rows".equals(areaType)) {
                unsupported.add("Rectangle".equals(areaType) ? "rectangleArea" : "Columns".equals(areaType) ? "columnArea" : "namedItem");
                continue;
            }
            int begin = Math.max(0, number(childText(area, "beginRow"), 0));
            int end = Math.max(begin, number(childText(area, "endRow"), begin));
            if (end >= MAX_ROWS) throw tooManyRows(end + 1);
            height = Math.max(height, end + 1);
            spans.add(new int[] {begin, end, spanNames.size()});
            spanNames.add(childText(item, "name"));
        }
        if (height > MAX_ROWS) throw tooManyRows(height);
        if (columnCount > MAX_COLUMNS) throw tooManyColumns(columnCount);

        for (Element child : elements(root)) {
            switch (child.getLocalName()) {
                case "languageSettings", "columns", "rowsItem", "templateMode", "defaultFormatIndex", "height", "vgRows",
                        "merge", "namedItem", "line", "font", "format" -> { }
                default -> unsupported.add(child.getLocalName());
            }
        }

        // Areas may nest or overlap; the compact form keeps the smallest ones that do not.
        spans.sort((a, b) -> a[1] - a[0] != b[1] - b[0] ? Integer.compare(a[1] - a[0], b[1] - b[0]) : Integer.compare(a[0], b[0]));
        int[] startOf = new int[height];
        Arrays.fill(startOf, -1);
        boolean[] taken = new boolean[height];
        for (int[] span : spans) {
            boolean free = true;
            for (int r = span[0]; r <= span[1]; r++) free &= !taken[r];
            if (!free) {
                unsupported.add("overlappingArea");
                continue;
            }
            for (int r = span[0]; r <= span[1]; r++) taken[r] = true;
            startOf[span[0]] = span[2];
        }

        List<Area> areas = new ArrayList<>();
        List<List<Cell>> unnamed = new ArrayList<>();
        int r = 0;
        while (r < height) {
            if (startOf[r] < 0) {
                unnamed.add(row(cells.get(r), merges, r));
                r++;
                continue;
            }
            if (!unnamed.isEmpty()) {
                areas.add(new Area("", Collections.unmodifiableList(unnamed)));
                unnamed = new ArrayList<>();
            }
            int[] span = null;
            for (int[] candidate : spans) if (candidate[2] == startOf[r]) span = candidate;
            List<List<Cell>> rows = new ArrayList<>();
            for (int i = span[0]; i <= span[1]; i++) rows.add(row(cells.get(i), merges, i));
            areas.add(new Area(spanNames.get(startOf[r]), Collections.unmodifiableList(rows)));
            r = span[1] + 1;
        }
        if (!unnamed.isEmpty()) areas.add(new Area("", Collections.unmodifiableList(unnamed)));

        List<Integer> columns = new ArrayList<>();
        for (int column = 0; column < columnCount; column++) {
            int width = widths.getOrDefault(column, defaultWidth);
            columns.add(width == DEFAULT_WIDTH ? null : width);
        }
        return new Layout(Collections.unmodifiableList(columns), Collections.unmodifiableList(areas),
                List.copyOf(unsupported));
    }

    /** The cells of a row by column; the outer {@code c} names its column when it skips some. */
    private static TreeMap<Integer, Cell> parseRow(Element row, Source source) {
        if (!children(row, "columnsID").isEmpty()) source.unsupported().add("columnSets");
        Element rowFormat = source.format(number(childText(row, "formatIndex"), 0));
        if (rowFormat != null) {
            for (Element property : elements(rowFormat)) {
                source.unsupported().add("height".equals(property.getLocalName()) ? "rowHeight" : "rowFormat");
            }
        }
        TreeMap<Integer, Cell> cells = new TreeMap<>();
        int column = -1;
        for (Element outer : children(row, "c")) {
            String skip = childText(outer, "i");
            column = skip.isEmpty() ? column + 1 : number(skip, column + 1);
            Element inner = first(outer, "c");
            if (inner != null && column >= 0) cells.put(column, source.cell(inner));
        }
        return cells;
    }

    /** One row in the compact form: merges become colspan, the cells they cover are dropped. */
    private static List<Cell> row(TreeMap<Integer, Cell> cells, Map<Long, int[]> merges, int index) {
        List<Cell> row = new ArrayList<>();
        int last = cells == null || cells.isEmpty() ? -1 : cells.lastKey();
        for (Map.Entry<Long, int[]> merge : merges.entrySet()) {
            if (merge.getKey() >> 32 == index) last = Math.max(last, (int) (merge.getKey() & 0xffffffffL));
        }
        int column = 0;
        while (column <= last) {
            Cell cell = cells == null ? null : cells.get(column);
            int[] merge = merges.get(key(index, column));
            int extra = merge == null ? 0 : Math.max(0, merge[0]);
            if (cell == null && extra == 0) {
                row.add(null);
                column++;
                continue;
            }
            if (cell == null) cell = new Cell("", null, null, null, null, null, null, null, null);
            if (extra > 0) {
                cell = new Cell(cell.text(), cell.parameter(), cell.template(), cell.bold(), cell.fontSize(), cell.align(),
                        cell.border(), extra + 1, cell.wrap());
            }
            row.add(cell);
            column += extra + 1;
        }
        while (!row.isEmpty() && row.get(row.size() - 1) == null) row.remove(row.size() - 1);
        return Collections.unmodifiableList(row);
    }

    /** The format, font and line tables of a file being read, and the kinds it found unsupported. */
    private record Source(List<Element> formats, List<Element> fonts, List<Element> lines, Set<String> unsupported) {
        /** Format numbers start at 1; 0 and anything out of range mean "no format". */
        Element format(int number) {
            return number >= 1 && number <= formats.size() ? formats.get(number - 1) : null;
        }

        Cell cell(Element inner) {
            Element format = format(number(childText(inner, "f"), 0));
            Boolean bold = null;
            Integer fontSize = null;
            String align = null;
            Boolean border = null;
            Boolean wrap = null;
            String fill = "Text";
            Map<String, String> sides = new HashMap<>();
            boolean picture = false;
            List<Element> properties = format == null ? List.of() : elements(format);
            for (Element property : properties) if ("picIndex".equals(property.getLocalName())) picture = true;
            for (Element property : properties) {
                String name = property.getLocalName();
                String value = property.getTextContent().trim();
                switch (name) {
                    case "font" -> {
                        int index = number(value, -1);
                        if (index >= 0 && index < fonts.size()) {
                            Element font = fonts.get(index);
                            fontTraits(font);
                            if ("true".equals(font.getAttribute("bold"))) bold = true;
                            int size = (int) Math.round(decimal(font.getAttribute("height"), DEFAULT_FONT_SIZE));
                            if (size != DEFAULT_FONT_SIZE && size > 0) fontSize = size;
                        }
                    }
                    case "border", "leftBorder", "topBorder", "rightBorder", "bottomBorder" -> sides.put(name, value);
                    case "horizontalAlignment" -> {
                        align = alignment(value);
                        if (align == null && !"Auto".equals(value)) unsupported.add("horizontalAlignment");
                    }
                    case "textPlacement" -> {
                        if ("Wrap".equals(value)) wrap = true;
                        else if (!"Auto".equals(value)) unsupported.add("textPlacement");
                    }
                    case "fillType" -> fill = value;
                    case "picIndex" -> unsupported.add("picture");
                    case "picHorizontalAlignment", "picVerticalAlignment" -> {
                        if (picture) unsupported.add("picture");
                    }
                    case "format" -> unsupported.add("valueFormat");
                    default -> {
                        if (!CELL_SIZE.contains(name) && !value.equals(CELL_DEFAULTS.get(name))) unsupported.add(name);
                    }
                }
            }
            if (!sides.isEmpty()) border = border(sides);

            String text = null;
            String parameter = null;
            for (Element child : elements(inner)) {
                switch (child.getLocalName()) {
                    case "f" -> { }
                    case "tl" -> text = localized(child);
                    case "tfl" -> {
                        text = localized(child);
                        unsupported.add("formattedText");
                    }
                    case "parameter" -> parameter = child.getTextContent().trim();
                    case "pictureParameter" -> unsupported.add("picture");
                    default -> unsupported.add(child.getLocalName());
                }
            }
            String body = text == null ? "" : text;
            if ("Parameter".equals(fill) && parameter != null && !parameter.isEmpty()) {
                return new Cell(null, parameter, null, bold, fontSize, align, border, null, wrap);
            }
            if ("Template".equals(fill)) return new Cell(null, null, body, bold, fontSize, align, border, null, wrap);
            return new Cell(body, null, null, bold, fontSize, align, border, null, wrap);
        }

        private void fontTraits(Element font) {
            boolean absolute = font.getAttribute("kind").isEmpty() || "Absolute".equals(font.getAttribute("kind"));
            if (!absolute || !font.getAttribute("ref").isEmpty() || !"Arial".equalsIgnoreCase(font.getAttribute("faceName"))) {
                unsupported.add("fontFace");
            }
            for (String trait : List.of("italic", "underline", "strikeout")) {
                if ("true".equals(font.getAttribute(trait))) unsupported.add(trait);
            }
            String scale = font.getAttribute("scale");
            if (!scale.isEmpty() && decimal(scale, 100) != 100) unsupported.add("fontScale");
        }

        /** true for a line on all four sides; a thicker or dashed one is kept as true and reported. */
        private Boolean border(Map<String, String> sides) {
            List<Element> used = new ArrayList<>();
            int visible = 0;
            for (String side : SIDES) {
                String value = sides.containsKey("border") ? sides.get("border") : sides.get(side);
                if (value == null) continue;
                int index = number(value, -1);
                Element line = index >= 0 && index < lines.size() ? lines.get(index) : null;
                if (line == null || "None".equals(childText(line, "style"))) continue;
                visible++;
                used.add(line);
            }
            if (visible == 0) return null;
            if (visible < SIDES.size()) {
                unsupported.add("partialBorder");
                return null;
            }
            for (Element line : used) {
                if (!"Solid".equals(childText(line, "style")) || number(line.getAttribute("width"), 1) != 1) {
                    unsupported.add("borderStyle");
                }
            }
            return true;
        }

        /** The Russian text of a localized string, or the first one; several languages are reported. */
        private String localized(Element string) {
            List<Element> items = children(string, "item");
            if (items.size() > 1) unsupported.add("languages");
            String text = null;
            for (Element item : items) {
                String content = childText(item, "content", false);
                if (text == null || "ru".equals(childText(item, "lang"))) text = content;
            }
            return text == null ? "" : text;
        }
    }

    // --------------------------------------------------------------------------------------- helpers

    private static Document document(String text) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler()); // throws on a fatal error instead of printing it to stderr
            Document document = builder.parse(new InputSource(new StringReader(text)));
            Element root = document.getDocumentElement();
            if (!"document".equals(root.getLocalName()) || !NS.equals(root.getNamespaceURI())) {
                throw new IllegalArgumentException("the template is not a spreadsheet document: root " + root.getNodeName());
            }
            return document;
        } catch (IllegalArgumentException refusal) {
            throw refusal;
        } catch (Exception malformed) {
            throw new IllegalArgumentException("the template is not well-formed XML: " + malformed.getMessage());
        }
    }

    private static List<Element> elements(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) result.add(element);
        }
        return result;
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        for (Element element : elements(parent)) if (localName.equals(element.getLocalName())) result.add(element);
        return result;
    }

    private static Element first(Element parent, String localName) {
        List<Element> found = children(parent, localName);
        return found.isEmpty() ? null : found.get(0);
    }

    private static String childText(Element parent, String localName) {
        return childText(parent, localName, true);
    }

    private static String childText(Element parent, String localName, boolean trim) {
        Element found = first(parent, localName);
        if (found == null) return "";
        return trim ? found.getTextContent().trim() : found.getTextContent();
    }

    private static String localType(String qualified) {
        int colon = qualified.indexOf(':');
        return colon < 0 ? qualified : qualified.substring(colon + 1);
    }

    private static int number(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (RuntimeException notANumber) {
            return fallback;
        }
    }

    private static double decimal(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (RuntimeException notANumber) {
            return fallback;
        }
    }

    private static long key(int row, int column) {
        return ((long) row << 32) | (column & 0xffffffffL);
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
