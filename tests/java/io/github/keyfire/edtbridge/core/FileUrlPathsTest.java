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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The path behind a {@code file:} URL, as the bridge reports the EDT install directory. */
class FileUrlPathsTest {

    @Test
    @DisplayName("a plus in a component directory stays a plus")
    void keepsPlus() {
        assertEquals("/C:/Program Files/1C/1CE/components/1c-edt-2026.1.2+2-x86_64/",
                FileUrlPaths.decode("/C:/Program Files/1C/1CE/components/1c-edt-2026.1.2+2-x86_64/"));
    }

    @Test
    @DisplayName("percent-encoded characters are decoded")
    void decodesEscapes() {
        assertEquals("/C:/Program Files/EDT 2026/", FileUrlPaths.decode("/C:/Program%20Files/EDT%202026/"));
        assertEquals("/D:/\u0415\u0414\u0422/", FileUrlPaths.decode("/D:/%D0%95%D0%94%D0%A2/"));
    }

    @Test
    @DisplayName("an encoded plus is a plus too")
    void decodesEncodedPlus() {
        assertEquals("/opt/1c-edt+2/", FileUrlPaths.decode("/opt/1c-edt%2B2/"));
    }
}
