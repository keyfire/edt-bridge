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
package com.e1c.fresh.edtbridge.edt;

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
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
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
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.Button;
import com._1c.g5.v8.dt.form.model.CommandHandler;
import com._1c.g5.v8.dt.form.model.CommandHandlerContainer;
import com._1c.g5.v8.dt.form.model.DataItem;
import com._1c.g5.v8.dt.form.model.Decoration;
import com._1c.g5.v8.dt.form.model.EventHandler;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormItemContainer;
import com._1c.g5.v8.dt.form.model.FormParameter;
import com._1c.g5.v8.dt.form.model.NativeRenderEvent;
import com._1c.g5.v8.dt.form.model.Titled;
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
 * Thin adapter over EDT/Eclipse model access. This single class is the only place that
 * touches the IDE model, so an EDT/Eclipse API change is contained here ( condition).
 *
 * M0 uses ONLY standard Eclipse resources API: EDT validation results surface as IMarker
 * problems on project resources. M1 TODO: extend with com._1c.g5.v8.dt.* for DT-specific
 * data (check id, BLOCKER/CRITICAL/... severities, validate_query, semantic refs/def).
 */
public final class EdtModelGateway {

    /**
     * Dedicated single thread that owns the SWT Display used for form rendering — isolated from both
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
        public String severity; // ERROR | WARNING
        public String message;
        public String resource; // project-relative path
        public int line;        // -1 if not applicable
        public String markerType;
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
                out.add(pr);
            }
        }
        return out;
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
     * dbViewDefs) are skipped — they are computed, not structural, and never {@link MdObject}.
     */
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
     * resolves metadata for that project — it derives the project from the resource URI
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
        public List<FormNode> children = new ArrayList<>();
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
                // document form is NOT a top object — only its owner is — so resolve "Owner.Form.Name"
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
            int width, int height, int scale, String outPath) {
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
        final int w = width > 0 ? width : 1280;
        final int h = height > 0 ? height : 800;

        final int zoom = scale;
        // Render on a dedicated thread with its OWN Display, isolated from the workbench's main UI
        // thread: in a GUI EDT the platform Display lives on the main thread, so marshalling the
        // render onto it (syncExec) freezes/​times-out the editor. A private Display avoids that and
        // also works headless. The thread is single, so the Display is created once and reused.
        try {
            RENDER_EXECUTOR.submit(() -> {
                Display d = Display.findDisplay(Thread.currentThread());
                if (d == null) {
                    d = new Display();
                }
                renderOnUi(r, d, model, mm, fqn, variant, theme, w, h, zoom, outPath);
                return null;
            }).get();
        } catch (Exception e) {
            if (r.message == null) {
                r.message = describeThrowable(e);
            }
        }
        return r;
    }

    /** The whole render, on the SWT UI thread. Fills {@code r}; never throws. */
    private void renderOnUi(RenderResult r, Display d, IBmModel model, IBmModelManager mm, String fqn,
            ClientInterfaceVariant variant, ClientInterfaceTheme theme, int w, int h, int zoom, String outPath) {
        final boolean isDark = theme == ClientInterfaceTheme.ECIT_DARK;
        final ClientInterfaceScale scale = ClientInterfaceScale.ECIS_NORMAL;
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
            pvc.setCommonRatio(100);

            // 3) services + wire the offscreen windows. EDT's own composite doubles as the image
            //    supplier + mouse listener, so a null supplier/listener trips SWT's null check —
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
            //    fails; executeAndRollback is what EDT's own WYSIWYG uses — changes never persist.
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
     * root synchronously — {@code buildRootModel()} alone leaves the CMI incomplete for forms with a
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

    /** Class + message + top stack frames (and cause) — enough to locate a render-pipeline failure. */
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

    // ---- Metadata write, Phase 2: add attribute — DRY-RUN stage --------------------------

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
     *   <li>{@code apply=false} (default) — DRY-RUN: validate (owner resolvable, attribute name free,
     *       type string parses, reference target exists) and return the planned change WITHOUT writing.
     *   <li>{@code apply=true} — perform the write: build the {@link TypeDescription} from the type
     *       string (platform-primitive proxy via {@link IEObjectProvider}, or the target object's
     *       produced {@code Ref} type via {@link MdProducedTypesUtil}), create the attribute, set its
     *       name/synonym/comment/type/uuid, attach it to the owner's {@code attributes}, and commit in
     *       a BM read-write transaction. The write is skipped when validation fails (nothing written).
     * </ul>
     * The {@code bsl_support_status = EDITABLE} gate before an apply is the caller's
     * responsibility — the EDT support API is not on the surface, so it stays a process gate for now.
     */
    public AddAttrResult addAttribute(String projectName, String ownerFqn, String name, String typeSpec,
            String synonymRu, String comment, boolean apply) {
        AddAttrResult r = new AddAttrResult();
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
        // Stage 2 — apply: only when requested AND validation passed. Refuse to write otherwise.
        if (apply) {
            if (!r.ok) {
                r.message = (r.message == null ? "validation failed" : r.message)
                        + " — apply refused (nothing written).";
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
                                : " — WARNING: committed in-memory but forceExport did not persist (.mdo unchanged)");
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
     * Phase-2 write tool: remove an attribute from a metadata object. DESTRUCTIVE — removing an
     * attribute is a schema change (drops the column in a real infobase). Checks inbound references
     * first (BM cross-reference index): if referenced, removal is blocked unless {@code force=true}.
     * Dry-run by default ({@code apply=false}) reports references + the plan. The caller must verify
     * {@code bsl_support_status = EDITABLE} before any apply.
     */
    public RemoveAttrResult removeAttribute(String projectName, String ownerFqn, String name,
            boolean apply, boolean force) {
        RemoveAttrResult r = new RemoveAttrResult();
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

        // Stage 1 — locate the attribute and collect its inbound references (read).
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
                // own forms, presentation) — those are NOT a breakage signal and EDT updates them on
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
            r.message = "attribute is referenced by " + r.externalRefCount + " other object(s) — removal "
                    + "blocked; pass force=true to remove anyway (this will break those references). "
                    + "Note: BSL text references may not be captured by the model index.";
        }

        // Stage 2 — apply: remove from the containment, commit, serialize. Refused when not ok.
        if (apply) {
            if (!r.ok) {
                r.message = (r.message == null ? "cannot remove" : r.message)
                        + " — apply refused (nothing removed).";
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
                                : " — WARNING: removed in-memory but forceExport did not persist (.mdo unchanged)");
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
     * Phase-2 write tool: modify an existing attribute — its type, ru synonym and/or comment. At least
     * one change must be requested. Changing the TYPE may break backward compatibility (data + code) —
     * a warning is returned. Dry-run by default ({@code apply=false}). Caller verifies
     * {@code bsl_support_status = EDITABLE} before apply.
     */
    public ModifyAttrResult modifyAttribute(String projectName, String ownerFqn, String name,
            String newType, String newSynonymRu, String newComment, boolean apply) {
        ModifyAttrResult r = new ModifyAttrResult();
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
            r.message = "nothing to change — provide at least one of newType, newSynonymRu, newComment";
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

        // Stage 1 — find the attribute, capture its current type, validate the new type's references.
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

        // Stage 2 — apply.
        if (apply) {
            if (!r.ok) {
                r.message = (r.message == null ? "cannot modify" : r.message) + " — apply refused (nothing changed).";
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
                                : " — WARNING: changed in-memory but forceExport did not persist (.mdo unchanged)");
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
     * refactoring engine ({@link IMdRefactoringService#createMdObjectRenameRefactoring}) — not a brittle
     * text replace. Two stages by {@code apply}:
     * <ul>
     *   <li>{@code apply=false} (default) — DRY-RUN: resolve the target, build the refactoring(s) and
     *       return their change items ({@code getItems}) + validation problems ({@code getStatus}),
     *       performing nothing.
     *   <li>{@code apply=true} — perform the cascade via {@code IRefactoring.perform()}, run inside
     *       {@link IProjectOperationApi#performExclusiveOperation} (the build pipeline is suspended and
     *       the project locked — exactly how EDT's own editor applies a name change). The engine manages
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

        // Stage 1 — resolve the target and BUILD the refactoring (read-only). Building is pure analysis
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
                    // Not a top object — resolve a child member by its full FQN (e.g.
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
            r.message = "refactoring engine reported " + r.problems.size() + " problem(s) — apply blocked "
                    + "(e.g. name taken, or object not editable / support-locked).";
        }

        // Stage 2 — apply: perform the cascade. Requires force=true (the breaking-change override) AND a
        // clean dry-run. Runs OUTSIDE the read transaction, inside an exclusive project operation.
        if (apply) {
            if (!force) {
                r.message = "rename is a breaking change for peer configurations — apply "
                        + "refused; pass force=true (owner's explicit override) to perform it.";
                return r;
            }
            if (!r.ok) {
                r.message = (r.message == null ? "cannot rename" : r.message) + " — apply refused (nothing changed).";
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
                        + " — cascade performed across metadata + BSL.";
            } catch (Exception ex) {
                r.applied = false;
                r.message = "apply failed (cascade may be partial — verify the working tree): "
                        + ex.getClass().getSimpleName() + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
            }
        }
        return r;
    }

    /**
     * Resolve a child {@link MdObject} from its FQN segments: from {@code container}, consume the
     * remaining (kind, name) pairs starting at {@code idx}, descending one named child per pair. The
     * kind segment (e.g. "Attribute"/"TabularSection"/"Form") disambiguates when names collide — a
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
     * InformationRegister, …) using EDT's own object factory + per-type initializer (so the object is
     * born valid, with standard attributes/presentations), then register it as a BM top object and link
     * it into the {@code Configuration}. Two stages by {@code apply}:
     * <ul>
     *   <li>{@code apply=false} (default) — DRY-RUN: validate (type → eClass; {@code name} a legal 1C
     *       identifier; FQN free; the {@code Configuration} has a containment feature for the eClass) and
     *       return the plan, creating nothing.
     *   <li>{@code apply=true} — perform the recipe (from EDT's New-object wizard): {@code factory.create}
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

        // Stage 1 — validate against the live model (read): config present, has a feature for this
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
                + "/alienability) — имя по конвенциям; добавление аддитивно и обратную совместимость "
                + "не ломает.";
        if (!r.configFound) {
            r.message = "Configuration top object not found";
        } else if (featureName[0] == null) {
            r.message = "Configuration has no containment feature for eClass " + mdClassName;
        } else if (Boolean.FALSE.equals(r.nameAvailable)) {
            r.message = "object already exists: " + fqn;
        }

        // Stage 2 — apply: factory-create (initialized) outside the tx, then attach + link inside it.
        if (apply) {
            if (!r.ok) {
                r.message = (r.message == null ? "validation failed" : r.message)
                        + " — apply refused (nothing created).";
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
                                : " — WARNING: created in-memory but forceExport incomplete (object=" + exportedObj
                                        + ", configuration=" + exportedCfg + ")");
            } catch (RuntimeException ex) {
                r.applied = false;
                r.message = "create failed (verify the working tree — a partial object may remain): "
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
     * {@code catalogs}). In EDT's mdclass model these are NON-containment references — top objects are
     * owned by the BM, and the Configuration just lists them — so scan all many-valued references, not
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
        // TYPE_ITEM (NOT TYPE — that registry slot is empty), e.g. ...platform.type.v8_5_1.TypeItemProvider.
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
}
