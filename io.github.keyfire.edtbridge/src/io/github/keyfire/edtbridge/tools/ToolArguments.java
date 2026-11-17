/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.tools;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Compatibility names published in the schema and normalized before scope checks and dispatch. */
public final class ToolArguments {
    private record Alias(String name, String canonical) { }

    private ToolArguments() { }

    private static List<Alias> aliases(String tool) {
        return switch (tool) {
            case "edt_search_modules", "edt_search_queries" ->
                List.of(new Alias("query", "pattern"), new Alias("limit", "maxResults"));
            case "edt_check_info" -> List.of(new Alias("checkId", "check"));
            case "edt_property_reference" -> List.of(new Alias("formFqn", "fqn"));
            case "edt_module_text" -> List.of(new Alias("moduleFqn", "fqn"), new Alias("objectFqn", "fqn"));
            case "edt_validate_query" -> List.of(new Alias("query", "queryText"));
            case "edt_project_errors" -> List.of(new Alias("severities", "severity"), new Alias("levels", "severity"));
            case "edt_form_identifiers", "edt_form_structure", "edt_form_render",
                 "edt_add_form_attribute", "edt_modify_form_attribute", "edt_remove_form_attribute",
                 "edt_add_form_command", "edt_modify_form_command", "edt_remove_form_command",
                 "edt_add_form_handler", "edt_add_form_item", "edt_modify_form_item",
                 "edt_move_form_item", "edt_remove_form_item" -> List.of(new Alias("fqn", "formFqn"));
            default -> List.of();
        };
    }

    public static void describe(JsonObject tool) {
        String name = tool.get("name").getAsString();
        JsonObject schema = tool.getAsJsonObject("inputSchema");
        JsonObject props = schema.getAsJsonObject("properties");
        if (name.equals("edt_project_errors")) {
            JsonArray types = new JsonArray();
            types.add("string");
            types.add("array");
            var severity = props.getAsJsonObject("severity");
            severity.add("type", types);
            severity.add("items", ToolJson.strProp("Severity name"));
        }
        var aliases = aliases(name);
        for (Alias alias : aliases) {
            JsonObject property = props.getAsJsonObject(alias.canonical).deepCopy();
            property.addProperty("description", "Alias for " + alias.canonical + ". Pass only one name.");
            props.add(alias.name, property);
        }
        var required = schema.getAsJsonArray("required");
        if (required == null) {
            return;
        }
        for (String canonical : aliases.stream().map(Alias::canonical).distinct().toList()) {
            if (required.remove(new com.google.gson.JsonPrimitive(canonical))) {
                JsonArray options = new JsonArray();
                options.add(requiring(canonical));
                aliases.stream().filter(alias -> alias.canonical.equals(canonical))
                        .forEach(alias -> options.add(requiring(alias.name)));
                JsonObject alternative = new JsonObject();
                alternative.add("anyOf", options);
                if (!schema.has("allOf")) {
                    schema.add("allOf", new JsonArray());
                }
                schema.getAsJsonArray("allOf").add(alternative);
            }
        }
    }

    private static JsonObject requiring(String name) {
        JsonArray required = new JsonArray();
        required.add(name);
        JsonObject option = new JsonObject();
        option.add("required", required);
        return option;
    }

    public static void normalize(String tool, JsonObject args) {
        for (Alias alias : aliases(tool)) {
            if (args.has(alias.name)) {
                if (args.has(alias.canonical)) {
                    throw new IllegalArgumentException("pass only one of '" + alias.canonical + "' and '" + alias.name + "'");
                }
                args.add(alias.canonical, args.remove(alias.name));
            }
        }
        if (tool.equals("edt_project_errors") && args.has("severity") && args.get("severity").isJsonArray()) {
            StringBuilder joined = new StringBuilder();
            for (var severity : args.getAsJsonArray("severity")) {
                if (!severity.isJsonPrimitive() || !severity.getAsJsonPrimitive().isString() || severity.getAsString().isBlank()) {
                    throw new IllegalArgumentException("severity must contain non-empty strings");
                }
                if (joined.length() > 0) {
                    joined.append(',');
                }
                joined.append(severity.getAsString());
            }
            args.addProperty("severity", joined.toString());
        }
    }
}
