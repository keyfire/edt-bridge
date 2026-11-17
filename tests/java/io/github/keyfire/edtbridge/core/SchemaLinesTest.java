/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** EDT-DCS-04: a search over the whole text of a schema shows each match with the element around it. */
class SchemaLinesTest {
    private static final String SCHEMA = "<DataCompositionSchema>\r\n"
            + "\t<dataSet xsi:type=\"DataSetQuery\">\r\n"
            + "\t\t<field xsi:type=\"DataSetFieldField\">\r\n"
            + "\t\t\t<dataPath>Сумма</dataPath>\r\n"
            + "\t\t\t<appearance>\r\n"
            + "\t\t\t\t<dcscor:parameter>Формат</dcscor:parameter>\r\n"
            + "\t\t\t\t<v8:content>ЧДЦ=2</v8:content>\r\n"
            + "\t\t\t</appearance>\r\n"
            + "\t\t</field>\r\n"
            + "\t</dataSet>\r\n"
            + "\t<dcsset:item>\r\n"
            + "\t\t<dcsset:viewMode>QuickAccess</dcsset:viewMode>\r\n"
            + "\t</dcsset:item>\r\n"
            + "</DataCompositionSchema>";

    @Test void aMatchComesWithItsLineAndTheLinesAroundIt() {
        List<SchemaLines.Match> found = SchemaLines.find(SCHEMA, Pattern.compile(Pattern.quote("ЧДЦ=")), 2);
        assertEquals(1, found.size());
        SchemaLines.Match match = found.get(0);
        assertEquals(7, match.line());
        assertEquals("<v8:content>ЧДЦ=2</v8:content>", match.text());
        assertEquals("<appearance>\n<dcscor:parameter>Формат</dcscor:parameter>\n<v8:content>ЧДЦ=2</v8:content>\n"
                + "</appearance>\n</field>", match.fragment());
    }

    @Test void everyMatchingLineIsAHitAndTheEdgesOfTheTextAreKept() {
        Pattern any = SearchPattern.compile("quickaccess|DataCompositionSchema", false);
        List<SchemaLines.Match> found = SchemaLines.find(SCHEMA, any, 1);
        assertEquals(List.of(1, 12, 14), found.stream().map(SchemaLines.Match::line).toList());
        assertEquals("<DataCompositionSchema>\n<dataSet xsi:type=\"DataSetQuery\">", found.get(0).fragment());
        assertEquals("</dcsset:item>\n</DataCompositionSchema>", found.get(2).fragment());
        assertTrue(SchemaLines.find("", any, 2).isEmpty());
        assertEquals("<dcsset:viewMode>QuickAccess</dcsset:viewMode>",
                SchemaLines.find(SCHEMA, any, 0).get(1).fragment());
    }
}
