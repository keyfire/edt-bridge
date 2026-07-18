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
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseConfigurationChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateCallback;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateConflictResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolution;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.Section;
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

// Phase 3 debugger
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.model.IDebugTarget;
import com._1c.g5.v8.dt.debug.core.IDebugConfigurationAttributes;
import com._1c.g5.v8.dt.debug.core.IDebugConfigurationTypes;
import com._1c.g5.v8.dt.debug.core.launchconfigurations.DebugServerLocationType;
import com._1c.g5.v8.dt.debug.core.launchconfigurations.DtLaunch;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTargetManager;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugTargetThread;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;
import com._1c.g5.v8.dt.debug.core.model.IDebugMonitoringManager;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType;
import com._1c.g5.v8.dt.debug.model.area.DebugAreaInfo;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationEngine;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationRequest;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationResult;
import com._1c.g5.v8.dt.debug.core.model.evaluation.EvaluationRequest;
import com._1c.g5.v8.dt.debug.core.model.values.BslValuePath;
import com._1c.g5.v8.dt.debug.model.calculations.CalculationResultBaseData;
import com._1c.g5.v8.dt.debug.model.calculations.BaseValueInfoData;
import com._1c.g5.v8.dt.debug.model.calculations.ViewInterface;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.eclipse.debug.core.model.IThread;
import com._1c.g5.wiring.ServiceAccess;
import com.google.inject.Injector;

/**
 * Thin adapter over EDT/Eclipse model access. This single class is the only place that
 * touches the IDE model, so an EDT/Eclipse API change is contained here ( condition).
 *
 * M0 uses ONLY standard Eclipse resources API: EDT validation results surface as IMarker
 * problems on project resources. M1 TODO: extend with com._1c.g5.v8.dt.* for DT-specific
 * data (check id, BLOCKER/CRITICAL/... severities, validate_query, semantic refs/def).
 */
public final class EdtModelGateway {

