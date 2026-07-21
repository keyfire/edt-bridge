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

import io.github.keyfire.edtbridge.core.IbcmdArgs;
import io.github.keyfire.edtbridge.edt.IbcmdGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_infobase_config_state - READ. Answers whether an infobase actually RUNS the
 * configuration it holds: in 1C the code a session executes is the database configuration, a separate
 * thing from the configuration being edited, and they diverge whenever changes are loaded but not
 * applied. Establishes it with ibcmd by dumping both configurations and comparing them, so it works
 * against a clustered infobase by DBMS coordinates - no cluster access and no EDT session needed.
 */
public final class InfobaseConfigStateTool {

    private final IbcmdGateway gateway = new IbcmdGateway();

    public String name() {
        return "edt_infobase_config_state";
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("databasePath", strProp("File infobase directory. Use this OR the DBMS coordinates."));
        props.add("dbms", strProp("DBMS kind for a DBMS-hosted infobase: MSSQLServer, PostgreSQL, "
                + "IBMDB2, OracleDatabase."));
        props.add("dbServer", strProp("DBMS host (optional for a local instance)."));
        props.add("dbName", strProp("Database name (required with dbms)."));
        props.add("dbUser", strProp("Database user (optional)."));
        props.add("dbPassword", strProp("Database password (optional). Never echoed back."));
        props.add("platformVersion", strProp("Platform version line to prefer when picking the ibcmd "
                + "install, e.g. 8.5.1.1302. ibcmd is absent from some builds, so the newest install "
                + "is not always the right one. Optional."));

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", new JsonArray());

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "READ: whether an infobase RUNS the configuration it holds - does its database "
                + "configuration match the main one. Sessions execute the DATABASE configuration, so "
                + "loaded-but-not-applied changes mean every session still serves the previous code, "
                + "while a project-to-infobase comparison happily reports them as equal. Established "
                + "with ibcmd (dumps both configurations and compares), addressing the infobase by file "
                + "path or DBMS coordinates - works for a clustered infobase without cluster access. "
                + "Returns databaseConfigMatches plus both hashes.");
        t.addProperty("descriptionRu",
                "ЧТЕНИЕ: работает ли информационная база на той конфигурации, которую хранит – совпадает "
                + "ли конфигурация базы данных с основной. Сеансы исполняют КОНФИГУРАЦИЮ БАЗЫ ДАННЫХ, "
                + "поэтому загруженные, но не применённые изменения означают, что все сеансы по-прежнему "
                + "работают на старом коде, хотя сравнение проекта с базой покажет равенство. "
                + "Определяется через ibcmd (выгружает обе конфигурации и сравнивает); база адресуется "
                + "путём к файловой базе или координатами СУБД – для кластерной базы доступ к кластеру "
                + "не нужен. Возвращает databaseConfigMatches и обе хеш-суммы.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        IbcmdArgs.Target target = IbcmdArgs.target(
                getStr(args, "databasePath"),
                getStr(args, "dbms"),
                getStr(args, "dbServer"),
                getStr(args, "dbName"),
                getStr(args, "dbUser"),
                getStr(args, "dbPassword"));
        try {
            IbcmdGateway.ConfigStateResult res =
                    gateway.configState(target, getStr(args, "platformVersion"));
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            if (res.infobase != null) {
                o.addProperty("infobase", res.infobase);
            }
            if (res.platform != null) {
                o.addProperty("platform", res.platform);
            }
            if (res.databaseConfigMatches != null) {
                o.addProperty("databaseConfigMatches", res.databaseConfigMatches.booleanValue());
            }
            if (res.mainConfigHash != null) {
                o.addProperty("mainConfigHash", res.mainConfigHash);
                o.addProperty("databaseConfigHash", res.databaseConfigHash);
            }
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_infobase_config_state failed: " + e.getMessage());
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
