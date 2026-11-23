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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupport;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupportProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.htmldocument.model.HtmlDocument;
import com._1c.g5.v8.dt.htmldocument.model.HtmlDocumentFactory;
import com._1c.g5.v8.dt.htmldocument.model.HtmlDocumentPage;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Templates of a metadata object. The first kind the bridge creates is the data composition
 * schema: an empty schema is attached to a new template, its text is then written with
 * {@code edt_replace_dcs}, and a report can take it as its main schema - which makes the client
 * open the report with the configuration's common report form. A spreadsheet, an HTML document
 * and a text document follow; the last two keep their texts in files of their own.
 */
public final class TemplateWriteGateway {

    private static final Pattern IDENT = Pattern.compile("^[\\p{L}_][\\p{L}\\p{N}_]*$");
    private static final String MAIN_SCHEMA = "mainDataCompositionSchema";

    public static final class AddTemplateResult {
        public boolean ok, applied, ownerFound, nameValid;
        public Boolean nameAvailable;
        public String projectName, ownerFqn, ownerType, name, templateType, templateFqn, plan, message;
        public boolean mainSchema;
        /** An HTML template: the language of its only page, the one EDT edits the project in. */
        public String language;
        public List<String> existingTemplates = new ArrayList<>();
        /** After apply: whether the content file was read back, its path and the sourceHash for the next write. */
        public Boolean contentReadable;
        public String contentPath, sourceHash;
    }

