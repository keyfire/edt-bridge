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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Temporary tables through a batch: a read must follow the ПОМЕСТИТЬ that creates the table. */
class TempTableFlowTest {

    private static String batch(String... queries) {
        return String.join(";\n", queries);
    }

    @Test
    @DisplayName("a table put by an earlier query is read without complaint")
    void putThenReadIsClean() {
        String text = batch(
                "ВЫБРАТЬ\n\tТовары.Ссылка КАК Ссылка\nПОМЕСТИТЬ ВТТовары\nИЗ\n\tСправочник.Товары КАК Товары",
                "ВЫБРАТЬ\n\tВТТовары.Ссылка\nИЗ\n\tВТТовары КАК ВТТовары\n\t\tЛЕВОЕ СОЕДИНЕНИЕ Справочник.Цены КАК Цены\n\t\tПО ВТТовары.Ссылка = Цены.Товар");
        assertTrue(TempTableFlow.check(text).isEmpty());
    }

    @Test
    @DisplayName("a read of a table nobody puts is reported at the name's position")
    void readWithoutPutIsReported() {
        String text = batch(
                "ВЫБРАТЬ\n\tТовары.Ссылка КАК Ссылка\nПОМЕСТИТЬ ВТТовары\nИЗ\n\tСправочник.Товары КАК Товары",
                "ВЫБРАТЬ\n\tОстатки.Товар\nИЗ\n\tВТОстатки КАК Остатки");
        List<TempTableFlow.Problem> problems = TempTableFlow.check(text);
        assertEquals(1, problems.size());
        TempTableFlow.Problem p = problems.get(0);
        assertEquals("ВТОстатки", p.table);
        assertEquals(2, p.query);
        assertEquals(TempTableFlow.CODE_NOT_CREATED, p.code);
        assertEquals(9, p.line);
        assertEquals(2, p.column);
        assertEquals(text.indexOf("ВТОстатки"), p.offset);
        assertEquals("ВТОстатки".length(), p.length);
        assertTrue(p.message.contains("\"ВТОстатки\""), p.message);
        assertTrue(p.message.contains("Query 2"), p.message);
    }

    @Test
    @DisplayName("a table put only by a later query is reported, and the message says so")
    void putLaterIsReportedWithTheHint() {
        String text = batch(
                "ВЫБРАТЬ Т.Поле ИЗ ВТДанные КАК Т",
                "ВЫБРАТЬ Товары.Ссылка КАК Поле ПОМЕСТИТЬ ВТДанные ИЗ Справочник.Товары КАК Товары");
        List<TempTableFlow.Problem> problems = TempTableFlow.check(text);
        assertEquals(1, problems.size());
        assertEquals(TempTableFlow.CODE_NOT_CREATED, problems.get(0).code);
        assertTrue(problems.get(0).message.contains("query 2"), problems.get(0).message);
    }

    @Test
    @DisplayName("reading the table in the very query that puts it is an error of its own")
    void readInTheCreatingQuery() {
        String text = "ВЫБРАТЬ Т.Поле ПОМЕСТИТЬ ВТДанные ИЗ ВТДанные КАК Т";
        List<TempTableFlow.Problem> problems = TempTableFlow.check(text);
        assertEquals(1, problems.size());
        assertEquals(TempTableFlow.CODE_SAME_QUERY, problems.get(0).code);
    }

    @Test
    @DisplayName("a dropped table is gone; putting it again brings it back")
    void dropIsFollowed() {
        String text = batch(
                "ВЫБРАТЬ Товары.Ссылка ПОМЕСТИТЬ ВТ ИЗ Справочник.Товары КАК Товары",
                "УНИЧТОЖИТЬ ВТ",
                "ВЫБРАТЬ ВТ.Ссылка ИЗ ВТ КАК ВТ",
                "ВЫБРАТЬ Товары.Ссылка ПОМЕСТИТЬ ВТ ИЗ Справочник.Товары КАК Товары",
                "ВЫБРАТЬ ВТ.Ссылка ИЗ ВТ КАК ВТ");
        List<TempTableFlow.Problem> problems = TempTableFlow.check(text);
        assertEquals(1, problems.size());
        assertEquals(TempTableFlow.CODE_DROPPED, problems.get(0).code);
        assertEquals(3, problems.get(0).query);
    }

