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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** The slow part of the bridge status: read within a limit, never twice at once. */
class BoundedReadTest {

    @Test
    void aQuickReadGivesTheValue() {
        BoundedRead<String> read = new BoundedRead<>("test-read", 1000, () -> "model");
        assertEquals("model", read.get());
    }

    @Test
    void aSlowReadIsNotWaitedForAndIsSharedUntilItEnds() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        BoundedRead<Integer> read = new BoundedRead<>("test-read", 1000, () -> {
            int number = reads.incrementAndGet();
            if (number == 1) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return number;
        });

        long started = System.nanoTime();
        assertNull(read.get(), "a read past the limit leaves the caller without the value");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5000,
                "the caller waits the limit, not the read");
        assertNull(read.get(), "a caller that comes while the read hangs waits for that read");
        assertEquals(1, reads.get(), "a hanging read is not started a second time");

        FutureTask<Integer> waiter = new FutureTask<>(read::get);
        new Thread(waiter, "test-waiter").start();
        Thread.sleep(300); // the waiter is inside get(), on the read that still runs
        release.countDown();
        assertEquals(1, waiter.get(10, TimeUnit.SECONDS),
                "the read that ends within the limit gives its value to the caller waiting for it");
        assertEquals(2, read.get(), "a caller after the end starts a fresh read");
    }

    @Test
    void aFailedReadGivesNoValue() {
        BoundedRead<String> read = new BoundedRead<>("test-read", 1000, () -> {
            throw new IllegalStateException("model unavailable");
        });
        assertNull(read.get());
    }
}
