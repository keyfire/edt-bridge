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
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Read-only metadata navigation over the live BM model: object details + child structure, inbound
 * references, and object listing (per project or across all). Split out of the original model gateway to
 * keep that file focused; behaviour is unchanged.
 */
public final class MetadataReadGateway {

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
            c.valueType = GatewaySupport.renderType(((BasicFeature) md).getType());
        }
        c.children = childrenOf(md, maxDepth - 1);
        group.items.add(c);
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
}
