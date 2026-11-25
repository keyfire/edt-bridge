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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names around copying a managed form: which form the caller means and what the copy is called.
 */
public final class FormCopyPaths {

    private static final Pattern OWNER_FORM = Pattern.compile("([A-Za-z]+\\.[^.\\s]+)\\.Form\\.([^.\\s]+)(?:\\.Form)?");
    private static final Pattern COMMON_FORM = Pattern.compile("CommonForm\\.([^.\\s]+)(?:\\.Form)?");
    /** A 1C identifier: letters (Latin or Cyrillic), digits and underscore, not starting with a digit. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-zА-Яа-яЁё_][A-Za-zА-Яа-яЁё0-9_]*");

    private FormCopyPaths() {
    }

    /** A form to copy: the FQN of the object that owns it ({@code null} for a common form) and its name. */
    public record Source(String ownerFqn, String formName) {

        public boolean common() {
            return ownerFqn == null;
        }

        /** The FQN the caller names the form by: {@code Catalog.X.Form.Y} or {@code CommonForm.Y}. */
        public String fqn() {
            return common() ? "CommonForm." + formName : ownerFqn + ".Form." + formName;
        }
    }

    /**
     * Read {@code <Type>.<Owner>.Form.<Name>} or {@code CommonForm.<Name>}, with English type names as
     * everywhere in the bridge. Accepts the trailing {@code .Form} of the form content FQN.
     * Returns {@code null} for anything else.
     */
    public static Source parse(String fqn) {
        if (fqn == null) return null;
        String text = fqn.trim();
        Matcher common = COMMON_FORM.matcher(text);
        if (common.matches()) return new Source(null, common.group(1));
        Matcher owned = OWNER_FORM.matcher(text);
        if (owned.matches()) return new Source(owned.group(1), owned.group(2));
        return null;
    }

    /**
     * The name of the copy: the requested one, or the source form's name when none is given. A form an
     * extension adds to an adopted object belongs to the extension, so it takes the extension prefix;
     * pass {@code null} as the prefix anywhere else.
     */
    public static String copyName(String requested, String sourceName, String prefix) {
        String name = (requested == null || requested.isBlank()) ? sourceName : requested.trim();
        return NewObjects.extensionName(name, prefix);
    }

    /** Whether the name can name a form: a 1C identifier. */
    public static boolean isIdentifier(String name) {
        return name != null && IDENTIFIER.matcher(name).matches();
    }
}
