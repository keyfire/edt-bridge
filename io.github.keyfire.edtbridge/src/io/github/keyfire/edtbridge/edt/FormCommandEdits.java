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
import org.eclipse.emf.common.util.Enumerator;
import com._1c.g5.v8.dt.form.model.CommandActionPurpose;
import com._1c.g5.v8.dt.form.model.CurrentRowUse;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormItemContainer;
import com._1c.g5.v8.dt.form.model.FormPackage;
import com._1c.g5.v8.dt.form.model.SelectedRowsUse;
import com._1c.g5.v8.dt.form.model.Table;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Picture;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.platform.version.Version;
import io.github.keyfire.edtbridge.core.AllowedValues;
import io.github.keyfire.edtbridge.core.FormPropertyValues;
import io.github.keyfire.edtbridge.core.PictureNames;

/** Validated command-property edits shared by form-command writes, reads and property reference. */
public final class FormCommandEdits {
    private static final Version TABLE_NAME_VERSION = new Version(8, 3, 15);

    /** Optional typed properties of a {@link FormCommand}; an empty picture clears it. */
    public record Options(String actionPurpose, String associatedTable, String currentRowUse,
            String selectedRowsUse, String picture) {
        public static final Options EMPTY = new Options(null, null, null, null, null);

        public Options(String actionPurpose, String associatedTable, String currentRowUse, String selectedRowsUse) {
            this(actionPurpose, associatedTable, currentRowUse, selectedRowsUse, null);
        }

        public boolean isEmpty() {
            return actionPurpose == null && associatedTable == null && currentRowUse == null
                    && selectedRowsUse == null && picture == null;
        }
    }

    /** Finds the picture a name stands for in the project; the model is needed for that. */
    @FunctionalInterface
    interface Pictures {
        Picture find(String name);
    }

    private FormCommandEdits() { }

    static void validate(Form form, Options options) {
        validate(form, options, null);
    }

    static void validate(Form form, Options options, Pictures pictures) {
        if (options == null || options.isEmpty()) return;
        if (options.picture() != null && PictureNames.parse(options.picture()) != null) {
            requirePictures(pictures).find(options.picture());
        }
        if (options.actionPurpose() != null) parseActionPurpose(options.actionPurpose());
        if (options.currentRowUse() != null) parseCurrentRowUse(options.currentRowUse());
        if (options.selectedRowsUse() != null) parseSelectedRowsUse(options.selectedRowsUse());
        if (options.associatedTable() != null && !options.associatedTable().isBlank()) {
            findTable(form, options.associatedTable());
        }
    }

    static void apply(Form form, FormCommand command, Options options, Version version) {
        apply(form, command, options, version, null);
    }

    static void apply(Form form, FormCommand command, Options options, Version version, Pictures pictures) {
        if (options == null || options.isEmpty()) return;
        if (options.picture() != null) {
            command.setPicture(PictureNames.parse(options.picture()) == null ? null
                    : requirePictures(pictures).find(options.picture()));
        }
        if (options.actionPurpose() != null) {
            command.setActionPurpose(parseActionPurpose(options.actionPurpose()));
        }
        if (options.currentRowUse() != null) {
            command.setCurrentRowUse(parseCurrentRowUse(options.currentRowUse()));
        }
        if (options.selectedRowsUse() != null) {
            command.setSelectedRowsUse(parseSelectedRowsUse(options.selectedRowsUse()));
        }
        if (options.associatedTable() != null) {
            if (options.associatedTable().isBlank()) {
                command.setAssociatedTableElementId8315(null);
                command.eUnset(FormPackage.Literals.FORM_COMMAND__ASSOCIATED_TABLE_ELEMENT_ID);
            } else {
                Table table = findTable(form, options.associatedTable());
                boolean byName = version == null || version.isGreaterThanOrEquals(TABLE_NAME_VERSION);
                if (byName) {
                    StringValue value = McoreFactory.eINSTANCE.createStringValue();
                    value.setValue(table.getName());
                    command.setAssociatedTableElementId8315(value);
                    command.eUnset(FormPackage.Literals.FORM_COMMAND__ASSOCIATED_TABLE_ELEMENT_ID);
                } else {
                    command.setAssociatedTableElementId(table.getId());
                    command.setAssociatedTableElementId8315(null);
                }
            }
        }
    }

    static String associatedTableName(Form form, FormCommand command) {
        Value v8315 = command.getAssociatedTableElementId8315();
        if (v8315 instanceof StringValue sv && sv.getValue() != null && !sv.getValue().isBlank()) {
            return sv.getValue();
        }
        if (command.eIsSet(FormPackage.Literals.FORM_COMMAND__ASSOCIATED_TABLE_ELEMENT_ID)) {
            int id = command.getAssociatedTableElementId();
            Table match = form == null ? null : findTableById(FormItemEdits.children(form), id);
            return match != null && match.getName() != null ? match.getName() : String.valueOf(id);
        }
        return null;
    }

