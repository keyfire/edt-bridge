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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.junit.jupiter.api.Test;
import com._1c.g5.v8.dt.form.generator.FormType;
import com._1c.g5.v8.dt.form.model.*;
import com._1c.g5.v8.dt.metadata.mdclass.*;
import com._1c.g5.v8.dt.mcore.*;

/** Regression cases use the SDK model rather than serialized XML substitutes. */
class BacklogModelRegressionTest {
    @Test void catalogDefaultsKeepObjectAndFolderFormsIndependent() throws Exception {
        Catalog owner = MdClassFactory.eINSTANCE.createCatalog();
        BasicForm object = formFor(owner, "defaultObjectForm");
        BasicForm folder = formFor(owner, "defaultFolderForm");
        setDefault(owner, object, FormType.OBJECT);
        setDefault(owner, folder, FormType.FOLDER);
        assertSame(object, owner.getDefaultObjectForm());
        assertSame(folder, owner.getDefaultFolderForm());
    }

    @Test void listAndChoiceDefaultsUseSeparateSlots() throws Exception {
        Catalog owner = MdClassFactory.eINSTANCE.createCatalog();
        BasicForm list = formFor(owner, "defaultListForm");
        BasicForm choice = formFor(owner, "defaultChoiceForm");
        setDefault(owner, list, FormType.LIST);
        setDefault(owner, choice, FormType.CHOICE);
        assertSame(list, owner.getDefaultListForm());
        assertSame(choice, owner.getDefaultChoiceForm());
    }

    @Test void processorsKeepTheirGenericDefaultSlot() throws Exception {
        DataProcessor owner = MdClassFactory.eINSTANCE.createDataProcessor();
        BasicForm form = formFor(owner, "defaultForm");
        setDefault(owner, form, FormType.OBJECT);
        assertSame(form, owner.getDefaultForm());
    }

    @Test void unsupportedDefaultTypeIsRefused() {
        Catalog owner = MdClassFactory.eINSTANCE.createCatalog();
        assertThrows(IllegalArgumentException.class,
                () -> setDefault(owner, formFor(owner, "defaultObjectForm"), FormType.GENERIC));
        assertNull(owner.getDefaultObjectForm());
    }

    @Test void externalObjectPropertiesPreviewAndUpdateBothRootKinds() {
        var factory = MdClassFactory.eINSTANCE;
        var processor = factory.createExternalDataProcessor();
        processor.setName("Check");
        var processorForm = factory.createDataProcessorForm();
        processorForm.setName("Main");
        processorForm.setFormType(com._1c.g5.v8.dt.metadata.mdclass.FormType.MANAGED);
        processor.getForms().add(processorForm);

        var report = factory.createExternalReport();
        report.setName("Check");
        var first = factory.createReportForm();
        first.setName("First");
        first.setFormType(com._1c.g5.v8.dt.metadata.mdclass.FormType.MANAGED);
        var second = factory.createReportForm();
        second.setName("Second");
        second.setFormType(com._1c.g5.v8.dt.metadata.mdclass.FormType.MANAGED);
        report.getForms().add(first);
        report.getForms().add(second);
        report.setDefaultForm(first);

        for (MdObject root : List.of(processor, report)) {
            String fqn = root.eClass().getName() + "." + root.getName();
            root.getSynonym().put("ru", "Old title");
            root.getSynonym().put("en", "English title");
            String chosen = fqn + (root == processor ? ".Form.Main" : ".Form.Second");
            var preview = ExternalObjectSettings.update(root, fqn, Map.of("synonymRu", "New title", "defaultForm", chosen), false);
            assertEquals(new MetadataSettingsGateway.Change("Old title", "New title"), preview.get("synonymRu"));
            assertEquals(new MetadataSettingsGateway.Change(root == report ? fqn + ".Form.First" : null, chosen),
                    preview.get("defaultForm"));
            assertEquals("Old title", root.getSynonym().get("ru"));
            assertEquals(preview, ExternalObjectSettings.update(root, fqn, Map.of("synonymRu", "New title", "defaultForm", chosen), true));
            assertEquals("New title", root.getSynonym().get("ru"));
            assertEquals("English title", root.getSynonym().get("en"));
            assertTrue(ExternalObjectSettings.update(root, fqn, Map.of("synonymRu", "New title", "defaultForm", chosen), true).isEmpty());
        }
        assertSame(processorForm, processor.getDefaultForm());
        assertSame(second, report.getDefaultForm());
    }

