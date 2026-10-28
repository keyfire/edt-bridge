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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Inspect full platform installations on disk without starting their graphical client. */
public final class PlatformInstallations {
    private static final Pattern BUILD = Pattern.compile("\\d+\\.\\d+\\.\\d+\\.\\d+");
    private static final Set<String> ARCHITECTURES = Set.of("x86_64", "i386", "arm64", "aarch64");

    private PlatformInstallations() {
    }

    public record Installation(Path binDir, String version) {
    }

    /** Accept an installation root, Linux architecture directory, or registered version directory. */
    public static List<Installation> scan(Collection<Path> roots) {
        List<Installation> found = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (Path root : roots) {
            addVersion(root, found, seen);
            for (Path child : children(root)) {
                addVersion(child, found, seen);
                if (ARCHITECTURES.contains(child.getFileName().toString())) {
                    for (Path version : children(child)) {
                        addVersion(version, found, seen);
                    }
                }
            }
        }
        found.sort((a, b) -> PlatformSelection.compare(a.version(), b.version(), null));
        return found;
    }

    private static void addVersion(Path directory, List<Installation> found, Set<Path> seen) {
        if (directory.getFileName() == null || !Files.isDirectory(directory)) {
            return;
        }
        String version = directory.getFileName().toString();
        if (!BUILD.matcher(version).matches()) {
            return;
        }
        for (Path bin : List.of(directory.resolve("bin"), directory)) {
            if (Files.isRegularFile(bin.resolve("1cv8.exe")) || Files.isRegularFile(bin.resolve("1cv8"))) {
                if (seen.add(bin.toAbsolutePath().normalize())) {
                    found.add(new Installation(bin, version));
                }
                return;
            }
        }
    }

    private static List<Path> children(Path directory) {
        try (var files = Files.list(directory)) {
            return files.filter(Files::isDirectory).sorted().toList();
        } catch (IOException | SecurityException ignored) {
            return List.of();
        }
    }
}
