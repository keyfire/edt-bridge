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

/**
 * How a listing names the remains it found.
 *
 * <p>The wording is the finding, not decoration: a directory this bridge process left is evidence
 * that a stop here did not finish, and calling it an earlier process's leftover reads as somebody
 * else's crash - which sends the reader looking in the wrong place.
 */
class AgentLeftoversTest {

    @Test
    @DisplayName("nothing left over says nothing")
    void silentWhenClean() {
        assertEquals("", AgentLeftovers.summary(0, 0));
        assertEquals("", AgentLeftovers.summary(-1, -2));
    }

    @Test
    @DisplayName("this process's own remains are not attributed to an earlier run")
    void ownRemainsAreOwned() {
        String own = AgentLeftovers.summary(1, 0);
        assertTrue(own.contains(AgentLeftovers.FROM_THIS_PROCESS), own);
        assertFalse(own.contains(AgentLeftovers.FROM_EARLIER_PROCESS), own);
        assertTrue(own.contains("clears it"), "one directory is an it");
    }

    @Test
    @DisplayName("an earlier run's remains keep their own wording")
    void earlierRemains() {
        String earlier = AgentLeftovers.summary(0, 2);
        assertTrue(earlier.contains(AgentLeftovers.FROM_EARLIER_PROCESS), earlier);
        assertFalse(earlier.contains(AgentLeftovers.FROM_THIS_PROCESS), earlier);
        assertTrue(earlier.contains("2 left over"), earlier);
        assertTrue(earlier.contains("clears them"), earlier);
    }

    @Test
    @DisplayName("both kinds at once are counted apart")
    void bothKinds() {
        String both = AgentLeftovers.summary(1, 3);
        assertTrue(both.contains("1 left over from " + AgentLeftovers.FROM_THIS_PROCESS), both);
        assertTrue(both.contains("3 from an earlier one"), both);
        assertTrue(both.contains("clears them"), both);
    }

    @Test
    @DisplayName("every summary says how to be rid of them")
    void namesTheCure() {
        assertTrue(AgentLeftovers.summary(1, 0).contains("action=sweep"));
        assertTrue(AgentLeftovers.summary(0, 1).contains("action=sweep"));
        assertTrue(AgentLeftovers.summary(2, 2).contains("action=sweep"));
    }
}
