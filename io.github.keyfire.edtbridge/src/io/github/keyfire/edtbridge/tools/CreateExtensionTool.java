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
 * MCP tool: edt_create_extension - WRITE (Phase 2). Creates a new configuration-EXTENSION project
 * next to a base configuration project, via EDT's own IExtensionProjectManager (the engine behind
 * the New Configuration Extension wizard), then stamps the extension Configuration's name prefix
 * and purpose. Dry-run by default. Creating a project is additive → no force; still requires a
 * configured token.
 */
public final class CreateExtensionTool {

    private final EdtModelGateway gateway = new EdtModelGateway();

    public String name() {
        return "edt_create_extension";
    }

    /** Write tool — the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("name", strProp("New extension project (and extension configuration) name — "
                + "a valid identifier, Cyrillic allowed"));
        props.add("baseProjectName", strProp("Base configuration project in the workspace that the "
                + "extension extends"));
        props.add("namePrefix", strProp("Extension name prefix stamped on its Configuration "
                + "(e.g. \"Расш_\"); required"));
        props.add("purpose", strProp("Extension purpose: Customization/Адаптация (default), "
                + "AddOn/Дополнение, Patch/Исправление. Optional."));
        props.add("apply", boolProp("false (default) = dry-run: validate (name free, base project has "
                + "a Configuration) and return the plan, create nothing. true = create the project and "
                + "stamp prefix/purpose on its Configuration."));

        JsonArray req = new JsonArray();
        req.add("name");
        req.add("baseProjectName");
        req.add("namePrefix");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE (Phase 2): create a new configuration-EXTENSION project extending a base "
                + "configuration project, via EDT's own IExtensionProjectManager (the New Configuration "
                + "Extension wizard engine); stamps the extension's name prefix and purpose. Dry-run by "
                + "default: validates and returns the plan WITHOUT creating. apply=true creates the "
                + "project (model loads in background — poll edt_projects). Additive — no force needed — "
                + "but requires a configured token.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ (Phase 2): создать новый проект РАСШИРЕНИЯ конфигурации к базовому проекту "
                + "через штатный IExtensionProjectManager (движок мастера нового расширения); "
                + "проставляет префикс имён и назначение расширения. По умолчанию dry-run: проверяет и "
                + "возвращает план БЕЗ создания. apply=true создаёт проект (модель загружается в фоне – "
                + "опрашивать edt_projects). Аддитивно – force не нужен – но требует токен.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String name = getStr(args, "name");
        String baseProjectName = getStr(args, "baseProjectName");
        String namePrefix = getStr(args, "namePrefix");
        if (name == null || baseProjectName == null || namePrefix == null) {
            return McpServer.toolError("name, baseProjectName and namePrefix are required");
        }
        String purpose = getStr(args, "purpose");
        boolean apply = args.has("apply") && !args.get("apply").isJsonNull() && args.get("apply").getAsBoolean();
        try {
            EdtModelGateway.CreateExtensionResult res =
                    gateway.createExtension(name, baseProjectName, namePrefix, purpose, apply);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            o.addProperty("name", res.name);
            o.addProperty("baseProject", res.baseProject);
            o.addProperty("namePrefix", res.namePrefix);
            if (res.purpose != null) {
                o.addProperty("purpose", res.purpose);
            }
            o.addProperty("nameValid", res.nameValid);
            if (res.nameAvailable != null) {
                o.addProperty("nameAvailable", res.nameAvailable.booleanValue());
            }
            o.addProperty("baseFound", res.baseFound);
            if (res.version != null) {
                o.addProperty("version", res.version);
            }
            if (res.location != null) {
                o.addProperty("location", res.location);
            }
            o.addProperty("stamped", res.stamped);
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_create_extension failed: " + e.getMessage());
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
