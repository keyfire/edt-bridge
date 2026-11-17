/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.core;

import java.util.regex.Pattern;

/** One-based source ranges, preserving the file's original line endings. */
public final class SourceLines {
    private static final Pattern LINE_END = Pattern.compile("\r\n|\r|\n");

    public record Slice(String text, int totalLines, boolean truncated) { }

    private SourceLines() { }

    public static Slice read(String source, int startLine, int endLine, int maxChars) {
        if (startLine < 1 || endLine < startLine || maxChars < 1) {
            throw new IllegalArgumentException("expected 1 <= startLine <= endLine and maxChars >= 1");
        }
        var endings = LINE_END.matcher(source);
        int line = 1;
        int start = startLine == 1 ? 0 : source.length();
        int end = source.length();
        while (endings.find()) {
            if (line == endLine) {
                end = endings.end();
            }
            if (++line == startLine) {
                start = endings.end();
            }
        }
        if (end - start <= maxChars) {
            return new Slice(source.substring(start, end), line, false);
        }
        int capped = start + maxChars;
        if (Character.isHighSurrogate(source.charAt(capped - 1))
                && Character.isLowSurrogate(source.charAt(capped))) {
            capped--;
        }
        return new Slice(source.substring(start, capped), line, true);
    }
}
