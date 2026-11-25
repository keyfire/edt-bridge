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

import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
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
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessorForm;
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

    private record Destination(String fqn, UUID uuid, IFile module) { }

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
                r.namePrefix = extensionPrefix(tx, (MdObject) target);
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
        r.ok = false;
        IModelObjectFactory factory = FormWriteGateway.modelObjectFactory();
        ITopObjectFqnGenerator fqnGenerator = FormWriteGateway.topObjectFqnGenerator();
        if (factory == null || fqnGenerator == null || support == null) {
            r.message = "EDT services unavailable (model object factory, FQN generator or project file system)";
            return r;
        }

        IDtProject dtProject = mm.getDtProject(model);
        if (dtProject == null) {
            r.message = "no EDT project for export: " + projectName;
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
        Destination destination;
        try {
            destination = model.getGlobalContext().executeImportTask(new AbstractBmTask<Destination>(
                    "edt-bridge.copyForm.apply") {
                @Override
                public Destination execute(IBmTransaction tx, IProgressMonitor monitor) {
                    BasicForm form = sourceForm(tx, source);
                    if (form == null || RoleRightsGateway.adopted(form) || !(form.getForm() instanceof Form)) {
                        r.message = "the source is no longer an own managed form - apply refused";
                        return null;
                    }
                    if (!(tx.getTopObjectByFqn(targetOwnerFqn) instanceof MdObject target)) {
                        r.message = "target no longer exists - apply refused";
                        return null;
                    }
                    EReference feature = FormWriteGateway.findFormsFeature(target.eClass());
                    String currentName = FormCopyPaths.copyName(name, form.getName(), extensionPrefix(tx, target));
                    if (feature == null || !copyName.equals(currentName)) {
                        r.message = "the target or the effective copy name changed - apply refused";
                        return null;
                    }
                    for (BasicForm existing : FormWriteGateway.formsOf(target, feature)) {
                        if (copyName.equalsIgnoreCase(existing.getName())) {
                            r.nameAvailable = false;
                            r.message = "a form named \"" + copyName + "\" already exists on " + targetOwnerFqn
                                    + " - apply refused";
                            return null;
                        }
                    }
                    EObject created = factory.create(feature.getEReferenceType(), version);
                    if (!(created instanceof BasicForm copyEntry)) {
                        throw new IllegalStateException("factory.create did not return a BasicForm");
                    }
                    describeLike(form, copyEntry);
                    copyEntry.setName(copyName);
                    UUID uuid = UUID.randomUUID();
                    copyEntry.setUuid(uuid);
                    if (synonymRu != null && !synonymRu.isBlank()) {
                        copyEntry.getSynonym().put("ru", synonymRu);
                    }

                    // EDT derives the FQN and resource paths from the new entry's containment.
                    @SuppressWarnings("unchecked")
                    List<EObject> forms = (List<EObject>) target.eGet(feature);
                    forms.add(copyEntry);
                    String contentFqn = fqnGenerator.generateExternalPropertyFqn(copyEntry,
                            MdClassPackage.Literals.BASIC_FORM__FORM);
                    IFile formFile = support.getFile(copyEntry, MdClassPackage.Literals.BASIC_FORM__FORM);
                    IFile moduleFile = support.getFile(copyEntry, MdClassPackage.Literals.ABSTRACT_FORM__MODULE);
                    if (contentFqn == null || formFile == null || moduleFile == null
                            || formFile.getLocation() == null || moduleFile.getLocation() == null) {
                        forms.remove(copyEntry);
                        r.message = "cannot resolve a local path for the copy's form or module - apply refused";
                        return null;
                    }
                    if (tx.getTopObjectsByFqnIgnoreCase(contentFqn).hasNext()
                            || destinationOccupied(formFile) || destinationOccupied(moduleFile)) {
                        forms.remove(copyEntry);
                        r.nameAvailable = false;
                        r.message = "the form destination already contains model data or files - apply refused: "
                                + formFile.getFullPath();
                        return null;
                    }

                    FormCopy.Result copied = copyContent(form, target, copyEntry);
                    Form content = (Form) copied.content;
                    content.setMdForm(copyEntry);
                    tx.attachTopObject((IBmObject) content, contentFqn);
                    factory.fillDefaultReferences(copyEntry);
                    r.leftovers.clear();
                    note(r, copied);
                    return new Destination(contentFqn, uuid, moduleFile);
                }
            }, false);
        } catch (RuntimeException ex) {
            r.message = "form copy failed (verify the working tree - a partial form may remain): "
                    + GatewaySupport.describeCause(ex);
            return r;
        }
        if (destination == null) {
            return r;
        }

        r.formFqn = destination.fqn();
        r.applied = true;
        try {
            boolean isOurCopy = model.executeReadonlyTask(new AbstractBmTask<Boolean>("edt-bridge.copyForm.readback") {
                @Override
                public Boolean execute(IBmTransaction tx, IProgressMonitor monitor) {
                    EObject owner = tx.getTopObjectByFqn(targetOwnerFqn);
                    if (owner == null || !(tx.getTopObjectByFqn(destination.fqn()) instanceof Form)) {
                        return false;
                    }
                    EReference feature = FormWriteGateway.findFormsFeature(owner.eClass());
                    return feature != null && FormWriteGateway.formsOf(owner, feature).stream().anyMatch(
                            entry -> destination.uuid().equals(entry.getUuid()) && copyName.equals(entry.getName()));
                }
            });
            if (!isOurCopy) {
                throw new IllegalStateException("the new form no longer resolves to its original entry");
            }
            if (moduleText != null) {
                r.modulePath = writeNewModule(destination.module(), moduleText);
            }

            boolean exportedOwner = mm.forceExport(dtProject, targetOwnerFqn);
            boolean exportedForm = mm.forceExport(dtProject, r.formFqn);
            FormValidationSupport.afterFormWrite(p, model, targetOwnerFqn + ".Form." + copyName);
            if (!exportedOwner || !exportedForm) {
                throw new IllegalStateException("EDT export was incomplete (owner=" + exportedOwner
                        + ", form=" + exportedForm + ")");
            }
            r.ok = true;
            r.message = "copied " + source.fqn() + " to " + targetOwnerFqn + ".Form." + copyName
                    + " (EDT accepted export of owner .mdo and Form.form"
                    + (r.modulePath != null ? "; Module.bsl written" : "") + ")";
        } catch (Exception ex) {
            r.warning = "the form was created, but the copy is incomplete: " + GatewaySupport.describeCause(ex);
            r.message = "check the destination before retrying: " + targetOwnerFqn + ".Form." + copyName;
        }
        return r;
    }

    private static String extensionPrefix(IBmTransaction tx, MdObject target) {
        if (!RoleRightsGateway.adopted(target)
                || !(tx.getTopObjectByFqn("Configuration") instanceof Configuration configuration)) {
            return null;
        }
        String prefix = configuration.getNamePrefix();
        return prefix == null || prefix.isBlank() ? null : prefix;
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
    static void describeLike(BasicForm source, BasicForm copy) {
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
        if (source.eClass() == copy.eClass() && source instanceof DataProcessorForm sourceProcessor
                && copy instanceof DataProcessorForm copyProcessor) {
            copyProcessor.getExtendedPresentation().putAll(sourceProcessor.getExtendedPresentation());
        }
    }

    private static void note(CopyFormResult r, FormCopy.Result preview) {
        r.remapped = preview.remapped;
        r.leftoverCount = preview.leftovers.size();
        preview.leftovers.stream().limit(LEFTOVERS_SHOWN).forEach(left -> r.leftovers.add(left.toString()));
    }

    static boolean destinationOccupied(IFile file) {
        IPath location = file.getLocation();
        return file.exists()
                || location == null
                || !Files.notExists(location.toFile().toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    static String writeNewModule(IFile file, byte[] text) throws Exception {
        IPath location = file.getLocation();
        if (location == null) {
            throw new IllegalStateException("no local path for module destination: " + file.getFullPath());
        }
        if (file.exists()) {
            throw new FileAlreadyExistsException(location.toOSString());
        }
        FormWriteGateway.createParentFolders(file);
        // CREATE_NEW makes testing for existence and creating the file one filesystem operation.
        Files.write(location.toFile().toPath(), text, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        file.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
        return file.getProjectRelativePath().toString();
    }
}
