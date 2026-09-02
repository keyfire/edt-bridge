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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Following the temporary tables through a query batch: which query puts each one, which reads it.
 *
 * <p>EDT's QL validator checks every query of a batch against the metadata, and the batch against
 * nothing: a query that reads a ПОМЕСТИТЬ table no earlier query of the same batch creates is
 * "valid" to it, and fails only when the platform runs it. A live check found exactly that - the
 * batch that had broken an operation in production and its repaired form answered the same. This
 * class adds what the validator leaves out: the batch is split at {@code ;}, the names put by
 * ПОМЕСТИТЬ/INTO and dropped by УНИЧТОЖИТЬ/DROP are collected in order, and every source of
 * ИЗ/FROM and СОЕДИНЕНИЕ/JOIN (subqueries included) that looks like a temporary table is checked
 * against the tables that exist at that point.
 *
 * <p>What is judged is deliberately narrow, because a false alarm on a working batch costs more than
 * a missed one. A source is taken for a temporary table only when it is a bare name: no dot (that is
 * a metadata table, {@code Справочник.Товары}, or a virtual table), no ampersand (a parameter table),
 * not the constants table, and not glued to a preceding character ({@code %Name} is a template
 * placeholder). Anything the scan cannot follow - unbalanced parentheses, a keyword where a source
 * should be - ends the judgement of that batch rather than guessing.
 *
 * <p>No EDT or Eclipse types here on purpose - it compiles and is tested without the SDK.
 */
public final class TempTableFlow {

    /** Issue code: the table is read and no query before this one puts it. */
    public static final String CODE_NOT_CREATED = "edt-bridge.temp-table-not-created";
    /** Issue code: the query reads the table it is itself putting. */
    public static final String CODE_SAME_QUERY = "edt-bridge.temp-table-same-query";
    /** Issue code: an earlier query dropped the table before this read. */
    public static final String CODE_DROPPED = "edt-bridge.temp-table-dropped";

    /** One read of a temporary table that does not exist at that point of the batch. */
    public static final class Problem {
        /** The table name as written in the query. */
        public final String table;
        /** 1-based query number within the batch. */
        public final int query;
        /** 1-based line of the name within the batch text. */
        public final int line;
        /** 1-based column of the name within its line. */
        public final int column;
        /** 0-based offset of the name within the batch text. */
        public final int offset;
        /** Length of the name. */
        public final int length;
        /** One of the CODE_* constants. */
        public final String code;
        public final String message;

        Problem(String table, int query, int line, int column, int offset, int length, String code,
                String message) {
            this.table = table;
            this.query = query;
            this.line = line;
            this.column = column;
            this.offset = offset;
            this.length = length;
            this.code = code;
            this.message = message;
        }
    }

    private enum Kind { WORD, PARAMETER, NUMBER, STRING, PUNCT }

    private static final class Token {
        final Kind kind;
        final String text;
        final int offset;
        /** A word glued to the character before it (%Name, #Name) is a template placeholder. */
        final boolean glued;

        Token(Kind kind, String text, int offset, boolean glued) {
            this.kind = kind;
            this.text = text;
            this.offset = offset;
            this.glued = glued;
        }

        boolean is(char c) {
            return kind == Kind.PUNCT && text.charAt(0) == c;
        }

        boolean word(Set<String> any) {
            return kind == Kind.WORD && any.contains(text.toLowerCase(Locale.ROOT));
        }
    }

    private static final Set<String> SOURCE_LEAD = Set.of("из", "from", "соединение", "join");
    private static final Set<String> PUT = Set.of("поместить", "into");
    private static final Set<String> DROP = Set.of("уничтожить", "drop");
    private static final Set<String> ALIAS = Set.of("как", "as");
    //: The constants table is the one source without a dot that is not a temporary table.
    private static final Set<String> CONSTANTS = Set.of("константы", "constants");
    //: Words that cannot be a source or an alias; a bare alias is any other word.
    private static final Set<String> KEYWORDS = Set.of(
            "выбрать", "select", "разрешенные", "allowed", "различные", "distinct", "первые", "top",
            "поместить", "into", "из", "from", "как", "as", "где", "where", "сгруппировать", "group",
            "по", "by", "on", "имеющие", "having", "упорядочить", "order", "итоги", "totals",
            "объединить", "union", "все", "all", "левое", "left", "правое", "right", "полное", "full",
            "внутреннее", "inner", "внешнее", "outer", "соединение", "join", "индексировать", "index",
            "автоупорядочивание", "autoorder", "для", "for", "изменения", "update", "уничтожить", "drop",
            "и", "and", "или", "or", "не", "not", "выбор", "case", "когда", "when", "тогда", "then",
            "иначе", "else", "конец", "end", "в", "in", "иерархии", "hierarchy", "подобно", "like",
            "есть", "is", "null", "между", "between", "ссылка", "refs", "спецсимвол", "escape",
            "убывание", "desc", "возр", "asc", "общие", "overall", "только", "only", "периодами",
            "periods", "сгруппированобы", "groupedby");

