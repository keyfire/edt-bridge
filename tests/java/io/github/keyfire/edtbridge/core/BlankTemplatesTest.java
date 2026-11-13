/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

class BlankTemplatesTest {
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @TempDir
    Path dir;

    /** A template as the export writes it: Templates/Name.xml with its type, Templates/Name/Ext/Template.xml. */
    private Path template(String folder, String name, String type, byte[] content) throws IOException {
        Path templates = dir.resolve("ExternalReports/Report/" + folder);
        Files.createDirectories(templates.resolve(name + "/Ext"));
        Files.writeString(templates.resolve(name + ".xml"), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<MetaDataObject><Template><Properties><Name>" + name + "</Name>\n"
                + "<TemplateType>" + type + "</TemplateType></Properties></Template></MetaDataObject>",
                StandardCharsets.UTF_8);
        Path file = templates.resolve(name + "/Ext/Template.xml");
        Files.write(file, content);
        return file;
    }

    @Test
    void aSpreadsheetTemplateWithOnlyTheByteOrderMarkGetsTheEmptyDocument() throws Exception {
        Path file = template("Templates", "Печать", "SpreadsheetDocument", BOM);

        List<String> filled = BlankTemplates.fill(dir);

        assertEquals(List.of("ExternalReports/Report/Templates/Печать/Ext/Template.xml"), filled);
        byte[] bytes = Files.readAllBytes(file);
        assertArrayEquals(BOM, java.util.Arrays.copyOf(bytes, 3), "the file keeps the byte order mark the export writes");
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertEquals(BlankTemplates.EMPTY_SPREADSHEET, text);
        assertFalse(text.endsWith("\n"), "no final line end, as DESIGNER writes it");
        assertFalse(text.replace("\r\n", "").contains("\n"), "CRLF line ends only");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        assertEquals("document", document.getDocumentElement().getLocalName());
        assertEquals(SpreadsheetLayout.NS, document.getDocumentElement().getNamespaceURI());
        assertEquals("0", document.getElementsByTagNameNS("*", "size").item(0).getTextContent());
    }

    @Test
    void aZeroLengthOrWhiteSpaceFileIsBlankToo() throws Exception {
        template("Templates", "Пустой", "SpreadsheetDocument", new byte[0]);
        template("Templates", "Пробелы", "SpreadsheetDocument", "\r\n  \t".getBytes(StandardCharsets.UTF_8));

        assertEquals(2, BlankTemplates.fill(dir).size());
    }

    @Test
    void aCommonTemplateOfAConfigurationIsFilledTheSameWay() throws Exception {
        Path common = dir.resolve("CommonTemplates");
        Files.createDirectories(common.resolve("Общий/Ext"));
        Files.writeString(common.resolve("Общий.xml"),
                "<MetaDataObject><CommonTemplate><Properties><TemplateType>SpreadsheetDocument</TemplateType>"
                        + "</Properties></CommonTemplate></MetaDataObject>", StandardCharsets.UTF_8);
        Files.write(common.resolve("Общий/Ext/Template.xml"), BOM);

        assertEquals(List.of("CommonTemplates/Общий/Ext/Template.xml"), BlankTemplates.fill(dir));
    }

    @Test
    void otherTemplateKindsAndFilesWithContentStayAsTheyAre() throws Exception {
        Path schema = template("Templates", "Схема", "DataCompositionSchema", BOM);
        byte[] filledSheet = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n<document xmlns=\"" + SpreadsheetLayout.NS
                + "\">\r\n</document>").getBytes(StandardCharsets.UTF_8);
        Path sheet = template("Templates", "Счет", "SpreadsheetDocument", filledSheet);

        assertEquals(List.of(), BlankTemplates.fill(dir));
        assertArrayEquals(BOM, Files.readAllBytes(schema), "a blank schema is not ours to invent");
        assertArrayEquals(filledSheet, Files.readAllBytes(sheet));
    }

    @Test
    void aBlankFileWithoutATemplateDescriptionIsLeftAlone() throws Exception {
        Path orphan = dir.resolve("Somewhere/Ext/Template.xml");
        Files.createDirectories(orphan.getParent());
        Files.write(orphan, BOM);

        assertEquals(List.of(), BlankTemplates.fill(dir));
        assertArrayEquals(BOM, Files.readAllBytes(orphan));
    }

    @Test
    void aMissingDirectoryHasNothingToFill() throws Exception {
        assertEquals(List.of(), BlankTemplates.fill(dir.resolve("absent")));
        assertEquals(List.of(), BlankTemplates.fill(null));
    }

    @Test
    void blankMeansNothingButTheByteOrderMarkAndWhiteSpace() {
        assertTrue(BlankTemplates.blank(new byte[0]));
        assertTrue(BlankTemplates.blank(BOM));
        assertTrue(BlankTemplates.blank(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, ' ', '\r', '\n'}));
        assertFalse(BlankTemplates.blank("<".getBytes(StandardCharsets.UTF_8)));
        assertFalse(BlankTemplates.blank(new byte[] {(byte) 0xEF, (byte) 0xBB}), "a broken mark is content");
    }
}
