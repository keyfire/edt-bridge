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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

class ModuleTextToolTest {

    @Test
    void aMethodReadLeavesTheCatalogueOutUnlessItIsAskedFor() {
        JsonObject none = new JsonObject();
        assertFalse(ModuleTextTool.includeMethods(none, "FormOnCreateAtServer"));
        assertTrue(ModuleTextTool.includeMethods(none, null));

        JsonObject asked = new JsonObject();
        asked.addProperty("includeMethods", true);
        assertTrue(ModuleTextTool.includeMethods(asked, "FormOnCreateAtServer"));

        JsonObject refused = new JsonObject();
        refused.addProperty("includeMethods", false);
        assertFalse(ModuleTextTool.includeMethods(refused, null));

        JsonObject unset = new JsonObject();
        unset.add("includeMethods", JsonNull.INSTANCE);
        assertFalse(ModuleTextTool.includeMethods(unset, "FormOnCreateAtServer"));
    }

    @Test
    void theDescriptionSaysWhenTheCatalogueComes() {
        JsonObject descriptor = new ModuleTextTool().descriptor();
        String includeMethods = descriptor.getAsJsonObject("inputSchema").getAsJsonObject("properties")
                .getAsJsonObject("includeMethods").get("description").getAsString();
        assertTrue(includeMethods.contains("false when method is given"), includeMethods);
        assertTrue(descriptor.get("description").getAsString().contains("methodsIncluded: false"));
    }
}