    private TempTableFlow() {
    }

    /** Every read of a temporary table that does not exist at that point of the batch, in order. */
    public static List<Problem> check(String batch) {
        List<Problem> out = new ArrayList<>();
        if (batch == null || batch.isBlank()) {
            return out;
        }
        List<Token> tokens = tokenize(batch);
        List<List<Token>> queries = split(tokens);
        if (queries == null) {
            return out; // unbalanced parentheses - not a batch this scan can follow
        }
        // Every put of the batch up front, so a read of a table put only later says so.
        Map<String, Integer> firstPut = new LinkedHashMap<>();
        for (int q = 0; q < queries.size(); q++) {
            for (String name : puts(queries.get(q))) {
                firstPut.putIfAbsent(name, q + 1);
            }
        }
        Set<String> live = new HashSet<>();
        Set<String> dropped = new HashSet<>();
        for (int q = 0; q < queries.size(); q++) {
            List<Token> query = queries.get(q);
            int number = q + 1;
            if (query.isEmpty()) {
                continue;
            }
            if (query.get(0).word(DROP)) {
                if (query.size() > 1 && query.get(1).kind == Kind.WORD) {
                    String name = key(query.get(1));
                    live.remove(name);
                    dropped.add(name);
                }
                continue;
            }
            Set<String> putHere = puts(query);
            for (Token read : reads(query)) {
                String name = key(read);
                if (live.contains(name)) {
                    continue;
                }
                String code;
                String message;
                if (putHere.contains(name)) {
                    code = CODE_SAME_QUERY;
                    message = "Query " + number + " reads temporary table \"" + read.text
                            + "\" in the same query that puts it (ПОМЕСТИТЬ/INTO) - a query cannot select"
                            + " from the table it is creating";
                } else if (dropped.contains(name)) {
                    code = CODE_DROPPED;
                    message = "Query " + number + " reads temporary table \"" + read.text
                            + "\", which an earlier query of the batch dropped (УНИЧТОЖИТЬ/DROP)";
                } else {
                    code = CODE_NOT_CREATED;
                    Integer later = firstPut.get(name);
                    message = "Query " + number + " reads temporary table \"" + read.text
                            + "\", which no earlier query of the batch puts (no ПОМЕСТИТЬ " + read.text
                            + " / INTO " + read.text + " before it)"
                            + (later != null ? " - it is put only later, by query " + later : "");
                }
                int[] position = lineAndColumn(batch, read.offset);
                out.add(new Problem(read.text, number, position[0], position[1], read.offset,
                        read.text.length(), code, message));
            }
            for (String name : putHere) {
                live.add(name);
                dropped.remove(name);
            }
        }
        return out;
    }

    /** Names put by ПОМЕСТИТЬ/INTO anywhere in the query, lower-cased. */
    private static Set<String> puts(List<Token> query) {
        Set<String> names = new HashSet<>();
        for (int i = 0; i + 1 < query.size(); i++) {
            if (query.get(i).word(PUT) && query.get(i + 1).kind == Kind.WORD) {
                names.add(key(query.get(i + 1)));
            }
        }
        return names;
    }

    /**
     * Every source of the query that looks like a temporary table, subqueries included: the bare names
     * after ИЗ/FROM and СОЕДИНЕНИЕ/JOIN and after the commas that continue a source list.
     */
    private static List<Token> reads(List<Token> query) {
        List<Token> out = new ArrayList<>();
        for (int i = 0; i < query.size(); i++) {
            if (query.get(i).word(SOURCE_LEAD)) {
                sourceList(query, i + 1, out);
            }
        }
        return out;
    }

