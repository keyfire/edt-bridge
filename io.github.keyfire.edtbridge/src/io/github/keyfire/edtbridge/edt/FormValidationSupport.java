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

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupport;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupportProvider;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.wiring.ServiceAccess;
import com.e1c.g5.v8.dt.check.ICheckScheduler;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import io.github.keyfire.edtbridge.core.FormPropertyValues;

/**
 * Revalidates form modules when {@code Form.form} changes through the bridge, and gathers BSL module
 * targets for scoped validation so BSL checks (such as SU243 on {@code РеквизитФормыВЗначение}) do
 * not keep stale markers from before a form-model edit.
 */
final class FormValidationSupport {
    private static final FormPropertyValues.PendingForms PENDING = new FormPropertyValues.PendingForms();

    private FormValidationSupport() { }

    /**
     * Record a form edit and immediately ask EDT's check scheduler to recheck the form and its BSL
     * module. Best-effort: never fails the already-committed write.
     */
    static void afterFormWrite(IProject project, IBmModel model, String formFqn) {
        if (project == null || formFqn == null || formFqn.isBlank()) return;
        PENDING.mark(project.getName(), formFqn);
        if (model == null) return;
        try {
            ICheckScheduler scheduler = ServiceAccess.get(ICheckScheduler.class);
            ICheckRepository repository = ServiceAccess.get(ICheckRepository.class);
            if (scheduler == null || repository == null || repository.isMassiveCheckProcessDisabled(project)) {
                return;
            }
            model.executeReadonlyTask(new AbstractBmTask<Void>("edt-bridge.formWrite.revalidate") {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor monitor) {
                    Form form = FormWriteGateway.resolveForm(tx, formFqn);
                    if (form == null) return null;
                    Set<Object> targets = new LinkedHashSet<>();
                    collectTargets(project, form, null, null, targets);
                    if (!targets.isEmpty()) {
                        scheduler.scheduleValidation(project, Set.of(), List.copyOf(targets), false, tx,
                                new NullProgressMonitor());
                    }
                    return null;
                }
            });
        } catch (RuntimeException | LinkageError ignored) {
            // Revalidation is best-effort; refresh=true will also pick up the pending form.
        }
    }

    static Set<String> pendingForms(String projectName) {
        return PENDING.snapshot(projectName);
    }

    static void acknowledgeForms(String projectName, Collection<String> forms) {
        PENDING.acknowledge(projectName, forms);
    }

    /**
     * Collect BM top-object ids and BSL module platform paths ({@code /Project/src/.../Module.bsl})
     * for {@code resolved} so {@link ICheckScheduler#scheduleValidation} runs both model checks and
     * language checks without turning on project-wide recursion.
     */
    static void collectTargets(IProject project, Object resolved, String modulePath, IResource scopeResource,
            Collection<Object> out) {
        if (resolved instanceof IBmObject bm && bm.bmGetId() != IBmObject.BM_NULL_ID) {
            out.add(Long.valueOf(bm.bmGetId()));
        }
        IProjectFileSystemSupport support = fileSystemSupport(project);
        if (resolved instanceof Form form) {
            addFormModule(support, form.getMdForm(), out);
        } else if (resolved instanceof BasicForm basicForm) {
            Form content = FormWriteGateway.asFormContent(basicForm);
            if (content != null && content.bmGetId() != IBmObject.BM_NULL_ID) {
                out.add(Long.valueOf(content.bmGetId()));
            }
            addFormModule(support, basicForm, out);
        } else if (resolved instanceof MdObject md) {
            collectOwnerForms(support, md, out);
            if (scopeResource != null && scopeResource != project) {
                collectBslFiles(scopeResource, out);
            }
        }
        if (modulePath != null && !modulePath.isBlank()) {
            IResource member = project.findMember(modulePath.trim().replace('\\', '/'));
            if (member instanceof IFile file && file.exists()) {
                String path = FormPropertyValues.moduleTargetPath(file.getFullPath().toString());
                if (path != null) out.add(path);
            }
        }
    }

    private static void collectOwnerForms(IProjectFileSystemSupport support, MdObject owner, Collection<Object> out) {
        for (EReference ref : owner.eClass().getEAllReferences()) {
            if (!ref.isMany() || !ref.isContainment() || ref.getEReferenceType() == null
                    || !MdClassPackage.Literals.BASIC_FORM.isSuperTypeOf(ref.getEReferenceType())) {
                continue;
            }
            Object value = owner.eGet(ref);
            if (!(value instanceof List<?> list)) continue;
            for (Object each : list) {
                if (each instanceof BasicForm basicForm) {
                    Form content = FormWriteGateway.asFormContent(basicForm);
                    if (content != null && content.bmGetId() != IBmObject.BM_NULL_ID) {
                        out.add(Long.valueOf(content.bmGetId()));
                    }
                    addFormModule(support, basicForm, out);
                }
            }
        }
    }

    private static void addFormModule(IProjectFileSystemSupport support, BasicForm mdForm, Collection<Object> out) {
        if (support == null || mdForm == null) return;
        try {
            IFile file = support.getFile(mdForm, MdClassPackage.Literals.ABSTRACT_FORM__MODULE);
            if (file != null && file.exists()) {
                String path = FormPropertyValues.moduleTargetPath(file.getFullPath().toString());
                if (path != null) out.add(path);
            }
        } catch (RuntimeException ignored) {
            // Form without a module file
        }
    }

    private static void collectBslFiles(IResource resource, Collection<Object> out) {
        try {
            if (resource instanceof IFile file && file.exists()) {
                String path = FormPropertyValues.moduleTargetPath(file.getFullPath().toString());
                if (path != null) out.add(path);
                return;
            }
            if (resource instanceof IContainer container && container.exists()) {
                container.accept(member -> {
                    if (member instanceof IFile file && file.exists()) {
                        String path = FormPropertyValues.moduleTargetPath(file.getFullPath().toString());
                        if (path != null) out.add(path);
                        return false;
                    }
                    return true;
                });
            }
        } catch (CoreException ignored) {
            // Best-effort collection
        }
    }

    private static IProjectFileSystemSupport fileSystemSupport(IProject project) {
        try {
            IProjectFileSystemSupportProvider provider =
                    FormWriteGateway.coreService(IProjectFileSystemSupportProvider.class);
            return provider == null ? null : provider.getProjectFileSystemSupport(project);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
