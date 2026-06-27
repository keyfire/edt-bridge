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
 * MCP tool: edt_debug_inspect - READ (Phase 3). Lists the threads of a live debug session
 * (opened by edt_debug_attach) and, for suspended threads, their BSL stack frames + the top frame's
 * variables. Read-only; no code execution.
 */
public final class DebugInspectTool {

    private final EdtModelGateway gateway = new EdtModelGateway();

    public String name() {
        return "edt_debug_inspect";
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
                "READ (Phase 3): inspect a live debug session — list its threads (debug items: "
                + "background jobs, server calls, clients) and, for SUSPENDED threads, their BSL stack frames "
                + "+ the top frame's variables (name/value/type). Read-only. Suspend first (edt_debug_control) "
                + "or wait for a breakpoint hit to see frames.");
        t.addProperty("descriptionRu",
                "ЧТЕНИЕ (Фаза 3): осмотреть живую сессию отладки — список потоков (debug-объекты: "
                + "фоновые задания, серверные вызовы, клиенты) и, для ПРИОСТАНОВЛЕННЫХ потоков, кадры стека BSL "
                + "+ переменные верхнего кадра (имя/значение/тип). Только чтение. Чтобы увидеть кадры, сначала "
                + "приостановите (edt_debug_control) или дождитесь срабатывания точки останова.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String sessionId = getStr(args, "sessionId");
        if (sessionId == null) {
            return McpServer.toolError("sessionId is required");
        }
        try {
            EdtModelGateway.InspectResult res = gateway.inspectDebug(sessionId);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("sessionId", res.sessionId);
            o.addProperty("targetTerminated", res.targetTerminated);
            o.addProperty("threadCount", res.threadCount);
            JsonArray threads = new JsonArray();
            for (EdtModelGateway.DbgThread th : res.threads) {
                JsonObject to = new JsonObject();
                if (th.name != null) {
                    to.addProperty("name", th.name);
                }
                if (th.type != null) {
                    to.addProperty("type", th.type);
                }
                to.addProperty("suspended", th.suspended);
                if (!th.frames.isEmpty()) {
                    JsonArray frames = new JsonArray();
                    for (EdtModelGateway.DbgFrame f : th.frames) {
                        JsonObject fo = new JsonObject();
                        fo.addProperty("level", f.level);
                        fo.addProperty("line", f.line);
                        if (f.signature != null) {
                            fo.addProperty("signature", f.signature);
                        }
                        if (f.source != null) {
                            fo.addProperty("source", f.source);
                        }
                        if (!f.variables.isEmpty()) {
                            JsonArray vars = new JsonArray();
                            for (EdtModelGateway.DbgVar v : f.variables) {
                                JsonObject vo = new JsonObject();
                                vo.addProperty("name", v.name);
                                if (v.value != null) {
                                    vo.addProperty("value", v.value);
                                }
                                if (v.type != null) {
                                    vo.addProperty("type", v.type);
                                }
                                vars.add(vo);
                            }
                            fo.add("variables", vars);
                        }
                        frames.add(fo);
                    }
                    to.add("frames", frames);
                }
                threads.add(to);
            }
            o.add("threads", threads);
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_debug_inspect failed: " + e.getMessage());
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
