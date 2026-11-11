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

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ModuleFragmentTest {
    private static final String MODULE = "#Region Variables\nVar Cache;\n#EndRegion\n\n"
            + "&AtServer\nProcedure Load()\nEndProcedure\n";

    @Test
    void replacesAFragmentOutsideMethods() {
        var edit = ModuleFragment.replace(MODULE, "Var Cache;", "Var Cache Export;");
        assertTrue(edit.result().contains("Var Cache Export;\n#EndRegion"));
        assertEquals(2, edit.line());
    }

    @Test
    void adaptsLineBreaksToTheModule() {
        String windows = MODULE.replace("\n", "\r\n");
        var edit = ModuleFragment.replace(windows, "&AtServer\nProcedure Load()", "&AtClient\nProcedure Load()");
        assertTrue(edit.result().contains("&AtClient\r\nProcedure Load()"));
        assertFalse(edit.result().contains("&AtClient\nProcedure"));
    }

    @Test
    void ambiguousFragmentIsRefused() {
        String twice = MODULE + "Var Cache;\n";
        var error = assertThrows(IllegalArgumentException.class,
                () -> ModuleFragment.replace(twice, "Var Cache;", "Var Other;"));
        assertTrue(error.getMessage().contains("2 times"));
    }

    @Test
    void missingOrEmptyFragmentIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ModuleFragment.replace(MODULE, "Var Absent;", "x"));
        assertThrows(IllegalArgumentException.class, () -> ModuleFragment.replace(MODULE, "", "x"));
        assertThrows(IllegalArgumentException.class, () -> ModuleFragment.replace(MODULE, "Var Cache;", null));
    }

    @Test
    void aBatchOpensAndClosesARegion() {
        var edit = ModuleFragment.replaceAll(MODULE, java.util.List.of(
                new ModuleFragment.Change("&AtServer\n", "#Region Handlers\n&AtServer\n"),
                new ModuleFragment.Change("EndProcedure\n", "EndProcedure\n#EndRegion\n")));
        assertTrue(edit.result().endsWith("#Region Handlers\n&AtServer\nProcedure Load()\nEndProcedure\n#EndRegion\n"));
        assertEquals(5, edit.line());
    }

    @Test
    void aBatchNamesTheReplacementThatFailed() {
        var error = assertThrows(IllegalArgumentException.class, () -> ModuleFragment.replaceAll(MODULE,
                java.util.List.of(new ModuleFragment.Change("Var Cache;", "Var A;"),
                        new ModuleFragment.Change("Var Cache;", "Var B;"))));
        assertTrue(error.getMessage().startsWith("replacement 2:"));
    }

    @Test
    void emptyReplacementDeletesTheFragment() {
        var edit = ModuleFragment.replace(MODULE, "Var Cache;\n", "");
        assertFalse(edit.result().contains("Var Cache"));
    }
}
