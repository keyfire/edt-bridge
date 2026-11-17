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
import java.util.List;
import java.util.regex.Pattern;

/**
 * The lines of a schema text a pattern matches, each with the lines around it. A setting of a data composition
 * schema - a format, quick access, a filter - sits a few lines below the element it belongs to, so a hit carries
 * those lines and the caller sees the field or the setting it is in without reading the schema.
 */
public final class SchemaLines {
    /** A matched line: its number from 1, its text without indentation and the lines around it. */
    public record Match(int line, String text, String fragment) { }

    /** The longest fragment a match carries; a longer one is cut and ends with "...". */
    static final int FRAGMENT_LIMIT = 1500;

    private SchemaLines() { }

    public static List<Match> find(String text, Pattern pattern, int context) {
        List<Match> found = new ArrayList<>();
        if (text == null || text.isEmpty()) return found;
        String[] lines = text.split("\\R", -1);
        int around = Math.max(0, context);
        for (int index = 0; index < lines.length; index++) {
            if (!pattern.matcher(lines[index]).find()) continue;
            StringBuilder fragment = new StringBuilder();
            for (int near = Math.max(0, index - around); near <= Math.min(lines.length - 1, index + around); near++) {
                if (fragment.length() > 0) fragment.append('\n');
                fragment.append(lines[near].strip());
            }
            String shown = fragment.length() > FRAGMENT_LIMIT ? fragment.substring(0, FRAGMENT_LIMIT) + "..." : fragment.toString();
            found.add(new Match(index + 1, lines[index].strip(), shown));
        }
        return found;
    }
}
