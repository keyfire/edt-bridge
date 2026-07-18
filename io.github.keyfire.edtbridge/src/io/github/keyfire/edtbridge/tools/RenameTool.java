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
        props.add("newName", strProp("New name (a valid 1C identifier, Cyrillic allowed)"));
        props.add("apply", boolProp("false (default) = dry-run: build the refactoring and return the full "
                + "change list + problems, perform nothing. true = perform the cascade (metadata + BSL). "
                + "Verify bsl_support_status EDITABLE first."));
        props.add("force", boolProp("false (default) = refuse apply (rename is breaking for peer "
                + "configurations). true = the owner's explicit breaking-change override; "
                + "required for any apply. Has no effect on a dry-run."));

        JsonArray req = new JsonArray();
        req.add("projectName");
        req.add("targetFqn");
        req.add("newName");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE (Phase 2): rename a metadata object or child member (attribute/dimension/"
                + "resource/...) and cascade every reference in metadata AND BSL via EDT's native refactoring "
                + "engine. Dry-run by default: returns the engine's change list + validation problems WITHOUT "
                + "changing. apply=true performs the cascade; requires a configured token AND force=true (rename "
                + "is breaking for peer configurations –, owner approval). Verify bsl_support_status "
                + "EDITABLE before apply.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ (Phase 2): переименовать объект метаданных или его член (реквизит/измерение/"
                + "ресурс/...) с каскадным обновлением ВСЕХ ссылок в метаданных И в BSL через штатный движок "
                + "рефакторинга EDT. По умолчанию dry-run: возвращает список изменений и проблемы БЕЗ применения. "
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
        if (project == null || targetFqn == null || newName == null) {
            return McpServer.toolError("projectName, targetFqn and newName are required");
        }
        boolean apply = args.has("apply") && !args.get("apply").isJsonNull() && args.get("apply").getAsBoolean();
        boolean force = args.has("force") && !args.get("force").isJsonNull() && args.get("force").getAsBoolean();
        try {
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
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
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
