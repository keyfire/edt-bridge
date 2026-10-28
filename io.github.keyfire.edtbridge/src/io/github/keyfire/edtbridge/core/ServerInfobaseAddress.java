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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Parse registration addresses without accepting or echoing embedded credentials. */
public record ServerInfobaseAddress(String server, String reference) {
    public static ServerInfobaseAddress parse(String text) {
        if (text == null || text.isBlank() || text.chars().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        String value = text.trim();
        String host;
        String base;
        if (!value.contains("=")) {
            if (value.startsWith("/S")) value = value.substring(2);
            int separator = value.indexOf('\\');
            if (separator <= 0 || separator != value.lastIndexOf('\\')) throw invalid();
            host = value.substring(0, separator).trim();
            base = value.substring(separator + 1).trim();
        } else {
            Map<String, String> fields = new HashMap<>();
            int offset = 0;
            while (offset < value.length()) {
                while (offset < value.length() && Character.isWhitespace(value.charAt(offset))) offset++;
                if (offset == value.length()) break;
                int equals = value.indexOf('=', offset);
                if (equals < 0) throw invalid();
                String key = value.substring(offset, equals).trim().toLowerCase(Locale.ROOT);
                if (!key.equals("srvr") && !key.equals("ref")) {
                    throw new IllegalArgumentException("connect accepts only Srvr and Ref; pass user separately, never a password");
                }
                offset = equals + 1;
                while (offset < value.length() && Character.isWhitespace(value.charAt(offset))) offset++;
                String field;
                if (offset < value.length() && value.charAt(offset) == '"') {
                    offset++;
                    StringBuilder decoded = new StringBuilder();
                    boolean closed = false;
                    while (offset < value.length()) {
                        char ch = value.charAt(offset++);
                        if (ch == '"') {
                            if (offset < value.length() && value.charAt(offset) == '"') {
                                decoded.append('"'); offset++;
                            } else { closed = true; break; }
                        } else decoded.append(ch);
                    }
                    if (!closed) throw invalid();
                    field = decoded.toString();
                    while (offset < value.length() && Character.isWhitespace(value.charAt(offset))) offset++;
                    if (offset < value.length() && value.charAt(offset) != ';') throw invalid();
                } else {
                    int end = value.indexOf(';', offset);
                    if (end < 0) end = value.length();
                    field = value.substring(offset, end).trim();
                    offset = end;
                }
                if (fields.putIfAbsent(key, field) != null) throw invalid();
                if (offset < value.length()) offset++;
            }
            host = fields.get("srvr");
            base = fields.get("ref");
        }
        if (host == null || host.isBlank() || base == null || base.isBlank()
                || host.matches(".*[\\\\/;=\\s\"].*") || base.indexOf('\\') >= 0 || base.indexOf('/') >= 0) {
            throw invalid();
        }
        return new ServerInfobaseAddress(host, base);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("connect must be Srvr=\"host\";Ref=\"base\"; or host\\base");
    }
}
