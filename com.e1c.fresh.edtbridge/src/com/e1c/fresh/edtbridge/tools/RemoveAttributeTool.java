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
package com.e1c.fresh.edtbridge.tools;

import com.e1c.fresh.edtbridge.edt.EdtModelGateway;
import com.e1c.fresh.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_remove_attribute - WRITE (Phase 2). Removes an attribute from a metadata object.
 * DESTRUCTIVE: removing an attribute is a schema change (drops the column in a real infobase). Checks
 * inbound references first (BM cross-reference index); if the attribute is referenced, removal is
 * blocked unless force=true. Dry-run by default (apply=false) reports the references and the plan.
 * Requires a configured token; the caller must verify bsl_support_status EDITABLE before apply.
 */
public final class RemoveAttributeTool {

    private final EdtModelGateway gateway = new EdtModelGateway();

    public String name() {
        return "edt_remove_attribute";
    }

    /** Write tool — the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name"));
        props.add("ownerFqn", strProp("Object to remove the attribute from, e.g. Catalog.Контрагенты"));
        props.add("name", strProp("Attribute name to remove (Cyrillic)"));
        props.add("apply", boolProp("false (default) = dry-run: report references + plan, remove nothing. "
                + "true = perform the removal (commit + serialize). Verify bsl_support_status EDITABLE first."));
        props.add("force", boolProp("false (default) = refuse if the attribute has inbound references. "
                + "true = remove even if referenced (WILL break those references — last resort)."));

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
                "WRITE (Phase 2): remove an attribute from a metadata object. DESTRUCTIVE — a schema "
                + "change. Dry-run by default: checks inbound references (BM xref) and returns the plan "
                + "WITHOUT removing. apply=true removes (commit + serialize); blocked if referenced unless "
                + "force=true. Requires a configured token. Verify bsl_support_status EDITABLE before apply "
                + ".");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ (Phase 2): удалить реквизит объекта метаданных. ДЕСТРУКТИВНО – изменение схемы. "
                + "По умолчанию dry-run: проверяет входящие ссылки (BM xref) и возвращает план БЕЗ удаления. "
                + "apply=true удаляет (фиксирует + сериализует); если на реквизит ссылаются – блокируется, "
                + "пока не задан force=true. Требует токен. Перед apply проверить bsl_support_status = EDITABLE.");
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
        boolean apply = args.has("apply") && !args.get("apply").isJsonNull() && args.get("apply").getAsBoolean();
        boolean force = args.has("force") && !args.get("force").isJsonNull() && args.get("force").getAsBoolean();
        try {
            EdtModelGateway.RemoveAttrResult res = gateway.removeAttribute(project, ownerFqn, name, apply, force);
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
            if (res.refCount != null) {
                o.addProperty("refCount", res.refCount.intValue());
                o.addProperty("externalRefCount", res.externalRefCount);
                o.addProperty("referenced", res.referenced);
            }
            o.addProperty("force", res.forced);
            if (res.refs != null && !res.refs.isEmpty()) {
                JsonArray refs = new JsonArray();
                for (EdtModelGateway.Ref ref : res.refs) {
                    JsonObject ro = new JsonObject();
                    ro.addProperty("sourceFqn", ref.sourceFqn);
                    ro.addProperty("sourceType", ref.sourceType);
                    if (ref.feature != null) {
                        ro.addProperty("feature", ref.feature);
                    }
                    refs.add(ro);
                }
                o.add("references", refs);
            }
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_remove_attribute failed: " + e.getMessage());
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
