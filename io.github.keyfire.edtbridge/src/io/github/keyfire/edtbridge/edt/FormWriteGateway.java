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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.osgi.framework.Bundle;

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
import com._1c.g5.v8.dt.core.platform.IEditingLanguageManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.form.generator.FormFieldInfo;
import com._1c.g5.v8.dt.form.generator.FormType;
import com._1c.g5.v8.dt.form.generator.IFormFieldGenerator;
import com._1c.g5.v8.dt.form.generator.IFormGenerator;
import org.eclipse.emf.common.util.EMap;

import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.AbstractFormAttribute;
import com._1c.g5.v8.dt.form.model.CommandHandler;
import com._1c.g5.v8.dt.form.model.DataItem;
import com._1c.g5.v8.dt.form.model.DataPath;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormAttributeColumn;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormCommandHandlerContainer;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormItemContainer;
import com._1c.g5.v8.dt.form.model.ManagedFormGroupType;
import com._1c.g5.v8.dt.form.model.Table;
import com._1c.g5.v8.dt.form.model.Titled;
import com._1c.g5.v8.dt.form.model.Visible;
import com._1c.g5.v8.dt.form.service.FormIdentifierService;
import com._1c.g5.v8.dt.form.service.item.FormNewItemDescriptor;
import com._1c.g5.v8.dt.form.service.item.IFormItemManagementService;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractForm;
import com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.InterfaceCompatibilityMode;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.wiring.ServiceAccess;
import com.google.inject.Injector;

/**
 * Form-writing side of the EDT model: creating a managed form on an existing metadata object through
 * EDT's own form generator ({@link IFormGenerator}) - the very engine behind the "New form" wizard - so
 * the produced form is born valid instead of being hand-assembled as XML.
 *
 * <p>The flow mirrors EDT's {@code FormNewWizardRelatedModelsFactory}: create the {@code BasicForm}
 * metadata entry, link it into the owner's {@code forms} feature, generate the {@link Form} content,
 * bind the two with {@code setMdForm}, attach the form as a BM top object under the FQN produced by
 * {@link ITopObjectFqnGenerator}, then materialise the form module from
 * {@link IFormGenerator#generateModuleContent}.
 */
public final class FormWriteGateway {

    /** A 1C identifier: letters (Latin or Cyrillic), digits and underscore, not starting with a digit. */
    private static final Pattern IDENT = Pattern.compile("[A-Za-zА-Яа-яЁё_][A-Za-zА-Яа-яЁё0-9_]*");

    /** Russian form-kind aliases accepted in addition to the {@link FormType} enum names. */
    private static final Map<String, FormType> RU_FORM_TYPES = new LinkedHashMap<>();

    /** The form kind EDT offers first for a given owner eClass; anything else falls back to GENERIC. */
    private static final Map<String, FormType> DEFAULT_BY_OWNER = new LinkedHashMap<>();

    static {
        RU_FORM_TYPES.put("произвольная", FormType.GENERIC);
        RU_FORM_TYPES.put("произвольнаяформа", FormType.GENERIC);
        RU_FORM_TYPES.put("форма", FormType.GENERIC);
        RU_FORM_TYPES.put("формаобъекта", FormType.OBJECT);
        RU_FORM_TYPES.put("формаэлемента", FormType.OBJECT);
        RU_FORM_TYPES.put("формадокумента", FormType.OBJECT);
        RU_FORM_TYPES.put("формагруппы", FormType.FOLDER);
        RU_FORM_TYPES.put("формасписка", FormType.LIST);
        RU_FORM_TYPES.put("формавыбора", FormType.CHOICE);
        RU_FORM_TYPES.put("формавыборагруппы", FormType.FOLDER_CHOICE);
        RU_FORM_TYPES.put("формазаписи", FormType.RECORD);
        RU_FORM_TYPES.put("форманаборазаписей", FormType.RECORD_SET);
        RU_FORM_TYPES.put("формаотчета", FormType.REPORT);
        RU_FORM_TYPES.put("форманастроек", FormType.REPORT_SETTINGS);
        RU_FORM_TYPES.put("формаварианта", FormType.REPORT_VARIANT);
        RU_FORM_TYPES.put("формаконстант", FormType.CONSTANTS);
        RU_FORM_TYPES.put("формапоиска", FormType.SEARCH);
        RU_FORM_TYPES.put("формасохранения", FormType.SAVE);
        RU_FORM_TYPES.put("формазагрузки", FormType.LOAD);
        RU_FORM_TYPES.put("формадинамическогосписка", FormType.DYNAMIC_LIST);

        DEFAULT_BY_OWNER.put("Catalog", FormType.OBJECT);
        DEFAULT_BY_OWNER.put("Document", FormType.OBJECT);
        DEFAULT_BY_OWNER.put("ChartOfCharacteristicTypes", FormType.OBJECT);
        DEFAULT_BY_OWNER.put("ChartOfAccounts", FormType.OBJECT);
        DEFAULT_BY_OWNER.put("ChartOfCalculationTypes", FormType.OBJECT);
        DEFAULT_BY_OWNER.put("BusinessProcess", FormType.OBJECT);
        DEFAULT_BY_OWNER.put("Task", FormType.OBJECT);
        DEFAULT_BY_OWNER.put("ExchangePlan", FormType.OBJECT);
        DEFAULT_BY_OWNER.put("InformationRegister", FormType.RECORD);
        DEFAULT_BY_OWNER.put("AccumulationRegister", FormType.LIST);
        DEFAULT_BY_OWNER.put("AccountingRegister", FormType.LIST);
        DEFAULT_BY_OWNER.put("CalculationRegister", FormType.LIST);
        DEFAULT_BY_OWNER.put("Enum", FormType.LIST);
        DEFAULT_BY_OWNER.put("Report", FormType.REPORT);
        DEFAULT_BY_OWNER.put("ExternalReport", FormType.REPORT);
        // A processor's main form is an object form: OBJECT gives it the main "Объект" attribute that
        // reaches the object module, which GENERIC (a blank form) does not.
        DEFAULT_BY_OWNER.put("DataProcessor", FormType.OBJECT);
        DEFAULT_BY_OWNER.put("ExternalDataProcessor", FormType.OBJECT);
    }

    /** Outcome of {@link #addForm}. */
    public static final class AddFormResult {
        public boolean ok;
        public boolean applied;
        public String projectName;
        public String ownerFqn;
        public String ownerType;
        public String name;
        public String formType;
        public String formFqn;
        public String modulePath;
        public boolean nameValid;
        public boolean ownerFound;
        public Boolean nameAvailable;
        public boolean defaultForm;
        public String plan;
        public String warning;
        public String message;
        public final List<String> existingForms = new ArrayList<>();
    }

    /**
     * Create a managed form on an existing metadata object.
     *
     * @param projectName EDT project name
     * @param ownerFqn    owner FQN with an English type prefix, e.g. {@code Catalog.Контрагенты} or
     *                    {@code ExternalDataProcessor.ЗагрузкаКурсовВалют}
     * @param name        new form name (a valid 1C identifier)
     * @param formTypeArg {@link FormType} name or a Russian alias; {@code null} picks the owner default
     * @param synonymRu   Russian synonym, optional
     * @param setDefault  also make it the owner's default form
     * @param columnCount generator column count (EDT's wizard default is 1)
     * @param apply       {@code false} validates and returns the plan; {@code true} performs the write
     */
    public AddFormResult addForm(String projectName, String ownerFqn, String name, String formTypeArg,
            String synonymRu, boolean setDefault, Integer columnCount, boolean apply) {
        AddFormResult r = new AddFormResult();
        r.projectName = projectName;
        r.ownerFqn = ownerFqn;
        r.name = name;
        r.defaultForm = setDefault;

        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        if (name == null || name.isBlank() || !IDENT.matcher(name).matches()) {
            r.message = "name is not a valid 1C identifier: \"" + name
                    + "\" (letters/digits/underscore; must not start with a digit, no spaces)";
            return r;
        }
        r.nameValid = true;
        if (ownerFqn == null || ownerFqn.isBlank()) {
            r.message = "ownerFqn is required, e.g. Catalog.Контрагенты";
            return r;
        }

        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }

