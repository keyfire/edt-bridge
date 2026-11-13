package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.rights.model.ObjectRight;
import com._1c.g5.v8.dt.rights.model.ObjectRights;
import com._1c.g5.v8.dt.rights.model.Right;
import com._1c.g5.v8.dt.rights.model.RightValue;
import com._1c.g5.v8.dt.rights.model.RightsFactory;
import com._1c.g5.v8.dt.rights.model.Rls;
import com._1c.g5.v8.dt.rights.model.RoleDescription;

class RoleRightsGatewayTest {
    private final Right read = right("Read", "Чтение");
    private final Right view = right("View", "Просмотр");
    private final Right insert = right("Insert", "Добавление");

    @Test void valuesAreWrittenAndADefaultValueRemovesTheExplicitEntry() {
        Role role = role();
        RoleDescription rights = (RoleDescription) role.getRights();
        Catalog products = catalog();

        RoleRightsGateway.applyValues(role, rights, products, "Catalog.Products", values(read, true, view, true), "Role.Manager", null);
        ObjectRights entry = rights.getRights().get(0);
        assertSame(products, entry.getObject());
        assertEquals(List.of(RightValue.SET, RightValue.SET), entry.getRights().stream().map(ObjectRight::getValue).toList());

        // The role sets no rights for new objects, so false is its default and leaves no entry behind.
        RoleRightsGateway.applyValues(role, rights, products, "Catalog.Products", values(view, false), "Role.Manager", null);
        assertEquals(List.of("Read"), entry.getRights().stream().map(r -> r.getRight().getName()).toList());

        var states = RoleRightsGateway.states(role, rights, products, List.of(read, view));
        assertEquals(Boolean.TRUE, states.get(0).value);
        assertTrue(states.get(0).explicit);
        assertEquals(Boolean.FALSE, states.get(1).value);
        assertFalse(states.get(1).explicit);
    }

    @Test void aRestrictedRightRefusesTheWholeChangeBeforeAnythingIsWritten() {
        Role role = role();
        RoleDescription rights = (RoleDescription) role.getRights();
        Catalog products = catalog();
        RoleRightsGateway.applyValues(role, rights, products, "Catalog.Products", values(insert, true), "Role.Manager", null);
        Rls rls = RightsFactory.eINSTANCE.createRls();
        rls.setCondition("WHERE Owner = &CurrentUser");
        rights.getRights().get(0).getRights().get(0).getRestrictionsByCondition().add(rls);

        var error = assertThrows(IllegalArgumentException.class, () -> RoleRightsGateway.applyValues(role, rights,
                products, "Catalog.Products", values(read, true, insert, false), "Role.Manager", null));
        assertTrue(error.getMessage().startsWith("Insert of Catalog.Products in Role.Manager carries a data access restriction"),
                error.getMessage());
        assertEquals(List.of("Insert"), rights.getRights().get(0).getRights().stream().map(r -> r.getRight().getName()).toList());

        var state = RoleRightsGateway.states(role, rights, products, List.of(insert)).get(0);
        assertTrue(state.restricted);
        assertEquals(List.of("WHERE Owner = &CurrentUser"), state.conditions);
    }

    @Test void rightsFollowTheOrderEdtGivesAndKeepTheOnesItLeavesOut() {
        assertEquals(List.of(view, read, insert), RoleRightsGateway.ordered(new java.util.LinkedHashSet<>(List.of(read, insert, view)),
                Map.of(2, read, 1, view, 3, right("Posting", "Проведение")), "Catalog.Products"));
        assertThrows(IllegalArgumentException.class, () -> RoleRightsGateway.ordered(Set.of(), Map.of(), "Catalog.Products"));
    }

    @Test void aBaseDecidedValueIsReportedAsNull() {
        assertNull(RoleRightsGateway.value(RightValue.PROVIDED));
        assertEquals(Boolean.TRUE, RoleRightsGateway.value(RightValue.SET));
        assertEquals(Boolean.FALSE, RoleRightsGateway.value(RightValue.UNSET));
    }

    private static Map<Right, Boolean> values(Object... pairs) {
        Map<Right, Boolean> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((Right) pairs[i], (Boolean) pairs[i + 1]);
        return values;
    }

    private static Right right(String name, String nameRu) {
        Right right = RightsFactory.eINSTANCE.createRight();
        right.setName(name);
        right.setNameRu(nameRu);
        return right;
    }

    private static Role role() {
        Role role = MdClassFactory.eINSTANCE.createRole();
        role.setName("Manager");
        role.setUuid(UUID.randomUUID());
        RoleDescription rights = RightsFactory.eINSTANCE.createRoleDescription();
        rights.setSetForNewObjects(false);
        role.setRights(rights);
        return role;
    }

    private static Catalog catalog() {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Products");
        catalog.setUuid(UUID.randomUUID());
        return catalog;
    }
}
