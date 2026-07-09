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
 * MCP tool: edt_delete_method - WRITE (Phase 2). Deletes a procedure/function from a module — the
 * inverse of edt_add_method. Dry-run by default (apply=false): parses the module, locates the method by
 * name via the model, returns the plan + the exact text that would be removed WITHOUT writing.
 * apply=true cuts the method (plus its adjacent leading doc comments and the blank separation above)
 * and writes the .bsl, but ONLY if the result re-parses cleanly and loses exactly this one method (the
 * safety invariant), AND force=true is passed (deleting code is destructive; an exported method is a
 * breaking change for consumers). Requires a configured token; the caller must verify
 * bsl_support_status EDITABLE before apply.
 */
public final class DeleteMethodTool {

    private final EdtModelGateway gateway = new EdtModelGateway();

    public String name() {
        return "edt_delete_method";
    }

    /** Write tool — the server gates this on a configured token. */
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
        props.add("methodName", strProp("Name of the procedure/function to delete (case-insensitive, "
                + "as BSL names are)."));
        props.add("apply", boolProp("false (default) = dry-run: locate + validate + return the plan and the "
                + "exact text that would be removed, write nothing. true = write the .bsl without the method "
                + "(only if the result parses cleanly); also requires force=true. Verify bsl_support_status "
                + "EDITABLE first."));
        props.add("force", boolProp("false (default) = refuse apply (deleting code is destructive; an "
                + "exported method is breaking for consumers). true = the owner's explicit override; "
                + "required for any apply. Has no effect on a dry-run."));

        JsonArray req = new JsonArray();
        req.add("projectName");
        req.add("methodName");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE (Phase 2): delete a procedure/function from a module's BSL — the inverse of "
                + "edt_add_method. Dry-run by default — parses the module, locates the method by name via the "
                + "model (never a regex), and returns the plan + the exact text that would be removed WITHOUT "
                + "writing. apply=true cuts the method (with its adjacent leading doc comments and the blank "
                + "line above) and writes the .bsl, but only if the result re-parses cleanly and loses exactly "
                + "this one method (safety invariant), AND force=true (destructive; deleting an EXPORTED "
                + "method breaks consumers — check callers via edt_find_references method mode and prefer "
                + "deprecation). Requires a configured token; caller must verify bsl_support_status EDITABLE "
                + "before apply.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ (Phase 2): удалить процедуру/функцию из BSL модуля – обратная операция к "
                + "edt_add_method. По умолчанию dry-run – парсит модуль, находит метод по имени через модель "
                + "(не regex) и возвращает план + точный текст, который будет удалён, БЕЗ записи. apply=true "
                + "вырезает метод (вместе с прилегающим комментарием-описанием и пустой строкой над ним) и "
                + "пишет .bsl, но только если результат парсится без ошибок и теряет ровно этот один метод "
                + "(инвариант безопасности), И передан force=true (деструктивно; удаление ЭКСПОРТНОГО метода "
                + "ломает вызывающий код – проверьте вызовы через edt_find_references по методу, "
                + "предпочитайте deprecated). Требует токен; перед apply вызывающий обязан проверить "
                + "bsl_support_status = EDITABLE.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        String fqn = getStr(args, "fqn");
        String modulePath = getStr(args, "modulePath");
        String moduleType = getStr(args, "moduleType");
        String methodName = getStr(args, "methodName");
        if (project == null || methodName == null || (fqn == null && modulePath == null)) {
            return McpServer.toolError("projectName, methodName and (fqn or modulePath) are required");
        }
        boolean apply = args.has("apply") && !args.get("apply").isJsonNull() && args.get("apply").getAsBoolean();
        boolean force = args.has("force") && !args.get("force").isJsonNull() && args.get("force").getAsBoolean();
        try {
            EdtModelGateway.DeleteMethodResult res = gateway.deleteMethod(project, fqn, moduleType, modulePath,
                    methodName, apply, force);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            if (res.applyPending) {
                o.addProperty("applyPending", true);
            }
            o.addProperty("modulePath", res.modulePath);
            o.addProperty("moduleFound", res.moduleFound);
            o.addProperty("methodFound", res.methodFound);
            if (res.methodName != null) {
                o.addProperty("methodName", res.methodName);
            }
            if (res.methodKind != null) {
                o.addProperty("methodKind", res.methodKind);
            }
            if (res.export != null) {
                o.addProperty("export", res.export.booleanValue());
            }
            o.addProperty("valid", res.valid);
            o.addProperty("force", res.forced);
            if (res.lineFrom > 0) {
                o.addProperty("lineFrom", res.lineFrom);
                o.addProperty("lineTo", res.lineTo);
            }
            if (res.deletedText != null) {
                o.addProperty("deletedText", res.deletedText);
            }
            if (res.deletedTextTruncated) {
                o.addProperty("deletedTextTruncated", true);
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
            return McpServer.toolError("edt_delete_method failed: " + e.getMessage());
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
