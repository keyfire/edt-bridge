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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_delete_project - WRITE (Phase 2). Removes a project from the workspace, closing the
 * create/work/delete cycle the project-creating tools open. The delete goes through the Eclipse
 * workspace so its resource tree is updated - removing a project folder by hand instead leaves a
 * ghost project that returns from the tree snapshot and keeps the name taken. Destructive:
 * dry-run by default and {@code force} required to apply.
 */
public final class DeleteProjectTool {

    private final ProjectGateway gateway = new ProjectGateway();

    public String name() {
        return "edt_delete_project";
    }

    /** Write tool - the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("Project to remove from the workspace"));
        props.add("deleteContent", boolProp("true = also erase the project's files from disk "
                + "(irreversible). false (default) = unregister the project and keep the files."));
        props.add("force", boolProp("Required for apply: deleting a project is irreversible."));
        props.add("apply", boolProp("false (default) = dry-run: report what would be removed (location, "
                + "file count, whether it is a ghost) and delete nothing. true = perform the delete "
                + "(force must also be true)."));

        JsonArray req = new JsonArray();
        req.add("projectName");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE (Phase 2): remove a project from the workspace - the delete half of the cycle that "
                + "edt_create_extension / edt_create_external_object start. Goes through the Eclipse "
                + "workspace so the resource tree is updated; deleting a project folder by hand instead "
                + "leaves a ghost project that comes back from the tree snapshot and keeps the name "
                + "taken. deleteContent=false (default) unregisters and keeps the files, true erases "
                + "them. Dry-run by default; force=true required to apply.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ (Phase 2): удалить проект из workspace - вторая половина цикла, который открывают "
                + "edt_create_extension / edt_create_external_object. Удаление идёт через workspace "
                + "Eclipse, поэтому дерево ресурсов обновляется; если снести каталог проекта руками, "
                + "остаётся фантом - проект возвращается из снимка дерева и держит имя занятым. "
                + "deleteContent=false (по умолчанию) снимает регистрацию и оставляет файлы, true - "
                + "стирает их. По умолчанию dry-run; для применения нужен force=true.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        if (project == null) {
            return McpServer.toolError("projectName is required");
        }
        boolean deleteContent = getBool(args, "deleteContent");
        boolean force = getBool(args, "force");
        boolean apply = getBool(args, "apply");
        try {
            ProjectGateway.DeleteProjectResult res =
                    gateway.deleteProject(project, deleteContent, force, apply);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            o.addProperty("name", res.name);
            o.addProperty("exists", res.exists);
            o.addProperty("open", res.open);
            o.addProperty("contentOnDisk", res.contentOnDisk);
            o.addProperty("deleteContent", res.deleteContent);
            if (res.location != null) {
                o.addProperty("location", res.location);
            }
            if (res.fileCount >= 0) {
                o.addProperty("fileCount", res.fileCount);
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
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_delete_project failed: " + e.getMessage());
        }
    }

    private static JsonObject strProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        o.addProperty("description", desc);
        return o;
    }

    private static JsonObject boolProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "boolean");
        o.addProperty("description", desc);
        return o;
    }

    private static String getStr(JsonObject a, String k) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? a.get(k).getAsString() : null;
    }

    private static boolean getBool(JsonObject a, String k) {
        return a.has(k) && !a.get(k).isJsonNull() && a.get(k).getAsBoolean();
    }
}
