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
package io.github.keyfire.edtbridge.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * What a new object starts with: the name of an object an extension owns, and the rights of a
 * new role.
 *
 * <p>The designer of the platform gives a new role of a configuration the main window modes and
 * the analytics system client on the configuration, and nothing else. A role of an extension
 * gets no rights on the configuration: an extension cannot set them.
 */
public final class NewObjects {
    private NewObjects() { }

    /** The rights a new role has on the configuration, in the order the designer lists them. */
    public static final List<String> ROLE_CONFIGURATION_RIGHTS = List.of(
            "MainWindowModeNormal",
            "MainWindowModeWorkplace",
            "MainWindowModeEmbeddedWorkplace",
            "MainWindowModeFullscreenWorkplace",
            "MainWindowModeKiosk",
            "AnalyticsSystemClient");

    /**
     * The rights of {@link #ROLE_CONFIGURATION_RIGHTS} that the configuration has. A configuration
     * in an older compatibility mode lacks some of them, and those are left out.
     */
    public static List<String> roleConfigurationRights(Collection<String> available) {
        List<String> rights = new ArrayList<>();
        if (available == null) return rights;
        for (String right : ROLE_CONFIGURATION_RIGHTS) {
            if (available.contains(right)) rights.add(right);
        }
        return rights;
    }

    /**
     * The name of an object an extension owns. The extension's prefix goes first, unless the name
     * already starts with it; names in 1C ignore case, so neither does this check.
     */
    public static String extensionName(String name, String prefix) {
        if (name == null || prefix == null || prefix.isBlank()) return name;
        if (name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) return name;
        return prefix + name;
    }
}
