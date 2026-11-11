/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.edt;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.MultiPageEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.dt.core.operations.IProjectOperationApi;
import com._1c.g5.v8.dt.core.operations.ProjectPipelineJob;
import com._1c.g5.v8.dt.core.operations.model.IEditingContext;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.htmldocument.model.HtmlDocument;
import com._1c.g5.v8.dt.htmldocument.model.HtmlDocumentPage;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.ui.editor.IDtEditor;
import com._1c.g5.v8.dt.ui.editor.IDtGranularEditorEmbeddedEditorPage;
import com._1c.g5.wiring.ServiceAccess;
import io.github.keyfire.edtbridge.core.ExactTextReplacement;

/** Resolve one existing HTML locale through EDT and replace only its exact UTF-8 source. */
public final class HtmlTemplateGateway {
    /** How long a write waits for EDT to free the project before it answers busy. */
    static final long EXCLUSIVE_WAIT_MS = 15_000;
    /** How long a check waits for the UI thread. A modal dialog's event loop still runs it. */
    static final long UI_WAIT_MS = 30_000;

    /**
     * The language slot of a data composition schema: it has one content file and no locales, so the
     * schema calls pass this marker where an HTML call names a locale.
     */
    static final String SCHEMA = "schema";

    /** The language slot of a spreadsheet template: one Template.mxlx file, like the schema. */
    static final String SPREADSHEET = "spreadsheet";

    /** EDT is occupied by another operation; nothing was written and the call may be repeated. */
    static final class BusyException extends IllegalStateException {
        BusyException(String message) { super(message); }
    }

    record Target(String projectName, String templateFqn, String templateUuid, String templateType,
            String language, List<String> languages, Map<String, String> pagePaths, IFile file,
            IFile metadataFile, IFile documentFile) {
        Target {
            languages = List.copyOf(languages);
            pagePaths = Collections.unmodifiableMap(new LinkedHashMap<>(pagePaths));
        }
        String path() { return pagePaths.get(language); }
    }
    record Source(String text, long stamp, String charset) {}

    interface Access {
        Target resolve(String projectName, String fqn, String language) throws Exception;
        Source read(Target target) throws Exception;
        void requireSaved(Target target, String source) throws Exception;
        void requireWritable(Target target) throws Exception;
        void exclusive(Target target, Runnable operation) throws Exception;
        void write(Target target, byte[] bytes) throws Exception;
    }

    public static final class Result {
        public boolean ok, found, applied, verified, unchanged, applyPending, valid, force, writeAttempted, busy;
        public String projectName, templateFqn, templateUuid, templateType, language, path, charset;
        public String hashPolicy = "sha256-utf8-exact";
        public String text, oldText, newText, sourceHash, resultHash, message;
        public List<String> availableLanguages = new ArrayList<>();
    }

    private final Access access;
    public HtmlTemplateGateway() { this(new EdtAccess()); }
    HtmlTemplateGateway(Access access) { this.access = Objects.requireNonNull(access); }

    public Result read(String projectName, String fqn, String language) {
        Result result = new Result();
        try {
            requireArguments(projectName, fqn, language);
            Target target = access.resolve(projectName, fqn, language);
            describe(result, target);
            Source source = access.read(target);
            access.requireSaved(target, source.text());
            requireSameSource(source, access.read(target));
            result.charset = source.charset();
            result.sourceHash = ExactTextReplacement.hash(source.text());
            result.text = source.text();
            result.valid = true;
            result.ok = true;
            result.message = "exact saved " + kind(language) + " source; UTF-8 hash includes any BOM and original line endings";
        } catch (Exception exception) {
            result.message = kind(language) + " template read refused or failed: " + cause(exception);
        }
        return result;
    }

