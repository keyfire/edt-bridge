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
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Judging the argument names of a call against the names the tool declares.
 *
 * <p>A name the schema does not know used to be dropped without a word, and the caller read the
 * answer as a done deed: a delete asked with {@code deleteContents} left every file on disk and
 * reported success, because the schema spells it {@code deleteContent}. A refusal that names the
 * near miss costs one call; a silent drop costs a wrong belief about the disk.
 *
 * <p>Pure string work, kept out of the gateways so it can be tested without EDT.
 */
public final class ArgumentNames {

    /** Below this length a single edit is already a good part of the word, so only one is forgiven. */
    private static final int SHORT_NAME = 5;

    private ArgumentNames() {
    }

    /** The passed names no declared name answers to, in the order they were passed. */
    public static List<String> unknown(Collection<String> passed, Collection<String> declared) {
        List<String> rest = new ArrayList<>();
        if (passed == null) {
            return rest;
        }
        for (String name : passed) {
            if (name != null && !contains(declared, name)) {
                rest.add(name);
            }
        }
        return rest;
    }

    /**
     * The declared name an unknown one was most likely meant to be, or {@code null}.
     *
     * <p>Differing only in case or in the punctuation between the words is a hit outright; beyond
     * that a couple of edits are forgiven, which covers the plural that is not there
     * ({@code deleteContents}) and the letter typed twice.
     */
    public static String nearest(String name, Collection<String> declared) {
        if (name == null || declared == null) {
            return null;
        }
        String wanted = normalized(name);
        for (String candidate : declared) {
            if (candidate != null && normalized(candidate).equals(wanted)) {
                return candidate;
            }
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        int forgiven = 0;
        for (String candidate : declared) {
            if (candidate == null) {
                continue;
            }
            String plain = normalized(candidate);
            int distance = distance(wanted, plain);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
                // The shorter of the two decides: two edits into a four-letter name is a different
                // name, while two into a fourteen-letter one is still the same word misspelled.
                forgiven = Math.min(wanted.length(), plain.length()) < SHORT_NAME ? 1 : 2;
            }
        }
        return bestDistance <= forgiven ? best : null;
    }

    /**
     * The refusal for a call carrying names the tool does not declare, or {@code null} when every
     * name is known. The text names the near miss when there is one and lists what the tool takes,
     * so the next call can be written from the answer alone.
     */
    public static String refusal(String tool, Collection<String> passed, Collection<String> declared) {
        List<String> unknown = unknown(passed, declared);
        if (unknown.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        text.append(tool).append(": ");
        text.append(unknown.size() == 1 ? "unknown argument " : "unknown arguments ");
        for (int i = 0; i < unknown.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append('\'').append(unknown.get(i)).append('\'');
            String near = nearest(unknown.get(i), declared);
            if (near != null) {
                text.append(" (did you mean '").append(near).append("'?)");
            }
        }
        text.append(". Nothing was done - an argument that is not in the schema would be dropped, "
                + "and the answer would read as success. Arguments this tool takes: ");
        text.append(sorted(declared));
        return text.toString();
    }

    private static boolean contains(Collection<String> declared, String name) {
        if (declared == null) {
            return false;
        }
        for (String candidate : declared) {
            if (name.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String sorted(Collection<String> declared) {
        if (declared == null || declared.isEmpty()) {
            return "(none)";
        }
        List<String> names = new ArrayList<>(declared);
        names.sort(String::compareTo);
        return String.join(", ", names);
    }

    /** Case and the punctuation between words carry no meaning here, so they are dropped. */
    private static String normalized(String name) {
        StringBuilder plain = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                plain.append(Character.toLowerCase(c));
            }
        }
        return plain.toString().toLowerCase(Locale.ROOT);
    }

    /** Levenshtein distance - the number of single-character edits between the two strings. */
    private static int distance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitute = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitute, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