    @Test void invalidExternalDefaultFormLeavesTheWholePatchUnapplied() {
        var factory = MdClassFactory.eINSTANCE;
        var root = factory.createExternalReport();
        root.setName("Check");
        root.getSynonym().put("ru", "Original");
        var form = factory.createReportForm();
        form.setName("Ordinary");
        form.setFormType(com._1c.g5.v8.dt.metadata.mdclass.FormType.ORDINARY);
        root.getForms().add(form);
        for (String invalid : List.of("", "Missing", "Ordinary", "ExternalReport.Other.Form.Ordinary")) {
            assertThrows(IllegalArgumentException.class, () -> ExternalObjectSettings.update(
                    root, "ExternalReport.Check", Map.of("synonymRu", "Replacement", "defaultForm", invalid), true));
            assertEquals("Original", root.getSynonym().get("ru"));
            assertNull(root.getDefaultForm());
        }
        assertThrows(IllegalArgumentException.class, () -> ExternalObjectSettings.update(
                factory.createReport(), "Report.Check", Map.of("synonymRu", "Replacement"), true));
    }


    @Test void reportSettingsAndSchemaReferencesAreTypeCheckedBeforeWriting() {
        var factory = MdClassFactory.eINSTANCE;
        var report = factory.createExternalReport();
        var settings = factory.createReportForm();
        settings.setName("Settings");
        settings.setFormType(com._1c.g5.v8.dt.metadata.mdclass.FormType.MANAGED);
        report.getForms().add(settings);
        var variant = factory.createReportForm();
        variant.setName("Variant");
        variant.setFormType(com._1c.g5.v8.dt.metadata.mdclass.FormType.MANAGED);
        report.getForms().add(variant);
        var schema = factory.createTemplate();
        schema.setName("Schema");
        schema.setTemplateType(TemplateType.DATA_COMPOSITION_SCHEMA);
        report.getTemplates().add(schema);
        var text = factory.createTemplate();
        text.setName("Text");
        text.setTemplateType(TemplateType.TEXT_DOCUMENT);
        report.getTemplates().add(text);
        String fqn = "ExternalReport.Summary";

        assertThrows(IllegalArgumentException.class, () -> ExternalObjectSettings.update(report, fqn,
                Map.of("defaultSettingsForm", "Settings", "mainDataCompositionSchema", "Text"), true));
        assertNull(report.getDefaultSettingsForm());
        assertNull(report.getMainDataCompositionSchema());
        var changes = ExternalObjectSettings.update(report, fqn,
                Map.of("defaultSettingsForm", "Settings", "defaultVariantForm", "Variant",
                        "mainDataCompositionSchema", fqn + ".Template.Schema"), true);
        assertEquals(3, changes.size());
        assertSame(settings, report.getDefaultSettingsForm());
        assertSame(variant, report.getDefaultVariantForm());
        assertSame(schema, report.getMainDataCompositionSchema());
        assertNull(report.getDefaultForm());
    }

    @Test void extensionMetadataPreviewDoesNotChangeTheRootAndInvalidPurposeIsAtomic() {
        var factory = MdClassFactory.eINSTANCE;
        var root = factory.createConfiguration();
        var language = factory.createLanguage();
        language.setLanguageCode("en");
        root.getLanguages().add(language);
        root.setDefaultLanguage(language);
        root.getSynonym().put("en", "Old title");
        root.getSynonym().put("ru", "Translation");
        root.setNamePrefix("Before_");

        var values = Map.of("synonym", "New title", "namePrefix", "After_", "purpose", "AddOn",
                "version", "1.2.3", "vendor", "Example", "comment", "Developers");
        var changes = ExtensionProjectSettings.update(root, values, false);
        assertEquals(new MetadataSettingsGateway.Change("Before_", "After_"), changes.get("namePrefix"));
        assertEquals("Old title", root.getSynonym().get("en"));
        for (var invalid : List.of(Map.of("purpose", "Unknown", "synonym", "Bad edit"),
                Map.of("namePrefix", "bad prefix", "synonym", "Bad edit"))) {
            assertThrows(IllegalArgumentException.class, () -> ExtensionProjectSettings.update(root, invalid, true));
            assertEquals("Old title", root.getSynonym().get("en"));
        }
        assertEquals(changes, ExtensionProjectSettings.update(root, values, true));
        assertEquals(values, ExtensionProjectSettings.current(root));
        assertEquals("Translation", root.getSynonym().get("ru"));
        assertTrue(ExtensionProjectSettings.update(root, values, true).isEmpty());
    }

