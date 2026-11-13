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

import java.util.LinkedHashMap;
import java.util.Map;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.edt.RoleRightsGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Set rights of a role on one object; a right with a data access restriction is refused. */
public final class ModifyRoleRightsTool {
    public String name() { return "edt_modify_role_rights"; }
    public boolean isWrite() { return true; }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", ToolJson.strProp("EDT project name; in an extension, the extension project"));
        props.add("role", ToolJson.strProp("Role FQN, such as Role.Manager. In an extension the role must be the "
                + "extension's own or adopted with edt_adopt_object."));
        props.add("object", ToolJson.strProp("Top object FQN, such as Catalog.Products, or Configuration. In an "
                + "extension the object must be the extension's own or adopted."));
        JsonObject rights = new JsonObject();
        rights.addProperty("type", "object");
        rights.addProperty("description", "Right name to true or false, such as {\"Read\": true, \"View\": true}. "
                + "English or Russian names as edt_role_rights shows them; case does not matter.");
        JsonObject value = new JsonObject();
        value.addProperty("type", "boolean");
        rights.add("additionalProperties", value);
        props.add("rights", rights);
        props.add("apply", ToolJson.boolProp("false (default): show the rights before and after, write nothing. "
                + "true: write them and export the role."));
        return ToolJson.descriptor(name(), "WRITE: set rights of a role on one object through the live model, the "
                + "way the rights editor of EDT does: a value equal to the role's default for the object removes "
                + "the explicit entry. An unknown right is refused with the rights the object has; a right with a "
                + "data access restriction (RLS) is never changed. Dry-run by default.",
                "ЗАПИСЬ: задать права роли на один объект через живую модель так, как это делает редактор прав "
                + "EDT: значение, равное значению по умолчанию для объекта, убирает явную запись. Неизвестное право "
                + "отклоняется с перечнем прав объекта; право с ограничением доступа к данным (RLS) не меняется. По "
                + "умолчанию dry-run.",
                props, "projectName", "role", "object", "rights");
    }

    public JsonObject call(JsonObject args) {
        try {
            var result = new RoleRightsGateway().modify(ToolJson.getStr(args, "projectName"),
                    ToolJson.getStr(args, "role"), ToolJson.getStr(args, "object"), rights(args),
                    ToolJson.getBool(args, "apply"));
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (RuntimeException error) {
            return McpServer.toolError("edt_modify_role_rights: " + error.getMessage());
        }
    }

    /** The rights object as names to booleans; anything else is passed on as text and refused there. */
    private static Map<String, Object> rights(JsonObject args) {
        if (!args.has("rights") || args.get("rights").isJsonNull()) return null;
        if (!args.get("rights").isJsonObject()) throw new IllegalArgumentException("rights must be an object such as {\"Read\": true}");
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : args.getAsJsonObject("rights").entrySet()) {
            JsonElement element = entry.getValue();
            boolean flag = element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean();
            values.put(entry.getKey(), flag ? Boolean.valueOf(element.getAsBoolean()) : String.valueOf(element));
        }
        return values;
    }
}
