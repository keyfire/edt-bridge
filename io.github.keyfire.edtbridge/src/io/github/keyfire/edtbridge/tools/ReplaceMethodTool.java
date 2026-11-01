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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.edt.BslRefactorGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** Replace one existing BSL method after an EDT syntax check and an optimistic source check. */
public final class ReplaceMethodTool {
    private final BslRefactorGateway gateway = new BslRefactorGateway();
    public String name() { return "edt_replace_method"; }
    public boolean isWrite() { return true; }

    public JsonObject descriptor() {
        JsonObject properties = new JsonObject();
        properties.add("projectName", string("EDT project name"));
        properties.add("fqn", string("Module owner FQN: CommonModule.X, a form, or an object + moduleType. Alternative to modulePath."));
        properties.add("moduleType", string("For an object with several modules: ObjectModule, ManagerModule, etc. Optional."));
        properties.add("modulePath", string("Project-relative .bsl path. If fqn is also given, both must identify the same module."));
        properties.add("methodName", string("Existing procedure/function name, case-insensitive. Ambiguous names are refused."));
        properties.add("methodText", string("Full replacement for exactly one same-named, same-kind method, including its attached documentation, pragmas and trailing comment. Omitted attachments are removed. No module-level statements, variables, regions or preprocessor wrappers. Parameters and export may change; callers are not rewritten."));
        properties.add("expectedHash", string("sourceHash from a current dry-run: SHA-256 of the entire UTF-8 module, including any BOM. Required for apply; a changed source is refused."));
        properties.add("apply", bool("false (default) = validate and return oldText/newText and sourceHash, without writing. true = replace after checking expectedHash; requires force=true. Check edt_support_status=EDITABLE first."));
        properties.add("force", bool("false (default) = refuse apply. true = allow this replacement after source and syntax checks. Does not bypass a hash mismatch or dirty editor."));
        JsonArray required = new JsonArray();
        required.add("projectName"); required.add("methodName"); required.add("methodText");
        JsonObject schema = new JsonObject(); schema.addProperty("type", "object");
        schema.add("properties", properties); schema.add("required", required);
        JsonObject descriptor = new JsonObject(); descriptor.addProperty("name", name());
        descriptor.addProperty("description", "WRITE: replace one existing BSL method using EDT's parsed method range. Dry-run returns exact oldText/newText, sourceHash and resultHash. apply requires force=true and expectedHash from a current dry-run. Keeps the method name and kind; attached comments and pragmas are part of methodText and are removed if omitted. Refuses extra methods, module-level code, wrappers, parse errors, dirty editors and stale sources. Preserves surrounding source and its BOM, normalizing only the replacement's line endings. Does not refactor callers; check references and run edt_project_errors after changing a signature. Requires a configured write token and caller verification of edt_support_status=EDITABLE.");
        descriptor.addProperty("descriptionRu", "ЗАПИСЬ: заменить один существующий метод BSL по диапазону из модели EDT. По умолчанию dry-run: точные oldText/newText, sourceHash и resultHash без записи. Для apply нужны force=true и expectedHash из актуального dry-run. Имя и вид метода сохраняются; прилегающие комментарии и директивы входят в methodText и удаляются, если их не передать. Второй метод, код уровня модуля, обертки областей/препроцессора, ошибки разбора, несохраненный редактор и устаревший источник дают отказ. Окружающий код и BOM сохраняются, окончания строк нормализуются только в новом фрагменте. Вызовы не меняются: после изменения сигнатуры проверьте ссылки и edt_project_errors. Нужны токен записи и предварительная проверка вызывающим edt_support_status=EDITABLE.");
        descriptor.add("inputSchema", schema);
        return descriptor;
    }

    public JsonObject call(JsonObject args) {
        try {
            String project = value(args, "projectName"), fqn = value(args, "fqn");
            String path = value(args, "modulePath"), method = value(args, "methodName");
            String text = value(args, "methodText");
            if (project == null || method == null || text == null || (fqn == null && path == null)) {
                return McpServer.toolError("projectName, methodName, methodText and (fqn or modulePath) are required");
            }
            var result = gateway.replaceMethod(project, fqn, value(args, "moduleType"), path,
                    method, text, value(args, "expectedHash"), flag(args, "apply"), flag(args, "force"));
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (Exception exception) {
            return McpServer.toolError("edt_replace_method failed: " + exception.getMessage());
        }
    }
    private static JsonObject string(String description) {
        JsonObject value = new JsonObject(); value.addProperty("type", "string");
        value.addProperty("description", description); return value;
    }
    private static JsonObject bool(String description) {
        JsonObject value = new JsonObject(); value.addProperty("type", "boolean");
        value.addProperty("description", description); return value;
    }
    private static String value(JsonObject args, String name) {
        return args.has(name) && !args.get(name).isJsonNull() ? args.get(name).getAsString() : null;
    }
    private static boolean flag(JsonObject args, String name) {
        return args.has(name) && !args.get(name).isJsonNull() && args.get(name).getAsBoolean();
    }
}
