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

/**
 * MCP tool: edt_add_route - WRITE. Adds a route (a URL template plus one HTTP method) to an
 * HTTPService. Without it, a new route means editing the service .mdo by hand and generating the uuid
 * of both the urlTemplates block and its nested methods block. Dry-run by default (apply=false):
 * resolves the service, checks the template name is free and the HTTP method is valid, returns the
 * plan. apply=true creates the url template and method through the model (uuids from the same source
 * as the other writers) and serialises the .mdo; createHandler additionally writes a handler stub into
 * the service module. Requires a configured token; the caller must verify bsl_support_status EDITABLE
 * before apply.
 */
public final class AddRouteTool {

    private final MetadataWriteGateway gateway = new MetadataWriteGateway();

    public String name() {
        return "edt_add_route";
    }

    /** Write tool – the server gates this on a configured token. */
    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project name"));
        props.add("serviceFqn", strProp("HTTP service to add the route to, e.g. HTTPService.Payments"));
        props.add("name", strProp("URL template name – a valid 1C identifier, e.g. UserInfo. Unique within "
                + "the service."));
        props.add("template", strProp("URL template, e.g. /userinfo or /subscribers/{id}. A leading slash is "
                + "added if missing."));
        props.add("httpMethod", strProp("HTTP method: GET (default), POST, PUT, DELETE, PATCH, HEAD, OPTIONS, "
                + "..."));
        props.add("handler", strProp("Handler procedure name in the service module (a valid 1C identifier). "
                + "Optional – defaults to name+httpMethod, e.g. UserInfoGET."));
        props.add("synonymRu", strProp("Russian synonym for the url template (optional; defaults to name)."));
        props.add("createHandler", boolProp("true = also splice a handler stub Функция <handler>(Запрос) into "
                + "the service module (region ОбработчикиСобытий), created via edt_add_method. Default false."));
        props.add("apply", boolProp("false (default) = dry-run: validate + return the plan, write nothing. "
                + "true = create the url template and method and commit. Verify bsl_support_status EDITABLE "
                + "first."));

        JsonArray req = new JsonArray();
        req.add("projectName");
        req.add("serviceFqn");
        req.add("name");
        req.add("template");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE: add a route (a URL template plus one HTTP method) to an HTTPService – the write tool "
                + "for HTTP service routes, next to edt_add_attribute (attributes) and edt_add_method (module "
                + "code). Dry-run by default – resolves the service, checks the template name is free and the "
                + "HTTP method is valid, returns the plan WITHOUT writing. apply=true creates the url template "
                + "and its method through the model (uuids generated like the other writers) and serialises the "
                + ".mdo; createHandler also writes a handler stub into the service module. Requires a configured "
                + "token. Caller must verify bsl_support_status EDITABLE before any apply.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ: добавить маршрут (шаблон URL и один HTTP-метод) в HTTPService – инструмент записи "
                + "маршрутов HTTP-сервиса, рядом с edt_add_attribute (реквизиты) и edt_add_method (код модуля). "
                + "По умолчанию dry-run – находит сервис, проверяет что имя шаблона свободно и HTTP-метод "
                + "допустим, возвращает план БЕЗ записи. apply=true создаёт шаблон URL и его метод через модель "
                + "(uuid генерируются как у прочих инструментов записи) и сериализует .mdo; createHandler ещё и "
                + "пишет заглушку обработчика в модуль сервиса. Требует токен. Перед apply вызывающий обязан "
                + "проверить bsl_support_status = EDITABLE.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String project = getStr(args, "projectName");
        String serviceFqn = getStr(args, "serviceFqn");
        String name = getStr(args, "name");
        String template = getStr(args, "template");
        if (project == null || serviceFqn == null || name == null || template == null) {
            return McpServer.toolError("projectName, serviceFqn, name and template are required");
        }
        String httpMethod = getStr(args, "httpMethod");
        String handler = getStr(args, "handler");
        String synonymRu = getStr(args, "synonymRu");
        boolean createHandler = args.has("createHandler") && !args.get("createHandler").isJsonNull()
                && args.get("createHandler").getAsBoolean();
        boolean apply = args.has("apply") && !args.get("apply").isJsonNull() && args.get("apply").getAsBoolean();
        try {
            MetadataWriteGateway.AddRouteResult res = gateway.addRoute(project, serviceFqn, name, template,
                    httpMethod, handler, synonymRu, createHandler, apply);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            o.addProperty("serviceFqn", res.serviceFqn);
            o.addProperty("serviceFound", res.serviceFound);
            if (res.serviceType != null) {
                o.addProperty("serviceType", res.serviceType);
            }
            o.addProperty("name", res.name);
            if (res.template != null) {
                o.addProperty("template", res.template);
            }
            if (res.httpMethod != null) {
                o.addProperty("httpMethod", res.httpMethod);
            }
            if (res.handler != null) {
                o.addProperty("handler", res.handler);
            }
            if (res.nameAvailable != null) {
                o.addProperty("nameAvailable", res.nameAvailable.booleanValue());
            }
            o.addProperty("httpMethodValid", res.httpMethodValid);
            if (res.templateInUse) {
                o.addProperty("templateInUse", true);
            }
            if (res.templateUuid != null) {
                o.addProperty("templateUuid", res.templateUuid);
            }
            if (res.methodUuid != null) {
                o.addProperty("methodUuid", res.methodUuid);
            }
            if (res.handlerWritten) {
                o.addProperty("handlerWritten", true);
            }
            if (res.modulePath != null) {
                o.addProperty("modulePath", res.modulePath);
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
            return McpServer.toolError("edt_add_route failed: " + e.getMessage());
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