    @Test
    @DisplayName("metadata tables, virtual tables, parameters, subqueries and constants are not judged")
    void otherSourcesAreLeftAlone() {
        String text = batch(
                "ВЫБРАТЬ\n\tОстатки.Товар,\n\tКурсы.Курс\nПОМЕСТИТЬ ВТОстатки\nИЗ\n"
                        + "\tРегистрНакопления.Остатки.Остатки(&Дата, Товар В (&Товары)) КАК Остатки\n"
                        + "\t\tЛЕВОЕ СОЕДИНЕНИЕ РегистрСведений.Курсы.СрезПоследних(&Дата, ) КАК Курсы\n"
                        + "\t\tПО (ИСТИНА)\n"
                        + "\t\tВНУТРЕННЕЕ СОЕДИНЕНИЕ &ТаблицаПараметр КАК Параметр\n"
                        + "\t\tПО Остатки.Товар = Параметр.Товар\n"
                        + "\t\tПОЛНОЕ СОЕДИНЕНИЕ (ВЫБРАТЬ\n\t\t\tТовары.Ссылка КАК Ссылка\n\t\tИЗ\n"
                        + "\t\t\tСправочник.Товары КАК Товары) КАК Подзапрос\n"
                        + "\t\tПО Остатки.Товар = Подзапрос.Ссылка,\n"
                        + "\tКонстанты КАК Константы,\n"
                        + "\tДокумент.Заказ.Товары КАК Строки",
                "ВЫБРАТЬ ВТОстатки.Товар ИЗ ВТОстатки КАК ВТОстатки");
        assertTrue(TempTableFlow.check(text).isEmpty());
    }

    @Test
    @DisplayName("a join source and a comma-continued source are checked like the first one")
    void joinAndCommaSources() {
        String text = batch(
                "ВЫБРАТЬ Товары.Ссылка ПОМЕСТИТЬ ВТ1 ИЗ Справочник.Товары КАК Товары",
                "ВЫБРАТЬ\n\tА.Ссылка\nИЗ\n\tВТ1 А,\n\tВТ2 Б\n\t\tЛЕВОЕ СОЕДИНЕНИЕ ВТ3 КАК В\n\t\tПО А.Ссылка = В.Ссылка");
        List<TempTableFlow.Problem> problems = TempTableFlow.check(text);
        assertEquals(2, problems.size());
        assertEquals("ВТ2", problems.get(0).table);
        assertEquals("ВТ3", problems.get(1).table);
    }

    @Test
    @DisplayName("a subquery inside a source list is followed too")
    void subqueryInsideSourceListIsFollowed() {
        String text = "ВЫБРАТЬ\n\tП.Поле\nИЗ\n\t(ВЫБРАТЬ\n\t\tВТ.Поле КАК Поле\n\tИЗ\n\t\tВТ КАК ВТ) КАК П";
        List<TempTableFlow.Problem> problems = TempTableFlow.check(text);
        assertEquals(1, problems.size());
        assertEquals("ВТ", problems.get(0).table);
    }

    @Test
    @DisplayName("the English spellings of the keywords work the same")
    void englishKeywords() {
        String text = batch(
                "SELECT Goods.Ref INTO TT FROM Catalog.Goods AS Goods",
                "SELECT TT.Ref FROM TT AS TT",
                "DROP TT",
                "SELECT TT.Ref FROM TT AS TT");
        List<TempTableFlow.Problem> problems = TempTableFlow.check(text);
        assertEquals(1, problems.size());
        assertEquals(TempTableFlow.CODE_DROPPED, problems.get(0).code);
        assertEquals(4, problems.get(0).query);
    }

    @Test
    @DisplayName("text inside string literals and comments is not a source")
    void literalsAndCommentsAreSkipped() {
        String text = batch(
                "ВЫБРАТЬ\n\t\"ИЗ ВТНет; УНИЧТОЖИТЬ ВТ\" КАК Текст, // читаем ИЗ ВТКомментарий\n"
                        + "\tТовары.Ссылка\nПОМЕСТИТЬ ВТ\nИЗ\n\tСправочник.Товары КАК Товары",
                "ВЫБРАТЬ ВТ.Ссылка ИЗ ВТ КАК ВТ");
        assertTrue(TempTableFlow.check(text).isEmpty());
    }

    @Test
    @DisplayName("a template placeholder glued to a mark is not a temporary table")
    void placeholderIsNotJudged() {
        String text = "ВЫБРАТЬ Т.Поле ИЗ %ИмяТаблицы КАК Т ЛЕВОЕ СОЕДИНЕНИЕ #Таблица КАК Д ПО (ИСТИНА)";
        assertTrue(TempTableFlow.check(text).isEmpty());
    }

    @Test
    @DisplayName("a batch the scan cannot follow is left alone rather than guessed at")
    void unbalancedParenthesesGiveNoVerdict() {
        assertTrue(TempTableFlow.check("ВЫБРАТЬ Т.Поле ИЗ ВТ КАК Т ГДЕ (Т.Поле = 1").isEmpty());
        assertTrue(TempTableFlow.check("ВЫБРАТЬ Т.Поле ИЗ КАК Т").isEmpty());
        assertTrue(TempTableFlow.check("   ").isEmpty());
        assertTrue(TempTableFlow.check(null).isEmpty());
    }

    @Test
    @DisplayName("names compare without regard to case")
    void namesAreCaseInsensitive() {
        String text = batch(
                "ВЫБРАТЬ Товары.Ссылка ПОМЕСТИТЬ втТовары ИЗ Справочник.Товары КАК Товары",
                "ВЫБРАТЬ ВТТОВАРЫ.Ссылка ИЗ ВТТОВАРЫ КАК ВТТОВАРЫ");
        assertTrue(TempTableFlow.check(text).isEmpty());
    }
}
