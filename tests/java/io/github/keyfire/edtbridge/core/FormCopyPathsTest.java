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

class FormCopyPathsTest {

    @Test void anObjectFormNamesItsOwnerAndName() {
        FormCopyPaths.Source source = FormCopyPaths.parse(" DataProcessor.ПодачаЗаявки.Form.Форма ");
        assertEquals("DataProcessor.ПодачаЗаявки", source.ownerFqn());
        assertEquals("Форма", source.formName());
        assertFalse(source.common());
        assertEquals("DataProcessor.ПодачаЗаявки.Form.Форма", source.fqn());
    }

    @Test void aCommonFormHasNoOwner() {
        FormCopyPaths.Source source = FormCopyPaths.parse("CommonForm.Вопрос");
        assertTrue(source.common());
        assertNull(source.ownerFqn());
        assertEquals("CommonForm.Вопрос", source.fqn());
    }

    @Test void acceptTheFormContentFqnReturnedByAddForm() {
        assertEquals(FormCopyPaths.parse("Catalog.Items.Form.List"),
                FormCopyPaths.parse("Catalog.Items.Form.List.Form"));
        assertEquals(FormCopyPaths.parse("CommonForm.Confirm"),
                FormCopyPaths.parse("CommonForm.Confirm.Form"));
    }

    @Test void otherTextIsNotAForm() {
        assertNull(FormCopyPaths.parse(null));
        assertNull(FormCopyPaths.parse("DataProcessor.ПодачаЗаявки"));
        assertNull(FormCopyPaths.parse("DataProcessor.ПодачаЗаявки.Form"));
        assertNull(FormCopyPaths.parse("Обработка.ПодачаЗаявки.Форма.Форма"));
        assertNull(FormCopyPaths.parse("DataProcessor.ПодачаЗаявки.Form.Форма.Module"));
    }

    @Test void theCopyKeepsTheSourceNameUnlessAnotherIsGiven() {
        assertEquals("Форма", FormCopyPaths.copyName(null, "Форма", null));
        assertEquals("Форма", FormCopyPaths.copyName("  ", "Форма", null));
        assertEquals("ФормаПодачиЗаявки", FormCopyPaths.copyName(" ФормаПодачиЗаявки ", "Форма", null));
    }

    @Test void aFormInAnAdoptedObjectTakesThePrefixOnce() {
        assertEquals("ПНК_Форма", FormCopyPaths.copyName(null, "Форма", "ПНК_"));
        assertEquals("ПНК_ФормаЗаявки", FormCopyPaths.copyName("ФормаЗаявки", "Форма", "ПНК_"));
        assertEquals("пнк_Форма", FormCopyPaths.copyName("пнк_Форма", "Форма", "ПНК_"), "a prefix in another case counts");
    }

    @Test void onlyAnIdentifierNamesAForm() {
        assertTrue(FormCopyPaths.isIdentifier("ФормаПодачиЗаявки"));
        assertTrue(FormCopyPaths.isIdentifier("_Form2"));
        assertFalse(FormCopyPaths.isIdentifier("2Форма"));
        assertFalse(FormCopyPaths.isIdentifier("Форма заявки"));
        assertFalse(FormCopyPaths.isIdentifier(null));
    }
}
