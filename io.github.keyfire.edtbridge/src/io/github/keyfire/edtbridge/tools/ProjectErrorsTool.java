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
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_project_errors - list EDT validation problems for a project (or all open
 * projects) from the live workspace. The M0 proof tool: plugin in EDT -> MCP -> EDT state.
 */
public final class ProjectErrorsTool {

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
                + "src/CommonModules/X/Module.bsl, or a folder prefix. Targets Eclipse syntax/build markers. "
                + "Optional."));
        props.add("severity", strProp("Keep only ERROR, WARNING or INFO (case-insensitive). Optional."));
        props.add("countOnly", boolProp("true = return only the counts (total, by severity, by source), no "
                + "problem list – what a before/after baseline needs on a large configuration. Default false."));
        props.add("limit", intProp("Max problems in the returned list (default 1000); ignored when countOnly. "
                + "Excess sets truncated=true."));
        props.add("brief", boolProp("true = plain text, one line per problem - severity, resource:line, "
                + "message, [checkId] - under a one-line summary, instead of the full objects with extraInfo "
                + "and locations: what the question \"are there errors in this object\" needs. Ignored when "
                + "countOnly. Default false."));

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
                + "The report names the disk folder behind each validated project (locations): the model "
                + "validates the REGISTERED folder, so check it against the checkout you are editing – a "
                + "parallel worktree of the same sources is NOT what gets validated.");
        t.addProperty("descriptionRu", "Список проблем валидации EDT по проекту из живой рабочей области: "
                + "и стандартные маркеры Eclipse (синтаксис/сборка), и результаты проверок EDT (Стандарты, "
                + "напр. com.e1c.v8codestyle) из собственного хранилища маркеров EDT. У каждой проблемы – "
                + "источник (eclipse|edt-check), важность, сообщение, ресурс, строка, а у проверок EDT – checkId "
                + "и класс важности. Сужение через fqn / modulePath / severity либо countOnly для одних "
                + "счётчиков – на большой конфигурации полный список это тысячи проблем, фильтруйте или считайте; "
                + "brief даёт по одной текстовой строке на проблему. "
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
        boolean countOnly = args.has("countOnly") && !args.get("countOnly").isJsonNull()
                && args.get("countOnly").getAsBoolean();
        boolean brief = args.has("brief") && !args.get("brief").isJsonNull() && args.get("brief").getAsBoolean();
        int limit = (args.has("limit") && !args.get("limit").isJsonNull()) ? args.get("limit").getAsInt() : 1000;
        try {
            ProjectGateway.ProblemReport rep =
                    gateway.reportProblems(project, fqn, modulePath, severity, countOnly, limit);
            if (brief && !countOnly) {
                return McpServer.textResult(briefReport(project, fqn, modulePath, severity, rep));
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
        for (ProjectGateway.Problem p : rep.problems) {
            sb.append(p.severity).append("  ").append(p.resource);
            if (p.line > 0) {
                sb.append(':').append(p.line);
            }
            sb.append("  ").append(p.message == null ? "" : p.message.replaceAll("\\s*\\R\\s*", " ").trim());
            if (p.checkId != null) {
                sb.append("  [").append(p.checkId).append(']');
            }
            sb.append('\n');
        }
        if (rep.truncated) {
            sb.append("... ").append(rep.problems.size()).append(" of ").append(rep.total)
              .append(" shown (limit ").append(rep.limit).append(")\n");
        }
        return sb.toString();
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
}
