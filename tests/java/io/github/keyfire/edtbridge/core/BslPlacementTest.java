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
import java.util.List;
import org.junit.jupiter.api.Test;
class BslPlacementTest {
    private final List<BslPlacement.Region> regions = List.of(
        new BslPlacement.Region("Parent", 0, 10, 190),
        new BslPlacement.Region("Child", 20, 30, 90),
        new BslPlacement.Region("Other", 200, 210, 390),
        new BslPlacement.Region("Child", 220, 230, 290));
    private final List<BslPlacement.Member> methods = List.of(
        new BslPlacement.Member("First", 40, 60),
        new BslPlacement.Member("Second", 70, 80),
        new BslPlacement.Member("Outer", 110, 130),
        new BslPlacement.Member("Elsewhere", 240, 260));
    @Test void regionPathSelectsTheRequestedSiblingAndReportsNeighbors() {
        var p = choose("Parent/Child", "Second", null);
        assertEquals(70, p.offset());
        assertEquals("First", p.previousMethod());
        assertEquals("Second", p.nextMethod());
    }
    @Test void appendsOnlyDirectMembersOfSelectedRegion() {
        assertEquals(130, choose("Parent", null, null).offset());
        assertEquals(80, choose("Parent/Child", null, null).offset());
    }
    @Test void refusesAmbiguousOrMissingRegionsAndConflictingAnchors() {
        assertThrows(IllegalArgumentException.class, () -> choose("Child", null, null));
        assertThrows(IllegalArgumentException.class, () -> choose("Missing", null, null));
        assertThrows(IllegalArgumentException.class, () -> choose(null, "First", "Second"));
        assertThrows(IllegalArgumentException.class, () -> choose("Parent/Child", "Elsewhere", null));
    }
    @Test void afterAnchorReportsBothNeighbors() {
        var p = choose("Parent/Child", null, "First");
        assertEquals(60, p.offset());
        assertEquals("First", p.previousMethod());
        assertEquals("Second", p.nextMethod());
    }
    @Test void emptyScopeInsertsAfterHeaderAndScopeLimitsLookup() {
        var p = BslPlacement.choose(400, regions, List.of(), 0, 200, "Parent/Child", null, null);
        assertEquals(30, p.offset());
        assertThrows(IllegalArgumentException.class, () -> BslPlacement.choose(400, regions, methods,
                0, 200, "Other/Child", null, null));
    }
    @Test void duplicateMethodsAndDuplicatePathsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> BslPlacement.choose(400, regions,
                List.of(new BslPlacement.Member("Same",40,50), new BslPlacement.Member("same",60,70)),
                0,400,null,"Same",null));
        assertThrows(IllegalArgumentException.class, () -> BslPlacement.choose(400,
                List.of(new BslPlacement.Region("Same",0,10,20),new BslPlacement.Region("Same",30,40,50)),
                List.of(),0,400,"Same",null,null));
    }
    private static final String P2 = "Процедура П2()\nКонецПроцедуры";

    private static String splice(String text, int position) {
        return text.substring(0, position) + BslPlacement.block(text, position, P2, "\n") + text.substring(position);
    }

    @Test void afterTheLastMethodOfARegionOneBlankLineStaysBeforeEndRegion() {
        String text = "#Область А\n\nПроцедура П1()\nКонецПроцедуры\n\n#КонецОбласти\n";
        int position = text.indexOf("КонецПроцедуры") + "КонецПроцедуры".length();
        assertEquals("#Область А\n\nПроцедура П1()\nКонецПроцедуры\n\n" + P2 + "\n\n#КонецОбласти\n",
                splice(text, position));
    }

    @Test void anEmptyRegionGetsTheMethodBetweenTwoBlankLines() {
        String expected = "#Область А\n\n" + P2 + "\n\n#КонецОбласти\n";
        for (String text : new String[] {"#Область А\n\n#КонецОбласти\n", "#Область А\n#КонецОбласти\n"}) {
            assertEquals(expected, splice(text, text.indexOf('\n') + 1), text);
        }
    }

    @Test void beforeAMethodTheBlankLineAboveIsNotDoubled() {
        String text = "Процедура П1()\nКонецПроцедуры\n\nПроцедура П3()\nКонецПроцедуры\n";
        assertEquals("Процедура П1()\nКонецПроцедуры\n\n" + P2 + "\n\nПроцедура П3()\nКонецПроцедуры\n",
                splice(text, text.indexOf("Процедура П3")));
    }

    @Test void windowsLineBreaksAreCountedAndWritten() {
        String text = "#Область А\r\n\r\nПроцедура П1()\r\nКонецПроцедуры\r\n\r\n#КонецОбласти\r\n";
        int position = text.indexOf("КонецПроцедуры") + "КонецПроцедуры".length();
        String method = P2.replace("\n", "\r\n");
        String spliced = text.substring(0, position) + BslPlacement.block(text, position, method, "\r\n")
                + text.substring(position);
        assertEquals("#Область А\r\n\r\nПроцедура П1()\r\nКонецПроцедуры\r\n\r\n" + method
                + "\r\n\r\n#КонецОбласти\r\n", spliced);
    }

    @Test void theStartAndTheEndOfTheTextGetNoBlankLineOutside() {
        String text = "Процедура П1()\nКонецПроцедуры\n";
        assertEquals(P2 + "\n\n" + text, splice(text, 0));
        assertEquals(text + "\n" + P2 + "\n", splice(text, text.length()));
        String unterminated = "Процедура П1()\nКонецПроцедуры";
        assertEquals(unterminated + "\n\n" + P2 + "\n", splice(unterminated, unterminated.length()));
    }

    @Test void blankLinesThatAreAlreadyThereStay() {
        String text = "Процедура П1()\nКонецПроцедуры\n\n\n\n#КонецОбласти\n";
        int position = text.indexOf("КонецПроцедуры") + "КонецПроцедуры".length();
        assertEquals("Процедура П1()\nКонецПроцедуры\n\n" + P2 + "\n\n\n\n#КонецОбласти\n", splice(text, position));
    }

    private BslPlacement.Point choose(String region, String before, String after) {
        return BslPlacement.choose(400, regions, methods, 0, 400, region, before, after);
    }
}