    @Test void stringQualifiersIncludeUnlimitedAndKeepExplicitLength() throws Exception {
        for (String spec : List.of("Строка", "Строка(100)")) {
            MetadataWriteGateway gateway = new MetadataWriteGateway();
            Method parse = MetadataWriteGateway.class.getDeclaredMethod("parseType", String.class);
            parse.setAccessible(true);
            Object parsed = parse.invoke(gateway, spec);
            Method qualifiers = MetadataWriteGateway.class.getDeclaredMethod("applyTypeQualifiers",
                    TypeDescription.class, parsed.getClass());
            qualifiers.setAccessible(true);
            TypeDescription result = McoreFactory.eINSTANCE.createTypeDescription();
            qualifiers.invoke(null, result, parsed);
            assertNotNull(result.getStringQualifiers(), spec);
            assertEquals(spec.endsWith("(100)") ? 100 : 0, result.getStringQualifiers().getLength());
        }
    }

    @Test void stockCommandBarIsAnAddressableDestination() {
        Form form = FormFactory.eINSTANCE.createForm();
        AutoCommandBar bar = FormFactory.eINSTANCE.createAutoCommandBar();
        bar.setName("FormCommandBar"); bar.setId(-1); form.setAutoCommandBar(bar);
        Button button = FormFactory.eINSTANCE.createButton(); button.setName("Refresh"); button.setId(20);
        form.getItems().add(button);
        FormItemEdits.Position plan = FormItemEdits.position(form, button, "FormCommandBar", null, null, null);
        assertSame(bar, plan.parent);
        assertEquals(20, button.getId());
        assertSame(form, button.eContainer(), "preflight must not move the button");
    }

    @Test void stockCommandBarCannotBeMovedAsARegularItem() {
        Form form = FormFactory.eINSTANCE.createForm();
        AutoCommandBar bar = FormFactory.eINSTANCE.createAutoCommandBar();
        bar.setName("FormCommandBar"); form.setAutoCommandBar(bar);
        assertThrows(IllegalArgumentException.class,
                () -> FormItemEdits.position(form, bar, "", 0, null, null));
        assertSame(form, bar.eContainer());
    }

    @Test void htmlFieldOptionsUseTheirOwnExtInfo() {
        FormField field = input();
        DataPath path = FormFactory.eINSTANCE.createDataPath(); path.getSegments().add("Html"); field.setDataPath(path);
        FormItemEdits.applySettings(field, Map.of("fieldType", "HTMLDocumentField", "width", 90,
                "height", 25, "horizontalStretch", true, "verticalStretch", true), type -> {
            field.setType(type); field.setExtInfo(FormFactory.eINSTANCE.createHtmlFieldExtInfo());
        });
        assertInstanceOf(HtmlFieldExtInfo.class, field.getExtInfo());
        assertSame(path, field.getDataPath());
        assertEquals(90, FormItemEdits.readSettings(field).get("width"));
        assertEquals(true, FormItemEdits.readSettings(field).get("verticalStretch"));
    }

    @Test void inputChoiceFlagsAreTypedAndReported() {
        FormField field = input();
        Map<String, Object> values = Map.of("width", 16, "choiceButton", true, "clearButton", false,
                "textEdit", false, "listChoiceMode", true);
        settings(field, values);
        values.forEach((key, value) -> assertEquals(value, FormItemEdits.readSettings(field).get(key), key));
        assertThrows(IllegalArgumentException.class, () -> settings(field, Map.of("width", -1)));
        assertEquals(16, FormItemEdits.readSettings(field).get("width"));
    }