    /**
     * Create a template on an owner object. Dry-run by default. A data composition schema and a
     * spreadsheet start empty and are filled by their own tools. An HTML template gets one page in
     * the language EDT edits the project in, and a text template its {@code Template.txt}; both take
     * the starting text from {@code text}, empty when it is omitted.
     */
    public AddTemplateResult addTemplate(String projectName, String ownerFqn, String name, String typeArg,
            String synonymRu, boolean setMainSchema, String text, boolean apply) {
        AddTemplateResult r = new AddTemplateResult();
        r.projectName = projectName;
        r.ownerFqn = ownerFqn;
        r.name = name;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        if (name == null || !IDENT.matcher(name).matches()) {
            r.message = "name is not a valid 1C identifier: \"" + name + "\"";
            return r;
        }
        r.nameValid = true;
        TemplateType type = templateType(typeArg);
        if (type == null) {
            r.message = "unsupported templateType: \"" + typeArg + "\" (supported: DataCompositionSchema, "
                    + "alias СхемаКомпоновкиДанных; SpreadsheetDocument, alias ТабличныйДокумент; HTMLDocument, "
                    + "alias HTMLДокумент; TextDocument, alias ТекстовыйДокумент)";
            return r;
        }
        r.templateType = type.getLiteral();
        boolean textual = type == TemplateType.HTML_DOCUMENT || type == TemplateType.TEXT_DOCUMENT;
        if (text != null && !textual) {
            r.message = "text applies to an HTML or a text template, not to " + r.templateType
                    + "; fill this one with its own tool after creation";
            return r;
        }
        if (type == TemplateType.HTML_DOCUMENT) {
            r.language = FormWriteGateway.editingLanguageCode(p);
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }
        final boolean[] mainSupported = {false};
        model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.addTemplate.validate") {
            @Override
            public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                EObject owner = tx.getTopObjectByFqn(ownerFqn);
                if (!(owner instanceof MdObject)) {
                    return null;
                }
                r.ownerFound = true;
                r.ownerType = owner.eClass().getName();
                EReference feature = templatesFeature(owner);
                if (feature == null) {
                    return null;
                }
                boolean free = true;
                for (Object item : (List<?>) owner.eGet(feature)) {
                    if (item instanceof BasicTemplate t && t.getName() != null) {
                        r.existingTemplates.add(t.getName());
                        free &= !t.getName().equalsIgnoreCase(name);
                    }
                }
                r.nameAvailable = free;
                mainSupported[0] = owner.eClass().getEStructuralFeature(MAIN_SCHEMA) != null;
                return null;
            }
        });
        if (!r.ownerFound) {
            r.message = "owner not found (or not a metadata object): " + ownerFqn;
            return r;
        }
        if (r.nameAvailable == null) {
            r.message = "owner " + r.ownerType + " cannot carry templates";
            return r;
        }
        if (setMainSchema && type != TemplateType.DATA_COMPOSITION_SCHEMA) {
            r.message = "setMainSchema applies to a data composition schema, not to " + r.templateType;
            return r;
        }
        if (setMainSchema && !mainSupported[0]) {
            r.message = "owner " + r.ownerType + " has no main data composition schema - only a report has one";
            return r;
        }
        r.ok = r.nameAvailable;
        r.mainSchema = setMainSchema;
        r.plan = "Create template \"" + name + "\" [" + r.templateType + "] on " + ownerFqn
                + (setMainSchema ? " and make it the main data composition schema" : "")
                + (r.language != null ? " with a page in language " + r.language : "")
                + (textual ? (text == null || text.isEmpty() ? ", empty" : ", with the given text of "
                        + text.length() + " character(s)") : "");
        if (!r.nameAvailable) {
            r.message = "a template named \"" + name + "\" already exists on " + ownerFqn;
        }
        if (!apply || !r.ok) {
            if (apply) {
                r.message = r.message + " - apply refused (nothing created).";
            }
            return r;
        }
        IModelObjectFactory factory = FormWriteGateway.modelObjectFactory();
        ITopObjectFqnGenerator fqnGenerator = FormWriteGateway.topObjectFqnGenerator();
        if (factory == null || fqnGenerator == null) {
            r.message = "EDT model factory or FQN generator unavailable";
            return r;
        }
        final Version version = GatewaySupport.projectVersion(p);
        final String[] created = {null};
        try {
            model.getGlobalContext().executeImportTask(new AbstractBmTask<Object>("edt-bridge.addTemplate.apply") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    EObject owner = tx.getTopObjectByFqn(ownerFqn);
                    EReference feature = templatesFeature(owner);
                    EObject made = factory.create(feature.getEReferenceType(), version);
                    if (!(made instanceof BasicTemplate template)) {
                        throw new IllegalStateException("factory.create returned "
                                + (made == null ? "null" : made.eClass().getName()) + " (not a BasicTemplate)");
                    }
                    template.setName(name);
                    template.setUuid(UUID.randomUUID());
                    template.setTemplateType(type);
                    if (synonymRu != null && !synonymRu.isBlank()) {
                        template.getSynonym().put("ru", synonymRu);
                    }
                    // Link first: the FQN of the content derives from the containment.
                    @SuppressWarnings("unchecked")
                    List<EObject> templates = (List<EObject>) owner.eGet(feature);
                    templates.add(template);
                    // The content is a top object of its own, like the template editor creates it: a
                    // data composition schema (Template.dcs), a spreadsheet in template mode (Template.mxlx)
                    // or the list of pages of an HTML document (Template.htmldoc). A text template has no
                    // such object: its content is the file Template.txt, which EDT reads as it is.
                    if (type != TemplateType.TEXT_DOCUMENT) {
                        EObject content;
                        if (type == TemplateType.SPREADSHEET_DOCUMENT) {
                            SpreadsheetDocument sheet = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
                            sheet.setTemplateMode(true);
                            content = sheet;
                        } else if (type == TemplateType.HTML_DOCUMENT) {
                            content = htmlDocument(r.language);
                        } else {
                            content = DcsFactory.eINSTANCE.createDataCompositionSchema();
                        }
                        String contentFqn = fqnGenerator.generateExternalPropertyFqn(template,
                                MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE);
                        tx.attachTopObject((IBmObject) content, contentFqn);
                        template.setTemplate(content);
                        created[0] = contentFqn;
                    }
                    if (setMainSchema) {
                        EStructuralFeature main = owner.eClass().getEStructuralFeature(MAIN_SCHEMA);
                        owner.eSet(main, template);
                    }
                    factory.fillDefaultReferences(template);
                    return null;
                }
            }, false);
        } catch (RuntimeException ex) {
            r.message = "template creation failed (verify the working tree): " + GatewaySupport.describeCause(ex);
            return r;
        }
        r.templateFqn = ownerFqn + ".Template." + name;
        IDtProject dtProject = mm.getDtProject(model);
        boolean exportedOwner = dtProject != null && mm.forceExport(dtProject, ownerFqn);
        boolean exportedContent = created[0] == null || (dtProject != null && mm.forceExport(dtProject, created[0]));
        r.applied = true;
        if (textual) {
            return finishTextual(r, p, model, type, text, exportedOwner, exportedContent);
        }
        String file = type == TemplateType.SPREADSHEET_DOCUMENT ? "Template.mxlx" : "Template.dcs";
        // EDT accepts the export before the file is written. Reading it back here, with the wait of the
        // template read, means the next edt_replace_dcs or edt_write_spreadsheet finds the file. An export
        // EDT did not accept writes no file, so there is nothing to wait for.
        HtmlTemplateGateway.noteCreated(projectName, r.templateFqn);
        HtmlTemplateGateway.Result content = new HtmlTemplateGateway.Result();
        content.message = "EDT did not accept the export of the content";
        if (exportedContent) {
            content = new HtmlTemplateGateway().read(projectName, r.templateFqn,
                    type == TemplateType.SPREADSHEET_DOCUMENT ? HtmlTemplateGateway.SPREADSHEET : HtmlTemplateGateway.SCHEMA);
        }
        r.contentReadable = content.ok;
        r.contentPath = content.path;
        r.sourceHash = content.ok ? content.sourceHash : null;
        r.message = "created template " + r.templateFqn + " [" + r.templateType + "]"
                + (setMainSchema ? " as the main data composition schema" : "")
                + ((exportedOwner && exportedContent)
                        ? " (serialized: owner .mdo + " + file + ");"
                        : " - WARNING: forceExport incomplete (owner=" + exportedOwner + ", content="
                                + exportedContent + ");")
                + (content.ok ? " " + file + " is on disk and readable, sourceHash " + content.sourceHash + ";"
                        : " WARNING: " + file + " could not be read back: " + content.message + ";")
                + (type == TemplateType.SPREADSHEET_DOCUMENT
                        ? " describe its areas and cells with edt_write_spreadsheet"
                        : " write its text with edt_replace_dcs or edt_modify_dcs");
        return r;
    }

    /** The pages of a new HTML template: one, in the given language, its file named after the language. */
    static HtmlDocument htmlDocument(String language) {
        HtmlDocument document = HtmlDocumentFactory.eINSTANCE.createHtmlDocument();
        HtmlDocumentPage page = HtmlDocumentFactory.eINSTANCE.createHtmlDocumentPage();
        page.setLang(language);
        document.getPages().add(page);
        return document;
    }

    /**
     * Writes the text of a new HTML page or text template into its file and reads it back. EDT keeps
     * these texts in files of their own, not in the model: the page {@code <language>.html} next to
     * {@code Template.htmldoc}, the text in {@code Template.txt}.
     */
    private AddTemplateResult finishTextual(AddTemplateResult r, IProject project, IBmModel model, TemplateType type,
            String text, boolean exportedOwner, boolean exportedContent) {
        String fileName = type == TemplateType.HTML_DOCUMENT ? r.language + ".html" : "Template.txt";
        IFile file = null;
        String written;
        try {
            file = contentFile(project, model, r.templateFqn, type);
            writeText(file, text == null ? "" : text);
            written = null;
        } catch (Exception failure) {
            written = GatewaySupport.describeCause(failure);
        }
        if (file != null) r.contentPath = file.getProjectRelativePath().toPortableString();
        HtmlTemplateGateway.Result content = null;
        if (written == null && type == TemplateType.HTML_DOCUMENT) {
            // Read through the page EDT resolves, as edt_replace_html_template will.
            HtmlTemplateGateway.noteCreated(r.projectName, r.templateFqn);
            content = new HtmlTemplateGateway().read(r.projectName, r.templateFqn, r.language);
            r.contentReadable = content.ok;
            if (content.ok) {
                r.contentPath = content.path;
                r.sourceHash = content.sourceHash;
            }
        } else if (written == null) {
            r.contentReadable = file.exists();
        } else {
            r.contentReadable = false;
        }
        r.message = "created template " + r.templateFqn + " [" + r.templateType + "]"
                + ((exportedOwner && exportedContent)
                        ? " (serialized: owner .mdo" + (type == TemplateType.HTML_DOCUMENT ? " + Template.htmldoc" : "")
                                + ");"
                        : " - WARNING: forceExport incomplete (owner=" + exportedOwner + ", content="
                                + exportedContent + ");")
                + (written != null ? " WARNING: " + fileName + " could not be written: " + written + ";"
                        : content != null && !content.ok ? " WARNING: " + fileName + " could not be read back: "
                                + content.message + ";"
                        : " " + fileName + " is on disk" + (r.sourceHash != null ? ", sourceHash " + r.sourceHash : "")
                                + ";")
                + (type == TemplateType.HTML_DOCUMENT
                        ? " change the page with edt_replace_html_template, language " + r.language
                        : " its text is the file " + (r.contentPath == null ? fileName : r.contentPath));
        return r;
    }

    /** The file of the text: the page of the HTML document, or the file a text template is read from. */
    private static IFile contentFile(IProject project, IBmModel model, String templateFqn, TemplateType type) {
        IProjectFileSystemSupportProvider provider = FormWriteGateway.coreService(IProjectFileSystemSupportProvider.class);
        IProjectFileSystemSupport support = provider == null ? null : provider.getProjectFileSystemSupport(project);
        IFile file = model.executeReadonlyTask(new AbstractBmTask<IFile>("edt-bridge.addTemplate.contentFile") {
            @Override
            public IFile execute(IBmTransaction tx, IProgressMonitor monitor) {
                BasicTemplate template = HtmlTemplateGateway.resolveTemplate(tx, templateFqn);
                if (type == TemplateType.HTML_DOCUMENT) {
                    if (!(template.getTemplate() instanceof HtmlDocument document) || document.getPages().isEmpty()) {
                        throw new IllegalStateException("the HTML document of the template has no page");
                    }
                    return document.getPages().get(0).getFile();
                }
                if (support == null) throw new IllegalStateException("project file-system support unavailable");
                return support.getFile(template, MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE);
            }
        });
        if (file == null) throw new IllegalStateException("EDT gives no file for the text of " + templateFqn);
        if (!project.equals(file.getProject())) {
            throw new IllegalStateException("the file of the text is outside the project: " + file.getFullPath());
        }
        return file;
    }

    private static void writeText(IFile file, String text) throws CoreException {
        byte[] bytes = io.github.keyfire.edtbridge.core.ExactTextReplacement.encode(text);
        createFolders(file.getParent());
        // EDT may have written the folder, or the page itself, a moment ago without telling the
        // workspace; look at the disk before deciding between creating and replacing.
        file.getParent().refreshLocal(IResource.DEPTH_ONE, new NullProgressMonitor());
        if (file.exists()) {
            file.setContents(new ByteArrayInputStream(bytes), IResource.FORCE | IResource.KEEP_HISTORY,
                    new NullProgressMonitor());
        } else {
            file.create(new ByteArrayInputStream(bytes), IResource.FORCE, new NullProgressMonitor());
        }
        if (!StandardCharsets.UTF_8.name().equalsIgnoreCase(file.getCharset())) {
            file.setCharset(StandardCharsets.UTF_8.name(), new NullProgressMonitor());
        }
    }

    private static void createFolders(org.eclipse.core.resources.IContainer container) throws CoreException {
        if (container instanceof IFolder folder && !folder.exists()) {
            createFolders(folder.getParent());
            folder.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
            if (!folder.exists()) folder.create(IResource.FORCE, true, new NullProgressMonitor());
        }
    }

    static TemplateType templateType(String arg) {
        if (arg == null || arg.isBlank()) {
            return TemplateType.DATA_COMPOSITION_SCHEMA;
        }
        String key = arg.strip().replace("_", "").toLowerCase(Locale.ROOT);
        if (key.equals("datacompositionschema") || key.equals("dcs") || key.equals("схемакомпоновкиданных")) {
            return TemplateType.DATA_COMPOSITION_SCHEMA;
        }
        if (key.equals("spreadsheetdocument") || key.equals("spreadsheet") || key.equals("mxl")
                || key.equals("табличныйдокумент")) {
            return TemplateType.SPREADSHEET_DOCUMENT;
        }
        if (key.equals("htmldocument") || key.equals("html") || key.equals("htmlдокумент")) {
            return TemplateType.HTML_DOCUMENT;
        }
        if (key.equals("textdocument") || key.equals("text") || key.equals("txt") || key.equals("текстовыйдокумент")) {
            return TemplateType.TEXT_DOCUMENT;
        }
        return null;
    }

    private static EReference templatesFeature(EObject owner) {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature("templates");
        return (feature instanceof EReference ref && ref.isMany()) ? ref : null;
    }
}
