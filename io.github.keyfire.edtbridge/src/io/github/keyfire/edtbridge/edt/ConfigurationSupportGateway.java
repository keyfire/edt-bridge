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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.event.IBmSyncEventListener;
import com._1c.g5.v8.dt.core.library.ILibraryUpdateService;
import com._1c.g5.v8.dt.core.library.LibraryDescriptor;
import com._1c.g5.v8.dt.core.library.repository.ILibraryRepository;
import com._1c.g5.v8.dt.core.operations.IProjectOperationApi;
import com._1c.g5.v8.dt.core.operations.ProjectPipelineJob;
import com._1c.g5.v8.dt.compare.datasource.LibraryComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.wiring.ServiceAccess;
import com.e1c.g5.v8.dt.distribution.IDistributionSupportManager;
import com.e1c.g5.v8.dt.distribution.IDistributionSupportManager.ChangeUserModeResponseOptionsResult;
import com.e1c.g5.v8.dt.distribution.model.ConfigurationFileState;
import com.e1c.g5.v8.dt.distribution.model.DistributionSupport;
import com.e1c.g5.v8.dt.distribution.model.ParentConfigurationInfo;
import com.e1c.g5.v8.dt.distribution.model.ParentConfigurationStoreMode;
import com.e1c.g5.v8.dt.distribution.model.UserSupportMode;

import io.github.keyfire.edtbridge.core.Jobs;
import io.github.keyfire.edtbridge.edt.ConfigurationComparisonGateway.SupplierTarget;

/** Reads and changes the support rules of connected suppliers through the EDT model. */
public final class ConfigurationSupportGateway {
    private record Project(IBmModelManager models, IBmModel model, IDtProject project) { }

    public record Supplier(UUID id, String provider, String configuration, String release) { }
    public record Rule(UUID supplierId, String fqn, String supplierMode, String userMode) { }
    public record State(String project, boolean fullSupport, List<Supplier> suppliers, List<Rule> rules) { }
    public record Change(String fqn, String before, String after, boolean supplierWarning) { }
    public record Edit(UUID supplierId, boolean applied, boolean exportScheduled, List<Change> changes, String warning) { }

    public record EnableEdit(String project, boolean applied, boolean fullSupport, boolean exportScheduled, List<Supplier> suppliers,
            String initialUserMode, String warning) { }

    private ConfigurationSupportGateway() { }

    public static State read(String projectName, List<String> fqns, UUID supplierId) {
        Project project = project(projectName);
        IDistributionSupportManager service = service();
        return project.model().executeReadonlyTask(new AbstractBmTask<State>("edt-bridge.support.read") {
            @Override
            public State execute(IBmTransaction tx, IProgressMonitor monitor) {
                DistributionSupport support = service.getDistributionSupport(project.project());
                List<MdObject> objects = objects(tx, fqns);
                if (support == null && supplierId == null) {
                    return new State(projectName, false, List.of(), List.of());
                }
                List<ParentConfigurationInfo> parents = supplierId == null ? support.getParentConfigurationInfos()
                        : List.of(supplier(support, supplierId));
                List<Supplier> suppliers = new ArrayList<>();
                List<Rule> rules = new ArrayList<>();
                for (ParentConfigurationInfo parent : parents) {
                    suppliers.add(describeSupplier(parent));
                    for (int i = 0; i < objects.size(); i++) {
                        var modes = service.getSupportModes(parent, objects.get(i), support, project.project());
                        if (modes != null) {
                            rules.add(new Rule(parent.getId(), fqns.get(i), name(modes.getFirst()), name(modes.getSecond())));
                        }
                    }
                }
                return new State(projectName, fullSupport(support), suppliers, rules);
            }
        });
    }

