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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading what {@code rac} prints: records of {@code key : value} lines, one blank line between them.
 *
 * <p>Pure text work, kept out of the gateway so it can be tested without a cluster. Two details decide
 * whether a parser here is right or subtly wrong: a value may itself contain a colon (a connection
 * string, a timestamp), so only the FIRST one separates; and values arrive quoted often enough
 * ({@code name : "Local cluster"}) that leaving the quotes in turns a comparison into a silent
 * mismatch.
 */
public final class RacOutput {

    private RacOutput() {
    }

    /** Split the output into records, each a field map in the order the utility printed them. */
    public static List<Map<String, String>> parse(String output) {
        List<Map<String, String>> records = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return records;
        }
        Map<String, String> current = new LinkedHashMap<>();
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                if (!current.isEmpty()) {
                    records.add(current);
                    current = new LinkedHashMap<>();
                }
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue; // a banner or an error line, not a field
            }
            String key = line.substring(0, colon).strip();
            String value = unquote(line.substring(colon + 1).strip());
            if (!key.isEmpty()) {
                current.put(key, value);
            }
        }
        if (!current.isEmpty()) {
            records.add(current);
        }
        return records;
    }

    /** The value of a field in the first record that has it, or null. */
    public static String first(List<Map<String, String>> records, String field) {
        for (Map<String, String> record : records) {
            String value = record.get(field);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** The records whose field equals the value, compared without case. */
    public static List<Map<String, String>> where(List<Map<String, String>> records, String field,
            String value) {
        List<Map<String, String>> found = new ArrayList<>();
        for (Map<String, String> record : records) {
            String actual = record.get(field);
            if (actual != null && actual.equalsIgnoreCase(value)) {
                found.add(record);
            }
        }
        return found;
    }

    /** Strip the quotes rac wraps names in, leaving anything else alone. */
    static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
