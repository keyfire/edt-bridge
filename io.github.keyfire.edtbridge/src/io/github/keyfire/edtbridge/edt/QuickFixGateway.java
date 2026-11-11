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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.wiring.ServiceAccess;
import com.e1c.g5.v8.dt.check.qfix.FixProcessHandle;
import com.e1c.g5.v8.dt.check.qfix.FixVariantDescriptor;
import com.e1c.g5.v8.dt.check.qfix.IFixManager;
import com.e1c.g5.v8.dt.check.qfix.IFixRepository;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

import io.github.keyfire.edtbridge.core.TextChange;

/**
 * EDT's own quick fixes, the ones the Problems view offers on a marker, applied to one marker of one
 * check. The fix code is EDT's: this gateway finds the marker, asks EDT which fix variants apply to
 * it, runs the chosen one and reports what changed in the file and whether the marker is gone.
 */
public final class QuickFixGateway {
    private static final int VALIDATION_WAIT_SECONDS = 120;
    private static final String NO_WORKBENCH = "EDT applies a quick fix to a module only through the module "
            + "editor and has no non-interactive mode, so it needs client EDT; this bridge runs headless. "
            + "Switch to client EDT (edt-bridge-mcp gui) and repeat, or make the change by hand.";

    public static final class Occurrence {
        public String checkId;
        public String message;
        public String resource;
        public String location;
        public int line;
    }

    public static final class Variant {
        public int index;
        public String description;
        public String details;
    }

    public static final class Result {
        public boolean ok;
        public boolean applied;
        public String project;
        public String checkId;
        /** The check as EDT registers it: contributor and id. */
        public List<String> checks = new ArrayList<>();
        /** Whether EDT has a quick fix for the check at all. */
        public Boolean hasFix;
        /** The markers of the check in scope; one of them is fixed. */
        public List<Occurrence> markers = new ArrayList<>();
        public Occurrence target;
        public List<Variant> variants = new ArrayList<>();
        public Integer variant;
        public String file;
        public TextChange change;
        /** Markers of the same check on the same object after the fix and a fresh validation. */
        public Integer remaining;
        public Boolean validationCompleted;
        /** Whether the EDT workbench runs in this process: EDT applies a quick fix through it. */
        public Boolean workbenchRunning;
        /** EDT applies a fix to a module only through its editor, which needs the workbench. */
        public Boolean requiresWorkbench;
        public String message;
        public String warning;
        /** The first frames of an unexpected failure, for a report. */
        public List<String> stack;
    }

