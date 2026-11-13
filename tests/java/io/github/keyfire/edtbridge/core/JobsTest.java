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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Long calls as jobs: the id, the wait, the cancel and what is kept. */
class JobsTest {

    private final ExecutorService threads = Executors.newSingleThreadExecutor();
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final Jobs<String> jobs = new Jobs<>("abc234", threads, clock::get, result -> result.startsWith("error"));

    @AfterEach
    void stop() {
        threads.shutdownNow();
    }

    @Test
    @DisplayName("a job runs, and the wait returns its result")
    void runsToTheEnd() throws Exception {
        var started = jobs.submit("edt_build_extension", () -> "built");
        assertTrue(started.id().matches("job-abc234-1"), started.id());
        assertEquals("edt_build_extension", started.tool());

        var done = jobs.await(started.id(), 5_000);
        assertEquals(Jobs.State.SUCCEEDED, done.state());
        assertEquals("built", done.result());
        assertNull(done.error());
    }

    @Test
    @DisplayName("a result the tool marks as an error and an exception both fail the job")
    void failures() throws Exception {
        var refused = jobs.await(jobs.submit("t", () -> "error: no such project").id(), 5_000);
        assertEquals(Jobs.State.FAILED, refused.state());
        assertEquals("error: no such project", refused.result());

        var thrown = jobs.await(jobs.submit("t", () -> {
            throw new IllegalStateException("no model");
        }).id(), 5_000);
        assertEquals(Jobs.State.FAILED, thrown.state());
        assertEquals("IllegalStateException: no model", thrown.error());
    }

    @Test
    @DisplayName("the wait gives up on a running job and says it is still running")
    void waitGivesUp() throws Exception {
        var release = new CountDownLatch(1);
        var job = jobs.submit("t", () -> {
            release.await();
            return "late";
        });
        long before = System.nanoTime();
        var running = jobs.await(job.id(), 200);
        assertEquals(Jobs.State.RUNNING, running.state());
        assertTrue(System.nanoTime() - before >= TimeUnit.MILLISECONDS.toNanos(150));
        release.countDown();
        assertEquals("late", jobs.await(job.id(), 5_000).result());
    }

