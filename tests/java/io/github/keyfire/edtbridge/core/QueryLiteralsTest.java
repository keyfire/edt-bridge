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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pulling query literals out of BSL module source - the |-framed strings a query lives in. */
class QueryLiteralsTest {

    private static final String MODULE = String.join("\n",
            "// module comment with a \"quote\" that must not open a string",
            "Процедура Прочитать()",
            "\tЗапрос = Новый Запрос;",
            "\tЗапрос.Текст =",
            "\t\t\"ВЫБРАТЬ",
            "\t\t|\tТаблица.Поле КАК \"\"Поле\"\"",
            "\t\t|ИЗ",
            "\t\t|\tСправочник.Товары КАК Таблица\";",
            "\tСообщить(\"Выбрать файл\"); // a caption, not a query",
            "КонецПроцедуры",
            "",
            "Function Read()",
            "\tText =",
            "\t\t\"SELECT",
            "\t\t|\tOne.Field",
            "\t\t|FROM",
            "\t\t|\tCatalog.Goods AS One\";",
            "\tReturn Text;",
            "EndFunction");

    @Test
    @DisplayName("a |-framed literal starting with the query word becomes a candidate")
    void framedLiteralIsExtracted() {
        List<QueryLiterals.Candidate> found = QueryLiterals.extract(MODULE);
        assertEquals(2, found.size());
        QueryLiterals.Candidate first = found.get(0);
        assertEquals("Прочитать", first.method);
        assertEquals(5, first.line);
        assertEquals("ВЫБРАТЬ\n\tТаблица.Поле КАК \"Поле\"\nИЗ\n\tСправочник.Товары КАК Таблица",
                first.text);
    }

    @Test
    @DisplayName("English spellings work the same - the language itself is bilingual")
    void englishQueryAndMethodKeywords() {
        QueryLiterals.Candidate second = QueryLiterals.extract(MODULE).get(1);
        assertEquals("Read", second.method);
        assertEquals(14, second.line);
        assertTrue(second.text.startsWith("SELECT\n"));
    }

    @Test
    @DisplayName("a single-line literal starting with the word is a caption, not a query")
    void singleLineCaptionIsNotACandidate() {
        // "Выбрать файл" starts with the query word too; only the multiline |-framed form is
        // the query idiom, and a validator run over a caption would report nonsense.
        String source = "Процедура П()\n\tСообщить(\"Выбрать файл для загрузки\");\nКонецПроцедуры";
        assertTrue(QueryLiterals.extract(source).isEmpty());
    }

    @Test
    @DisplayName("a multiline literal of ordinary text is not a candidate either")
    void multilineProseIsNotACandidate() {
        String source = String.join("\n",
                "Перем Текст = \"Первая строка",
                "\t|вторая строка\";");
        assertTrue(QueryLiterals.extract(source).isEmpty());
    }

    @Test
    @DisplayName("a literal outside any method carries no method name")
    void moduleLevelLiteralHasNoMethod() {
        String source = String.join("\n",
                "Перем Т = \"ВЫБРАТЬ",
                "\t|Поле ИЗ Справочник.Товары\";");
        List<QueryLiterals.Candidate> found = QueryLiterals.extract(source);
        assertEquals(1, found.size());
        assertNull(found.get(0).method);
        assertEquals(1, found.get(0).line);
    }

    @Test
    @DisplayName("the query word must be a whole word: ВЫБРАТЬСЯ is not it")
    void keywordMatchesAsAWholeWord() {
        String source = String.join("\n",
                "Перем Т = \"ВЫБРАТЬСЯ из положения",
                "\t|как-нибудь\";");
        assertTrue(QueryLiterals.extract(source).isEmpty());
    }

    @Test
    @DisplayName("a quote inside a line comment does not open a string")
    void commentQuotesAreIgnored() {
        String source = String.join("\n",
                "// Забытая \"кавычка",
                "Перем Т = \"ВЫБРАТЬ",
                "\t|Поле ИЗ Справочник.Товары\";");
        List<QueryLiterals.Candidate> found = QueryLiterals.extract(source);
        assertEquals(1, found.size());
        assertEquals(2, found.get(0).line);
    }

    @Test
    @DisplayName("leading blank query lines do not hide the keyword")
    void leadingBlankLineInsideTheLiteral() {
        String source = String.join("\n",
                "Перем Т = \"",
                "\t|ВЫБРАТЬ РАЗРЕШЕННЫЕ",
                "\t|\tПоле ИЗ Справочник.Товары\";");
        assertEquals(1, QueryLiterals.extract(source).size());
    }
}
