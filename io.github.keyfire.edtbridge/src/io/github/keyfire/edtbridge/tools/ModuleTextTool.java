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
        props.add("method", strProp("Return only this procedure/function's source. Optional. The module's "
                + "catalogue is then left out unless includeMethods=true."));
        props.add("modulePath", strProp("Workspace-relative .bsl path (e.g. src/CommonModules/X/Module.bsl) "
                + "– an alternative to fqn. Optional."));
        props.add("includeMethods", boolProp("Also return the module's procedure/function catalogue with "
                + "signatures. Default: true for a whole-module read, false when method is given or a line range is requested."));
        props.add("startLine", ToolJson.intProp("First module line to return, starting at 1. Default 1. Omit when reading a method."));
        props.add("endLine", ToolJson.intProp("Last module line to return, inclusive. Default: end of the module. Omit when reading a method."));
        props.add("maxChars", ToolJson.intProp("Maximum source text length, in UTF-16 code units. Default and maximum: 200000."));

        JsonArray req = new JsonArray();
        req.add("projectName");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "READ: BSL source of a module (or a single method) from the live workspace, with the module's "
                + "procedure/function list and signatures when the whole module is read. Resolve by fqn "
                + "(CommonModule.X, HTTPService.X, a form FQN, or an object + moduleType) or by modulePath. A "
                + "method read leaves the list out (methodsIncluded: false) unless includeMethods=true. If a top "
                + "object has several modules and none is chosen, returns the candidates in availableModules. "
                + "Use startLine/endLine for a range and maxChars to cap the text. totalLines counts module lines. "
                + "sourceHash covers the whole saved module and can be passed as expectedHash when writing.");
        t.addProperty("descriptionRu",
                "ЧТЕНИЕ: исходный BSL модуля (или одного метода) из живой рабочей области, а при чтении модуля "
                + "целиком еще и список процедур/функций с сигнатурами. Адресация по fqn (CommonModule.X, "
                + "HTTPService.X, FQN формы или объект + moduleType) либо по modulePath. Чтение метода не "
                + "возвращает список (methodsIncluded: false), если не передан includeMethods=true. Если у объекта "
                + "несколько модулей и не выбран moduleType – вернет варианты в availableModules. "
                + "startLine/endLine задают диапазон, maxChars ограничивает объем текста, totalLines содержит число строк модуля. "
                + "sourceHash вычисляется по всему сохраненному модулю и подходит для expectedHash при записи.");
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
        try {
            if (method != null && hasRange(args)) {
                return McpServer.toolError("method cannot be combined with startLine or endLine");
            }
            int start = positiveInt(args, "startLine", 1);
            int end = positiveInt(args, "endLine", Integer.MAX_VALUE);
            int cap = Math.min(positiveInt(args, "maxChars", BslGateway.MODULE_TEXT_CAP), BslGateway.MODULE_TEXT_CAP);
            if (end < start) {
                return McpServer.toolError("endLine must be at least startLine");
            }
            BslGateway.ModuleTextResult res =
                    gateway.moduleText(project, fqn, moduleType, method, modulePath, includeMethods(args, method),
                            start, end, cap);
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
            if (res.sourceHash != null) {
                o.addProperty("sourceHash", res.sourceHash);
                o.addProperty("totalLines", res.totalLines);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_module_text failed: " + e.getMessage());
        }
    }

    /**
     * Whether the procedure/function catalogue goes with the answer. Asked for explicitly, it does as asked.
     * Otherwise it comes with a whole-module read only: models read one method without passing the flag, and
     * on a large module the catalogue made each such answer many times the size of the method.
     */
    static boolean includeMethods(JsonObject args, String method) {
        if (args.has("includeMethods") && !args.get("includeMethods").isJsonNull()) {
            return args.get("includeMethods").getAsBoolean();
        }
        return method == null && !hasRange(args);
    }

    private static boolean hasRange(JsonObject args) {
        var start = args.get("startLine");
        var end = args.get("endLine");
        return (start != null && !start.isJsonNull()) || (end != null && !end.isJsonNull());
    }

    private static int positiveInt(JsonObject args, String key, int fallback) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            return fallback;
        }
        var value = args.get(key);
        try {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                int number = value.getAsBigDecimal().intValueExact();
                if (number > 0) {
                    return number;
                }
            }
        } catch (ArithmeticException | NumberFormatException invalid) {
            // The value is outside the accepted integer range.
        }
        throw new IllegalArgumentException(key + " must be a positive integer");
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
