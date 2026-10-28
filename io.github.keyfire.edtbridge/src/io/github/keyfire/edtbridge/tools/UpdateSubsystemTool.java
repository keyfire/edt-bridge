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

import java.util.ArrayList;
import java.util.List;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.edt.MetadataSettingsGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Change subsystem membership without rewriting unrelated metadata references. */
public final class UpdateSubsystemTool {
    public String name() { return "edt_update_subsystem"; }
    public boolean isWrite() { return true; }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", ToolJson.strProp("EDT project name"));
        props.add("fqn", ToolJson.strProp("Subsystem FQN; nested form: Subsystem.Parent.Subsystem.Child"));
        for (String name : new String[]{"add", "remove"}) {
            JsonObject list = new JsonObject(); list.addProperty("type", "array");
            JsonObject item = new JsonObject(); item.addProperty("type", "string"); list.add("items", item);
            list.addProperty("description", name + " these existing top-object FQNs; duplicates do not duplicate membership");
            props.add(name, list);
        }
        props.add("force", ToolJson.boolProp("Required with apply=true when removing existing members. Default false."));
        props.add("apply", ToolJson.boolProp("false: validate references and show before/after content; "
                + "true: commit and export the subsystem."));
        return ToolJson.descriptor(name(), "WRITE: add or remove subsystem members through the live metadata "
                + "model. Every reference is resolved before writing; dry-run is the default.",
                "ЗАПИСЬ: добавить или убрать объекты из состава подсистемы через живую модель метаданных. "
                + "Все ссылки проверяются до записи; по умолчанию dry-run.", props, "projectName", "fqn");
    }

    public JsonObject call(JsonObject args) {
        try {
            var result = new MetadataSettingsGateway().updateSubsystem(ToolJson.getStr(args, "projectName"),
                    ToolJson.getStr(args, "fqn"), names(args, "add"), names(args, "remove"),
                    ToolJson.getBool(args, "force"), ToolJson.getBool(args, "apply"));
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (RuntimeException error) {
            return McpServer.toolError("edt_update_subsystem: " + error.getMessage());
        }
    }

    private static List<String> names(JsonObject args, String name) {
        if (!args.has(name) || args.get(name).isJsonNull()) return List.of();
        if (!args.get(name).isJsonArray()) throw new IllegalArgumentException(name + " must be an array of FQNs");
        List<String> result = new ArrayList<>();
        for (var entry : args.getAsJsonArray(name)) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString() || entry.getAsString().isBlank()) {
                throw new IllegalArgumentException(name + " must contain non-empty FQNs");
            }
            result.add(entry.getAsString());
        }
        return result;
    }
}