    public Result replace(String projectName, String fqn, String language, String text, String expectedHash,
            boolean apply, boolean force) {
        Result result = new Result();
        result.force = force;
        try {
            requireArguments(projectName, fqn, language);
            if (text == null) throw new IllegalArgumentException("text is required; an empty string is allowed");
            ExactTextReplacement.encode(text);
            if (apply && (expectedHash == null || expectedHash.isBlank())) {
                throw new IllegalArgumentException("apply requires expectedHash from a current read or dry-run");
            }
            if (apply && !force) throw new IllegalArgumentException("apply requires force=true");
            Target target = access.resolve(projectName, fqn, language);
            describe(result, target);
            Source source = access.read(target);
            access.requireSaved(target, source.text());
            requireSameSource(source, access.read(target));
            var plan = ExactTextReplacement.plan(source.text(), text, expectedHash);
            access.requireWritable(target);
            result.charset = source.charset();
            result.oldText = plan.oldText(); result.newText = plan.newText();
            result.sourceHash = plan.sourceHash(); result.resultHash = plan.resultHash();
            result.unchanged = plan.unchanged(); result.valid = true;
            if (!apply || plan.unchanged()) {
                result.ok = true;
                result.applyPending = !apply && !plan.unchanged();
                result.message = plan.unchanged() ? kind(language) + " source is unchanged; nothing written"
                        : "dry-run: exact " + kind(language) + " replacement validated; apply requires expectedHash and force=true";
                return result;
            }
            Exception[] callbackFailure = {null};
            access.exclusive(target, () -> {
                try {
                    Target current = access.resolve(projectName, fqn, language);
                    requireSameTarget(target, current);
                    Source beforeWrite = access.read(current);
                    access.requireSaved(current, beforeWrite.text());
                    access.requireWritable(current);
                    requireSameSource(source, access.read(current));
                    result.writeAttempted = true;
                    access.write(current, ExactTextReplacement.encode(plan.newText()));
                    result.applied = true;
                    Source saved = access.read(current);
                    if (!plan.newText().equals(saved.text()) || !plan.resultHash().equals(ExactTextReplacement.hash(saved.text()))) {
                        throw new IllegalStateException("saved " + kind(language) + " source differs from the planned exact text");
                    }
                    requireSameTarget(target, access.resolve(projectName, fqn, language));
                    result.verified = true;
                } catch (Exception exception) {
                    callbackFailure[0] = exception;
                }
            });
            if (callbackFailure[0] != null) throw callbackFailure[0];
            if (!result.applied || !result.verified) {
                throw new IllegalStateException("exclusive " + kind(language) + " replacement did not complete and verify");
            }
            result.ok = true;
            result.message = SCHEMA.equals(language)
                    ? "exact schema source replaced and reread; EDT reloads the schema from the file, template metadata was not written"
                    : SPREADSHEET.equals(language)
                    ? "exact spreadsheet source replaced and reread; EDT reloads the template from the file, template metadata was not written"
                    : "exact HTML locale source replaced and reread; other locales and metadata were not written";
        } catch (Exception exception) {
            result.ok = false;
            result.busy = !result.writeAttempted && rootCause(exception) instanceof BusyException;
            result.message = (result.applied ? kind(language) + " source was written but verification failed: "
                    : result.writeAttempted ? kind(language) + " write failed; reread the template before retrying: "
                    : kind(language) + " template replacement refused or failed: ") + cause(exception);
        }
        return result;
    }

    /** The schema calls: the same exact read and replacement, addressed to the one schema file. */
    public Result readSchema(String projectName, String fqn) {
        return read(projectName, fqn, SCHEMA);
    }

    public Result replaceSchema(String projectName, String fqn, String text, String expectedHash,
            boolean apply, boolean force) {
        return replace(projectName, fqn, SCHEMA, text, expectedHash, apply, force);
    }

    /** {@link #replaceSchema} with the additions applied to the saved schema, plus what they were. */
    public static final class EditResult {
        public Result replacement;
        public List<String> plan = new ArrayList<>();
        public io.github.keyfire.edtbridge.core.TextChange change;
        public boolean ok;
        public String message;
    }

