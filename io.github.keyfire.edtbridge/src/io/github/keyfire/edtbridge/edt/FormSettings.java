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
package io.github.keyfire.edtbridge.edt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EStructuralFeature;
import com._1c.g5.v8.dt.form.model.Form;
import io.github.keyfire.edtbridge.core.AllowedValues;
import io.github.keyfire.edtbridge.core.FormPropertyValues;
import io.github.keyfire.edtbridge.edt.MetadataSettingsGateway.Change;

/** Typed root settings of a managed {@link Form}, validated as one patch before any change. */
final class FormSettings {
    private static final Set<String> BOOLS = Set.of("autoTitle", "showTitle", "showCloseButton",
            "allowFormCustomize");
    private static final Set<String> ENUMS = Set.of("windowOpeningMode", "scaleVariant");
    private static final Set<String> INTS = Set.of("width", "height");
    private static final List<String> NAMES = List.of("allowFormCustomize", "autoTitle", "height",
            "scaleVariant", "showCloseButton", "showTitle", "titleRu", "width", "windowOpeningMode");

    private record Assignment(String name, EStructuralFeature feature, Object value) { }

    private FormSettings() { }

    static Map<String, Change> describe(Form form, Map<String, Object> properties, boolean use851Title) {
        Map<String, Change> changes = new LinkedHashMap<>();
        for (Assignment assignment : prepare(form, properties)) {
            Object before = readValue(form, assignment.name);
            Object after = "titleRu".equals(assignment.name)
                    ? (((String) assignment.value).isBlank() ? null : assignment.value)
                    : printable(assignment.value);
            if (!Objects.equals(before, after)) {
                changes.put(assignment.name, new Change(before, after));
            }
        }
        return changes;
    }

    static void apply(Form form, Map<String, Object> properties, boolean use851Title) {
        for (Assignment assignment : prepare(form, properties)) {
            if ("titleRu".equals(assignment.name)) {
                String text = (String) assignment.value;
                if (text.isBlank()) form.getTitle().removeKey("ru");
                else form.getTitle().put("ru", text);
                continue;
            }
            if ("showTitle".equals(assignment.name)) {
                EStructuralFeature current = form.eClass().getEStructuralFeature("showTitle851");
                if (use851Title && current != null && current.getEType() instanceof EEnum enumType) {
                    EEnumLiteral literal = matchLiteral(enumType, assignment.value.toString());
                    if (literal != null) {
                        form.eUnset(assignment.feature);
                        form.eSet(current, literal.getInstance());
                        continue;
                    }
                }
                if (current != null) form.eUnset(current);
                form.eSet(assignment.feature, assignment.value);
                continue;
            }
            form.eSet(assignment.feature, assignment.value);
        }
    }

    static List<Map<String, Object>> reference(Form form) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : NAMES) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            if ("titleRu".equals(name)) {
                entry.put("type", "string");
                entry.put("note", "Russian form title; an empty string clears it");
            } else {
                EStructuralFeature feature = form.eClass().getEStructuralFeature(name);
                if (feature == null || !feature.isChangeable() || feature.isMany()) continue;
                if (BOOLS.contains(name)) {
                    entry.put("type", "boolean");
                } else if (ENUMS.contains(name) && feature.getEType() instanceof EEnum enumType) {
                    entry.put("type", "enum");
                    entry.put("values", literals(enumType));
                } else if (INTS.contains(name)) {
                    entry.put("type", "integer");
                    entry.put("note", "nonnegative");
                } else {
                    continue;
                }
            }
            Object current = readValue(form, name);
            if (current != null) entry.put("current", current);
            result.add(entry);
        }
        return result;
    }

    private static List<Assignment> prepare(Form form, Map<String, Object> properties) {
        if (form == null) throw new IllegalArgumentException("Form is required");
        Map<String, Object> values = properties == null ? Map.of() : properties;
        List<Assignment> patch = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String name = entry.getKey();
            Object raw = entry.getValue();
            if ("titleRu".equals(name)) {
                if (!(raw instanceof String)) throw new IllegalArgumentException("titleRu must be a string");
                patch.add(new Assignment(name, null, raw));
                continue;
            }
            if (!BOOLS.contains(name) && !ENUMS.contains(name) && !INTS.contains(name)) {
                throw new IllegalArgumentException(AllowedValues.unsupportedName("Form property", name, NAMES));
            }
            EStructuralFeature feature = form.eClass().getEStructuralFeature(name);
            if (feature == null || !feature.isChangeable() || feature.isMany()) {
                throw new IllegalArgumentException("Property is not writable in this EDT: " + name);
            }
            Object converted;
            if (BOOLS.contains(name)) {
                if (!(raw instanceof Boolean)) throw new IllegalArgumentException(name + " must be a boolean");
                converted = raw;
            } else if (INTS.contains(name)) {
                converted = FormPropertyValues.parseNonNegativeInt(name, raw);
            } else {
                if (!(raw instanceof String) || !(feature.getEType() instanceof EEnum enumType)) {
                    throw new IllegalArgumentException(name + " must be an enum string");
                }
                EEnumLiteral literal = matchLiteral(enumType, (String) raw);
                if (literal == null) {
                    throw new IllegalArgumentException(AllowedValues.unknownValue(name, raw, literals(enumType)));
                }
                converted = literal.getInstance();
            }
            patch.add(new Assignment(name, feature, converted));
        }
        return patch;
    }

    private static Object readValue(Form form, String name) {
        if ("titleRu".equals(name)) {
            String ru = form.getTitle().get("ru");
            return ru == null || ru.isBlank() ? null : ru;
        }
        EStructuralFeature feature = form.eClass().getEStructuralFeature(name);
        if (feature == null) return null;
        if ("showTitle".equals(name)) {
            EStructuralFeature current = form.eClass().getEStructuralFeature("showTitle851");
            if (current != null && form.eIsSet(current)) {
                Object value = form.eGet(current);
                String literal = value instanceof Enumerator enumerator ? enumerator.getLiteral() : String.valueOf(value);
                if ("true".equalsIgnoreCase(literal)) return Boolean.TRUE;
                if ("false".equalsIgnoreCase(literal)) return Boolean.FALSE;
                return literal;
            }
        }
        return printable(form.eGet(feature));
    }

    private static EEnumLiteral matchLiteral(EEnum enumType, String raw) {
        String key = raw.trim();
        for (EEnumLiteral literal : enumType.getELiterals()) {
            if (literal.getLiteral().equalsIgnoreCase(key) || literal.getName().equalsIgnoreCase(key)) {
                return literal;
            }
        }
        return null;
    }

    private static List<String> literals(EEnum enumType) {
        List<String> out = new ArrayList<>();
        for (EEnumLiteral literal : enumType.getELiterals()) out.add(literal.getLiteral());
        return out;
    }

    private static Object printable(Object value) {
        return value instanceof Enumerator enumerator ? enumerator.getLiteral() : value;
    }
}
