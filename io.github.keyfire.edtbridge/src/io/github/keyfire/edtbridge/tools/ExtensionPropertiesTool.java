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

import io.github.keyfire.edtbridge.edt.BslGateway;
import io.github.keyfire.edtbridge.edt.PlatformGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_extension_properties - read and set the properties an extension carries inside an
 * INFOBASE (safe mode, dangerous-action protection, active, ...) through {@code ibcmd extension}.
 * These are registration properties of the infobase, which neither building a .cfe nor updating from
 * EDT decides - and both leave safe mode and dangerous-action protection ON, which an extension that
 * changes methods cannot run under. Reading is free; changing is token-gated and dry-run by default.
 */
public final class ExtensionPropertiesTool {

    private final PlatformGateway gateway = new PlatformGateway();
    private final BslGateway bsl = new BslGateway();

    public String name() {
        return "edt_extension_properties";
    }

    /** Write tool - it can change how an extension is registered in an infobase. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("databasePath", strProp("File infobase directory (ibcmd --database-path). Use this "
                + "or the DBMS coordinates below."));
        props.add("dbms", strProp("DBMS kind for a server infobase: MSSQLServer, PostgreSQL, IBMDB2, "
                + "OracleDatabase"));
        props.add("dbServer", strProp("DBMS server for a server infobase"));
        props.add("dbName", strProp("Database name for a server infobase"));
        props.add("dbUser", strProp("DBMS user"));
        props.add("dbPassword", strProp("DBMS password"));
        props.add("infobase", strProp("Name or uuid of an infobase REGISTERED IN EDT (as edt_infobases "
                + "lists them), instead of spelling the address out. Works for a FILE infobase, whose "
                + "path EDT knows. A server infobase is registered by its 1C cluster coordinate, which "
                + "does not carry the DBMS coordinates ibcmd needs - for those pass dbms + dbName."));
        props.add("infobaseUser", strProp("1C infobase user, e.g. Администратор. Required when the "
                + "infobase authenticates its users - this is the 1C account, not the DBMS one."));
        props.add("infobasePassword", strProp("1C infobase password (optional). Never echoed back."));
        props.add("name", strProp("Extension name as registered in the infobase; omit to list every "
                + "extension. Required to change anything."));
        props.add("safeMode", boolProp("Set safe mode. false for an extension that changes methods of "
                + "the base configuration."));
        props.add("unsafeActionProtection", boolProp("Set protection from dangerous actions. false for "
                + "an extension that changes methods of the base configuration."));
        props.add("active", boolProp("Set whether the extension is active"));
        props.add("projectName", strProp("Optional extension project in the workspace - when given, "
                + "the result also says whether it changes methods, i.e. whether the two flags above "
                + "have to be off."));
        props.add("platform", strProp("Optional platform version line to pick the ibcmd install, e.g. "
                + "8.5.1; it must match the infobase version"));
        props.add("apply", boolProp("false (default) = report current properties and the planned "
                + "change. true = perform the update, then read the properties back."));

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", new JsonArray());

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "Read and set the properties an extension carries INSIDE an infobase - safe mode, "
                + "protection from dangerous actions, active, scope - via ibcmd extension info|list|"
                + "update. These belong to the infobase registration, not to the project: neither "
                + "edt_build_extension nor edt_update_infobase decides them, and a freshly registered "
                + "extension gets safe mode and dangerous-action protection ON. An extension that "
                + "CHANGES METHODS of the base configuration cannot run under those, so both must be "
                + "cleared - pass projectName and the result tells you whether that applies. Address "
                + "the infobase by databasePath (file) or by the DBMS coordinates. Dry-run by default; "
                + "changing requires a configured token.");
        t.addProperty("descriptionRu",
                "Чтение и установка свойств, с которыми расширение зарегистрировано В ИНФОРМАЦИОННОЙ "
                + "БАЗЕ – безопасный режим, защита от опасных действий, активность, область действия – "
                + "через ibcmd extension info|list|update. Это свойства регистрации в базе, а не "
                + "проекта: их не задают ни edt_build_extension, ни edt_update_infobase, а только что "
                + "зарегистрированное расширение получает безопасный режим и защиту от опасных "
                + "действий ВКЛЮЧЁННЫМИ. Расширение, которое МЕНЯЕТ МЕТОДЫ базовой конфигурации, под "
                + "ними не работает, поэтому оба флага нужно снять – передайте projectName, и результат "
                + "скажет, тот ли это случай. База адресуется databasePath (файловая) либо реквизитами "
                + "СУБД. По умолчанию dry-run; изменение требует токен.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String projectName = getStr(args, "projectName");
        boolean apply = getBool(args, "apply") != null && Boolean.TRUE.equals(getBool(args, "apply"));
        try {
            PlatformGateway.ExtensionPropertiesResult res = gateway.extensionProperties(
                    getStr(args, "infobase"),
                    getStr(args, "databasePath"), getStr(args, "dbms"), getStr(args, "dbServer"),
                    getStr(args, "dbName"), getStr(args, "dbUser"), getStr(args, "dbPassword"),
                    getStr(args, "infobaseUser"), getStr(args, "infobasePassword"),
                    getStr(args, "name"), getBool(args, "safeMode"),
                    getBool(args, "unsafeActionProtection"), getBool(args, "active"),
                    getStr(args, "platform"), apply);

            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            if (res.infobase != null) {
                o.addProperty("infobase", res.infobase);
            }
            if (res.platform != null) {
                o.addProperty("platform", res.platform);
            }
            if (res.name != null) {
                o.addProperty("name", res.name);
            }
            JsonArray exts = new JsonArray();
            for (PlatformGateway.ExtensionFlags f : res.extensions) {
                JsonObject e = new JsonObject();
                e.addProperty("name", f.name);
                if (f.version != null) {
                    e.addProperty("version", f.version);
                }
                addBool(e, "active", f.active);
                if (f.purpose != null) {
                    e.addProperty("purpose", f.purpose);
                }
                addBool(e, "safeMode", f.safeMode);
                if (f.securityProfileName != null) {
                    e.addProperty("securityProfileName", f.securityProfileName);
                }
                addBool(e, "unsafeActionProtection", f.unsafeActionProtection);
                addBool(e, "usedInDistributedInfobase", f.usedInDistributedInfobase);
                if (f.scope != null) {
                    e.addProperty("scope", f.scope);
                }
                exts.add(e);
            }
            o.add("extensions", exts);
            if (!res.changed.isEmpty()) {
                JsonArray ch = new JsonArray();
                res.changed.forEach(ch::add);
                o.add("changed", ch);
            }
            if (projectName != null && !projectName.isBlank()) {
                BslGateway.MethodChangesResult mc = bsl.methodChanges(projectName);
                JsonObject m = new JsonObject();
                m.addProperty("project", projectName);
                m.addProperty("changesMethods", mc.changesMethods);
                m.addProperty("count", mc.count);
                if (mc.message != null) {
                    m.addProperty("message", mc.message);
                }
                o.add("methodChanges", m);
                if (mc.changesMethods) {
                    o.addProperty("required", "safeMode=false, unsafeActionProtection=false");
                }
            }
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.warning != null) {
                o.addProperty("warning", res.warning);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_extension_properties failed: " + e.getMessage());
        }
    }

    private static void addBool(JsonObject o, String key, Boolean value) {
        if (value != null) {
            o.addProperty(key, value.booleanValue());
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

    private static Boolean getBool(JsonObject a, String k) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? Boolean.valueOf(a.get(k).getAsBoolean()) : null;
    }
}
