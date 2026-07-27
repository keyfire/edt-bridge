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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How the designer command line addresses an infobase - glued to its switch, never spaced. */
class DesignerAddressTest {

    @Test
    @DisplayName("a file infobase is glued to /F")
    void fileInfobase() {
        assertEquals("/FD:\\Bases\\demo", DesignerAddress.file("D:\\Bases\\demo"));
        assertEquals("/F/opt/bases/demo", DesignerAddress.file("  /opt/bases/demo  "));
    }

    @Test
    @DisplayName("a server infobase joins host and reference with a backslash")
    void serverInfobase() {
        assertEquals("/Ssrv.example.test\\payments",
                DesignerAddress.server("srv.example.test", "payments"));
        assertEquals("/Ssrv.example.test:1741\\payments",
                DesignerAddress.server("srv.example.test:1741", "payments"));
    }

    @Test
    @DisplayName("an incomplete coordinate has no address at all")
    void incomplete() {
        assertNull(DesignerAddress.file(null));
        assertNull(DesignerAddress.file("   "));
        assertNull(DesignerAddress.server("srv.example.test", null));
        assertNull(DesignerAddress.server(null, "payments"));
        assertNull(DesignerAddress.server("srv.example.test", ""));
    }

    @Test
    @DisplayName("an address written out by the caller is recognised in the forms people use")
    void explicitAddress() {
        // already a designer switch - passed through untouched
        assertEquals("/Ssrv.example.test\\payments", DesignerAddress.explicit("/Ssrv.example.test\\payments"));
        assertEquals("/FD:\\Bases\\demo", DesignerAddress.explicit("/FD:\\Bases\\demo"));
        // the bare pair a person writes: host and infobase name
        assertEquals("/Ssrv.example.test\\payments", DesignerAddress.explicit("srv.example.test\\payments"));
        // a file infobase directory, on either platform. A Windows path carries a backslash too, so
        // reading it as host\base would turn a directory into a server address.
        assertEquals("/FD:\\Bases\\demo", DesignerAddress.explicit("D:\\Bases\\demo"));
        assertEquals("/F/opt/bases/demo", DesignerAddress.explicit("/opt/bases/demo"));
    }

    @Test
    @DisplayName("a plain name is NOT an address - it has to be looked up in EDT")
    void plainNameIsNotAnAddress() {
        assertNull(DesignerAddress.explicit("payments"));
        assertNull(DesignerAddress.explicit("  "));
        assertNull(DesignerAddress.explicit(null));
        // a uuid names a registered infobase, it does not address one
        assertNull(DesignerAddress.explicit("377598b5-6fed-44e8-ad29-2e1b7a6cdbcd"));
    }

    @Test
    @DisplayName("only file and server infobases have a designer address")
    void byKind() {
        assertEquals("/FD:\\Bases\\demo",
                DesignerAddress.of("file", "D:\\Bases\\demo", null, null));
        assertEquals("/Ssrv.example.test\\payments",
                DesignerAddress.of("server", null, "srv.example.test", "payments"));
        // published over a web server: the designer cannot reach it, and saying so beats guessing
        assertNull(DesignerAddress.of("web", null, "https://example.test/base", null));
        assertNull(DesignerAddress.of("unknown", "D:\\Bases\\demo", null, null));
    }

    @Test
    @DisplayName("an address is taken apart again to ask the cluster about the infobase")
    void backToClusterAndInfobase() {
        String address = DesignerAddress.server("srv.example.test", "payments");
        assertTrue(DesignerAddress.isServer(address));
        assertEquals("srv.example.test", DesignerAddress.serverHost(address));
        assertEquals("payments", DesignerAddress.serverReference(address));
        // the port travels with the host, exactly as the designer takes it
        assertEquals("srv.example.test:1741",
                DesignerAddress.serverHost("/Ssrv.example.test:1741\\payments"));
    }

    @Test
    @DisplayName("a file infobase has no cluster - and a Windows path must not be read as one")
    void fileHasNoCluster() {
        String file = DesignerAddress.file("D:\\Bases\\demo");
        assertFalse(DesignerAddress.isServer(file));
        assertNull(DesignerAddress.serverHost(file));
        assertNull(DesignerAddress.serverReference(file));
        assertNull(DesignerAddress.serverHost(null));
        // a switch with nothing after the backslash names no infobase
        assertNull(DesignerAddress.serverReference("/Ssrv.example.test\\"));
    }
}
