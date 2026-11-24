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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupport;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupportProvider;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.wiring.ServiceAccess;

import io.github.keyfire.edtbridge.core.FormCopyPaths;

/**
 * Copying a managed form to another object of the same project: a new form entry on the target, the
 * source form's content copied into it with the source owner's objects swapped for the target's, and
 * the source module copied as it is. It works the same in a configuration and in an extension.
 */
public final class FormCopyGateway {

    /** How many references left behind the result lists before it only counts. */
    private static final int LEFTOVERS_SHOWN = 20;

    /** Attributes of the form entry that identify it rather than describe it, so the copy keeps its own. */
    private static final Set<String> OWN_ATTRIBUTES = Set.of("name", "uuid", "objectBelonging",
            "extendedConfigurationObject");

    /** Outcome of {@link #copyForm}. */
    public static final class CopyFormResult {
        public boolean ok;
        public boolean applied;
        public String projectName;
        public String sourceFormFqn;
        public String targetOwnerFqn;
        public String name;
        public String requestedName;
        public String namePrefix;
        public String formFqn;
        public String modulePath;
        public boolean sourceFound;
        public boolean targetFound;
        public Boolean nameAvailable;
        public boolean sourceModule;
        public int remapped;
        public int leftoverCount;
        public final List<String> leftovers = new ArrayList<>();
        public final List<String> existingForms = new ArrayList<>();
        public String plan;
        public String warning;
        public String message;
    }

