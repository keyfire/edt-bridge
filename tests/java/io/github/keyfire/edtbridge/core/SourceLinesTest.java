/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SourceLinesTest {
    @Test void preservesOriginalEndingsAndCoordinates() {
        String source = "\ufefffirst\r\nsecond\rthird\nfourth";
        assertEquals(new SourceLines.Slice("second\rthird\n", 4, false), SourceLines.read(source, 2, 3, 500));
        assertEquals(new SourceLines.Slice("", 4, false), SourceLines.read(source, 5, 6, 500));
        assertEquals(new SourceLines.Slice(source, 4, false), SourceLines.read(source, 1, Integer.MAX_VALUE, 500));
    }

    @Test void reportsCharacterTruncationWithoutSplittingUnicode() {
        assertEquals(new SourceLines.Slice("A", 2, true), SourceLines.read("A🐈\r\nB", 1, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> SourceLines.read("a\nb", 2, 1, 1));
    }
}
