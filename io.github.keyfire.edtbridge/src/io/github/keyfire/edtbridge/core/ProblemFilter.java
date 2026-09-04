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
 * <p>That fallback is where a live check caught a defect worth keeping a test for: matching the name
 * as a plain substring made a request for one object return the problems of a differently-named
 * neighbour (Payments also matches Payments_v2). The name must match a whole identifier segment.
 *
 * <p>No EDT or Eclipse types here on purpose - it compiles and is tested without the SDK.
 */
public final class ProblemFilter {

    private ProblemFilter() {
    }

    /**
     * Whether a problem at {@code resource} is in scope.
     *
     * @param resource   the problem's resource - a project-relative path, or an object presentation
     * @param pathPrefix project-relative path or folder prefix to keep, or null
     * @param nameToken  object name to accept in a presentation, or null
     */
    public static boolean matchesLocation(String resource, String pathPrefix, String nameToken) {
        String value = normalize(resource);
        if (pathPrefix != null && !pathPrefix.isBlank() && !value.isEmpty()
                && value.startsWith(normalize(pathPrefix))) {
            return true;
        }
        return nameToken != null && !nameToken.isBlank()
                && namesSegment(value, nameToken.trim().toLowerCase());
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
        for (String segment : text.split("[^\\p{L}\\p{N}_]+")) {
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
