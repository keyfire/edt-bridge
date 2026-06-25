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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.diagnostics.Severity;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.EObjectAtOffsetHelper;
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
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.mcore.NamedElement;
import com._1c.g5.v8.dt.mcore.NumberQualifiers;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.mcore.StringQualifiers;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.wiring.ServiceAccess;
import com.google.inject.Injector;

/**
 * Thin adapter over the 1C:EDT / Eclipse model. This single class is the only place that
 * touches the IDE model, so an EDT/Eclipse API change stays contained here. It backs the MCP
 * tools with read-only operations: validation markers, metadata details and listing,
 * cross-references, query validation, and BSL navigation (definition / type).
 */
public final class EdtModelGateway {

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