    public EditResult editSchema(String projectName, String fqn, List<io.github.keyfire.edtbridge.core.DcsEdit.Operation> edits,
            String expectedHash, boolean apply) {
        EditResult edit = new EditResult();
        Result current = readSchema(projectName, fqn);
        if (!current.ok) {
            edit.replacement = current;
            edit.message = current.message;
            return edit;
        }
        if (apply && (expectedHash == null || expectedHash.isBlank())) {
            edit.message = "apply requires expectedHash: the sourceHash of the dry-run";
            return edit;
        }
        try {
            var planned = io.github.keyfire.edtbridge.core.DcsEdit.apply(current.text, edits);
            edit.plan = new ArrayList<>(planned.plan());
            edit.change = io.github.keyfire.edtbridge.core.TextChange.of(current.text, planned.text());
            Result replaced = replaceSchema(projectName, fqn, planned.text(),
                    apply ? expectedHash : current.sourceHash, apply, apply);
            // The full texts are already on disk and in the change; the answer stays short.
            replaced.oldText = null;
            replaced.newText = null;
            edit.replacement = replaced;
            edit.ok = replaced.ok;
            edit.message = !replaced.ok ? replaced.message
                    : apply ? "schema written: " + String.join("; ", edit.plan)
                            + ". EDT validation does not check the schema XML; build the report or data processor "
                            + "(edt_dump_external_object) to check it"
                    : "dry-run: " + String.join("; ", edit.plan) + "; apply with expectedHash=" + replaced.sourceHash;
        } catch (IllegalArgumentException refusal) {
            edit.message = "nothing written: " + refusal.getMessage();
        }
        return edit;
    }

    /** The spreadsheet calls: the exact read and replacement of the one Template.mxlx file. */
    public Result readSpreadsheet(String projectName, String fqn) {
        return read(projectName, fqn, SPREADSHEET);
    }

    public Result replaceSpreadsheet(String projectName, String fqn, String text, String expectedHash,
            boolean apply, boolean force) {
        return replace(projectName, fqn, SPREADSHEET, text, expectedHash, apply, force);
    }

    private static String kind(String language) {
        return SCHEMA.equals(language) ? "schema" : SPREADSHEET.equals(language) ? "spreadsheet" : "HTML";
    }

    private static void requireArguments(String projectName, String fqn, String language) {
        if (projectName == null || projectName.isBlank() || fqn == null || fqn.isBlank()
                || language == null || language.isBlank()) {
            throw new IllegalArgumentException("projectName, templateFqn and explicit language are required");
        }
        if (!projectName.equals(projectName.strip()) || !fqn.equals(fqn.strip()) || !language.equals(language.strip())) {
            throw new IllegalArgumentException("projectName, templateFqn and language must not have surrounding whitespace");
        }
    }

    private static void describe(Result result, Target target) {
        result.found = true;
        result.projectName = target.projectName(); result.templateFqn = target.templateFqn();
        result.templateUuid = target.templateUuid(); result.templateType = target.templateType();
        result.language = target.language(); result.availableLanguages = target.languages(); result.path = target.path();
    }

    private static void requireSameTarget(Target planned, Target current) {
        if (!planned.equals(current)) throw new IllegalArgumentException("template identity or locale mapping changed; repeat the dry-run");
    }

    private static void requireSameSource(Source planned, Source current) {
        if (!planned.equals(current)) {
            throw new IllegalArgumentException("template source, encoding or file revision changed; repeat the read or dry-run");
        }
    }

    static BasicTemplate resolveTemplate(IBmTransaction transaction, String fqn) {
        String[] segments = fqn.split("\\.", -1);
        if (segments.length < 2 || segments.length % 2 != 0) throw new IllegalArgumentException("invalid templateFqn: " + fqn);
        for (String segment : segments) {
            if (segment.isBlank() || !segment.equals(segment.strip())) throw new IllegalArgumentException("invalid templateFqn: " + fqn);
        }
        EObject indexed = transaction.getTopObjectByFqn(fqn);
        if (indexed instanceof BasicTemplate template && !template.eIsProxy()) return template;
        var object = MetadataWriteGateway.resolveDeleteTarget(transaction, fqn);
        if (!(object instanceof BasicTemplate template) || template.eIsProxy()) {
            throw new IllegalArgumentException("existing common or nested template not found: " + fqn);
        }
        return template;
    }

