/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/** EDT-DCS-03: what a schema text gets wrong, found when it is written instead of when the report runs. */
class DcsCheckTest {
    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<DataCompositionSchema xmlns=\"http://v8.1c.ru/8.1/data-composition-system/schema\" "
            + "xmlns:dcsset=\"http://v8.1c.ru/8.1/data-composition-system/settings\" "
            + "xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" "
            + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n";
    private static final String SOURCE = "\t<dataSource>\n\t\t<name>ИсточникДанных1</name>\n"
            + "\t\t<dataSourceType>Local</dataSourceType>\n\t</dataSource>\n";
    private static final String SET = "\t<dataSet xsi:type=\"DataSetQuery\">\n\t\t<name>Заказы</name>\n"
            + "\t\t<dataSource>ИсточникДанных1</dataSource>\n\t\t<query>ВЫБРАТЬ 1 КАК Число</query>\n\t</dataSet>\n";
    private static final String VARIANT = "\t<settingsVariant>\n\t\t<dcsset:name>Основной</dcsset:name>\n"
            + "\t\t<dcsset:settings>\n"
            + "\t\t\t<dcsset:item xsi:type=\"dcsset:StructureItemGroup\">\n"
            + "\t\t\t\t<dcsset:selection>\n\t\t\t\t\t<dcsset:item xsi:type=\"dcsset:SelectedItemAuto\"/>\n"
            + "\t\t\t\t</dcsset:selection>\n\t\t\t</dcsset:item>\n"
            + "\t\t</dcsset:settings>\n\t</settingsVariant>\n";
    private static final String GOOD = HEAD + SOURCE + SET + VARIANT + "</DataCompositionSchema>\n";

    @Test void aWorkingSchemaHasNoProblems() {
        assertEquals(List.of(), DcsCheck.check(GOOD));
    }

    @Test void aUtf8BomDoesNotMakeASchemaUnreadable() {
        assertEquals(List.of(), DcsCheck.check("\uFEFF" + GOOD));
    }

    /** Seen in a model session: forming the report stopped with "the data source of the data set is not set". */
    @Test void aDataSetWithoutADataSourceIsAnErrorOnItsLine() {
        String text = GOOD.replace("\t\t<dataSource>ИсточникДанных1</dataSource>\n", "");
        var problems = DcsCheck.check(text);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).error());
        assertTrue(problems.get(0).message().startsWith("data set Заказы has no dataSource"), problems.get(0).message());
        assertTrue(problems.get(0).message().endsWith("the schema declares ИсточникДанных1"), problems.get(0).message());
        assertEquals(8, problems.get(0).line());
    }

    @Test void aDataSourceTheSchemaDoesNotDeclareIsAnError() {
        var problems = DcsCheck.check(GOOD.replace("<dataSource>ИсточникДанных1</dataSource>", "<dataSource>Другой</dataSource>"));
        assertTrue(problems.get(0).message().contains("names data source Другой, which the schema does not declare"),
                problems.toString());
    }

    @Test void theSetsOfAUnionAreCheckedToo() {
        String union = "\t<dataSet xsi:type=\"DataSetUnion\">\n\t\t<name>Все</name>\n"
                + "\t\t<item xsi:type=\"DataSetQuery\">\n\t\t\t<name>Часть</name>\n\t\t\t<query>ВЫБРАТЬ 1</query>\n"
                + "\t\t</item>\n\t</dataSet>\n";
        var problems = DcsCheck.check(HEAD + SOURCE + union + VARIANT + "</DataCompositionSchema>\n");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).message().startsWith("data set Часть has no dataSource"));
    }

    /** Seen in a model session: the report output "Параметры:" and nothing else. */
    @Test void aVariantWithoutStructureIsAWarning() {
        String flat = GOOD.replaceAll("(?s)\t\t\t<dcsset:item xsi:type=\"dcsset:StructureItemGroup\">.*?\n\t\t\t</dcsset:item>\n", "");
        var problems = DcsCheck.check(flat);
        assertEquals(1, problems.size(), problems.toString());
        assertFalse(problems.get(0).error());
        assertTrue(problems.get(0).message().startsWith("settings variant Основной has no structure item"));
        assertEquals(13, problems.get(0).line());
    }

    /** Seen in a model session: the build stopped with an XDTO exception and no place in the file. */
    @Test void textThatIsNoSchemaIsAnErrorWithItsPlace() {
        var broken = DcsCheck.check(GOOD.replace("</query>", "</qery>"));
        assertEquals(1, broken.size());
        assertTrue(broken.get(0).message().startsWith("not well-formed XML"), broken.get(0).message());
        assertEquals(10, broken.get(0).line());
        assertTrue(broken.get(0).column() > 0);

        var root = DcsCheck.check("<?xml version=\"1.0\"?>\n<Schema/>\n");
        assertTrue(root.get(0).message().startsWith("the root is Schema"), root.toString());
        assertTrue(DcsCheck.check("  ").get(0).error());
    }

    /** EDT's reader writes the place into its message, as it did in a live EDT 2026.2. */
    @Test void thePlaceIsTakenFromTheWordsOfEdtsReader() {
        String message = "Wrong element: {http://v8.1c.ru/8.1/data-composition-system/schema}unknownElement, "
                + "Line number = 14\nColumn number = 19\nSystem Id = null\nPublic Id = null";
        assertEquals(14, DcsCheck.numberAfter(message, "Line number = "));
        assertEquals(19, DcsCheck.numberAfter(message, "Column number = "));
        assertEquals(0, DcsCheck.numberAfter("no place", "Line number = "));
    }

    @Test void aSchemaKeptOnItsPreviousVersionIsNamedInTheHint() {
        String hint = MarkerFreshness.hint(2, 0, true, 2);
        assertTrue(hint.contains("2 of them sit on a data composition schema"), hint);
        assertTrue(hint.contains("edt_replace_dcs or edt_modify_dcs"), hint);
        assertFalse(hint.contains("edt_clean_project"), hint);
        assertTrue(MarkerFreshness.hint(2, 0, true).contains("edt_clean_project"));
    }
}
