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
class QueryMetadataNamesTest {
    @Test void russianAndEnglishTablesNameTheSameMetadataObject() {
        assertTrue(QueryMetadataNames.matches("InformationRegister.History","РегистрСведений.History.СрезПоследних"));
        assertTrue(QueryMetadataNames.matches("InformationRegister.History","informationregister.history.SliceLast"));
        assertTrue(QueryMetadataNames.matches("Catalog.Products","Справочник.Products"));
    }
    @Test void similarlyNamedObjectsAndUnknownPrefixesDoNotMatch() {
        assertFalse(QueryMetadataNames.matches("Catalog.Products","Справочник.ProductsOld"));
        assertFalse(QueryMetadataNames.matches("Catalog.Products","РегистрСведений.Products"));
        assertFalse(QueryMetadataNames.matches("Catalog.Products","Products"));
    }
}