    public static synchronized EnableEdit enableEditing(String projectName, boolean apply) {
        Project project = project(projectName);
        IDistributionSupportManager supportService = service();
        var updateService = ServiceAccess.get(ILibraryUpdateService.class);
        if (updateService == null) throw new IllegalStateException("EDT's library update service is unavailable.");
        EnablePlan plan = project.model().executeReadonlyTask(
                new AbstractBmTask<EnablePlan>("edt-bridge.support.enable.preview") {
                    @Override
                    public EnablePlan execute(IBmTransaction tx, IProgressMonitor monitor) {
                        var support = supportService.getDistributionSupport(project.project());
                        if (!fullSupport(support)) {
                            String fqn = support != null && !support.getParentConfigurationInfos().isEmpty()
                                    ? exportFqn(support) : null;
                            return new EnablePlan(List.of(), List.of(), fqn);
                        }
                        var local = support.getParentConfigurationInfos().stream()
                                .filter(parent -> parent.getStoreMode() == ParentConfigurationStoreMode.SELF).toList();
                        if (local.isEmpty()) {
                            throw new IllegalArgumentException("EDT did not expose a supplier to enable editing for.");
                        }
                        return new EnablePlan(local,
                                local.stream().map(ConfigurationSupportGateway::describeSupplier).toList(), null);
                    }
                });
        UserSupportMode initialMode = UserSupportMode.CHANGES_NOT_ALLOWED;
        if (!apply || plan.parents().isEmpty()) {
            ExportConfirmation export = apply && plan.exportFqn() != null
                    ? requestExport(project, plan.exportFqn()) : new ExportConfirmation(false, null);
            return new EnableEdit(projectName, false, !plan.parents().isEmpty(), export.scheduled(),
                    plan.suppliers(), initialMode.name(), export.warning());
        }
        updateService.enableModification(plan.parents(), initialMode, initialMode, new NullProgressMonitor());
        EnableResult result = project.model().executeReadonlyTask(
                new AbstractBmTask<EnableResult>("edt-bridge.support.enable.verify") {
                    @Override
                    public EnableResult execute(IBmTransaction tx, IProgressMonitor monitor) {
                        var support = supportService.getDistributionSupport(project.project());
                        return new EnableResult(fullSupport(support), exportFqn(support),
                                hasConvertedSuppliers(support, plan.suppliers()));
                    }
                });
        boolean applied = !result.fullSupport() && result.suppliersRetained();
        if (!applied) {
            return new EnableEdit(projectName, false, result.fullSupport(), false, plan.suppliers(),
                    initialMode.name(), "EDT did not confirm the transition for all selected suppliers. Check support settings.");
        }
        ExportConfirmation export = requestExport(project, result.exportFqn());
        return new EnableEdit(projectName, true, false, export.scheduled(), plan.suppliers(),
                initialMode.name(), export.warning());
    }

    private record EnablePlan(List<ParentConfigurationInfo> parents, List<Supplier> suppliers, String exportFqn) { }

    private record EnableResult(boolean fullSupport, String exportFqn, boolean suppliersRetained) { }

    private static boolean hasConvertedSuppliers(DistributionSupport support, List<Supplier> suppliers) {
        if (support == null) return false;
        Set<UUID> converted = new LinkedHashSet<>();
        for (var parent : support.getParentConfigurationInfos()) {
            if (parent.getStoreMode() == ParentConfigurationStoreMode.FILE) converted.add(parent.getId());
        }
        return suppliers.stream().allMatch(supplier -> converted.contains(supplier.id()));
    }

    private static Supplier describeSupplier(ParentConfigurationInfo parent) {
        return new Supplier(parent.getId(), parent.getProviderName(), parent.getConfigName(), parent.getConfigRelease());
    }

