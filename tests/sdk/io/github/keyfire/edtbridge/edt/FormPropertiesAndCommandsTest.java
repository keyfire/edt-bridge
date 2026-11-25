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

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com._1c.g5.v8.dt.form.model.Button;
import com._1c.g5.v8.dt.form.model.CommandActionPurpose;
import com._1c.g5.v8.dt.form.model.CurrentRowUse;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormPackage;
import com._1c.g5.v8.dt.form.model.ManagedFormFieldType;
import com._1c.g5.v8.dt.form.model.SelectedRowsUse;
import com._1c.g5.v8.dt.form.model.Table;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.PictureRef;
import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.platform.version.Version;

class FormPropertiesAndCommandsTest {
    @Test
    void commandPictureIsSetReadBackAndCleared() {
        Form form = FormFactory.eINSTANCE.createForm();
        FormCommand command = FormFactory.eINSTANCE.createFormCommand();
        command.setName("Post");
        CommonPicture logo = MdClassFactory.eINSTANCE.createCommonPicture();
        logo.setName("Logo");
        FormCommandEdits.Pictures pictures = raw -> {
            PictureRef reference = McoreFactory.eINSTANCE.createPictureRef();
            reference.setPicture(logo);
            return reference;
        };
        var set = new FormCommandEdits.Options(null, null, null, null, "CommonPicture.Logo");
        FormCommandEdits.validate(form, set, pictures);
        FormCommandEdits.apply(form, command, set, new Version(8, 3, 24), pictures);
        assertSame(logo, ((PictureRef) command.getPicture()).getPicture());
        assertEquals("CommonPicture.Logo", FormCommandEdits.readProperties(form, command).get("picture"));
        assertTrue(FormCommandEdits.reference(form, command, "Post").stream()
                .anyMatch(entry -> "picture".equals(entry.get("name")) && "CommonPicture.Logo".equals(entry.get("current"))));
        StringBuilder plan = new StringBuilder();
        FormCommandEdits.appendPlan(plan, set);
        assertEquals(" picture -> CommonPicture.Logo", plan.toString());

        FormCommandEdits.apply(form, command, new FormCommandEdits.Options(null, null, null, null, ""),
                new Version(8, 3, 24), pictures);
        assertNull(command.getPicture());
        assertNull(FormCommandEdits.readProperties(form, command).get("picture"));

        assertThrows(IllegalArgumentException.class, () -> FormCommandEdits.validate(form,
                new FormCommandEdits.Options(null, null, null, null, "Post"), pictures));
        assertThrows(IllegalStateException.class, () -> FormCommandEdits.validate(form, set));
        assertFalse(new FormCommandEdits.Options(null, null, null, null, "").isEmpty());
        assertTrue(new FormCommandEdits.Options(null, null, null, null).isEmpty());
    }


    @Test
    void formRootSettingsValidateDescribeAndApply() {
        Form form = FormFactory.eINSTANCE.createForm();
        form.setAutoTitle(true);
        Map<String, Object> patch = Map.of(
                "autoTitle", false,
                "showTitle", true,
                "showCloseButton", false,
                "allowFormCustomize", false,
                "titleRu", "Диалог выбора",
                "windowOpeningMode", "LockOwnerWindow",
                "scaleVariant", "Compact",
                "width", new BigDecimal("64"),
                "height", new BigDecimal("20")
        );

        var changes = FormSettings.describe(form, patch, true);
        assertEquals(false, changes.get("autoTitle").after());
        assertEquals("Диалог выбора", changes.get("titleRu").after());
        assertEquals("LockOwnerWindow", changes.get("windowOpeningMode").after());
        assertEquals("Compact", changes.get("scaleVariant").after());
        assertEquals(64, changes.get("width").after());

        FormSettings.apply(form, patch, true);
        assertFalse(form.isAutoTitle());
        assertEquals("Диалог выбора", form.getTitle().get("ru"));
        assertEquals(64, form.getWidth());
        assertEquals(20, form.getHeight());
        assertTrue(FormSettings.describe(form, patch, true).isEmpty());

        FormSettings.apply(form, Map.of("titleRu", ""), true);
        assertNull(form.getTitle().get("ru"));

        assertThrows(IllegalArgumentException.class,
                () -> FormSettings.apply(form, Map.of("unknownSetting", true), true));
        assertThrows(IllegalArgumentException.class,
                () -> FormSettings.apply(form, Map.of("width", -5), true));
        assertThrows(IllegalArgumentException.class,
                () -> FormSettings.apply(form, Map.of("scaleVariant", "Unknown"), true));

        List<Map<String, Object>> reference = FormSettings.reference(form);
        assertTrue(reference.stream().anyMatch(entry -> "autoTitle".equals(entry.get("name"))));
        assertTrue(reference.stream().anyMatch(entry -> "windowOpeningMode".equals(entry.get("name"))));
    }

