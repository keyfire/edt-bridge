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

import java.util.Map;

import io.github.keyfire.edtbridge.edt.DesignerAgentGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_delete_extension - WRITE. Removes an extension from an infobase, the last step of the
 * lifecycle the bridge could not perform: it could create, load and configure one, but not take it off.
 */
public final class DeleteExtensionTool {

    private final DesignerAgentGateway gateway = new DesignerAgentGateway();

    public String name() {
        return "edt_delete_extension";
    }

    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("infobase", strProp("Infobase registered in EDT - name or uuid - or an address: "
                + "\"host\\base\" for a server infobase, the directory of a file one."));
        props.add("name", strProp("Extension name as the INFOBASE holds it (edt_extension_properties "
                + "lists them). Required."));
        props.add("infobaseUser", strProp("1C infobase user, e.g. Администратор. Empty for an infobase "
                + "without users."));
        props.add("infobasePassword", strProp("That user's password. Never echoed back."));
        props.add("platformVersion", strProp("Platform version line for the configurator, e.g. "
                + "8.5.1.1423. It has to match the server the infobase runs on. Optional."));
        props.add("updateDatabaseConfig", boolProp("Apply the database configuration afterwards "
                + "(default true), so sessions stop running the removed extension's code."));
        props.add("apply", boolProp("false (default) = report what would be deleted."));
        props.add("force", boolProp("Required on top of apply. An extension's configuration lives in "
                + "the infobase, not in a project - deleting it is not something a rebuild undoes."));

        JsonArray required = new JsonArray();
        required.add("infobase");
        required.add("name");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", required);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE (dry-run by default, needs force): remove an extension from an infobase through "
                + "the configurator agent, which authenticates as the infobase user - so a server "
                + "infobase with users is reachable. The dry-run first reads the extension's current "
                + "properties, so an empty answer means the name is wrong rather than the deletion "
                + "being a no-op. IRREVERSIBLE from here: the extension's configuration lives in the "
                + "infobase, and only a project or a .cfe you still have can put it back.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ (по умолчанию dry-run, нужен force): удаляет расширение из информационной базы "
                + "через агент конфигуратора, который проходит аутентификацию как пользователь базы – "
                + "поэтому доступна и серверная база с пользователями. Dry-run сначала читает текущие "
                + "свойства расширения, поэтому пустой ответ означает неверное имя, а не то, что "
                + "удалять нечего. НЕОБРАТИМО: конфигурация расширения хранится в базе, вернуть её "
                + "может только проект или сохранённый .cfe.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        try {
            DesignerAgentGateway.ExtensionsResult res = gateway.deleteExtension(
                    getStr(args, "infobase"),
                    getStr(args, "name"),
                    getStr(args, "infobaseUser"),
                    getStr(args, "infobasePassword"),
                    getStr(args, "platformVersion"),
                    !args.has("updateDatabaseConfig") || args.get("updateDatabaseConfig").isJsonNull()
                            || args.get("updateDatabaseConfig").getAsBoolean(),
                    getBool(args, "apply"),
                    getBool(args, "force"));

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
            for (Map<String, Object> ext : res.extensions) {
                JsonObject e = new JsonObject();
                ext.forEach((key, value) -> {
                    if (value instanceof Boolean) {
                        e.addProperty(key, (Boolean) value);
                    } else if (value != null) {
                        e.addProperty(key, String.valueOf(value));
                    }
                });
                exts.add(e);
            }
            o.add("extensions", exts);
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_delete_extension failed: " + e.getMessage());
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
}
