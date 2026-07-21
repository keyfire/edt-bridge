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

    /** Lower-cased, forward-slashed form used for every comparison here. */
    private static String normalize(String value) {
        return (value == null) ? "" : value.replace('\\', '/').trim().toLowerCase();
    }
}
