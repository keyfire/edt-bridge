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
 * MCP tool: edt_debug_detach - WRITE/SESSION (Phase 3). Terminates a debug session opened by
 * edt_debug_attach (frees the suspended infobase / avoids a lingering session). Token-gated.
 */
public final class DebugDetachTool {

    private final EdtModelGateway gateway = new EdtModelGateway();

    public String name() {
        return "edt_debug_detach";
    }

    /** Session tool — the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("sessionId", strProp("The debug session id returned by edt_debug_attach"));

        JsonArray req = new JsonArray();
        req.add("sessionId");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE/SESSION (Phase 3): detach (terminate) a debug session opened by "
                + "edt_debug_attach. Frees the infobase and removes the launch. Requires a configured token.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ/СЕССИЯ (Фаза 3): отключить (завершить) сессию отладки, открытую "
                + "edt_debug_attach. Освобождает ИБ и удаляет launch. Требует токен.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String sessionId = getStr(args, "sessionId");
        if (sessionId == null) {
            return McpServer.toolError("sessionId is required");
        }
        try {
            boolean detached = gateway.detachDebug(sessionId);
            JsonObject o = new JsonObject();
            o.addProperty("ok", detached);
            o.addProperty("sessionId", sessionId);
            o.addProperty("message", detached ? "detached debug session " + sessionId
                    : "no such debug session: " + sessionId);
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_debug_detach failed: " + e.getMessage());
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
