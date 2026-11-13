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
package io.github.keyfire.edtbridge.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import io.github.keyfire.edtbridge.core.PersonalData;

/**
 * The personal data filter on the tools whose answers carry infobase data (EDT-PII-01): the values of a
 * debugged frame, the value of an evaluated expression, the users and computers of the sessions.
 *
 * <p>The answer of such a tool is JSON in a text item; every string of data in it is filtered by
 * {@link PersonalData}, the paths, statuses and code are left alone, and the answer says how many values
 * were replaced in {@code piiFilter}. Settings that cannot be read withhold the answer instead of letting
 * it through unfiltered. Every other tool is untouched: its texts are code and model, not data.
 */
final class PersonalDataFilter {

    static final Set<String> TOOLS = Set.of("edt_debug_inspect", "edt_evaluate", "edt_infobase_sessions",
            "edt_infobase_maintenance", "edt_designer_agent");

    private PersonalDataFilter() {
    }

    /** The filter the settings name: EDT_BRIDGE_PII, its salt and its rules file (or the -Dedt.bridge.pii* properties). */
    static PersonalData fromSettings() {
        PersonalData.Mode mode = PersonalData.mode(
                System.getProperty("edt.bridge.pii", System.getenv(PersonalData.MODE_ENV)));
        String salt = System.getProperty("edt.bridge.pii.salt", System.getenv(PersonalData.SALT_ENV));
        String rules = System.getProperty("edt.bridge.pii.rules", System.getenv(PersonalData.RULES_ENV));
        List<PersonalData.Rule> values = new ArrayList<>();
        List<PersonalData.Rule> names = new ArrayList<>();
        if (mode != PersonalData.Mode.OFF && rules != null && !rules.isBlank()) {
            try {
                String text = Files.readString(Path.of(rules.strip()), StandardCharsets.UTF_8).replace("﻿", "");
                JsonObject data = JsonParser.parseString(text).getAsJsonObject();
                if (data.has("values")) {
                    for (JsonElement item : data.getAsJsonArray("values")) {
                        JsonObject rule = item.getAsJsonObject();
                        values.add(new PersonalData.Rule(rule.get("kind").getAsString(),
                                Pattern.compile(rule.get("pattern").getAsString())));
                    }
                }
                if (data.has("names")) {
                    for (JsonElement item : data.getAsJsonArray("names")) {
                        JsonObject rule = item.getAsJsonObject();
                        names.add(PersonalData.name(rule.get("kind").getAsString(), rule.get("pattern").getAsString()));
                    }
                }
            } catch (IOException | RuntimeException e) {
                throw new IllegalArgumentException(PersonalData.RULES_ENV + " " + rules + ": " + e.getMessage(), e);
            }
        }
        return new PersonalData(mode, salt, values, names);
    }

    /** The result of {@code tool}, filtered when the tool carries data and the filter is on. */
    static JsonObject apply(String tool, JsonObject result) {
        if (!TOOLS.contains(tool) || result == null) {
            return result;
        }
        PersonalData filter;
        try {
            filter = fromSettings();
        } catch (IllegalArgumentException e) {
            return McpServer.toolError(e.getMessage() + " - the answer of " + tool + " is withheld");
        }
        return filter.active() ? apply(result, filter) : result;
    }

