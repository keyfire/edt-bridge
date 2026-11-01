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
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import com._1c.g5.v8.dt.form.model.*;
import com._1c.g5.v8.dt.form.service.FormIdentifierService;
import com._1c.g5.v8.dt.form.service.item.FormNewItemDescriptor;
import com._1c.g5.v8.dt.form.service.item.IFormItemManagementService;

class FormIdentifiersRegressionTest {
    private static final FormFactory FACTORY = FormFactory.eINSTANCE;
    private static final FormIdentifierService IDS = FormIdentifierService.INSTANCE;

    @Test void bridgeButtonDoesNotUseCounterPrimedBeforeExternalItems() {
        Form form = FACTORY.createForm();
        button(form, "Initial", 1, 2);
        assertEquals(3, IDS.getNextItemId(form));
        button(form, "External", 123, 124);
        FormCommand command = FACTORY.createFormCommand(); command.setName("Run");
        // The live management service requires an EDT injector. Its native allocator remains real.
        IFormItemManagementService service = (IFormItemManagementService) Proxy.newProxyInstance(
                IFormItemManagementService.class.getClassLoader(),
                new Class<?>[] {IFormItemManagementService.class}, (proxy, method, args) -> {
                    if (!"addButton".equals(method.getName())) throw new UnsupportedOperationException(method.getName());
                    return button(form, "Generated", IDS.getNextItemId(form), IDS.getNextItemId(form));
                });
        Button created = FormWriteGateway.addButtonWithName(service, form, command, form,
                new FormNewItemDescriptor("Requested", Map.of(), false));
        assertEquals(125, created.getId());
        assertEquals(126, created.getExtendedTooltip().getId());
    }

    @Test void auditIncludesEveryAuxiliaryContainmentButExcludesOtherIdNamespaces() {
        Form form = collisions();
        form.getAttributes().get(0).setId(123); form.getFormCommands().get(0).setId(123);
        AutoCommandBar top = FACTORY.createAutoCommandBar(); top.setName("Top"); top.setId(400); form.setTopCommandBar(top);
        AutoCommandBar bottom = FACTORY.createAutoCommandBar(); bottom.setName("Bottom"); bottom.setId(500); form.setBottomCommandBar(bottom);
        AutoCommandBar fab = FACTORY.createAutoCommandBar(); fab.setName("Fab"); fab.setId(600); form.setFABCommandBar(fab);
        FormField field = FACTORY.createFormField(); field.setName("Input"); field.setId(10);
        ExtendedTooltip tooltip = FACTORY.createExtendedTooltip(); tooltip.setName("InputTooltip"); tooltip.setId(11); field.setExtendedTooltip(tooltip);
        ContextMenu menu = FACTORY.createContextMenu(); menu.setName("InputMenu"); menu.setId(700); field.setContextMenu(menu);
        Button nested = FACTORY.createButton(); nested.setName("MenuAction"); nested.setId(13); menu.getItems().add(nested);
        ExtendedTooltip nestedTooltip = FACTORY.createExtendedTooltip(); nestedTooltip.setName("MenuActionTooltip"); nestedTooltip.setId(14); nested.setExtendedTooltip(nestedTooltip);
        form.getItems().add(field);
        FormIdentifiersGateway.Audit audit = FormIdentifiersGateway.scan(form);
        assertEquals(13, audit.items.size());
        assertEquals(700, audit.maxItemId);
        assertEquals(List.of(123, 124), audit.conflicts.stream().map(c -> c.id).toList());
        assertTrue(audit.conflicts.stream().allMatch(c -> c.occurrences.size() == 2));
        assertEquals(1, audit.attributeCount); assertEquals(1, audit.commandCount);
        assertTrue(audit.items.stream().anyMatch(i -> "InputMenu".equals(i.name) && i.structural));
        for (FormIdentifiersGateway.Occurrence item : audit.items) {
            assertTrue(EcoreUtil.getEObject(form, item.path) instanceof FormItem);
        }
    }

    @Test void auditAndPlanDoNotAllocateOrChangeModelProperties() {
        Form form = collisions();
        assertEquals(125, IDS.getNextItemId(form));
        Map<String, String> properties = new LinkedHashMap<>(form.bmGetProperties());
        FormIdentifiersGateway.Audit audit = FormIdentifiersGateway.scan(form);
        FormIdentifiersGateway.RepairPlan plan = FormIdentifiersGateway.plan(form, selected(form), audit.sourceHash);
        assertEquals(List.of(125, 126), plan.changes.stream().map(c -> c.newId).toList());
        assertEquals(properties, form.bmGetProperties());
        assertEquals(123, form.getItems().get(1).getId());
    }

