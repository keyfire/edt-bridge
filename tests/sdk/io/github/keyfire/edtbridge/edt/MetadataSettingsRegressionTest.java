package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

class MetadataSettingsRegressionTest {
    @Test void incompleteNestedAddressNeverSelectsAnAncestor() {
        var parent = MdClassFactory.eINSTANCE.createSubsystem(); parent.setName("Tools");
        var child = MdClassFactory.eINSTANCE.createSubsystem(); child.setName("Internal"); parent.getSubsystems().add(child);
        IBmTransaction tx = (IBmTransaction) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IBmTransaction.class},
                (proxy, method, args) -> method.getName().equals("getTopObjectByFqn") && args[0].equals("Subsystem.Tools") ? parent : null);
        assertSame(child, MetadataSettingsGateway.subsystem(tx, "Subsystem.Tools.Subsystem.Internal"));
        for (String address : List.of("Subsystem.Tools.Subsystem", "Subsystem.Tools.", "Subsystem..Subsystem.Internal", "Subsystem.Tools.Attribute.Internal")) {
            assertThrows(IllegalArgumentException.class, () -> MetadataSettingsGateway.subsystem(tx, address), address);
        }
    }
    @Test void alternateNamesOfTheSameObjectCannotBeAddedAndRemovedTogether() {
        var first = MdClassFactory.eINSTANCE.createCommonModule(); first.setName("First"); first.setUuid(java.util.UUID.fromString("1c64c583-034b-40c6-b76f-cb5acbb1c184"));
        var alias = MdClassFactory.eINSTANCE.createCommonModule(); alias.setName("Alias"); alias.setUuid(first.getUuid());
        assertThrows(IllegalArgumentException.class, () -> MetadataSettingsGateway.validateDisjoint(List.of(first), List.of(alias)));
        assertDoesNotThrow(() -> MetadataSettingsGateway.validateDisjoint(List.of(first), List.of()));
        assertEquals("First", first.getName());
    }
}
