package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.eclipse.emf.ecore.EObject;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.mcore.McoreFactory;

class MetadataFieldsTest {
    private static final List<Supplier<? extends MdObject>> REGISTERS = List.of(
            () -> MdClassFactory.eINSTANCE.createInformationRegister(),
            () -> MdClassFactory.eINSTANCE.createAccumulationRegister(),
            () -> MdClassFactory.eINSTANCE.createAccountingRegister(),
            () -> MdClassFactory.eINSTANCE.createCalculationRegister());

    @Test void addsEachFieldKindToItsOwnRegisterList() {
        for (var factory : REGISTERS) {
            var owner = factory.get();
            for (String kind : List.of("attribute", "resource", "dimension")) {
                var type = McoreFactory.eINSTANCE.createTypeDescription();
                var added = MetadataFields.add(owner, kind, kind + "Field", type, "Field label", "Field comment");
                String listName = switch (kind) {
                    case "attribute" -> "attributes";
                    case "resource" -> "resources";
                    default -> "dimensions";
                };
                assertEquals(List.of(added), owner.eGet(owner.eClass().getEStructuralFeature(listName)));
                assertSame(type, ((BasicFeature) added).getType());
                assertEquals("Field label", added.getSynonym().get("ru"));
                assertEquals("Field comment", added.getComment());
                assertNotNull(added.getUuid());
            }
        }
    }

    @Test void duplicateNamesAcrossEveryPairOfListsAreRefusedWithoutMutation() {
        for (var factory : REGISTERS) {
            for (String existing : List.of("attribute", "resource", "dimension")) {
                for (String requested : List.of("attribute", "resource", "dimension")) {
                    var owner = factory.get();
                    var first = MetadataFields.add(owner, existing, "Версия", McoreFactory.eINSTANCE.createTypeDescription(), null, null);
                    var before = owner.eContents().size();
                    assertThrows(IllegalArgumentException.class, () -> MetadataFields.add(owner, requested, "вЕРСИЯ",
                            McoreFactory.eINSTANCE.createTypeDescription(), null, null), owner.eClass().getName() + "/" + existing + "/" + requested);
                    assertEquals(before, owner.eContents().size());
                    assertEquals(List.of(new MetadataFields.Field(existing, first)), MetadataFields.matches(owner, "ВЕРСИЯ"));
                }
            }
        }
    }

    @Test void modifiesTheSelectedKindAndSupportsClearingOptionalText() {
        for (var factory : REGISTERS) {
            var owner = factory.get();
            for (String kind : List.of("attribute", "resource", "dimension")) {
                var first = MetadataFields.add(owner, kind, kind + "Field", McoreFactory.eINSTANCE.createTypeDescription(), "Old", "Old comment");
                var type = McoreFactory.eINSTANCE.createTypeDescription();
                assertSame(first, MetadataFields.modify(owner, kind, kind.toUpperCase() + "FIELD", type, "New", "New comment"));
                assertSame(type, ((BasicFeature) first).getType());
                assertEquals("New", first.getSynonym().get("ru"));
                assertEquals("New comment", first.getComment());
                MetadataFields.modify(owner, kind, kind + "Field", null, "", "");
                assertFalse(first.getSynonym().containsKey("ru"));
                assertEquals("", first.getComment());
                assertSame(type, ((BasicFeature) first).getType());
            }
        }
    }

    @Test void defaultKindPreservesAttributeCallsAndUnsupportedKindsWriteNothing() {
        var catalog = MdClassFactory.eINSTANCE.createCatalog();
        var field = MetadataFields.add(catalog, null, "Note", McoreFactory.eINSTANCE.createTypeDescription(), null, null);
        assertEquals(List.of(field), catalog.getAttributes());
        for (String kind : List.of("resource", "dimension", "unknown", "", "name")) {
            assertThrows(IllegalArgumentException.class, () -> MetadataFields.add(catalog, kind, "Other",
                    McoreFactory.eINSTANCE.createTypeDescription(), null, null));
            assertEquals(List.of(field), catalog.getAttributes());
        }
    }

