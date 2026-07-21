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
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.MarkerFilter;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Project-level reads: validation problems (Eclipse + EDT check markers) and the workspace project
 * list. Split out of the original model gateway to keep that file focused; behaviour is unchanged.
 */
public final class ProjectGateway {

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

    /** A filtered, counted view of the validation problems: the answer to "what is wrong with the
     *  module/object I just edited", without serialising the other thousands. */
    public static final class ProblemReport {
        public String project;          // echo of projectName (null = all open projects)
        public int total;               // problems matching the filters
        public int totalBeforeFilter;   // all problems collected (baseline for a before/after compare)
        public int errors;
        public int warnings;
        public int infos;
        public int eclipse;             // by source: standard Eclipse markers
        public int edtCheck;            // by source: EDT check markers
        public boolean countOnly;
        public boolean truncated;       // the returned list was capped at limit
        public int limit;
        public List<Problem> problems = new ArrayList<>();
    }

    /**
     * Filtered problems for a project. Additive over {@link #getProjectErrors}: narrow to one object
     * ({@code fqn}) or module ({@code modulePath}), to a {@code severity} (ERROR/WARNING/INFO), and/or
     * ask for {@code countOnly} (the counts, no list). The location filter matches a problem's
     * project-relative resource path, so it targets the Eclipse syntax/build markers precisely; an EDT
     * check marker is addressed by object presentation, so an {@code fqn} filter also matches its name
     * loosely, while {@code modulePath} (a file path) does not reach it. Everything is optional; with no
     * filter and {@code countOnly=false} the behaviour matches the old tool, but the list is capped at
     * {@code limit} to keep a large configuration's output bounded.
     */
    public ProblemReport reportProblems(String projectName, String fqn, String modulePath,
            String severity, boolean countOnly, int limit) throws CoreException {
        List<Problem> all = getProjectErrors(projectName);
        String pathPrefix = null;
        String nameToken = null;
        if (modulePath != null && !modulePath.isBlank()) {
            pathPrefix = modulePath.replace('\\', '/').trim();
        } else if (fqn != null && !fqn.isBlank()) {
            pathPrefix = GatewaySupport.objectFolderPrefix(fqn);
            nameToken = GatewaySupport.fqnNameToken(fqn);
        }
        String sevFilter = (severity != null && !severity.isBlank()) ? severity.trim().toUpperCase() : null;
        int cap = (limit > 0) ? limit : 1000;
        ProblemReport r = new ProblemReport();
        r.project = projectName;
        r.countOnly = countOnly;
        r.limit = cap;
        r.totalBeforeFilter = all.size();
        boolean locationFilter = pathPrefix != null || nameToken != null;
        for (Problem p : all) {
            if (sevFilter != null && !sevFilter.equalsIgnoreCase(p.severity)) {
                continue;
            }
            if (locationFilter && !locationMatches(p, pathPrefix, nameToken)) {
                continue;
            }
            r.total++;
            if ("ERROR".equalsIgnoreCase(p.severity)) {
                r.errors++;
            } else if ("WARNING".equalsIgnoreCase(p.severity)) {
                r.warnings++;
            } else {
                r.infos++;
            }
            if ("eclipse".equals(p.source)) {
                r.eclipse++;
            } else {
                r.edtCheck++;
            }
            if (!countOnly && r.problems.size() < cap) {
                r.problems.add(p);
            }
        }
        if (!countOnly && r.total > r.problems.size()) {
            r.truncated = true;
        }
        return r;
    }

    /** True when a problem's resource sits under the path prefix, or (for EDT-check markers named by
     *  object presentation rather than by path) names the object as a whole segment. */
    private static boolean locationMatches(Problem p, String pathPrefix, String nameToken) {
        String res = (p.resource == null) ? "" : p.resource.replace('\\', '/').toLowerCase();
        if (pathPrefix != null && !res.isEmpty() && res.startsWith(pathPrefix.toLowerCase())) {
            return true;
        }
        return nameToken != null && !nameToken.isBlank()
                && namesSegment(res, nameToken.toLowerCase());
    }

