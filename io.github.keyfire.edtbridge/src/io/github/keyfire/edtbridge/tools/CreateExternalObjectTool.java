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

import io.github.keyfire.edtbridge.edt.EdtModelGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_create_external_object - WRITE (Phase 2). Creates a new external-data-processor
 * PROJECT via EDT's own IExternalObjectProjectManager (the New External Data Processor wizard
 * engine). Optionally linked to a base configuration project. Dry-run by default; token-gated.
 */
public final class CreateExternalObjectTool {

    private final EdtModelGateway gateway = new EdtModelGateway();

    public String name() {
        return "edt_create_external_object";
    }

    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("name", strProp("New external data processor project (and object) name — a valid "
                + "identifier, Cyrillic allowed"));
        props.add("baseProjectName", strProp("Configuration project the processor is developed for "
                + "(links its runtime version and types context). Optional — omit for standalone."));
        props.add("apply", boolProp("false (default) = dry-run: validate and return the plan. "
                + "true = create the project."));

        JsonArray req = new JsonArray();
        req.add("name");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE (Phase 2): create a new EXTERNAL DATA PROCESSOR project via EDT's own "
                + "IExternalObjectProjectManager (the wizard engine), optionally linked to a base "
                + "configuration project. Dry-run by default; apply=true creates the project (model "
                + "loads in background — poll edt_projects). Token-gated. Use edt_dump_external_object "
                + "to compile it into an .epf afterwards.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ (Phase 2): создать новый проект ВНЕШНЕЙ ОБРАБОТКИ через штатный "
                + "IExternalObjectProjectManager (движок мастера), при необходимости привязанный к "
                + "базовому проекту конфигурации. По умолчанию dry-run; apply=true создаёт проект "
                + "(модель грузится в фоне – опрашивать edt_projects). Требует токен. Скомпилировать в "
                + ".epf – инструментом edt_dump_external_object.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String name = getStr(args, "name");
        if (name == null) {
            return McpServer.toolError("name is required");
        }
        String baseProjectName = getStr(args, "baseProjectName");
        boolean apply = args.has("apply") && !args.get("apply").isJsonNull() && args.get("apply").getAsBoolean();
        try {
            EdtModelGateway.CreateExternalObjectResult res =
                    gateway.createExternalObject(name, baseProjectName, apply);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            o.addProperty("name", res.name);
            if (res.baseProject != null) {
                o.addProperty("baseProject", res.baseProject);
            }
            o.addProperty("nameValid", res.nameValid);
            if (res.nameAvailable != null) {
                o.addProperty("nameAvailable", res.nameAvailable.booleanValue());
            }
            if (res.version != null) {
                o.addProperty("version", res.version);
            }
            if (res.location != null) {
                o.addProperty("location", res.location);
            }
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_create_external_object failed: " + e.getMessage());
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
