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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tool sets: the part of the tool list one kind of job needs.
 *
 * <p>The server registers more than eighty tools, and a model that is shown all of them has that
 * many more wrong ones to choose from. EDT_BRIDGE_TOOLSET names one set or several, separated by
 * commas, and the server then lists and runs only the tools of those sets. An empty value keeps
 * the whole list.
 *
 * <p>Every tool the server registers is named here exactly once: in the base that belongs to every
 * set, in one or more sets, or in the list of tools left outside every set. A new tool therefore
 * has to be placed deliberately, and the test of this class fails until it is.
 *
 * <p>The sets are narrow on purpose. Each holds what a job of that kind takes from creating the
 * project to building the artifact: an external data processor, an external report, a
 * configuration extension. Widening a set later keeps existing setups working; narrowing one
 * would not.
 *
 * <p>Pure string work, kept out of the server so it can be tested without EDT.
 */
public final class ToolSets {

    /** Reading and checking: what every job needs whatever it builds. */
    private static final List<String> BASE = List.of(
            "edt_tool_help",
            "edt_projects",
            "edt_project_errors",
            "edt_check_info",
            "edt_metadata_objects",
            "edt_metadata_details",
            "edt_module_text",
            "edt_search_modules",
            "edt_search_queries",
            "edt_symbol_info",
            "edt_validate_query",
            "edt_support_status",
            "edt_platform_help",
            "edt_platform_installations",
            "edt_form_structure",
            "edt_form_identifiers",
            "edt_form_render");

    /** Building and changing a managed form. */
    private static final List<String> FORM_WRITES = List.of(
            "edt_add_form",
            "edt_add_form_attribute",
            "edt_add_form_command",
            "edt_add_form_handler",
            "edt_add_form_item",
            "edt_modify_form_attribute",
            "edt_modify_form_command",
            "edt_modify_form_item",
            "edt_move_form_item",
            "edt_remove_form_attribute",
            "edt_remove_form_command",
            "edt_remove_form_item");

    /**
     * Changing the text of a module. edt_delete_method is part of it because the description of
     * edt_remove_form_command names it as the way to remove the handler a removed command leaves.
     */
    private static final List<String> MODULE_WRITES = List.of(
            "edt_add_method",
            "edt_replace_method",
            "edt_move_method",
            "edt_delete_method",
            "edt_replace_module_fragment");

    /** The tools of each set beyond the base, by set name, in the order answers list the sets. */
    private static final Map<String, List<String>> SETS = sets();

    private static Map<String, List<String>> sets() {
        Map<String, List<String>> sets = new LinkedHashMap<>();
        sets.put("processor", joined(
                List.of("edt_create_external_object", "edt_dump_external_object", "edt_delete_object"),
                FORM_WRITES, MODULE_WRITES));
        // A report is built like a data processor, plus its data composition schema.
        sets.put("report", joined(
                sets.get("processor"),
                List.of("edt_add_template", "edt_read_dcs", "edt_replace_dcs")));
        sets.put("extension", joined(
                List.of("edt_create_extension", "edt_adopt_object", "edt_build_extension",
                        "edt_delete_object"),
                MODULE_WRITES));
        return Collections.unmodifiableMap(sets);
    }

    /**
     * Tools left outside every set, each group for its own reason. They stay available when no
     * set is selected.
     */
    private static final List<String> OUTSIDE = List.of(
            // Navigation through existing code. A job of these kinds reads the base
            // configuration with edt_module_text, edt_search_modules and edt_symbol_info.
            "edt_find_references",
            "edt_go_to_definition",
            "edt_outgoing_calls",
            "edt_outgoing_structures",
            // Metadata of a configuration: objects, their attributes, subsystems, HTTP routes and
            // renaming belong to a configuration job, which has no set yet.
            "edt_create_object",
            "edt_modify_object",
            "edt_add_attribute",
            "edt_modify_attribute",
            "edt_remove_attribute",
            "edt_add_route",
            "edt_update_subsystem",
            "edt_rename",
            // Pictures and HTML templates.
            "edt_picture_export",
            "edt_read_html_template",
            "edt_replace_html_template",
            // The workspace itself: a job works in the project it was given or has created.
            "edt_import_project",
            "edt_import_external_object",
            "edt_delete_project",
            "edt_clean_project",
            // Infobases, the cluster and platform installations: administration around a job.
            "edt_infobases",
            "edt_create_infobase",
            "edt_register_infobase",
            "edt_update_infobase",
            "edt_update_database_config",
            "edt_infobase_config_state",
            "edt_infobase_dump",
            "edt_infobase_maintenance",
            "edt_infobase_sessions",
            "edt_designer_agent",
            "edt_applications",
            "edt_extension_properties",
            "edt_delete_extension",
            "edt_register_platform",
            "edt_platform_build_readiness",
            // Debugging a running infobase.
            "edt_debug_attach",
            "edt_debug_control",
            "edt_debug_detach",
            "edt_debug_inspect",
            "edt_evaluate");

    private ToolSets() {
    }

    /** The set names, in the order answers list them. */
    public static List<String> names() {
        return new ArrayList<>(SETS.keySet());
    }

