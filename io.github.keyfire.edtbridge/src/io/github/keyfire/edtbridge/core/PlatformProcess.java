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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Bounded, non-interactive execution of platform utilities. */
public final class PlatformProcess {
    private static final int MAX_OUTPUT_BYTES = 1 << 20;
    private static final int POLL_MILLIS = 20;

    public enum End {
        EXITED, LAUNCH_FAILED, TIMED_OUT, OUTPUT_LIMIT, STOP_REQUESTED, INTERRUPTED, READ_FAILED
    }

    public record Result(End end, Integer exitCode, byte[] output) {
        /** Describe a fixed operation stage without rendering the process arguments. */
        public String problem(Path executable, String stage, Duration timeout) {
            String operation = executable.getFileName().toString();
            if (!stage.isEmpty()) {
                operation += " " + stage;
            }
            return operation + switch (end) {
                case EXITED -> " exit " + exitCode;
                case LAUNCH_FAILED -> " could not start";
                case TIMED_OUT -> " timed out after " + timeout.toSeconds() + "s";
                case OUTPUT_LIMIT -> " exceeded " + MAX_OUTPUT_BYTES + " bytes of output and was stopped";
                case STOP_REQUESTED -> " was stopped";
                case INTERRUPTED -> " was interrupted";
                case READ_FAILED -> " output could not be read";
            };
        }
    }

    private PlatformProcess() {
    }

    public static Result run(Path executable, List<String> args, Path workingDirectory,
            Duration timeout, Predicate<byte[]> stopEarly) {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException | RuntimeException ex) {
            // A launcher error may include command arguments.
            return new Result(End.LAUNCH_FAILED, null, new byte[0]);
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        var output = new ByteArrayOutputStream();
        try (InputStream input = process.getInputStream()) {
            byte[] chunk = new byte[8192];
            while (true) {
                int available = input.available();
                if (available > 0) {
                    int remaining = MAX_OUTPUT_BYTES - output.size();
                    if (remaining == 0) {
                        return new Result(End.OUTPUT_LIMIT, null, output.toByteArray());
                    }
                    int read = input.read(chunk, 0, Math.min(Math.min(available, chunk.length), remaining));
                    if (read > 0) {
                        output.write(chunk, 0, read);
                        if (stopEarly != null && stopEarly.test(output.toByteArray())) {
                            return new Result(End.STOP_REQUESTED, null, output.toByteArray());
                        }
                    }
                } else if (!process.isAlive() && input.available() == 0) {
                    return new Result(End.EXITED, process.exitValue(), output.toByteArray());
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return new Result(End.TIMED_OUT, null, output.toByteArray());
                }
                if (available == 0) {
                    TimeUnit.NANOSECONDS.sleep(Math.min(left, TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS)));
                }
            }
        } catch (IOException ex) {
            return new Result(End.READ_FAILED, null, output.toByteArray());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Result(End.INTERRUPTED, null, output.toByteArray());
        } finally {
            if (process.isAlive()) {
                terminate(process);
            }
        }
    }

    private static File nullDevice() {
        return new File(File.separatorChar == '\\' ? "NUL" : "/dev/null");
    }

    private static void terminate(Process process) {
        var descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean interrupted = Thread.interrupted();
        try {
            while ((process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))
                    && System.nanoTime() - deadline < 0) {
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
