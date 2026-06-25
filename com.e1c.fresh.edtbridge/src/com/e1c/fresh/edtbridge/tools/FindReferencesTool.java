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

import java.util.List;

import com.e1c.fresh.edtbridge.edt.EdtModelGateway;
import com.e1c.fresh.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_find_references - inbound references to a top-level metadata object
 * (who references it: metadata + BSL code) from the live BM cross-reference index.
 */
public final class FindReferencesTool {

    private static final int DEFAULT_LIMIT = 200;

    private final EdtModelGateway gateway = new EdtModelGateway();

    public String name() {
        return "edt_find_references";
    }

    public JsonObject descriptor() {
        JsonObject pn = new JsonObject();
        pn.addProperty("type", "string");
        pn.addProperty("description", "EDT project name");

        JsonObject fqn = new JsonObject();
        fqn.addProperty("type", "string");
        fqn.addProperty("description", "Target top object FQN (English type prefix), e.g. Catalog.Контрагенты");

        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("description", "Max references returned (default " + DEFAULT_LIMIT + ")");

        JsonObject props = new JsonObject();
        props.add("projectName", pn);
        props.add("fqn", fqn);
        props.add("limit", limit);

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
                "Inbound references to a top-level metadata object (metadata + BSL usages) from the live EDT model.");
        t.addProperty("descriptionRu", "Входящие ссылки на объект метаданных верхнего уровня (метаданные + использование в BSL) из живой модели EDT.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = (args.has("projectName") && !args.get("projectName").isJsonNull())
                ? args.get("projectName").getAsString() : null;
        String fqn = (args.has("fqn") && !args.get("fqn").isJsonNull())
                ? args.get("fqn").getAsString() : null;
        if (project == null || fqn == null) {
            return McpServer.toolError("projectName and fqn are required");
        }
        int limit = DEFAULT_LIMIT;
        if (args.has("limit") && !args.get("limit").isJsonNull()) {
            try {
                limit = args.get("limit").getAsInt();
            } catch (RuntimeException ignored) {
                // keep default
            }
        }
        try {
            EdtModelGateway.RefResult res = gateway.getReferences(project, fqn, limit);
            JsonArray arr = new JsonArray();
            if (res.refs != null) {
                for (EdtModelGateway.Ref r : res.refs) {
                    JsonObject o = new JsonObject();
                    o.addProperty("sourceFqn", r.sourceFqn);
                    o.addProperty("sourceType", r.sourceType);
                    o.addProperty("feature", r.feature);
                    o.addProperty("sourceUri", r.sourceUri);
                    arr.add(o);
                }
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("found", res.found);
            payload.addProperty("fqn", res.fqn);
            payload.addProperty("total", res.total);
            payload.addProperty("returned", arr.size());
            payload.addProperty("truncated", res.truncated);
            payload.add("references", arr);
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(payload));
        } catch (Exception e) {
            return McpServer.toolError("edt_find_references failed: " + e.getMessage());
        }
    }
}