    static HtmlDocumentPage selectPage(BasicTemplate template, String language) {
        if (language == null || language.isBlank()) throw new IllegalArgumentException("an explicit existing language is required");
        if (template == null || template.getTemplateType() != TemplateType.HTML_DOCUMENT
                || !(template.getTemplate() instanceof HtmlDocument document) || document.eIsProxy()) {
            throw new IllegalArgumentException("template must be an existing resolved HTML_DOCUMENT");
        }
        List<String> languages = new ArrayList<>();
        HtmlDocumentPage selected = null;
        for (var page : document.getPages()) {
            String lang = page.getLang();
            if (page.eIsProxy() || lang == null || lang.isBlank()
                    || languages.stream().anyMatch(existing -> existing.equalsIgnoreCase(lang))) {
                throw new IllegalArgumentException("template has an unresolved, empty or duplicate locale");
            }
            languages.add(lang);
            if (language.equals(lang)) selected = page;
        }
        if (selected == null) throw new IllegalArgumentException("language not found: " + language + "; available languages: " + languages);
        return selected;
    }

    static String bufferProblem(ITextFileBuffer buffer, String source) {
        if (buffer == null) return null;
        if (buffer.isDirty()) return "save the template text editor before replacement";
        if (!buffer.isSynchronized()) return "template text buffer is out of sync with its file";
        if (buffer.getStatus() != null && !buffer.getStatus().isOK()) return "template text buffer has an unresolved status";
        if (source != null) {
            var document = buffer.getDocument();
            String text = document == null ? null : document.get();
            if (!source.equals(text) && !(source.startsWith("\ufeff") && source.substring(1).equals(text))) {
                return "clean template text buffer differs from saved source";
            }
        }
        return null;
    }

    static void requireContextSaved(IEditingContext context) {
        if (context != null && (context.isDisposed() || context.isDirty())) {
            throw new IllegalArgumentException("save the template metadata/editor context before replacement");
        }
    }

    private static final class EdtAccess implements Access {
        public Target resolve(String projectName, String fqn, String language) {
            IProject project = project(projectName);
            IBmModelManager models = ServiceAccess.get(IBmModelManager.class);
            var model = models == null ? null : models.getModel(project);
            if (model == null) throw new IllegalStateException("EDT model is not loaded: " + projectName);
            IProjectOperationApi operations = operations();
            IResourceLookup lookup = FormWriteGateway.coreService(IResourceLookup.class);
            if (lookup == null) throw new IllegalStateException("EDT resource lookup is unavailable");
            return model.executeReadonlyTask(new AbstractBmTask<Target>("edt-bridge.htmlTemplate.resolve") {
                @Override public Target execute(IBmTransaction transaction, IProgressMonitor monitor) {
                    BasicTemplate template = resolveTemplate(transaction, fqn);
                    if (SCHEMA.equals(language) || SPREADSHEET.equals(language)) {
                        return contentTarget(projectName, fqn, template, project, operations, lookup, language);
                    }
                    HtmlDocumentPage selected = selectPage(template, language);
                    if (template.getUuid() == null) throw new IllegalArgumentException("template UUID is unavailable");
                    requireContextSaved(operations.getExistingContext(template));
                    var document = (HtmlDocument) template.getTemplate();
                    requireContextSaved(operations.getExistingContext(document));
                    List<String> languages = new ArrayList<>();
                    List<IFile> pageFiles = new ArrayList<>();
                    Map<String, String> paths = new LinkedHashMap<>();
                    for (var page : document.getPages()) {
                        IFile file = page.getFile();
                        requireFile(projectName, file);
                        if (pageFiles.contains(file)) throw new IllegalArgumentException("template locales share one source file");
                        pageFiles.add(file);
                        languages.add(page.getLang()); paths.put(page.getLang(), file.getProjectRelativePath().toPortableString());
                    }
                    IFile file = selected.getFile();
                    IFile metadata = lookup.getPlatformResource(template);
                    if (metadata == null || !project.equals(metadata.getProject())) {
                        throw new IllegalStateException("template metadata resource does not belong to the requested project");
                    }
                    requireFile(projectName, metadata);
                    IFile documentFile = lookup.getPlatformResource(document);
                    if (documentFile != null) requireFile(projectName, documentFile);
                    return new Target(projectName, fqn, template.getUuid().toString(), template.getTemplateType().getLiteral(),
                            language, languages, paths, file, metadata, documentFile);
                }
            });
        }

