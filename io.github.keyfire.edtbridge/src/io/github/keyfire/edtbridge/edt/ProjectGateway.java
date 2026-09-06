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

import io.github.keyfire.edtbridge.core.MarkerFreshness;
import io.github.keyfire.edtbridge.core.MetadataPaths;
import io.github.keyfire.edtbridge.core.ProblemFilter;
import io.github.keyfire.edtbridge.core.ValidationSettle;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerFilter;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com._1c.g5.v8.dt.validation.marker.PlainEObjectMarker;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
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
        public String sourceType;  // which validation produced it - tells a standards check from EDT's own
        public java.util.Map<String, String> extraInfo; // whatever the check attached, e.g. its own uid
        public String edtSeverity; // EDT grade for edt-check: BLOCKER/CRITICAL/MAJOR/MINOR/TRIVIAL
        public String location; // EDT location, e.g. "строка 8" or a field presentation
        /** The marker predates the last change of its file on disk - see MarkerFreshness. */
        public boolean stale;
        /** The file changed on disk and the workspace has not read the change yet. */
        public boolean unsynchronized;
        /** When the marker was created, epoch ms; 0 when the marker does not say. */
        public long markerCreatedAt;
        /** The last write of the file on disk, epoch ms; 0 when the file was not found. */
        public long fileModifiedAt;
        /** Project-relative path of the file behind an EDT check marker, once traced; else null. */
        public String filePath;
        /** The workspace resource the problem sits on, when known - what the freshness check reads. */
        IResource file;
        /** The EDT check marker behind an edt-check problem, kept to trace its file on demand. */
        Marker edtMarker;
    }

    /** The projects a validation call addresses: the named one when open, else every open project. */
    private static List<IProject> selectProjects(String projectName) {
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
        return projects;
    }

    public List<Problem> getProjectErrors(String projectName) throws CoreException {
        List<IProject> projects = selectProjects(projectName);

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
                    pr.markerCreatedAt = m.getCreationTime();
                } catch (CoreException e) {
                    pr.markerCreatedAt = 0;
                }
                pr.file = r;
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
        // Disk folder behind each validated project. The model validates the REGISTERED
        // folder, and a caller editing a parallel checkout of the same sources gets a
        // plausible-looking report about the WRONG tree - silently, since the file names
        // match. Naming the folders lets the caller see the divergence.
        public java.util.Map<String, String> locations = new java.util.LinkedHashMap<>();
        /** Listed problems whose marker predates the last change of their file; -1 when nothing
         *  was listed to judge (countOnly). */
        public int staleCount = -1;
        /** Files in scope the workspace has not read since their last write on disk - the first
         *  few; unsynchronizedCount says how many there are. */
        public List<String> unsynchronized = new ArrayList<>();
        public int unsynchronizedCount;
        /** What to do about the stale and unsynchronized findings; null when there are none. */
        public String hint;
        /** The refresh that preceded this report, when the caller asked for one. */
        public RefreshResult refreshed;
    }

    /**
     * Filtered problems for a project. Additive over {@link #getProjectErrors}: narrow to one object
     * ({@code fqn}) or module ({@code modulePath}), to one or SEVERAL severities
     * ({@code severity}: "ERROR", "ERROR,WARNING", ...), and/or
     * ask for {@code countOnly} (the counts, no list). The location filter matches a problem's
     * project-relative resource path, so it targets the Eclipse syntax/build markers precisely; an EDT
     * check marker is addressed by object presentation, so an {@code fqn} filter also matches its name
     * loosely, while {@code modulePath} (a file path) does not reach it. Everything is optional; with no
     * filter and {@code countOnly=false} the behaviour matches the old tool, but the list is capped at
     * {@code limit} to keep a large configuration's output bounded.
     */
    public ProblemReport reportProblems(String projectName, String fqn, String modulePath,
            String severity, boolean countOnly, int limit) throws CoreException {
        return reportProblems(projectName, fqn, modulePath, severity, countOnly, limit, null);
    }

    /**
     * As above, for a report taken right after {@link #refreshScope}: the refresh is echoed in the
     * report, and a hint still due points at the full clean instead of another refresh.
     */
    public ProblemReport reportProblems(String projectName, String fqn, String modulePath,
            String severity, boolean countOnly, int limit, RefreshResult refreshed)
            throws CoreException {
        List<Problem> all = getProjectErrors(projectName);
        List<IProject> projects = selectProjects(projectName);
        java.util.Map<String, String> locations = new java.util.LinkedHashMap<>();
        for (IProject p : projects) {
            locations.put(p.getName(),
                    p.getLocation() == null ? null : p.getLocation().toOSString());
        }
        String pathPrefix = locationPrefix(fqn, modulePath);
        String nameToken = locationName(fqn, modulePath);
        java.util.Set<String> sevFilter = ProblemFilter.severities(severity);
        int cap = (limit > 0) ? limit : 1000;
        ProblemReport r = new ProblemReport();
        r.project = projectName;
        r.countOnly = countOnly;
        r.limit = cap;
        r.totalBeforeFilter = all.size();
        r.locations = locations;
        boolean locationFilter = pathPrefix != null || nameToken != null;
        for (Problem p : all) {
            if (!ProblemFilter.matchesSeverity(p.severity, sevFilter)) {
                continue;
            }
            if (locationFilter && !ProblemFilter.matchesLocation(p.resource, pathPrefix, nameToken)) {
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
        r.refreshed = refreshed;
        // Every listed problem is judged against its file, and the scope is checked for files
        // the workspace has not read: a marker outliving its cause is the defect this report
        // exists to expose, and a file holding problems nothing reports yet is its twin.
        if (!countOnly) {
            judgeFreshness(r.problems);
            r.staleCount = 0;
            for (Problem p : r.problems) {
                if (p.stale) {
                    r.staleCount++;
                }
            }
        }
        boolean several = projects.size() > 1;
        java.util.Set<String> unseen = new java.util.LinkedHashSet<>();
        int unseenCount = 0;
        for (IProject p : projects) {
            UnseenFiles files = unseenFiles(p, pathPrefix, nameToken);
            unseenCount += files.count;
            for (String path : files.paths) {
                unseen.add(several ? p.getName() + "/" + path : path);
            }
        }
        // A problem's file may lie outside the scope walked above - an EDT check marker matched
        // by object name sits wherever its object does - so the listed verdicts are added too.
        for (Problem p : r.problems) {
            if (p.unsynchronized && p.file != null) {
                String path = p.file.getProjectRelativePath().toString();
                if (unseen.add(several ? p.project + "/" + path : path)) {
                    unseenCount++;
                }
            }
        }
        r.unsynchronizedCount = unseenCount;
        for (String path : unseen) {
            if (r.unsynchronized.size() >= UNSYNCHRONIZED_LIST_CAP) {
                break;
            }
            r.unsynchronized.add(path);
        }
        r.hint = MarkerFreshness.hint(Math.max(r.staleCount, 0), r.unsynchronizedCount,
                refreshed != null);
        return r;
    }

    /** How many unsynchronized paths a report lists; the count says the rest. */
    private static final int UNSYNCHRONIZED_LIST_CAP = 50;

    /**
     * The project-relative path prefix a narrowing addresses: the module path as given, or the
     * object's source folder; null when nothing narrows or the FQN has an unknown shape.
     */
    private static String locationPrefix(String fqn, String modulePath) {
        if (modulePath != null && !modulePath.isBlank()) {
            return modulePath.replace('\\', '/').trim();
        }
        if (fqn != null && !fqn.isBlank()) {
            String folder = MetadataPaths.objectFolder(fqn);
            return (folder == null) ? null : "src/" + folder;
        }
        return null;
    }

    /** The object name an fqn narrowing also matches by presentation; null for a path narrowing. */
    private static String locationName(String fqn, String modulePath) {
        if (modulePath != null && !modulePath.isBlank()) {
            return null;
        }
        return (fqn != null && !fqn.isBlank()) ? MetadataPaths.nameToken(fqn) : null;
    }

    /**
     * Judge the listed problems against their files. Only the listed ones: for an Eclipse marker
     * the check is a stat of its file, but an EDT check marker sits on an OBJECT and must first
     * be traced to a file, and tracing the thousands the filter dropped would cost more than the
     * report is worth. So the stale count covers what the caller reads - which is what the
     * caller acts on.
     */
    private static void judgeFreshness(List<Problem> listed) {
        if (listed.isEmpty()) {
            return;
        }
        IResourceLookup lookup = FormWriteGateway.coreService(IResourceLookup.class);
        java.util.Map<Object, IResource> byObject = new java.util.HashMap<>();
        java.util.Map<IResource, long[]> stamps = new java.util.HashMap<>();
        for (Problem p : listed) {
            IResource res = p.file;
            if (res == null && p.edtMarker != null) {
                res = traceMarkerFile(p.edtMarker, lookup, byObject);
                p.file = res;
                if (res != null) {
                    p.filePath = res.getProjectRelativePath().toString();
                }
            }
            if (res == null) {
                continue;
            }
            long[] stamp = stamps.computeIfAbsent(res, file -> new long[] {
                diskStamp(file), file.isSynchronized(IResource.DEPTH_ZERO) ? 1L : 0L });
            MarkerFreshness.Verdict verdict =
                    MarkerFreshness.judge(p.markerCreatedAt, stamp[0], stamp[1] == 1L);
            p.stale = verdict.stale;
            p.unsynchronized = verdict.unsynchronized;
            p.fileModifiedAt = stamp[0];
        }
    }

    /**
     * The file behind an EDT check marker. A marker on a plain EObject carries the object's URI,
     * and a platform URI names the file outright - no object is loaded for it. A marker on a BM
     * object is asked for its object, and the object for its file. Cached by object id, since
     * one object usually carries several problems. Null when nothing traces.
     */
    private static IResource traceMarkerFile(Marker mk, IResourceLookup lookup,
            java.util.Map<Object, IResource> byObject) {
        Object key = null;
        try {
            key = mk.getMarkerObjectId();
        } catch (RuntimeException ignored) {
            // no id - no cache
        }
        if (key != null && byObject.containsKey(key)) {
            return byObject.get(key);
        }
        IResource res = null;
        try {
            if (mk instanceof PlainEObjectMarker) {
                URI uri = ((PlainEObjectMarker) mk).getURI();
                if (uri != null && uri.isPlatformResource()) {
                    res = ResourcesPlugin.getWorkspace().getRoot().findMember(
                            new org.eclipse.core.runtime.Path(uri.toPlatformString(true)));
                } else if (uri != null && lookup != null) {
                    res = lookup.getPlatformResource(uri);
                }
            }
            if (res == null && lookup != null) {
                java.util.function.Function<EObject, IFile> toFile =
                        object -> (object == null) ? null : lookup.getPlatformResource(object);
                res = mk.provideObject(toFile);
            }
        } catch (Throwable t) {
            // an object the model no longer has, or a store without the API: unjudged, not broken
            res = null;
        }
        if (key != null) {
            byObject.put(key, res);
        }
        return res;
    }

    /** The file's last write on disk, epoch ms; 0 when it is not there or has no location. */
    private static long diskStamp(IResource res) {
        return (res.getLocation() == null) ? 0L : res.getLocation().toFile().lastModified();
    }

    /**
     * The workspace resource a narrowing addresses: the file or folder at the path when the
     * workspace knows it, else the nearest ancestor it does know - a file new on disk has no
     * resource yet, and its parent is where the workspace will notice it - and the project
     * itself when nothing narrows.
     */
    private static IResource scopeResource(IProject p, String pathPrefix) {
        if (pathPrefix == null || pathPrefix.isBlank()) {
            return p;
        }
        IPath path = new org.eclipse.core.runtime.Path(pathPrefix.replace('\\', '/').trim());
        while (path.segmentCount() > 0) {
            IResource member = p.findMember(path);
            if (member != null) {
                return member;
            }
            path = path.removeLastSegments(1);
        }
        return p;
    }

    /**
     * Resources the synchronization check and the refresh leave alone: the version control
     * folders, which change on every git operation and are validated by nobody, and whatever
     * the workspace itself marks as derived, hidden or team-private.
     */
    private static boolean leftAlone(IResource res) {
        String name = res.getName();
        return res.isDerived() || res.isHidden() || res.isTeamPrivateMember()
                || ".git".equals(name) || ".svn".equals(name) || ".hg".equals(name);
    }

    /** Files the workspace has not read, under one scope: the first few and the full count. */
    private static final class UnseenFiles {
        final List<String> paths = new ArrayList<>();
        int count;

        void note(String path, String pathPrefix, String nameToken) {
            if ((pathPrefix != null || nameToken != null)
                    && !ProblemFilter.matchesLocation(path, pathPrefix, nameToken)) {
                return;
            }
            count++;
            if (paths.size() < UNSYNCHRONIZED_LIST_CAP) {
                paths.add(path);
            }
        }
    }

    /**
     * Files under the scope whose last write the workspace has not read: changed, deleted, or new
     * on disk. With nothing to narrow, the scope is what validation reads - the sources folder.
     * Each root is first asked as a whole - Eclipse walks it in one pass and stops at the first
     * mismatch - so a tree that is in step costs one walk and no list; only a root that is off is
     * walked file by file. A file new on disk has no resource to visit: it shows as a name its
     * folder lists on disk and the workspace does not.
     */
    private static UnseenFiles unseenFiles(IProject p, String pathPrefix, String nameToken)
            throws CoreException {
        UnseenFiles found = new UnseenFiles();
        IResource scope = scopeResource(p, pathPrefix);
        List<IResource> roots = (scope == p) ? validatedRoots(p) : List.of(scope);
        for (IResource root : roots) {
            if (root.isSynchronized(IResource.DEPTH_INFINITE)) {
                continue;
            }
            root.accept(res -> {
                if (res != root && leftAlone(res)) {
                    return false;
                }
                if (res.getType() == IResource.FILE) {
                    if (!res.isSynchronized(IResource.DEPTH_ZERO)) {
                        found.note(res.getProjectRelativePath().toString(), pathPrefix, nameToken);
                    }
                    return false;
                }
                noteNewOnDisk((IContainer) res, found, pathPrefix, nameToken);
                return true;
            });
        }
        return found;
    }

    /**
     * What validation reads of a whole project: its sources folder. The rest of the tree has no
     * marker to go stale - the build output of an external object project (bin/, rewritten by EDT
     * around the workspace on every dump) was reported as unsynchronized before this, and the
     * hint then told the caller to revalidate a file nobody validates. A project laid out without
     * a sources folder falls back to every member that is not left alone.
     */
    private static List<IResource> validatedRoots(IProject p) throws CoreException {
        IResource sources = p.findMember("src");
        if (sources != null) {
            return List.of(sources);
        }
        List<IResource> roots = new ArrayList<>();
        for (IResource member : p.members()) {
            if (!leftAlone(member)) {
                roots.add(member);
            }
        }
        return roots;
    }

    /** Names a folder lists on disk and the workspace has no resource for: files new on disk. */
    private static void noteNewOnDisk(IContainer folder, UnseenFiles found, String pathPrefix,
            String nameToken) {
        if (folder.getLocation() == null) {
            return;
        }
        String[] onDisk = folder.getLocation().toFile().list();
        if (onDisk == null) {
            return;
        }
        for (String name : onDisk) {
            if (folder.findMember(name) == null && !".git".equals(name) && !".svn".equals(name)
                    && !".hg".equals(name)) {
                found.note(folder.getProjectRelativePath().append(name).toString() + " (new on disk)",
                        pathPrefix, nameToken);
            }
        }
    }

    /** Outcome of {@link #refreshScope}: what was re-read from disk and what the rebuild cost. */
    public static final class RefreshResult {
        /** What was refreshed: a project-relative path, or the project name for a whole project. */
        public List<String> resources = new ArrayList<>();
        public long refreshMs;
        public long buildMs;
        public long waitMs;
        /** Whether the problem count settled within the wait - see ValidationSettle. */
        public boolean settled;
        /** Whether validation was seen running at all while waiting. */
        public boolean sawValidation;
        public String warning;
    }

    /**
     * Re-read the narrowed scope from disk and validate it in place: the point fix for a stale
     * marker. {@code refreshLocal} tells the workspace about the files that changed under it, an
     * INCREMENTAL build lets EDT's builders process exactly those, and the wait is the one that
     * follows a clean - the checks run after the build, not in it. Seconds for one module, where
     * {@link #cleanProject} rebuilds everything for minutes. With nothing to narrow, the project's
     * sources folder is refreshed - what validation reads.
     *
     * <p>A project with no build state yet - never built in this session - gets a full build from
     * the same call: that is how Eclipse answers an incremental request it cannot honour.
     *
     * @param projectName project, or null for every open project
     * @param fqn         object narrowing, as for {@link #reportProblems}
     * @param modulePath  path narrowing, as for {@link #reportProblems}
     * @param waitSeconds how long to wait for validation to settle after the build
     */
    public RefreshResult refreshScope(String projectName, String fqn, String modulePath,
            int waitSeconds) throws CoreException {
        RefreshResult r = new RefreshResult();
        List<IProject> projects = selectProjects(projectName);
        String pathPrefix = locationPrefix(fqn, modulePath);
        IProgressMonitor monitor = new NullProgressMonitor();
        boolean several = projects.size() > 1;
        long started = System.currentTimeMillis();
        for (IProject p : projects) {
            IResource scope = scopeResource(p, pathPrefix);
            if (scope == p) {
                // Depth one first, so a sources folder new at the root is discovered; then what
                // validation reads, in full - the same roots the synchronization check walks.
                p.refreshLocal(IResource.DEPTH_ONE, monitor);
                for (IResource root : validatedRoots(p)) {
                    root.refreshLocal(IResource.DEPTH_INFINITE, monitor);
                }
                r.resources.add(p.getName());
            } else {
                scope.refreshLocal(IResource.DEPTH_INFINITE, monitor);
                String path = scope.getProjectRelativePath().toString();
                r.resources.add(several ? p.getName() + "/" + path : path);
            }
        }
        r.refreshMs = System.currentTimeMillis() - started;
        long building = System.currentTimeMillis();
        try {
            for (IProject p : projects) {
                p.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
            }
            Job.getJobManager().join(ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor);
            Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, monitor);
        } catch (CoreException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            r.warning = "build failed: " + GatewaySupport.describeCause(e);
        }
        r.buildMs = System.currentTimeMillis() - building;
        ValidationSettle settle = new ValidationSettle();
        long waiting = System.currentTimeMillis();
        r.settled = awaitSettle(projectName, settle, (waitSeconds > 0 ? waitSeconds : 120) * 1000L, 1000L);
        r.waitMs = System.currentTimeMillis() - waiting;
        r.sawValidation = settle.sawWork();
        if (!r.settled) {
            r.warning = (r.warning == null ? "" : r.warning + "; ")
                    + "validation was still running when the wait ran out - re-read in a moment";
        }
        return r;
    }

    /**
     * Poll the problem count until it settles or the time runs out; true when it settled. See
     * {@link ValidationSettle} for why a count that merely stopped changing is not enough.
     */
    private boolean awaitSettle(String projectName, ValidationSettle settle, long limitMs,
            long pollMs) {
        long started = System.currentTimeMillis();
        while (System.currentTimeMillis() - started < limitMs) {
            boolean idle = Job.getJobManager().isIdle();
            if (settle.poll(countProblems(projectName), idle)) {
                return true;
            }
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
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
                pr.sourceType = mk.getSourceType();
                // Not every check id is documented: some markers carry a short code (SU200) that no
                // bundle resource knows, so whatever the check attached itself is the only other
                // handle on "which rule is this".
                try {
                    com._1c.g5.v8.dt.validation.marker.IExtraInfoMap extra = mk.getExtraInfo();
                    if (extra != null && !extra.isEmpty()) {
                        java.util.Map<String, String> copy = new java.util.LinkedHashMap<>();
                        extra.forEach((k, v) -> copy.put(String.valueOf(k), String.valueOf(v)));
                        pr.extraInfo = copy;
                    }
                } catch (Throwable noExtra) {
                    // older marker store - nothing to add
                }
                pr.location = mk.getLocation();
                pr.resource = mk.getObjectPresentation();
                pr.line = parseLine(mk.getLocation());
                try {
                    pr.markerCreatedAt = mk.getCreatedAt();
                } catch (Throwable noTime) {
                    // older marker store - judged by synchronization alone
                    pr.markerCreatedAt = 0;
                }
                pr.edtMarker = mk;
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
        /** Whether validation was seen running at all while waiting - see ValidationSettle. */
        public boolean sawValidation;
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

        // Validation keeps running after the build returns - and it is NOT part of the build job
        // families joined above, so a count that merely stopped changing proves nothing: right after
        // a clean it sits at zero because nothing has been reported yet. Waiting for the workspace to
        // go idle as well is what tells "not started" apart from "done".
        long limit = (waitSeconds > 0 ? waitSeconds : 120) * 1000L;
        ValidationSettle settle = new ValidationSettle();
        r.settled = awaitSettle(projectName, settle, limit, 2000L);
        r.sawValidation = settle.sawWork();
        r.problemsAfter = settle.problems();
        r.elapsedMs = System.currentTimeMillis() - started;
        r.applied = true;
        r.message = "cleaned " + projectName + (rebuild ? " and rebuilt" : "")
                + "; problems " + r.problemsBefore + " -> " + r.problemsAfter
                + (r.settled
                        ? (r.sawValidation ? " (settled)"
                                : " (settled, but validation was never seen running - if the count "
                                        + "looks too good, re-read edt_project_errors in a moment)")
                        : " - WARNING: still changing when the wait ran out, "
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

    /** Outcome of registering an existing project directory in the workspace. */
    public static final class ImportProjectResult {
        public boolean ok;
        public boolean applied;
        public String name;
        public String declaredName;
        public String location;
        public boolean directory;
        public boolean descriptor;
        public boolean already;
        public boolean nameTaken;
        public boolean insideWorkspace;
        public boolean open;
        public String plan;
        public String warning;
        public String message;
    }

    /**
     * Register an existing project directory in the workspace - "Import existing project" without the
     * dialog, the one step of the create/work/delete cycle that had no programmatic form.
     *
     * <p>Why it is needed: an extension in "modification and control" mode is validated against a BASE
     * project that must sit on the target release. The checkout at hand is usually on some other
     * branch, so the second project comes from a worktree - and adding it was a GUI-only action, which
     * stopped the work dead.
     *
     * <p>The name comes from the directory's own {@code .project} unless the caller overrides it: two
     * checkouts of one repository declare the SAME name, and the workspace allows a name once, so the
     * override is what makes the second one importable at all.
     *
     * @param path      directory holding a {@code .project} file
     * @param name      name to register it under; {@code null} keeps the declared one
     * @param apply     {@code false} reports the plan; {@code true} registers and opens the project
     */
    public ImportProjectResult importProject(String path, String name, boolean apply) {
        ImportProjectResult r = new ImportProjectResult();
        r.location = path;
        if (path == null || path.isBlank()) {
            r.message = "path is required (the directory holding the project)";
            return r;
        }
        java.nio.file.Path dir;
        try {
            dir = java.nio.file.Path.of(path).toAbsolutePath().normalize();
        } catch (RuntimeException badPath) {
            r.message = "path is not a valid file path: " + path;
            return r;
        }
        r.location = dir.toString();
        r.directory = java.nio.file.Files.isDirectory(dir);
        if (!r.directory) {
            r.message = "not a directory: " + r.location;
            return r;
        }
        java.nio.file.Path descriptorFile = dir.resolve(".project");
        r.descriptor = java.nio.file.Files.isRegularFile(descriptorFile);
        if (!r.descriptor) {
            r.message = "no .project file in " + r.location
                    + " - an existing project is imported by its descriptor; this directory holds none.";
            return r;
        }
        org.eclipse.core.resources.IProjectDescription description;
        try {
            description = ResourcesPlugin.getWorkspace().loadProjectDescription(
                    new org.eclipse.core.runtime.Path(descriptorFile.toString()));
        } catch (CoreException | RuntimeException e) {
            r.message = "the .project file could not be read: " + GatewaySupport.describeCause(e);
            return r;
        }
        r.declaredName = description.getName();
        r.name = (name != null && !name.isBlank()) ? name.trim() : r.declaredName;
        if (r.name == null || r.name.isBlank()) {
            r.message = "the .project file declares no name - pass name explicitly";
            return r;
        }
        description.setName(r.name);
        // A directory that already lies inside the workspace root is registered by name alone: setting
        // an explicit location for it is what Eclipse rejects as "overlaps the workspace location".
        java.nio.file.Path workspaceRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation() == null
                ? null
                : java.nio.file.Path.of(
                        ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString());
        r.insideWorkspace = workspaceRoot != null && dir.getParent() != null
                && dir.getParent().equals(workspaceRoot);
        if (!r.insideWorkspace) {
            description.setLocation(new org.eclipse.core.runtime.Path(dir.toString()));
        }

        IProject existing = ResourcesPlugin.getWorkspace().getRoot().getProject(r.name);
        if (existing.exists()) {
            java.io.File where = (existing.getLocation() == null) ? null : existing.getLocation().toFile();
            if (where != null && where.toPath().toAbsolutePath().normalize().equals(dir)) {
                r.ok = true;
                r.already = true;
                r.open = existing.isOpen();
                r.message = "this directory is already imported as \"" + r.name + "\""
                        + (r.open ? "" : " (closed - open it in EDT or reopen the workspace)");
                return r;
            }
            r.nameTaken = true;
            r.message = "the workspace already has a project named \"" + r.name + "\""
                    + (where == null ? "" : " at " + where)
                    + " - pass another name to import this directory beside it.";
            return r;
        }

        r.ok = true;
        r.plan = "Register " + r.location + " as project \"" + r.name + "\""
                + (r.name.equals(r.declaredName) ? "" : " (its .project declares \"" + r.declaredName + "\")")
                + " and open it";
        if (!r.name.equals(r.declaredName)) {
            r.warning = "the project is registered under a name of its own while the .project on disk "
                    + "keeps the declared one - that is how two checkouts of one repository live side "
                    + "by side, and nothing on disk is rewritten.";
        }
        if (!apply) {
            return r;
        }
        try {
            org.eclipse.core.runtime.IProgressMonitor monitor =
                    new org.eclipse.core.runtime.NullProgressMonitor();
            existing.create(description, monitor);
            existing.open(monitor);
            r.open = existing.isOpen();
            r.applied = true;
            String persisted = snapshotWorkspace();
            r.message = "imported " + r.location + " as \"" + r.name + "\""
                    + " - validation of a freshly imported project takes a while; read"
                    + " edt_project_errors after edt_clean_project rather than right away"
                    + (persisted == null ? "" : " - WARNING: " + persisted);
        } catch (CoreException | RuntimeException e) {
            r.ok = false;
            r.message = "import failed: " + GatewaySupport.describeCause(e);
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
