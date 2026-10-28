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
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.ReturnValuesReuse;
import com._1c.g5.v8.dt.metadata.mdclass.extension.CommonModuleExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;

/** Typed common-module settings, validated as one patch before any property is changed. */
final class CommonModuleSettings {
    private static final Set<String> FLAGS = Set.of("global", "server", "externalConnection",
            "clientManagedApplication", "clientOrdinaryApplication", "serverCall", "privileged");
    record Change(Object before, Object after) { }
    private record Assignment(EObject target, EStructuralFeature feature, Object value, String name) { }
    private CommonModuleSettings() { }

    static Map<String, Change> describe(CommonModule module, Map<String, Object> properties, boolean extendModule) {
        Map<String, Change> changes = new LinkedHashMap<>();
        for (Assignment assignment : prepare(module, properties, extendModule)) {
            Object before = printable(assignment.target.eGet(assignment.feature));
            Object after = printable(assignment.value);
            if (!Objects.equals(before, after)) changes.put(assignment.name, new Change(before, after));
        }
        return changes;
    }

    static void apply(CommonModule module, Map<String, Object> properties, boolean extendModule) {
        List<Assignment> patch = prepare(module, properties, extendModule);
        for (Assignment assignment : patch) assignment.target.eSet(assignment.feature, assignment.value);
    }

    private static List<Assignment> prepare(CommonModule module, Map<String, Object> properties, boolean extendModule) {
        if (module == null) throw new IllegalArgumentException("CommonModule is required");
        Map<String, Object> values = properties == null ? Map.of() : properties;
        if (!values.isEmpty() && module.getObjectBelonging() != null
                && "Adopted".equalsIgnoreCase(module.getObjectBelonging().toString())) {
            throw new IllegalArgumentException("Execution flags of an adopted module are controlled by its base; "
                    + "change an owned common module or use extendModule for the adopted module body");
        }
        List<Assignment> patch = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (FLAGS.contains(name)) {
                if (!(value instanceof Boolean)) throw new IllegalArgumentException(name + " must be a boolean");
            } else if ("returnValuesReuse".equals(name)) {
                if (!(value instanceof String)) throw new IllegalArgumentException(name + " must be an enum name");
                ReturnValuesReuse parsed = null;
                for (ReturnValuesReuse candidate : ReturnValuesReuse.VALUES) {
                    if (candidate.getName().equalsIgnoreCase((String) value)
                            || candidate.getLiteral().equalsIgnoreCase((String) value)) parsed = candidate;
                }
                if (parsed == null) throw new IllegalArgumentException("Unknown returnValuesReuse: " + value);
                value = parsed;
            } else {
                throw new IllegalArgumentException("Unsupported CommonModule property: " + name);
            }
            EStructuralFeature feature = module.eClass().getEStructuralFeature(name);
            if (feature == null || !feature.isChangeable() || feature.isMany()) {
                throw new IllegalArgumentException("Property is not writable in this EDT: " + name);
            }
            patch.add(new Assignment(module, feature, value, name));
        }
        if (extendModule) {
            if (!(module.getExtension() instanceof CommonModuleExtension extension)) {
                throw new IllegalArgumentException("extendModule requires an adopted CommonModule with an extension control block");
            }
            EStructuralFeature feature = extension.eClass().getEStructuralFeature("module");
            patch.add(new Assignment(extension, feature, MdPropertyState.EXTENDED, "extension.module"));
        }
        return patch;
    }
    private static Object printable(Object value) {
        return value instanceof Enum<?> ? value.toString() : value;
    }
}
