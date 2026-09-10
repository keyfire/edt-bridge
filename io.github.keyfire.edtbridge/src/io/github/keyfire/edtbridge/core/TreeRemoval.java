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
import java.util.Comparator;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Removing a temporary directory tree the bridge created - including one a process on its way out is
 * still holding.
 *
 * <p>Why the waiting variant exists: on Windows a kill returns before the process is gone, and until
 * it is gone the files it opened stay locked. A configurator agent keeps its own {@code /Out} log
 * open, so a delete issued right after the kill removes everything BUT that log and leaves the
 * directory standing - a trace that then looks like the remains of some earlier run. Measured: the
 * agent's record file was deleted while its process was still alive, and the process disappeared
 * 150 ms later. A single attempt cannot win that race; waiting a moment and repeating does.
 *
 * <p>Removal stays best-effort in the sense that it never throws - a directory that will not go is
 * reported by the return value, not by an exception in the middle of somebody's operation.
 */
public final class TreeRemoval {

    /** How long {@link #deleteWaiting(Path)} keeps trying before it gives the directory up. */
    public static final long PATIENCE_MILLIS = 5_000;

    /** How long to wait between attempts - short enough that a normal stop pays nothing. */
    public static final long PAUSE_MILLIS = 100;

    private TreeRemoval() {
    }

    /**
     * Delete a tree in one pass.
     *
     * @return true when nothing is left at {@code root} - including when there was nothing there
     */
    public static boolean delete(Path root) {
        if (root == null) {
            return true;
        }
        if (!Files.exists(root)) {
            return true;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException held) {
                    // A locked file must not stop the rest of the tree; the caller learns from the
                    // return value whether anything survived.
                }
            });
        } catch (IOException unreadable) {
            return !Files.exists(root);
        }
        return !Files.exists(root);
    }

    /** Delete a tree, giving a process that is going away time to let go of the files it holds. */
    public static boolean deleteWaiting(Path root) {
        return deleteWaiting(root, PATIENCE_MILLIS, PAUSE_MILLIS);
    }

    /** As {@link #deleteWaiting(Path)}, with the patience spelled out. */
    public static boolean deleteWaiting(Path root, long patienceMillis, long pauseMillis) {
        return until(() -> delete(root), patienceMillis, pauseMillis);
    }

    /**
     * Repeat an attempt until it succeeds or the patience runs out. The first attempt is made at
     * once, so nothing is paid when there is nothing to wait for.
     *
     * <p>Package-private and separate from the file system on purpose: the retry policy is what the
     * tests can pin down, while "an open file cannot be deleted" is true on Windows and false
     * elsewhere.
     *
     * @return whether the attempt ever succeeded
     */
    static boolean until(BooleanSupplier attempt, long patienceMillis, long pauseMillis) {
        long deadline = System.nanoTime() + Math.max(0, patienceMillis) * 1_000_000L;
        while (true) {
            if (attempt.getAsBoolean()) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(Math.max(1, pauseMillis));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
