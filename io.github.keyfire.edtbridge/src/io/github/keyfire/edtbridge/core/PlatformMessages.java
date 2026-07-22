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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Condensing the platform's error prose to what a reader acts on.
 *
 * <p>The case that pays for it: one licensing refusal arrives as tens of kilobytes - the
 * platform appends the FULL hardware inventory (CPUs, memory, HASP keys, disks) to every
 * licensing line, repeats the block per license file per lookup stage, and the SSH client
 * then wraps the same text into both the exception and its cause. The reader scrolls pages
 * to learn one line: "no license".
 */
public final class PlatformMessages {

    /** Hardware-inventory lines of the licensing dump; pure bulk, never the diagnosis. */
    private static final Pattern INVENTORY = Pattern.compile(
            "^(?:Phys mem_\\d+|CPU_\\d+|HASP_\\d+|Sys name_\\d+|DISK_\\d+):.*");

    /** Past this size the platform is quoting itself; a condensed message stays far below. */
    private static final int HARD_CAP = 4000;

    private PlatformMessages() {
    }

    /**
     * The message with the inventory dropped and repeated lines kept once. A message without
     * such bulk comes back unchanged - ordinary errors must not be touched.
     */
    public static String condense(String message) {
        if (message == null || message.length() < 200) {
            return message;  // short messages carry no dump worth the bookkeeping
        }
        String[] lines = message.split("\r?\n");
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int dropped = 0;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;  // the dump pads with blank lines; condensed output does not need them
            }
            if (INVENTORY.matcher(line).matches()) {
                dropped++;
                continue;
            }
            if (!seen.add(line)) {
                dropped++;  // the same licensing line per file and per lookup stage
                continue;
            }
            out.add(line);
        }
        if (dropped == 0) {
            return message;
        }
        out.add("(... " + dropped + " repeated/inventory lines omitted)");
        String condensed = String.join("\n", out);
        if (condensed.length() > HARD_CAP) {
            condensed = condensed.substring(0, HARD_CAP) + " (... truncated)";
        }
        return condensed;
    }
}