    /**
     * Copy a managed form to another object of the same project.
     *
     * @param projectName    EDT project name
     * @param sourceFormFqn  the form to copy: {@code Catalog.X.Form.Y} or {@code CommonForm.Y}
     * @param targetOwnerFqn the object that gets the copy, e.g. {@code DataProcessor.Z}
     * @param name           the copy's name; {@code null} keeps the source name
     * @param synonymRu      the copy's Russian synonym; {@code null} keeps the source synonym
     * @param apply          {@code false} validates and returns the plan; {@code true} copies
     */
    public CopyFormResult copyForm(String projectName, String sourceFormFqn, String targetOwnerFqn, String name,
            String synonymRu, boolean apply) {
        CopyFormResult r = new CopyFormResult();
        r.projectName = projectName;
        r.sourceFormFqn = sourceFormFqn;
        r.targetOwnerFqn = targetOwnerFqn;

        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        FormCopyPaths.Source source = FormCopyPaths.parse(sourceFormFqn);
        if (source == null) {
            r.message = "sourceFormFqn must name a form as <Type>.<Owner>.Form.<Name> or CommonForm.<Name>, got: "
                    + sourceFormFqn;
            return r;
        }
        if (targetOwnerFqn == null || targetOwnerFqn.isBlank()) {
            r.message = "targetOwnerFqn is required, e.g. DataProcessor.Обработка";
            return r;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }
        IProjectFileSystemSupportProvider provider = FormWriteGateway.fileSystemSupportProvider();
        IProjectFileSystemSupport support = (provider == null) ? null : provider.getProjectFileSystemSupport(p);

        // Stage 1 - validate against the live model and copy the content in memory, so the plan tells
        // how many references move to the target and which stay with the source owner.
        String[] refusal = {null};
        IFile[] sourceModule = {null};
        model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.copyForm.validate") {
            @Override
            public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                BasicForm form = sourceForm(tx, source);
                if (form == null) {
                    return null;
                }
                r.sourceFound = true;
                if (RoleRightsGateway.adopted(form)) {
                    refusal[0] = "the source is an adopted form: its content is the extension's version of a "
                            + "base form, which a copy cannot carry over";
                    return null;
                }
                if (!(form.getForm() instanceof Form)) {
                    refusal[0] = "the source is not a managed form (ordinary forms are not copied)";
                    return null;
                }
                EObject target = tx.getTopObjectByFqn(targetOwnerFqn);
                if (!(target instanceof MdObject)) {
                    return null;
                }
                r.targetFound = true;
                EReference feature = FormWriteGateway.findFormsFeature(target.eClass());
                if (feature == null) {
                    refusal[0] = "target " + target.eClass().getName() + " has no forms feature - it cannot carry forms";
                    return null;
                }
                if (RoleRightsGateway.adopted((MdObject) target)
                        && tx.getTopObjectByFqn("Configuration") instanceof Configuration configuration
                        && configuration.getNamePrefix() != null && !configuration.getNamePrefix().isBlank()) {
                    r.namePrefix = configuration.getNamePrefix();
                }
                r.name = FormCopyPaths.copyName(name, form.getName(), r.namePrefix);
                if (name != null && !name.isBlank() && !r.name.equals(name.trim())) {
                    r.requestedName = name.trim();
                }
                boolean free = true;
                for (BasicForm existing : FormWriteGateway.formsOf(target, feature)) {
                    if (existing.getName() != null) {
                        r.existingForms.add(existing.getName());
                        free &= !existing.getName().equalsIgnoreCase(r.name);
                    }
                }
                r.nameAvailable = free;
                note(r, copyContent(form, target, null));
                IFile module = (support == null) ? null : support.getFile(form, MdClassPackage.Literals.ABSTRACT_FORM__MODULE);
                r.sourceModule = module != null && module.exists();
                sourceModule[0] = r.sourceModule ? module : null;
                return null;
            }
        });

        if (!r.sourceFound) {
            r.message = "source form not found: " + source.fqn();
            return r;
        }
        if (refusal[0] != null) {
            r.message = refusal[0];
            return r;
        }
        if (!r.targetFound) {
            r.message = "target not found (or not a metadata object): " + targetOwnerFqn;
            return r;
        }
        if (!FormCopyPaths.isIdentifier(r.name)) {
            r.message = "name is not a valid 1C identifier: \"" + r.name + "\"";
            return r;
        }
        r.ok = Boolean.TRUE.equals(r.nameAvailable);
        r.plan = "Copy form " + source.fqn() + " to " + targetOwnerFqn + ".Form." + r.name
                + (r.sourceModule ? " with its module" : " (the source has no module)")
                + (r.remapped > 0 ? "; " + r.remapped + " reference(s) move to " + targetOwnerFqn : "")
                + (r.leftoverCount > 0 ? "; " + r.leftoverCount + " reference(s) keep pointing into the source owner" : "")
                + (r.requestedName != null ? "; the extension prefix " + r.namePrefix + " is added to the name" : "");
        r.warning = "the copy carries the source form's data paths, handlers and module as they are: run "
                + "edt_project_errors on the copy and fix what does not fit the new owner.";
        if (Boolean.FALSE.equals(r.nameAvailable)) {
            r.message = "a form named \"" + r.name + "\" already exists on " + targetOwnerFqn;
        }
        if (!apply) {
            return r;
        }
        if (!r.ok) {
            r.message = (r.message == null ? "validation failed" : r.message) + " - apply refused (nothing copied).";
            return r;
        }
        IModelObjectFactory factory = FormWriteGateway.modelObjectFactory();
        ITopObjectFqnGenerator fqnGenerator = FormWriteGateway.topObjectFqnGenerator();
        if (factory == null || fqnGenerator == null || support == null) {
            r.message = "EDT services unavailable (model object factory, FQN generator or project file system)";
            return r;
        }

        byte[] moduleText = null;
        if (sourceModule[0] != null) {
            try (InputStream in = sourceModule[0].getContents(true)) {
                moduleText = in.readAllBytes();
            } catch (Exception unreadable) {
                r.message = "cannot read the source module " + sourceModule[0].getFullPath() + ": "
                        + GatewaySupport.describeCause(unreadable) + " - apply refused (nothing copied).";
                return r;
            }
        }
        Version version = GatewaySupport.projectVersion(p);
        String copyName = r.name;
        try {
            model.getGlobalContext().executeImportTask(new AbstractBmTask<Object>("edt-bridge.copyForm.apply") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    BasicForm form = sourceForm(tx, source);
                    EObject target = tx.getTopObjectByFqn(targetOwnerFqn);
                    EReference feature = FormWriteGateway.findFormsFeature(target.eClass());
                    EObject created = factory.create(feature.getEReferenceType(), version);
                    if (!(created instanceof BasicForm copyEntry)) {
                        throw new IllegalStateException("factory.create returned "
                                + (created == null ? "null" : created.eClass().getName()) + " (not a BasicForm)");
                    }
                    describeLike(form, copyEntry);
                    copyEntry.setName(copyName);
                    copyEntry.setUuid(UUID.randomUUID());
                    if (synonymRu != null && !synonymRu.isBlank()) {
                        copyEntry.getSynonym().put("ru", synonymRu);
                    }
                    // Link the entry first: the FQN generator derives the content's path from the containment.
                    @SuppressWarnings("unchecked")
                    List<EObject> forms = (List<EObject>) target.eGet(feature);
                    forms.add(copyEntry);

                    Form content = (Form) copyContent(form, target, copyEntry).content;
                    content.setMdForm(copyEntry);
                    String contentFqn = fqnGenerator.generateExternalPropertyFqn(copyEntry,
                            MdClassPackage.Literals.BASIC_FORM__FORM);
                    tx.attachTopObject((IBmObject) content, contentFqn);
                    r.formFqn = contentFqn;
                    factory.fillDefaultReferences(copyEntry);
                    return null;
                }
            }, false);
        } catch (RuntimeException ex) {
            r.message = "form copy failed (verify the working tree - a partial form may remain): "
                    + GatewaySupport.describeCause(ex);
            return r;
        }

        if (moduleText != null) {
            try {
                r.modulePath = writeModule(p, model, support, targetOwnerFqn, copyName, moduleText);
            } catch (Exception ex) {
                r.warning = "form copied, but its module could not be written: " + GatewaySupport.describeCause(ex);
            }
        }
        IDtProject dtProject = mm.getDtProject(model);
        boolean exportedOwner = dtProject != null && mm.forceExport(dtProject, targetOwnerFqn);
        boolean exportedForm = dtProject != null && r.formFqn != null && mm.forceExport(dtProject, r.formFqn);
        FormValidationSupport.afterFormWrite(p, model, targetOwnerFqn + ".Form." + copyName);
        r.applied = true;
        r.message = "copied " + source.fqn() + " to " + targetOwnerFqn + ".Form." + copyName
                + ((exportedOwner && exportedForm)
                        ? " (serialized: owner .mdo + Form.form" + (r.modulePath != null ? " + Module.bsl" : "") + ")"
                        : " - WARNING: copied in memory but forceExport incomplete (owner=" + exportedOwner
                                + ", form=" + exportedForm + ")");
        return r;
    }

    /**
     * The source content copied for {@code target}. A copy into another object of the same kind moves
     * the references to the source owner's objects onto the target's; a copy into the same object or
     * from a common form keeps every reference, since all of them stay valid.
     */
    private static FormCopy.Result copyContent(BasicForm form, EObject target, BasicForm copyEntry) {
        EObject sourceOwner = (form.eContainer() instanceof MdObject owner) ? owner : null;
        boolean otherOwner = sourceOwner != null && sourceOwner != target;
        Map<EObject, EObject> pairs = otherOwner ? FormCopy.counterparts(sourceOwner, target) : Map.of();
        return FormCopy.copy(form.getForm(), pairs, otherOwner ? sourceOwner : null, form, copyEntry, otherOwner);
    }

    /** The source form entry: an object's form or a common form. */
    private static BasicForm sourceForm(IBmTransaction tx, FormCopyPaths.Source source) {
        if (source.common()) {
            return tx.getTopObjectByFqn(source.fqn()) instanceof BasicForm common ? common : null;
        }
        EObject owner = tx.getTopObjectByFqn(source.ownerFqn());
        EReference feature = (owner == null) ? null : FormWriteGateway.findFormsFeature(owner.eClass());
        if (feature == null) {
            return null;
        }
        for (BasicForm form : FormWriteGateway.formsOf(owner, feature)) {
            if (source.formName().equalsIgnoreCase(form.getName())) {
                return form;
            }
        }
        return null;
    }

    /** Give the new entry the source entry's describing attributes and synonym: kind, purposes, comment. */
    private static void describeLike(BasicForm source, BasicForm copy) {
        for (EAttribute attribute : source.eClass().getEAllAttributes()) {
            if (OWN_ATTRIBUTES.contains(attribute.getName()) || attribute.isDerived() || attribute.isTransient()
                    || !attribute.isChangeable() || !source.eIsSet(attribute)
                    || copy.eClass().getEStructuralFeature(attribute.getName()) != attribute) {
                continue;
            }
            if (attribute.isMany()) {
                @SuppressWarnings("unchecked")
                List<Object> values = (List<Object>) copy.eGet(attribute);
                values.clear();
                values.addAll((List<?>) source.eGet(attribute));
            } else {
                copy.eSet(attribute, source.eGet(attribute));
            }
        }
        copy.getSynonym().putAll(source.getSynonym());
    }

    private static void note(CopyFormResult r, FormCopy.Result preview) {
        r.remapped = preview.remapped;
        r.leftoverCount = preview.leftovers.size();
        preview.leftovers.stream().limit(LEFTOVERS_SHOWN).forEach(left -> r.leftovers.add(left.toString()));
    }

    private static String writeModule(IProject project, IBmModel model, IProjectFileSystemSupport support,
            String ownerFqn, String formName, byte[] text) throws Exception {
        IFile[] target = {null};
        model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.copyForm.modulePath") {
            @Override
            public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                EObject owner = tx.getTopObjectByFqn(ownerFqn);
                EReference feature = FormWriteGateway.findFormsFeature(owner.eClass());
                for (BasicForm form : FormWriteGateway.formsOf(owner, feature)) {
                    if (formName.equals(form.getName())) {
                        target[0] = support.getFile(form, MdClassPackage.Literals.ABSTRACT_FORM__MODULE);
                        break;
                    }
                }
                return null;
            }
        });
        IFile file = target[0];
        if (file == null) {
            throw new IllegalStateException("could not resolve the module file for form " + formName);
        }
        NullProgressMonitor monitor = new NullProgressMonitor();
        FormWriteGateway.createParentFolders(file);
        try (InputStream in = new ByteArrayInputStream(text)) {
            if (file.exists()) {
                file.setContents(in, true, true, monitor);
            } else {
                file.create(in, true, monitor);
            }
        }
        return file.getProjectRelativePath().toString();
    }
}
