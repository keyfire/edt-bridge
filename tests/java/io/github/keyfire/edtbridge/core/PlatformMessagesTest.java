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

/** Condensing the platform's licensing dump - shaped like the real refusal, shrunk. */
class PlatformMessagesTest {

    private static String inventoryBlock() {
        return String.join("\n",
                "Phys mem_0: 21046472704",
                "CPU_0: Intel(R) Core(TM) i7-8700K CPU @ 3.70GHz Family 6 Model 158",
                "",
                "CPU_1: Intel(R) Core(TM) i7-8700K CPU @ 3.70GHz Family 6 Model 158",
                "",
                "HASP_0: ORG8B, client, 500 users, id 978493417, 1",
                "Sys name_0: srv.example.test",
                "DISK_0: Virtual Disk Ver: 1.0 Size: 107374182400");
    }

    private static String licensingDump() {
        StringBuilder sb = new StringBuilder("На сервере не найдена лицензия!\n");
        for (int stage = 0; stage < 3; stage++) {
            sb.append("Ошибка привязки программной лицензии: file:///var/1C/licenses/a.lic\n");
            sb.append("После получения лицензии удалены:\n");
            sb.append(inventoryBlock()).append("\n");
            sb.append("Ошибка привязки программной лицензии: file:///var/1C/licenses/b.lic\n");
            sb.append("После получения лицензии удалены:\n");
            sb.append(inventoryBlock()).append("\n");
        }
        return sb.toString();
    }

    @Test
    @DisplayName("the dump loses its inventory and its repeats, keeps the diagnosis")
    void condensesTheDump() {
        String dump = licensingDump();
        String out = PlatformMessages.condense(dump);
        assertTrue(out.length() < dump.length() / 4, "expected a drastic shrink");
        assertTrue(out.startsWith("На сервере не найдена лицензия!"));
        // both license files stay visible - they differ, so both are diagnosis
        assertTrue(out.contains("a.lic") && out.contains("b.lic"));
        assertFalse(out.contains("CPU_0"), "inventory must be gone");
        assertEquals(out.indexOf("a.lic"), out.lastIndexOf("a.lic"), "repeats must be gone");
        assertTrue(out.contains("lines omitted"));
    }

    @Test
    @DisplayName("an ordinary message is returned untouched")
    void keepsOrdinaryMessages() {
        String plain = "Пользователь не идентифицирован";
        assertEquals(plain, PlatformMessages.condense(plain));
        // long, but no inventory and no repeats - nothing to drop, so nothing changes
        String longButClean = "первая строка достаточно длинного сообщения без инвентаря\n"
                + "вторая строка того же сообщения, тоже вполне осмысленная\n"
                + "третья строка, чтобы перевалить за двести символов и не совпасть ни с чем";
        assertEquals(longButClean, PlatformMessages.condense(longButClean));
        assertEquals(null, PlatformMessages.condense(null));
    }
}
