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

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure value normalization and modified-form tracking for managed-form edits. Kept free of EDT and
 * Eclipse types so boundary cases run under the plain-JDK test suite.
 */
public final class FormPropertyValues {
    private FormPropertyValues() { }

    /**
     * Normalize a caller's {@code actionPurpose} spelling before matching against EDT's
     * {@code CommandActionPurpose} literals ({@code Usual}, {@code Create}, {@code Finish}).
     * {@code Normal} is accepted as an alias for {@code Usual}.
     */
    public static String normalizeActionPurpose(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";
        if ("Normal".equalsIgnoreCase(trimmed)) return "Usual";
        return trimmed;
    }

    /** Parse a nonnegative integer property value from JSON/Java number input. */
    public static int parseNonNegativeInt(String key, Object value) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(key + " must be a nonnegative integer");
        }
        try {
            int parsed = new BigDecimal(value.toString()).intValueExact();
            if (parsed >= 0) return parsed;
        } catch (ArithmeticException | NumberFormatException ignored) {
        }
        throw new IllegalArgumentException(key + " must be a nonnegative integer");
    }

    /**
     * Normalize an Eclipse full workspace path for a BSL module target passed to EDT's check
     * scheduler (unencoded path with a leading slash, e.g. {@code /Proj/src/.../Module.bsl}).
     * Returns {@code null} when the path is blank or does not name a {@code .bsl} file.
     */
    public static String moduleTargetPath(String fullPath) {
        if (fullPath == null || fullPath.isBlank()) return null;
        String normalized = fullPath.trim().replace('\\', '/');
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        if (!normalized.toLowerCase(java.util.Locale.ROOT).endsWith(".bsl")) return null;
        return normalized;
    }

    /**
     * Forms whose {@code Form.form} changed through the bridge and whose form module should be
     * revalidated on the next unnarrowed {@code edt_project_errors refresh=true}.
     */
    public static final class PendingForms {
        private final Map<String, Set<String>> byProject = new ConcurrentHashMap<>();

        public void mark(String projectName, String formFqn) {
            if (projectName == null || projectName.isBlank() || formFqn == null || formFqn.isBlank()) {
                return;
            }
            byProject.computeIfAbsent(projectName, ignored -> ConcurrentHashMap.newKeySet())
                    .add(formFqn.trim());
        }

        public Set<String> snapshot(String projectName) {
            if (projectName == null || projectName.isBlank()) return Set.of();
            Set<String> set = byProject.get(projectName);
            if (set == null || set.isEmpty()) return Set.of();
            return Collections.unmodifiableSet(new LinkedHashSet<>(set));
        }

        public void acknowledge(String projectName, Collection<String> validated) {
            if (projectName == null || projectName.isBlank() || validated == null || validated.isEmpty()) {
                return;
            }
            Set<String> set = byProject.get(projectName);
            if (set == null) return;
            set.removeAll(validated);
            if (set.isEmpty()) {
                byProject.remove(projectName, set);
            }
        }
    }
}