    @Test void selectedRepairPreservesDataAndNativeCountersThenRepeatsWithoutChanges() {
        Form form = collisions();
        FormGroup group = (FormGroup) form.getItems().get(1);
        group.getTitle().put("en", "Keep title"); group.setVisible(true); group.setEnabled(false);
        ExtendedTooltip tooltip = group.getExtendedTooltip();
        Button button = (Button) form.getItems().get(0);
        FormCommand command = form.getFormCommands().get(0); button.setCommandName(command);
        FormAttribute attribute = form.getAttributes().get(0);
        List<FormItem> order = new ArrayList<>(form.getItems());
        List<String> paths = selected(form);
        assertEquals(125, IDS.getNextItemId(form));
        IDS.getNextAttributeId(form); IDS.getNextCommandId(form);
        FormIdentifiersGateway.RepairPlan applied = FormIdentifiersGateway.repair(form, paths, FormIdentifiersGateway.scan(form).sourceHash);
        assertEquals(2, applied.changes.size());
        assertEquals(125, group.getId()); assertEquals(126, tooltip.getId());
        assertEquals(123, button.getId()); assertEquals(124, button.getExtendedTooltip().getId());
        assertEquals(-1, form.getAutoCommandBar().getId());
        assertSame(tooltip, group.getExtendedTooltip()); assertEquals(order, form.getItems());
        assertSame(command, button.getCommandName()); assertSame(command, form.getFormCommands().get(0));
        assertSame(attribute, form.getAttributes().get(0));
        assertEquals("Keep title", group.getTitle().get("en")); assertTrue(group.isVisible()); assertFalse(group.isEnabled());
        assertEquals(10000, attribute.getId()); assertEquals(20000, command.getId());
        assertEquals(127, IDS.getNextItemId(form));
        assertEquals(10001, IDS.getNextAttributeId(form)); assertEquals(20001, IDS.getNextCommandId(form));
        assertTrue(FormIdentifiersGateway.scan(form).conflicts.isEmpty());
        Map<String, String> counters = new LinkedHashMap<>(form.bmGetProperties());
        assertTrue(FormIdentifiersGateway.repair(form, paths, FormIdentifiersGateway.scan(form).sourceHash).changes.isEmpty());
        assertEquals(counters, form.bmGetProperties());
    }

    @Test void staleHashAndSelectingEveryDuplicateAreRefusedBeforeMutation() {
        Form stale = collisions();
        String hash = FormIdentifiersGateway.scan(stale).sourceHash;
        FormGroup group = (FormGroup) stale.getItems().get(1);
        group.setId(999);
        assertThrows(IllegalArgumentException.class, () -> FormIdentifiersGateway.repair(stale, selected(stale), hash));
        assertEquals(999, group.getId()); assertEquals(124, group.getExtendedTooltip().getId());
        Form current = collisions();
        Form form = current;
        List<String> both = List.of(path(form, form.getItems().get(0)), path(form, form.getItems().get(1)));
        assertThrows(IllegalArgumentException.class,
                () -> FormIdentifiersGateway.repair(current, both, FormIdentifiersGateway.scan(current).sourceHash));
        assertEquals(123, current.getItems().get(0).getId()); assertEquals(123, current.getItems().get(1).getId());
    }

    @Test void numericReferencesToDuplicateIdsAreNotRetargetedByGuessing() {
        Form form = collisions();
        FormCommand command = form.getFormCommands().get(0); command.setAssociatedTableElementId(123);
        assertThrows(IllegalArgumentException.class,
                () -> FormIdentifiersGateway.repair(form, selected(form), FormIdentifiersGateway.scan(form).sourceHash));
        assertEquals(123, form.getItems().get(1).getId()); assertEquals(123, command.getAssociatedTableElementId());
        assertTrue(form.bmGetProperties().isEmpty());
    }

    @Test void bothGroupReferenceTypesAndLegacyValuesBlockAmbiguousRepair() {
        Form pages = collisions();
        PagesGroupExtInfo pagesInfo = FACTORY.createPagesGroupExtInfo(); pagesInfo.setAssociatedTableElementId(124);
        ((FormGroup) pages.getItems().get(1)).setExtInfo(pagesInfo);
        assertThrows(IllegalArgumentException.class,
                () -> FormIdentifiersGateway.repair(pages, selected(pages), FormIdentifiersGateway.scan(pages).sourceHash));
        Form usual = collisions();
        UsualGroupExtInfo usualInfo = FACTORY.createUsualGroupExtInfo(); usualInfo.setAssociatedTableElementId(123);
        ((FormGroup) usual.getItems().get(1)).setExtInfo(usualInfo);
        assertThrows(IllegalArgumentException.class,
                () -> FormIdentifiersGateway.repair(usual, selected(usual), FormIdentifiersGateway.scan(usual).sourceHash));
        Form legacy = collisions();
        var value = com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createNumberValue();
        value.setValue(new java.math.BigDecimal("124")); legacy.getFormCommands().get(0).setAssociatedTableElementId8315(value);
        assertThrows(IllegalArgumentException.class,
                () -> FormIdentifiersGateway.repair(legacy, selected(legacy), FormIdentifiersGateway.scan(legacy).sourceHash));
        assertTrue(pages.bmGetProperties().isEmpty()); assertTrue(usual.bmGetProperties().isEmpty()); assertTrue(legacy.bmGetProperties().isEmpty());
    }

