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
import java.util.TreeMap;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.model.IModelObjectCollectionRuntimeOrderSorter;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.rights.IRightInfosService;
import com._1c.g5.v8.dt.rights.RightsPlugin;
import com._1c.g5.v8.dt.rights.model.ObjectRight;
import com._1c.g5.v8.dt.rights.model.ObjectRights;
import com._1c.g5.v8.dt.rights.model.Right;
import com._1c.g5.v8.dt.rights.model.RightValue;
import com._1c.g5.v8.dt.rights.model.RightsFactory;
import com._1c.g5.v8.dt.rights.model.Rls;
import com._1c.g5.v8.dt.rights.model.RoleDescription;
import com._1c.g5.v8.dt.rights.model.util.RightsModelUtil;
import com._1c.g5.wiring.ServiceAccess;
import io.github.keyfire.edtbridge.core.RoleRightsEdit;

/**
 * The rights of one role on one object through the live model: read them, and change them with
 * a dry-run first. A right with a data access restriction (RLS) is reported and never changed.
 *
 * <p>In an extension the role and the object have to belong to the extension, its own or
 * adopted; a right there is either set by the extension or not set, and then the base role
 * decides. EDT keeps that third state as {@link RightValue#PROVIDED}, reported as {@code null}.
 */
public final class RoleRightsGateway {
    /** One right of the object in the role. */
    public static final class RightState {
        public String name;
        public String nameRu;
        /** true or false; null when the role leaves the right to the base configuration. */
        public Boolean value;
        /** Whether the role states the value itself rather than taking the default. */
        public boolean explicit;
        public boolean restricted;
        public List<String> conditions;
    }

    /** A top object the role has rights on, with the number of its own and its child entries. */
    public static final class ObjectEntry {
        public String object;
        public int rights;
        public int childObjects;
    }

    public static final class Result {
        public boolean ok;
        public boolean applied;
        public boolean serialized;
        public String project;
        public String role;
        public String object;
        public Boolean adoptedRole;
        public String plan;
        public String message;
        public String warning;
        public Map<String, Boolean> flags;
        public List<RightState> rights;
        public List<ObjectEntry> objects;
        public List<Change> changes;
    }

    /** One planned change; before is null when the base configuration decides. */
    public static final class Change {
        public String right;
        public Boolean before;
        public boolean after;

        static List<Change> of(List<RoleRightsEdit.Change> plan) {
            List<Change> changes = new ArrayList<>();
            for (RoleRightsEdit.Change planned : plan) {
                Change change = new Change();
                change.right = planned.right();
                change.before = planned.before();
                change.after = planned.after();
                changes.add(change);
            }
            return changes;
        }
    }

    /** A role or an object that is not in the project; the caller adds where it may be instead. */
    static final class Missing extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        final String fqn;

