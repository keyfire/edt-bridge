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

/**
 * Deciding whether one validation problem belongs to what the caller asked about.
 *
 * <p>Two kinds of problem arrive with different addresses, which is the whole difficulty: an Eclipse
 * marker carries a project-relative RESOURCE PATH, while an EDT check marker carries the object's
 * PRESENTATION ("HTTPСервис.Payments.Модуль"). A location filter therefore matches a path prefix,
 * and falls back to the object name for the presentation case.
 *
 * <p>That fallback is where two live checks caught defects worth keeping tests for. Matching the name
 * as a plain substring made a request for one object return the problems of a differently-named
 * neighbour (Payments also matches Payments_v2): the name must match a whole identifier segment.
 * And matching by the OBJECT name alone made a request for one form return the problems of the
 * object's other forms: a form is addressed by both names at once.
 *
 * <p>No EDT or Eclipse types here on purpose - it compiles and is tested without the SDK.
 */
public final class ProblemFilter {

    private ProblemFilter() {
    }

    /**
     * Whether a problem at {@code resource} is in scope.
     *
     * <p>The name half takes SEVERAL tokens and demands them CONSECUTIVELY, because neither one token
     * nor an unordered set was enough to address a form. A form's marker is presented as
     * "Справочник.Товары.Форма.Контроль.Модуль": knowing only the object name accepted the markers of
     * its OTHER forms, and knowing both names in any position still did - for a form named Форма,
     * which is the platform's default name, because the presentation says Форма about every form of
     * the object. The object name, the form word and the form name STANDING NEXT TO EACH OTHER name
     * exactly one form.
     *
     * @param resource   the problem's resource - a project-relative path, or an object presentation
     * @param pathPrefix project-relative path or folder prefix to keep, or null
     * @param nameTokens segments that must appear in this order, side by side, or empty/null for no
     *                   name filter; {@link MetadataPaths#FORM_WORD} stands for any spelling of the
     *                   word for a form
     */
    public static boolean matchesLocation(String resource, String pathPrefix,
            java.util.List<String> nameTokens) {
        String value = normalize(resource);
        if (pathPrefix != null && !pathPrefix.isBlank() && !value.isEmpty()
                && value.startsWith(normalize(pathPrefix))) {
            return true;
        }
        if (nameTokens == null || nameTokens.isEmpty()) {
            return false;
        }
        return namesSequence(value, nameTokens);
    }

    /**
     * Spellings a location can use for the form part: two languages, singular in a presentation
     * ("Форма.Контроль") and plural in a path ("Forms/Контроль").
     */
    private static final java.util.Set<String> FORM_WORDS =
            java.util.Set.of("форма", "формы", "form", "forms");

    /**
     * True when {@code tokens} appear in {@code text} as identifier segments standing NEXT TO EACH
     * OTHER, in the given order: "Товары", the form word, "Контроль" is inside
     * "Справочник.Товары.Форма.Контроль.Модуль" but not inside "Справочник.Товары.Форма.Форма.Модуль",
     * where the same three words are all present yet name a different form.
     */
    public static boolean namesSequence(String text, java.util.List<String> tokens) {
        if (text == null || tokens == null || tokens.isEmpty()) {
            return false;
        }
        String[] parts = segments(text);
        for (int start = 0; start + tokens.size() <= parts.length; start++) {
            boolean all = true;
            for (int i = 0; i < tokens.size(); i++) {
                if (!matchesToken(parts[start + i], tokens.get(i))) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    /** One segment against one token: the form-word placeholder accepts any of its spellings. */
    private static boolean matchesToken(String segment, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (MetadataPaths.FORM_WORD.equals(token)) {
            return FORM_WORDS.contains(segment.toLowerCase());
        }
        return segment.equalsIgnoreCase(token.trim());
    }

    /** Identifier segments of a location: everything a 1C name cannot contain is a delimiter. */
    private static String[] segments(String text) {
        return text.split("[^\\p{L}\\p{N}_]+");
    }

    /**
     * True when {@code name} appears in {@code text} as a WHOLE identifier segment rather than as a
     * substring: "HTTPСервис.Payments.Модуль" names Payments, "HTTPСервис.Payments_v2.Модуль" does not.
     * Segments are delimited by anything that cannot be part of a 1C identifier, so Cyrillic names
     * work the same as Latin ones.
     */
    public static boolean namesSegment(String text, String name) {
        if (text == null || name == null || name.isEmpty()) {
            return false;
        }
        for (String segment : segments(text)) {
            if (segment.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The severities a caller asked to keep, as an upper-cased set; empty means "every severity".
     *
     * <p>Accepts a list, because one severity is not enough to answer the question the tool exists
     * for. A call generating code wants what BLOCKS the result, and that is not one grade: EDT
     * reports a call to a method nobody declares as a WARNING, so `severity=ERROR` hides it and
     * the report reads clean while the code is broken. Separators are commas, semicolons and
     * spaces, so both `ERROR,WARNING` and `ERROR WARNING` work; an empty or blank value keeps
     * everything, the same as passing nothing.
     *
     * @param severity one severity, or several separated by commas, semicolons or spaces
     */
    public static java.util.Set<String> severities(String severity) {
        java.util.Set<String> kept = new java.util.LinkedHashSet<>();
        if (severity == null || severity.isBlank()) {
            return kept;
        }
        for (String part : severity.split("[,;\\s]+")) {
            String value = part.trim().toUpperCase();
            if (!value.isEmpty()) {
                kept.add(value);
            }
        }
        return kept;
    }

    /** Whether a problem of this severity is kept; an empty set keeps every one of them. */
    public static boolean matchesSeverity(String severity, java.util.Set<String> kept) {
        if (kept == null || kept.isEmpty()) {
            return true;
        }
        return severity != null && kept.contains(severity.trim().toUpperCase());
    }

    /** Lower-cased, forward-slashed form used for every comparison here. */
    private static String normalize(String value) {
        return (value == null) ? "" : value.replace('\\', '/').trim().toLowerCase();
    }
}
