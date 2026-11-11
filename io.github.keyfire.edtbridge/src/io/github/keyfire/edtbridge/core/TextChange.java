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

import java.util.Arrays;
import java.util.List;

/**
 * The lines one edit changed in a text: the common head and tail are cut off, the rest is reported
 * as the old and the new block with its first line number. Enough to show what a fix did to a file
 * without shipping the whole file back.
 */
public record TextChange(boolean changed, int firstLine, List<String> before, List<String> after) {
    /** The longest block reported on each side; a longer change is cut and marked by a final "...". */
    public static final int BLOCK_LIMIT = 60;

    public static TextChange of(String oldText, String newText) {
        String[] oldLines = lines(oldText);
        String[] newLines = lines(newText);
        if (Arrays.equals(oldLines, newLines)) return new TextChange(false, 0, List.of(), List.of());
        int head = 0;
        while (head < oldLines.length && head < newLines.length && oldLines[head].equals(newLines[head])) head++;
        int tail = 0;
        while (tail < oldLines.length - head && tail < newLines.length - head
                && oldLines[oldLines.length - 1 - tail].equals(newLines[newLines.length - 1 - tail])) tail++;
        return new TextChange(true, head + 1, block(oldLines, head, oldLines.length - tail),
                block(newLines, head, newLines.length - tail));
    }

    private static String[] lines(String text) {
        return text == null || text.isEmpty() ? new String[0] : text.split("\r\n|\n|\r", -1);
    }

    private static List<String> block(String[] lines, int from, int to) {
        if (to - from <= BLOCK_LIMIT) return List.of(Arrays.copyOfRange(lines, from, to));
        String[] cut = Arrays.copyOf(Arrays.copyOfRange(lines, from, from + BLOCK_LIMIT), BLOCK_LIMIT + 1);
        cut[BLOCK_LIMIT] = "...";
        return List.of(cut);
    }
}
