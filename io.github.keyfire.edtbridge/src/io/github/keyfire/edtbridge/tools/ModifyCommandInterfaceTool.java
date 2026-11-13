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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.core.CommandInterfaceEdit;
import io.github.keyfire.edtbridge.edt.CommandInterfaceGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Change visibility, group and order of commands in a subsystem section. */
public final class ModifyCommandInterfaceTool {
    public String name() { return "edt_modify_command_interface"; }
    public boolean isWrite() { return true; }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", ToolJson.strProp("EDT project name; in an extension, the extension project"));
        props.add("subsystem", ToolJson.strProp("Subsystem FQN; nested form: Subsystem.Parent.Subsystem.Child. In an "
                + "extension the subsystem must be the extension's own or adopted with edt_adopt_object."));

        JsonObject change = new JsonObject();
        change.addProperty("type", "object");
        JsonObject changeProps = new JsonObject();
        changeProps.add("command", ToolJson.strProp("Command as edt_command_interface names it, such as "
                + "Catalog.Products.StandardCommand.OpenList"));
        JsonObject visible = new JsonObject();
        visible.addProperty("type", "object");
        visible.addProperty("description", "{\"common\": bool, \"roles\": {\"Role.Manager\": bool}}. An omitted common "
                + "keeps the current value; roles, when given, replaces the role list.");
        changeProps.add("visible", visible);
        changeProps.add("group", ToolJson.strProp("Target group: NavigationPanelImportant, NavigationPanelOrdinary, "
                + "NavigationPanelSeeAlso, ActionsPanelCreate, ActionsPanelReports, ActionsPanelTools or "
                + "CommandGroup.<name>; a command stays within its panel"));
        JsonObject reset = new JsonObject();
        reset.addProperty("type", "array");
        JsonObject resetItem = new JsonObject();
        resetItem.addProperty("type", "string");
        JsonArray resetValues = new JsonArray();
        resetValues.add("visibility");
        resetValues.add("group");
        resetItem.add("enum", resetValues);
        reset.add("items", resetItem);
        reset.addProperty("description", "Return the visibility, the group or both to the command's default");
        changeProps.add("reset", reset);
        change.add("properties", changeProps);
        JsonArray changeRequired = new JsonArray();
        changeRequired.add("command");
        change.add("required", changeRequired);
        JsonObject commands = new JsonObject();
        commands.addProperty("type", "array");
        commands.add("items", change);
        commands.addProperty("description", "One change per command");
        props.add("commands", commands);

        JsonObject order = new JsonObject();
        order.addProperty("type", "object");
        order.addProperty("description", "{\"group\": \"NavigationPanelOrdinary\", \"commands\": [...]}: every "
                + "command of the group in the new order");
        props.add("order", order);
        props.add("apply", ToolJson.boolProp("false (default): show the changes, write nothing. true: write "
                + "CommandInterface.cmi and export it."));
        return ToolJson.descriptor(name(), "WRITE: change the command interface of a subsystem section through the "
                + "live model, the way the command interface editor of EDT does: visibility of a command in common "
                + "and by role, its group within its panel, the order of a group, or a return to the default. "
                + "Names come from edt_command_interface; a command outside the section, an unknown group or role, "
                + "and an order that leaves out or adds a command are refused. Dry-run by default.",
                "ЗАПИСЬ: изменить командный интерфейс раздела подсистемы через живую модель так, как это делает "
                + "редактор командного интерфейса EDT: видимость команды общая и по ролям, ее группа в пределах "
                + "панели, порядок группы, возврат к значению по умолчанию. Имена берутся из edt_command_interface; "
                + "команда не из раздела, неизвестная группа или роль и порядок, в котором команды не хватает или "
                + "она лишняя, отклоняются. По умолчанию dry-run.",
                props, "projectName", "subsystem");
    }

    public JsonObject call(JsonObject args) {
        try {
            List<CommandInterfaceEdit.CommandChange> changes = CommandInterfaceEdit.commands(changes(args));
            CommandInterfaceEdit.Order order = CommandInterfaceEdit.order(order(args));
            var result = new CommandInterfaceGateway().modify(ToolJson.getStr(args, "projectName"),
                    ToolJson.getStr(args, "subsystem"), changes, order, ToolJson.getBool(args, "apply"));
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (RuntimeException error) {
            return McpServer.toolError("edt_modify_command_interface: " + error.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> changes(JsonObject args) {
        if (!args.has("commands") || args.get("commands").isJsonNull()) return null;
        if (!args.get("commands").isJsonArray()) throw new IllegalArgumentException("commands must be a list of changes");
        List<Map<String, Object>> changes = new ArrayList<>();
        for (JsonElement element : args.getAsJsonArray("commands")) {
            Object change = plain(element);
            if (!(change instanceof Map)) throw new IllegalArgumentException("every entry of commands must be an object");
            changes.add((Map<String, Object>) change);
        }
        return changes;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> order(JsonObject args) {
        if (!args.has("order") || args.get("order").isJsonNull()) return null;
        Object order = plain(args.get("order"));
        if (!(order instanceof Map)) throw new IllegalArgumentException("order must be an object with group and commands");
        return (Map<String, Object>) order;
    }

    /** JSON as maps, lists, strings, booleans and numbers, the forms the core checks take. */
    private static Object plain(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) map.put(entry.getKey(), plain(entry.getValue()));
            return map;
        }
        if (element.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) list.add(plain(item));
            return list;
        }
        var primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isNumber()) return primitive.getAsNumber();
        return primitive.getAsString();
    }
}
