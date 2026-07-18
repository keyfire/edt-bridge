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

import java.util.List;

import io.github.keyfire.edtbridge.edt.BslGateway;
import io.github.keyfire.edtbridge.edt.MetadataReadGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_find_references - inbound references to a top-level metadata object
 * (who references it: metadata + BSL code) from the live BM cross-reference index.
 */
public final class FindReferencesTool {

    private static final int DEFAULT_LIMIT = 200;

    private final BslGateway gateway = new BslGateway();
    private final MetadataReadGateway mdRead = new MetadataReadGateway();

    public String name() {
        return "edt_find_references";
    }

    public JsonObject descriptor() {
        JsonObject pn = new JsonObject();
        pn.addProperty("type", "string");
        pn.addProperty("description", "EDT project name");

        JsonObject fqn = new JsonObject();
        fqn.addProperty("type", "string");
        fqn.addProperty("description", "Target top object FQN (English type prefix), e.g. Catalog.Контрагенты "
                + "– or CommonModule.X when method is given (method-caller mode).");

        JsonObject method = new JsonObject();
        method.addProperty("type", "string");
        method.addProperty("description", "Optional. When given, switches to method-caller mode: returns the BSL "
                + "call sites of the method of fqn (fqn = CommonModule.X) as module + line + call text. Separates "
                + "a qualified X.Method(...) call from same-named local procedures. Best-effort (literal calls).");

        JsonObject moduleType = new JsonObject();
        moduleType.addProperty("type", "string");
        moduleType.addProperty("description", "Reserved for non-CommonModule owners in method-caller mode. Optional.");

        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("description", "Max references / call sites returned (default " + DEFAULT_LIMIT + ")");

        JsonObject props = new JsonObject();
        props.add("projectName", pn);
        props.add("fqn", fqn);
        props.add("method", method);
        props.add("moduleType", moduleType);
        props.add("limit", limit);

        JsonArray req = new JsonArray();
        req.add("projectName");
        req.add("fqn");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "Inbound references to a top-level metadata object (metadata membership: subsystems, the "
                + "Configuration lists) from the live BM cross-reference index. With method given, switches to "
                + "method-caller mode: the BSL call sites of CommonModule.X.method (module + line + call text), "
                + "found by scanning the project's BSL – the counterpart the BM index does NOT cover.");
        t.addProperty("descriptionRu",
                "Входящие ссылки на объект метаданных верхнего уровня (членство в метаданных: подсистемы, "
                + "списки Configuration) из BM-индекса перекрёстных ссылок. С параметром method – режим поиска "
                + "мест вызова: BSL-места вызова CommonModule.X.method (модуль + строка + текст вызова), обходом "
                + "BSL проекта – то, чего BM-индекс не покрывает.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        String fqn = getStr(args, "fqn");
        if (project == null || fqn == null) {
            return McpServer.toolError("projectName and fqn are required");
        }
        String method = getStr(args, "method");
        String moduleType = getStr(args, "moduleType");
        int limit = DEFAULT_LIMIT;
        if (args.has("limit") && !args.get("limit").isJsonNull()) {
            try {
                limit = args.get("limit").getAsInt();
            } catch (RuntimeException ignored) {
                // keep default
            }
        }
        if (method != null) {
            return methodCallers(project, fqn, moduleType, method, limit);
        }
        try {
            MetadataReadGateway.RefResult res = mdRead.getReferences(project, fqn, limit);
            JsonArray arr = new JsonArray();
            if (res.refs != null) {
                for (MetadataReadGateway.Ref r : res.refs) {
                    JsonObject o = new JsonObject();
                    o.addProperty("sourceFqn", r.sourceFqn);
                    o.addProperty("sourceType", r.sourceType);
                    o.addProperty("feature", r.feature);
                    o.addProperty("sourceUri", r.sourceUri);
                    arr.add(o);
                }
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("found", res.found);
            payload.addProperty("fqn", res.fqn);
            payload.addProperty("total", res.total);
            payload.addProperty("returned", arr.size());
            payload.addProperty("truncated", res.truncated);
            payload.add("references", arr);
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(payload));
        } catch (Exception e) {
            return McpServer.toolError("edt_find_references failed: " + e.getMessage());
        }
    }

    /** Method-caller mode: render the BSL call sites of fqn's method. */
    private JsonObject methodCallers(String project, String fqn, String moduleType, String method, int limit) {
        try {
            BslGateway.MethodRefsResult res =
                    gateway.findMethodReferences(project, fqn, moduleType, method, limit);
            JsonObject o = new JsonObject();
            o.addProperty("mode", "methodCallers");
            o.addProperty("found", res.found);
            o.addProperty("fqn", res.fqn);
            o.addProperty("method", res.method);
            if (res.qualifier != null) {
                o.addProperty("qualifier", res.qualifier);
            }
            o.addProperty("total", res.total);
            o.addProperty("returned", res.returned);
            o.addProperty("truncated", res.truncated);
            o.addProperty("scannedModules", res.scannedModules);
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            JsonArray arr = new JsonArray();
            for (BslGateway.MethodRef m : res.refs) {
                JsonObject e = new JsonObject();
                if (m.module != null) {
                    e.addProperty("module", m.module);
                }
                e.addProperty("modulePath", m.modulePath);
                if (m.method != null) {
                    e.addProperty("method", m.method);
                }
                e.addProperty("line", m.line);
                if (m.text != null) {
                    e.addProperty("text", m.text);
                }
                arr.add(e);
            }
            o.add("callSites", arr);
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_find_references (method-caller mode) failed: " + e.getMessage());
        }
    }

    private static String getStr(JsonObject a, String k) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? a.get(k).getAsString() : null;
    }
}
