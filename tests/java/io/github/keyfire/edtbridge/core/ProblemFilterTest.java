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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Narrowing validation problems to the object the caller asked about. */
class ProblemFilterTest {

    // -- the defect a live run caught ------------------------------------------------------------

    @Test
    @DisplayName("a neighbour whose name merely starts the same is NOT in scope")
    void neighbourIsNotDraggedIn() {
        // Found on a live model: asking for one service returned a problem belonging to <name>_v2,
        // because the name was matched as a substring.
        assertFalse(ProblemFilter.matchesLocation("HTTPСервис.Payments_v2.Модуль", null, "Payments"));
        assertTrue(ProblemFilter.matchesLocation("HTTPСервис.Payments.Модуль", null, "Payments"));
    }

    @Test
    @DisplayName("the name matches only as a whole identifier segment")
    void nameMatchesWholeSegmentsOnly() {
        assertTrue(ProblemFilter.namesSegment("httpсервис.payments.модуль", "payments"));
        assertFalse(ProblemFilter.namesSegment("httpсервис.payments_v2.модуль", "payments"));
        assertFalse(ProblemFilter.namesSegment("httpсервис.prepayments.модуль", "payments"));
    }

    @Test
    @DisplayName("Cyrillic names are segments too - identifiers here are usually Russian")
    void cyrillicSegments() {
        assertTrue(ProblemFilter.namesSegment("Справочник.Товары.Модуль", "Товары"));
        assertFalse(ProblemFilter.namesSegment("Справочник.ТоварыСклада.Модуль", "Товары"));
    }

    @Test
    void segmentMatchIsCaseInsensitive() {
        assertTrue(ProblemFilter.namesSegment("Справочник.Товары.Модуль", "товары"));
        assertTrue(ProblemFilter.matchesLocation("HTTPСервис.Payments.Модуль", null, "payments"));
    }

    // -- Eclipse markers, addressed by path ------------------------------------------------------

    @Test
    @DisplayName("a resource under the requested folder is in scope")
    void pathPrefixMatches() {
        assertTrue(ProblemFilter.matchesLocation(
                "src/CommonModules/Общий/Module.bsl", "src/CommonModules/Общий", null));
        assertTrue(ProblemFilter.matchesLocation(
                "src/HTTPServices/Payments/Module.bsl", "src/HTTPServices/Payments/Module.bsl", null));
    }

    @Test
    void anUnrelatedPathIsOutOfScope() {
        assertFalse(ProblemFilter.matchesLocation(
                "src/CommonModules/Другой/Module.bsl", "src/CommonModules/Общий", null));
    }

    @Test
    @DisplayName("backslashes and case do not decide whether a path matches")
    void pathComparisonIsNormalised() {
        assertTrue(ProblemFilter.matchesLocation(
                "src\\CommonModules\\Общий\\Module.bsl", "src/CommonModules/Общий", null));
        assertTrue(ProblemFilter.matchesLocation(
                "SRC/CommonModules/Общий/Module.bsl", "src/commonmodules/Общий", null));
    }

    // -- edges -----------------------------------------------------------------------------------

    @Test
    void withoutAnyFilterNothingMatchesOnItsOwn() {
        assertFalse(ProblemFilter.matchesLocation("src/CommonModules/Общий/Module.bsl", null, null));
    }

    @Test
    void blankFiltersAreIgnoredRatherThanMatchingEverything() {
        assertFalse(ProblemFilter.matchesLocation("src/CommonModules/Общий/Module.bsl", "  ", "  "));
    }

    @Test
    void nullResourceIsSafe() {
        assertFalse(ProblemFilter.matchesLocation(null, "src/CommonModules/Общий", "Общий"));
        assertFalse(ProblemFilter.namesSegment(null, "Общий"));
        assertFalse(ProblemFilter.namesSegment("Справочник.Товары", null));
    }

    @Test
    @DisplayName("either address may match - a path filter and a presentation filter are alternatives")
    void eitherAddressMatches() {
        assertTrue(ProblemFilter.matchesLocation(
                "HTTPСервис.Payments.Модуль", "src/HTTPServices/Payments", "Payments"));
        assertTrue(ProblemFilter.matchesLocation(
                "src/HTTPServices/Payments/Module.bsl", "src/HTTPServices/Payments", "Payments"));
    }
}
