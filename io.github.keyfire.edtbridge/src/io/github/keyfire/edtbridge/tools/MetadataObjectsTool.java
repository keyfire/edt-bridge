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

import java.util.List;

import io.github.keyfire.edtbridge.edt.EdtModelGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_metadata_objects - list top-level metadata objects (optionally by type and
 * name substring) from the live BM model.
 */
public final class MetadataObjectsTool {

    private static final int DEFAULT_LIMIT = 500;

    private final EdtModelGateway gateway = new EdtModelGateway();

    public String name() {
        return "edt_metadata_objects";
    }

    public JsonObject descriptor() {
        JsonObject pn = new JsonObject();
        pn.addProperty("type", "string");
        pn.addProperty("description", "EDT project name. OPTIONAL – omit to search across ALL open projects "
                + "(each result is tagged with its project).");

        JsonObject type = new JsonObject();
        type.addProperty("type", "string");
        type.addProperty("description", "Metadata type (EClass), e.g. Catalog, Document, CommonModule, InformationRegister, Enum; omit or 'all' for every top object");

        JsonObject nameFilter = new JsonObject();
        nameFilter.addProperty("type", "string");
        nameFilter.addProperty("description", "Case-insensitive substring filter on object name");

        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("description", "Max objects returned (default " + DEFAULT_LIMIT + ")");

        JsonObject props = new JsonObject();
        props.add("projectName", pn);
        props.add("type", type);
        props.add("nameFilter", nameFilter);
        props.add("limit", limit);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", new JsonArray());   // projectName optional → search all open projects

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "List top-level metadata objects (optionally by type and name substring) from the live EDT "
                + "model. projectName is OPTIONAL: omit it to search across ALL open projects (each result is "
                + "tagged with its project).");
        t.addProperty("descriptionRu", "Список объектов метаданных верхнего уровня (опционально по типу и "
                + "подстроке имени) из живой модели EDT. projectName НЕОБЯЗАТЕЛЕН: без него поиск по ВСЕМ "
                + "открытым проектам (у каждого результата указан его проект).");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = (args.has("projectName") && !args.get("projectName").isJsonNull())
                ? args.get("projectName").getAsString() : null;
        if (project != null && project.isBlank()) {
            project = null;
        }
        String type = (args.has("type") && !args.get("type").isJsonNull())
                ? args.get("type").getAsString() : null;
        String nameFilter = (args.has("nameFilter") && !args.get("nameFilter").isJsonNull())
                ? args.get("nameFilter").getAsString() : null;
        int limit = DEFAULT_LIMIT;
        if (args.has("limit") && !args.get("limit").isJsonNull()) {
            try {
                limit = args.get("limit").getAsInt();
            } catch (RuntimeException ignored) {
                // keep default
            }
        }
        try {
            EdtModelGateway.MdListResult res = (project == null)
                    ? gateway.listMetadataAll(type, nameFilter, limit)
                    : gateway.listMetadata(project, type, nameFilter, limit);
            JsonArray arr = new JsonArray();
            if (res.items != null) {
                for (EdtModelGateway.MdItem it : res.items) {
                    JsonObject o = new JsonObject();
                    o.addProperty("fqn", it.fqn);
                    o.addProperty("type", it.type);
                    o.addProperty("name", it.name);
                    o.addProperty("synonymRu", it.synonymRu);
                    if (it.project != null) {
                        o.addProperty("project", it.project);
                    }
                    arr.add(o);
                }
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("found", res.found);
            payload.addProperty("scope", project == null ? "all open projects" : project);
            if (res.error != null) {
                payload.addProperty("error", res.error);
            }
            payload.addProperty("type", res.type == null ? "all" : res.type);
            payload.addProperty("returned", arr.size());
            payload.addProperty("truncated", res.truncated);
            payload.add("objects", arr);
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(payload));
        } catch (Exception e) {
            return McpServer.toolError("edt_metadata_objects failed: " + e.getMessage());
        }
    }
}
