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
package io.github.keyfire.edtbridge.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The requested changes of a subsystem section's command interface, checked before the model is
 * touched: which command, its visibility in common and by role, its group, what goes back to the
 * default, and the order of one group.
 *
 * Commands and groups are named the way {@code CommandInterface.cmi} names them:
 * {@code Catalog.Products.StandardCommand.OpenList}, {@code CommonCommand.Report},
 * {@code NavigationPanelImportant}, {@code CommandGroup.Sales}.
 */
public final class CommandInterfaceEdit {
    private CommandInterfaceEdit() { }

    /** The groups every section has, in the order the panels show them. */
    public static final List<String> STANDARD_GROUPS = List.of("NavigationPanelImportant",
            "NavigationPanelOrdinary", "NavigationPanelSeeAlso", "ActionsPanelCreate", "ActionsPanelReports",
            "ActionsPanelTools");

    private static final List<String> RESETS = List.of("visibility", "group");
    private static final List<String> CHANGE_KEYS = List.of("command", "visible", "group", "reset");
    private static final String COMMAND_GROUP = "CommandGroup.";

    /** {@code common} null keeps the current value; {@code roles} null keeps the current role list. */
    public record Visibility(Boolean common, LinkedHashMap<String, Boolean> roles) { }

    public record CommandChange(String command, Visibility visible, String group, Set<String> reset) { }

    public record Order(String group, List<String> commands) { }

    /** A standard group or {@code CommandGroup.<name>}. */
    public static String group(String requested) {
        String group = requested == null ? "" : requested.trim();
        if (STANDARD_GROUPS.contains(group)) return group;
        if (group.startsWith(COMMAND_GROUP) && group.length() > COMMAND_GROUP.length()
                && group.indexOf('.', COMMAND_GROUP.length()) < 0) {
            return group;
        }
        List<String> allowed = new ArrayList<>(STANDARD_GROUPS);
        allowed.add(COMMAND_GROUP + "<name>");
        throw new IllegalArgumentException("unknown command group: " + requested + "; allowed: " + String.join(", ", allowed));
    }

    public static List<CommandChange> commands(List<? extends Map<String, ?>> raw) {
        List<CommandChange> changes = new ArrayList<>();
        if (raw == null) return changes;
        Set<String> seen = new HashSet<>();
        for (Map<String, ?> entry : raw) {
            Object named = entry == null ? null : entry.get("command");
            if (!(named instanceof String command) || command.isBlank()) {
                throw new IllegalArgumentException("every change names its command, such as Catalog.Products.StandardCommand.OpenList");
            }
            for (String key : entry.keySet()) {
                if (!CHANGE_KEYS.contains(key)) {
                    throw new IllegalArgumentException(AllowedValues.unsupportedName("command change property", key, CHANGE_KEYS));
                }
            }
            if (!seen.add(command)) throw new IllegalArgumentException("command " + command + " is changed twice");
            Visibility visible = entry.get("visible") == null ? null : visibility(command, entry.get("visible"));
            String group = entry.get("group") == null ? null : group(String.valueOf(entry.get("group")));
            Set<String> reset = resets(entry.get("reset"));
            if (visible == null && group == null && reset.isEmpty()) {
                throw new IllegalArgumentException("a change of " + command + " needs visible, group or reset");
            }
            if (visible != null && reset.contains("visibility")) {
                throw new IllegalArgumentException("reset of visibility contradicts visible for " + command);
            }
            if (group != null && reset.contains("group")) {
                throw new IllegalArgumentException("reset of group contradicts group for " + command);
            }
            changes.add(new CommandChange(command, visible, group, reset));
        }
        return changes;
    }

    /** The order of one group, or null when none is requested. */
    public static Order order(Map<String, ?> raw) {
        if (raw == null) return null;
        String group = group(raw.get("group") == null ? null : String.valueOf(raw.get("group")));
        if (!(raw.get("commands") instanceof List<?> listed) || listed.isEmpty()) {
            throw new IllegalArgumentException("order needs commands, the commands of " + group + " in the new order");
        }
        List<String> commands = new ArrayList<>();
        for (Object command : listed) {
            String name = command == null ? "" : String.valueOf(command).trim();
            if (name.isEmpty()) throw new IllegalArgumentException("order contains an empty command name");
            if (commands.contains(name)) throw new IllegalArgumentException("order names " + name + " twice");
            commands.add(name);
        }
        return new Order(group, commands);
    }

    /** An order has to be a permutation of the group's commands: nothing left out, nothing added. */
    public static void checkOrder(List<String> requested, List<String> current, String group) {
        if (requested.size() != current.size() || !new HashSet<>(requested).equals(new HashSet<>(current))) {
            throw new IllegalArgumentException("order of " + group + " must list exactly its commands: "
                    + (current.isEmpty() ? "nothing" : String.join(", ", current)));
        }
    }

    private static Visibility visibility(String command, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("visible of " + command + " must be an object such as {\"common\": true}");
        }
        for (Object key : map.keySet()) {
            if (!"common".equals(key) && !"roles".equals(key)) {
                throw new IllegalArgumentException(AllowedValues.unsupportedName("visible property of " + command,
                        String.valueOf(key), List.of("common", "roles")));
            }
        }
        Object common = map.get("common");
        if (common != null && !(common instanceof Boolean)) {
            throw new IllegalArgumentException("visible.common of " + command + " must be true or false, got: " + common);
        }
        LinkedHashMap<String, Boolean> roles = null;
        if (map.get("roles") != null) {
            if (!(map.get("roles") instanceof Map<?, ?> listed)) {
                throw new IllegalArgumentException("visible.roles of " + command + " must map Role.<name> to true or false");
            }
            roles = new LinkedHashMap<>();
            for (Map.Entry<?, ?> role : listed.entrySet()) {
                String name = String.valueOf(role.getKey());
                if (!name.startsWith("Role.") || name.length() == "Role.".length() || name.indexOf('.', 5) >= 0) {
                    throw new IllegalArgumentException("visible of " + command + " names a role as Role.<name>, got: " + name);
                }
                if (!(role.getValue() instanceof Boolean value)) {
                    throw new IllegalArgumentException("visible of " + command + " for " + name
                            + " must be true or false, got: " + role.getValue());
                }
                roles.put(name, value);
            }
        }
        if (common == null && roles == null) {
            throw new IllegalArgumentException("visible of " + command + " needs common, roles or both");
        }
        return new Visibility((Boolean) common, roles);
    }

    private static Set<String> resets(Object raw) {
        Set<String> resets = new LinkedHashSet<>();
        if (raw == null) return resets;
        if (!(raw instanceof List<?> listed)) throw new IllegalArgumentException("reset must be a list such as [\"visibility\"]");
        for (Object value : listed) {
            String name = String.valueOf(value);
            if (!RESETS.contains(name)) throw new IllegalArgumentException(AllowedValues.unknownValue("reset", name, RESETS));
            resets.add(name);
        }
        return resets;
    }
}
