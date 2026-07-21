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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Resolving an object's sources from its FQN - the part that needs no workspace. */
class MetadataPathsTest {

    @ParameterizedTest
    @CsvSource({
        "Catalog.Товары,                     Catalogs/Товары",
        "Document.Заказ,                     Documents/Заказ",
        "CommonModule.ОбщийКлиент,           CommonModules/ОбщийКлиент",
        "CommonForm.Настройки,               CommonForms/Настройки",
        "InformationRegister.Курсы,          InformationRegisters/Курсы",
        "HTTPService.Payments,               HTTPServices/Payments",
        "WebService.Exchange,                WebServices/Exchange",
        "ExternalDataProcessor.Загрузка,     ExternalDataProcessors/Загрузка",
    })
    @DisplayName("an object FQN resolves to its source folder")
    void objectFolder(String fqn, String expected) {
        assertEquals(expected, MetadataPaths.objectFolder(fqn));
    }

    @Test
    @DisplayName("a form FQN resolves under its owner's Forms folder")
    void formFolder() {
        assertEquals("Catalogs/Товары/Forms/Список",
                MetadataPaths.objectFolder("Catalog.Товары.Form.Список"));
        assertEquals("DataProcessors/Обмен/Forms/Форма",
                MetadataPaths.objectFolder("DataProcessor.Обмен.Form.Форма"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"catalog.Товары", "CATALOG.Товары", "CaTaLoG.Товары"})
    @DisplayName("the type prefix is case-insensitive, as FQNs are written by hand")
    void typePrefixIsCaseInsensitive(String fqn) {
        assertEquals("Catalogs/Товары", MetadataPaths.objectFolder(fqn));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Unknown.Thing", "Catalog", "", "   ", "Unknown.Obj.Form.F"})
    @DisplayName("an FQN that names nothing resolvable yields null, never a guess")
    void unresolvableFqn(String fqn) {
        assertNull(MetadataPaths.objectFolder(fqn));
    }

    @Test
    void nullFqnIsHandled() {
        assertNull(MetadataPaths.objectFolder(null));
        assertNull(MetadataPaths.nameToken(null));
        assertNull(MetadataPaths.singleModulePath(null));
        assertFalse(MetadataPaths.hasSingleModule(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CommonModule.Общий", "HTTPService.Payments", "WebService.Exchange",
                            "CommonForm.Настройки", "Catalog.Товары.Form.Список"})
    @DisplayName("forms, common modules and services keep exactly one module file")
    void singleModuleOwners(String fqn) {
        assertTrue(MetadataPaths.hasSingleModule(fqn));
        assertTrue(MetadataPaths.singleModulePath(fqn).endsWith("/Module.bsl"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Catalog.Товары", "Document.Заказ", "InformationRegister.Курсы"})
    @DisplayName("a top object has several modules, so the path cannot follow from the FQN alone")
    void multiModuleOwners(String fqn) {
        assertFalse(MetadataPaths.hasSingleModule(fqn));
        assertNull(MetadataPaths.singleModulePath(fqn));
    }

    @Test
    @DisplayName("the HTTP service module path is the one edt_delete_method used to demand by hand")
    void httpServiceModulePath() {
        assertEquals("HTTPServices/Payments/Module.bsl",
                MetadataPaths.singleModulePath("HTTPService.Payments"));
    }

    @ParameterizedTest
    @CsvSource({
        "Catalog.Товары,                Товары",
        "HTTPService.Payments,          Payments",
        "Catalog.Товары.Form.Список,    Товары",
    })
    @DisplayName("the name token is the segment after the type prefix")
    void nameToken(String fqn, String expected) {
        assertEquals(expected, MetadataPaths.nameToken(fqn));
    }

    @Test
    @DisplayName("the folder maps are exact inverses - a mismatch is how the two used to disagree")
    void mapsAreInverses() {
        for (Map.Entry<String, String> entry : MetadataPaths.TYPE_BY_FOLDER.entrySet()) {
            String type = entry.getValue();
            assertEquals(entry.getKey(), MetadataPaths.FOLDER_BY_TYPE.get(type.toLowerCase()),
                    "folder " + entry.getKey() + " maps to type " + type + ", which must map back");
        }
    }

    @Test
    void folderForTypeIsNullForUnknownTypes() {
        assertNull(MetadataPaths.folderForType("NoSuchType"));
        assertNull(MetadataPaths.folderForType(null));
        assertEquals("Catalogs", MetadataPaths.folderForType("Catalog"));
    }

    @Test
    void isFormRecognisesBothFormShapes() {
        assertTrue(MetadataPaths.isForm("Catalog.Товары.Form.Список"));
        assertTrue(MetadataPaths.isForm("CommonForm.Настройки"));
        assertFalse(MetadataPaths.isForm("Catalog.Товары"));
        assertFalse(MetadataPaths.isForm("HTTPService.Payments"));
    }
}
