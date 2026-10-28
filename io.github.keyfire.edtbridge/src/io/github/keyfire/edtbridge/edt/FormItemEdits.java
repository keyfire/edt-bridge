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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import com._1c.g5.v8.dt.form.model.*;
import com._1c.g5.v8.dt.form.service.item.IFormItemMovementService;
import com._1c.g5.v8.dt.mcore.*;

/** Validated edits over the EDT form model, shared by preflight and apply. */
public final class FormItemEdits {
    private FormItemEdits() { }
    private static final Set<String> SETTINGS = Set.of("groupDirection", "representation", "showTitle",
            "united", "titleLocation", "maxHeight", "verticalStretch", "fieldType", "choiceList");

    /** A zero-based destination index in the sibling list after excluding the moved item. */
    public static final class Position {
        public final FormItem item;
        public final FormItemContainer parent;
        public final int index;
        Position(FormItem item, FormItemContainer parent, int index) {
            this.item = item; this.parent = parent; this.index = index;
        }
        public String describe() {
            return "index " + index + " in " + (parent instanceof FormItem
                    ? ((FormItem) parent).getName() : "form root");
        }
        public void apply(IFormItemMovementService movement) {
            if (item.eContainer() == parent && parent.getItems().indexOf(item) == index) { return; }
            if (!movement.move(item, parent, index)) {
                throw new IllegalArgumentException("EDT refused the destination container");
            }
        }
        public void placeAdded(FormItem created) {
            parent.getItems().move(index, created);
        }
    }

    /** No model object is changed while resolving the destination. Omitted parent keeps a moved item's parent. */
    public static Position position(Form form, FormItem item, String parentName, Integer index,
            String beforeItem, String afterItem) {
        int selectors = (index == null ? 0 : 1) + (beforeItem == null ? 0 : 1) + (afterItem == null ? 0 : 1);
        if (selectors > 1) { throw new IllegalArgumentException("index, beforeItem and afterItem are mutually exclusive"); }
        FormItemContainer parent;
        if (parentName == null && item != null) {
            if (!(item.eContainer() instanceof FormItemContainer)) {
                throw new IllegalArgumentException("the item is not a movable member of a container");
            }
            parent = (FormItemContainer) item.eContainer();
        } else if (parentName == null || parentName.isBlank()) {
            parent = form;
        } else {
            FormItem target = findUnique(form.getItems(), parentName);
            if (!(target instanceof FormItemContainer)) {
                throw new IllegalArgumentException("parent container not found: " + parentName);
            }
            parent = (FormItemContainer) target;
        }
        if (item != null) {
            for (EObject owner = parent; owner != null; owner = owner.eContainer()) {
                if (owner == item) { throw new IllegalArgumentException("an item cannot be moved into itself or its descendants"); }
            }
            compatible(item, parent);
        }
        List<FormItem> siblings = new ArrayList<>(parent.getItems());
        siblings.remove(item);
        int destination = siblings.size();
        if (index != null) {
            destination = index.intValue();
        } else if (beforeItem != null || afterItem != null) {
            String anchor = beforeItem != null ? beforeItem : afterItem;
            if (anchor.isBlank()) { throw new IllegalArgumentException("the anchor item name cannot be blank"); }
            FormItem sibling = findUnique(siblings, anchor);
            if (sibling == null || !siblings.contains(sibling)) {
                throw new IllegalArgumentException("anchor is not a direct sibling in the destination: " + anchor);
            }
            destination = siblings.indexOf(sibling) + (afterItem == null ? 0 : 1);
        }
        if (destination < 0 || destination > siblings.size()) {
            throw new IllegalArgumentException("index must be between 0 and " + siblings.size());
        }
        return new Position(item, parent, destination);
    }

