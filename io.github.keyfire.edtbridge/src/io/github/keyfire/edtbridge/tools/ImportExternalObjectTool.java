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

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.edt.ExternalObjectImportGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Import an existing external processor or report into a new EDT project. */
public final class ImportExternalObjectTool {
    private final ExternalObjectImportGateway gateway = new ExternalObjectImportGateway();
    public String name() { return "edt_import_external_object"; }
    public boolean isWrite() { return true; }
    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("file", ToolJson.strProp("Absolute path to the input .epf or .erf; the original file is preserved"));
        props.add("projectName", ToolJson.strProp("Name of the new EDT project; an existing project or directory is refused"));
        props.add("baseProjectName", ToolJson.strProp("Optional open configuration project for metadata context and runtime version"));
        props.add("kind", ToolJson.strProp("ExternalDataProcessor or ExternalReport; inferred from .epf/.erf when omitted"));
        props.add("route", ToolJson.strProp("auto (default): EDT restorer, then full platform on disk; edt: only EDT restorer; disk: only on-disk platform. XML is imported through EDT in all modes."));
        props.add("apply", ToolJson.boolProp("false = validate input and destination only (default); true = unpack and import"));
        return ToolJson.descriptor(name(),
                "WRITE: import an existing .epf/.erf into a new EDT project, optionally linked to a base configuration. "
                + "Uses the platform's XML conversion and EDT's native importer, preserves the input, and removes its own partial "
                + "project after failure. Handles processor/report kinds and Unicode XML. Dry-run by default; requires a token.",
                "ЗАПИСЬ: импортировать существующую .epf/.erf в новый проект EDT с необязательной связью с базовой конфигурацией. "
                + "Использует преобразование платформы в XML и штатный импорт EDT, сохраняет исходный файл и удаляет собственный "
                + "незавершенный проект при ошибке. Поддерживает внешние обработки и отчеты, XML с Unicode BOM. По умолчанию dry-run; требует токен.",
                props, "file", "projectName");
    }
    public JsonObject call(JsonObject args) {
        var result = gateway.importObject(ToolJson.getStr(args, "file"), ToolJson.getStr(args, "projectName"),
                ToolJson.getStr(args, "baseProjectName"), ToolJson.getStr(args, "kind"),
                ToolJson.getStr(args, "route"), ToolJson.getBool(args, "apply"));
        return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }
}
