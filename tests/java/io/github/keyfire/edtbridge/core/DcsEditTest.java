/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class DcsEditTest {
    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
            + "<DataCompositionSchema xmlns=\"http://v8.1c.ru/8.1/data-composition-system/schema\" "
            + "xmlns:v8=\"http://v8.1c.ru/8.1/data/core\" xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" "
            + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\r\n";
    private static final String SET = "\t<dataSet xsi:type=\"DataSetQuery\">\r\n"
            + "\t\t<name>Sales</name>\r\n"
            + "\t\t<field xsi:type=\"DataSetFieldField\">\r\n"
            + "\t\t\t<dataPath>Amount</dataPath>\r\n"
            + "\t\t\t<field>Amount</field>\r\n"
            + "\t\t</field>\r\n"
            + "\t\t<dataSource>Source</dataSource>\r\n"
            + "\t\t<query>SELECT 1 AS Amount, &amp;Period AS Period</query>\r\n"
            + "\t</dataSet>\r\n";
    private static final String SETTINGS = "\t<settingsVariant>\r\n\t\t<name>Main</name>\r\n\t</settingsVariant>\r\n";
    private static final String SCHEMA = HEAD + SET + SETTINGS + "</DataCompositionSchema>";

    private static DcsEdit.Operation field(String set, String path, String title) {
        return new DcsEdit.Operation("addField", set, path, null, null, title, null);
    }

    private static DcsEdit.Operation parameter(String name, String type) {
        return new DcsEdit.Operation("addParameter", null, null, null, name, null, type);
    }

    @Test void fieldGoesAfterTheLastFieldOfItsSetAndTheRestKeepsItsBytes() {
        var result = DcsEdit.apply(SCHEMA, List.of(field("sales", "Period", "Период & дата")));
        String text = result.text();
        int added = text.indexOf("<dataPath>Period</dataPath>");
        assertTrue(added > text.indexOf("<dataPath>Amount</dataPath>"));
        assertTrue(added < text.indexOf("<dataSource>Source</dataSource>"));
        assertTrue(text.contains("<v8:content>Период &amp; дата</v8:content>"));
        assertEquals(SCHEMA, text.replaceFirst("(?s)\r\n\t\t<field xsi:type=\"DataSetFieldField\">\r\n\t\t\t<dataPath>Period.*?\t\t</field>", ""));
        assertFalse(text.replace("\r\n", "").contains("\n"), "the CRLF file gets no bare line feeds");
        assertEquals(List.of("add field Period to data set Sales"), result.plan());
    }

    @Test void parameterGoesBeforeSettingsVariantsWithTypedValue() {
        String text = DcsEdit.apply(SCHEMA, List.of(parameter("Period", "StandardPeriod"),
                parameter("Limit", "Число(15,2)"))).text();
        int period = text.indexOf("<name>Period</name>");
        int limit = text.indexOf("<name>Limit</name>");
        assertTrue(period > text.indexOf("</dataSet>") && period < limit && limit < text.indexOf("<settingsVariant>"));
        assertTrue(text.contains("<v8:Type>v8:StandardPeriod</v8:Type>"));
        assertTrue(text.contains("<v8:Digits>15</v8:Digits>") && text.contains("<v8:FractionDigits>2</v8:FractionDigits>"));
        assertTrue(text.contains("<value xsi:type=\"xs:decimal\">0</value>"));
    }

    @Test void parameterOfAnEmptySchemaGoesBeforeTheRootEnd() {
        String empty = HEAD + "</DataCompositionSchema>";
        String text = DcsEdit.apply(empty, List.of(parameter("Flag", "Булево"))).text();
        assertTrue(text.indexOf("<name>Flag</name>") < text.indexOf("</DataCompositionSchema>"));
        assertTrue(text.contains("<value xsi:type=\"xs:boolean\">false</value>"));
    }

    @Test void duplicatesAndUnknownTargetsAreRefusedWithWhatExists() {
        var duplicate = assertThrows(IllegalArgumentException.class,
                () -> DcsEdit.apply(SCHEMA, List.of(field("Sales", "amount", null))));
        assertTrue(duplicate.getMessage().contains("already has a field"));
        var missing = assertThrows(IllegalArgumentException.class,
                () -> DcsEdit.apply(SCHEMA, List.of(field("Stock", "Qty", null))));
        assertTrue(missing.getMessage().endsWith("the schema has: Sales"));
        var twice = assertThrows(IllegalArgumentException.class,
                () -> DcsEdit.apply(SCHEMA, List.of(parameter("P", "Date"), parameter("p", "Date"))));
        assertTrue(twice.getMessage().contains("already has a parameter"));
    }

    @Test void badInputIsRefusedBeforeAnyChange() {
        assertThrows(IllegalArgumentException.class, () -> DcsEdit.apply(SCHEMA, List.of(parameter("P", "Money"))));
        assertThrows(IllegalArgumentException.class, () -> DcsEdit.apply(SCHEMA, List.of(parameter("1P", "Date"))));
        assertThrows(IllegalArgumentException.class, () -> DcsEdit.apply(SCHEMA, List.of(field("Sales", "a b", null))));
        assertThrows(IllegalArgumentException.class, () -> DcsEdit.apply(SCHEMA, List.of(
                new DcsEdit.Operation("renameField", "Sales", "Amount", null, null, null, null))));
        assertThrows(IllegalArgumentException.class, () -> DcsEdit.apply("<root/>", List.of(parameter("P", "Date"))));
        assertThrows(IllegalArgumentException.class, () -> DcsEdit.apply(SCHEMA.replace("</dataSet>", ""), List.of(parameter("P", "Date"))));
    }

    @Test void schemaOnOneLineIsRefusedAsNotInTheDesignerLayout() {
        String flat = SCHEMA.replace("\r\n", "").replace("\t", "");
        var refusal = assertThrows(IllegalArgumentException.class,
                () -> DcsEdit.apply(flat, List.of(field("Sales", "Period", null))));
        assertTrue(refusal.getMessage().contains("designer layout"));
    }

    // EDT-DCS-02: changes of a part of the schema without its whole text.
    private static final String ROOT_PREFIXES = "xmlns:dcscor=\"http://v8.1c.ru/8.1/data-composition-system/core\" "
            + "xmlns:dcsset=\"http://v8.1c.ru/8.1/data-composition-system/settings\" ";
    private static final String FULL = SCHEMA.replace("xmlns:v8=", ROOT_PREFIXES + "xmlns:v8=");

    private static DcsEdit.Operation edit(String action, String set, String path) {
        return new DcsEdit.Operation(action, set, path, null, null, null, null);
    }

    @Test void setQueryReplacesTheQueryOfItsSetWithTheFileLineEndings() {
        var op = new DcsEdit.Operation("setQuery", "sales", null, null, null, null, null,
                "SELECT 2 AS Amount\nWHERE Amount > 0 AND &Period <> 0", null, null, null);
        var result = DcsEdit.apply(SCHEMA, List.of(op));
        assertTrue(result.text().contains("\t\t<query>SELECT 2 AS Amount\r\nWHERE Amount &gt; 0 AND &amp;Period &lt;&gt; 0</query>\r\n"));
        assertFalse(result.text().contains("SELECT 1 AS Amount"));
        assertEquals(SCHEMA, result.text().replaceFirst("(?s)<query>.*?</query>",
                "<query>SELECT 1 AS Amount, &amp;Period AS Period</query>"));
        assertEquals(List.of("set the query of data set Sales (2 lines)"), result.plan());
    }

    @Test void setQueryNeedsAQueryDataSet() {
        String objectSet = SCHEMA.replace("DataSetQuery", "DataSetObject").replace(
                "\t\t<query>SELECT 1 AS Amount, &amp;Period AS Period</query>\r\n", "");
        var refusal = assertThrows(IllegalArgumentException.class, () -> DcsEdit.apply(objectSet, List.of(
                new DcsEdit.Operation("setQuery", "Sales", null, null, null, null, null, "SELECT 1", null, null, null))));
        assertTrue(refusal.getMessage().contains("has no query"), refusal.getMessage());
    }

    @Test void replaceFragmentNeedsExactlyOneOccurrence() {
        var op = new DcsEdit.Operation("replaceFragment", null, null, null, null, null, null, null,
                "SELECT 1 AS Amount", "SELECT 3 AS Amount", null);
        var result = DcsEdit.apply(SCHEMA, List.of(op));
        assertEquals(SCHEMA.replace("SELECT 1", "SELECT 3"), result.text());

        var none = assertThrows(IllegalArgumentException.class, () -> DcsEdit.apply(SCHEMA, List.of(
                new DcsEdit.Operation("replaceFragment", null, null, null, null, null, null, null, "SELECT 9", "x", null))));
        assertTrue(none.getMessage().contains("not found"), none.getMessage());
        var many = assertThrows(IllegalArgumentException.class, () -> DcsEdit.apply(SCHEMA, List.of(
                new DcsEdit.Operation("replaceFragment", null, null, null, null, null, null, null, "Amount", "Total", null))));
        assertTrue(many.getMessage().contains("occurs 3 times"), many.getMessage());
        assertTrue(many.getMessage().contains("lines 6, 7, 10"), many.getMessage());
    }

    @Test void aFragmentWrittenAsTheQueryReadsIsMatchedEscaped() {
        // The file keeps & < > escaped; a fragment copied from the query text is found all the same.
        var op = new DcsEdit.Operation("replaceFragment", null, null, null, null, null, null, null,
                "&Period AS Period", "&Period AS Start, Amount > 1 AS Big", null);
        var result = DcsEdit.apply(SCHEMA, List.of(op));
        assertTrue(result.text().contains("&amp;Period AS Start, Amount &gt; 1 AS Big</query>"));
        assertTrue(result.plan().get(0).contains("escaped"), result.plan().toString());
    }

    @Test void aReplacementThatBreaksTheXmlIsRefused() {
        var op = new DcsEdit.Operation("replaceFragment", null, null, null, null, null, null, null,
                "</query>", "</qery>", null);
        var refusal = assertThrows(IllegalArgumentException.class, () -> DcsEdit.apply(SCHEMA, List.of(op)));
        assertTrue(refusal.getMessage().contains("well-formed"), refusal.getMessage());
    }

    @Test void setFieldAddsATitleAndAFormatInTheirPlaces() {
        var op = new DcsEdit.Operation("setField", "Sales", "amount", null, null, "Сумма", null, null, null, null, "ЧДЦ=2");
        var result = DcsEdit.apply(FULL, List.of(op));
        String text = result.text();
        int title = text.indexOf("<v8:content>Сумма</v8:content>");
        int format = text.indexOf("<dcscor:parameter>Формат</dcscor:parameter>");
        assertTrue(text.indexOf("<field>Amount</field>") < title && title < format);
        assertTrue(format < text.indexOf("\t\t</field>"));
        assertTrue(text.contains("\t\t\t<appearance>\r\n\t\t\t\t<dcscor:item xsi:type=\"dcsset:SettingsParameterValue\">"));
        assertTrue(text.contains("<v8:content>ЧДЦ=2</v8:content>"));
        assertEquals(List.of("set title \"Сумма\" and format \"ЧДЦ=2\" of field Amount in data set Sales"), result.plan());

        // A second call changes the values in place instead of adding a second title or format.
        var again = DcsEdit.apply(text, List.of(new DcsEdit.Operation("setField", "Sales", "Amount", null, null,
                "Итого", null, null, null, null, "ЧДЦ=0"))).text();
        assertEquals(1, again.split("<title ", -1).length - 1);
        assertEquals(1, again.split("<dcscor:parameter>Формат", -1).length - 1);
        assertTrue(again.contains("<v8:content>Итого</v8:content>") && again.contains("<v8:content>ЧДЦ=0</v8:content>"));
        assertFalse(again.contains("Сумма") || again.contains("ЧДЦ=2"));
    }

    @Test void setFieldKeepsTheOtherLanguagesOfTheTitle() {
        String titled = FULL.replace("\t\t\t<field>Amount</field>\r\n", "\t\t\t<field>Amount</field>\r\n"
                + "\t\t\t<title xsi:type=\"v8:LocalStringType\">\r\n\t\t\t\t<v8:item>\r\n\t\t\t\t\t<v8:lang>en</v8:lang>\r\n"
                + "\t\t\t\t\t<v8:content>Amount</v8:content>\r\n\t\t\t\t</v8:item>\r\n\t\t\t</title>\r\n");
        String text = DcsEdit.apply(titled, List.of(new DcsEdit.Operation("setField", "Sales", "Amount", null, null,
                "Сумма", null, null, null, null, null))).text();
        assertTrue(text.contains("<v8:lang>en</v8:lang>") && text.contains("<v8:content>Amount</v8:content>"));
        assertTrue(text.indexOf("<v8:lang>ru</v8:lang>") > text.indexOf("<v8:lang>en</v8:lang>"));
        assertTrue(text.indexOf("<v8:content>Сумма</v8:content>") < text.indexOf("</title>"));
    }

    @Test void setFieldReplacesMultilineRussianContentWithoutChangingAnotherTitle() {
        String title = "\t\t\t<title xsi:type=\"v8:LocalStringType\">\r\n"
                + "\t\t\t\t<v8:item>\r\n\t\t\t\t\t<v8:lang>en</v8:lang>\r\n"
                + "\t\t\t\t\t<v8:content>Amount\r\n(including tax)</v8:content>\r\n\t\t\t\t</v8:item>\r\n"
                + "\t\t\t\t<v8:item>\r\n\t\t\t\t\t<v8:lang>ru</v8:lang>\r\n"
                + "\t\t\t\t\t<v8:content>Старая\r\nсумма</v8:content>\r\n\t\t\t\t</v8:item>\r\n"
                + "\t\t\t</title>\r\n";
        String schema = SCHEMA.replace("\t\t\t<field>Amount</field>\r\n", "\t\t\t<field>Amount</field>\r\n" + title);
        var edit = new DcsEdit.Operation("setField", "Sales", "Amount", null, null, "Сумма & налог", null);
        assertEquals(schema.replace("Старая\r\nсумма", "Сумма &amp; налог"), DcsEdit.apply(schema, List.of(edit)).text());
    }

    @Test void setFieldFillsTheExistingSelfClosingTitle() {
        String empty = "\t\t\t<title xsi:type=\"v8:LocalStringType\"/>\r\n";
        String filled = "\t\t\t<title xsi:type=\"v8:LocalStringType\">\r\n"
                + "\t\t\t\t<v8:item>\r\n\t\t\t\t\t<v8:lang>ru</v8:lang>\r\n"
                + "\t\t\t\t\t<v8:content>Сумма</v8:content>\r\n\t\t\t\t</v8:item>\r\n\t\t\t</title>\r\n";
        String schema = SCHEMA.replace("\t\t\t<field>Amount</field>\r\n", "\t\t\t<field>Amount</field>\r\n" + empty);
        var edit = new DcsEdit.Operation("setField", "Sales", "Amount", null, null, "Сумма", null);
        assertEquals(schema.replace(empty, filled), DcsEdit.apply(schema, List.of(edit)).text());
    }

    @Test void editingASchemaWithABomKeepsItAndTheOriginalLineEndings() {
        String schema = "\uFEFF" + SCHEMA;
        var edit = new DcsEdit.Operation("setQuery", "Sales", null, null, null, null, null,
                "SELECT 2 AS Amount", null, null, null);
        assertEquals(schema.replace("SELECT 1 AS Amount, &amp;Period AS Period", "SELECT 2 AS Amount"),
                DcsEdit.apply(schema, List.of(edit)).text());
    }

    @Test void setFieldNeedsATitleOrAFormatAndAnExistingField() {
        assertThrows(IllegalArgumentException.class, () -> DcsEdit.apply(FULL, List.of(edit("setField", "Sales", "Amount"))));
        var missing = assertThrows(IllegalArgumentException.class, () -> DcsEdit.apply(FULL, List.of(
                new DcsEdit.Operation("setField", "Sales", "Qty", null, null, "Кол-во", null, null, null, null, null))));
        assertTrue(missing.getMessage().contains("has no field Qty; its fields: Amount"), missing.getMessage());
    }

    @Test void removeFieldTakesOutItsLinesAndNamesWhereItIsStillUsed() {
        String used = SCHEMA.replace("\t\t<name>Main</name>\r\n", "\t\t<name>Main</name>\r\n\t\t<field>Amount</field>\r\n");
        var result = DcsEdit.apply(used, List.of(edit("removeField", "Sales", "Amount")));
        assertFalse(result.text().contains("<dataPath>Amount</dataPath>"));
        assertEquals(used.replace("\t\t<field xsi:type=\"DataSetFieldField\">\r\n\t\t\t<dataPath>Amount</dataPath>\r\n"
                + "\t\t\t<field>Amount</field>\r\n\t\t</field>\r\n", ""), result.text());
        assertTrue(result.plan().get(0).startsWith("remove field Amount from data set Sales"));
        assertTrue(result.plan().get(0).contains("still named"), result.plan().toString());
    }
}
