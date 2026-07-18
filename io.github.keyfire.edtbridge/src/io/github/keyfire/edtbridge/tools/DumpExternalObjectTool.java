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

import io.github.keyfire.edtbridge.edt.MetadataWriteGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_dump_external_object - WRITE (Phase 2). Compiles an external data processor /
 * report of a project into a binary .epf/.erf via EDT's own IExternalObjectDumper. Requires a
 * locally installed 1C platform matching the project version. Dry-run by default; token-gated.
 */
public final class DumpExternalObjectTool {

    private final MetadataWriteGateway gateway = new MetadataWriteGateway();

    public String name() {
        return "edt_dump_external_object";
    }

    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("External object project name in the workspace"));
        props.add("objectName", strProp("The external data processor / report name inside the "
                + "project (usually equals the project name)"));
        props.add("kind", strProp("processor (default) or report"));
        props.add("targetPath", strProp("Absolute path of the .epf/.erf file to write"));
        props.add("apply", boolProp("false (default) = dry-run: resolve the object and validate that "
                + "dump generation is available (local 1C platform). true = write the file."));

        JsonArray req = new JsonArray();
        req.add("projectName");
        req.add("objectName");
        req.add("targetPath");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE (Phase 2): compile an external data processor/report into a binary .epf/.erf "
                + "file via EDT's own IExternalObjectDumper (needs a locally installed 1C platform "
                + "matching the project version). Dry-run by default: resolves the object and validates "
                + "dump availability; apply=true writes the file. Token-gated.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ (Phase 2): скомпилировать внешнюю обработку/отчёт в бинарный файл .epf/.erf "
                + "через штатный IExternalObjectDumper (нужна установленная платформа 1С, совместимая с "
                + "версией проекта). По умолчанию dry-run: находит объект и проверяет доступность "
                + "выгрузки; apply=true пишет файл. Требует токен.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String projectName = getStr(args, "projectName");
        String objectName = getStr(args, "objectName");
        String targetPath = getStr(args, "targetPath");
        if (projectName == null || objectName == null || targetPath == null) {
            return McpServer.toolError("projectName, objectName and targetPath are required");
        }
        String kind = getStr(args, "kind");
        boolean apply = args.has("apply") && !args.get("apply").isJsonNull() && args.get("apply").getAsBoolean();
        try {
            MetadataWriteGateway.DumpExternalObjectResult res =
                    gateway.dumpExternalObject(projectName, objectName, kind, targetPath, apply);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            o.addProperty("project", res.project);
            if (res.fqn != null) {
                o.addProperty("fqn", res.fqn);
            }
            o.addProperty("targetPath", res.targetPath);
            if (res.validation != null && !res.validation.isEmpty()) {
                o.addProperty("validation", res.validation);
            }
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_dump_external_object failed: " + e.getMessage());
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
