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

import java.util.LinkedHashMap;
import java.util.Map;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Typed optional metadata field settings. */
final class MetadataFieldToolOptions {
    private MetadataFieldToolOptions() { }
    static void properties(JsonObject props) {
        JsonObject use = ToolJson.strProp("Hierarchical field use: ForFolder, ForItem or ForFolderAndItem. Optional; rejected on other fields.");
        JsonArray values = new JsonArray(); values.add("ForFolder"); values.add("ForItem"); values.add("ForFolderAndItem");
        use.add("enum", values); props.add("use", use);
        props.add("multiLine", ToolJson.boolProp("Allow multiline field input. Optional; the metadata field must support this property."));
    }
    static Map<String, Object> values(JsonObject args) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (args.has("use")) {
            var value = args.get("use");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("use must be a string");
            result.put("use", value.getAsString());
        }
        if (args.has("multiLine")) {
            var value = args.get("multiLine");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("multiLine must be boolean");
            result.put("multiLine", value.getAsBoolean());
        }
        return result;
    }
}
