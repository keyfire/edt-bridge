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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The trace an agent leaves so a later bridge process can recognise its remains. */
class AgentRecordTest {

    private static AgentRecord sample() {
        AgentRecord r = new AgentRecord();
        r.connectionString = "/Ssrv.example.test\\sm";
        r.label = "sm";
        r.port = 1543;
        r.pid = 4242;
        r.user = "Администратор";
        r.platformVersion = "8.5.1.1423";
        r.startedAtMillis = 1_753_000_000_000L;
        r.host = "WORKSTATION";
        r.clusterSessionId = "18755254-fb54-43bf-8354-7b6b8b2177c3";
        return r;
    }

    @Test
    @DisplayName("a record survives a round trip through the file, Cyrillic user included")
    void roundTrip(@TempDir Path dir) throws IOException {
        sample().write(dir);
        AgentRecord back = AgentRecord.read(dir);
        assertEquals("/Ssrv.example.test\\sm", back.connectionString);
        assertEquals("sm", back.label);
        assertEquals(1543, back.port);
        assertEquals(4242, back.pid);
        assertEquals("Администратор", back.user);
        assertEquals("8.5.1.1423", back.platformVersion);
        assertEquals(1_753_000_000_000L, back.startedAtMillis);
        assertEquals("WORKSTATION", back.host);
        assertEquals("18755254-fb54-43bf-8354-7b6b8b2177c3", back.clusterSessionId);
    }

    @Test
    @DisplayName("the session id is learned later, so a record without one is still a record")
    void sessionIdIsOptional(@TempDir Path dir) throws IOException {
        AgentRecord r = sample();
        r.clusterSessionId = null;
        r.write(dir);
        AgentRecord back = AgentRecord.read(dir);
        assertNull(back.clusterSessionId);
        assertEquals(4242, back.pid);
    }

    @Test
    @DisplayName("NO credentials are written - the file outlives the process")
    void noSecrets(@TempDir Path dir) throws IOException {
        sample().write(dir);
        String text = Files.readString(dir.resolve(AgentRecord.FILE_NAME), StandardCharsets.UTF_8);
        assertFalse(text.toLowerCase().contains("password"), text);
        assertFalse(text.toLowerCase().contains("pwd"), text);
    }

    @Test
    @DisplayName("an absent, empty or truncated file reads as no record at all")
    void unreadable(@TempDir Path dir) throws IOException {
        assertNull(AgentRecord.read(dir));
        Files.writeString(dir.resolve(AgentRecord.FILE_NAME), "port=1543\n", StandardCharsets.UTF_8);
        assertNull(AgentRecord.read(dir), "without a connection string there is nothing to sweep");
    }

    @Test
    @DisplayName("the cluster of a server infobase comes off the address; a file one has none")
    void clusterFields() {
        AgentRecord server = sample();
        assertTrue(server.isServerInfobase());
        assertEquals("srv.example.test", server.clusterServer());
        assertEquals("sm", server.clusterInfobase());

        AgentRecord file = sample();
        file.connectionString = "/FD:\\Bases\\demo";
        assertFalse(file.isServerInfobase());
        assertNull(file.clusterServer());
        assertNull(file.clusterInfobase());
    }
}
