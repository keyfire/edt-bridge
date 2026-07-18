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
 * MCP tool: edt_modify_attribute - WRITE (Phase 2). Changes an existing attribute's type, ru synonym
 * and/or comment. Changing the TYPE may break backward compatibility (data + code) – a warning is
 * returned. Dry-run by default (apply=false). Requires a configured token; the caller must verify
 * bsl_support_status EDITABLE before apply.
 */
public final class ModifyAttributeTool {

    private final MetadataWriteGateway gateway = new MetadataWriteGateway();

    public String name() {
        return "edt_modify_attribute";
    }

    /** Write tool – the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name"));
        props.add("ownerFqn", strProp("Object that holds the attribute, e.g. Catalog.Контрагенты"));
        props.add("name", strProp("Existing attribute name to modify (Cyrillic)"));
        props.add("newType", strProp("New value type (same syntax as edt_add_attribute). Optional – omit to keep."));
        props.add("newSynonymRu", strProp("New Russian synonym. Optional; empty string clears it."));
        props.add("newComment", strProp("New comment. Optional; empty string clears it."));
        props.add("apply", boolProp("false (default) = dry-run: validate + return current→new plan, change nothing. "
                + "true = perform the change (commit + serialize). Verify bsl_support_status EDITABLE first."));

        JsonArray req = new JsonArray();
        req.add("projectName");
        req.add("ownerFqn");
        req.add("name");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE (Phase 2): modify an existing attribute – type, ru synonym and/or comment (provide at "
                + "least one). Dry-run by default: validates and returns the current→new plan WITHOUT changing. "
                + "apply=true performs the change. Changing the type may break backward compatibility (warned). "
                + "Requires a configured token. Verify bsl_support_status EDITABLE before apply.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ (Phase 2): изменить существующий реквизит – тип, синоним(ru) и/или comment (хотя бы одно). "
                + "По умолчанию dry-run: проверяет и возвращает план было→стало БЕЗ изменения. apply=true применяет. "
                + "Смена типа может нарушить обратную совместимость (предупреждение). Требует токен. Перед apply "
                + "проверить bsl_support_status = EDITABLE.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        String ownerFqn = getStr(args, "ownerFqn");
        String name = getStr(args, "name");
        if (project == null || ownerFqn == null || name == null) {
            return McpServer.toolError("projectName, ownerFqn and name are required");
        }
        String newType = getStr(args, "newType");
        String newSynonymRu = has(args, "newSynonymRu") ? args.get("newSynonymRu").getAsString() : null;
        String newComment = has(args, "newComment") ? args.get("newComment").getAsString() : null;
        boolean apply = args.has("apply") && !args.get("apply").isJsonNull() && args.get("apply").getAsBoolean();
        try {
            MetadataWriteGateway.ModifyAttrResult res =
                    gateway.modifyAttribute(project, ownerFqn, name, newType, newSynonymRu, newComment, apply);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            o.addProperty("ownerFqn", res.ownerFqn);
            o.addProperty("ownerFound", res.ownerFound);
            if (res.ownerType != null) {
                o.addProperty("ownerType", res.ownerType);
            }
            o.addProperty("name", res.name);
            o.addProperty("attrFound", res.attrFound);
            if (res.currentType != null) {
                o.addProperty("currentType", res.currentType);
            }
            if (res.typeChange) {
                o.addProperty("typeChange", true);
                o.addProperty("newType", res.newType);
                o.addProperty("typeValid", res.typeValid);
                if (res.refFqn != null) {
                    o.addProperty("refFqn", res.refFqn);
                    o.addProperty("refResolved", Boolean.TRUE.equals(res.refResolved));
                }
            }
            if (res.synonymChange) {
                o.addProperty("synonymChange", true);
            }
            if (res.commentChange) {
                o.addProperty("commentChange", true);
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
            return McpServer.toolError("edt_modify_attribute failed: " + e.getMessage());
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

    private static boolean has(JsonObject a, String k) {
        return a.has(k) && !a.get(k).isJsonNull();
    }

    private static String getStr(JsonObject a, String k) {
        return has(a, k) ? a.get(k).getAsString() : null;
    }
}
