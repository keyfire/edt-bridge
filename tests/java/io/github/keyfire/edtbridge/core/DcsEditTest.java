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
                new DcsEdit.Operation("removeField", "Sales", "Amount", null, null, null, null))));
        assertThrows(IllegalArgumentException.class, () -> DcsEdit.apply("<root/>", List.of(parameter("P", "Date"))));
        assertThrows(IllegalArgumentException.class, () -> DcsEdit.apply(SCHEMA.replace("</dataSet>", ""), List.of(parameter("P", "Date"))));
    }

    @Test void schemaOnOneLineIsRefusedAsNotInTheDesignerLayout() {
        String flat = SCHEMA.replace("\r\n", "").replace("\t", "");
        var refusal = assertThrows(IllegalArgumentException.class,
                () -> DcsEdit.apply(flat, List.of(field("Sales", "Period", null))));
        assertTrue(refusal.getMessage().contains("designer layout"));
    }
}
