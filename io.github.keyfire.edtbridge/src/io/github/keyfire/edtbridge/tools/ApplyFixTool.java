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
import io.github.keyfire.edtbridge.edt.QuickFixGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** EDT's own quick fix for one marker of one check. */
public final class ApplyFixTool {
    private final QuickFixGateway gateway = new QuickFixGateway();

    public String name() { return "edt_apply_fix"; }
    public boolean isWrite(JsonObject args) { return ToolJson.getBool(args, "apply"); }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", ToolJson.strProp("EDT project name"));
        props.add("checkId", ToolJson.strProp("Check of the marker to fix, as edt_project_errors reports it: "
                + "a short code such as SU100 or a check id such as module-consecutive-blank-lines"));
        props.add("fqn", ToolJson.strProp("Object or form whose marker to fix, the same filter edt_project_errors "
                + "takes. Optional, but needed when the check fires in several places."));
        props.add("line", ToolJson.intProp("Line of the marker, when the check fires more than once in the scope. Optional."));
        props.add("variant", ToolJson.intProp("Index of the fix variant from the dry-run. Optional when EDT offers one variant."));
        props.add("apply", ToolJson.boolProp("false (default): find the marker and list the fix variants EDT offers, "
                + "change nothing. true: run the chosen variant, validate again and report the changed lines and "
                + "whether the marker is gone; requires a configured token."));
        return ToolJson.descriptor(name(),
                "Apply EDT's own quick fix - the one the Problems view offers - to one marker of a check. A dry-run "
                + "finds the marker and lists the fix variants with their descriptions; apply=true runs the chosen "
                + "variant, then returns the changed lines of the file and the number of markers of the check that "
                + "remain at that place after a fresh validation. For a check without a quick fix the answer says "
                + "so (hasFix=false): change the code by hand. A fix of a module needs client EDT, because EDT "
                + "applies it through the module editor; the bridge then saves that editor (editorSaved) and "
                + "refuses when it already holds unsaved changes. Dry-run by default.",
                "Применить штатное исправление EDT – то, что предлагает панель \"Ошибки\", – к одному маркеру проверки. "
                + "Предпросмотр находит маркер и перечисляет варианты исправления с описанием; apply=true выполняет "
                + "выбранный вариант и возвращает измененные строки файла и число маркеров проверки, оставшихся на "
                + "этом месте после новой проверки. Для проверки без штатного исправления ответ говорит об этом "
                + "(hasFix=false): код меняется вручную. Исправлению модуля нужна клиентская EDT, потому что EDT "
                + "применяет его через редактор модуля; мост затем сохраняет этот редактор (editorSaved) и "
                + "отказывает, если в нем уже есть несохраненные правки. По умолчанию предпросмотр.",
                props, "projectName", "checkId");
    }

    public JsonObject call(JsonObject args) {
        try {
            Integer line = args.has("line") && !args.get("line").isJsonNull() ? args.get("line").getAsInt() : null;
            Integer variant = args.has("variant") && !args.get("variant").isJsonNull() ? args.get("variant").getAsInt() : null;
            var result = gateway.fix(ToolJson.getStr(args, "projectName"), ToolJson.getStr(args, "checkId"),
                    ToolJson.getStr(args, "fqn"), line, variant, ToolJson.getBool(args, "apply"));
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (RuntimeException failure) {
            return McpServer.toolError("edt_apply_fix failed: " + failure.getMessage());
        }
    }
}
