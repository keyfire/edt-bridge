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

import io.github.keyfire.edtbridge.edt.FormGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_picture_export - READ. The content of a CommonPicture from its Picture.zip: the list
 * of variants from the manifest (DPI / interface variant 8.2 vs 8.5 / theme / isTemplate / glyph
 * size) and, when {@code variant} is given, that variant's bytes as base64. Saves going to disk and
 * unzipping Picture.zip by hand. {@code variant} accepts an exact entry name, or "svg" (the vector
 * master) / "best" (svg else the largest PNG).
 */
public final class PictureExportTool {

    private final FormGateway gateway = new FormGateway();

    public String name() {
        return "edt_picture_export";
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name"));
        props.add("fqn", strProp("CommonPicture FQN, e.g. CommonPicture.МояКартинка"));
        props.add("variant", strProp("Which variant's bytes to return as base64: an exact entry name "
                + "(e.g. 400.png, l, <hash>.svg), or \"svg\" (the vector master) / \"best\" (svg else the "
                + "largest PNG). Omit to list variants only (no base64). Optional."));

        JsonArray req = new JsonArray();
        req.add("projectName");
        req.add("fqn");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "READ: a CommonPicture's content from its Picture.zip. Without variant: lists the "
                + "variants (DPI / interface variant version8_2 vs version8_5 / theme / isTemplate / "
                + "glyph size) + a recommended pick. With variant: that entry's bytes as base64 + "
                + "contentType. Avoids going to disk and unzipping Picture.zip by hand.");
        t.addProperty("descriptionRu",
                "ЧТЕНИЕ: содержимое CommonPicture из Picture.zip. Без variant – перечень вариантов "
                + "(DPI / вариант интерфейса version8_2 или version8_5 / тема / isTemplate / размер "
                + "глифа) + рекомендуемый. С variant – байты этого варианта в base64 + contentType. "
                + "Избавляет от ручной распаковки Picture.zip с диска.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        String fqn = getStr(args, "fqn");
        if (project == null || fqn == null) {
            return McpServer.toolError("projectName and fqn are required");
        }
        String variant = getStr(args, "variant");
        try {
            FormGateway.PictureResult res = gateway.exportPicture(project, fqn, variant);
            JsonObject o = new JsonObject();
            o.addProperty("found", res.found);
            o.addProperty("fqn", res.fqn);
            if (!res.found) {
                o.addProperty("message", res.message);
                return McpServer.textResult(pretty(o));
            }
            if (res.zipPath != null) {
                o.addProperty("zipPath", res.zipPath);
            }
            o.add("variants", variantsJson(res));
            if (res.recommended != null) {
                o.addProperty("recommended", res.recommended);
            }
            if (res.base64 != null) {
                o.addProperty("selectedName", res.selectedName);
                o.addProperty("contentType", res.selectedContentType);
                o.addProperty("sizeBytes", res.selectedSize);
                o.addProperty("base64", res.base64);
            } else if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(pretty(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_picture_export failed: " + e.getMessage());
        }
    }

    private JsonArray variantsJson(FormGateway.PictureResult res) {
        JsonArray arr = new JsonArray();
        for (FormGateway.PictureVariant v : res.variants) {
            JsonObject o = new JsonObject();
            o.addProperty("name", v.name);
            if (v.contentType != null) {
                o.addProperty("contentType", v.contentType);
            }
            if (v.screenDensity != null) {
                o.addProperty("screenDensity", v.screenDensity);
            }
            if (v.interfaceVariant != null) {
                o.addProperty("interfaceVariant", v.interfaceVariant);
            }
            if (v.theme != null && !v.theme.isEmpty()) {
                o.addProperty("theme", v.theme);
            }
            if (v.isTemplate) {
                o.addProperty("isTemplate", true);
            }
            if (v.glyphWidth > 0 || v.glyphHeight > 0) {
                o.addProperty("glyphWidth", v.glyphWidth);
                o.addProperty("glyphHeight", v.glyphHeight);
            }
            o.addProperty("sizeBytes", v.sizeBytes);
            arr.add(o);
        }
        return arr;
    }

    private static String pretty(JsonObject o) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(o);
    }

    private static JsonObject strProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        o.addProperty("description", desc);
        return o;
    }

    private static String getStr(JsonObject a, String k) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? a.get(k).getAsString() : null;
    }
}
