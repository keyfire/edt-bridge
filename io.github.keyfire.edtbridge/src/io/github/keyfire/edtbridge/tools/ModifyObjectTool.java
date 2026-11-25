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
import java.util.ArrayList;
import java.util.Map;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.edt.MetadataSettingsGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Typed metadata settings; object identity and arbitrary references cannot be changed here. */
public final class ModifyObjectTool {
    public String name() { return "edt_modify_object"; }
    public boolean isWrite() { return true; }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", ToolJson.strProp("EDT project name"));
        props.add("fqn", ToolJson.strProp("Metadata object or managed form FQN, e.g. Constant.DefaultRate, "
                + "Catalog.Products, CommonModule.Api or Catalog.Products.Form.ItemForm"));
        JsonObject settings = new JsonObject();
        settings.addProperty("type", "object");
        settings.addProperty("description", "Typed settings listed by edt_property_reference for the target. "
                + "Examples: type for a constant, codeType and codeLength for a catalog, numberType, numberLength "
                + "and posting for a document, dataLockControlMode, an array of dataLockFields, or Russian "
                + "presentations such as objectPresentationRu, listPresentationRu and recordPresentationRu.");
        props.add("properties", settings);
        props.add("extendModule", ToolJson.boolProp("true sets the adopted common module control state to "
                + "Extended; preserves its module body. Default false. CommonModule only."));
        props.add("apply", ToolJson.boolProp("false: validate all properties and show changes without writing; "
                + "true: commit and export the object."));
        return ToolJson.descriptor(name(), "WRITE: set properties of metadata objects and managed forms. "
                + "Use edt_property_reference to see which properties a target supports. "
                + "Use extendModule to extend the body of an adopted common module. Dry-run by default.",
                "ЗАПИСЬ: изменить свойства объектов метаданных и управляемых форм. "
                + "Доступные свойства показывает edt_property_reference. "
                + "Для расширения тела заимствованного общего модуля передать extendModule. "
                + "По умолчанию пробный запуск.",
                props, "projectName", "fqn");
    }

    public JsonObject call(JsonObject args) {
        try {
            var result = new MetadataSettingsGateway().modifyObject(ToolJson.getStr(args, "projectName"),
                    ToolJson.getStr(args, "fqn"), properties(args), ToolJson.getBool(args, "extendModule"),
                    ToolJson.getBool(args, "apply"));
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (RuntimeException error) {
            return McpServer.toolError("edt_modify_object: " + error.getMessage());
        }
    }

    static Map<String, Object> properties(JsonObject args) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!args.has("properties") || args.get("properties").isJsonNull()) return result;
        if (!args.get("properties").isJsonObject()) throw new IllegalArgumentException("properties must be an object");
        for (Map.Entry<String, JsonElement> entry : args.getAsJsonObject("properties").entrySet()) {
            if ("dataLockFields".equals(entry.getKey()) && entry.getValue().isJsonArray()) {
                var fields = new ArrayList<String>();
                for (var field : entry.getValue().getAsJsonArray()) {
                    if (!field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()) {
                        throw new IllegalArgumentException("dataLockFields must contain only strings");
                    }
                    fields.add(field.getAsString());
                }
                result.put(entry.getKey(), fields);
                continue;
            }
            if (!entry.getValue().isJsonPrimitive()) {
                throw new IllegalArgumentException("Property values must be booleans, strings or numbers");
            }
            var value = entry.getValue().getAsJsonPrimitive();
            if (value.isBoolean()) result.put(entry.getKey(), value.getAsBoolean());
            else if (value.isString()) result.put(entry.getKey(), value.getAsString());
            else if (value.isNumber()) result.put(entry.getKey(), value.getAsBigDecimal());
            else throw new IllegalArgumentException("Property values must be booleans, strings or numbers");
        }
        return result;
    }
}