    static Map<String, Object> readProperties(Form form, FormCommand command) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (command.getActionPurpose() != null) {
            out.put("actionPurpose", command.getActionPurpose().getLiteral());
        }
        String table = associatedTableName(form, command);
        if (table != null) {
            out.put("associatedTable", table);
        }
        if (command.getCurrentRowUse() != null) {
            out.put("currentRowUse", command.getCurrentRowUse().getLiteral());
        }
        if (command.getSelectedRowsUse() != null) {
            out.put("selectedRowsUse", command.getSelectedRowsUse().getLiteral());
        }
        String picture = command.getPicture() == null ? null : FormPictures.name(command.getPicture());
        if (picture != null) {
            out.put("picture", picture);
        }
        return out;
    }

    private static Pictures requirePictures(Pictures pictures) {
        if (pictures == null) throw new IllegalStateException("a command picture is looked up in the project model");
        return pictures;
    }

    static List<Map<String, Object>> reference(Form form, FormCommand command, String handler) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(entry("titleRu", "string", command.getTitle().get("ru"),
                "an empty string clears the title", null));
        out.add(entry("toolTipRu", "string", command.getToolTip().get("ru"),
                "an empty string clears the tooltip", null));
        out.add(entry("handler", "identifier", handler,
                "handler procedure in the form module", null));
        out.add(entry("actionPurpose", "enum",
                command.getActionPurpose() == null ? null : command.getActionPurpose().getLiteral(),
                "alias Normal maps to Usual", literals(CommandActionPurpose.VALUES)));
        out.add(entry("associatedTable", "string", associatedTableName(form, command),
                "table item name on this form; an empty string clears it", tableNames(form)));
        out.add(entry("currentRowUse", "enum",
                command.getCurrentRowUse() == null ? null : command.getCurrentRowUse().getLiteral(),
                null, literals(CurrentRowUse.VALUES)));
        out.add(entry("selectedRowsUse", "enum",
                command.getSelectedRowsUse() == null ? null : command.getSelectedRowsUse().getLiteral(),
                null, literals(SelectedRowsUse.VALUES)));
        out.add(entry("picture", "string", command.getPicture() == null ? null : FormPictures.name(command.getPicture()),
                "StdPicture.<Name> of the platform, CommonPicture.<Name> of the configuration or "
                        + "БиблиотекаКартинок.<Name>; an empty string clears it", null));
        return out;
    }

    static List<String> tableNames(Form form) {
        List<String> out = new ArrayList<>();
        collectTableNames(FormItemEdits.children(form), out);
        return out;
    }

    static void appendPlan(StringBuilder what, Options options) {
        if (options == null || options.isEmpty()) return;
        if (options.actionPurpose() != null) {
            what.append(" actionPurpose -> ").append(parseActionPurpose(options.actionPurpose()).getLiteral());
        }
        if (options.associatedTable() != null) {
            what.append(" associatedTable -> \"").append(options.associatedTable()).append('"');
        }
        if (options.currentRowUse() != null) {
            what.append(" currentRowUse -> ").append(parseCurrentRowUse(options.currentRowUse()).getLiteral());
        }
        if (options.selectedRowsUse() != null) {
            what.append(" selectedRowsUse -> ").append(parseSelectedRowsUse(options.selectedRowsUse()).getLiteral());
        }
        if (options.picture() != null) {
            PictureNames.Name name = PictureNames.parse(options.picture());
            what.append(" picture -> ").append(name == null ? "none" : options.picture().strip());
        }
    }

    private static CommandActionPurpose parseActionPurpose(String raw) {
        String normalized = FormPropertyValues.normalizeActionPurpose(raw);
        for (CommandActionPurpose candidate : CommandActionPurpose.VALUES) {
            if (candidate.getLiteral().equalsIgnoreCase(normalized)
                    || candidate.getName().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(AllowedValues.unknownValue("actionPurpose", raw,
                literals(CommandActionPurpose.VALUES)));
    }

    private static CurrentRowUse parseCurrentRowUse(String raw) {
        String key = raw == null ? "" : raw.trim();
        for (CurrentRowUse candidate : CurrentRowUse.VALUES) {
            if (candidate.getLiteral().equalsIgnoreCase(key) || candidate.getName().equalsIgnoreCase(key)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(AllowedValues.unknownValue("currentRowUse", raw,
                literals(CurrentRowUse.VALUES)));
    }

    private static SelectedRowsUse parseSelectedRowsUse(String raw) {
        String key = raw == null ? "" : raw.trim();
        for (SelectedRowsUse candidate : SelectedRowsUse.VALUES) {
            if (candidate.getLiteral().equalsIgnoreCase(key) || candidate.getName().equalsIgnoreCase(key)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(AllowedValues.unknownValue("selectedRowsUse", raw,
                literals(SelectedRowsUse.VALUES)));
    }

    private static Table findTable(Form form, String name) {
        FormItem item = FormItemEdits.findUnique(form, name.trim());
        if (item instanceof Table table) return table;
        throw new IllegalArgumentException(AllowedValues.unknownValue("associatedTable", name, tableNames(form)));
    }

    private static Table findTableById(List<FormItem> items, int id) {
        for (FormItem item : items) {
            if (item instanceof Table table && table.getId() == id) return table;
            if (item instanceof FormItemContainer container) {
                Table nested = findTableById(FormItemEdits.children(container), id);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static void collectTableNames(List<FormItem> items, List<String> out) {
        for (FormItem item : items) {
            if (item instanceof Table && item.getName() != null) out.add(item.getName());
            if (item instanceof FormItemContainer container) {
                collectTableNames(FormItemEdits.children(container), out);
            }
        }
    }

    private static List<String> literals(List<? extends Enumerator> values) {
        List<String> out = new ArrayList<>();
        for (Enumerator value : values) out.add(value.getLiteral());
        return out;
    }

    private static Map<String, Object> entry(String name, String type, Object current, String note,
            List<String> values) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("type", type);
        if (values != null) map.put("values", values);
        if (note != null) map.put("note", note);
        if (current != null) map.put("current", current);
        return map;
    }
}
