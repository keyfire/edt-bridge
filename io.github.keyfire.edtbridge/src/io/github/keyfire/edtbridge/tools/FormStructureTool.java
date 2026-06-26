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

import io.github.keyfire.edtbridge.edt.EdtModelGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_form_structure - the structure of a managed form from the live EDT model: the
 * visual items tree (fields / groups / tables / buttons / decorations) with data bindings, plus
 * the form's attributes, commands, parameters and event handlers. The serialized .form file is a
 * complex EMF model that is painful to read statically; EDT resolves it for us.
 */
public final class FormStructureTool {

    private static final int DEFAULT_LIMIT = 500;

    private final EdtModelGateway gateway = new EdtModelGateway();

    public String name() {
        return "edt_form_structure";
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name"));
        props.add("fqn", strProp("Form FQN with English type prefixes, e.g. Catalog.Контрагенты.Form.ФормаЭлемента, "
                + "Document.МойДокумент.Form.ФормаДокумента, CommonForm.МояОбщаяФорма"));
        props.add("limit", intProp("Max items in the visual tree (default " + DEFAULT_LIMIT + "; <=0 means no cap)"));

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
                "Structure of a managed form from the live EDT model: the visual items tree "
                + "(fields, groups, tables, buttons, decorations) with their data bindings, plus the "
                + "form's attributes (with value types), commands, parameters and event handlers "
                + "(form event -> BSL handler). Cleaner than parsing the serialized .form file.");
        t.addProperty("descriptionRu",
                "Структура управляемой формы из живой модели EDT: дерево элементов "
                + "(поля, группы, таблицы, кнопки, декорации) с их привязками к данным, а также "
                + "реквизиты формы (с типами значений), команды, параметры и обработчики событий "
                + "(событие формы -> процедура BSL). Чище, чем разбор сериализованного файла .form.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        String fqn = getStr(args, "fqn");
        if (project == null || fqn == null) {
            return McpServer.toolError("projectName and fqn are required");
        }
        int limit = getInt(args, "limit", DEFAULT_LIMIT);
        try {
            EdtModelGateway.FormDetails d = gateway.getFormStructure(project, fqn, limit);
            JsonObject o = new JsonObject();
            o.addProperty("found", d.found);
            o.addProperty("fqn", d.fqn);
            if (!d.found) {
                o.addProperty("type", d.type);
                o.addProperty("message", d.message);
                return McpServer.textResult(pretty(o));
            }
            o.addProperty("type", d.type);
            o.addProperty("titleRu", d.titleRu);
            o.addProperty("width", d.width);
            o.addProperty("height", d.height);
            o.addProperty("truncated", d.truncated);
            o.add("attributes", attributesJson(d));
            o.add("commands", commandsJson(d));
            o.add("parameters", parametersJson(d));
            o.add("handlers", handlersJson(d));
            o.add("items", nodesJson(d.items));
            return McpServer.textResult(pretty(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_form_structure failed: " + e.getMessage());
        }
    }

    private JsonArray attributesJson(EdtModelGateway.FormDetails d) {
        JsonArray arr = new JsonArray();
        for (EdtModelGateway.FormAttr a : d.attributes) {
            JsonObject o = new JsonObject();
            o.addProperty("name", a.name);
            if (a.valueType != null) {
                o.addProperty("valueType", a.valueType);
            }
            if (a.main) {
                o.addProperty("main", true);
            }
            arr.add(o);
        }
        return arr;
    }

    private JsonArray commandsJson(EdtModelGateway.FormDetails d) {
        JsonArray arr = new JsonArray();
        for (EdtModelGateway.FormCmd c : d.commands) {
            JsonObject o = new JsonObject();
            o.addProperty("name", c.name);
            if (c.title != null) {
                o.addProperty("title", c.title);
            }
            if (c.handler != null) {
                o.addProperty("handler", c.handler);
            }
            arr.add(o);
        }
        return arr;
    }

    private JsonArray parametersJson(EdtModelGateway.FormDetails d) {
        JsonArray arr = new JsonArray();
        for (EdtModelGateway.FormParam pm : d.parameters) {
            JsonObject o = new JsonObject();
            o.addProperty("name", pm.name);
            if (pm.valueType != null) {
                o.addProperty("valueType", pm.valueType);
            }
            arr.add(o);
        }
        return arr;
    }

    private JsonArray handlersJson(EdtModelGateway.FormDetails d) {
        JsonArray arr = new JsonArray();
        for (EdtModelGateway.FormEvt h : d.handlers) {
            JsonObject o = new JsonObject();
            o.addProperty("name", h.name);
            if (h.event != null) {
                o.addProperty("event", h.event);
            }
            arr.add(o);
        }
        return arr;
    }

    private JsonArray nodesJson(java.util.List<EdtModelGateway.FormNode> nodes) {
        JsonArray arr = new JsonArray();
        if (nodes == null) {
            return arr;
        }
        for (EdtModelGateway.FormNode n : nodes) {
            JsonObject o = new JsonObject();
            o.addProperty("name", n.name);
            o.addProperty("kind", n.kind);
            if (n.itemType != null) {
                o.addProperty("itemType", n.itemType);
            }
            if (n.dataPath != null) {
                o.addProperty("dataPath", n.dataPath);
            }
            if (n.title != null) {
                o.addProperty("title", n.title);
            }
            if (n.children != null && !n.children.isEmpty()) {
                o.add("items", nodesJson(n.children));
            }
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
