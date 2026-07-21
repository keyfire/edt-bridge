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
 * MCP tool: edt_infobase_dump - WRITE. Dumps an infobase to a .dt file through ibcmd: the backup that
 * belongs before any configuration apply, and which the bridge previously had no way to take. Nothing
 * in the infobase changes, but a dump reads every byte of it, so it is token-gated and dry-run by
 * default like the other write tools.
 */
public final class InfobaseDumpTool {

    private final IbcmdGateway gateway = new IbcmdGateway();

    public String name() {
        return "edt_infobase_dump";
    }

    /** Write tool – the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("outputPath", strProp("Path of the .dt file to write. Refused if it already exists."));
        props.add("databasePath", strProp("File infobase directory. Use this OR the DBMS coordinates."));
        props.add("dbms", strProp("DBMS kind: MSSQLServer, PostgreSQL, IBMDB2, OracleDatabase."));
        props.add("dbServer", strProp("DBMS host (optional for a local instance)."));
        props.add("dbName", strProp("Database name (required with dbms)."));
        props.add("dbUser", strProp("DBMS user (optional). The DATABASE account, not the 1C one."));
        props.add("dbPassword", strProp("DBMS password (optional). Never echoed back."));
        props.add("infobaseUser", strProp("1C infobase user, e.g. Администратор. Required when the "
                + "infobase authenticates its users."));
        props.add("infobasePassword", strProp("1C infobase password (optional). Never echoed back."));
        props.add("platformVersion", strProp("Platform version line to prefer when picking the ibcmd "
                + "install, e.g. 8.5.1.1302. Optional."));
        props.add("apply", boolProp("false (default) = dry-run: resolve ibcmd, check the destination "
                + "and return the plan, write nothing. true = take the dump."));

        JsonArray req = new JsonArray();
        req.add("outputPath");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE: dump an infobase to a .dt file via ibcmd - the backup to take BEFORE applying a "
                + "configuration to the database. Addresses the infobase by file path or DBMS "
                + "coordinates, so a clustered infobase needs no cluster access. Dry-run by default; "
                + "refuses to overwrite an existing file. Nothing in the infobase changes, but the dump "
                + "reads all of its data, so a token is required.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ: выгрузить информационную базу в файл .dt через ibcmd – резервная копия, которую "
                + "стоит снять ПЕРЕД применением конфигурации к базе данных. База адресуется путём или "
                + "координатами СУБД, поэтому для кластерной базы доступ к кластеру не нужен. По "
                + "умолчанию dry-run; перезаписывать существующий файл отказывается. В самой базе ничего "
                + "не меняется, но выгрузка читает все её данные, поэтому нужен токен.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String outputPath = getStr(args, "outputPath");
        if (outputPath == null) {
            return McpServer.toolError("outputPath is required");
        }
        IbcmdArgs.Target target = IbcmdArgs.target(
                getStr(args, "databasePath"),
                getStr(args, "dbms"),
                getStr(args, "dbServer"),
                getStr(args, "dbName"),
                getStr(args, "dbUser"),
                getStr(args, "dbPassword"),
                getStr(args, "infobaseUser"),
                getStr(args, "infobasePassword"));
        boolean apply = args.has("apply") && !args.get("apply").isJsonNull()
                && args.get("apply").getAsBoolean();
        try {
            IbcmdGateway.DumpResult res = gateway.dumpInfobase(target, outputPath,
                    getStr(args, "platformVersion"), apply);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            if (res.infobase != null) {
                o.addProperty("infobase", res.infobase);
            }
            if (res.platform != null) {
                o.addProperty("platform", res.platform);
            }
            if (res.outputPath != null) {
                o.addProperty("outputPath", res.outputPath);
            }
            if (res.sizeBytes >= 0) {
                o.addProperty("sizeBytes", res.sizeBytes);
            }
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_infobase_dump failed: " + e.getMessage());
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
}