    public static Edit modify(String projectName, List<String> fqns, UUID supplierId, String mode, boolean apply) {
        UserSupportMode requested = UserSupportMode.valueOf(mode);
        Project project = project(projectName);
        IDistributionSupportManager service = service();
        if (requested == UserSupportMode.CHANGES_NOT_ALLOWED) {
            return restore(projectName, project, service, fqns, supplierId, apply);
        }
        var task = new AbstractBmTask<PendingEdit>(apply ? "edt-bridge.support.apply" : "edt-bridge.support.preview") {
            @Override
            public PendingEdit execute(IBmTransaction tx, IProgressMonitor monitor) {
                DistributionSupport support = service.getDistributionSupport(project.project());
                ParentConfigurationInfo parent = supplier(support, supplierId);
                if (fullSupport(support)) {
                    throw new IllegalArgumentException("The project is on full support. Call edt_enable_configuration_editing first.");
                }
                List<MdObject> objects = objects(tx, fqns);
                Map<MdObject, Integer> selected = new LinkedHashMap<>();
                List<Change> changes = new ArrayList<>();
                for (int i = 0; i < objects.size(); i++) {
                    MdObject object = objects.get(i);
                    var modes = service.getSupportModes(parent, object, support, project.project());
                    if (modes == null || modes.getSecond() == null) {
                        throw new IllegalArgumentException("No support rule for this supplier: " + fqns.get(i));
                    }
                    if (modes.getSecond() != requested) selected.put(object, i);
                }
                String exportFqn = exportFqn(support);
                if (apply && exportFqn == null) {
                    throw new IllegalStateException("EDT did not expose writable support settings for this project.");
                }
                if (selected.isEmpty()) return new PendingEdit(exportFqn, changes);
                var response = service.getChangeUserModeResponseOptions(
                        new LinkedHashSet<>(selected.keySet()), parent, requested, false, support);
                if (response == null) throw new IllegalArgumentException("EDT returned no support change plan.");
                if (response.getResult() == ChangeUserModeResponseOptionsResult.SET_ONLY_IN_COMPARE_MERGE) {
                    throw new IllegalArgumentException("EDT can set this support mode only through comparison and merge.");
                }
                if (response.getProtectObjs() != null && !response.getProtectObjs().isEmpty()) {
                    throw new IllegalArgumentException("The supplier protects one or more selected objects.");
                }
                Set<MdObject> normal = new LinkedHashSet<>();
                if (response.getNormalObjs() != null) normal.addAll(response.getNormalObjs());
                Set<MdObject> warnings = new LinkedHashSet<>();
                if (response.getWarningObjs() != null) warnings.addAll(response.getWarningObjs());
                normal.addAll(warnings);
                if (!normal.equals(selected.keySet())) {
                    throw new IllegalArgumentException("EDT requires a different object selection for this support change.");
                }
                for (var entry : selected.entrySet()) {
                    MdObject object = entry.getKey();
                    var modes = service.getSupportModes(parent, object, support, project.project());
                    changes.add(new Change(fqns.get(entry.getValue()), name(modes.getSecond()), requested.name(),
                            warnings.contains(object)));
                }
                if (apply) {
                    for (MdObject object : selected.keySet()) {
                        service.setObjectSupportModeForUser(parent, object, requested, support);
                    }
                }
                return new PendingEdit(exportFqn, changes);
            }
        };
        PendingEdit edit = apply ? project.model().execute(task) : project.model().executeReadonlyTask(task);
        boolean applied = apply && !edit.changes().isEmpty();
        ExportConfirmation export = apply ? requestExport(project, edit.exportFqn()) : new ExportConfirmation(false, null);
        return new Edit(supplierId, applied, export.scheduled(), edit.changes(), export.warning());
    }

    private record RestorePlan(Supplier supplier, UUID version, List<SupplierTarget> targets,
            List<Change> changes, List<SavedObject> savedObjects) { }

    private record SavedObject(Map<String, Long> files, List<String> metadata) { }

