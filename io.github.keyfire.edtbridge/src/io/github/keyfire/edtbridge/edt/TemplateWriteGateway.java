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

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
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
import com._1c.g5.v8.dt.core.platform.IDtProject;
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
 * open the report with the configuration's common report form.
 */
public final class TemplateWriteGateway {

    private static final Pattern IDENT = Pattern.compile("^[\\p{L}_][\\p{L}\\p{N}_]*$");
    private static final String MAIN_SCHEMA = "mainDataCompositionSchema";

    public static final class AddTemplateResult {
        public boolean ok, applied, ownerFound, nameValid;
        public Boolean nameAvailable;
        public String projectName, ownerFqn, ownerType, name, templateType, templateFqn, plan, message;
        public boolean mainSchema;
        public List<String> existingTemplates = new ArrayList<>();
    }

    /**
     * Create a template on an owner object. Dry-run by default. Only the data composition schema is
     * supported for now: its content is an empty schema that the caller fills with edt_replace_dcs.
     */
    public AddTemplateResult addTemplate(String projectName, String ownerFqn, String name, String typeArg,
            String synonymRu, boolean setMainSchema, boolean apply) {
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
                    + "alias СхемаКомпоновкиДанных; SpreadsheetDocument, alias ТабличныйДокумент)";
            return r;
        }
        r.templateType = type.getLiteral();
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
                + (setMainSchema ? " and make it the main data composition schema" : "");
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
                    // data composition schema (Template.dcs) or a spreadsheet in template mode (Template.mxlx).
                    EObject content;
                    if (type == TemplateType.SPREADSHEET_DOCUMENT) {
                        SpreadsheetDocument sheet = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
                        sheet.setTemplateMode(true);
                        content = sheet;
                    } else {
                        content = DcsFactory.eINSTANCE.createDataCompositionSchema();
                    }
                    String contentFqn = fqnGenerator.generateExternalPropertyFqn(template,
                            MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE);
                    tx.attachTopObject((IBmObject) content, contentFqn);
                    template.setTemplate(content);
                    if (setMainSchema) {
                        EStructuralFeature main = owner.eClass().getEStructuralFeature(MAIN_SCHEMA);
                        owner.eSet(main, template);
                    }
                    factory.fillDefaultReferences(template);
                    created[0] = contentFqn;
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
        boolean exportedContent = dtProject != null && mm.forceExport(dtProject, created[0]);
        r.applied = true;
        r.message = "created template " + r.templateFqn + " [" + r.templateType + "]"
                + (setMainSchema ? " as the main data composition schema" : "")
                + ((exportedOwner && exportedContent)
                        ? " (serialized: owner .mdo + " + (type == TemplateType.SPREADSHEET_DOCUMENT
                                ? "Template.mxlx" : "Template.dcs") + ");"
                        : " - WARNING: forceExport incomplete (owner=" + exportedOwner + ", content="
                                + exportedContent + ");")
                + (type == TemplateType.SPREADSHEET_DOCUMENT
                        ? " describe its areas and cells with edt_write_spreadsheet"
                        : " write its text with edt_replace_dcs or edt_modify_dcs");
        return r;
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
        return null;
    }

    private static EReference templatesFeature(EObject owner) {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature("templates");
        return (feature instanceof EReference ref && ref.isMany()) ? ref : null;
    }
}
