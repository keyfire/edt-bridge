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
package io.github.keyfire.edtbridge.edt;

import io.github.keyfire.edtbridge.core.Jobs;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

/**
 * The progress monitor of a long EDT operation: it tells the current job which step the operation is on
 * and answers {@code isCanceled} once the job is cancelled, so an export or a synchronization stops at its
 * next check. Outside a job it is a {@link NullProgressMonitor}.
 */
public final class JobProgressMonitor extends NullProgressMonitor {

    private String task = "";

    private JobProgressMonitor() {
    }

    /** A monitor for one EDT operation of the current call. */
    public static IProgressMonitor create() {
        return new JobProgressMonitor();
    }

    @Override
    public boolean isCanceled() {
        return super.isCanceled() || Jobs.cancelRequested();
    }

    @Override
    public void beginTask(String name, int totalWork) {
        task = name == null ? "" : name.strip();
        if (!task.isEmpty()) {
            Jobs.stage(task);
        }
    }

    @Override
    public void setTaskName(String name) {
        beginTask(name, UNKNOWN);
    }

    @Override
    public void subTask(String name) {
        String sub = name == null ? "" : name.strip();
        if (!sub.isEmpty()) {
            Jobs.stage(task.isEmpty() ? sub : task + ": " + sub);
        }
    }
}
