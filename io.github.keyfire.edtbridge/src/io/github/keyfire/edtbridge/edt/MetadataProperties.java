package io.github.keyfire.edtbridge.edt;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import io.github.keyfire.edtbridge.core.AllowedValues;

/** Scalar and Russian-localized metadata properties shared by the supported write tools. */
final class MetadataProperties {
    private final List<String> names;

    MetadataProperties(String... names) {
        this.names = List.of(names);
    }

    List<String> available(EClass type) {
        return names.stream().filter(name -> feature(type, name) != null).toList();
    }

    private EStructuralFeature feature(EClass type, String name) {
        if (!names.contains(name)) return null;
        var feature = type.getEStructuralFeature(name.endsWith("Ru") ? name.substring(0, name.length() - 2) : name);
        return feature != null && feature.isChangeable()
                && (feature.isMany() == isLocalized(name)) ? feature : null;
    }

    private static boolean isLocalized(String name) {
        return name.endsWith("Ru") || name.equals("format") || name.equals("editFormat");
    }

    Map<EStructuralFeature, Object> validate(EClass type, Map<String, Object> requested, String category) {
        Map<EStructuralFeature, Object> result = new LinkedHashMap<>();
        requested.forEach((name, value) -> {
            var feature = feature(type, name);
            if (feature == null) {
                String message = names.contains(name)
                        ? AllowedValues.unavailable(name, type.getName(), available(type))
                        : AllowedValues.unsupportedName(category, name, available(type));
                throw new IllegalArgumentException(message);
            }
            result.put(feature, parse(name, feature, value));
        });
        return result;
    }

    private static Object parse(String name, EStructuralFeature feature, Object value) {
        if (feature.getEType() instanceof EEnum enumeration) {
            if (value instanceof String text) {
                for (var literal : enumeration.getELiterals()) {
                    if (literal.getLiteral().equalsIgnoreCase(text)) return literal.getInstance();
                }
            }
            throw new IllegalArgumentException(name + " must be one of " + String.join(", ", values(enumeration)));
        }
        Class<?> javaType = feature.getEType().getInstanceClass();
        if (javaType == boolean.class || javaType == Boolean.class) {
            if (value instanceof Boolean) return value;
            throw new IllegalArgumentException(name + " must be boolean");
        }
        if (javaType == int.class || javaType == Integer.class) {
            if (value instanceof Number number) {
                try {
                    int integer = new BigDecimal(number.toString()).intValueExact();
                    if (integer >= 0) return integer;
                } catch (NumberFormatException | ArithmeticException invalid) {
                    // The same validation message covers fractional and out-of-range inputs.
                }
            }
            throw new IllegalArgumentException(name + " must be a nonnegative integer");
        }
        if (value instanceof String) return value;
        throw new IllegalArgumentException(name + " must be a string");
    }

    static Object display(Object value) {
        return value instanceof Enumerator enumeration ? enumeration.getLiteral() : value;
    }

    static Object current(EObject object, EStructuralFeature feature) {
        return feature.isMany() ? text(object, feature).get("ru") : display(object.eGet(feature));
    }

    static void apply(EObject object, EStructuralFeature feature, Object value) {
        if (feature.isMany()) {
            if (((String) value).isEmpty()) text(object, feature).removeKey("ru");
            else text(object, feature).put("ru", (String) value);
        } else {
            object.eSet(feature, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static EMap<String, String> text(EObject object, EStructuralFeature feature) {
        return (EMap<String, String>) object.eGet(feature);
    }

    Map<String, Object> current(EObject object) {
        var result = new LinkedHashMap<String, Object>();
        for (String name : available(object.eClass())) {
            Object value = current(object, feature(object.eClass(), name));
            if (value != null) result.put(name, value);
        }
        return result;
    }

    List<Map<String, Object>> reference(EObject object) {
        return available(object.eClass()).stream().map(name -> {
            var feature = feature(object.eClass(), name);
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", name);
            if (feature.getEType() instanceof EEnum enumeration) {
                entry.put("type", "enum");
                entry.put("values", values(enumeration));
            } else if (isLocalized(name)) {
                entry.put("type", "string");
                entry.put("note", "Russian text. An empty string removes the Russian value.");
            } else {
                Class<?> javaType = feature.getEType().getInstanceClass();
                entry.put("type", javaType == boolean.class || javaType == Boolean.class ? "boolean"
                        : javaType == int.class || javaType == Integer.class ? "integer" : "string");
            }
            Object current = current(object, feature);
            if (current != null) entry.put("current", current);
            return (Map<String, Object>) entry;
        }).toList();
    }

    private static List<String> values(EEnum enumeration) {
        return enumeration.getELiterals().stream().map(literal -> literal.getLiteral()).toList();
    }
}