    public Result fix(String projectName, String checkId, String fqn, Integer line, Integer variantIndex,
            boolean apply) {
        Result r = new Result();
        r.project = projectName; r.checkId = checkId;
        try {
            if (checkId == null || checkId.isBlank()) throw new IllegalArgumentException("checkId is required");
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!project.exists() || !project.isOpen()) throw new IllegalArgumentException("project not found or closed: " + projectName);
            ICheckRepository checks = service(ICheckRepository.class);
            IFixRepository fixes = service(IFixRepository.class);
            IFixManager manager = service(IFixManager.class);
            if (checks == null || fixes == null || manager == null) {
                // The check framework registers its services when validation first runs, and a
                // freshly started EDT may not have validated anything yet.
                new ProjectGateway().refreshScope(projectName, fqn, null, VALIDATION_WAIT_SECONDS);
                checks = service(ICheckRepository.class);
                fixes = service(IFixRepository.class);
                manager = service(IFixManager.class);
            }
            final ICheckRepository checkRepository = checks;
            IDtProjectManager projects = FormWriteGateway.coreService(IDtProjectManager.class);
            IDtProject dtProject = projects == null ? null : projects.getDtProject(project);
            if (checks == null || fixes == null || manager == null || dtProject == null) {
                if (!new ProjectGateway().modelsReady(projectName)) {
                    throw new IllegalArgumentException("EDT is still loading the model of " + projectName
                            + "; call again once edt_projects reports the model loaded");
                }
                throw new IllegalStateException("the EDT check and quick-fix services are not available in this EDT");
            }

            Set<CheckUid> uids = resolve(checks, checkId.trim(), project);
            if (uids.isEmpty()) {
                throw new IllegalArgumentException("unknown check: " + checkId
                        + "; pass the id an edt_project_errors problem carries, e.g. SU100 or module-consecutive-blank-lines");
            }
            for (CheckUid uid : uids) r.checks.add(uid.getContributorId() + ":" + uid.getCheckId());
            r.hasFix = uids.stream().anyMatch(fixes::hasFixes);
            r.workbenchRunning = workbenchRunning();
            if (!r.hasFix) {
                r.ok = true;
                r.message = "EDT has no quick fix for check " + checkId + ". Change the code by hand; "
                        + "edt_check_info describes the check and shows a correct example.";
                return r;
            }

            List<ProjectGateway.Problem> found = new ArrayList<>();
            var report = new ProjectGateway().reportProblems(projectName, fqn, null, null, false, 10_000);
            Map<String, CheckUid> byShortId = new HashMap<>();
            for (ProjectGateway.Problem problem : report.problems) {
                if (problem.edtMarker == null || problem.checkId == null) continue;
                CheckUid uid = byShortId.computeIfAbsent(problem.checkId, id -> checkRepository.getUidForShortUid(id, project));
                boolean same = uid != null ? uids.contains(uid) : problem.checkId.equalsIgnoreCase(checkId.trim());
                if (!same || line != null && problem.line != line) continue;
                found.add(problem);
                r.markers.add(occurrence(problem));
            }
            if (found.isEmpty()) {
                throw new IllegalArgumentException("no marker of check " + checkId + " in scope"
                        + (fqn == null ? "" : " " + fqn) + (line == null ? "" : " at line " + line)
                        + "; run edt_project_errors with refresh=true to see the current markers");
            }
            if (found.size() > 1) {
                throw new IllegalArgumentException(found.size() + " markers of check " + checkId
                        + " match; narrow with fqn and line, the candidates are listed in markers");
            }
            ProjectGateway.Problem problem = found.get(0);
            Marker marker = problem.edtMarker;
            r.target = r.markers.get(0);

            IFile file = file(marker);
            r.file = file == null ? null : file.getProjectRelativePath().toString();
            r.requiresWorkbench = file != null && "bsl".equalsIgnoreCase(file.getFileExtension());
            String before = file == null ? null : read(file);
            FixProcessHandle handle = manager.prepareFix(marker, dtProject);
            try {
                List<FixVariantDescriptor> descriptors = new ArrayList<>(manager.getApplicableFixVariants(handle));
                for (int i = 0; i < descriptors.size(); i++) {
                    Variant variant = new Variant();
                    variant.index = i;
                    variant.description = descriptors.get(i).getDescription();
                    variant.details = descriptors.get(i).getDetails();
                    r.variants.add(variant);
                }
                if (descriptors.isEmpty()) {
                    r.ok = true;
                    r.message = "EDT offers no applicable fix for this marker. Change the code by hand.";
                    return r;
                }
                int chosen = variantIndex != null ? variantIndex : descriptors.size() == 1 ? 0 : -1;
                if (chosen < 0 || chosen >= descriptors.size()) {
                    if (!apply) {
                        r.ok = true;
                        r.message = "dry-run: " + descriptors.size() + " fix variants; pass variant to choose one";
                        return r;
                    }
                    throw new IllegalArgumentException("variant must be one of 0.." + (descriptors.size() - 1)
                            + "; the variants are listed in variants");
                }
                r.variant = chosen;
                boolean blocked = r.requiresWorkbench && !r.workbenchRunning;
                if (!apply) {
                    r.ok = true;
                    r.message = "dry-run: apply=true runs \"" + descriptors.get(chosen).getDescription()
                            + "\"" + (r.file == null ? "" : " on " + r.file)
                            + (blocked ? ". " + NO_WORKBENCH : "");
                    return r;
                }
                if (blocked) {
                    r.ok = false;
                    r.message = "Nothing changed. " + NO_WORKBENCH + " The fix would: "
                            + descriptors.get(chosen).getDescription() + ".";
                    return r;
                }
                manager.selectFixVariant(descriptors.get(chosen), handle);
                manager.executeFix(handle, new NullProgressMonitor());
                r.applied = true;
            } finally {
                manager.finishFix(handle);
            }

            if (file != null) {
                file.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
                r.change = TextChange.of(before, read(file));
            }
            ProjectGateway gateway = new ProjectGateway();
            var refresh = gateway.refreshScope(projectName, fqn, null, VALIDATION_WAIT_SECONDS);
            r.validationCompleted = refresh.validationCompleted;
            int remaining = 0;
            for (ProjectGateway.Problem after : gateway.reportProblems(projectName, fqn, null, null, false, 10_000).problems) {
                if (after.checkId == null || after.edtMarker == null) continue;
                CheckUid uid = byShortId.computeIfAbsent(after.checkId, id -> checkRepository.getUidForShortUid(id, project));
                if ((uid != null ? uids.contains(uid) : after.checkId.equalsIgnoreCase(checkId.trim()))
                        && after.line == problem.line && java.util.Objects.equals(after.resource, problem.resource)) {
                    remaining++;
                }
            }
            r.remaining = remaining;
            r.ok = true;
            r.message = remaining == 0
                    ? "fixed: the marker is gone after validation"
                    : "the fix ran, but " + remaining + " marker(s) of the check remain at the same place";
            if (r.change != null && !r.change.changed()) r.warning = "the file text did not change";
        } catch (Exception failure) {
            r.ok = false;
            if (!(failure instanceof IllegalArgumentException)) r.stack = frames(failure);
            r.message = failure instanceof IllegalArgumentException && failure.getMessage() != null
                    ? failure.getMessage() : GatewaySupport.describeCause(failure);
            if (String.valueOf(failure.getMessage()).contains("Workbench has not been created")) {
                r.message = "Nothing changed. " + NO_WORKBENCH;
            }
        }
        return r;
    }

    private static boolean workbenchRunning() {
        try {
            return org.eclipse.ui.PlatformUI.isWorkbenchRunning();
        } catch (Throwable noUi) {
            return false;
        }
    }

    private static List<String> frames(Throwable failure) {
        List<String> frames = new ArrayList<>();
        for (Throwable cause = failure; cause != null && frames.size() < 14; cause = cause.getCause()) {
            if (cause != failure) frames.add("caused by " + cause);
            StackTraceElement[] trace = cause.getStackTrace();
            for (int i = 0; i < Math.min(10, trace.length) && frames.size() < 14; i++) frames.add(trace[i].toString());
        }
        return frames;
    }

    private static Set<CheckUid> resolve(ICheckRepository checks, String id, IProject project) {
        Set<CheckUid> uids = new LinkedHashSet<>();
        CheckUid byShort = checks.getUidForShortUid(id, project);
        if (byShort != null) uids.add(byShort);
        String bare = id.contains(":") ? id.substring(id.lastIndexOf(':') + 1) : id;
        Set<CheckUid> byId = checks.getCheckUidForCheckId(bare, project);
        if (byId != null) {
            for (CheckUid uid : byId) {
                if (!id.contains(":") || id.equalsIgnoreCase(uid.getContributorId() + ":" + uid.getCheckId())) uids.add(uid);
            }
        }
        return uids;
    }

    private static Occurrence occurrence(ProjectGateway.Problem problem) {
        Occurrence o = new Occurrence();
        o.checkId = problem.checkId; o.message = problem.message; o.resource = problem.resource;
        o.location = problem.location; o.line = problem.line;
        return o;
    }

    private static IFile file(Marker marker) {
        IResource resource = ProjectGateway.traceMarkerFile(marker, FormWriteGateway.coreService(IResourceLookup.class),
                new HashMap<>());
        return resource instanceof IFile f ? f : null;
    }

    private static String read(IFile file) throws Exception {
        if (!file.exists()) return "";
        try (InputStream in = file.getContents(true)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /** A service of the check framework: through the wiring first, then the OSGi registry. */
    private static <T> T service(Class<T> type) {
        try {
            T viaWiring = ServiceAccess.get(type);
            if (viaWiring != null) return viaWiring;
        } catch (RuntimeException notWired) {
            // not published through the wiring - ask the registry
        }
        Bundle bundle = FrameworkUtil.getBundle(QuickFixGateway.class);
        BundleContext context = bundle == null ? null : bundle.getBundleContext();
        if (context == null) return null;
        ServiceReference<T> reference = context.getServiceReference(type);
        return reference == null ? null : context.getService(reference);
    }
}
