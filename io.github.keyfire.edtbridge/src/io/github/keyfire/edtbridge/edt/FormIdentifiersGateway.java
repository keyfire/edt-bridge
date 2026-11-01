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

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.form.model.AbstractFormAttribute;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.service.FormIdentifierService;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.wiring.ServiceAccess;

/** Audit and targeted repair of the visual-item identifier namespace. */
public final class FormIdentifiersGateway {
    public static final class Occurrence {
        public String path;
        public String name;
        public String kind;
        public int id;
        public boolean structural;
    }
    public static final class Conflict {
        public int id;
        public List<Occurrence> occurrences = new ArrayList<>();
    }
    public static final class Audit {
        public String namespace = "item";
        public String sourceHash;
        public int maxItemId;
        public int attributeCount;
        public int commandCount;
        public List<Occurrence> items = new ArrayList<>();
        public List<Conflict> conflicts = new ArrayList<>();
        public List<NumericReference> numericReferences = new ArrayList<>();
    }
    public static final class NumericReference {
        public String path;
        public String feature;
        public String value;
        public Integer itemId;
    }
    public static final class Change {
        public String path;
        public String name;
        public int oldId;
        public int newId;
    }
    public static final class RepairPlan {
        public String sourceHash;
        public List<Change> changes = new ArrayList<>();
        public List<String> unchangedPaths = new ArrayList<>();
    }
    public static final class Result {
        public boolean ok;
        public boolean applied;
        public boolean exportAccepted;
        public String projectName;
        public String formFqn;
        public String message;
        public String warning;
        public Audit audit;
        public RepairPlan plan;
        public Audit after;
    }

    /** Scan only containment: references to commands, attributes and other forms are not item IDs. */
    static Audit scan(Form form) {
        Audit audit = new Audit();
        Map<Integer, List<Occurrence>> byId = new LinkedHashMap<>();
        List<EObject> objects = contents(form);
        for (EObject object : objects) {
            if (object instanceof AbstractFormAttribute) audit.attributeCount++;
            if (object instanceof FormCommand) audit.commandCount++;
            if (object instanceof FormItem item) {
                Occurrence occurrence = new Occurrence();
                occurrence.path = EcoreUtil.getRelativeURIFragmentPath(form, item);
                occurrence.name = item.getName(); occurrence.kind = item.eClass().getName();
                occurrence.id = item.getId(); occurrence.structural = !FormItemEdits.isListMember(item);
                audit.items.add(occurrence);
                byId.computeIfAbsent(occurrence.id, ignored -> new ArrayList<>()).add(occurrence);
                audit.maxItemId = Math.max(audit.maxItemId, occurrence.id);
            }
            collectNumericReferences(form, object, audit.numericReferences);
        }
        byId.forEach((id, occurrences) -> {
            if (occurrences.size() > 1) {
                Conflict conflict = new Conflict(); conflict.id = id; conflict.occurrences.addAll(occurrences);
                audit.conflicts.add(conflict);
            }
        });
        audit.sourceHash = hash(form, objects);
        return audit;
    }

    /** Compute fresh IDs without invoking the allocator, which mutates BM properties even on a read. */
    static RepairPlan plan(Form form, List<String> paths, String expectedHash) {
        Audit audit = scan(form);
        if (expectedHash != null && !audit.sourceHash.equals(expectedHash)) {
            throw new IllegalArgumentException("The form changed since the preview; scan it again and use the new sourceHash");
        }
        RepairPlan plan = new RepairPlan(); plan.sourceHash = audit.sourceHash;
        Map<String, Occurrence> byPath = new LinkedHashMap<>();
        audit.items.forEach(item -> byPath.put(item.path, item));
        Set<String> selected = new LinkedHashSet<>();
        for (String path : paths == null ? List.<String>of() : paths) {
            if (path == null || path.isBlank() || !byPath.containsKey(path)) {
                throw new IllegalArgumentException("Not a contained form-item path: " + path);
            }
            if (!selected.add(path)) throw new IllegalArgumentException("Repeated item path: " + path);
        }
        Map<Integer, Conflict> conflicts = new LinkedHashMap<>();
        audit.conflicts.forEach(conflict -> conflicts.put(conflict.id, conflict));
        Set<Integer> changedIds = new LinkedHashSet<>();
        long next = (long) audit.maxItemId + 1;
        for (Occurrence item : audit.items) {
            if (!selected.contains(item.path)) continue;
            Conflict conflict = conflicts.get(item.id);
            if (conflict == null) { plan.unchangedPaths.add(item.path); continue; }
            if (item.id <= 0 || EcoreUtil.getEObject(form, item.path) == form.getAutoCommandBar()) {
                throw new IllegalArgumentException("Reserved or non-positive item IDs cannot be repaired: " + item.path);
            }
            if (conflict.occurrences.stream().allMatch(occurrence -> selected.contains(occurrence.path))) {
                throw new IllegalArgumentException("Leave at least one occurrence of ID " + item.id + " unselected");
            }
            if (next > Integer.MAX_VALUE) throw new IllegalArgumentException("No larger form-item ID is available");
            Change change = new Change(); change.path = item.path; change.name = item.name;
            change.oldId = item.id; change.newId = (int) next++;
            plan.changes.add(change); changedIds.add(item.id);
        }
        if (!changedIds.isEmpty()) {
            for (NumericReference reference : audit.numericReferences) {
                if (reference.itemId == null || changedIds.contains(reference.itemId)) {
                    throw new IllegalArgumentException("Ambiguous numeric item reference at " + reference.path + "/"
                            + reference.feature + "; no IDs were changed");
                }
            }
        }
        return plan;
    }

