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

import org.eclipse.ui.IStartup;

import com.e1c.fresh.edtbridge.mcp.McpServer;

/**
 * Registered via org.eclipse.ui.startup so EDT activates this bundle on launch.
 * (Headless alternative: an Equinox DS component with immediate=true.)
 */
public class EarlyStartup implements IStartup {

    @Override
    public void earlyStartup() {
        McpServer.getInstance().startQuietly();
    }
}