        /**
         * A data composition schema and a spreadsheet keep their whole content in one file next to
         * the template metadata ({@code Template.dcs}, {@code Template.mxlx}), in the designer XML;
         * that file is the text.
         */
        private static Target contentTarget(String projectName, String fqn, BasicTemplate template, IProject project,
                IProjectOperationApi operations, IResourceLookup lookup, String language) {
            TemplateType expected = SCHEMA.equals(language) ? TemplateType.DATA_COMPOSITION_SCHEMA
                    : TemplateType.SPREADSHEET_DOCUMENT;
            if (template.getTemplateType() != expected
                    || template.getTemplate() == null || template.getTemplate().eIsProxy()) {
                throw new IllegalArgumentException("template must be an existing resolved " + expected.getName()
                        + "; this one is " + template.getTemplateType().getName());
            }
            if (template.getUuid() == null) throw new IllegalArgumentException("template UUID is unavailable");
            requireContextSaved(operations.getExistingContext(template));
            requireContextSaved(operations.getExistingContext(template.getTemplate()));
            IFile file = lookup.getPlatformResource(template.getTemplate());
            requireFile(projectName, file);
            IFile metadata = lookup.getPlatformResource(template);
            if (metadata == null || !project.equals(metadata.getProject())) {
                throw new IllegalStateException("template metadata resource does not belong to the requested project");
            }
            requireFile(projectName, metadata);
            String path = file.getProjectRelativePath().toPortableString();
            return new Target(projectName, fqn, template.getUuid().toString(), template.getTemplateType().getLiteral(),
                    language, List.of(language), Map.of(language, path), file, metadata, null);
        }

        public Source read(Target target) throws Exception {
            IFile file = target.file();
            requireFile(target.projectName(), file);
            String charset = file.getCharset();
            ExactTextReplacement.requireUtf8(charset);
            long stamp = file.getModificationStamp();
            String text;
            try (var input = file.getContents()) { text = ExactTextReplacement.decode(input.readAllBytes()); }
            if (stamp != file.getModificationStamp()) throw new IllegalArgumentException("template changed while being read");
            return new Source(text, stamp, charset);
        }

        public void requireSaved(Target target, String source) {
            onWorkbench(() -> {
                requireSameTarget(target, resolve(target.projectName(), target.templateFqn(), target.language()));
                for (IFile file : files(target)) {
                    requireBuffersSaved(file, file.equals(target.file()) ? source : null);
                }
                if (!PlatformUI.isWorkbenchRunning()) return;
                IResourceLookup lookup = FormWriteGateway.coreService(IResourceLookup.class);
                for (var window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
                    for (var page : window.getPages()) {
                        for (var reference : page.getEditorReferences()) {
                            IEditorPart editor = reference.getEditor(false);
                            try {
                                IEditorInput input = editor == null ? reference.getEditorInput() : editor.getEditorInput();
                                boolean matching = matchesFile(input, target);
                                if (editor instanceof IDtEditor<?> dt && dt.getModel() instanceof BasicTemplate template
                                        && target.templateUuid().equals(String.valueOf(template.getUuid()))) {
                                    IProject modelProject = lookup == null ? null : lookup.getProject(template);
                                    if (modelProject == null) throw new IllegalArgumentException("cannot determine template editor project");
                                    matching |= target.projectName().equals(modelProject.getName());
                                }
                                if (matching) {
                                    if (reference.isDirty()) throw new IllegalArgumentException("save the template editor before replacement");
                                    if (editor != null) requireEditorSaved(editor, Collections.newSetFromMap(new IdentityHashMap<>()));
                                }
                            } catch (org.eclipse.ui.PartInitException exception) {
                                throw new IllegalStateException("cannot verify an existing editor input", exception);
                            }
                        }
                    }
                }
            });
        }

