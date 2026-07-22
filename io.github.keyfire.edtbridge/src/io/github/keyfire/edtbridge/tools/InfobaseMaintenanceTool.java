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
 * MCP tool: edt_infobase_maintenance - a maintenance window around a database update.
 *
 * <p>The case that pays for it: taking the exclusive lock on a lively infobase fails as long as the
 * application spawns fresh BackgroundJob sessions every minute - terminating them is pointless while
 * scheduled jobs are allowed to start. Raising {@code scheduled-jobs-deny} first lets those sessions
 * drain BY THEMSELVES, and nothing has to be killed.
 */
public final class InfobaseMaintenanceTool {

    private final ClusterGateway gateway = new ClusterGateway();

    public String name() {
        return "edt_infobase_maintenance";
    }

    /** Write tool - it flips infobase-level denial flags. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject action = new JsonObject();
        action.addProperty("type", "string");
        action.addProperty("description", "status (default): report the flags and the sessions. "
                + "begin: raise the flags, then watch the sessions drain. end: lower the flags.");
        JsonArray actions = new JsonArray();
        actions.add("status");
        actions.add("begin");
        actions.add("end");
        action.add("enum", actions);

        JsonObject allowedAppIds = new JsonObject();
        allowedAppIds.addProperty("type", "array");
        allowedAppIds.addProperty("description", "Sessions of these applications do not block the "
                + "update (default: Designer). Everything else is reported as a blocker.");
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        allowedAppIds.add("items", items);

        JsonObject waitSeconds = new JsonObject();
        waitSeconds.addProperty("type", "integer");
        waitSeconds.addProperty("description", "begin: how long to watch the drain before reporting "
                + "(default 90; 0 looks once). BackgroundJob sessions are short - with the flag up "
                + "they usually drain within a minute.");

        JsonObject props = new JsonObject();
        props.add("server", strProp("Cluster host, optionally with a port (the 1C server, not the "
                + "DBMS one). Required."));
        props.add("infobase", strProp("Infobase name IN THE CLUSTER. Required - the denial flags "
                + "belong to one infobase."));
        props.add("action", action);
        props.add("allowedAppIds", allowedAppIds);
        props.add("sessionsDeny", boolProp("begin/end: also deny NEW sessions, not only scheduled "
                + "jobs. Mind that this can lock out the updater too - set permissionCode to keep "
                + "a door open."));
        props.add("permissionCode", strProp("begin with sessionsDeny: the pass-code that lets a "
                + "session in while sessions are denied."));
        props.add("deniedMessage", strProp("begin with sessionsDeny: message shown to a refused "
                + "session."));
        props.add("waitSeconds", waitSeconds);
        props.add("clusterUser", strProp("Cluster administrator, when the cluster has one."));
        props.add("clusterPassword", strProp("That administrator's password. Never echoed back."));
        props.add("infobaseUser", strProp("Infobase administrator - the flags and the narrowed "
                + "session list need it; a bare call answers \"Недостаточно прав пользователя\"."));
        props.add("infobasePassword", strProp("That user's password. Never echoed back."));
        props.add("platformVersion", strProp("Platform version line to pick rac from, e.g. 8.5.1. "
                + "Optional."));
        props.add("apply", boolProp("begin/end: false (default) reports the plan and changes "
                + "nothing. The flags are reversible, so force is not required."));

        JsonArray required = new JsonArray();
        required.add("server");
        required.add("infobase");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", required);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "A maintenance window around a database-configuration update, through rac: begin "
                + "raises scheduled-jobs-deny (and optionally sessions-deny with a permission code), "
                + "watches the session list until only the allowed applications remain and reports "
                + "\"clear to update\"; end lowers the flags; status just reports. The point: on a "
                + "lively base BackgroundJob sessions respawn every minute, so terminating them is "
                + "useless - with the flag up they drain by themselves and nothing has to be killed. "
                + "Needs the infobase administrator. The flags live in the cluster: the configurator "
                + "agent has no operation for them, rac is the route. Terminate what refuses to "
                + "drain with edt_infobase_sessions.");
        t.addProperty("descriptionRu",
                "Окно обслуживания вокруг обновления конфигурации базы данных, через rac: begin "
                + "поднимает scheduled-jobs-deny (и, по запросу, sessions-deny с кодом доступа), "
                + "ждёт, пока в списке сеансов останутся только разрешённые приложения, и отвечает "
                + "\"clear to update\"; end опускает флаги; status только отчитывается. Смысл: на "
                + "живой базе сеансы BackgroundJob пересоздаются каждую минуту, и завершать их "
                + "бесполезно – с поднятым флагом они иссякают сами, убивать никого не нужно. Нужен "
                + "администратор информационной базы. Флаги живут в кластере: у агента конфигуратора "
                + "операции для них нет, путь – rac. Кто не иссяк – завершается "
                + "edt_infobase_sessions.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        try {
            ClusterGateway.MaintenanceResult res = gateway.maintenance(
                    getStr(args, "action"), getStr(args, "server"), getStr(args, "infobase"),
                    getList(args, "allowedAppIds"), getBool(args, "sessionsDeny"),
                    getStr(args, "permissionCode"), getStr(args, "deniedMessage"),
                    getInt(args, "waitSeconds", 90), getStr(args, "clusterUser"),
                    getStr(args, "clusterPassword"), getStr(args, "infobaseUser"),
                    getStr(args, "infobasePassword"), getStr(args, "platformVersion"),
                    getBool(args, "apply"));

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
            if (res.flags != null) {
                JsonObject flags = new JsonObject();
                res.flags.forEach(flags::addProperty);
                o.add("flags", flags);
            }
            if (res.clearToUpdate != null) {
                o.addProperty("clearToUpdate", res.clearToUpdate);
            }
            o.addProperty("sessionCount", res.sessions.size());
            o.add("blockers", sessionArray(res.blockers));
            if (!res.sessions.isEmpty() && res.blockers.size() != res.sessions.size()) {
                o.add("sessions", sessionArray(res.sessions));
            }
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_infobase_maintenance failed: " + e.getMessage());
        }
    }

    private static JsonArray sessionArray(List<ClusterGateway.Session> sessions) {
        JsonArray out = new JsonArray();
        for (ClusterGateway.Session s : sessions) {
            JsonObject one = new JsonObject();
            one.addProperty("session", s.id);
            one.addProperty("infobase", s.infobase);
            one.addProperty("userName", s.userName);
            one.addProperty("host", s.host);
            one.addProperty("appId", s.appId);
            one.addProperty("startedAt", s.startedAt);
            out.add(one);
        }
        return out;
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

    private static int getInt(JsonObject a, String k, int fallback) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? a.get(k).getAsInt() : fallback;
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
