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

import java.io.File;

import com.e1c.fresh.edtbridge.edt.EdtModelGateway;
import com.e1c.fresh.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_form_render - render a managed form to a PNG image via EDT's native offscreen
 * renderer (the form-editor WYSIWYG engine), in a chosen interface variant (TAXI 8.3 / VERSION8_5)
 * and theme. For visual review of a form's layout/implementation.
 */
public final class FormRenderTool {

    private final EdtModelGateway gateway = new EdtModelGateway();

    public String name() {
        return "edt_form_render";
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name"));
        props.add("fqn", strProp("Form FQN, e.g. CommonForm.МояФорма, Catalog.Контрагенты.Form.ФормаЭлемента"));
        props.add("variant", strProp("Interface variant: TAXI (8.3) or VERSION8_5 (default VERSION8_5)"));
        props.add("theme", strProp("Theme: LIGHT (default) or DARK"));
        props.add("width", intProp("Viewport width px (default 1280)"));
        props.add("height", intProp("Viewport height px (default 800)"));
        props.add("scale", intProp("Upscale the finished PNG by this percent (>100; default 100 = native size). E.g. 150 to roughly match a 150% display. Raster upscale — the render itself is at 100%."));
        props.add("outPath", strProp("Absolute PNG output path (default: <tmp>/edt_form_<...>.png)"));

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
                "Render a managed form to a PNG via EDT's native offscreen renderer (the form-editor "
                + "WYSIWYG engine), in the chosen interface variant (TAXI 8.3 / VERSION8_5) and theme "
                + "(LIGHT/DARK). Returns the saved PNG path + size. For visual review of form layout.");
        t.addProperty("descriptionRu",
                "Отрендерить управляемую форму в PNG через нативный offscreen-рендерер EDT (движок "
                + "WYSIWYG редактора форм), в выбранном варианте интерфейса (Такси 8.3 / Версия 8.5) и "
                + "теме (светлая/тёмная). Возвращает путь к PNG и размер. Для визуальной оценки формы.");
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
        String theme = getStr(args, "theme");
        int width = getInt(args, "width", 0);
        int height = getInt(args, "height", 0);
        int scale = getInt(args, "scale", 0);
        String outPath = getStr(args, "outPath");
        if (outPath == null) {
            String safe = fqn.replaceAll("[^A-Za-z0-9._-]", "_");
            outPath = new File(System.getProperty("java.io.tmpdir"), "edt_form_" + safe + ".png").getAbsolutePath();
        }
        try {
            EdtModelGateway.RenderResult res = gateway.renderForm(project, fqn, variant, theme, width, height, scale, outPath);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("fqn", res.fqn);
            if (res.ok) {
                o.addProperty("pngPath", res.pngPath);
                o.addProperty("width", res.width);
                o.addProperty("height", res.height);
                o.addProperty("variant", res.variant);
                o.addProperty("theme", res.theme);
                File f = new File(res.pngPath);
                o.addProperty("fileBytes", f.isFile() ? f.length() : 0);
            } else {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_form_render failed: " + e.getMessage());
        }
    }

    private static JsonObject strProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        o.addProperty("description", desc);
        return o;
    }

    private static JsonObject intProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "integer");
        o.addProperty("description", desc);
        return o;
    }

    private static String getStr(JsonObject a, String k) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? a.get(k).getAsString() : null;
    }

    private static int getInt(JsonObject a, String k, int def) {
        if (a.has(k) && !a.get(k).isJsonNull()) {
            try {
                return a.get(k).getAsInt();
            } catch (RuntimeException ignored) {
                // keep default
            }
        }
        return def;
    }
}
