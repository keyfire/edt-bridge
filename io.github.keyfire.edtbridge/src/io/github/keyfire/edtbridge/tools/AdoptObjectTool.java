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

import io.github.keyfire.edtbridge.edt.MetadataWriteGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_adopt_object - WRITE (Phase 2). Adopts ("заимствует") an object of the base
 * configuration into an extension project through EDT's own {@code IModelObjectAdopter} - the step
 * that must happen before an extension can intercept anything on that object.
 */
public final class AdoptObjectTool {

    private final MetadataWriteGateway gateway = new MetadataWriteGateway();

    public String name() {
        return "edt_adopt_object";
    }

    /** Write tool - the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", ToolJson.strProp("The EXTENSION project to adopt into"));
        props.add("fqn", ToolJson.strProp("Object of the BASE configuration to adopt, with an English "
                + "type prefix, e.g. Catalog.Контрагенты, CommonModule.ОбщегоНазначения, "
                + "Document.Заказ"));
        props.add("apply", ToolJson.boolProp("false (default) = dry-run: resolve the extension, its "
                + "base configuration and the object, report whether it is adoptable or already "
                + "adopted, and change nothing. true = perform the adopt."));
        return ToolJson.descriptor(name(),
                "WRITE (Phase 2): adopt an object of the base configuration into an extension project - "
                + "the step that has to happen before the extension can intercept anything on that "
                + "object, and the one that completes edt_create_extension. Runs EDT's own "
                + "IModelObjectAdopter, so the adopted copy gets the correct objectBelonging, the link "
                + "back to the base object's uuid and the per-property control block, and is attached to "
                + "the extension's configuration - none of which is safe to hand-write. Dry-run by "
                + "default; additive, so no force, but a configured token is required.",
                "ЗАПИСЬ (Phase 2): заимствовать объект базовой конфигурации в проект расширения - шаг, "
                + "без которого расширение не может ничего перехватить у этого объекта, и который "
                + "достраивает edt_create_extension. Работает штатный IModelObjectAdopter EDT, поэтому "
                + "заимствованная копия получает верный objectBelonging, ссылку на uuid базового "
                + "объекта и блок пофайловых флагов управления свойствами, и привязывается к "
                + "конфигурации расширения - писать это руками небезопасно. По умолчанию dry-run; "
                + "аддитивно, force не нужен, но нужен токен.",
                props, "projectName", "fqn");
    }

    public JsonObject call(JsonObject args) {
        String project = ToolJson.getStr(args, "projectName");
        String fqn = ToolJson.getStr(args, "fqn");
        if (project == null || fqn == null) {
            return McpServer.toolError("projectName and fqn are required");
        }
        try {
            MetadataWriteGateway.AdoptResult res =
                    gateway.adoptObject(project, fqn, ToolJson.getBool(args, "apply"));
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            o.addProperty("project", res.project);
            if (res.baseProject != null) {
                o.addProperty("baseProject", res.baseProject);
            }
            o.addProperty("fqn", res.fqn);
            if (res.type != null) {
                o.addProperty("type", res.type);
            }
            o.addProperty("extensionFound", res.extensionFound);
            o.addProperty("objectFound", res.objectFound);
            if (res.adoptable != null) {
                o.addProperty("adoptable", res.adoptable.booleanValue());
            }
            if (res.alreadyAdopted != null) {
                o.addProperty("alreadyAdopted", res.alreadyAdopted.booleanValue());
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
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_adopt_object failed: " + e.getMessage());
        }
    }
}
