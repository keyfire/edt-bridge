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

import io.github.keyfire.edtbridge.core.Jobs;
import io.github.keyfire.edtbridge.edt.ProjectGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/** Registers an existing project or imports a configuration into a new EDT project. */
public final class ImportProjectTool {

    private final ProjectGateway gateway = new ProjectGateway();

    public String name() {
        return "edt_import_project";
    }

    /** Write tool - the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("mode", ToolJson.strProp("existing (default) registers an EDT project. configuration imports "
                + "a 1C:Enterprise configuration into a new project in the current workspace."));
        props.add("path", ToolJson.strProp("In existing mode, a directory with .project. In configuration mode, "
                + "a platform dump directory with Configuration.xml, or a .cf file."));
        props.add("name", ToolJson.strProp("Workspace project name. Required in configuration mode. Existing "
                + "mode defaults to the name declared in .project unless project scope requires an explicit name."));
        props.add("apply", ToolJson.boolProp("false (default) returns the plan. true registers, reopens, "
                + "or creates the project, depending on the mode and the current workspace state."));
        return ToolJson.descriptor(name(),
                "WRITE (Phase 2): register an existing EDT project or import a 1C:Enterprise configuration. "
                + "In existing mode, a different name lets you register two checkouts declaring the same project "
                + "name. Reopening preserves project settings and infobase associations. In configuration mode, "
                + "EDT imports the configuration into a new project in the current workspace. A .cf file "
                + "requires a local 1C:Enterprise thick client to extract XML into a temporary directory. "
                + "The tool requires a configured token. Configuration mode requires a writable name when "
                + "project scope is enabled. Returns the workspace name, location and open state.",
                "ЗАПИСЬ (Phase 2): зарегистрировать существующий проект EDT или импортировать конфигурацию "
                + "1С:Предприятия. В режиме existing можно задать другое имя для второго рабочего дерева "
                + "с тем же именем в .project. При повторном открытии настройки и связи с базами сохраняются. "
                + "В режиме configuration EDT импортирует конфигурацию в новый проект текущей рабочей области. "
                + "Для извлечения XML из файла .cf во временный каталог требуется локальный толстый клиент "
                + "1С:Предприятия. Для вызова нужен токен. При ограничении доступа к проектам новое имя "
                + "должно входить в список проектов, доступных для записи. Результат содержит имя проекта, "
                + "путь и признак открытого проекта.",
                props, "path");
    }

    public JsonObject call(JsonObject args) {
        String path = ToolJson.getStr(args, "path");
        if (path == null) {
            return McpServer.toolError("path is required");
        }
        try {
            ProjectGateway.ImportProjectResult res = gateway.importProject(
                    path, ToolJson.getStr(args, "name"), ToolJson.getStr(args, "mode"), ToolJson.getBool(args, "apply"));
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            o.addProperty("name", res.name);
            if (res.declaredName != null) {
                o.addProperty("declaredName", res.declaredName);
            }
            o.addProperty("location", res.location);
            o.addProperty("directory", res.directory);
            o.addProperty("descriptor", res.descriptor);
            o.addProperty("already", res.already);
            o.addProperty("nameTaken", res.nameTaken);
            o.addProperty("insideWorkspace", res.insideWorkspace);
            o.addProperty("open", res.open);
            if (res.mode != null) {
                o.addProperty("mode", res.mode);
                o.addProperty("sourcePath", res.sourcePath);
                o.addProperty("sourceKind", res.sourceKind);
                o.addProperty("version", res.version);
            }
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.warning != null) {
                o.addProperty("warning", res.warning);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            String payload = new GsonBuilder().setPrettyPrinting().create().toJson(o);
            return !res.ok && Jobs.inJob() ? McpServer.toolError(payload) : McpServer.textResult(payload);
        } catch (Exception e) {
            return McpServer.toolError("edt_import_project failed: " + e.getMessage());
        }
    }
}
