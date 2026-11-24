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

import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.Test;

import com._1c.g5.v8.dt.form.model.DataPath;
import com._1c.g5.v8.dt.form.model.DataPathReferredObject;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdtype.CatalogTypes;
import com._1c.g5.v8.dt.metadata.mdtype.MdObjectType;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypeFactory;

class FormCopyTest {

    @Test void copyIntoAnotherCatalogMovesItsTypesAndAttributes() {
        Catalog source = catalog("Товары", "Цена");
        Catalog target = catalog("Услуги", "Цена");
        Form form = objectForm(source, "Цена");

        FormCopy.Result copied = FormCopy.copy(form, FormCopy.counterparts(source, target), source, null, null, true);

        Form copy = (Form) copied.content;
        assertNotSame(form, copy);
        assertSame(objectType(target), copy.getAttributes().get(0).getValueType().getTypes().get(0));
        assertSame(target.getAttributes().get(0), referred(copy).getObject());
        assertEquals(2, copied.remapped);
        assertTrue(copied.leftovers.isEmpty(), copied.leftovers.toString());
        assertSame(objectType(source), form.getAttributes().get(0).getValueType().getTypes().get(0),
                "the source form stays as it was");
    }

    @Test void anAttributeTheTargetLacksStaysAndIsReported() {
        Catalog source = catalog("Товары", "Артикул");
        Catalog target = catalog("Услуги", "Цена");
        Form form = objectForm(source, "Артикул");

        FormCopy.Result copied = FormCopy.copy(form, FormCopy.counterparts(source, target), source, null, null, true);

        assertSame(source.getAttributes().get(0), referred((Form) copied.content).getObject());
        assertEquals(1, copied.remapped, "the main attribute's type still moves");
        assertEquals(1, copied.leftovers.size());
        assertEquals("FormField Артикул / DataPathReferredObject.object -> CatalogAttribute Артикул",
                copied.leftovers.get(0).toString());
    }

    @Test void anOwnerOfAnotherKindPairsNothing() {
        Catalog source = catalog("Товары", "Цена");
        DataProcessor target = MdClassFactory.eINSTANCE.createDataProcessor();
        target.setName("Цены");

        assertTrue(FormCopy.counterparts(source, target).isEmpty());
        FormCopy.Result copied = FormCopy.copy(objectForm(source, "Цена"), Map.of(), source, null, null, true);
        assertEquals(0, copied.remapped);
        assertEquals(2, copied.leftovers.size(), copied.leftovers.toString());
    }

    @Test void aReferenceToTheSourceEntryMovesToTheCopyEntry() {
        Catalog source = catalog("Товары", "Цена");
        CatalogForm entry = MdClassFactory.eINSTANCE.createCatalogForm();
        entry.setName("ФормаЭлемента");
        source.getForms().add(entry);
        CatalogForm copyEntry = MdClassFactory.eINSTANCE.createCatalogForm();
        Form form = objectForm(source, "Цена");
        form.setMdForm(entry);

        FormCopy.Result dryRun = FormCopy.copy(form, Map.of(), null, entry, null, false);
        FormCopy.Result applied = FormCopy.copy(form, Map.of(), null, entry, copyEntry, false);

        assertSame(entry, ((Form) dryRun.content).getMdForm(), "a dry run has no entry to move to");
        assertSame(copyEntry, ((Form) applied.content).getMdForm());
        assertEquals(0, applied.remapped);
        assertTrue(applied.leftovers.isEmpty());
    }

    @Test void aCopyWithinTheSameObjectReportsNothing() {
        Catalog source = catalog("Товары", "Цена");
        FormCopy.Result copied = FormCopy.copy(objectForm(source, "Цена"), Map.of(), null, null, null, false);
        assertEquals(0, copied.remapped);
        assertTrue(copied.leftovers.isEmpty());
        assertSame(source.getAttributes().get(0), referred((Form) copied.content).getObject());
    }

    @Test void theOwnersNameInsideANameDoesNotSeparateCounterparts() {
        assertEquals("~", FormCopy.ownerRelative("Товары", "Товары"));
        assertEquals("Catalog.~", FormCopy.ownerRelative("Catalog.Товары", "Товары"));
        assertEquals("ТоварыИУслуги", FormCopy.ownerRelative("ТоварыИУслуги", "Товары"));
        assertEquals("Catalog.Услуги", FormCopy.ownerRelative("Catalog.Услуги", null));
    }

    private static Catalog catalog(String name, String attribute) {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(name);
        CatalogAttribute owned = MdClassFactory.eINSTANCE.createCatalogAttribute();
        owned.setName(attribute);
        catalog.getAttributes().add(owned);
        CatalogTypes types = MdTypeFactory.eINSTANCE.createCatalogTypes();
        MdObjectType objectType = MdTypeFactory.eINSTANCE.createMdObjectType();
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName("CatalogObject." + name);
        objectType.setType(type);
        types.setObjectType(objectType);
        catalog.setProducedTypes(types);
        return catalog;
    }

    private static Type objectType(Catalog catalog) {
        return catalog.getProducedTypes().getObjectType().getType();
    }

    /** An object form: the main attribute typed by the catalog's object and a field on one attribute. */
    private static Form objectForm(Catalog catalog, String attribute) {
        Form form = FormFactory.eINSTANCE.createForm();
        FormAttribute main = FormFactory.eINSTANCE.createFormAttribute();
        main.setName("Объект");
        TypeDescription valueType = McoreFactory.eINSTANCE.createTypeDescription();
        valueType.getTypes().add(objectType(catalog));
        main.setValueType(valueType);
        form.getAttributes().add(main);
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setName(attribute);
        DataPath path = FormFactory.eINSTANCE.createDataPath();
        path.getSegments().add("Объект");
        path.getSegments().add(attribute);
        DataPathReferredObject referred = FormFactory.eINSTANCE.createDataPathReferredObject();
        EObject owned = catalog.getAttributes().get(0);
        referred.setObject(owned);
        path.getObjects().add(referred);
        field.setDataPath(path);
        form.getItems().add(field);
        return form;
    }

    private static DataPathReferredObject referred(Form form) {
        return ((DataPath) ((FormField) form.getItems().get(0)).getDataPath()).getObjects().get(0);
    }
}
