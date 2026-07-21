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

import io.github.keyfire.edtbridge.edt.BslGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_module_text - READ. Returns the BSL source of a module (or a single method) plus the
 * module's procedure/function list with signatures, from the live workspace. Resolve the module by FQN
 * (CommonModule.X, a form FQN like DataProcessor.X.Form.Y, or an object FQN + moduleType) or by an
 * explicit modulePath. Avoids reading .bsl from disk by hand.
 */
public final class ModuleTextTool {

    private final BslGateway gateway = new BslGateway();

    public String name() {
        return "edt_module_text";
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name"));
        props.add("fqn", strProp("Module FQN: CommonModule.X, a form (DataProcessor.X.Form.Y / "
                + "CommonForm.Y), or an object (Catalog.X) – for objects also pass moduleType. Omit if "
                + "modulePath is given."));
        props.add("moduleType", strProp("For object FQNs: which module – ObjectModule / ManagerModule / "
                + "RecordSetModule / ValueManagerModule / CommandModule. Optional."));
        props.add("method", strProp("Return only this procedure/function's source. Optional (the method "
                + "list is still returned unless includeMethods=false)."));
        props.add("modulePath", strProp("Workspace-relative .bsl path (e.g. src/CommonModules/X/Module.bsl) "
                + "– an alternative to fqn. Optional."));
        props.add("includeMethods", boolProp("true (default) = also return the module's procedure/function "
                + "catalogue with signatures. false = skip it and return only the requested text – a targeted "
                + "method read on a large module then avoids re-sending the whole list (147 methods on some)."));

        JsonArray req = new JsonArray();
        req.add("projectName");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "READ: BSL source of a module (or a single method) + the module's procedure/function list "
                + "with signatures, from the live workspace. Resolve by fqn (CommonModule.X, HTTPService.X, a "
                + "form FQN, or an object + moduleType) or by modulePath. Pass includeMethods=false to skip the "
                + "catalogue on a targeted read. If a top object has several modules and none is chosen, "
                + "returns the candidates in availableModules.");
        t.addProperty("descriptionRu",
                "ЧТЕНИЕ: исходный BSL модуля (или одного метода) + список процедур/функций модуля с "
                + "сигнатурами, из живой рабочей области. Адресация по fqn (CommonModule.X, HTTPService.X, FQN "
                + "формы или объект + moduleType) либо по modulePath. includeMethods=false пропускает каталог "
                + "при точечном чтении. Если у объекта несколько модулей и не выбран moduleType – вернёт "
                + "варианты в availableModules.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        if (project == null) {
            return McpServer.toolError("projectName is required");
        }
        String fqn = getStr(args, "fqn");
        String moduleType = getStr(args, "moduleType");
        String method = getStr(args, "method");
        String modulePath = getStr(args, "modulePath");
        if (fqn == null && modulePath == null) {
            return McpServer.toolError("provide fqn or modulePath");
        }
        boolean includeMethods = !args.has("includeMethods") || args.get("includeMethods").isJsonNull()
                || args.get("includeMethods").getAsBoolean();
        try {
            BslGateway.ModuleTextResult res =
                    gateway.moduleText(project, fqn, moduleType, method, modulePath, includeMethods);
            JsonObject o = new JsonObject();
            o.addProperty("found", res.found);
            if (res.fqn != null) {
                o.addProperty("fqn", res.fqn);
            }
            if (res.modulePath != null) {
                o.addProperty("modulePath", res.modulePath);
            }
            if (!res.availableModules.isEmpty()) {
                JsonArray am = new JsonArray();
                for (String m : res.availableModules) {
                    am.add(m);
                }
                o.add("availableModules", am);
            }
            if (res.includeMethods) {
                JsonArray methods = new JsonArray();
                for (BslGateway.BslMethod m : res.methods) {
                    JsonObject mo = new JsonObject();
                    mo.addProperty("name", m.name);
                    mo.addProperty("kind", m.kind);
                    mo.addProperty("export", m.export);
                    mo.addProperty("line", m.line);
                    JsonArray ps = new JsonArray();
                    for (String pr : m.params) {
                        ps.add(pr);
                    }
                    mo.add("params", ps);
                    methods.add(mo);
                }
                o.add("methods", methods);
                o.addProperty("methodCount", res.methods.size());
            } else {
                o.addProperty("methodsIncluded", false);
            }
            if (res.text != null) {
                o.addProperty("text", res.text);
                o.addProperty("textTruncated", res.textTruncated);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_module_text failed: " + e.getMessage());
        }
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

    private static String getStr(JsonObject a, String k) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? a.get(k).getAsString() : null;
    }
}
