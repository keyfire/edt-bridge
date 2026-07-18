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

import io.github.keyfire.edtbridge.edt.DocsGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_platform_help - READ. The real 1C:Enterprise API reference (Syntax Helper) shipped
 * inside EDT's platform doc bundles: search object/method/property/event pages by name (Russian or
 * English), or read a page as text. Lets an agent consult the actual API instead of guessing.
 */
public final class PlatformHelpTool {

    private final DocsGateway docs = new DocsGateway();

    public String name() {
        return "edt_platform_help";
    }

    public boolean isWrite() {
        return false;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("query", strProp("Search terms (Russian or English), e.g. \"ТаблицаЗначений Добавить\" "
                + "or \"ValueTable Add\". All terms must appear in the page title. Omit when reading a page."));
        props.add("path", strProp("Read this exact page (a `path` from a search hit) as text instead "
                + "of searching."));
        props.add("bundle", strProp("The `bundle` of the page to read (from the search hit). Optional – "
                + "resolved from the index when omitted."));
        props.add("limit", numProp("Max search hits (default 15)."));

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "READ: the 1C:Enterprise platform Syntax Helper bundled with EDT (real API reference – "
                + "objects, methods, properties, events, Ru+En). Search by name, or read a page by its "
                + "path. Use this to consult the actual API instead of guessing signatures.");
        t.addProperty("descriptionRu",
                "ЧТЕНИЕ: Синтакс-помощник платформы 1С:Предприятие из поставки EDT (реальная справка API – "
                + "объекты, методы, свойства, события, рус+англ). Поиск по имени или чтение страницы по "
                + "пути. Свериться с фактическим API вместо угадывания сигнатур.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String query = getStr(args, "query");
        String path = getStr(args, "path");
        String bundle = getStr(args, "bundle");
        int limit = (args.has("limit") && !args.get("limit").isJsonNull())
                ? args.get("limit").getAsInt() : 0;
        try {
            DocsGateway.HelpResult res = docs.platformHelp(query, path, bundle, limit);
            JsonObject o = new JsonObject();
            o.addProperty("mode", res.mode);
            o.addProperty("indexed", res.indexed);
            if ("page".equals(res.mode)) {
                o.addProperty("title", res.title);
                o.addProperty("bundle", res.bundle);
                o.addProperty("path", res.path);
                o.addProperty("text", res.text);
            } else {
                JsonArray hits = new JsonArray();
                for (DocsGateway.HelpEntry e : res.hits) {
                    JsonObject h = new JsonObject();
                    h.addProperty("title", e.title);
                    h.addProperty("version", e.version);
                    h.addProperty("bundle", e.bundle);
                    h.addProperty("path", e.path);
                    hits.add(h);
                }
                o.add("hits", hits);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_platform_help failed: " + e.getMessage());
        }
    }

    private static JsonObject strProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        o.addProperty("description", desc);
        return o;
    }

    private static JsonObject numProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "integer");
        o.addProperty("description", desc);
        return o;
    }

    private static String getStr(JsonObject a, String k) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? a.get(k).getAsString() : null;
    }
}
