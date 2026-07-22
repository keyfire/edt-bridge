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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The maintenance-window logic: blockers, the rac flag arguments and the flag report. */
class MaintenanceWindowTest {

    private static Map<String, String> session(String id, String appId) {
        return Map.of("session", id, "app-id", appId);
    }

    @Test
    @DisplayName("everything outside the allowed applications blocks, case-insensitively")
    void blockers() {
        List<Map<String, String>> sessions = List.of(
                session("a", "Designer"),
                session("b", "BackgroundJob"),
                session("c", "designer"),
                session("d", "1CV8C"));
        List<Map<String, String>> out = MaintenanceWindow.blockers(sessions, List.of("Designer"));
        assertEquals(List.of("b", "d"), out.stream().map(s -> s.get("session")).toList());
    }

    @Test
    @DisplayName("a session with no app-id at all is a blocker, not a crash")
    void blockersWithoutAppId() {
        List<Map<String, String>> out = MaintenanceWindow.blockers(
                List.of(Map.of("session", "x")), List.of("Designer"));
        assertEquals(1, out.size());
    }

    @Test
    @DisplayName("raising: scheduled jobs always, sessions with code and message only when asked")
    void raiseArgs() {
        assertEquals(List.of("--scheduled-jobs-deny=on"),
                MaintenanceWindow.denyArgs(true, false, "1234", "ignored without sessionsDeny"));
        assertEquals(
                List.of("--scheduled-jobs-deny=on", "--sessions-deny=on",
                        "--permission-code=1234", "--denied-message=maintenance"),
                MaintenanceWindow.denyArgs(true, true, " 1234 ", "maintenance"));
    }

    @Test
    @DisplayName("lowering never re-sends the code or the message")
    void lowerArgs() {
        assertEquals(List.of("--scheduled-jobs-deny=off", "--sessions-deny=off"),
                MaintenanceWindow.denyArgs(false, true, "1234", "maintenance"));
        assertEquals(List.of("--scheduled-jobs-deny=off"),
                MaintenanceWindow.denyArgs(false, false, null, null));
    }

    @Test
    @DisplayName("the flag report keeps the known fields, their order, and drops the blank ones")
    void flags() {
        // field names exactly as `rac infobase info` prints them, probed live
        Map<String, String> record = Map.of(
                "name", "sm",
                "scheduled-jobs-deny", "on",
                "sessions-deny", "off",
                "denied-from", "",
                "permission-code", "1234567",
                "security-profile-name", "irrelevant");
        Map<String, String> out = MaintenanceWindow.flags(record);
        assertEquals(List.of("scheduled-jobs-deny", "sessions-deny", "permission-code"),
                List.copyOf(out.keySet()));
        assertEquals("on", out.get("scheduled-jobs-deny"));
        assertTrue(out.values().stream().noneMatch(String::isBlank));
    }
}
