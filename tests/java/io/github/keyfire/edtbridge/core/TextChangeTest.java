/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextChangeTest {
    @Test void removedBlankLinesAreReportedWithTheirPosition() {
        var change = TextChange.of("a\nb\n\n\n\nc\n", "a\nb\n\nc\n");
        assertTrue(change.changed());
        assertEquals(4, change.firstLine());
        assertEquals(List.of("", ""), change.before());
        assertEquals(List.of(), change.after());
    }

    @Test void replacedLineKeepsTheSurroundingTextOut() {
        var change = TextChange.of("Процедура А() Экспорт\nКонецПроцедуры\n", "Процедура А()\nКонецПроцедуры\n");
        assertEquals(1, change.firstLine());
        assertEquals(List.of("Процедура А() Экспорт"), change.before());
        assertEquals(List.of("Процедура А()"), change.after());
    }

    @Test void identicalTextsAreNoChangeEvenWithDifferentLineEndings() {
        assertFalse(TextChange.of("a\r\nb\r\n", "a\nb\n").changed());
        assertFalse(TextChange.of(null, "").changed());
    }

    @Test void longBlockIsCutAndMarked() {
        String many = "x\n".repeat(TextChange.BLOCK_LIMIT + 10);
        var change = TextChange.of("", many);
        assertEquals(TextChange.BLOCK_LIMIT + 1, change.after().size());
        assertEquals("...", change.after().get(TextChange.BLOCK_LIMIT));
    }
}
