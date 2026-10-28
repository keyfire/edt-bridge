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

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Decode the configurator's /Out files independently of the bridge JVM's locale. */
public final class PlatformLog {
    private PlatformLog() {
    }

    public static String decode(byte[] raw) {
        if (raw.length >= 2 && raw[0] == (byte) 0xff && raw[1] == (byte) 0xfe) {
            return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE);
        }
        if (raw.length >= 2 && raw[0] == (byte) 0xfe && raw[1] == (byte) 0xff) {
            return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (CharacterCodingException notUtf8) {
            // Windows /Out uses the ANSI code page, unlike ibcmd's OEM console help.
            return new String(raw, Charset.forName("windows-1251"));
        }
    }
}
