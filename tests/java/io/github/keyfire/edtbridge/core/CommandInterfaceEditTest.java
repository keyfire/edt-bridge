/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CommandInterfaceEditTest {
    private static final String OPEN_LIST = "Catalog.Products.StandardCommand.OpenList";

    @Test void aStandardGroupOrACommandGroupOfTheConfigurationIsAccepted() {
        assertEquals("NavigationPanelImportant", CommandInterfaceEdit.group("NavigationPanelImportant"));
        assertEquals("CommandGroup.Sales", CommandInterfaceEdit.group("CommandGroup.Sales"));
        var error = assertThrows(IllegalArgumentException.class, () -> CommandInterfaceEdit.group("NavigationPanelTop"));
        assertEquals("unknown command group: NavigationPanelTop; allowed: NavigationPanelImportant, "
                + "NavigationPanelOrdinary, NavigationPanelSeeAlso, ActionsPanelCreate, ActionsPanelReports, "
                + "ActionsPanelTools, CommandGroup.<name>", error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> CommandInterfaceEdit.group("CommandGroup."));
    }

    @Test void aCommandChangeCarriesVisibilityGroupAndResets() {
        Map<String, Object> roles = new LinkedHashMap<>();
        roles.put("Role.Manager", true);
        roles.put("Role.Viewer", false);
        var changes = CommandInterfaceEdit.commands(List.of(
                Map.of("command", OPEN_LIST, "visible", Map.of("common", false, "roles", roles)),
                Map.of("command", "CommonCommand.Report", "group", "ActionsPanelReports", "reset", List.of("visibility"))));

        assertEquals(OPEN_LIST, changes.get(0).command());
        assertEquals(Boolean.FALSE, changes.get(0).visible().common());
        assertEquals(List.of("Role.Manager", "Role.Viewer"), List.copyOf(changes.get(0).visible().roles().keySet()));
        assertNull(changes.get(0).group());
        assertEquals("ActionsPanelReports", changes.get(1).group());
        assertEquals(Set.of("visibility"), changes.get(1).reset());
        assertNull(changes.get(1).visible());
    }

    @Test void visibilityWithoutRolesLeavesTheRoleListAlone() {
        var change = CommandInterfaceEdit.commands(List.of(Map.of("command", OPEN_LIST, "visible", Map.of("common", true)))).get(0);
        assertEquals(Boolean.TRUE, change.visible().common());
        assertNull(change.visible().roles());
    }

    @Test void contradictoryOrEmptyChangesAreRefused() {
        assertRefused("a change of " + OPEN_LIST + " needs visible, group or reset",
                List.of(Map.of("command", OPEN_LIST)));
        assertRefused("unsupported command change property: colour; supported here: command, visible, group, reset",
                List.of(Map.of("command", OPEN_LIST, "colour", "red")));
        assertRefused("reset of visibility contradicts visible for " + OPEN_LIST,
                List.of(Map.of("command", OPEN_LIST, "visible", Map.of("common", true), "reset", List.of("visibility"))));
        assertRefused("reset of group contradicts group for " + OPEN_LIST,
                List.of(Map.of("command", OPEN_LIST, "group", "NavigationPanelOrdinary", "reset", List.of("group"))));
        assertRefused("unknown reset value: order; allowed: visibility, group",
                List.of(Map.of("command", OPEN_LIST, "reset", List.of("order"))));
        assertRefused("visible of " + OPEN_LIST + " for Role.Manager must be true or false, got: yes",
                List.of(Map.of("command", OPEN_LIST, "visible", Map.of("roles", Map.of("Role.Manager", "yes")))));
        assertRefused("visible of " + OPEN_LIST + " names a role as Role.<name>, got: Manager",
                List.of(Map.of("command", OPEN_LIST, "visible", Map.of("roles", Map.of("Manager", true)))));
        assertRefused("command " + OPEN_LIST + " is changed twice",
                List.of(Map.of("command", OPEN_LIST, "group", "NavigationPanelOrdinary"),
                        Map.of("command", OPEN_LIST, "reset", List.of("group"))));
        assertRefused("every change names its command, such as Catalog.Products.StandardCommand.OpenList",
                List.of(Map.of("group", "NavigationPanelOrdinary")));
    }

    @Test void anOrderNamesItsGroupAndEachCommandOnce() {
        var order = CommandInterfaceEdit.order(Map.of("group", "NavigationPanelOrdinary",
                "commands", List.of(OPEN_LIST, "CommonCommand.Report")));
        assertEquals("NavigationPanelOrdinary", order.group());
        assertEquals(List.of(OPEN_LIST, "CommonCommand.Report"), order.commands());

        var twice = assertThrows(IllegalArgumentException.class, () -> CommandInterfaceEdit.order(
                Map.of("group", "NavigationPanelOrdinary", "commands", List.of(OPEN_LIST, OPEN_LIST))));
        assertEquals("order names " + OPEN_LIST + " twice", twice.getMessage());
        assertThrows(IllegalArgumentException.class, () -> CommandInterfaceEdit.order(
                Map.of("group", "NavigationPanelOrdinary", "commands", List.of())));
        assertNull(CommandInterfaceEdit.order(null));
    }

    @Test void theOrderMustListExactlyTheCommandsOfTheGroup() {
        assertDoesNotThrow(() -> CommandInterfaceEdit.checkOrder(List.of("B", "A"), List.of("A", "B"), "NavigationPanelOrdinary"));
        var error = assertThrows(IllegalArgumentException.class,
                () -> CommandInterfaceEdit.checkOrder(List.of("A"), List.of("A", "B"), "NavigationPanelOrdinary"));
        assertEquals("order of NavigationPanelOrdinary must list exactly its commands: A, B", error.getMessage());
    }

    private static void assertRefused(String message, List<Map<String, Object>> raw) {
        var error = assertThrows(IllegalArgumentException.class, () -> CommandInterfaceEdit.commands(raw));
        assertEquals(message, error.getMessage());
    }
}
