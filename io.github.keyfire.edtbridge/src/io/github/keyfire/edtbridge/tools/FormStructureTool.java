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
                + "(form event -> BSL handler). Items also carry their static visible/enabled/readOnly "
                + "(DESIGN values from .form – runtime BSL e.g. ПриСозданииНаСервере may override them), "
                + "per-item event handlers (e.g. a table column's Selection/Выбор handler), a cellHyperlink "
                + "flag, for input fields the password/choice-button design props (passwordMode / choiceButton / "
                + "choiceButtonPicture – the reveal-eye secret idiom), and for buttons the wired command + "
                + "representation/placement (command -> button). The "
                + "form's declarative conditional appearance (УсловноеОформление: fields + filter + appearance) "
                + "is returned in conditionalAppearance when present. Cleaner than parsing the serialized .form.");
        t.addProperty("descriptionRu",
                "Структура управляемой формы из живой модели EDT: дерево элементов "
                + "(поля, группы, таблицы, кнопки, декорации) с их привязками к данным, а также "
                + "реквизиты формы (с типами значений), команды, параметры и обработчики событий "
                + "(событие формы -> процедура BSL). У элементов также: обработчики уровня элемента "
                + "(например, обработчик Выбор у колонки таблицы), флаг cellHyperlink, у полей ввода – "
                + "design-свойства пароля/кнопки выбора (passwordMode / choiceButton / choiceButtonPicture – "
                + "идиома «глазика» раскрытия секрета) и для кнопок – привязанная команда + "
                + "представление/размещение (команда -> кнопка). Декларативное "
                + "условное оформление (УсловноеОформление: поля + отбор + оформление) – в "
                + "conditionalAppearance, если задано. Чище, чем разбор сериализованного файла .form.");
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
            if (!d.conditionalAppearance.isEmpty()) {
                o.add("conditionalAppearance", condAppearanceJson(d));
            }
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
        return evtArray(d.handlers);
    }

    private static JsonArray evtArray(java.util.List<EdtModelGateway.FormEvt> handlers) {
        JsonArray arr = new JsonArray();
        if (handlers == null) {
            return arr;
        }
        for (EdtModelGateway.FormEvt h : handlers) {
            JsonObject o = new JsonObject();
            o.addProperty("name", h.name);
            if (h.event != null) {
                o.addProperty("event", h.event);
            }
            arr.add(o);
        }
        return arr;
    }

    private JsonArray condAppearanceJson(EdtModelGateway.FormDetails d) {
        JsonArray arr = new JsonArray();
        for (EdtModelGateway.CondAppearance c : d.conditionalAppearance) {
            JsonObject o = new JsonObject();
            if (!c.use) {
                o.addProperty("use", false);
            }
            JsonArray fields = new JsonArray();
            for (String f : c.fields) {
                fields.add(f);
            }
            o.add("fields", fields);
            if (!c.filter.isEmpty()) {
                JsonArray filter = new JsonArray();
                for (String f : c.filter) {
                    filter.add(f);
                }
                o.add("filter", filter);
            }
            JsonObject appearance = new JsonObject();
            for (String[] pv : c.appearance) {
                appearance.addProperty(pv[0], pv[1]);
            }
            o.add("appearance", appearance);
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
            if (n.visible != null) {
                o.addProperty("visible", n.visible.booleanValue());
            }
            if (n.enabled != null) {
                o.addProperty("enabled", n.enabled.booleanValue());
            }
            if (n.readOnly != null) {
                o.addProperty("readOnly", n.readOnly.booleanValue());
            }
            if (n.cellHyperlink != null) {
                o.addProperty("cellHyperlink", n.cellHyperlink.booleanValue());
            }
            if (n.passwordMode != null) {
                o.addProperty("passwordMode", n.passwordMode.booleanValue());
            }
            if (n.choiceButton != null) {
                o.addProperty("choiceButton", n.choiceButton.booleanValue());
            }
            if (n.choiceButtonPicture != null) {
                o.addProperty("choiceButtonPicture", n.choiceButtonPicture.booleanValue());
            }
            if (n.choiceButtonRepresentation != null) {
                o.addProperty("choiceButtonRepresentation", n.choiceButtonRepresentation);
            }
            if (n.command != null) {
                o.addProperty("command", n.command);
            }
            if (n.representation != null) {
                o.addProperty("representation", n.representation);
            }
            if (n.placement != null) {
                o.addProperty("placement", n.placement);
            }
            if (n.handlers != null && !n.handlers.isEmpty()) {
                o.add("handlers", evtArray(n.handlers));
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
