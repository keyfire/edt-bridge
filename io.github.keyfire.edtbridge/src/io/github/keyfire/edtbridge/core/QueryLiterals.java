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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulling query literals out of BSL module source.
 *
 * <p>A query in BSL lives in a string literal whose continuation lines are framed with {@code |}.
 * Extracting it by hand means unescaping the doubled quotes and dropping the framing, and doing
 * that outside the bridge doubles the context: the text is copied out, validated, and by then may
 * have drifted from what the module actually holds. This class does the extraction next to the
 * model instead, so the validated text IS the stored text.
 *
 * <p>What counts as a query is deliberately narrow: a MULTILINE |-framed literal whose first word
 * is ВЫБРАТЬ or SELECT. A single-line literal starting with the same word is far more often a UI
 * caption ("Выбрать файл"), and a validator run over a caption reports nonsense. A query built by
 * concatenation cannot be validated at all - its pieces never qualify here, which is correct.
 *
 * <p>No EDT or Eclipse types here on purpose - it compiles and is tested without the SDK.
 */
public final class QueryLiterals {

    /** One extracted literal: where it sits and the text with the framing removed. */
    public static final class Candidate {
        /** Name of the enclosing procedure or function, or null at module level. */
        public final String method;
        /** 1-based line of the literal's opening quote. */
        public final int line;
        /** The query text: framing dropped, doubled quotes unescaped, lines joined with \n. */
        public final String text;

        Candidate(String method, int line, String text) {
            this.method = method;
            this.line = line;
            this.text = text;
        }
    }

    //: A method header names the enclosing scope; both languages of the platform.
    private static final Pattern METHOD_HEADER = Pattern.compile(
            "^\\s*(?:процедура|функция|procedure|function)\\s+([\\p{L}_][\\p{L}\\p{N}_]*)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern METHOD_FOOTER = Pattern.compile(
            "^\\s*(?:конецпроцедуры|конецфункции|endprocedure|endfunction)(?![\\p{L}\\p{N}_])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    //: The first word of the literal decides; the run of letters must BE the keyword, so
    //: ВЫБРАТЬСЯ does not qualify.
    private static final Pattern FIRST_WORD = Pattern.compile("^[\\p{L}]+");

    private QueryLiterals() {
    }

    /** Every query literal of the module source, in order of appearance. */
    public static List<Candidate> extract(String source) {
        List<Candidate> out = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return out;
        }
        String[] lines = source.split("\n", -1);
        String method = null;
        for (int i = 0; i < lines.length; i++) {
            String line = stripCr(lines[i]);
            Matcher header = METHOD_HEADER.matcher(line);
            if (header.find()) {
                method = header.group(1);
            } else if (METHOD_FOOTER.matcher(line).find()) {
                method = null;
            }
            int pos = 0;
            while (pos < line.length()) {
                char c = line.charAt(pos);
                if (c == '/' && pos + 1 < line.length() && line.charAt(pos + 1) == '/') {
                    break; // a line comment runs to the end of the line
                }
                if (c != '"') {
                    pos++;
                    continue;
                }
                int startLine = i + 1;
                StringBuilder content = new StringBuilder();
                int[] end = consume(lines, i, pos + 1, content);
                if (end == null) {
                    break; // unterminated literal - broken source, stop scanning this line
                }
                if (end[0] != i && isQuery(content)) {
                    out.add(new Candidate(method, startLine, content.toString()));
                }
                if (end[0] != i) {
                    i = end[0];
                    line = stripCr(lines[i]);
                }
                pos = end[1];
            }
        }
        return out;
    }

    /**
     * Walk a literal from just after its opening quote to just after the closing one.
     *
     * <p>A line that ends while the literal is open continues it only when the next line's first
     * non-blank character is {@code |} - that is the BSL framing; anything else means the source
     * does not parse, and the walk gives up rather than guesses.
     *
     * @return {index of the line holding the closing quote, position right after it}, or null
     */
    private static int[] consume(String[] lines, int lineIndex, int start, StringBuilder content) {
        int i = lineIndex;
        String line = stripCr(lines[i]);
        int pos = start;
        while (true) {
            if (pos >= line.length()) {
                int next = i + 1;
                if (next >= lines.length) {
                    return null;
                }
                String continuation = stripCr(lines[next]);
                int bar = firstNonBlank(continuation);
                if (bar >= continuation.length() || continuation.charAt(bar) != '|') {
                    return null;
                }
                content.append('\n');
                i = next;
                line = continuation;
                pos = bar + 1;
                continue;
            }
            char c = line.charAt(pos);
            if (c == '"') {
                if (pos + 1 < line.length() && line.charAt(pos + 1) == '"') {
                    content.append('"');
                    pos += 2;
                    continue;
                }
                return new int[] {i, pos + 1};
            }
            content.append(c);
            pos++;
        }
    }

    private static boolean isQuery(StringBuilder content) {
        Matcher word = FIRST_WORD.matcher(content.toString().strip());
        if (!word.find()) {
            return false;
        }
        String first = word.group();
        return first.equalsIgnoreCase("ВЫБРАТЬ") || first.equalsIgnoreCase("SELECT");
    }

    private static int firstNonBlank(String line) {
        int pos = 0;
        while (pos < line.length() && (line.charAt(pos) == ' ' || line.charAt(pos) == '\t')) {
            pos++;
        }
        return pos;
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
