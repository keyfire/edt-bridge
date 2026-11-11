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

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.wiring.ServiceAccess;

/**
 * What the write tools take for one target: the property names, their types, the accepted values
 * and the current values. The lists come from the same code that validates a write, so a name or
 * a value reported here is one the write accepts.
 */
public final class PropertyReferenceGateway {
    private static final int ITEM_NAME_LIMIT = 200;

    public static final class Result {
        public boolean ok;
        public String project;
        public String fqn;
        public String item;
        /** commonModule, field, formItem, form or object. */
        public String kind;
        public String objectType;
        /** The write tool that takes the listed properties. */
        public String tool;
        /** Where the properties go in a call of that tool. */
        public String usage;
        public List<Map<String, Object>> properties = new ArrayList<>();
        /** Item names of the form, when the form was addressed without an item or the item was not found. */
        public List<String> items;
        public String message;
    }

    public Result reference(String projectName, String fqn, String itemName) {
        Result result = new Result();
        result.project = projectName; result.fqn = fqn;
        result.item = itemName == null || itemName.isBlank() ? null : itemName;
        try {
            if (fqn == null || fqn.isBlank()) throw new IllegalArgumentException("fqn is required");
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!project.exists() || !project.isOpen()) {
                throw new IllegalArgumentException("project not found or closed: " + projectName);
            }
            IBmModelManager manager = ServiceAccess.get(IBmModelManager.class);
            IBmModel model = manager == null ? null : manager.getModel(project);
            if (model == null) throw new IllegalArgumentException("no BM model for project: " + projectName);
            model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.propertyReference") {
                @Override public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    describe(tx, result);
                    return null;
                }
            });
            result.ok = true;
        } catch (RuntimeException failure) {
            result.ok = false;
            // A refusal of this gateway is a sentence for the caller; the class name adds nothing to it.
            result.message = failure instanceof IllegalArgumentException && failure.getMessage() != null
                    ? failure.getMessage() : GatewaySupport.describeCause(failure);
        }
        return result;
    }

    private static void describe(IBmTransaction tx, Result result) {
        String fqn = result.fqn;
        if (result.item != null) {
            Form form = FormWriteGateway.resolveForm(tx, fqn);
            if (form == null) {
                throw new IllegalArgumentException("form not found: " + fqn
                        + "; item needs the FQN of a form, e.g. Catalog.Products.Form.ItemForm");
            }
            FormItem item = FormItemEdits.findUnique(form, result.item);
            if (item == null) {
                result.items = itemNames(form);
                throw new IllegalArgumentException("form item not found: " + result.item + "; the form's items are listed in items");
            }
            formItem(item, result);
            return;
        }
        for (String segment : List.of("Attribute", "Resource", "Dimension")) {
            int index = fqn.lastIndexOf("." + segment + ".");
            if (index <= 0) continue;
            String name = fqn.substring(index + segment.length() + 2);
            EObject owner = name.contains(".") ? null : tx.getTopObjectByFqn(fqn.substring(0, index));
            if (owner == null) continue;
            String kind = segment.toLowerCase(java.util.Locale.ROOT);
            MdObject field = MetadataFields.find(owner, kind, name);
            if (field == null) {
                throw new IllegalArgumentException(MetadataFields.missingMessage(owner, fqn.substring(0, index), kind, name));
            }
            field(field, kind, fqn.substring(0, index), result);
            return;
        }
        EObject object = tx.getTopObjectByFqn(fqn);
        if (object instanceof CommonModule module) {
            result.kind = "commonModule"; result.objectType = module.eClass().getName();
            result.tool = "edt_modify_object";
            result.usage = "pass the listed names inside the properties argument; extendModule=true is a separate argument "
                    + "for the module body of an adopted common module";
            result.properties = CommonModuleSettings.reference(module);
            return;
        }
        Form form = FormWriteGateway.resolveForm(tx, fqn);
        if (form != null) {
            result.kind = "form"; result.objectType = "Form";
            result.items = itemNames(form);
            result.message = "pass item to list the settings of one form item (edt_modify_form_item). "
                    + "A form attribute takes type, titleRu and main (edt_modify_form_attribute); "
                    + "a form command takes titleRu, toolTipRu and handler (edt_modify_form_command).";
            return;
        }
        if (object == null) throw new IllegalArgumentException("object not found: " + fqn);
        result.kind = "object"; result.objectType = object.eClass().getName();
        result.message = "The bridge sets typed properties of common modules, of fields and of form items. "
                + "For a field of this object pass its FQN, e.g. " + fqn + ".Attribute.Name; "
                + "for a form item pass the form FQN, e.g. " + fqn + ".Form.FormName, together with item.";
    }

    private static void formItem(FormItem item, Result result) {
        result.kind = "formItem"; result.objectType = item.eClass().getName();
        result.tool = "edt_modify_form_item";
        result.usage = "pass the listed names as arguments next to projectName, formFqn and name";
        result.properties.add(entry("newName", "identifier", item.getName(), "renames the item"));
        result.properties.add(entry("titleRu", "string", title(item), "an empty string clears the title"));
        for (String flag : List.of("visible", "enabled")) {
            EStructuralFeature feature = item.eClass().getEStructuralFeature(flag);
            if (feature != null && !feature.isMany()) result.properties.add(entry(flag, "boolean", item.eGet(feature), null));
        }
        result.properties.addAll(FormItemEdits.describeSettings(item));
    }

    private static void field(MdObject field, String kind, String ownerFqn, Result result) {
        result.kind = "field"; result.objectType = field.eClass().getName();
        result.tool = "edt_modify_attribute";
        result.usage = "pass ownerFqn=" + ownerFqn + ", name=" + field.getName() + ", kind=" + kind
                + " and the listed names as arguments";
        String type = field instanceof BasicFeature feature ? GatewaySupport.renderType(feature.getType()) : null;
        result.properties.add(entry("newType", "type", type, "value type in the grammar of edt_add_attribute"));
        result.properties.add(entry("newSynonymRu", "string", field.getSynonym().get("ru"), "an empty string clears the synonym"));
        result.properties.add(entry("newComment", "string", field.getComment(), "an empty string clears the comment"));
        result.properties.addAll(MetadataFields.optionReference(field));
    }

    private static Map<String, Object> entry(String name, String type, Object current, String note) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("type", type);
        if (note != null) entry.put("note", note);
        if (current != null) entry.put("current", current);
        return entry;
    }

    private static String title(FormItem item) {
        EStructuralFeature feature = item.eClass().getEStructuralFeature("title");
        Object value = feature == null ? null : item.eGet(feature);
        if (value instanceof EMap<?, ?> titles) {
            Object ru = titles.get("ru");
            return ru == null ? null : ru.toString();
        }
        return null;
    }

    private static List<String> itemNames(Form form) {
        List<String> names = new ArrayList<>();
        form.eAllContents().forEachRemaining(each -> {
            if (each instanceof FormItem item && item.getName() != null && names.size() < ITEM_NAME_LIMIT) {
                names.add(item.getName());
            }
        });
        return names;
    }
}
