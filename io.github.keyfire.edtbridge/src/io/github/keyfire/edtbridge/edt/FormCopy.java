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
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;

/**
 * The content of a managed form copied for another owner. It is a plain EMF copy; every reference to
 * an object of the source owner then moves to the target's counterpart, when the target has one.
 */
final class FormCopy {

    /** A reference the copy keeps into the source owner, because the target has nothing to move it to. */
    record Leftover(String where, String target) {

        @Override
        public String toString() {
            return where + " -> " + target;
        }
    }

    /** The copied content, the number of references moved to the target owner and the ones left. */
    static final class Result {
        EObject content;
        int remapped;
        final List<Leftover> leftovers = new ArrayList<>();
    }

    private FormCopy() {
    }

    /**
     * Pair the objects of the source owner a form can refer to with the target owner's: the owner itself,
     * the types it produces (object, reference, manager and so on) and its subordinate objects -
     * attributes, tabular sections and their attributes, the fields of its table, commands, forms -
     * matched by their path from the owner and their class. Owners of different classes pair nothing:
     * a catalog form in a data processor keeps its references, and the caller reports them.
     */
    static Map<EObject, EObject> counterparts(EObject sourceOwner, EObject targetOwner) {
        Map<EObject, EObject> pairs = new IdentityHashMap<>();
        if (sourceOwner == null || targetOwner == null || sourceOwner.eClass() != targetOwner.eClass()) {
            return pairs;
        }
        pairs.put(sourceOwner, targetOwner);
        pairProducedTypes(sourceOwner, targetOwner, pairs);
        Map<String, EObject> targets = namedContents(targetOwner);
        namedContents(sourceOwner).forEach((path, source) -> {
            EObject target = targets.get(path);
            if (target != null && target.eClass() == source.eClass()) {
                pairs.put(source, target);
            }
        });
        return pairs;
    }

    /**
     * Copy {@code content} and move its references. A reference to the source form goes to
     * {@code targetForm}; when that is {@code null}, as in a dry run, it stays as it is. A reference to a
     * paired object goes to its counterpart. A reference into the source owner without a counterpart
     * stays and is reported, and so is the main table of a dynamic list copied to another owner: it
     * names the source owner's table.
     */
    static Result copy(EObject content, Map<EObject, EObject> pairs, EObject sourceOwner, EObject sourceForm,
            EObject targetForm, boolean otherOwner) {
        EcoreUtil.Copier copier = new EcoreUtil.Copier() {
            @Override
            protected void copyReference(EReference reference, EObject original, EObject target) {
                // EDT resolves the new form's module from its own Module.bsl.
                if (reference != MdClassPackage.Literals.ABSTRACT_FORM__MODULE) {
                    super.copyReference(reference, original, target);
                }
            }
        };
        EObject copy = copier.copy(content);
        copier.copyReferences();
        Result result = new Result();
        result.content = copy;
        List<EObject> objects = new ArrayList<>();
        objects.add(copy);
        copy.eAllContents().forEachRemaining(objects::add);
        for (EObject object : objects) {
            for (EReference reference : object.eClass().getEAllReferences()) {
                if (reference.isContainment() || reference.isContainer() || reference.isDerived()
                        || !reference.isChangeable() || !object.eIsSet(reference)) {
                    continue;
                }
                if (reference.isMany()) {
                    @SuppressWarnings("unchecked")
                    EList<EObject> values = (EList<EObject>) object.eGet(reference);
                    for (int i = 0; i < values.size(); i++) {
                        EObject value = values.get(i);
                        EObject moved = move(value, object, reference, pairs, sourceOwner, sourceForm, targetForm,
                                result);
                        if (moved != value) {
                            values.set(i, moved);
                        }
                    }
                } else {
                    EObject value = (EObject) object.eGet(reference);
                    EObject moved = move(value, object, reference, pairs, sourceOwner, sourceForm, targetForm,
                            result);
                    if (moved != value) {
                        object.eSet(reference, moved);
                    }
                }
                if (otherOwner && "mainTable".equals(reference.getName())
                        && "DynamicListExtInfo".equals(object.eClass().getName())) {
                    result.leftovers.add(new Leftover(describe(object) + ".mainTable",
                            "the source owner's table: set the main table and the query of the list anew"));
                }
            }
        }
        return result;
    }

