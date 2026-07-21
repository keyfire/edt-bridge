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

import io.github.keyfire.edtbridge.edt.DesignerAgentGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_designer_agent - lifecycle of the configurator agents the bridge drives.
 *
 * <p>An agent is a configurator started with {@code /AgentMode}: it holds an open infobase session and
 * takes commands over SSH, authenticating as the infobase user. Starting one is slow (a large
 * configuration takes its time to open) and it holds a session on the server, so agents are kept
 * between calls and stopped explicitly - this tool is how a caller sees and ends them.
 */
public final class DesignerAgentTool {

    private final DesignerAgentGateway gateway = new DesignerAgentGateway();

    public String name() {
        return "edt_designer_agent";
    }

    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject action = new JsonObject();
        action.addProperty("type", "string");
        action.addProperty("description", "list (default), start or stop.");
        JsonArray values = new JsonArray();
        values.add("list");
        values.add("start");
        values.add("stop");
        action.add("enum", values);

        JsonObject props = new JsonObject();
        props.add("action", action);
        props.add("infobase", strProp("Infobase registered in EDT - name or uuid. Required for start "
                + "and stop."));
        props.add("infobaseUser", strProp("1C infobase user the agent authenticates as, e.g. "
                + "Администратор. Empty for an infobase without users."));
        props.add("infobasePassword", strProp("That user's password (optional). Never echoed back."));
        props.add("platformVersion", strProp("Platform version line for the configurator, e.g. "
                + "8.5.1.1423. It has to match the server the infobase runs on. Optional."));

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", new JsonArray());

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "Manage the configurator agents the bridge uses to reach infobases: list them, start "
                + "one for an infobase, or stop it. An agent is a configurator in /AgentMode holding an "
                + "open infobase session, and it authenticates as the INFOBASE user - which is how the "
                + "bridge reaches a server infobase that authenticates its users. Agents are started on "
                + "demand by the tools that need them; stopping one frees the session it holds on the "
                + "server.");
        t.addProperty("descriptionRu",
                "Управление агентами конфигуратора, через которые мост обращается к информационным "
                + "базам: список, запуск для базы, остановка. Агент – это конфигуратор в режиме "
                + "/AgentMode с открытым сеансом базы; он проходит аутентификацию КАК ПОЛЬЗОВАТЕЛЬ "
                + "БАЗЫ, поэтому доступен и серверной базе с аутентификацией 1С. Агенты поднимаются по "
                + "требованию тех инструментов, которым нужны; остановка освобождает сеанс на сервере.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String action = getStr(args, "action");
        String infobase = getStr(args, "infobase");
        try {
            DesignerAgentGateway.AgentResult res;
            if ("start".equalsIgnoreCase(action)) {
                res = gateway.start(infobase, getStr(args, "infobaseUser"),
                        getStr(args, "infobasePassword"), getStr(args, "platformVersion"));
            } else if ("stop".equalsIgnoreCase(action)) {
                res = gateway.stop(infobase);
            } else {
                res = gateway.list();
            }

            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("started", res.started);
            o.addProperty("stopped", res.stopped);
            JsonArray agents = new JsonArray();
            for (DesignerAgentGateway.Agent a : res.agents) {
                JsonObject one = new JsonObject();
                one.addProperty("infobase", a.infobase);
                one.addProperty("connectionString", a.connectionString);
                one.addProperty("port", a.port);
                one.addProperty("pid", a.pid);
                one.addProperty("platform", a.platformVersion);
                one.addProperty("user", a.user);
                one.addProperty("baseDir", a.baseDir);
                agents.add(one);
            }
            o.add("agents", agents);
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_designer_agent failed: " + e.getMessage());
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
