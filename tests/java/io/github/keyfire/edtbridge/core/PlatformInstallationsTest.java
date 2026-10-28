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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformInstallationsTest {
    @Test
    void discoversLinuxArchitecturesWithoutStartingClient(@TempDir Path root) throws Exception {
        // Non-executable marker files prove that discovery only inspects the filesystem.
        for (String arch : List.of("x86_64", "i386", "arm64")) {
            Path bin = Files.createDirectories(root.resolve(arch).resolve("8.3.25.1257"));
            Files.writeString(bin.resolve("1cv8"), "not an executable");
        }
        var installs = PlatformInstallations.scan(List.of(root));
        assertEquals(3, installs.size());
        assertEquals(List.of("arm64", "i386", "x86_64"), installs.stream()
                .map(i -> i.binDir().getParent().getFileName().toString()).sorted().toList());
        assertTrue(installs.stream().allMatch(i -> i.version().equals("8.3.25.1257")));
    }

    @Test
    void discoversWindowsLayoutAndSortsBuildsNumerically(@TempDir Path root) throws Exception {
        install(root.resolve("8.5.1.999/bin"), "1cv8.exe");
        install(root.resolve("8.5.1.1302/bin"), "1cv8.exe");
        assertEquals(List.of("8.5.1.1302", "8.5.1.999"), PlatformInstallations.scan(List.of(root))
                .stream().map(i -> i.version()).toList());
    }

    @Test
    void thinOnlyRootCannotHideFullInstallOfSameBuild(@TempDir Path tmp) throws Exception {
        Path thinRoot = tmp.resolve("thin");
        install(thinRoot.resolve("8.5.1.1302/bin"), "1cv8c.exe");
        Path fullRoot = tmp.resolve("full");
        install(fullRoot.resolve("8.5.1.1302/bin"), "1cv8.exe");
        var installs = PlatformInstallations.scan(List.of(thinRoot, fullRoot));
        assertEquals(1, installs.size());
        assertEquals(fullRoot.resolve("8.5.1.1302/bin"), installs.get(0).binDir());
    }

    @Test
    void registeredLinuxLocationAndOverlappingRootsDoNotDuplicateInstall(@TempDir Path root) throws Exception {
        Path bin = root.resolve("x86_64/8.5.1.1302");
        install(bin, "1cv8");
        var installs = PlatformInstallations.scan(List.of(root, root.resolve("x86_64"), bin));
        assertEquals(1, installs.size());
        assertEquals(bin, installs.get(0).binDir());
    }

    @Test
    void ignoresNonVersionAndMissingRoots(@TempDir Path root) throws Exception {
        install(root.resolve("current/bin"), "1cv8");
        install(root.resolve("8.5.1.1302-backup/bin"), "1cv8");
        assertTrue(PlatformInstallations.scan(List.of(root, root.resolve("missing"))).isEmpty());
    }

    private static void install(Path bin, String name) throws Exception {
        Files.createDirectories(bin);
        Files.writeString(bin.resolve(name), "not an executable");
    }
}
