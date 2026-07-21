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
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.osgi.framework.Bundle;
import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.operations.IProjectOperationApi;
import com._1c.g5.v8.dt.core.operations.ProjectPipelineJob;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProjectManager;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumpSupport;
import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumper;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ConfigurationExtensionPurpose;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.mcore.DateFractions;
import com._1c.g5.v8.dt.mcore.DateQualifiers;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.NumberQualifiers;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.StringQualifiers;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.util.MdProducedTypesUtil;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypePackage;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringItem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringProblem;
import com._1c.g5.wiring.ServiceAccess;
import com.google.inject.Injector;

/**
 * Metadata write / refactoring operations: add / remove / modify attributes, rename and delete
 * objects, create objects, create configuration-extension and external-object projects, and dump an
 * external object. Includes the type-spec parser used by the attribute tools. Split out of
 * the original model gateway to keep that file focused; behaviour is unchanged.
 */
public final class MetadataWriteGateway {
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
            r.plan = "Add attribute \"" + name + "\" of type " + (r.typeParsed != null ? r.typeParsed : typeSpec)
                    + " to " + ownerFqn + (synonymRu != null && !synonymRu.isBlank() ? " (synonym: " + synonymRu + ")" : "");
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
            final Version version = GatewaySupport.projectVersion(p);
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

    // ---- Metadata write: add an HTTP service route (url template + method) -------------------------

    /** Result of {@link #addRoute}. */
    public static final class AddRouteResult {
        public boolean ok;               // validation passed -> an apply would be valid
        public boolean applied;          // a real write happened
        public String serviceFqn;
        public boolean serviceFound;
        public String serviceType;       // resolved eClass name (HTTPService)
        public String name;              // url-template name
        public String template;          // normalized URL template
        public String httpMethod;        // requested HTTP method (upper-cased)
        public String handler;           // handler procedure name
        public Boolean nameAvailable;    // url-template name not already used on the service
        public boolean templateInUse;    // another url-template already uses this template (warning only)
        public boolean httpMethodValid;  // the method resolves to an enum literal
        public String templateUuid;      // generated on apply
        public String methodUuid;        // generated on apply
        public boolean handlerWritten;   // the stub was spliced into the service module
        public String modulePath;        // service module (when the stub was written)
        public String plan;
        public String warning;
        public String message;
    }

