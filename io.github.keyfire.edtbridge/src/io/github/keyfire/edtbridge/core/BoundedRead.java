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

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * A value read on a thread of its own, waited for no longer than a limit.
 *
 * <p>The status of the bridge asks the project model, and under several heavy calls that can take
 * seconds. A client that probes the bridge must still get an answer in time, so the slow part is read
 * here: a caller waits up to the limit and goes on without the value. Callers that come while a read
 * runs wait for that same read, so a read that hangs holds one thread and is never started twice.
 * A caller that comes after a read ended starts a new one.
 */
public final class BoundedRead<T> {

    private final Supplier<T> reader;
    private final long limitMillis;
    private final ThreadPoolExecutor thread;
    private Future<T> running; // guarded by this

    public BoundedRead(String threadName, long limitMillis, Supplier<T> reader) {
        this.reader = reader;
        this.limitMillis = limitMillis;
        this.thread = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread daemon = new Thread(runnable, threadName);
            daemon.setDaemon(true);
            return daemon;
        });
    }

    /** The value, or null when the read did not end within the limit or failed. */
    public T get() {
        Future<T> read;
        synchronized (this) {
            if (running == null || running.isDone()) {
                running = thread.submit(reader::get);
            }
            read = running;
        }
        try {
            return read.get(limitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            return null;
        } catch (ExecutionException failed) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
