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
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactBuildTest {
    @Test
    void failedProcessCannotReplaceExistingArtifact(@TempDir Path dir) throws Exception {
        Path target = Files.writeString(dir.resolve("report.epf"), "previous artifact");
        String error = ArtifactBuild.build(target, output -> {
            Files.writeString(output, "partial output");
            return "exit 1: build failed";
        });
        assertEquals("exit 1: build failed", error);
        assertEquals("previous artifact", Files.readString(target));
        assertOnlyTarget(dir, target);
    }

    @Test
    void emptyOutputCannotReplaceExistingArtifact(@TempDir Path dir) throws Exception {
        Path target = Files.writeString(dir.resolve("report.erf"), "previous artifact");
        assertNotNull(ArtifactBuild.build(target, output -> {
            Files.write(output, new byte[0]);
            return null;
        }));
        assertEquals("previous artifact", Files.readString(target));
        assertOnlyTarget(dir, target);
    }

    @Test
    void missingOutputCannotReplaceExistingArtifact(@TempDir Path dir) throws Exception {
        Path target = Files.writeString(dir.resolve("report.epf"), "previous artifact");
        assertNotNull(ArtifactBuild.build(target, output -> {
            Files.deleteIfExists(output);
            return null;
        }));
        assertEquals("previous artifact", Files.readString(target));
        assertOnlyTarget(dir, target);
    }

    @Test
    void thrownFailurePreservesExistingArtifact(@TempDir Path dir) throws Exception {
        Path target = Files.writeString(dir.resolve("report.epf"), "previous artifact");
        assertThrows(IOException.class, () -> ArtifactBuild.build(target, output -> {
            Files.writeString(output, "partial output");
            throw new IOException("platform failed");
        }));
        assertEquals("previous artifact", Files.readString(target));
        assertOnlyTarget(dir, target);
    }

    @Test
    void successfulBuildPublishesOnlyAfterVerification(@TempDir Path dir) throws Exception {
        Path target = Files.writeString(dir.resolve("report.epf"), "previous artifact");
        assertNull(ArtifactBuild.build(target, output -> {
            assertNotEquals(target, output);
            assertEquals(dir, output.getParent());
            assertTrue(output.getFileName().toString().endsWith(".epf"));
            assertFalse(Files.exists(output), "the platform must not need an overwrite confirmation");
            assertEquals("previous artifact", Files.readString(target));
            Files.writeString(output, "complete new artifact");
            return null;
        }));
        assertEquals("complete new artifact", Files.readString(target));
        assertOnlyTarget(dir, target);
    }

    @Test
    void failedFirstBuildLeavesNoArtifact(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("report.epf");
        assertNotNull(ArtifactBuild.build(target, output -> {
            Files.writeString(output, "partial output");
            return "exit 1";
        }));
        assertFalse(Files.exists(target));
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void logCannotAliasTheArtifact(@TempDir Path dir) throws Exception {
        Path target = Files.writeString(dir.resolve("report.epf"), "previous artifact");
        assertThrows(IOException.class, () -> ArtifactBuild.requireSeparateLog(target, target));
        Path alias = Files.createLink(dir.resolve("alias.log"), target);
        assertThrows(IOException.class, () -> ArtifactBuild.requireSeparateLog(target, alias));
        assertEquals("previous artifact", Files.readString(target));
        ArtifactBuild.requireSeparateLog(target, dir.resolve("report.epf.log"));
    }

    private static void assertOnlyTarget(Path dir, Path target) throws IOException {
        try (var files = Files.list(dir)) {
            assertEquals(java.util.List.of(target), files.toList());
        }
    }
}
