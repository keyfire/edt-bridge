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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FormPropertyValuesTest {

    @Test
    void normalizesActionPurposeAliases() {
        assertNull(FormPropertyValues.normalizeActionPurpose(null));
        assertEquals("", FormPropertyValues.normalizeActionPurpose("   "));
        assertEquals("Usual", FormPropertyValues.normalizeActionPurpose("Normal"));
        assertEquals("Usual", FormPropertyValues.normalizeActionPurpose(" normal "));
        assertEquals("Usual", FormPropertyValues.normalizeActionPurpose("Usual"));
        assertEquals("Finish", FormPropertyValues.normalizeActionPurpose("Finish"));
        assertEquals("Create", FormPropertyValues.normalizeActionPurpose("Create"));
    }

    @Test
    void parsesNonNegativeIntegersAndRejectsInvalidValues() {
        assertEquals(0, FormPropertyValues.parseNonNegativeInt("width", 0));
        assertEquals(64, FormPropertyValues.parseNonNegativeInt("width", new BigDecimal("64")));
        assertEquals(12, FormPropertyValues.parseNonNegativeInt("height", new BigDecimal("12.0")));
        assertThrows(IllegalArgumentException.class,
                () -> FormPropertyValues.parseNonNegativeInt("width", -1));
        assertThrows(IllegalArgumentException.class,
                () -> FormPropertyValues.parseNonNegativeInt("width", new BigDecimal("1.5")));
        assertThrows(IllegalArgumentException.class,
                () -> FormPropertyValues.parseNonNegativeInt("width", "10"));
    }

    @Test
    void normalizesBslModuleTargetPaths() {
        assertEquals("/Proj/src/ExternalDataProcessors/Тест/Forms/Форма/Module.bsl",
                FormPropertyValues.moduleTargetPath(
                        "Proj\\src\\ExternalDataProcessors\\Тест\\Forms\\Форма\\Module.bsl"));
        assertEquals("/Proj/src/CommonModules/Api/Module.bsl",
                FormPropertyValues.moduleTargetPath("/Proj/src/CommonModules/Api/Module.bsl"));
        assertNull(FormPropertyValues.moduleTargetPath("/Proj/src/Catalogs/A/Forms/F/Form.form"));
        assertNull(FormPropertyValues.moduleTargetPath("   "));
        assertNull(FormPropertyValues.moduleTargetPath(null));
    }

    @Test
    void tracksAndAcknowledgesPendingFormsPerProject() {
        var pending = new FormPropertyValues.PendingForms();
        pending.mark("P1", "ExternalDataProcessor.Test.Form.Main");
        pending.mark("P1", "Catalog.Users.Form.Item");
        pending.mark("P2", "CommonForm.Dialog");
        pending.mark("P1", "  ");

        assertEquals(Set.of("ExternalDataProcessor.Test.Form.Main", "Catalog.Users.Form.Item"),
                pending.snapshot("P1"));
        assertEquals(Set.of("CommonForm.Dialog"), pending.snapshot("P2"));

        pending.acknowledge("P1", List.of("ExternalDataProcessor.Test.Form.Main"));
        assertEquals(Set.of("Catalog.Users.Form.Item"), pending.snapshot("P1"));

        pending.acknowledge("P1", List.of("Catalog.Users.Form.Item"));
        assertTrue(pending.snapshot("P1").isEmpty());
        assertEquals(Set.of("CommonForm.Dialog"), pending.snapshot("P2"));
    }
}
