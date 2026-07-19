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

import io.github.keyfire.edtbridge.edt.FormWriteGateway;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Small JSON helpers shared by the form-member tools: schema property builders, argument readers and
 * the common rendering of a {@link FormWriteGateway.FormMemberResult}. Kept package-private so it
 * stays an implementation detail of this package.
 */
final class ToolJson {

    private ToolJson() {
    }

    static JsonObject strProp(String desc) {
        return prop("string", desc);
    }

    static JsonObject boolProp(String desc) {
        return prop("boolean", desc);
    }

    static JsonObject intProp(String desc) {
        return prop("integer", desc);
    }

    private static JsonObject prop(String type, String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("description", desc);
        return o;
    }

    static String getStr(JsonObject a, String k) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? a.get(k).getAsString() : null;
    }

    static boolean getBool(JsonObject a, String k) {
        return a.has(k) && !a.get(k).isJsonNull() && a.get(k).getAsBoolean();
    }

    /** Tri-state read: {@code null} when the caller did not mention the flag at all. */
    static Boolean getBoolOrNull(JsonObject a, String k) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? Boolean.valueOf(a.get(k).getAsBoolean()) : null;
    }

    /** Assemble the standard tool descriptor around a property set. */
    static JsonObject descriptor(String name, String description, String descriptionRu,
            JsonObject props, String... required) {
        JsonArray req = new JsonArray();
        for (String r : required) {
            req.add(r);
        }
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("description", description);
        t.addProperty("descriptionRu", descriptionRu);
        t.add("inputSchema", schema);
        return t;
    }

    /** The properties every form-member tool takes. */
    static JsonObject formMemberProps() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name"));
        props.add("formFqn", strProp("Form FQN with English type prefixes, e.g. "
                + "Catalog.Контрагенты.Form.ФормаЭлемента, DataProcessor.X.Form.Форма, CommonForm.Y"));
        return props;
    }

    /** Render a form-item outcome as the tool's JSON result. */
    static String renderItem(FormWriteGateway.FormItemResult res) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", res.ok);
        o.addProperty("applied", res.applied);
        o.addProperty("formFqn", res.formFqn);
        if (res.kind != null && !res.kind.isBlank()) {
            o.addProperty("kind", res.kind);
        }
        o.addProperty("name", res.name);
        if (res.parent != null) {
            o.addProperty("parent", res.parent);
        }
        if (res.dataPath != null) {
            o.addProperty("dataPath", res.dataPath);
        }
        if (res.command != null) {
            o.addProperty("command", res.command);
        }
        if (res.id != null) {
            o.addProperty("id", res.id.intValue());
        }
        o.addProperty("formFound", res.formFound);
        if (res.parentFound != null) {
            o.addProperty("parentFound", res.parentFound.booleanValue());
        }
        if (res.nameAvailable != null) {
            o.addProperty("nameAvailable", res.nameAvailable.booleanValue());
        }
        if (res.present != null) {
            o.addProperty("present", res.present.booleanValue());
        }
        if (!res.createdColumns.isEmpty()) {
            JsonArray arr = new JsonArray();
            res.createdColumns.forEach(arr::add);
            o.add("nestedItems", arr);
        }
        if (!res.items.isEmpty()) {
            JsonArray arr = new JsonArray();
            res.items.forEach(arr::add);
            o.add("items", arr);
        }
        if (res.plan != null) {
            o.addProperty("plan", res.plan);
        }
        if (res.warning != null) {
            o.addProperty("warning", res.warning);
        }
        if (res.message != null) {
            o.addProperty("message", res.message);
        }
        return new GsonBuilder().setPrettyPrinting().create().toJson(o);
    }

    /** Render a form-member outcome as the tool's JSON result. */
    static String render(FormWriteGateway.FormMemberResult res) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", res.ok);
        o.addProperty("applied", res.applied);
        o.addProperty("formFqn", res.formFqn);
        o.addProperty("member", res.member);
        o.addProperty("name", res.name);
        if (res.columnOf != null && !res.columnOf.isBlank()) {
            o.addProperty("columnOf", res.columnOf);
        }
        if (res.type != null) {
            o.addProperty("type", res.type);
        }
        if (res.id != null) {
            o.addProperty("id", res.id.intValue());
        }
        if (res.handler != null) {
            o.addProperty("handler", res.handler);
        }
        if (res.modulePath != null) {
            o.addProperty("modulePath", res.modulePath);
        }
        o.addProperty("formFound", res.formFound);
        o.addProperty("nameValid", res.nameValid);
        if (res.nameAvailable != null) {
            o.addProperty("nameAvailable", res.nameAvailable.booleanValue());
        }
        if (res.present != null) {
            o.addProperty("present", res.present.booleanValue());
        }
        if (!res.members.isEmpty()) {
            JsonArray arr = new JsonArray();
            res.members.forEach(arr::add);
            o.add(res.member.equals("attribute") ? "attributes" : "commands", arr);
        }
        if (!res.boundItems.isEmpty()) {
            JsonArray arr = new JsonArray();
            res.boundItems.forEach(arr::add);
            o.add("boundItems", arr);
        }
        if (res.plan != null) {
            o.addProperty("plan", res.plan);
        }
        if (res.warning != null) {
            o.addProperty("warning", res.warning);
        }
        if (res.message != null) {
            o.addProperty("message", res.message);
        }
        return new GsonBuilder().setPrettyPrinting().create().toJson(o);
    }
}
