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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A wait that takes no timeout of its own, such as EDT's wait for the model to catch up with the
 * files, given a bound. The wait runs in a daemon thread: it cannot be stopped from outside, and
 * when it ends after the bound, nobody is waiting for it any more.
 */
public final class BoundedWait {
    private BoundedWait() { }

    /** A blocking call. */
    public interface Blocking {
        void run() throws Exception;
    }

    /** true when the wait ended within the bound; an exception of the wait comes out as it was thrown. */
    public static boolean within(Blocking wait, long timeoutMs, String threadName) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            wait.run();
            return null;
        });
        Thread thread = new Thread(task, threadName);
        thread.setDaemon(true);
        thread.start();
        try {
            task.get(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException late) {
            return false;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw failed;
        }
    }
}
