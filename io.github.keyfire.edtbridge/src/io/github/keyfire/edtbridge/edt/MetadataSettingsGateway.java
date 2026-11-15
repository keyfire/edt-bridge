package io.github.keyfire.edtbridge.edt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.wiring.ServiceAccess;

/** Typed metadata settings and subsystem membership through live BM transactions. */
public final class MetadataSettingsGateway {
    public record Change(Object before, Object after) { }

    public static final class Result {
        public boolean ok;
        public boolean applied;
        public boolean serialized;
        public String project;
        public String fqn;
        public String plan;
        public String message;
        public String warning;
        public Map<String, Change> changes = new LinkedHashMap<>();
        public List<String> contentBefore = new ArrayList<>();
        public List<String> contentAfter = new ArrayList<>();
        public List<String> added = new ArrayList<>();
        public List<String> removed = new ArrayList<>();
    }

    private static IProject project(String projectName) {
        if (projectName == null || projectName.isBlank()) throw new IllegalArgumentException("projectName is required");
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists() || !project.isOpen()) throw new IllegalArgumentException("Project not found or closed: " + projectName);
        return project;
    }

    private static IBmModel model(IProject project) {
        IBmModelManager manager = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = manager == null ? null : manager.getModel(project);
        if (model == null) throw new IllegalArgumentException("No BM model for project: " + project.getName());
        return model;
    }

    static boolean isExtensionProject(IProject project) {
        var manager = ServiceAccess.get(IV8ProjectManager.class);
        return manager != null && manager.getProject(project) instanceof IExtensionProject;
    }

    public Result modifyObject(String projectName, String fqn, Map<String, Object> properties,
            boolean extendModule, boolean apply) {
        Result result = new Result(); result.project = projectName; result.fqn = fqn;
        try {
            IBmModel model = model(project(projectName));
            model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.metadataSettings.inspect") {
                @Override public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    EObject object = tx.getTopObjectByFqn(fqn);
                    if (!(object instanceof CommonModule module)) throw new IllegalArgumentException("An existing CommonModule FQN is required");
                    result.changes = CommonModuleSettings.describe(module, properties, extendModule);
                    return null;
                }
            });
            result.ok = true;
            result.plan = "Set " + result.changes.size() + " common-module properties on " + fqn;
            if (!apply || result.changes.isEmpty()) {
                result.message = result.changes.isEmpty() ? "The requested settings already match" : "dry-run: nothing written";
                return result;
            }
            model.execute(new AbstractBmTask<Object>("edt-bridge.metadataSettings.apply") {
                @Override public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    EObject object = tx.getTopObjectByFqn(fqn);
                    if (!(object instanceof CommonModule module)) throw new IllegalArgumentException("The common module disappeared");
                    CommonModuleSettings.apply(module, properties, extendModule);
                    return null;
                }
            });
            result.applied = true;
            export(model, fqn, result);
            result.message = "Updated common-module properties on " + fqn;
        } catch (RuntimeException failure) {
            result.ok = false;
            result.message = (result.applied ? "The model changed, but export failed: " : "Nothing written: ")
                    + GatewaySupport.describeCause(failure);
        }
        return result;
    }

    public Result modifyExternalObject(String projectName, String fqn, Map<String, String> values, boolean apply) {
        return modifyProjectRoot(projectName, fqn, values, apply, false);
    }

    public Result modifyExtension(String projectName, Map<String, String> values, boolean apply) {
        return modifyProjectRoot(projectName, "Configuration", values, apply, true);
    }

    private Result modifyProjectRoot(String projectName, String fqn, Map<String, String> values, boolean apply,
            boolean extension) {
        Result result = new Result();
        result.project = projectName;
        result.fqn = fqn;
        try {
            if (fqn == null || fqn.isBlank()) throw new IllegalArgumentException("fqn is required");
            IProject project = project(projectName);
            if (extension && !isExtensionProject(project)) {
                throw new IllegalArgumentException("An EDT configuration extension project is required");
            }
            IBmModel model = model(project);
            var task = new AbstractBmTask<Map<String, Change>>("edt-bridge.projectRootSettings") {
                @Override public Map<String, Change> execute(IBmTransaction tx, IProgressMonitor monitor) {
                    EObject object = tx.getTopObjectByFqn(fqn);
                    if (extension) {
                        if (!(object instanceof Configuration configuration)) {
                            throw new IllegalArgumentException("Extension Configuration is not available");
                        }
                        return ExtensionProjectSettings.update(configuration, values, apply);
                    }
                    return ExternalObjectSettings.update(object instanceof MdObject md ? md : null,
                            fqn, values, apply);
                }
            };
            result.changes = apply ? model.execute(task) : model.executeReadonlyTask(task);
            result.applied = apply && !result.changes.isEmpty();
            if (result.applied) export(model, fqn, result);
            result.ok = true;
            result.plan = "Set " + result.changes.size() + " properties on " + fqn;
            result.message = result.changes.isEmpty() ? "The requested settings already match"
                    : apply ? "Updated properties on " + fqn : "dry-run: nothing written";
        } catch (RuntimeException failure) {
            result.ok = false;
            result.message = (result.applied ? "The model changed, but export failed: " : "Nothing written: ")
                    + GatewaySupport.describeCause(failure);
        }
        return result;
    }

    public Result updateSubsystem(String projectName, String fqn, List<String> add, List<String> remove,
            boolean force, boolean apply) {
        Result result = new Result(); result.project = projectName; result.fqn = fqn;
        try {
            IBmModel model = model(project(projectName));
            List<String> additions = add == null ? List.of() : List.copyOf(add);
            List<String> removals = remove == null ? List.of() : List.copyOf(remove);
            for (String name : additions) if (removals.contains(name)) {
                throw new IllegalArgumentException("An object cannot be both added and removed: " + name);
            }
            model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.subsystemContent.inspect") {
                @Override public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    Subsystem subsystem = subsystem(tx, fqn);
                    List<MdObject> added = resolveMembers(tx, additions);
                    List<MdObject> removed = resolveMembers(tx, removals);
                    validateDisjoint(added, removed);
                    result.contentBefore = names(subsystem.getContent());
                    List<MdObject> after = new ArrayList<>(subsystem.getContent());
                    for (int i = 0; i < added.size(); i++) if (!contains(after, added.get(i))) {
                        after.add(added.get(i)); result.added.add(additions.get(i));
                    }
                    for (int i = 0; i < removed.size(); i++) if (remove(after, removed.get(i))) {
                        result.removed.add(removals.get(i));
                    }
                    result.contentAfter = names(after);
                    return null;
                }
            });
            result.ok = true;
            result.plan = "Subsystem " + fqn + ": add " + result.added.size() + ", remove " + result.removed.size();
            if (!apply || result.added.isEmpty() && result.removed.isEmpty()) {
                result.message = "dry-run or unchanged membership: nothing written";
                return result;
            }
            if (!result.removed.isEmpty() && !force) throw new IllegalArgumentException("force=true is required to remove subsystem members");
            model.execute(new AbstractBmTask<Object>("edt-bridge.subsystemContent.apply") {
                @Override public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    Subsystem subsystem = subsystem(tx, fqn);
                    List<MdObject> added = resolveMembers(tx, additions);
                    List<MdObject> removed = resolveMembers(tx, removals);
                    validateDisjoint(added, removed);
                    List<MdObject> content = subsystem.getContent();
                    if (!force && removed.stream().anyMatch(object -> contains(content, object))) {
                        throw new IllegalArgumentException("force=true is required to remove subsystem members");
                    }
                    for (MdObject object : added) if (!contains(content, object)) content.add(object);
                    for (MdObject object : removed) remove(content, object);
                    return null;
                }
            });
            result.applied = true;
            String topFqn = topSubsystem(fqn);
            export(model, topFqn, result);
            result.message = "Updated subsystem membership: " + fqn;
        } catch (RuntimeException failure) {
            result.ok = false;
            result.message = (result.applied ? "The model changed, but export failed: " : "Nothing written: ")
                    + GatewaySupport.describeCause(failure);
        }
        return result;
    }

    private static void export(IBmModel model, String fqn, Result result) {
        IBmModelManager manager = ServiceAccess.get(IBmModelManager.class);
        var project = manager == null ? null : manager.getDtProject(model);
        result.serialized = project != null && manager.forceExport(project, fqn);
        if (!result.serialized) result.warning = "The model changed but serialization was not confirmed; inspect the working tree";
    }
    private static String topSubsystem(String fqn) {
        String[] parts = fqn == null ? new String[0] : fqn.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : fqn;
    }
    static Subsystem subsystem(IBmTransaction tx, String fqn) {
        String[] segments = fqn == null ? new String[0] : fqn.split("\\.", -1);
        if (segments.length < 2 || segments.length % 2 != 0) throw new IllegalArgumentException("A complete Subsystem FQN is required");
        for (int i = 0; i < segments.length; i += 2) {
            if (!"Subsystem".equals(segments[i]) || segments[i + 1].isBlank()) {
                throw new IllegalArgumentException("Invalid Subsystem FQN: " + fqn);
            }
        }
        EObject object = tx.getTopObjectByFqn(fqn);
        if (object instanceof Subsystem subsystem) return subsystem;
        String[] parts = fqn.split("\\.");
        object = tx.getTopObjectByFqn(topSubsystem(fqn));
        for (int i = 2; object instanceof Subsystem && i + 1 < parts.length; i += 2) {
            if (!"Subsystem".equals(parts[i])) throw new IllegalArgumentException("Invalid nested subsystem FQN");
            String name = parts[i + 1];
            object = ((Subsystem) object).getSubsystems().stream().filter(s -> name.equals(s.getName())).findFirst().orElse(null);
        }
        if (!(object instanceof Subsystem subsystem)) throw new IllegalArgumentException("Subsystem not found: " + fqn);
        return subsystem;
    }
    private static List<MdObject> resolveMembers(IBmTransaction tx, List<String> fqns) {
        List<MdObject> objects = new ArrayList<>();
        for (String fqn : fqns) {
            EObject object = fqn == null ? null : tx.getTopObjectByFqn(fqn);
            if (!(object instanceof MdObject md) || object instanceof Subsystem || "Configuration".equals(object.eClass().getName())) {
                throw new IllegalArgumentException("Subsystem member must be an existing top metadata object: " + fqn);
            }
            objects.add(md);
        }
        return objects;
    }
    static void validateDisjoint(List<MdObject> added, List<MdObject> removed) {
        for (MdObject object : added) if (contains(removed, object)) {
            throw new IllegalArgumentException("An object cannot be both added and removed: " + object.getName());
        }
    }
    private static boolean same(MdObject left, MdObject right) {
        return left.getUuid() != null && right.getUuid() != null ? left.getUuid().equals(right.getUuid())
                : left.eClass() == right.eClass() && java.util.Objects.equals(left.getName(), right.getName());
    }
    private static boolean contains(List<MdObject> list, MdObject object) {
        return list.stream().anyMatch(item -> same(item, object));
    }
    private static boolean remove(List<MdObject> list, MdObject object) {
        return list.removeIf(item -> same(item, object));
    }
    private static List<String> names(List<MdObject> objects) {
        return objects.stream().map(o -> o.eClass().getName() + "." + o.getName()).toList();
    }
}
