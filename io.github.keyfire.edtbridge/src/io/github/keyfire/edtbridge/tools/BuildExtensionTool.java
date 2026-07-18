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

import io.github.keyfire.edtbridge.edt.PlatformGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_build_extension - WRITE (Phase 2). Build a configuration extension binary (.cfe)
 * from an EDT configuration-extension project, off EDT's platform resolver: export the model to
 * designer XML in-process, then build the .cfe with a full on-disk 1C install (ibcmd) via a
 * throwaway temp infobase that is deleted afterwards. Token-gated.
 */
public final class BuildExtensionTool {

    private final PlatformGateway gateway = new PlatformGateway();

    public String name() {
        return "edt_build_extension";
    }

    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("The configuration-extension project name in the workspace"));
        props.add("outputPath", strProp("Absolute path of the .cfe file to write"));
        props.add("extensionName", strProp("Extension name for ibcmd --extension; defaults to the "
                + "project's Configuration name"));
        props.add("platform", strProp("Optional platform version to match the target base, e.g. 8.5.1 "
                + "(picks an on-disk full install of that line that carries ibcmd); defaults to the "
                + "project's version"));
        props.add("apply", boolProp("false (default) = dry-run: resolve the project, extension name and "
                + "a usable on-disk ibcmd install. true = export and build the .cfe."));

        JsonArray req = new JsonArray();
        req.add("projectName");
        req.add("outputPath");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE (Phase 2): build a configuration extension (.cfe) from an EDT extension project, "
                + "bypassing EDT's platform resolver. Exports the model to designer XML in-process (no "
                + "thick client), then builds the .cfe with a full on-disk 1C install via ibcmd "
                + "(infobase create -> config import --extension -> config save --extension) in a "
                + "throwaway temp base that is deleted afterwards. Pick platform to match the target "
                + "base version. Dry-run by default; token-gated.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ (Phase 2): собрать расширение конфигурации (.cfe) из проекта расширения EDT в "
                + "обход резолвера платформы EDT. Модель выгружается в XML конструктора в процессе (толстый "
                + "клиент не нужен), затем .cfe собирается полной установкой 1С с диска через ibcmd "
                + "(infobase create -> config import --extension -> config save --extension) во временной "
                + "базе, которая удаляется после. platform – под версию целевой базы. По умолчанию dry-run; "
                + "требует токен.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String projectName = getStr(args, "projectName");
        String outputPath = getStr(args, "outputPath");
        if (projectName == null || outputPath == null) {
            return McpServer.toolError("projectName and outputPath are required");
        }
        String extensionName = getStr(args, "extensionName");
        String platform = getStr(args, "platform");
        boolean apply = args.has("apply") && !args.get("apply").isJsonNull() && args.get("apply").getAsBoolean();
        try {
            PlatformGateway.BuildExtensionResult res =
                    gateway.buildExtension(projectName, extensionName, outputPath, platform, apply);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            o.addProperty("project", res.project);
            if (res.extensionName != null) {
                o.addProperty("extensionName", res.extensionName);
            }
            o.addProperty("outputPath", res.outputPath);
            if (res.platform != null) {
                o.addProperty("platform", res.platform);
            }
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_build_extension failed: " + e.getMessage());
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