    @Test
    void formCommandOptionsApplyReadBackAndClearTable() {
        Form form = FormFactory.eINSTANCE.createForm();
        Table table = FormFactory.eINSTANCE.createTable();
        table.setName("ItemsTree");
        table.setId(42);
        form.getItems().add(table);

        FormCommand command = FormFactory.eINSTANCE.createFormCommand();
        command.setName("ApplySelection");
        command.getTitle().put("ru", "Применить");

        var options = new FormCommandEdits.Options("Finish", "ItemsTree", "Use", "DontUse");
        FormCommandEdits.validate(form, options);
        FormCommandEdits.apply(form, command, options, new Version(8, 5, 1));

        assertEquals(CommandActionPurpose.FINISH, command.getActionPurpose());
        assertEquals(CurrentRowUse.USE, command.getCurrentRowUse());
        assertEquals(SelectedRowsUse.DONT_USE, command.getSelectedRowsUse());
        assertEquals("ItemsTree", FormCommandEdits.associatedTableName(form, command));
        assertFalse(command.eIsSet(FormPackage.Literals.FORM_COMMAND__ASSOCIATED_TABLE_ELEMENT_ID));

        var described = new FormGateway().commandOf(form, command);
        assertEquals("Finish", described.actionPurpose);
        assertEquals("ItemsTree", described.associatedTable);
        assertEquals("Use", described.currentRowUse);
        assertEquals("DontUse", described.selectedRowsUse);

        FormCommandEdits.apply(form, command,
                new FormCommandEdits.Options("Normal", "", "Auto", "Auto"), new Version(8, 5, 1));
        assertEquals(CommandActionPurpose.USUAL, command.getActionPurpose());
        assertNull(FormCommandEdits.associatedTableName(form, command));

        FormCommandEdits.apply(form, command,
                new FormCommandEdits.Options(null, "ItemsTree", null, null), new Version(8, 3, 14));
        assertEquals("ItemsTree", FormCommandEdits.associatedTableName(form, command));
        assertTrue(command.eIsSet(FormPackage.Literals.FORM_COMMAND__ASSOCIATED_TABLE_ELEMENT_ID));

        assertThrows(IllegalArgumentException.class, () -> FormCommandEdits.validate(form,
                new FormCommandEdits.Options(null, "MissingTable", null, null)));
        assertThrows(IllegalArgumentException.class, () -> FormCommandEdits.validate(form,
                new FormCommandEdits.Options("Unknown", null, null, null)));

        List<Map<String, Object>> reference = FormCommandEdits.reference(form, command, "ApplyHandler");
        assertTrue(reference.stream().anyMatch(entry -> "actionPurpose".equals(entry.get("name"))));
        assertTrue(reference.stream().anyMatch(entry -> "associatedTable".equals(entry.get("name"))));
    }

    @Test
    void tableAndButtonSettingsValidateTypesAndReadBack() {
        Table table = FormFactory.eINSTANCE.createTable();
        table.setName("Tree");
        FormItemEdits.applySettings(table, Map.of(
                "selectionMode", "SingleRow",
                "initialTreeView", "ExpandAllLevels"
        ), type -> { });
        Map<String, Object> tableProps = FormItemEdits.readSettings(table);
        assertEquals("SingleRow", tableProps.get("selectionMode"));
        assertEquals("ExpandAllLevels", tableProps.get("initialTreeView"));

        Button button = FormFactory.eINSTANCE.createButton();
        button.setName("RunButton");
        FormItemEdits.applySettings(button, Map.of(
                "defaultButton", true,
                "buttonImportance", "Main"
        ), type -> { });
        Map<String, Object> buttonProps = FormItemEdits.readSettings(button);
        assertEquals(Boolean.TRUE, buttonProps.get("defaultButton"));
        assertEquals("Main", buttonProps.get("buttonImportance"));

        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setType(ManagedFormFieldType.INPUT_FIELD);
        field.setExtInfo(FormFactory.eINSTANCE.createInputFieldExtInfo());
        assertThrows(IllegalArgumentException.class, () -> FormItemEdits.applySettings(
                field, Map.of("selectionMode", "MultiRow"), type -> { }));
        assertThrows(IllegalArgumentException.class, () -> FormItemEdits.applySettings(
                field, Map.of("defaultButton", true), type -> { }));
        assertThrows(IllegalArgumentException.class, () -> FormItemEdits.applySettings(
                table, Map.of("buttonImportance", "Main"), type -> { }));
    }
}
