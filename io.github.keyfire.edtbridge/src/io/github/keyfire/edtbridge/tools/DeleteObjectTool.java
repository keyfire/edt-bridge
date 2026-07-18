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

import io.github.keyfire.edtbridge.edt.EdtModelGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_delete_object - WRITE (Phase 2). The inverse of edt_create_object: deletes a metadata
 * object (a top object such as Catalog.X, or a child member) and cascades the removal of every reference
 * in metadata AND BSL using EDT's OWN refactoring engine – not a brittle text edit. Dry-run by default
 * (apply=false) returns the engine's change list + validation problems. Deleting an object is
 * irreversible and breaking for peer configurations: an apply requires a configured token AND force=true
 * (the owner's explicit override); the caller must verify bsl_support_status EDITABLE before apply.
 */
public final class DeleteObjectTool {

    private final EdtModelGateway gateway = new EdtModelGateway();

    public String name() {
        return "edt_delete_object";
    }

    /** Write tool – the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name"));
        props.add("targetFqn", strProp("Object or child member to delete, by full FQN: a top object "
                + "(e.g. Catalog.Контрагенты) or a child (e.g. Catalog.Контрагенты.Attribute.ИНН)"));
        props.add("apply", boolProp("false (default) = dry-run: build the delete refactoring and return the "
                + "full change list + problems, delete nothing. true = perform the cascade (metadata + BSL). "
                + "Verify bsl_support_status EDITABLE first."));
        props.add("force", boolProp("false (default) = refuse apply (delete is irreversible and breaking for "
                + "peer configurations). true = the owner's explicit override; required for any apply. Has no "
                + "effect on a dry-run."));

        JsonArray req = new JsonArray();
        req.add("projectName");
        req.add("targetFqn");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE (Phase 2): delete a metadata object (top object or child member) and cascade the "
                + "removal of every reference in metadata AND BSL via EDT's native refactoring engine; the "
                + "engine removes the object's .mdo and updates the Configuration. Dry-run by default: returns "
                + "the engine's change list + validation problems WITHOUT deleting. apply=true performs the "
                + "cascade; requires a configured token AND force=true (delete is irreversible and breaking for "
                + "peer configurations – owner approval). Verify bsl_support_status EDITABLE before apply.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ (Phase 2): удалить объект метаданных (объект или его член) с каскадным удалением ВСЕХ "
                + "ссылок в метаданных И в BSL через штатный движок рефакторинга EDT; движок сам удаляет .mdo "
                + "объекта и правит Configuration. По умолчанию dry-run: возвращает список изменений и проблемы "
                + "БЕЗ удаления. apply=true выполняет каскад; требует токен И force=true (удаление необратимо и "
                + "ломает обратную совместимость для конфигураций-партнёров – нужно одобрение владельца). Перед "
                + "apply проверить bsl_support_status = EDITABLE.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        String targetFqn = getStr(args, "targetFqn");
        if (project == null || targetFqn == null) {
            return McpServer.toolError("projectName and targetFqn are required");
        }
        boolean apply = args.has("apply") && !args.get("apply").isJsonNull() && args.get("apply").getAsBoolean();
        boolean force = args.has("force") && !args.get("force").isJsonNull() && args.get("force").getAsBoolean();
        try {
            EdtModelGateway.DeleteObjectResult res = gateway.deleteObject(project, targetFqn, apply, force);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            o.addProperty("targetFqn", res.targetFqn);
            o.addProperty("targetFound", res.targetFound);
            if (res.targetType != null) {
                o.addProperty("targetType", res.targetType);
            }
            o.addProperty("topObject", res.topObject);
            if (res.name != null) {
                o.addProperty("name", res.name);
            }
            o.addProperty("force", res.forced);
            o.addProperty("refactoringCount", res.refactoringCount);
            if (!res.problems.isEmpty()) {
                JsonArray probs = new JsonArray();
                for (EdtModelGateway.RenameProblem pr : res.problems) {
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
            for (EdtModelGateway.RenameItem it : res.items) {
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
            return McpServer.toolError("edt_delete_object failed: " + e.getMessage());
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
