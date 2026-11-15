package io.github.keyfire.edtbridge.edt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalReport;
import com._1c.g5.v8.dt.metadata.mdclass.FormType;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;

import io.github.keyfire.edtbridge.edt.MetadataSettingsGateway.Change;

/** Writable properties of an external object and its owned references. */
final class ExternalObjectSettings {
    private ExternalObjectSettings() { }

    record Reference(EStructuralFeature feature, String segment, List<? extends MdObject> candidates) {
        String fqn(String ownerFqn, MdObject object) {
            return object == null ? null : ownerFqn + "." + segment + "." + object.getName();
        }

        MdObject resolve(String ownerFqn, String input) {
            return candidates.stream()
                    .filter(object -> input.equals(object.getName()) || input.equals(fqn(ownerFqn, object)))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "No eligible " + segment.toLowerCase(java.util.Locale.ROOT) + " '" + input
                            + "' belongs to " + ownerFqn + " for " + feature.getName()));
        }
    }

    static Map<String, Change> update(MdObject owner, String fqn, Map<String, String> values, boolean apply) {
        if (!(owner instanceof ExternalDataProcessor || owner instanceof ExternalReport)) {
            throw new IllegalArgumentException("An existing ExternalDataProcessor or ExternalReport FQN is required");
        }
        if (values.isEmpty()) throw new IllegalArgumentException("Provide at least one external-object property");
        Map<String, Change> changes = new LinkedHashMap<>();
        Map<EStructuralFeature, MdObject> references = new LinkedHashMap<>();
        var slots = referenceSlots(owner);
        for (var entry : values.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if ("synonymRu".equals(name)) {
                addChange(changes, name, owner.getSynonym().get("ru"), value.isEmpty() ? null : value);
            } else if ("comment".equals(name)) {
                addChange(changes, name, owner.getComment(), value);
            } else {
                var slot = slots.get(name);
                if (slot == null) throw new IllegalArgumentException(name + " is not writable on " + fqn);
                var target = slot.resolve(fqn, value);
                if (addChange(changes, name, slot.fqn(fqn, (MdObject) owner.eGet(slot.feature())),
                        slot.fqn(fqn, target))) references.put(slot.feature(), target);
            }
        }

        if (apply) {
            references.forEach(owner::eSet);
            if (changes.containsKey("comment")) owner.setComment(values.get("comment"));
            if (changes.containsKey("synonymRu")) {
                String synonym = values.get("synonymRu");
                if (synonym.isEmpty()) owner.getSynonym().removeKey("ru");
                else owner.getSynonym().put("ru", synonym);
            }
        }
        return changes;
    }

    static Map<String, Reference> referenceSlots(MdObject owner) {
        List<? extends BasicForm> forms = owner instanceof ExternalDataProcessor processor
                ? processor.getForms() : ((ExternalReport) owner).getForms();
        var managedForms = forms.stream().filter(form -> form.getFormType() == FormType.MANAGED).toList();
        Map<String, Reference> slots = new LinkedHashMap<>();
        slots.put("defaultForm", reference(owner, "defaultForm", "Form", managedForms));
        if (owner instanceof ExternalReport report) {
            slots.put("defaultSettingsForm", reference(owner, "defaultSettingsForm", "Form", managedForms));
            slots.put("defaultVariantForm", reference(owner, "defaultVariantForm", "Form", managedForms));
            var schemas = report.getTemplates().stream()
                    .filter(template -> template.getTemplateType() == TemplateType.DATA_COMPOSITION_SCHEMA).toList();
            slots.put("mainDataCompositionSchema", reference(owner, "mainDataCompositionSchema", "Template", schemas));
        }
        return slots;
    }

    private static Reference reference(MdObject owner, String name, String segment, List<? extends MdObject> values) {
        return new Reference(owner.eClass().getEStructuralFeature(name), segment, values);
    }

    private static boolean addChange(Map<String, Change> changes, String name, Object before, Object after) {
        if (Objects.equals(before, after)) return false;
        changes.put(name, new Change(before, after));
        return true;
    }
}