    /**
     * Add a route (a URL template plus one HTTP method) to an {@code HTTPService}. Fills the gap where a
     * new route otherwise meant editing the service {@code .mdo} by hand and generating the uuid of both
     * the {@code urlTemplates} block and its nested {@code methods} block. Dry-run by default: resolves
     * the service, checks the template name is free and the HTTP method is a known literal, and returns
     * the plan without writing. {@code apply=true} creates the url template and its method through the
     * model (uuids from {@link UUID#randomUUID}, the same source the other writers use) and serialises the
     * {@code .mdo}; {@code createHandler} additionally splices a handler stub into the service module via
     * {@link BslGateway#addMethod}, addressing it by the service FQN. The {@code bsl_support_status =
     * EDITABLE} gate before an apply is the caller's responsibility, as with the other write tools.
     */
    public AddRouteResult addRoute(String projectName, String serviceFqnRaw, String name, String template,
            String httpMethodArg, String handlerArg, String synonymRu, boolean createHandler, boolean apply) {
        AddRouteResult r = new AddRouteResult();
        final String serviceFqn = (serviceFqnRaw == null) ? null : serviceFqnRaw.trim();
        r.serviceFqn = serviceFqn;
        r.name = name;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        if (serviceFqn == null || serviceFqn.isBlank()) {
            r.message = "serviceFqn is required, e.g. HTTPService.Payments";
            return r;
        }
        if (name == null || name.isBlank() || !IDENT.matcher(name).matches()) {
            r.message = "url template name is required and must be a valid 1C identifier: \"" + name + "\"";
            return r;
        }
        if (template == null || template.isBlank()) {
            r.message = "template is required, e.g. /subscribers/{id}";
            return r;
        }
        final String tmpl = template.trim().startsWith("/") ? template.trim() : "/" + template.trim();
        r.template = tmpl;
        final String httpMethod = (httpMethodArg == null || httpMethodArg.isBlank())
                ? "GET" : httpMethodArg.trim().toUpperCase();
        r.httpMethod = httpMethod;
        final String handler = (handlerArg == null || handlerArg.isBlank()) ? name + httpMethod : handlerArg.trim();
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

        // Stage 1 – resolve the service, check the name is free and the HTTP method is a known literal.
        model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.addRoute.validate") {
            @Override
            public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                IBmObject svc = tx.getTopObjectByFqn(serviceFqn);
                if (svc == null) {
                    r.message = "HTTP service not found: " + serviceFqn;
                    return null;
                }
                r.serviceFound = true;
                r.serviceType = svc.eClass().getName();
                EStructuralFeature templatesF = svc.eClass().getEStructuralFeature("urlTemplates");
                if (!(templatesF instanceof EReference)) {
                    r.message = "object is not an HTTP service (no urlTemplates): " + r.serviceType;
                    return null;
                }
                boolean used = false;
                Object val = svc.eGet(templatesF);
                if (val instanceof List) {
                    for (Object x : (List<?>) val) {
                        if (x instanceof MdObject && name.equalsIgnoreCase(((MdObject) x).getName())) {
                            used = true;
                        }
                        if (x instanceof EObject) {
                            EStructuralFeature tf = ((EObject) x).eClass().getEStructuralFeature("template");
                            if (tf != null) {
                                Object tv = ((EObject) x).eGet(tf);
                                if (tv != null && tmpl.equalsIgnoreCase(tv.toString())) {
                                    r.templateInUse = true;
                                }
                            }
                        }
                    }
                }
                r.nameAvailable = !used;
                EEnumLiteral lit = resolveHttpMethod((EReference) templatesF, httpMethod);
                r.httpMethodValid = lit != null;
                return null;
            }
        });

        if (r.message != null) {
            return r;
        }
        if (!r.httpMethodValid) {
            r.message = "unsupported httpMethod: \"" + httpMethod + "\" (expected GET, POST, PUT, DELETE, "
                    + "PATCH, HEAD, OPTIONS, ...)";
            return r;
        }
        r.ok = r.serviceFound && Boolean.TRUE.equals(r.nameAvailable) && r.httpMethodValid;
        r.plan = "Add route " + httpMethod + " " + tmpl + " (url template \"" + name + "\") to " + serviceFqn
                + " -> handler " + handler + "()"
                + (createHandler ? " and write its stub into the service module" : "");
        if (Boolean.FALSE.equals(r.nameAvailable)) {
            r.message = "the service already has a url template named \"" + name + "\"";
        } else if (r.templateInUse) {
            r.warning = "another url template already uses the template \"" + tmpl + "\"";
        }

        if (!apply) {
            return r;
        }
        if (!r.ok) {
            r.message = (r.message == null ? "validation failed" : r.message)
                    + " - apply refused (nothing written).";
            return r;
        }
        final String[] failure = {null};
        try {
            model.execute(new AbstractBmTask<Object>("edt-bridge.addRoute.apply") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    IBmObject svc = tx.getTopObjectByFqn(serviceFqn);
                    if (svc == null) {
                        failure[0] = "the service disappeared between validation and apply";
                        return null;
                    }
                    EReference templatesF = (EReference) svc.eClass().getEStructuralFeature("urlTemplates");
                    EClass tmplClass = templatesF.getEReferenceType();
                    EObject urlTemplate = tmplClass.getEPackage().getEFactoryInstance().create(tmplClass);
                    MdObject tmd = (MdObject) urlTemplate;
                    tmd.setName(name);
                    UUID templateUuid = UUID.randomUUID();
                    tmd.setUuid(templateUuid);
                    tmd.getSynonym().put("ru", (synonymRu != null && !synonymRu.isBlank()) ? synonymRu : name);
                    urlTemplate.eSet(tmplClass.getEStructuralFeature("template"), tmpl);

                    EReference methodsF = (EReference) tmplClass.getEStructuralFeature("methods");
                    EClass methodClass = methodsF.getEReferenceType();
                    EObject method = methodClass.getEPackage().getEFactoryInstance().create(methodClass);
                    MdObject mmd = (MdObject) method;
                    mmd.setName(httpMethod);
                    UUID methodUuid = UUID.randomUUID();
                    mmd.setUuid(methodUuid);
                    mmd.getSynonym().put("ru", httpMethod);
                    method.eSet(methodClass.getEStructuralFeature("handler"), handler);
                    EStructuralFeature hmF = methodClass.getEStructuralFeature("httpMethod");
                    EEnumLiteral lit = resolveHttpMethod(templatesF, httpMethod);
                    method.eSet(hmF, (lit.getInstance() != null) ? lit.getInstance() : lit);

                    @SuppressWarnings("unchecked")
                    List<EObject> methods = (List<EObject>) urlTemplate.eGet(methodsF);
                    methods.add(method);
                    @SuppressWarnings("unchecked")
                    List<EObject> templates = (List<EObject>) svc.eGet(templatesF);
                    templates.add(urlTemplate);

                    r.templateUuid = templateUuid.toString();
                    r.methodUuid = methodUuid.toString();
                    return null;
                }
            });
        } catch (RuntimeException ex) {
            r.applied = false;
            r.message = "apply failed (nothing committed): " + GatewaySupport.describeCause(ex);
            return r;
        }
        if (failure[0] != null) {
            r.message = "apply failed (nothing committed): " + failure[0];
            return r;
        }
        // BM commit mutates the in-memory model only; force the DT export so the change reaches the .mdo.
        IDtProject dtProject = mm.getDtProject(model);
        boolean exported = dtProject != null && mm.forceExport(dtProject, serviceFqn);
        r.applied = true;
        r.message = "written: added route " + httpMethod + " " + tmpl + " to " + serviceFqn
                + (exported ? " (serialized to .mdo)"
                        : " - WARNING: committed in-memory but forceExport did not persist (.mdo unchanged)");

        if (createHandler) {
            try {
                String methodText = "Функция " + handler + "(Запрос)\n"
                        + "\t// TODO: реализовать обработчик; вернуть HTTPСервисОтвет.\n"
                        + "\tОтвет = Новый HTTPСервисОтвет(200);\n"
                        + "\tВозврат Ответ;\n"
                        + "КонецФункции";
                BslGateway.AddMethodResult am = new BslGateway().addMethod(projectName, serviceFqn, null, null,
                        methodText, "ОбработчикиСобытий", Boolean.FALSE, true);
                if (am.applied) {
                    r.handlerWritten = true;
                    r.modulePath = am.modulePath;
                } else {
                    r.warning = appendWarning(r.warning, "route created, but the handler stub was not written: "
                            + (am.message == null ? "unknown reason" : am.message));
                }
            } catch (RuntimeException ex) {
                r.warning = appendWarning(r.warning, "route created, but the handler stub could not be written: "
                        + GatewaySupport.describeCause(ex));
            }
        }
        return r;
    }

    /** Resolve an HTTP method name to the {@code httpMethod} enum literal of a service's method class,
     *  by literal then by name; null when the enum has no such member. */
    private static EEnumLiteral resolveHttpMethod(EReference urlTemplatesRef, String httpMethod) {
        EClass tmplClass = urlTemplatesRef.getEReferenceType();
        EStructuralFeature methodsF = tmplClass.getEStructuralFeature("methods");
        if (!(methodsF instanceof EReference)) {
            return null;
        }
        EClass methodClass = ((EReference) methodsF).getEReferenceType();
        EStructuralFeature hmF = methodClass.getEStructuralFeature("httpMethod");
        if (hmF == null || !(hmF.getEType() instanceof EEnum)) {
            return null;
        }
        EEnum en = (EEnum) hmF.getEType();
        EEnumLiteral lit = en.getEEnumLiteralByLiteral(httpMethod);
        return (lit != null) ? lit : en.getEEnumLiteral(httpMethod);
    }

    /** Join a second warning onto an existing one (null-safe), separated by "; ". */
    private static String appendWarning(String existing, String more) {
        return (existing == null || existing.isBlank()) ? more : existing + "; " + more;
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
        public List<MetadataReadGateway.Ref> refs = new ArrayList<>(); // sample of referencing sources
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
                        MetadataReadGateway.Ref e = new MetadataReadGateway.Ref();
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
        r.plan = "Remove attribute \"" + name + "\" from " + ownerFqn + " (inbound references: "
                + r.refCount + " total, " + r.externalRefCount + " external)";
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
                    r.currentType = GatewaySupport.renderType(((BasicFeature) attr).getType());
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
        StringBuilder plan = new StringBuilder("Modify attribute \"" + name + "\" in " + ownerFqn + ":");
        if (wantType) {
            plan.append(" type ").append(r.currentType != null ? r.currentType : "?").append(" → ").append(r.newType);
        }
        if (wantSyn) {
            plan.append(" synonym(ru) → \"").append(newSynonymRu).append("\"");
        }
        if (wantComment) {
            plan.append(" comment → \"").append(newComment).append("\"");
        }
        r.plan = plan.toString();
        if (wantType) {
            r.warning = "changing an attribute's type can break backward compatibility (infobase data and code).";
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
            final Version version = GatewaySupport.projectVersion(p);
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
        r.plan = "Rename " + (r.topObject ? "object" : "attribute/member") + " \"" + r.currentName
                + "\" → \"" + newName + "\" in " + targetFqn + " (affected changes: "
                + r.items.size() + (r.truncated ? "+" : "") + ", refactorings: " + r.refactoringCount + ")";
        r.warning = "a rename changes the metadata contract and BREAKS backward compatibility for "
                + "dependent configurations and extensions on other deployments. The cascade touches "
                + "metadata AND BSL across all projects. Requires explicit confirmation (force).";
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
        r.plan = "Delete " + (r.topObject ? "object" : "member") + " \"" + r.name + "\" (" + targetFqn
                + "); affected changes: " + r.items.size() + (r.truncated ? "+" : "")
                + ", refactorings: " + r.refactoringCount;
        r.warning = "deleting an object is IRREVERSIBLE and BREAKS backward compatibility for dependent "
                + "configurations; the cascade removes the object's .mdo and clears references in metadata "
                + "AND BSL across all projects. Requires explicit confirmation (force). Back up before apply.";
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
        r.plan = "Create " + objectType + " \"" + name + "\" (" + fqn + ")"
                + (r.feature != null ? " in Configuration." + r.feature : "")
                + (synonymRu != null && !synonymRu.isBlank() ? " (synonym: " + synonymRu + ")" : "");
        r.warning = "a new object becomes part of the configuration contract – name it per conventions; "
                + "the addition is additive and does not break backward compatibility.";
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
            final Version version = GatewaySupport.projectVersion(p);
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
        public boolean stamped;       // prefix/purpose confirmed on the created Configuration
        // Read back from the created project - the traits that make it a valid extension.
        public boolean modelReady;    // the model loaded in time to be read back at all
        public String objectBelonging;// Adopted - the platform rejects the project otherwise
        public String extensionBlock; // ConfigurationExtension control block
        public boolean keepMappingByIds; // adopted objects link to the base ones by uuid, not by name
        public String adoptedLanguage;   // language adopted from the base configuration
        public boolean languageLinked;   // that language carries the base language's uuid
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
     *       project's runtime version. Its root Configuration is the BASE configuration ADOPTED (see
     *       the body): that is what carries {@code objectBelonging=Adopted}, the extension control
     *       block, the adopted default language and the inherited compatibility modes, without which
     *       an infobase refuses to load the extension. The new project's model loads in background
     *       jobs; the result is read back from it and reports what actually landed, or
     *       {@code modelReady=false} if the model was still loading (the create itself is done).
     * </ul>
     */
    public CreateExtensionResult createExtension(String name, String baseProjectName, String namePrefix,
            String purposeName, String synonym, boolean apply) {
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
        Version version = GatewaySupport.projectVersion(base);
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
        IModelObjectAdopter adopter = modelObjectAdopter();
        if (epm == null || adopter == null) {
            r.message = "extension project services unavailable (IExtensionProjectManager / adopter)";
            return r;
        }
        try {
            // The root Configuration of an extension is the BASE configuration ADOPTED - not a fresh
            // Configuration from the md factory. That is what makes the project an extension at all:
            // the adopter sets objectBelonging=Adopted, builds the <extension> control block, inherits
            // scriptVariant / compatibility modes / defaultRunMode / usePurposes from the base, adopts
            // the base's default language (languages are not top objects, so this is the only way they
            // get in) and fills contained-object ids. A factory-made Configuration carries none of that
            // and instead carries full-configuration properties an extension has no business with; an
            // infobase refuses to load such a project ("the load must not change the ownership of the
            // main configuration object"). This mirrors ExtensionWizard.adopt(), the New Configuration
            // Extension wizard's own step. create() then attaches the object DIRECTLY to the new
            // project's model (ExtensionProjectManager -> tx.attachTopObject, no copy), so what is
            // handed over must be detached - which an adopted copy is.
            Configuration adopted = adopter.adopt(baseConfig, version, new NullProgressMonitor());
            if (adopted == null) {
                r.message = "the adopter returned nothing for the base configuration of "
                        + baseProjectName;
                return r;
            }
            // The adopted copy inherits the base configuration's name; the extension gets its own.
            adopted.setName(name);
            adopted.setNamePrefix(namePrefix);
            adopted.setConfigurationExtensionPurpose(purpose);
            if (synonym != null && !synonym.isBlank()) {
                // Keyed by the language adopted from the base - the one the extension actually has.
                for (Language lang : adopted.getLanguages()) {
                    if (lang.getLanguageCode() != null && !lang.getLanguageCode().isBlank()) {
                        adopted.getSynonym().put(lang.getLanguageCode(), synonym);
                    }
                    break;
                }
            }
            // Without this flag every object adopted later binds to its base object BY NAME; with it
            // EDT writes the base object's uuid (MdObjectAdopterParticipant reads the flag off the
            // extension's Configuration when it builds an adopted copy). The wizard always sets it.
            adopted.setKeepMappingToExtendedConfigurationObjectsByIDs(true);

            IProject created = epm.create(name, version, adopted, base, new NullProgressMonitor());
            r.location = (created != null && created.getLocation() != null)
                    ? created.getLocation().toOSString() : null;
            r.applied = true;
            ExtensionCheck check = checkExtensionConfiguration(created, namePrefix, purpose);
            r.modelReady = check.modelReady;
            r.objectBelonging = check.objectBelonging;
            r.extensionBlock = check.extensionBlock;
            r.keepMappingByIds = check.keepMappingByIds;
            r.adoptedLanguage = check.adoptedLanguage;
            r.languageLinked = check.languageLinked;
            r.stamped = check.modelReady && namePrefix.equals(check.namePrefix)
                    && purpose.getName().equals(check.purpose);
            r.message = "created extension project " + name + " extending " + baseProjectName
                    + (check.modelReady
                            ? " (root Configuration adopted from the base: belonging="
                              + check.objectBelonging + ", keepMappingByIds=" + check.keepMappingByIds
                              + (check.adoptedLanguage == null ? ", no adopted language"
                                      : ", language " + check.adoptedLanguage)
                              + (check.repaired ? "; prefix/purpose written after creation" : "") + ")"
                            : " – WARNING: created, but its model was still loading, so nothing could be"
                              + " read back. Re-check the Configuration before building the extension.");
        } catch (CoreException | RuntimeException ex) {
            r.applied = false;
            r.message = "create failed: " + GatewaySupport.describeCause(ex);
        }
        return r;
    }

    /** What a freshly created extension project's Configuration actually carries, read back. */
    private static final class ExtensionCheck {
        boolean modelReady;      // the project's model loaded and its Configuration could be read
        String objectBelonging;  // must be Adopted for the platform to accept the project
        String extensionBlock;   // class of the <extension> control block (ConfigurationExtension)
        boolean keepMappingByIds;
        String adoptedLanguage;  // name of the language adopted from the base, null if none
        boolean languageLinked;  // that language carries the base language's uuid
        String namePrefix;
        String purpose;
        boolean repaired;        // prefix/purpose had to be written after creation
    }

    /**
     * Read back a freshly created extension project's Configuration and report the traits that make
     * it a valid extension - the ones whose absence an infobase only reveals on load. Repairs
     * {@code namePrefix} / {@code purpose} if they did not survive creation, and serializes only
     * then. The project's BM model loads asynchronously after {@link IExtensionProjectManager#create},
     * so poll briefly (up to ~30 s) for the model and its Configuration before giving up.
     */
    private ExtensionCheck checkExtensionConfiguration(IProject created, String namePrefix,
            ConfigurationExtensionPurpose purpose) {
        ExtensionCheck c = new ExtensionCheck();
        if (created == null) {
            return c;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        if (mm == null) {
            return c;
        }
        for (int attempt = 0; attempt < 15; attempt++) {
            IBmModel model = mm.getModel(created);
            if (model != null) {
                try {
                    Boolean done = model.execute(
                            new AbstractBmTask<Boolean>("edt-bridge.createExtension.check") {
                                @Override
                                public Boolean execute(IBmTransaction tx, IProgressMonitor monitor) {
                                    EObject cfg = tx.getTopObjectByFqn("Configuration");
                                    if (!(cfg instanceof Configuration)) {
                                        return Boolean.FALSE;
                                    }
                                    Configuration conf = (Configuration) cfg;
                                    c.objectBelonging = (conf.getObjectBelonging() == null) ? null
                                            : conf.getObjectBelonging().getName();
                                    c.extensionBlock = (conf.getExtension() == null) ? null
                                            : conf.getExtension().eClass().getName();
                                    c.keepMappingByIds =
                                            conf.isKeepMappingToExtendedConfigurationObjectsByIDs();
                                    for (Language lang : conf.getLanguages()) {
                                        c.adoptedLanguage = lang.getName();
                                        c.languageLinked = lang.getExtendedConfigurationObject() != null;
                                        break;
                                    }
                                    if (!namePrefix.equals(conf.getNamePrefix())) {
                                        conf.setNamePrefix(namePrefix);
                                        c.repaired = true;
                                    }
                                    if (conf.getConfigurationExtensionPurpose() != purpose) {
                                        conf.setConfigurationExtensionPurpose(purpose);
                                        c.repaired = true;
                                    }
                                    c.namePrefix = conf.getNamePrefix();
                                    c.purpose = (conf.getConfigurationExtensionPurpose() == null) ? null
                                            : conf.getConfigurationExtensionPurpose().getName();
                                    return Boolean.TRUE;
                                }
                            });
                    if (Boolean.TRUE.equals(done)) {
                        c.modelReady = true;
                        if (c.repaired) {
                            IDtProject dt = mm.getDtProject(model);
                            if (dt != null) {
                                mm.forceExport(dt, "Configuration");
                            }
                        }
                        return c;
                    }
                } catch (RuntimeException ignored) {
                    // model not ready for writes yet – keep polling
                }
            }
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return c;
            }
        }
        return c;
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
        public String scriptVariant;  // resolved built-in language variant (Russian / English)
        public String plan;
        public String warning;
        public String message;
    }

    /**
     * Phase-2 write tool: create a NEW external-data-processor PROJECT via EDT's own
     * {@link IExternalObjectProjectManager} (the engine behind the New External Data Processor
     * wizard). {@code baseProjectName} is optional: with it, the processor project is linked to that
     * configuration project (its runtime version and types context); without it, a standalone
     * processor project with the default runtime version. Dry-run by default.
     *
     * <p>{@code scriptVariantName} picks the built-in language ("Russian" / "English"). EDT defaults a
     * standalone project to English, which names generated members {@code Object} instead of
     * {@code Объект} – pass the variant explicitly for a Russian project.
     */
    public CreateExternalObjectResult createExternalObject(String name, String baseProjectName,
            String scriptVariantName, boolean apply) {
        CreateExternalObjectResult r = new CreateExternalObjectResult();
        r.name = name;
        r.baseProject = (baseProjectName == null || baseProjectName.isBlank()) ? null : baseProjectName;
        ScriptVariant variant = parseScriptVariant(scriptVariantName);
        if (scriptVariantName != null && !scriptVariantName.isBlank() && variant == null) {
            r.message = "unknown scriptVariant: \"" + scriptVariantName + "\" (use Russian or English)";
            return r;
        }
        r.scriptVariant = (variant == null) ? null : variant.getName();

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
        Version version = (base != null) ? GatewaySupport.projectVersion(base) : Version.LATEST;
        r.version = String.valueOf(version);
        r.ok = Boolean.TRUE.equals(r.nameAvailable);
        r.plan = "Create external data processor project \"" + name + "\""
                + (r.baseProject != null ? " for configuration project " + r.baseProject : " (standalone)")
                + " [" + r.version + "]"
                + (r.scriptVariant != null ? " script variant " + r.scriptVariant : "");
        if (variant == null && r.baseProject == null) {
            r.warning = "no scriptVariant given – EDT defaults a standalone project to English, so "
                    + "generated members are named Object rather than Объект. Pass scriptVariant=Russian "
                    + "for a Russian project.";
        }
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
            String variantProblem = (variant == null) ? null
                    : applyScriptVariant(created, factory, eom, version, variant);
            if (variantProblem != null) {
                r.warning = "project created, but the script variant stayed at EDT's default: "
                        + variantProblem;
            }
            r.message = "created external data processor project " + name
                    + (variant != null && variantProblem == null ? " [" + variant.getName() + "]" : "")
                    + " (the project model loads in background – poll edt_projects)";
        } catch (CoreException | RuntimeException ex) {
            r.applied = false;
            r.message = "create failed: " + GatewaySupport.describeCause(ex);
        }
        return r;
    }

    /** {@code Russian} / {@code English} (case-insensitive), or {@code null} when unset or unknown. */
    private static ScriptVariant parseScriptVariant(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.trim();
        if (key.equalsIgnoreCase("Russian") || key.equalsIgnoreCase("Русский")
                || key.equalsIgnoreCase("ru")) {
            return ScriptVariant.RUSSIAN;
        }
        if (key.equalsIgnoreCase("English") || key.equalsIgnoreCase("Английский")
                || key.equalsIgnoreCase("en")) {
            return ScriptVariant.ENGLISH;
        }
        return null;
    }

    /**
     * Switch a freshly created external-object project to the given built-in language variant and give
     * it the matching interface language, through EDT's own project manager. Returns {@code null} on
     * success, otherwise a short reason.
     *
     * <p>{@code create()} returns before the project model is loaded, so the {@link IV8Project} is
     * polled for a bounded while rather than read once.
     */
    private String applyScriptVariant(IProject created, IModelObjectFactory factory,
            IExternalObjectProjectManager eom, Version version, ScriptVariant variant) {
        if (created == null) {
            return "the created project handle is null";
        }
        IV8ProjectManager pm = ServiceAccess.get(IV8ProjectManager.class);
        if (pm == null) {
            return "IV8ProjectManager unavailable";
        }
        IExternalObjectProject target = null;
        for (int attempt = 0; attempt < 60 && target == null; attempt++) {
            IV8Project v8 = pm.getProject(created);
            if (v8 instanceof IExternalObjectProject) {
                target = (IExternalObjectProject) v8;
                break;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return "interrupted while waiting for the project model";
            }
        }
        if (target == null) {
            return "the project model did not load within 30 s";
        }
        IProgressMonitor monitor = new NullProgressMonitor();
        eom.setScriptVariant(target, variant, monitor);
        EObject langObject = factory.create(
                (EClass) MdClassPackage.eINSTANCE.getEClassifier("Language"), version);
        if (langObject instanceof Language) {
            boolean russian = variant == ScriptVariant.RUSSIAN;
            Language language = (Language) langObject;
            language.setName(russian ? "Русский" : "English");
            language.setLanguageCode(russian ? "ru" : "en");
            language.setUuid(UUID.randomUUID());
            eom.setLanguages(target, List.of(language), monitor);
        }
        return null;
    }

    /** Result of {@link #adoptObject}. */
    public static final class AdoptResult {
        public boolean ok;
        public boolean applied;
        public String project;        // the extension project
        public String baseProject;    // the configuration it extends
        public String fqn;            // object being adopted
        public String type;
        public boolean extensionFound;
        public boolean objectFound;
        public Boolean adoptable;
        public Boolean alreadyAdopted;
        public String plan;
        public String warning;
        public String message;
    }

    /**
     * Adopt ("заимствовать") an object of the base configuration into an extension project - the step
     * that has to happen before an extension can intercept anything on that object.
     *
     * <p>Goes through EDT's own {@link IModelObjectAdopter}: it produces the adopted copy with the
     * right {@code objectBelonging}, the link back to the base object's uuid and the per-property
     * control block, and attaches it to the extension. Hand-writing that {@code .mdo} is the thing
     * this replaces - the control block alone is easy to get subtly wrong.
     *
     * @param projectName the EXTENSION project
     * @param fqn         object of the base configuration, e.g. {@code Catalog.Контрагенты}
     * @param apply       {@code false} validates and returns the plan; {@code true} performs the adopt
     */
    public AdoptResult adoptObject(String projectName, String fqn, boolean apply) {
        AdoptResult r = new AdoptResult();
        r.project = projectName;
        r.fqn = fqn;
        if (projectName == null || projectName.isBlank() || fqn == null || fqn.isBlank()) {
            r.message = "projectName (the extension) and fqn (the base object) are required";
            return r;
        }
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        IV8ProjectManager pm = ServiceAccess.get(IV8ProjectManager.class);
        IV8Project v8 = (pm == null) ? null : pm.getProject(p);
        if (!(v8 instanceof IExtensionProject)) {
            r.message = "not an extension project: " + projectName
                    + " - adopting only makes sense into a configuration extension";
            return r;
        }
        IExtensionProject extension = (IExtensionProject) v8;
        r.extensionFound = true;
        IProject base = extension.getParentProject();
        if (base == null) {
            r.message = "the extension has no base configuration project attached";
            return r;
        }
        r.baseProject = base.getName();

        IModelObjectAdopter adopter = modelObjectAdopter();
        if (adopter == null) {
            r.message = "adopt service unavailable (EDT md.extension plugin not active)";
            return r;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel baseModel = (mm == null) ? null : mm.getModel(base);
        if (baseModel == null) {
            r.message = "no BM model for the base project: " + r.baseProject;
            return r;
        }

        // Inspect in the BASE model - that is where the object to adopt lives.
        baseModel.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.adopt.inspect") {
            @Override
            public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                IBmObject o = tx.getTopObjectByFqn(fqn);
                if (o == null) {
                    return null;
                }
                r.objectFound = true;
                r.type = o.eClass().getName();
                try {
                    r.adoptable = Boolean.valueOf(adopter.isAdoptable(o));
                    r.alreadyAdopted = Boolean.valueOf(adopter.isAdopted(o, extension));
                } catch (RuntimeException ignored) {
                    // leave the flags unset - the plan below reports what is known
                }
                return null;
            }
        });

        if (!r.objectFound) {
            r.message = "object not found in the base configuration " + r.baseProject + ": " + fqn;
            return r;
        }
        r.ok = !Boolean.FALSE.equals(r.adoptable) && !Boolean.TRUE.equals(r.alreadyAdopted);
        r.plan = "Adopt " + fqn + " (" + r.type + ") from " + r.baseProject + " into " + projectName;
        if (Boolean.TRUE.equals(r.alreadyAdopted)) {
            r.message = fqn + " is already adopted into " + projectName;
        } else if (Boolean.FALSE.equals(r.adoptable)) {
            r.message = r.type + " cannot be adopted into an extension";
        } else {
            r.warning = "adopting brings the object under the extension's control - its own members stay "
                    + "owned by the base configuration until they are adopted too.";
        }

        if (!apply) {
            return r;
        }
        if (!r.ok) {
            r.message = (r.message == null ? "validation failed" : r.message)
                    + " - apply refused (nothing adopted).";
            return r;
        }
        try {
            final EObject[] adopted = {null};
            baseModel.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.adopt.read") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    adopted[0] = tx.getTopObjectByFqn(fqn);
                    return null;
                }
            });
            if (adopted[0] == null) {
                r.message = "the object disappeared between validation and apply";
                return r;
            }
            EObject result = adopter.adoptAndAttach(adopted[0], extension, new NullProgressMonitor());
            r.applied = result != null;
            r.message = r.applied
                    ? "adopted " + fqn + " into " + projectName
                            + " (attached to the extension's configuration)"
                    : "the adopter returned nothing for " + fqn;
        } catch (CoreException | RuntimeException ex) {
            r.applied = false;
            r.message = "adopt failed: " + GatewaySupport.describeCause(ex);
        }
        return r;
    }

    /** EDT's adopt engine, from the md.extension plugin's Guice injector. */
    private IModelObjectAdopter modelObjectAdopter() {
        try {
            Bundle b = Platform.getBundle("com._1c.g5.v8.dt.md.extension");
            if (b == null) {
                return null;
            }
            Class<?> cls = b.loadClass("com._1c.g5.v8.dt.internal.md.extension.MdExtensionPlugin");
            Object plugin = cls.getMethod("getDefault").invoke(null);
            if (plugin == null) {
                return null;
            }
            Injector inj = (Injector) cls.getMethod("getInjector").invoke(plugin);
            return (inj == null) ? null : inj.getInstance(IModelObjectAdopter.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Result of {@link #dumpExternalObject}. */
    public static final class DumpExternalObjectResult {
        public boolean ok;
        public boolean applied;
        public String project;
        public String fqn;            // resolved top-object FQN (ExternalDataProcessor.X / ExternalReport.X)
        public String targetPath;
        public String validation;     // validateDumpGeneration status message (empty = OK)
        public String method;         // edt | disk:<version> - which route built the file
        public String logPath;        // where the platform build log was written (disk route only)
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
            String kind, String targetPath, String logPath, boolean apply) {
        DumpExternalObjectResult r = new DumpExternalObjectResult();
        r.project = projectName;
        r.targetPath = targetPath;
        // Default the build log to a sibling of the artefact, so it is always somewhere findable.
        r.logPath = (logPath == null || logPath.isBlank()) ? targetPath + ".log" : logPath;
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
        // validateDumpGeneration reports OK even when EDT resolves no thick client, and the native
        // dumper then dies on apply - so check the platform ourselves and say which route will be
        // taken, instead of promising a dump that cannot happen.
        final Version version = GatewaySupport.projectVersion(p);
        PlatformGateway platforms = new PlatformGateway();
        boolean nativeAvailable = platforms.edtResolvesThickClient();
        String diskPlatform = nativeAvailable ? null : platforms.diskPlatformFor(String.valueOf(version));
        r.method = nativeAvailable ? "edt" : (diskPlatform != null ? "disk:" + diskPlatform : null);
        r.ok = nativeAvailable || diskPlatform != null;
        r.plan = "Dump " + fqn + " to " + targetPath
                + (nativeAvailable ? " via EDT's dumper"
                        : diskPlatform != null ? " via the on-disk platform " + diskPlatform
                                + " (EDT resolves no thick client for " + version + ")" : "");
        if (!r.ok) {
            r.message = "no full (thick-client) 1C:Enterprise install is available for " + version
                    + " - neither EDT's resolver nor a scan of the disk found one"
                    + (r.validation == null || r.validation.isEmpty() ? "" : "; EDT reports: " + r.validation);
        }
        if (!apply) {
            return r;
        }
        if (!r.ok) {
            r.message = (r.message == null ? "validation failed" : r.message)
                    + " – apply refused (nothing dumped).";
            return r;
        }
        java.nio.file.Path target = java.nio.file.Path.of(targetPath);
        if (!nativeAvailable) {
            // EDT cannot resolve a thick client for this line, so go straight to the disk platform
            // rather than provoking the MatchingRuntimeNotFound we already know is coming.
            StringBuilder method = new StringBuilder();
            java.nio.file.Path log = java.nio.file.Path.of(r.logPath);
            String problem = platforms.dumpExternalObjectViaDisk(found[0], target, version, log, method);
            r.applied = problem == null && java.nio.file.Files.exists(target);
            r.method = r.applied ? method.toString() : r.method;
            r.message = r.applied
                    ? "dumped " + fqn + " to " + target + " using the on-disk platform ("
                            + method + "; EDT resolves no thick client for " + version
                            + "); build log: " + r.logPath
                    : "dump failed via the on-disk platform: " + problem
                            + " - build log: " + r.logPath;
            return r;
        }
        IExternalObjectDumper dumper = ServiceAccess.get(IExternalObjectDumper.class);
        if (dumper == null) {
            r.message = "IExternalObjectDumper service unavailable";
            return r;
        }
        try {
            if (target.getParent() != null) {
                java.nio.file.Files.createDirectories(target.getParent());
            }
            dumper.dump(p, found[0], target, new NullProgressMonitor());
            r.applied = java.nio.file.Files.exists(target);
            r.message = r.applied ? ("dumped " + fqn + " to " + target)
                    : "dumper finished but the target file is missing: " + target;
        } catch (CoreException | java.io.IOException | RuntimeException ex) {
            r.applied = false;
            r.message = "dump failed: " + GatewaySupport.describeCause(ex);
            if (ex instanceof CoreException) {
                // CoreException hides the real cause inside its IStatus, not always in getCause().
                IStatus st = ((CoreException) ex).getStatus();
                if (st != null && st.getException() != null) {
                    r.message += " (cause: " + GatewaySupport.describeCause(st.getException()) + ")";
                } else if (st != null && st.getMessage() != null && !st.getMessage().isBlank()) {
                    r.message += " (status: " + st.getMessage() + ")";
                }
            }
            if (r.message.toLowerCase(java.util.Locale.ROOT).contains("thick client")) {
                r.message += " – no full (thick-client) 1C:Enterprise install is registered in EDT for "
                        + "this version; register one (Preferences > 1C:Enterprise, see "
                        + "edt_platform_installations).";
            }
        }
        return r;
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
    /**
     * Parse a type spec ("Строка(150)", "Число(15, 2)", "СправочникСсылка.X", or a comma-separated
     * composite) into a {@link TypeDescription}, or {@code null} when the spec does not parse. The
     * single entry point other gateways in this package use, so the type grammar stays in one place.
     */
    TypeDescription typeDescriptionFor(IBmTransaction tx, String spec, Version version,
            boolean allowPlatformTypes) {
        ParsedType pt = parseType(spec);
        if (!pt.valid && allowPlatformTypes && spec != null && IDENT.matcher(spec.trim()).matches()) {
            // Form attributes accept platform types the metadata grammar has no business allowing -
            // ТаблицаЗначений, СписокЗначений, ДеревоЗначений and friends. Hand the bare name to the
            // platform type provider, which resolves it (or fails with a readable message).
            pt = new ParsedType();
            pt.valid = true;
            pt.platformCode = spec.trim();
            pt.display = spec.trim();
        }
        return pt.valid ? buildTypeDescription(tx, pt, version) : null;
    }

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
        if (part.kind == null) {
            // A bare platform type resolved by name (ТаблицаЗначений, СписокЗначений, ...) - it has no
            // qualifiers to apply, and switching on a null kind would blow up.
            return;
        }
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
}
