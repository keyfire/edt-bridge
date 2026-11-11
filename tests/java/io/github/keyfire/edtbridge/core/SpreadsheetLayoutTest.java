/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.*;
import io.github.keyfire.edtbridge.core.SpreadsheetLayout.Area;
import io.github.keyfire.edtbridge.core.SpreadsheetLayout.Cell;
import io.github.keyfire.edtbridge.core.SpreadsheetLayout.Layout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class SpreadsheetLayoutTest {
    static Cell text(String text) {
        return new Cell(text, null, null, null, null, null, null, null, null);
    }

    static Cell parameter(String name) {
        return new Cell(null, name, null, null, null, null, null, null, null);
    }

    static Cell template(String text) {
        return new Cell(null, null, text, null, null, null, null, null, null);
    }

    /** The same cell with the given style; a null argument keeps the cell's own value. */
    static Cell styled(Cell cell, Boolean bold, Integer fontSize, String align, Boolean border, Integer colspan, Boolean wrap) {
        return new Cell(cell.text(), cell.parameter(), cell.template(),
                bold != null ? bold : cell.bold(), fontSize != null ? fontSize : cell.fontSize(),
                align != null ? align : cell.align(), border != null ? border : cell.border(),
                colspan != null ? colspan : cell.colspan(), wrap != null ? wrap : cell.wrap());
    }

    @SafeVarargs
    static List<Cell> row(Cell... cells) {
        return Collections.unmodifiableList(Arrays.asList(cells));
    }

    @SafeVarargs
    static Area area(String name, List<Cell>... rows) {
        return new Area(name, List.of(rows));
    }

    static Layout invoice() {
        Cell head = styled(text("№"), true, null, "Center", true, null, null);
        return new Layout(Arrays.asList(30, null, 200, 90), List.of(
                area("Заголовок", row(styled(template("Счет на оплату № [Номер] от [Дата]"), true, 14, null, null, 4, null))),
                area("Отступ", row()),
                area("Шапка", row(head, styled(text("Товар"), true, null, null, true, 2, null),
                        styled(text("Сумма"), true, null, "Right", true, null, null))),
                area("Строка", row(styled(parameter("НомерСтроки"), null, null, "Center", true, null, null),
                        styled(parameter("Товар"), null, null, null, true, 2, true),
                        styled(parameter("Сумма"), null, null, "Right", true, null, null))),
                area("Итого", row(null, null, styled(text("Итого:"), true, null, "Right", null, null, null),
                        styled(parameter("Всего"), true, null, "Right", true, null, null)),
                        row(styled(text("Всего наименований [Количество],\nна сумму [СуммаПрописью]"), null, 8, null, null, 4, true)))),
                List.of());
    }

    @Test void writtenLayoutReadsBackUnchanged() {
        Layout invoice = invoice();
        assertEquals(invoice, SpreadsheetLayout.parse(SpreadsheetLayout.write(invoice)));

        Layout plain = new Layout(null, List.of(area("", row(text("Без имени"), null, text(""))),
                area("Данные", row(parameter("Значение")), row())), List.of());
        Layout read = SpreadsheetLayout.parse(SpreadsheetLayout.write(plain));
        assertEquals(Arrays.asList(null, null, null), read.columns(), "the widest row sets the column count");
        assertEquals(plain.areas(), read.areas());

        Layout template = new Layout(List.of(), List.of(area("Подвал",
                row(styled(template("Руководитель ____ [ФИО]"), null, null, null, null, null, true)))), List.of());
        assertEquals(template.areas(), SpreadsheetLayout.parse(SpreadsheetLayout.write(template)).areas());
    }

    @Test void fileIsWrittenTheWayEdtWritesIt() {
        String file = SpreadsheetLayout.write(invoice());
        assertTrue(file.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n<document xmlns=\"http://v8.1c.ru/8.2/data/spreadsheet\""));
        assertTrue(file.endsWith("\t</format>\r\n</document>"), "no line end after the root, as in EDT");
        assertFalse(file.replace("\r\n", "").contains("\n"), "CRLF only");
        assertFalse(file.contains("    "), "tabs, not spaces");
        assertTrue(file.contains("\t<columns>\r\n\t\t<size>4</size>\r\n\t\t<columnsItem>\r\n\t\t\t<index>0</index>"));
        assertFalse(file.contains("<index>1</index>\r\n\t\t\t<column>"), "a column of the default width has no item");
        assertTrue(file.contains("\t<merge>\r\n\t\t<r>0</r>\r\n\t\t<c>0</c>\r\n\t\t<w>3</w>\r\n\t</merge>"));
        assertTrue(file.contains("\t\t\t<c>\r\n\t\t\t\t<i>3</i>\r\n\t\t\t\t<c>"), "the cell after a merge names its column");
        assertTrue(file.contains("\t\t<row>\r\n\t\t\t<empty>true</empty>\r\n\t\t</row>"));
        assertEquals(1, count(file, "<line width=\"1\" gap=\"false\">"));
        assertEquals(List.of("Заголовок", "Итого", "Отступ", "Строка", "Шапка"), names(file), "named areas go by name");
        assertTrue(file.contains("<name>Итого</name>\r\n\t\t<area>\r\n\t\t\t<type>Rows</type>\r\n\t\t\t<beginRow>4</beginRow>"
                + "\r\n\t\t\t<endRow>5</endRow>\r\n\t\t\t<beginColumn>-1</beginColumn>\r\n\t\t\t<endColumn>-1</endColumn>"));
        assertTrue(file.contains("<v8:content>Всего наименований [Количество],\r\nна сумму [СуммаПрописью]</v8:content>"));
        assertTrue(file.contains("<templateMode>true</templateMode>\r\n\t<defaultFormatIndex>" + count(file, "<format>")
                + "</defaultFormatIndex>\r\n\t<height>6</height>\r\n\t<vgRows>6</vgRows>"), "the default format is the last one");
        assertTrue(file.endsWith("\t<format>\r\n\t\t<width>72</width>\r\n\t</format>\r\n</document>"));
        int fonts = file.indexOf("\t<font ");
        assertTrue(file.indexOf("\t<namedItem") < file.indexOf("\t<line") && file.indexOf("\t<line") < fonts
                && fonts < file.indexOf("\t<format>"), "the order of the root's children follows EDT");
    }

    @Test void equalFormatsAndFontsAreWrittenOnce() {
        Cell header = styled(text("A"), true, null, "Center", true, null, null);
        Layout layout = new Layout(Arrays.asList(100, 100, null), List.of(area("Таблица",
                row(header, styled(text("B"), true, null, "Center", true, null, null), styled(text("C"), true, 14, null, null, null, null)),
                row(text("plain"), text(""), parameter("P")))), List.of());
        String file = SpreadsheetLayout.write(layout);
        // width 100 shared by two columns, the bold centred border, the bold 14, the parameter, the default
        assertEquals(5, count(file, "<format>"));
        assertEquals(2, count(file, "\t<font "));
        assertTrue(file.contains("<font faceName=\"Arial\" height=\"10\" bold=\"true\" italic=\"false\" underline=\"false\" "
                + "strikeout=\"false\" kind=\"Absolute\" scale=\"100\"/>"));
        assertEquals(2, count(file, "<formatIndex>1</formatIndex>"));
        assertTrue(file.contains("\t<format>\r\n\t\t<font>0</font>\r\n\t\t<border>0</border>\r\n"
                + "\t\t<horizontalAlignment>Center</horizontalAlignment>\r\n\t</format>"));
        assertTrue(file.contains("\t<format>\r\n\t\t<fillType>Parameter</fillType>\r\n\t</format>"));
        assertEquals(2, count(file, "<f>0</f>"), "a cell without a style of its own has format 0 and no font");
    }

    @Test void explicitDefaultsWriteTheSameFileAsNulls() {
        Cell spelled = new Cell("Итого", null, null, false, 10, null, false, 1, false);
        Layout explicit = new Layout(Arrays.asList(72, null), List.of(area("Итог", row(spelled))), List.of());
        Layout implicit = new Layout(Arrays.asList(null, null), List.of(area("Итог", row(text("Итого")))), List.of());
        assertEquals(SpreadsheetLayout.write(implicit), SpreadsheetLayout.write(explicit));
        assertEquals(implicit, SpreadsheetLayout.parse(SpreadsheetLayout.write(explicit)));
        Layout lowerCase = new Layout(null, List.of(area("A", row(styled(text("x"), null, null, "center", null, null, null)))), List.of());
        assertTrue(SpreadsheetLayout.write(lowerCase).contains("<horizontalAlignment>Center</horizontalAlignment>"),
                "the alignment is matched without case");
    }

    @Test void badLayoutsAreRefusedWithTheReason() {
        assertRefused("the layout has no areas", new Layout(null, List.of(), List.of()));
        assertRefused("has no name", new Layout(null, List.of(area(null, row())), List.of()));
        assertRefused("not a 1C identifier: \"1Шапка\"", new Layout(null, List.of(area("1Шапка", row())), List.of()));
        assertRefused("area name repeats: шапка (Шапка is already there",
                new Layout(null, List.of(area("Шапка", row()), area("шапка", row())), List.of()));
        assertRefused("area Пусто has no rows", new Layout(null, List.of(new Area("Пусто", List.of())), List.of()));
        assertRefused("area Шапка, rows[0][1]: set exactly one of text, parameter, template (2 set)",
                new Layout(null, List.of(area("Шапка", row(text("a"), new Cell("b", "B", null, null, null, null, null, null, null)))), List.of()));
        assertRefused("rows[0][0]: set exactly one of text, parameter, template (0 set)",
                new Layout(null, List.of(area("Шапка", row(new Cell(null, null, null, true, null, null, null, null, null)))), List.of()));
        assertRefused("parameter is not a 1C identifier: \"Сумма документа\"",
                new Layout(null, List.of(area("Строка", row(parameter("Сумма документа")))), List.of()));
        assertRefused("area Шапка, rows[0]: the cells take 4 columns, the layout has 3",
                new Layout(Arrays.asList(10, 20, 30), List.of(area("Шапка", row(text("a"), styled(text("b"), null, null, null, null, 3, null)))), List.of()));
        assertRefused("unknown align value: Justify; allowed: Left, Center, Right",
                new Layout(null, List.of(area("Шапка", row(styled(text("a"), null, null, "Justify", null, null, null)))), List.of()));
        assertRefused("colspan 0 is less than 1",
                new Layout(null, List.of(area("Шапка", row(styled(text("a"), null, null, null, null, 0, null)))), List.of()));
        assertRefused("fontSize 500 is out of range",
                new Layout(null, List.of(area("Шапка", row(styled(text("a"), null, 500, null, null, null, null)))), List.of()));
        assertRefused("columns[1]: width 0 is out of range",
                new Layout(Arrays.asList(10, 0), List.of(area("Шапка", row())), List.of()));
        assertRefused("control character U+0007", new Layout(null, List.of(area("Шапка", row(text("a\u0007b")))), List.of()));
    }

    @Test void sizeIsLimited() {
        assertRefused("the layout has 201 columns; at most 200 are supported",
                new Layout(Collections.nCopies(201, null), List.of(area("A", row())), List.of()));
        assertRefused("the layout has 201 columns; at most 200 are supported",
                new Layout(null, List.of(area("A", row(styled(text("wide"), null, null, null, null, 201, null)))), List.of()));
        List<List<Cell>> rows = new ArrayList<>(Collections.nCopies(2001, List.of()));
        assertRefused("the layout has 2001 rows; at most 2000 are supported", new Layout(null, List.of(new Area("A", rows)), List.of()));
        String tall = SpreadsheetLayout.write(new Layout(null, List.of(new Area("A", rows.subList(0, 2000))), List.of()))
                .replace("<height>2000</height>", "<height>2001</height>");
        var refusal = assertThrows(IllegalArgumentException.class, () -> SpreadsheetLayout.parse(tall));
        assertTrue(refusal.getMessage().contains("2001 rows; at most 2000"), refusal.getMessage());
    }

    /** An EDT-shaped template made up for the test: the corpus of real templates is not ours to ship. */
    static final String FIXTURE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
            + "<document xmlns=\"http://v8.1c.ru/8.2/data/spreadsheet\" xmlns:v8=\"http://v8.1c.ru/8.1/data/core\" "
            + "xmlns:v8ui=\"http://v8.1c.ru/8.1/data/ui\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
            + "<columns><size>3</size><columnsItem><index>1</index><column><formatIndex>1</formatIndex></column></columnsItem></columns>"
            + "<columns><id>2f3c1a9e-0000-4000-8000-000000000001</id><size>2</size></columns>"
            + "<rowsItem><index>0</index><row><c><c><f>2</f><tl><v8:item><v8:lang>ru</v8:lang><v8:content>Акт сверки</v8:content>"
            + "</v8:item></tl></c></c></row></rowsItem>"
            + "<rowsItem><index>1</index><indexTo>2</indexTo><row><empty>true</empty></row></rowsItem>"
            + "<rowsItem><index>3</index><row><formatIndex>6</formatIndex><c><i>1</i><c><f>3</f><parameter>Контрагент</parameter>"
            + "<detailParameter>Ссылка</detailParameter></c></c><c><c><f>0</f><tl><v8:item><v8:lang>en</v8:lang><v8:content>x</v8:content>"
            + "</v8:item><v8:item><v8:lang>ru</v8:lang><v8:content>a &amp; b</v8:content></v8:item></tl></c></c></row></rowsItem>"
            + "<rowsItem><index>4</index><row><columnsID>2f3c1a9e-0000-4000-8000-000000000001</columnsID><c><c><f>4</f></c></c>"
            + "<c><c><f>5</f><tl><v8:item><v8:lang>ru</v8:lang><v8:content>Итого [Сумма]</v8:content></v8:item></tl></c></c>"
            + "</row></rowsItem>"
            + "<templateMode>true</templateMode><defaultFormatIndex>7</defaultFormatIndex><height>6</height><vgRows>6</vgRows>"
            + "<merge><r>0</r><c>0</c><w>2</w></merge><merge><r>4</r><c>0</c><h>1</h></merge>"
            + named("Заголовок", "Rows", 0, 0, -1, -1) + named("Таблица", "Rows", 3, 4, -1, -1)
            + named("Строка", "Rows", 3, 3, -1, -1) + named("Ячейка", "Rectangle", 4, 4, 1, 1)
            + "<line width=\"1\" gap=\"false\"><v8ui:style xsi:type=\"v8ui:SpreadsheetDocumentCellLineType\">Solid</v8ui:style></line>"
            + "<font faceName=\"Arial\" height=\"12\" bold=\"true\" italic=\"false\" underline=\"false\" strikeout=\"false\" kind=\"Absolute\" scale=\"100\"/>"
            + "<font faceName=\"Arial\" height=\"10\" bold=\"false\" italic=\"true\" underline=\"false\" strikeout=\"false\" kind=\"Absolute\" scale=\"100\"/>"
            + "<format><width>150</width></format>"
            + "<format><font>0</font><width>300</width><horizontalAlignment>Center</horizontalAlignment></format>"
            + "<format><border>0</border><textPlacement>Wrap</textPlacement><fillType>Parameter</fillType></format>"
            + "<format><topBorder>0</topBorder><backColor>style:ReportHeaderBackColor</backColor></format>"
            + "<format><font>1</font><leftBorder>0</leftBorder><topBorder>0</topBorder><rightBorder>0</rightBorder>"
            + "<bottomBorder>0</bottomBorder><horizontalAlignment>Right</horizontalAlignment><fillType>Template</fillType></format>"
            + "<format><height>30</height></format>"
            + "<format><width>72</width></format>"
            + "</document>";

    static String named(String name, String type, int beginRow, int endRow, int beginColumn, int endColumn) {
        return "<namedItem xsi:type=\"NamedItemCells\"><name>" + name + "</name><area><type>" + type + "</type><beginRow>"
                + beginRow + "</beginRow><endRow>" + endRow + "</endRow><beginColumn>" + beginColumn + "</beginColumn>"
                + "<endColumn>" + endColumn + "</endColumn></area></namedItem>";
    }

    @Test void existingTemplateIsReadIntoAreasAndWhatIsLeftBehindIsListed() {
        Layout layout = SpreadsheetLayout.parse(FIXTURE);
        assertEquals(Arrays.asList(null, 150, null), layout.columns());
        Area title = area("Заголовок", row(styled(text("Акт сверки"), true, 12, "Center", null, 3, null)));
        Area gap = area("", row(), row());
        Area line = area("Строка", row(null, styled(parameter("Контрагент"), null, null, null, true, null, true), text("a & b")));
        Area rest = area("", row(text(""), styled(template("Итого [Сумма]"), null, null, "Right", true, null, null)), row());
        assertEquals(List.of(title, gap, line, rest), layout.areas(), "the smaller of two nested areas is kept");
        assertEquals(List.of("backColor", "columnSets", "detailParameter", "italic", "languages", "overlappingArea",
                "partialBorder", "rectangleArea", "rowHeight", "verticalMerge"), layout.unsupported());

        Layout again = SpreadsheetLayout.parse(SpreadsheetLayout.write(layout));
        assertEquals(new Layout(layout.columns(), layout.areas(), List.of()), again);
    }

    @Test void foreignTextIsRefused() {
        var notXml = assertThrows(IllegalArgumentException.class, () -> SpreadsheetLayout.parse("<document"));
        assertTrue(notXml.getMessage().startsWith("the template is not well-formed XML"));
        var notSheet = assertThrows(IllegalArgumentException.class, () -> SpreadsheetLayout.parse("<document/>"));
        assertTrue(notSheet.getMessage().contains("not a spreadsheet document"));
        assertThrows(IllegalArgumentException.class, () -> SpreadsheetLayout.parse(" "));
    }

    /**
     * Every template of a local corpus is read, written back and read again to the same compact form.
     * The corpus is a folder of real templates named by MXL_CORPUS; without it the test is skipped.
     */
    @Test void corpusOfRealTemplatesSurvivesTheRoundTrip() throws IOException {
        String folder = System.getenv("MXL_CORPUS");
        Assumptions.assumeTrue(folder != null && Files.isDirectory(Path.of(folder)), "MXL_CORPUS names no folder");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Path.of(folder))) {
            files = walk.filter(path -> path.getFileName().toString().endsWith(".mxlx")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "no .mxlx under " + folder);
        TreeMap<String, Integer> kinds = new TreeMap<>();
        int areas = 0;
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Layout layout;
            try {
                layout = SpreadsheetLayout.parse(text);
                Layout again = SpreadsheetLayout.parse(SpreadsheetLayout.write(layout));
                assertEquals(layout.columns(), again.columns(), file.toString());
                assertEquals(layout.areas(), again.areas(), file.toString());
                assertTrue(again.unsupported().isEmpty(), file + ": " + again.unsupported());
                assertTrue(SpreadsheetRender.png(layout).length > 0);
            } catch (RuntimeException failure) {
                throw new AssertionError(file + ": " + failure, failure);
            }
            areas += layout.areas().size();
            for (String kind : layout.unsupported()) kinds.merge(kind, 1, Integer::sum);
        }
        System.out.println("mxlx corpus: " + files.size() + " templates, " + areas + " areas; unsupported kinds by templates: " + kinds);
    }

    static void assertRefused(String expected, Layout layout) {
        var refusal = assertThrows(IllegalArgumentException.class, () -> SpreadsheetLayout.write(layout));
        assertTrue(refusal.getMessage().contains(expected), refusal.getMessage());
    }

    static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) count++;
        return count;
    }

    static List<String> names(String file) {
        List<String> names = new ArrayList<>();
        Matcher matcher = Pattern.compile("<name>([^<]*)</name>").matcher(file);
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }
}
