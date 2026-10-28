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

class SearchPatternTest {
    @Test
    void cyrillicWordClassesFindACompleteIdentifier() {
        assertTrue(SearchPattern.compile("\\bСоздать\\w+\\b", true)
                .matcher("Справочники.Товары.СоздатьЭлемент()").find());
        assertFalse(SearchPattern.compile("\\bСоздатьЭлемент\\b", true)
                .matcher("СоздатьЭлементКопию()").find());
    }

    @Test
    void defaultCaseFoldingIncludesCyrillic() {
        assertTrue(SearchPattern.compile("создатьэлемент", false)
                .matcher("СоздатьЭлемент()").find());
        assertFalse(SearchPattern.compile("создатьэлемент", true)
                .matcher("СоздатьЭлемент()").find());
    }

    @Test
    void latinIdentifiersRetainTheirWordBoundaries() {
        assertTrue(SearchPattern.compile("\\bCreate\\w+\\b", true)
                .matcher("Catalog.Items.CreateItem()").find());
        assertFalse(SearchPattern.compile("\\bCreateItem\\b", true)
                .matcher("CreateItemCopy()").find());
    }
}
