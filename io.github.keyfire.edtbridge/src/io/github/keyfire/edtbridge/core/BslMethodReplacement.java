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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** A version-checked source splice; method identity and syntax are checked by EDT. */
public final class BslMethodReplacement {
    private BslMethodReplacement() {}

    public record Plan(String sourceHash, String resultHash, String oldText, String newText,
            String text, boolean unchanged) {}

    public static Plan plan(String source, int from, int to, String replacement, String expectedHash) {
        if (source == null || from < 0 || to <= from || to > source.length()) {
            throw new IllegalArgumentException("invalid method source range");
        }
        if (replacement == null || replacement.isBlank()) {
            throw new IllegalArgumentException("methodText must not be empty");
        }
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(replacement)) {
            throw new IllegalArgumentException("methodText contains malformed Unicode");
        }
        String sourceHash = hash(source);
        if (expectedHash != null && !sourceHash.equalsIgnoreCase(expectedHash)) {
            throw new IllegalArgumentException("source hash mismatch; read the module and repeat the dry-run");
        }
        String normalized = replacement.strip().replace("\r\n", "\n").replace("\r", "\n");
        if (source.contains("\r\n")) normalized = normalized.replace("\n", "\r\n");
        String result = source.substring(0, from) + normalized + source.substring(to);
        return new Plan(sourceHash, hash(result), source.substring(from, to), normalized,
                result, source.equals(result));
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
