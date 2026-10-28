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

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PlatformLogTest {
    @Test
    void russianWindowsLogRetainsPlatformFailure() {
        String message = "Ошибка при выполнении операции: недостаточно памяти";
        assertEquals(message, PlatformLog.decode(message.getBytes(Charset.forName("windows-1251"))));
    }

    @Test
    void utf8AndEnglishLogsRemainReadable() {
        for (String message : java.util.List.of("Ошибка при выполнении операции", "Error executing operation")) {
            assertEquals(message, PlatformLog.decode(message.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void unicodeBomIsDecodedAndRemoved() {
        String message = "Ошибка: Error";
        assertEquals(message, PlatformLog.decode(("\uFEFF" + message).getBytes(StandardCharsets.UTF_8)));
        assertEquals(message, PlatformLog.decode(("\uFEFF" + message).getBytes(StandardCharsets.UTF_16LE)));
        assertEquals(message, PlatformLog.decode(("\uFEFF" + message).getBytes(StandardCharsets.UTF_16BE)));
    }

    @Test
    void undecodableByteCannotHideTheRestOfTheFailure() {
        String message = PlatformLog.decode(new byte[] { 'E', 'r', 'r', 'o', 'r', ':', ' ', (byte) 0x98 });
        assertTrue(message.startsWith("Error: "));
    }
}