    /**
     * Dedicated single thread that owns the SWT Display used for form rendering – isolated from both
     * the MCP HTTP thread and a GUI workbench's main UI thread, so {@code edt_form_render} works in
     * either a headless CLI or a running GUI EDT without freezing the editor.
     */
    private static final ExecutorService RENDER_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "edt-bridge-render");
        t.setDaemon(true);
        return t;
    });

    /** A single validation problem (errors and warnings). */
    public static final class Problem {
        public String project;
        public String severity; // ERROR | WARNING | INFO
        public String message;
        public String resource; // project-relative path
        public int line;        // -1 if not applicable
        public String markerType;
        public String source;   // "eclipse" (Eclipse IMarker) | "edt-check" (EDT check store)
        public String checkId;  // EDT check id, e.g. com.e1c.v8codestyle.bsl:module-unused-local-variable
        public String edtSeverity; // EDT grade for edt-check: BLOCKER/CRITICAL/MAJOR/MINOR/TRIVIAL
        public String location; // EDT location, e.g. "строка 8" or a field presentation
    }

    public List<Problem> getProjectErrors(String projectName) throws CoreException {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

        List<IProject> projects = new ArrayList<>();
        if (projectName != null && !projectName.isBlank()) {
            IProject p = root.getProject(projectName);
            if (p.exists() && p.isOpen()) {
                projects.add(p);
            }
        } else {
            for (IProject p : root.getProjects()) {
                if (p.isOpen()) {
                    projects.add(p);
                }
            }
        }

        List<Problem> out = new ArrayList<>();
        for (IProject p : projects) {
            // 1) Standard Eclipse markers (syntax/build problems surfaced as IMarker.PROBLEM).
            IMarker[] markers = p.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
            for (IMarker m : markers) {
                int sev = m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
                if (sev != IMarker.SEVERITY_ERROR && sev != IMarker.SEVERITY_WARNING) {
                    continue;
                }
                Problem pr = new Problem();
                pr.project = p.getName();
                pr.severity = (sev == IMarker.SEVERITY_ERROR) ? "ERROR" : "WARNING";
                pr.message = m.getAttribute(IMarker.MESSAGE, "");
                IResource r = m.getResource();
                pr.resource = (r.getProjectRelativePath() != null)
                        ? r.getProjectRelativePath().toString()
                        : r.getName();
                pr.line = m.getAttribute(IMarker.LINE_NUMBER, -1);
                try {
                    pr.markerType = m.getType();
                } catch (CoreException e) {
                    pr.markerType = "?";
                }
                pr.source = "eclipse";
                out.add(pr);
            }
            // 2) EDT validation markers (the "Standards"/check results, com.e1c.v8codestyle & co).
            //    These live in EDT's OWN marker store (IMarkerManager), NOT as Eclipse IMarker, so
            //    step 1 misses them – yet this is exactly what EDT's Problems/Checks view shows.
            out.addAll(readEdtCheckMarkers(p));
        }
        return out;
    }

    /**
     * Read EDT check markers from EDT's marker store (IMarkerManager) for one project. Returns empty
     * on any failure (headless CLI without the service, API mismatch) so Eclipse markers still work.
     */
    private List<Problem> readEdtCheckMarkers(IProject p) {
        List<Problem> out = new ArrayList<>();
        try {
            IMarkerManager mgr = ServiceAccess.get(IMarkerManager.class);
            if (mgr == null) {
                return out;
            }
            mgr.markers(MarkerFilter.createProjectFilter(p)).forEach(mk -> {
                MarkerSeverity ms = mk.getSeverity();
                if (ms == null || ms == MarkerSeverity.NONE) {
                    return;
                }
                Problem pr = new Problem();
                pr.project = p.getName();
                pr.edtSeverity = ms.name();
                pr.severity = mapEdtSeverity(ms);
                pr.message = mk.getMessage();
                pr.checkId = mk.getCheckId();
                pr.location = mk.getLocation();
                pr.resource = mk.getObjectPresentation();
                pr.line = parseLine(mk.getLocation());
                pr.source = "edt-check";
                out.add(pr);
            });
        } catch (Throwable t) {
            // EDT marker store unavailable / API mismatch – keep Eclipse markers only.
        }
        return out;
    }

    /** Map EDT's 1C grade to the ERROR/WARNING/INFO buckets project_errors already uses. */
    private static String mapEdtSeverity(MarkerSeverity ms) {
        switch (ms) {
            case ERRORS:
            case BLOCKER:
            case CRITICAL:
                return "ERROR";
            case MAJOR:
            case MINOR:
                return "WARNING";
            default:
                return "INFO"; // TRIVIAL
        }
    }

    /** Pull a line number out of an EDT location like "строка 8" / "line 8"; -1 if none. */
    private static int parseLine(String location) {
        if (location == null) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(location);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Names of the currently open workspace projects (for the status dashboard). */
    public List<String> listOpenProjects() {
        List<String> out = new ArrayList<>();
        for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (p.isOpen()) {
                out.add(p.getName());
            }
        }
        return out;
    }

    /** An open workspace project: name, disk location, nature ids, and whether it is a 1C:EDT project. */
    public static final class ProjInfo {
        public String name;
        public String location;            // absolute path on disk (null if unavailable)
        public boolean open;
        public boolean dtProject;          // has a 1C:EDT (DT) nature / a BM model
        public List<String> natures = new ArrayList<>();
    }

    /**
     * List workspace projects with names, disk paths and natures. Lets a caller discover what is
     * addressable (e.g. which project name maps to which folder on disk) without guessing.
     */
    public List<ProjInfo> listProjectsDetailed() {
        List<ProjInfo> out = new ArrayList<>();
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            ProjInfo pi = new ProjInfo();
            pi.name = p.getName();
            pi.open = p.isOpen();
            if (p.getLocation() != null) {
                pi.location = p.getLocation().toOSString();
            }
            if (p.isOpen()) {
                try {
                    for (String nat : p.getDescription().getNatureIds()) {
                        pi.natures.add(nat);
                        if (nat.contains("com._1c.g5.v8.dt") || nat.toLowerCase().contains("dtproject")) {
                            pi.dtProject = true;
                        }
                    }
                } catch (CoreException ignored) {
                    // description unavailable
                }
                if (!pi.dtProject && mm != null) {
                    try {
                        pi.dtProject = mm.getModel(p) != null;
                    } catch (RuntimeException ignored) {
                        // no BM model
                    }
                }
            }
            out.add(pi);
        }
        return out;
    }

    /** Core properties of a top-level metadata object. */
    public static final class MdDetails {
        public boolean found;
        public String fqn;
        public String type;
        public String name;
        public String uuid;
        public String comment;
        public String synonymRu;
        /** Child members grouped by feature (attributes, tabularSections, forms, ...). */
        public List<MdGroup> structure = new ArrayList<>();
        /** Structural (MdObject-typed) containment features that EXIST for this type but are empty –
         *  so a caller can tell "this object has no attributes" from "attributes not reported". */
        public List<String> emptyStructuralFeatures = new ArrayList<>();
    }

    /** A group of child members sharing one containment feature (e.g. "attributes"). */
    public static final class MdGroup {
        public String feature;
        public List<MdChild> items = new ArrayList<>();
    }

    /** A single child metadata member (attribute / tabular section / form / ...). */
    public static final class MdChild {
        public String name;
        public String type;
        public String synonymRu;
        /** For attribute-like members: rendered value type (e.g. "Строка(150)"); null otherwise. */
        public String valueType;
        /** Nested groups (e.g. a tabular section's own "attributes" group); empty when none. */
        public List<MdGroup> children = new ArrayList<>();
    }

    /**
     * Read a top object by FQN (e.g. "Catalog.Контрагенты") from the live BM model, inside a
     * read-only transaction. Returns found=false if the project or object is absent.
     */
    public MdDetails getMetadataDetails(String projectName, String fqn) {
        MdDetails d = new MdDetails();
        d.fqn = fqn;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            return d;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        if (mm == null) {
            return d;
        }
        IBmModel model = mm.getModel(p);
        if (model == null) {
            return d;
        }
        return model.executeReadonlyTask(new AbstractBmTask<MdDetails>("edt-bridge.metadataDetails") {
            @Override
            public MdDetails execute(IBmTransaction transaction, IProgressMonitor monitor) {
                MdDetails r = new MdDetails();
                r.fqn = fqn;
                IBmObject o = transaction.getTopObjectByFqn(fqn);
                if (o == null) {
                    return r;
                }
                r.found = true;
                r.type = o.eClass().getName();
                if (o instanceof MdObject) {
                    MdObject md = (MdObject) o;
                    r.name = md.getName();
                    r.uuid = String.valueOf(md.getUuid());
                    r.comment = md.getComment();
                    EMap<String, String> syn = md.getSynonym();
                    if (syn != null) {
                        r.synonymRu = syn.get("ru");
                    }
                    r.structure = childrenOf(md, 2);
                    r.emptyStructuralFeatures = emptyStructuralFeatures(md, r.structure);
                }
                return r;
            }
        });
    }

    /**
     * Group the direct {@link MdObject} children of {@code obj} by containment feature
     * (attributes, tabularSections, forms, commands, templates, dimensions, resources,
     * enumValues, ...), recursing up to {@code maxDepth} levels so a tabular section's own
     * attributes nest one level deeper. Derived/transient containments (producedTypes,
     * dbViewDefs) are skipped – they are computed, not structural, and never {@link MdObject}.
     */
    /**
     * Names of MdObject-typed containment features that EXIST on {@code obj}'s type but contributed no
     * group in {@code present} (i.e. are empty) – lets the caller distinguish "none" from "not reported".
     */
    private List<String> emptyStructuralFeatures(EObject obj, List<MdGroup> present) {
        List<String> empty = new ArrayList<>();
        java.util.Set<String> has = new java.util.HashSet<>();
        for (MdGroup g : present) {
            has.add(g.feature);
        }
        EClass mdObjectClass = MdClassPackage.Literals.MD_OBJECT;
        for (EReference ref : obj.eClass().getEAllContainments()) {
            if (ref.isDerived() || ref.isTransient()) {
                continue;
            }
            EClass rt = ref.getEReferenceType();
            if (rt == null || !mdObjectClass.isSuperTypeOf(rt)) {
                continue;   // only structural (metadata-member) features
            }
            if (!has.contains(ref.getName())) {
                empty.add(ref.getName());
            }
        }
        return empty;
    }

    private List<MdGroup> childrenOf(EObject obj, int maxDepth) {
        List<MdGroup> groups = new ArrayList<>();
        if (obj == null || maxDepth <= 0) {
            return groups;
        }
        for (EReference ref : obj.eClass().getEAllContainments()) {
            if (ref.isDerived() || ref.isTransient()) {
                continue;
            }
            Object val;
            try {
                val = obj.eGet(ref);
            } catch (RuntimeException e) {
                continue;
            }
            MdGroup group = new MdGroup();
            group.feature = ref.getName();
            if (val instanceof List) {
                for (Object x : (List<?>) val) {
                    addChild(group, x, maxDepth);
                }
            } else {
                addChild(group, val, maxDepth);
            }
            if (!group.items.isEmpty()) {
                groups.add(group);
            }
        }
        return groups;
    }

    private void addChild(MdGroup group, Object value, int maxDepth) {
        if (!(value instanceof MdObject)) {
            return;
        }
        MdObject md = (MdObject) value;
        MdChild c = new MdChild();
        c.name = md.getName();
        c.type = md.eClass().getName();
        EMap<String, String> syn = md.getSynonym();
        if (syn != null) {
            c.synonymRu = syn.get("ru");
        }
        if (md instanceof BasicFeature) {
            c.valueType = renderType(((BasicFeature) md).getType());
        }
        c.children = childrenOf(md, maxDepth - 1);
        group.items.add(c);
    }

    /**
     * Render a {@link TypeDescription} to a compact string: e.g. "Строка(150)", "Число(15, 2)",
     * "СправочникСсылка.Контрагенты", or "A, B" for a composite type. Best-effort; never throws.
     */
    private String renderType(TypeDescription td) {
        if (td == null) {
            return null;
        }
        List<TypeItem> items = td.getTypes();
        if (items == null || items.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (TypeItem ti : items) {
            String n = null;
            try {
                n = McoreUtil.getTypeNameRu(ti);
                if (n == null || n.isBlank()) {
                    n = McoreUtil.getTypeName(ti);
                }
            } catch (RuntimeException ignored) {
                // unresolved / proxy type item
            }
            names.add((n == null || n.isBlank()) ? "?" : n);
        }
        String base = String.join(", ", names);
        if (items.size() == 1) {
            try {
                StringQualifiers sq = td.getStringQualifiers();
                NumberQualifiers nq = td.getNumberQualifiers();
                if (sq != null && sq.getLength() > 0) {
                    base += "(" + sq.getLength() + ")";
                } else if (nq != null && nq.getPrecision() > 0) {
                    base += "(" + nq.getPrecision()
                            + (nq.getScale() > 0 ? ", " + nq.getScale() : "") + ")";
                }
            } catch (RuntimeException ignored) {
                // qualifiers are optional
            }
        }
        return base;
    }

    /** One inbound reference to a target object. */
    public static final class Ref {
        public String sourceFqn;
        public String sourceType;
        public String feature;
        public String sourceUri;
    }

    /** Result of {@link #getReferences}. */
    public static final class RefResult {
        public boolean found;
        public String fqn;
        public int total;
        public boolean truncated;
        public List<Ref> refs = new ArrayList<>();
    }

    /**
     * Inbound references to a top object by FQN (who references it: metadata + BSL) via the
     * BM cross-reference index. Capped at {@code limit} (<= 0 means no cap).
     */
    public RefResult getReferences(String projectName, String fqn, int limit) {
        RefResult res = new RefResult();
        res.fqn = fqn;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            return res;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        if (mm == null) {
            return res;
        }
        IBmModel model = mm.getModel(p);
        if (model == null) {
            return res;
        }
        final int cap = limit > 0 ? limit : Integer.MAX_VALUE;
        return model.executeReadonlyTask(new AbstractBmTask<RefResult>("edt-bridge.findReferences") {
            @Override
            public RefResult execute(IBmTransaction transaction, IProgressMonitor monitor) {
                RefResult r = new RefResult();
                r.fqn = fqn;
                IBmObject target = transaction.getTopObjectByFqn(fqn);
                if (target == null) {
                    return r;
                }
                r.found = true;
                Collection<IBmCrossReference> refs = transaction.getReferences(target.bmGetUri());
                r.total = refs.size();
                int n = 0;
                for (IBmCrossReference xr : refs) {
                    if (n >= cap) {
                        r.truncated = true;
                        break;
                    }
                    IBmObject src = xr.getObject();
                    if (src == null) {
                        continue;
                    }
                    IBmObject top = src.bmGetTopObject();
                    Ref e = new Ref();
                    e.sourceFqn = (top != null) ? top.bmGetFqn() : src.bmGetFqn();
                    e.sourceType = (top != null) ? top.eClass().getName() : src.eClass().getName();
                    e.feature = (xr.getFeature() != null) ? xr.getFeature().getName() : null;
                    e.sourceUri = src.bmGetUriAsString();
                    r.refs.add(e);
                    n++;
                }
                return r;
            }
        });
    }

    /** One listed metadata object. */
    public static final class MdItem {
        public String fqn;
        public String name;
        public String type;
        public String synonymRu;
        public String project;   // which project it was found in (set by the all-projects search)
    }

    /** Result of {@link #listMetadata}. */
    public static final class MdListResult {
        public boolean found;
        public String type;
        public boolean truncated;
        public String error;
        public List<MdItem> items = new ArrayList<>();
    }

    /**
     * List top objects, optionally by type (EClass name, e.g. "Catalog") and a case-insensitive
     * name substring, capped at {@code limit} (<= 0 means no cap). type null/blank/"all" = every
     * top object.
     */
    public MdListResult listMetadata(String projectName, String type, String nameFilter, int limit) {
        MdListResult res = new MdListResult();
        res.type = type;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            return res;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        if (mm == null) {
            return res;
        }
        IBmModel model = mm.getModel(p);
        if (model == null) {
            return res;
        }
        final EClass eclass;
        if (type == null || type.isBlank() || "all".equalsIgnoreCase(type)) {
            eclass = null;
        } else {
            EClassifier c = MdClassPackage.eINSTANCE.getEClassifier(type);
            if (!(c instanceof EClass)) {
                res.error = "unknown type: " + type;
                return res;
            }
            eclass = (EClass) c;
        }
        final String nf = (nameFilter == null || nameFilter.isBlank()) ? null : nameFilter.toLowerCase();
        final int cap = limit > 0 ? limit : Integer.MAX_VALUE;
        return model.executeReadonlyTask(new AbstractBmTask<MdListResult>("edt-bridge.listMetadata") {
            @Override
            public MdListResult execute(IBmTransaction transaction, IProgressMonitor monitor) {
                MdListResult r = new MdListResult();
                r.type = type;
                r.found = true;
                Iterator<IBmObject> it = (eclass == null)
                        ? transaction.getTopObjectIterator()
                        : transaction.getTopObjectIterator(eclass);
                int n = 0;
                while (it.hasNext()) {
                    IBmObject o = it.next();
                    String name = (o instanceof MdObject) ? ((MdObject) o).getName() : null;
                    if (nf != null && (name == null || !name.toLowerCase().contains(nf))) {
                        continue;
                    }
                    if (n >= cap) {
                        r.truncated = true;
                        break;
                    }
                    MdItem e = new MdItem();
                    e.fqn = o.bmGetFqn();
                    e.type = o.eClass().getName();
                    if (o instanceof MdObject) {
                        MdObject md = (MdObject) o;
                        e.name = md.getName();
                        EMap<String, String> syn = md.getSynonym();
                        if (syn != null) {
                            e.synonymRu = syn.get("ru");
                        }
                    }
                    r.items.add(e);
                    n++;
                }
                return r;
            }
        });
    }

    /**
     * Like {@link #listMetadata} but across ALL open projects: when the caller does not know which
     * project holds the object. Each item is tagged with its {@code project}.
     */
    public MdListResult listMetadataAll(String type, String nameFilter, int limit) {
        MdListResult agg = new MdListResult();
        agg.type = type;
        agg.found = true;
        int cap = limit > 0 ? limit : Integer.MAX_VALUE;
        for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (!p.isOpen()) {
                continue;
            }
            int remaining = cap - agg.items.size();
            if (remaining <= 0) {
                agg.truncated = true;
                break;
            }
            MdListResult r = listMetadata(p.getName(), type, nameFilter, remaining);
            if (r.error != null) {
                agg.error = r.error;   // e.g. unknown type – same for every project
                break;
            }
            for (MdItem it : r.items) {
                it.project = p.getName();
                agg.items.add(it);
            }
            if (r.truncated) {
                agg.truncated = true;
                break;
            }
        }
        return agg;
    }

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
    private static final Map<String, String> MD_FOLDER = Map.ofEntries(
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
            Map.entry("commoncommand", "CommonCommands"));

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
            String method, String modulePath) {
        ModuleTextResult r = new ModuleTextResult();
        r.fqn = fqn;
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
            Map.entry("CommonCommands", "CommonCommand"));

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

    // ---- Managed form structure ------------------------------------------------------------

    /** A node in a form's visual items tree (a field, group, table, button, decoration, ...). */
    public static final class FormNode {
        public String name;
        public String kind;       // eClass simple name (FormField, FormGroup, Table, Button, Decoration, ...)
        public String itemType;   // the Managed*Type literal (InputField, UsualGroup, ...) when applicable
        public String dataPath;   // bound data path for data items (e.g. "Объект.Наименование"); null otherwise
        public String title;      // ru title, when present
        // Static (.form) properties – note these are the DESIGN values; BSL (e.g. ПриСозданииНаСервере)
        // may change them at runtime. null = not applicable to this item kind.
        public Boolean visible;
        public Boolean enabled;
        public Boolean readOnly;
        // command -> button: the command this button is wired to + how it is drawn. null otherwise.
        public String command;        // name of the referenced command (matches a form command, best-effort)
        public String representation; // ButtonRepresentation (Text / Picture / ...), when set
        public String placement;      // placement area in a command bar / menu, when set
        // per-item event handlers (columns/fields carry their own – e.g. a cell-click "Выбор"/Selection
        // handler – separate from the form-level handlers). Empty when the item has none.
        public List<FormEvt> handlers = new ArrayList<>();
        public Boolean cellHyperlink; // the cell is rendered as a clickable hyperlink (FormField only)
        // Input-field design props (InputField only) – password masking + the reveal ("eye") choice
        // button, the design-time signal for the "show a stored secret on the client" pattern. null = n/a.
        public Boolean passwordMode;              // the field masks input as ***
        public Boolean choiceButton;              // an in-field choice button is shown
        public Boolean choiceButtonPicture;       // a choice-button picture is set – the button carries a glyph (the eye)
        public String choiceButtonRepresentation; // where the choice button is drawn (non-Auto only)
        public List<FormNode> children = new ArrayList<>();
    }

    /** One declarative conditional-appearance rule (УсловноеОформление) from the .form (DCS settings). */
    public static final class CondAppearance {
        public boolean use = true;
        public List<String> fields = new ArrayList<>();   // formatted fields (which cells get the styling)
        public List<String> filter = new ArrayList<>();   // selection conditions (left op right), best-effort
        public List<String[]> appearance = new ArrayList<>(); // [param, value] set styling, e.g. {ЦветТекста, ...}
    }

    /** A form data attribute. */
    public static final class FormAttr {
        public String name;
        public String valueType;  // rendered TypeDescription
        public boolean main;      // the form's main attribute (Объект / Список / ...)
    }

    /** A form command. */
    public static final class FormCmd {
        public String name;
        public String title;      // ru
        public String handler;    // BSL action procedure, best-effort
    }

    /** A form parameter. */
    public static final class FormParam {
        public String name;
        public String valueType;  // rendered TypeDescription
    }

    /** A form-level event handler (form event -> BSL procedure). */
    public static final class FormEvt {
        public String name;       // BSL handler procedure
        public String event;      // platform event name (e.g. ПриОткрытии / OnOpen)
    }

    /** Result of {@link #getFormStructure}. */
    public static final class FormDetails {
        public boolean found;
        public String fqn;
        public String type;       // eClass of the resolved object
        public String message;    // set when not found / not a form
        public String titleRu;
        public int width;
        public int height;
        public boolean truncated; // item-tree node cap reached
        public List<FormAttr> attributes = new ArrayList<>();
        public List<FormCmd> commands = new ArrayList<>();
        public List<FormParam> parameters = new ArrayList<>();
        public List<FormEvt> handlers = new ArrayList<>();
        public List<CondAppearance> conditionalAppearance = new ArrayList<>(); // declarative .form rules
        public List<FormNode> items = new ArrayList<>();
    }

    /**
     * Read a managed form by FQN (e.g. "Catalog.Контрагенты.Form.ФормаЭлемента", "CommonForm.МояФорма")
     * from the live BM model: the visual items tree (fields/groups/tables/buttons/decorations with
     * data bindings) plus the form's attributes, commands, parameters and event handlers. The
     * forms's content/metadata are one unified {@link Form} top object in EDT. The items tree is
     * capped at {@code itemLimit} nodes (<= 0 means no cap); {@code truncated} flags an overflow.
     */
    public FormDetails getFormStructure(String projectName, String fqn, int itemLimit) {
        FormDetails d = new FormDetails();
        d.fqn = fqn;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            d.message = "project not found or closed: " + projectName;
            return d;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        if (mm == null) {
            d.message = "BM model manager unavailable";
            return d;
        }
        IBmModel model = mm.getModel(p);
        if (model == null) {
            d.message = "no BM model for project: " + projectName;
            return d;
        }
        final int cap = itemLimit > 0 ? itemLimit : Integer.MAX_VALUE;
        return model.executeReadonlyTask(new AbstractBmTask<FormDetails>("edt-bridge.formStructure") {
            @Override
            public FormDetails execute(IBmTransaction transaction, IProgressMonitor monitor) {
                FormDetails r = new FormDetails();
                r.fqn = fqn;
                // A common form is a top object (CommonForm); the editable content (items,
                // attributes) is the form.model.Form reached via BasicForm.getForm(). A catalog/
                // document form is NOT a top object – only its owner is – so resolve "Owner.Form.Name"
                // through the owner's "forms" feature.
                EObject mdForm = transaction.getTopObjectByFqn(fqn);
                Form form = asFormContent(mdForm);
                if (form == null) {
                    int idx = fqn.lastIndexOf(".Form.");
                    if (idx > 0) {
                        IBmObject owner = transaction.getTopObjectByFqn(fqn.substring(0, idx));
                        if (owner != null) {
                            BasicForm bf = findForm(owner, fqn.substring(idx + ".Form.".length()));
                            if (bf != null) {
                                mdForm = bf;
                                form = asFormContent(bf);
                            }
                        }
                    }
                }
                if (form == null) {
                    if (mdForm != null) {
                        r.type = mdForm.eClass().getName();
                        r.message = "not a form (resolved " + r.type + "): " + fqn;
                    } else {
                        r.message = "form not found: " + fqn;
                    }
                    return r;
                }
                r.type = mdForm.eClass().getName();
                r.found = true;
                r.titleRu = ruOf(form.getTitle());
                try {
                    r.width = form.getWidth();
                    r.height = form.getHeight();
                } catch (RuntimeException ignored) {
                    // dimensions are optional
                }
                for (FormAttribute a : form.getAttributes()) {
                    FormAttr fa = new FormAttr();
                    fa.name = a.getName();
                    fa.valueType = renderType(a.getValueType());
                    fa.main = a.isMain();
                    r.attributes.add(fa);
                }
                for (FormCommand c : form.getFormCommands()) {
                    FormCmd fc = new FormCmd();
                    fc.name = c.getName();
                    fc.title = ruOf(c.getToolTip());
                    fc.handler = handlerOf(c);
                    r.commands.add(fc);
                }
                for (FormParameter pm : form.getParameters()) {
                    FormParam fp = new FormParam();
                    fp.name = pm.getName();
                    fp.valueType = renderType(pm.getValueType());
                    r.parameters.add(fp);
                }
                for (EventHandler h : form.getHandlers()) {
                    r.handlers.add(eventOf(h));
                }
                collectConditionalAppearance(form, r);
                int[] counter = {0};
                for (FormItem it : form.getItems()) {
                    FormNode n = nodeOf(it, cap, counter, r);
                    if (n != null) {
                        r.items.add(n);
                    }
                }
                return r;
            }
        });
    }

    /** The editable {@link Form} content of a resolved object: itself, or via its metadata form. */
    private Form asFormContent(EObject o) {
        if (o instanceof Form) {
            return (Form) o;
        }
        if (o instanceof BasicForm) {
            AbstractForm content = ((BasicForm) o).getForm();
            if (content instanceof Form) {
                return (Form) content;
            }
        }
        return null;
    }

    /** Find a named form among an owner object's "forms" (catalog/document/... subordinate forms). */
    private BasicForm findForm(EObject owner, String formName) {
        EStructuralFeature f = owner.eClass().getEStructuralFeature("forms");
        if (f == null || formName == null) {
            return null;
        }
        Object val;
        try {
            val = owner.eGet(f);
        } catch (RuntimeException e) {
            return null;
        }
        if (val instanceof List) {
            for (Object x : (List<?>) val) {
                if (x instanceof BasicForm && formName.equals(((MdObject) x).getName())) {
                    return (BasicForm) x;
                }
            }
        }
        return null;
    }

    /** Recursively build a form node, honouring the {@code cap} on total tree nodes. */
    private FormNode nodeOf(FormItem item, int cap, int[] counter, FormDetails r) {
        if (item == null) {
            return null;
        }
        if (counter[0] >= cap) {
            r.truncated = true;
            return null;
        }
        counter[0]++;
        FormNode n = new FormNode();
        n.name = item.getName();
        n.kind = item.eClass().getName();
        n.itemType = itemTypeOf(item);
        if (item instanceof DataItem) {
            n.dataPath = pathOf(((DataItem) item).getDataPath());
        }
        if (item instanceof Titled) {
            n.title = ruOf(((Titled) item).getTitle());
        }
        // Static .form properties (design-time values; runtime BSL may override).
        try {
            if (item instanceof Visible) {
                n.visible = ((Visible) item).isVisible();
                n.enabled = ((Visible) item).isEnabled();
            }
            if (item instanceof FormField) {
                FormField ff = (FormField) item;
                n.readOnly = ff.isReadOnly();
                n.cellHyperlink = ff.isCellHyperlink() ? Boolean.TRUE : null;
                readInputFieldProps(ff, n); // password / choice-button ("eye") design props
            } else if (item instanceof Table) {
                n.readOnly = ((Table) item).isReadOnly();
            }
            if (item instanceof Button) { // command -> button
                Button b = (Button) item;
                n.command = commandRefName(b.getCommandName());
                n.representation = enumName(b.getRepresentation());
                n.placement = enumName(b.getPlacementArea());
            }
            if (item instanceof EventHandlerContainer) { // per-item (column/field) event handlers
                for (EventHandler h : ((EventHandlerContainer) item).getHandlers()) {
                    n.handlers.add(eventOf(h));
                }
            }
        } catch (RuntimeException ignored) {
            // properties are best-effort
        }
        if (item instanceof FormItemContainer) {
            for (FormItem child : ((FormItemContainer) item).getItems()) {
                FormNode cn = nodeOf(child, cap, counter, r);
                if (cn != null) {
                    n.children.add(cn);
                }
            }
        }
        return n;
    }

    /**
     * Input-field design props from the .form: РежимПароля (the field masks input) and the choice
     * ("eye") button – КнопкаВыбора / КартинкаКнопкиВыбора – the design-time signal for the reveal-password
     * idiom. Only an InputField carries these (its ext-info is InputFieldExtInfo); other field kinds have
     * none. Emits only the meaningful (true / present / non-Auto) value to keep the tree lean. The picture
     * is reported as presence, not a name (the .form ref is an EMF proxy, not a plain CommonPicture name).
     * Best-effort; never throws.
     */
    private void readInputFieldProps(FormField ff, FormNode n) {
        try {
            FieldExtInfo ext = ff.getExtInfo();
            if (!(ext instanceof InputFieldExtInfo)) {
                return;
            }
            InputFieldExtInfo in = (InputFieldExtInfo) ext;
            if (Boolean.TRUE.equals(in.getPasswordMode())) {
                n.passwordMode = Boolean.TRUE;
            }
            if (Boolean.TRUE.equals(in.getChoiceButton())) {
                n.choiceButton = Boolean.TRUE;
            }
            if (in.getChoiceButtonPicture() != null) {
                n.choiceButtonPicture = Boolean.TRUE;
            }
            String rep = enumName(in.getChoiceButtonRepresentation());
            if (rep != null && !"auto".equalsIgnoreCase(rep)) {
                n.choiceButtonRepresentation = rep;
            }
        } catch (RuntimeException ignored) {
            // design props are best-effort
        }
    }

    /** The Managed*Type literal of an item (field/group/button/decoration kind); null otherwise. */
    private String itemTypeOf(FormItem item) {
        Object t = null;
        try {
            if (item instanceof FormField) {
                t = ((FormField) item).getType();
            } else if (item instanceof FormGroup) {
                t = ((FormGroup) item).getType();
            } else if (item instanceof Button) {
                t = ((Button) item).getType();
            } else if (item instanceof Decoration) {
                t = ((Decoration) item).getType();
            }
        } catch (RuntimeException ignored) {
            // type is best-effort
        }
        return (t instanceof Enumerator) ? ((Enumerator) t).getName() : null;
    }

    /** Render a data path as a dotted string (e.g. "Объект.Наименование"); best-effort. */
    private String pathOf(AbstractDataPath dp) {
        if (dp == null) {
            return null;
        }
        try {
            List<String> segs = dp.getSegments();
            if (segs != null && !segs.isEmpty()) {
                return String.join(".", segs);
            }
            String s = dp.toString();
            return (s == null || s.isBlank()) ? null : s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** BSL action procedure backing a form command; best-effort (never throws). */
    private String handlerOf(FormCommand cmd) {
        try {
            CommandHandlerContainer action = cmd.getAction();
            if (action != null) {
                for (EObject ch : action.eContents()) {
                    if (ch instanceof CommandHandler) {
                        String h = ((CommandHandler) ch).getName();
                        if (h != null && !h.isBlank()) {
                            return h;
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // handler wiring is best-effort
        }
        return null;
    }

    /** Map a form event handler to {procedure, event}; the event name is best-effort. */
    private FormEvt eventOf(EventHandler h) {
        FormEvt e = new FormEvt();
        e.name = h.getName();
        try {
            Event ev = h.getEvent();
            if (ev != null && !ev.eIsProxy()) {
                e.event = ev.getName();
            }
        } catch (RuntimeException ignored) {
            // event may be an unresolved proxy
        }
        return e;
    }

    /** A command reference's name, best-effort (matches a form command in "commands"); never throws. */
    private String commandRefName(EObject cmd) {
        if (cmd == null) {
            return null;
        }
        try {
            EStructuralFeature nf = cmd.eClass().getEStructuralFeature("name");
            if (nf != null) {
                Object v = cmd.eGet(nf);
                if (v != null && !v.toString().isBlank()) {
                    return v.toString();
                }
            }
        } catch (RuntimeException ignored) {
            // name resolution is best-effort
        }
        return cmd.eClass().getName(); // last resort: the command kind
    }

    /** The literal name of an EMF enum value (Enumerator), or null. */
    private String enumName(Object e) {
        return (e instanceof Enumerator) ? ((Enumerator) e).getName() : null;
    }

    /**
     * Collect the form's declarative conditional appearance (УсловноеОформление) from the .form
     * (DCS settings): for each rule the formatted fields, the selection conditions and the set
     * appearance parameters. Rules built at runtime in BSL (УстановитьУсловноеОформление) are NOT
     * here – only what is stored declaratively in the .form. Best-effort; never throws.
     */
    private void collectConditionalAppearance(Form form, FormDetails r) {
        try {
            DataCompositionConditionalAppearance ca = form.getConditionalAppearance();
            if (ca == null) {
                return;
            }
            for (DataCompositionConditionalAppearanceItem it : ca.getItems()) {
                CondAppearance c = new CondAppearance();
                c.use = it.isUse();
                if (it.getSelection() != null) {
                    for (DataCompositionAppearanceField f : it.getSelection().getItems()) {
                        String fn = dcValueText(f.getField());
                        if (fn != null) {
                            c.fields.add(fn);
                        }
                    }
                }
                DataCompositionFilter filter = it.getFilter();
                if (filter != null) {
                    for (FilterItem fi : filter.getItems()) {
                        String cond = renderFilterItem(fi);
                        if (cond != null) {
                            c.filter.add(cond);
                        }
                    }
                }
                if (it.getAppearance() != null) {
                    for (DataCompositionParameterValue pv : it.getAppearance().getItems()) {
                        if (!pv.isUse()) {
                            continue;
                        }
                        String pname = (pv.getParameter() instanceof DataCompositionParameter)
                                ? ((DataCompositionParameter) pv.getParameter()).getValue()
                                : dcValueText(pv.getParameter());
                        if (pname == null) {
                            continue;
                        }
                        String val = (pv.getValues() != null && !pv.getValues().isEmpty())
                                ? dcValueText(pv.getValues().get(0)) : null;
                        c.appearance.add(new String[] { pname, val });
                    }
                }
                r.conditionalAppearance.add(c);
            }
        } catch (RuntimeException ignored) {
            // declarative conditional appearance is best-effort
        }
    }

    /** Render a DCS filter item (field comparison value) as a readable condition; best-effort. */
    private String renderFilterItem(FilterItem fi) {
        try {
            if (!(fi instanceof DataCompositionFilterItem)) {
                return fi.eClass().getName(); // a filter group (AND/OR) – note the kind, no deep render
            }
            DataCompositionFilterItem f = (DataCompositionFilterItem) fi;
            String left = dcValueText(f.getLeft());
            String op = enumName(f.getComparisonType());
            StringBuilder right = new StringBuilder();
            if (f.getRight() != null) {
                for (EObject rv : f.getRight()) {
                    String s = dcValueText(rv);
                    if (s != null) {
                        right.append(right.length() > 0 ? ", " : "").append(s);
                    }
                }
            }
            StringBuilder out = new StringBuilder(left != null ? left : "?");
            if (op != null) {
                out.append(' ').append(op);
            }
            if (right.length() > 0) {
                out.append(' ').append(right);
            }
            return out.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Render a DCS / mcore value (a field reference or a literal) as readable text; best-effort.
     * DataCompositionField/Parameter expose getValue() (the dotted field / param name); other value
     * kinds fall back to a "value"/"name"/"content" attribute, else the eClass kind.
     */
    private String dcValueText(EObject v) {
        if (v == null) {
            return null;
        }
        try {
            if (v instanceof DataCompositionField) {
                return ((DataCompositionField) v).getValue();
            }
            if (v instanceof DataCompositionParameter) {
                return ((DataCompositionParameter) v).getValue();
            }
            for (String fname : new String[] { "value", "name", "content" }) {
                EStructuralFeature f = v.eClass().getEStructuralFeature(fname);
                if (f instanceof EAttribute) {
                    Object x = v.eGet(f);
                    if (x != null && !x.toString().isBlank()) {
                        return x.toString();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // value rendering is best-effort
        }
        return v.eClass().getName();
    }

    // ---- CommonPicture / binary resource export -------------------------------------------

    /** One variant of a CommonPicture (a row of the Picture.zip manifest). */
    public static final class PictureVariant {
        public String name;             // zip entry: "l", "400.png", "<hash>.svg", "Picture.png"
        public String screenDensity;    // ldpi / mdpi / hdpi / ... (DPI bucket)
        public String interfaceVariant; // version8_5 / version8_2 / version8_2_OrdinaryApp
        public String theme;            // theme name ("" = default)
        public boolean isTemplate;      // recolorable template picture
        public int glyphWidth;
        public int glyphHeight;
        public long sizeBytes;          // size of the entry in the zip
        public String contentType;      // image/svg+xml | image/png
    }

    /** Result of {@link #exportPicture}. */
    public static final class PictureResult {
        public boolean found;
        public String fqn;
        public String message;
        public String zipPath;          // project-relative Picture.zip path
        public List<PictureVariant> variants = new ArrayList<>();
        public String recommended;      // suggested variant name (8.5 vector/template first)
        // set only when a specific variant is requested:
        public String selectedName;
        public String selectedContentType;
        public long selectedSize;
        public String base64;
    }

    /**
     * Export a CommonPicture's content: list the variants from the Picture.zip manifest (DPI /
     * interface variant / theme / isTemplate) and, when {@code variant} is given, return that entry's
     * bytes as base64. {@code variant} accepts an exact entry name, or "svg" (the vector master) /
     * "best" (svg else the largest PNG). Resolves CommonPicture.&lt;name&gt; to
     * &lt;project&gt;/src/CommonPictures/&lt;name&gt;/Picture.zip. Read-only; never throws.
     */
    public PictureResult exportPicture(String projectName, String fqn, String variant) {
        PictureResult r = new PictureResult();
        r.fqn = fqn;
        try {
            IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!p.exists() || !p.isOpen()) {
                r.message = "project not found or closed: " + projectName;
                return r;
            }
            String name = null;
            if (fqn != null) {
                int dot = fqn.indexOf('.');
                if (dot > 0 && fqn.substring(0, dot).equalsIgnoreCase("CommonPicture")) {
                    name = fqn.substring(dot + 1);
                }
            }
            if (name == null || name.isBlank()) {
                r.message = "expected CommonPicture.<name>, got: " + fqn;
                return r;
            }
            String src = p.getFolder("src").exists() ? "src/" : "";
            IFile zip = p.getFile(src + "CommonPictures/" + name + "/Picture.zip");
            if (!zip.exists() || zip.getLocation() == null) {
                r.message = "Picture.zip not found for CommonPicture." + name;
                return r;
            }
            r.zipPath = zip.getProjectRelativePath().toString();
            Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
            String manifestXml = null;
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip.getLocation().toFile())) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry ze = en.nextElement();
                    if (ze.isDirectory()) {
                        continue;
                    }
                    byte[] bytes = zf.getInputStream(ze).readAllBytes();
                    entries.put(ze.getName(), bytes);
                    if (ze.getName().equalsIgnoreCase("manifest.xml")) {
                        manifestXml = new String(bytes, StandardCharsets.UTF_8);
                    }
                }
            }
            parsePictureManifest(manifestXml, entries, r);
            r.recommended = recommendVariant(r.variants);
            r.found = true;
            if (variant != null && !variant.isBlank()) {
                String pick = resolveVariantName(variant, r);
                byte[] bytes = pick != null ? entries.get(pick) : null;
                if (bytes == null) {
                    r.message = "variant not found: " + variant;
                } else {
                    r.selectedName = pick;
                    r.selectedSize = bytes.length;
                    r.selectedContentType = contentTypeOf(pick);
                    r.base64 = java.util.Base64.getEncoder().encodeToString(bytes);
                }
            }
            return r;
        } catch (Exception e) {
            r.message = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            return r;
        }
    }

    /** Build variants from the manifest's {@code <PictureVariant/>} rows; defensive fallback to raw entries. */
    private void parsePictureManifest(String xml, Map<String, byte[]> entries, PictureResult r) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (xml != null) {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("<PictureVariant\\b([^>]*)/>").matcher(xml);
            while (m.find()) {
                String attrs = m.group(1);
                PictureVariant v = new PictureVariant();
                v.name = attr(attrs, "name");
                v.screenDensity = attr(attrs, "screenDensity");
                v.interfaceVariant = attr(attrs, "interfaceVariant");
                v.theme = attr(attrs, "theme");
                v.isTemplate = "true".equalsIgnoreCase(attr(attrs, "isTemplate"));
                v.glyphWidth = intAttr(attrs, "glyphWidth");
                v.glyphHeight = intAttr(attrs, "glyphHeight");
                if (v.name != null) {
                    byte[] b = entries.get(v.name);
                    v.sizeBytes = b != null ? b.length : 0;
                    v.contentType = contentTypeOf(v.name);
                    seen.add(v.name);
                }
                r.variants.add(v);
            }
        }
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (e.getKey().equalsIgnoreCase("manifest.xml") || seen.contains(e.getKey())) {
                continue;
            }
            PictureVariant v = new PictureVariant();
            v.name = e.getKey();
            v.sizeBytes = e.getValue().length;
            v.contentType = contentTypeOf(e.getKey());
            r.variants.add(v);
        }
    }

    private static String attr(String attrs, String key) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile(key + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static int intAttr(String attrs, String key) {
        String s = attr(attrs, key);
        try {
            return s != null ? Integer.parseInt(s.trim()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String contentTypeOf(String name) {
        if (name == null) {
            return null;
        }
        String n = name.toLowerCase();
        if (n.endsWith(".png")) {
            return "image/png";
        }
        if (n.endsWith(".svg") || n.equals("l")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    /** Suggested variant: the vector/template master (8.5 SVG) if present, else the largest PNG. */
    private String recommendVariant(List<PictureVariant> vs) {
        PictureVariant svg = null;
        PictureVariant largestPng = null;
        for (PictureVariant v : vs) {
            if ("image/svg+xml".equals(v.contentType)
                    && (svg == null || "version8_5".equals(v.interfaceVariant))) {
                svg = v;
            } else if ("image/png".equals(v.contentType)
                    && (largestPng == null || v.sizeBytes > largestPng.sizeBytes)) {
                largestPng = v;
            }
        }
        PictureVariant pick = svg != null ? svg : largestPng;
        return pick != null ? pick.name : null;
    }

    /** Map a {@code variant} argument ("svg" / "best" / an exact entry name) to a zip entry name. */
    private String resolveVariantName(String variant, PictureResult r) {
        if ("svg".equalsIgnoreCase(variant)) {
            for (PictureVariant v : r.variants) {
                if ("image/svg+xml".equals(v.contentType)) {
                    return v.name;
                }
            }
            return null;
        }
        if ("best".equalsIgnoreCase(variant)) {
            return r.recommended;
        }
        return variant; // exact entry name
    }

    /** Russian entry of a localized string map (falls back to the first value); null-safe. */
    private String ruOf(EMap<String, String> m) {
        if (m == null || m.isEmpty()) {
            return null;
        }
        String ru = m.get("ru");
        if (ru != null && !ru.isBlank()) {
            return ru;
        }
        for (String v : m.values()) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    // ---- Managed form rendering (EDT native offscreen render → PNG) ------------------------------

    /** Result of {@link #renderForm}. */
    public static final class RenderResult {
        public boolean ok;
        public String fqn;
        public String message;
        public String pngPath;
        public Integer width;
        public Integer height;
        public String variant;
        public String theme;
    }

    /**
     * Render a managed form to a PNG via EDT's native offscreen renderer (the engine behind the form
     * editor's WYSIWYG preview): build an offscreen SWT scaffold, a desktop theme projection for the
     * chosen interface variant (TAXI / VERSION8_5) + theme, then drive {@code HippoLayoutService
     * .createHippoSession(...)} inside a read transaction and save {@code session.getFormImageData()}.
     * Must run on the SWT UI thread (the Display thread); the MCP single-thread executor owns it.
     */
    public RenderResult renderForm(String projectName, String fqn, String variantName, String themeName,
            String densityName, int commonRatio, int width, int height, int scale, String outPath) {
        RenderResult r = new RenderResult();
        r.fqn = fqn;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        if (mm == null) {
            r.message = "BM model manager unavailable";
            return r;
        }
        IBmModel model = mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }
        final ClientInterfaceVariant variant = parseVariant(variantName);
        final ClientInterfaceTheme theme = parseTheme(themeName);
        final ClientInterfaceScale density = parseScale(densityName);
        final int ratio = commonRatio > 0 ? commonRatio : 100;
        final int w = width > 0 ? width : 1280;
        final int h = height > 0 ? height : 800;

        final int zoom = scale;
        // Where to run the render depends on the runtime. NativeRenderService.setWindows() disposes
        // widgets it owns, and in a GUI EDT those belong to the workbench's main-thread Display – so
        // the render MUST run on that thread (a private Display throws "Invalid thread access" inside
        // setWindows). Headless CLI has no workbench Display, so there we own a private one on a
        // dedicated single thread (created once, reused). Pick per runtime:
        try {
            Display workbenchDisplay = workbenchDisplayOrNull();
            if (workbenchDisplay != null && !workbenchDisplay.isDisposed()) {
                // GUI EDT: marshal onto the workbench UI thread. syncExec briefly blocks the editor
                // for the render (~1-2 s) – the same thread EDT's own form preview uses.
                final Display wd = workbenchDisplay;
                wd.syncExec(() -> renderOnUi(r, wd, model, mm, fqn, variant, theme, density, ratio, w, h, zoom, outPath));
            } else {
                // Headless CLI: private Display on the dedicated render thread.
                RENDER_EXECUTOR.submit(() -> {
                    Display d = Display.findDisplay(Thread.currentThread());
                    if (d == null) {
                        d = new Display();
                    }
                    renderOnUi(r, d, model, mm, fqn, variant, theme, density, ratio, w, h, zoom, outPath);
                    return null;
                }).get();
            }
        } catch (Exception e) {
            if (r.message == null) {
                r.message = describeThrowable(e);
            }
        }
        return r;
    }

    /** The workbench's main-thread Display in a GUI EDT, or null when running headless (CLI). */
    private static Display workbenchDisplayOrNull() {
        try {
            if (PlatformUI.isWorkbenchRunning()) {
                return PlatformUI.getWorkbench().getDisplay();
            }
        } catch (Throwable ignore) {
            // No workbench (headless) – fall through to null.
        }
        return null;
    }

    /** The whole render, on the SWT UI thread. Fills {@code r}; never throws. */
    private void renderOnUi(RenderResult r, Display d, IBmModel model, IBmModelManager mm, String fqn,
            ClientInterfaceVariant variant, ClientInterfaceTheme theme, ClientInterfaceScale scale,
            int commonRatio, int w, int h, int zoom, String outPath) {
        final boolean isDark = theme == ClientInterfaceTheme.ECIT_DARK;
        final LFTargetPlatform targetPlatform = LFTargetPlatform.ELFT_THIN_THICK_CLIENT;
        final Version version = Version.LATEST;

        Shell shell = null;
        ScrolledComposite scrolled = null;
        Image scratch = null;
        Image winImg = null;
        GC gc = null;
        IThemeProjection projection = null;
        ILayoutRenderService renderService = null;
        LayoutTransformationService transformator = null;
        try {
            // 1) offscreen SWT scaffold (NativeRenderService casts only to Composite / ScrolledComposite)
            shell = new Shell(d, SWT.NO_TRIM);
            shell.setSize(w, h);
            scrolled = new ScrolledComposite(shell, SWT.H_SCROLL | SWT.V_SCROLL);
            scrolled.setBounds(0, 0, w, h);
            Composite formComposite = new Composite(scrolled, SWT.NONE);
            formComposite.setBounds(0, 0, w, h);
            scrolled.setContent(formComposite);
            // Realize the native window off-screen so the native renderer has a valid device context
            // (it draws into the composite's OS handle; an unrealized/zero-state window has none).
            shell.setLocation(-32000, -32000);
            shell.open();
            shell.setVisible(false);
            formComposite.layout();
            Color bg = d.getSystemColor(SWT.COLOR_WHITE);

            // 2) theme projection for the chosen variant (HippoThemeTaxi lives in a non-exported
            //    package → reflection). Compatibility mode comes from the configuration.
            CompatibilityMode compat = readCompatibilityMode(model);
            scratch = new Image(d, 1, 1);
            gc = new GC(scratch);
            projection = IThemeProjection.createDesktopProjection(gc);
            setHippoTheme(projection, createHippoTheme(isDark, targetPlatform, variant, compat));
            IPlatformVisualComputer pvc = projection.getPlatformVisualComputer();
            pvc.setCommonRatio(commonRatio);

            // 3) services + wire the offscreen windows. EDT's own composite doubles as the image
            //    supplier + mouse listener, so a null supplier/listener trips SWT's null check –
            //    pass a real backing image and a no-op listener.
            renderService = new RenderServiceProvider().get(version);
            transformator = new TransformatorServiceProvider().get();
            winImg = new Image(d, w, h);
            final Image suppliedImg = winImg;
            renderService.setWindows(formComposite, bg, () -> suppliedImg, scrolled, new MouseAdapter() {
            });

            final ILayoutRenderService rs = renderService;
            final LayoutTransformationService ts = transformator;
            final IPlatformVisualComputer fpvc = pvc;
            final ImageData[] out = {null};
            final String[] err = {null};
            // 4) drive the render in a read-WRITE transaction that is ROLLED BACK: the layout/render
            //    pipeline mutates the form model (e.g. setVerticalScroll), so a read-only transaction
            //    fails; executeAndRollback is what EDT's own WYSIWYG uses – changes never persist.
            model.executeAndRollback(new AbstractBmTask<Object>("edt-bridge.renderForm") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    EObject mdForm = tx.getTopObjectByFqn(fqn);
                    Form form = asFormContent(mdForm);
                    if (form == null) {
                        int idx = fqn.lastIndexOf(".Form.");
                        if (idx > 0) {
                            EObject owner = tx.getTopObjectByFqn(fqn.substring(0, idx));
                            if (owner != null) {
                                form = asFormContent(findForm(owner, fqn.substring(idx + ".Form.".length())));
                            }
                        }
                    }
                    if (form == null) {
                        err[0] = "form not found / not a form: " + fqn;
                        return null;
                    }
                    CommandInterfaceMapping cmi = buildCmi(form, model, mm);
                    IHippoLayModelSession session = HippoLayoutService.INSTANCE.createHippoSession(
                            tx, form, ts, rs, fpvc, cmi, variant, theme, targetPlatform, scale,
                            w, h, false, 0, 0, 0, 0, false, version,
                            NativeRenderEvent.buildUpdateEvent(), false);
                    try {
                        out[0] = session.getFormImageData();
                    } finally {
                        session.dispose();
                    }
                    return null;
                }
            });
            if (err[0] != null) {
                r.message = err[0];
                return;
            }
            if (out[0] == null) {
                r.message = "render produced no image (getFormImageData == null)";
                return;
            }
            // The native render is fixed at 100% (device zoom crashes the native renderer headless),
            // so scale > 100 raster-upscales the finished PNG to match a Hi-DPI screen.
            ImageData img = out[0];
            if (zoom > 100) {
                img = img.scaledTo(Math.round(img.width * zoom / 100f), Math.round(img.height * zoom / 100f));
            }
            ImageLoader loader = new ImageLoader();
            loader.data = new ImageData[] {img};
            loader.save(outPath, SWT.IMAGE_PNG);
            r.ok = true;
            r.pngPath = outPath;
            r.width = img.width;
            r.height = img.height;
            r.variant = variant.getName();
            r.theme = theme.getName();
        } catch (Throwable t) {
            r.message = describeThrowable(t);
        } finally {
            disposeQuietly(gc);
            disposeQuietly(scratch);
            disposeQuietly(winImg);
            try {
                if (projection != null) {
                    projection.dispose();
                }
            } catch (RuntimeException ignored) {
                // best-effort
            }
            try {
                if (transformator != null) {
                    transformator.dispose();
                }
            } catch (RuntimeException ignored) {
                // best-effort
            }
            try {
                if (renderService != null) {
                    renderService.dispose();
                }
            } catch (RuntimeException ignored) {
                // best-effort
            }
            if (shell != null) {
                shell.dispose();
            }
        }
    }

    /**
     * The form's command-interface mapping model (needed by the layout session). Built the way EDT's
     * form editor does it: register the mapping in a {@link MappingController} and get the completed
     * root synchronously – {@code buildRootModel()} alone leaves the CMI incomplete for forms with a
     * rich command interface (object forms).
     */
    private CommandInterfaceMapping buildCmi(Form form, IBmModel model, IBmModelManager mm) {
        MappingController controller = new MappingController();
        FormCommandInterfaceMapping mapping = new FormCommandInterfaceMapping(form, model, mm);
        controller.register(mapping);
        return controller.getMappingRoot(CommandInterfaceMapping.class);
    }

    /** The configuration's compatibility mode (affects the rendered look); null if unavailable. */
    private CompatibilityMode readCompatibilityMode(IBmModel model) {
        try {
            return model.executeReadonlyTask(new AbstractBmTask<CompatibilityMode>("edt-bridge.compatMode") {
                @Override
                public CompatibilityMode execute(IBmTransaction tx, IProgressMonitor monitor) {
                    EObject o = tx.getTopObjectByFqn("Configuration");
                    return (o instanceof Configuration) ? ((Configuration) o).getCompatibilityMode() : null;
                }
            });
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Build a HippoThemeTaxi via reflection (its package theme.hippo is not exported). */
    private Object createHippoTheme(boolean isDark, LFTargetPlatform target, ClientInterfaceVariant variant,
            CompatibilityMode compat) throws Exception {
        Bundle b = Platform.getBundle("com._1c.g5.v8.dt.form.presentation");
        Class<?> cls = b.loadClass("com._1c.g5.v8.dt.form.presentation.theme.hippo.HippoThemeTaxi");
        Constructor<?> ctor = cls.getConstructor(boolean.class, LFTargetPlatform.class,
                ClientInterfaceVariant.class, CompatibilityMode.class);
        return ctor.newInstance(isDark, target, variant, compat);
    }

    /** Reflectively call {@code IThemeProjection.setHippoTheme(AbstractHippoTheme)} (param non-exported). */
    private void setHippoTheme(IThemeProjection projection, Object hippoTheme) throws Exception {
        Bundle b = Platform.getBundle("com._1c.g5.v8.dt.form.presentation");
        Class<?> abstractTheme = b.loadClass("com._1c.g5.v8.dt.form.presentation.theme.hippo.AbstractHippoTheme");
        Method setter = IThemeProjection.class.getMethod("setHippoTheme", abstractTheme);
        setter.invoke(projection, hippoTheme);
    }

    private ClientInterfaceVariant parseVariant(String name) {
        if (name != null) {
            String n = name.trim().toUpperCase().replace("-", "_").replace(".", "_").replace(" ", "_");
            if (n.equals("TAXI") || n.equals("TAXI8_3") || n.equals("V8_3")) {
                return ClientInterfaceVariant.TAXI;
            }
            if (n.contains("8_5") || n.equals("VERSION8_5") || n.equals("V8_5")) {
                return ClientInterfaceVariant.VERSION8_5;
            }
            if (n.equals("TAXI_COMPACT")) {
                return ClientInterfaceVariant.TAXI_COMPACT;
            }
        }
        return ClientInterfaceVariant.VERSION8_5;
    }

    private ClientInterfaceTheme parseTheme(String name) {
        if (name != null && name.trim().toUpperCase().contains("DARK")) {
            return ClientInterfaceTheme.ECIT_DARK;
        }
        return ClientInterfaceTheme.ECIT_LIGHT;
    }

    /** Interface density (field spacing): COMPACT matches EDT's compact editor mode; default NORMAL. */
    private ClientInterfaceScale parseScale(String name) {
        if (name != null && name.trim().toUpperCase().contains("COMPACT")) {
            return ClientInterfaceScale.ECIS_COMPACT;
        }
        return ClientInterfaceScale.ECIS_NORMAL;
    }

    /** Class + message + top stack frames (and cause) – enough to locate a render-pipeline failure. */
    private String describeThrowable(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getName());
        if (t.getMessage() != null) {
            sb.append(": ").append(t.getMessage());
        }
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(10, st.length); i++) {
            sb.append("\n  at ").append(st[i]);
        }
        Throwable c = t.getCause();
        if (c != null && c != t) {
            sb.append("\nCaused by: ").append(c.getClass().getName());
            if (c.getMessage() != null) {
                sb.append(": ").append(c.getMessage());
            }
            StackTraceElement[] cs = c.getStackTrace();
            for (int i = 0; i < Math.min(8, cs.length); i++) {
                sb.append("\n  at ").append(cs[i]);
            }
        }
        return sb.toString();
    }

    private void disposeQuietly(org.eclipse.swt.graphics.Resource res) {
        try {
            if (res != null && !res.isDisposed()) {
                res.dispose();
            }
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    // ---- Metadata write, Phase 2: add attribute – DRY-RUN stage --------------------------

    /** Result of {@link #addAttribute}. */
    public static final class AddAttrResult {
        public boolean ok;            // all validations pass → an apply would be valid
        public boolean applied;       // a real write happened (false in the dry-run stage)
        public boolean applyPending;  // apply requested but real write not implemented yet
        public String ownerFqn;
        public String ownerType;      // EClass of the resolved owner
        public boolean ownerFound;
        public boolean ownerSupportsAttributes;
        public String name;
        public Boolean nameAvailable; // null if not checked (owner missing)
        public String typeInput;
        public String typeParsed;     // human-readable normalized type, null if unparseable
        public boolean typeValid;
        public String refFqn;         // for reference types: the resolved owner FQN, e.g. Catalog.X
        public Boolean refResolved;   // null if not a reference type
        public String synonymRu;
        public String plan;           // human-readable "add X of type Y to Z"
        public String message;        // error / note
    }

    /**
     * Phase-2 write tool: add an attribute to a metadata object. Two stages, by {@code apply}:
     * <ul>
     *   <li>{@code apply=false} (default) – DRY-RUN: validate (owner resolvable, attribute name free,
     *       type string parses, reference target exists) and return the planned change WITHOUT writing.
     *   <li>{@code apply=true} – perform the write: build the {@link TypeDescription} from the type
     *       string (platform-primitive proxy via {@link IEObjectProvider}, or the target object's
     *       produced {@code Ref} type via {@link MdProducedTypesUtil}), create the attribute, set its
     *       name/synonym/comment/type/uuid, attach it to the owner's {@code attributes}, and commit in
     *       a BM read-write transaction. The write is skipped when validation fails (nothing written).
     * </ul>
     * The {@code bsl_support_status = EDITABLE} gate before an apply is the caller's
     * responsibility – the EDT support API is not on the surface, so it stays a process gate for now.
     */
    public AddAttrResult addAttribute(String projectName, String ownerFqnRaw, String name, String typeSpec,
            String synonymRu, String comment, boolean apply) {
        AddAttrResult r = new AddAttrResult();
        final String ownerFqn = normalizeOwnerFqn(ownerFqnRaw);
        r.ownerFqn = ownerFqn;
        r.name = name;
        r.typeInput = typeSpec;
        r.synonymRu = synonymRu;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        if (name == null || name.isBlank()) {
            r.message = "attribute name is required";
            return r;
        }
        if (!IDENT.matcher(name).matches()) {
            r.message = "attribute name is not a valid 1C identifier: \"" + name
                    + "\" (letters/digits/underscore; must not start with a digit, no spaces)";
            return r;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }
        ParsedType pt = parseType(typeSpec);
        r.typeValid = pt.valid;
        r.typeParsed = pt.display;

        model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.addAttribute.validate") {
            @Override
            public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                IBmObject o = tx.getTopObjectByFqn(ownerFqn);
                if (o == null) {
                    r.message = "owner object not found: " + ownerFqn;
                    return null;
                }
                r.ownerFound = true;
                r.ownerType = o.eClass().getName();
                EStructuralFeature f = o.eClass().getEStructuralFeature("attributes");
                if (f == null) {
                    r.message = "owner has no 'attributes' (cannot hold attributes): " + r.ownerType;
                    return null;
                }
                r.ownerSupportsAttributes = true;
                boolean used = false;
                try {
                    Object val = o.eGet(f);
                    if (val instanceof List) {
                        for (Object x : (List<?>) val) {
                            if (x instanceof MdObject && name.equalsIgnoreCase(((MdObject) x).getName())) {
                                used = true;
                                break;
                            }
                        }
                    }
                } catch (RuntimeException ignored) {
                    // leave nameAvailable null on failure
                }
                r.nameAvailable = !used;
                if (pt.valid) {
                    String firstRef = null;
                    String unresolved = null;
                    boolean allResolved = true;
                    for (ParsedType part : partsOf(pt)) {
                        if (part.refFqn == null) {
                            continue;
                        }
                        if (firstRef == null) {
                            firstRef = part.refFqn;
                        }
                        if (tx.getTopObjectByFqn(part.refFqn) == null) {
                            allResolved = false;
                            if (unresolved == null) {
                                unresolved = part.refFqn;
                            }
                        }
                    }
                    if (firstRef != null) {
                        r.refResolved = allResolved;
                        r.refFqn = allResolved ? firstRef : unresolved;
                    }
                }
                return null;
            }
        });

        if (r.message == null) {
            r.ok = r.ownerFound && r.ownerSupportsAttributes && Boolean.TRUE.equals(r.nameAvailable)
                    && r.typeValid && (r.refFqn == null || Boolean.TRUE.equals(r.refResolved));
            r.plan = "Добавить реквизит \"" + name + "\" типа " + (r.typeParsed != null ? r.typeParsed : typeSpec)
                    + " в " + ownerFqn + (synonymRu != null && !synonymRu.isBlank() ? " (синоним: " + synonymRu + ")" : "");
            if (!r.typeValid) {
                r.message = "type not recognized: \"" + typeSpec + "\" (supported: Строка(N), Число(N,M), "
                        + "Булево, Дата/ДатаВремя/Время, ХранилищеЗначения, ОпределяемыйТип.X, and a reference "
                        + "<kind>Ссылка.X where <kind> = Справочник/Документ/Перечисление/ПланВидовХарактеристик/"
                        + "ПланСчетов/ПланВидовРасчета/БизнесПроцесс/Задача/ПланОбмена)";
            } else if (Boolean.FALSE.equals(r.nameAvailable)) {
                r.message = "attribute already exists: " + name;
            } else if (r.refFqn != null && Boolean.FALSE.equals(r.refResolved)) {
                r.message = "reference target not found: " + r.refFqn;
            }
        }
        // Stage 2 – apply: only when requested AND validation passed. Refuse to write otherwise.
        if (apply) {
            if (!r.ok) {
                r.message = (r.message == null ? "validation failed" : r.message)
                        + " – apply refused (nothing written).";
                return r;
            }
            final Version version = projectVersion(p);
            try {
                model.execute(new AbstractBmTask<Object>("edt-bridge.addAttribute.apply") {
                    @Override
                    public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                        IBmObject o = tx.getTopObjectByFqn(ownerFqn);
                        EReference attrsRef = (EReference) o.eClass().getEStructuralFeature("attributes");
                        EClass attrEClass = attrsRef.getEReferenceType();
                        EObject attr = attrEClass.getEPackage().getEFactoryInstance().create(attrEClass);
                        MdObject md = (MdObject) attr;
                        md.setName(name);
                        md.setUuid(UUID.randomUUID());
                        if (synonymRu != null && !synonymRu.isBlank()) {
                            md.getSynonym().put("ru", synonymRu);
                        }
                        if (comment != null && !comment.isBlank()) {
                            md.setComment(comment);
                        }
                        ((BasicFeature) attr).setType(buildTypeDescription(tx, pt, version));
                        @SuppressWarnings("unchecked")
                        List<EObject> attrs = (List<EObject>) o.eGet(attrsRef);
                        attrs.add(attr);
                        return null;
                    }
                });
                // BM commit mutates the in-memory model only; force the DT export so the change is
                // serialized to the .mdo on disk (IBmModelManager.forceExport, the model->files step).
                IDtProject dtProject = mm.getDtProject(model);
                boolean exported = dtProject != null && mm.forceExport(dtProject, ownerFqn);
                r.applied = true;
                r.message = "written: added attribute \"" + name + "\" to " + ownerFqn
                        + (exported ? " (serialized to .mdo)"
                                : " – WARNING: committed in-memory but forceExport did not persist (.mdo unchanged)");
            } catch (RuntimeException ex) {
                r.applied = false;
                r.message = "apply failed (nothing committed): " + ex.getClass().getSimpleName()
                        + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
            }
        }
        return r;
    }

    // ---- Metadata write, Phase 2: remove attribute (DESTRUCTIVE; reference-checked) -----------------

    /** Result of {@link #removeAttribute}. */
    public static final class RemoveAttrResult {
        public boolean ok;            // safe to remove: attr found AND (not referenced OR forced)
        public boolean applied;       // a real removal happened
        public String ownerFqn;
        public String ownerType;
        public boolean ownerFound;
        public String name;
        public boolean attrFound;
        public Integer refCount;      // total inbound references (null if not checked)
        public int externalRefCount;  // references from OTHER top objects (the block driver)
        public boolean referenced;    // = externalRefCount > 0
        public boolean forced;
        public List<Ref> refs = new ArrayList<>(); // sample of referencing sources
        public String plan;
        public String message;
    }

    /**
     * Phase-2 write tool: remove an attribute from a metadata object. DESTRUCTIVE – removing an
     * attribute is a schema change (drops the column in a real infobase). Checks inbound references
     * first (BM cross-reference index): if referenced, removal is blocked unless {@code force=true}.
     * Dry-run by default ({@code apply=false}) reports references + the plan. The caller must verify
     * {@code bsl_support_status = EDITABLE} before any apply.
     */
    public RemoveAttrResult removeAttribute(String projectName, String ownerFqnRaw, String name,
            boolean apply, boolean force) {
        RemoveAttrResult r = new RemoveAttrResult();
        final String ownerFqn = normalizeOwnerFqn(ownerFqnRaw);
        r.ownerFqn = ownerFqn;
        r.name = name;
        r.forced = force;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        if (name == null || name.isBlank()) {
            r.message = "attribute name is required";
            return r;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }

        // Stage 1 – locate the attribute and collect its inbound references (read).
        model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.removeAttribute.inspect") {
            @Override
            public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                IBmObject o = tx.getTopObjectByFqn(ownerFqn);
                if (o == null) {
                    r.message = "owner object not found: " + ownerFqn;
                    return null;
                }
                r.ownerFound = true;
                r.ownerType = o.eClass().getName();
                EStructuralFeature f = o.eClass().getEStructuralFeature("attributes");
                if (f == null) {
                    r.message = "owner has no 'attributes': " + r.ownerType;
                    return null;
                }
                IBmObject attr = null;
                Object val = o.eGet(f);
                if (val instanceof List) {
                    for (Object x : (List<?>) val) {
                        if (x instanceof MdObject && name.equalsIgnoreCase(((MdObject) x).getName())
                                && x instanceof IBmObject) {
                            attr = (IBmObject) x;
                            break;
                        }
                    }
                }
                if (attr == null) {
                    r.message = "attribute not found: " + name;
                    return null;
                }
                r.attrFound = true;
                // The BM xref index also counts the owner's own internal plumbing (producedTypes,
                // own forms, presentation) – those are NOT a breakage signal and EDT updates them on
                // removal. Only references from OTHER top objects matter, so classify and block on those.
                Collection<IBmCrossReference> refs = tx.getReferences(attr.bmGetUri());
                r.refCount = refs.size();
                String ownerPrefix = ownerFqn + ".";
                int ext = 0;
                for (IBmCrossReference xr : refs) {
                    IBmObject src = xr.getObject();
                    if (src == null) {
                        continue;
                    }
                    IBmObject top = src.bmGetTopObject();
                    String topFqn = (top != null) ? top.bmGetFqn() : src.bmGetFqn();
                    boolean internal = topFqn == null || topFqn.equals(ownerFqn) || topFqn.startsWith(ownerPrefix);
                    if (internal) {
                        continue;
                    }
                    ext++;
                    if (r.refs.size() < 20) {
                        Ref e = new Ref();
                        e.sourceFqn = topFqn;
                        e.sourceType = (top != null) ? top.eClass().getName() : src.eClass().getName();
                        e.feature = (xr.getFeature() != null) ? xr.getFeature().getName() : null;
                        e.sourceUri = src.bmGetUriAsString();
                        r.refs.add(e);
                    }
                }
                r.externalRefCount = ext;
                r.referenced = ext > 0;
                return null;
            }
        });

        if (!r.attrFound) {
            return r; // owner/attr-not-found message already set
        }
        r.ok = !r.referenced || force;
        r.plan = "Удалить реквизит \"" + name + "\" из " + ownerFqn + " (входящих ссылок всего: "
                + r.refCount + ", внешних: " + r.externalRefCount + ")";
        if (r.referenced && !force) {
            r.message = "attribute is referenced by " + r.externalRefCount + " other object(s) – removal "
                    + "blocked; pass force=true to remove anyway (this will break those references). "
                    + "Note: BSL text references may not be captured by the model index.";
        }

        // Stage 2 – apply: remove from the containment, commit, serialize. Refused when not ok.
        if (apply) {
            if (!r.ok) {
                r.message = (r.message == null ? "cannot remove" : r.message)
                        + " – apply refused (nothing removed).";
                return r;
            }
            try {
                model.execute(new AbstractBmTask<Object>("edt-bridge.removeAttribute.apply") {
                    @Override
                    public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                        IBmObject o = tx.getTopObjectByFqn(ownerFqn);
                        EStructuralFeature f = o.eClass().getEStructuralFeature("attributes");
                        Object val = o.eGet(f);
                        if (val instanceof List) {
                            Iterator<?> it = ((List<?>) val).iterator();
                            while (it.hasNext()) {
                                Object x = it.next();
                                if (x instanceof MdObject && name.equalsIgnoreCase(((MdObject) x).getName())) {
                                    it.remove();
                                    break;
                                }
                            }
                        }
                        return null;
                    }
                });
                IDtProject dtProject = mm.getDtProject(model);
                boolean exported = dtProject != null && mm.forceExport(dtProject, ownerFqn);
                r.applied = true;
                r.message = "removed attribute \"" + name + "\" from " + ownerFqn
                        + (exported ? " (serialized to .mdo)"
                                : " – WARNING: removed in-memory but forceExport did not persist (.mdo unchanged)");
            } catch (RuntimeException ex) {
                r.applied = false;
                r.message = "remove failed (nothing committed): " + ex.getClass().getSimpleName()
                        + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
            }
        }
        return r;
    }

    // ---- Metadata write, Phase 2: modify attribute (type / synonym / comment) ----------------------

    /** Result of {@link #modifyAttribute}. */
    public static final class ModifyAttrResult {
        public boolean ok;
        public boolean applied;
        public String ownerFqn;
        public String ownerType;
        public boolean ownerFound;
        public boolean attrFound;
        public String name;
        public String currentType;    // rendered current value type
        public boolean typeChange;
        public String newType;        // parsed display of the requested type
        public boolean typeValid;
        public String refFqn;         // for a new reference type
        public Boolean refResolved;
        public boolean synonymChange;
        public boolean commentChange;
        public String plan;
        public String warning;
        public String message;
    }

    /**
     * Phase-2 write tool: modify an existing attribute – its type, ru synonym and/or comment. At least
     * one change must be requested. Changing the TYPE may break backward compatibility (data + code) –
     * a warning is returned. Dry-run by default ({@code apply=false}). Caller verifies
     * {@code bsl_support_status = EDITABLE} before apply.
     */
    public ModifyAttrResult modifyAttribute(String projectName, String ownerFqnRaw, String name,
            String newType, String newSynonymRu, String newComment, boolean apply) {
        ModifyAttrResult r = new ModifyAttrResult();
        final String ownerFqn = normalizeOwnerFqn(ownerFqnRaw);
        r.ownerFqn = ownerFqn;
        r.name = name;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        if (name == null || name.isBlank()) {
            r.message = "attribute name is required";
            return r;
        }
        boolean wantType = newType != null && !newType.isBlank();
        boolean wantSyn = newSynonymRu != null;
        boolean wantComment = newComment != null;
        if (!wantType && !wantSyn && !wantComment) {
            r.message = "nothing to change – provide at least one of newType, newSynonymRu, newComment";
            return r;
        }
        r.typeChange = wantType;
        r.synonymChange = wantSyn;
        r.commentChange = wantComment;
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }
        final ParsedType pt = wantType ? parseType(newType) : null;
        if (wantType) {
            r.typeValid = pt.valid;
            r.newType = pt.display;
        }

        // Stage 1 – find the attribute, capture its current type, validate the new type's references.
        model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.modifyAttribute.inspect") {
            @Override
            public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                IBmObject o = tx.getTopObjectByFqn(ownerFqn);
                if (o == null) {
                    r.message = "owner object not found: " + ownerFqn;
                    return null;
                }
                r.ownerFound = true;
                r.ownerType = o.eClass().getName();
                EStructuralFeature f = o.eClass().getEStructuralFeature("attributes");
                if (f == null) {
                    r.message = "owner has no 'attributes': " + r.ownerType;
                    return null;
                }
                Object attr = null;
                Object val = o.eGet(f);
                if (val instanceof List) {
                    for (Object x : (List<?>) val) {
                        if (x instanceof MdObject && name.equalsIgnoreCase(((MdObject) x).getName())) {
                            attr = x;
                            break;
                        }
                    }
                }
                if (attr == null) {
                    r.message = "attribute not found: " + name;
                    return null;
                }
                r.attrFound = true;
                if (attr instanceof BasicFeature) {
                    r.currentType = renderType(((BasicFeature) attr).getType());
                }
                if (wantType && pt.valid) {
                    for (ParsedType part : partsOf(pt)) {
                        if (part.refFqn != null) {
                            if (r.refFqn == null) {
                                r.refFqn = part.refFqn;
                            }
                            if (tx.getTopObjectByFqn(part.refFqn) == null) {
                                r.refResolved = Boolean.FALSE;
                                r.refFqn = part.refFqn;
                                break;
                            }
                            r.refResolved = Boolean.TRUE;
                        }
                    }
                }
                return null;
            }
        });

        if (!r.attrFound) {
            return r;
        }
        r.ok = (!wantType || (r.typeValid && (r.refFqn == null || Boolean.TRUE.equals(r.refResolved))));
        StringBuilder plan = new StringBuilder("Изменить реквизит \"" + name + "\" в " + ownerFqn + ":");
        if (wantType) {
            plan.append(" тип ").append(r.currentType != null ? r.currentType : "?").append(" → ").append(r.newType);
        }
        if (wantSyn) {
            plan.append(" синоним(ru) → \"").append(newSynonymRu).append("\"");
        }
        if (wantComment) {
            plan.append(" comment → \"").append(newComment).append("\"");
        }
        r.plan = plan.toString();
        if (wantType) {
            r.warning = "смена типа реквизита может нарушить обратную совместимость (данные в ИБ и код).";
            if (!r.typeValid) {
                r.message = "type not recognized: \"" + newType + "\"";
            } else if (r.refFqn != null && Boolean.FALSE.equals(r.refResolved)) {
                r.message = "reference target not found: " + r.refFqn;
            }
        }

        // Stage 2 – apply.
        if (apply) {
            if (!r.ok) {
                r.message = (r.message == null ? "cannot modify" : r.message) + " – apply refused (nothing changed).";
                return r;
            }
            final Version version = projectVersion(p);
            try {
                model.execute(new AbstractBmTask<Object>("edt-bridge.modifyAttribute.apply") {
                    @Override
                    public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                        IBmObject o = tx.getTopObjectByFqn(ownerFqn);
                        EStructuralFeature f = o.eClass().getEStructuralFeature("attributes");
                        Object val = o.eGet(f);
                        Object attr = null;
                        if (val instanceof List) {
                            for (Object x : (List<?>) val) {
                                if (x instanceof MdObject && name.equalsIgnoreCase(((MdObject) x).getName())) {
                                    attr = x;
                                    break;
                                }
                            }
                        }
                        if (attr == null) {
                            throw new IllegalStateException("attribute disappeared: " + name);
                        }
                        if (wantType) {
                            ((BasicFeature) attr).setType(buildTypeDescription(tx, pt, version));
                        }
                        if (wantSyn) {
                            if (newSynonymRu.isBlank()) {
                                ((MdObject) attr).getSynonym().removeKey("ru");
                            } else {
                                ((MdObject) attr).getSynonym().put("ru", newSynonymRu);
                            }
                        }
                        if (wantComment) {
                            ((MdObject) attr).setComment(newComment);
                        }
                        return null;
                    }
                });
                IDtProject dtProject = mm.getDtProject(model);
                boolean exported = dtProject != null && mm.forceExport(dtProject, ownerFqn);
                r.applied = true;
                r.message = "modified attribute \"" + name + "\" in " + ownerFqn
                        + (exported ? " (serialized to .mdo)"
                                : " – WARNING: changed in-memory but forceExport did not persist (.mdo unchanged)");
            } catch (RuntimeException ex) {
                r.applied = false;
                r.message = "modify failed (nothing committed): " + ex.getClass().getSimpleName()
                        + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
            }
        }
        return r;
    }

    // ---- Metadata write, Phase 2: rename via EDT's native refactoring engine -----------

    /** One change item in a rename refactoring's plan (the dry-run preview). */
    public static final class RenameItem {
        public String name;       // human-readable description of the change
        public boolean optional;  // the item may be unchecked (engine offers it as optional)
        public boolean checked;   // selected to be applied
    }

    /** One validation problem reported by the refactoring engine (a blocker for apply). */
    public static final class RenameProblem {
        public String kind;       // problem class simple name (EditingForbiddenProblem, ...)
        public String object;     // best-effort FQN / description of the problematic object
    }

    /** Result of {@link #renameObject}. */
    public static final class RenameResult {
        public boolean ok;            // dry-run valid (refactoring built, no problems) → an apply would run
        public boolean applied;       // the cascade was performed
        public String targetFqn;
        public boolean targetFound;
        public String targetType;     // EClass of the resolved target
        public boolean topObject;     // target is a top metadata object (the widest blast radius)
        public String currentName;
        public String newName;
        public boolean nameValid;     // newName is a legal 1C identifier and differs from the current name
        public boolean forced;
        public int refactoringCount;  // number of IRefactoring (base config + adopted extension counterparts)
        public boolean truncated;     // the item list was capped
        public List<RenameItem> items = new ArrayList<>();
        public List<RenameProblem> problems = new ArrayList<>();
        public String plan;
        public String warning;
        public String message;
    }

    /** Max items returned in the dry-run preview (the cascade can touch very many objects). */
    private static final int RENAME_ITEM_CAP = 300;

    /**
     * Phase-2 flagship write tool: rename a metadata object OR a child member (attribute,
     * dimension, resource, ...) and cascade every reference in metadata AND BSL, using EDT's OWN
     * refactoring engine ({@link IMdRefactoringService#createMdObjectRenameRefactoring}) – not a brittle
     * text replace. Two stages by {@code apply}:
     * <ul>
     *   <li>{@code apply=false} (default) – DRY-RUN: resolve the target, build the refactoring(s) and
     *       return their change items ({@code getItems}) + validation problems ({@code getStatus}),
     *       performing nothing.
     *   <li>{@code apply=true} – perform the cascade via {@code IRefactoring.perform()}, run inside
     *       {@link IProjectOperationApi#performExclusiveOperation} (the build pipeline is suspended and
     *       the project locked – exactly how EDT's own editor applies a name change). The engine manages
     *       its own BM write transactions and serializes the touched objects.
     * </ul>
     * Renaming is the widest backward-compatibility surface: a rename is breaking for peer
     * configurations, so an apply additionally requires {@code force=true} (the owner's explicit
     * override) on top of the configured token. The {@code bsl_support_status = EDITABLE} gate before an
     * apply is the caller's responsibility.
     */
    public RenameResult renameObject(String projectName, String targetFqn, String newName,
            boolean apply, boolean force) {
        RenameResult r = new RenameResult();
        r.targetFqn = targetFqn;
        r.newName = newName;
        r.forced = force;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        if (targetFqn == null || targetFqn.isBlank()) {
            r.message = "targetFqn is required";
            return r;
        }
        if (newName == null || newName.isBlank() || !IDENT.matcher(newName).matches()) {
            r.message = "newName is not a valid 1C identifier: \"" + newName
                    + "\" (letters/digits/underscore; must not start with a digit, no spaces)";
            return r;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }
        IMdRefactoringService service = ServiceAccess.get(IMdRefactoringService.class);
        if (service == null) {
            r.message = "md refactoring service unavailable (EDT refactoring bundles not active)";
            return r;
        }

        // Stage 1 – resolve the target and BUILD the refactoring (read-only). Building is pure analysis
        // (it computes the change list + problems), so it is safe inside a read-only BM transaction,
        // which also guarantees the MdObject we hand to the engine is live. perform() happens later,
        // OUTSIDE any transaction (it opens its own write transactions via a batch session).
        final List<IRefactoring> built = new ArrayList<>();
        model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.rename.build") {
            @Override
            public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                EObject target = tx.getTopObjectByFqn(targetFqn);
                boolean top = target instanceof MdObject;
                if (!top) {
                    // Not a top object – resolve a child member by its full FQN (e.g.
                    // Catalog.Контрагенты.Attribute.ИНН). The FQN is the top object (first two segments)
                    // followed by (kind, name) pairs; walk them by name (a child MdObject does NOT
                    // support bmGetFqn, only top objects do).
                    String[] segs = targetFqn.split("\\.");
                    if (segs.length >= 4 && segs.length % 2 == 0) {
                        EObject topObj = tx.getTopObjectByFqn(segs[0] + "." + segs[1]);
                        if (topObj instanceof MdObject) {
                            target = resolveChild(topObj, segs, 2);
                        }
                    }
                }
                if (!(target instanceof MdObject)) {
                    r.message = "target not found: " + targetFqn;
                    return null;
                }
                MdObject md = (MdObject) target;
                r.targetFound = true;
                r.targetType = md.eClass().getName();
                r.topObject = top;
                r.currentName = md.getName();
                if (newName.equals(r.currentName)) {
                    r.nameValid = false;
                    r.message = "newName equals the current name: " + newName;
                    return null;
                }
                r.nameValid = true;
                Collection<IRefactoring> refs = service.createMdObjectRenameRefactoring(md, newName);
                if (refs != null) {
                    built.addAll(refs);
                }
                r.refactoringCount = built.size();
                for (IRefactoring rf : built) {
                    try {
                        if (rf.getStatus() != null) {
                            for (IRefactoringProblem pr : rf.getStatus().getProblems()) {
                                RenameProblem rp = new RenameProblem();
                                rp.kind = pr.getClass().getSimpleName();
                                rp.object = describeProblemObject(pr.getObject());
                                r.problems.add(rp);
                            }
                        }
                        for (IRefactoringItem it : rf.getItems()) {
                            if (r.items.size() >= RENAME_ITEM_CAP) {
                                r.truncated = true;
                                break;
                            }
                            RenameItem ri = new RenameItem();
                            ri.name = it.getName();
                            ri.optional = it.isOptional();
                            ri.checked = it.isChecked();
                            r.items.add(ri);
                        }
                    } catch (RuntimeException ignored) {
                        // item/problem extraction is best-effort
                    }
                }
                return null;
            }
        });

        if (!r.targetFound || !r.nameValid) {
            return r; // message already set
        }
        r.ok = r.refactoringCount > 0 && r.problems.isEmpty();
        r.plan = "Переименовать " + (r.topObject ? "объект" : "реквизит/член") + " \"" + r.currentName
                + "\" → \"" + newName + "\" в " + targetFqn + " (затрагиваемых изменений: "
                + r.items.size() + (r.truncated ? "+" : "") + ", refactorings: " + r.refactoringCount + ")";
        r.warning = "переименование меняет контракт метаданных и НАРУШАЕТ обратную совместимость для "
                + "конфигураций-партнёров (SA/SM/расширения на других внедрениях);. Каскад "
                + "затрагивает метаданные И BSL во всех проектах. Требуется одобрение владельца.";
        if (!r.problems.isEmpty()) {
            r.message = "refactoring engine reported " + r.problems.size() + " problem(s) – apply blocked "
                    + "(e.g. name taken, or object not editable / support-locked).";
        }

        // Stage 2 – apply: perform the cascade. Requires force=true (the breaking-change override) AND a
        // clean dry-run. Runs OUTSIDE the read transaction, inside an exclusive project operation.
        if (apply) {
            if (!force) {
                r.message = "rename is a breaking change for peer configurations – apply "
                        + "refused; pass force=true (owner's explicit override) to perform it.";
                return r;
            }
            if (!r.ok) {
                r.message = (r.message == null ? "cannot rename" : r.message) + " – apply refused (nothing changed).";
                return r;
            }
            try {
                IProjectOperationApi ops = ServiceAccess.get(IProjectOperationApi.class);
                IDtProject dtProject = mm.getDtProject(model);
                final List<IRefactoring> toPerform = built;
                Runnable run = () -> {
                    for (IRefactoring rf : toPerform) {
                        rf.perform();
                    }
                };
                if (ops != null && dtProject != null) {
                    ops.performExclusiveOperation(run, ProjectPipelineJob.BUILD, new NullProgressMonitor(), dtProject);
                } else {
                    // Fallback: the engine still manages its own BM transactions; we just lose the
                    // build-pipeline suspension. Surface that we took the fallback path.
                    run.run();
                    r.warning = (r.warning == null ? "" : r.warning + " ")
                            + "(applied WITHOUT exclusive project lock: IProjectOperationApi/IDtProject unavailable.)";
                }
                r.applied = true;
                r.message = "renamed \"" + r.currentName + "\" → \"" + newName + "\" in " + targetFqn
                        + " – cascade performed across metadata + BSL.";
            } catch (Exception ex) {
                r.applied = false;
                r.message = "apply failed (cascade may be partial – verify the working tree): "
                        + ex.getClass().getSimpleName() + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
            }
        }
        return r;
    }

    /**
     * Resolve a child {@link MdObject} from its FQN segments: from {@code container}, consume the
     * remaining (kind, name) pairs starting at {@code idx}, descending one named child per pair. The
     * kind segment (e.g. "Attribute"/"TabularSection"/"Form") disambiguates when names collide – a
     * child's eClass name ends with it (CatalogAttribute → "Attribute"). Child objects do not expose
     * a BM FQN, so this name-walk is how a nested member is located.
     */
    private MdObject resolveChild(EObject container, String[] segs, int idx) {
        EObject cur = container;
        for (int i = idx; i + 1 < segs.length; i += 2) {
            String kind = segs[i];
            String name = segs[i + 1];
            MdObject byNameKind = null;
            MdObject byName = null;
            for (EReference ref : cur.eClass().getEAllContainments()) {
                if (ref.isDerived() || ref.isTransient()) {
                    continue;
                }
                Object val;
                try {
                    val = cur.eGet(ref);
                } catch (RuntimeException e) {
                    continue;
                }
                List<?> list = (val instanceof List) ? (List<?>) val
                        : (val instanceof EObject) ? List.of(val) : List.of();
                for (Object x : list) {
                    if (!(x instanceof MdObject)) {
                        continue;
                    }
                    MdObject md = (MdObject) x;
                    if (!name.equals(md.getName())) {
                        continue;
                    }
                    if (md.eClass().getName().endsWith(kind)) {
                        byNameKind = md;
                    } else if (byName == null) {
                        byName = md;
                    }
                }
            }
            MdObject next = (byNameKind != null) ? byNameKind : byName;
            if (next == null) {
                return null;
            }
            cur = next;
        }
        return (cur instanceof MdObject) ? (MdObject) cur : null;
    }

    /** Best-effort label for a refactoring problem's object: its BM FQN, else its eClass name. */
    private String describeProblemObject(EObject o) {
        if (o == null) {
            return null;
        }
        if (o instanceof IBmObject) {
            String f = ((IBmObject) o).bmGetFqn();
            if (f != null && !f.isBlank()) {
                return f;
            }
        }
        return o.eClass().getName();
    }

    // ---- Metadata write, Phase 2: delete a metadata object (inverse of create) ----------

    /**
     * Result of {@link #deleteObject}. Reuses {@link RenameItem}/{@link RenameProblem} for the engine's
     * change list + validation problems (same refactoring family as rename).
     */
    public static final class DeleteObjectResult {
        public boolean ok;            // dry-run valid (refactoring built, no problems) → an apply would run
        public boolean applied;       // the delete cascade was performed
        public String targetFqn;
        public boolean targetFound;
        public String targetType;     // EClass of the resolved target
        public boolean topObject;     // target is a top metadata object (the widest blast radius)
        public String name;           // resolved object/member name
        public boolean forced;
        public int refactoringCount;  // 1 when the engine built a delete refactoring
        public boolean truncated;     // the item list was capped
        public List<RenameItem> items = new ArrayList<>();
        public List<RenameProblem> problems = new ArrayList<>();
        public String plan;
        public String warning;
        public String message;
    }

    /**
     * Delete a metadata object (the inverse of {@link #createObject}) – a top object (e.g.
     * {@code Catalog.X}) or a child member – and cascade the removal of every reference in metadata AND
     * BSL using EDT's OWN refactoring engine
     * ({@link IMdRefactoringService#createMdObjectDeleteRefactoring}), the same native machinery as
     * {@link #renameObject}. The engine deletes the object's {@code .mdo} (and its directory) and updates
     * the {@code Configuration}, so no manual detach/forceExport is needed. Two stages by {@code apply}:
     * <ul>
     *   <li>{@code apply=false} (default) – DRY-RUN: resolve the target, build the delete refactoring and
     *       return its change items ({@code getItems}) + validation problems ({@code getStatus}), deleting
     *       nothing.
     *   <li>{@code apply=true} – perform the cascade via {@code IRefactoring.perform()} inside
     *       {@link IProjectOperationApi#performExclusiveOperation} (build pipeline suspended, project
     *       locked), exactly as the rename apply does.
     * </ul>
     * Deleting an object is irreversible and breaking for peer configurations, so an apply requires
     * {@code force=true} (the owner's explicit override) on top of the configured token. The
     * {@code bsl_support_status = EDITABLE} gate before an apply is the caller's responsibility.
     */
    public DeleteObjectResult deleteObject(String projectName, String targetFqn, boolean apply, boolean force) {
        DeleteObjectResult r = new DeleteObjectResult();
        r.targetFqn = targetFqn;
        r.forced = force;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        if (targetFqn == null || targetFqn.isBlank()) {
            r.message = "targetFqn is required";
            return r;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }
        IMdRefactoringService service = ServiceAccess.get(IMdRefactoringService.class);
        if (service == null) {
            r.message = "md refactoring service unavailable (EDT refactoring bundles not active)";
            return r;
        }

        // Stage 1 – resolve the target and BUILD the delete refactoring (read-only analysis), mirroring
        // rename: building is pure analysis (change list + problems), safe in a read-only BM transaction,
        // and guarantees the MdObject handed to the engine is live. perform() happens later, outside any tx.
        final List<IRefactoring> built = new ArrayList<>();
        model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.delete.build") {
            @Override
            public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                EObject target = tx.getTopObjectByFqn(targetFqn);
                boolean top = target instanceof MdObject;
                if (!top) {
                    // Resolve a child member by its full FQN (e.g. Catalog.X.Attribute.Y), as rename does.
                    String[] segs = targetFqn.split("\\.");
                    if (segs.length >= 4 && segs.length % 2 == 0) {
                        EObject topObj = tx.getTopObjectByFqn(segs[0] + "." + segs[1]);
                        if (topObj instanceof MdObject) {
                            target = resolveChild(topObj, segs, 2);
                        }
                    }
                }
                if (!(target instanceof MdObject)) {
                    r.message = "target not found: " + targetFqn;
                    return null;
                }
                MdObject md = (MdObject) target;
                r.targetFound = true;
                r.targetType = md.eClass().getName();
                r.topObject = top;
                r.name = md.getName();
                IRefactoring del = service.createMdObjectDeleteRefactoring(List.of(md));
                if (del != null) {
                    built.add(del);
                }
                r.refactoringCount = built.size();
                for (IRefactoring rf : built) {
                    try {
                        if (rf.getStatus() != null) {
                            for (IRefactoringProblem pr : rf.getStatus().getProblems()) {
                                RenameProblem rp = new RenameProblem();
                                rp.kind = pr.getClass().getSimpleName();
                                rp.object = describeProblemObject(pr.getObject());
                                r.problems.add(rp);
                            }
                        }
                        for (IRefactoringItem it : rf.getItems()) {
                            if (r.items.size() >= RENAME_ITEM_CAP) {
                                r.truncated = true;
                                break;
                            }
                            RenameItem ri = new RenameItem();
                            ri.name = it.getName();
                            ri.optional = it.isOptional();
                            ri.checked = it.isChecked();
                            r.items.add(ri);
                        }
                    } catch (RuntimeException ignored) {
                        // item/problem extraction is best-effort
                    }
                }
                return null;
            }
        });

        if (!r.targetFound) {
            return r; // target-not-found message already set
        }
        r.ok = r.refactoringCount > 0 && r.problems.isEmpty();
        r.plan = "Удалить " + (r.topObject ? "объект" : "член") + " \"" + r.name + "\" (" + targetFqn
                + "), затрагиваемых изменений: " + r.items.size() + (r.truncated ? "+" : "")
                + ", refactorings: " + r.refactoringCount;
        r.warning = "удаление объекта НЕОБРАТИМО и НАРУШАЕТ обратную совместимость для конфигураций-партнёров; "
                + "каскад удаляет .mdo объекта и чистит ссылки в метаданных И BSL во всех проектах. Требуется "
                + "одобрение владельца. Перед apply сделайте резервную копию.";
        if (!r.problems.isEmpty()) {
            r.message = "refactoring engine reported " + r.problems.size() + " problem(s) – apply blocked "
                    + "(e.g. object not editable / support-locked).";
        }

        // Stage 2 – apply: perform the delete cascade. Requires force=true (the breaking-change override)
        // AND a clean dry-run. Runs outside the read transaction, inside an exclusive project operation.
        if (apply) {
            if (!force) {
                r.message = "delete is an irreversible breaking change for peer configurations – apply "
                        + "refused; pass force=true (owner's explicit override) to perform it.";
                return r;
            }
            if (!r.ok) {
                r.message = (r.message == null ? "cannot delete" : r.message) + " – apply refused (nothing deleted).";
                return r;
            }
            try {
                IProjectOperationApi ops = ServiceAccess.get(IProjectOperationApi.class);
                IDtProject dtProject = mm.getDtProject(model);
                final List<IRefactoring> toPerform = built;
                Runnable run = () -> {
                    for (IRefactoring rf : toPerform) {
                        rf.perform();
                    }
                };
                if (ops != null && dtProject != null) {
                    ops.performExclusiveOperation(run, ProjectPipelineJob.BUILD, new NullProgressMonitor(), dtProject);
                } else {
                    // Fallback: the engine still manages its own BM transactions; we lose only the
                    // build-pipeline suspension. Surface that we took the fallback path.
                    run.run();
                    r.warning = (r.warning == null ? "" : r.warning + " ")
                            + "(applied WITHOUT exclusive project lock: IProjectOperationApi/IDtProject unavailable.)";
                }
                r.applied = true;
                r.message = "deleted \"" + r.name + "\" (" + targetFqn
                        + ") – cascade performed across metadata + BSL.";
            } catch (Exception ex) {
                r.applied = false;
                r.message = "apply failed (cascade may be partial – verify the working tree): "
                        + ex.getClass().getSimpleName() + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
            }
        }
        return r;
    }

    // ---- Metadata write, Phase 2: create a top metadata object --------------------------

    /** Russian object kind (lower-cased) → MdClass EClass name, for {@link #createObject}. */
    private static final Map<String, String> CREATE_KIND_TO_MDCLASS = Map.ofEntries(
            Map.entry("справочник", "Catalog"),
            Map.entry("документ", "Document"),
            Map.entry("перечисление", "Enum"),
            Map.entry("регистрсведений", "InformationRegister"),
            Map.entry("регистрнакопления", "AccumulationRegister"),
            Map.entry("регистрбухгалтерии", "AccountingRegister"),
            Map.entry("регистррасчета", "CalculationRegister"),
            Map.entry("отчет", "Report"),
            Map.entry("обработка", "DataProcessor"),
            Map.entry("планвидовхарактеристик", "ChartOfCharacteristicTypes"),
            Map.entry("плансчетов", "ChartOfAccounts"),
            Map.entry("планвидоврасчета", "ChartOfCalculationTypes"),
            Map.entry("бизнеспроцесс", "BusinessProcess"),
            Map.entry("задача", "Task"),
            Map.entry("планобмена", "ExchangePlan"),
            Map.entry("константа", "Constant"),
            Map.entry("общиймодуль", "CommonModule"));

    /**
     * Normalize the class prefix of a top-object FQN from a Russian metadata kind
     * (Справочник, Документ, ...) to the English MdClass name (Catalog, Document, ...)
     * expected by {@code getTopObjectByFqn}. The object NAME (which may be Cyrillic)
     * is left untouched. FQNs that already use an English prefix, or whose head is
     * unknown, are returned unchanged.
     */
    private static String normalizeOwnerFqn(String fqn) {
        if (fqn == null) {
            return null;
        }
        int dot = fqn.indexOf('.');
        if (dot <= 0) {
            return fqn;
        }
        String en = CREATE_KIND_TO_MDCLASS.get(fqn.substring(0, dot).toLowerCase());
        return en == null ? fqn : en + fqn.substring(dot);
    }

    /** Result of {@link #createObject}. */
    public static final class CreateObjectResult {
        public boolean ok;            // dry-run valid → an apply would create
        public boolean applied;       // the object was created
        public String objectType;     // requested Russian kind
        public String eClass;         // resolved MdClass EClass name
        public String name;
        public String fqn;            // Type.name
        public String synonymRu;
        public boolean nameValid;     // name is a legal 1C identifier
        public boolean typeKnown;     // objectType resolved to an eClass
        public Boolean nameAvailable; // null if not checked
        public boolean configFound;
        public String feature;        // Configuration containment feature it goes into (e.g. catalogs)
        public String plan;
        public String warning;
        public String message;
    }

    /**
     * Phase-2 write tool: create a new TOP metadata object (Catalog, Document, Enum,
     * InformationRegister, ...) using EDT's own object factory + per-type initializer (so the object is
     * born valid, with standard attributes/presentations), then register it as a BM top object and link
     * it into the {@code Configuration}. Two stages by {@code apply}:
     * <ul>
     *   <li>{@code apply=false} (default) – DRY-RUN: validate (type → eClass; {@code name} a legal 1C
     *       identifier; FQN free; the {@code Configuration} has a containment feature for the eClass) and
     *       return the plan, creating nothing.
     *   <li>{@code apply=true} – perform the recipe (from EDT's New-object wizard): {@code factory.create}
     *       → set name/synonym/comment → {@code tx.toTransactionObject} → {@code tx.attachTopObject(fqn)}
     *       → add to the config feature → {@code factory.fillDefaultReferences}, then forceExport the new
     *       object AND the Configuration. Creating an object is additive (non-breaking) → no
     *       {@code force}; still token-gated and {@code bsl_support_status} EDITABLE is the caller's gate.
     * </ul>
     */
    public CreateObjectResult createObject(String projectName, String objectType, String name,
            String synonymRu, String comment, boolean apply) {
        CreateObjectResult r = new CreateObjectResult();
        r.objectType = objectType;
        r.name = name;
        r.synonymRu = synonymRu;
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
        String mdClassName = (objectType == null) ? null
                : CREATE_KIND_TO_MDCLASS.get(objectType.trim().toLowerCase());
        if (mdClassName == null) {
            r.message = "unknown objectType: \"" + objectType + "\" (supported: "
                    + "Справочник/Документ/Перечисление/РегистрСведений/РегистрНакопления/РегистрБухгалтерии/"
                    + "РегистрРасчета/Отчет/Обработка/ПланВидовХарактеристик/ПланСчетов/ПланВидовРасчета/"
                    + "БизнесПроцесс/Задача/ПланОбмена/Константа/ОбщийМодуль)";
            return r;
        }
        EClassifier classifier = MdClassPackage.eINSTANCE.getEClassifier(mdClassName);
        if (!(classifier instanceof EClass)) {
            r.message = "MdClass eClass not found for type: " + mdClassName;
            return r;
        }
        final EClass eClass = (EClass) classifier;
        r.typeKnown = true;
        r.eClass = mdClassName;
        final String fqn = mdClassName + "." + name;
        r.fqn = fqn;
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }

        // Stage 1 – validate against the live model (read): config present, has a feature for this
        // eClass, and the FQN is free.
        final String[] featureName = {null};
        model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.createObject.validate") {
            @Override
            public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                EObject config = tx.getTopObjectByFqn("Configuration");
                r.configFound = config != null;
                if (config != null) {
                    EReference feat = findConfigFeature(config, eClass);
                    if (feat != null) {
                        featureName[0] = feat.getName();
                        r.feature = feat.getName();
                    }
                }
                r.nameAvailable = tx.getTopObjectByFqn(fqn) == null;
                return null;
            }
        });

        r.ok = r.configFound && featureName[0] != null && Boolean.TRUE.equals(r.nameAvailable);
        r.plan = "Создать " + objectType + " \"" + name + "\" (" + fqn + ")"
                + (r.feature != null ? " в Configuration." + r.feature : "")
                + (synonymRu != null && !synonymRu.isBlank() ? " (синоним: " + synonymRu + ")" : "");
        r.warning = "новый объект становится частью контракта конфигурации (поставляемая технология, "
                + "/alienability) – имя по конвенциям; добавление аддитивно и обратную совместимость "
                + "не ломает.";
        if (!r.configFound) {
            r.message = "Configuration top object not found";
        } else if (featureName[0] == null) {
            r.message = "Configuration has no containment feature for eClass " + mdClassName;
        } else if (Boolean.FALSE.equals(r.nameAvailable)) {
            r.message = "object already exists: " + fqn;
        }

        // Stage 2 – apply: factory-create (initialized) outside the tx, then attach + link inside it.
        if (apply) {
            if (!r.ok) {
                r.message = (r.message == null ? "validation failed" : r.message)
                        + " – apply refused (nothing created).";
                return r;
            }
            IModelObjectFactory factory = modelObjectFactory();
            if (factory == null) {
                r.message = "model object factory unavailable (EDT md model plugin not active)";
                return r;
            }
            final Version version = projectVersion(p);
            try {
                final EObject created = factory.create(eClass, version);
                if (!(created instanceof MdObject)) {
                    r.message = "factory.create returned " + (created == null ? "null"
                            : created.eClass().getName()) + " (not an MdObject)";
                    return r;
                }
                MdObject md = (MdObject) created;
                md.setName(name);
                md.setUuid(UUID.randomUUID());
                if (synonymRu != null && !synonymRu.isBlank()) {
                    md.getSynonym().put("ru", synonymRu);
                }
                if (comment != null && !comment.isBlank()) {
                    md.setComment(comment);
                }
                // A brand-new object is fully detached; toTransactionObject rejects it ("does not belong
                // to any namespace") in every transaction kind. The correct path for bringing a NEW object
                // tree into the model is the GLOBAL context's IMPORT task: attachTopObject adopts the
                // detached draft directly (no toTransactionObject), then link it into the Configuration.
                model.getGlobalContext().executeImportTask(new AbstractBmTask<Object>("edt-bridge.createObject.apply") {
                    @Override
                    public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                        tx.attachTopObject((IBmObject) created, fqn);
                        EObject config = tx.getTopObjectByFqn("Configuration");
                        EReference feat = findConfigFeature(config, eClass);
                        @SuppressWarnings("unchecked")
                        List<EObject> list = (List<EObject>) config.eGet(feat);
                        list.add(created);
                        factory.fillDefaultReferences(created);
                        return null;
                    }
                }, false);
                // Serialize the new object AND the Configuration (its child list changed).
                IDtProject dtProject = mm.getDtProject(model);
                boolean exportedObj = dtProject != null && mm.forceExport(dtProject, fqn);
                boolean exportedCfg = dtProject != null && mm.forceExport(dtProject, "Configuration");
                r.applied = true;
                r.message = "created " + objectType + " " + fqn
                        + ((exportedObj && exportedCfg) ? " (serialized: object .mdo + Configuration.mdo)"
                                : " – WARNING: created in-memory but forceExport incomplete (object=" + exportedObj
                                        + ", configuration=" + exportedCfg + ")");
            } catch (RuntimeException ex) {
                r.applied = false;
                r.message = "create failed (verify the working tree – a partial object may remain): "
                        + ex.getClass().getSimpleName() + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
            }
        }
        return r;
    }

    /**
     * EDT's metadata-object factory, taken from the MD plugin's Guice injector via reflection (the
     * factory is Guice-bound, NOT registered through {@code ServiceAccess} like IBmModelManager / the
     * refactoring service). Reflection stays contained here, matching {@link #languageInjector}.
     */
    private IModelObjectFactory modelObjectFactory() {
        try {
            Bundle b = Platform.getBundle("com._1c.g5.v8.dt.md");
            if (b == null) {
                return null;
            }
            Class<?> pluginCls = b.loadClass("com._1c.g5.v8.dt.md.MdPlugin");
            Object plugin = pluginCls.getMethod("getDefault").invoke(null);
            if (plugin == null) {
                return null;
            }
            Injector inj = (Injector) pluginCls.getMethod("getInjector").invoke(plugin);
            return (inj == null) ? null : inj.getInstance(IModelObjectFactory.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The {@code Configuration} list {@link EReference} that holds objects of {@code eClass} (e.g.
     * {@code catalogs}). In EDT's mdclass model these are NON-containment references – top objects are
     * owned by the BM, and the Configuration just lists them – so scan all many-valued references, not
     * only containments. Prefer an exact type match over a supertype one.
     */
    private EReference findConfigFeature(EObject config, EClass eClass) {
        if (config == null) {
            return null;
        }
        EReference exact = null;
        EReference superType = null;
        for (EReference ref : config.eClass().getEAllReferences()) {
            if (!ref.isMany()) {
                continue;
            }
            EClass type = ref.getEReferenceType();
            if (type == null) {
                continue;
            }
            if (type == eClass && exact == null) {
                exact = ref;
            } else if (type.isSuperTypeOf(eClass) && superType == null) {
                superType = ref;
            }
        }
        return (exact != null) ? exact : superType;
    }

    /** The project's 1C platform version (for the platform type provider); falls back to LATEST. */
    private Version projectVersion(IProject p) {
        try {
            IRuntimeVersionSupport vs = ServiceAccess.get(IRuntimeVersionSupport.class);
            if (vs != null) {
                Version v = vs.getRuntimeVersionOrDefault(p, Version.LATEST);
                if (v != null) {
                    return v;
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to LATEST
        }
        return Version.LATEST;
    }

    /** Result of {@link #createExtension}. */
    public static final class CreateExtensionResult {
        public boolean ok;            // dry-run valid → an apply would create
        public boolean applied;       // the extension project was created
        public String name;           // extension project (and extension configuration) name
        public String baseProject;    // base configuration project it extends
        public String namePrefix;     // extension name prefix (stamped on its Configuration)
        public String purpose;        // Customization / AddOn / Patch (enum name)
        public boolean nameValid;
        public Boolean nameAvailable; // no workspace project with this name yet; null if not checked
        public boolean baseFound;     // base project exists, is open and has a Configuration
        public String version;        // runtime version inherited from the base project
        public String location;       // created project's disk location
        public boolean stamped;       // prefix/purpose written and serialized
        public String plan;
        public String message;
    }

    /**
     * Phase-2 write tool: create a NEW configuration-extension PROJECT next to a base configuration
     * project, via EDT's own {@link IExtensionProjectManager} – the engine behind the
     * File &gt; New &gt; Configuration Extension wizard. Two stages by {@code apply}:
     * <ul>
     *   <li>{@code apply=false} (default) – DRY-RUN: validate (extension name a legal identifier and
     *       free in the workspace; base project open and carrying a Configuration; purpose known) and
     *       return the plan, creating nothing.
     *   <li>{@code apply=true} – create the project in the default workspace location with the base
     *       project's runtime version, then stamp the extension Configuration's {@code namePrefix} and
     *       {@code configurationExtensionPurpose} and serialize it. The new project's model loads in
     *       background jobs; the stamp step polls for it briefly and reports {@code stamped=false} if
     *       the model is still not ready (re-run later – the create itself is done).
     * </ul>
     */
    public CreateExtensionResult createExtension(String name, String baseProjectName, String namePrefix,
            String purposeName, boolean apply) {
        CreateExtensionResult r = new CreateExtensionResult();
        r.name = name;
        r.baseProject = baseProjectName;
        r.namePrefix = namePrefix;

        if (name == null || name.isBlank() || !IDENT.matcher(name).matches()) {
            r.message = "name is not a valid identifier: \"" + name
                    + "\" (letters/digits/underscore; must not start with a digit, no spaces)";
            return r;
        }
        r.nameValid = true;
        if (namePrefix == null || namePrefix.isBlank() || !IDENT.matcher(namePrefix).matches()) {
            r.message = "namePrefix is required and must be a valid identifier (e.g. \"Расш_\")";
            return r;
        }
        final ConfigurationExtensionPurpose purpose = resolvePurpose(purposeName);
        if (purpose == null) {
            r.message = "unknown purpose: \"" + purposeName
                    + "\" (supported: Customization/Адаптация, AddOn/Дополнение, Patch/Исправление; "
                    + "default Customization)";
            return r;
        }
        r.purpose = purpose.getName();

        IProject base = (baseProjectName == null || baseProjectName.isBlank()) ? null
                : ResourcesPlugin.getWorkspace().getRoot().getProject(baseProjectName);
        if (base == null || !base.exists() || !base.isOpen()) {
            r.message = "base project not found or closed: " + baseProjectName;
            return r;
        }
        IConfigurationProvider cp = ServiceAccess.get(IConfigurationProvider.class);
        Configuration baseConfig = (cp == null) ? null : cp.getConfiguration(base);
        r.baseFound = baseConfig != null;
        if (baseConfig == null) {
            r.message = "base project has no Configuration (not a 1C:EDT configuration project?): "
                    + baseProjectName;
            return r;
        }
        r.nameAvailable = !ResourcesPlugin.getWorkspace().getRoot().getProject(name).exists();
        Version version = projectVersion(base);
        r.version = String.valueOf(version);
        r.ok = Boolean.TRUE.equals(r.nameAvailable);
        r.plan = "Create extension project \"" + name + "\" (prefix " + namePrefix + ", purpose "
                + r.purpose + ") extending " + baseProjectName + " [" + r.version + "]";
        if (Boolean.FALSE.equals(r.nameAvailable)) {
            r.message = "a project with this name already exists in the workspace: " + name;
        }
        if (!apply) {
            return r;
        }
        if (!r.ok) {
            r.message = (r.message == null ? "validation failed" : r.message)
                    + " – apply refused (nothing created).";
            return r;
        }
        IExtensionProjectManager epm = ServiceAccess.get(IExtensionProjectManager.class);
        IModelObjectFactory factory = modelObjectFactory();
        if (epm == null || factory == null) {
            r.message = "extension project services unavailable (IExtensionProjectManager / md factory)";
            return r;
        }
        try {
            // create() attaches the given Configuration object DIRECTLY to the new project's model
            // (ExtensionProjectManager.attachConfiguration -> tx.attachTopObject, no copy). So it must
            // be a FRESH, detached Configuration – the base project's live config fails "object is
            // already attached", and null fails the lifecycle. Build one via the md factory (same
            // factory create_object uses); create() names it after the project.
            EObject freshConfig = factory.create(
                    (EClass) MdClassPackage.eINSTANCE.getEClassifier("Configuration"), version);
            if (!(freshConfig instanceof Configuration)) {
                r.message = "md factory did not produce a Configuration (got "
                        + (freshConfig == null ? "null" : freshConfig.eClass().getName()) + ")";
                return r;
            }
            IProject created = epm.create(name, version, (Configuration) freshConfig, base,
                    new NullProgressMonitor());
            r.location = (created != null && created.getLocation() != null)
                    ? created.getLocation().toOSString() : null;
            r.applied = true;
            r.stamped = stampExtensionConfiguration(created, namePrefix, purpose);
            r.message = "created extension project " + name + " extending " + baseProjectName
                    + (r.stamped ? " (prefix/purpose stamped and serialized)"
                            : " – WARNING: created, but prefix/purpose stamping incomplete (the project"
                              + " model is still loading; re-check later and stamp via the model)");
        } catch (CoreException | RuntimeException ex) {
            r.applied = false;
            r.message = "create failed: " + describeCause(ex);
        }
        return r;
    }

    /**
     * Stamp {@code namePrefix} + {@code purpose} on a freshly created extension project's
     * Configuration and serialize it. The project's BM model loads asynchronously after
     * {@link IExtensionProjectManager#create}, so poll briefly (up to ~30 s) for the model and its
     * Configuration top object before giving up. Returns whether the stamp + export happened.
     */
    private boolean stampExtensionConfiguration(IProject created, String namePrefix,
            ConfigurationExtensionPurpose purpose) {
        if (created == null) {
            return false;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        if (mm == null) {
            return false;
        }
        for (int attempt = 0; attempt < 15; attempt++) {
            IBmModel model = mm.getModel(created);
            if (model != null) {
                try {
                    Boolean done = model.execute(
                            new AbstractBmTask<Boolean>("edt-bridge.createExtension.stamp") {
                                @Override
                                public Boolean execute(IBmTransaction tx, IProgressMonitor monitor) {
                                    EObject cfg = tx.getTopObjectByFqn("Configuration");
                                    if (!(cfg instanceof Configuration)) {
                                        return Boolean.FALSE;
                                    }
                                    ((Configuration) cfg).setNamePrefix(namePrefix);
                                    ((Configuration) cfg).setConfigurationExtensionPurpose(purpose);
                                    return Boolean.TRUE;
                                }
                            });
                    if (Boolean.TRUE.equals(done)) {
                        IDtProject dt = mm.getDtProject(model);
                        return dt != null && mm.forceExport(dt, "Configuration");
                    }
                } catch (RuntimeException ignored) {
                    // model not ready for writes yet – keep polling
                }
            }
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Result of {@link #createExternalObject}. */
    public static final class CreateExternalObjectResult {
        public boolean ok;
        public boolean applied;
        public String name;
        public String baseProject;    // optional configuration project the processor is "for"
        public boolean nameValid;
        public Boolean nameAvailable;
        public String version;
        public String location;
        public String plan;
        public String message;
    }

    /**
     * Phase-2 write tool: create a NEW external-data-processor PROJECT via EDT's own
     * {@link IExternalObjectProjectManager} (the engine behind the New External Data Processor
     * wizard). {@code baseProjectName} is optional: with it, the processor project is linked to that
     * configuration project (its runtime version and types context); without it, a standalone
     * processor project with the default runtime version. Dry-run by default.
     */
    public CreateExternalObjectResult createExternalObject(String name, String baseProjectName,
            boolean apply) {
        CreateExternalObjectResult r = new CreateExternalObjectResult();
        r.name = name;
        r.baseProject = (baseProjectName == null || baseProjectName.isBlank()) ? null : baseProjectName;

        if (name == null || name.isBlank() || !IDENT.matcher(name).matches()) {
            r.message = "name is not a valid identifier: \"" + name
                    + "\" (letters/digits/underscore; must not start with a digit, no spaces)";
            return r;
        }
        r.nameValid = true;
        IProject base = null;
        if (r.baseProject != null) {
            base = ResourcesPlugin.getWorkspace().getRoot().getProject(r.baseProject);
            if (!base.exists() || !base.isOpen()) {
                r.message = "base project not found or closed: " + r.baseProject;
                return r;
            }
        }
        r.nameAvailable = !ResourcesPlugin.getWorkspace().getRoot().getProject(name).exists();
        Version version = (base != null) ? projectVersion(base) : Version.LATEST;
        r.version = String.valueOf(version);
        r.ok = Boolean.TRUE.equals(r.nameAvailable);
        r.plan = "Create external data processor project \"" + name + "\""
                + (r.baseProject != null ? " for configuration project " + r.baseProject : " (standalone)")
                + " [" + r.version + "]";
        if (Boolean.FALSE.equals(r.nameAvailable)) {
            r.message = "a project with this name already exists in the workspace: " + name;
        }
        if (!apply) {
            return r;
        }
        if (!r.ok) {
            r.message = (r.message == null ? "validation failed" : r.message)
                    + " – apply refused (nothing created).";
            return r;
        }
        IExternalObjectProjectManager eom = ServiceAccess.get(IExternalObjectProjectManager.class);
        IModelObjectFactory factory = modelObjectFactory();
        if (eom == null || factory == null) {
            r.message = "external object project services unavailable "
                    + "(IExternalObjectProjectManager / md factory)";
            return r;
        }
        try {
            // Like create_extension: create() attaches the given root MdObject directly, so it must be
            // a FRESH, detached object (not null). Build an ExternalDataProcessor via the md factory.
            EObject freshRoot = factory.create(
                    (EClass) MdClassPackage.eINSTANCE.getEClassifier("ExternalDataProcessor"), version);
            if (!(freshRoot instanceof MdObject)) {
                r.message = "md factory did not produce an ExternalDataProcessor (got "
                        + (freshRoot == null ? "null" : freshRoot.eClass().getName()) + ")";
                return r;
            }
            ((MdObject) freshRoot).setName(name);
            IProject created = eom.create(name, version, (MdObject) freshRoot, base,
                    new NullProgressMonitor());
            r.applied = true;
            r.location = (created != null && created.getLocation() != null)
                    ? created.getLocation().toOSString() : null;
            r.message = "created external data processor project " + name
                    + " (the project model loads in background – poll edt_projects)";
        } catch (CoreException | RuntimeException ex) {
            r.applied = false;
            r.message = "create failed: " + describeCause(ex);
        }
        return r;
    }

    /** Result of {@link #dumpExternalObject}. */
    public static final class DumpExternalObjectResult {
        public boolean ok;
        public boolean applied;
        public String project;
        public String fqn;            // resolved top-object FQN (ExternalDataProcessor.X / ExternalReport.X)
        public String targetPath;
        public String validation;     // validateDumpGeneration status message (empty = OK)
        public String plan;
        public String message;
    }

    /**
     * Phase-2 write tool: dump (compile) an external data processor / external report of a project
     * into a binary {@code .epf}/{@code .erf} file via EDT's own {@link IExternalObjectDumper} –
     * the engine behind the wizard's "auto-dump" support. Requires a locally installed 1C platform
     * matching the project version ({@code validateDumpGeneration} reports that). Dry-run by default.
     */
    public DumpExternalObjectResult dumpExternalObject(String projectName, String objectName,
            String kind, String targetPath, boolean apply) {
        DumpExternalObjectResult r = new DumpExternalObjectResult();
        r.project = projectName;
        r.targetPath = targetPath;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName == null ? "" : projectName);
        if (projectName == null || !p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        String top = (kind != null && kind.trim().equalsIgnoreCase("report"))
                ? "ExternalReport" : "ExternalDataProcessor";
        final String fqn = top + "." + objectName;
        r.fqn = fqn;
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }
        final EObject[] found = {null};
        model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.dumpExternalObject.find") {
            @Override
            public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                found[0] = tx.getTopObjectByFqn(fqn);
                return null;
            }
        });
        if (found[0] == null) {
            r.message = "top object not found in the project: " + fqn;
            return r;
        }
        IExternalObjectDumpSupport support = ServiceAccess.get(IExternalObjectDumpSupport.class);
        if (support != null) {
            IStatus st = support.validateDumpGeneration(p);
            r.validation = (st == null || st.isOK()) ? "" : st.getMessage();
        }
        r.ok = (r.validation == null || r.validation.isEmpty());
        r.plan = "Dump " + fqn + " to " + targetPath;
        if (!r.ok) {
            r.message = "dump generation is not available: " + r.validation
                    + " (a local 1C platform matching the project version is required)";
        }
        if (!apply) {
            return r;
        }
        if (!r.ok) {
            r.message = (r.message == null ? "validation failed" : r.message)
                    + " – apply refused (nothing dumped).";
            return r;
        }
        IExternalObjectDumper dumper = ServiceAccess.get(IExternalObjectDumper.class);
        if (dumper == null) {
            r.message = "IExternalObjectDumper service unavailable";
            return r;
        }
        try {
            java.nio.file.Path target = java.nio.file.Path.of(targetPath);
            if (target.getParent() != null) {
                java.nio.file.Files.createDirectories(target.getParent());
            }
            dumper.dump(p, found[0], target, new NullProgressMonitor());
            r.applied = java.nio.file.Files.exists(target);
            r.message = r.applied ? ("dumped " + fqn + " to " + target)
                    : "dumper finished but the target file is missing: " + target;
        } catch (CoreException | java.io.IOException | RuntimeException ex) {
            r.applied = false;
            r.message = "dump failed: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
        }
        return r;
    }

    /** One registered infobase for {@link #listInfobases}. */
    public static final class InfobaseInfo {
        public String name;
        public String uuid;
        public String connection;
    }

    /** One project→infobase association for {@link #listInfobases}. */
    public static final class InfobaseAssociationInfo {
        public String project;
        public String infobaseName;
        public String infobaseUuid;
    }

    /** Result of {@link #listInfobases}. */
    public static final class InfobasesResult {
        public List<InfobaseInfo> infobases = new ArrayList<>();
        public List<InfobaseAssociationInfo> associations = new ArrayList<>();
        public String message;
    }

    /** Registered infobases (EDT's infobases list) + which open project is associated with which. */
    public InfobasesResult listInfobases() {
        InfobasesResult r = new InfobasesResult();
        IInfobaseManager im = ServiceAccess.get(IInfobaseManager.class);
        if (im == null) {
            r.message = "IInfobaseManager service unavailable";
            return r;
        }
        for (Section s : im.getAll()) {
            if (s instanceof InfobaseReference) {
                InfobaseReference ib = (InfobaseReference) s;
                InfobaseInfo info = new InfobaseInfo();
                info.name = ib.getName();
                info.uuid = String.valueOf(ib.getUuid());
                info.connection = String.valueOf(ib.getConnectionString());
                r.infobases.add(info);
            }
        }
        IInfobaseAssociationManager am = ServiceAccess.get(IInfobaseAssociationManager.class);
        if (am != null) {
            for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (!p.isOpen()) {
                    continue;
                }
                try {
                    am.getAssociation(p).ifPresent(assoc -> {
                        InfobaseReference ib = assoc.getDefaultInfobase();
                        if (ib != null) {
                            InfobaseAssociationInfo ai = new InfobaseAssociationInfo();
                            ai.project = p.getName();
                            ai.infobaseName = ib.getName();
                            ai.infobaseUuid = String.valueOf(ib.getUuid());
                            r.associations.add(ai);
                        }
                    });
                } catch (Exception ignored) {
                    // a project without association support – skip
                }
            }
        }
        return r;
    }

    /** Result of {@link #updateInfobase}. */
    public static final class UpdateInfobaseResult {
        public boolean ok;
        public boolean applied;
        public String project;
        public String infobaseName;
        public String infobaseUuid;
        public boolean connected;
        public String equality;       // EDT's project-vs-infobase equality state, when known
        public String status;         // the update IStatus, when applied
        public String plan;
        public String message;
    }

    /**
     * Phase-2 write tool: update an infobase's configuration FROM an EDT project (a configuration
     * or a configuration-extension project) via EDT's own {@link IInfobaseSynchronizationManager} –
     * the engine behind "Update infobase configuration". The infobase is picked by name or UUID, or
     * defaults to the project's associated one. Database-structure changes are confirmed
     * automatically; a CONFLICT (the infobase has its own changes) aborts the update – resolve it
     * interactively in EDT then. Dry-run by default.
     */
    public UpdateInfobaseResult updateInfobase(String projectName, String infobase, boolean apply) {
        UpdateInfobaseResult r = new UpdateInfobaseResult();
        r.project = projectName;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName == null ? "" : projectName);
        if (projectName == null || !p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        IInfobaseSynchronizationManager sm = ServiceAccess.get(IInfobaseSynchronizationManager.class);
        IInfobaseManager im = ServiceAccess.get(IInfobaseManager.class);
        if (sm == null || im == null) {
            r.message = "infobase services unavailable (IInfobaseSynchronizationManager / IInfobaseManager)";
            return r;
        }
        InfobaseReference ref = null;
        if (infobase != null && !infobase.isBlank()) {
            ref = im.findInfobaseByName(infobase.trim()).orElse(null);
            if (ref == null) {
                try {
                    ref = im.findInfobaseByUuid(UUID.fromString(infobase.trim())).orElse(null);
                } catch (IllegalArgumentException notAUuid) {
                    // not a UUID – name lookup already failed
                }
            }
            if (ref == null) {
                r.message = "infobase not found by name or uuid: " + infobase;
                return r;
            }
        } else {
            IInfobaseAssociationManager am = ServiceAccess.get(IInfobaseAssociationManager.class);
            try {
                ref = (am == null) ? null
                        : am.getAssociation(p).map(a -> a.getDefaultInfobase()).orElse(null);
            } catch (Exception e) {
                ref = null;
            }
            if (ref == null) {
                r.message = "the project has no associated infobase – pass the infobase name or uuid";
                return r;
            }
        }
        r.infobaseName = ref.getName();
        r.infobaseUuid = String.valueOf(ref.getUuid());
        r.connected = sm.isConnected(p, ref);
        try {
            r.equality = String.valueOf(sm.getEqualityState(p, ref));
        } catch (RuntimeException e) {
            r.equality = null;
        }
        r.ok = true;
        r.plan = "Update infobase \"" + r.infobaseName + "\" from project " + projectName
                + " (db-structure changes auto-confirmed; a conflict aborts)";
        if (!apply) {
            return r;
        }
        final InfobaseReference target = ref;
        IInfobaseUpdateCallback callback = new IInfobaseUpdateCallback() {
            @Override
            public boolean onConfirm(IProject project, InfobaseReference infobaseRef,
                    List<com._1c.g5.designer.ssh.client.operation.IDbStructureChange> changes,
                    IProgressMonitor monitor) {
                return true; // batch mode: accept database-structure changes
            }

            @Override
            public InfobaseConflictResolution resolveInfobaseChanges(IProject project,
                    InfobaseReference infobaseRef, java.util.Set<EObject> set1, java.util.Set<EObject> set2,
                    java.util.Set<String> set3, IInfobaseConfigurationChange change,
                    IInfobaseUpdateConflictResolver resolver,
                    IInfobaseUpdateConflictResolver.IConflictResolveAssist assist,
                    com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationFlow flow,
                    IProgressMonitor monitor) {
                throw new IllegalStateException(
                        "the infobase has its own configuration changes – batch update aborted; "
                        + "resolve the conflict interactively in EDT");
            }
        };
        try {
            IStatus st = sm.updateInfobase(p, target, callback, false, new NullProgressMonitor());
            r.status = (st == null) ? "null" : (st.isOK() ? "OK" : st.toString());
            r.applied = st != null && st.isOK();
            r.message = r.applied ? ("infobase \"" + r.infobaseName + "\" updated from " + projectName)
                    : ("update finished with status: " + r.status);
        } catch (RuntimeException ex) {
            r.applied = false;
            r.message = "update failed: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
        }
        return r;
    }

    // ── Platform Syntax Helper docs (the real 1C:Enterprise API reference bundled with EDT) ──

    /** One documentation page: its title (Ru + En) and how to read it (bundle + entry path). */
    public static final class HelpEntry {
        public String title;
        public String bundle;
        public String path;
        public String version;   // platform version tag of the source bundle (v8_5_1, ...)
    }

    /** Result of {@link #platformHelp}. */
    public static final class HelpResult {
        public String mode;                    // "search" | "page"
        public List<HelpEntry> hits = new ArrayList<>();  // search mode
        public String title;                   // page mode
        public String text;                    // page mode: tag-stripped content
        public String bundle;
        public String path;
        public int indexed;                    // how many pages the index holds
        public String message;
    }

    // Lazy title index over the platform Syntax Helper bundles; built once, then reused.
    private volatile List<HelpEntry> helpIndex;

    /**
     * Search the platform Syntax Helper, or read one page. The docs are the HTML reference shipped
     * inside EDT's {@code com._1c.g5.v8.dt.platform.doc_v8_*} bundles (objects, methods, properties,
     * events – the real 1C:Enterprise API, in Russian + English). {@code path} non-empty → read that
     * page as text; otherwise search titles for {@code query} (all whitespace-separated terms must
     * appear, case-insensitive; matches Ru or En names).
     */
    public HelpResult platformHelp(String query, String path, String bundle, int limit) {
        HelpResult r = new HelpResult();
        List<HelpEntry> index = ensureHelpIndex();
        r.indexed = index.size();
        if (index.isEmpty()) {
            r.message = "no platform documentation bundles found "
                    + "(com._1c.g5.v8.dt.platform.doc_v8_* not installed in this EDT)";
            return r;
        }
        if (path != null && !path.isBlank()) {
            r.mode = "page";
            String sn = (bundle != null && !bundle.isBlank()) ? bundle
                    : index.stream().filter(e -> e.path.equals(path)).map(e -> e.bundle)
                            .findFirst().orElse(null);
            if (sn == null) {
                r.message = "page not in the index; pass the exact path from a search hit "
                        + "(and its bundle)";
                return r;
            }
            String html = readBundleEntry(sn, path);
            if (html == null) {
                r.message = "could not read the page: " + sn + "!" + path;
                return r;
            }
            r.bundle = sn;
            r.path = path;
            r.title = extractTitle(html);
            r.text = htmlToText(html);
            return r;
        }
        r.mode = "search";
        if (query == null || query.isBlank()) {
            r.message = "query is required for search";
            return r;
        }
        String[] terms = query.trim().toLowerCase().split("\\s+");
        int cap = (limit > 0) ? limit : 15;
        java.util.Set<String> seenTitles = new java.util.HashSet<>();
        for (HelpEntry e : index) {
            String hay = e.title.toLowerCase();
            boolean all = true;
            for (String t : terms) {
                if (!hay.contains(t)) {
                    all = false;
                    break;
                }
            }
            // The generic and version bundles repeat the same page; show each title once.
            if (all && seenTitles.add(e.title)) {
                r.hits.add(e);
                if (r.hits.size() >= cap) {
                    break;
                }
            }
        }
        if (r.hits.isEmpty()) {
            r.message = "nothing matched \"" + query + "\" among " + index.size() + " pages";
        }
        return r;
    }

    private List<HelpEntry> ensureHelpIndex() {
        List<HelpEntry> local = helpIndex;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (helpIndex != null) {
                return helpIndex;
            }
            List<HelpEntry> built = new ArrayList<>();
            BundleContext ctx = null;
            try {
                Bundle self = FrameworkUtil.getBundle(EdtModelGateway.class);
                ctx = (self == null) ? null : self.getBundleContext();
            } catch (RuntimeException ignored) {
                // no framework context – leave the index empty
            }
            if (ctx != null) {
                String base = "com._1c.g5.v8.dt.platform.doc";
                for (Bundle b : ctx.getBundles()) {
                    String sn = b.getSymbolicName();
                    // The generic bundle (base name) holds the full Syntax Helper; the version bundles
                    // (base_v8_5_1, base_v8_3_27, ...) add version-specific pages. Index them all.
                    if (sn == null || !(sn.equals(base) || sn.startsWith(base + "_v8_"))) {
                        continue;
                    }
                    String version = sn.equals(base) ? "generic" : sn.substring(base.length() + 1);
                    java.util.Enumeration<java.net.URL> entries =
                            b.findEntries("nl/ru/html", "*.html", true);
                    if (entries == null) {
                        continue;
                    }
                    while (entries.hasMoreElements()) {
                        java.net.URL url = entries.nextElement();
                        String p = url.getPath();
                        String title = readTitle(url);
                        if (title == null || title.isBlank()) {
                            continue;
                        }
                        HelpEntry e = new HelpEntry();
                        e.title = title;
                        e.bundle = sn;
                        e.path = p.startsWith("/") ? p.substring(1) : p;
                        e.version = version;
                        built.add(e);
                    }
                }
            }
            helpIndex = built;
            return built;
        }
    }

    /** Read only enough of an entry to extract its {@code <title>} (the index is title-only). */
    private static String readTitle(java.net.URL url) {
        try (java.io.InputStream in = url.openStream()) {
            byte[] buf = new byte[2048];
            int n = in.read(buf);
            if (n <= 0) {
                return null;
            }
            return extractTitle(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private String readBundleEntry(String symbolicName, String path) {
        Bundle b = Platform.getBundle(symbolicName);
        if (b == null) {
            return null;
        }
        java.net.URL url = b.getEntry(path);
        if (url == null) {
            return null;
        }
        try (java.io.InputStream in = url.openStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static final Pattern TITLE_RE =
            Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static String extractTitle(String html) {
        java.util.regex.Matcher m = TITLE_RE.matcher(html);
        return m.find() ? m.group(1).replaceAll("\\s+", " ").trim() : null;
    }

    /** HTML → readable text: drop script/style, tags to spaces, unescape the few common entities. */
    private static String htmlToText(String html) {
        String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>", "\n");
        s = s.replaceAll("<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        s = s.replaceAll("[ \\t]+", " ").replaceAll("\\s*\\n\\s*", "\n").replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    /** Exception summary with its cause chain – EDT wraps the real error (e.g. a lifecycle
     *  RESOURCE_LOADING failure) several layers deep, so the top message alone is uninformative. */
    private static String describeCause(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable t = ex;
        int depth = 0;
        while (t != null && depth < 6) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getSimpleName());
            if (t.getMessage() != null) {
                sb.append(": ").append(t.getMessage());
            }
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
            depth++;
        }
        return sb.toString();
    }

    /** Purpose name (EN or RU, case-insensitive) → enum; blank → CUSTOMIZATION; unknown → null. */
    private static ConfigurationExtensionPurpose resolvePurpose(String s) {
        if (s == null || s.isBlank()) {
            return ConfigurationExtensionPurpose.CUSTOMIZATION;
        }
        switch (s.trim().toLowerCase()) {
            case "customization":
            case "адаптация":
                return ConfigurationExtensionPurpose.CUSTOMIZATION;
            case "addon":
            case "add_on":
            case "add-on":
            case "дополнение":
                return ConfigurationExtensionPurpose.ADD_ON;
            case "patch":
            case "исправление":
                return ConfigurationExtensionPurpose.PATCH;
            default:
                return null;
        }
    }

    /** The one-or-more parts of a parsed type: a composite's parts, or the single type itself. */
    private static List<ParsedType> partsOf(ParsedType pt) {
        return (pt.composite != null) ? pt.composite : List.of(pt);
    }

    /**
     * Build a {@link TypeDescription} for a parsed type (single or composite). Each part is added as a
     * {@link TypeItem}: a platform-type proxy from {@link IEObjectProvider} (+ qualifiers), or the
     * target object's produced type via {@link MdProducedTypesUtil} (serializes as CatalogRef.X /
     * DefinedType.X / ...). Runs inside a BM transaction (it resolves reference targets by FQN).
     */
    private TypeDescription buildTypeDescription(IBmTransaction tx, ParsedType pt, Version version) {
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        for (ParsedType part : partsOf(pt)) {
            addTypeItem(tx, part, version, td);
        }
        return td;
    }

    /** Add one type (ref/defined or platform primitive + its qualifier) to {@code td}. */
    private void addTypeItem(IBmTransaction tx, ParsedType part, Version version, TypeDescription td) {
        if (part.refFqn != null) {
            EObject target = tx.getTopObjectByFqn(part.refFqn);
            if (!(target instanceof MdObject)) {
                throw new IllegalStateException("reference target not resolvable: " + part.refFqn);
            }
            TypeItem ref = MdProducedTypesUtil.getProducedType((MdObject) target, part.producedTypeEClass);
            if (ref == null) {
                throw new IllegalStateException("no produced type for: " + part.refFqn);
            }
            td.getTypes().add(ref);
            return;
        }
        // Platform types are served by the version-specific TypeItemProvider, registered for eClass
        // TYPE_ITEM (NOT TYPE – that registry slot is empty), e.g. ...platform.type.v8_5_1.TypeItemProvider.
        IEObjectProvider provider = IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version);
        if (provider == null) {
            throw new IllegalStateException("no platform type provider (TYPE_ITEM) for version " + version);
        }
        // The provider's index keys are NOT the bare serialized token (createProxy("String") throws
        // "unknown name"), so discover the description whose qualified name ends with the wanted token
        // and proxy THAT exact name.
        String want = part.platformCode;
        String matched = null;
        List<String> sample = new ArrayList<>();
        for (IEObjectDescription d : provider.getEObjectDescriptions(input -> true)) {
            QualifiedName qn = d.getName();
            if (qn == null) {
                continue;
            }
            String last = qn.getLastSegment();
            if (last != null && last.equalsIgnoreCase(want)) {
                matched = qn.toString();
                break;
            }
            if (sample.size() < 40) {
                sample.add(qn.toString());
            }
        }
        if (matched == null) {
            throw new IllegalStateException("platform type '" + want + "' not found among "
                    + provider.getClass().getSimpleName() + " names; sample=" + sample);
        }
        Type primitive = provider.createProxy(matched);
        if (primitive == null) {
            throw new IllegalStateException("createProxy returned null for '" + matched + "'");
        }
        td.getTypes().add(primitive);
        switch (part.kind) {
            case STRING:
                if (part.length > 0) {
                    StringQualifiers sq = McoreFactory.eINSTANCE.createStringQualifiers();
                    sq.setLength(part.length);
                    td.setStringQualifiers(sq);
                }
                break;
            case NUMBER:
                if (part.precision > 0) {
                    NumberQualifiers nq = McoreFactory.eINSTANCE.createNumberQualifiers();
                    nq.setPrecision(part.precision);
                    nq.setScale(part.scale);
                    td.setNumberQualifiers(nq);
                }
                break;
            case DATE:
                DateQualifiers dq = McoreFactory.eINSTANCE.createDateQualifiers();
                dq.setDateFractions(part.dateFractions);
                td.setDateQualifiers(dq);
                break;
            default:
                break;
        }
    }

    /** Attribute type kind (drives qualifier construction in {@link #buildTypeDescription}). */
    private enum TypeKind { STRING, NUMBER, BOOLEAN, DATE, VALUESTORAGE, REF }

    /**
     * Parsed attribute type: validity, normalized display, the structured data needed to build a
     * {@link TypeDescription}, and (for reference types) the target owner FQN.
     */
    private static final class ParsedType {
        boolean valid;
        String display;
        TypeKind kind;
        String platformCode;          // platform type name for the provider: String/Number/Boolean/Date/ValueStorage
        int length;                   // string length (0 = unbounded)
        int precision;                // number precision
        int scale;                    // number scale
        DateFractions dateFractions;  // for DATE: Date/DateTime/Time
        String refFqn;                // non-null for ref/defined types (Catalog.X / ... / DefinedType.X)
        EClass producedTypeEClass;    // which produced type to fetch: MD_REF_TYPE (refs) or MD_USER_DEFINED_TYPE
        List<ParsedType> composite;   // non-null for a composite type: its individual parts (other fields unused)
    }

    /** Legal 1C identifier: a Unicode letter or underscore, then letters/digits/underscores (no spaces). */
    private static final Pattern IDENT = Pattern.compile("^[\\p{L}_][\\p{L}\\p{N}_]*$");
    private static final Pattern STRING_Q = Pattern.compile("(?i)^Строка\\s*\\(\\s*(\\d+)\\s*\\)$");
    private static final Pattern NUMBER_Q = Pattern.compile("(?i)^Число\\s*\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)$");
    /** Reference type prefix (Russian, lower-cased) → EDT metadata-class FQN prefix. All produce MD_REF_TYPE. */
    private static final Map<String, String> REF_KIND_TO_MDCLASS = Map.of(
            "справочник", "Catalog",
            "документ", "Document",
            "перечисление", "Enum",
            "планвидовхарактеристик", "ChartOfCharacteristicTypes",
            "плансчетов", "ChartOfAccounts",
            "планвидоврасчета", "ChartOfCalculationTypes",
            "бизнеспроцесс", "BusinessProcess",
            "задача", "Task",
            "планобмена", "ExchangePlan");
    private static final Pattern REF_T = Pattern.compile("(?i)^(Справочник|Документ|Перечисление|"
            + "ПланВидовХарактеристик|ПланСчетов|ПланВидовРасчета|БизнесПроцесс|Задача|ПланОбмена)Ссылка\\.(.+)$");
    private static final Pattern DEFINED_T = Pattern.compile("(?i)^ОпределяемыйТип\\.(.+)$");

    /**
     * Parse a type string (single or composite). A composite is comma-separated at the top level
     * (commas inside parentheses, e.g. Число(10,2), are not separators); every part must be valid.
     */
    private ParsedType parseType(String spec) {
        if (spec == null || spec.isBlank()) {
            return new ParsedType();
        }
        String s = spec.trim();
        List<String> tops = splitTopLevel(s);
        if (tops.size() <= 1) {
            return parseSingleType(s);
        }
        ParsedType t = new ParsedType();
        List<ParsedType> parts = new ArrayList<>();
        List<String> displays = new ArrayList<>();
        for (String p : tops) {
            ParsedType part = parseSingleType(p.trim());
            if (!part.valid) {
                return t; // any invalid part → the whole composite is invalid
            }
            parts.add(part);
            displays.add(part.display);
        }
        t.valid = true;
        t.composite = parts;
        t.display = String.join(", ", displays);
        return t;
    }

    /** Split on top-level commas; commas inside parentheses (Число(10,2)) stay within their part. */
    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
            } else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    /** Parse ONE type string into a validated plan + the data to build a TypeDescription. */
    private ParsedType parseSingleType(String spec) {
        ParsedType t = new ParsedType();
        if (spec == null || spec.isBlank()) {
            return t;
        }
        String s = spec.trim();
        Matcher m;
        if ((m = STRING_Q.matcher(s)).matches()) {
            t.valid = true;
            t.kind = TypeKind.STRING;
            t.platformCode = "String";
            t.length = Integer.parseInt(m.group(1));
            t.display = "Строка(" + t.length + ")";
            return t;
        }
        if (s.equalsIgnoreCase("Строка")) {
            t.valid = true;
            t.kind = TypeKind.STRING;
            t.platformCode = "String";
            t.length = 0; // unbounded
            t.display = "Строка (неограниченная)";
            return t;
        }
        if ((m = NUMBER_Q.matcher(s)).matches()) {
            t.valid = true;
            t.kind = TypeKind.NUMBER;
            t.platformCode = "Number";
            t.precision = Integer.parseInt(m.group(1));
            t.scale = (m.group(2) != null) ? Integer.parseInt(m.group(2)) : 0;
            t.display = "Число(" + t.precision + (t.scale > 0 ? ", " + t.scale : "") + ")";
            return t;
        }
        if (s.equalsIgnoreCase("Число")) {
            t.valid = true;
            t.kind = TypeKind.NUMBER;
            t.platformCode = "Number";
            t.display = "Число";
            return t;
        }
        if (s.equalsIgnoreCase("Булево")) {
            t.valid = true;
            t.kind = TypeKind.BOOLEAN;
            t.platformCode = "Boolean";
            t.display = "Булево";
            return t;
        }
        if (s.equalsIgnoreCase("Дата") || s.equalsIgnoreCase("ДатаВремя") || s.equalsIgnoreCase("Время")) {
            t.valid = true;
            t.kind = TypeKind.DATE;
            t.platformCode = "Date";
            t.dateFractions = s.equalsIgnoreCase("Дата") ? DateFractions.DATE
                    : s.equalsIgnoreCase("Время") ? DateFractions.TIME : DateFractions.DATE_TIME;
            t.display = s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
            return t;
        }
        if (s.equalsIgnoreCase("ХранилищеЗначения")) {
            t.valid = true;
            t.kind = TypeKind.VALUESTORAGE;
            t.platformCode = "ValueStorage";
            t.display = "ХранилищеЗначения";
            return t;
        }
        if ((m = REF_T.matcher(s)).matches()) {
            String kind = m.group(1).toLowerCase();
            String objName = m.group(2).trim();
            String typePrefix = REF_KIND_TO_MDCLASS.get(kind);
            if (typePrefix != null) {
                t.valid = true;
                t.kind = TypeKind.REF;
                t.display = m.group(1) + "Ссылка." + objName;
                t.refFqn = typePrefix + "." + objName;
                t.producedTypeEClass = MdTypePackage.Literals.MD_REF_TYPE;
            }
            return t;
        }
        if ((m = DEFINED_T.matcher(s)).matches()) {
            String objName = m.group(1).trim();
            t.valid = true;
            t.kind = TypeKind.REF;
            t.display = "ОпределяемыйТип." + objName;
            t.refFqn = "DefinedType." + objName;
            t.producedTypeEClass = MdTypePackage.Literals.MD_USER_DEFINED_TYPE;
            return t;
        }
        return t; // invalid
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

    // ---- Phase 3 debugger – attach a live debug session ---------------------------------

    /** A live debug session: the Eclipse launch + the connected 1C runtime debug target. */
    public static final class DebugSession {
        public final String sessionId;
        public final ILaunch launch;
        public final IRuntimeDebugClientTarget target;
        DebugSession(String id, ILaunch launch, IRuntimeDebugClientTarget target) {
            this.sessionId = id;
            this.launch = launch;
            this.target = target;
        }
    }

    /** Active debug sessions by id, so the control/inspect/evaluate tools can find the target later. */
    private static final Map<String, DebugSession> DEBUG_SESSIONS = new ConcurrentHashMap<>();

    /** Look up a live debug session by id (for the follow-up debug tools). */
    public static DebugSession debugSession(String id) {
        return DEBUG_SESSIONS.get(id);
    }

    /** Result of {@link #attachDebug}. */
    public static final class AttachResult {
        public boolean ok;
        public String sessionId;
        public String projectName;
        public String serverUrl;
        public int serverPort;
        public boolean launched;
        public boolean connected;
        public String debugServerUrl;   // as reported by the connected target
        public int targetCount;
        public String message;
        public String warning;
    }

    /**
     *: attach a debug session to a RUNNING infobase's debug server (dbgs). Builds a
     * {@code REMOTE_RUNTIME} Eclipse launch configuration ({@link IDebugConfigurationAttributes}),
     * launches it in {@code DEBUG_MODE} (EDT's {@code RemoteRuntimeDebugLaunchDelegate} performs the
     * network connect), and records the resulting {@link IRuntimeDebugClientTarget} under a session id for
     * the control/inspect/evaluate tools. Token-gated (opens a session against a live IB). STAND-ONLY:
     * target the {@code tests} stand, never production.
     *
     * <p>Written against the reverse-engineered debug API; the feasibility spike ({@link #debugProbe})
     * proved the infra loads headless – this is the live network half, verified on the stand.
     */
    public AttachResult attachDebug(String projectName, String serverUrl, int serverPort,
            String infobaseAlias, String infobaseUuid) {
        AttachResult r = new AttachResult();
        r.projectName = projectName;
        r.serverUrl = serverUrl;
        r.serverPort = serverPort;
        if (serverUrl == null || serverUrl.isBlank()) {
            r.message = "serverUrl is required (the running IB's debug server / dbgs host)";
            return r;
        }
        DebugPlugin dp = DebugPlugin.getDefault();
        if (dp == null || dp.getLaunchManager() == null) {
            r.message = "Eclipse debug infrastructure unavailable in this runtime";
            return r;
        }
        ILaunchManager lm = dp.getLaunchManager();
        ILaunchConfigurationType type = lm.getLaunchConfigurationType(IDebugConfigurationTypes.REMOTE_RUNTIME);
        if (type == null) {
            r.message = "REMOTE_RUNTIME launch configuration type not registered";
            return r;
        }
        boolean haveAlias = infobaseAlias != null && !infobaseAlias.isBlank();
        boolean haveUuid = infobaseUuid != null && !infobaseUuid.isBlank();
        if (!haveAlias && !haveUuid) {
            r.message = "infobaseAlias (or infobaseUuid) is required – a debug server hosts many infobases; "
                    + "specify which one (the infobase's debug-server alias).";
            return r;
        }
        IRuntimeDebugClientTargetManager mgr = ServiceAccess.get(IRuntimeDebugClientTargetManager.class);
        IDebugMonitoringManager mon = ServiceAccess.get(IDebugMonitoringManager.class);
        if (mgr == null || mon == null) {
            r.message = "debug services unavailable (targetManager=" + (mgr != null)
                    + ", monitoringManager=" + (mon != null) + ")";
            return r;
        }
        // Compose a full debug-server URL (the manager parses it with new URL(...); the port lives in it).
        String url = serverUrl.contains("://") ? serverUrl : "http://" + serverUrl;
        if (serverPort > 0 && !url.matches(".*:\\d+(/.*)?$")) {
            url = url + ":" + serverPort;
        }
        r.serverUrl = url;
        DtLaunch launch = null;
        try {
            String name = lm.generateLaunchConfigurationName(
                    "edt-bridge-debug-" + (projectName == null || projectName.isBlank() ? "ib" : projectName));
            ILaunchConfigurationWorkingCopy wc = type.newInstance(null, name);
            if (projectName != null && !projectName.isBlank()) {
                wc.setAttribute(IDebugConfigurationAttributes.PROJECT_NAME, projectName);
            }
            wc.setAttribute(IDebugConfigurationAttributes.DEBUG_SERVER_URL, url);
            wc.setAttribute(IDebugConfigurationAttributes.DEBUG_SERVER_LOCATION_TYPE,
                    DebugServerLocationType.PROVIDED.name());
            if (haveUuid) {
                wc.setAttribute(IDebugConfigurationAttributes.INFOBASE_UUID, infobaseUuid);
            }
            if (haveAlias) {
                wc.setAttribute(IDebugConfigurationAttributes.DEBUG_INFOBASE_ALIAS, infobaseAlias);
            }
            // Attach EXACTLY as EDT's RemoteRuntimeDebugLaunchDelegate does, but WITHOUT
            // ILaunchConfiguration.launch(): that path runs preLaunchCheck/saveBeforeLaunch which load
            // org.eclipse.debug.ui (Prompter/ISuspendTrigger) – absent headless, so the launch aborts.
            // Instead build a DtLaunch directly and ask the target manager to create the remote target.
            launch = new DtLaunch(wc, ILaunchManager.DEBUG_MODE, "RemoteDebugServer", mon);
            lm.addLaunch(launch);
            String alias = haveAlias ? infobaseAlias : infobaseUuid;
            // getOrCreateRemote is idempotent (reuses an existing local target); but on a conflict it
            // returns null silently. Fall back to createRemote so the underlying server reason surfaces
            // (e.g. it throws "already in debug process with the requested infobase").
            IRuntimeDebugClientTarget rt = mgr.getOrCreateRemote(url, alias, launch);
            if (rt == null) {
                rt = mgr.createRemote(url, alias, launch);
            }
            if (rt != null) {
                launch.addDebugTarget(rt);
                // Auto-attach to running debug items so threads appear (background jobs, server calls,
                // clients). Without this the connection is up but has no threads to inspect/suspend.
                try {
                    // All desktop debug-item types (mobile excluded). Crucially includes the FILE_MODE
                    // variants (JOB_FILE_MODE, WEB_SOCKET_FILE_MODE) and plain CLIENT – a thin client
                    // against a FILE infobase registers through those, so a narrower list sees no threads.
                    rt.setAutoconnectDebugTargets(java.util.List.of(
                            DebugTargetType.CLIENT, DebugTargetType.MANAGED_CLIENT, DebugTargetType.WEB_CLIENT,
                            DebugTargetType.SERVER, DebugTargetType.SERVER_EMULATION,
                            DebugTargetType.JOB, DebugTargetType.JOB_FILE_MODE,
                            DebugTargetType.WEB_SOCKET, DebugTargetType.WEB_SOCKET_FILE_MODE,
                            DebugTargetType.HTTP_SERVICE, DebugTargetType.WEB_SERVICE,
                            DebugTargetType.ODATA, DebugTargetType.COM_CONNECTOR));
                } catch (Exception autoEx) {
                    r.warning = "autoconnect targets not set (" + autoEx.getClass().getSimpleName()
                            + ") – threads may not attach; ";
                }
            }
            r.launched = true;
            // createRemote may connect asynchronously; poll briefly until the target reports its server URL.
            if (rt != null) {
                long deadline = System.currentTimeMillis() + 15_000L;
                while (!rt.isTerminated() && System.currentTimeMillis() < deadline) {
                    try {
                        if (rt.getDebugServerUrl() != null) {
                            break;
                        }
                    } catch (RuntimeException ignored) {
                        // not ready yet
                    }
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            // Subscribe to the infobase's data areas. WITHOUT this the target is attached but sees NO
            // threads: a running client executes inside a data area the debugger isn't scoped to, so
            // getThreads() stays empty. For a plain (non-multitenant) infobase this is the single
            // default area; subscribe to every area the server reports.
            String areaNote = "";
            if (rt != null) {
                try {
                    java.util.List<DebugAreaInfo> areas = rt.getDebugAreas();
                    if (areas != null && !areas.isEmpty()) {
                        rt.setDebugAreas(areas);
                        areaNote = "subscribed to " + areas.size() + " data area(s)";
                    } else {
                        areaNote = "no data areas reported by the server";
                    }
                } catch (Exception areaEx) {
                    areaNote = "setDebugAreas failed (" + areaEx.getClass().getSimpleName() + ") – threads may stay hidden";
                }
            }
            r.targetCount = launch.getDebugTargets().length;
            if (rt == null) {
                detachLaunch(lm, launch);
                r.message = "createRemote returned no target – check the infobase alias and that the "
                        + "infobase is running with debugging enabled.";
                return r;
            }
            // IRuntimeDebugClientTarget does not expose isConnected(); a live, non-terminated Eclipse
            // debug target is our "connected" proxy (ITerminate.isTerminated from IDebugTarget).
            r.connected = !rt.isTerminated();
            try {
                r.debugServerUrl = rt.getDebugServerUrl();
            } catch (RuntimeException ignored) {
                // best-effort
            }
            String sid = "dbg-" + UUID.randomUUID().toString().substring(0, 8);
            DEBUG_SESSIONS.put(sid, new DebugSession(sid, launch, rt));
            r.sessionId = sid;
            r.ok = true;
            r.message = "attached debug session " + sid + " to " + url + " (infobase " + alias + ")"
                    + (r.connected ? " – connected" : " – created, not yet connected");
            r.warning = (r.warning == null ? "" : r.warning)
                    + "ОТЛАДКА ЖИВОЙ ИБ – только тестовый стенд, никогда продакшен. Области данных: "
                    + areaNote + ".";
        } catch (Exception ex) {
            if (launch != null) {
                detachLaunch(lm, launch);
            }
            r.message = "attach failed: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
        }
        return r;
    }

    /** Terminate + remove a launch, best-effort (shared by attach failure paths and detach). */
    private void detachLaunch(ILaunchManager lm, ILaunch launch) {
        try {
            if (!launch.isTerminated()) {
                launch.terminate();
            }
        } catch (Exception ignored) {
            // best-effort
        }
        try {
            lm.removeLaunch(launch);
        } catch (Exception ignored) {
            // best-effort
        }
    }

    /** Detach a debug session: terminate its launch and forget it. */
    public boolean detachDebug(String sessionId) {
        DebugSession s = (sessionId == null) ? null : DEBUG_SESSIONS.remove(sessionId);
        if (s == null) {
            return false;
        }
        try {
            if (s.launch != null && !s.launch.isTerminated()) {
                s.launch.terminate();
            }
            DebugPlugin dp = DebugPlugin.getDefault();
            if (dp != null && s.launch != null) {
                dp.getLaunchManager().removeLaunch(s.launch);
            }
        } catch (Exception ignored) {
            // best-effort
        }
        return true;
    }

    // ---- Phase 3 debugger – inspect (threads / stack frames / variables) ----------------

    /** A variable in a stack frame: name + rendered value. */
    public static final class DbgVar {
        public String name;
        public String value;
        public String type;
    }

    /** A stack frame of a suspended thread. */
    public static final class DbgFrame {
        public int level;
        public int line;
        public String signature;
        public String source;
        public List<DbgVar> variables = new ArrayList<>();
    }

    /** A debug thread (a running/suspended debug item: background job, server call, client, ...). */
    public static final class DbgThread {
        public String name;
        public String type;        // DebugTargetType
        public boolean suspended;
        public List<DbgFrame> frames = new ArrayList<>(); // populated only when suspended
    }

    /** Result of {@link #inspectDebug}. */
    public static final class InspectResult {
        public boolean ok;
        public String sessionId;
        public boolean targetTerminated;
        public int threadCount;
        public List<DbgThread> threads = new ArrayList<>();
        public String message;
    }

    /** Max stack frames / variables returned per thread (keep responses bounded). */
    private static final int DBG_FRAME_CAP = 50;
    private static final int DBG_VAR_CAP = 100;

    /**
     *: inspect a live debug session – list its threads (debug items) and, for suspended ones,
     * their BSL stack frames + the top frame's variables. Read-only. The session must have been opened by
     * {@link #attachDebug}. Frames/variables are only present for SUSPENDED threads (suspend via
     * {@link #controlDebug} or a breakpoint hit).
     */
    public InspectResult inspectDebug(String sessionId) {
        InspectResult r = new InspectResult();
        r.sessionId = sessionId;
        DebugSession s = (sessionId == null) ? null : DEBUG_SESSIONS.get(sessionId);
        if (s == null) {
            r.message = "no such debug session: " + sessionId + " (attach first)";
            return r;
        }
        IRuntimeDebugClientTarget t = s.target;
        try {
            r.targetTerminated = t.isTerminated();
            IRuntimeDebugTargetThread[] threads = t.getThreads();
            r.threadCount = threads == null ? 0 : threads.length;
            if (threads != null) {
                for (IRuntimeDebugTargetThread th : threads) {
                    DbgThread dt = new DbgThread();
                    try {
                        dt.name = th.getName();
                    } catch (Exception e) {
                        dt.name = "(name unavailable)";
                    }
                    try {
                        dt.type = th.getType() != null ? th.getType().getName() : null;
                    } catch (Exception ignored) {
                        // best-effort
                    }
                    dt.suspended = th.isSuspended();
                    if (dt.suspended) {
                        collectFrames(th, dt);
                    }
                    r.threads.add(dt);
                }
            }
            r.ok = true;
            r.message = "session " + sessionId + ": " + r.threadCount + " thread(s)"
                    + (r.targetTerminated ? " (target terminated)" : "");
        } catch (Exception ex) {
            r.message = "inspect failed: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
        }
        return r;
    }

    /** Collect a suspended thread's stack frames + the top frame's variables into {@code dt}. */
    private void collectFrames(IRuntimeDebugTargetThread th, DbgThread dt) {
        try {
            IBslStackFrame[] frames = th.getStackFrames();
            if (frames == null) {
                return;
            }
            for (int i = 0; i < frames.length && i < DBG_FRAME_CAP; i++) {
                IBslStackFrame f = frames[i];
                DbgFrame df = new DbgFrame();
                try {
                    df.level = f.getLevel();
                    df.line = f.getLineNumber();
                    df.signature = f.getSignature();
                    df.source = f.getSource() != null ? f.getSource().toString() : null;
                } catch (Exception ignored) {
                    // best-effort per-frame
                }
                if (i == 0) {
                    collectVars(f, df);
                }
                dt.frames.add(df);
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    /** Collect a frame's local variables (name + rendered value) into {@code df}. */
    private void collectVars(IBslStackFrame f, DbgFrame df) {
        try {
            IBslVariable[] vars = f.getVariables();
            if (vars == null) {
                return;
            }
            for (int j = 0; j < vars.length && j < DBG_VAR_CAP; j++) {
                IBslVariable v = vars[j];
                DbgVar dv = new DbgVar();
                try {
                    dv.name = v.getName();
                    IBslValue val = v.getValue();
                    if (val != null) {
                        dv.value = val.getValueString();
                        dv.type = val.getValueTypeName();
                    }
                } catch (Exception e) {
                    if (dv.value == null) {
                        dv.value = "(unavailable)";
                    }
                }
                df.variables.add(dv);
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    // ---- Phase 3 debugger – control (suspend / resume / step) ---------------------------

    /** Result of {@link #controlDebug}. */
    public static final class ControlResult {
        public boolean ok;
        public String sessionId;
        public String action;
        public String message;
    }

    /**
     *: control execution of a live debug session: {@code suspend}/{@code resume} the whole
     * target, or {@code stepOver}/{@code stepInto}/{@code stepReturn} a suspended thread (the first
     * suspended one, or {@code threadName} if given). Token-gated (changes runtime execution).
     */
    public ControlResult controlDebug(String sessionId, String action, String threadName) {
        ControlResult r = new ControlResult();
        r.sessionId = sessionId;
        r.action = action;
        DebugSession s = (sessionId == null) ? null : DEBUG_SESSIONS.get(sessionId);
        if (s == null) {
            r.message = "no such debug session: " + sessionId + " (attach first)";
            return r;
        }
        if (action == null) {
            r.message = "action is required (suspend|resume|stepOver|stepInto|stepReturn)";
            return r;
        }
        IRuntimeDebugClientTarget t = s.target;
        try {
            switch (action) {
                case "suspend":
                    if (t.canSuspend()) {
                        t.suspend();
                    }
                    r.ok = true;
                    r.message = "suspend requested";
                    break;
                case "resume":
                    if (t.canResume()) {
                        t.resume();
                    }
                    r.ok = true;
                    r.message = "resume requested";
                    break;
                case "stepOver":
                case "stepInto":
                case "stepReturn": {
                    IThread th = pickThread(t, threadName);
                    if (th == null) {
                        r.message = "no suspended thread to step (suspend first, or pass threadName)";
                        return r;
                    }
                    if ("stepOver".equals(action) && th.canStepOver()) {
                        th.stepOver();
                    } else if ("stepInto".equals(action) && th.canStepInto()) {
                        th.stepInto();
                    } else if ("stepReturn".equals(action) && th.canStepReturn()) {
                        th.stepReturn();
                    } else {
                        r.message = action + " not available on the selected thread";
                        return r;
                    }
                    r.ok = true;
                    r.message = action + " requested on thread " + safeThreadName(th);
                    break;
                }
                default:
                    r.message = "unknown action: " + action
                            + " (suspend|resume|stepOver|stepInto|stepReturn)";
            }
        } catch (Exception ex) {
            r.message = action + " failed: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
        }
        return r;
    }

    /** Pick a suspended thread to step: by name if given, else the first suspended one. */
    private IThread pickThread(IRuntimeDebugClientTarget t, String threadName) {
        try {
            IRuntimeDebugTargetThread[] threads = t.getThreads();
            if (threads == null) {
                return null;
            }
            for (IRuntimeDebugTargetThread th : threads) {
                if (!th.isSuspended()) {
                    continue;
                }
                if (threadName == null || threadName.isBlank() || threadName.equals(safeThreadName(th))) {
                    return th;
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
        return null;
    }

    private String safeThreadName(IThread th) {
        try {
            return th.getName();
        } catch (Exception e) {
            return "(thread)";
        }
    }

    // ---- Phase 3 debugger – evaluate (arbitrary BSL expression) --------------------------

    /** Result of {@link #evaluateDebug}. */
    public static final class EvaluateResult {
        public boolean ok;
        public String sessionId;
        public String expression;
        public boolean success;
        public String value;
        public String type;
        public String error;
        public String frame;     // signature of the frame the expression ran in
        public String message;
    }

    /**
     * flagship (HEAVIEST gate): evaluate an arbitrary BSL {@code expression} in a suspended
     * frame of a live debug session and return the result. This is <b>arbitrary code execution against a
     * live infobase</b>, so it is gated three ways: (1) a configured token (the tool is a write tool),
     * (2) the per-call {@code allowCodeExecution=true} opt-in, and (3) the server-side switch
     * {@code EDT_BRIDGE_ALLOW_EVALUATE} (env) – all required; off by default. STAND-ONLY.
     *
     * <p>Runs via {@link IEvaluationEngine#evaluateExpression} (async; we wait on a latch for the
     * {@link IEvaluationListener} callback) on the chosen suspended thread's frame.
     */
    public EvaluateResult evaluateDebug(String sessionId, String expression, String threadName,
            int frameLevel, boolean allowCodeExecution) {
        EvaluateResult r = new EvaluateResult();
        r.sessionId = sessionId;
        r.expression = expression;
        // Gate 1: per-call opt-in.
        if (!allowCodeExecution) {
            r.message = "refused: edt_evaluate runs ARBITRARY BSL against a live infobase – pass "
                    + "allowCodeExecution=true to confirm .";
            return r;
        }
        // Gate 2: server-side switch (off by default even with a token).
        String sw = System.getenv("EDT_BRIDGE_ALLOW_EVALUATE");
        boolean enabled = sw != null && (sw.equals("1") || sw.equalsIgnoreCase("true") || sw.equalsIgnoreCase("yes"));
        if (!enabled) {
            r.message = "refused: server-side evaluate is disabled – set EDT_BRIDGE_ALLOW_EVALUATE=1 to "
                    + "enable code execution .";
            return r;
        }
        if (expression == null || expression.isBlank()) {
            r.message = "expression is required";
            return r;
        }
        DebugSession s = (sessionId == null) ? null : DEBUG_SESSIONS.get(sessionId);
        if (s == null) {
            r.message = "no such debug session: " + sessionId + " (attach first)";
            return r;
        }
        try {
            IBslStackFrame frame = suspendedFrame(s.target, threadName, frameLevel);
            if (frame == null) {
                r.message = "no suspended frame to evaluate in – suspend a thread first (edt_debug_control "
                        + "suspend) or hit a breakpoint.";
                return r;
            }
            r.frame = frame.getSignature();
            IEvaluationEngine engine = s.target.getEvaluationEngine();
            if (engine == null) {
                r.message = "evaluation engine unavailable on the target";
                return r;
            }
            final IEvaluationResult[] holder = {null};
            final CountDownLatch latch = new CountDownLatch(1);
            IEvaluationRequest req = EvaluationRequest.builder(new BslValuePath(expression))
                    .setStackFrame(frame)
                    .setExpressionUuid(UUID.randomUUID()) // required by build() (checkArgument non-null)
                    .setInterfaces(java.util.List.of(ViewInterface.CONTEXT, ViewInterface.ENUM,
                            ViewInterface.COLLECTION)) // non-null required (engine reads .size())
                    .setMaxTestSize(4096)
                    .setMultiLine(false)
                    .setEvaluationListener(result -> {
                        holder[0] = result;
                        latch.countDown();
                    })
                    .build();
            engine.evaluateExpression(req);
            boolean done = latch.await(20, TimeUnit.SECONDS);
            if (!done || holder[0] == null) {
                r.message = "evaluation timed out (no result within 20s)";
                return r;
            }
            IEvaluationResult res = holder[0];
            r.success = res.isSuccess();
            if (!r.success) {
                r.error = res.getErrorMessage();
                r.message = "evaluation returned an error";
            } else {
                renderCalculation(res.getResult(), r);
                r.message = "evaluated";
            }
            r.ok = true;
        } catch (Exception ex) {
            r.message = "evaluate failed: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
        }
        return r;
    }

    /** The suspended frame to evaluate in: thread by name (or first suspended), frame at {@code level}. */
    private IBslStackFrame suspendedFrame(IRuntimeDebugClientTarget t, String threadName, int level) {
        try {
            IRuntimeDebugTargetThread[] threads = t.getThreads();
            if (threads == null) {
                return null;
            }
            for (IRuntimeDebugTargetThread th : threads) {
                if (!th.isSuspended()) {
                    continue;
                }
                if (threadName != null && !threadName.isBlank() && !threadName.equals(safeThreadName(th))) {
                    continue;
                }
                IBslStackFrame[] frames = th.getStackFrames();
                if (frames != null && frames.length > 0) {
                    int idx = (level >= 0 && level < frames.length) ? level : 0;
                    return frames[idx];
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
        return null;
    }

    /** Render a {@link CalculationResultBaseData} (typed value info) into the result's value+type. */
    private void renderCalculation(CalculationResultBaseData d, EvaluateResult r) {
        if (d == null) {
            r.value = "(no result)";
            return;
        }
        try {
            BaseValueInfoData vi = d.getResultValueInfo();
            if (vi == null) {
                r.value = "(complex value)";
                return;
            }
            r.type = vi.getTypeName();
            if (vi.getValueBoolean() != null) {
                r.value = vi.getValueBoolean().toString();
            } else if (vi.getValueDecimal() != null) {
                r.value = vi.getValueDecimal().toPlainString();
            } else if (vi.getValueDateTime() != null) {
                r.value = vi.getValueDateTime().toString();
            } else if (vi.getValueString() != null) {
                r.value = new String(vi.getValueString(), StandardCharsets.UTF_8);
            } else {
                r.value = "";
            }
        } catch (Exception e) {
            r.value = "(render failed: " + e.getClass().getSimpleName() + ")";
        }
    }

}