    /** Call inside a writable BM task. A thrown exception lets the task roll back every change. */
    static RepairPlan repair(Form form, List<String> paths, String expectedHash) {
        if (expectedHash == null || expectedHash.isBlank()) {
            throw new IllegalArgumentException("expectedHash from a fresh preview is required for repair");
        }
        RepairPlan plan = plan(form, paths, expectedHash);
        if (plan.changes.isEmpty()) return plan;
        FormIdentifierService ids = FormIdentifierService.INSTANCE;
        ids.clearCaches(form);
        for (Change change : plan.changes) {
            int allocated = ids.getNextItemId(form);
            if (allocated != change.newId) throw new IllegalStateException("Native item allocation changed; repair was rolled back");
            FormItem item = (FormItem) EcoreUtil.getEObject(form, change.path);
            item.setId(allocated);
        }
        return plan;
    }

    public Result identifiers(String projectName, String formFqn, List<String> itemPaths,
            String expectedHash, boolean apply) {
        Result result = new Result(); result.projectName = projectName; result.formFqn = formFqn;
        try {
            if (projectName == null || projectName.isBlank() || formFqn == null || formFqn.isBlank()) {
                throw new IllegalArgumentException("projectName and formFqn are required");
            }
            List<String> paths = itemPaths == null ? List.of() : List.copyOf(itemPaths);
            if (apply && paths.isEmpty()) throw new IllegalArgumentException("itemPaths is required for repair");
            if (apply && (expectedHash == null || expectedHash.isBlank())) {
                throw new IllegalArgumentException("expectedHash from the preview is required for apply");
            }
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!project.exists() || !project.isOpen()) throw new IllegalArgumentException("Project not found or closed: " + projectName);
            IBmModelManager manager = ServiceAccess.get(IBmModelManager.class);
            IBmModel model = manager == null ? null : manager.getModel(project);
            if (model == null) throw new IllegalArgumentException("No BM model for project: " + projectName);
            model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.formIdentifiers.inspect") {
                @Override public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    Form form = requireForm(tx, formFqn);
                    result.audit = scan(form);
                    if (!paths.isEmpty()) result.plan = plan(form, paths, expectedHash);
                    return null;
                }
            });
            result.ok = true;
            if (!apply || result.plan.changes.isEmpty()) {
                result.message = paths.isEmpty() ? "Form-item ID audit completed" : result.plan.changes.isEmpty()
                        ? "Selected item IDs are already unique; nothing written" : "dry-run: no IDs or allocator caches changed";
                return result;
            }
            String editorProblem = editorProblem(model, formFqn);
            if (editorProblem != null) throw new IllegalArgumentException(editorProblem);
            model.execute(new AbstractBmTask<Object>("edt-bridge.formIdentifiers.repair") {
                @Override public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    result.plan = repair(requireForm(tx, formFqn), paths, expectedHash);
                    return null;
                }
            });
            result.applied = true;
            String topFqn = FormWriteGateway.formTopFqn(model, formFqn);
            var dtProject = manager.getDtProject(model);
            result.exportAccepted = dtProject != null && topFqn != null && manager.forceExport(dtProject, topFqn);
            model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.formIdentifiers.readback") {
                @Override public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    result.after = scan(requireForm(tx, formFqn));
                    for (Change change : result.plan.changes) {
                        FormItem item = (FormItem) EcoreUtil.getEObject(requireForm(tx, formFqn), change.path);
                        if (item == null || item.getId() != change.newId) throw new IllegalStateException("Repaired ID was not found during readback");
                    }
                    return null;
                }
            });
            if (!result.exportAccepted) result.warning = "The model changed, but the form export request was not accepted";
            result.message = "Repaired " + result.plan.changes.size() + " selected item IDs; native allocator caches were reset";
        } catch (RuntimeException failure) {
            result.ok = false;
            result.message = (result.applied ? "The model changed, but export/readback failed: " : "Nothing written: ")
                    + GatewaySupport.describeCause(failure);
        }
        return result;
    }

    private static Form requireForm(IBmTransaction tx, String formFqn) {
        Form form = FormWriteGateway.resolveForm(tx, formFqn);
        if (form == null) throw new IllegalArgumentException("Form not found: " + formFqn);
        return form;
    }

    /** Open form editors retain a local model and allocator state; do not save or close them here. */
    static String editorProblem(IBmModel model, String formFqn) {
        if (!PlatformUI.isWorkbenchRunning()) return null;
        long targetId = model.executeReadonlyTask(new AbstractBmTask<Long>("edt-bridge.formIdentifiers.editorIdentity") {
            @Override public Long execute(IBmTransaction tx, IProgressMonitor monitor) {
                return requireForm(tx, formFqn).bmGetId();
            }
        });
        String[] problem = {null};
        PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
                for (IWorkbenchPage page : window.getPages()) {
                    for (IEditorReference reference : page.getEditorReferences()) {
                        try {
                            IEditorPart part = reference.getEditor(false);
                            Object candidate = part == null ? null : publicGetter(part, "getForm");
                            Form form = candidate instanceof EObject object ? FormWriteGateway.asFormContent(object) : null;
                            if (form == null) {
                                candidate = publicGetter(reference.getEditorInput(), "getModel");
                                form = candidate instanceof EObject object ? FormWriteGateway.asFormContent(object) : null;
                            }
                            if (form != null && sameObject(targetId, model.getEngine(), form.bmGetId(), form.bmGetEngine())) {
                                problem[0] = reference.isDirty() ? "Save and close the matching form editor before changing IDs"
                                        : "Close the matching form editor before changing IDs; its local allocator state may be stale";
                                return;
                            }
                        } catch (Exception failure) {
                            problem[0] = "Cannot inspect form editors safely: " + GatewaySupport.describeCause(failure);
                            return;
                        }
                    }
                }
            }
        });
        return problem[0];
    }

    static boolean sameObject(long targetId, Object targetEngine, long candidateId, Object candidateEngine) {
        return targetId == candidateId && targetEngine == candidateEngine;
    }
    private static Object publicGetter(Object owner, String name) throws ReflectiveOperationException {
        if (owner == null) return null;
        Method getter;
        try { getter = owner.getClass().getMethod(name); }
        catch (NoSuchMethodException absent) { return null; }
        return getter.invoke(owner);
    }
    private static List<EObject> contents(Form form) {
        List<EObject> objects = new ArrayList<>(); objects.add(form);
        form.eAllContents().forEachRemaining(objects::add);
        return objects;
    }
    private static void collectNumericReferences(Form form, EObject owner, List<NumericReference> out) {
        for (String name : List.of("associatedTableElementId", "associatedTableElementId8315")) {
            EStructuralFeature feature = owner.eClass().getEStructuralFeature(name);
            if (feature == null || !owner.eIsSet(feature)) continue;
            Object value = owner.eGet(feature, false);
            if (value == null) continue;
            NumericReference reference = new NumericReference();
            reference.path = EcoreUtil.getRelativeURIFragmentPath(form, owner); reference.feature = name;
            Object number = value instanceof NumberValue numeric ? numeric.getValue()
                    : value instanceof StringValue string ? string.getValue() : value;
            reference.value = number instanceof EObject object ? object.eClass().getName() : String.valueOf(number);
            try { reference.itemId = new BigDecimal(reference.value).intValueExact(); }
            catch (NumberFormatException | ArithmeticException unknown) { /* Unknown legacy values block repair. */ }
            out.add(reference);
        }
    }
    private static String hash(Form form, List<EObject> objects) {
        StringBuilder source = new StringBuilder();
        for (EObject object : objects) {
            token(source, EcoreUtil.getRelativeURIFragmentPath(form, object)); token(source, object.eClass().getName());
            for (EStructuralFeature feature : object.eClass().getEAllStructuralFeatures()) {
                if (feature.isDerived() || feature.isTransient() || feature.isVolatile() || !object.eIsSet(feature)) continue;
                token(source, feature.getName()); appendValue(source, form, object.eGet(feature, false));
            }
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void appendValue(StringBuilder source, Form form, Object value) {
        if (value instanceof List<?> values) {
            token(source, "list:" + values.size()); values.forEach(item -> appendValue(source, form, item));
        } else if (value instanceof EObject object) {
            EObject root = object;
            while (root.eContainer() != null && root != form) root = root.eContainer();
            token(source, root == form ? "local:" + EcoreUtil.getRelativeURIFragmentPath(form, object)
                    : "reference:" + EcoreUtil.getURI(object));
        } else token(source, value == null ? "null" : value.getClass().getName() + ":" + value);
    }
    private static void token(StringBuilder source, String value) { source.append(value.length()).append(':').append(value); }
}
