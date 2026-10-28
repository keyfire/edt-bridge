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

/** Move a whole BSL method within its module after validating both intermediate and final parses. */
public final class MoveMethodTool {
    private final BslRefactorGateway gateway = new BslRefactorGateway();
    public String name() { return "edt_move_method"; }
    public boolean isWrite() { return true; }

    public JsonObject descriptor() {
        JsonObject properties = new JsonObject();
        properties.add("projectName", property("string", "EDT project name"));
        properties.add("fqn", property("string", "Module owner FQN; alternatively supply modulePath."));
        properties.add("modulePath", property("string", "Project-relative BSL module path."));
        properties.add("moduleType", property("string", "ObjectModule, ManagerModule, etc. when the owner has several modules."));
        properties.add("methodName", property("string", "Unique procedure/function name to move, ignoring case."));
        properties.add("region", property("string", "Destination region name or nested path, e.g. Public/Helpers. Short names must be unique."));
        properties.add("beforeMethod", property("string", "Insert before this unique method in the selected scope; excludes afterMethod."));
        properties.add("afterMethod", property("string", "Insert after this unique method in the selected scope; excludes beforeMethod."));
        properties.add("serverBlock", property("boolean", "Select the server preprocessor block as the destination scope."));
        properties.add("apply", property("boolean", "false (default): return the validated plan without writing. true: write the move."));
        properties.add("force", property("boolean", "Required with apply=true because moving a method can change its compilation scope."));
        JsonArray required = new JsonArray();
        required.add("projectName");
        required.add("methodName");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("name", name());
        descriptor.addProperty("description", "WRITE: move a method within its BSL module, preserving its exact text, "
                + "attached documentation and pragmas. Destination supports region paths, beforeMethod and afterMethod. "
                + "Dry-run returns the insertion line, neighbors and affected file. Refuses ambiguous or missing targets "
                + "and any result that fails to parse. Apply requires apply=true and force=true. Save the editor first.");
        descriptor.addProperty("descriptionRu", "ЗАПИСЬ: перенести метод внутри BSL-модуля с сохранением точного текста, "
                + "комментария и директив. Место задается путем области, beforeMethod или afterMethod. Dry-run возвращает "
                + "строку, соседние методы и файл. Неоднозначные, отсутствующие цели и ошибки разбора приводят к отказу. "
                + "Для записи нужны apply=true и force=true. Сначала сохранить редактор.");
        descriptor.add("inputSchema", schema);
        return descriptor;
    }

    public JsonObject call(JsonObject arguments) {
        String project = string(arguments, "projectName");
        String fqn = string(arguments, "fqn");
        String path = string(arguments, "modulePath");
        String method = string(arguments, "methodName");
        if (project == null || method == null || (fqn == null && path == null)) {
            return McpServer.toolError("projectName, methodName and (fqn or modulePath) are required");
        }
        var result = gateway.moveMethod(project, fqn, string(arguments, "moduleType"), path, method,
                string(arguments, "region"), bool(arguments, "serverBlock"), string(arguments, "beforeMethod"),
                string(arguments, "afterMethod"), bool(arguments, "apply"), bool(arguments, "force"));
        return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }
    private static JsonObject property(String type, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", type);
        property.addProperty("description", description);
        return property;
    }
    private static String string(JsonObject arguments, String key) {
        return arguments.has(key) && !arguments.get(key).isJsonNull() ? arguments.get(key).getAsString() : null;
    }
    private static boolean bool(JsonObject arguments, String key) {
        return arguments.has(key) && !arguments.get(key).isJsonNull() && arguments.get(key).getAsBoolean();
    }
}