    private static Edit restore(String projectName, Project project, IDistributionSupportManager service,
            List<String> fqns, UUID supplierId, boolean apply) {
        var modelChanged = new AtomicBoolean();
        IBmSyncEventListener listener = event -> modelChanged.set(true);
        if (apply) project.model().addSyncEventListener(listener);
        try {
            RestorePlan plan = project.model().executeReadonlyTask(new AbstractBmTask<RestorePlan>("edt-bridge.support.restore.plan") {
                @Override
                public RestorePlan execute(IBmTransaction tx, IProgressMonitor monitor) {
                    return restorePlan(project, service, tx, fqns, supplierId);
                }
            });
            if (!plan.targets().isEmpty()) {
                var baseline = library(plan.supplier());
                try {
                    ConfigurationComparisonGateway.requireSupplierMatch(projectName,
                            new LibraryComparisonDataSourceDescriptor(baseline), plan.targets());
                } catch (RuntimeException failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new IllegalStateException("Supplier comparison failed: " + GatewaySupport.describeCause(failure), failure);
                }
            }
            if (!apply) return new Edit(supplierId, false, false, plan.changes(), null);
            return applyRestore(project, service, fqns, supplierId, plan, modelChanged);
        } finally {
            if (apply) project.model().removeSyncEventListener(listener);
        }
    }

    private static Edit applyRestore(Project project, IDistributionSupportManager service, List<String> fqns,
            UUID supplierId, RestorePlan plan, AtomicBoolean modelChanged) {
        var operations = ServiceAccess.get(IProjectOperationApi.class);
        if (operations == null) throw new IllegalStateException("Exclusive EDT project operations are unavailable.");
        var exportFqn = new AtomicReference<String>();
        var error = new AtomicReference<RuntimeException>();
        operations.performExclusiveOperation(() -> {
            try {
                exportFqn.set(project.model().execute(new AbstractBmTask<String>("edt-bridge.support.restore.apply") {
                    @Override
                    public String execute(IBmTransaction tx, IProgressMonitor monitor) {
                        if (Jobs.cancelRequested()) throw new org.eclipse.core.runtime.OperationCanceledException();
                        var updated = restorePlan(project, service, tx, fqns, supplierId);
                        if (modelChanged.get() || !plan.equals(updated)) {
                            throw new IllegalArgumentException("The project changed during supplier comparison. Retry the request.");
                        }
                        var support = service.getDistributionSupport(project.project());
                        var parent = supplier(support, supplierId);
                        var fqn = exportFqn(support);
                        if (fqn == null) throw new IllegalStateException("EDT did not expose writable support settings for this project.");
                        for (SupplierTarget target : plan.targets()) {
                            MdObject object = MetadataWriteGateway.resolveMetadataTarget(tx, target.fqn());
                            service.setObjectSupportModeForUser(parent, object, UserSupportMode.CHANGES_NOT_ALLOWED, support);
                        }
                        return fqn;
                    }
                }));
            } catch (RuntimeException failure) {
                error.set(failure);
            }
        }, ProjectPipelineJob.BUILD, new NullProgressMonitor(), project.project());
        if (error.get() != null) throw error.get();
        if (exportFqn.get() == null) throw new IllegalStateException("EDT could not start the exclusive support operation.");
        var export = requestExport(project, exportFqn.get());
        return new Edit(supplierId, !plan.changes().isEmpty(), export.scheduled(), plan.changes(), export.warning());
    }

