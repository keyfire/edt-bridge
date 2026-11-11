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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The names offered in place of a tool name nothing answers to. */
class ToolNamesTest {

    private static final List<String> KNOWN = Arrays.asList(
            "edt_add_form", "edt_add_form_item", "edt_add_route", "edt_form_render",
            "edt_form_structure", "edt_projects", "edt_project_errors", "edt_tool_help");

    @Test
    @DisplayName("a misspelled name finds the one it was meant to be")
    void aTypoFindsItsName() {
        assertEquals(List.of("edt_add_form"), ToolNames.nearest("edt_add_from", KNOWN, 5));
        assertEquals(List.of("edt_projects"), ToolNames.nearest("edt_project", KNOWN, 1));
        assertEquals(List.of("edt_add_form"), ToolNames.nearest("EDT-Add-Form", KNOWN, 1));
    }

    @Test
    @DisplayName("a name without the prefix, or a fragment, finds the names that contain it")
    void aFragmentFindsWhatContainsIt() {
        assertEquals(List.of("edt_tool_help"), ToolNames.nearest("tool_help", KNOWN, 5));
        assertEquals(List.of("edt_form_render", "edt_form_structure"),
                ToolNames.nearest("edt_form", KNOWN, 5));
    }

    @Test
    @DisplayName("the nearest name comes first and the list stops at the limit")
    void nearestFirstUpToTheLimit() {
        assertEquals(List.of("edt_add_form", "edt_add_form_item"),
                ToolNames.nearest("add_form", KNOWN, 5));
        assertEquals(List.of("edt_add_form"), ToolNames.nearest("add_form", KNOWN, 1));
        assertEquals(List.of("edt_projects", "edt_project_errors"),
                ToolNames.nearest("edt_project", KNOWN, 5));
    }

    @Test
    @DisplayName("the refusal for an unknown name carries the names to try next")
    void theRefusalNamesTheNeighbours() {
        String refusal = ToolNames.unknownRefusal("edt_tool_help", "edt_add_from", KNOWN);
        assertTrue(refusal.startsWith("edt_tool_help: no tool is named 'edt_add_from'."), refusal);
        assertTrue(refusal.contains("Nearest names: edt_add_form."), refusal);
        assertTrue(refusal.contains("tools/list"), refusal);

        String far = ToolNames.unknownRefusal("edt_tool_help", "whatever_else", KNOWN);
        assertTrue(far.contains("no tool is named 'whatever_else'"), far);
        assertTrue(far.contains("No tool name is close to it."), far);
        assertTrue(!far.contains("Nearest names"), far);
    }

    @Test
    @DisplayName("a name that resembles nothing gets no guess")
    void aFarNameGetsNothing() {
        assertEquals(List.of(), ToolNames.nearest("whatever_else", KNOWN, 5));
        assertEquals(List.of(), ToolNames.nearest("edt", KNOWN, 5));
        assertEquals(List.of(), ToolNames.nearest("", KNOWN, 5));
        assertEquals(List.of(), ToolNames.nearest(null, KNOWN, 5));
        assertEquals(List.of(), ToolNames.nearest("edt_add_form", KNOWN, 0));
    }
}
