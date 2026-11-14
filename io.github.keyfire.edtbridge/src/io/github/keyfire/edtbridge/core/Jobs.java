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

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Long calls run as jobs: the call answers with a job id at once, and the caller asks for the state
 * and the result later.
 *
 * <p>A build or a load into an infobase takes minutes, and an MCP client drops a call it has waited on
 * for too long. A job keeps running in EDT whatever happens to the client, and its result waits here for
 * an hour. The id names the run of EDT it was started in, so after a restart of EDT an old id gets a
 * clear answer instead of "unknown".
 *
 * <p>Cancelling is cooperative. A cancel sets a flag the running code checks between its steps: a
 * platform process stops at the next poll ({@link PlatformProcess}), an EDT operation at the next
 * check of its progress monitor. The thread is never interrupted - an interrupt in the middle of a
 * workspace operation closes the file channels it holds. The steps the job has already applied are
 * recorded with {@link #applied(String)}, so a cancelled job says what is done and what is not.
 *
 * <p>A job ends as cancelled only when its code saw the request - read the flag and got yes. Work
 * that has no check ran to its end, and it ends with its real result and {@code cancelRequested}:
 * a database update that went through must not read as cancelled with nothing applied.
 *
 * @param <R> the result of a job: an MCP tool result in the bridge, anything in the tests
 */
public final class Jobs<R> {

    /** The longest a status call waits for a job to finish: below the minute many clients allow a call. */
    public static final long MAX_WAIT_MILLIS = 50_000;
    /** How long a finished job keeps its result. */
    public static final long RETENTION_MILLIS = 60 * 60_000L;
    /** How many finished jobs are kept at most; the oldest go first. */
    public static final int KEEP_FINISHED = 50;

    public enum State {
        QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

        public boolean finished() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** What an id names. */
    public enum Lookup {
        /** A job of this run. */
        FOUND,
        /** An id of this run that names no job: never started, or its result is gone. */
        UNKNOWN,
        /** A job of an earlier run of EDT: it ended with that run, and its result is lost. */
        OTHER_RUN,
        /** Not a job id at all. */
        MALFORMED
    }

    /**
     * The job as its running code sees it. A progress monitor keeps the context it was created with, so
     * an EDT operation that asks the monitor from threads of its own still reaches the job.
     */
    public interface Context {
        void stage(String text);

        void applied(String text);

        /** Whether the job has been asked to stop; a yes counts as the code having seen the request. */
        boolean cancelRequested();
    }

    private static final Context NONE = new Context() {
        @Override
        public void stage(String text) {
        }

        @Override
        public void applied(String text) {
        }

        @Override
        public boolean cancelRequested() {
            return false;
        }
    };

    /** The work of a job. */
    @FunctionalInterface
    public interface Work<R> {
        R run() throws Exception;
    }

    /** A job as it stands at the moment of the call. */
    public record Snapshot<R>(String id, String tool, State state, String stage, List<String> applied,
            long submittedAt, long startedAt, long finishedAt, R result, String error, boolean cancelRequested) {

        /** Seconds since the job was submitted, up to its end when it has ended. */
        public long elapsedSeconds(long now) {
            long end = finishedAt > 0 ? finishedAt : now;
            return Math.max(0, (end - submittedAt) / 1000);
        }
    }

    private static final Pattern ID = Pattern.compile("job-([a-z0-9]{6})-([0-9]{1,18})");
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final ThreadLocal<Job<?>> CURRENT = new ThreadLocal<>();

    private final String run;
    private final ExecutorService executor;
    private final LongSupplier now;
    private final Predicate<R> failed;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Job<R>> jobs = new LinkedHashMap<>();

    /**
     * @param run      the id of this run of EDT, {@link #newRunId()}
     * @param executor the threads jobs run on: never the threads that serve MCP requests
     * @param now      the clock, in milliseconds
     * @param failed   whether a result is a failure (a tool result marked as an error)
     */
    public Jobs(String run, ExecutorService executor, LongSupplier now, Predicate<R> failed) {
        if (run == null || !run.matches("[a-z0-9]{6}")) {
            throw new IllegalArgumentException("a run id is six lower-case letters or digits");
        }
        this.run = run;
        this.executor = executor;
        this.now = now;
        this.failed = failed;
    }

    /** A fresh id for a run of EDT: six characters, so job ids stay short enough to read. */
    public static String newRunId() {
        var random = new SecureRandom();
        var id = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            id.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return id.toString();
    }

    public String run() {
        return run;
    }

    /** Start a job; it waits in the queue while the job threads are busy. */
    public Snapshot<R> submit(String tool, Work<R> work) {
        Job<R> job;
        synchronized (jobs) {
            purge();
            job = new Job<>("job-" + run + "-" + sequence.incrementAndGet(), tool, now.getAsLong());
            jobs.put(job.id, job);
        }
        try {
            executor.execute(() -> execute(job, work));
        } catch (RejectedExecutionException stopping) {
            job.finish(State.FAILED, null, "the bridge is stopping and runs no new jobs", now.getAsLong());
        }
        return job.snapshot();
    }

    /** What an id names, without waiting. */
    public Lookup lookup(String id) {
        Matcher matcher = id == null ? null : ID.matcher(id.trim());
        if (matcher == null || !matcher.matches()) {
            return Lookup.MALFORMED;
        }
        if (!matcher.group(1).equals(run)) {
            return Lookup.OTHER_RUN;
        }
        synchronized (jobs) {
            purge();
            return jobs.containsKey(id.trim()) ? Lookup.FOUND : Lookup.UNKNOWN;
        }
    }

    /**
     * The job after waiting up to {@code waitMillis} (at most {@link #MAX_WAIT_MILLIS}) for it to
     * finish; {@code null} when {@link #lookup(String)} does not find it.
     */
    public Snapshot<R> await(String id, long waitMillis) throws InterruptedException {
        Job<R> job = find(id);
        if (job == null) {
            return null;
        }
        long wait = Math.max(0, Math.min(waitMillis, MAX_WAIT_MILLIS));
        if (wait > 0) {
            job.done.await(wait, TimeUnit.MILLISECONDS);
        }
        return job.snapshot();
    }

    /**
     * Ask a job to stop and wait up to {@code waitMillis} for it to do so. A queued job is cancelled at
     * once and never runs; a running one stops at its next check, and until then it is still
     * {@code running} with {@code cancelRequested}. A finished job is returned as it is.
     */
    public Snapshot<R> cancel(String id, long waitMillis) throws InterruptedException {
        Job<R> job = find(id);
        if (job == null) {
            return null;
        }
        synchronized (job) {
            if (job.state.finished()) {
                return job.snapshot();
            }
            job.cancelRequested = true;
            if (job.state == State.QUEUED) {
                job.finish(State.CANCELLED, null, null, now.getAsLong());
                return job.snapshot();
            }
        }
        long wait = Math.max(0, Math.min(waitMillis, MAX_WAIT_MILLIS));
        if (wait > 0) {
            job.done.await(wait, TimeUnit.MILLISECONDS);
        }
        return job.snapshot();
    }

    /**
     * Drop a finished job whose result the caller already has: a call with async=true that ended before
     * it answered returns the result itself, and its job must not take a place among the kept results.
     * A job still running is left alone.
     */
    public void forget(String id) {
        synchronized (jobs) {
            Job<R> job = id == null ? null : jobs.get(id.trim());
            if (job != null && job.snapshot().state().finished()) {
                jobs.remove(id.trim());
            }
        }
    }

    /** Every job this run still keeps, the newest first. */
    public List<Snapshot<R>> list() {
        List<Snapshot<R>> all = new ArrayList<>();
        synchronized (jobs) {
            purge();
            for (Job<R> job : jobs.values()) {
                all.add(job.snapshot());
            }
        }
        Collections.reverse(all);
        return all;
    }

    // ---------------------------------------------------------------- the running code's side

    /** The job this thread runs, or a context that does nothing outside a job. */
    public static Context context() {
        Job<?> job = CURRENT.get();
        return job != null ? job : NONE;
    }

    /** The step the current job is on; nothing outside a job. */
    public static void stage(String text) {
        context().stage(text);
    }

    /**
     * A step of the current job that has changed something and stays changed if the job is cancelled
     * after it: "the configuration is loaded into the infobase". Nothing outside a job.
     */
    public static void applied(String text) {
        context().applied(text);
    }

    /**
     * Whether the current job has been asked to stop; always false outside a job. A yes is taken as the
     * code having seen the request: the job then ends as cancelled.
     */
    public static boolean cancelRequested() {
        return context().cancelRequested();
    }

    /** Whether this thread runs a job. */
    public static boolean inJob() {
        return CURRENT.get() != null;
    }

    // ---------------------------------------------------------------- internals

    private void execute(Job<R> job, Work<R> work) {
        synchronized (job) {
            if (job.state != State.QUEUED) {
                return; // cancelled while it waited in the queue
            }
            job.state = State.RUNNING;
            job.startedAt = now.getAsLong();
        }
        CURRENT.set(job);
        R result = null;
        String error = null;
        boolean threw = false;
        try {
            result = work.run();
        } catch (Throwable failure) {
            threw = true;
            String message = failure.getMessage();
            error = failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
        } finally {
            CURRENT.remove();
        }
        State end;
        if (job.cancelSeen) {
            end = State.CANCELLED;
        } else if (threw || (result != null && failed.test(result))) {
            end = State.FAILED;
        } else {
            end = State.SUCCEEDED;
        }
        job.finish(end, result, error, now.getAsLong());
        synchronized (jobs) {
            purge();
        }
    }

    private Job<R> find(String id) {
        if (lookup(id) != Lookup.FOUND) {
            return null;
        }
        synchronized (jobs) {
            return jobs.get(id.trim());
        }
    }

    /** Drop the results kept past the retention time, then the oldest finished ones over the limit. */
    private void purge() {
        long time = now.getAsLong();
        int finished = 0;
        for (Iterator<Job<R>> it = jobs.values().iterator(); it.hasNext();) {
            Job<R> job = it.next();
            Snapshot<R> state = job.snapshot();
            if (state.state().finished()) {
                if (time - state.finishedAt() > RETENTION_MILLIS) {
                    it.remove();
                } else {
                    finished++;
                }
            }
        }
        if (finished > KEEP_FINISHED) {
            // By the time they finished, not by the order they were started in: a long job started first
            // and finished last is the newest result, not the oldest.
            List<Snapshot<R>> ended = new ArrayList<>();
            for (Job<R> job : jobs.values()) {
                Snapshot<R> state = job.snapshot();
                if (state.state().finished()) {
                    ended.add(state);
                }
            }
            ended.sort(java.util.Comparator.comparingLong(Snapshot::finishedAt));
            for (int i = 0; i < finished - KEEP_FINISHED; i++) {
                jobs.remove(ended.get(i).id());
            }
        }
    }

    private static final class Job<R> implements Context {
        final String id;
        final String tool;
        final long submittedAt;
        final CountDownLatch done = new CountDownLatch(1);
        State state = State.QUEUED;
        String stage = "";
        final List<String> applied = new ArrayList<>();
        long startedAt;
        long finishedAt;
        R result;
        String error;
        volatile boolean cancelRequested;
        /** The running code read the flag and got yes. */
        volatile boolean cancelSeen;

        Job(String id, String tool, long submittedAt) {
            this.id = id;
            this.tool = tool;
            this.submittedAt = submittedAt;
        }

        synchronized void setStage(String text) {
            stage = text == null ? "" : text;
        }

        synchronized void addApplied(String text) {
            if (text != null && !text.isBlank()) {
                applied.add(text);
            }
        }

        boolean isCancelRequested() {
            return cancelRequested;
        }

        @Override
        public void stage(String text) {
            setStage(text);
        }

        @Override
        public void applied(String text) {
            addApplied(text);
        }

        @Override
        public boolean cancelRequested() {
            if (!cancelRequested) {
                return false;
            }
            cancelSeen = true;
            return true;
        }

        void finish(State end, R value, String failure, long time) {
            synchronized (this) {
                if (state.finished()) {
                    return;
                }
                state = end;
                result = value;
                error = failure;
                finishedAt = time;
            }
            done.countDown();
        }

        synchronized Snapshot<R> snapshot() {
            return new Snapshot<>(id, tool, state, stage, List.copyOf(applied), submittedAt, startedAt, finishedAt,
                    result, error, cancelRequested);
        }
    }
}
