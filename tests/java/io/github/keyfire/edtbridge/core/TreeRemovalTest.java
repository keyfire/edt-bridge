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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Removing the directory an agent leaves behind, including one its own dying process still holds.
 *
 * <p>The waiting is what the live defect was about: a stop killed the agent, removed its base
 * directory in the same breath and left the {@code agent.log} the process had not let go of yet -
 * so the directory stood, empty of everything that identified it, and the next listing called it
 * the remains of an earlier run. The retry policy is tested on its own, without the file system,
 * because "an open file cannot be deleted" is true on Windows and false on Linux, where this suite
 * also runs; the file-holding test then proves the same thing end to end where the rule applies.
 */
class TreeRemovalTest {

    @Test
    @DisplayName("a tree goes whole, and a directory that was never there is already gone")
    void deletes(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectory(tmp.resolve("edtbridge-agent-1"));
        Files.writeString(root.resolve("agent.log"), "", StandardCharsets.UTF_8);
        Files.createDirectory(root.resolve("nested"));
        Files.writeString(root.resolve("nested").resolve("deep.txt"), "x", StandardCharsets.UTF_8);

        assertTrue(TreeRemoval.delete(root));
        assertFalse(Files.exists(root));

        assertTrue(TreeRemoval.delete(root), "deleting what is not there is a success, not a failure");
        assertTrue(TreeRemoval.delete(null));
    }

    @Test
    @DisplayName("the first attempt costs no waiting")
    void succeedsAtOnce() {
        AtomicInteger attempts = new AtomicInteger();
        long started = System.nanoTime();
        assertTrue(TreeRemoval.until(() -> {
            attempts.incrementAndGet();
            return true;
        }, 5_000, 100));
        assertEquals(1, attempts.get());
        assertTrue(System.nanoTime() - started < 1_000_000_000L, "it must not have paused");
    }

    @Test
    @DisplayName("an attempt that starts failing is repeated until it works")
    void repeatsUntilItWorks() {
        AtomicInteger attempts = new AtomicInteger();
        assertTrue(TreeRemoval.until(() -> attempts.incrementAndGet() >= 3, 5_000, 5));
        assertEquals(3, attempts.get());
    }

    @Test
    @DisplayName("the patience is bounded - a directory that will not go is reported, not waited on forever")
    void givesUpInTheEnd() {
        AtomicInteger attempts = new AtomicInteger();
        long started = System.nanoTime();
        assertFalse(TreeRemoval.until(() -> {
            attempts.incrementAndGet();
            return false;
        }, 60, 10));
        assertTrue(attempts.get() >= 2, "it must have retried at least once, not given up on the first");
        assertTrue(System.nanoTime() - started < 5_000_000_000L, "it must not have run past its patience");
    }

    @Test
    @DisplayName("a file held open defeats one pass and is waited out by the other")
    @EnabledOnOs(OS.WINDOWS)
    void waitsOutAHeldFile(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectory(tmp.resolve("edtbridge-agent-2"));
        Path log = root.resolve("agent.log");
        Files.writeString(log, "", StandardCharsets.UTF_8);
        Path record = root.resolve(AgentRecord.FILE_NAME);
        Files.writeString(record, "connectionString=/Fx", StandardCharsets.UTF_8);

        // Deliberately the old IO stream: it is the one that opens a file the way a foreign process
        // does, without letting the file be deleted underneath it. The NIO stream shares deletion and
        // would prove nothing here.
        OutputStream held = new FileOutputStream(log.toFile(), true);
        try {
            // Exactly the state the live defect left behind: everything but the held log is gone,
            // and the directory stands because of it.
            assertFalse(TreeRemoval.delete(root));
            assertFalse(Files.exists(record), "the record went, which is why the leftover looks anonymous");
            assertTrue(Files.exists(log));

            assertFalse(TreeRemoval.deleteWaiting(root, 150, 10), "a held file is not waited out forever");

            // The process lets go a moment later - which is what a killed agent does.
            Thread releaseLater = new Thread(() -> {
                try {
                    Thread.sleep(200);
                    held.close();
                } catch (Exception ignored) {
                    // the assertion below is what reports a failure here
                }
            });
            releaseLater.start();
            assertTrue(TreeRemoval.deleteWaiting(root, 5_000, 20));
            releaseLater.join(5_000);
            assertFalse(Files.exists(root));
        } finally {
            held.close();
        }
    }
}
