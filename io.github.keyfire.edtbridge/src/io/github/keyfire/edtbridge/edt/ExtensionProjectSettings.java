package io.github.keyfire.edtbridge.edt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ConfigurationExtensionPurpose;

import io.github.keyfire.edtbridge.edt.MetadataSettingsGateway.Change;

/** Editable metadata of an extension project root. */
final class ExtensionProjectSettings {
    static final List<String> PURPOSES = List.of("Customization", "AddOn", "Patch");

    private ExtensionProjectSettings() { }

    static Map<String, String> current(Configuration root) {
        Map<String, String> values = new LinkedHashMap<>();
        var language = root.getDefaultLanguage();
        if (language != null && language.getLanguageCode() != null) {
            values.put("synonym", root.getSynonym().get(language.getLanguageCode()));
        }
        values.put("comment", root.getComment());
        values.put("vendor", root.getVendor());
        values.put("version", root.getVersion());
        values.put("namePrefix", root.getNamePrefix());
        var purpose = root.getConfigurationExtensionPurpose();
        values.put("purpose", purpose == null ? null : purpose.getName());
        return values;
    }

    static Map<String, Change> update(Configuration root, Map<String, String> values, boolean apply) {
        if (values.isEmpty()) throw new IllegalArgumentException("Provide at least one extension property");
        var current = current(root);
        Map<String, Change> changes = new LinkedHashMap<>();
        ConfigurationExtensionPurpose purpose = null;
        for (var entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!current.containsKey(key)) throw new IllegalArgumentException(key + " is not writable on this extension");
            switch (key) {
                case "synonym":
                    if (value.isEmpty()) value = null;
                    break;
                case "namePrefix":
                    if (!MetadataWriteGateway.isIdentifier(value)) {
                        throw new IllegalArgumentException("namePrefix must be a valid 1C identifier");
                    }
                    break;
                case "purpose":
                    purpose = value.isBlank() ? null : MetadataWriteGateway.resolvePurpose(value);
                    if (purpose == null) throw new IllegalArgumentException("purpose must be Customization, AddOn or Patch");
                    value = purpose.getName();
                    break;
                default:
                    break;
            }
            if (!Objects.equals(current.get(key), value)) changes.put(key, new Change(current.get(key), value));
        }
        if (apply) {
            if (changes.containsKey("comment")) root.setComment(values.get("comment"));
            if (changes.containsKey("vendor")) root.setVendor(values.get("vendor"));
            if (changes.containsKey("version")) root.setVersion(values.get("version"));
            if (changes.containsKey("namePrefix")) root.setNamePrefix(values.get("namePrefix"));
            if (changes.containsKey("purpose")) root.setConfigurationExtensionPurpose(purpose);
            var synonym = changes.get("synonym");
            if (synonym != null) {
                String language = root.getDefaultLanguage().getLanguageCode();
                if (synonym.after() == null) root.getSynonym().removeKey(language);
                else root.getSynonym().put(language, values.get("synonym"));
            }
        }
        return changes;
    }
}
