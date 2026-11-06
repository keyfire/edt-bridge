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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** Checks a disk Designer against a fresh file infobase in an isolated temporary directory. */
public final class PlatformBuildProbe {
    private static final int MAX_LOG_BYTES = 1 << 20;
    private static final Stage NOT_CHECKED = new Stage("notChecked", null, null);

    public record Stage(String status, String program, Integer exitCode) {
        public boolean passed() {
            return "passed".equals(status);
        }
    }

    public record Result(Stage createInfobase, Stage designer, String cleanup) {
        public boolean ok() {
            return createInfobase.passed() && designer.passed() && "passed".equals(cleanup);
        }
    }

    @FunctionalInterface
    interface Runner {
        PlatformProcess.Result run(Path executable, List<String> args, Path root, Duration timeout);
    }

    private PlatformBuildProbe() {
    }

    public static Stage notChecked() {
        return NOT_CHECKED;
    }

    public static List<String> createInfobaseArgs(Path base, Path log) {
        String connection = "File=" + (base.toString().indexOf(' ') >= 0 ? "\"" + base + "\"" : base);
        return List.of("CREATEINFOBASE", connection, "/DisableStartupDialogs",
                "/DisableStartupMessages", "/Out", log.toString());
    }

    public static Result check(Path executable, Duration timeout) {
        return check(executable, Path.of(System.getProperty("java.io.tmpdir")), timeout,
                (exe, args, root, time) -> PlatformProcess.run(exe, args, root, time, null));
    }

    static Result check(Path executable, Path tempParent, Duration timeout, Runner runner) {
        Stage created = NOT_CHECKED;
        Stage designer = NOT_CHECKED;
        Path root;
        try {
            root = Files.createTempDirectory(tempParent.toRealPath(), "edtbridge-build-probe-");
        } catch (IOException | SecurityException ex) {
            return new Result(new Stage("setupFailed", null, null), designer, "notChecked");
        }

        String cleanup;
        try {
            Path base = root.resolve("ib");
            Path createLog = root.resolve("create.log");
            var create = runner.run(executable, createInfobaseArgs(base, createLog), root, timeout);
            created = stage(executable, create, createLog, fileNamed(base, "1cv8.1cd", root));
            if (created.passed()) {
                Path config = root.resolve("empty.cf");
                Path designerLog = root.resolve("designer.log");
                var dump = runner.run(executable, List.of("DESIGNER", "/F", base.toString(),
                        "/DumpCfg", config.toString(), "/DisableStartupDialogs",
                        "/DisableStartupMessages", "/Out", designerLog.toString()), root, timeout);
                designer = stage(executable, dump, designerLog, isArtifact(config));
            }
        } finally {
            cleanup = TreeRemoval.until(() -> deleteTemporaryTree(root),
                    TreeRemoval.PATIENCE_MILLIS, TreeRemoval.PAUSE_MILLIS) ? "passed" : "failed";
        }
        return new Result(created, designer, cleanup);
    }

    private static Stage stage(Path executable, PlatformProcess.Result run, Path log, boolean artifact) {
        String status = switch (run.end()) {
            case TIMED_OUT -> "timedOut";
            case LAUNCH_FAILED -> "launchFailed";
            case INTERRUPTED -> "interrupted";
            case EXITED -> {
                if (run.exitCode() == 0 && artifact) {
                    yield "passed";
                }
                boolean refused = PlatformMessages.isLicenseRefusal(readLog(log))
                        || PlatformMessages.isLicenseRefusal(PlatformLog.decode(run.output()));
                yield refused ? "licenseRefused" : "failed";
            }
            default -> "failed";
        };
        return new Stage(status, executable.getFileName().toString(), run.exitCode());
    }

    private static boolean fileNamed(Path directory, String name, Path root) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            if (!directory.toRealPath().startsWith(root)) {
                return false;
            }
            try (var files = Files.list(directory)) {
                return files.anyMatch(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).equals(name)
                        && isArtifact(path));
            }
        } catch (IOException | SecurityException ex) {
            return false;
        }
    }

    private static boolean isArtifact(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static String readLog(Path log) {
        if (!isArtifact(log)) {
            return "";
        }
        try (var input = Files.newInputStream(log)) {
            return PlatformLog.decode(input.readNBytes(MAX_LOG_BYTES));
        } catch (IOException | SecurityException ex) {
            return "";
        }
    }

    private static boolean deleteTemporaryTree(Path root) {
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                    if (!directory.toRealPath().startsWith(root)) {
                        throw new IOException("Temporary directory resolves outside the probe");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                    if (error != null) {
                        throw error;
                    }
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (IOException | SecurityException ex) {
            return false;
        }
    }
}
