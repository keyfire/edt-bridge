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

/** Which infobase user an already running agent answers for. */
class AgentUserTest {

    @Test
    @DisplayName("asking for nobody in particular takes whatever runs")
    void noRequest() {
        assertTrue(AgentUser.serves("Администратор", null));
        assertTrue(AgentUser.serves("Администратор", ""));
        assertTrue(AgentUser.serves("Администратор", "   "));
        assertTrue(AgentUser.serves("", null));
    }

    @Test
    @DisplayName("the reported case: an agent started without a user does not answer for one")
    void startedWithoutUser() {
        assertFalse(AgentUser.serves("", "ВторойПользователь"));
        assertFalse(AgentUser.serves(null, "Администратор"));
    }

    @Test
    @DisplayName("another user is refused, the same one - spelling aside - is served")
    void sameUserServes() {
        assertFalse(AgentUser.serves("ПервыйПользователь", "ВторойПользователь"));
        assertTrue(AgentUser.serves("Администратор", "Администратор"));
        assertTrue(AgentUser.serves("Администратор", "  администратор  "));
    }

    @Test
    @DisplayName("the refusal names both identities and how to get out of it")
    void refusalIsActionable() {
        String m = AgentUser.mismatch("ПервыйПользователь", "ВторойПользователь");
        assertTrue(m.contains("ПервыйПользователь"), m);
        assertTrue(m.contains("ВторойПользователь"), m);
        assertTrue(m.contains("action=stop"), m);

        // The case that produced a bare "Auth fail" on the stand: no user bound at all.
        String none = AgentUser.mismatch("", "Администратор");
        assertTrue(none.contains("without a user"), none);
        assertTrue(none.contains("Администратор"), none);
    }
}