    @Test
    @DisplayName("a job waits in the queue while the threads are busy, and a cancel there means it never runs")
    void cancelInTheQueue() throws Exception {
        var release = new CountDownLatch(1);
        jobs.submit("first", () -> {
            release.await();
            return "first";
        });
        var ran = new AtomicBoolean();
        var queued = jobs.submit("second", () -> {
            ran.set(true);
            return "second";
        });
        assertEquals(Jobs.State.QUEUED, jobs.await(queued.id(), 0).state());

        var cancelled = jobs.cancel(queued.id(), 0);
        assertEquals(Jobs.State.CANCELLED, cancelled.state());
        assertTrue(cancelled.applied().isEmpty());
        release.countDown();
        threads.shutdown();
        assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS));
        assertFalse(ran.get());
    }

    @Test
    @DisplayName("a running job stops at its next check and keeps the list of what it had applied")
    void cancelWhileRunning() throws Exception {
        var loaded = new CountDownLatch(1);
        var job = jobs.submit("edt_update_infobase", () -> {
            Jobs.stage("loading the configuration");
            Jobs.applied("the configuration is loaded into the infobase");
            Jobs.stage("updating the database");
            loaded.countDown();
            while (!Jobs.cancelRequested()) {
                Thread.sleep(10);
            }
            return "stopped before the database update";
        });
        assertTrue(loaded.await(5, TimeUnit.SECONDS));
        assertEquals("updating the database", jobs.await(job.id(), 0).stage());

        var cancelled = jobs.cancel(job.id(), 5_000);
        assertEquals(Jobs.State.CANCELLED, cancelled.state());
        assertTrue(cancelled.cancelRequested());
        assertEquals(List.of("the configuration is loaded into the infobase"), cancelled.applied());
        assertEquals("stopped before the database update", cancelled.result());
    }

    @Test
    @DisplayName("work that never looks at the request ends with its real result and the request noted")
    void cancelNotYetReached() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var job = jobs.submit("t", () -> {
            started.countDown();
            release.await();
            return "done anyway";
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var pending = jobs.cancel(job.id(), 100);
        assertEquals(Jobs.State.RUNNING, pending.state());
        assertTrue(pending.cancelRequested());
        release.countDown();
        // It never saw the request and did its work: a cancelled state with nothing applied would hide that.
        var end = jobs.await(job.id(), 5_000);
        assertEquals(Jobs.State.SUCCEEDED, end.state());
        assertTrue(end.cancelRequested());
        assertEquals("done anyway", end.result());
    }

    @Test
    @DisplayName("a context taken in the job thread sees the cancel from another thread")
    void contextFromAnotherThread() throws Exception {
        var job = jobs.submit("edt_build_extension", () -> {
            Jobs.Context context = Jobs.context();
            var seen = new AtomicBoolean();
            // The way an EDT operation asks its progress monitor: from a thread of its own.
            Thread worker = new Thread(() -> {
                context.stage("exporting");
                while (!context.cancelRequested()) {
                    Thread.onSpinWait();
                }
                seen.set(true);
            });
            worker.start();
            worker.join(10_000);
            return seen.get() ? "stopped" : "ran out";
        });
        for (int i = 0; i < 100 && !"exporting".equals(jobs.await(job.id(), 0).stage()); i++) {
            Thread.sleep(20);
        }
        var end = jobs.cancel(job.id(), 5_000);
        assertEquals(Jobs.State.CANCELLED, end.state());
        assertEquals("stopped", end.result());
        assertFalse(Jobs.context().cancelRequested(), "outside a job the context says no");
    }

    @Test
    @DisplayName("a long job started first and finished last is kept over the quick ones")
    void retentionByFinishTime() throws Exception {
        var two = Executors.newFixedThreadPool(2);
        try {
            var registry = new Jobs<String>("abc234", two, clock::get, result -> false);
            var release = new CountDownLatch(1);
            var slow = registry.submit("edt_update_infobase", () -> {
                release.await();
                return "loaded";
            });
            String first = null;
            for (int i = 0; i <= Jobs.KEEP_FINISHED; i++) {
                clock.incrementAndGet();
                var quick = registry.submit("edt_project_errors", () -> "x");
                registry.await(quick.id(), 5_000);
                first = first == null ? quick.id() : first;
            }
            clock.incrementAndGet();
            release.countDown();
            registry.await(slow.id(), 5_000);
            assertEquals(Jobs.Lookup.FOUND, registry.lookup(slow.id()));
            assertEquals(Jobs.Lookup.UNKNOWN, registry.lookup(first));
        } finally {
            two.shutdownNow();
        }
    }

    @Test
    @DisplayName("a job whose result the caller already has is forgotten, a running one is kept")
    void forget() throws Exception {
        var done = jobs.submit("t", () -> "x");
        jobs.await(done.id(), 5_000);
        jobs.forget(done.id());
        assertEquals(Jobs.Lookup.UNKNOWN, jobs.lookup(done.id()));

        var release = new CountDownLatch(1);
        var running = jobs.submit("t", () -> {
            release.await();
            return "y";
        });
        jobs.forget(running.id());
        assertEquals(Jobs.Lookup.FOUND, jobs.lookup(running.id()));
        release.countDown();
    }

    @Test
    @DisplayName("a cancel of a finished job changes nothing")
    void cancelFinished() throws Exception {
        var job = jobs.submit("t", () -> "built");
        jobs.await(job.id(), 5_000);
        var again = jobs.cancel(job.id(), 0);
        assertEquals(Jobs.State.SUCCEEDED, again.state());
        assertFalse(again.cancelRequested());
    }

    @Test
    @DisplayName("an id of another run, an unknown one and a string that is no id are told apart")
    void lookups() {
        var job = jobs.submit("t", () -> "x");
        assertEquals(Jobs.Lookup.FOUND, jobs.lookup(job.id()));
        assertEquals(Jobs.Lookup.FOUND, jobs.lookup(" " + job.id() + " "));
        assertEquals(Jobs.Lookup.UNKNOWN, jobs.lookup("job-abc234-99"));
        assertEquals(Jobs.Lookup.OTHER_RUN, jobs.lookup("job-zzz999-1"));
        assertEquals(Jobs.Lookup.MALFORMED, jobs.lookup("12"));
        assertEquals(Jobs.Lookup.MALFORMED, jobs.lookup(null));
    }

    @Test
    @DisplayName("a result is kept for an hour, and only the newest finished jobs are kept")
    void retention() throws Exception {
        var old = jobs.submit("t", () -> "old");
        jobs.await(old.id(), 5_000);
        clock.addAndGet(Jobs.RETENTION_MILLIS + 1);
        assertEquals(Jobs.Lookup.UNKNOWN, jobs.lookup(old.id()));

        String first = null;
        for (int i = 0; i <= Jobs.KEEP_FINISHED; i++) {
            var job = jobs.submit("t", () -> "x");
            jobs.await(job.id(), 5_000);
            first = first == null ? job.id() : first;
        }
        assertEquals(Jobs.KEEP_FINISHED, jobs.list().size());
        assertEquals(Jobs.Lookup.UNKNOWN, jobs.lookup(first));
    }

    @Test
    @DisplayName("the list puts the newest job first")
    void listing() throws Exception {
        var a = jobs.submit("a", () -> "a");
        var b = jobs.submit("b", () -> "b");
        jobs.await(b.id(), 5_000);
        var all = jobs.list();
        assertEquals(b.id(), all.get(0).id());
        assertEquals(a.id(), all.get(1).id());
    }

    @Test
    @DisplayName("outside a job the context calls do nothing")
    void outsideAJob() {
        Jobs.stage("ignored");
        Jobs.applied("ignored");
        assertFalse(Jobs.cancelRequested());
        assertFalse(Jobs.inJob());
    }

    @Test
    @DisplayName("the wait is bounded, so a status call never outlives a client's patience")
    void boundedWait() {
        assertTimeoutPreemptively(Duration.ofSeconds(Jobs.MAX_WAIT_MILLIS / 1000 + 5), () -> {
            var release = new CountDownLatch(1);
            var job = jobs.submit("t", () -> {
                release.await();
                return "x";
            });
            // A wait far past the limit is cut to it; the latch released early ends the wait sooner.
            new Thread(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                release.countDown();
            }).start();
            assertEquals("x", jobs.await(job.id(), Long.MAX_VALUE).result());
        });
    }

    @Test
    @DisplayName("a run id is six characters a person can read back")
    void runIds() {
        String id = Jobs.newRunId();
        assertTrue(id.matches("[a-z0-9]{6}"), id);
        assertFalse(id.contains("l") || id.contains("o") || id.contains("0") || id.contains("1"), id);
    }
}