    private static RestorePlan restorePlan(Project project, IDistributionSupportManager service, IBmTransaction tx,
            List<String> fqns, UUID supplierId) {
        var support = service.getDistributionSupport(project.project());
        var parent = supplier(support, supplierId);
        if (fullSupport(support)) {
            throw new IllegalArgumentException("The project is on full support. Call edt_enable_configuration_editing first.");
        }
        var selected = objects(tx, fqns);
        List<SupplierTarget> targets = new ArrayList<>();
        List<SavedObject> savedObjects = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            var object = selected.get(i);
            String fqn = fqns.get(i);
            var modes = service.getSupportModes(parent, object, support, project.project());
            if (modes == null || modes.getSecond() == null) {
                throw new IllegalArgumentException("No support rule for this supplier: " + fqn);
            }
            if (modes.getSecond() == UserSupportMode.CHANGES_NOT_ALLOWED) continue;
            var item = service.getParentConfigurationInfoItem(parent, object, support, project.project());
            if (item == null || item.getParentId() == null || object.getUuid() == null
                    || !object.getUuid().equals(item.getUserId())) {
                throw new IllegalArgumentException("EDT did not identify the corresponding supplier object: " + fqn);
            }
            if (!(object instanceof IBmObject bm) || !bm.bmIsTop()) {
                throw new IllegalArgumentException("EDT supplier comparison requires a top-level metadata object: " + fqn);
            }
            var options = service.getChangeUserModeResponseOptions(Set.of(object), parent,
                    UserSupportMode.CHANGES_NOT_ALLOWED, false, support);
            if (options == null || options.getResult() != ChangeUserModeResponseOptionsResult.SET_ONLY_IN_COMPARE_MERGE) {
                throw new IllegalArgumentException("EDT cannot restore the support rule for this object independently: " + fqn);
            }
            List<String> metadata = new ArrayList<>();
            captureMetadata(object, metadata);
            savedObjects.add(new SavedObject(savedObjectStamps(object, fqn), metadata));
            targets.add(new SupplierTarget(bm.bmGetFqn(), object.eClass(), object.getUuid(), item.getParentId()));
            changes.add(new Change(fqn, name(modes.getSecond()), UserSupportMode.CHANGES_NOT_ALLOWED.name(), false));
        }
        return new RestorePlan(describeSupplier(parent), parent.getConfigVersion(), List.copyOf(targets),
                List.copyOf(changes), List.copyOf(savedObjects));
    }

    /** Capture persisted model properties while the transaction holds the selected object's model locks. */
    private static void captureMetadata(EObject object, List<String> values) {
        values.add(object.eClass().getName());
        for (EStructuralFeature feature : object.eClass().getEAllStructuralFeatures()) {
            if (feature.isDerived() || feature.isTransient() || feature.isVolatile() || !object.eIsSet(feature)) continue;
            values.add(feature.getName());
            Object value = object.eGet(feature, false);
            if (feature.isMany()) {
                List<?> members = (List<?>) value;
                values.add(Integer.toString(members.size()));
                for (Object member : members) captureValue(feature, member, values);
            } else {
                captureValue(feature, value, values);
            }
        }
        values.add(null);
    }

    private static void captureValue(EStructuralFeature feature, Object value, List<String> values) {
        if (value instanceof EObject target) {
            if (feature instanceof EReference reference && reference.isContainment()) captureMetadata(target, values);
            else values.add(EcoreUtil.getURI(target).toString());
        } else if (feature instanceof EAttribute attribute) {
            values.add(EcoreUtil.convertToString(attribute.getEAttributeType(), value));
        }
    }

    private static Map<String, Long> savedObjectStamps(MdObject object, String fqn) {
        var operations = ServiceAccess.get(IProjectOperationApi.class);
        if (operations == null) throw new IllegalStateException("EDT editing contexts are unavailable.");
        var editor = operations.getExistingContext(object);
        if (editor != null && (editor.isDisposed() || editor.isDirty())) {
            throw new IllegalArgumentException("Save the metadata editor before supplier comparison: " + fqn);
        }
        var lookup = FormWriteGateway.coreService(IResourceLookup.class);
        var resource = lookup == null ? null : lookup.getPlatformResource(object);
        if (resource == null || resource.getParent() == null || resource.getParent().getLocation() == null
                || !resource.getParent().isSynchronized(IResource.DEPTH_INFINITE)) {
            throw new IllegalArgumentException("Synchronize the object resources in EDT before supplier comparison: " + fqn);
        }
        var buffers = FileBuffers.getTextFileBufferManager();
        if (buffers == null) throw new IllegalStateException("Eclipse text file buffers are unavailable.");
        for (var buffer : buffers.getFileBuffers()) {
            var path = buffer.getLocation();
            if (buffer.isDirty() && path != null && (resource.getParent().getFullPath().isPrefixOf(path)
                    || resource.getParent().getLocation().isPrefixOf(path))) {
                throw new IllegalArgumentException("Save the object text editors before supplier comparison: " + fqn);
            }
        }
        Map<String, Long> stamps = new LinkedHashMap<>();
        try {
            resource.getParent().accept(member -> {
                if (member instanceof IFile file) {
                    stamps.put(file.getProjectRelativePath().toPortableString(), file.getModificationStamp());
                }
                return true;
            });
        } catch (CoreException failure) {
            throw new IllegalStateException("Could not inspect the saved object resources: " + fqn, failure);
        }
        return stamps;
    }

    private static LibraryDescriptor library(Supplier supplier) {
        var repository = ServiceAccess.get(ILibraryRepository.class);
        if (repository == null) throw new IllegalStateException("EDT's library repository is unavailable.");
        var descriptor = repository.getLibraryDescriptor(
                supplier.provider(), supplier.configuration(), supplier.release(), false);
        if (descriptor == null || !java.util.Objects.equals(supplier.provider(), descriptor.getLibraryVendor())
                || !java.util.Objects.equals(supplier.configuration(), descriptor.getLibraryName())
                || !java.util.Objects.equals(supplier.release(), descriptor.getLibraryVersion())) {
            throw new IllegalArgumentException("EDT did not find the recorded supplier release in its library repository.");
        }
        return descriptor;
    }

    private static String exportFqn(DistributionSupport support) {
        return support instanceof IBmObject bm && bm.bmIsTop() ? bm.bmGetFqn() : null;
    }

    private static ExportConfirmation requestExport(Project project, String fqn) {
        try {
            boolean scheduled = fqn != null && project.models().forceExport(project.project(), fqn);
            return new ExportConfirmation(scheduled,
                    scheduled ? null : "EDT did not accept the export request for the support settings.");
        } catch (RuntimeException failure) {
            return new ExportConfirmation(false,
                    "Could not request export of the support settings: " + GatewaySupport.describeCause(failure));
        }
    }

    private record ExportConfirmation(boolean scheduled, String warning) { }

    private record PendingEdit(String exportFqn, List<Change> changes) { }

    private static boolean fullSupport(DistributionSupport support) {
        return support != null && support.getFileState() == ConfigurationFileState.DISTRIBUTIVE;
    }

    private static String name(Enum<?> mode) {
        return mode == null ? null : mode.name();
    }

    private static List<MdObject> objects(IBmTransaction tx, List<String> fqns) {
        Set<MdObject> result = new LinkedHashSet<>();
        for (String fqn : fqns) {
            MdObject object = MetadataWriteGateway.resolveMetadataTarget(tx, fqn);
            if (object == null || object.eIsProxy()) {
                throw new IllegalArgumentException("Metadata object not found: " + fqn);
            }
            if (!result.add(object)) throw new IllegalArgumentException("Object already selected: " + fqn);
        }
        return List.copyOf(result);
    }

    private static ParentConfigurationInfo supplier(DistributionSupport support, UUID id) {
        if (support != null) {
            for (ParentConfigurationInfo parent : support.getParentConfigurationInfos()) {
                if (id.equals(parent.getId())) return parent;
            }
        }
        throw new IllegalArgumentException("Supplier is not connected to the project: " + id);
    }

    private static Project project(String name) {
        var resource = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        IBmModelManager models = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = models == null || !resource.isOpen() ? null : models.getModel(resource);
        IDtProject project = model == null ? null : models.getDtProject(model);
        if (project == null) throw new IllegalArgumentException("Open EDT project required: " + name);
        return new Project(models, model, project);
    }

    private static IDistributionSupportManager service() {
        IDistributionSupportManager service = ServiceAccess.get(IDistributionSupportManager.class);
        if (service == null) throw new IllegalStateException("EDT's configuration support service is unavailable.");
        return service;
    }
}
