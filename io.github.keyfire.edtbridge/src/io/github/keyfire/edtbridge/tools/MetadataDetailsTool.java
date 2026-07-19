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

import io.github.keyfire.edtbridge.edt.MetadataReadGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_metadata_details - core properties of a top-level metadata object from the
 * live EDT BM model (type, name, uuid, comment, ru synonym).
 */
public final class MetadataDetailsTool {

    private final MetadataReadGateway gateway = new MetadataReadGateway();

    public String name() {
        return "edt_metadata_details";
    }

    public JsonObject descriptor() {
        JsonObject pn = new JsonObject();
        pn.addProperty("type", "string");
        pn.addProperty("description", "EDT project name");

        JsonObject fqn = new JsonObject();
        fqn.addProperty("type", "string");
        fqn.addProperty("description", "Top object FQN with English type prefix, e.g. Catalog.Контрагенты, Document.МойДокумент, CommonModule.ОбщегоНазначения");

        JsonObject props = new JsonObject();
        props.add("projectName", pn);
        props.add("fqn", fqn);

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
                "Details of a top-level metadata object from the live EDT model: core properties "
                + "(type, name, uuid, comment, synonym) plus its structure grouped by feature "
                + "(attributes, tabularSections, forms, commands, templates, dimensions, resources, "
                + "enumValues, ...), with each attribute's value type.");
        t.addProperty("descriptionRu", "Детали объекта метаданных верхнего уровня из живой модели EDT: основные свойства (тип, имя, uuid, комментарий, синоним) и структура по группам (реквизиты, табличные части, формы, команды, макеты, измерения, ресурсы, значения перечисления, ...), с типом значения каждого реквизита.");
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
        try {
            MetadataReadGateway.MdDetails d = gateway.getMetadataDetails(project, fqn);
            JsonObject o = new JsonObject();
            o.addProperty("found", d.found);
            o.addProperty("fqn", d.fqn);
            if (d.found) {
                o.addProperty("type", d.type);
                o.addProperty("name", d.name);
                o.addProperty("uuid", d.uuid);
                o.addProperty("comment", d.comment);
                o.addProperty("synonymRu", d.synonymRu);
                if (d.properties != null && !d.properties.isEmpty()) {
                    JsonObject props = new JsonObject();
                    d.properties.forEach(props::addProperty);
                    o.add("properties", props);
                }
                if (d.content != null && !d.content.isEmpty()) {
                    JsonArray content = new JsonArray();
                    for (io.github.keyfire.edtbridge.edt.MetadataReadGateway.ContentItem ci : d.content) {
                        JsonObject c = new JsonObject();
                        c.addProperty("fqn", ci.fqn);
                        c.addProperty("type", ci.type);
                        c.addProperty("autoRecord", ci.autoRecord);
                        content.add(c);
                    }
                    o.add("content", content);
                }
                o.add("structure", groupsToJson(d.structure));
                if (d.emptyStructuralFeatures != null && !d.emptyStructuralFeatures.isEmpty()) {
                    JsonArray ef = new JsonArray();
                    for (String f : d.emptyStructuralFeatures) {
                        ef.add(f);
                    }
                    o.add("emptyStructuralFeatures", ef);
                }
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_metadata_details failed: " + e.getMessage());
        }
    }

    private JsonArray groupsToJson(java.util.List<MetadataReadGateway.MdGroup> groups) {
        JsonArray arr = new JsonArray();
        if (groups == null) {
            return arr;
        }
        for (MetadataReadGateway.MdGroup g : groups) {
            JsonObject go = new JsonObject();
            go.addProperty("feature", g.feature);
            JsonArray items = new JsonArray();
            for (MetadataReadGateway.MdChild c : g.items) {
                items.add(childToJson(c));
            }
            go.addProperty("count", items.size());
            go.add("items", items);
            arr.add(go);
        }
        return arr;
    }

    private JsonObject childToJson(MetadataReadGateway.MdChild c) {
        JsonObject o = new JsonObject();
        o.addProperty("name", c.name);
        o.addProperty("type", c.type);
        if (c.synonymRu != null) {
            o.addProperty("synonymRu", c.synonymRu);
        }
        if (c.valueType != null) {
            o.addProperty("valueType", c.valueType);
        }
        if (c.children != null && !c.children.isEmpty()) {
            o.add("structure", groupsToJson(c.children));
        }
        return o;
    }
}
