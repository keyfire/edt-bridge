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
import io.github.keyfire.edtbridge.edt.CommandInterfaceGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Read the command interface of a subsystem section as EDT derives it. */
public final class CommandInterfaceTool {
    public String name() { return "edt_command_interface"; }
    public boolean isWrite() { return false; }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", ToolJson.strProp("EDT project name; in an extension, the extension project"));
        props.add("subsystem", ToolJson.strProp("Subsystem FQN; nested form: Subsystem.Parent.Subsystem.Child"));
        return ToolJson.descriptor(name(), "Read the command interface of a subsystem section the way EDT derives "
                + "it for the client: the groups of the navigation and actions panels and, in each, the commands in "
                + "the order shown, with visibility in common and by role and whether the visibility or the group "
                + "was customized. Commands and groups are named as CommandInterface.cmi names them, such as "
                + "Catalog.Products.StandardCommand.OpenList and NavigationPanelImportant; edt_modify_command_interface "
                + "takes the same names. commandSources lists the objects the section takes commands from.",
                "Командный интерфейс раздела подсистемы так, как EDT выводит его для клиента: группы панели "
                + "навигации и панели действий, в каждой команды в порядке показа, видимость общая и по ролям, "
                + "признаки ручной настройки видимости и группы. Команды и группы названы так, как в "
                + "CommandInterface.cmi, например Catalog.Products.StandardCommand.OpenList и NavigationPanelImportant; "
                + "edt_modify_command_interface принимает те же имена. commandSources перечисляет объекты, из "
                + "которых раздел берет команды.",
                props, "projectName", "subsystem");
    }

    public JsonObject call(JsonObject args) {
        try {
            var result = new CommandInterfaceGateway().read(ToolJson.getStr(args, "projectName"), ToolJson.getStr(args, "subsystem"));
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(result));
        } catch (RuntimeException error) {
            return McpServer.toolError("edt_command_interface: " + error.getMessage());
        }
    }
}
