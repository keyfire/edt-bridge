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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.github.keyfire.edtbridge.edt.ConfigurationSupportGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Read the project rules for connected suppliers. */
public final class ConfigurationSupportTool {
    public String name() {
        return "edt_configuration_support";
    }

    public JsonObject descriptor() {
        return ToolJson.descriptor(name(),
                "READ: list connected suppliers and read their support rules for selected objects.",
                "ЧТЕНИЕ: показать подключенных поставщиков и их правила поддержки для выбранных объектов.",
                properties(), "projectName");
    }

    public JsonObject call(JsonObject args) {
        try {
            var result = ConfigurationSupportGateway.read(required(args, "projectName"), fqns(args), supplierId(args));
            return McpServer.textResult(new Gson().toJson(result));
        } catch (RuntimeException failure) {
            return McpServer.toolError(name() + ": " + failure.getMessage());
        } catch (LinkageError failure) {
            return McpServer.toolError(name() + ": this EDT version does not provide a compatible support API.");
        }
    }

    static JsonObject properties() {
        var props = new JsonObject();
        props.add("projectName", ToolJson.strProp("Name of the open EDT project"));
        props.add("supplierId", ToolJson.strProp("Supplier UUID from edt_configuration_support"));
        var fqns = new JsonObject();
        fqns.addProperty("type", "array");
        fqns.addProperty("description", "Exact metadata FQNs. Use Configuration for the root.");
        fqns.add("items", ToolJson.strProp("Metadata object FQN"));
        props.add("fqns", fqns);
        return props;
    }

    static boolean apply(JsonObject args) {
        if (!args.has("apply")) return false;
        var flag = args.get("apply");
        if (!flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("apply must be a boolean");
        }
        return flag.getAsBoolean();
    }

    static UUID supplierId(JsonObject args) {
        if (!args.has("supplierId")) return null;
        return UUID.fromString(required(args, "supplierId"));
    }

    static List<String> fqns(JsonObject args) {
        if (!args.has("fqns")) return List.of();
        if (!args.get("fqns").isJsonArray()) throw new IllegalArgumentException("fqns must be an array");
        var unique = new LinkedHashSet<String>();
        for (var value : args.getAsJsonArray("fqns")) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || value.getAsString().isBlank()) {
                throw new IllegalArgumentException("fqns must contain nonempty strings");
            }
            String fqn = value.getAsString();
            if (!unique.add(fqn)) throw new IllegalArgumentException("Duplicate FQN: " + fqn);
        }
        return List.copyOf(unique);
    }

    static String required(JsonObject args, String key) {
        var value = args.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(key + " must be a nonempty string");
        }
        return value.getAsString();
    }
}
