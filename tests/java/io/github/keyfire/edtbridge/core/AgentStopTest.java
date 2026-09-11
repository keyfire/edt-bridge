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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a stop spends on being polite.
 *
 * <p>A regression with a price tag: a minute and a half of waiting per stop, spent reconnecting to an
 * agent that could not answer and had nothing worth protecting.
 */
class AgentStopTest {

    @Test
    @DisplayName("an agent with no session and no infobase connection is not asked at all")
    void deadSessionSkipsThePoliteRound() {
        assertEquals(0, AgentStop.connectAttempts(false, false),
                "nothing to protect: reconnecting only spends the caller's time");
    }

    @Test
    @DisplayName("a session still in hand costs nothing, so the request goes out")
    void liveSessionIsAsked() {
        assertTrue(AgentStop.connectAttempts(true, false) > 0);
        assertTrue(AgentStop.connectAttempts(true, true) > 0);
    }

    @Test
    @DisplayName("an agent holding the infobase is worth a short reconnect")
    void connectedAgentIsWorthReopening() {
        int attempts = AgentStop.connectAttempts(false, true);
        assertEquals(AgentStop.REOPEN_ATTEMPTS, attempts);
        assertTrue(attempts >= 2, "one attempt ends on the refusal the loop is meant to learn from");
    }

    @Test
    @DisplayName("the reopen budget stays a fraction of a first connection's")
    void reopenBudgetStaysSmall() {
        assertTrue(AgentStop.REOPEN_ATTEMPTS <= 3,
                "the point of the budget is that a dead session is not waited on");
    }
}