    /**
     * True when {@code name} appears in {@code res} as a WHOLE identifier segment, not as a substring.
     * An EDT-check marker names its object by presentation ("HTTPСервис.Payments.Модуль"), so the
     * match has to respect identifier boundaries: asking for Payments must not drag in the problems
     * of Payments_v2, which a plain "contains" did.
     */
    private static boolean namesSegment(String res, String name) {
        for (String segment : res.split("[^\\p{L}\\p{N}_]+")) {
            if (segment.equals(name)) {
                return true;
            }
        }
        return false;
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

    /** Outcome of {@link #cleanProject}. */
    public static final class CleanProjectResult {
        public boolean ok;
        public boolean applied;
        public String name;
        public boolean exists;
        public boolean open;
        public boolean rebuild;
        public boolean autoBuilding;
        public int problemsBefore = -1;
        public int problemsAfter = -1;
        public boolean settled;
        public long elapsedMs;
        public String plan;
        public String warning;
        public String message;
    }

    /**
     * Discard a project's build results and let them be recomputed - the programmatic equivalent of
     * EDT's "Clean" dialog. EDT hangs its checks off the build, so this is what makes validation run
     * again: a marker can otherwise survive long after the code that caused it was fixed, and reading
     * a stale marker is worse than reading none.
     *
     * <p>After building, marker counts are polled until they stop changing, so the caller gets numbers
     * that have settled rather than a snapshot taken mid-validation.
     *
     * @param projectName  project to clean
     * @param rebuild      also run a full build afterwards (what the Clean dialog does when auto-build
     *                     is off); {@code false} cleans only
     * @param waitSeconds  how long to wait for validation to settle (default 120)
     * @param apply        {@code false} reports the plan and the current problem count; {@code true}
     *                     performs the clean
     */
    public CleanProjectResult cleanProject(String projectName, boolean rebuild, int waitSeconds,
            boolean apply) {
        CleanProjectResult r = new CleanProjectResult();
        r.name = projectName;
        r.rebuild = rebuild;
        if (projectName == null || projectName.isBlank()) {
            r.message = "projectName is required";
            return r;
        }
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        r.exists = p.exists();
        r.open = r.exists && p.isOpen();
        if (!r.exists) {
            r.message = "project not found in the workspace: " + projectName;
            return r;
        }
        if (!r.open) {
            r.message = "project is closed: " + projectName;
            return r;
        }
        r.autoBuilding = ResourcesPlugin.getWorkspace().isAutoBuilding();
        r.problemsBefore = countProblems(projectName);
        r.ok = true;
        r.plan = "Clean project \"" + projectName + "\""
                + (rebuild ? " and run a full build" : "")
                + " (auto-build is " + (r.autoBuilding ? "on" : "off")
                + "; " + r.problemsBefore + " problem(s) reported now)";
        if (!rebuild && !r.autoBuilding) {
            r.warning = "auto-build is off and rebuild=false - the project would be left unbuilt, so "
                    + "validation would report nothing at all. Pass rebuild=true.";
        }
        if (!apply) {
            return r;
        }

        long started = System.currentTimeMillis();
        org.eclipse.core.runtime.IProgressMonitor monitor =
                new org.eclipse.core.runtime.NullProgressMonitor();
        try {
            p.build(org.eclipse.core.resources.IncrementalProjectBuilder.CLEAN_BUILD, monitor);
            if (rebuild) {
                p.build(org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, monitor);
            }
            org.eclipse.core.runtime.jobs.Job.getJobManager()
                    .join(ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor);
            org.eclipse.core.runtime.jobs.Job.getJobManager()
                    .join(ResourcesPlugin.FAMILY_AUTO_BUILD, monitor);
        } catch (CoreException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            r.message = "clean failed: " + GatewaySupport.describeCause(e);
            r.elapsedMs = System.currentTimeMillis() - started;
            return r;
        }

        // Validation keeps running after the build returns, so wait for the count to stop moving
        // rather than reporting a number caught mid-flight.
        int limit = (waitSeconds > 0 ? waitSeconds : 120) * 1000;
        int previous = Integer.MIN_VALUE;
        int stable = 0;
        while (System.currentTimeMillis() - started < limit) {
            int now = countProblems(projectName);
            stable = (now == previous) ? stable + 1 : 0;
            previous = now;
            if (stable >= 3) {
                r.settled = true;
                break;
            }
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        r.problemsAfter = previous;
        r.elapsedMs = System.currentTimeMillis() - started;
        r.applied = true;
        r.message = "cleaned " + projectName + (rebuild ? " and rebuilt" : "")
                + "; problems " + r.problemsBefore + " -> " + r.problemsAfter
                + (r.settled ? " (settled)" : " - WARNING: still changing when the wait ran out, "
                        + "re-read edt_project_errors in a moment");
        return r;
    }

    /** Problem count for a project, or -1 when it cannot be read. */
    private int countProblems(String projectName) {
        try {
            return getProjectErrors(projectName).size();
        } catch (CoreException | RuntimeException e) {
            return -1;
        }
    }

    /** Outcome of {@link #deleteProject}. */
    public static final class DeleteProjectResult {
        public boolean ok;
        public boolean applied;
        public String name;
        public boolean exists;
        public boolean open;
        public boolean contentOnDisk;
        public boolean deleteContent;
        public String location;
        public int fileCount = -1;
        public String plan;
        public String warning;
        public String message;
    }

    /**
     * Remove a project from the workspace, completing the create/work/delete cycle that
     * {@code edt_create_extension} and {@code edt_create_external_object} start.
     *
     * <p>Deletion goes through the Eclipse workspace rather than the file system on purpose: the
     * workspace updates its own resource tree, so the project does not come back from the tree
     * snapshot on the next start. Removing a project's folder by hand leaves exactly that ghost -
     * a registered, contentless project whose name stays taken.
     *
     * <p>Destructive, so it follows the same rule as the other breaking tools: dry-run by default and
     * {@code force} required for the actual delete.
     *
     * @param projectName   project to remove
     * @param deleteContent also erase its files from disk; {@code false} unregisters and leaves them
     * @param force         explicit override, required for {@code apply}
     * @param apply         {@code false} reports the plan; {@code true} performs the delete
     */
    public DeleteProjectResult deleteProject(String projectName, boolean deleteContent, boolean force,
            boolean apply) {
        DeleteProjectResult r = new DeleteProjectResult();
        r.name = projectName;
        r.deleteContent = deleteContent;
        if (projectName == null || projectName.isBlank()) {
            r.message = "projectName is required";
            return r;
        }
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        r.exists = p.exists();
        if (!r.exists) {
            r.message = "project not found in the workspace: " + projectName;
            return r;
        }
        r.open = p.isOpen();
        java.io.File dir = (p.getLocation() == null) ? null : p.getLocation().toFile();
        r.location = (dir == null) ? null : dir.toString();
        r.contentOnDisk = dir != null && dir.isDirectory();
        if (r.contentOnDisk) {
            r.fileCount = countFiles(dir);
        }
        r.ok = true;
        r.plan = "Remove project \"" + projectName + "\" from the workspace"
                + (deleteContent
                        ? " AND delete its files from disk"
                                + (r.fileCount >= 0 ? " (" + r.fileCount + " file(s))" : "")
                        : " (files on disk are kept)")
                + (r.location != null ? " [" + r.location + "]" : "");
        if (!r.contentOnDisk) {
            r.warning = "the project is registered but has no folder on disk - a ghost left by a manual "
                    + "folder removal; deleting it here frees the name.";
        } else if (deleteContent) {
            r.warning = "deleting the content is irreversible - the files are erased, not moved to trash.";
        }

        if (!apply) {
            return r;
        }
        if (!force) {
            r.message = "deleting a project is irreversible - apply refused; pass force=true to perform it.";
            return r;
        }
        try {
            // force=true: delete even when the workspace is out of sync with disk, which is exactly
            // the ghost case (registered project, missing folder).
            p.delete(deleteContent, true, new org.eclipse.core.runtime.NullProgressMonitor());
            // Persist the removal right away. The workspace keeps its resource tree in memory and only
            // snapshots it periodically, so an EDT that is stopped hard before the next snapshot would
            // replay the older tree on startup - and the project would come back as a ghost, which is
            // exactly what this tool exists to avoid.
            String persisted = snapshotWorkspace();
            r.applied = true;
            r.message = "removed project " + projectName
                    + (deleteContent ? " and deleted its files" : " (files kept on disk)")
                    + (persisted == null ? "" : " - WARNING: " + persisted);
        } catch (CoreException e) {
            r.applied = false;
            r.message = "delete failed: " + GatewaySupport.describeCause(e);
        }
        return r;
    }

    /**
     * Snapshot the workspace so structural changes survive a hard stop. Returns {@code null} on
     * success, otherwise a short reason worth reporting alongside an otherwise successful delete.
     */
    private static String snapshotWorkspace() {
        try {
            ResourcesPlugin.getWorkspace().save(false, new org.eclipse.core.runtime.NullProgressMonitor());
            return null;
        } catch (CoreException | RuntimeException e) {
            return "the workspace could not be snapshotted (" + GatewaySupport.describeCause(e)
                    + "); the project may reappear if EDT is stopped hard before its next snapshot";
        }
    }

    /** Files under a directory, counted recursively; -1 when it cannot be walked. */
    private static int countFiles(java.io.File dir) {
        java.io.File[] children = dir.listFiles();
        if (children == null) {
            return -1;
        }
        int total = 0;
        for (java.io.File child : children) {
            if (child.isDirectory()) {
                int nested = countFiles(child);
                if (nested > 0) {
                    total += nested;
                }
            } else {
                total++;
            }
        }
        return total;
    }
}
