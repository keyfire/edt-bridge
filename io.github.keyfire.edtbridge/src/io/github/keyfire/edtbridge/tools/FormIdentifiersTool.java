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

import java.util.ArrayList;
import java.util.List;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.edt.FormIdentifiersGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Audit every contained visual item and optionally repair explicitly selected duplicate IDs. */
public final class FormIdentifiersTool {
    private final FormIdentifiersGateway gateway = new FormIdentifiersGateway();

    public String name() { return "edt_form_identifiers"; }
    public boolean isWrite(JsonObject args) { return ToolJson.getBool(args, "apply"); }

    public JsonObject descriptor() {
        JsonObject props = ToolJson.formMemberProps();
        JsonObject paths = new JsonObject(); paths.addProperty("type", "array");
        JsonObject item = new JsonObject(); item.addProperty("type", "string"); paths.add("items", item);
        paths.addProperty("description", "Exact paths from audit.items for duplicate occurrences to renumber. "
                + "Omit to audit only. Leave at least one occurrence of each duplicate ID unselected.");
        props.add("itemPaths", paths);
        props.add("expectedHash", ToolJson.strProp("sourceHash from the preview. Required for apply=true; "
                + "a changed form is refused. Allocator caches are excluded from the hash."));
        props.add("apply", ToolJson.boolProp("false (default): audit or preview without allocating IDs. "
                + "true: repair selected duplicate occurrences and request Form.form export; requires a configured token."));
        return ToolJson.descriptor(name(),
                "Audit visual-item IDs across complete form containment, including command bars, tooltips and context menus. "
                + "Commands and attributes have separate ID namespaces. Optional itemPaths previews targeted duplicate-ID repair; "
                + "apply=true requires expectedHash and a token. Preserves all unselected IDs and object data, resets native allocator caches. "
                + "Refuses reserved IDs, ambiguous numeric references and any open matching form editor, including a clean editor.",
                "Проверить ID визуальных элементов во всей модели формы, включая командные панели, подсказки и контекстные меню. "
                + "У команд и реквизитов отдельные пространства ID. itemPaths задает выбранные повторные вхождения для плана исправления; "
                + "apply=true требует expectedHash и токен. Остальные ID и данные объектов сохраняются, штатные кеши генераторов сбрасываются. "
                + "Зарезервированные ID, неоднозначные числовые ссылки и открытый редактор этой формы, включая сохраненный, блокируют запись.",
                props, "projectName", "formFqn");
    }

    public JsonObject call(JsonObject args) {
        try {
            List<String> paths = new ArrayList<>();
            if (args.has("itemPaths")) {
                if (!args.get("itemPaths").isJsonArray()) return McpServer.toolError("itemPaths must be an array of strings");
                for (JsonElement item : args.getAsJsonArray("itemPaths")) {
                    if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                        return McpServer.toolError("itemPaths must contain strings only");
                    }
                    paths.add(item.getAsString());
                }
            }
            var result = gateway.identifiers(ToolJson.getStr(args, "projectName"), ToolJson.getStr(args, "formFqn"),
                    paths, ToolJson.getStr(args, "expectedHash"), ToolJson.getBool(args, "apply"));
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (RuntimeException failure) {
            return McpServer.toolError("edt_form_identifiers failed: " + failure.getMessage());
        }
    }
}
