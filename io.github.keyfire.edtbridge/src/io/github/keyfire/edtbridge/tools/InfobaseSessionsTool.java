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

import java.util.ArrayList;
import java.util.List;

import io.github.keyfire.edtbridge.edt.ClusterGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_infobase_sessions - the cluster's sessions, listed and (with force) ended.
 *
 * <p>The case that pays for it: a designer session holds the infobase's configuration lock, and a
 * designer that was killed instead of shut down leaves that session behind. Every later attempt then
 * fails with a lock error that reads as if somebody else were configuring the base.
 */
public final class InfobaseSessionsTool {

    private final ClusterGateway gateway = new ClusterGateway();

    public String name() {
        return "edt_infobase_sessions";
    }

    /** Write tool - it can end other people's sessions. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject action = new JsonObject();
        action.addProperty("type", "string");
        action.addProperty("description", "list (default) or terminate.");
        JsonArray actions = new JsonArray();
        actions.add("list");
        actions.add("terminate");
        action.add("enum", actions);

        JsonObject sessions = new JsonObject();
        sessions.addProperty("type", "array");
        sessions.addProperty("description", "Session uuids to terminate. Omitted, terminate ends every "
                + "session matching infobase and appId - which is why it also needs force.");
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        sessions.add("items", items);

        JsonObject props = new JsonObject();
        props.add("server", strProp("Cluster host, optionally with a port (the 1C server, not the "
                + "DBMS one). Required."));
        props.add("infobase", strProp("Infobase name IN THE CLUSTER - narrows the list to it. "
                + "Optional."));
        props.add("appId", strProp("Keep only sessions of this application: Designer, 1CV8C, "
                + "BackgroundJob, WebClient, ... Optional."));
        props.add("sessions", sessions);
        props.add("clusterUser", strProp("Cluster administrator, when the cluster has one."));
        props.add("clusterPassword", strProp("That administrator's password. Never echoed back."));
        props.add("reason", strProp("Message shown to the user whose session is ended."));
        props.add("platformVersion", strProp("Platform version line to pick rac from, e.g. 8.5.1. "
                + "Optional."));
        props.add("action", action);
        props.add("apply", boolProp("terminate: false (default) reports what would be ended."));
        props.add("force", boolProp("terminate: required on top of apply - ending a session interrupts "
                + "someone's work."));

        JsonArray required = new JsonArray();
        required.add("server");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", required);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "The 1C cluster's sessions, through rac: list them (optionally for one infobase or one "
                + "application) and end them. This is the one thing neither the configurator agent nor "
                + "ibcmd can do - sessions live in the cluster manager, and ibcmd's session mode only "
                + "addresses a stand-alone ibcmd server. Reach for it when an infobase refuses to be "
                + "configured (\"Ошибка блокировки информационной базы для конфигурирования\"): a "
                + "designer session that was killed rather than closed still holds the configuration "
                + "lock, and it shows up here as a Designer session. Terminating is dry-run by default "
                + "and needs force on top of apply.");
        t.addProperty("descriptionRu",
                "Сеансы кластера 1С через rac: список (можно по одной базе или одному виду приложения) "
                + "и завершение. Это единственное, чего не умеют ни агент конфигуратора, ни ibcmd – "
                + "сеансы живут в менеджере кластера, а режим session у ibcmd адресует только "
                + "автономный сервер ibcmd. Нужен, когда база отказывается конфигурироваться (\"Ошибка "
                + "блокировки информационной базы для конфигурирования\"): убитый, а не закрытый сеанс "
                + "конфигуратора продолжает держать блокировку и виден здесь как сеанс Designer. "
                + "Завершение по умолчанию dry-run и требует force вдобавок к apply.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        try {
            String action = getStr(args, "action");
            ClusterGateway.SessionsResult res;
            if ("terminate".equalsIgnoreCase(action)) {
                res = gateway.terminate(getStr(args, "server"), getStr(args, "infobase"),
                        getStr(args, "appId"), getList(args, "sessions"), getStr(args, "clusterUser"),
                        getStr(args, "clusterPassword"), getStr(args, "infobaseUser"),
                        getStr(args, "infobasePassword"), getStr(args, "platformVersion"),
                        getStr(args, "reason"), getBool(args, "apply"), getBool(args, "force"));
            } else {
                res = gateway.sessions(getStr(args, "server"), getStr(args, "infobase"),
                        getStr(args, "appId"), getStr(args, "clusterUser"),
                        getStr(args, "clusterPassword"), getStr(args, "infobaseUser"),
                        getStr(args, "infobasePassword"), getStr(args, "platformVersion"));
            }

            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            if (res.server != null) {
                o.addProperty("server", res.server);
            }
            if (res.cluster != null) {
                o.addProperty("cluster", res.cluster);
            }
            if (res.infobase != null) {
                o.addProperty("infobase", res.infobase);
            }
            if (res.platform != null) {
                o.addProperty("platform", res.platform);
            }
            o.addProperty("sessionCount", res.sessions.size());
            JsonArray sessions = new JsonArray();
            for (ClusterGateway.Session s : res.sessions) {
                JsonObject one = new JsonObject();
                one.addProperty("session", s.id);
                one.addProperty("infobase", s.infobase);
                one.addProperty("userName", s.userName);
                one.addProperty("host", s.host);
                one.addProperty("appId", s.appId);
                one.addProperty("startedAt", s.startedAt);
                sessions.add(one);
            }
            o.add("sessions", sessions);
            if (!res.terminated.isEmpty()) {
                JsonArray terminated = new JsonArray();
                res.terminated.forEach(terminated::add);
                o.add("terminated", terminated);
            }
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_infobase_sessions failed: " + e.getMessage());
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

    private static boolean getBool(JsonObject a, String k) {
        return a.has(k) && !a.get(k).isJsonNull() && a.get(k).getAsBoolean();
    }

    private static List<String> getList(JsonObject a, String k) {
        List<String> values = new ArrayList<>();
        if (a.has(k) && a.get(k).isJsonArray()) {
            for (JsonElement e : a.getAsJsonArray(k)) {
                if (!e.isJsonNull()) {
                    values.add(e.getAsString());
                }
            }
        }
        return values;
    }
}