    @Test void choiceFoldersAndItemsIsTypedAndLimitedToInputFields() {
        FormField field = input();
        assertEquals("Auto", FormItemEdits.readSettings(field).get("choiceFoldersAndItems"));
        settings(field, Map.of("width", 12));
        settings(field, Map.of("choiceFoldersAndItems", "Folders"));
        assertEquals("Folders", FormItemEdits.readSettings(field).get("choiceFoldersAndItems"));
        assertEquals(12, FormItemEdits.readSettings(field).get("width"));
        settings(field, Map.of("choiceFoldersAndItems", "foldersAndItems"));
        assertEquals("FoldersAndItems", FormItemEdits.readSettings(field).get("choiceFoldersAndItems"));
        assertThrows(IllegalArgumentException.class, () -> settings(field, Map.of("choiceFoldersAndItems", "Groups")));
        assertThrows(IllegalArgumentException.class, () -> settings(field, Map.of("choiceFoldersAndItems", true)));
        assertEquals("FoldersAndItems", FormItemEdits.readSettings(field).get("choiceFoldersAndItems"));
        FormField box = FormFactory.eINSTANCE.createFormField();
        box.setType(ManagedFormFieldType.CHECK_BOX_FIELD); box.setExtInfo(FormFactory.eINSTANCE.createCheckBoxFieldExtInfo());
        assertThrows(IllegalArgumentException.class, () -> settings(box, Map.of("choiceFoldersAndItems", "Folders")));
        assertFalse(FormItemEdits.readSettings(box).containsKey("choiceFoldersAndItems"));
    }

    @Test void tableControlsAreIndependentFromCellEditing() {
        com._1c.g5.v8.dt.form.model.Table table = FormFactory.eINSTANCE.createTable();
        table.setChangeRowSet(true); table.setAutoInsertNewRow(true); table.setChangeRowOrder(true);
        settings(table, Map.of("changeRowSet", false, "autoInsertNewRow", false,
                "changeRowOrder", false, "readOnly", false));
        assertFalse(table.isChangeRowSet()); assertFalse(table.isAutoInsertNewRow());
        assertFalse(table.isChangeRowOrder()); assertFalse(table.isReadOnly());
        assertEquals(false, FormItemEdits.readSettings(table).get("changeRowSet"));
        FormField column = input(); table.getItems().add(column);
        settings(column, Map.of("width", 20, "readOnly", true));
        assertEquals(true, FormItemEdits.readSettings(column).get("readOnly"));
    }

    @Test void invalidPropertyDoesNotPartiallyChangeOtherSettings() {
        FormField field = input();
        InputFieldExtInfo info = (InputFieldExtInfo) field.getExtInfo(); info.setWidth(12);
        assertThrows(IllegalArgumentException.class,
                () -> settings(field, Map.of("width", 30, "changeRowSet", false)));
        assertEquals(12, info.getWidth());
        assertThrows(IllegalArgumentException.class,
                () -> settings(field, Map.of("width", 30, "choiceButton", "true")));
        assertEquals(12, info.getWidth());
    }

    private static FormField input() {
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setType(ManagedFormFieldType.INPUT_FIELD);
        field.setExtInfo(FormFactory.eINSTANCE.createInputFieldExtInfo());
        return field;
    }
    private static void settings(FormItem item, Map<String, Object> values) {
        FormItemEdits.applySettings(item, values, type -> { throw new AssertionError("unexpected type change"); });
    }
    private static BasicForm formFor(EObject owner, String slot) {
        EReference reference = (EReference) owner.eClass().getEStructuralFeature(slot);
        if (reference.getEReferenceType().isAbstract()) {
            reference = (EReference) owner.eClass().getEStructuralFeature("forms");
        }
        return (BasicForm) reference.getEReferenceType().getEPackage().getEFactoryInstance()
                .create(reference.getEReferenceType());
    }
    private static void setDefault(EObject owner, BasicForm form, FormType type) throws Exception {
        Method setter = FormWriteGateway.class.getDeclaredMethod("setDefaultForm", EObject.class,
                BasicForm.class, FormType.class);
        setter.setAccessible(true);
        try { setter.invoke(null, owner, form, type); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException) { throw (RuntimeException) failure.getCause(); }
            throw failure;
        }
    }
}
