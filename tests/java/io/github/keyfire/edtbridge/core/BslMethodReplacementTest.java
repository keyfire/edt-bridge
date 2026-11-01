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

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class BslMethodReplacementTest {
    @Test void replacesOnlyTheSelectedRangeAndFingerprintsBothSources() throws Exception {
        String before = "// header\nProcedure Run()\nEndProcedure\n// next\n";
        int start = before.indexOf("Procedure"); int end = before.indexOf("\n// next");
        var edit = BslMethodReplacement.plan(before, start, end,
                "Procedure Run(Value)\n    Message(Value);\nEndProcedure", null);
        assertEquals(before.substring(start, end), edit.oldText());
        assertEquals("// header\n" + edit.newText() + "\n// next\n", edit.text());
        assertEquals(digest(before), edit.sourceHash());
        assertEquals(digest(edit.text()), edit.resultHash());
        assertFalse(edit.unchanged());
    }
    @Test void normalizesOnlyReplacementLineEndingsAndPreservesBomAndUnicode() {
        String prefix = "\ufeff// Заголовок\r\n#Область Интерфейс\r\n";
        String old = "Процедура Выполнить()\r\nКонецПроцедуры";
        String suffix = "\r\n#КонецОбласти\r\n// Другой текст\n";
        var edit = BslMethodReplacement.plan(prefix + old + suffix, prefix.length(), prefix.length() + old.length(),
                "\nПроцедура Выполнить(Знач Данные)\n    Сообщить(Данные);\nКонецПроцедуры\n", null);
        assertEquals(prefix + "Процедура Выполнить(Знач Данные)\r\n    Сообщить(Данные);\r\nКонецПроцедуры" + suffix, edit.text());
        assertFalse(edit.newText().replace("\r\n", "").contains("\n"));
    }
    @Test void acceptsCurrentHashButRejectsAnyStaleSource() {
        String source = "prefix\nold\nsuffix";
        var first = BslMethodReplacement.plan(source, 7, 10, "new", null);
        assertEquals(first.text(), BslMethodReplacement.plan(source, 7, 10, "new", first.sourceHash()).text());
        assertThrows(IllegalArgumentException.class,
                () -> BslMethodReplacement.plan(source + " ", 7, 10, "new", first.sourceHash()));
        assertThrows(IllegalArgumentException.class,
                () -> BslMethodReplacement.plan(source, 7, 10, "new", "invalid"));
    }
    @Test void matchingReplacementIsANoOpWithTheSameHash() {
        var edit = BslMethodReplacement.plan("head\nbody\n", 5, 9, "body\n", null);
        assertTrue(edit.unchanged());
        assertEquals(edit.sourceHash(), edit.resultHash());
        assertEquals("head\nbody\n", edit.text());
    }
    @Test void anExactMethodRoundTripPreservesIndentationAndTrailingSpaces() {
        for (String method : new String[] {"\tProcedure Run()\n\tEndProcedure", "// docs  \r\n    Procedure Run()\r\n    EndProcedure // tail  "}) {
            String newline = method.contains("\r\n") ? "\r\n" : "\n";
            String source = "// header" + newline + newline + method + newline + "// neighbor";
            int from = source.indexOf(method);
            var edit = BslMethodReplacement.plan(source, from, from + method.length(), method, null);
            assertEquals(method, edit.newText());
            assertTrue(edit.unchanged());
        }
    }
    @Test void rejectsInvalidRangesAndMissingReplacement() {
        assertThrows(IllegalArgumentException.class, () -> BslMethodReplacement.plan("abc", -1, 2, "x", null));
        assertThrows(IllegalArgumentException.class, () -> BslMethodReplacement.plan("abc", 1, 4, "x", null));
        assertThrows(IllegalArgumentException.class, () -> BslMethodReplacement.plan("abc", 2, 1, "x", null));
        assertThrows(IllegalArgumentException.class, () -> BslMethodReplacement.plan("abc", 1, 1, "x", null));
        assertThrows(IllegalArgumentException.class, () -> BslMethodReplacement.plan("abc", 0, 1, " \r\n ", null));
        assertThrows(IllegalArgumentException.class, () -> BslMethodReplacement.plan("abc", 0, 1, null, null));
    }
    @Test void refusesMalformedUnicodeInsteadOfChangingItDuringUtf8Encoding() {
        assertThrows(IllegalArgumentException.class,
                () -> BslMethodReplacement.plan("old", 0, 3, "\ud800", null));
    }
    private static String digest(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