    static JsonObject apply(JsonObject result, PersonalData filter) {
        JsonObject copy = result.deepCopy();
        if (!copy.has("content") || !copy.get("content").isJsonArray()) {
            return copy;
        }
        for (JsonElement element : copy.getAsJsonArray("content")) {
            if (!element.isJsonObject() || !element.getAsJsonObject().has("text")) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            String text = item.get("text").getAsString();
            JsonElement parsed = parse(text);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject masked = mask(parsed, filter, null).getAsJsonObject();
                JsonObject marker = new JsonObject();
                marker.addProperty("mode", filter.modeValue().name().toLowerCase(java.util.Locale.ROOT));
                marker.addProperty("masked", filter.count());
                masked.add("piiFilter", marker);
                item.addProperty("text", new GsonBuilder().setPrettyPrinting().create().toJson(masked));
            } else {
                item.addProperty("text", filter.text(text));
            }
        }
        return copy;
    }

    private static JsonElement parse(String text) {
        String trimmed = text.strip();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            return JsonParser.parseString(trimmed);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static JsonElement mask(JsonElement node, PersonalData filter, String name) {
        return mask(node, filter, name, false);
    }

    /**
     * The node with every value of data filtered; the structure and the technical keys stay. {@code name}
     * is the field the node belongs to; {@code data} says the node is the value of a field, so a number
     * there is filtered too. Elsewhere a number is a count, a line or a time, and only the name of its
     * field can make it personal data.
     */
    static JsonElement mask(JsonElement node, PersonalData filter, String name, boolean data) {
        if (node == null || node.isJsonNull()) {
            return node;
        }
        if (node.isJsonPrimitive()) {
            JsonPrimitive primitive = node.getAsJsonPrimitive();
            if (primitive.isString()) {
                return new JsonPrimitive(filter.named(name, primitive.getAsString()));
            }
            if (primitive.isNumber()) {
                String text = primitive.getAsString();
                String kind = filter.kindOf(name);
                if (kind != null) {
                    return new JsonPrimitive(filter.token(kind, text));
                }
                if (data) {
                    String masked = filter.text(text);
                    return masked.equals(text) ? primitive : new JsonPrimitive(masked);
                }
            }
            return primitive;
        }
        if (node.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement item : node.getAsJsonArray()) {
                out.add(mask(item, filter, name, data));
            }
            return out;
        }
        JsonObject object = node.getAsJsonObject();
        String owner = null;
        for (String key : PersonalData.OWNER_KEYS) {
            if (object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsJsonPrimitive().isString()) {
                owner = object.get(key).getAsString();
                break;
            }
        }
        JsonArray columns = object.has("columns") && object.get("columns").isJsonArray()
                ? object.getAsJsonArray("columns") : null;
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            boolean string = value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            if (PersonalData.TECHNICAL.contains(key)) {
                out.add(key, value);
            } else if (PersonalData.WHOLE.containsKey(key) && string && !value.getAsString().isEmpty()) {
                out.addProperty(key, filter.token(PersonalData.WHOLE.get(key), value.getAsString()));
            } else if ("rows".equals(key) && columns != null && value.isJsonArray()) {
                JsonArray rows = new JsonArray();
                for (JsonElement row : value.getAsJsonArray()) {
                    if (!row.isJsonArray()) {
                        rows.add(mask(row, filter, null));
                        continue;
                    }
                    JsonArray cells = new JsonArray();
                    JsonArray source = row.getAsJsonArray();
                    for (int i = 0; i < source.size(); i++) {
                        cells.add(mask(source.get(i), filter, columnName(columns, i), true));
                    }
                    rows.add(cells);
                }
                out.add(key, rows);
            } else if (PersonalData.VALUE_KEYS.contains(key) && owner != null && PersonalData.WHOLE.containsKey(owner)
                    && string && !value.getAsString().isEmpty()) {
                out.addProperty(key, filter.token(PersonalData.WHOLE.get(owner), value.getAsString()));
            } else if (PersonalData.VALUE_KEYS.contains(key)) {
                out.add(key, mask(value, filter, owner, true));
            } else {
                out.add(key, mask(value, filter, key, false));
            }
        }
        return out;
    }

    private static String columnName(JsonArray columns, int index) {
        if (index >= columns.size() || !columns.get(index).isJsonObject()) {
            return null;
        }
        JsonElement name = columns.get(index).getAsJsonObject().get("name");
        return name != null && name.isJsonPrimitive() ? name.getAsString() : null;
    }
}
