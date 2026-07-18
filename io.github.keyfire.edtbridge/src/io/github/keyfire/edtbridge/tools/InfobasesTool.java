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
 * MCP tool: edt_infobases - READ. EDT's registered infobases (name, uuid, connection string)
 * plus which open project is associated with which infobase — the discovery step before
 * edt_update_infobase.
 */
public final class InfobasesTool {

    private final EdtModelGateway gateway = new EdtModelGateway();

    public String name() {
        return "edt_infobases";
    }

    public boolean isWrite() {
        return false;
    }

    public JsonObject descriptor() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "READ: EDT's registered infobases (name, uuid, connection string) and the open "
                + "projects' infobase associations — discover targets for edt_update_infobase.");
        t.addProperty("descriptionRu",
                "ЧТЕНИЕ: зарегистрированные информационные базы EDT (имя, uuid, строка соединения) и "
                + "привязки открытых проектов к базам – выбор цели для edt_update_infobase.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        try {
            EdtModelGateway.InfobasesResult res = gateway.listInfobases();
            JsonObject o = new JsonObject();
            JsonArray bases = new JsonArray();
            for (EdtModelGateway.InfobaseInfo b : res.infobases) {
                JsonObject jb = new JsonObject();
                jb.addProperty("name", b.name);
                jb.addProperty("uuid", b.uuid);
                jb.addProperty("connection", b.connection);
                bases.add(jb);
            }
            o.add("infobases", bases);
            JsonArray assoc = new JsonArray();
            for (EdtModelGateway.InfobaseAssociationInfo a : res.associations) {
                JsonObject ja = new JsonObject();
                ja.addProperty("project", a.project);
                ja.addProperty("infobaseName", a.infobaseName);
                ja.addProperty("infobaseUuid", a.infobaseUuid);
                assoc.add(ja);
            }
            o.add("associations", assoc);
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_infobases failed: " + e.getMessage());
        }
    }
}
