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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.TextChange;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.xtext.ui.refactoring.IRenameRefactoringProvider;
import org.eclipse.xtext.ui.refactoring.impl.AbstractRenameProcessor;
import org.eclipse.xtext.ui.refactoring.ui.IRenameElementContext;

import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.core.operations.IProjectOperationApi;
import com._1c.g5.v8.dt.core.operations.ProjectPipelineJob;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.wiring.ServiceAccess;

/** Native EDT method rename and checked text-preserving moves within a BSL module. */
public final class BslRefactorGateway {
    private final BslGateway bsl = new BslGateway();
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*");

    public static final class AffectedFile {
        public String path;
        public int changeCount;
        public boolean changeCountComplete = true;
        public Integer editCount = 0;
        public int nativeTextEditCount;
        public boolean editCountComplete = true;
        AffectedFile(String path) { this.path = path; }
    }
    public static final class Result {
        public boolean ok;
        public boolean applied;
        public boolean applyPending;
        public boolean rollbackAttempted;
        public boolean availableUndoApplied;
        public String rollbackWarning;
        public String mode;
        public String modulePath;
        public String methodName;
        public String newName;
        public boolean forced;
        public boolean valid;
        public int insertLine;
        public String previousMethod;
        public String nextMethod;
        public int affectedFileCount;
        public int changeCount;
        public Integer editCount = 0;
        public int nativeTextEditCount;
        public boolean editCountComplete = true;
        public boolean coverageComplete = true;
        public int unmappedItemCount;
        public int selectedItemCount;
        public List<String> unmappedItems = new ArrayList<>();
        public int unknownChangeCount;
        public List<AffectedFile> affectedFiles = new ArrayList<>();
        public List<String> problems = new ArrayList<>();
        public String preview;
        public String plan;
        public String message;
    }

