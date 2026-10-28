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

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.Test;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.form.model.*;
import com._1c.g5.v8.dt.form.service.item.*;
import com._1c.g5.v8.dt.mcore.*;
import com._1c.g5.v8.dt.metadata.mdclass.*;
import com._1c.g5.v8.dt.metadata.mdtype.*;
import com._1c.g5.v8.dt.platform.version.Version;

/** Regression fixtures use EDT's real EMF model and replace only the workspace/service boundary. */
class MetadataAndFormRegressionTest {
    @Test
    void commonAttributeReportsContentUseAndSeparation() throws Exception {
        CommonAttribute attribute = MdClassFactory.eINSTANCE.createCommonAttribute();
        attribute.setAutoUse(CommonAttributeAutoUse.DONT_USE);
        attribute.setDataSeparation(CommonAttributeDataSeparation.SEPARATE);
        attribute.setSeparatedDataUse(CommonAttributeSeparatedDataUse.INDEPENDENTLY_AND_SIMULTANEOUSLY);
        SessionParameter value = MdClassFactory.eINSTANCE.createSessionParameter();
        value.setName("TenantValue");
        attribute.setDataSeparationValue(value);
        SessionParameter enabled = MdClassFactory.eINSTANCE.createSessionParameter();
        enabled.setName("TenantEnabled");
        attribute.setDataSeparationUse(enabled);
        Catalog catalog = catalog("Customers");
        CommonAttributeContentItem item = MdClassFactory.eINSTANCE.createCommonAttributeContentItem();
        item.setMetadata(catalog);
        item.setUse(CommonAttributeUse.AUTO);
        attribute.getContent().add(item);

        MetadataReadGateway.MdDetails result = specifics(attribute);
        assertEquals("DontUse", result.properties.get("autoUse"));
        assertEquals("Separate", result.properties.get("dataSeparation"));
        assertEquals("IndependentlyAndSimultaneously", result.properties.get("separatedDataUse"));
        assertEquals("SessionParameter.TenantValue", result.properties.get("dataSeparationValue"));
        assertEquals("SessionParameter.TenantEnabled", result.properties.get("dataSeparationUse"));
        assertEquals(1, result.content.size());
        assertEquals("Catalog.Customers", result.content.get(0).fqn);
        assertEquals("Auto", result.content.get(0).use);
    }

    @Test
    void eventSubscriptionReportsSourceTypesEventAndHandler() throws Exception {
        EventSubscription subscription = MdClassFactory.eINSTANCE.createEventSubscription();
        TypeDescription source = McoreFactory.eINSTANCE.createTypeDescription();
        Type first = McoreFactory.eINSTANCE.createType();
        first.setName("CatalogObject.Customers");
        Type second = McoreFactory.eINSTANCE.createType();
        second.setName("DocumentObject.Order");
        source.getTypes().add(first);
        source.getTypes().add(second);
        subscription.setSource(source);
        subscription.setEvent("BeforeWrite");
        subscription.setHandler("CommonModule.Handlers.BeforeWrite");

        MetadataReadGateway.MdDetails result = specifics(subscription);
        assertEquals("BeforeWrite", result.properties.get("event"));
        assertEquals("CommonModule.Handlers.BeforeWrite", result.properties.get("handler"));
        assertEquals(List.of("CatalogObject.Customers", "DocumentObject.Order"),
                result.sourceTypes);
    }

    @Test
    void formTypeUsesBaseConfigurationWhenExternalProjectDoesNotOwnReference() throws Exception {
        Catalog baseCatalog = catalog("Customers");
        IBmTransaction local = transaction(Map.of());
        Function<String, EObject> base = fqn -> "Catalog.Customers".equals(fqn) ? baseCatalog : null;
        TypeDescription result = assertDoesNotThrow(() -> type(local, "СправочникСсылка.Customers", base));
        assertEquals(1, result.getTypes().size());
        assertSame(baseCatalog.getProducedTypes().getRefType().getType(), result.getTypes().get(0));
    }

    @Test
    void formTypeKeepsLocalReferenceWhenBaseHasSameName() throws Exception {
        Catalog localCatalog = catalog("Customers");
        Catalog baseCatalog = catalog("Customers");
        TypeDescription result = type(transaction(Map.of("Catalog.Customers", localCatalog)),
                "СправочникСсылка.Customers", fqn -> baseCatalog);
        assertSame(localCatalog.getProducedTypes().getRefType().getType(), result.getTypes().get(0));
        assertThrows(IllegalStateException.class, () -> type(transaction(Map.of()),
                "СправочникСсылка.Missing", fqn -> null));
    }

    @Test
    void buttonRetainsExplicitNameAndRenamesOwnedTooltip() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        FormCommand command = FormFactory.eINSTANCE.createFormCommand();
        command.setName("Run");
        Button button = addButton(form, command, "RunButton");
        assertEquals("RunButton", button.getName());
        assertEquals("RunButtonExtendedTooltip", button.getExtendedTooltip().getName());
        assertSame(command, button.getCommandName());
    }

    @Test
    void buttonWithoutExplicitNameKeepsEdtGeneratedName() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        FormCommand command = FormFactory.eINSTANCE.createFormCommand();
        command.setName("Run");
        assertEquals("Run1", addButton(form, command, null).getName());
    }

    private static MetadataReadGateway.MdDetails specifics(MdObject object) throws Exception {
        MetadataReadGateway.MdDetails result = new MetadataReadGateway.MdDetails();
        Method method = MetadataReadGateway.class.getDeclaredMethod("fillTypeSpecifics", MdObject.class,
                MetadataReadGateway.MdDetails.class);
        method.setAccessible(true);
        method.invoke(null, object, result);
        return result;
    }

    private static Catalog catalog(String name) {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(name);
        CatalogTypes types = MdTypeFactory.eINSTANCE.createCatalogTypes();
        types.setRefType(MdTypeFactory.eINSTANCE.createMdRefType());
        types.getRefType().setType(McoreFactory.eINSTANCE.createType());
        catalog.setProducedTypes(types);
        return catalog;
    }

    private static IBmTransaction transaction(Map<String, EObject> objects) {
        return (IBmTransaction) Proxy.newProxyInstance(IBmTransaction.class.getClassLoader(),
                new Class<?>[] {IBmTransaction.class}, (proxy, method, args) -> {
                    if ("getTopObjectByFqn".equals(method.getName())) {
                        return objects.get(args[0]);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static TypeDescription type(IBmTransaction transaction, String spec,
            Function<String, EObject> base) throws Exception {
        return new MetadataWriteGateway().typeDescriptionFor(transaction, spec, Version.LATEST, true, base);
    }

    private static Button addButton(Form form, FormCommand command, String name) throws Exception {
        IFormItemManagementService service = (IFormItemManagementService) Proxy.newProxyInstance(
                IFormItemManagementService.class.getClassLoader(),
                new Class<?>[] {IFormItemManagementService.class}, (proxy, method, args) -> {
                    if (!"addButton".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    Button button = FormFactory.eINSTANCE.createButton();
                    button.setName("Run1");
                    ExtendedTooltip tooltip = FormFactory.eINSTANCE.createExtendedTooltip();
                    tooltip.setName("Run1ExtendedTooltip");
                    button.setExtendedTooltip(tooltip);
                    button.setCommandName(command);
                    form.getItems().add(button);
                    return button;
                });
        FormNewItemDescriptor descriptor = new FormNewItemDescriptor(name, Map.of(), false);
        return FormWriteGateway.addButtonWithName(service, form, command, form, descriptor);
    }
}
