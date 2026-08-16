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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** When a problem count may be reported as final. */
class ValidationSettleTest {

    @Test
    @DisplayName("a repeating count over an idle workspace settles after three quiet polls")
    void quietPolls() {
        ValidationSettle settle = new ValidationSettle();
        assertFalse(settle.poll(13, true));   // first reading, nothing to repeat yet
        assertFalse(settle.poll(13, true));
        assertFalse(settle.poll(13, true));
        assertTrue(settle.poll(13, true));
        assertEquals(13, settle.problems());
    }

    @Test
    @DisplayName("a count that repeats while jobs run does NOT settle - that is the reported defect")
    void busyNeverSettles() {
        ValidationSettle settle = new ValidationSettle();
        // Right after a clean the count sits at zero because the checks have not reported yet.
        for (int i = 0; i < 10; i++) {
            assertFalse(settle.poll(0, false));
        }
        assertFalse(settle.settled());
        assertTrue(settle.sawWork());
    }

    @Test
    @DisplayName("a busy moment restarts the count of quiet polls")
    void busyResets() {
        ValidationSettle settle = new ValidationSettle();
        settle.poll(0, true);
        settle.poll(0, true);
        settle.poll(0, false);          // validation woke up
        assertFalse(settle.poll(13, true));
        assertFalse(settle.poll(13, true));
        assertFalse(settle.poll(13, true));
        assertTrue(settle.poll(13, true));
        assertEquals(13, settle.problems());
    }

    @Test
    @DisplayName("a changing count does not settle however idle the workspace looks")
    void changingCount() {
        ValidationSettle settle = new ValidationSettle();
        assertFalse(settle.poll(1, true));
        assertFalse(settle.poll(2, true));
        assertFalse(settle.poll(3, true));
        assertFalse(settle.poll(4, true));
        assertFalse(settle.settled());
    }

    @Test
    @DisplayName("settling without ever seeing work is reported, not hidden")
    void neverSawWork() {
        ValidationSettle settle = new ValidationSettle();
        for (int i = 0; i < 4; i++) {
            settle.poll(0, true);
        }
        assertTrue(settle.settled());
        assertFalse(settle.sawWork());
    }
}