        public void exclusive(Target target, Runnable operation) {
            // EDT's own jobs take the project operation first and then wait for the UI thread, for example
            // an infobase update asking a question. Taking the locks in the opposite order deadlocks, so the
            // operation is awaited here, off the UI thread and with a deadline, and checks inside it are bounded.
            if (PlatformUI.isWorkbenchRunning() && Display.getCurrent() != null) {
                throw new BusyException("a template write cannot wait for an EDT project operation on the UI thread");
            }
            IProject project = project(target.projectName());
            performBounded((run, monitor) -> operations().performExclusiveOperation(run, ProjectPipelineJob.BUILD,
                    monitor, project), operation, EXCLUSIVE_WAIT_MS);
        }

        public void requireWritable(Target target) {
            requireFile(target.projectName(), target.file());
            var attributes = target.file().getResourceAttributes();
            if (attributes != null && attributes.isReadOnly()) throw new IllegalArgumentException("template file is read-only");
        }

        public void write(Target target, byte[] bytes) throws Exception {
            // As in BSL method replacement: workspace API preserves exact source and refreshes clean editors.
            target.file().setContents(new ByteArrayInputStream(bytes), false, true, new NullProgressMonitor());
        }

        private static List<IFile> files(Target target) {
            List<IFile> files = new ArrayList<>();
            IProject project = target.file().getProject();
            for (String path : target.pagePaths().values()) files.add(project.getFile(path));
            files.add(target.metadataFile());
            if (target.documentFile() != null && !files.contains(target.documentFile())) files.add(target.documentFile());
            return files;
        }

        private static void requireBuffersSaved(IFile file, String source) {
            var manager = FileBuffers.getTextFileBufferManager();
            if (manager == null) throw new IllegalStateException("Eclipse text file buffers are unavailable");
            String problem = bufferProblem(manager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE), source);
            if (problem == null && file.getLocation() != null) {
                problem = bufferProblem(manager.getTextFileBuffer(file.getLocation(), LocationKind.LOCATION), source);
            }
            if (problem != null) throw new IllegalArgumentException(problem + ": " + file.getProjectRelativePath());
        }

        private static boolean matchesFile(IEditorInput input, Target target) {
            IFile file = input instanceof IFileEditorInput fileInput ? fileInput.getFile()
                    : input == null ? null : input.getAdapter(IFile.class);
            return file != null && files(target).contains(file);
        }

        private static void requireEditorSaved(IEditorPart editor, Set<IEditorPart> seen) {
            if (!seen.add(editor)) return;
            if (editor.isDirty()) throw new IllegalArgumentException("save the template editor before replacement");
            if (editor instanceof IDtEditor<?> dt) {
                IEditingContext context = dt.getApiEditingContext();
                if (context == null) throw new IllegalArgumentException("cannot verify the template editor context");
                requireContextSaved(context);
            }
            if (editor instanceof ITextEditor textEditor) {
                var provider = textEditor.getDocumentProvider();
                if (provider == null || provider.mustSaveDocument(editor.getEditorInput())) {
                    throw new IllegalArgumentException("save the template embedded text editor before replacement");
                }
            }
            if (editor instanceof IDtGranularEditorEmbeddedEditorPage<?> page && page.getEmbeddedEditor() != null) {
                requireEditorSaved(page.getEmbeddedEditor(), seen);
            }
            if (editor instanceof MultiPageEditorPart multi && multi.getSelectedPage() instanceof IEditorPart page) {
                requireEditorSaved(page, seen);
            }
        }

