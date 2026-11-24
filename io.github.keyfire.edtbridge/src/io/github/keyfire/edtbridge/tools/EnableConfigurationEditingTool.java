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
package io.github.keyfire.edtbridge.tools;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.github.keyfire.edtbridge.edt.ConfigurationSupportGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Enable editing in a project on full configuration support. */
public final class EnableConfigurationEditingTool {
    public String name() {
        return "edt_enable_configuration_editing";
    }

    public JsonObject descriptor() {
        var props = new JsonObject();
        props.add("projectName", ToolJson.strProp("Name of the open EDT project"));
        props.add("apply", ToolJson.boolProp("false (default): preview. true: enable editing."));
        return ToolJson.descriptor(name(),
                "WRITE: enable editing for a project on full support through EDT."
                        + " Locks objects of suppliers converted from full support. Change individual rules afterward."
                        + " A write token and apply=true are required.",
                "ЗАПИСЬ: включить возможность изменения проекта на полной поддержке через EDT."
                        + " Правила объектов поставщиков, переведенных с полной поддержки, запрещают редактирование."
                        + " Изменить их можно отдельным вызовом. Для записи нужны токен и apply=true.",
                props, "projectName");
    }

    public JsonObject call(JsonObject args) {
        try {
            String project = ConfigurationSupportTool.required(args, "projectName");
            var result = ConfigurationSupportGateway.enableEditing(project, ConfigurationSupportTool.apply(args));
            return McpServer.textResult(new Gson().toJson(result));
        } catch (RuntimeException failure) {
            return McpServer.toolError(name() + ": " + failure.getMessage());
        } catch (LinkageError failure) {
            return McpServer.toolError(name() + ": this EDT version does not provide a compatible support API.");
        }
    }
}
