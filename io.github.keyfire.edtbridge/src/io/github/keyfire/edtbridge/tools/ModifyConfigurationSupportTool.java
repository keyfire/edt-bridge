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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.github.keyfire.edtbridge.edt.ConfigurationSupportGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Change support rules for selected objects of one connected supplier. */
public final class ModifyConfigurationSupportTool {
    public String name() {
        return "edt_modify_configuration_support";
    }

    public JsonObject descriptor() {
        var props = ConfigurationSupportTool.properties();
        var fqns = props.getAsJsonObject("fqns");
        fqns.addProperty("minItems", 1);
        fqns.addProperty("uniqueItems", true);
        var mode = ToolJson.strProp("New user support mode");
        var modes = new JsonArray();
        modes.add("CHANGES_ALLOWED");
        modes.add("CANCELLED");
        modes.add("CHANGES_NOT_ALLOWED");
        mode.add("enum", modes);
        props.add("mode", mode);
        props.add("apply", ToolJson.boolProp("false (default): preview. true: apply the proposed rules."));
        return ToolJson.descriptor(name(),
                "WRITE: preview or change the support rules of selected objects for a connected supplier."
                        + " CHANGES_NOT_ALLOWED requires a matching top-level object in EDT's comparison with"
                        + " the recorded supplier release. A write token and apply=true are required to write.",
                "ЗАПИСЬ: показать или изменить правила поддержки выбранных объектов подключенного поставщика."
                        + " Режим CHANGES_NOT_ALLOWED требует совпадения объекта верхнего уровня при сравнении"
                        + " в EDT с указанным в настройках релизом поставщика. Для записи нужны токен и apply=true.",
                props, "projectName", "supplierId", "fqns", "mode");
    }

    public JsonObject call(JsonObject args) {
        try {
            String project = ConfigurationSupportTool.required(args, "projectName");
            var supplier = ConfigurationSupportTool.supplierId(args);
            var fqns = ConfigurationSupportTool.fqns(args);
            if (supplier == null || fqns.isEmpty()) {
                throw new IllegalArgumentException("supplierId and nonempty fqns are required");
            }
            var mode = ConfigurationSupportTool.required(args, "mode");
            var result = ConfigurationSupportGateway.modify(project, fqns, supplier, mode,
                    ConfigurationSupportTool.apply(args));
            return McpServer.textResult(new Gson().toJson(result));
        } catch (RuntimeException failure) {
            return McpServer.toolError(name() + ": " + failure.getMessage());
        } catch (LinkageError failure) {
            return McpServer.toolError(name() + ": this EDT version does not provide a compatible support API.");
        }
    }
}
