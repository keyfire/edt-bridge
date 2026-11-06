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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

class DecorationToolTest {

    @Test
    void advertisesSupportedTypesWithoutRequiringTheParameter() {
        JsonObject schema = new AddFormItemTool().descriptor().getAsJsonObject("inputSchema");
        JsonObject types = schema.getAsJsonObject("properties").getAsJsonObject("decorationType");
        assertTrue(types != null, "Clients must be able to discover the decoration type parameter");
        assertEquals("[\"Label\",\"Picture\"]", types.getAsJsonArray("enum").toString());
        assertEquals("[\"projectName\",\"formFqn\",\"kind\"]", schema.getAsJsonArray("required").toString());
    }

    @Test
    void rejectsUnknownTypesBeforeOpeningTheWorkspace() {
        JsonObject args = itemArgs();
        args.addProperty("kind", "decoration");
        args.addProperty("decorationType", "Video");

        JsonObject result = new AddFormItemTool().call(args);
        assertTrue(result.get("isError").getAsBoolean());
        assertTrue(message(result).contains("Label or Picture"), message(result));
    }

    @Test
    void rejectsTypeChangesBeforeApplyingOtherEdits() {
        JsonObject args = itemArgs();
        args.addProperty("decorationType", "Label");
        args.addProperty("visible", false);

        JsonObject result = new ModifyFormItemTool().call(args);
        assertTrue(result.get("isError").getAsBoolean());
        assertTrue(message(result).contains("decorationType"), message(result));
    }

    private static JsonObject itemArgs() {
        JsonObject args = new JsonObject();
        args.addProperty("projectName", "Sample");
        args.addProperty("formFqn", "CommonForm.Sample");
        args.addProperty("name", "Decoration");
        args.addProperty("apply", true);
        return args;
    }

    private static String message(JsonObject result) {
        return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }
}
