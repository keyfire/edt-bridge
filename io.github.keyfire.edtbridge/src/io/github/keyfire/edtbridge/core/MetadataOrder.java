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
import java.util.List;
import java.util.Locale;

/** Preserve an existing alphabetic list without reordering an intentionally arranged one. */
public final class MetadataOrder {
    private MetadataOrder() { }
    public static int insertionIndex(List<String> names, String added) {
        if (added == null) return names.size();
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i) == null || i > 0 && compare(names.get(i - 1), names.get(i)) > 0) {
                return names.size();
            }
        }
        for (int i = 0; i < names.size(); i++) {
            if (compare(added, names.get(i)) < 0) return i;
        }
        return names.size();
    }
    private static int compare(String left, String right) {
        return left.toLowerCase(Locale.ROOT).compareTo(right.toLowerCase(Locale.ROOT));
    }
}
