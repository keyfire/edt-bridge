package io.github.keyfire.edtbridge.edt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.EReference;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import io.github.keyfire.edtbridge.core.AllowedValues;

/** Typed field lists and the shared attribute/resource/dimension namespace. */
final class MetadataFields {
    private MetadataFields() {}

    record Field(String kind, MdObject value) {
        String fqn(String ownerFqn) {
            String segment = switch (kind) {
                case "attribute" -> "Attribute";
                case "resource" -> "Resource";
                case "dimension" -> "Dimension";
                default -> throw new IllegalArgumentException("unsupported field kind: " + kind);
            };
            return ownerFqn + "." + segment + "." + value.getName();
        }
    }

    static String kind(String raw) {
        String kind = raw == null ? "attribute" : raw.trim().toLowerCase(Locale.ROOT);
        if (!List.of("attribute", "resource", "dimension").contains(kind)) {
            throw new IllegalArgumentException("kind must be attribute, resource or dimension");
        }
        return kind;
    }

    private static String featureName(String raw) {
        return switch (kind(raw)) {
            case "attribute" -> "attributes";
            case "resource" -> "resources";
            default -> "dimensions";
        };
    }

    static EReference reference(EObject owner, String kind) {
        if (owner == null) throw new IllegalArgumentException("owner object disappeared");
        var feature = owner.eClass().getEStructuralFeature(featureName(kind));
        if (!(feature instanceof EReference ref) || !ref.isMany() || !ref.isContainment()) {
            throw new IllegalArgumentException("owner does not support field kind " + kind(kind)
                    + ": " + owner.eClass().getName());
        }
        return ref;
    }

    @SuppressWarnings("unchecked")
    private static List<MdObject> fields(EObject owner, String kind) {
        Object raw = owner.eGet(reference(owner, kind));
        if (!(raw instanceof List<?> values)) throw new IllegalArgumentException("field list cannot be read: " + kind);
        for (Object value : values) {
            if (!(value instanceof MdObject md) || !(value instanceof BasicFeature) || md.eIsProxy()) {
                throw new IllegalArgumentException("field list contains an unresolved or unsupported member: " + kind);
            }
        }
        return (List<MdObject>) values;
    }

    static List<Field> matches(EObject owner, String name) {
        if (owner == null) throw new IllegalArgumentException("owner object disappeared");
        var found = new ArrayList<Field>();
        for (String kind : List.of("attribute", "resource", "dimension")) {
            if (owner.eClass().getEStructuralFeature(featureName(kind)) == null) continue;
            for (MdObject member : fields(owner, kind)) {
                if (name.equalsIgnoreCase(member.getName())) found.add(new Field(kind, member));
            }
        }
        return List.copyOf(found);
    }

    static MdObject find(EObject owner, String kind, String name) {
        MdObject found = null;
        for (MdObject member : fields(owner, kind)) {
            if (!name.equalsIgnoreCase(member.getName())) continue;
            if (found != null) throw new IllegalArgumentException("ambiguous " + kind(kind) + " name: " + name);
            found = member;
        }
        return found;
    }

    static MdObject add(EObject owner, String kind, String name, TypeDescription type, String synonym, String comment) {
        EReference reference = reference(owner, kind);
        if (!matches(owner, name).isEmpty()) throw new IllegalArgumentException("field already exists: " + name);
        EObject value = reference.getEReferenceType().getEPackage().getEFactoryInstance().create(reference.getEReferenceType());
        if (!(value instanceof MdObject member) || !(value instanceof BasicFeature field)) {
            throw new IllegalArgumentException("unsupported field type: " + reference.getEReferenceType().getName());
        }
        member.setName(name);
        member.setUuid(UUID.randomUUID());
        field.setType(type);
        if (synonym != null && !synonym.isBlank()) member.getSynonym().put("ru", synonym);
        if (comment != null && !comment.isBlank()) member.setComment(comment);
        fields(owner, kind).add(member);
        return member;
    }

