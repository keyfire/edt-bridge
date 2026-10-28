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
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class ExternalObjectSourceTest {
    @Test void readsProcessorXmlIncludingUnicodeBom(@TempDir Path dir) throws Exception {
        Path xml=dir.resolve("object.xml");
        String text="<MetaDataObject xmlns=\"http://v8.1c.ru/8.3/MDClasses\"><ExternalDataProcessor><Properties><Name>Demo</Name></Properties></ExternalDataProcessor></MetaDataObject>";
        for (var encoding : java.util.List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
            Files.write(xml,("\uFEFF"+text).getBytes(encoding));
            var source=ExternalObjectSource.read(xml);
            assertEquals("ExternalDataProcessor",source.kind());
            assertEquals("Demo",source.name());
        }
    }
    @Test void readsReportAndRejectsOtherMetadata(@TempDir Path dir) throws Exception {
        Path xml=Files.writeString(dir.resolve("object.xml"),"<MetaDataObject><ExternalReport><Properties><Name>Demo</Name></Properties></ExternalReport></MetaDataObject>");
        assertEquals("ExternalReport",ExternalObjectSource.read(xml).kind());
        Files.writeString(xml,"<MetaDataObject><Catalog><Properties><Name>Demo</Name></Properties></Catalog></MetaDataObject>");
        assertThrows(java.io.IOException.class,()->ExternalObjectSource.read(xml));
    }
    @Test void readsTheRootNameRatherThanAChildName(@TempDir Path dir) throws Exception {
        Path xml=Files.writeString(dir.resolve("object.xml"),"<MetaDataObject><ExternalReport><ChildObjects><Form><Properties><Name>Child</Name></Properties></Form></ChildObjects><Properties><Name>Root</Name></Properties></ExternalReport></MetaDataObject>");
        assertEquals("Root",ExternalObjectSource.read(xml).name());
    }
    @Test void refusesExternalEntities(@TempDir Path dir) throws Exception {
        Path secret=Files.writeString(dir.resolve("secret.txt"),"must not be read");
        Path xml=Files.writeString(dir.resolve("object.xml"),"<!DOCTYPE x [<!ENTITY ex SYSTEM \""+secret.toUri()+"\">]><MetaDataObject><ExternalReport><Properties><Name>&ex;</Name></Properties></ExternalReport></MetaDataObject>");
        assertThrows(java.io.IOException.class,()->ExternalObjectSource.read(xml));
    }
}