        private static IProjectOperationApi operations() {
            IProjectOperationApi operations = ServiceAccess.get(IProjectOperationApi.class);
            if (operations == null) throw new IllegalStateException("exclusive EDT project operations are unavailable");
            return operations;
        }

        private static IProject project(String name) {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
            if (!project.exists() || !project.isOpen()) throw new IllegalArgumentException("EDT project is missing or closed: " + name);
            return project;
        }

        private static void requireFile(String projectName, IFile file) {
            if (file == null || !file.exists() || !file.isAccessible()
                    || !projectName.equals(file.getProject().getName())) {
                throw new IllegalArgumentException("existing template file is unavailable in the requested project");
            }
            if (!file.isSynchronized(IResource.DEPTH_ZERO)) throw new IllegalArgumentException("template file is out of sync; refresh it in EDT");
        }
    }

    private static void onWorkbench(Runnable operation) {
        if (!PlatformUI.isWorkbenchRunning()) { operation.run(); return; }
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (display == null || display.isDisposed()) throw new IllegalStateException("EDT workbench is unavailable");
        if (Display.getCurrent() == display) operation.run(); else dispatchBounded(display::asyncExec, operation, UI_WAIT_MS);
    }

    /**
     * Runs an operation on another thread and waits at most {@code waitMs} for it to start. An operation
     * that has not started by then is abandoned and never runs; one that has started is awaited to the end.
     */
    static void dispatchBounded(Consumer<Runnable> dispatcher, Runnable operation, long waitMs) {
        AtomicInteger state = new AtomicInteger(); // 0 queued, 1 running, 2 abandoned
        CountDownLatch finished = new CountDownLatch(1);
        Throwable[] failure = {null};
        dispatcher.accept(() -> {
            if (!state.compareAndSet(0, 1)) return;
            try { operation.run(); } catch (RuntimeException | Error exception) { failure[0] = exception; }
            finally { finished.countDown(); }
        });
        try {
            if (!finished.await(waitMs, TimeUnit.MILLISECONDS)) {
                if (state.compareAndSet(0, 2)) {
                    throw new BusyException("the EDT UI thread did not respond in " + waitMs + " ms; nothing was written");
                }
                finished.await();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (state.compareAndSet(0, 2)) throw new BusyException("interrupted while waiting for the EDT UI thread");
            throw new IllegalStateException("interrupted while an EDT UI check was running", interrupted);
        }
        if (failure[0] instanceof RuntimeException exception) throw exception;
        if (failure[0] instanceof Error error) throw error;
    }

    /**
     * Starts an EDT project operation that polls its monitor while queued. When the deadline passes before
     * EDT starts it, EDT drops the queued operation and this method reports busy; a started one is not cancelled.
     */
    static void performBounded(BiConsumer<Runnable, IProgressMonitor> perform, Runnable operation, long waitMs) {
        AtomicBoolean started = new AtomicBoolean();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        IProgressMonitor monitor = new NullProgressMonitor() {
            @Override public boolean isCanceled() {
                return super.isCanceled() || !started.get() && System.nanoTime() - deadline >= 0;
            }
        };
        perform.accept(() -> { started.set(true); operation.run(); }, monitor);
        if (!started.get()) {
            throw new BusyException("another EDT operation holds the project (for example an infobase update "
                    + "waiting for an answer); nothing was written, retry after it completes");
        }
    }

    private static Throwable rootCause(Throwable exception) {
        while (exception.getCause() != null && exception.getCause() != exception) exception = exception.getCause();
        return exception;
    }

    private static String cause(Throwable exception) {
        while (exception.getCause() != null && exception.getCause() != exception) exception = exception.getCause();
        return exception.getClass().getSimpleName() + (exception.getMessage() == null ? "" : ": " + exception.getMessage());
    }
}
