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
import java.util.Comparator;
import java.util.List;

/**
 * Finding the tool names a misspelled one was most likely meant to be.
 *
 * <p>A request for the description of a tool that does not exist is answered with a refusal, and
 * the refusal is only useful when it carries the names to try next. Unlike an argument name, a
 * tool name has several plausible neighbours - the family of form tools differs in one word - so
 * the answer is a short list rather than one guess.
 *
 * <p>Pure string work, kept out of the server so it can be tested without EDT.
 */
public final class ToolNames {

    /** A fragment shorter than this is contained in too many names to be a hint. */
    private static final int SHORT_FRAGMENT = 4;

    /** How many names a refusal offers: enough for a tool family, short enough to read. */
    private static final int SUGGESTIONS = 5;

    private ToolNames() {
    }

    /**
     * The known names closest to the given one, nearest first, at most {@code limit} of them.
     *
     * <p>A name counts as close when a few edits separate the two, or when one contains the other:
     * {@code tool_help} finds {@code edt_tool_help}, {@code edt_form} finds the form tools. Case
     * and the punctuation between words are not compared.
     */
    public static List<String> nearest(String name, Collection<String> known, int limit) {
        List<String> found = new ArrayList<>();
        if (name == null || known == null || limit <= 0) {
            return found;
        }
        String wanted = ArgumentNames.normalized(name);
        if (wanted.isEmpty()) {
            return found;
        }
        int forgiven = Math.max(2, wanted.length() / 4);
        List<Scored> scored = new ArrayList<>();
        for (String candidate : known) {
            if (candidate == null) {
                continue;
            }
            String plain = ArgumentNames.normalized(candidate);
            int distance = ArgumentNames.distance(wanted, plain);
            boolean contained = wanted.length() >= SHORT_FRAGMENT
                    && (plain.contains(wanted) || wanted.contains(plain));
            if (contained || distance <= forgiven) {
                scored.add(new Scored(candidate, distance));
            }
        }
        scored.sort(Comparator.comparingInt((Scored s) -> s.score).thenComparing(s -> s.name));
        for (Scored hit : scored) {
            if (found.size() == limit) {
                break;
            }
            found.add(hit.name);
        }
        return found;
    }

    /**
     * The refusal for a name no tool answers to. It names the nearest tools when there are any,
     * so the next call can be written from the answer alone.
     */
    public static String unknownRefusal(String tool, String name, Collection<String> known) {
        List<String> near = nearest(name, known, SUGGESTIONS);
        StringBuilder text = new StringBuilder();
        text.append(tool).append(": no tool is named '").append(name).append("'. ");
        if (near.isEmpty()) {
            text.append("No tool name is close to it.");
        } else {
            text.append("Nearest names: ").append(String.join(", ", near)).append('.');
        }
        text.append(" tools/list names the tools that can be called.");
        return text.toString();
    }

    private static final class Scored {
        final String name;
        final int score;

        Scored(String name, int score) {
            this.name = name;
            this.score = score;
        }
    }
}
