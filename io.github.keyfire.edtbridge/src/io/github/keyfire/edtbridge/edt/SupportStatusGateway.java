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

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.dt.core.model.EditingMode;
import com._1c.g5.v8.dt.core.model.IModelEditingSupport;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.wiring.ServiceAccess;

/** Read EDT's direct-edit policy; unavailable policy never means editable. */
public final class SupportStatusGateway {
    public static final class Result {
        public boolean ok;
        public boolean found;
        public boolean editable;
        public String status = "UNKNOWN";
        public String projectName;
        public String objectFqn;
        public String source = "EDT IModelEditingSupport / DIRECT";
        public String message;
    }

    static Result inspect(EObject owner, IModelEditingSupport policy) {
        Result result = new Result();
        result.found = owner != null && !owner.eIsProxy();
        if (!result.found || policy == null) {
            result.message = result.found ? "EDT editing policy is unavailable" : "object not found or unresolved";
            return result;
        }
        try {
            result.editable = policy.canEdit(owner, EditingMode.DIRECT);
            result.status = result.editable ? "EDITABLE" : "READ_ONLY";
            result.ok = true;
        } catch (RuntimeException unavailable) {
            result.message = "EDT editing policy could not be read";
        }
        return result;
    }

    public Result read(String projectName, String objectFqn) {
        Result result = new Result();
        result.projectName = projectName; result.objectFqn = objectFqn;
        if (projectName == null || objectFqn == null || objectFqn.isBlank()) {
            result.message = "projectName and objectFqn are required"; return result;
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        IBmModelManager models = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = project.exists() && project.isOpen() && models != null ? models.getModel(project) : null;
        if (model == null) { result.message = "project is closed, missing or has no model"; return result; }
        return model.executeReadonlyTask(new AbstractBmTask<Result>("edt-bridge.supportStatus") {
            @Override public Result execute(IBmTransaction tx, IProgressMonitor monitor) {
                EObject owner = tx.getTopObjectByFqn(objectFqn);
                IModelEditingSupport policy = FormWriteGateway.coreService(IModelEditingSupport.class);
                Result state = inspect(owner, policy);
                state.projectName = projectName; state.objectFqn = objectFqn;
                IResourceLookup lookup = FormWriteGateway.coreService(IResourceLookup.class);
                if (state.found && lookup != null) {
                    var file = lookup.getPlatformResource(owner);
                    if (file != null && file.getResourceAttributes() != null && file.getResourceAttributes().isReadOnly()) {
                        state.editable = false; state.status = "READ_ONLY"; state.ok = true;
                        state.message = "the backing workspace resource is read-only";
                    }
                }
                return state;
            }
        });
    }
}
