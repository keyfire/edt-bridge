package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;

class MetadataCreateAndEditTest {
    private static final MdClassFactory MD = MdClassFactory.eINSTANCE;

    @Test void registersHttpServiceSubsystemAndScheduledJobInTheirConfigurationLists() {
        var config = MD.createConfiguration();
        var service = MD.createHTTPService();
        assertEquals("HTTPService", MetadataWriteGateway.createClassName("HTTPСервис"));
        assertEquals("Subsystem", MetadataWriteGateway.createClassName("Подсистема"));
        assertEquals("ScheduledJob", MetadataWriteGateway.createClassName("РегламентноеЗадание"));
        assertEquals("HTTPService", MetadataWriteGateway.createClassName("HTTPService"));
        assertEquals("httpServices", MetadataWriteGateway.findConfigFeature(config, service.eClass()).getName());

        var subsystem = MD.createSubsystem();
        assertEquals("subsystems", MetadataWriteGateway.findConfigFeature(config, subsystem.eClass()).getName());
        var job = MD.createScheduledJob();
        assertEquals("scheduledJobs", MetadataWriteGateway.findConfigFeature(config, job.eClass()).getName());

        MetadataWriteGateway.createProperties(service, Map.of("rootURL", "orders"), false);
        assertNotEquals("orders", service.getRootURL());
        MetadataWriteGateway.createProperties(service, Map.of("rootURL", "orders"), true);
        MetadataWriteGateway.createProperties(subsystem, Map.of("includeInCommandInterface", true), true);
        MetadataWriteGateway.createProperties(job, Map.of("methodName", "CommonModule.Jobs.Refresh"), true);
        assertEquals("orders", service.getRootURL());
        assertTrue(subsystem.isIncludeInCommandInterface());
        assertEquals("CommonModule.Jobs.Refresh", job.getMethodName());
        assertThrows(IllegalArgumentException.class,
                () -> MetadataWriteGateway.createProperties(job, Map.of("rootURL", "orders"), true));
    }

    @Test void enumerationsExposeNewValuesAndTheirSynonymsInDetails() {
        var enumeration = MD.createEnum();
        var reference = MetadataWriteGateway.memberReference(enumeration, "enumValue");
        var open = MD.createEnumValue();
        MetadataWriteGateway.attachMember(enumeration, reference, open, "Open", "Открыт", "Initial state");
        assertEquals(List.of(open), enumeration.getEnumValues());
        assertNotNull(open.getUuid());
        assertEquals("Initial state", open.getComment());
        MetadataReadGateway.MdGroup group = MetadataReadGateway.childrenOf(enumeration, 2).stream()
                .filter(member -> member.feature.equals("enumValues")).findFirst().orElseThrow();
        assertEquals("Open", group.items.get(0).name);
        assertEquals("Открыт", group.items.get(0).synonymRu);
        assertThrows(IllegalArgumentException.class,
                () -> MetadataWriteGateway.attachMember(enumeration, reference, MD.createEnumValue(), "OPEN", null, null));
        assertEquals(List.of(open), enumeration.getEnumValues());
    }