    public static FormItem findUnique(List<FormItem> roots, String name) {
        List<FormItem> matches = new ArrayList<>();
        collectMatches(roots, name, matches);
        if (matches.size() > 1) { throw new IllegalArgumentException("ambiguous form item name: " + name); }
        return matches.isEmpty() ? null : matches.get(0);
    }
    private static void collectMatches(List<FormItem> roots, String name, List<FormItem> matches) {
        for (FormItem item : roots) {
            if (name != null && name.equalsIgnoreCase(item.getName())) { matches.add(item); }
            if (item instanceof FormItemContainer) { collectMatches(((FormItemContainer) item).getItems(), name, matches); }
        }
    }
    private static Table tableOf(EObject item) {
        for (EObject o = item; o != null; o = o.eContainer()) { if (o instanceof Table) { return (Table) o; } }
        return null;
    }
    private static boolean commandContainer(EObject item) {
        if (!(item instanceof FormGroup)) { return false; }
        ManagedFormGroupType type = ((FormGroup) item).getType();
        return type == ManagedFormGroupType.COMMAND_BAR || type == ManagedFormGroupType.BUTTON_GROUP
                || type == ManagedFormGroupType.POPUP;
    }
    private static void compatible(FormItem item, FormItemContainer parent) {
        if (item.eContainer() == parent) { return; }
        Table sourceTable = tableOf(item.eContainer()), targetTable = tableOf(parent);
        if (sourceTable != targetTable && (sourceTable != null || targetTable != null)) {
            throw new IllegalArgumentException("moving across table boundaries would change data bindings; move within the same table");
        }
        if (commandContainer(item.eContainer()) != commandContainer(parent)) {
            throw new IllegalArgumentException("moving across command-bar boundaries would change the item kind");
        }
        if (item instanceof FormGroup) {
            ManagedFormGroupType itemType = ((FormGroup) item).getType();
            if (commandContainer(parent) && itemType == ManagedFormGroupType.COMMAND_BAR
                    || !commandContainer(parent) && (itemType == ManagedFormGroupType.BUTTON_GROUP
                            || itemType == ManagedFormGroupType.POPUP)
                    || targetTable == null && itemType == ManagedFormGroupType.COLUMN_GROUP) {
                throw new IllegalArgumentException("the destination would require changing the group kind");
            }
        }
        if (parent instanceof FormGroup) {
            ManagedFormGroupType type = ((FormGroup) parent).getType();
            if (type == ManagedFormGroupType.PAGES
                    && (!(item instanceof FormGroup) || ((FormGroup) item).getType() != ManagedFormGroupType.PAGE)) {
                throw new IllegalArgumentException("a Pages container accepts Page groups");
            }
            if (commandContainer(parent) && !(item instanceof Button)
                    && (!(item instanceof FormGroup) || !commandContainer(item))) {
                throw new IllegalArgumentException("a command container accepts buttons and command groups");
            }
        }
        if (item instanceof FormGroup && ((FormGroup) item).getType() == ManagedFormGroupType.PAGE
                && (!(parent instanceof FormGroup) || ((FormGroup) parent).getType() != ManagedFormGroupType.PAGES)) {
            throw new IllegalArgumentException("a Page group requires a Pages container");
        }
    }

