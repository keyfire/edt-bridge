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
package com.e1c.fresh.edtbridge.tools;

import com.e1c.fresh.edtbridge.edt.EdtModelGateway;
import com.e1c.fresh.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_debug_attach - WRITE/SESSION (Phase 3). Attaches a debug session to a RUNNING
 * infobase's debug server (dbgs) by building a REMOTE_RUNTIME Eclipse launch and launching it in debug
 * mode; the connected target is stored under a returned sessionId for the control/inspect/evaluate tools.
 * Token-gated. STAND-ONLY: target a test stand, never production. Data-area scoping is not
 * yet applied — do not attach to a multi-tenant production IB.
 */
public final class DebugAttachTool {

    private final EdtModelGateway gateway = new EdtModelGateway();

    public String name() {
        return "edt_debug_attach";
    }

    /** Session/exec tool — the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name whose BSL modules map to the debugged infobase"));
        props.add("serverUrl", strProp("Debug server (dbgs) host of the RUNNING infobase, e.g. the tests "
                + "stand. Required."));
        props.add("serverPort", intProp("Debug server port. Optional (0/omit = default)."));
        props.add("infobaseAlias", strProp("Infobase alias on the debug server. Optional (alias OR uuid)."));
        props.add("infobaseUuid", strProp("Infobase UUID on the debug server. Optional (alias OR uuid)."));

        JsonArray req = new JsonArray();
        req.add("serverUrl");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE/SESSION (Phase 3): attach a debug session to a RUNNING infobase's debug "
                + "server (dbgs). Builds a REMOTE_RUNTIME launch and launches it in debug mode; returns a "
                + "sessionId for the follow-up debug tools. Requires a configured token. STAND-ONLY — target "
                + "a test stand, never production. Data-area scoping not yet applied.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ/СЕССИЯ (Фаза 3): подключить сессию отладки к debug-серверу (dbgs) ЗАПУЩЕННОЙ "
                + "ИБ. Строит REMOTE_RUNTIME launch и запускает в режиме отладки; возвращает sessionId для "
                + "последующих инструментов отладки. Требует токен. ТОЛЬКО СТЕНД – целиться в тестовый стенд, "
                + "никогда в продакшен. Скоуп областей данных пока не применяется.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String serverUrl = getStr(args, "serverUrl");
        if (serverUrl == null) {
            return McpServer.toolError("serverUrl is required");
        }
        String project = getStr(args, "projectName");
        String alias = getStr(args, "infobaseAlias");
        String uuid = getStr(args, "infobaseUuid");
        int port = (args.has("serverPort") && !args.get("serverPort").isJsonNull())
                ? args.get("serverPort").getAsInt() : 0;
        try {
            EdtModelGateway.AttachResult res = gateway.attachDebug(project, serverUrl, port, alias, uuid);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            if (res.sessionId != null) {
                o.addProperty("sessionId", res.sessionId);
            }
            if (res.projectName != null) {
                o.addProperty("projectName", res.projectName);
            }
            o.addProperty("serverUrl", res.serverUrl);
            o.addProperty("serverPort", res.serverPort);
            o.addProperty("launched", res.launched);
            o.addProperty("connected", res.connected);
            if (res.debugServerUrl != null) {
                o.addProperty("debugServerUrl", res.debugServerUrl);
            }
            o.addProperty("targetCount", res.targetCount);
            if (res.warning != null) {
                o.addProperty("warning", res.warning);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_debug_attach failed: " + e.getMessage());
        }
    }

    private static JsonObject strProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
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