    @Test void addsTypedColumnsOnBothDocumentAndCatalogTabularSections() {
        for (var owner : List.of(MD.createDocument(), MD.createCatalog())) {
            var reference = MetadataWriteGateway.memberReference(owner, "tabularSection");
            var section = owner instanceof Document
                    ? MD.createDocumentTabularSection() : MD.createCatalogTabularSection();
            MetadataWriteGateway.attachMember(owner, reference, section, "Lines", "Строки", null);
            String top = owner.eClass().getName() + ".Orders";
            IBmTransaction tx = (IBmTransaction) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{IBmTransaction.class}, (proxy, method, args) ->
                            method.getName().equals("getTopObjectByFqn") && top.equals(args[0]) ? owner : null);
            assertSame(section, MetadataWriteGateway.resolveFieldOwner(tx, top + ".TabularSection.lines"));
            assertNull(MetadataWriteGateway.resolveFieldOwner(tx, top + ".TabularSection.Missing"));
            var column = MetadataFields.add(section, "attribute", "Amount",
                    McoreFactory.eINSTANCE.createTypeDescription(), "Сумма", null);
            assertSame(column, section.getAttributes().get(0));
            var group = MetadataReadGateway.childrenOf(owner, 2).stream()
                    .filter(member -> member.feature.equals("tabularSections")).findFirst().orElseThrow();
            assertEquals("Lines", group.items.get(0).name);
            assertEquals("Amount", group.items.get(0).children.get(0).items.get(0).name);
        }
        assertThrows(IllegalArgumentException.class,
                () -> MetadataWriteGateway.memberReference(MD.createEnum(), "tabularSection"));
    }

    @Test void documentAttributesAndTabularSectionsShareTheirNames() {
        var document = MD.createDocument();
        var section = MD.createDocumentTabularSection();
        var reference = MetadataWriteGateway.memberReference(document, "tabularSection");
        MetadataWriteGateway.attachMember(document, reference, section, "Lines", null, null);
        MetadataFields.add(document, "attribute", "Amount", McoreFactory.eINSTANCE.createTypeDescription(),
                null, null);
        assertEquals(List.of("Document.Orders.TabularSection.Lines"),
                MetadataWriteGateway.fieldNameConflicts(document, "Document.Orders", "lines"));
        assertEquals(List.of("Document.Orders.Attribute.Amount"),
                MetadataWriteGateway.fieldNameConflicts(document, "Document.Orders", "amount"));
        assertTrue(MetadataWriteGateway.fieldNameConflicts(section, "Document.Orders.TabularSection.Lines",
                "Amount").isEmpty());
    }

    @Test void objectPropertyUpdatesAreValidatedAsABatchAndReferenceListsTheSupportedSettings() {
        var catalog = MD.createCatalog();
        var identifier = McoreFactory.eINSTANCE.createField();
        identifier.setName("Ref");
        identifier.setNameRu("Ссылка");
        catalog.getFields().add(identifier);
        catalog.getListPresentation().put("en", "Products");
        var settings = Map.<String, Object>of("codeType", "String", "codeLength", 12,
                "objectPresentationRu", "Товар", "listPresentationRu", "Товары", "dataLockFields", List.of("Ссылка"));
        assertTrue(MetadataObjectProperties.update(catalog, settings, ignored -> null, false).containsKey("codeLength"));
        assertNotEquals(12, catalog.getCodeLength());
        assertThrows(IllegalArgumentException.class, () -> MetadataObjectProperties.update(catalog,
                Map.of("codeLength", 13, "dataLockFields", List.of("missing")), ignored -> null, true));
        assertNotEquals(13, catalog.getCodeLength());
        MetadataObjectProperties.update(catalog, settings, ignored -> null, true);
        assertEquals(12, catalog.getCodeLength());
        assertEquals("Товар", catalog.getObjectPresentation().get("ru"));
        assertEquals("Товары", catalog.getListPresentation().get("ru"));
        assertEquals("Products", catalog.getListPresentation().get("en"));
        assertEquals(List.of(identifier), catalog.getDataLockFields());
        assertTrue(MetadataObjectProperties.reference(catalog).stream().anyMatch(entry ->
                "dataLockFields".equals(entry.get("name")) && List.of("Ref").equals(entry.get("current"))));
        MetadataObjectProperties.update(catalog, Map.of("dataLockFields", List.of(), "listPresentationRu", ""),
                ignored -> null, true);
        assertTrue(catalog.getDataLockFields().isEmpty());
        assertFalse(catalog.getListPresentation().containsKey("ru"));
    }

    @Test void updatesDocumentRegisterAndConstantProperties() {
        var document = MD.createDocument();
        MetadataObjectProperties.update(document, Map.of("numberType", "String", "numberLength", 9,
                "posting", "Deny", "dataLockControlMode", "Managed"), ignored -> null, true);
        assertEquals(9, document.getNumberLength());
        assertEquals("Deny", document.getPosting().getLiteral());
        assertEquals("Managed", document.getDataLockControlMode().getLiteral());

        var register = MD.createInformationRegister();
        MetadataObjectProperties.update(register, Map.of("recordPresentationRu", "Версия",
                "dataLockControlMode", "Managed"), ignored -> null, true);
        assertEquals("Версия", register.getRecordPresentation().get("ru"));
        assertEquals("Managed", register.getDataLockControlMode().getLiteral());

        var constant = MD.createConstant();
        var type = McoreFactory.eINSTANCE.createTypeDescription();
        MetadataObjectProperties.update(constant, Map.of("type", "Булево"), ignored -> type, true);
        assertSame(type, constant.getType());
        assertTrue(MetadataObjectProperties.reference(constant).stream().anyMatch(entry ->
                "type".equals(entry.get("name"))));
    }
}
