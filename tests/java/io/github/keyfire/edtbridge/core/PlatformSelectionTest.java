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

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which installed build answers a request for one - a pinned build, or the newest of a line. */
class PlatformSelectionTest {

    /** What a stand running 8.5.1.1302 typically has beside it. */
    private static final List<String> INSTALLED =
            List.of("8.5.1.1464", "8.5.1.1302", "8.3.24.1548");

    /** The gateways filter by {@code accepts} and order by {@code compare}; so does this. */
    private static List<String> select(List<String> installed, String requested) {
        return installed.stream()
                .filter(v -> PlatformSelection.accepts(v, requested))
                .sorted((a, b) -> PlatformSelection.compare(a, b, requested))
                .toList();
    }

    @Test
    @DisplayName("four digits are a build, three or fewer are a line")
    void shape() {
        assertTrue(PlatformSelection.isBuild("8.5.1.1302"));
        assertTrue(PlatformSelection.isBuild("  8.5.1.1302  "));
        assertFalse(PlatformSelection.isBuild("8.5.1"));
        assertFalse(PlatformSelection.isBuild("8.5"));
        assertFalse(PlatformSelection.isBuild(null));
        assertFalse(PlatformSelection.isBuild("8.5.1.*"));
    }

    @Test
    @DisplayName("the line is the first three groups, whatever was asked for")
    void line() {
        assertEquals("8.5.1", PlatformSelection.line("8.5.1.1302"));
        assertEquals("8.5.1", PlatformSelection.line("8.5.1"));
        assertEquals("8.5", PlatformSelection.line("8.5"));
        assertNull(PlatformSelection.line(null));
        assertNull(PlatformSelection.line("  "));
    }

    @Test
    @DisplayName("the reported bug: a pinned build is not answered with a newer one of its line")
    void pinnedBuildWins() {
        assertEquals(List.of("8.5.1.1302"), select(INSTALLED, "8.5.1.1302"));
    }

    @Test
    @DisplayName("a line still means the newest build of it, then everything descending")
    void lineTakesTheNewest() {
        assertEquals(List.of("8.5.1.1464", "8.5.1.1302", "8.3.24.1548"), select(INSTALLED, "8.5.1"));
        assertEquals(List.of("8.3.24.1548", "8.5.1.1464", "8.5.1.1302"), select(INSTALLED, "8.3.24"));
    }

    @Test
    @DisplayName("nothing requested leaves every install, newest first")
    void noRequest() {
        assertEquals(List.of("8.5.1.1464", "8.5.1.1302", "8.3.24.1548"), select(INSTALLED, null));
        assertEquals(List.of("8.5.1.1464", "8.5.1.1302", "8.3.24.1548"), select(INSTALLED, ""));
    }

    @Test
    @DisplayName("builds order by number, where a string comparison would lie")
    void numericOrder() {
        assertEquals(List.of("8.5.1.1464", "8.5.1.999"),
                select(List.of("8.5.1.999", "8.5.1.1464"), "8.5.1"));
    }

    @Test
    @DisplayName("a pinned build that is not installed leaves no candidate at all")
    void pinnedBuildMissing() {
        assertEquals(List.of(), select(INSTALLED, "8.5.1.1317"));
    }

    @Test
    @DisplayName("a running tool serves a pinned request only when it is that build")
    void runningTool() {
        assertTrue(PlatformSelection.accepts("8.5.1.1302", "8.5.1.1302"));
        assertFalse(PlatformSelection.accepts("8.5.1.1464", "8.5.1.1302"));
        // A line asks for no more than a fresh start would have given, so anything running serves.
        assertTrue(PlatformSelection.accepts("8.3.24.1548", "8.5.1"));
        assertTrue(PlatformSelection.accepts("8.5.1.1464", null));
    }

    @Test
    @DisplayName("the refusal names the build asked for, what is installed, and the way out")
    void refusalIsActionable() {
        String m = PlatformSelection.unavailable("configurator", "8.5.1.1302", List.of("8.5.1.1464"));
        assertTrue(m.contains("8.5.1.1302"), m);
        assertTrue(m.contains("8.5.1.1464"), m);
        assertTrue(m.contains("line 8.5.1"), m);

        String line = PlatformSelection.unavailable("configurator", "8.5.1", List.of());
        assertTrue(line.contains("version line 8.5.1"), line);
        assertTrue(line.contains("none is installed"), line);

        String any = PlatformSelection.unavailable("configurator", null, List.of("8.3.24.1548"));
        assertFalse(any.contains("line"), any);
        assertTrue(any.contains("8.3.24.1548"), any);
    }

    @Test
    @DisplayName("a running agent of another build is refused by naming both builds")
    void runningRefusalNamesBoth() {
        String m = PlatformSelection.alreadyRunning("8.5.1.1464", "8.5.1.1302");
        assertTrue(m.contains("8.5.1.1464"), m);
        assertTrue(m.contains("8.5.1.1302"), m);
    }
}