    private static EObject move(EObject value, EObject holder, EReference reference, Map<EObject, EObject> pairs,
            EObject sourceOwner, EObject sourceForm, EObject targetForm, Result result) {
        if (value == null) {
            return null;
        }
        if (value == sourceForm) {
            return targetForm != null ? targetForm : value;
        }
        EObject counterpart = pairs.get(value);
        if (counterpart != null) {
            result.remapped++;
            return counterpart;
        }
        if (sourceOwner != null && EcoreUtil.getRootContainer(value) == sourceOwner) {
            result.leftovers.add(new Leftover(describe(holder) + "." + reference.getName(), describe(value)));
        }
        return value;
    }

    /** The types the owner produces, paired kind by kind: the holder object and the type in it. */
    private static void pairProducedTypes(EObject source, EObject target, Map<EObject, EObject> pairs) {
        EStructuralFeature feature = source.eClass().getEStructuralFeature("producedTypes");
        if (!(feature instanceof EReference)) {
            return;
        }
        if (!(source.eGet(feature) instanceof EObject sourceTypes)
                || !(target.eGet(feature) instanceof EObject targetTypes)
                || sourceTypes.eClass() != targetTypes.eClass()) {
            return;
        }
        pairs.put(sourceTypes, targetTypes);
        for (EReference kind : sourceTypes.eClass().getEAllReferences()) {
            if (kind.isMany() || !(sourceTypes.eGet(kind) instanceof EObject sourceKind)
                    || !(targetTypes.eGet(kind) instanceof EObject targetKind)) {
                continue;
            }
            pairs.put(sourceKind, targetKind);
            EStructuralFeature type = sourceKind.eClass().getEStructuralFeature("type");
            if (type instanceof EReference && sourceKind.eGet(type) instanceof EObject sourceType
                    && targetKind.eGet(type) instanceof EObject targetType) {
                pairs.put(sourceType, targetType);
            }
        }
    }

    /**
     * The owner's contents keyed by their path from the owner: the containing feature of each level, and
     * the name too where the feature holds a list, e.g. {@code /attributes:Цена}. The owner's own name
     * inside a name becomes {@code ~}, so the table of {@code Catalog.Товары} and that of
     * {@code Catalog.Услуги} get one path. A list element without a name has no path.
     */
    private static Map<String, EObject> namedContents(EObject owner) {
        Map<String, EObject> named = new HashMap<>();
        String ownerName = nameOf(owner);
        for (Iterator<EObject> it = owner.eAllContents(); it.hasNext();) {
            EObject object = it.next();
            String path = namePath(object, owner, ownerName);
            if (path != null) {
                named.putIfAbsent(path, object);
            }
        }
        return named;
    }

    private static String namePath(EObject object, EObject owner, String ownerName) {
        StringBuilder path = new StringBuilder();
        for (EObject current = object; current != owner; current = current.eContainer()) {
            EStructuralFeature feature = (current == null) ? null : current.eContainingFeature();
            if (feature == null) {
                return null;
            }
            String step = "/" + feature.getName();
            if (feature.isMany()) {
                String name = nameOf(current);
                if (name == null) {
                    return null;
                }
                // Qualified names, such as a generated table path, encode the owner in the name.
                step += ":" + (name.indexOf('.') >= 0 ? ownerRelative(name, ownerName) : name);
            }
            path.insert(0, step);
        }
        return path.toString();
    }

    /** The name with every dot-separated segment equal to the owner's name replaced by {@code ~}. */
    static String ownerRelative(String name, String ownerName) {
        if (ownerName == null) {
            return name;
        }
        String[] segments = name.split("\\.", -1);
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].equals(ownerName)) {
                segments[i] = "~";
            }
        }
        return String.join(".", segments);
    }

    static String nameOf(EObject object) {
        EStructuralFeature name = object.eClass().getEStructuralFeature("name");
        if (!(name instanceof EAttribute) || name.isMany()) {
            return null;
        }
        return object.eGet(name) instanceof String text && !text.isEmpty() ? text : null;
    }

    /** The object by its class and name, or by its nearest named container when it has none. */
    static String describe(EObject object) {
        String name = nameOf(object);
        if (name != null) {
            return object.eClass().getName() + " " + name;
        }
        for (EObject up = object.eContainer(); up != null; up = up.eContainer()) {
            String upName = nameOf(up);
            if (upName != null) {
                return up.eClass().getName() + " " + upName + " / " + object.eClass().getName();
            }
        }
        return object.eClass().getName();
    }
}
