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
        props.add("tool", ToolJson.strProp("Name of the tool to describe, e.g. edt_project_errors"));
        return ToolJson.descriptor(name(),
                "READ: the full description and the argument schema of one tool, by its name. The "
                + "server also describes a tool outside the active tool set (EDT_BRIDGE_TOOLSET): the "
                + "answer then states that a call of it is refused and lists the tool sets that contain "
                + "it. The answer contains name, description, inputSchema, toolsets and available. For "
                + "an unknown name the server returns a refusal with the nearest tool names.",
                "ЧТЕНИЕ: полное описание и схема аргументов одного инструмента по его имени. Сервер "
                + "описывает и инструмент вне действующего набора (EDT_BRIDGE_TOOLSET): в этом случае в "
                + "ответе указано, что вызов инструмента отклоняется, и перечислены наборы, в которые он "
                + "входит. Ответ содержит name, description, inputSchema, toolsets и available. Для "
                + "неизвестного имени сервер возвращает отказ с ближайшими именами инструментов.",
                props, "tool");
    }

    /**
     * Describe one of the tools the server registers.
     *
     * @param descriptors every registered descriptor, not only those the selection serves
     * @param toolset the selection EDT_BRIDGE_TOOLSET asks for
     */
    public JsonObject call(JsonObject args, JsonArray descriptors, ToolSets.Selection toolset) {
        JsonElement passed = args.get("tool");
        String wanted = passed != null && passed.isJsonPrimitive() ? passed.getAsString().trim() : "";
        if (wanted.isEmpty()) {
            return McpServer.toolError(name() + ": 'tool' is required - the name of the tool to "
                    + "describe, e.g. edt_project_errors");
        }
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
        answer.add("description", found.get("description"));
        answer.add("inputSchema", found.get("inputSchema"));
        JsonArray sets = new JsonArray();
        ToolSets.containing(wanted).forEach(sets::add);
        answer.add("toolsets", sets);
        boolean available = toolset.allows(wanted);
        answer.addProperty("available", available);
        if (!available) {
            answer.addProperty("note", toolset.note(wanted));
        }
        // No HTML escaping: a description is prose, and an apostrophe written as ' is noise.
        return McpServer.textResult(
                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(answer));
    }
}
