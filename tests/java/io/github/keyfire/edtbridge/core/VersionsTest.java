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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Version literals ordered by digit groups, where a string comparison would lie. */
class VersionsTest {

    @Test
    @DisplayName("digit groups are extracted from a dotted literal")
    void digitGroups() {
        assertArrayEquals(new int[] {8, 5, 1}, Versions.numbers("8.5.1"));
        assertArrayEquals(new int[] {8, 3, 21}, Versions.numbers("Version8_3_21"));
        assertArrayEquals(new int[0], Versions.numbers("DontUse"));
        assertArrayEquals(new int[0], Versions.numbers(null));
        assertArrayEquals(new int[0], Versions.numbers("v99999999999999999999"));
    }

    @Test
    @DisplayName("groups compare numerically, not lexicographically")
    void numericOrder() {
        assertTrue(Versions.compare("8.10", "8.5") > 0);
        assertTrue(Versions.compare("8.5", "8.5.1") < 0);
        assertTrue(Versions.compare("8.5.1", "8.5.2") < 0);
        assertEquals(0, Versions.compare("8.5.1", "Version8_5_1"));
    }

    @Test
    @DisplayName("a literal without digits sorts below any real version")
    void digitlessSortsBelow() {
        assertTrue(Versions.compare("DontUse", "8.5.1") < 0);
        assertTrue(Versions.compare("8.5.1", "DontUse") > 0);
        assertEquals(0, Versions.compare("DontUse", null));
    }

    @Test
    @DisplayName("the compatibility gate: 8.5.1 and below versus above")
    void compatibilityGate() {
        assertTrue(Versions.compare("8.5.1", "8.5.1") <= 0);
        assertTrue(Versions.compare("8.3.21", "8.5.1") <= 0);
        assertTrue(Versions.compare("DontUse", "8.5.1") <= 0);
        assertTrue(Versions.compare("8.5.2", "8.5.1") > 0);
        assertTrue(Versions.compare("8.6", "8.5.1") > 0);
    }
}
