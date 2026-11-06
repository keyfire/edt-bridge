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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformBuildProbeTest {
    @TempDir Path temp;
    private final Path program = Path.of("install", "1cv8");
    private final Duration timeout = Duration.ofSeconds(10);

    @Test
    void validatesBothArtifactsAndRemovesTheTemporaryBase() throws IOException {
        var result = PlatformBuildProbe.check(program, temp, timeout, (exe, args, root, time) -> {
            assertEquals(program, exe);
            assertTrue(root.startsWith(temp));
            assertEquals(timeout, time);
            if (args.get(0).equals("CREATEINFOBASE")) {
                createDatabase(root);
            } else {
                assertEquals(List.of("DESIGNER", "/F", root.resolve("ib").toString()), args.subList(0, 3));
                write(Path.of(args.get(args.indexOf("/DumpCfg") + 1)), "configuration");
            }
            return exited(0);
        });
        assertTrue(result.ok());
        assertEquals("passed", result.createInfobase().status());
        assertEquals("passed", result.designer().status());
        assertEquals("1cv8", result.designer().program());
        assertEquals(0, result.designer().exitCode());
        assertEmpty(temp);
    }

    @Test
    void diagnosesRussianLicenseRefusalAndSkipsDesigner() throws IOException {
        var result = PlatformBuildProbe.check(program, temp, timeout, (exe, args, root, time) -> {
            assertEquals("CREATEINFOBASE", args.get(0));
            write(Path.of(args.get(args.indexOf("/Out") + 1)), "Не найдена лицензия. Sensitive details.");
            return exited(1);
        });
        assertFalse(result.ok());
        assertEquals("licenseRefused", result.createInfobase().status());
        assertEquals("notChecked", result.designer().status());
        assertFalse(result.toString().contains("Sensitive details"));
        assertEmpty(temp);
    }

    @Test
    void diagnosesEnglishLicenseRefusalFromDesignerOutput() throws IOException {
        var result = PlatformBuildProbe.check(program, temp, timeout, (exe, args, root, time) -> {
            if (args.get(0).equals("CREATEINFOBASE")) {
                createDatabase(root);
                return exited(0);
            }
            return new PlatformProcess.Result(PlatformProcess.End.EXITED, 1,
                    "License not found. Software protection key or acquired software license not found!"
                            .getBytes(StandardCharsets.UTF_8));
        });
        assertEquals("passed", result.createInfobase().status());
        assertEquals("licenseRefused", result.designer().status());
        assertEquals(1, result.designer().exitCode());
        assertEmpty(temp);
    }

    @Test
    void requiresTheInfobaseArtifactEvenWithExitZero() throws IOException {
        var result = PlatformBuildProbe.check(program, temp, timeout, (exe, args, root, time) -> exited(0));
        assertFalse(result.ok());
        assertEquals("failed", result.createInfobase().status());
        assertEquals("notChecked", result.designer().status());
        assertEmpty(temp);
    }

    @Test
    void recordsTimeoutAndCleansUpPartialOutput() throws IOException {
        var result = PlatformBuildProbe.check(program, temp, timeout, (exe, args, root, time) -> {
            createDatabase(root);
            return new PlatformProcess.Result(PlatformProcess.End.TIMED_OUT, null, new byte[0]);
        });
        assertEquals("timedOut", result.createInfobase().status());
        assertEquals("notChecked", result.designer().status());
        assertEquals("passed", result.cleanup());
        assertEmpty(temp);
    }

    private static PlatformProcess.Result exited(int code) {
        return new PlatformProcess.Result(PlatformProcess.End.EXITED, code, new byte[0]);
    }

    private static void createDatabase(Path root) {
        write(root.resolve("ib").resolve("1Cv8.1CD"), "database");
    }

    private static void write(Path path, String contents) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, contents);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void assertEmpty(Path root) throws IOException {
        try (var files = Files.list(root)) {
            assertEquals(0, files.count());
        }
    }
}
