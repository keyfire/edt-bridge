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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Native metadata deletion with an explicit possible-reference audit; every apply requires force. */
public final class DeleteObjectTool {
    private final MetadataWriteGateway gateway = new MetadataWriteGateway();

    public String name() { return "edt_delete_object"; }
    public boolean isWrite() { return true; }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name"));
        props.add("targetFqn", strProp("Object or child member to delete, for example Catalog.Partners "
                + "or InformationRegister.Versions.Resource.Version"));
        props.add("apply", boolProp("false (default): report native delete items, indexed inbound references "
                + "and possible BSL/query/form references; change nothing. true: run native delete with force=true, "
                + "verify target absence in the live BM model, and audit remaining candidates. "
                + "A complete BSL/query/form cascade is not guaranteed. Verify bsl_support_status EDITABLE first."));
        props.add("force", boolProp("Required for every apply. Acknowledges irreversible deletion and possible "
                + "remaining references, incomplete audit coverage and retained form controls with dataPath cleared. "
                + "Does not override native refactoring problems or a plan with no checked delete items."));

        JsonArray required = new JsonArray();
        required.add("projectName"); required.add("targetFqn");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props); schema.add("required", required);
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("name", name());
        descriptor.addProperty("description", "WRITE (Phase 2): delete a metadata object or child through EDT's "
                + "native refactoring. Dry-run reports native change items and a separate bounded audit of possible "
                + "references in BSL code/strings, actual dynamic-list/DCS query fields and form dataPath bindings. "
                + "These lexical candidates can include unrelated names; coverage, lower-bound counts, samples and "
                + "read failures are explicit. Native items are not proof of a complete BSL/query/form cascade. "
                + "Every apply requires a configured token and force=true; native blockers and empty plans are "
                + "refused. After apply, deletionConfirmed reports target absence in the live BM model, and "
                + "remainingPossibleReferences reports remaining candidates. Form controls can remain with dataPath "
                + "cleared; observed cases are listed separately. No automatic text replacement is performed by "
                + "the audit. Verify bsl_support_status EDITABLE before apply. Native analysis can take minutes.");
        descriptor.addProperty("descriptionRu", "ЗАПИСЬ (Phase 2): удалить объект метаданных или его член штатным "
                + "рефакторингом EDT. Сухой прогон показывает штатный план изменений и отдельный ограниченный аудит "
                + "возможных ссылок в коде и строках BSL, текстах запросов динамических списков/СКД и dataPath форм. "
                + "Это лексические кандидаты: одинаковые имена могут относиться к другим членам; покрытие, нижняя "
                + "граница счетчиков, образцы и ошибки чтения указаны явно. Этот план не доказывает полный каскад "
                + "по BSL, запросам и формам. Любое применение требует токен и force=true; проблемы штатного "
                + "рефакторинга и пустой план блокируют запись. После применения deletionConfirmed подтверждает "
                + "отсутствие цели в живой BM-модели, remainingPossibleReferences показывает оставшихся кандидатов. "
                + "Элементы формы могут остаться с очищенным dataPath; найденные случаи перечислены отдельно. "
                + "Аудит не заменяет текст автоматически. Перед apply проверить bsl_support_status EDITABLE. "
                + "Штатный анализ может занять несколько минут.");
        descriptor.add("inputSchema", schema);
        return descriptor;
    }

    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        String fqn = getStr(args, "targetFqn");
        if (project == null || fqn == null) return McpServer.toolError("projectName and targetFqn are required");
        boolean apply = getBool(args, "apply");
        boolean force = getBool(args, "force");
        try {
            MetadataWriteGateway.DeleteObjectResult result = gateway.deleteObject(project, fqn, apply, force);
            var gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
            JsonObject payload = gson.toJsonTree(result).getAsJsonObject();
            payload.addProperty("force", result.forced);
            payload.remove("forced");
            payload.addProperty("itemCount", result.items.size());
            payload.addProperty("indexedRefsTruncated", result.indexedRefCount != null
                    && result.indexedRefCount > result.indexedRefs.size());
            payload.addProperty("indexedReferenceCoverage", "BM inbound-reference index before native deletion; "
                    + "includes metadata plumbing and does not cover every BSL/query/runtime reference");
            return McpServer.textResult(gson.toJson(payload));
        } catch (Exception failure) {
            return McpServer.toolError("edt_delete_object failed: " + failure.getMessage());
        }
    }

    private static JsonObject strProp(String description) {
        JsonObject result = new JsonObject(); result.addProperty("type", "string");
        result.addProperty("description", description); return result;
    }
    private static JsonObject boolProp(String description) {
        JsonObject result = new JsonObject(); result.addProperty("type", "boolean");
        result.addProperty("description", description); return result;
    }
    private static String getStr(JsonObject arguments, String key) {
        return arguments.has(key) && !arguments.get(key).isJsonNull() ? arguments.get(key).getAsString() : null;
    }
    private static boolean getBool(JsonObject arguments, String key) {
        return arguments.has(key) && !arguments.get(key).isJsonNull() && arguments.get(key).getAsBoolean();
    }
}