    @Test void missingAttributeHintsUseTheStoredMemberNameAndExactKind() {
        var owner = MdClassFactory.eINSTANCE.createInformationRegister();
        MetadataFields.add(owner, "resource", "Value", McoreFactory.eINSTANCE.createTypeDescription(), null, null);
        MetadataFields.add(owner, "dimension", "Key", McoreFactory.eINSTANCE.createTypeDescription(), null, null);
        for (String[] sample : List.of(new String[]{"value", "resource", "Resource.Value"}, new String[]{"KEY", "dimension", "Dimension.Key"})) {
            String message = MetadataFields.missingMessage(owner, "InformationRegister.Versions", null, sample[0]);
            assertTrue(message.contains("InformationRegister.Versions." + sample[2]), message);
            assertTrue(message.contains("kind=" + sample[1]), message);
            assertTrue(message.contains("edt_delete_object"), message);
            assertTrue(message.contains("edt_rename"), message);
        }
        assertNull(MetadataFields.find(owner, null, "Value"));
        assertThrows(IllegalArgumentException.class, () -> MetadataFields.modify(owner, null, "Value", null, "Wrong", null));
        assertNull(owner.getResources().get(0).getSynonym().get("ru"));
    }

    @Test void ambiguousSameKindNamesCannotModifyTheFirstMatch() {
        var owner = MdClassFactory.eINSTANCE.createInformationRegister();
        var first = MdClassFactory.eINSTANCE.createInformationRegisterResource(); first.setName("Value");
        var second = MdClassFactory.eINSTANCE.createInformationRegisterResource(); second.setName("VALUE");
        owner.getResources().add(first); owner.getResources().add(second);
        assertThrows(IllegalArgumentException.class, () -> MetadataFields.modify(owner, "resource", "value", null, "Wrong", null));
        assertTrue(first.getSynonym().isEmpty()); assertTrue(second.getSynonym().isEmpty());
    }
    @Test void explicitKindDisambiguatesAnExistingCrossKindNameCollision() {
        var owner = MdClassFactory.eINSTANCE.createInformationRegister();
        var attribute = MdClassFactory.eINSTANCE.createInformationRegisterAttribute(); attribute.setName("Value");
        var resource = MdClassFactory.eINSTANCE.createInformationRegisterResource(); resource.setName("VALUE");
        owner.getAttributes().add(attribute); owner.getResources().add(resource);
        MetadataFields.modify(owner, "resource", "value", null, "Resource label", null);
        assertEquals("Resource label", resource.getSynonym().get("ru"));
        assertTrue(attribute.getSynonym().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> MetadataFields.add(owner, "dimension", "value",
                McoreFactory.eINSTANCE.createTypeDescription(), null, null));
        assertTrue(owner.getDimensions().isEmpty());
    }

    @Test void aggregateRegisterResourcesRequireAnExplicitSingleNumericType() {
        var gateway = new MetadataWriteGateway();
        for (var factory : REGISTERS.subList(1, REGISTERS.size())) {
            var owner = factory.get();
            for (String type : List.of("Строка(10)", "Булево", "Дата", "ХранилищеЗначения", "Число(10,2), Строка(5)", "ОпределяемыйТип.Amount")) {
                assertFalse(gateway.fieldTypeAllowed(owner, "resource", type), owner.eClass().getName() + ": " + type);
            }
            assertTrue(gateway.fieldTypeAllowed(owner, "resource", "Число(15,2)"));
            assertTrue(gateway.fieldTypeAllowed(owner, "resource", "Число"));
            assertTrue(gateway.fieldTypeAllowed(owner, "attribute", "Строка(10)"));
            assertTrue(gateway.fieldTypeAllowed(owner, "dimension", "Строка(10)"));
        }
        assertTrue(gateway.fieldTypeAllowed(MdClassFactory.eINSTANCE.createInformationRegister(), "resource", "Строка(10)"));
    }

}