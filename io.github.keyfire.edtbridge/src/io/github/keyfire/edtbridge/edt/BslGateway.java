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
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerFilter;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import org.eclipse.xtext.diagnostics.Severity;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.EObjectAtOffsetHelper;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.resource.IResourceFactory;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.resource.XtextResourceSet;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.validation.CheckMode;
import org.eclipse.xtext.validation.IResourceValidator;
import org.eclipse.xtext.validation.Issue;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.operations.IProjectOperationApi;
import com._1c.g5.v8.dt.core.operations.ProjectPipelineJob;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProjectManager;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProjectManager;
import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumpSupport;
import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumper;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseConfigurationChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateCallback;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateConflictResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolution;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.Section;
import com._1c.g5.v8.dt.platform.services.core.operations.CompoundOperationException;
import com._1c.g5.v8.dt.platform.services.core.operations.IInfobaseCreationOperation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.IRuntimeInstallationManager;
import com._1c.g5.v8.dt.export.IExportOperation;
import com._1c.g5.v8.dt.export.IExportOperationFactory;
import com._1c.g5.wiring.ServiceProperties;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.MatchingRuntimeNotFound;
import com._1c.g5.v8.dt.platform.services.model.AppArch;
import com._1c.g5.v8.dt.platform.services.model.CreateInfobaseArguments;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.Button;
import com._1c.g5.v8.dt.form.model.CommandHandler;
import com._1c.g5.v8.dt.form.model.CommandHandlerContainer;
import com._1c.g5.v8.dt.form.model.DataItem;
import com._1c.g5.v8.dt.form.model.Decoration;
import com._1c.g5.v8.dt.form.model.EventHandler;
import com._1c.g5.v8.dt.form.model.FieldExtInfo;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormItemContainer;
import com._1c.g5.v8.dt.form.model.FormParameter;
import com._1c.g5.v8.dt.form.model.InputFieldExtInfo;
import com._1c.g5.v8.dt.form.model.NativeRenderEvent;
import com._1c.g5.v8.dt.form.model.Titled;
import com._1c.g5.v8.dt.form.model.Table;
import com._1c.g5.v8.dt.form.model.Visible;
import com._1c.g5.v8.dt.form.model.EventHandlerContainer;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearance;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearanceItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionAppearanceField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilter;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterItem;
import com._1c.g5.v8.dt.dcs.model.settings.FilterItem;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterValue;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameter;
import com._1c.g5.v8.dt.form.layout.model.calculation.context.LFTargetPlatform;
import com._1c.g5.v8.dt.form.layout.model.description.ClientInterfaceScale;
import com._1c.g5.v8.dt.form.layout.model.description.ClientInterfaceTheme;
import com._1c.g5.v8.dt.form.layout.model.description.ClientInterfaceVariant;
import com._1c.g5.v8.dt.form.layout.model.platform.IPlatformVisualComputer;
import com._1c.g5.v8.dt.form.layout.service.HippoLayoutService;
import com._1c.g5.v8.dt.form.layout.service.IHippoLayModelSession;
import com._1c.g5.v8.dt.form.layout.service.ILayoutRenderService;
import com._1c.g5.v8.dt.form.layout.service.LayoutTransformationService;
import com._1c.g5.v8.dt.form.layout.service.RenderServiceProvider;
import com._1c.g5.v8.dt.form.layout.service.TransformatorServiceProvider;
import com._1c.g5.v8.dt.form.mapping.cmi.FormCommandInterfaceMapping;
import com._1c.g5.v8.dt.form.mapping.core.MappingController;
import com._1c.g5.v8.dt.form.mapping.model.CommandInterfaceMapping;
import com._1c.g5.v8.dt.form.presentation.theme.IThemeProjection;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.NamedElement;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ConfigurationExtensionPurpose;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.mcore.DateFractions;
import com._1c.g5.v8.dt.mcore.DateQualifiers;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.NumberQualifiers;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.mcore.StringQualifiers;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.util.MdProducedTypesUtil;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypePackage;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringItem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringProblem;
import com._1c.g5.wiring.ServiceAccess;
import com.google.inject.Injector;

/**
 * BSL (1C:Enterprise script) operations over the live EDT model: query validation, go-to-definition,
 * module text, add/delete method, outgoing calls and structures, method references and symbol info.
 * The residual of the original model gateway after it was split into focused per-area gateways;
 * behaviour is unchanged.
 */
public final class BslGateway {




    /** One QL validation issue. */
    public static final class QueryIssue {
        public String severity; // ERROR | WARNING | INFO
        public String message;
        public String code;
        public Integer line;
        public Integer column;
        public Integer offset;
        public Integer length;
    }

    /** Result of {@link #validateQuery}. */
    public static final class QueryValidation {
        public boolean projectFound;
        public boolean valid;
        public int errorCount;
        public int warningCount;
        public List<QueryIssue> issues = new ArrayList<>();
        /** Infrastructure-level failure (injector/parse); null when validation actually ran. */
        public String error;
    }

