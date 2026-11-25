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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmWorkspaceSynchronizationListener;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.wiring.ServiceAccess;
import io.github.keyfire.edtbridge.core.BoundedWait;

/**
 * A form changed on disk is loaded into the model before the bridge reads the form for a write.
 *
 * <p>EDT learns that a file was edited outside it when the workspace refreshes the file, and loads
 * the file into the model a moment later. A write that comes in between starts from the old model,
 * and its export puts the old form back over the edit. So before a form write the bridge refreshes
 * {@code Form.form} itself and waits until EDT has synchronized the model of the project with its
 * files. When EDT does not get there in time, nothing is written.
 */
final class FormFileSync {
    static final long WAIT_MS = 30_000;

    private FormFileSync() { }

    /**
     * null when the form may be read for a write, also when there is no such form: the caller says
     * that in its own words. Otherwise why nothing is written.
     */
    static String settle(IProject project, IBmModel model, String formFqn) {
        IResourceLookup lookup = FormWriteGateway.coreService(IResourceLookup.class);
        IBmModelManager manager = ServiceAccess.get(IBmModelManager.class);
        if (lookup == null || manager == null || formFqn == null || formFqn.isBlank()) return null;
        IFile file;
        try {
            file = model.executeReadonlyTask(new AbstractBmTask<IFile>("edt-bridge.formFileSync.file") {
                @Override
                public IFile execute(IBmTransaction tx, IProgressMonitor monitor) {
                    Form form = FormWriteGateway.resolveForm(tx, formFqn);
                    return form == null ? null : lookup.getPlatformResource(form);
                }
            });
        } catch (RuntimeException unresolved) {
            return null;
        }
        if (file == null) return null;
        String path = file.getProjectRelativePath().toPortableString();
        boolean changed = false;
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
            CountDownLatch imported = new CountDownLatch(1);
            IBmWorkspaceSynchronizationListener listener = new IBmWorkspaceSynchronizationListener() {
                @Override public void resourceSynchronized(IFile resource) {
                    if (file.equals(resource)) imported.countDown();
                }

                @Override public void resourceDeleted(IFile resource) {
                    if (file.equals(resource)) imported.countDown();
                }
            };
            boolean importFinished = true;
            model.addResourceSyncListener(listener);
            try {
                changed = !file.isSynchronized(IResource.DEPTH_ZERO);
                if (changed) {
                    file.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
                    // A queued background import does not count as active in waitModelSynchronization yet.
                    importFinished = imported.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                }
            } finally {
                model.removeResourceSyncListener(listener);
            }
            long remaining = deadline - System.nanoTime();
            if (importFinished && remaining > 0 && BoundedWait.within(
                    () -> manager.waitModelSynchronization(project),
                    Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)), "edt-bridge-model-sync")) {
                return null;
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return "nothing written: could not load " + path + " from disk into the model: "
                    + GatewaySupport.describeCause(failure);
        }
        long seconds = WAIT_MS / 1000;
        return changed
                ? "nothing written: " + path + " was changed on disk, and EDT has not loaded it into the model in "
                        + seconds + " s; repeat the call when EDT is idle, or the write would put the old form back"
                : "nothing written: EDT has not synchronized the model of " + project.getName() + " with its files in "
                        + seconds + " s; repeat the call when EDT is idle";
    }
}
