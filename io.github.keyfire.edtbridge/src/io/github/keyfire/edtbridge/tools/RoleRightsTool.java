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
import io.github.keyfire.edtbridge.edt.RoleRightsGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Read the rights of a role on one object, or list the objects the role has rights on. */
public final class RoleRightsTool {
    public String name() { return "edt_role_rights"; }
    public boolean isWrite() { return false; }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", ToolJson.strProp("EDT project name; in an extension, the extension project"));
        props.add("role", ToolJson.strProp("Role FQN, such as Role.Manager"));
        props.add("object", ToolJson.strProp("Top object FQN, such as Catalog.Products, or Configuration. "
                + "Without it the answer lists the objects the role has rights on."));
        return ToolJson.descriptor(name(), "Read the rights of a role on one object through the live model: every "
                + "right the object has, in the order EDT shows them, with the English and Russian name and the "
                + "value. value is null when the role leaves the right to the base configuration (an adopted role "
                + "in an extension); explicit tells whether the role states the value itself. A right with a data "
                + "access restriction carries restricted=true and its conditions. Without object, lists the "
                + "objects the role has rights on and the role's flags.",
                "Права роли на один объект по живой модели: каждое право объекта в порядке EDT, английское и "
                + "русское имя и значение. value равно null, когда право решает базовая конфигурация "
                + "(заимствованная роль в расширении); explicit показывает, задано ли значение в самой роли. У права "
                + "с ограничением доступа к данным restricted=true и тексты условий. Без object – перечень объектов, "
                + "на которые у роли есть права, и флаги роли.",
                props, "projectName", "role");
    }

    public JsonObject call(JsonObject args) {
        try {
            var result = new RoleRightsGateway().read(ToolJson.getStr(args, "projectName"),
                    ToolJson.getStr(args, "role"), ToolJson.getStr(args, "object"));
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (RuntimeException error) {
            return McpServer.toolError("edt_role_rights: " + error.getMessage());
        }
    }
}
