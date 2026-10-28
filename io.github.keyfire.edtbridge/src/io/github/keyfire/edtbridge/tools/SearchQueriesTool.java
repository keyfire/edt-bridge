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

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.edt.QueryUsageGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Search the actual query fields of dynamic lists and DCS datasets. */
public final class SearchQueriesTool {
    private final QueryUsageGateway gateway = new QueryUsageGateway();
    public String name() { return "edt_search_queries"; }
    public boolean isWrite() { return false; }
    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("pattern", ToolJson.strProp("Text or Java regular expression to find inside query lines"));
        props.add("projectName", ToolJson.strProp("Optional EDT project name; omitted searches every open project"));
        props.add("regex", ToolJson.boolProp("Treat pattern as a Java regular expression; default false"));
        props.add("caseSensitive", ToolJson.boolProp("Match case; default false with Unicode case folding"));
        props.add("pathFilter", ToolJson.strProp("Optional substring of the project-relative .form/.dcs path"));
        props.add("kind", ToolJson.strProp("all (default), dynamicList, or dcs"));
        props.add("maxResults", ToolJson.intProp("Maximum matching lines, default 200, at most 10000"));
        return ToolJson.descriptor(name(),
                "READ: search dynamic-list queryText in .form files and DCS dataset queries in .dcs files. Returns the form and "
                + "attribute or template and dataset, query line, XML field line and source path. Reads stored XML and open text "
                + "buffers; unsaved graphical-designer changes and BSL query literals are outside this scan. Parse failures and "
                + "truncation make coverage incomplete and are reported explicitly.",
                "ЧТЕНИЕ: искать текст в queryText динамических списков файлов .form и запросах наборов данных .dcs. Возвращает "
                + "форму и реквизит либо макет и набор, строку запроса, строку XML-поля и путь. Читает сохраненный XML и открытые "
                + "текстовые буферы; несохраненные правки визуальных редакторов и BSL-литералы сюда не входят. Ошибки разбора и "
                + "усечение явно отмечаются как неполное покрытие.", props, "pattern");
    }
    public JsonObject call(JsonObject args) {
        int limit = args.has("maxResults") ? args.get("maxResults").getAsInt() : 200;
        var result = gateway.search(ToolJson.getStr(args, "projectName"), ToolJson.getStr(args, "pattern"),
                ToolJson.getBool(args, "regex"), ToolJson.getBool(args, "caseSensitive"),
                ToolJson.getStr(args, "pathFilter"), ToolJson.getStr(args, "kind"), limit);
        return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }
}
