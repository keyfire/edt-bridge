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
package com.e1c.fresh.edtbridge;

import com.e1c.fresh.edtbridge.mcp.McpServer;

/**
 * Headless start path: an Equinox DS component (immediate, declared in OSGI-INF/edtbridge-mcp.xml)
 * that starts the MCP server as soon as the bundle is wired by Declarative Services - so the server
 * runs even without the UI workbench (console / headless EDT), not only via the UI EarlyStartup.
 * {@link McpServer#startQuietly()} is idempotent, so co-existing with EarlyStartup in the GUI is safe.
 */
public final class McpServerComponent {

    public void activate() {
        McpServer.getInstance().startQuietly();
    }

    public void deactivate() {
        McpServer.getInstance().stop();
    }
}
