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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The record of what the bridge last loaded into an infobase, file by file. */
class LoadSnapshotTest {

    private static void put(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("every file of an export is hashed under its '/'-separated path")
    void hashesTheTree(@TempDir Path dir) throws IOException {
        put(dir, "Configuration.xml", "<root/>");
        put(dir, "CommonModules/Календарь/Ext/Module.bsl", "Процедура А() КонецПроцедуры");
        put(dir, "CommonModules/Календарь.xml", "<module/>");

        LoadSnapshot s = LoadSnapshot.of(dir);

        assertEquals(List.of("CommonModules/Календарь.xml", "CommonModules/Календарь/Ext/Module.bsl",
                "Configuration.xml"), List.copyOf(s.files.keySet()));
        s.files.values().forEach(h -> assertEquals(64, h.length(), "SHA-256 in hex"));
    }

    @Test
    @DisplayName("the same content hashes the same and different content differently")
    void hashFollowsContent(@TempDir Path a, @TempDir Path b) throws IOException {
        put(a, "Module.bsl", "Возврат;");
        put(b, "Module.bsl", "Возврат;");
        assertEquals(LoadSnapshot.of(a).files, LoadSnapshot.of(b).files);

        put(b, "Module.bsl", "Возврат ;");
        assertNotEquals(LoadSnapshot.of(a).files, LoadSnapshot.of(b).files);
    }

    @Test
    @DisplayName("the export's version file is not configuration content and stays out")
    void dumpInfoIsLeftOut(@TempDir Path dir) throws IOException {
        put(dir, "Configuration.xml", "<root/>");
        put(dir, "ConfigDumpInfo.xml", "<versions/>");
        assertFalse(LoadSnapshot.of(dir).files.containsKey("ConfigDumpInfo.xml"));
    }

    private static LoadSnapshot sample() {
        LoadSnapshot s = new LoadSnapshot();
        s.target = LoadSnapshot.target("/FC:\\Базы\\Пустая база", null);
        s.platformVersion = "8.5.1.1529";
        s.generation = "99bf6cd4045c8f43a86244a0dab4f24000000000";
        s.files.put("Configuration.xml", "a".repeat(64));
        s.files.put("CommonModules/Общий модуль/Ext/Module.bsl", "b".repeat(64));
        return s;
    }

    @Test
    @DisplayName("a snapshot survives a round trip - Cyrillic and spaces in paths included")
    void roundTrip() {
        LoadSnapshot back = LoadSnapshot.parse(sample().format());
        assertEquals(sample().target, back.target);
        assertEquals("8.5.1.1529", back.platformVersion);
        assertEquals("99bf6cd4045c8f43a86244a0dab4f24000000000", back.generation);
        assertEquals(sample().files, back.files);
    }

    @Test
    @DisplayName("an unknown generation is simply absent, not written as the word null")
    void unknownGeneration() {
        LoadSnapshot s = sample();
        s.generation = null;
        assertFalse(s.format().contains("generation"));
        assertNull(LoadSnapshot.parse(s.format()).generation);
    }

    @Test
    @DisplayName("text that is not a snapshot, or holds a line this version does not know, reads as none")
    void rejectsWhatItDoesNotKnow() {
        assertNull(LoadSnapshot.parse(null));
        assertNull(LoadSnapshot.parse(""));
        assertNull(LoadSnapshot.parse("something else\n"));
        assertNull(LoadSnapshot.parse(LoadSnapshot.HEADER + "\nowner somebody\n"));
        assertNull(LoadSnapshot.parse(LoadSnapshot.HEADER + "\nnospace\n"));
    }

    @Test
    @DisplayName("written and read back through a file; nothing temporary is left beside it")
    void writeAndRead(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("load.snapshot");
        assertNull(LoadSnapshot.read(file), "no file, no snapshot");

        sample().write(file);
        assertEquals(sample().files, LoadSnapshot.read(file).files);
        assertFalse(Files.exists(dir.resolve("load.snapshot.tmp")));

        LoadSnapshot changed = sample();
        changed.generation = "0".repeat(40);
        changed.write(file);
        assertEquals("0".repeat(40), LoadSnapshot.read(file).generation, "a rewrite replaces the old one");
    }

    @Test
    @DisplayName("a file that is not readable as a snapshot reads as none rather than failing")
    void garbageReadsAsNone(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("load.snapshot");
        Files.write(file, new byte[] {(byte) 0xff, (byte) 0xfe, 0x00});
        assertNull(LoadSnapshot.read(file));
    }

    @Test
    @DisplayName("the target names the infobase without case and tells the configuration from an extension")
    void target() {
        assertEquals(LoadSnapshot.target("/SSrv.Example.Test\\SM", null),
                LoadSnapshot.target("/ssrv.example.test\\sm", ""));
        assertTrue(LoadSnapshot.target("/ssrv.example.test\\sm", null).endsWith(" configuration"));
        assertNotEquals(LoadSnapshot.target("/ssrv.example.test\\sm", null),
                LoadSnapshot.target("/ssrv.example.test\\sm", "Тест"));
    }

    @Test
    @DisplayName("the file name is stable for a target, differs between targets and is a plain name")
    void fileName() {
        String a = LoadSnapshot.fileName(LoadSnapshot.target("/ssrv.example.test\\sm", null));
        assertEquals(a, LoadSnapshot.fileName(LoadSnapshot.target("/ssrv.example.test\\sm", null)));
        assertNotEquals(a, LoadSnapshot.fileName(LoadSnapshot.target("/ssrv.example.test\\lib", null)));
        assertTrue(a.matches("load-[0-9a-f]{16}\\.snapshot"), a);
    }
}
