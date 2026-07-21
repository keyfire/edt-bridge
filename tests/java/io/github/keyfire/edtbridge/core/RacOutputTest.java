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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reading rac output - real shapes, including the two that break a naive parser. */
class RacOutputTest {

    private static final String SESSIONS = String.join("\n",
            "session          : 18755254-fb54-43bf-8354-7b6b8b2177c3",
            "infobase         : 798fbfb5-ff3e-42b0-85ce-6897da1e784d",
            "user-name        : Администратор",
            "host             : WORKSTATION",
            "app-id           : Designer",
            "started-at       : 2026-07-21T13:47:18",
            "",
            "session          : 1f2ce033-17c3-464b-b8eb-1ba1018ca7b6",
            "infobase         : 798fbfb5-ff3e-42b0-85ce-6897da1e784d",
            "user-name        : DefUser",
            "host             : app.example.test",
            "app-id           : BackgroundJob",
            "started-at       : 2026-07-21T15:55:13",
            "");

    @Test
    @DisplayName("records are split on blank lines, fields keep their order")
    void records() {
        List<Map<String, String>> records = RacOutput.parse(SESSIONS);
        assertEquals(2, records.size());
        assertEquals("Designer", records.get(0).get("app-id"));
        assertEquals("BackgroundJob", records.get(1).get("app-id"));
        assertEquals("session", records.get(0).keySet().iterator().next());
    }

    @Test
    @DisplayName("only the FIRST colon separates - a timestamp keeps its own")
    void valueMayContainAColon() {
        List<Map<String, String>> records = RacOutput.parse(SESSIONS);
        assertEquals("2026-07-21T13:47:18", records.get(0).get("started-at"));
    }

    @Test
    @DisplayName("quoted names come back unquoted, so a comparison actually matches")
    void quotedValues() {
        List<Map<String, String>> records = RacOutput.parse(String.join("\n",
                "cluster : 47e6bb2d-5b36-4dbe-a686-202458836a3b",
                "host    : srv.example.test",
                "name    : \"Local cluster\"",
                ""));
        assertEquals("Local cluster", records.get(0).get("name"));
    }

    @Test
    @DisplayName("filtering and first-value lookups")
    void filtering() {
        List<Map<String, String>> records = RacOutput.parse(SESSIONS);
        assertEquals(1, RacOutput.where(records, "app-id", "designer").size());
        assertEquals("18755254-fb54-43bf-8354-7b6b8b2177c3", RacOutput.first(records, "session"));
        assertNull(RacOutput.first(records, "no-such-field"));
        assertTrue(RacOutput.where(records, "app-id", "1CV8C").isEmpty());
    }

    @Test
    @DisplayName("banner and error lines are not fields, empty output is not a failure")
    void noise() {
        assertTrue(RacOutput.parse("").isEmpty());
        assertTrue(RacOutput.parse(null).isEmpty());
        List<Map<String, String>> records = RacOutput.parse(String.join("\n",
                "Ошибка соединения с сервером администрирования",
                "session : 18755254-fb54-43bf-8354-7b6b8b2177c3",
                ""));
        assertEquals(1, records.size());
        assertEquals(1, records.get(0).size());
    }
}
