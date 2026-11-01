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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.edt.ApplicationGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Applications and targeted bridge-owned client sessions. */
public final class ApplicationsTool {
    private final ApplicationGateway gateway = new ApplicationGateway();
    public String name() { return "edt_applications"; }
    public boolean isWrite(JsonObject args) { return !"list".equals(action(args)); }
    private static String action(JsonObject args) {
        String action = ToolJson.getStr(args, "action"); return action == null ? "list" : action;
    }
    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        JsonObject action = ToolJson.strProp("list (default), associate, start or stop. Mutations require apply=true and a configured token.");
        JsonArray actions = new JsonArray(); for (String value : java.util.List.of("list", "associate", "start", "stop")) actions.add(value);
        action.add("enum", actions); props.add("action", action);
        props.add("projectName", ToolJson.strProp("Existing open EDT project."));
        props.add("infobase", ToolJson.strProp("Registered infobase UUID or exact name; required for associate. No new database is created."));
        props.add("applicationId", ToolJson.strProp("Application id from list; required for start/stop."));
        props.add("apply", ToolJson.boolProp("false (default) = validate and return a plan. true = apply associate/start/stop."));
        return ToolJson.descriptor(name(),
                "READ/WRITE: list native EDT applications, associate an existing registered infobase, or start/stop a thin client for a selected infobase application. Association is idempotent and does not update the database. Stop addresses only a session started by this bridge process; a client without a returned process handle must be closed in EDT. Credentials are taken from EDT settings and omitted from replies.",
                "ЧТЕНИЕ/ЗАПИСЬ: список штатных приложений EDT, связь проекта с уже зарегистрированной базой, запуск и остановка тонкого клиента выбранного приложения информационной базы. Повторная связь не создает дубликат и не обновляет базу. Остановка доступна только для сеанса этого процесса моста; клиент без возвращенного дескриптора нужно закрыть в EDT. Данные входа берутся из настроек EDT и в ответ не включаются.",
                props, "projectName");
    }
    public JsonObject call(JsonObject args) {
        try {
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(gateway.execute(
                    action(args), ToolJson.getStr(args, "projectName"), ToolJson.getStr(args, "infobase"),
                    ToolJson.getStr(args, "applicationId"), ToolJson.getBool(args, "apply"))));
        } catch (RuntimeException failure) { return McpServer.toolError("EDT application request failed"); }
    }
}
