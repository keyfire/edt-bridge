/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoleRightsEditTest {
    private static final List<RoleRightsEdit.RightName> CATALOG = List.of(
            new RoleRightsEdit.RightName("Read", "Чтение"),
            new RoleRightsEdit.RightName("View", "Просмотр"),
            new RoleRightsEdit.RightName("InteractiveInsert", "ИнтерактивноеДобавление"));

    @Test void aRightIsFoundByEitherNameWhateverTheCase() {
        assertEquals("Read", RoleRightsEdit.canonical("read", CATALOG, "Catalog.Products"));
        assertEquals("Read", RoleRightsEdit.canonical("ЧТЕНИЕ", CATALOG, "Catalog.Products"));
        assertEquals("InteractiveInsert",
                RoleRightsEdit.canonical("ИнтерактивноеДобавление", CATALOG, "Catalog.Products"));
    }

    @Test void anUnknownRightNamesTheObjectAndEveryAllowedRight() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> RoleRightsEdit.canonical("Reed", CATALOG, "Catalog.Products"));
        assertEquals("unknown right of Catalog.Products: Reed; allowed: Read, View, InteractiveInsert",
                error.getMessage());
    }

    @Test void requestedValuesMustBeBooleansAndKeepTheirOrder() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("View", true);
        raw.put("Read", false);
        assertEquals(List.of("View", "Read"), List.copyOf(RoleRightsEdit.requested(raw).keySet()));
        assertEquals(Boolean.FALSE, RoleRightsEdit.requested(raw).get("Read"));

        assertThrows(IllegalArgumentException.class, () -> RoleRightsEdit.requested(Map.of("Read", "yes")));
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("Read", null);
        assertThrows(IllegalArgumentException.class, () -> RoleRightsEdit.requested(withNull));
        assertThrows(IllegalArgumentException.class, () -> RoleRightsEdit.requested(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> RoleRightsEdit.requested(null));
    }

    @Test void twoSpellingsOfOneRightCannotBeRequestedTogether() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("Read", true);
        raw.put("Чтение", false);
        var error = assertThrows(IllegalArgumentException.class,
                () -> RoleRightsEdit.resolve(RoleRightsEdit.requested(raw), CATALOG, "Catalog.Products"));
        assertEquals("right Read of Catalog.Products is requested twice: Read, Чтение", error.getMessage());
    }

    @Test void thePlanLeavesOutRightsThatAlreadyHaveTheValue() {
        Map<String, Boolean> requested = new LinkedHashMap<>();
        requested.put("View", true);
        requested.put("Read", true);
        requested.put("InteractiveInsert", false);
        Map<String, Boolean> current = new HashMap<>();
        current.put("Read", true);
        current.put("InteractiveInsert", null);

        assertEquals(List.of(new RoleRightsEdit.Change("View", null, true),
                        new RoleRightsEdit.Change("InteractiveInsert", null, false)),
                RoleRightsEdit.plan(requested, current));
    }

    @Test void aRestrictedRightIsRefusedWithTheReason() {
        assertEquals("Read of Catalog.Products in Role.Manager carries a data access restriction (RLS); "
                        + "the bridge does not change such rights, change it in EDT",
                RoleRightsEdit.restrictedRefusal("Read", "Catalog.Products", "Role.Manager"));
    }
}
