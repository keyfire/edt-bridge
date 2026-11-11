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

import java.util.Collection;

/**
 * Refusal texts of the write tools that name what would have been accepted.
 *
 * A refusal that only repeats the rejected spelling leaves the caller guessing the next one. Every
 * text built here ends with the accepted values, so one failed call is enough to correct the request.
 */
public final class AllowedValues {
    private AllowedValues() { }

    /** A value outside an enumeration: {@code unknown <key> value: <value>; allowed: A, B}. */
    public static String unknownValue(String key, Object value, Collection<String> allowed) {
        return "unknown " + key + " value: " + value + "; allowed: " + list(allowed);
    }

    /** A property name the target does not take: {@code unsupported <what>: <name>; supported here: a, b}. */
    public static String unsupportedName(String what, String name, Collection<String> supported) {
        return "unsupported " + what + ": " + name + "; supported here: " + list(supported);
    }

    /** A known property that the target lacks: {@code <name> is not available on <target>; available here: a, b}. */
    public static String unavailable(String name, String target, Collection<String> available) {
        return name + " is not available on " + target + "; available here: " + list(available);
    }

    private static String list(Collection<String> values) {
        return values == null || values.isEmpty() ? "nothing" : String.join(", ", values);
    }
}
