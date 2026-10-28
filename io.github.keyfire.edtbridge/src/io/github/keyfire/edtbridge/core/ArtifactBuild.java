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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Publish a platform build only after its process and its output both succeeded. */
public final class ArtifactBuild {
    private ArtifactBuild() {
    }

    /** A caller-supplied /Out path must never let the platform overwrite the old artifact. */
    public static void requireSeparateLog(Path target, Path log) throws IOException {
        if (log != null && (target.toAbsolutePath().normalize().equals(log.toAbsolutePath().normalize())
                || (Files.exists(target) && Files.exists(log) && Files.isSameFile(target, log)))) {
            throw new IOException("logPath must refer to a different file from targetPath");
        }
    }

    @FunctionalInterface
    public interface Builder {
        /** Write to the supplied temporary path; return null only when the process succeeded. */
        String build(Path output) throws IOException;
    }

    /** The old artifact remains available throughout the build and on every failed attempt. */
    public static String build(Path target, Builder builder) throws IOException {
        Path absolute = target.toAbsolutePath();
        String name = absolute.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String suffix = dot >= 0 ? name.substring(dot) : ".tmp";
        Path temporary = Files.createTempFile(absolute.getParent(), ".edtbridge-build-", suffix);
        try {
            Files.delete(temporary); // The platform must not ask to overwrite a placeholder file.
            String error = builder.build(temporary);
            if (error != null) {
                return error;
            }
            if (!Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS) || Files.size(temporary) == 0) {
                return "the platform produced no non-empty artifact";
            }
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                throw new IOException("the destination filesystem cannot atomically replace the artifact: "
                        + absolute, ex);
            }
            return null;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
