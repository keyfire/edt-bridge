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
 * MCP tool: edt_register_platform - WRITE (Phase 2). Register a 1C:Enterprise platform install into
 * EDT so EDT's own engine (dump / infobase update) can use it. Needed when EDT auto-detected only a
 * thin client for a version but a full (thick-client) install exists on disk. Token-gated.
 */
public final class RegisterPlatformTool {

    private final PlatformGateway gateway = new PlatformGateway();

    public String name() {
        return "edt_register_platform";
    }

    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("target", strProp("What to register: omit or \"auto\" = every full (thick-client) "
                + "install found on disk (best-first); \"prefer-full\" = also DROP thin builds that "
                + "shadow a full one in the same version line (so EDT resolves the full install for "
                + "dump/update) then refresh; a version like 8.5.1.1302; or an absolute path to a "
                + "version directory."));

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE (Phase 2): register a full 1C:Enterprise platform install into EDT so EDT's own "
                + "engine (edt_dump_external_object, edt_update_infobase) can use it. Use when "
                + "edt_platform_installations shows only thin-client installs registered but a full one "
                + "under diskFullInstalls. target: auto (all full installs on disk), a version, or a "
                + "path. Token-gated.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ (Phase 2): зарегистрировать полную установку платформы 1С:Предприятие в EDT, "
                + "чтобы штатный движок EDT (edt_dump_external_object, edt_update_infobase) мог её "
                + "использовать. Нужно, когда edt_platform_installations показывает зарегистрированными "
                + "только тонкие клиенты, а полная установка есть в diskFullInstalls. target: auto (все "
                + "полные установки с диска), версия или путь. Требует токен.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String target = getStr(args, "target");
        try {
            PlatformGateway.RegisterPlatformResult res = gateway.registerPlatform(target);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.add("registered", toArray(res.registered));
            o.add("alreadyKnown", toArray(res.alreadyKnown));
            o.add("removed", toArray(res.removed));
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_register_platform failed: " + e.getMessage());
        }
    }

    private static JsonArray toArray(java.util.List<String> list) {
        JsonArray a = new JsonArray();
        for (String s : list) {
            a.add(s);
        }
        return a;
    }

    private static JsonObject strProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        o.addProperty("description", desc);
        return o;
    }

    private static String getStr(JsonObject a, String k) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? a.get(k).getAsString() : null;
    }
}
