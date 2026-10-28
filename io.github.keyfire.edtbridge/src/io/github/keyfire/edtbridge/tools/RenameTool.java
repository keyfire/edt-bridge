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

import io.github.keyfire.edtbridge.edt.MetadataWriteGateway;
import io.github.keyfire.edtbridge.edt.BslRefactorGateway;
import io.github.keyfire.edtbridge.core.BslRenameTarget;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_rename - WRITE (Phase 2). Renames a metadata object or a child member
 * (attribute / dimension / resource / ...) and cascades every reference in metadata AND BSL using
 * EDT's OWN refactoring engine – not a brittle text replace. Dry-run by default (apply=false) returns
 * the engine's full change list + validation problems. Renaming is the widest backward-compatibility
 * surface: an apply requires a configured token AND force=true (the owner's explicit
 * breaking-change override); the caller must verify bsl_support_status EDITABLE before apply.
 */
public final class RenameTool {

    private final MetadataWriteGateway gateway = new MetadataWriteGateway();
    private final BslRefactorGateway bsl = new BslRefactorGateway();

    public String name() {
        return "edt_rename";
    }

    /** Write tool – the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name"));
        props.add("targetFqn", strProp("Object or child member to rename, by full FQN: a top object "
                + "(e.g. Catalog.Контрагенты) or a child (e.g. Catalog.Контрагенты.Attribute.ИНН)"));
        props.add("method", strProp("Compatible method alias: a short method name with targetFqn, or a full "
                + "method FQN such as CommonModule.Api.Run. Conflicting methodName/targetFqn values are refused."));
        props.add("methodName", strProp("BSL method mode: rename this unique procedure/function through EDT's "
                + "native refactoring. Supply modulePath or targetFqn as the module owner."));
        props.add("modulePath", strProp("Method mode: project-relative BSL module path."));
        props.add("moduleType", strProp("Method mode: ObjectModule, ManagerModule, etc. when targetFqn owns several modules."));
        props.add("newName", strProp("New name (a valid 1C identifier, Cyrillic allowed)"));
        props.add("apply", boolProp("false (default) = dry-run: build the refactoring and return the full "
                + "change list + problems, perform nothing. true = perform the cascade (metadata + BSL). "
                + "Verify bsl_support_status EDITABLE first."));
        props.add("force", boolProp("false (default) = refuse apply (rename is breaking for peer "
                + "configurations). true = the owner's explicit breaking-change override; "
                + "required for any apply. Has no effect on a dry-run."));

        JsonArray req = new JsonArray();
        req.add("projectName");
        req.add("newName");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE (Phase 2): rename a BSL method (methodName mode), metadata object or child member (attribute/dimension/"
                + "resource/...) and cascade every reference in metadata AND BSL via EDT's native refactoring "
                + "engine. Preview reports known file paths and nativeTextEditCount. editCount is null when model operations expose no text edits; coverageComplete=false identifies unmapped metadata items. Dry-run by default: returns the engine's change list + validation problems WITHOUT "
                + "changing. apply=true performs the cascade; requires a configured token AND force=true (rename "
                + "is breaking for peer configurations –, owner approval). Verify bsl_support_status "
                + "EDITABLE before apply.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ (Phase 2): переименовать метод BSL (methodName), объект метаданных или его член (реквизит/измерение/"
                + "ресурс/...) с каскадным обновлением ВСЕХ ссылок в метаданных И в BSL через штатный движок "
                + "рефакторинга EDT. Предпросмотр возвращает известные пути и nativeTextEditCount; editCount=null означает отсутствие текстового счетчика, coverageComplete=false отмечает неполное покрытие. По умолчанию dry-run: возвращает список изменений и проблемы БЕЗ применения. "
                + "apply=true выполняет каскад; требует токен И force=true (переименование ломает обратную "
                + "совместимость для конфигураций-партнёров –, нужно одобрение владельца). Перед apply "
                + "проверить bsl_support_status = EDITABLE.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        String targetFqn = getStr(args, "targetFqn");
        String newName = getStr(args, "newName");
        String methodName = getStr(args, "methodName");
        try {
            BslRenameTarget.Target target = BslRenameTarget.resolve(targetFqn, methodName, getStr(args, "method"));
            if (target != null) {
                targetFqn = target.owner();
                methodName = target.methodName();
            }
        } catch (IllegalArgumentException exception) {
            return McpServer.toolError(exception.getMessage());
        }
        String modulePath = getStr(args, "modulePath");
        if (project == null || newName == null || (targetFqn == null && modulePath == null)) {
            return McpServer.toolError("projectName, newName and targetFqn (or modulePath in method mode) are required");
        }
        if (methodName == null && targetFqn == null) {
            return McpServer.toolError("metadata mode requires targetFqn; method mode requires methodName");
        }
        boolean apply = args.has("apply") && !args.get("apply").isJsonNull() && args.get("apply").getAsBoolean();
        boolean force = args.has("force") && !args.get("force").isJsonNull() && args.get("force").getAsBoolean();
        try {
            if (methodName != null) {
                BslRefactorGateway.Result result = bsl.renameMethod(project, targetFqn,
                        getStr(args, "moduleType"), modulePath, methodName, newName, apply, force);
                return McpServer.textResult(new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(result));
            }
            MetadataWriteGateway.RenameResult res = gateway.renameObject(project, targetFqn, newName, apply, force);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            o.addProperty("targetFqn", res.targetFqn);
            o.addProperty("targetFound", res.targetFound);
            if (res.targetType != null) {
                o.addProperty("targetType", res.targetType);
            }
            o.addProperty("topObject", res.topObject);
            if (res.currentName != null) {
                o.addProperty("currentName", res.currentName);
            }
            o.addProperty("newName", res.newName);
            o.addProperty("nameValid", res.nameValid);
            o.addProperty("force", res.forced);
            o.addProperty("refactoringCount", res.refactoringCount);
            o.addProperty("targetPath", res.targetPath);
            o.addProperty("affectedFileCount", res.affectedFileCount);
            o.addProperty("nativeChangeCount", res.nativeChangeCount);
            o.addProperty("nativeTextEditCount", res.nativeTextEditCount);
            o.add("editCount", res.editCount == null ? com.google.gson.JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(res.editCount));
            o.addProperty("editCountComplete", res.editCountComplete);
            o.addProperty("coverageComplete", res.coverageComplete);
            o.addProperty("selectedItemCount", res.selectedItemCount);
            o.addProperty("unmappedItemCount", res.unmappedItemCount);
            o.addProperty("unknownChangeCount", res.unknownChangeCount);
            o.add("unmappedItems", new GsonBuilder().create().toJsonTree(res.unmappedItems));
            o.add("affectedFiles", new GsonBuilder().serializeNulls().create().toJsonTree(res.affectedFiles));
            if (!res.problems.isEmpty()) {
                JsonArray probs = new JsonArray();
                for (MetadataWriteGateway.RenameProblem pr : res.problems) {
                    JsonObject po = new JsonObject();
                    po.addProperty("kind", pr.kind);
                    if (pr.object != null) {
                        po.addProperty("object", pr.object);
                    }
                    probs.add(po);
                }
                o.add("problems", probs);
            }
            JsonArray items = new JsonArray();
            for (MetadataWriteGateway.RenameItem it : res.items) {
                JsonObject io = new JsonObject();
                io.addProperty("name", it.name);
                io.addProperty("optional", it.optional);
                io.addProperty("checked", it.checked);
                items.add(io);
            }
            o.add("items", items);
            o.addProperty("itemCount", res.items.size());
            o.addProperty("truncated", res.truncated);
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.warning != null) {
                o.addProperty("warning", res.warning);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_rename failed: " + e.getMessage());
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
}
