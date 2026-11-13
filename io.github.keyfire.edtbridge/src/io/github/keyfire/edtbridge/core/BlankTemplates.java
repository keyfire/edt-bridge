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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Spreadsheet templates that a designer-file export leaves without content.
 *
 * <p>EDT keeps an empty spreadsheet template as a zero-length {@code Template.mxlx}, and its export to
 * designer files writes it as a {@code Template.xml} that holds only the byte order mark. DESIGNER
 * refuses such a file ("Ошибка разбора XML: - [1,1]") and the whole build stops on it. {@link #fill}
 * writes the empty spreadsheet document DESIGNER itself dumps for an empty template in its place.
 */
public final class BlankTemplates {
    private static final String NL = "\r\n";

    /** What DESIGNER 8.5 dumps for an empty spreadsheet template: CRLF line ends, no final line end. */
    static final String EMPTY_SPREADSHEET = SpreadsheetLayout.HEAD
            + "\t<columns>" + NL
            + "\t\t<size>0</size>" + NL
            + "\t</columns>" + NL
            + "\t<rowsItem>" + NL
            + "\t\t<index>0</index>" + NL
            + "\t\t<row>" + NL
            + "\t\t\t<empty>true</empty>" + NL
            + "\t\t</row>" + NL
            + "\t</rowsItem>" + NL
            + "\t<templateMode>true</templateMode>" + NL
            + "\t<defaultFormatIndex>1</defaultFormatIndex>" + NL
            + "\t<vgRows>0</vgRows>" + NL
            + "\t<format>" + NL
            + "\t\t<width>72</width>" + NL
            + "\t</format>" + NL
            + "</document>";

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String SPREADSHEET_TYPE = "<TemplateType>SpreadsheetDocument</TemplateType>";
    /** A template's content file is far smaller than this; a larger one is not blank, so it is not read. */
    private static final long BLANK_LIMIT = 64;

    private BlankTemplates() { }

    /**
     * Writes the empty spreadsheet document into every blank {@code <Name>/Ext/Template.xml} under the
     * export directory whose template description {@code <Name>.xml} says SpreadsheetDocument. Other
     * template kinds and non-blank files stay as they are.
     *
     * @return the files written, relative to the export directory; empty when none needed it
     */
    public static List<String> fill(Path exportDir) throws IOException {
        List<String> filled = new ArrayList<>();
        if (exportDir == null || !Files.isDirectory(exportDir)) return filled;
        List<Path> candidates;
        try (Stream<Path> files = Files.walk(exportDir)) {
            candidates = files.filter(BlankTemplates::contentFile).sorted().toList();
        }
        for (Path content : candidates) {
            if (Files.size(content) > BLANK_LIMIT || !blank(Files.readAllBytes(content))) continue;
            Path owner = content.getParent().getParent();
            Path description = owner.resolveSibling(owner.getFileName() + ".xml");
            if (!Files.isRegularFile(description)) continue;
            if (!Files.readString(description, StandardCharsets.UTF_8).contains(SPREADSHEET_TYPE)) continue;
            byte[] text = EMPTY_SPREADSHEET.getBytes(StandardCharsets.UTF_8);
            byte[] bytes = new byte[BOM.length + text.length];
            System.arraycopy(BOM, 0, bytes, 0, BOM.length);
            System.arraycopy(text, 0, bytes, BOM.length, text.length);
            Files.write(content, bytes);
            filled.add(exportDir.relativize(content).toString().replace('\\', '/'));
        }
        return filled;
    }

    /** {@code Template.xml} directly in an {@code Ext} directory: the content file of a template. */
    private static boolean contentFile(Path path) {
        Path parent = path.getParent();
        return parent != null && parent.getParent() != null
                && "Template.xml".equals(String.valueOf(path.getFileName()))
                && "Ext".equals(String.valueOf(parent.getFileName()))
                && Files.isRegularFile(path);
    }

    /** No content: nothing, or only a byte order mark and white space. */
    static boolean blank(byte[] content) {
        int start = content.length >= BOM.length && content[0] == BOM[0] && content[1] == BOM[1]
                && content[2] == BOM[2] ? BOM.length : 0;
        for (int index = start; index < content.length; index++) {
            if (!Character.isWhitespace(content[index])) return false;
        }
        return true;
    }
}
