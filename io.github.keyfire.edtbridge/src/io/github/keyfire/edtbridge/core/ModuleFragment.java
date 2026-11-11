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
 * Replace one exact fragment of a module's text: the part of a module that no method tool
 * reaches - compilation directives, module variables, regions, the preprocessor block around
 * them. The fragment must occur exactly once, so the edit cannot land somewhere the caller did
 * not look.
 *
 * <p>A caller usually writes the fragment with {@code \n} line breaks while a module on disk may
 * use {@code \r\n}; when the literal fragment is absent, its line breaks are adapted to the
 * module's, and the replacement follows the same breaks.
 */
public final class ModuleFragment {

    /** Where the fragment was found and the module after the replacement. */
    public record Edit(String result, int offset, int line, String oldText, String newText) {}

    private ModuleFragment() {
    }

    public static Edit replace(String source, String oldText, String newText) {
        if (source == null) {
            throw new IllegalArgumentException("the module text is unavailable");
        }
        if (oldText == null || oldText.isEmpty()) {
            throw new IllegalArgumentException("oldText is required: the exact fragment to replace,"
                    + " with enough surrounding lines to occur once");
        }
        if (newText == null) {
            throw new IllegalArgumentException("newText is required; an empty string deletes the fragment");
        }
        String from = oldText;
        String to = newText;
        if (count(source, from) == 0 && source.contains("\r\n") && !from.contains("\r\n")) {
            from = from.replace("\n", "\r\n");
            to = to.replace("\r\n", "\n").replace("\n", "\r\n");
        }
        int matches = count(source, from);
        if (matches == 0) {
            throw new IllegalArgumentException("oldText was not found in the module; read the module with"
                    + " edt_module_text and copy the fragment exactly, including indentation");
        }
        if (matches > 1) {
            throw new IllegalArgumentException("oldText occurs " + matches + " times in the module;"
                    + " add surrounding lines until it occurs once");
        }
        int offset = source.indexOf(from);
        String result = source.substring(0, offset) + to + source.substring(offset + from.length());
        return new Edit(result, offset, lineOf(source, offset), from, to);
    }

    /** One replacement of a batch: the exact fragment and the text that takes its place. */
    public record Change(String oldText, String newText) {}

    /**
     * Apply several replacements in order, each to the text the previous one left. A region or a
     * preprocessor block opens in one place and closes in another; only the batch as a whole keeps
     * the module parseable, so the caller checks the parse once, after the last change.
     */
    public static Edit replaceAll(String source, java.util.List<Change> changes) {
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("at least one replacement is required");
        }
        String text = source;
        Edit first = null;
        int index = 0;
        for (Change change : changes) {
            index++;
            Edit edit;
            try {
                edit = replace(text, change.oldText(), change.newText());
            } catch (IllegalArgumentException refused) {
                throw changes.size() == 1 ? refused
                        : new IllegalArgumentException("replacement " + index + ": " + refused.getMessage());
            }
            if (first == null) {
                first = edit;
            }
            text = edit.result();
        }
        return new Edit(text, first.offset(), first.line(), first.oldText(), first.newText());
    }

    static int count(String text, String fragment) {
        int found = 0;
        for (int at = text.indexOf(fragment); at >= 0; at = text.indexOf(fragment, at + 1)) {
            found++;
        }
        return found;
    }

    static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
