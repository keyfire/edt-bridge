/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NewObjectsTest {
    @Test void aNewRoleGetsTheMainWindowModesAndTheAnalyticsClientInTheDesignerOrder() {
        Set<String> configuration = Set.of("Administration", "AnalyticsSystemClient", "ThinClient",
                "MainWindowModeKiosk", "MainWindowModeNormal", "MainWindowModeWorkplace",
                "MainWindowModeEmbeddedWorkplace", "MainWindowModeFullscreenWorkplace");
        assertEquals(NewObjects.ROLE_CONFIGURATION_RIGHTS, NewObjects.roleConfigurationRights(configuration));
    }

    @Test void aRightTheConfigurationLacksIsLeftOut() {
        assertEquals(List.of("MainWindowModeNormal", "MainWindowModeWorkplace"),
                NewObjects.roleConfigurationRights(List.of("MainWindowModeWorkplace", "MainWindowModeNormal", "Output")));
        assertEquals(List.of(), NewObjects.roleConfigurationRights(List.of()));
        assertEquals(List.of(), NewObjects.roleConfigurationRights(null));
    }

    @Test void anExtensionNameGetsThePrefixOnce() {
        assertEquals("Расш_Менеджер", NewObjects.extensionName("Менеджер", "Расш_"));
        assertEquals("Расш_Менеджер", NewObjects.extensionName("Расш_Менеджер", "Расш_"));
        assertEquals("расш_Менеджер", NewObjects.extensionName("расш_Менеджер", "Расш_"));
    }

    @Test void withoutAPrefixTheNameStaysAsGiven() {
        assertEquals("Менеджер", NewObjects.extensionName("Менеджер", null));
        assertEquals("Менеджер", NewObjects.extensionName("Менеджер", ""));
        assertEquals("Менеджер", NewObjects.extensionName("Менеджер", "  "));
        assertNull(NewObjects.extensionName(null, "Расш_"));
    }
}
