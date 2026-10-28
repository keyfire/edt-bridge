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

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.edt.InfobaseRegistrationGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Register a server infobase in the running EDT's list. */
public final class RegisterInfobaseTool {
    private final InfobaseRegistrationGateway gateway = new InfobaseRegistrationGateway();
    public String name() { return "edt_register_infobase"; }
    public boolean isWrite() { return true; }
    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("name", ToolJson.strProp("Display name in EDT's infobase list"));
        props.add("connect", ToolJson.strProp("Server address: Srvr=\"host\";Ref=\"base\"; or host\\base. Credentials are not accepted here."));
        props.add("version", ToolJson.strProp("Platform line or build, for example 8.3.27 or 8.3.27.1989"));
        props.add("user", ToolJson.strProp("Optional 1C user name stored as the launch /N parameter; no password is stored"));
        props.add("apply", ToolJson.boolProp("false = validate only (default); true = register the existing infobase"));
        return ToolJson.descriptor(name(),
                "WRITE: register an existing server infobase in EDT through its infobase manager. Dry-run by default. "
                + "Refuses duplicate names or server addresses, verifies registration by UUID and updates the live list. "
                + "Does not connect to the server or create/change its database. Requires a token.",
                "ЗАПИСЬ: зарегистрировать существующую серверную ИБ через менеджер информационных баз EDT. По умолчанию dry-run. "
                + "Отклоняет повторное имя или серверный адрес, проверяет регистрацию по UUID и обновляет список работающей EDT. "
                + "Не подключается к серверу и не создает и не меняет его базу данных. Требует токен.",
                props, "name", "connect", "version");
    }
    public JsonObject call(JsonObject args) {
        var result = gateway.register(ToolJson.getStr(args, "name"), ToolJson.getStr(args, "connect"),
                ToolJson.getStr(args, "version"), ToolJson.getStr(args, "user"), ToolJson.getBool(args, "apply"));
        return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }
}