    @Test void unrelatedNumericReferencesStayUnchangedAndPropertyEditsInvalidatePreview() {
        Form form = collisions();
        form.getFormCommands().get(0).setAssociatedTableElementId(20);
        FormIdentifiersGateway.repair(form, selected(form), FormIdentifiersGateway.scan(form).sourceHash);
        assertEquals(20, form.getFormCommands().get(0).getAssociatedTableElementId());
        Form stale = collisions();
        String hash = FormIdentifiersGateway.scan(stale).sourceHash;
        ((FormGroup) stale.getItems().get(1)).getTitle().put("en", "Changed after preview");
        assertThrows(IllegalArgumentException.class, () -> FormIdentifiersGateway.repair(stale, selected(stale), hash));
        assertEquals(123, stale.getItems().get(1).getId());
    }

    @Test void editorIdentityDoesNotMatchAnotherProjectWithTheSameBmId() {
        Object engine = new Object();
        assertTrue(FormIdentifiersGateway.sameObject(42, engine, 42, engine));
        assertFalse(FormIdentifiersGateway.sameObject(42, engine, 43, engine));
        assertFalse(FormIdentifiersGateway.sameObject(42, engine, 42, new Object()));
    }

    @Test void invalidPathsReservedIdsAndOverflowDoNotPartiallyRepair() {
        Form form = collisions();
        assertThrows(IllegalArgumentException.class, () -> FormIdentifiersGateway.repair(form,
                List.of(path(form, form.getAttributes().get(0))), FormIdentifiersGateway.scan(form).sourceHash));
        assertThrows(IllegalArgumentException.class, () -> FormIdentifiersGateway.repair(form,
                List.of("missing"), FormIdentifiersGateway.scan(form).sourceHash));
        assertThrows(IllegalArgumentException.class, () -> FormIdentifiersGateway.repair(form,
                List.of(selected(form).get(0), selected(form).get(0)), FormIdentifiersGateway.scan(form).sourceHash));
        assertThrows(IllegalArgumentException.class, () -> FormIdentifiersGateway.repair(form, selected(form), null));
        AutoCommandBar bar = form.getAutoCommandBar();
        FormGroup duplicateBar = FACTORY.createFormGroup(); duplicateBar.setName("Wrong"); duplicateBar.setId(-1); form.getItems().add(duplicateBar);
        assertThrows(IllegalArgumentException.class, () -> FormIdentifiersGateway.repair(form,
                List.of(path(form, bar)), FormIdentifiersGateway.scan(form).sourceHash));
        duplicateBar.setId(Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class,
                () -> FormIdentifiersGateway.repair(form, selected(form), FormIdentifiersGateway.scan(form).sourceHash));
        assertEquals(123, form.getItems().get(1).getId()); assertEquals(-1, bar.getId());
    }

    private static Form collisions() {
        Form form = FACTORY.createForm();
        button(form, "Original", 123, 124);
        FormGroup group = FACTORY.createFormGroup(); group.setName("LaterGroup"); group.setId(123);
        ExtendedTooltip tooltip = FACTORY.createExtendedTooltip(); tooltip.setName("LaterGroupTooltip"); tooltip.setId(124);
        group.setExtendedTooltip(tooltip); form.getItems().add(group);
        AutoCommandBar bar = FACTORY.createAutoCommandBar(); bar.setName("FormCommandBar"); bar.setId(-1); form.setAutoCommandBar(bar);
        FormAttribute attribute = FACTORY.createFormAttribute(); attribute.setName("Data"); attribute.setId(10000); form.getAttributes().add(attribute);
        FormCommand command = FACTORY.createFormCommand(); command.setName("Run"); command.setId(20000); form.getFormCommands().add(command);
        return form;
    }
    private static List<String> selected(Form form) {
        FormGroup group = (FormGroup) form.getItems().get(1);
        return List.of(path(form, group), path(form, group.getExtendedTooltip()));
    }
    private static String path(Form form, EObject object) { return EcoreUtil.getRelativeURIFragmentPath(form, object); }

    private static Button button(Form form, String name, int id, int tooltipId) {
        Button button = FACTORY.createButton(); button.setName(name); button.setId(id);
        ExtendedTooltip tooltip = FACTORY.createExtendedTooltip();
        tooltip.setName(name + "ExtendedTooltip"); tooltip.setId(tooltipId);
        button.setExtendedTooltip(tooltip); form.getItems().add(button);
        return button;
    }
}
