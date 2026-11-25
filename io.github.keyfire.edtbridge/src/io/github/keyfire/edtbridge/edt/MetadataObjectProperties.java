package io.github.keyfire.edtbridge.edt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.metadata.mdclass.BasicDbObject;
import com._1c.g5.v8.dt.metadata.mdclass.Constant;
import com._1c.g5.v8.dt.mcore.Field;
import com._1c.g5.v8.dt.mcore.TypeDescription;

import io.github.keyfire.edtbridge.edt.MetadataSettingsGateway.Change;

/** The supported metadata object properties and their values in the live EDT model. */
final class MetadataObjectProperties {
    private static final String TYPE = "type";
    private static final String DATA_LOCK_FIELDS = "dataLockFields";
    private static final MetadataProperties SCALARS = new MetadataProperties(
            "rootURL", "includeInCommandInterface", "methodName",
            "codeType", "codeLength", "numberType", "numberLength", "posting",
            "dataLockControlMode", "createOnInput",
            "objectPresentationRu", "extendedObjectPresentationRu", "listPresentationRu",
            "extendedListPresentationRu", "recordPresentationRu", "extendedRecordPresentationRu",
            "extendedPresentationRu");

    private MetadataObjectProperties() {}

    static boolean supports(EObject object) {
        return object != null && !SCALARS.available(object.eClass()).isEmpty();
    }

    static List<Map<String, Object>> reference(EObject object) {
        var properties = new ArrayList<>(SCALARS.reference(object));
        if (object instanceof Constant constant) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", TYPE);
            entry.put("type", "type");
            entry.put("note", "Value type in the grammar of edt_add_attribute.");
            entry.put("current", GatewaySupport.renderType(constant.getType()));
            properties.add(entry);
        }
        if (object instanceof BasicDbObject databaseObject) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", DATA_LOCK_FIELDS);
            entry.put("type", "array");
            entry.put("items", "string");
            entry.put("note", "Field names in this object. Replaces the whole list. An empty array clears it.");
            entry.put("values", databaseObject.getFields().stream().filter(field -> !field.eIsProxy())
                    .map(Field::getName).filter(Objects::nonNull).toList());
            entry.put("current", lockNames(databaseObject.getDataLockFields()));
            properties.add(entry);
        }
        return properties;
    }

    static Map<String, Object> current(EObject object) {
        return SCALARS.current(object);
    }

    static Map<String, Change> update(EObject object, Map<String, Object> properties,
            Function<String, TypeDescription> resolveType, boolean apply) {
        var scalars = new LinkedHashMap<>(properties);
        Object requestedType = scalars.remove(TYPE);
        Object requestedLocks = scalars.remove(DATA_LOCK_FIELDS);
        var scalarValues = SCALARS.validate(object.eClass(), scalars, "object property");
        Constant constant = object instanceof Constant value ? value : null;
        TypeDescription newType = null;
        if (properties.containsKey(TYPE)) {
            if (constant == null) throw new IllegalArgumentException("type is only supported on Constant");
            if (!(requestedType instanceof String spec) || spec.isBlank()) {
                throw new IllegalArgumentException("type must be a value type string, as in edt_add_attribute");
            }
            newType = resolveType.apply(spec);
            if (newType == null) throw new IllegalArgumentException("invalid constant type: " + spec);
        }
        BasicDbObject databaseObject = object instanceof BasicDbObject value ? value : null;
        List<Field> newLocks = null;
        if (properties.containsKey(DATA_LOCK_FIELDS)) {
            if (databaseObject == null) {
                throw new IllegalArgumentException("dataLockFields is unavailable for " + object.eClass().getName());
            }
            newLocks = lockFields(databaseObject, requestedLocks);
        }

        var changes = new LinkedHashMap<String, Change>();
        var scalarNames = scalars.keySet().iterator();
        for (var entry : scalarValues.entrySet()) {
            EStructuralFeature feature = entry.getKey();
            String name = scalarNames.next();
            Object before = MetadataProperties.current(object, feature);
            Object after = feature.isMany() && "".equals(entry.getValue()) ? null
                    : MetadataProperties.display(entry.getValue());
            if (!Objects.equals(before, after)) {
                changes.put(name, new Change(before, after));
                if (apply) MetadataProperties.apply(object, feature, entry.getValue());
            }
        }
        if (newType != null && !EcoreUtil.equals(constant.getType(), newType)) {
            changes.put(TYPE, new Change(GatewaySupport.renderType(constant.getType()),
                    GatewaySupport.renderType(newType)));
            if (apply) constant.setType(newType);
        }
        if (newLocks != null && !databaseObject.getDataLockFields().equals(newLocks)) {
            changes.put(DATA_LOCK_FIELDS, new Change(lockNames(databaseObject.getDataLockFields()), lockNames(newLocks)));
            if (apply) {
                databaseObject.getDataLockFields().clear();
                databaseObject.getDataLockFields().addAll(newLocks);
            }
        }
        return changes;
    }

    private static List<String> lockNames(List<Field> fields) {
        return fields.stream().map(Field::getName).toList();
    }

    private static List<Field> lockFields(BasicDbObject owner, Object value) {
        if (!(value instanceof List<?> requested)) {
            throw new IllegalArgumentException("dataLockFields must be an array of field names");
        }
        var fields = new ArrayList<Field>();
        for (Object element : requested) {
            if (!(element instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("dataLockFields must contain nonempty field names");
            }
            Field matched = null;
            for (Field candidate : owner.getFields()) {
                if (candidate.eIsProxy() || !(name.equalsIgnoreCase(candidate.getName())
                        || name.equalsIgnoreCase(candidate.getNameRu()))) continue;
                if (matched != null) throw new IllegalArgumentException("ambiguous data lock field: " + name);
                matched = candidate;
            }
            if (matched == null) throw new IllegalArgumentException("data lock field not found on this object: " + name);
            if (fields.contains(matched)) throw new IllegalArgumentException("duplicate data lock field: " + name);
            fields.add(matched);
        }
        return fields;
    }
}