    public static ManagedFormFieldType fieldType(Object name) {
        if (!(name instanceof String)) { throw new IllegalArgumentException("fieldType must be a string"); }
        for (ManagedFormFieldType type : List.of(ManagedFormFieldType.INPUT_FIELD,
                ManagedFormFieldType.RADIO_BUTTON_FIELD, ManagedFormFieldType.CHECK_BOX_FIELD)) {
            if (type.getLiteral().equalsIgnoreCase((String) name) || type.name().equalsIgnoreCase((String) name)) { return type; }
        }
        throw new IllegalArgumentException("fieldType must be InputField, RadioButtonField or CheckBoxField");
    }
    private static FieldExtInfo fieldInfo(ManagedFormFieldType type) {
        switch (type) {
            case INPUT_FIELD: return FormFactory.eINSTANCE.createInputFieldExtInfo();
            case RADIO_BUTTON_FIELD: return FormFactory.eINSTANCE.createRadioButtonsFieldExtInfo();
            case CHECK_BOX_FIELD: return FormFactory.eINSTANCE.createCheckBoxFieldExtInfo();
            default: throw new IllegalArgumentException("unsupported field type: " + type);
        }
    }
    private static EObject effectiveInfo(FormItem item, Map<String, Object> settings) {
        if (item instanceof FormField) {
            if (settings.containsKey("fieldType")) { return fieldInfo(fieldType(settings.get("fieldType"))); }
            return ((FormField) item).getExtInfo();
        }
        return item instanceof FormGroup ? ((FormGroup) item).getExtInfo() : null;
    }
    private static final class Assignment {
        final String key; final Object value;
        Assignment(String key, Object value) { this.key = key; this.value = value; }
    }
    public static void validateSettings(FormItem item, Map<String, Object> settings) {
        assignments(item, settings);
    }
    private static List<Assignment> assignments(FormItem item, Map<String, Object> settings) {
        List<Assignment> result = new ArrayList<>();
        EObject info = effectiveInfo(item, settings);
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (!SETTINGS.contains(key)) { throw new IllegalArgumentException("unsupported form item property: " + key); }
            if ("fieldType".equals(key)) {
                if (!(item instanceof FormField)) { throw new IllegalArgumentException("fieldType requires a field"); }
                fieldType(value); continue;
            }
            if ("choiceList".equals(key)) {
                if (!(info instanceof InputFieldExtInfo) && !(info instanceof RadioButtonsFieldExtInfo)) {
                    throw new IllegalArgumentException("choiceList requires InputField or RadioButtonField");
                }
                choices(value); result.add(new Assignment(key, value)); continue;
            }
            EObject target = propertyTarget(item, info, key);
            String featureName = featureName(item, key);
            EStructuralFeature feature = target.eClass().getEStructuralFeature(featureName);
            if (feature == null || !feature.isChangeable() || feature.isMany()) {
                throw new IllegalArgumentException(key + " is not available on " + item.eClass().getName());
            }
            Object converted = convert(feature, value, key);
            result.add(new Assignment(key, converted));
        }
        return result;
    }
    private static String featureName(FormItem item, String key) {
        if ("groupDirection".equals(key)) { return "group"; }
        if ("titleLocation".equals(key) && item instanceof FormGroup) { return "childItemsTitleLocation"; }
        return key;
    }
    private static EObject propertyTarget(FormItem item, EObject info, String key) {
        if (Set.of("groupDirection", "showTitle", "united").contains(key)
                || "representation".equals(key) && item instanceof FormGroup
                || "titleLocation".equals(key) && item instanceof FormGroup) {
            if (!(info instanceof UsualGroupExtInfo)) { throw new IllegalArgumentException(key + " requires a usual group"); }
            return info;
        }
        if ("maxHeight".equals(key) || "verticalStretch".equals(key) && item instanceof FormField) {
            if (!(info instanceof InputFieldExtInfo)) { throw new IllegalArgumentException(key + " requires an input field"); }
            return info;
        }
        return item;
    }
    private static Object convert(EStructuralFeature feature, Object value, String key) {
        if (feature.getEType() instanceof EEnum) {
            if (!(value instanceof String)) { throw new IllegalArgumentException(key + " must be an enum string"); }
            for (EEnumLiteral literal : ((EEnum) feature.getEType()).getELiterals()) {
                if (literal.getLiteral().equalsIgnoreCase((String) value) || literal.getName().equalsIgnoreCase((String) value)) {
                    return literal.getInstance();
                }
            }
            throw new IllegalArgumentException("unknown " + key + " value: " + value);
        }
        Class<?> type = feature.getEType().getInstanceClass();
        if (type == boolean.class || type == Boolean.class) {
            if (!(value instanceof Boolean)) { throw new IllegalArgumentException(key + " must be boolean"); }
            return value;
        }
        if (type == int.class || type == Integer.class) {
            if (!(value instanceof Number)) { throw new IllegalArgumentException(key + " must be a nonnegative integer"); }
            BigDecimal number = new BigDecimal(value.toString());
            try { int n = number.intValueExact(); if (n >= 0) { return n; } } catch (ArithmeticException ignored) { }
            throw new IllegalArgumentException(key + " must be a nonnegative integer");
        }
        throw new IllegalArgumentException("unsupported property type: " + key);
    }
    private static List<FormChoiceListDesTimeValue> choices(Object value) {
        if (!(value instanceof List<?>)) { throw new IllegalArgumentException("choiceList must be an array"); }
        List<FormChoiceListDesTimeValue> result = new ArrayList<>();
        for (Object raw : (List<?>) value) {
            if (!(raw instanceof Map<?, ?>)) { throw new IllegalArgumentException("each choiceList entry must be an object"); }
            Map<?, ?> choice = (Map<?, ?>) raw;
            if (!choice.containsKey("value") || !Set.of("value", "titleRu").containsAll(choice.keySet())) {
                throw new IllegalArgumentException("a choice needs value and optional titleRu");
            }
            FormChoiceListDesTimeValue target = FormFactory.eINSTANCE.createFormChoiceListDesTimeValue();
            Object scalar = choice.get("value");
            if (scalar instanceof String) {
                StringValue v = McoreFactory.eINSTANCE.createStringValue(); v.setValue((String) scalar); target.setValue(v);
            } else if (scalar instanceof Boolean) {
                BooleanValue v = McoreFactory.eINSTANCE.createBooleanValue(); v.setValue((Boolean) scalar); target.setValue(v);
            } else if (scalar instanceof Number) {
                NumberValue v = McoreFactory.eINSTANCE.createNumberValue(); v.setValue(new BigDecimal(scalar.toString())); target.setValue(v);
            } else { throw new IllegalArgumentException("choice values must be strings, numbers or booleans"); }
            if (choice.containsKey("titleRu")) {
                if (!(choice.get("titleRu") instanceof String)) { throw new IllegalArgumentException("choice titleRu must be a string"); }
                target.getPresentation().put("ru", (String) choice.get("titleRu"));
            }
            result.add(target);
        }
        return result;
    }
    public static void applySettings(FormItem item, Map<String, Object> settings,
            Consumer<ManagedFormFieldType> changeType) {
        applySettings(item, settings, changeType, true);
    }

    /** The legacy boolean and 8.5.1 enum share one XML name; set only the active representation. */
    @SuppressWarnings("unchecked")
    public static void applySettings(FormItem item, Map<String, Object> settings,
            Consumer<ManagedFormFieldType> changeType, boolean use851Title) {
        List<Assignment> changes = assignments(item, settings);
        if (settings.containsKey("fieldType")) {
            ManagedFormFieldType requested = fieldType(settings.get("fieldType"));
            if (((FormField) item).getType() != requested) { changeType.accept(requested); }
        }
        EObject info = effectiveInfo(item, Map.of());
        for (Assignment change : changes) {
            if ("choiceList".equals(change.key)) {
                List<FormChoiceListDesTimeValue> target = (List<FormChoiceListDesTimeValue>) info.eGet(info.eClass().getEStructuralFeature("choiceList"));
                target.clear(); target.addAll(choices(change.value));
            } else {
                EObject target = propertyTarget(item, info, change.key);
                EStructuralFeature feature = target.eClass().getEStructuralFeature(featureName(item, change.key));
                if ("showTitle".equals(change.key)) {
                    EStructuralFeature current = info.eClass().getEStructuralFeature("showTitle851");
                    if (use851Title && current != null && current.getEType() instanceof EEnum) {
                        info.eUnset(feature);
                        info.eSet(current, ((EEnum) current.getEType()).getEEnumLiteralByLiteral(change.value.toString()).getInstance());
                    } else {
                        if (current != null) { info.eUnset(current); }
                        info.eSet(feature, change.value);
                    }
                } else {
                    target.eSet(feature, change.value);
                }
                if ("maxHeight".equals(change.key)) { ((InputFieldExtInfo) info).setAutoMaxHeight(false); }
            }
        }
    }
    public static Map<String, Object> readSettings(FormItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        EObject info = effectiveInfo(item, Map.of());
        for (String key : List.of("groupDirection", "representation", "showTitle", "united", "titleLocation", "maxHeight", "verticalStretch")) {
            try {
                EObject target = propertyTarget(item, info, key);
                EStructuralFeature feature = target.eClass().getEStructuralFeature(featureName(item, key));
                if (feature != null) {
                    Object value = target.eGet(feature);
                    if ("showTitle".equals(key)) {
                        EStructuralFeature current = target.eClass().getEStructuralFeature("showTitle851");
                        if (current != null && target.eIsSet(current)) {
                            String literal = target.eGet(current).toString();
                            value = "true".equals(literal) ? Boolean.TRUE : "false".equals(literal) ? Boolean.FALSE : literal;
                        }
                    }
                    result.put(key, value instanceof Enumerator ? ((Enumerator) value).getLiteral() : value);
                }
            } catch (IllegalArgumentException ignored) { }
        }
        if (item instanceof FormField) { result.put("fieldType", ((FormField) item).getType().getLiteral()); }
        if (info instanceof InputFieldExtInfo || info instanceof RadioButtonsFieldExtInfo) {
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Object raw : (List<?>) info.eGet(info.eClass().getEStructuralFeature("choiceList"))) {
                FormChoiceListDesTimeValue choice = (FormChoiceListDesTimeValue) raw;
                Map<String, Object> entry = new LinkedHashMap<>(); Value value = choice.getValue();
                if (value instanceof StringValue) { entry.put("value", ((StringValue) value).getValue()); }
                else if (value instanceof NumberValue) { entry.put("value", ((NumberValue) value).getValue()); }
                else if (value instanceof BooleanValue) { entry.put("value", ((BooleanValue) value).isValue()); }
                else { entry.put("valueType", value == null ? null : value.eClass().getName()); }
                if (choice.getPresentation().get("ru") != null) { entry.put("titleRu", choice.getPresentation().get("ru")); }
                entries.add(entry);
            }
            result.put("choiceList", entries);
        }
        return result;
    }
}
