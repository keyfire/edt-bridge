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
 * MCP tool: edt_infobase_config_state - READ. Answers whether an infobase actually RUNS the
 * configuration it holds: in 1C the code a session executes is the DATABASE configuration, a separate
 * thing from the configuration being edited, and the two diverge whenever changes are loaded but not
 * applied.
 *
 * <p>The answer comes from the platform, through the configurator agent: the update is started and its
 * confirmation refused, so the platform itself says whether anything is pending and what exactly. An
 * earlier version compared dumped configuration files instead and had to be withdrawn - it reported
 * "differs" after a successful dynamic update, because that leaves container bookkeeping different
 * while the content matches.
 */
public final class InfobaseConfigStateTool {

    private final DesignerAgentGateway gateway = new DesignerAgentGateway();

    public String name() {
        return "edt_infobase_config_state";
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("infobase", strProp("Infobase registered in EDT - name or uuid, as edt_infobases "
                + "lists them. File and server infobases are both reachable."));
        props.add("extension", strProp("Ask about one extension's database configuration instead of "
                + "the configuration itself. Optional."));
        props.add("infobaseUser", strProp("1C infobase user, e.g. Администратор. The agent "
                + "authenticates AS THIS USER; leave empty for an infobase without users."));
        props.add("infobasePassword", strProp("That user's password (optional). Never echoed back."));
        props.add("platformVersion", strProp("Platform version line for the configurator, e.g. "
                + "8.5.1.1423. It has to match the server the infobase runs on. Optional."));

        JsonArray required = new JsonArray();
        required.add("infobase");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", required);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "READ: is the infobase's DATABASE configuration - the one sessions execute - up to "
                + "date, or is an update still pending? Asks the platform through a configurator agent "
                + "and REFUSES the confirmation, so nothing is applied; when an update is pending the "
                + "answer carries the full list of structure changes that are waiting. Works for a "
                + "server infobase that authenticates its users, which is where the other transports "
                + "stop. Starts a configurator agent for the infobase if none is running (it is kept "
                + "for later calls - edt_designer_agent lists and stops them).");
        t.addProperty("descriptionRu",
                "ЧТЕНИЕ: применена ли КОНФИГУРАЦИЯ БАЗЫ ДАННЫХ – та, которую исполняют сеансы, – или "
                + "обновление ещё ждёт? Спрашивает саму платформу через агент конфигуратора и "
                + "ОТКАЗЫВАЕТСЯ подтверждать, поэтому ничего не применяется; если обновление ждёт, в "
                + "ответе полный список изменений структуры. Работает и для серверной базы с "
                + "аутентификацией 1С – там, где остальные транспорты бессильны. Если агент для базы не "
                + "запущен, поднимает его (и оставляет для следующих вызовов – список и остановка "
                + "инструментом edt_designer_agent).");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        try {
            DesignerAgentGateway.ConfigStateResult res = gateway.databaseConfigState(
                    getStr(args, "infobase"),
                    getStr(args, "extension"),
                    getStr(args, "infobaseUser"),
                    getStr(args, "infobasePassword"),
                    getStr(args, "platformVersion"));
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            if (res.infobase != null) {
                o.addProperty("infobase", res.infobase);
            }
            if (res.platform != null) {
                o.addProperty("platform", res.platform);
            }
            if (res.extension != null) {
                o.addProperty("extension", res.extension);
            }
            if (res.databaseConfigUpToDate != null) {
                o.addProperty("databaseConfigUpToDate", res.databaseConfigUpToDate.booleanValue());
                o.addProperty("verdict", res.databaseConfigUpToDate.booleanValue()
                        ? "applied" : "update pending");
                o.addProperty("pendingChangeCount", res.pendingChangeCount);
                if (!res.pendingChanges.isEmpty()) {
                    JsonArray changes = new JsonArray();
                    res.pendingChanges.forEach(changes::add);
                    o.add("pendingChanges", changes);
                }
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
