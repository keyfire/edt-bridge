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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Typed form-item options shared by the add, modify and move tools. */
final class FormItemToolOptions {
    private FormItemToolOptions() { }
    static void positionProps(JsonObject props, boolean move) {
        JsonObject index = new JsonObject(); index.addProperty("type", "integer"); index.addProperty("minimum", 0);
        index.addProperty("description", "Zero-based sibling index after excluding the moved item. Exclusive with beforeItem/afterItem.");
        props.add("index", index);
        props.add("beforeItem", ToolJson.strProp("Insert before this direct sibling in the destination. Exclusive with index/afterItem."));
        props.add("afterItem", ToolJson.strProp("Insert after this direct sibling in the destination. Exclusive with index/beforeItem."));
        if (move) { props.add("parent", ToolJson.strProp("Destination container. Omitted keeps the current parent; an empty string selects the form root. Moves that would change the item kind or table data binding are refused.")); }
    }
    static void settingsProps(JsonObject props) {
        props.add("groupDirection", ToolJson.strProp("Usual-group child layout: Auto, Horizontal, Vertical, HorizontalIfPossible or AlwaysHorizontal. Optional."));
        props.add("group", ToolJson.strProp("Alias of groupDirection. Do not combine the two names."));
        props.add("representation", ToolJson.strProp("Representation enum supported by the current group or button, e.g. None/WeakSeparation/StrongSeparation for a usual group. Optional."));
        props.add("showTitle", ToolJson.boolProp("Show the usual group's title. Optional."));
        props.add("united", ToolJson.boolProp("Keep usual-group children united. Optional."));
        props.add("titleLocation", ToolJson.strProp("Field title location (Auto/Left/Right/Top/Bottom/None), or usual-group child title location. The value must exist in the target EDT enum. Optional."));
        JsonObject maxHeight = new JsonObject(); maxHeight.addProperty("type", "integer"); maxHeight.addProperty("minimum", 0);
        maxHeight.addProperty("description", "Maximum input-field height; disables autoMaxHeight. Requires InputField. Optional."); props.add("maxHeight", maxHeight);
        props.add("verticalStretch", ToolJson.boolProp("Vertical stretch of a group or input field. Optional."));
        JsonObject type = ToolJson.strProp("Field kind. EDT initializes the matching ext-info. Optional.");
        JsonArray types = new JsonArray(); types.add("InputField"); types.add("RadioButtonField"); types.add("CheckBoxField"); type.add("enum", types); props.add("fieldType", type);
        JsonObject choices = new JsonObject(); choices.addProperty("type", "array");
        choices.addProperty("description", "Replace the InputField/RadioButtonField choice list. Entries have a scalar string/number/boolean value and optional titleRu. Enum/reference/object values are not supported. An empty array clears the list.");
        JsonObject entry = new JsonObject(); entry.addProperty("type", "object"); entry.addProperty("additionalProperties", false);
        JsonObject fields = new JsonObject(); JsonObject value = new JsonObject(); JsonArray scalarTypes = new JsonArray();
        scalarTypes.add("string"); scalarTypes.add("number"); scalarTypes.add("boolean"); value.add("type", scalarTypes);
        fields.add("value", value); fields.add("titleRu", ToolJson.strProp("Russian choice presentation. Optional.")); entry.add("properties", fields);
        JsonArray required = new JsonArray(); required.add("value"); entry.add("required", required); choices.add("items", entry); props.add("choiceList", choices);
    }
    static Integer index(JsonObject args) {
        if (!args.has("index") || args.get("index").isJsonNull()) { return null; }
        JsonElement value = args.get("index");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) { throw new IllegalArgumentException("index must be an integer"); }
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("index must be an integer"); }
    }
    static Map<String, Object> settings(JsonObject args) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (args.has("group") && args.has("groupDirection")) { throw new IllegalArgumentException("group and groupDirection are aliases; pass one"); }
        for (String key : List.of("groupDirection", "group", "representation", "showTitle", "united", "titleLocation", "maxHeight", "verticalStretch", "fieldType", "choiceList")) {
            if (args.has(key)) {
                result.put("group".equals(key) ? "groupDirection" : key, value(args.get(key)));
            }
        }
        return result;
    }
    private static Object value(JsonElement element) {
        if (element == null || element.isJsonNull()) { throw new IllegalArgumentException("form item option values cannot be null"); }
        if (element.isJsonArray()) {
            List<Object> result = new ArrayList<>(); for (JsonElement entry : element.getAsJsonArray()) { result.add(value(entry)); } return result;
        }
        if (element.isJsonObject()) {
            Map<String, Object> result = new LinkedHashMap<>(); element.getAsJsonObject().entrySet().forEach(entry -> result.put(entry.getKey(), value(entry.getValue()))); return result;
        }
        JsonPrimitive value = element.getAsJsonPrimitive();
        if (value.isBoolean()) { return value.getAsBoolean(); }
        if (value.isNumber()) { return value.getAsBigDecimal(); }
        return value.getAsString();
    }
}
