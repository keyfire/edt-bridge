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

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.edt.PropertyReferenceGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** The properties a write tool takes for one target, with their types and accepted values. */
public final class PropertyReferenceTool {
    private final PropertyReferenceGateway gateway = new PropertyReferenceGateway();

    public String name() { return "edt_property_reference"; }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", ToolJson.strProp("EDT project name"));
        props.add("fqn", ToolJson.strProp("What to describe: a common module (CommonModule.Api), a field "
                + "(Catalog.Products.Attribute.Weight), an external object (ExternalReport.Summary), an extension root (Configuration) or a form "
                + "(Catalog.Products.Form.ItemForm, ExternalDataProcessor.Check.Form.Main)"));
        props.add("item", ToolJson.strProp("Form item name, when fqn is a form. Mutually exclusive with command. "
                + "Omitted on a form returns the form's own properties plus its item and command names."));
        props.add("command", ToolJson.strProp("Form command name, when fqn is a form. Mutually exclusive with item."));
        return ToolJson.descriptor(name(),
                "READ: list editable properties of a common module, external object, extension, field, form, "
                + "form item or form command. "
                + "Returns each property's type, accepted values and current value, and names the "
                + "tool that takes them. Call it before edt_modify_object, edt_modify_attribute, "
                + "edt_modify_form_item or edt_modify_form_command instead of trying spellings: the list is "
                + "built by the code that validates the write, for this very object, so a property the "
                + "object lacks is not listed.",
                "ЧТЕНИЕ: перечислить свойства общего модуля, внешнего объекта, расширения, реквизита, формы, "
                + "элемента формы или команды формы. "
                + "Возвращает тип каждого свойства, допустимые значения и "
                + "текущее значение, а также имя инструмента, который их принимает. Вызывать перед edt_modify_object, "
                + "edt_modify_attribute, edt_modify_form_item или edt_modify_form_command вместо перебора написаний: "
                + "перечень строит код, который проверяет запись, для этого же объекта, поэтому отсутствующее у "
                + "объекта свойство в перечень не попадает.",
                props, "projectName", "fqn");
    }

    public JsonObject call(JsonObject args) {
        try {
            var result = gateway.reference(ToolJson.getStr(args, "projectName"), ToolJson.getStr(args, "fqn"),
                    ToolJson.getStr(args, "item"), ToolJson.getStr(args, "command"));
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (RuntimeException failure) {
            return McpServer.toolError("edt_property_reference failed: " + failure.getMessage());
        }
    }
}
