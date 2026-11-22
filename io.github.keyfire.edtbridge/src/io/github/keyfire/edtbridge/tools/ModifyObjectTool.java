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
import io.github.keyfire.edtbridge.edt.MetadataSettingsGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Typed metadata settings; object identity and arbitrary references cannot be changed here. */
public final class ModifyObjectTool {
    public String name() { return "edt_modify_object"; }
    public boolean isWrite() { return true; }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", ToolJson.strProp("EDT project name"));
        props.add("fqn", ToolJson.strProp("CommonModule or Form FQN, e.g. CommonModule.Api, "
                + "Catalog.Products.Form.ItemForm, CommonForm.Settings"));
        JsonObject settings = new JsonObject();
        settings.addProperty("type", "object");
        settings.addProperty("description", "Typed properties of the target. Owned CommonModule: global, server, "
                + "externalConnection, clientManagedApplication, clientOrdinaryApplication, serverCall, privileged "
                + "(boolean); returnValuesReuse (DontUse, DuringRequest, DuringSession). Form: autoTitle, showTitle, "
                + "showCloseButton, allowFormCustomize (boolean); titleRu (string; empty clears); windowOpeningMode "
                + "(Independent, LockOwnerWindow, LockWholeInterface); scaleVariant (Auto, Compact, Normal, "
                + "NormalIfPossible); width, height (nonnegative integer). Call edt_property_reference to inspect.");
        props.add("properties", settings);
        props.add("extendModule", ToolJson.boolProp("true sets the adopted common module control state to "
                + "Extended; preserves its module body. Default false. CommonModule only."));
        props.add("apply", ToolJson.boolProp("false: validate all properties and show changes without writing; "
                + "true: commit and export the object."));
        return ToolJson.descriptor(name(), "WRITE: set typed properties of an owned common module or a managed "
                + "form (autoTitle, titleRu, windowOpeningMode, scaleVariant, width, height, ...), "
                + "or enable the module body of an adopted common module. Dry-run by default; "
                + "every property is validated before writing.",
                "ЗАПИСЬ: изменить типизированные свойства собственного общего модуля или управляемой формы "
                + "(autoTitle, titleRu, windowOpeningMode, scaleVariant, width, height и др.), либо включить "
                + "расширение заимствованного общего модуля. По умолчанию dry-run; все свойства проверяются до записи.",
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
