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
 * MCP tool: edt_update_database_config - WRITE. Applies the DATABASE configuration, the step that
 * makes running sessions execute the code the infobase holds.
 *
 * <p>Distinct from {@code edt_update_infobase}, which loads an EDT project INTO the infobase: that
 * changes the configuration being edited, and a session goes on running the previous one until this
 * step happens.
 */
public final class UpdateDatabaseConfigTool {

    private final DesignerAgentGateway gateway = new DesignerAgentGateway();

    public String name() {
        return "edt_update_database_config";
    }

    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject termination = new JsonObject();
        termination.addProperty("type", "string");
        termination.addProperty("description", "What to do when an exclusive lock is needed and "
                + "sessions hold the infobase: disable (default - fail instead), prompt, or force "
                + "(END other people's sessions).");
        JsonArray modes = new JsonArray();
        modes.add("disable");
        modes.add("prompt");
        modes.add("force");
        termination.add("enum", modes);

        JsonObject apply = new JsonObject();
        apply.addProperty("type", "boolean");
        apply.addProperty("description", "false (default) reports what would be applied and applies "
                + "nothing; true applies it.");

        JsonObject props = new JsonObject();
        props.add("infobase", strProp("Infobase registered in EDT - name or uuid."));
        props.add("extension", strProp("Apply one extension's database configuration instead of the "
                + "configuration itself. Optional."));
        props.add("sessionTermination", termination);
        props.add("terminationMessage", strProp("Message shown to users whose sessions are ended "
                + "(with sessionTermination=force). Optional."));
        props.add("infobaseUser", strProp("1C infobase user, e.g. Администратор. The agent "
                + "authenticates AS THIS USER; leave empty for an infobase without users."));
        props.add("infobasePassword", strProp("That user's password (optional). Never echoed back."));
        props.add("platformVersion", strProp("Platform version line for the configurator, e.g. "
                + "8.5.1.1423. It has to match the server the infobase runs on. Optional."));
        props.add("apply", apply);

        JsonArray required = new JsonArray();
        required.add("infobase");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", required);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE (dry-run by default): apply an infobase's DATABASE configuration - the step "
                + "that makes running sessions execute the configuration the infobase holds. Loading a "
                + "project into an infobase (edt_update_infobase) does NOT do this; until it happens, "
                + "every session keeps running the previous code, which is what a freshly added HTTP "
                + "route answering 404 looks like. The dry-run starts the same operation and refuses "
                + "its confirmation, so it reports exactly the structure changes that would be applied. "
                + "sessionTermination=force ends other people's sessions when an exclusive lock is "
                + "needed - the whole deny-sessions / terminate / apply procedure in one call. Driven "
                + "through a configurator agent, so a server infobase that authenticates its users is "
                + "reachable.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ (по умолчанию dry-run): применяет КОНФИГУРАЦИЮ БАЗЫ ДАННЫХ – шаг, после "
                + "которого работающие сеансы начинают исполнять ту конфигурацию, которую хранит база. "
                + "Загрузка проекта в базу (edt_update_infobase) этого НЕ делает: до применения каждый "
                + "сеанс исполняет прежний код – именно так выглядит только что добавленный маршрут "
                + "HTTP-сервиса, отвечающий 404. Dry-run запускает ту же операцию и отказывается её "
                + "подтверждать, поэтому показывает точный список изменений структуры. "
                + "sessionTermination=force завершает чужие сеансы, когда нужна монопольная блокировка, "
                + "– вся процедура блокировки, завершения сеансов и применения одним вызовом. Работает "
                + "через агент конфигуратора, поэтому доступна и серверная база с аутентификацией 1С.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        try {
            DesignerAgentGateway.UpdateResult res = gateway.updateDatabaseConfiguration(
                    getStr(args, "infobase"),
                    getStr(args, "extension"),
                    getStr(args, "sessionTermination"),
                    getStr(args, "terminationMessage"),
                    getStr(args, "infobaseUser"),
                    getStr(args, "infobasePassword"),
                    getStr(args, "platformVersion"),
                    getBool(args, "apply"));
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            if (res.infobase != null) {
                o.addProperty("infobase", res.infobase);
            }
            if (res.platform != null) {
                o.addProperty("platform", res.platform);
            }
            if (res.extension != null) {
                o.addProperty("extension", res.extension);
            }
            o.addProperty("sessionTermination", res.sessionTermination);
            o.addProperty("changeCount", res.changes.size());
            if (!res.changes.isEmpty()) {
                JsonArray changes = new JsonArray();
                res.changes.forEach(changes::add);
                o.add("changes", changes);
            }
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_update_database_config failed: " + e.getMessage());
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

    private static boolean getBool(JsonObject a, String k) {
        return a.has(k) && !a.get(k).isJsonNull() && a.get(k).getAsBoolean();
    }
}
