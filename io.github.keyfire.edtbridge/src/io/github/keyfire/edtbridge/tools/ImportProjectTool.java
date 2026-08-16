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

import io.github.keyfire.edtbridge.edt.ProjectGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_import_project - WRITE (Phase 2). Registers an EXISTING project directory in the
 * workspace: "Import existing project" without the dialog. The create/work/delete cycle had no such
 * step, so a second project - the base one an extension in "modification and control" mode is
 * validated against, taken from a worktree of the target release - could only be added by hand in
 * the GUI, and the work stopped there.
 */
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
        props.add("path", ToolJson.strProp("Directory holding the project (it must contain a .project "
                + "file). Nothing on disk is rewritten."));
        props.add("name", ToolJson.strProp("Name to register it under. Default: the name its .project "
                + "declares. Two checkouts of one repository declare the SAME name and the workspace "
                + "allows a name once - overriding it here is what lets the second one in."));
        props.add("apply", ToolJson.boolProp("false (default) = dry-run: report what would be "
                + "registered and under which name. true = register the project and open it."));
        return ToolJson.descriptor(name(),
                "WRITE (Phase 2): register an EXISTING project directory in the workspace - the "
                + "programmatic form of \"Import existing project\". Needed whenever a second project "
                + "must join the workspace: an extension in modification-and-control mode is checked "
                + "against a BASE project on the target release, and that one comes from another "
                + "worktree. Sources are not touched - only the workspace learns about them. Reports "
                + "the declared name, the name it will take and whether that name is already busy.",
                "ЗАПИСЬ (Phase 2): зарегистрировать в workspace СУЩЕСТВУЮЩИЙ каталог проекта - "
                + "программный аналог \"Импорт существующего проекта\". Нужно, когда в workspace "
                + "должен появиться второй проект: расширение в режиме изменения и контроля сверяется "
                + "с БАЗОВЫМ проектом на целевом релизе, а он лежит в другом worktree. Исходники не "
                + "трогаются - о них узнаёт только workspace. Возвращает объявленное имя, имя под "
                + "которым проект будет зарегистрирован, и занято ли оно.",
                props, "path");
    }

    public JsonObject call(JsonObject args) {
        String path = ToolJson.getStr(args, "path");
        if (path == null) {
            return McpServer.toolError("path is required");
        }
        try {
            ProjectGateway.ImportProjectResult res = gateway.importProject(
                    path, ToolJson.getStr(args, "name"), ToolJson.getBool(args, "apply"));
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
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.warning != null) {
                o.addProperty("warning", res.warning);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_import_project failed: " + e.getMessage());
        }
    }
}
