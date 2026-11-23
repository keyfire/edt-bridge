/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedWaitTest {
    @Test void aWaitThatEndsInTimeIsReportedAsDone() throws Exception {
        boolean[] ran = {false};
        assertTrue(BoundedWait.within(() -> ran[0] = true, 5_000, "test-wait"));
        assertTrue(ran[0]);
    }

    @Test void aWaitPastTheBoundIsLeftBehindAndReportedAsLate() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        long start = System.nanoTime();
        assertFalse(BoundedWait.within(() -> never.await(1, TimeUnit.MINUTES), 200, "test-wait"));
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(tookMs < 5_000, "the bound was not kept: " + tookMs + " ms");
        never.countDown();
    }

    @Test void theWaitRunsInADaemonThreadThatDoesNotHoldTheProcess() throws Exception {
        boolean[] daemon = {false};
        String[] name = {null};
        assertTrue(BoundedWait.within(() -> {
            daemon[0] = Thread.currentThread().isDaemon();
            name[0] = Thread.currentThread().getName();
        }, 5_000, "edt-bridge-model-sync"));
        assertTrue(daemon[0]);
        assertEquals("edt-bridge-model-sync", name[0]);
    }

    @Test void aFailureOfTheWaitComesOutAsItWasThrown() {
        var failure = assertThrows(IllegalStateException.class,
                () -> BoundedWait.within(() -> { throw new IllegalStateException("model is gone"); }, 5_000, "test-wait"));
        assertEquals("model is gone", failure.getMessage());
    }
}
