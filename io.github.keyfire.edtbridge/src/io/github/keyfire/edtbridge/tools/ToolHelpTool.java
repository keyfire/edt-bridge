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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.keyfire.edtbridge.core.ToolNames;
import io.github.keyfire.edtbridge.core.ToolSets;
import io.github.keyfire.edtbridge.mcp.McpServer;

/**
 * MCP tool: edt_tool_help - READ. The description and the argument schema of one tool, asked for by
 * name. With a tool set selected the list a client receives is short, and this call is how a tool
 * outside the set can still be read about: the answer says that a call of it is refused and names
 * the sets that contain it.
 */
public final class ToolHelpTool {

    public String name() {
        return "edt_tool_help";
    }

    public boolean isWrite() {
        return false;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("tool", ToolJson.strProp("Tool to describe. Omit to list the active tools."));
        props.add("brief", ToolJson.boolProp("Return only the signature of a named tool. An asterisk marks a required argument."));
        return ToolJson.descriptor(name(),
                "READ: list the active tools with a short description, or pass tool for its description and argument schema. "
                + "Use brief=true with tool for its signature. The "
                + "server also describes a tool outside the active tool set (EDT_BRIDGE_TOOLSET): the "
                + "answer then states that a call of it is refused and lists the tool sets that contain "
                + "it. The answer contains name, description, inputSchema, toolsets and available. For "
                + "an unknown name the server returns a refusal with the nearest tool names.",
                "ЧТЕНИЕ: список инструментов действующего набора с краткими описаниями или описание и схема аргументов инструмента по имени. "
                + "Для краткой сигнатуры передайте имя в tool и brief=true. Сервер "
                + "описывает и инструмент вне действующего набора (EDT_BRIDGE_TOOLSET): в этом случае в "
                + "ответе указано, что вызов инструмента отклоняется, и перечислены наборы, в которые он "
                + "входит. Ответ содержит name, description, inputSchema, toolsets и available. Для "
                + "неизвестного имени сервер возвращает отказ с ближайшими именами инструментов.",
                props);
    }

    /**
     * Describe one of the tools the server registers.
     *
     * @param descriptors every registered descriptor, not only those the selection serves
     * @param toolset the selection EDT_BRIDGE_TOOLSET asks for
     */
    public JsonObject call(JsonObject args, JsonArray descriptors, ToolSets.Selection toolset) {
        JsonElement passed = args.get("tool");
        if (passed == null || passed.isJsonNull()) {
            return list(descriptors, toolset);
        }
        if (!passed.isJsonPrimitive() || !passed.getAsJsonPrimitive().isString() || passed.getAsString().isBlank()) {
            return McpServer.toolError("edt_tool_help: tool must be a non-empty name");
        }
        String wanted = passed.getAsString().trim();
        List<String> known = new ArrayList<>();
        JsonObject found = null;
        for (JsonElement element : descriptors) {
            JsonObject descriptor = element.getAsJsonObject();
            String registered = descriptor.get("name").getAsString();
            known.add(registered);
            if (registered.equals(wanted)) {
                found = descriptor;
            }
        }
        if (found == null) {
            return McpServer.toolError(ToolNames.unknownRefusal(name(), wanted, known));
        }
        JsonObject answer = new JsonObject();
        answer.addProperty("name", wanted);
        JsonObject schema = found.getAsJsonObject("inputSchema");
        if (ToolJson.getBool(args, "brief")) {
            answer.addProperty("signature", signature(wanted, schema));
        } else {
            answer.add("description", found.get("description"));
            answer.add("inputSchema", schema);
        }
        JsonArray sets = new JsonArray();
        ToolSets.containing(wanted).forEach(sets::add);
        answer.add("toolsets", sets);
        boolean available = toolset.allows(wanted);
        answer.addProperty("available", available);
        if (!available) {
            answer.addProperty("note", toolset.note(wanted));
        }
        return result(answer);
    }

    private static JsonObject list(JsonArray descriptors, ToolSets.Selection toolset) {
        JsonArray tools = new JsonArray();
        for (JsonElement element : descriptors) {
            JsonObject descriptor = element.getAsJsonObject();
            String name = descriptor.get("name").getAsString();
            if (!toolset.allows(name)) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("name", name);
            entry.addProperty("description", summary(descriptor.get("description").getAsString()));
            tools.add(entry);
        }
        JsonObject answer = new JsonObject();
        answer.add("tools", tools);
        return result(answer);
    }

    private static String summary(String description) {
        String line = description.strip().replaceAll("\\s+", " ");
        int sentence = line.indexOf(". ");
        if (sentence >= 0) {
            line = line.substring(0, sentence + 1);
        }
        return line.length() <= 160 ? line : line.substring(0, 157).stripTrailing() + "...";
    }

    private static String signature(String name, JsonObject schema) {
        var required = new java.util.HashSet<String>();
        addRequired(required, schema.getAsJsonArray("required"));
        var groups = schema.getAsJsonArray("allOf");
        if (groups != null) {
            for (var group : groups) {
                var alternatives = group.getAsJsonObject().getAsJsonArray("anyOf");
                if (alternatives != null && alternatives.size() > 0) {
                    // The canonical argument precedes its compatibility names.
                    addRequired(required, alternatives.get(0).getAsJsonObject().getAsJsonArray("required"));
                }
            }
        }
        var arguments = new java.util.StringJoiner(", ", name + "(", ")");
        for (var entry : schema.getAsJsonObject("properties").entrySet()) {
            var type = entry.getValue().isJsonObject() ? entry.getValue().getAsJsonObject().get("type") : null;
            var types = new java.util.StringJoiner("|");
            if (type != null && type.isJsonArray()) {
                type.getAsJsonArray().forEach(option -> types.add(option.getAsString()));
            } else {
                types.add(type != null ? type.getAsString() : "any");
            }
            arguments.add(entry.getKey() + (required.contains(entry.getKey()) ? "*" : "") + ": " + types);
        }
        return arguments.toString();
    }

    private static void addRequired(java.util.Set<String> names, JsonArray required) {
        if (required != null) {
            required.forEach(argument -> names.add(argument.getAsString()));
        }
    }

    private static JsonObject result(JsonObject answer) {
        return McpServer.textResult(
                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(answer));
    }
}
