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
        public boolean ok, found, applied, verified, unchanged, applyPending, valid, force, writeAttempted;
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
            result.message = "exact saved HTML source; UTF-8 hash includes any BOM and original line endings";
        } catch (Exception exception) {
            result.message = "HTML template read refused or failed: " + cause(exception);
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
                result.message = plan.unchanged() ? "HTML source is unchanged; nothing written"
                        : "dry-run: exact locale replacement validated; apply requires expectedHash and force=true";
                return result;
            }
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
                        throw new IllegalStateException("saved HTML source differs from the planned exact text");
                    }
                    requireSameTarget(target, access.resolve(projectName, fqn, language));
                    result.verified = true;
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            result.ok = true;
            result.message = "exact HTML locale source replaced and reread; other locales and metadata were not written";
        } catch (Exception exception) {
            result.ok = false;
            result.message = (result.applied ? "HTML source was written but verification failed: "
                    : result.writeAttempted ? "HTML write failed; reread the template before retrying: "
                    : "HTML template replacement refused or failed: ") + cause(exception);
        }
        return result;
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
            // Acquire the UI thread before the project operation; never wait for UI under a BM transaction.
            onWorkbench(() -> operations().performExclusiveOperation(operation, ProjectPipelineJob.BUILD,
                    new NullProgressMonitor(), project(target.projectName())));
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
            IFile file = input == null ? null : input.getAdapter(IFile.class);
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
                throw new IllegalArgumentException("existing HTML page file is unavailable in the requested project");
            }
            if (!file.isSynchronized(IResource.DEPTH_ZERO)) throw new IllegalArgumentException("template file is out of sync; refresh it in EDT");
        }
    }

    private static void onWorkbench(Runnable operation) {
        if (!PlatformUI.isWorkbenchRunning()) { operation.run(); return; }
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (display == null || display.isDisposed()) throw new IllegalStateException("EDT workbench is unavailable");
        if (Display.getCurrent() == display) operation.run(); else display.syncExec(operation);
    }

    private static String cause(Throwable exception) {
        while (exception.getCause() != null && exception.getCause() != exception) exception = exception.getCause();
        return exception.getClass().getSimpleName() + (exception.getMessage() == null ? "" : ": " + exception.getMessage());
    }
}
