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
}