    static MdObject modify(EObject owner, String kind, String name, TypeDescription type, String synonym, String comment) {
        MdObject member = find(owner, kind, name);
        if (member == null) throw new IllegalArgumentException(kind(kind) + " disappeared: " + name);
        if (type != null) ((BasicFeature) member).setType(type);
        if (synonym != null) {
            if (synonym.isBlank()) member.getSynonym().removeKey("ru");
            else member.getSynonym().put("ru", synonym);
        }
        if (comment != null) member.setComment(comment);
        return member;
    }

    static Map<EStructuralFeature, Object> validateOptions(EClass type, Map<String, Object> options) {
        Map<EStructuralFeature, Object> changes = new LinkedHashMap<>();
        for (var entry : options.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (!Set.of("use", "multiLine").contains(key)) {
                throw new IllegalArgumentException(AllowedValues.unsupportedName("field option", key, optionNames(type)));
            }
            EStructuralFeature feature = type.getEStructuralFeature(key);
            if (feature == null || feature.isMany() || !feature.isChangeable()) {
                throw new IllegalArgumentException(AllowedValues.unavailable(key, type.getName(), optionNames(type)));
            }
            if ("multiLine".equals(key)) {
                if (!(value instanceof Boolean)) throw new IllegalArgumentException("multiLine must be boolean");
                changes.put(feature, value);
            } else {
                if (!(value instanceof String) || !(feature.getEType() instanceof EEnum enumeration)) {
                    throw new IllegalArgumentException("use requires a hierarchical field and an enum string");
                }
                var literal = enumeration.getELiterals().stream().filter(each ->
                        each.getLiteral().equalsIgnoreCase((String) value)).findFirst().orElseThrow(() ->
                                new IllegalArgumentException("use must be ForFolder, ForItem or ForFolderAndItem"));
                changes.put(feature, literal.getInstance());
            }
        }
        return changes;
    }

    private static List<String> optionNames(EClass type) {
        List<String> names = new ArrayList<>();
        for (String key : List.of("use", "multiLine")) {
            EStructuralFeature feature = type.getEStructuralFeature(key);
            if (feature != null && !feature.isMany() && feature.isChangeable()) names.add(key);
        }
        return names;
    }

    /** The optional settings this field takes, with their types, accepted values and current values. */
    static List<Map<String, Object>> optionReference(EObject field) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Object> current = readOptions(field);
        for (String key : optionNames(field.eClass())) {
            EStructuralFeature feature = field.eClass().getEStructuralFeature(key);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", key);
            if (feature.getEType() instanceof EEnum enumeration) {
                entry.put("type", "enum");
                entry.put("values", enumeration.getELiterals().stream().map(each -> each.getLiteral()).toList());
            } else {
                entry.put("type", "boolean");
            }
            entry.put("current", current.get(key));
            result.add(entry);
        }
        return result;
    }

    static void applyOptions(EObject field, Map<String, Object> options) {
        var changes = validateOptions(field.eClass(), options);
        changes.forEach(field::eSet);
    }

    static Map<String, Object> readOptions(EObject field) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("use", "multiLine")) {
            var feature = field.eClass().getEStructuralFeature(key);
            if (feature == null) continue;
            Object value = field.eGet(feature);
            result.put(key, value instanceof Enumerator enumeration ? enumeration.getLiteral() : value);
        }
        return result;
    }

    static String missingMessage(EObject owner, String ownerFqn, String kind, String name) {
        var message = new StringBuilder(kind(kind) + " not found: " + name);
        for (Field field : matches(owner, name)) {
            String fqn = field.fqn(ownerFqn);
            message.append("; found ").append(field.kind()).append(" at ").append(fqn)
                    .append("; use edt_modify_attribute kind=").append(field.kind())
                    .append(" to modify, or edt_delete_object / edt_rename with targetFqn=").append(fqn);
        }
        return message.toString();
    }
}