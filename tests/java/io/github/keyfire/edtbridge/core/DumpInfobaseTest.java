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

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DumpInfobaseTest {
    @Test
    void emptySettingMeansNoInfobase() {
        assertNull(DumpInfobase.directory(null));
        assertNull(DumpInfobase.directory("  "));
    }

    @Test
    void directoryDataFileAndConnectionStringNameTheSameInfobase() {
        Path expected = Paths.get("base", "demo");
        assertEquals(expected, DumpInfobase.directory(expected.toString()));
        assertEquals(expected, DumpInfobase.directory(expected.resolve("1Cv8.1CD").toString()));
        assertEquals(expected, DumpInfobase.directory(expected.resolve("1cv8.1cd").toString()));
        assertEquals(expected, DumpInfobase.directory("File=\"" + expected + "\";"));
        assertEquals(expected, DumpInfobase.directory("file=" + expected + ";Locale=ru_RU;"));
    }

    @Test
    void directoryWithoutDataFileIsRefused(@TempDir Path temp) throws Exception {
        assertNotNull(DumpInfobase.unusable(null));
        String refusal = DumpInfobase.unusable(temp);
        assertNotNull(refusal);
        assertTrue(refusal.contains("1Cv8.1CD"));
        Files.write(temp.resolve("1Cv8.1CD"), new byte[] { 1 });
        assertNull(DumpInfobase.unusable(temp));
    }

    @Test
    void xdtoFailureIsRecognisedInBothPlatformLanguages() {
        assertTrue(DumpInfobase.isXdtoReadFailure(
                "Error loading document.\r\nXDTO exception while reading file C:\\x\\Forms\\Form\\Ext\\Form.xml."));
        assertTrue(DumpInfobase.isXdtoReadFailure(
                "Ошибка загрузки документа.\r\nИсключение XDTO произошло при чтении файла- C:\\x\\Form.xml."));
        assertFalse(DumpInfobase.isXdtoReadFailure("Load completed."));
        assertFalse(DumpInfobase.isXdtoReadFailure(null));
    }

    @Test
    void hintOnlyForXdtoFailureInEmptyInfobase() {
        String log = "Исключение XDTO произошло при чтении файла- C:\\x\\Form.xml.";
        String hint = DumpInfobase.hint(log, true);
        assertNotNull(hint);
        assertTrue(hint.contains(DumpInfobase.ENV));
        // A configured infobase resolved the types: the same failure has another cause there.
        assertNull(DumpInfobase.hint(log, false));
        assertNull(DumpInfobase.hint("Ошибка: нет лицензии", true));
    }

    @Test
    void failingTemplateIsBlamedOnTheTemplateEvenWithAConfiguredInfobase() {
        String log = "Исключение XDTO произошло при чтении файла- C:\\t\\xml\\ExternalReports\\R\\Templates\\Main\\Ext\\Template.xml.";
        String configured = DumpInfobase.hint(log, false);
        assertNotNull(configured);
        assertTrue(configured.contains("template"));
        assertFalse(configured.contains(DumpInfobase.ENV));
        String empty = DumpInfobase.hint(log, true);
        assertTrue(empty.startsWith("the failing file is a template"));
        assertTrue(empty.contains(DumpInfobase.ENV));
        assertNotNull(DumpInfobase.hint("XDTO exception while reading file - /t/xml/Reports/R/Templates/Main/Ext/Template.xml", false));
    }
}
