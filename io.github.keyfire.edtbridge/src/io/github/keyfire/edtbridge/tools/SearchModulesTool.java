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

import io.github.keyfire.edtbridge.edt.BslGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_search_modules - READ. Full-text search across a project's BSL modules, reading
 * through Eclipse's file buffers so modules open in an editor are searched as they currently stand,
 * unsaved edits included.
 */
public final class SearchModulesTool {

    private final BslGateway gateway = new BslGateway();

    public String name() {
        return "edt_search_modules";
    }

    public boolean isWrite() {
        return false;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("pattern", ToolJson.strProp("Text to find - a plain substring, or a regular "
                + "expression when regex=true"));
        props.add("projectName", ToolJson.strProp("Project to search. Optional - omitted searches "
                + "every open project."));
        props.add("regex", ToolJson.boolProp("Treat pattern as a Java regular expression. Default false."));
        props.add("caseSensitive", ToolJson.boolProp("Match case. Default false."));
        props.add("pathFilter", ToolJson.strProp("Only modules whose project-relative path contains "
                + "this substring, e.g. CommonModules or Catalogs/Контрагенты. Optional."));
        props.add("maxResults", ToolJson.intProp("Cap on hits returned (default 200). The result says "
                + "whether it truncated."));
        return ToolJson.descriptor(name(),
                "READ: full-text search across the BSL modules of a project (or every open project) - "
                + "the exploratory step that edt_find_references does not cover: it answers \"who calls "
                + "this method\", this answers \"where does this text appear\". Returns project, "
                + "module path, line number and the matching line. Reading goes through Eclipse's file "
                + "buffers, so a module open in an editor is searched as it currently stands, unsaved "
                + "edits included - each hit says whether it came from an unsaved buffer.",
                "ЧТЕНИЕ: полнотекстовый поиск по модулям BSL проекта (или всех открытых проектов) - "
                + "тот шаг разведки, который не закрывает edt_find_references: он отвечает на вопрос "
                + "\"кто вызывает этот метод\", а этот - \"где встречается такой текст\". Возвращает "
                + "проект, путь модуля, номер строки и саму строку. Чтение идёт через буферы файлов "
                + "Eclipse, поэтому модуль, открытый в редакторе, ищется в текущем виде, вместе с "
                + "несохранёнными правками - у каждого попадания указано, из буфера оно или с диска.",
                props, "pattern");
    }

    public JsonObject call(JsonObject args) {
        String pattern = ToolJson.getStr(args, "pattern");
        if (pattern == null) {
            return McpServer.toolError("pattern is required");
        }
        int maxResults = (args.has("maxResults") && !args.get("maxResults").isJsonNull())
                ? args.get("maxResults").getAsInt() : 200;
        try {
            BslGateway.SearchResult res = gateway.searchModules(ToolJson.getStr(args, "projectName"),
                    pattern, ToolJson.getBool(args, "regex"), ToolJson.getBool(args, "caseSensitive"),
                    ToolJson.getStr(args, "pathFilter"), maxResults);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("pattern", res.pattern);
            o.addProperty("regex", res.regex);
            o.addProperty("caseSensitive", res.caseSensitive);
            o.addProperty("modulesScanned", res.modulesScanned);
            o.addProperty("hitCount", res.hits.size());
            o.addProperty("truncated", res.truncated);
            if (res.filesWithUnsavedChanges > 0) {
                o.addProperty("filesWithUnsavedChanges", res.filesWithUnsavedChanges);
            }
            JsonArray hits = new JsonArray();
            for (BslGateway.SearchHit h : res.hits) {
                JsonObject j = new JsonObject();
                j.addProperty("project", h.project);
                j.addProperty("modulePath", h.modulePath);
                j.addProperty("line", h.line);
                j.addProperty("text", h.text);
                if (h.unsaved) {
                    j.addProperty("unsaved", true);
                }
                hits.add(j);
            }
            o.add("hits", hits);
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_search_modules failed: " + e.getMessage());
        }
    }
}
