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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Whether a validation marker still describes the file it sits on. */
class MarkerFreshnessTest {

    /** Some instant; only the order of the stamps matters. */
    private static final long T = 1_800_000_000_000L;

    @Test
    @DisplayName("a marker created after the file's last write is current")
    void current() {
        MarkerFreshness.Verdict v = MarkerFreshness.judge(T + 5_000, T, true);
        assertFalse(v.stale);
        assertFalse(v.unsynchronized);
        assertTrue(v.current());
    }

    @Test
    @DisplayName("a file written after the marker leaves the marker stale even when the workspace read the write")
    void staleButSynchronized() {
        // The auto-build-off case: the workspace has the new text, no validation ran over it.
        MarkerFreshness.Verdict v = MarkerFreshness.judge(T, T + 5_000, true);
        assertTrue(v.stale);
        assertFalse(v.unsynchronized);
        assertFalse(v.current());
    }

    @Test
    @DisplayName("a write the workspace has not read is unsynchronized, and therefore stale")
    void unsynchronized() {
        MarkerFreshness.Verdict v = MarkerFreshness.judge(T, T + 5_000, false);
        assertTrue(v.stale);
        assertTrue(v.unsynchronized);
    }

    @Test
    @DisplayName("a marker that does not say when it was created is judged by synchronization alone")
    void unknownMarkerTime() {
        assertTrue(MarkerFreshness.judge(0, T, true).current());
        assertTrue(MarkerFreshness.judge(-1, T, true).current());
        MarkerFreshness.Verdict unseen = MarkerFreshness.judge(0, T, false);
        assertTrue(unseen.stale);
        assertTrue(unseen.unsynchronized);
    }

    @Test
    @DisplayName("a file gone from disk leaves its marker stale whatever the marker says")
    void fileGone() {
        MarkerFreshness.Verdict v = MarkerFreshness.judge(T, 0, false);
        assertTrue(v.stale);
        assertTrue(v.unsynchronized);
        assertTrue(MarkerFreshness.judge(0, 0, true).stale);
    }

    @Test
    @DisplayName("the same millisecond is not a change")
    void sameInstant() {
        assertTrue(MarkerFreshness.judge(T, T, true).current());
    }

    @Test
    @DisplayName("the hint names what is old and says to refresh")
    void hintBeforeRefresh() {
        assertNull(MarkerFreshness.hint(0, 0, false));
        assertEquals("3 problem(s) predate the last change of their file – pass refresh=true to revalidate",
                MarkerFreshness.hint(3, 0, false));
        assertEquals("1 file(s) changed on disk since the workspace last read them and may hold problems "
                + "nothing reports yet – pass refresh=true to revalidate",
                MarkerFreshness.hint(0, 1, false));
        assertEquals("2 problem(s) predate the last change of their file and 1 file(s) changed on disk "
                + "since the workspace last read them – pass refresh=true to revalidate",
                MarkerFreshness.hint(2, 1, false));
    }

    @Test
    @DisplayName("after a refresh the hint points at the full clean, and stays silent when all is current")
    void hintAfterRefresh() {
        assertNull(MarkerFreshness.hint(0, 0, true));
        assertEquals("2 problem(s) predate the last change of their file even after the refresh – "
                + "edt_clean_project rebuilds the project from scratch",
                MarkerFreshness.hint(2, 0, true));
        assertTrue(MarkerFreshness.hint(0, 4, true).startsWith("4 file(s) changed on disk"));
    }
}
