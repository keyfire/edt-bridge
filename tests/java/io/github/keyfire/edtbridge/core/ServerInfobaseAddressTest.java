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
import org.junit.jupiter.api.Test;
class ServerInfobaseAddressTest {
    @Test void parsesNativeConnectionStringAndBareServerAddress() {
        var a = ServerInfobaseAddress.parse("Srvr=\"demo-host:1541\";Ref=\"demo-base\";");
        assertEquals("demo-host:1541", a.server());
        assertEquals("demo-base", a.reference());
        assertEquals(a, ServerInfobaseAddress.parse("demo-host:1541\\demo-base"));
        assertEquals(a, ServerInfobaseAddress.parse(" /Sdemo-host:1541\\demo-base "));
    }
    @Test void acceptsQuotedSemicolonsAndEscapedQuotesInReference() {
        assertEquals("demo;one", ServerInfobaseAddress.parse("Srvr=host;Ref=\"demo;one\";").reference());
    }
    @Test void refusesCredentialsAndIncompleteOrMalformedStrings() {
        for (String bad : new String[] {"File=demo", "Srvr=host", "Srvr=host;Ref=a;Ref=b", "host\\", "host\\base\\extra", "Srvr=host;Ref=base;Pwd=secret", "Srvr=\"unterminated", "Srvr=host;Ref=base;garbage", "Srvr=host;Ref=base;Usr=user"}) {
            var ex = assertThrows(IllegalArgumentException.class, () -> ServerInfobaseAddress.parse(bad), bad);
            assertFalse(ex.getMessage().contains("secret"));
        }
    }
}