        // Stage 1 - validate against the live model: the owner exists, carries a "forms" feature and
        // has no namesake form yet.
        final String[] ownerEClass = {null};
        final String[] formsFeature = {null};
        model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.addForm.validate") {
            @Override
            public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                EObject owner = tx.getTopObjectByFqn(ownerFqn);
                if (!(owner instanceof MdObject)) {
                    return null;
                }
                r.ownerFound = true;
                ownerEClass[0] = owner.eClass().getName();
                r.ownerType = ownerEClass[0];
                EReference feat = findFormsFeature(owner.eClass());
                if (feat == null) {
                    return null;
                }
                formsFeature[0] = feat.getName();
                boolean free = true;
                for (BasicForm bf : formsOf(owner, feat)) {
                    String existing = bf.getName();
                    if (existing != null) {
                        r.existingForms.add(existing);
                        if (existing.equalsIgnoreCase(name)) {
                            free = false;
                        }
                    }
                }
                r.nameAvailable = free;
                return null;
            }
        });

        if (!r.ownerFound) {
            r.message = "owner not found (or not a metadata object): " + ownerFqn;
            return r;
        }
        if (formsFeature[0] == null) {
            r.message = "owner " + r.ownerType + " has no forms feature - it cannot carry forms";
            return r;
        }

        FormType formType = resolveFormType(formTypeArg, ownerEClass[0]);
        if (formType == null) {
            r.message = "unknown formType: \"" + formTypeArg + "\" (use a FormType name such as GENERIC, "
                    + "OBJECT, LIST, CHOICE, RECORD, RECORD_SET, REPORT, or a Russian alias such as "
                    + "ФормаЭлемента / ФормаСписка / ФормаЗаписи)";
            return r;
        }
        r.formType = formType.name();

        r.ok = r.ownerFound && Boolean.TRUE.equals(r.nameAvailable);
        r.plan = "Create form \"" + name + "\" [" + formType.name() + "] on " + ownerFqn
                + (setDefault ? " and make it the default form" : "")
                + (synonymRu != null && !synonymRu.isBlank() ? " (synonym: " + synonymRu + ")" : "");
        r.warning = "a form is part of the configuration's user interface - the generated content follows "
                + "EDT's own wizard, review it before shipping.";
        if (Boolean.FALSE.equals(r.nameAvailable)) {
            r.message = "a form named \"" + name + "\" already exists on " + ownerFqn;
        }

        if (!apply) {
            return r;
        }
        if (!r.ok) {
            r.message = (r.message == null ? "validation failed" : r.message)
                    + " - apply refused (nothing created).";
            return r;
        }

        IFormGenerator generator = formGenerator();
        if (generator == null) {
            r.message = "form generator unavailable (EDT form plugin not active)";
            return r;
        }
        IModelObjectFactory factory = modelObjectFactory();
        if (factory == null) {
            r.message = "model object factory unavailable (EDT md plugin not active)";
            return r;
        }
        ITopObjectFqnGenerator fqnGenerator = topObjectFqnGenerator();
        if (fqnGenerator == null) {
            r.message = "top object FQN generator unavailable (EDT core plugin not active)";
            return r;
        }

        final Version version = GatewaySupport.projectVersion(p);
        final ScriptVariant scriptVariant = scriptVariantOf(p);
        final String languageCode = editingLanguageCode(p);
        final int columns = (columnCount == null || columnCount < 1) ? 1 : columnCount.intValue();
        final FormType type = formType;
        final String featureName = formsFeature[0];
        final String[] createdFqn = {null};

        try {
            model.getGlobalContext().executeImportTask(new AbstractBmTask<Object>("edt-bridge.addForm.apply") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    EObject owner = tx.getTopObjectByFqn(ownerFqn);
                    EReference feat = findFormsFeature(owner.eClass());
                    EClass formClass = feat.getEReferenceType();

                    EObject created = factory.create(formClass, version);
                    if (!(created instanceof BasicForm)) {
                        throw new IllegalStateException("factory.create returned "
                                + (created == null ? "null" : created.eClass().getName()) + " (not a BasicForm)");
                    }
                    BasicForm basicForm = (BasicForm) created;
                    basicForm.setName(name);
                    basicForm.setUuid(UUID.randomUUID());
                    if (synonymRu != null && !synonymRu.isBlank()) {
                        basicForm.getSynonym().put("ru", synonymRu);
                    }

                    // Link it into the owner first: the FQN generator derives the form's path from the
                    // containment, so an orphan BasicForm would produce a wrong (or empty) FQN.
                    @SuppressWarnings("unchecked")
                    List<EObject> forms = (List<EObject>) owner.eGet(feat);
                    forms.add(basicForm);

                    FormFieldInfo fields = generatorFields((MdObject) owner, type, scriptVariant, version);
                    Form form = generator.generateForm((MdObject) owner, basicForm, type, scriptVariant,
                            languageCode, version, fields, Integer.valueOf(columns),
                            interfaceCompatibilityMode(tx));
                    if (form == null) {
                        throw new IllegalStateException("form generator produced no content for type " + type);
                    }
                    form.setMdForm(basicForm);

                    String formFqn = fqnGenerator.generateExternalPropertyFqn(basicForm,
                            MdClassPackage.Literals.BASIC_FORM__FORM);
                    tx.attachTopObject((IBmObject) form, formFqn);
                    createdFqn[0] = formFqn;

                    if (setDefault) {
                        setDefaultForm(owner, basicForm);
                    }
                    factory.fillDefaultReferences(basicForm);
                    return null;
                }
            }, false);
        } catch (RuntimeException ex) {
            r.message = "form creation failed (verify the working tree - a partial form may remain): "
                    + GatewaySupport.describeCause(ex);
            return r;
        }

        r.formFqn = createdFqn[0];

        // The module lives in its own .bsl next to Form.form; EDT computes that path from the model, so
        // ask it rather than guessing the layout.
        try {
            r.modulePath = writeFormModule(p, model, ownerFqn, name, featureName, generator, type,
                    scriptVariant, languageCode, version);
        } catch (Exception ex) {
            r.modulePath = null;
            r.warning = "form created, but its module could not be written: " + GatewaySupport.describeCause(ex);
        }

        IDtProject dtProject = mm.getDtProject(model);
        boolean exportedOwner = dtProject != null && mm.forceExport(dtProject, ownerFqn);
        boolean exportedForm = dtProject != null && r.formFqn != null
                && mm.forceExport(dtProject, r.formFqn);
        r.applied = true;
        r.message = "created form " + ownerFqn + ".Form." + name + " [" + type.name() + "]"
                + ((exportedOwner && exportedForm)
                        ? " (serialized: owner .mdo + Form.form"
                                + (r.modulePath != null ? " + Module.bsl" : "") + ")"
                        : " - WARNING: created in-memory but forceExport incomplete (owner=" + exportedOwner
                                + ", form=" + exportedForm + ")");
        return r;
    }

    /**
     * Materialise the form module from the generator. Returns the workspace-relative path, or
     * {@code null} when the generator supplies no module for this form type.
     */
    private String writeFormModule(IProject project, IBmModel model, String ownerFqn, String formName,
            String featureName, IFormGenerator generator, FormType type, ScriptVariant scriptVariant,
            String languageCode, Version version) throws Exception {
        Supplier<InputStream> content =
                generator.generateModuleContent(type, scriptVariant, languageCode, version);
        if (content == null) {
            return null;
        }
        IProjectFileSystemSupportProvider provider = fileSystemSupportProvider();
        if (provider == null) {
            throw new IllegalStateException("project file-system support unavailable");
        }
        IProjectFileSystemSupport support = provider.getProjectFileSystemSupport(project);
        final IFile[] target = {null};
        model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.addForm.modulePath") {
            @Override
            public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                EObject owner = tx.getTopObjectByFqn(ownerFqn);
                EReference feat = findFormsFeature(owner.eClass());
                for (BasicForm bf : formsOf(owner, feat)) {
                    if (formName.equals(bf.getName())) {
                        target[0] = support.getFile(bf, MdClassPackage.Literals.ABSTRACT_FORM__MODULE);
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
        IProgressMonitor monitor = new NullProgressMonitor();
        createParentFolders(file);
        try (InputStream in = content.get()) {
            if (file.exists()) {
                file.setContents(in, true, true, monitor);
            } else {
                file.create(in, true, monitor);
            }
        }
        return file.getProjectRelativePath().toString();
    }

    /** Create every missing folder above {@code file} (EDT's FileUtil equivalent, kept dependency-free). */
    private static void createParentFolders(IFile file) throws Exception {
        IContainer parent = file.getParent();
        List<IFolder> missing = new ArrayList<>();
        while (parent instanceof IFolder && !parent.exists()) {
            missing.add(0, (IFolder) parent);
            parent = parent.getParent();
        }
        for (IFolder folder : missing) {
            folder.create(true, true, new NullProgressMonitor());
        }
    }

    /**
     * The default field set EDT's wizard pre-selects for this owner and form kind. Best-effort: a null
     * result is legal and simply means "generator decides".
     */
    private FormFieldInfo generatorFields(MdObject owner, FormType type, ScriptVariant scriptVariant,
            Version version) {
        try {
            IFormFieldGenerator fieldGenerator = formFieldGenerator();
            return (fieldGenerator == null) ? null
                    : fieldGenerator.getFormGeneratorFields(owner, type, scriptVariant, version);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** The configuration's interface compatibility mode, defaulting to Taxi for standalone projects. */
    private static InterfaceCompatibilityMode interfaceCompatibilityMode(IBmTransaction tx) {
        try {
            EObject config = tx.getTopObjectByFqn("Configuration");
            if (config instanceof Configuration) {
                InterfaceCompatibilityMode mode = ((Configuration) config).getInterfaceCompatibilityMode();
                if (mode != null) {
                    return mode;
                }
            }
        } catch (RuntimeException ignored) {
            // external object projects have no Configuration - fall through to the default
        }
        return InterfaceCompatibilityMode.TAXI;
    }

    /** Point the owner's {@code defaultForm}-style feature at the new form, when it has exactly one. */
    private static void setDefaultForm(EObject owner, BasicForm form) {
        for (EReference ref : owner.eClass().getEAllReferences()) {
            if (ref.isMany() || ref.isContainment()) {
                continue;
            }
            if (!"defaultForm".equals(ref.getName())) {
                continue;
            }
            EClass type = ref.getEReferenceType();
            if (type != null && type.isSuperTypeOf(form.eClass())) {
                owner.eSet(ref, form);
                return;
            }
        }
    }

    /**
     * The owner's containment list of forms. In EDT's mdclass model a form is owned by its metadata
     * object, so this is a containment reference whose type derives from {@code BasicForm}.
     */
    private static EReference findFormsFeature(EClass ownerClass) {
        EClass basicForm = MdClassPackage.Literals.BASIC_FORM;
        EReference exact = null;
        for (EReference ref : ownerClass.getEAllReferences()) {
            if (!ref.isMany() || !ref.isContainment()) {
                continue;
            }
            EClass type = ref.getEReferenceType();
            if (type == null || !basicForm.isSuperTypeOf(type)) {
                continue;
            }
            if ("forms".equals(ref.getName())) {
                return ref;
            }
            if (exact == null) {
                exact = ref;
            }
        }
        return exact;
    }

    @SuppressWarnings("unchecked")
    private static List<BasicForm> formsOf(EObject owner, EReference feature) {
        Object value = owner.eGet(feature);
        return (value instanceof List) ? (List<BasicForm>) value : new ArrayList<>();
    }

    /** Map the caller's form-kind argument onto {@link FormType}, or the owner default when absent. */
    private static FormType resolveFormType(String arg, String ownerEClass) {
        if (arg == null || arg.isBlank()) {
            FormType byOwner = DEFAULT_BY_OWNER.get(ownerEClass);
            return (byOwner != null) ? byOwner : FormType.GENERIC;
        }
        String key = arg.trim();
        for (FormType t : FormType.values()) {
            if (t.name().equalsIgnoreCase(key) || t.name().replace("_", "").equalsIgnoreCase(key)) {
                return t;
            }
        }
        return RU_FORM_TYPES.get(key.toLowerCase(Locale.ROOT).replace(" ", ""));
    }

    private static ScriptVariant scriptVariantOf(IProject project) {
        try {
            IV8ProjectManager pm = ServiceAccess.get(IV8ProjectManager.class);
            IV8Project v8 = (pm == null) ? null : pm.getProject(project);
            ScriptVariant variant = (v8 == null) ? null : v8.getScriptVariant();
            if (variant != null) {
                return variant;
            }
        } catch (RuntimeException ignored) {
            // fall through to the Russian default used across 1C projects
        }
        return ScriptVariant.RUSSIAN;
    }

    private static String editingLanguageCode(IProject project) {
        try {
            IEditingLanguageManager mgr = coreService(IEditingLanguageManager.class);
            String code = (mgr == null) ? null : mgr.getEditingLanguageCode(project);
            if (code != null && !code.isBlank()) {
                return code;
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return "ru";
    }

    private static IProjectFileSystemSupportProvider fileSystemSupportProvider() {
        return coreService(IProjectFileSystemSupportProvider.class);
    }

    private static ITopObjectFqnGenerator topObjectFqnGenerator() {
        return coreService(ITopObjectFqnGenerator.class);
    }

    /**
     * A service of EDT's core plugin: registered ones come from {@code ServiceAccess}, plain Guice
     * bindings only from the plugin's own injector - try both, in that order.
     */
    private static <T> T coreService(Class<T> type) {
        try {
            T viaService = ServiceAccess.get(type);
            if (viaService != null) {
                return viaService;
            }
        } catch (RuntimeException ignored) {
            // not a registered service - fall back to the injector
        }
        Injector injector = pluginInjector("com._1c.g5.v8.dt.core", "com._1c.g5.v8.dt.internal.core.V8CorePlugin");
        try {
            return (injector == null) ? null : injector.getInstance(type);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static IFormGenerator formGenerator() {
        Injector injector = formInjector();
        try {
            return (injector == null) ? null : injector.getInstance(IFormGenerator.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static IFormFieldGenerator formFieldGenerator() {
        Injector injector = formInjector();
        try {
            return (injector == null) ? null : injector.getInstance(IFormFieldGenerator.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Injector formInjector() {
        return pluginInjector("com._1c.g5.v8.dt.form", "com._1c.g5.v8.dt.internal.form.FormPlugin");
    }

    private static IModelObjectFactory modelObjectFactory() {
        Injector injector = pluginInjector("com._1c.g5.v8.dt.md", "com._1c.g5.v8.dt.md.MdPlugin");
        try {
            return (injector == null) ? null : injector.getInstance(IModelObjectFactory.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A bundle's Guice injector, reached through its plugin class by reflection. The plugin classes sit
     * in internal packages, so we never compile against them - the same containment rule the BSL/QL
     * language injectors follow.
     */
    private static Injector pluginInjector(String bundleId, String pluginClass) {
        try {
            Bundle bundle = Platform.getBundle(bundleId);
            if (bundle == null) {
                return null;
            }
            Class<?> cls = bundle.loadClass(pluginClass);
            Object plugin = cls.getMethod("getDefault").invoke(null);
            if (plugin == null) {
                return null;
            }
            return (Injector) cls.getMethod("getInjector").invoke(plugin);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Form members: attributes and commands (add / modify / remove) ---------------------------

    /** Outcome shared by the six form-member operations. */
    public static final class FormMemberResult {
        public boolean ok;
        public boolean applied;
        public String projectName;
        public String formFqn;
        public String member;          // attribute | command
        public String name;
        public String columnOf;        // set when the attribute is a column of a value-table attribute
        public String type;            // attributes: the resolved value type
        public Integer id;             // id the form assigned
        public boolean formFound;
        public boolean ownerMissing;   // columnOf named an attribute the form does not have
        public boolean nameValid;
        public Boolean nameAvailable;  // add: the name is free
        public Boolean present;        // modify / remove: the member exists
        public String handler;         // commands
        public String modulePath;      // commands: where the handler stub landed
        public String plan;
        public String warning;
        public String message;
        public final List<String> members = new ArrayList<>();     // what the form carries now
        public final List<String> boundItems = new ArrayList<>();  // remove: items still bound to it
    }

    /**
     * Add an attribute to a managed form.
     *
     * @param formFqn  form FQN, e.g. {@code Catalog.Контрагенты.Form.ФормаЭлемента}
     * @param typeSpec value type, e.g. {@code Строка(150)}, {@code Число(15, 2)},
     *                 {@code СправочникСсылка.Контрагенты}, or a comma-separated composite
     */
    public FormMemberResult addFormAttribute(String projectName, String formFqn, String name,
            String typeSpec, String titleRu, String columnOf, boolean apply) {
        FormMemberResult r = newResult(projectName, formFqn, name, "attribute");
        r.columnOf = columnOf;
        IProject p = validateBasics(r, projectName, name);
        if (p == null) {
            return r;
        }
        if (typeSpec == null || typeSpec.isBlank()) {
            r.message = "type is required, e.g. Строка(150) or СправочникСсылка.Контрагенты";
            return r;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }
        inspectForm(model, r, formFqn, name, true, columnOf);
        if (!r.formFound) {
            r.message = "form not found: " + formFqn;
            return r;
        }
        if (r.ownerMissing) {
            r.message = "the form has no attribute \"" + columnOf + "\" to hold columns";
            return r;
        }
        final boolean asColumn = columnOf != null && !columnOf.isBlank();
        String typeIssue = typeProblem(model, typeSpec, GatewaySupport.projectVersion(p));
        r.ok = Boolean.TRUE.equals(r.nameAvailable) && typeIssue == null;
        r.type = typeSpec;
        r.plan = "Add " + (asColumn ? "column \"" + name + "\" of " + columnOf : "attribute \"" + name + "\"")
                + " : " + typeSpec + " to " + formFqn;
        if (!Boolean.TRUE.equals(r.nameAvailable)) {
            r.message = (asColumn ? "the attribute \"" + columnOf + "\" already has a column named \""
                    : "the form already has an attribute named \"") + name + "\"";
        } else if (typeIssue != null) {
            r.message = typeIssue;
        }
        if (!apply) {
            return r;
        }
        if (!r.ok) {
            r.message = r.message + " - apply refused (nothing written).";
            return r;
        }
        final Version version = GatewaySupport.projectVersion(p);
        final MetadataWriteGateway types = new MetadataWriteGateway();
        final String[] failure = {null};
        try {
            model.execute(new AbstractBmTask<Object>("edt-bridge.addFormAttribute.apply") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    Form form = resolveForm(tx, formFqn);
                    if (form == null) {
                        failure[0] = "the form disappeared between validation and apply";
                        return null;
                    }
                    TypeDescription td = types.typeDescriptionFor(tx, typeSpec, version, true);
                    if (td == null) {
                        failure[0] = "type does not parse: \"" + typeSpec + "\"";
                        return null;
                    }
                    AbstractFormAttribute attribute;
                    if (asColumn) {
                        FormAttribute owner = findAttribute(form, columnOf);
                        if (owner == null) {
                            failure[0] = "the owning attribute disappeared: " + columnOf;
                            return null;
                        }
                        FormAttributeColumn column = FormFactory.eINSTANCE.createFormAttributeColumn();
                        owner.getColumns().add(column);
                        attribute = column;
                    } else {
                        FormAttribute plain = FormFactory.eINSTANCE.createFormAttribute();
                        form.getAttributes().add(plain);
                        attribute = plain;
                    }
                    attribute.setName(name);
                    attribute.setId(FormIdentifierService.INSTANCE.getNextAttributeId(form));
                    attribute.setValueType(td);
                    attribute.setView(adjustable(true));
                    attribute.setEdit(adjustable(true));
                    if (titleRu != null && !titleRu.isBlank()) {
                        attribute.getTitle().put("ru", titleRu);
                    }
                    r.id = Integer.valueOf(attribute.getId());
                    return null;
                }
            });
        } catch (RuntimeException ex) {
            r.message = "apply failed (nothing committed): " + GatewaySupport.describeCause(ex);
            return r;
        }
        if (failure[0] != null) {
            r.message = "apply failed (nothing committed): " + failure[0];
            return r;
        }
        finish(r, mm, model, formFqn, "added attribute \"" + name + "\" to ");
        return r;
    }

    /**
     * Change an existing form attribute's value type, title or main flag. Renaming is deliberately not
     * offered: item bindings address an attribute by name, so a rename belongs to the refactoring
     * engine rather than a property edit.
     */
    public FormMemberResult modifyFormAttribute(String projectName, String formFqn, String name,
            String typeSpec, String titleRu, Boolean main, String columnOf, boolean apply) {
        FormMemberResult r = newResult(projectName, formFqn, name, "attribute");
        r.columnOf = columnOf;
        IProject p = validateBasics(r, projectName, name);
        if (p == null) {
            return r;
        }
        boolean wantsType = typeSpec != null && !typeSpec.isBlank();
        boolean wantsTitle = titleRu != null;
        if (!wantsType && !wantsTitle && main == null) {
            r.message = "nothing to change - pass at least one of type, titleRu, main";
            return r;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }
        inspectForm(model, r, formFqn, name, true, columnOf);
        if (!r.formFound) {
            r.message = "form not found: " + formFqn;
            return r;
        }
        if (r.ownerMissing) {
            r.message = "the form has no attribute \"" + columnOf + "\" to hold columns";
            return r;
        }
        if (main != null && columnOf != null && !columnOf.isBlank()) {
            r.message = "main applies to a form attribute, not to a column";
            return r;
        }
        r.present = Boolean.valueOf(!Boolean.TRUE.equals(r.nameAvailable));
        String typeIssue = wantsType ? typeProblem(model, typeSpec, GatewaySupport.projectVersion(p)) : null;
        r.ok = Boolean.TRUE.equals(r.present) && typeIssue == null;
        r.type = wantsType ? typeSpec : null;
        StringBuilder what = new StringBuilder();
        if (wantsType) {
            what.append(" type -> ").append(typeSpec);
        }
        if (wantsTitle) {
            what.append(" title -> \"").append(titleRu).append('"');
        }
        if (main != null) {
            what.append(" main -> ").append(main);
        }
        r.plan = "Change attribute \"" + name + "\" of " + formFqn + ":" + what;
        if (!Boolean.TRUE.equals(r.present)) {
            r.message = "the form has no attribute named \"" + name + "\"";
        } else if (typeIssue != null) {
            r.message = typeIssue;
        }
        if (!apply) {
            return r;
        }
        if (!r.ok) {
            r.message = r.message + " - apply refused (nothing written).";
            return r;
        }
        final Version version = GatewaySupport.projectVersion(p);
        final MetadataWriteGateway types = new MetadataWriteGateway();
        final String[] failure = {null};
        try {
            model.execute(new AbstractBmTask<Object>("edt-bridge.modifyFormAttribute.apply") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    Form form = resolveForm(tx, formFqn);
                    AbstractFormAttribute attribute = (form == null) ? null
                            : findMember(form, columnOf, name);
                    if (attribute == null) {
                        failure[0] = "the attribute disappeared between validation and apply";
                        return null;
                    }
                    if (wantsType) {
                        TypeDescription td = types.typeDescriptionFor(tx, typeSpec, version, true);
                        if (td == null) {
                            failure[0] = "type does not parse: \"" + typeSpec + "\"";
                            return null;
                        }
                        attribute.setValueType(td);
                    }
                    if (wantsTitle) {
                        if (titleRu.isBlank()) {
                            attribute.getTitle().removeKey("ru");
                        } else {
                            attribute.getTitle().put("ru", titleRu);
                        }
                    }
                    if (main != null && attribute instanceof FormAttribute) {
                        ((FormAttribute) attribute).setMain(main.booleanValue());
                    }
                    r.id = Integer.valueOf(attribute.getId());
                    return null;
                }
            });
        } catch (RuntimeException ex) {
            r.message = "apply failed (nothing committed): " + GatewaySupport.describeCause(ex);
            return r;
        }
        if (failure[0] != null) {
            r.message = "apply failed (nothing committed): " + failure[0];
            return r;
        }
        finish(r, mm, model, formFqn, "changed attribute \"" + name + "\" of ");
        return r;
    }

    /**
     * Remove a form attribute. Items bound to it are reported first: dropping an attribute that a field
     * still addresses leaves the form broken, so that needs {@code force}.
     */
    public FormMemberResult removeFormAttribute(String projectName, String formFqn, String name,
            String columnOf, boolean force, boolean apply) {
        FormMemberResult r = newResult(projectName, formFqn, name, "attribute");
        r.columnOf = columnOf;
        IProject p = validateBasics(r, projectName, name);
        if (p == null) {
            return r;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }
        inspectForm(model, r, formFqn, name, true, columnOf);
        if (!r.formFound) {
            r.message = "form not found: " + formFqn;
            return r;
        }
        if (r.ownerMissing) {
            r.message = "the form has no attribute \"" + columnOf + "\" to hold columns";
            return r;
        }
        r.present = Boolean.valueOf(!Boolean.TRUE.equals(r.nameAvailable));
        r.ok = Boolean.TRUE.equals(r.present);
        r.plan = "Remove " + (columnOf == null || columnOf.isBlank() ? "attribute \"" + name + "\""
                : "column \"" + name + "\" of " + columnOf) + " from " + formFqn
                + (r.boundItems.isEmpty() ? "" : " (" + r.boundItems.size() + " item(s) bound to it)");
        if (!r.ok) {
            r.message = "the form has no attribute named \"" + name + "\"";
        } else if (!r.boundItems.isEmpty()) {
            r.warning = "items still address this attribute: " + String.join(", ", r.boundItems)
                    + " - removing it leaves them dangling; force is required.";
        }
        if (!apply) {
            return r;
        }
        if (!r.ok) {
            r.message = r.message + " - apply refused (nothing removed).";
            return r;
        }
        if (!force) {
            r.message = "removing a form attribute is destructive"
                    + (r.boundItems.isEmpty() ? "" : " and " + r.boundItems.size() + " item(s) address it")
                    + " - apply refused; pass force=true to perform it.";
            return r;
        }
        final String[] failure = {null};
        try {
            model.execute(new AbstractBmTask<Object>("edt-bridge.removeFormAttribute.apply") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    Form form = resolveForm(tx, formFqn);
                    AbstractFormAttribute attribute = (form == null) ? null
                            : findMember(form, columnOf, name);
                    if (attribute == null) {
                        failure[0] = "the attribute disappeared between validation and apply";
                        return null;
                    }
                    if (attribute instanceof FormAttributeColumn) {
                        findAttribute(form, columnOf).getColumns().remove(attribute);
                    } else {
                        form.getAttributes().remove(attribute);
                    }
                    return null;
                }
            });
        } catch (RuntimeException ex) {
            r.message = "apply failed (nothing committed): " + GatewaySupport.describeCause(ex);
            return r;
        }
        if (failure[0] != null) {
            r.message = "apply failed (nothing committed): " + failure[0];
            return r;
        }
        finish(r, mm, model, formFqn, "removed attribute \"" + name + "\" from ");
        return r;
    }

    /**
     * Add a command to a managed form. The command's action points at a handler procedure in the form
     * module; {@code createHandler} also writes that procedure's stub, creating the module when the
     * form has none yet.
     */
    public FormMemberResult addFormCommand(String projectName, String formFqn, String name,
            String titleRu, String toolTipRu, String handlerName, boolean createHandler, boolean apply) {
        FormMemberResult r = newResult(projectName, formFqn, name, "command");
        IProject p = validateBasics(r, projectName, name);
        if (p == null) {
            return r;
        }
        final String handler = (handlerName == null || handlerName.isBlank()) ? name : handlerName;
        if (!IDENT.matcher(handler).matches()) {
            r.message = "handler is not a valid 1C identifier: \"" + handler + "\"";
            return r;
        }
        r.handler = handler;
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }
        inspectForm(model, r, formFqn, name, false, null);
        if (!r.formFound) {
            r.message = "form not found: " + formFqn;
            return r;
        }
        r.ok = Boolean.TRUE.equals(r.nameAvailable);
        r.plan = "Add command \"" + name + "\" to " + formFqn + " with handler " + handler + "()"
                + (createHandler ? " and write its stub into the form module" : "");
        if (!r.ok) {
            r.message = "the form already has a command named \"" + name + "\"";
        }
        if (!apply) {
            return r;
        }
        if (!r.ok) {
            r.message = r.message + " - apply refused (nothing written).";
            return r;
        }
        final String[] failure = {null};
        try {
            model.execute(new AbstractBmTask<Object>("edt-bridge.addFormCommand.apply") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    Form form = resolveForm(tx, formFqn);
                    if (form == null) {
                        failure[0] = "the form disappeared between validation and apply";
                        return null;
                    }
                    FormCommand command = FormFactory.eINSTANCE.createFormCommand();
                    command.setName(name);
                    command.setId(FormIdentifierService.INSTANCE.getNextCommandId(form));
                    if (titleRu != null && !titleRu.isBlank()) {
                        command.getTitle().put("ru", titleRu);
                    }
                    if (toolTipRu != null && !toolTipRu.isBlank()) {
                        command.getToolTip().put("ru", toolTipRu);
                    }
                    command.setUse(adjustable(true));
                    FormCommandHandlerContainer action =
                            FormFactory.eINSTANCE.createFormCommandHandlerContainer();
                    CommandHandler commandHandler = FormFactory.eINSTANCE.createCommandHandler();
                    commandHandler.setName(handler);
                    action.setHandler(commandHandler);
                    command.setAction(action);
                    form.getFormCommands().add(command);
                    r.id = Integer.valueOf(command.getId());
                    return null;
                }
            });
        } catch (RuntimeException ex) {
            r.message = "apply failed (nothing committed): " + GatewaySupport.describeCause(ex);
            return r;
        }
        if (failure[0] != null) {
            r.message = "apply failed (nothing committed): " + failure[0];
            return r;
        }
        if (createHandler) {
            try {
                r.modulePath = writeHandlerStub(p, model, formFqn, handler);
            } catch (Exception ex) {
                r.warning = "command created, but its handler stub could not be written: "
                        + GatewaySupport.describeCause(ex);
            }
        }
        finish(r, mm, model, formFqn, "added command \"" + name + "\" to ");
        return r;
    }

    /** Change an existing form command's title, tooltip or handler name. */
    public FormMemberResult modifyFormCommand(String projectName, String formFqn, String name,
            String titleRu, String toolTipRu, String handlerName, boolean apply) {
        FormMemberResult r = newResult(projectName, formFqn, name, "command");
        IProject p = validateBasics(r, projectName, name);
        if (p == null) {
            return r;
        }
        boolean wantsTitle = titleRu != null;
        boolean wantsToolTip = toolTipRu != null;
        boolean wantsHandler = handlerName != null && !handlerName.isBlank();
        if (!wantsTitle && !wantsToolTip && !wantsHandler) {
            r.message = "nothing to change - pass at least one of titleRu, toolTipRu, handler";
            return r;
        }
        if (wantsHandler && !IDENT.matcher(handlerName).matches()) {
            r.message = "handler is not a valid 1C identifier: \"" + handlerName + "\"";
            return r;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }
        inspectForm(model, r, formFqn, name, false, null);
        if (!r.formFound) {
            r.message = "form not found: " + formFqn;
            return r;
        }
        r.present = Boolean.valueOf(!Boolean.TRUE.equals(r.nameAvailable));
        r.ok = Boolean.TRUE.equals(r.present);
        if (wantsHandler) {
            r.handler = handlerName;
        }
        StringBuilder what = new StringBuilder();
        if (wantsTitle) {
            what.append(" title -> \"").append(titleRu).append('"');
        }
        if (wantsToolTip) {
            what.append(" tooltip -> \"").append(toolTipRu).append('"');
        }
        if (wantsHandler) {
            what.append(" handler -> ").append(handlerName).append("()");
        }
        r.plan = "Change command \"" + name + "\" of " + formFqn + ":" + what;
        if (!r.ok) {
            r.message = "the form has no command named \"" + name + "\"";
        } else if (wantsHandler) {
            r.warning = "the handler procedure itself is not renamed - point the command at a procedure "
                    + "that exists in the form module, or add it with edt_add_method.";
        }
        if (!apply) {
            return r;
        }
        if (!r.ok) {
            r.message = r.message + " - apply refused (nothing written).";
            return r;
        }
        final String[] failure = {null};
        try {
            model.execute(new AbstractBmTask<Object>("edt-bridge.modifyFormCommand.apply") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    Form form = resolveForm(tx, formFqn);
                    FormCommand command = (form == null) ? null : findCommand(form, name);
                    if (command == null) {
                        failure[0] = "the command disappeared between validation and apply";
                        return null;
                    }
                    if (wantsTitle) {
                        if (titleRu.isBlank()) {
                            command.getTitle().removeKey("ru");
                        } else {
                            command.getTitle().put("ru", titleRu);
                        }
                    }
                    if (wantsToolTip) {
                        if (toolTipRu.isBlank()) {
                            command.getToolTip().removeKey("ru");
                        } else {
                            command.getToolTip().put("ru", toolTipRu);
                        }
                    }
                    if (wantsHandler) {
                        FormCommandHandlerContainer action =
                                (command.getAction() instanceof FormCommandHandlerContainer)
                                        ? (FormCommandHandlerContainer) command.getAction()
                                        : FormFactory.eINSTANCE.createFormCommandHandlerContainer();
                        CommandHandler commandHandler = action.getHandler();
                        if (commandHandler == null) {
                            commandHandler = FormFactory.eINSTANCE.createCommandHandler();
                            action.setHandler(commandHandler);
                        }
                        commandHandler.setName(handlerName);
                        command.setAction(action);
                    }
                    r.id = Integer.valueOf(command.getId());
                    return null;
                }
            });
        } catch (RuntimeException ex) {
            r.message = "apply failed (nothing committed): " + GatewaySupport.describeCause(ex);
            return r;
        }
        if (failure[0] != null) {
            r.message = "apply failed (nothing committed): " + failure[0];
            return r;
        }
        finish(r, mm, model, formFqn, "changed command \"" + name + "\" of ");
        return r;
    }

    /** Remove a form command. Buttons wired to it are reported first; dropping it needs {@code force}. */
    public FormMemberResult removeFormCommand(String projectName, String formFqn, String name,
            boolean force, boolean apply) {
        FormMemberResult r = newResult(projectName, formFqn, name, "command");
        IProject p = validateBasics(r, projectName, name);
        if (p == null) {
            return r;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }
        inspectForm(model, r, formFqn, name, false, null);
        if (!r.formFound) {
            r.message = "form not found: " + formFqn;
            return r;
        }
        r.present = Boolean.valueOf(!Boolean.TRUE.equals(r.nameAvailable));
        r.ok = Boolean.TRUE.equals(r.present);
        r.plan = "Remove command \"" + name + "\" from " + formFqn
                + (r.boundItems.isEmpty() ? "" : " (" + r.boundItems.size() + " button(s) wired to it)");
        if (!r.ok) {
            r.message = "the form has no command named \"" + name + "\"";
        } else if (!r.boundItems.isEmpty()) {
            r.warning = "buttons still reference this command: " + String.join(", ", r.boundItems)
                    + " - removing it leaves them dangling; force is required.";
        }
        if (!apply) {
            return r;
        }
        if (!r.ok) {
            r.message = r.message + " - apply refused (nothing removed).";
            return r;
        }
        if (!force) {
            r.message = "removing a form command is destructive"
                    + (r.boundItems.isEmpty() ? "" : " and " + r.boundItems.size() + " button(s) use it")
                    + " - apply refused; pass force=true to perform it.";
            return r;
        }
        final String[] failure = {null};
        try {
            model.execute(new AbstractBmTask<Object>("edt-bridge.removeFormCommand.apply") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    Form form = resolveForm(tx, formFqn);
                    FormCommand command = (form == null) ? null : findCommand(form, name);
                    if (command == null) {
                        failure[0] = "the command disappeared between validation and apply";
                        return null;
                    }
                    form.getFormCommands().remove(command);
                    return null;
                }
            });
        } catch (RuntimeException ex) {
            r.message = "apply failed (nothing committed): " + GatewaySupport.describeCause(ex);
            return r;
        }
        if (failure[0] != null) {
            r.message = "apply failed (nothing committed): " + failure[0];
            return r;
        }
        finish(r, mm, model, formFqn, "removed command \"" + name + "\" from ");
        return r;
    }

    // ---- shared plumbing for the form-member operations ------------------------------------------

    private static FormMemberResult newResult(String projectName, String formFqn, String name,
            String member) {
        FormMemberResult r = new FormMemberResult();
        r.projectName = projectName;
        r.formFqn = formFqn;
        r.name = name;
        r.member = member;
        return r;
    }

    /** Common argument checks; returns the project when they pass, {@code null} (with a message) when not. */
    private static IProject validateBasics(FormMemberResult r, String projectName, String name) {
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return null;
        }
        if (name == null || name.isBlank() || !IDENT.matcher(name).matches()) {
            r.message = "name is not a valid 1C identifier: \"" + name
                    + "\" (letters/digits/underscore; must not start with a digit, no spaces)";
            return null;
        }
        r.nameValid = true;
        if (r.formFqn == null || r.formFqn.isBlank()) {
            r.message = "formFqn is required, e.g. Catalog.Контрагенты.Form.ФормаЭлемента";
            return null;
        }
        return p;
    }

    /**
     * Read the form once: whether it resolves, what members it carries, whether the name is free, and
     * which items are bound to that name.
     */
    private void inspectForm(IBmModel model, FormMemberResult r, String formFqn, String name,
            boolean attributes, String columnOf) {
        model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.formMember.inspect") {
            @Override
            public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                Form form = resolveForm(tx, formFqn);
                if (form == null) {
                    return null;
                }
                r.formFound = true;
                boolean free = true;
                if (attributes) {
                    boolean asColumn = columnOf != null && !columnOf.isBlank();
                    FormAttribute owner = asColumn ? findAttribute(form, columnOf) : null;
                    if (asColumn && owner == null) {
                        r.ownerMissing = true;
                        return null;
                    }
                    for (AbstractFormAttribute a : asColumn ? owner.getColumns() : form.getAttributes()) {
                        if (a.getName() != null) {
                            r.members.add(a.getName());
                            if (a.getName().equalsIgnoreCase(name)) {
                                free = false;
                            }
                        }
                    }
                    collectItemsBoundToAttribute(form.getItems(),
                            asColumn ? new String[] {columnOf, name} : new String[] {name}, r.boundItems);
                } else {
                    for (FormCommand c : form.getFormCommands()) {
                        if (c.getName() != null) {
                            r.members.add(c.getName());
                            if (c.getName().equalsIgnoreCase(name)) {
                                free = false;
                            }
                        }
                    }
                    collectButtonsUsingCommand(form.getItems(), name, r.boundItems);
                    // fall through to the shared availability assignment below
                }
                r.nameAvailable = Boolean.valueOf(free);
                return null;
            }
        });
    }

    /**
     * Build the type in a read transaction so an unusable spec fails the dry-run rather than the apply.
     * Returns {@code null} when the type resolves, otherwise the reason.
     */
    private String typeProblem(IBmModel model, String typeSpec, Version version) {
        if (typeSpec == null || typeSpec.isBlank()) {
            return null;
        }
        final MetadataWriteGateway types = new MetadataWriteGateway();
        return model.executeReadonlyTask(new AbstractBmTask<String>("edt-bridge.formMember.checkType") {
            @Override
            public String execute(IBmTransaction tx, IProgressMonitor monitor) {
                try {
                    return (types.typeDescriptionFor(tx, typeSpec, version, true) == null)
                            ? "type does not parse: \"" + typeSpec + "\"" : null;
                } catch (RuntimeException e) {
                    return "type cannot be resolved: " + GatewaySupport.describeCause(e);
                }
            }
        });
    }

    /** Serialize the changed form and fill in the success message. */
    private static void finish(FormMemberResult r, IBmModelManager mm, IBmModel model, String formFqn,
            String what) {
        String topFqn = formTopFqn(model, formFqn);
        IDtProject dtProject = mm.getDtProject(model);
        boolean exported = dtProject != null && topFqn != null && mm.forceExport(dtProject, topFqn);
        r.applied = true;
        r.message = what + formFqn
                + (exported ? " (serialized to Form.form)"
                        : " - WARNING: committed in-memory but forceExport did not persist (Form.form "
                                + "unchanged)");
    }

    /** The BM FQN of the form content object, which is what {@code forceExport} serializes. */
    private static String formTopFqn(IBmModel model, String formFqn) {
        return model.executeReadonlyTask(new AbstractBmTask<String>("edt-bridge.formMember.topFqn") {
            @Override
            public String execute(IBmTransaction tx, IProgressMonitor monitor) {
                Form form = resolveForm(tx, formFqn);
                return (form instanceof IBmObject) ? ((IBmObject) form).bmGetFqn() : null;
            }
        });
    }

    /** Resolve a form by FQN, accepting both the content object and the {@code Owner.Form.Name} form. */
    private static Form resolveForm(IBmTransaction tx, String formFqn) {
        Form form = asFormContent(tx.getTopObjectByFqn(formFqn));
        if (form != null) {
            return form;
        }
        int idx = formFqn.lastIndexOf(".Form.");
        if (idx <= 0) {
            return null;
        }
        EObject owner = tx.getTopObjectByFqn(formFqn.substring(0, idx));
        if (owner == null) {
            return null;
        }
        EReference feature = findFormsFeature(owner.eClass());
        if (feature == null) {
            return null;
        }
        String formName = formFqn.substring(idx + ".Form.".length());
        for (BasicForm bf : formsOf(owner, feature)) {
            if (formName.equals(bf.getName())) {
                return asFormContent(bf);
            }
        }
        return null;
    }

    /** The editable content object: a {@code BasicForm} only carries it via {@code AbstractForm}. */
    private static Form asFormContent(EObject candidate) {
        if (candidate instanceof Form) {
            return (Form) candidate;
        }
        if (candidate instanceof BasicForm) {
            AbstractForm content = ((BasicForm) candidate).getForm();
            if (content instanceof Form) {
                return (Form) content;
            }
        }
        return null;
    }

    private static FormAttribute findAttribute(Form form, String name) {
        for (FormAttribute a : form.getAttributes()) {
            if (name.equalsIgnoreCase(a.getName())) {
                return a;
            }
        }
        return null;
    }

    private static FormCommand findCommand(Form form, String name) {
        for (FormCommand c : form.getFormCommands()) {
            if (name.equalsIgnoreCase(c.getName())) {
                return c;
            }
        }
        return null;
    }

    /**
     * Item names whose data path starts with the given segments, walked through nested containers.
     * One segment matches an attribute and everything under it; two match a single column.
     */
    private static void collectItemsBoundToAttribute(List<FormItem> items, String[] path,
            List<String> out) {
        for (FormItem item : items) {
            if (item instanceof DataItem) {
                AbstractDataPath dp = ((DataItem) item).getDataPath();
                List<String> segments = (dp == null) ? null : dp.getSegments();
                if (segments != null && segments.size() >= path.length && item.getName() != null) {
                    boolean match = true;
                    for (int i = 0; i < path.length; i++) {
                        if (!path[i].equalsIgnoreCase(segments.get(i))) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        out.add(item.getName());
                    }
                }
            }
            if (item instanceof FormItemContainer) {
                collectItemsBoundToAttribute(((FormItemContainer) item).getItems(), path, out);
            }
        }
    }

    /**
     * The attribute list an operation works on: the form's own attributes, or the columns of the
     * value-table attribute named by {@code columnOf}. Returns {@code null} when that owner is missing.
     */
    private static List<? extends AbstractFormAttribute> attributeScope(Form form, String columnOf) {
        if (columnOf == null || columnOf.isBlank()) {
            return form.getAttributes();
        }
        FormAttribute owner = findAttribute(form, columnOf);
        return (owner == null) ? null : owner.getColumns();
    }

    /** Find an attribute or a column by name within the requested scope. */
    private static AbstractFormAttribute findMember(Form form, String columnOf, String name) {
        List<? extends AbstractFormAttribute> scope = attributeScope(form, columnOf);
        if (scope == null) {
            return null;
        }
        for (AbstractFormAttribute a : scope) {
            if (name.equalsIgnoreCase(a.getName())) {
                return a;
            }
        }
        return null;
    }

    /** Item names referencing the given form command, walked through nested containers. */
    private static void collectButtonsUsingCommand(List<FormItem> items, String commandName,
            List<String> out) {
        for (FormItem item : items) {
            for (EReference ref : item.eClass().getEAllReferences()) {
                if (ref.isMany() || ref.isContainment()) {
                    continue;
                }
                Object value = item.eGet(ref, false);
                if (value instanceof FormCommand && commandName.equalsIgnoreCase(
                        ((FormCommand) value).getName()) && item.getName() != null) {
                    out.add(item.getName());
                    break;
                }
            }
            if (item instanceof FormItemContainer) {
                collectButtonsUsingCommand(((FormItemContainer) item).getItems(), commandName, out);
            }
        }
    }

    private static AdjustableBoolean adjustable(boolean value) {
        AdjustableBoolean flag = MdClassFactory.eINSTANCE.createAdjustableBoolean();
        flag.setCommon(value);
        return flag;
    }

    /**
     * Append a client-side handler stub to the form module, creating the module file when the form has
     * none. Returns the workspace-relative path, or {@code null} when the handler is already there.
     */
    private String writeHandlerStub(IProject project, IBmModel model, String formFqn, String handler)
            throws Exception {
        IProjectFileSystemSupportProvider provider = fileSystemSupportProvider();
        if (provider == null) {
            throw new IllegalStateException("project file-system support unavailable");
        }
        IProjectFileSystemSupport support = provider.getProjectFileSystemSupport(project);
        final IFile[] target = {null};
        model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.formMember.modulePath") {
            @Override
            public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                Form form = resolveForm(tx, formFqn);
                BasicForm mdForm = (form == null) ? null : form.getMdForm();
                if (mdForm != null) {
                    target[0] = support.getFile(mdForm, MdClassPackage.Literals.ABSTRACT_FORM__MODULE);
                }
                return null;
            }
        });
        IFile file = target[0];
        if (file == null) {
            throw new IllegalStateException("could not resolve the form module file");
        }
        boolean russian = scriptVariantOf(project) == ScriptVariant.RUSSIAN;
        String existing = file.exists() ? readFile(file) : "";
        if (existing.contains(russian ? "Процедура " + handler : "Procedure " + handler)) {
                return file.getProjectRelativePath().toString();
        }
        String stub = russian
                ? "&НаКлиенте\nПроцедура " + handler + "(Команда)\n\nКонецПроцедуры\n"
                : "&AtClient\nProcedure " + handler + "(Command)\n\nEndProcedure\n";
        String body = existing.isBlank() ? stub
                : existing + (existing.endsWith("\n") ? "\n" : "\n\n") + stub;
        IProgressMonitor monitor = new NullProgressMonitor();
        createParentFolders(file);
        java.io.InputStream in =
                new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (file.exists()) {
            file.setContents(in, true, true, monitor);
        } else {
            file.create(in, true, monitor);
        }
        return file.getProjectRelativePath().toString();
    }

    private static String readFile(IFile file) throws Exception {
        try (java.io.InputStream in = file.getContents()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }


    // ---- Form items: fields, tables, buttons, groups ---------------------------------------------

    /** Group kinds accepted by {@code edt_add_form_item}, plus the Russian names for them. */
    private static final Map<String, ManagedFormGroupType> GROUP_TYPES = new LinkedHashMap<>();

    static {
        GROUP_TYPES.put("usualgroup", ManagedFormGroupType.USUAL_GROUP);
        GROUP_TYPES.put("обычнаягруппа", ManagedFormGroupType.USUAL_GROUP);
        GROUP_TYPES.put("группа", ManagedFormGroupType.USUAL_GROUP);
        GROUP_TYPES.put("page", ManagedFormGroupType.PAGE);
        GROUP_TYPES.put("страница", ManagedFormGroupType.PAGE);
        GROUP_TYPES.put("pages", ManagedFormGroupType.PAGES);
        GROUP_TYPES.put("страницы", ManagedFormGroupType.PAGES);
        GROUP_TYPES.put("commandbar", ManagedFormGroupType.COMMAND_BAR);
        GROUP_TYPES.put("команднаяпанель", ManagedFormGroupType.COMMAND_BAR);
        GROUP_TYPES.put("buttongroup", ManagedFormGroupType.BUTTON_GROUP);
        GROUP_TYPES.put("группакнопок", ManagedFormGroupType.BUTTON_GROUP);
        GROUP_TYPES.put("columngroup", ManagedFormGroupType.COLUMN_GROUP);
        GROUP_TYPES.put("группаколонок", ManagedFormGroupType.COLUMN_GROUP);
        GROUP_TYPES.put("popup", ManagedFormGroupType.POPUP);
        GROUP_TYPES.put("подменю", ManagedFormGroupType.POPUP);
    }

    /** Outcome of the form-item operations. */
    public static final class FormItemResult {
        public boolean ok;
        public boolean applied;
        public String projectName;
        public String formFqn;
        public String kind;
        public String name;
        public String parent;
        public String dataPath;
        public String command;
        public Integer id;
        public boolean formFound;
        public Boolean parentFound;
        public Boolean nameAvailable;
        public Boolean present;
        public String plan;
        public String warning;
        public String message;
        public final List<String> items = new ArrayList<>();
        public final List<String> createdColumns = new ArrayList<>();
    }

    /**
     * Add a visual item to a managed form through EDT's own {@link IFormItemManagementService} - the
     * service the form editor itself calls - so naming, ids, the field's actual type and a table's
     * columns are all decided by EDT rather than guessed here.
     *
     * @param kind      field, table, button, group or decoration
     * @param name      item name; {@code null} lets EDT pick a unique one
     * @param parentName container item to place it in; {@code null} means the form root
     * @param dataPath  what a field or table is bound to, e.g. {@code ДатаКурсов} or {@code Курсы.Курс}
     * @param command   form command a button runs
     * @param groupType group kind, defaulting to a usual group
     */
    public FormItemResult addFormItem(String projectName, String formFqn, String kind, String name,
            String parentName, String dataPath, String command, String groupType, String titleRu,
            boolean apply) {
        FormItemResult r = new FormItemResult();
        r.projectName = projectName;
        r.formFqn = formFqn;
        r.name = name;
        r.parent = parentName;
        r.dataPath = dataPath;
        r.command = command;
        String itemKind = (kind == null) ? "" : kind.trim().toLowerCase(Locale.ROOT);
        r.kind = itemKind;

        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        if (formFqn == null || formFqn.isBlank()) {
            r.message = "formFqn is required";
            return r;
        }
        if (name != null && !name.isBlank() && !IDENT.matcher(name).matches()) {
            r.message = "name is not a valid 1C identifier: \"" + name + "\"";
            return r;
        }
        if (!List.of("field", "table", "button", "group", "decoration").contains(itemKind)) {
            r.message = "unknown kind: \"" + kind + "\" (use field, table, button, group or decoration)";
            return r;
        }
        if (("field".equals(itemKind) || "table".equals(itemKind))
                && (dataPath == null || dataPath.isBlank())) {
            r.message = itemKind + " needs a dataPath, e.g. ДатаКурсов or Курсы.Курс";
            return r;
        }
        if ("button".equals(itemKind) && (command == null || command.isBlank())) {
            r.message = "button needs a command - the name of a form command";
            return r;
        }
        final ManagedFormGroupType group = "group".equals(itemKind)
                ? resolveGroupType(groupType) : null;
        if ("group".equals(itemKind) && group == null) {
            r.message = "unknown groupType: \"" + groupType + "\" (usual group, page, pages, command bar, "
                    + "button group, column group, popup)";
            return r;
        }

        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }

        final String[] problem = {null};
        model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.addFormItem.validate") {
            @Override
            public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                Form form = resolveForm(tx, formFqn);
                if (form == null) {
                    return null;
                }
                r.formFound = true;
                collectItemNames(form.getItems(), r.items);
                if (name != null && !name.isBlank()) {
                    r.nameAvailable = Boolean.valueOf(findItem(form.getItems(), name) == null);
                }
                r.parentFound = Boolean.valueOf(parentName == null || parentName.isBlank()
                        || findContainer(form.getItems(), parentName) != null);
                if (dataPath != null && !dataPath.isBlank()) {
                    String head = dataPath.split("\\.")[0];
                    if (findAttribute(form, head) == null) {
                        problem[0] = "the form has no attribute \"" + head + "\" for dataPath \""
                                + dataPath + "\"";
                    }
                }
                if (command != null && !command.isBlank() && findCommand(form, command) == null) {
                    problem[0] = "the form has no command \"" + command + "\"";
                }
                return null;
            }
        });

        if (!r.formFound) {
            r.message = "form not found: " + formFqn;
            return r;
        }
        r.ok = !Boolean.FALSE.equals(r.nameAvailable) && Boolean.TRUE.equals(r.parentFound)
                && problem[0] == null;
        r.plan = "Add " + itemKind + (name == null || name.isBlank() ? "" : " \"" + name + "\"")
                + (dataPath != null && !dataPath.isBlank() ? " bound to " + dataPath : "")
                + (command != null && !command.isBlank() ? " running " + command : "")
                + (group != null ? " [" + group.getName() + "]" : "")
                + " to " + formFqn
                + (parentName == null || parentName.isBlank() ? " (form root)" : " inside " + parentName);
        if (Boolean.FALSE.equals(r.nameAvailable)) {
            r.message = "the form already has an item named \"" + name + "\"";
        } else if (Boolean.FALSE.equals(r.parentFound)) {
            r.message = "parent container not found on the form: \"" + parentName + "\"";
        } else if (problem[0] != null) {
            r.message = problem[0];
        }

        if (!apply) {
            return r;
        }
        if (!r.ok) {
            r.message = (r.message == null ? "validation failed" : r.message)
                    + " - apply refused (nothing added).";
            return r;
        }
        IFormItemManagementService items = formItemService();
        if (items == null) {
            r.message = "form item management service unavailable (EDT form plugin not active)";
            return r;
        }
        final String[] failure = {null};
        try {
            model.execute(new AbstractBmTask<Object>("edt-bridge.addFormItem.apply") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    Form form = resolveForm(tx, formFqn);
                    if (form == null) {
                        failure[0] = "the form disappeared between validation and apply";
                        return null;
                    }
                    FormItemContainer parent = form;
                    if (parentName != null && !parentName.isBlank()) {
                        parent = findContainer(form.getItems(), parentName);
                        if (parent == null) {
                            failure[0] = "parent container disappeared: " + parentName;
                            return null;
                        }
                    }
                    Map<String, String> title = new LinkedHashMap<>();
                    if (titleRu != null && !titleRu.isBlank()) {
                        title.put("ru", titleRu);
                    }
                    FormNewItemDescriptor descriptor = new FormNewItemDescriptor(
                            (name == null || name.isBlank()) ? null : name, title, false);
                    FormItem created;
                    switch (itemKind) {
                        case "field":
                            created = items.addField(parent, dataPathOf(dataPath), form, descriptor);
                            break;
                        case "table":
                            Table table = items.addTable(parent, dataPathOf(dataPath), true, form,
                                    descriptor);
                            created = table;
                            if (table != null) {
                                collectItemNames(table.getItems(), r.createdColumns);
                            }
                            break;
                        case "button":
                            created = items.addButton(parent, findCommand(form, command), null, form,
                                    descriptor);
                            break;
                        case "group":
                            created = items.addGroup(parent, group, form, descriptor);
                            break;
                        default:
                            created = items.addDecoration(parent, form, descriptor);
                            break;
                    }
                    if (created == null) {
                        failure[0] = "EDT's form item service returned nothing for kind " + itemKind;
                        return null;
                    }
                    r.name = created.getName();
                    r.id = Integer.valueOf(created.getId());
                    return null;
                }
            });
        } catch (RuntimeException ex) {
            r.message = "apply failed (nothing committed): " + GatewaySupport.describeCause(ex);
            return r;
        }
        if (failure[0] != null) {
            r.message = "apply failed (nothing committed): " + failure[0];
            return r;
        }
        finishItem(r, mm, model, formFqn, "added " + itemKind + " \"" + r.name + "\" to ");
        return r;
    }

    /** Change a form item's title, visibility or enabled state. */
    public FormItemResult modifyFormItem(String projectName, String formFqn, String name,
            String titleRu, Boolean visible, Boolean enabled, boolean apply) {
        FormItemResult r = new FormItemResult();
        r.projectName = projectName;
        r.formFqn = formFqn;
        r.name = name;
        if (name == null || name.isBlank()) {
            r.message = "name is required - the form item to change";
            return r;
        }
        if (titleRu == null && visible == null && enabled == null) {
            r.message = "nothing to change - pass at least one of titleRu, visible, enabled";
            return r;
        }
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }
        inspectItem(model, r, formFqn, name);
        if (!r.formFound) {
            r.message = "form not found: " + formFqn;
            return r;
        }
        r.ok = Boolean.TRUE.equals(r.present);
        StringBuilder what = new StringBuilder();
        if (titleRu != null) {
            what.append(" title -> \"").append(titleRu).append('"');
        }
        if (visible != null) {
            what.append(" visible -> ").append(visible);
        }
        if (enabled != null) {
            what.append(" enabled -> ").append(enabled);
        }
        r.plan = "Change item \"" + name + "\" of " + formFqn + ":" + what;
        if (!r.ok) {
            r.message = "the form has no item named \"" + name + "\"";
        }
        if (!apply) {
            return r;
        }
        if (!r.ok) {
            r.message = r.message + " - apply refused (nothing written).";
            return r;
        }
        final String[] failure = {null};
        try {
            model.execute(new AbstractBmTask<Object>("edt-bridge.modifyFormItem.apply") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    Form form = resolveForm(tx, formFqn);
                    FormItem item = (form == null) ? null : findItem(form.getItems(), name);
                    if (item == null) {
                        failure[0] = "the item disappeared between validation and apply";
                        return null;
                    }
                    if (titleRu != null && item instanceof Titled) {
                        EMap<String, String> title = ((Titled) item).getTitle();
                        if (titleRu.isBlank()) {
                            title.removeKey("ru");
                        } else {
                            title.put("ru", titleRu);
                        }
                    }
                    if (item instanceof Visible) {
                        if (visible != null) {
                            ((Visible) item).setVisible(visible.booleanValue());
                        }
                        if (enabled != null) {
                            ((Visible) item).setEnabled(enabled.booleanValue());
                        }
                    } else if (visible != null || enabled != null) {
                        failure[0] = "item " + name + " has no visibility properties";
                        return null;
                    }
                    r.id = Integer.valueOf(item.getId());
                    return null;
                }
            });
        } catch (RuntimeException ex) {
            r.message = "apply failed (nothing committed): " + GatewaySupport.describeCause(ex);
            return r;
        }
        if (failure[0] != null) {
            r.message = "apply failed (nothing committed): " + failure[0];
            return r;
        }
        finishItem(r, mm, model, formFqn, "changed item \"" + name + "\" of ");
        return r;
    }

    /** Remove a form item, and with it everything nested inside. Destructive, hence {@code force}. */
    public FormItemResult removeFormItem(String projectName, String formFqn, String name, boolean force,
            boolean apply) {
        FormItemResult r = new FormItemResult();
        r.projectName = projectName;
        r.formFqn = formFqn;
        r.name = name;
        if (name == null || name.isBlank()) {
            r.message = "name is required - the form item to remove";
            return r;
        }
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }
        inspectItem(model, r, formFqn, name);
        if (!r.formFound) {
            r.message = "form not found: " + formFqn;
            return r;
        }
        r.ok = Boolean.TRUE.equals(r.present);
        r.plan = "Remove item \"" + name + "\" from " + formFqn
                + (r.createdColumns.isEmpty() ? ""
                        : " together with " + r.createdColumns.size() + " nested item(s)");
        if (!r.ok) {
            r.message = "the form has no item named \"" + name + "\"";
        } else if (!r.createdColumns.isEmpty()) {
            r.warning = "nested items go with it: " + String.join(", ", r.createdColumns);
        }
        if (!apply) {
            return r;
        }
        if (!r.ok) {
            r.message = r.message + " - apply refused (nothing removed).";
            return r;
        }
        if (!force) {
            r.message = "removing a form item is destructive"
                    + (r.createdColumns.isEmpty() ? ""
                            : " and takes " + r.createdColumns.size() + " nested item(s) with it")
                    + " - apply refused; pass force=true to perform it.";
            return r;
        }
        final String[] failure = {null};
        try {
            model.execute(new AbstractBmTask<Object>("edt-bridge.removeFormItem.apply") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    Form form = resolveForm(tx, formFqn);
                    FormItem item = (form == null) ? null : findItem(form.getItems(), name);
                    if (item == null) {
                        failure[0] = "the item disappeared between validation and apply";
                        return null;
                    }
                    EObject container = item.eContainer();
                    if (container instanceof FormItemContainer) {
                        ((FormItemContainer) container).getItems().remove(item);
                    } else {
                        form.getItems().remove(item);
                    }
                    return null;
                }
            });
        } catch (RuntimeException ex) {
            r.message = "apply failed (nothing committed): " + GatewaySupport.describeCause(ex);
            return r;
        }
        if (failure[0] != null) {
            r.message = "apply failed (nothing committed): " + failure[0];
            return r;
        }
        finishItem(r, mm, model, formFqn, "removed item \"" + name + "\" from ");
        return r;
    }

    // ---- shared plumbing for the form-item operations --------------------------------------------

    private void inspectItem(IBmModel model, FormItemResult r, String formFqn, String name) {
        model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.formItem.inspect") {
            @Override
            public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                Form form = resolveForm(tx, formFqn);
                if (form == null) {
                    return null;
                }
                r.formFound = true;
                collectItemNames(form.getItems(), r.items);
                FormItem item = findItem(form.getItems(), name);
                r.present = Boolean.valueOf(item != null);
                if (item instanceof FormItemContainer) {
                    collectItemNames(((FormItemContainer) item).getItems(), r.createdColumns);
                }
                return null;
            }
        });
    }

    private static void finishItem(FormItemResult r, IBmModelManager mm, IBmModel model, String formFqn,
            String what) {
        String topFqn = formTopFqn(model, formFqn);
        IDtProject dtProject = mm.getDtProject(model);
        boolean exported = dtProject != null && topFqn != null && mm.forceExport(dtProject, topFqn);
        r.applied = true;
        r.message = what + formFqn
                + (exported ? " (serialized to Form.form)"
                        : " - WARNING: committed in-memory but forceExport did not persist (Form.form "
                                + "unchanged)");
    }

    private static DataPath dataPathOf(String path) {
        DataPath dp = FormFactory.eINSTANCE.createDataPath();
        for (String segment : path.split("\\.")) {
            if (!segment.isBlank()) {
                dp.getSegments().add(segment.trim());
            }
        }
        return dp;
    }

    private static ManagedFormGroupType resolveGroupType(String spec) {
        if (spec == null || spec.isBlank()) {
            return ManagedFormGroupType.USUAL_GROUP;
        }
        String key = spec.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
        return GROUP_TYPES.get(key);
    }

    /** Depth-first search for an item by name. */
    private static FormItem findItem(List<FormItem> items, String name) {
        for (FormItem item : items) {
            if (name.equalsIgnoreCase(item.getName())) {
                return item;
            }
            if (item instanceof FormItemContainer) {
                FormItem nested = findItem(((FormItemContainer) item).getItems(), name);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /** Depth-first search for a container item by name. */
    private static FormItemContainer findContainer(List<FormItem> items, String name) {
        FormItem item = findItem(items, name);
        return (item instanceof FormItemContainer) ? (FormItemContainer) item : null;
    }

    private static void collectItemNames(List<FormItem> items, List<String> out) {
        for (FormItem item : items) {
            if (item.getName() != null) {
                out.add(item.getName());
            }
            if (item instanceof FormItemContainer) {
                collectItemNames(((FormItemContainer) item).getItems(), out);
            }
        }
    }

    private static IFormItemManagementService formItemService() {
        Injector injector = formInjector();
        try {
            return (injector == null) ? null : injector.getInstance(IFormItemManagementService.class);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
