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

import io.github.keyfire.edtbridge.edt.DebugGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_evaluate - EXEC (Phase 3). Evaluates an ARBITRARY BSL expression in a suspended
 * frame of a live debug session – i.e. arbitrary code execution against a live infobase. The heaviest-gated
 * tool: requires (1) a configured token, (2) per-call allowCodeExecution=true, and (3) the server-side
 * switch EDT_BRIDGE_ALLOW_EVALUATE=1. Off by default. STAND-ONLY, never production.
 */
public final class DebugEvaluateTool {

    private final DebugGateway gateway = new DebugGateway();

    public String name() {
        return "edt_evaluate";
    }

    /** Exec tool – the server gates this on a configured token (plus the in-tool opt-in + env switch). */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("sessionId", strProp("The debug session id returned by edt_debug_attach"));
        props.add("expression", strProp("BSL expression to evaluate in the suspended frame (e.g. a "
                + "variable name, or 1+1). Executes against the live infobase."));
        props.add("threadName", strProp("Which suspended thread (optional; default = first suspended)."));
        props.add("frameLevel", intProp("Stack frame level to evaluate in (optional; default 0 = top)."));
        props.add("allowCodeExecution", boolProp("MUST be true to run – explicit confirmation that this "
                + "executes arbitrary BSL against a live infobase."));

        JsonArray req = new JsonArray();
        req.add("sessionId");
        req.add("expression");
        req.add("allowCodeExecution");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "EXEC (Phase 3): evaluate an ARBITRARY BSL expression in a suspended debug frame – "
                + "arbitrary code execution against a live infobase. Heaviest gate: requires a configured "
                + "token AND allowCodeExecution=true AND the server switch EDT_BRIDGE_ALLOW_EVALUATE=1 (off by "
                + "default). STAND-ONLY, never production. Suspend a thread first (edt_debug_control) or hit a "
                + "breakpoint.");
        t.addProperty("descriptionRu",
                "ИСПОЛНЕНИЕ (Фаза 3): вычислить ПРОИЗВОЛЬНОЕ BSL-выражение в приостановленном кадре – "
                + "это исполнение произвольного кода против живой ИБ. Самый жёсткий гейт: нужен токен И "
                + "allowCodeExecution=true И серверный переключатель EDT_BRIDGE_ALLOW_EVALUATE=1 (по умолчанию "
                + "выключено). ТОЛЬКО СТЕНД, никогда продакшен. Сначала приостановите поток "
                + "(edt_debug_control) или дождитесь точки останова.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String sessionId = getStr(args, "sessionId");
        String expression = getStr(args, "expression");
        if (sessionId == null || expression == null) {
            return McpServer.toolError("sessionId and expression are required");
        }
        String threadName = getStr(args, "threadName");
        int frameLevel = (args.has("frameLevel") && !args.get("frameLevel").isJsonNull())
                ? args.get("frameLevel").getAsInt() : 0;
        boolean allow = args.has("allowCodeExecution") && !args.get("allowCodeExecution").isJsonNull()
                && args.get("allowCodeExecution").getAsBoolean();
        try {
            DebugGateway.EvaluateResult res =
                    gateway.evaluateDebug(sessionId, expression, threadName, frameLevel, allow);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("sessionId", res.sessionId);
            o.addProperty("expression", res.expression);
            o.addProperty("success", res.success);
            if (res.value != null) {
                o.addProperty("value", res.value);
            }
            if (res.type != null) {
                o.addProperty("type", res.type);
            }
            if (res.error != null) {
                o.addProperty("error", res.error);
            }
            if (res.frame != null) {
                o.addProperty("frame", res.frame);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_evaluate failed: " + e.getMessage());
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
