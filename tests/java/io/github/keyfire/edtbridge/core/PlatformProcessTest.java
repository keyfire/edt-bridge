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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformProcessTest {
    @TempDir Path temp;

    @Test
    void identifiesTheProgramAndStageWithoutCommandArguments() {
        var result = new PlatformProcess.Result(PlatformProcess.End.EXITED, 3, new byte[0]);
        assertEquals("1cv8.exe DESIGNER exit 3",
                result.problem(Path.of("install", "1cv8.exe"), "DESIGNER", Duration.ofSeconds(1)));
        assertEquals("ibcmd config save exit 3",
                result.problem(Path.of("install", "ibcmd"), "config save", Duration.ofSeconds(1)));
    }

    @Test
    void reportsLaunchFailureWithoutRenderingArguments() {
        Path program = temp.resolve("ibcmd.exe");
        var result = PlatformProcess.run(program, List.of("--password=example"), null,
                Duration.ofSeconds(1), null);
        assertEquals(PlatformProcess.End.LAUNCH_FAILED, result.end());
        assertNull(result.exitCode());
        assertEquals("ibcmd.exe config save could not start",
                result.problem(program, "config save", Duration.ofSeconds(1)));
    }

    @Test
    void capturesOutputAndExitCode() throws Exception {
        var result = run("exit", Duration.ofSeconds(8));
        assertEquals(PlatformProcess.End.EXITED, result.end());
        assertEquals(7, result.exitCode());
        assertEquals("child output", new String(result.output(), StandardCharsets.UTF_8));
    }

    @Test
    void stopsSilentProcessOnDeadline() {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            var result = run("silent", Duration.ofMillis(300));
            assertEquals(PlatformProcess.End.TIMED_OUT, result.end());
            assertNull(result.exitCode());
        });
    }

    @Test
    void stopsTheProcessWhenItsJobIsCancelled() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            var threads = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var jobs = new Jobs<PlatformProcess.Result>("abc234", threads, System::currentTimeMillis, r -> false);
                var job = jobs.submit("edt_build_extension", () -> run("silent", Duration.ofSeconds(30)));
                Thread.sleep(500);
                var cancelled = jobs.cancel(job.id(), 10_000);
                assertEquals(Jobs.State.CANCELLED, cancelled.state());
                assertEquals(PlatformProcess.End.CANCELLED, cancelled.result().end());
                assertEquals("ibcmd config save was stopped: the job was cancelled",
                        cancelled.result().problem(Path.of("ibcmd"), "config save", Duration.ofSeconds(1)));
            } finally {
                threads.shutdownNow();
            }
        });
    }

    @Test
    void stopsProcessWhenCapturedOutputReachesTheLimit() throws Exception {
        var result = run("flood", Duration.ofSeconds(8));
        assertEquals(PlatformProcess.End.OUTPUT_LIMIT, result.end());
        assertTrue(result.output().length <= (1 << 20));
    }

    @Test
    void sanitizesCredentialEchoesBeforeRenderingOutput() {
        String output = "ibcmd config save --database-password=example-db-pass --password=sample-1c-pass\n"
                + "Database refused example-db-pass and sample-1c-pass.";
        String safe = IbcmdArgs.safeOutput(output,
                List.of("config", "save", "--database-password=example-db-pass", "--password=sample-1c-pass"));
        assertEquals("Database refused *** and ***.", safe);
    }

    private PlatformProcess.Result run(String action, Duration timeout) throws Exception {
        String classpath = Path.of(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        return PlatformProcess.run(java, List.of("-cp", classpath, Child.class.getName(), action),
                null, timeout, null);
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "exit" -> {
                    System.out.print("child output");
                    System.exit(7);
                }
                case "silent" -> Thread.sleep(30_000);
                case "flood" -> {
                    byte[] bytes = new byte[8192];
                    while (true) {
                        System.out.write(bytes);
                    }
                }
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
    }
}
