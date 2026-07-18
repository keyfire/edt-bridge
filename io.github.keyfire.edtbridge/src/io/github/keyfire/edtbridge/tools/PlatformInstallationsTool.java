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

import io.github.keyfire.edtbridge.edt.PlatformGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_platform_installations - READ. The 1C:Enterprise platform installations EDT knows
 * about – the very set EDT resolves from when it dumps an .epf/.erf or creates an infobase, so the
 * caller can confirm the RIGHT full install is picked without hard-coding a path. Optional
 * discoverPath scans a folder for installs EDT has not registered yet (probe only); register=true
 * adds them to EDT's list and is token-gated because it mutates EDT configuration.
 */
public final class PlatformInstallationsTool {

    private final PlatformGateway gateway = new PlatformGateway();

    public String name() {
        return "edt_platform_installations";
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
                "READ: the 1C:Enterprise platform installations EDT resolves from when dumping an "
                + ".epf/.erf or creating an infobase – each registered entry resolved to a concrete "
                + "install carrying a thick client (name, version mask, resolved version+build, "
                + "filesystem path, arch, training flag). Use it to confirm EDT picks the right full "
                + "install (EDT keeps this per machine, so it also works on macOS).");
        t.addProperty("descriptionRu",
                "ЧТЕНИЕ: установки платформы 1С:Предприятие, из которых EDT выбирает при выгрузке "
                + ".epf/.erf или создании информационной базы – каждая запись разрешается до конкретной "
                + "установки с толстым клиентом (имя, маска версии, разрешённая версия+сборка, путь на "
                + "диске, разрядность, признак учебной). Позволяет убедиться, что EDT берёт правильную "
                + "полную установку (EDT хранит это по машине, поэтому работает и на macOS).");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        try {
            PlatformGateway.PlatformInstallationsResult res =
                    gateway.platformInstallations(null, false);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.add("installations", toArray(res.installations));
            JsonArray disk = new JsonArray();
            for (PlatformGateway.DiskFullInstall d : res.diskFullInstalls) {
                JsonObject j = new JsonObject();
                j.addProperty("version", d.version);
                j.addProperty("binDir", d.binDir);
                disk.add(j);
            }
            o.add("diskFullInstalls", disk);
            o.add("registeredRaw", toArray(res.registeredRaw));
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_platform_installations failed: " + e.getMessage());
        }
    }

    private static JsonArray toArray(java.util.List<PlatformGateway.PlatformInstallInfo> list) {
        JsonArray a = new JsonArray();
        for (PlatformGateway.PlatformInstallInfo i : list) {
            JsonObject j = new JsonObject();
            j.addProperty("name", i.name);
            j.addProperty("versionMask", i.versionMask);
            j.addProperty("resolved", i.resolved);
            j.addProperty("thickClient", i.thickClient);
            j.addProperty("version", i.version);
            j.addProperty("build", i.build);
            j.addProperty("location", i.location);
            j.addProperty("arch", i.arch);
            j.addProperty("training", i.training);
            j.addProperty("uuid", i.uuid);
            j.addProperty("typeId", i.typeId);
            if (i.note != null) {
                j.addProperty("note", i.note);
            }
            a.add(j);
        }
        return a;
    }
}
