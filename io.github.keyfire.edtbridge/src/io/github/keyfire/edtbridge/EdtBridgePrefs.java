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
package io.github.keyfire.edtbridge;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;

/**
 * Persistent settings for the bridge, stored in Eclipse's instance (workspace) preferences under the
 * bundle's node and edited from the "edt-bridge" preference page. The server reads these as a fallback
 * after launch-time configuration (a {@code -Dedt.bridge.*} system property or an {@code EDT_BRIDGE_*}
 * environment variable), so a GUI EDT started from a plain shortcut – which has neither – can still
 * authenticate write tools with a token set once in the UI.
 */
public final class EdtBridgePrefs {

    /** The preferences node id (also used by the preference page's ScopedPreferenceStore). */
    public static final String NODE = "io.github.keyfire.edtbridge";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_PORT = "port";
    public static final String KEY_ALLOW_EVALUATE = "allowEvaluate";

    private EdtBridgePrefs() {
    }

    /** A trimmed non-blank preference value, or {@code null} (also null if preferences are unavailable). */
    public static String get(String key) {
        try {
            IEclipsePreferences node = InstanceScope.INSTANCE.getNode(NODE);
            String v = node.get(key, null);
            return (v == null || v.isBlank()) ? null : v.trim();
        } catch (RuntimeException e) {
            return null; // preferences service not ready / headless without an instance area
        }
    }

    /** A boolean preference: true only for {@code "true"} / {@code "1"} (case-insensitive). */
    public static boolean getBool(String key) {
        String v = get(key);
        return v != null && (v.equalsIgnoreCase("true") || v.equals("1"));
    }
}
