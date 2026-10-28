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
package io.github.keyfire.edtbridge.tools;

import io.github.keyfire.edtbridge.edt.ProjectGateway;
import io.github.keyfire.edtbridge.core.ProblemBaselines;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_project_errors - list EDT validation problems for a project (or all open
 * projects) from the live workspace. The M0 proof tool: plugin in EDT -> MCP -> EDT state.
 */
public final class ProjectErrorsTool {

    /** How long a refresh waits for validation to settle before the markers are read. */
    private static final int REFRESH_WAIT_SECONDS = 120;
    private static final int BASELINE_MARKER_LIMIT = 100_000;
    private static final ProblemBaselines BASELINES = new ProblemBaselines(16, 200_000);

    private final ProjectGateway gateway = new ProjectGateway();

    public String name() {
        return "edt_project_errors";
    }

    /** MCP tool descriptor for tools/list. */
    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name; omit for all open projects"));
        props.add("fqn", strProp("Narrow to one object: CommonModule.X, Catalog.Y, HTTPService.S, or a form "
                + "FQN. Matches the object's source path (and, for EDT-check markers, its name). Optional."));
        props.add("modulePath", strProp("Narrow to one module by project-relative path, e.g. "
                + "src/CommonModules/X/Module.bsl, or a folder prefix. Includes EDT check markers traced to files; unresolved markers set complete=false and a hint. "
                + "Optional."));
        props.add("waitSeconds", intProp("Validation wait after refresh=true or a baseline operation, in seconds (1-600, default 120). "
                + "A timeout still returns the current markers with complete=false and refreshed.settled=false."));
        props.add("baseline", strProp("Optional: save captures all matching problems, compare returns only "
                + "new problems and resolvedProblems relative to baselineId. Snapshots last for this EDT session; "
                + "project location and filters must match. Both operations refresh and wait for scoped validation to settle. Incomplete snapshots are refused."));
        props.add("baselineId", strProp("Snapshot id returned by baseline=save; required for baseline=compare."));
        props.add("severity", strProp("Keep these severities: ERROR, WARNING or INFO, one or several "
                + "separated by commas (\"ERROR,WARNING\"), case-insensitive. A generated-code check wants both: EDT reports a call to a method nobody declares as a WARNING, so ERROR alone reads clean over broken code. Optional - omit for every severity."));
        props.add("countOnly", boolProp("true = return only the counts (total, by severity, by source), no "
                + "problem list – what a before/after baseline needs on a large configuration. The listed "
                + "problems are what gets judged for staleness, so there is no staleCount here; the "
                + "unsynchronized files are still named. Default false."));
        props.add("limit", intProp("Max problems in the returned list (default 1000); ignored when countOnly. "
                + "Excess sets truncated=true."));
        props.add("brief", boolProp("true = plain text, one line per problem - severity, resource:line, "
                + "message, [checkId], (stale) - under a one-line summary, instead of the full objects with "
                + "extraInfo and locations: what the question \"are there errors in this object\" needs. "
                + "Ignored when countOnly. Default false."));
        props.add("refresh", boolProp("true = re-read the narrowed scope from disk (refreshLocal), run an "
                + "INCREMENTAL build of the project and wait for validation to settle before reading the "
                + "markers – the point fix for a stale marker: seconds for one module, where "
                + "edt_clean_project rebuilds everything for minutes. Without fqn / modulePath the project's "
                + "sources folder is refreshed – what validation reads. A project never built in this "
                + "session gets a full build instead. The result carries refreshed {resources, buildMs}. "
                + "Default false."));

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", new JsonArray());

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "List EDT validation problems for a project from the live workspace: both standard "
                + "Eclipse markers (syntax/build) and EDT check results (Standards, e.g. com.e1c.v8codestyle) "
                + "read from EDT's own marker store. Each problem carries source (eclipse|edt-check), "
                + "severity, message, resource, line, and for EDT checks the checkId and EDT grade. Narrow "
                + "with fqn / modulePath / severity, or pass countOnly for just the counts – on a large "
                + "configuration the unfiltered result is thousands of problems, so filter or count instead; "
                + "brief gives one text line per problem. "
                + "A marker is a snapshot and can outlive the code that caused it, so every listed problem "
                + "is judged against its file: stale=true when the marker predates the file's last change on "
                + "disk, unsynchronized=true when the workspace has not even read that change. The summary "
                + "counts them (staleCount, unsynchronized: [paths]) and a hint says what to do; refresh=true "
                + "revalidates the narrowed scope in place – an incremental build, seconds – where "
                + "edt_clean_project rebuilds the whole project. "
                + "modelReady=false or complete=false means the counts are a partial snapshot, not a clean result. The report names the disk folder behind each validated project (locations): the model "
                + "validates the REGISTERED folder, so check it against the checkout you are editing – a "
                + "parallel worktree of the same sources is NOT what gets validated.");
        t.addProperty("descriptionRu", "Список проблем валидации EDT по проекту из живой рабочей области: "
                + "и стандартные маркеры Eclipse (синтаксис/сборка), и результаты проверок EDT (Стандарты, "
                + "напр. com.e1c.v8codestyle) из собственного хранилища маркеров EDT. У каждой проблемы – "
                + "источник (eclipse|edt-check), важность, сообщение, ресурс, строка, а у проверок EDT – checkId "
                + "и класс важности. Сужение через fqn / modulePath / severity либо countOnly для одних "
                + "счётчиков – на большой конфигурации полный список это тысячи проблем, фильтруйте или считайте; "
                + "brief даёт по одной текстовой строке на проблему. "
                + "Маркер – это снимок, и он может пережить код, который его вызвал, поэтому каждая "
                + "выведенная проблема сверяется со своим файлом: stale=true, если маркер старше последнего "
                + "изменения файла на диске, unsynchronized=true, если рабочая область это изменение ещё не "
                + "прочитала. В сводке – счётчики (staleCount, unsynchronized: [пути]) и подсказка hint; "
                + "refresh=true перепроверяет суженную область на месте – инкрементальная сборка, секунды, – "
                + "тогда как edt_clean_project пересобирает весь проект. "
                + "Отчёт называет каталог диска за каждым проверенным проектом (locations): модель проверяет "
                + "ЗАРЕГИСТРИРОВАННЫЙ каталог – сверьте его с тем, что правите; параллельный worktree тех же "
                + "исходников проверен НЕ будет.");
        t.add("inputSchema", schema);
        return t;
    }

    /** Execute the tool; returns an MCP tool result (content[] / isError). */
    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        String fqn = getStr(args, "fqn");
        String modulePath = getStr(args, "modulePath");
        String severity = getStr(args, "severity");
        boolean countOnly = getBool(args, "countOnly");
        boolean brief = getBool(args, "brief");
        boolean refresh = getBool(args, "refresh");
        int waitSeconds = args.has("waitSeconds") && !args.get("waitSeconds").isJsonNull()
                ? args.get("waitSeconds").getAsInt() : REFRESH_WAIT_SECONDS;
        if (waitSeconds < 1 || waitSeconds > 600) {
            return McpServer.toolError("waitSeconds must be between 1 and 600");
        }
        int limit = (args.has("limit") && !args.get("limit").isJsonNull()) ? args.get("limit").getAsInt() : 1000;
        String baselineMode = getStr(args, "baseline");
        String baselineId = getStr(args, "baselineId");
        if (baselineMode != null && !"save".equals(baselineMode) && !"compare".equals(baselineMode)) {
            return McpServer.toolError("baseline must be save or compare");
        }
        if ("compare".equals(baselineMode) && (baselineId == null || baselineId.isBlank())) {
            return McpServer.toolError("baselineId is required for baseline=compare");
        }
        if (!"compare".equals(baselineMode) && baselineId != null) {
            return McpServer.toolError("baselineId requires baseline=compare");
        }
        try {
            ProjectGateway.RefreshResult refreshed = (refresh || baselineMode != null)
                    ? gateway.refreshScope(project, fqn, modulePath, waitSeconds)
                    : null;
            ProjectGateway.ProblemReport rep = gateway.reportProblems(project, fqn, modulePath, severity,
                    baselineMode == null && countOnly, baselineMode == null ? limit : BASELINE_MARKER_LIMIT, refreshed);
            JsonObject baseline = null;
            String baselineText = "";
            if (baselineMode != null) {
                if (!complete(rep) || rep.truncated) {
                    return McpServer.toolError("A baseline requires a complete validation snapshot. "
                            + "Refresh and wait for modelReady=true, synchronized sources and settled validation; "
                            + "narrow fqn or modulePath if the marker limit is exceeded. "
                            + (rep.hint == null ? "" : rep.hint));
                }
                java.util.List<ProblemBaselines.Entry> entries = rep.problems.stream().map(ProjectErrorsTool::entry).toList();
                String scope = baselineScope(project, fqn, modulePath, severity, rep.locations);
                baseline = new JsonObject();
                baseline.addProperty("mode", baselineMode);
                baseline.addProperty("currentTotal", rep.total);
                if ("save".equals(baselineMode)) {
                    ProblemBaselines.Saved saved = BASELINES.save(scope, entries);
                    baseline.addProperty("id", saved.id());
                    baseline.addProperty("savedCount", saved.count());
                    baseline.add("evictedIds", new GsonBuilder().create().toJsonTree(saved.evictedIds()));
                    baselineText = "baseline saved: " + saved.id() + " (" + saved.count() + " problems)\n";
                } else {
                    ProblemBaselines.Difference diff = BASELINES.compare(baselineId, scope, entries);
                    baseline.addProperty("id", baselineId);
                    baseline.addProperty("newCount", diff.added().size());
                    baseline.addProperty("resolvedCount", diff.removed().size());
                    int displayLimit = limit > 0 ? limit : 1000;
                    if (!countOnly) {
                        baseline.add("resolvedProblems", new GsonBuilder().create().toJsonTree(
                                diff.removed().stream().limit(displayLimit).toList()));
                        baseline.addProperty("resolvedTruncated", diff.removed().size() > displayLimit);
                    }
                    java.util.Map<ProblemBaselines.Entry, java.util.ArrayDeque<ProjectGateway.Problem>> originals =
                            new java.util.HashMap<>();
                    for (ProjectGateway.Problem problem : rep.problems) {
                        originals.computeIfAbsent(entry(problem), ignored -> new java.util.ArrayDeque<>()).add(problem);
                    }
                    rep.problems = new java.util.ArrayList<>();
                    rep.total = diff.added().size();
                    rep.errors = rep.warnings = rep.infos = rep.eclipse = rep.edtCheck = 0;
                    for (ProblemBaselines.Entry added : diff.added()) {
                        ProjectGateway.Problem problem = originals.get(added).removeFirst();
                        rep.problems.add(problem);
                        if ("ERROR".equals(problem.severity)) rep.errors++;
                        else if ("WARNING".equals(problem.severity)) rep.warnings++;
                        else rep.infos++;
                        if ("eclipse".equals(problem.source)) rep.eclipse++;
                        else rep.edtCheck++;
                    }
                    baselineText = "baseline " + baselineId + ": " + diff.added().size() + " new, "
                            + diff.removed().size() + " resolved\n";
                    if (brief) {
                        for (ProblemBaselines.Entry resolved : diff.removed().stream().limit(displayLimit).toList()) {
                            baselineText += "resolved: " + resolved.severity() + " " + resolved.resource()
                                    + ":" + resolved.line() + " " + resolved.message().replaceAll("\\R", " ") + "\n";
                        }
                    }
                }
                int displayLimit = limit > 0 ? limit : 1000;
                rep.countOnly = countOnly;
                rep.limit = displayLimit;
                rep.truncated = rep.problems.size() > displayLimit;
                rep.problems = new java.util.ArrayList<>(rep.problems.stream().limit(countOnly ? 0 : displayLimit).toList());
            }
            if (brief && !countOnly) {
                return McpServer.textResult(baselineText + briefReport(project, fqn, modulePath, severity, rep));
            }
            JsonObject payload = new JsonObject();
            if (project != null) {
                payload.addProperty("project", project);
            }
            // The disk folder behind each validated project, named up front: the model
            // validates the REGISTERED folder, and a caller editing a parallel checkout
            // of the same sources would otherwise get a plausible report about the wrong
            // tree - the file names match, so nothing else gives the divergence away.
            JsonObject locations = new JsonObject();
            rep.locations.forEach(locations::addProperty);
            payload.add("locations", locations);
            payload.addProperty("modelReady", rep.modelReady);
            payload.add("modelStatus", new GsonBuilder().create().toJsonTree(rep.modelStatus));
            payload.addProperty("unresolvedEdtMarkers", rep.unresolvedEdtMarkers);
            payload.addProperty("complete", complete(rep));
            if (baseline != null) payload.add("baseline", baseline);
            payload.addProperty("total", rep.total);
            payload.addProperty("totalBeforeFilter", rep.totalBeforeFilter);
            JsonObject bySeverity = new JsonObject();
            bySeverity.addProperty("ERROR", rep.errors);
            bySeverity.addProperty("WARNING", rep.warnings);
            bySeverity.addProperty("INFO", rep.infos);
            payload.add("bySeverity", bySeverity);
            JsonObject bySource = new JsonObject();
            bySource.addProperty("eclipse", rep.eclipse);
            bySource.addProperty("edtCheck", rep.edtCheck);
            payload.add("bySource", bySource);
            // Freshness: how many listed markers outlived a change of their file, which files the
            // workspace has not read, and the one line saying what to do about it.
            if (rep.staleCount >= 0) {
                payload.addProperty("staleCount", rep.staleCount);
            }
            JsonArray unsynchronized = new JsonArray();
            rep.unsynchronized.forEach(unsynchronized::add);
            payload.add("unsynchronized", unsynchronized);
            payload.addProperty("unsynchronizedCount", rep.unsynchronizedCount);
            if (rep.hint != null) {
                payload.addProperty("hint", rep.hint);
            }
            if (rep.refreshed != null) {
                payload.add("refreshed", refreshedJson(rep.refreshed));
            }
            JsonObject filter = new JsonObject();
            if (fqn != null) {
                filter.addProperty("fqn", fqn);
            }
            if (modulePath != null) {
                filter.addProperty("modulePath", modulePath);
            }
            if (severity != null) {
                filter.addProperty("severity", severity);
            }
            if (countOnly) {
                filter.addProperty("countOnly", true);
            }
            if (filter.size() > 0) {
                payload.add("filter", filter);
            }
            if (!rep.countOnly) {
                JsonArray arr = new JsonArray();
                for (ProjectGateway.Problem p : rep.problems) {
                    JsonObject o = new JsonObject();
                    o.addProperty("project", p.project);
                    o.addProperty("severity", p.severity);
                    o.addProperty("message", p.message);
                    o.addProperty("resource", p.resource);
                    o.addProperty("line", p.line);
                    o.addProperty("source", p.source);
                    if (p.sourceType != null) {
                        o.addProperty("sourceType", p.sourceType);
                    }
                    if (p.extraInfo != null && !p.extraInfo.isEmpty()) {
                        JsonObject extra = new JsonObject();
                        p.extraInfo.forEach(extra::addProperty);
                        o.add("extraInfo", extra);
                    }
                    if (p.checkId != null) {
                        o.addProperty("checkId", p.checkId);
                    }
                    if (p.edtSeverity != null) {
                        o.addProperty("edtSeverity", p.edtSeverity);
                    }
                    if (p.location != null) {
                        o.addProperty("location", p.location);
                    }
                    if (p.markerType != null) {
                        o.addProperty("markerType", p.markerType);
                    }
                    // An EDT check marker names its object, not a file; the file it was traced to
                    // is what the freshness verdict was read from.
                    if (p.filePath != null) {
                        o.addProperty("file", p.filePath);
                    }
                    if (p.stale) {
                        o.addProperty("stale", true);
                        if (p.markerCreatedAt > 0) {
                            o.addProperty("validatedAt", iso(p.markerCreatedAt));
                        }
                        if (p.fileModifiedAt > 0) {
                            o.addProperty("fileChangedAt", iso(p.fileModifiedAt));
                        }
                    }
                    if (p.unsynchronized) {
                        o.addProperty("unsynchronized", true);
                    }
                    arr.add(o);
                }
                payload.add("problems", arr);
                payload.addProperty("count", arr.size());
                if (rep.truncated) {
                    payload.addProperty("truncated", true);
                    payload.addProperty("limit", rep.limit);
                }
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(payload));
        } catch (Exception e) {
            return McpServer.toolError("edt_project_errors failed: " + e.getMessage());
        }
    }

    private static boolean complete(ProjectGateway.ProblemReport report) {
        return report.modelReady && report.unresolvedEdtMarkers == 0 && report.unsynchronizedCount == 0
                && report.staleCount <= 0 && (report.refreshed == null
                        || report.refreshed.settled && report.refreshed.warning == null);
    }

    private static ProblemBaselines.Entry entry(ProjectGateway.Problem problem) {
        return new ProblemBaselines.Entry(problem.project, problem.severity, problem.message,
                problem.resource, problem.line, problem.source, problem.checkId);
    }

    private static String baselineScope(String project, String fqn, String modulePath, String severity,
            java.util.Map<String, String> locations) {
        java.util.Map<String, Object> scope = new java.util.TreeMap<>();
        scope.put("project", project == null ? "" : project.trim());
        scope.put("locations", new java.util.TreeMap<>(locations));
        scope.put("fqn", fqn == null ? "" : fqn.trim().toLowerCase(java.util.Locale.ROOT));
        scope.put("modulePath", modulePath == null ? "" : modulePath.trim().replace('\\', '/').toLowerCase(java.util.Locale.ROOT));
        scope.put("severities", new java.util.TreeSet<>(io.github.keyfire.edtbridge.core.ProblemFilter.severities(severity)));
        return new GsonBuilder().create().toJson(scope);
    }

    /** The refresh that preceded the report, as the caller sees it. */
    private static JsonObject refreshedJson(ProjectGateway.RefreshResult res) {
        JsonObject o = new JsonObject();
        JsonArray resources = new JsonArray();
        res.resources.forEach(resources::add);
        o.add("resources", resources);
        o.addProperty("refreshMs", res.refreshMs);
        o.addProperty("buildMs", res.buildMs);
        o.addProperty("waitMs", res.waitMs);
        o.addProperty("settled", res.settled);
        // Whether the checks were seen running at all: "settled" without them is the shape of a
        // wait that finished before validation started - see edt_clean_project.
        o.addProperty("sawValidation", res.sawValidation);
        if (res.warning != null) {
            o.addProperty("warning", res.warning);
        }
        return o;
    }

    /**
     * The one-line-per-problem form. The full objects carry extraInfo, marker types and the
     * disk locations, which a caller asking "is this object clean" pays for on every problem;
     * here a problem is severity, resource with line, message and the check id when there is one.
     */
    static String briefReport(String project, String fqn, String modulePath, String severity,
            ProjectGateway.ProblemReport rep) {
        StringBuilder sb = new StringBuilder();
        sb.append(project != null ? project : "all open projects").append(": ").append(rep.total)
          .append(" problem(s) (ERROR ").append(rep.errors).append(", WARNING ").append(rep.warnings)
          .append(", INFO ").append(rep.infos).append("; eclipse ").append(rep.eclipse)
          .append(", edt-check ").append(rep.edtCheck).append(")");
        if (rep.total != rep.totalBeforeFilter) {
            sb.append(", ").append(rep.totalBeforeFilter).append(" before the filter");
        }
        if (rep.staleCount > 0) {
            sb.append("; stale ").append(rep.staleCount);
        }
        if (rep.unsynchronizedCount > 0) {
            sb.append("; unsynchronized ").append(rep.unsynchronizedCount).append(" file(s): ")
              .append(String.join(", ", rep.unsynchronized));
            if (rep.unsynchronizedCount > rep.unsynchronized.size()) {
                sb.append(", ...");
            }
        }
        java.util.List<String> filters = new java.util.ArrayList<>();
        if (fqn != null) {
            filters.add("fqn=" + fqn);
        }
        if (modulePath != null) {
            filters.add("modulePath=" + modulePath);
        }
        if (severity != null) {
            filters.add("severity=" + severity);
        }
        if (!filters.isEmpty()) {
            sb.append("; filter: ").append(String.join(", ", filters));
        }
        sb.append('\n');
        sb.append("modelReady: ").append(rep.modelReady);
        if (!rep.modelReady || rep.unresolvedEdtMarkers > 0) {
            sb.append("; PARTIAL validation snapshot");
        }
        sb.append('\n');
        if (rep.refreshed != null) {
            sb.append("refreshed: ").append(String.join(", ", rep.refreshed.resources))
              .append(" (build ").append(rep.refreshed.buildMs).append(" ms, validation ")
              .append(rep.refreshed.settled ? "settled" : "NOT settled").append(" after ")
              .append(rep.refreshed.waitMs).append(" ms")
              .append(rep.refreshed.sawValidation ? "" : ", never seen running").append(")");
            if (rep.refreshed.warning != null) {
                sb.append(" - ").append(rep.refreshed.warning);
            }
            sb.append('\n');
        }
        if (rep.hint != null) {
            sb.append("hint: ").append(rep.hint).append('\n');
        }
        for (ProjectGateway.Problem p : rep.problems) {
            sb.append(p.severity).append("  ").append(p.resource);
            if (p.line > 0) {
                sb.append(':').append(p.line);
            }
            sb.append("  ").append(p.message == null ? "" : p.message.replaceAll("\\s*\\R\\s*", " ").trim());
            if (p.checkId != null) {
                sb.append("  [").append(p.checkId).append(']');
            }
            if (p.unsynchronized) {
                sb.append("  (stale, unsynchronized)");
            } else if (p.stale) {
                sb.append("  (stale)");
            }
            sb.append('\n');
        }
        if (rep.truncated) {
            sb.append("... ").append(rep.problems.size()).append(" of ").append(rep.total)
              .append(" shown (limit ").append(rep.limit).append(")\n");
        }
        return sb.toString();
    }

    /** An instant as the server's local ISO time, the form the status page uses. */
    private static String iso(long epochMs) {
        return java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static JsonObject strProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        o.addProperty("description", desc);
        return o;
    }

    private static JsonObject boolProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "boolean");
        o.addProperty("description", desc);
        return o;
    }

    private static JsonObject intProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "integer");
        o.addProperty("description", desc);
        return o;
    }

    private static String getStr(JsonObject a, String k) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? a.get(k).getAsString() : null;
    }

    private static boolean getBool(JsonObject a, String k) {
        return a.has(k) && !a.get(k).isJsonNull() && a.get(k).getAsBoolean();
    }
}