    /** The tools every set carries. */
    public static List<String> base() {
        return BASE;
    }

    /** The tools left outside every set. */
    public static List<String> outside() {
        return OUTSIDE;
    }

    /** The tools of one set beyond the base, or an empty list for a name that is not a set. */
    public static List<String> own(String set) {
        List<String> own = SETS.get(set);
        return own == null ? List.of() : own;
    }

    /** Every tool of one set, the base included, or an empty list for a name that is not a set. */
    public static List<String> tools(String set) {
        if (!SETS.containsKey(set)) {
            return List.of();
        }
        return joined(BASE, SETS.get(set));
    }

    /** The sets that carry the tool, in the order of {@link #names()}; all of them for a base tool. */
    public static List<String> containing(String tool) {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, List<String>> set : SETS.entrySet()) {
            if (BASE.contains(tool) || set.getValue().contains(tool)) {
                found.add(set.getKey());
            }
        }
        return found;
    }

    /** The selection a value of EDT_BRIDGE_TOOLSET asks for. */
    public static Selection parse(String raw) {
        Set<String> known = new LinkedHashSet<>();
        Set<String> unknown = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String name = part.trim().toLowerCase(Locale.ROOT);
                if (name.isEmpty()) {
                    continue;
                }
                (SETS.containsKey(name) ? known : unknown).add(name);
            }
        }
        return new Selection(new ArrayList<>(known), new ArrayList<>(unknown));
    }

    private static List<String> joined(List<String> first, List<String> second) {
        return joined(first, second, List.of());
    }

    private static List<String> joined(List<String> first, List<String> second, List<String> third) {
        Set<String> all = new LinkedHashSet<>(first);
        all.addAll(second);
        all.addAll(third);
        return Collections.unmodifiableList(new ArrayList<>(all));
    }

    private static String quoted(List<String> names) {
        StringBuilder text = new StringBuilder();
        for (String name : names) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append('\'').append(name).append('\'');
        }
        return text.toString();
    }

    /** What EDT_BRIDGE_TOOLSET selected: nothing, some sets, or names that are not sets. */
    public static final class Selection {

        private final List<String> names;
        private final List<String> unknown;
        private final Set<String> tools;

        private Selection(List<String> names, List<String> unknown) {
            this.names = Collections.unmodifiableList(names);
            this.unknown = Collections.unmodifiableList(unknown);
            Set<String> all = new LinkedHashSet<>();
            for (String name : names) {
                all.addAll(ToolSets.tools(name));
            }
            this.tools = Collections.unmodifiableSet(all);
        }

        /** The selected sets, in the order the value names them. */
        public List<String> names() {
            return names;
        }

        /** The names in the value that are not sets. */
        public List<String> unknown() {
            return unknown;
        }

        /** Whether the tool list is narrowed at all. */
        public boolean limits() {
            return !names.isEmpty() || !unknown.isEmpty();
        }

        /**
         * Why the value cannot be served, or {@code null} when it can.
         *
         * <p>A name that is not a set is refused as a whole. Serving the sets that were recognized
         * would run a job on a list nobody asked for, and serving the whole list would undo the
         * limit - in both cases without a word to whoever misspelled the name.
         */
        public String problem() {
            if (unknown.isEmpty()) {
                return null;
            }
            StringBuilder text = new StringBuilder("EDT_BRIDGE_TOOLSET names ");
            text.append(unknown.size() == 1 ? "an unknown tool set " : "unknown tool sets ");
            for (int i = 0; i < unknown.size(); i++) {
                if (i > 0) {
                    text.append(", ");
                }
                text.append('\'').append(unknown.get(i)).append('\'');
                String near = ArgumentNames.nearest(unknown.get(i), SETS.keySet());
                if (near != null) {
                    text.append(" (did you mean '").append(near).append("'?)");
                }
            }
            text.append(". Known tool sets: ").append(String.join(", ", SETS.keySet()));
            text.append(". No tool is listed or run until the variable names known sets or is empty.");
            return text.toString();
        }

        /** Whether the selection serves the tool. Nothing is served while the value has a problem. */
        public boolean allows(String tool) {
            if (!unknown.isEmpty()) {
                return false;
            }
            return names.isEmpty() || tools.contains(tool);
        }

        /** The selected sets as a phrase: {@code tool set 'report'}, {@code tool sets 'a', 'b'}. */
        public String label() {
            return (names.size() == 1 ? "tool set " : "tool sets ") + quoted(names);
        }

        /** The refusal for a call of a tool the selection does not serve. */
        public String refusal(String tool) {
            return tool + " is outside the active " + label() + " (EDT_BRIDGE_TOOLSET), so the call "
                    + "was refused and nothing was done. " + membership(tool);
        }

        /** The note a description of a tool outside the selection carries. */
        public String note(String tool) {
            return tool + " is outside the active " + label() + " (EDT_BRIDGE_TOOLSET): a call "
                    + "of it is refused. " + membership(tool);
        }

        private static String membership(String tool) {
            List<String> sets = containing(tool);
            return sets.isEmpty()
                    ? "No tool set contains it."
                    : "Tool sets that contain it: " + String.join(", ", sets) + ".";
        }
    }
}