    public Result renameMethod(String projectName, String fqn, String moduleType, String modulePath,
            String methodName, String newName, boolean apply, boolean force) {
        Result result = new Result();
        result.mode = "method";
        result.methodName = methodName;
        result.newName = newName;
        result.forced = force;
        Change change = null;
        boolean[] executionStarted = {false};
        try {
            if (newName == null || !IDENTIFIER.matcher(newName).matches()) {
                throw new IllegalArgumentException("newName must be a valid 1C identifier");
            }
            IFile file = moduleFile(projectName, fqn, moduleType, modulePath);
            result.modulePath = file.getProjectRelativePath().toPortableString();
            String text = bsl.readText(file);
            BslGateway.BslContext context = parse(projectName, result.modulePath, text);
            Module module = BslGateway.firstModule(context.resource);
            var method = uniqueMethod(module, methodName);
            result.methodName = method.getName();
            if (method.getName().equals(newName)) throw new IllegalArgumentException("newName equals current name");
            for (var other : module.allMethods()) {
                if (other != method && other.getName().equalsIgnoreCase(newName)) {
                    throw new IllegalArgumentException("method name already exists: " + newName);
                }
            }
            var bundle = Platform.getBundle("com._1c.g5.v8.dt.bsl.ui");
            Class<?> contextClass = bundle.loadClass("com._1c.g5.v8.dt.bsl.ui.refactoring.BslRenameElementContext");
            IRenameElementContext renameContext = (IRenameElementContext) contextClass.getConstructor(
                    URI.class, org.eclipse.emf.ecore.EClass.class, org.eclipse.ui.IEditorPart.class,
                    org.eclipse.jface.viewers.ISelection.class, URI.class, EObject.class, EObject.class)
                    .newInstance(EcoreUtil.getURI(method), method.eClass(), null, null,
                            context.resource.getURI(), method, method);
            var provider = context.injector.getInstance(IRenameRefactoringProvider.class);
            var refactoring = provider.getRenameRefactoring(renameContext);
            if (refactoring == null || !(refactoring.getProcessor() instanceof AbstractRenameProcessor)) {
                throw new IllegalStateException("EDT BSL rename processor unavailable");
            }
            var processor = (AbstractRenameProcessor) refactoring.getProcessor();
            processor.setNewName(newName);
            RefactoringStatus status = refactoring.checkInitialConditions(new NullProgressMonitor());
            status.merge(processor.validateNewName(newName));
            if (!status.hasError()) status.merge(refactoring.checkFinalConditions(new NullProgressMonitor()));
            for (var entry : status.getEntries()) result.problems.add(entry.getMessage());
            if (status.hasError()) {
                result.message = "EDT refactoring reported errors; nothing changed";
                return result;
            }
            change = refactoring.createChange(new NullProgressMonitor());
            if (change == null) throw new IllegalStateException("EDT returned no rename change");
            change = FormCommandRenameChanges.adapt(change, result.methodName, newName);
            change.initializeValidationData(new NullProgressMonitor());
            summarize(change, result);
            result.valid = true;
            result.ok = result.changeCount > 0 && result.unknownChangeCount == 0;
            result.plan = "Rename method " + result.methodName + " to " + newName + ": "
                    + result.affectedFileCount + " file(s), " + result.nativeTextEditCount + " exposed native text edit(s)"
                    + (result.editCountComplete ? "" : "; model operations have no text-edit count");
            if (!result.ok) {
                result.message = "cannot enumerate all affected files; apply refused";
                return result;
            }
            if (!apply) {
                result.applyPending = true;
                result.message = "dry-run: EDT refactoring planned; nothing changed";
                return result;
            }
            if (!force) {
                result.message = "apply refused: method rename requires force=true";
                return result;
            }
            final Change planned = change;
            exclusive(file.getProject(), () -> {
                try {
                    requireSaved(file);
                    if (!text.equals(bsl.readText(file))) throw new IllegalStateException("module changed after planning; retry");
                    for (AffectedFile affected : result.affectedFiles) {
                        IFile affectedFile = ResourcesPlugin.getWorkspace().getRoot().getFile(
                                new org.eclipse.core.runtime.Path(affected.path));
                        requireSaved(affectedFile);
                    }
                    RefactoringStatus validation = planned.isValid(new NullProgressMonitor());
                    if (validation.hasError()) throw new IllegalStateException(validation.toString());
                    executionStarted[0] = true;
                    performChangeWithRollback(planned, result);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            result.applied = true;
            result.message = "method renamed through EDT refactoring";
        } catch (Exception exception) {
            result.ok = false;
            result.message = (executionStarted[0] ? "rename failed after execution began; verify affected files: "
                    : "rename refused: ") + cause(exception);
        } finally {
            if (change != null) change.dispose();
        }
        return result;
    }

    public Result moveMethod(String projectName, String fqn, String moduleType, String modulePath,
            String methodName, String region, boolean serverBlock, String beforeMethod,
            String afterMethod, boolean apply, boolean force) {
        Result result = new Result();
        result.mode = "move";
        result.methodName = methodName;
        result.forced = force;
        try {
            if (beforeMethod != null && afterMethod != null) {
                throw new IllegalArgumentException("beforeMethod and afterMethod are mutually exclusive");
            }
            if (methodName != null && (methodName.equalsIgnoreCase(beforeMethod)
                    || methodName.equalsIgnoreCase(afterMethod))) {
                throw new IllegalArgumentException("a method cannot be its own placement anchor");
            }
            IFile file = moduleFile(projectName, fqn, moduleType, modulePath);
            result.modulePath = file.getProjectRelativePath().toPortableString();
            String text = bsl.readText(file);
            Module original = BslGateway.firstModule(parse(projectName, result.modulePath, text).resource);
            var method = uniqueMethod(original, methodName);
            result.methodName = method.getName();
            int[] range = BslGateway.methodRange(method, text);
            String methodText = text.substring(range[0], range[1]);
            String remaining = text.substring(0, range[0]) + text.substring(range[1]);
            Module without = BslGateway.firstModule(parse(projectName, result.modulePath, remaining).resource);
            var insertion = BslGateway.insertion(without, remaining, region, serverBlock, beforeMethod, afterMethod);
            String eol = text.contains("\r\n") ? "\r\n" : "\n";
            int position = insertion.point.offset();
            String block = BslGateway.insertionBlock(remaining, position, methodText, eol);
            String moved = remaining.substring(0, position) + block + remaining.substring(position);
            Module finalModule = BslGateway.firstModule(parse(projectName, result.modulePath, moved).resource);
            if (!methodNames(original).equals(methodNames(finalModule))) {
                throw new IllegalArgumentException("move would change the set of methods");
            }
            var finalMethod = uniqueMethod(finalModule, methodName);
            int[] finalRange = BslGateway.methodRange(finalMethod, moved);
            if (!methodText.equals(moved.substring(finalRange[0], finalRange[1]))) {
                throw new IllegalArgumentException("move would change method text, documentation or pragmas");
            }
            result.insertLine = lineAt(moved, finalRange[0]);
            result.previousMethod = insertion.point.previousMethod();
            result.nextMethod = insertion.point.nextMethod();
            result.preview = moved.substring(Math.max(0, position - 80), Math.min(moved.length(), position + block.length() + 80));
            if (result.preview.length() > 1600) result.preview = result.preview.substring(0, 1600) + " ...";
            AffectedFile affected = new AffectedFile(file.getFullPath().toPortableString());
            affected.changeCount = 1;
            affected.editCount = 2;
            result.affectedFiles.add(affected);
            result.affectedFileCount = 1;
            result.changeCount = 1;
            result.editCount = 2;
            result.nativeTextEditCount = 2;
            affected.nativeTextEditCount = 2;
            result.valid = true;
            result.ok = true;
            result.plan = "Move method " + methodName + " within " + result.modulePath + " to line " + result.insertLine;
            if (!apply) {
                result.applyPending = true;
                result.message = "dry-run: move validated; nothing changed";
                return result;
            }
            if (!force) {
                result.message = "apply refused: moving a method requires force=true";
                return result;
            }
            exclusive(file.getProject(), () -> {
                try {
                    requireSaved(file);
                    if (!text.equals(bsl.readText(file))) throw new IllegalStateException("module changed after planning; retry");
                    file.setContents(new ByteArrayInputStream(moved.getBytes(StandardCharsets.UTF_8)),
                            true, true, new NullProgressMonitor());
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            result.applied = true;
            result.message = "method moved; exact method text preserved";
        } catch (Exception exception) {
            result.ok = false;
            result.message = "move refused or failed: " + cause(exception);
        }
        return result;
    }

    private IFile moduleFile(String projectName, String fqn, String moduleType, String modulePath) throws Exception {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists() || !project.isOpen()) throw new IllegalArgumentException("project not found or closed: " + projectName);
        String path = modulePath;
        if (path == null || path.isBlank()) {
            var shim = new BslGateway.ModuleTextResult();
            path = bsl.resolveModulePath(project, fqn, moduleType, shim);
            if (path == null) throw new IllegalArgumentException(shim.message);
        }
        if (modulePath != null && !modulePath.isBlank() && fqn != null && !fqn.isBlank()) {
            var owner = new BslGateway.ModuleTextResult();
            String ownerPath = bsl.resolveModulePath(project, fqn, moduleType, owner);
            if (ownerPath == null || !ownerPath.equals(path.replace('\\', '/'))) {
                throw new IllegalArgumentException("modulePath and target owner identify different or unresolved modules");
            }
        }
        IFile file = project.getFile(path);
        if (!file.exists()) throw new IllegalArgumentException("module file not found: " + path);
        requireSaved(file);
        return file;
    }

    private BslGateway.BslContext parse(String project, String path, String text) {
        var context = bsl.loadBslText(project, path, text);
        if (context.error != null || context.resource == null) throw new IllegalArgumentException("cannot parse module: " + context.error);
        if (!context.resource.getErrors().isEmpty()) throw new IllegalArgumentException("module has parse errors: " + BslGateway.firstError(context.resource));
        if (BslGateway.firstModule(context.resource) == null) throw new IllegalArgumentException("no BSL module parsed");
        return context;
    }

    static com._1c.g5.v8.dt.bsl.model.Method uniqueMethod(Module module, String name) {
        List<com._1c.g5.v8.dt.bsl.model.Method> matches = new ArrayList<>();
        for (var method : module.allMethods()) if (name != null && name.equalsIgnoreCase(method.getName())) matches.add(method);
        if (matches.size() != 1) throw new IllegalArgumentException((matches.isEmpty()
                ? "method not found: " : "ambiguous method: ") + name);
        return matches.get(0);
    }

    private static Map<String, Integer> methodNames(Module module) {
        Map<String, Integer> names = new LinkedHashMap<>();
        for (var method : module.allMethods()) names.merge(method.getName().toLowerCase(Locale.ROOT), 1, Integer::sum);
        return names;
    }

    static void performChangeWithRollback(Change change, Result result) throws org.eclipse.core.runtime.CoreException {
        try {
            Change undo = change.perform(new NullProgressMonitor());
            if (undo != null) undo.dispose();
        } catch (org.eclipse.core.runtime.CoreException | RuntimeException failed) {
            Change undo = change instanceof CompositeChange composite ? composite.getUndoUntilException() : null;
            if (undo != null) {
                result.rollbackAttempted = true;
                try {
                    Change redo = undo.perform(new NullProgressMonitor());
                    result.availableUndoApplied = true;
                    if (redo != null) redo.dispose();
                    result.rollbackWarning = "Available undo changes were applied. Native changes without undo may remain; verify affected files.";
                } catch (org.eclipse.core.runtime.CoreException | RuntimeException rollback) {
                    failed.addSuppressed(rollback);
                    result.rollbackWarning = "Available undo failed: " + cause(rollback) + "; verify affected files.";
                } finally {
                    try { undo.dispose(); }
                    catch (RuntimeException cleanup) { failed.addSuppressed(cleanup); }
                }
            } else {
                result.rollbackWarning = "The failing native change exposed no composite undo; verify affected files.";
            }
            throw failed;
        }
    }

    static void summarize(Change change, Result result) {
        summarizeAll(List.of(change), result);
    }

    static void summarizeAll(Iterable<Change> changes, Result result) {
        Map<String, AffectedFile> files = new LinkedHashMap<>();
        java.util.Set<Change> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Change change : changes) summarize(change, result, files, seen);
        result.affectedFiles.addAll(files.values());
        result.affectedFileCount = result.affectedFiles.size();
        result.coverageComplete &= result.unknownChangeCount == 0 && result.unmappedItemCount == 0;
        result.editCount = result.editCountComplete ? result.nativeTextEditCount : null;
    }

    /** Metadata items expose native LTK changes only for part of the cascade. Keep gaps explicit. */
    static Result previewItems(Iterable<com._1c.g5.v8.dt.refactoring.core.IRefactoringItem> items) {
        Result preview = new Result();
        List<Change> nativeChanges = new ArrayList<>();
        for (var item : items) {
            try {
                if (!item.isChecked()) continue;
            } catch (RuntimeException unavailable) {
                preview.coverageComplete = false;
                preview.editCountComplete = false;
                preview.unmappedItemCount++;
                if (preview.unmappedItems.size() < 80) preview.unmappedItems.add("Item selection unavailable");
                continue;
            }
            preview.selectedItemCount++;
            try {
                Change nativeChange = item instanceof com._1c.g5.v8.dt.refactoring.core.INativeChangeRefactoringItem nativeItem
                        ? nativeItem.getNativeChange() : null;
                if (nativeChange != null) {
                    nativeChanges.add(nativeChange);
                    continue;
                }
            } catch (RuntimeException unavailable) {
                if (preview.unmappedItems.size() < 80) preview.unmappedItems.add(
                        "Native preview unavailable: " + unavailable.getClass().getSimpleName());
            }
            preview.unmappedItemCount++;
            preview.editCountComplete = false;
            if (preview.unmappedItems.size() < 80) {
                try { preview.unmappedItems.add(item.getName()); }
                catch (RuntimeException unavailable) { preview.unmappedItems.add("Item name unavailable"); }
            }
        }
        summarizeAll(nativeChanges, preview);
        return preview;
    }

    static void includeTargetPath(Result preview, String path) {
        if (path == null) {
            preview.coverageComplete = false;
            return;
        }
        for (AffectedFile file : preview.affectedFiles) if (path.equals(file.path)) return;
        AffectedFile target = new AffectedFile(path);
        target.editCount = null;
        target.editCountComplete = false;
        target.changeCountComplete = false;
        preview.affectedFiles.add(target);
        preview.coverageComplete = false;
        preview.affectedFileCount = preview.affectedFiles.size();
        preview.editCount = null;
        preview.editCountComplete = false;
    }

    private static void summarize(Change change, Result result, Map<String, AffectedFile> files,
            java.util.Set<Change> seen) {
        try {
            if (!change.isEnabled() || !seen.add(change)) return;
            if (change instanceof CompositeChange) {
                for (Change child : ((CompositeChange) change).getChildren()) summarize(child, result, files, seen);
                return;
            }
            result.changeCount++;
            TextEdit edit = nativeEdit(change);
            boolean editsKnown = edit != null;
            int edits = editsKnown ? countEdits(edit) : 0;
            result.nativeTextEditCount += edits;
            result.editCountComplete &= editsKnown;
            java.util.Set<String> paths = new java.util.LinkedHashSet<>();
            String path = resourcePath(change.getModifiedElement());
            if (path != null) paths.add(path);
            if (change instanceof TextFileChange) paths.add(((TextFileChange) change).getFile().getFullPath().toPortableString());
            if (paths.isEmpty() && change.getAffectedObjects() != null) {
                for (Object affected : change.getAffectedObjects()) {
                    String affectedPath = resourcePath(affected);
                    if (affectedPath != null) paths.add(affectedPath);
                }
            }
            if (paths.isEmpty()) {
                result.unknownChangeCount++;
                return;
            }
            for (String affectedPath : paths) {
                AffectedFile file = files.computeIfAbsent(affectedPath, AffectedFile::new);
                file.changeCount++;
                file.nativeTextEditCount += edits;
                file.editCountComplete &= editsKnown;
                file.editCount = file.editCountComplete ? file.nativeTextEditCount : null;
            }
        } catch (RuntimeException unavailable) {
            result.unknownChangeCount++;
            result.coverageComplete = false;
            result.editCountComplete = false;
            if (result.unmappedItems.size() < 80) result.unmappedItems.add(
                    "Native change preview unavailable: " + unavailable.getClass().getSimpleName());
        }
    }

    /** Handly source-file and EDT BM changes extend TextEditBasedChange, not TextChange. */
    private static TextEdit nativeEdit(Change change) {
        if (change instanceof TextChange text) return text.getEdit();
        if (change instanceof org.eclipse.ltk.core.refactoring.TextEditBasedChange) {
            try {
                Object edit = change.getClass().getMethod("getEdit").invoke(change);
                return edit instanceof TextEdit ? (TextEdit) edit : null;
            } catch (ReflectiveOperationException unavailable) {
                return null;
            }
        }
        return null;
    }

    static int countEdits(TextEdit edit) {
        if (edit == null) return 0;
        if (!edit.hasChildren()) return edit instanceof org.eclipse.text.edits.MultiTextEdit ? 0 : 1;
        int count = 0;
        for (TextEdit child : edit.getChildren()) count += countEdits(child);
        return count;
    }

    static String resourcePath(Object object) {
        if (object instanceof IResource) return ((IResource) object).getFullPath().toPortableString();
        URI uri = object instanceof URI ? (URI) object : object instanceof EObject
                ? EcoreUtil.getURI((EObject) object) : null;
        if (uri != null && uri.isPlatformResource()) return uri.trimFragment().toPlatformString(true);
        if (object instanceof EObject || uri != null) {
            IResourceLookup lookup = FormWriteGateway.coreService(IResourceLookup.class);
            IFile file = lookup == null ? null : object instanceof EObject
                    ? lookup.getPlatformResource((EObject) object) : lookup.getPlatformResource(uri);
            if (file != null) return file.getFullPath().toPortableString();
        }
        return null;
    }

    private static void requireSaved(IFile file) {
        var manager = org.eclipse.core.filebuffers.FileBuffers.getTextFileBufferManager();
        var buffer = manager.getTextFileBuffer(file.getFullPath(), org.eclipse.core.filebuffers.LocationKind.IFILE);
        if (buffer != null && buffer.isDirty()) throw new IllegalArgumentException("save the module editor before refactoring");
    }

    private static void exclusive(IProject project, Runnable operation) {
        IProjectOperationApi operations = ServiceAccess.get(IProjectOperationApi.class);
        IBmModelManager manager = ServiceAccess.get(IBmModelManager.class);
        var model = manager == null ? null : manager.getModel(project);
        var dtProject = model == null ? null : manager.getDtProject(model);
        if (operations == null || dtProject == null) throw new IllegalStateException("exclusive EDT project operation unavailable");
        operations.performExclusiveOperation(operation, ProjectPipelineJob.BUILD, new NullProgressMonitor(), dtProject);
    }

    private static int lineAt(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    private static String cause(Throwable exception) {
        while (exception.getCause() != null && exception.getCause() != exception) exception = exception.getCause();
        return exception.getClass().getSimpleName() + (exception.getMessage() == null ? "" : ": " + exception.getMessage());
    }
}
