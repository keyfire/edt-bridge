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
 * MCP tool: edt_add_method - WRITE (Phase 2). Inserts a new procedure/function into a module.
 * Dry-run by default (apply=false): parses the target module, validates that methodText is exactly one
 * legal BSL method whose name is free, computes the insertion point from the model (region / server block /
 * after the last method) and returns the plan + a preview WITHOUT writing. apply=true splices the method
 * text and writes the .bsl, but ONLY if the spliced module re-parses cleanly (the safety invariant).
 * Requires a configured token (write tool); the caller must verify bsl_support_status EDITABLE before apply.
 */
public final class AddMethodTool {

    private final EdtModelGateway gateway = new EdtModelGateway();

    public String name() {
        return "edt_add_method";
    }

    /** Write tool – the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name"));
        props.add("fqn", strProp("Module owner FQN: CommonModule.X, a form (DataProcessor.X.Form.Y / "
                + "CommonForm.Y), or an object + moduleType. Alternatively pass modulePath."));
        props.add("moduleType", strProp("For a top object with several modules: ObjectModule / ManagerModule "
                + "/ etc. (optional; forms and common modules resolve to Module.bsl)."));
        props.add("modulePath", strProp("Workspace-relative .bsl path (alternative to fqn), e.g. "
                + "src/CommonModules/X/Module.bsl"));
        props.add("methodText", strProp("The full BSL procedure/function text (exactly ONE method), e.g. "
                + "\"Процедура Имя() Экспорт ... КонецПроцедуры\". Must parse as one method."));
        props.add("region", strProp("Optional #Область name to insert the method into (by model, not text). "
                + "If omitted, the method is appended after the last method of the module (or of the server "
                + "block when serverBlock=true)."));
        props.add("serverBlock", boolProp("Optional: place the method inside the module's server preprocessor "
                + "block (#Если Сервер ... #КонецЕсли). Combined with region = the region inside that block."));
        props.add("apply", boolProp("false (default) = dry-run: validate + return the plan, write nothing. "
                + "true = write the .bsl (only if the result parses cleanly). Verify bsl_support_status "
                + "EDITABLE, first."));

        JsonArray req = new JsonArray();
        req.add("projectName");
        req.add("methodText");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE (Phase 2): add a new procedure/function to a module's BSL. Dry-run by "
                + "default – parses the module, validates methodText is exactly one legal method with a free "
                + "name, computes the insertion point from the model (region / server block / after the last "
                + "method) and returns the plan + preview WITHOUT writing. apply=true writes the .bsl, but "
                + "only if the spliced module re-parses cleanly (safety invariant – a bad methodText or "
                + "offset can never corrupt the module, worst case is a refused apply). Additive (a new "
                + "method is non-breaking) – no force. Requires a configured token; caller must verify "
                + "bsl_support_status EDITABLE before apply (cannot add to vendor-locked modules).");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ (Phase 2): добавить новую процедуру/функцию в BSL модуля. По умолчанию "
                + "dry-run – парсит модуль, проверяет, что methodText – ровно один корректный метод со "
                + "свободным именем, вычисляет точку вставки по модели (регион / серверный блок / после "
                + "последнего метода) и возвращает план + предпросмотр БЕЗ записи. apply=true пишет .bsl, но "
                + "только если результат парсится без ошибок (инвариант безопасности – плохой methodText или "
                + "смещение не могут испортить модуль, худший случай – отказ). Аддитивно (новый метод не "
                + "ломает совместимость) – без force. Требует токен; перед apply вызывающий обязан проверить "
                + "bsl_support_status = EDITABLE (в vendor-locked модули добавлять нельзя).");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        String fqn = getStr(args, "fqn");
        String modulePath = getStr(args, "modulePath");
        String moduleType = getStr(args, "moduleType");
        String methodText = getStr(args, "methodText");
        String region = getStr(args, "region");
        if (project == null || methodText == null || (fqn == null && modulePath == null)) {
            return McpServer.toolError("projectName, methodText and (fqn or modulePath) are required");
        }
        Boolean serverBlock = (args.has("serverBlock") && !args.get("serverBlock").isJsonNull())
                ? Boolean.valueOf(args.get("serverBlock").getAsBoolean()) : null;
        boolean apply = args.has("apply") && !args.get("apply").isJsonNull() && args.get("apply").getAsBoolean();
        try {
            EdtModelGateway.AddMethodResult res = gateway.addMethod(project, fqn, moduleType, modulePath,
                    methodText, region, serverBlock, apply);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            if (res.applyPending) {
                o.addProperty("applyPending", true);
            }
            o.addProperty("modulePath", res.modulePath);
            o.addProperty("moduleFound", res.moduleFound);
            if (res.methodName != null) {
                o.addProperty("methodName", res.methodName);
            }
            if (res.methodKind != null) {
                o.addProperty("methodKind", res.methodKind);
            }
            if (res.export != null) {
                o.addProperty("export", res.export.booleanValue());
            }
            if (res.nameAvailable != null) {
                o.addProperty("nameAvailable", res.nameAvailable.booleanValue());
            }
            o.addProperty("valid", res.valid);
            if (res.region != null) {
                o.addProperty("region", res.region);
            }
            if (res.regionFound != null) {
                o.addProperty("regionFound", res.regionFound.booleanValue());
            }
            if (res.serverBlock) {
                o.addProperty("serverBlock", true);
            }
            if (res.serverBlockFound != null) {
                o.addProperty("serverBlockFound", res.serverBlockFound.booleanValue());
            }
            if (res.insertAfter != null) {
                o.addProperty("insertAfter", res.insertAfter);
            }
            if (res.insertLine > 0) {
                o.addProperty("insertLine", res.insertLine);
            }
            if (res.preview != null) {
                o.addProperty("preview", res.preview);
            }
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_add_method failed: " + e.getMessage());
        }
    }

    private static JsonObject strProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        o.addProperty("description", desc);
        return o;
    }

    private static JsonObject boolProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "boolean");
        o.addProperty("description", desc);
        return o;
    }

    private static String getStr(JsonObject a, String k) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? a.get(k).getAsString() : null;
    }
}
