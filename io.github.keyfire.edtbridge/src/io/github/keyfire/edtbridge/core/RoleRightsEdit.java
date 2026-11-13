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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The requested values of rights of one role on one object, checked against the rights the object
 * has, and the plan of what changes.
 *
 * A right is named in English or in Russian, the way EDT shows it, and case does not matter. The
 * value is {@code true} or {@code false}; a role keeps no third value a caller could ask for.
 */
public final class RoleRightsEdit {
    private RoleRightsEdit() { }

    /** A right as EDT names it: {@code Read} and {@code Чтение}. */
    public record RightName(String name, String nameRu) { }

    /** One planned change: {@code before} is null when the role has no value for the right yet. */
    public record Change(String right, Boolean before, boolean after) { }

    /** The requested values in the caller's order; every value must be a boolean. */
    public static LinkedHashMap<String, Boolean> requested(Map<String, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("rights must name at least one right, such as {\"Read\": true}");
        }
        LinkedHashMap<String, Boolean> values = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            if (name.isEmpty()) throw new IllegalArgumentException("rights contains an empty right name");
            if (!(entry.getValue() instanceof Boolean value)) {
                throw new IllegalArgumentException("right " + name + " must be true or false, got: " + entry.getValue());
            }
            values.put(name, value);
        }
        return values;
    }

    /** The English name of the requested right, or a refusal that names every right of the object. */
    public static String canonical(String requested, Collection<RightName> allowed, String object) {
        String wanted = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
        for (RightName right : allowed) {
            if (wanted.equals(lower(right.name())) || wanted.equals(lower(right.nameRu()))) return right.name();
        }
        List<String> names = new ArrayList<>();
        for (RightName right : allowed) names.add(right.name());
        throw new IllegalArgumentException("unknown right of " + object + ": " + requested + "; allowed: "
                + (names.isEmpty() ? "nothing" : String.join(", ", names)));
    }

    /** The requested values under their English names; two spellings of one right are refused. */
    public static LinkedHashMap<String, Boolean> resolve(Map<String, Boolean> requested,
            Collection<RightName> allowed, String object) {
        LinkedHashMap<String, Boolean> resolved = new LinkedHashMap<>();
        Map<String, String> spelling = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : requested.entrySet()) {
            String name = canonical(entry.getKey(), allowed, object);
            String earlier = spelling.putIfAbsent(name, entry.getKey());
            if (earlier != null) {
                throw new IllegalArgumentException("right " + name + " of " + object + " is requested twice: "
                        + earlier + ", " + entry.getKey());
            }
            resolved.put(name, entry.getValue());
        }
        return resolved;
    }

    /** The changes in the requested order; a right that already has the value is left out. */
    public static List<Change> plan(Map<String, Boolean> requested, Map<String, Boolean> current) {
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : requested.entrySet()) {
            Boolean before = current == null ? null : current.get(entry.getKey());
            if (!Objects.equals(before, entry.getValue())) {
                changes.add(new Change(entry.getKey(), before, entry.getValue()));
            }
        }
        return changes;
    }

    /** Why a right with a data access restriction is not changed. */
    public static String restrictedRefusal(String right, String object, String role) {
        return right + " of " + object + " in " + role + " carries a data access restriction (RLS); "
                + "the bridge does not change such rights, change it in EDT";
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
