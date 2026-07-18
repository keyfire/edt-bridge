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
 * MCP tool: edt_debug_control - WRITE/SESSION (Phase 3). Controls execution of a live debug
 * session (opened by edt_debug_attach): suspend/resume the target, or step a suspended thread. Changes
 * runtime execution → token-gated.
 */
public final class DebugControlTool {

    private final EdtModelGateway gateway = new EdtModelGateway();

    public String name() {
        return "edt_debug_control";
    }

    /** Session/exec tool – the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("sessionId", strProp("The debug session id returned by edt_debug_attach"));
        props.add("action", strProp("One of: suspend, resume, stepOver, stepInto, stepReturn"));
        props.add("threadName", strProp("For step actions: which suspended thread (optional; default = the "
                + "first suspended thread)"));

        JsonArray req = new JsonArray();
        req.add("sessionId");
        req.add("action");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE/SESSION (Phase 3): control execution of a live debug session – "
                + "suspend/resume the target, or stepOver/stepInto/stepReturn a suspended thread. Requires a "
                + "configured token. Pair with edt_debug_inspect to read state after suspend/step.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ/СЕССИЯ (Фаза 3): управление выполнением живой сессии отладки – "
                + "suspend/resume цели или stepOver/stepInto/stepReturn приостановленного потока. Требует "
                + "токен. Состояние после suspend/step смотрите через edt_debug_inspect.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String sessionId = getStr(args, "sessionId");
        String action = getStr(args, "action");
        if (sessionId == null || action == null) {
            return McpServer.toolError("sessionId and action are required");
        }
        String threadName = getStr(args, "threadName");
        try {
            EdtModelGateway.ControlResult res = gateway.controlDebug(sessionId, action, threadName);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("sessionId", res.sessionId);
            o.addProperty("action", res.action);
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_debug_control failed: " + e.getMessage());
        }
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