        Missing(String message, String fqn) {
            super(message);
            this.fqn = fqn;
        }
    }

    public Result read(String projectName, String roleFqn, String objectFqn) {
        Result result = new Result();
        result.project = projectName;
        result.role = roleFqn;
        result.object = blank(objectFqn) ? null : objectFqn;
        try {
            IBmModel model = model(projectName);
            IRightInfosService infos = rightInfos();
            model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.roleRights.read") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    Role role = role(tx, roleFqn);
                    RoleDescription rights = description(role);
                    result.adoptedRole = adopted(role);
                    result.flags = flags(rights);
                    if (result.object == null) {
                        result.objects = objects(rights);
                        return null;
                    }
                    EObject object = object(tx, objectFqn);
                    result.object = fqn(object);
                    result.rights = states(role, rights, object, allowed(infos, object));
                    return null;
                }
            });
            result.ok = true;
            result.message = result.object == null
                    ? result.objects.size() + " object(s) with rights in " + roleFqn
                    : result.rights.size() + " right(s) of " + result.object + " in " + roleFqn;
        } catch (RuntimeException failure) {
            result.ok = false;
            result.message = describe(projectName, failure);
        }
        return result;
    }

    public Result modify(String projectName, String roleFqn, String objectFqn, Map<String, ?> rights,
            boolean apply) {
        Result result = new Result();
        result.project = projectName;
        result.role = roleFqn;
        result.object = objectFqn;
        try {
            if (blank(objectFqn)) throw new IllegalArgumentException("object is required, such as Catalog.Products or Configuration");
            Map<String, Boolean> requested = RoleRightsEdit.requested(rights);
            IBmModel model = model(projectName);
            IRightInfosService infos = rightInfos();
            Map<String, Boolean> resolved = new LinkedHashMap<>();
            model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.roleRights.inspect") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    Role role = role(tx, roleFqn);
                    RoleDescription description = description(role);
                    EObject object = object(tx, objectFqn);
                    result.object = fqn(object);
                    result.adoptedRole = adopted(role);
                    List<Right> allowed = allowed(infos, object);
                    resolved.putAll(RoleRightsEdit.resolve(requested, names(allowed), result.object));
                    List<RightState> states = states(role, description, object, allowed);
                    Map<String, Boolean> current = new LinkedHashMap<>();
                    for (RightState state : states) current.put(state.name, state.value);
                    result.changes = Change.of(RoleRightsEdit.plan(resolved, current));
                    for (Change change : result.changes) {
                        for (RightState state : states) {
                            if (state.name.equals(change.right) && state.restricted) {
                                throw new IllegalArgumentException(
                                        RoleRightsEdit.restrictedRefusal(change.right, result.object, roleFqn));
                            }
                        }
                    }
                    result.rights = states;
                    return null;
                }
            });
            result.ok = true;
            result.plan = "Set " + result.changes.size() + " right(s) of " + result.object + " in " + roleFqn;
            if (!apply || result.changes.isEmpty()) {
                result.message = result.changes.isEmpty() ? "The rights already have the requested values"
                        : "dry-run: nothing written";
                return result;
            }
            String target = result.object;
            List<Change> changes = result.changes;
            List<String> exported = new ArrayList<>(List.of(roleFqn));
            model.execute(new AbstractBmTask<Object>("edt-bridge.roleRights.apply") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    Role role = role(tx, roleFqn);
                    EObject object = object(tx, target);
                    Map<String, Right> byName = new LinkedHashMap<>();
                    for (Right right : allowed(infos, object)) byName.put(right.getName(), right);
                    Map<Right, Boolean> values = new LinkedHashMap<>();
                    for (Change change : changes) values.put(byName.get(change.right), change.after);
                    RoleDescription description = writableDescription(role);
                    applyValues(role, description, object, target, values, roleFqn, sorter());
                    // The rights are a top object of their own (Rights.rights): exporting the role
                    // rewrites the role file and leaves the rights file as it was.
                    if (description instanceof IBmObject bm && bm.bmIsTop() && bm.bmGetFqn() != null) {
                        exported.add(bm.bmGetFqn());
                    }
                    return null;
                }
            });
            result.applied = true;
            export(model, exported, result);
            result.message = "Updated " + changes.size() + " right(s) of " + target + " in " + roleFqn;
        } catch (RuntimeException failure) {
            result.ok = false;
            result.message = (result.applied ? "The model changed, but export failed: " : "Nothing written: ")
                    + describe(projectName, failure);
        }
        return result;
    }

    /**
     * Writes the values the way the rights editor of EDT does: a value equal to the role's
     * default for the object removes the explicit entry, any other value creates or changes it.
     * A right with a data access restriction is refused before anything changes.
     */
    static void applyValues(Role role, RoleDescription description, EObject object, String objectFqn,
            Map<Right, Boolean> values, String roleFqn, IModelObjectCollectionRuntimeOrderSorter sorter) {
        ObjectRights entry = RightsModelUtil.getOrCreateObjectRights(object, description);
        for (Right right : values.keySet()) {
            ObjectRight existing = RightsModelUtil.filterObjectRightByRight(right, entry.getRights());
            if (existing != null && !existing.getRestrictionsByCondition().isEmpty()) {
                throw new IllegalArgumentException(RoleRightsEdit.restrictedRefusal(right.getName(), objectFqn, roleFqn));
            }
        }
        RightValue defaultValue = RightsModelUtil.getDefaultRightValue(object, role);
        for (Map.Entry<Right, Boolean> value : values.entrySet()) {
            RightsModelUtil.changeObjectRight(RightsModelUtil.getRightValue(value.getValue()), defaultValue, entry,
                    value.getKey());
        }
        RightsModelUtil.removeEmptyObjectRights(description, entry);
        if (sorter != null) {
            sorter.sort(description.getRights());
            sorter.sort(entry.getRights());
        }
    }

    /** The state of every allowed right, in the order EDT shows them. */
    static List<RightState> states(Role role, RoleDescription description, EObject object, List<Right> allowed) {
        ObjectRights entry = description == null ? null
                : RightsModelUtil.filterObjectRightsByEObjectFastly(object, description);
        Boolean byDefault = value(RightsModelUtil.getDefaultRightValue(object, role));
        List<RightState> states = new ArrayList<>();
        for (Right right : allowed) {
            RightState state = new RightState();
            state.name = right.getName();
            state.nameRu = right.getNameRu();
            ObjectRight explicit = entry == null ? null : RightsModelUtil.filterObjectRightByRight(right, entry.getRights());
            state.explicit = explicit != null;
            state.value = explicit != null ? value(explicit.getValue()) : byDefault;
            if (explicit != null && !explicit.getRestrictionsByCondition().isEmpty()) {
                state.restricted = true;
                state.conditions = new ArrayList<>();
                for (Rls rls : explicit.getRestrictionsByCondition()) state.conditions.add(rls.getCondition());
            }
            states.add(state);
        }
        return states;
    }

    /** The top objects of the role's entries, with their own and their child objects' entries counted. */
    static List<ObjectEntry> objects(RoleDescription description) {
        Map<String, ObjectEntry> entries = new LinkedHashMap<>();
        if (description == null) return new ArrayList<>();
        for (ObjectRights rights : description.getRights()) {
            EObject object = rights.getObject();
            EObject top = top(object);
            String name = top == null ? String.valueOf(object) : fqn(top);
            ObjectEntry entry = entries.computeIfAbsent(name, key -> {
                ObjectEntry created = new ObjectEntry();
                created.object = key;
                return created;
            });
            if (top == object) entry.rights += rights.getRights().size();
            else entry.childObjects++;
        }
        return new ArrayList<>(entries.values());
    }

    static Boolean value(RightValue value) {
        if (value == RightValue.SET) return Boolean.TRUE;
        if (value == RightValue.UNSET) return Boolean.FALSE;
        return null;
    }

    static List<RoleRightsEdit.RightName> names(List<Right> rights) {
        List<RoleRightsEdit.RightName> names = new ArrayList<>();
        for (Right right : rights) names.add(new RoleRightsEdit.RightName(right.getName(), right.getNameRu()));
        return names;
    }

    static Map<String, Boolean> flags(RoleDescription description) {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        if (description == null) return flags;
        flags.put("setForNewObjects", description.isSetForNewObjects());
        flags.put("setForAttributesByDefault", description.isSetForAttributesByDefault());
        flags.put("independentRightsOfChildObjects", description.isIndependentRightsOfChildObjects());
        return flags;
    }

    /** The rights the object has, in the order EDT lists them. */
    static List<Right> allowed(IRightInfosService infos, EObject object) {
        // getRights answers every right of the platform; the rights of one kind of object come by its class.
        EClass eClass = RightsModelUtil.getEClass(object);
        java.util.Set<Right> rights = infos.getEClassRights(object, eClass);
        Map<Integer, Right> order = infos.getEClassRightsOrder(object, eClass);
        return ordered(rights, order, fqn(object));
    }

    /** The rights in the order EDT gives, then any the order leaves out. */
    static List<Right> ordered(java.util.Set<Right> rights, Map<Integer, Right> order, String object) {
        LinkedHashSet<Right> ordered = new LinkedHashSet<>();
        if (rights == null || rights.isEmpty()) throw new IllegalArgumentException(object + " has no rights a role can set");
        if (order != null) {
            for (Right right : new TreeMap<>(order).values()) if (rights.contains(right)) ordered.add(right);
        }
        ordered.addAll(rights);
        ordered.remove(null);
        return new ArrayList<>(ordered);
    }

    static Role role(IBmTransaction tx, String fqn) {
        String[] parts = fqn == null ? new String[0] : fqn.split("\\.", -1);
        if (parts.length != 2 || !"Role".equals(parts[0]) || parts[1].isBlank()) {
            throw new IllegalArgumentException("role must be a role FQN such as Role.Manager, got: " + fqn);
        }
        EObject object = tx.getTopObjectByFqn(fqn);
        if (object instanceof Role role) return role;
        throw new Missing("Role not found: " + fqn, fqn);
    }

    static EObject object(IBmTransaction tx, String fqn) {
        if (blank(fqn)) throw new IllegalArgumentException("object is required, such as Catalog.Products or Configuration");
        String[] parts = fqn.split("\\.", -1);
        if ("Configuration".equals(parts[0]) && parts.length <= 2) {
            EObject configuration = tx.getTopObjectByFqn("Configuration");
            if (configuration instanceof MdObject md && (parts.length == 1 || parts[1].equals(md.getName()))) {
                return configuration;
            }
            throw new Missing("Configuration not found: " + fqn, fqn);
        }
        if (parts.length != 2 || parts[1].isBlank()) {
            throw new IllegalArgumentException("object must be a top object FQN such as Catalog.Products, or "
                    + "Configuration; rights of attributes and tabular sections are not supported, got: " + fqn);
        }
        EObject object = tx.getTopObjectByFqn(fqn);
        if (object == null) throw new Missing("Object not found: " + fqn, fqn);
        return object;
    }

    static RoleDescription description(Role role) {
        return role.getRights() instanceof RoleDescription description ? description : null;
    }

    private static RoleDescription writableDescription(Role role) {
        RoleDescription description = description(role);
        if (description != null) return description;
        description = RightsFactory.eINSTANCE.createRoleDescription();
        role.setRights(description);
        return description;
    }

    static boolean adopted(MdObject object) {
        return "Adopted".equalsIgnoreCase(String.valueOf(object.getObjectBelonging()));
    }

    static EObject top(EObject object) {
        EObject current = object;
        while (current != null) {
            if (current instanceof IBmObject bm && bm.bmIsTop()) return current;
            current = current.eContainer();
        }
        return null;
    }

    static String fqn(EObject object) {
        if (object instanceof IBmObject bm) {
            try {
                String fqn = bm.bmIsTop() ? bm.bmGetFqn() : null;
                if (fqn != null) return fqn;
            } catch (RuntimeException detached) {
                // An object outside a BM engine has no FQN of its own; its class and name do.
            }
        }
        if (object instanceof MdObject md) return object.eClass().getName() + "." + md.getName();
        return String.valueOf(object);
    }

    private static IRightInfosService rightInfos() {
        IRightInfosService service = null;
        try {
            service = ServiceAccess.get(IRightInfosService.class);
        } catch (RuntimeException absent) {
            // Not exported as an OSGi service in every EDT; the plugin's own injector has it.
        }
        if (service == null && RightsPlugin.getDefault() != null) {
            service = RightsPlugin.getDefault().getInjector().getInstance(IRightInfosService.class);
        }
        if (service == null) throw new IllegalStateException("EDT rights service is unavailable");
        return service;
    }

    private static IModelObjectCollectionRuntimeOrderSorter sorter() {
        try {
            return ServiceAccess.get(IModelObjectCollectionRuntimeOrderSorter.class);
        } catch (RuntimeException absent) {
            return null;
        }
    }

    private static IBmModel model(String projectName) {
        if (blank(projectName)) throw new IllegalArgumentException("projectName is required");
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists() || !project.isOpen()) throw new IllegalArgumentException("Project not found or closed: " + projectName);
        IBmModelManager manager = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = manager == null ? null : manager.getModel(project);
        if (model == null) throw new IllegalArgumentException("No BM model for project: " + projectName);
        return model;
    }

    private static void export(IBmModel model, List<String> fqns, Result result) {
        IBmModelManager manager = ServiceAccess.get(IBmModelManager.class);
        var project = manager == null ? null : manager.getDtProject(model);
        boolean serialized = project != null;
        for (String fqn : fqns) serialized &= project != null && manager.forceExport(project, fqn);
        result.serialized = serialized;
        if (!result.serialized) result.warning = "The model changed but serialization of " + fqns
                + " was not confirmed; inspect the working tree";
    }

    /** The failure, and for an object missing from an extension, whether the base configuration has it. */
    private static String describe(String projectName, RuntimeException failure) {
        if (!(failure instanceof Missing missing)) return GatewaySupport.describeCause(failure);
        String message = missing.getMessage();
        String base = baseProjectWith(projectName, missing.fqn);
        return base == null ? message : message + " in " + projectName + "; it belongs to the base configuration "
                + base + " - adopt it into " + projectName + " with edt_adopt_object first";
    }

    private static String baseProjectWith(String projectName, String fqn) {
        try {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            IV8ProjectManager projects = ServiceAccess.get(IV8ProjectManager.class);
            IV8Project v8 = projects == null ? null : projects.getProject(project);
            if (!(v8 instanceof IExtensionProject extension) || extension.getParentProject() == null) return null;
            IProject base = extension.getParentProject();
            IBmModel baseModel = ServiceAccess.get(IBmModelManager.class).getModel(base);
            if (baseModel == null) return null;
            Boolean found = baseModel.executeReadonlyTask(new AbstractBmTask<Boolean>("edt-bridge.roleRights.base") {
                @Override
                public Boolean execute(IBmTransaction tx, IProgressMonitor monitor) {
                    return tx.getTopObjectByFqn(fqn) != null;
                }
            });
            return Boolean.TRUE.equals(found) ? base.getName() : null;
        } catch (RuntimeException unknown) {
            return null;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
