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

/** When a standing agent has been idle long enough to give up its license. */
class AgentIdleTest {

    private static final long MINUTE = 60_000L;

    @Test
    @DisplayName("no setting means the default, a number means itself")
    void reading() {
        assertEquals(AgentIdle.DEFAULT_MINUTES, AgentIdle.minutes(null));
        assertEquals(AgentIdle.DEFAULT_MINUTES, AgentIdle.minutes("   "));
        assertEquals(5, AgentIdle.minutes("5"));
        assertEquals(5, AgentIdle.minutes(" 5 "));
    }

    @Test
    @DisplayName("switching the reaper off has four spellings, and a typo is one of them")
    void switchedOff() {
        assertEquals(0, AgentIdle.minutes("0"));
        assertEquals(0, AgentIdle.minutes("-1"));
        assertEquals(0, AgentIdle.minutes("off"));
        assertEquals(0, AgentIdle.minutes("FALSE"));
        // Unparsable is off, not the default: we do not know what was meant, and stopping agents on a
        // guess is the direction that costs somebody their work.
        assertEquals(0, AgentIdle.minutes("30m"));
    }

    @Test
    @DisplayName("the timeout is reached, not merely approached")
    void expiry() {
        long now = 1_753_000_000_000L;
        assertFalse(AgentIdle.expired(now - 29 * MINUTE, now, 30));
        assertTrue(AgentIdle.expired(now - 30 * MINUTE, now, 30));
        assertTrue(AgentIdle.expired(now - 300 * MINUTE, now, 30));
    }

    @Test
    @DisplayName("a disabled reaper and an unused agent never expire")
    void neverExpires() {
        long now = 1_753_000_000_000L;
        assertFalse(AgentIdle.expired(now - 300 * MINUTE, now, 0), "reaper off");
        assertFalse(AgentIdle.expired(0, now, 30), "never used");
    }

    @Test
    @DisplayName("idle seconds are whole and never negative")
    void idleSeconds() {
        long now = 1_753_000_000_000L;
        assertEquals(90, AgentIdle.idleSeconds(now - 90_500L, now));
        assertEquals(0, AgentIdle.idleSeconds(0, now));
        assertEquals(0, AgentIdle.idleSeconds(now + 5_000L, now), "a clock step back is not idleness");
    }
}
