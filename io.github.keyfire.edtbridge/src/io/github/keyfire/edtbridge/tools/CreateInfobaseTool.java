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
 * MCP tool: edt_create_infobase - WRITE (Phase 2). Creates an EMPTY file infobase via EDT's own
 * IInfobaseCreationOperation (the engine behind the "Create infobase" wizard; it drives the platform
 * client in batch mode) and registers it in EDT's infobases list. EDT resolves the platform itself
 * (see edt_platform_installations). Dry-run by default (validation only); token-gated.
 */
public final class CreateInfobaseTool {

    private final PlatformGateway gateway = new PlatformGateway();

    public String name() {
        return "edt_create_infobase";
    }

    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("name", strProp("Infobase display name (as it appears in EDT's infobases list)"));
        props.add("path", strProp("Absolute folder for the file infobase, e.g. D:\\Bases\\test1 "
                + "(created if missing)"));
        props.add("platform", strProp("Optional platform version to pin, e.g. 8.3.24 – omit to let EDT "
                + "resolve the installation itself"));
        props.add("cf", strProp("Optional path to a .cf file to load as the initial configuration – "
                + "omit for a blank base"));
        props.add("apply", boolProp("false (default) = dry-run: validate the request only. true = "
                + "create the infobase and register it in EDT."));

        JsonArray req = new JsonArray();
        req.add("name");
        req.add("path");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE (Phase 2): create an EMPTY file infobase and register it in EDT's infobases list. "
                + "Primary path is EDT's own IInfobaseCreationOperation. If EDT has no registered install "
                + "with a thick client for the version, it FALLS BACK to a full install found on disk "
                + "(highest matching version first, then descending) and creates the base with that "
                + "client; if none exists anywhere it says so. Pass platform to pin a version, cf to load "
                + "an initial .cf. Dry-run by default; apply=true creates it. Token-gated.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ (Phase 2): создать ПУСТУЮ файловую информационную базу и зарегистрировать её в "
                + "списке баз EDT. Основной путь – штатный IInfobaseCreationOperation. Если в EDT нет "
                + "зарегистрированной установки с толстым клиентом под версию, инструмент САМ ищет полную "
                + "установку на диске (сначала максимальная подходящая версия, затем по убыванию) и "
                + "создаёт базу её клиентом; если подходящей нет нигде – сообщает об этом. platform "
                + "фиксирует версию, cf загружает начальную .cf. По умолчанию dry-run; apply=true создаёт. "
                + "Требует токен.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String name = getStr(args, "name");
        String path = getStr(args, "path");
        if (name == null || path == null) {
            return McpServer.toolError("name and path are required");
        }
        String platform = getStr(args, "platform");
        String cf = getStr(args, "cf");
        boolean apply = args.has("apply") && !args.get("apply").isJsonNull() && args.get("apply").getAsBoolean();
        try {
            PlatformGateway.CreateInfobaseResult res =
                    gateway.createInfobase(name, path, platform, cf, apply);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            o.addProperty("name", res.name);
            o.addProperty("path", res.path);
            o.addProperty("platform", res.platform);
            if (res.method != null) {
                o.addProperty("method", res.method);
            }
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_create_infobase failed: " + e.getMessage());
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
