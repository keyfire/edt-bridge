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
 * The two names a validation check goes by. A problem carries either the id of the check
 * ({@code module-unused-local-variable}) or a short code the project's check settings assign to it
 * ({@code SU68}). The description resources are filed by id, so a short code has to be resolved
 * through the project before its description can be read.
 */
public final class CheckCodes {

    private CheckCodes() {
    }

    /** Whether the text is a short code: two to four letters followed by one to four digits. */
    public static boolean isShortCode(String text) {
        return text != null && text.trim().matches("[A-Za-z]{2,4}\\d{1,4}");
    }

    /**
     * The id as a problem of the standards framework reports it: {@code contributor:check}. Without a
     * contributor the bare check id is returned.
     */
    public static String qualified(String contributor, String checkId) {
        if (checkId == null || checkId.isBlank()) {
            return null;
        }
        String check = checkId.trim();
        return contributor == null || contributor.isBlank() ? check : contributor.trim() + ":" + check;
    }

    /** The check id without its contributor: the part after the last colon. */
    public static String bare(String qualified) {
        if (qualified == null) {
            return null;
        }
        String text = qualified.trim();
        int colon = text.lastIndexOf(':');
        return colon < 0 ? text : text.substring(colon + 1).trim();
    }
}