    /**
     * Validate a 1C query (QL) against the live metadata of {@code projectName}: syntax + semantics
     * (unknown tables/fields, type mismatches) via EDT's own QL validator. The transient query
     * resource is given a {@code platform:/resource/<project>/...ql} URI so the QL scope provider
     * resolves metadata for that project – it derives the project from the resource URI
     * (IResourceLookup), not from a file on disk (none is created; text is loaded from memory).
     */
    public QueryValidation validateQuery(String projectName, String queryText) {
        QueryValidation r = new QueryValidation();
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.error = "project not found or closed: " + projectName;
            return r;
        }
        r.projectFound = true;
        try {
            Injector injector = qlInjector();
            if (injector == null) {
                r.error = "QL services unavailable (EDT QL bundle not active)";
                return r;
            }
            IResourceFactory factory = injector.getInstance(IResourceFactory.class);
            XtextResourceSet resourceSet = injector.getInstance(XtextResourceSet.class);
            URI uri = URI.createURI("platform:/resource/" + projectName + "/__edt_bridge_query__.ql");
            XtextResource resource = (XtextResource) factory.createResource(uri);
            resourceSet.getResources().add(resource);
            byte[] bytes = (queryText == null ? "" : queryText).getBytes(StandardCharsets.UTF_8);
            resource.load(new ByteArrayInputStream(bytes), resourceSet.getLoadOptions());
            EcoreUtil.resolveAll(resource);
            IResourceValidator validator = injector.getInstance(IResourceValidator.class);
            List<Issue> issues = validator.validate(resource, CheckMode.ALL, CancelIndicator.NullImpl);
            for (Issue is : issues) {
                Severity sev = is.getSeverity();
                QueryIssue qi = new QueryIssue();
                qi.severity = (sev == null) ? "INFO" : sev.name();
                qi.message = is.getMessage();
                qi.code = is.getCode();
                qi.line = is.getLineNumber();
                qi.column = is.getColumn();
                qi.offset = is.getOffset();
                qi.length = is.getLength();
                if (sev == Severity.ERROR) {
                    r.errorCount++;
                } else if (sev == Severity.WARNING) {
                    r.warningCount++;
                }
                r.issues.add(qi);
            }
            r.valid = r.errorCount == 0;
        } catch (Throwable t) {
            // Reflective EDT-internal access: degrade to a clean error instead of breaking the handler.
            r.error = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
        }
        return r;
    }

    /**
     * EDT's fully-wired Xtext language injector, taken from the language's UI activator via the
     * bundle's own class loader. The activator sits in an internal package, so we never compile
     * against it (no Require-Bundle on the UI plugin); reflection stays contained in this adapter.
     */
    private Injector languageInjector(String bundleId, String activatorClass, String language) throws Exception {
        Bundle bundle = Platform.getBundle(bundleId);
        if (bundle == null) {
            return null;
        }
        Class<?> ac = bundle.loadClass(activatorClass);
        Object instance = ac.getMethod("getInstance").invoke(null);
        if (instance == null) {
            return null;
        }
        return (Injector) ac.getMethod("getInjector", String.class).invoke(instance, language);
    }

    private Injector qlInjector() throws Exception {
        return languageInjector("com._1c.g5.v8.dt.ql.ui",
                "com._1c.g5.v8.dt.ql.ui.internal.QlActivator", "com._1c.g5.v8.dt.ql.Ql");
    }

    private Injector bslInjector() throws Exception {
        return languageInjector("com._1c.g5.v8.dt.bsl.ui",
                "com._1c.g5.v8.dt.bsl.ui.internal.BslActivator", "com._1c.g5.v8.dt.bsl.Bsl");
    }

    /** A resolved go-to-definition target. */
    public static final class DefinitionResult {
        public boolean found;
        public String message;       // set when nothing was resolved
        public String targetType;    // EClass of the target (Method, ExplicitVariable, FormalParam, ...)
        public String targetName;    // symbol name, when the target is a named element
        public String ownerFqn;      // owning module's owner FQN (e.g. CommonModule.X), when resolvable
        public String uri;           // target resource URI
        public int line = -1;        // 1-based line of the target in its resource, -1 if unknown
        public boolean sameModule;   // target is in the same module as the position
    }

    /**
     * Resolve the definition of the symbol at a position in a BSL module: parse the module into a
     * transient Xtext resource under its real platform URI (so BSL scoping resolves the project's
     * symbols), then resolve the cross-referenced element at the offset and report the target.
     * {@code offsetArg} < 0 means derive the offset from 1-based {@code line}/{@code column}.
     */
    public DefinitionResult goToDefinition(String projectName, String modulePath, int line, int column, int offsetArg) {
        DefinitionResult r = new DefinitionResult();
        try {
            BslContext ctx = loadBsl(projectName, modulePath, line, column, offsetArg);
            if (ctx.error != null) {
                r.message = ctx.error;
                return r;
            }
            EObjectAtOffsetHelper helper = ctx.injector.getInstance(EObjectAtOffsetHelper.class);
            EObject target = helper.resolveCrossReferencedElementAt(ctx.resource, ctx.offset);
            if (target == null) {
                r.message = "no reference at this position";
                return r;
            }
            if (target.eIsProxy()) {
                r.message = "reference is unresolved at this position";
                return r;
            }
            r.found = true;
            r.targetType = target.eClass().getName();
            if (target instanceof NamedElement) {
                r.targetName = ((NamedElement) target).getName();
            }
            EObject c = target;
            while (c != null && !(c instanceof Module)) {
                c = c.eContainer();
            }
            if (c instanceof Module) {
                EObject owner = ((Module) c).getOwner();
                if (owner instanceof MdObject) {
                    r.ownerFqn = fqnOf((MdObject) owner);
                }
            }
            Resource targetResource = target.eResource();
            r.uri = (targetResource != null) ? String.valueOf(targetResource.getURI()) : null;
            r.sameModule = (targetResource == ctx.resource);
            ICompositeNode node = NodeModelUtils.getNode(target);
            if (node != null) {
                r.line = node.getStartLine();
            }
        } catch (Throwable t) {
            r.message = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
        }
        return r;
    }

    // ---- BSL module text + methods ---------------------------------------------------

    /** Type (eClass name, lower-case) → EDT src folder name (English plural). */
    static final Map<String, String> MD_FOLDER = Map.ofEntries(
            Map.entry("catalog", "Catalogs"), Map.entry("document", "Documents"),
            Map.entry("documentjournal", "DocumentJournals"), Map.entry("enum", "Enums"),
            Map.entry("report", "Reports"), Map.entry("dataprocessor", "DataProcessors"),
            Map.entry("chartofcharacteristictypes", "ChartsOfCharacteristicTypes"),
            Map.entry("chartofaccounts", "ChartsOfAccounts"),
            Map.entry("chartofcalculationtypes", "ChartsOfCalculationTypes"),
            Map.entry("informationregister", "InformationRegisters"),
            Map.entry("accumulationregister", "AccumulationRegisters"),
            Map.entry("accountingregister", "AccountingRegisters"),
            Map.entry("calculationregister", "CalculationRegisters"),
            Map.entry("businessprocess", "BusinessProcesses"), Map.entry("task", "Tasks"),
            Map.entry("exchangeplan", "ExchangePlans"), Map.entry("constant", "Constants"),
            Map.entry("commonmodule", "CommonModules"), Map.entry("commonform", "CommonForms"),
            Map.entry("commoncommand", "CommonCommands"),
            // External objects live in their own project, but the layout under src/ is the same, so
            // their modules and forms resolve by FQN like any other owner.
            Map.entry("externaldataprocessor", "ExternalDataProcessors"),
            Map.entry("externalreport", "ExternalReports"),
            // Service objects: one Module.bsl each, resolved by FQN like the module owners above.
            Map.entry("httpservice", "HTTPServices"), Map.entry("webservice", "WebServices"));

    /** A procedure/function in a module: signature parts. */
    public static final class BslMethod {
        public String name;
        public String kind;          // "Procedure" | "Function"
        public boolean export;
        public int line;
        public List<String> params = new ArrayList<>();   // "Знач Имя" / "Имя = Значение"
    }

    /** Result of {@link #moduleText}. */
    public static final class ModuleTextResult {
        public boolean found;
        public String fqn;
        public String modulePath;                 // resolved workspace-relative .bsl path
        public List<String> availableModules = new ArrayList<>(); // when ambiguous: candidate .bsl in the folder
        public List<BslMethod> methods = new ArrayList<>();
        public String text;                       // whole module, or one method's text when 'method' is given
        public boolean textTruncated;
        public boolean includeMethods = true;     // echo: false = the procedure/function catalogue was skipped
        public String message;
    }

    private static final int MODULE_TEXT_CAP = 200_000;

    /**
     * BSL source of a module/method from the live workspace. Resolve the module either by
     * {@code modulePath} (workspace-relative .bsl) directly, or by {@code fqn} (+ optional
     * {@code moduleType} like ObjectModule/ManagerModule; forms and common modules resolve to Module.bsl).
     * Returns the procedure/function list with signatures and the source text – of the whole module, or of
     * a single {@code method} when given. When a top object has several module files and none is selected,
     * returns the candidates in {@code availableModules}.
     */
    public ModuleTextResult moduleText(String projectName, String fqn, String moduleType,
            String method, String modulePath, boolean includeMethods) {
        ModuleTextResult r = new ModuleTextResult();
        r.fqn = fqn;
        r.includeMethods = includeMethods;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        String relPath = modulePath;
        if (relPath == null || relPath.isBlank()) {
            relPath = resolveModulePath(p, fqn, moduleType, r);
            if (relPath == null) {
                return r; // message / availableModules already set
            }
        }
        IFile file = p.getFile(relPath);
        if (!file.exists()) {
            r.message = "module file not found: " + relPath;
            return r;
        }
        r.modulePath = relPath;
        try {
            String fullText = readText(file);
            BslContext ctx = loadBsl(projectName, relPath, -1, -1, 0);
            if (ctx.error != null || ctx.resource == null) {
                // Still return the raw text even if parsing failed.
                r.found = true;
                r.text = cap(fullText, r);
                r.message = "parsed methods unavailable (" + ctx.error + "); returning raw text";
                return r;
            }
            Module module = null;
            for (EObject e : ctx.resource.getContents()) {
                if (e instanceof Module) {
                    module = (Module) e;
                    break;
                }
            }
            r.found = true;
            EObject methodEObj = null;
            if (module != null) {
                for (com._1c.g5.v8.dt.bsl.model.Method m : module.allMethods()) {
                    if (includeMethods) {
                        BslMethod bm = new BslMethod();
                        bm.name = m.getName();
                        bm.kind = (m instanceof com._1c.g5.v8.dt.bsl.model.Function) ? "Function" : "Procedure";
                        bm.export = m.isExport();
                        for (com._1c.g5.v8.dt.bsl.model.FormalParam fp : m.getFormalParams()) {
                            String ps = (fp.isByValue() ? "Знач " : "") + fp.getName()
                                    + (fp.getDefaultValue() != null ? " = ..." : "");
                            bm.params.add(ps);
                        }
                        ICompositeNode mn = NodeModelUtils.getNode(m);
                        if (mn != null) {
                            bm.line = mn.getStartLine();
                        }
                        r.methods.add(bm);
                    }
                    if (method != null && method.equalsIgnoreCase(m.getName())) {
                        methodEObj = m;
                    }
                }
            }
            if (method != null) {
                if (methodEObj == null) {
                    r.message = "method not found in module: " + method;
                    return r;
                }
                ICompositeNode node = NodeModelUtils.getNode(methodEObj);
                r.text = cap(node != null ? node.getText().strip() : fullText, r);
            } else {
                r.text = cap(fullText, r);
            }
        } catch (Exception ex) {
            r.message = "module text failed: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
        }
        return r;
    }

    private String cap(String s, ModuleTextResult r) {
        if (s == null) {
            return null;
        }
        if (s.length() > MODULE_TEXT_CAP) {
            r.textTruncated = true;
            return s.substring(0, MODULE_TEXT_CAP);
        }
        return s;
    }

    public static final class AddMethodResult {
        public boolean ok;
        public boolean applied;
        public boolean applyPending;
        public String modulePath;
        public boolean moduleFound;
        public String methodName;        // parsed from the spliced model
        public String methodKind;        // Procedure | Function
        public Boolean export;
        public Boolean nameAvailable;    // false when the name already exists in the module
        public boolean valid;            // methodText is exactly one method AND the spliced module parses clean
        public String region;            // requested region (echo)
        public Boolean regionFound;
        public boolean serverBlock;      // requested
        public Boolean serverBlockFound;
        public String insertAfter;       // anchor description (method name / region header / <end of module>)
        public int insertLine = -1;      // 1-based line of the inserted method in the spliced text
        public String preview;           // text snippet around the insertion (dry-run)
        public String plan;
        public String message;
    }

    /**
     * Add a new procedure/function to a module's BSL. Model-guided text splice:
     * parse the module, resolve the insertion offset from the node model (a named {@code region}, the server
     * {@code #Если Сервер} block, or after the last method), splice the caller's {@code methodText} and
     * validate by RE-PARSING the result – the tool refuses to write anything that does not parse cleanly,
     * adds not exactly one method, or duplicates an existing name. Dry-run unless {@code apply} is true.
     * Additive (a new method is non-breaking) – no force. Token + EDITABLE are enforced by the server/caller.
     */
    public AddMethodResult addMethod(String projectName, String fqn, String moduleType, String modulePath,
            String methodText, String region, Boolean serverBlockArg, boolean apply) {
        AddMethodResult r = new AddMethodResult();
        r.region = (region != null && !region.isBlank()) ? region : null;
        boolean serverBlock = Boolean.TRUE.equals(serverBlockArg);
        r.serverBlock = serverBlock;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        String relPath = modulePath;
        if (relPath == null || relPath.isBlank()) {
            ModuleTextResult shim = new ModuleTextResult();
            relPath = resolveModulePath(p, fqn, moduleType, shim);
            if (relPath == null) {
                r.message = shim.message;
                return r;
            }
        }
        IFile file = p.getFile(relPath);
        if (!file.exists()) {
            r.message = "module file not found: " + relPath;
            return r;
        }
        r.modulePath = relPath;
        if (methodText == null || methodText.isBlank()) {
            r.message = "methodText is required";
            return r;
        }
        try {
            String fullText = readText(file);
            String eol = fullText.contains("\r\n") ? "\r\n" : "\n";

            // 1) parse the original module; refuse to edit a module that does not already parse cleanly.
            BslContext ctx0 = loadBslText(projectName, relPath, fullText);
            if (ctx0.error != null || ctx0.resource == null) {
                r.message = "cannot parse target module: " + ctx0.error;
                return r;
            }
            if (!ctx0.resource.getErrors().isEmpty()) {
                r.message = "target module has parse errors; refusing to edit a non-parsing module ("
                        + firstError(ctx0.resource) + ")";
                return r;
            }
            Module module0 = firstModule(ctx0.resource);
            if (module0 == null) {
                r.message = "no BSL module parsed from " + relPath;
                return r;
            }
            r.moduleFound = true;
            java.util.Set<String> baseline = new java.util.HashSet<>();
            int baselineCount = 0;
            for (com._1c.g5.v8.dt.bsl.model.Method m : module0.allMethods()) {
                baseline.add(m.getName().toLowerCase());
                baselineCount++;
            }

            // 2) resolve the insertion scope from the model (never a regex).
            int[] scope = new int[] {0, fullText.length()};   // [start, end)
            EObject serverIf = null;
            if (serverBlock) {
                serverIf = findServerIf(module0);
                if (serverIf == null) {
                    r.serverBlockFound = Boolean.FALSE;
                    r.message = "server preprocessor block (#Если Сервер ...) not found in module";
                    return r;
                }
                r.serverBlockFound = Boolean.TRUE;
                ICompositeNode sn = NodeModelUtils.getNode(serverIf);
                scope = new int[] {sn.getOffset(), sn.getEndOffset()};
            }
            EObject regionEo = null;
            if (r.region != null) {
                regionEo = findRegion(module0, r.region, scope);
                if (regionEo == null) {
                    r.regionFound = Boolean.FALSE;
                    r.message = "region not found: #Область " + r.region
                            + (serverBlock ? " (inside the server block)" : "");
                    return r;
                }
                r.regionFound = Boolean.TRUE;
                ICompositeNode rn = NodeModelUtils.getNode(regionEo);
                scope = new int[] {rn.getOffset(), rn.getEndOffset()};
            }

            // anchor = the textually-last method belonging to the target scope. Membership is by the
            // MODEL, never a text-offset window: EDT nests consecutive top-level #Область regions
            // (region B parses as a child of region A), so both an offset window AND a plain
            // isAncestor(regionA, ...) wrongly pull in region B's methods and land the insert at the
            // module end instead of inside region A (a wrong-location bug). For a region we
            // therefore require the method's IMMEDIATE enclosing region to equal the target region
            // (excludes nested regions); for a server block, any method inside it (incl. its nested
            // regions) is in scope, so containment is correct there.
            com._1c.g5.v8.dt.bsl.model.Method anchor = null;
            int anchorEnd = -1;
            for (com._1c.g5.v8.dt.bsl.model.Method m : module0.allMethods()) {
                boolean inScope;
                if (regionEo != null) {
                    inScope = (immediateRegion(m) == regionEo);
                } else if (serverIf != null) {
                    inScope = EcoreUtil.isAncestor(serverIf, m);
                } else {
                    inScope = true;
                }
                if (!inScope) {
                    continue;
                }
                ICompositeNode mn = NodeModelUtils.getNode(m);
                if (mn == null) {
                    continue;
                }
                if (mn.getEndOffset() > anchorEnd) {
                    anchorEnd = mn.getEndOffset();
                    anchor = m;
                }
            }

            String normalized = normalizeEol(methodText.strip(), eol);
            int insertPos;
            String block;
            if (anchor != null) {
                insertPos = anchorEnd;
                block = eol + eol + normalized;
                r.insertAfter = anchor.getName();
            } else if (regionEo != null || serverIf != null) {
                // Empty scope: place after the header line (#Область ... / #Если ... Тогда).
                int nl = fullText.indexOf('\n', scope[0]);
                insertPos = (nl < 0) ? scope[0] : nl + 1;
                block = eol + normalized + eol;
                r.insertAfter = (regionEo != null) ? ("#Область " + r.region + " (empty)") : "#Если Сервер (empty)";
            } else {
                // Whole module, no methods: append at the end.
                insertPos = fullText.length();
                block = (fullText.endsWith("\n") ? eol : eol + eol) + normalized + eol;
                r.insertAfter = "<end of module>";
            }
            String spliced = fullText.substring(0, insertPos) + block + fullText.substring(insertPos);

            // 3) safety invariant: the spliced module must re-parse cleanly and add exactly one method.
            BslContext ctx1 = loadBslText(projectName, relPath, spliced);
            if (ctx1.error != null || ctx1.resource == null) {
                r.message = "spliced module failed to parse: " + ctx1.error;
                return r;
            }
            Module module1 = firstModule(ctx1.resource);
            if (module1 == null || !ctx1.resource.getErrors().isEmpty()) {
                r.valid = false;
                r.message = "refused: the result would not parse cleanly (bad methodText or insertion point). "
                        + firstError(ctx1.resource);
                return r;
            }
            // Identify the inserted method by node position inside the spliced block.
            int blockStart = insertPos;
            int blockEnd = insertPos + block.length();
            com._1c.g5.v8.dt.bsl.model.Method added = null;
            int newCount = 0;
            for (com._1c.g5.v8.dt.bsl.model.Method m : module1.allMethods()) {
                newCount++;
                ICompositeNode mn = NodeModelUtils.getNode(m);
                if (added == null && mn != null && mn.getOffset() >= blockStart && mn.getOffset() < blockEnd) {
                    added = m;
                }
            }
            if (newCount != baselineCount + 1 || added == null) {
                r.valid = false;
                r.message = "refused: methodText must be exactly one BSL procedure/function (parsed "
                        + (newCount - baselineCount) + " new methods)";
                return r;
            }
            r.methodName = added.getName();
            r.methodKind = (added instanceof com._1c.g5.v8.dt.bsl.model.Function) ? "Function" : "Procedure";
            r.export = Boolean.valueOf(added.isExport());
            boolean dup = baseline.contains(added.getName().toLowerCase());
            r.nameAvailable = Boolean.valueOf(!dup);
            if (dup) {
                r.valid = false;
                r.message = "refused: method '" + added.getName()
                        + "' already exists in the module (add is not replace)";
                return r;
            }
            r.valid = true;
            ICompositeNode addedNode = NodeModelUtils.getNode(added);
            r.insertLine = (addedNode != null) ? addedNode.getStartLine() : -1;
            r.preview = previewAround(spliced, blockStart, blockEnd);
            r.plan = "Insert " + r.methodKind + " " + r.methodName + (Boolean.TRUE.equals(r.export) ? " (Export)" : "")
                    + " after " + r.insertAfter + " in " + relPath
                    + (r.region != null ? " [region " + r.region + "]" : "")
                    + (serverBlock ? " [server block]" : "");

            if (!apply) {
                r.ok = true;
                r.applyPending = true;
                r.message = "dry-run: validated, nothing written. Re-call with apply=true to write.";
                return r;
            }

            // 4) apply – atomic file write; EDT re-reads the .bsl on its next model access.
            file.setContents(new ByteArrayInputStream(spliced.getBytes(StandardCharsets.UTF_8)),
                    true, true, new NullProgressMonitor());
            r.applied = true;
            r.ok = true;
            r.message = "method added to " + relPath;
            return r;
        } catch (Exception e) {
            r.message = "add method failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : "");
            return r;
        }
    }

    // ---- Phase 2 write – delete a BSL method from a module --------------------------------------------

    /** Cap for {@link DeleteMethodResult#deletedText} (recovery payload, not a transport for huge modules). */
    private static final int DELETED_TEXT_CAP = 40_000;

    /** Result of {@link #deleteMethod}. */
    public static final class DeleteMethodResult {
        public boolean ok;
        public boolean applied;
        public boolean applyPending;
        public String modulePath;
        public boolean moduleFound;
        public boolean methodFound;
        public String methodName;        // actual-case name from the model
        public String methodKind;        // Procedure | Function
        public Boolean export;
        public boolean valid;            // the module without the method re-parses clean, exactly one method gone
        public boolean forced;
        public int lineFrom = -1;        // 1-based method lines in the ORIGINAL text
        public int lineTo = -1;
        public String deletedText;       // the exact removed text (recovery: paste it back to restore)
        public boolean deletedTextTruncated;
        public String preview;           // text around the seam after removal
        public String plan;
        public String message;
    }

    /**
     * Delete a procedure/function from a module's BSL – the inverse of {@link #addMethod}. Model-guided
     * text cut: locate the {@code Method} node by name, cut its exact node range plus the ADJACENT leading
     * doc-comment lines and the blank separation above (comments separated by a blank line are preserved),
     * and validate by RE-PARSING – refuse any result that does not parse cleanly or removes anything but
     * exactly this one method. Dry-run unless {@code apply}; deleting code is destructive and (for an
     * exported method) breaking for consumers, so an apply additionally requires {@code force=true}.
     * Token + EDITABLE are the server/caller's gates.
     */
    public DeleteMethodResult deleteMethod(String projectName, String fqn, String moduleType, String modulePath,
            String methodName, boolean apply, boolean force) {
        DeleteMethodResult r = new DeleteMethodResult();
        r.forced = force;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        String relPath = modulePath;
        if (relPath == null || relPath.isBlank()) {
            ModuleTextResult shim = new ModuleTextResult();
            relPath = resolveModulePath(p, fqn, moduleType, shim);
            if (relPath == null) {
                r.message = shim.message;
                return r;
            }
        }
        IFile file = p.getFile(relPath);
        if (!file.exists()) {
            r.message = "module file not found: " + relPath;
            return r;
        }
        r.modulePath = relPath;
        if (methodName == null || methodName.isBlank()) {
            r.message = "methodName is required";
            return r;
        }
        try {
            String fullText = readText(file);

            // 1) parse the original module; refuse to edit a module that does not already parse cleanly.
            BslContext ctx0 = loadBslText(projectName, relPath, fullText);
            if (ctx0.error != null || ctx0.resource == null) {
                r.message = "cannot parse target module: " + ctx0.error;
                return r;
            }
            if (!ctx0.resource.getErrors().isEmpty()) {
                r.message = "target module has parse errors; refusing to edit a non-parsing module ("
                        + firstError(ctx0.resource) + ")";
                return r;
            }
            Module module0 = firstModule(ctx0.resource);
            if (module0 == null) {
                r.message = "no BSL module parsed from " + relPath;
                return r;
            }
            r.moduleFound = true;

            // 2) locate the method by name (BSL names are case-insensitive) via the MODEL, never a regex.
            java.util.Set<String> baseline = new java.util.HashSet<>();
            com._1c.g5.v8.dt.bsl.model.Method target = null;
            int matches = 0;
            int baselineCount = 0;
            for (com._1c.g5.v8.dt.bsl.model.Method m : module0.allMethods()) {
                baseline.add(m.getName().toLowerCase());
                baselineCount++;
                if (methodName.equalsIgnoreCase(m.getName())) {
                    matches++;
                    target = m;
                }
            }
            if (target == null) {
                r.methodFound = false;
                r.message = "method not found in module: " + methodName;
                return r;
            }
            if (matches > 1) {
                r.methodFound = true;
                r.message = "refused: " + matches + " methods named '" + methodName
                        + "' in the module (ambiguous); fix the module first";
                return r;
            }
            r.methodFound = true;
            r.methodName = target.getName();
            r.methodKind = (target instanceof com._1c.g5.v8.dt.bsl.model.Function) ? "Function" : "Procedure";
            r.export = Boolean.valueOf(target.isExport());
            ICompositeNode mn = NodeModelUtils.getNode(target);
            if (mn == null) {
                r.message = "no node model for method " + r.methodName;
                return r;
            }
            r.lineFrom = mn.getStartLine();
            r.lineTo = mn.getEndLine();

            // 3) cut range: the method node [totalOffset, endOffset) – i.e. the method PLUS its leading
            //    hidden tokens (the blank separation above and the adjacent doc comments), which makes
            //    the delete the exact byte inverse of addMethod's "eol+eol+text" splice. Two guards keep
            //    content that only LOOKS attached: (a) a trailing same-line comment of the previous
            //    statement stays on its line; (b) comments separated from the method by a blank line
            //    (e.g. the module header) stay – the cut then starts after the LAST blank line.
            int sigStart = mn.getOffset();
            int cutEnd = mn.getEndOffset();
            int cutStart = mn.getTotalOffset();
            int firstNl = fullText.indexOf('\n', cutStart);
            if (firstNl >= 0 && firstNl < sigStart && !fullText.substring(cutStart, firstNl).isBlank()) {
                cutStart = firstNl + 1;   // (a) keep the previous line's trailing comment
            }
            boolean nonWsAbove = false;
            int lastBlankEnd = -1;
            for (int pos = cutStart; pos < sigStart; ) {
                int nl = fullText.indexOf('\n', pos);
                int end = (nl < 0 || nl >= sigStart) ? sigStart : nl + 1;
                if (fullText.substring(pos, end).isBlank()) {
                    if (nonWsAbove) {
                        lastBlankEnd = end;
                    }
                } else {
                    nonWsAbove = true;
                }
                pos = end;
            }
            if (lastBlankEnd > 0) {
                cutStart = lastBlankEnd;  // (b) keep the separated comment block + its blank line
            }
            String removed = fullText.substring(cutStart, cutEnd);
            String spliced = fullText.substring(0, cutStart) + fullText.substring(cutEnd);

            // 4) safety invariant: the module without the method must re-parse cleanly and lose exactly
            //    this one method (every other name still present).
            BslContext ctx1 = loadBslText(projectName, relPath, spliced);
            if (ctx1.error != null || ctx1.resource == null) {
                r.message = "module without the method failed to parse: " + ctx1.error;
                return r;
            }
            Module module1 = firstModule(ctx1.resource);
            if (module1 == null || !ctx1.resource.getErrors().isEmpty()) {
                r.valid = false;
                r.message = "refused: the result would not parse cleanly (the method's node range is not "
                        + "self-contained). " + firstError(ctx1.resource);
                return r;
            }
            java.util.Set<String> after = new java.util.HashSet<>();
            int newCount = 0;
            for (com._1c.g5.v8.dt.bsl.model.Method m : module1.allMethods()) {
                after.add(m.getName().toLowerCase());
                newCount++;
            }
            java.util.Set<String> expected = new java.util.HashSet<>(baseline);
            expected.remove(r.methodName.toLowerCase());
            if (newCount != baselineCount - 1 || !after.equals(expected)) {
                r.valid = false;
                r.message = "refused: the cut would not remove exactly the one method '" + r.methodName
                        + "' (before " + baselineCount + ", after " + newCount + ")";
                return r;
            }
            r.valid = true;
            r.deletedText = removed;
            if (r.deletedText.length() > DELETED_TEXT_CAP) {
                r.deletedText = r.deletedText.substring(0, DELETED_TEXT_CAP);
                r.deletedTextTruncated = true;
            }
            r.preview = previewAround(spliced, cutStart, cutStart);
            String exportWarn = Boolean.TRUE.equals(r.export)
                    ? " WARNING: exported method – deleting it breaks consumers; check callers via "
                            + "edt_find_references (method mode) and prefer deprecation."
                    : "";
            r.plan = "Delete " + r.methodKind + " " + r.methodName
                    + (Boolean.TRUE.equals(r.export) ? " (Export)" : "") + " from " + relPath
                    + " [lines " + r.lineFrom + "-" + r.lineTo + "]";

            if (!apply) {
                r.ok = true;
                r.applyPending = true;
                r.message = "dry-run: validated, nothing deleted. Re-call with apply=true AND force=true "
                        + "to delete." + exportWarn;
                return r;
            }
            if (!force) {
                r.message = "apply refused: deleting a method is destructive; pass force=true (the owner's "
                        + "explicit override) to delete." + exportWarn;
                return r;
            }

            // 5) apply – atomic file write; EDT re-reads the .bsl on its next model access.
            file.setContents(new ByteArrayInputStream(spliced.getBytes(StandardCharsets.UTF_8)),
                    true, true, new NullProgressMonitor());
            r.applied = true;
            r.ok = true;
            r.message = "method " + r.methodName + " deleted from " + relPath + "." + exportWarn;
            return r;
        } catch (Exception e) {
            r.message = "delete method failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : "");
            return r;
        }
    }

    private static Module firstModule(XtextResource res) {
        for (EObject e : res.getContents()) {
            if (e instanceof Module) {
                return (Module) e;
            }
        }
        return null;
    }

    private static String firstError(XtextResource res) {
        return (res == null || res.getErrors().isEmpty()) ? "" : String.valueOf(res.getErrors().get(0).getMessage());
    }

    /** Parse {@code text} as the BSL module at {@code modulePath} into a transient resource (real URI for scoping). */
    private BslContext loadBslText(String projectName, String modulePath, String text) {
        BslContext ctx = new BslContext();
        try {
            Injector injector = bslInjector();
            if (injector == null) {
                ctx.error = "BSL services unavailable (EDT BSL bundle not active)";
                return ctx;
            }
            IResourceFactory factory = injector.getInstance(IResourceFactory.class);
            XtextResourceSet resourceSet = injector.getInstance(XtextResourceSet.class);
            URI uri = URI.createURI("platform:/resource/" + projectName + "/" + modulePath);
            XtextResource resource = (XtextResource) factory.createResource(uri);
            resourceSet.getResources().add(resource);
            resource.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), resourceSet.getLoadOptions());
            EcoreUtil.resolveAll(resource);
            ctx.injector = injector;
            ctx.resource = resource;
        } catch (Exception e) {
            ctx.error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        }
        return ctx;
    }

    /** First {@code #Если Сервер ...} preprocessor whose condition text mentions the Сервер symbol (best-effort). */
    private static EObject findServerIf(Module module) {
        for (java.util.Iterator<EObject> it = module.eAllContents(); it.hasNext();) {
            EObject e = it.next();
            if (e instanceof com._1c.g5.v8.dt.bsl.model.IfPreprocessor) {
                ICompositeNode n = NodeModelUtils.getNode(e);
                if (n != null && n.getText().contains("Сервер")) {
                    return e;
                }
            }
        }
        return null;
    }

    /** Named {@code #Область} whose node lies within {@code scope} (any nesting level). */
    private static EObject findRegion(Module module, String name, int[] scope) {
        for (java.util.Iterator<EObject> it = module.eAllContents(); it.hasNext();) {
            EObject e = it.next();
            if (e instanceof com._1c.g5.v8.dt.bsl.model.RegionPreprocessor) {
                com._1c.g5.v8.dt.bsl.model.RegionPreprocessor rp = (com._1c.g5.v8.dt.bsl.model.RegionPreprocessor) e;
                if (name.equalsIgnoreCase(rp.getName())) {
                    ICompositeNode n = NodeModelUtils.getNode(e);
                    if (n != null && n.getOffset() >= scope[0] && n.getOffset() < scope[1]) {
                        return e;
                    }
                }
            }
        }
        return null;
    }

    /** The nearest enclosing {@code #Область} of an element, or {@code null} if it is outside any region. */
    private static EObject immediateRegion(EObject e) {
        for (EObject c = e.eContainer(); c != null; c = c.eContainer()) {
            if (c instanceof com._1c.g5.v8.dt.bsl.model.RegionPreprocessor) {
                return c;
            }
        }
        return null;
    }

    private static String normalizeEol(String s, String eol) {
        String lf = s.replace("\r\n", "\n").replace("\r", "\n");
        return "\n".equals(eol) ? lf : lf.replace("\n", eol);
    }

    private static String previewAround(String text, int from, int to) {
        int a = Math.max(0, from - 80);
        int b = Math.min(text.length(), to + 40);
        String snip = text.substring(a, b);
        if (snip.length() > 1200) {
            snip = snip.substring(0, 1200) + " ...";
        }
        return snip;
    }


    /** Resolve an FQN (+ optional moduleType) to a workspace-relative .bsl path; lists candidates if ambiguous. */
    private String resolveModulePath(IProject p, String fqn, String moduleType, ModuleTextResult r) {
        if (fqn == null || fqn.isBlank()) {
            r.message = "provide fqn or modulePath";
            return null;
        }
        String[] s = fqn.split("\\.");
        // src root: EDT projects keep sources under "src/"; fall back to project root.
        String src = p.getFolder("src").exists() ? "src/" : "";
        String folder;
        boolean isForm = false;
        if (s.length >= 4 && "Form".equals(s[s.length - 2])) {
            // <Type>.<Obj>.Form.<FormName>
            String fld = MD_FOLDER.get(s[0].toLowerCase());
            if (fld == null) {
                r.message = "unsupported owner type for a form: " + s[0];
                return null;
            }
            folder = src + fld + "/" + s[1] + "/Forms/" + s[s.length - 1];
            isForm = true;
        } else if (s.length == 2 && "CommonForm".equalsIgnoreCase(s[0])) {
            folder = src + "CommonForms/" + s[1];
            isForm = true;
        } else if (s.length == 2 && "CommonModule".equalsIgnoreCase(s[0])) {
            folder = src + "CommonModules/" + s[1];
        } else if (s.length == 2) {
            String fld = MD_FOLDER.get(s[0].toLowerCase());
            if (fld == null) {
                r.message = "unsupported object type: " + s[0] + " (pass modulePath directly)";
                return null;
            }
            folder = src + fld + "/" + s[1];
        } else {
            r.message = "cannot parse fqn: " + fqn + " (pass modulePath directly)";
            return null;
        }
        if (isForm || s.length == 2 && ("CommonModule".equalsIgnoreCase(s[0]))) {
            return folder + "/Module.bsl";
        }
        // A top object can have several module files. Pick by moduleType, else list candidates.
        if (moduleType != null && !moduleType.isBlank()) {
            return folder + "/" + moduleType + ".bsl";
        }
        IFolder f = p.getFolder(folder);
        if (f.exists()) {
            try {
                for (org.eclipse.core.resources.IResource m : f.members()) {
                    if (m instanceof IFile && m.getName().endsWith(".bsl")) {
                        r.availableModules.add(m.getName().replace(".bsl", ""));
                    }
                }
            } catch (CoreException ignored) {
                // fall through
            }
        }
        if (r.availableModules.size() == 1) {
            return folder + "/" + r.availableModules.get(0) + ".bsl";
        }
        r.message = r.availableModules.isEmpty()
                ? "no .bsl modules in " + folder
                : "several modules – pass moduleType (one of: " + String.join(", ", r.availableModules) + ")";
        return null;
    }

    // ---- Outgoing calls (call-graph one level out) -------------------------------------------------

    /** Default ExtAPI-layer module prefix (the service programmatic interface). */
    private static final String DEFAULT_EXTAPI_PREFIX = "ПрограммныйИнтерфейсСервиса";

    /** A distinct outgoing call from the analysed scope: {@code qualifier.method}, with site count. */
    public static final class OutgoingCall {
        public String qualifier;   // the module/object before the dot (null = local call within the module)
        public String method;
        public int count;          // number of call sites
        public int firstLine;
        public boolean extApi;     // qualifier matches the ExtAPI-layer prefix
    }

    /** Result of {@link #outgoingCalls}. */
    public static final class OutgoingCallsResult {
        public boolean found;
        public String fqn;
        public String modulePath;
        public String scope;       // "module" or the method name
        public boolean truncated;
        public List<OutgoingCall> calls = new ArrayList<>();
        public String message;
    }

    private static final int OUTGOING_CAP = 1000;

    /**
     * The methods CALLED BY a module or a single method – the reverse of
     * {@link #getReferences} (which gives inbound refs). Parses the BSL via the Xtext model and walks
     * invocation expressions, aggregating distinct {@code qualifier.method} call targets with a site
     * count, and flagging calls through the ExtAPI layer ({@code extApiPrefix}, default
     * {@code ПрограммныйИнтерфейсСервиса}). Resolve the module by {@code fqn} (+ {@code moduleType}) or
     * {@code modulePath}; optionally restrict to one {@code method}.
     */
    public OutgoingCallsResult outgoingCalls(String projectName, String fqn, String moduleType,
            String method, String modulePath, String extApiPrefix) {
        OutgoingCallsResult r = new OutgoingCallsResult();
        r.fqn = fqn;
        String prefix = (extApiPrefix == null || extApiPrefix.isBlank()) ? DEFAULT_EXTAPI_PREFIX : extApiPrefix;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        String relPath = modulePath;
        if (relPath == null || relPath.isBlank()) {
            ModuleTextResult mt = new ModuleTextResult();
            relPath = resolveModulePath(p, fqn, moduleType, mt);
            if (relPath == null) {
                r.message = mt.message;
                return r;
            }
        }
        r.modulePath = relPath;
        r.scope = (method == null || method.isBlank()) ? "module" : method;
        try {
            BslContext ctx = loadBsl(projectName, relPath, -1, -1, 0);
            if (ctx.error != null || ctx.resource == null) {
                r.message = "parse failed: " + ctx.error;
                return r;
            }
            Module module = null;
            for (EObject e : ctx.resource.getContents()) {
                if (e instanceof Module) {
                    module = (Module) e;
                    break;
                }
            }
            if (module == null) {
                r.message = "no BSL module parsed";
                return r;
            }
            EObject root = module;
            if (method != null && !method.isBlank()) {
                root = null;
                for (com._1c.g5.v8.dt.bsl.model.Method m : module.allMethods()) {
                    if (method.equalsIgnoreCase(m.getName())) {
                        root = m;
                        break;
                    }
                }
                if (root == null) {
                    r.message = "method not found: " + method;
                    return r;
                }
            }
            r.found = true;
            // Aggregate distinct qualifier.method targets.
            Map<String, OutgoingCall> agg = new java.util.LinkedHashMap<>();
            for (java.util.Iterator<EObject> it = root.eAllContents(); it.hasNext();) {
                EObject e = it.next();
                if (!(e instanceof com._1c.g5.v8.dt.bsl.model.Invocation)) {
                    continue;
                }
                com._1c.g5.v8.dt.bsl.model.FeatureAccess fa =
                        ((com._1c.g5.v8.dt.bsl.model.Invocation) e).getMethodAccess();
                if (fa == null) {
                    continue;
                }
                String name = fa.getName();
                if (name == null || name.isBlank()) {
                    continue;
                }
                String qualifier = null;
                if (fa instanceof com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess) {
                    EObject src = ((com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess) fa).getSource();
                    if (src instanceof com._1c.g5.v8.dt.bsl.model.FeatureAccess) {
                        qualifier = ((com._1c.g5.v8.dt.bsl.model.FeatureAccess) src).getName();
                    } else if (src != null) {
                        ICompositeNode sn = NodeModelUtils.getNode(src);
                        qualifier = (sn != null) ? sn.getText().strip() : null;
                    }
                }
                String key = (qualifier == null ? "" : qualifier) + "." + name;
                OutgoingCall oc = agg.get(key);
                if (oc == null) {
                    if (agg.size() >= OUTGOING_CAP) {
                        r.truncated = true;
                        break;
                    }
                    oc = new OutgoingCall();
                    oc.qualifier = qualifier;
                    oc.method = name;
                    oc.extApi = qualifier != null && (qualifier.equals(prefix) || qualifier.startsWith(prefix));
                    ICompositeNode n = NodeModelUtils.getNode(e);
                    oc.firstLine = (n != null) ? n.getStartLine() : 0;
                    agg.put(key, oc);
                }
                oc.count++;
            }
            r.calls.addAll(agg.values());
        } catch (Exception ex) {
            r.message = "outgoing calls failed: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
        }
        return r;
    }

    // ---- Method-level find references ------------------------------------------------------------

    /** Folder name -> FQN type prefix – the inverse of {@link #MD_FOLDER}, for a readable caller label. */
    private static final Map<String, String> MD_FOLDER_INV = Map.ofEntries(
            Map.entry("Catalogs", "Catalog"), Map.entry("Documents", "Document"),
            Map.entry("DocumentJournals", "DocumentJournal"), Map.entry("Enums", "Enum"),
            Map.entry("Reports", "Report"), Map.entry("DataProcessors", "DataProcessor"),
            Map.entry("ChartsOfCharacteristicTypes", "ChartOfCharacteristicTypes"),
            Map.entry("ChartsOfAccounts", "ChartOfAccounts"),
            Map.entry("ChartsOfCalculationTypes", "ChartOfCalculationTypes"),
            Map.entry("InformationRegisters", "InformationRegister"),
            Map.entry("AccumulationRegisters", "AccumulationRegister"),
            Map.entry("AccountingRegisters", "AccountingRegister"),
            Map.entry("CalculationRegisters", "CalculationRegister"),
            Map.entry("BusinessProcesses", "BusinessProcess"), Map.entry("Tasks", "Task"),
            Map.entry("ExchangePlans", "ExchangePlan"), Map.entry("Constants", "Constant"),
            Map.entry("CommonModules", "CommonModule"), Map.entry("CommonForms", "CommonForm"),
            Map.entry("CommonCommands", "CommonCommand"),
            Map.entry("HTTPServices", "HTTPService"), Map.entry("WebServices", "WebService"));

    /** Max modules PARSED (having passed the cheap text prefilter) before the scan gives up. */
    private static final int METHODREF_PARSE_CAP = 800;

    /** One BSL call site of a target method. */
    public static final class MethodRef {
        public String modulePath;   // project-relative .bsl of the caller
        public String module;       // best-effort readable module label (e.g. CommonModule.X), from the path
        public String method;       // the caller procedure/function containing the call (null = module-level)
        public int line;            // 1-based line of the call
        public String text;         // the call expression text, single-lined and capped
    }

    /** Result of {@link #findMethodReferences}. */
    public static final class MethodRefsResult {
        public boolean found;          // the scan ran (a usable qualifier was derived)
        public String fqn;
        public String method;
        public String qualifier;       // the call qualifier matched (the called module's call-name)
        public int total;              // total matching call sites (before the limit cap)
        public int returned;
        public boolean truncated;      // capped by limit or by the parse budget
        public int scannedModules;     // modules actually parsed (passed the text prefilter)
        public String message;
        public List<MethodRef> refs = new ArrayList<>();
    }

    /**
     * BSL call sites of a specific method – the method-level counterpart of {@link #getReferences}, whose
     * BM cross-reference index tracks metadata membership (subsystem content, the Configuration lists) but
     * NOT BSL call sites. Because no index maps "callers of CommonModule.X.Method", this walks the project's
     * BSL: a cheap raw-text prefilter (skip modules whose text lacks the method name) narrows to candidates,
     * then each candidate is parsed and its invocations are matched by qualifier ({@code X}) + method name –
     * which separates a qualified {@code X.Method(...)} call from same-named LOCAL procedures. Best-effort:
     * literal qualified calls only (not a call through a variable that holds the module, nor dynamic feature
     * names). The target is a common-module method: {@code fqn = CommonModule.X}, {@code method = Method}.
     */
    public MethodRefsResult findMethodReferences(String projectName, String fqn, String moduleType,
            String method, int limit) {
        MethodRefsResult r = new MethodRefsResult();
        r.fqn = fqn;
        r.method = method;
        if (method == null || method.isBlank()) {
            r.message = "method is required";
            return r;
        }
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        // The call qualifier = the called module's call-name. For CommonModule.X that is X; otherwise
        // best-effort the last FQN segment (object-manager calls use a 2-part qualifier – out of scope).
        String qualifier = null;
        if (fqn != null && !fqn.isBlank()) {
            String[] s = fqn.split("\\.");
            qualifier = s[s.length - 1];
        }
        if (qualifier == null || qualifier.isBlank()) {
            r.message = "provide fqn (e.g. CommonModule.X) to identify the called module";
            return r;
        }
        r.qualifier = qualifier;
        final int cap = limit > 0 ? limit : Integer.MAX_VALUE;
        final String methodLc = method.toLowerCase(java.util.Locale.ROOT);
        try {
            List<IFile> bslFiles = new ArrayList<>();
            collectBslFiles(p, bslFiles);
            int parsed = 0;
            for (IFile file : bslFiles) {
                String relPath = file.getProjectRelativePath().toString();
                String text;
                try {
                    text = readText(file);
                } catch (Exception ex) {
                    continue;
                }
                // Cheap prefilter: the method name must appear literally (BSL identifiers are
                // case-insensitive) – otherwise this file cannot hold the call. Skip without parsing.
                if (text.toLowerCase(java.util.Locale.ROOT).indexOf(methodLc) < 0) {
                    continue;
                }
                if (parsed >= METHODREF_PARSE_CAP) {
                    r.truncated = true;
                    break;
                }
                parsed++;
                BslContext ctx = loadBsl(projectName, relPath, -1, -1, 0);
                if (ctx.error != null || ctx.resource == null) {
                    continue;
                }
                Module module = null;
                for (EObject e : ctx.resource.getContents()) {
                    if (e instanceof Module) {
                        module = (Module) e;
                        break;
                    }
                }
                if (module == null) {
                    continue;
                }
                for (java.util.Iterator<EObject> it = module.eAllContents(); it.hasNext();) {
                    EObject e = it.next();
                    if (!(e instanceof com._1c.g5.v8.dt.bsl.model.Invocation)) {
                        continue;
                    }
                    com._1c.g5.v8.dt.bsl.model.FeatureAccess fa =
                            ((com._1c.g5.v8.dt.bsl.model.Invocation) e).getMethodAccess();
                    // Only qualified calls (X.Method) – skips same-named local procedures.
                    if (!(fa instanceof com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess)) {
                        continue;
                    }
                    String name = fa.getName();
                    if (name == null || !name.equalsIgnoreCase(method)) {
                        continue;
                    }
                    EObject src = ((com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess) fa).getSource();
                    String q = (src instanceof com._1c.g5.v8.dt.bsl.model.FeatureAccess)
                            ? ((com._1c.g5.v8.dt.bsl.model.FeatureAccess) src).getName() : null;
                    if (q == null || !q.equalsIgnoreCase(qualifier)) {
                        continue;
                    }
                    r.total++;
                    if (r.refs.size() >= cap) {
                        r.truncated = true;
                        continue; // keep counting total, stop collecting
                    }
                    MethodRef mr = new MethodRef();
                    mr.modulePath = relPath;
                    mr.module = pathToModuleLabel(relPath);
                    mr.method = enclosingMethodName(e);
                    ICompositeNode n = NodeModelUtils.getNode(e);
                    mr.line = (n != null) ? n.getStartLine() : 0;
                    mr.text = (n != null) ? oneLine(n.getText()) : null;
                    r.refs.add(mr);
                }
            }
            r.scannedModules = parsed;
            r.found = true;
            r.returned = r.refs.size();
        } catch (Exception ex) {
            r.message = "find method references failed: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
        }
        return r;
    }

    /** Collect every .bsl file under the project's src (or root) – the candidate set. */
    private void collectBslFiles(IProject p, List<IFile> out) throws CoreException {
        final org.eclipse.core.resources.IContainer root =
                p.getFolder("src").exists() ? p.getFolder("src") : p;
        root.accept(res -> {
            if (res instanceof IFile && res.getName().endsWith(".bsl")) {
                out.add((IFile) res);
            }
            return true;
        });
    }

    /** The name of the BSL Method (procedure/function) containing a node; null if module-level. */
    private String enclosingMethodName(EObject e) {
        for (EObject c = e; c != null; c = c.eContainer()) {
            if (c instanceof com._1c.g5.v8.dt.bsl.model.Method) {
                return ((com._1c.g5.v8.dt.bsl.model.Method) c).getName();
            }
        }
        return null;
    }

    /** Best-effort readable module label from a project-relative .bsl path (inverse of the folder map). */
    private String pathToModuleLabel(String relPath) {
        try {
            String pth = relPath.replace('\\', '/');
            int i = pth.indexOf("src/");
            String rest = (i >= 0) ? pth.substring(i + 4) : pth;
            String[] parts = rest.split("/");
            if (parts.length < 2) {
                return null;
            }
            String type = MD_FOLDER_INV.get(parts[0]);
            if (type == null) {
                return null;
            }
            if ("CommonModule".equals(type) || "CommonForm".equals(type) || "CommonCommand".equals(type)) {
                return type + "." + parts[1];
            }
            StringBuilder sb = new StringBuilder(type).append('.').append(parts[1]);
            if (parts.length >= 5 && "Forms".equals(parts[2])) {
                sb.append(".Form.").append(parts[3]);
            } else {
                String last = parts[parts.length - 1];
                if (last.endsWith(".bsl") && !"Module.bsl".equals(last)) {
                    sb.append(" (").append(last.substring(0, last.length() - 4)).append(')');
                }
            }
            return sb.toString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Collapse a multi-line snippet to a single trimmed line, capped for readability. */
    private static String oneLine(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > 200 ? t.substring(0, 200) + "..." : t;
    }

    // ---- Structure arguments of outgoing calls – static key analysis (best-effort) ----------

    /** One outgoing call site + the top-level keys of the Структура argument passed to it (best-effort). */
    public static final class OutgoingStructureSite {
        public String qualifier;   // call qualifier (the module/object the method is called on)
        public String method;      // the method called
        public int line;
        public String arg;         // the structure variable (or inline helper) passed to the call
        public List<String> keys = new ArrayList<>();  // collected top-level keys
        public String viaHelper;   // a seed/template helper the structure came from, if any
        public boolean partial;    // best-effort: keys may be incomplete (dynamic / external helper / branches)
    }

    /** Result of {@link #outgoingStructures}. */
    public static final class OutgoingStructuresResult {
        public boolean found;
        public String fqn;
        public String modulePath;
        public String scope;
        public String message;
        public boolean truncated;
        public List<OutgoingStructureSite> structures = new ArrayList<>();
    }

    /**
     * Best-effort static analysis of the Структура arguments passed to outgoing (qualified) calls: for
     * each call site in a method/module, the top-level keys of the structure passed as an argument,
     * collected from {@code <var>.Вставить("key", ...)} inserts on that variable, with one-level expansion
     * of a seed/template helper ({@code <var> = Helper(...)}) resolvable in the same module. An optional
     * {@code qualifierFilter} (prefix) scopes to one layer (e.g. ПрограммныйИнтерфейсСервиса). Heuristic –
     * flow-insensitive, literal keys only, does not follow external helpers, dynamic keys or
     * {@code Новый Структура("a,b")} constructors; {@code partial=true} flags an incomplete result.
     * Read-only; never throws.
     */
    public OutgoingStructuresResult outgoingStructures(String projectName, String fqn, String moduleType,
            String method, String modulePath, String qualifierFilter) {
        OutgoingStructuresResult r = new OutgoingStructuresResult();
        r.fqn = fqn;
        // Optional qualifier filter (a prefix). Null = every qualified outgoing call that passes a
        // structure (e.g. set it to "ПрограммныйИнтерфейсСервиса" to scope to an ExtAPI wrapper layer).
        String filter = (qualifierFilter == null || qualifierFilter.isBlank()) ? null : qualifierFilter;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        String relPath = modulePath;
        if (relPath == null || relPath.isBlank()) {
            ModuleTextResult mt = new ModuleTextResult();
            relPath = resolveModulePath(p, fqn, moduleType, mt);
            if (relPath == null) {
                r.message = mt.message;
                return r;
            }
        }
        r.modulePath = relPath;
        r.scope = (method == null || method.isBlank()) ? "module" : method;
        try {
            BslContext ctx = loadBsl(projectName, relPath, -1, -1, 0);
            if (ctx.error != null || ctx.resource == null) {
                r.message = "parse failed: " + ctx.error;
                return r;
            }
            Module module = null;
            for (EObject e : ctx.resource.getContents()) {
                if (e instanceof Module) {
                    module = (Module) e;
                    break;
                }
            }
            if (module == null) {
                r.message = "no BSL module parsed";
                return r;
            }
            EObject root = module;
            if (method != null && !method.isBlank()) {
                root = null;
                for (com._1c.g5.v8.dt.bsl.model.Method m : module.allMethods()) {
                    if (method.equalsIgnoreCase(m.getName())) {
                        root = m;
                        break;
                    }
                }
                if (root == null) {
                    r.message = "method not found: " + method;
                    return r;
                }
            }
            r.found = true;
            Map<String, java.util.LinkedHashSet<String>> inserts = collectStructInserts(root);
            Map<String, String> assigns = collectAssigns(root);
            for (java.util.Iterator<EObject> it = root.eAllContents(); it.hasNext();) {
                EObject e = it.next();
                if (!(e instanceof com._1c.g5.v8.dt.bsl.model.Invocation)) {
                    continue;
                }
                com._1c.g5.v8.dt.bsl.model.Invocation inv = (com._1c.g5.v8.dt.bsl.model.Invocation) e;
                com._1c.g5.v8.dt.bsl.model.FeatureAccess fa = inv.getMethodAccess();
                if (!(fa instanceof com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess)) {
                    continue;
                }
                EObject src = ((com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess) fa).getSource();
                String qualifier = (src instanceof com._1c.g5.v8.dt.bsl.model.FeatureAccess)
                        ? ((com._1c.g5.v8.dt.bsl.model.FeatureAccess) src).getName() : null;
                if (qualifier == null) {
                    continue;   // only qualified (outgoing) calls
                }
                if (filter != null && !(qualifier.equals(filter) || qualifier.startsWith(filter))) {
                    continue;
                }
                ICompositeNode node = NodeModelUtils.getNode(e);
                int line = (node != null) ? node.getStartLine() : 0;
                for (com._1c.g5.v8.dt.bsl.model.Expression param : inv.getParams()) {
                    OutgoingStructureSite site = describeStructureArg(param, module, inserts, assigns);
                    if (site == null) {
                        continue;   // arg is not a structure (string / number / ...) – skip
                    }
                    if (r.structures.size() >= OUTGOING_CAP) {
                        r.truncated = true;
                        break;
                    }
                    site.qualifier = qualifier;
                    site.method = fa.getName();
                    site.line = line;
                    r.structures.add(site);
                }
                if (r.truncated) {
                    break;
                }
            }
        } catch (Exception ex) {
            r.message = "outgoing structures failed: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
        }
        return r;
    }

    /** Describe one ExtAPI-call argument as a request body (keys + seed helper), or null if not a structure. */
    private OutgoingStructureSite describeStructureArg(com._1c.g5.v8.dt.bsl.model.Expression param, Module module,
            Map<String, java.util.LinkedHashSet<String>> inserts, Map<String, String> assigns) {
        // inline helper call: Метод(Шаблон())
        if (param instanceof com._1c.g5.v8.dt.bsl.model.Invocation) {
            com._1c.g5.v8.dt.bsl.model.FeatureAccess hfa =
                    ((com._1c.g5.v8.dt.bsl.model.Invocation) param).getMethodAccess();
            if (hfa instanceof com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess) {
                OutgoingStructureSite s = new OutgoingStructureSite();
                s.arg = hfa.getName() + "()";
                s.viaHelper = hfa.getName();
                s.partial = !expandHelper(module, hfa.getName(), s);
                return s.keys.isEmpty() ? null : s;
            }
            return null;
        }
        // a plain local variable: Метод(Запрос)
        if (param instanceof com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess) {
            String var = ((com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess) param).getName();
            java.util.LinkedHashSet<String> direct = inserts.get(var);
            String helper = assigns.get(var);
            if ((direct == null || direct.isEmpty()) && helper == null) {
                return null;   // not a structure we can describe
            }
            OutgoingStructureSite s = new OutgoingStructureSite();
            s.arg = var;
            if (direct != null) {
                s.keys.addAll(direct);
            }
            if (helper != null) {
                s.viaHelper = helper;
                s.partial = !expandHelper(module, helper, s);   // external/unresolved helper => incomplete
            }
            return s;
        }
        return null;
    }

    /** Expand a seed/template helper one level: add the keys of the structure it builds and returns. */
    private boolean expandHelper(Module module, String helperName, OutgoingStructureSite s) {
        com._1c.g5.v8.dt.bsl.model.Method helper = null;
        for (com._1c.g5.v8.dt.bsl.model.Method m : module.allMethods()) {
            if (helperName.equalsIgnoreCase(m.getName())) {
                helper = m;
                break;
            }
        }
        if (helper == null) {
            return false;   // helper not in this module – caller marks partial
        }
        Map<String, java.util.LinkedHashSet<String>> hi = collectStructInserts(helper);
        String retVar = returnVarOf(helper);
        java.util.LinkedHashSet<String> keys = (retVar != null) ? hi.get(retVar) : null;
        if (keys == null) {
            keys = new java.util.LinkedHashSet<>();   // no single return var: merge all (best-effort)
            for (java.util.LinkedHashSet<String> v : hi.values()) {
                keys.addAll(v);
            }
        }
        for (String k : keys) {
            if (!s.keys.contains(k)) {
                s.keys.add(k);
            }
        }
        return true;
    }

    /** Collect {@code <var>.Вставить("key", ...)} literal keys grouped by receiver variable, within a scope. */
    private Map<String, java.util.LinkedHashSet<String>> collectStructInserts(EObject scope) {
        Map<String, java.util.LinkedHashSet<String>> map = new java.util.LinkedHashMap<>();
        for (java.util.Iterator<EObject> it = scope.eAllContents(); it.hasNext();) {
            EObject e = it.next();
            if (!(e instanceof com._1c.g5.v8.dt.bsl.model.Invocation)) {
                continue;
            }
            com._1c.g5.v8.dt.bsl.model.FeatureAccess fa =
                    ((com._1c.g5.v8.dt.bsl.model.Invocation) e).getMethodAccess();
            if (!(fa instanceof com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess)) {
                continue;
            }
            String name = fa.getName();
            if (!"Вставить".equalsIgnoreCase(name) && !"Insert".equalsIgnoreCase(name)) {
                continue;
            }
            EObject recv = ((com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess) fa).getSource();
            if (!(recv instanceof com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess)) {
                continue;   // only direct `Var.Вставить(...)`, not nested `Var.Sub.Вставить(...)`
            }
            String var = ((com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess) recv).getName();
            java.util.List<com._1c.g5.v8.dt.bsl.model.Expression> args =
                    ((com._1c.g5.v8.dt.bsl.model.Invocation) e).getParams();
            if (args.isEmpty()) {
                continue;
            }
            String key = literalKey(args.get(0));
            if (key != null) {
                map.computeIfAbsent(var, k -> new java.util.LinkedHashSet<>()).add(key);
            }
        }
        return map;
    }

    /** Collect {@code <var> = SomeFunc(...)} assignments (the seeding helper name) within a scope. */
    private Map<String, String> collectAssigns(EObject scope) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (java.util.Iterator<EObject> it = scope.eAllContents(); it.hasNext();) {
            EObject e = it.next();
            if (!(e instanceof com._1c.g5.v8.dt.bsl.model.SimpleStatement)) {
                continue;
            }
            com._1c.g5.v8.dt.bsl.model.SimpleStatement st = (com._1c.g5.v8.dt.bsl.model.SimpleStatement) e;
            if (!(st.getLeft() instanceof com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess)
                    || !(st.getRight() instanceof com._1c.g5.v8.dt.bsl.model.Invocation)) {
                continue;
            }
            String var = ((com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess) st.getLeft()).getName();
            com._1c.g5.v8.dt.bsl.model.FeatureAccess rfa =
                    ((com._1c.g5.v8.dt.bsl.model.Invocation) st.getRight()).getMethodAccess();
            // only a same-module function call (StaticFeatureAccess) is expandable; first assignment wins
            if (rfa instanceof com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess && !map.containsKey(var)) {
                map.put(var, rfa.getName());
            }
        }
        return map;
    }

    /** The variable name returned by a method's first {@code Возврат <var>}, or null. */
    private String returnVarOf(com._1c.g5.v8.dt.bsl.model.Method m) {
        for (java.util.Iterator<EObject> it = m.eAllContents(); it.hasNext();) {
            EObject e = it.next();
            if (e instanceof com._1c.g5.v8.dt.bsl.model.ReturnStatement) {
                EObject expr = ((com._1c.g5.v8.dt.bsl.model.ReturnStatement) e).getExpression();
                if (expr instanceof com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess) {
                    return ((com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess) expr).getName();
                }
            }
        }
        return null;
    }

    /** The string value of a BSL string-literal expression (node text without quotes), or null. */
    private String literalKey(EObject expr) {
        ICompositeNode n = NodeModelUtils.getNode(expr);
        if (n == null) {
            return null;
        }
        String t = n.getText().strip();
        if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            return t.substring(1, t.length() - 1);
        }
        return null;   // not a string literal (dynamic key) – skip
    }

    /** A BSL module parsed into a transient Xtext resource, positioned at an offset. */
    private static final class BslContext {
        Injector injector;
        XtextResource resource;
        int offset;
        String error;
    }

    /**
     * Parse {@code modulePath} of {@code projectName} into a transient Xtext resource under its real
     * platform URI (so BSL scoping resolves project symbols) and locate the offset. Shared by the
     * BSL navigation tools. {@code offsetArg} < 0 derives the offset from 1-based line/column.
     */
    private BslContext loadBsl(String projectName, String modulePath, int line, int column, int offsetArg) {
        BslContext ctx = new BslContext();
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            ctx.error = "project not found or closed: " + projectName;
            return ctx;
        }
        IFile file = p.getFile(modulePath);
        if (!file.exists()) {
            ctx.error = "module not found: " + modulePath;
            return ctx;
        }
        try {
            String text = readText(file);
            int offset = (offsetArg >= 0) ? offsetArg : computeOffset(text, line, column);
            if (offset < 0 || offset > text.length()) {
                ctx.error = "position out of range";
                return ctx;
            }
            Injector injector = bslInjector();
            if (injector == null) {
                ctx.error = "BSL services unavailable (EDT BSL bundle not active)";
                return ctx;
            }
            IResourceFactory factory = injector.getInstance(IResourceFactory.class);
            XtextResourceSet resourceSet = injector.getInstance(XtextResourceSet.class);
            URI uri = URI.createURI("platform:/resource/" + projectName + "/" + modulePath);
            XtextResource resource = (XtextResource) factory.createResource(uri);
            resourceSet.getResources().add(resource);
            resource.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), resourceSet.getLoadOptions());
            EcoreUtil.resolveAll(resource);
            ctx.injector = injector;
            ctx.resource = resource;
            ctx.offset = offset;
        } catch (Exception e) {
            ctx.error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        }
        return ctx;
    }

    /** Result of {@link #symbolInfo}. */
    public static final class SymbolInfoResult {
        public boolean found;
        public String message;
        public String elementType;                       // EClass of the element under the position
        public String name;                              // symbol name, when the element is named
        public List<String> types = new ArrayList<>();   // computed value type(s) of the expression
    }

    /**
     * Type/symbol info at a position in a BSL module: the element under the cursor (kind, name) plus
     * the computed value type(s) of the expression via EDT's dynamic BSL type system.
     */
    public SymbolInfoResult symbolInfo(String projectName, String modulePath, int line, int column, int offsetArg) {
        SymbolInfoResult r = new SymbolInfoResult();
        try {
            BslContext ctx = loadBsl(projectName, modulePath, line, column, offsetArg);
            if (ctx.error != null) {
                r.message = ctx.error;
                return r;
            }
            EObjectAtOffsetHelper helper = ctx.injector.getInstance(EObjectAtOffsetHelper.class);
            // Use the contained expression under the cursor (not the resolved declaration) so a
            // variable/feature reference yields the expression's computed value type. The symbol
            // name comes from the cross-referenced target when there is one.
            EObject element = helper.resolveContainedElementAt(ctx.resource, ctx.offset);
            if (element == null) {
                element = helper.resolveElementAt(ctx.resource, ctx.offset);
            }
            if (element == null) {
                r.message = "no element at this position";
                return r;
            }
            r.found = true;
            r.elementType = element.eClass().getName();
            EObject resolved = helper.resolveCrossReferencedElementAt(ctx.resource, ctx.offset);
            if (resolved instanceof NamedElement) {
                r.name = ((NamedElement) resolved).getName();
            } else if (element instanceof NamedElement) {
                r.name = ((NamedElement) element).getName();
            }
            try {
                TypesComputer typesComputer = ctx.injector.getInstance(TypesComputer.class);
                List<TypeItem> types = typesComputer.computeTypes(element, Environments.ALL);
                if (types != null) {
                    for (TypeItem ti : types) {
                        String n = typeItemName(ti);
                        if (n != null && !n.isBlank()) {
                            r.types.add(n);
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // type computation is best-effort
            }
        } catch (Throwable t) {
            r.message = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
        }
        return r;
    }



    /** Readable (Russian) name of a type item; best-effort, never throws. */
    private String typeItemName(TypeItem ti) {
        try {
            String n = McoreUtil.getTypeNameRu(ti);
            if (n == null || n.isBlank()) {
                n = McoreUtil.getTypeName(ti);
            }
            return n;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String readText(IFile file) throws Exception {
        try (InputStream in = file.getContents()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private int computeOffset(String text, int line, int column) {
        if (line < 1 || column < 1) {
            return -1;
        }
        int off = 0;
        int cur = 1;
        while (cur < line) {
            int nl = text.indexOf('\n', off);
            if (nl < 0) {
                return -1;
            }
            off = nl + 1;
            cur++;
        }
        return off + (column - 1);
    }

    private String fqnOf(MdObject md) {
        if (md instanceof IBmObject) {
            String f = ((IBmObject) md).bmGetFqn();
            if (f != null && !f.isBlank()) {
                return f;
            }
        }
        return md.eClass().getName() + "." + md.getName();
    }

    // ---- Method changes an extension makes to the base configuration -----------------------------

    /** Outcome of {@link #methodChanges}. */
    public static final class MethodChangesResult {
        public boolean ok;
        public String project;
        public boolean changesMethods;   // the extension intercepts at least one base method
        public int count;
        public boolean truncated;
        public String message;
        public final List<SearchHit> hits = new ArrayList<>();
    }

    /**
     * Report whether an extension project changes methods of the base configuration - that is,
     * whether any of its modules carries an interception annotation ({@code &Вместо} / {@code &Перед}
     * / {@code &После} / {@code &ИзменениеИКонтроль} and their English spellings).
     *
     * <p>This decides how the extension must be REGISTERED in an infobase, which is what makes it
     * worth answering mechanically. Both {@code ibcmd} and an update from EDT register an extension
     * with safe mode and dangerous-action protection ON by default; under those an intercepted method
     * cannot do what the base method could, so for an extension that changes methods both have to be
     * cleared - see {@code edt_extension_properties}.
     */
    public MethodChangesResult methodChanges(String projectName) {
        MethodChangesResult r = new MethodChangesResult();
        r.project = projectName;
        SearchResult sr = searchModules(projectName, METHOD_CHANGE_ANNOTATIONS, true, false, null, 200);
        r.ok = sr.ok;
        if (!sr.ok) {
            r.message = sr.message;
            return r;
        }
        r.hits.addAll(sr.hits);
        r.count = sr.hits.size();
        r.truncated = sr.truncated;
        r.changesMethods = r.count > 0;
        r.message = r.changesMethods
                ? (r.count + (r.truncated ? "+" : "") + " method change(s) across " + sr.modulesScanned
                   + " module(s) - the extension must be registered with safe mode and dangerous-action"
                   + " protection OFF")
                : ("no method changes across " + sr.modulesScanned + " module(s)");
        return r;
    }

    /** Annotations by which an extension takes over a method of the base configuration. */
    private static final String METHOD_CHANGE_ANNOTATIONS =
            "&\\s*(Вместо|Перед|После|ИзменениеИКонтроль|Instead|Before|After|ChangeAndValidate)\\s*\\(";

    // ---- Full-text search across BSL modules -----------------------------------------------------

    /** One matching line. */
    public static final class SearchHit {
        public String project;
        public String modulePath;   // project-relative .bsl path
        public int line;            // 1-based
        public String text;         // the matching line, trimmed
        public boolean unsaved;     // the text came from an open editor with unsaved changes
    }

    /** Outcome of {@link #searchModules}. */
    public static final class SearchResult {
        public boolean ok;
        public String pattern;
        public boolean regex;
        public boolean caseSensitive;
        public int modulesScanned;
        public int filesWithUnsavedChanges;
        public boolean truncated;
        public String message;
        public final List<SearchHit> hits = new ArrayList<>();
    }

    /**
     * Search the text of every BSL module in a project (or in all open projects).
     *
     * <p>The one exploratory step that always fell back to disk tools: {@code edt_find_references}
     * answers "who calls this method", but not "where does this substring appear". Reading goes
     * through Eclipse's file buffers, so a module open in an editor is searched as it currently
     * stands - unsaved edits included - rather than as last written to disk.
     *
     * @param projectName project to search; {@code null} searches every open project
     * @param pattern     substring, or a regular expression when {@code regex} is set
     * @param pathFilter  optional substring the module path must contain (e.g. {@code CommonModules})
     * @param maxResults  hit cap; the result says whether it truncated
     */
    public SearchResult searchModules(String projectName, String pattern, boolean regex,
            boolean caseSensitive, String pathFilter, int maxResults) {
        SearchResult r = new SearchResult();
        r.pattern = pattern;
        r.regex = regex;
        r.caseSensitive = caseSensitive;
        if (pattern == null || pattern.isEmpty()) {
            r.message = "pattern is required";
            return r;
        }
        List<IProject> projects = new ArrayList<>();
        if (projectName != null && !projectName.isBlank()) {
            IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!p.exists() || !p.isOpen()) {
                r.message = "project not found or closed: " + projectName;
                return r;
            }
            projects.add(p);
        } else {
            for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (p.isOpen()) {
                    projects.add(p);
                }
            }
        }

        Pattern compiled = null;
        if (regex) {
            try {
                compiled = Pattern.compile(pattern, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
            } catch (RuntimeException ex) {
                r.message = "pattern does not compile as a regular expression: " + ex.getMessage();
                return r;
            }
        }
        String needle = caseSensitive ? pattern : pattern.toLowerCase(java.util.Locale.ROOT);
        int cap = (maxResults > 0) ? maxResults : 200;

        for (IProject p : projects) {
            List<org.eclipse.core.resources.IFile> modules = new ArrayList<>();
            try {
                collectBslFiles(p, modules);
            } catch (CoreException ex) {
                r.message = "could not enumerate modules of " + p.getName() + ": " + ex.getMessage();
                continue;
            }
            for (org.eclipse.core.resources.IFile file : modules) {
                String path = file.getProjectRelativePath().toString();
                if (pathFilter != null && !pathFilter.isBlank() && !path.contains(pathFilter)) {
                    continue;
                }
                String[] text = readModuleText(file);
                if (text == null) {
                    continue;
                }
                r.modulesScanned++;
                if ("unsaved".equals(text[1])) {
                    r.filesWithUnsavedChanges++;
                }
                int lineNo = 0;
                for (String line : text[0].split("\r\n|\r|\n", -1)) {
                    lineNo++;
                    boolean hit = (compiled != null) ? compiled.matcher(line).find()
                            : (caseSensitive ? line.contains(needle)
                                    : line.toLowerCase(java.util.Locale.ROOT).contains(needle));
                    if (!hit) {
                        continue;
                    }
                    if (r.hits.size() >= cap) {
                        r.truncated = true;
                        break;
                    }
                    SearchHit h = new SearchHit();
                    h.project = p.getName();
                    h.modulePath = path;
                    h.line = lineNo;
                    h.text = line.trim();
                    h.unsaved = "unsaved".equals(text[1]);
                    r.hits.add(h);
                }
                if (r.truncated) {
                    break;
                }
            }
            if (r.truncated) {
                break;
            }
        }
        r.ok = true;
        if (r.message == null) {
            r.message = "scanned " + r.modulesScanned + " module(s), " + r.hits.size() + " hit(s)"
                    + (r.truncated ? " (capped at " + cap + ")" : "")
                    + (r.filesWithUnsavedChanges > 0
                            ? "; " + r.filesWithUnsavedChanges + " read from unsaved editor buffers" : "");
        }
        return r;
    }

    /** Every {@code .bsl} under a project. */
    private static void collectBslFiles(org.eclipse.core.resources.IContainer container,
            List<org.eclipse.core.resources.IFile> out) throws CoreException {
        for (org.eclipse.core.resources.IResource member : container.members()) {
            if (member instanceof org.eclipse.core.resources.IContainer) {
                collectBslFiles((org.eclipse.core.resources.IContainer) member, out);
            } else if (member instanceof org.eclipse.core.resources.IFile
                    && "bsl".equalsIgnoreCase(member.getFileExtension())) {
                out.add((org.eclipse.core.resources.IFile) member);
            }
        }
    }

    /**
     * A module's current text plus where it came from: {@code "unsaved"} when an editor holds
     * modifications that are not on disk yet, {@code "file"} otherwise. Returns {@code null} when the
     * module cannot be read at all.
     */
    private static String[] readModuleText(org.eclipse.core.resources.IFile file) {
        try {
            org.eclipse.core.filebuffers.ITextFileBufferManager mgr =
                    org.eclipse.core.filebuffers.FileBuffers.getTextFileBufferManager();
            if (mgr != null) {
                org.eclipse.core.filebuffers.ITextFileBuffer buf = mgr.getTextFileBuffer(
                        file.getFullPath(), org.eclipse.core.filebuffers.LocationKind.IFILE);
                if (buf != null && buf.getDocument() != null) {
                    return new String[] {buf.getDocument().get(), buf.isDirty() ? "unsaved" : "file"};
                }
            }
        } catch (RuntimeException ignored) {
            // no buffer for this file - fall through to reading the resource
        }
        try (java.io.InputStream in = file.getContents()) {
            return new String[] {new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
                    "file"};
        } catch (CoreException | java.io.IOException ex) {
            return null;
        }
    }

}
