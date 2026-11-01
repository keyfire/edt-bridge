/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ExactTextReplacementTest {
    @Test void replacementPreservesEveryCharacterInsteadOfFormattingHtml() {
        String replacement = "\ufeff \r\n<html>\n<script>const x = '&amp;';</script>\r\n<p>Привет \ud83d\ude00</p>\n</html> \r\n";
        var plan = ExactTextReplacement.plan("<p>Old</p>\n", replacement, null);
        assertEquals(replacement, plan.newText());
        assertEquals("<p>Old</p>\n", plan.oldText());
        assertFalse(plan.unchanged());
        assertArrayEquals(replacement.getBytes(StandardCharsets.UTF_8), ExactTextReplacement.encode(plan.newText()));
    }

    @Test void emptyTextIsAnIntentionalReplacementButMissingTextIsRefused() {
        assertEquals("", ExactTextReplacement.plan("old", "", null).newText());
        assertThrows(IllegalArgumentException.class, () -> ExactTextReplacement.plan("old", null, null));
    }

    @Test void hashUsesExactUtf8CharactersIncludingBomAndLineEndings() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ExactTextReplacement.hash(""));
        assertNotEquals(ExactTextReplacement.hash("x\n"), ExactTextReplacement.hash("x\r\n"));
        assertNotEquals(ExactTextReplacement.hash("x"), ExactTextReplacement.hash("\ufeffx"));
        assertNotEquals(ExactTextReplacement.hash("x"), ExactTextReplacement.hash("x\n"));
        assertTrue(ExactTextReplacement.plan("x\r\n", "x\r\n", ExactTextReplacement.hash("x\r\n")).unchanged());
        assertThrows(IllegalArgumentException.class,
                () -> ExactTextReplacement.plan("changed", "new", ExactTextReplacement.hash("old")));
    }

    @Test void strictUtf8RoundTripRetainsBomAndRejectsCorruptInput() {
        byte[] bytes = "\ufeff<p>Текст</p>\r\n".getBytes(StandardCharsets.UTF_8);
        assertEquals("\ufeff<p>Текст</p>\r\n", ExactTextReplacement.decode(bytes));
        assertArrayEquals(bytes, ExactTextReplacement.encode(ExactTextReplacement.decode(bytes)));
        assertThrows(IllegalArgumentException.class, () -> ExactTextReplacement.decode(new byte[] {(byte) 0xc3, 0x28}));
        assertThrows(IllegalArgumentException.class, () -> ExactTextReplacement.encode("\ud800"));
        assertThrows(IllegalArgumentException.class, () -> ExactTextReplacement.plan("ok", "\udc00", null));
    }

    @Test void unsupportedEncodingIsRejectedWithoutImplicitConversion() {
        assertDoesNotThrow(() -> ExactTextReplacement.requireUtf8("UTF-8"));
        assertDoesNotThrow(() -> ExactTextReplacement.requireUtf8("utf8"));
        assertThrows(IllegalArgumentException.class, () -> ExactTextReplacement.requireUtf8("windows-1251"));
        assertThrows(IllegalArgumentException.class, () -> ExactTextReplacement.requireUtf8("UTF-16"));
        assertThrows(IllegalArgumentException.class, () -> ExactTextReplacement.requireUtf8(null));
    }
}