    /** Walk one source list; the tokens inside parentheses are left to the caller's own walk. */
    private static void sourceList(List<Token> query, int start, List<Token> out) {
        int i = start;
        while (i < query.size()) {
            Token t = query.get(i);
            if (t.is('(')) {
                i = closing(query, i);
                if (i < 0) {
                    return;
                }
                i++;
            } else if (t.kind == Kind.PARAMETER) {
                i++;
            } else if (t.kind == Kind.WORD) {
                if (KEYWORDS.contains(t.text.toLowerCase(Locale.ROOT))) {
                    return; // a keyword where a source should be - not a list this scan follows
                }
                if (i + 1 < query.size() && query.get(i + 1).is('.')) {
                    // Metadata or virtual table: Name(.Name)* with optional parameters.
                    while (i + 2 < query.size() && query.get(i + 1).is('.')
                            && query.get(i + 2).kind == Kind.WORD) {
                        i += 2;
                    }
                    i++;
                    if (i < query.size() && query.get(i).is('(')) {
                        i = closing(query, i);
                        if (i < 0) {
                            return;
                        }
                        i++;
                    }
                } else {
                    if (!t.glued && !CONSTANTS.contains(t.text.toLowerCase(Locale.ROOT))) {
                        out.add(t);
                    }
                    i++;
                }
            } else {
                return;
            }
            // Optional alias, with or without КАК/AS.
            if (i < query.size() && query.get(i).word(ALIAS)) {
                i += 2;
            } else if (i < query.size() && query.get(i).kind == Kind.WORD
                    && !KEYWORDS.contains(query.get(i).text.toLowerCase(Locale.ROOT))) {
                i++;
            }
            if (i < query.size() && query.get(i).is(',')) {
                i++;
                continue;
            }
            return;
        }
    }

    /** Index of the parenthesis closing the one at {@code open}, or -1. */
    private static int closing(List<Token> tokens, int open) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            if (tokens.get(i).is('(')) {
                depth++;
            } else if (tokens.get(i).is(')')) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** The batch cut at the semicolons outside parentheses; null when parentheses do not balance. */
    private static List<List<Token>> split(List<Token> tokens) {
        List<List<Token>> out = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int depth = 0;
        for (Token t : tokens) {
            if (t.is('(')) {
                depth++;
            } else if (t.is(')')) {
                depth--;
                if (depth < 0) {
                    return null;
                }
            }
            if (depth == 0 && t.is(';')) {
                if (!current.isEmpty()) {
                    out.add(current);
                }
                current = new ArrayList<>();
                continue;
            }
            current.add(t);
        }
        if (depth != 0) {
            return null;
        }
        if (!current.isEmpty()) {
            out.add(current);
        }
        return out;
    }

    private static String key(Token t) {
        return t.text.toLowerCase(Locale.ROOT);
    }

    /** Words, &parameters, numbers, strings and single punctuation marks; comments are dropped. */
    private static List<Token> tokenize(String text) {
        List<Token> out = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                while (i < n && text.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '"') {
                int start = i++;
                while (i < n) {
                    if (text.charAt(i) == '"') {
                        if (i + 1 < n && text.charAt(i + 1) == '"') {
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    i++;
                }
                i = Math.min(i + 1, n);
                out.add(new Token(Kind.STRING, text.substring(start, i), start, false));
            } else if (isWordStart(c)) {
                int start = i;
                while (i < n && isWordPart(text.charAt(i))) {
                    i++;
                }
                boolean glued = start > 0 && !Character.isWhitespace(text.charAt(start - 1))
                        && "(,.".indexOf(text.charAt(start - 1)) < 0;
                out.add(new Token(Kind.WORD, text.substring(start, i), start, glued));
            } else if (c == '&' && i + 1 < n && isWordStart(text.charAt(i + 1))) {
                int start = i++;
                while (i < n && isWordPart(text.charAt(i))) {
                    i++;
                }
                out.add(new Token(Kind.PARAMETER, text.substring(start, i), start, false));
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < n && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '.')) {
                    i++;
                }
                out.add(new Token(Kind.NUMBER, text.substring(start, i), start, false));
            } else {
                out.add(new Token(Kind.PUNCT, String.valueOf(c), i, false));
                i++;
            }
        }
        return out;
    }

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** {1-based line, 1-based column} of an offset. */
    private static int[] lineAndColumn(String text, int offset) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new int[] {line, column};
    }
}
