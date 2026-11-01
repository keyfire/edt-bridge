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
import io.github.keyfire.edtbridge.edt.SupportStatusGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Native editability preflight for metadata writes. */
public final class SupportStatusTool {
    private final SupportStatusGateway gateway = new SupportStatusGateway();
    public String name() { return "edt_support_status"; }
    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", ToolJson.strProp("Open EDT project name."));
        props.add("objectFqn", ToolJson.strProp("Metadata object FQN, e.g. CommonModule.Worker or Catalog.Customers."));
        return ToolJson.descriptor(name(),
                "READ: check EDT's direct-edit policy and backing resource before a write. Returns EDITABLE, READ_ONLY or UNKNOWN. Missing objects or unavailable policy return UNKNOWN. Check the object that will be changed; this is a preflight and does not lock it against subsequent changes.",
                "ЧТЕНИЕ: проверить разрешение EDT на прямое редактирование объекта и доступность его файла для записи. Возвращает EDITABLE, READ_ONLY или UNKNOWN. Отсутствующий объект или недоступная служба дают UNKNOWN. Проверять изменяемый объект; проверка не блокирует последующие изменения.",
                props, "projectName", "objectFqn");
    }
    public JsonObject call(JsonObject args) {
        try {
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(
                    gateway.read(ToolJson.getStr(args, "projectName"), ToolJson.getStr(args, "objectFqn"))));
        } catch (RuntimeException failure) { return McpServer.toolError("editability check failed; edit permission is unknown"); }
    }
}
