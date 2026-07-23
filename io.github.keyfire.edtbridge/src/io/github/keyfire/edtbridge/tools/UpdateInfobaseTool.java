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

import io.github.keyfire.edtbridge.edt.BslGateway;
import io.github.keyfire.edtbridge.edt.DesignerAgentGateway;
import io.github.keyfire.edtbridge.edt.PlatformGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_update_infobase - WRITE (Phase 2). Updates an infobase's configuration FROM an EDT
 * project (configuration or configuration extension) via EDT's own synchronization engine – the
 * "Update infobase configuration" action. Db-structure changes are auto-confirmed; a conflict (the
 * infobase has its own changes) aborts. Dry-run by default; token-gated. THE INFOBASE IS MUTATED –
 * use on stands you own, not production.
 */
public final class UpdateInfobaseTool {

    private final PlatformGateway gateway = new PlatformGateway();
    private final BslGateway bsl = new BslGateway();
    private final DesignerAgentGateway agent = new DesignerAgentGateway();

    public String name() {
        return "edt_update_infobase";
    }

    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject props = new JsonObject();
        props.add("projectName", strProp("EDT project to update the infobase from (a configuration "
                + "or a configuration-extension project)"));
        props.add("infobase", strProp("Target infobase name or uuid (see edt_infobases). Optional – "
                + "defaults to the project's associated infobase."));
        props.add("apply", boolProp("false (default) = dry-run: resolve the target and report the "
                + "current project-vs-infobase state. true = update the infobase configuration."));
        JsonObject transport = new JsonObject();
        transport.addProperty("type", "string");
        transport.addProperty("description", "edt (default) uses EDT's own synchronization, which "
                + "cannot authenticate to an infobase that has users. agent exports the project to "
                + "designer XML and loads it through a configurator agent, which authenticates as the "
                + "infobase user - the only way into a server infobase with users.");
        JsonArray transports = new JsonArray();
        transports.add("edt");
        transports.add("agent");
        transport.add("enum", transports);
        props.add("transport", transport);
        props.add("infobaseUser", strProp("agent transport: 1C infobase user, e.g. Администратор. "
                + "Empty for an infobase without users."));
        props.add("infobasePassword", strProp("agent transport: that user's password. Never echoed "
                + "back."));
        props.add("platformVersion", strProp("agent transport: platform version line for the "
                + "configurator, e.g. 8.5.1.1423. It has to match the server the infobase runs on."));
        props.add("extension", strProp("agent transport: load the project as this extension of the "
                + "infobase. Left out, an extension project is recognised by its adopted root and its "
                + "own name is used; a configuration project loads as the configuration."));
        props.add("updateDatabaseConfig", boolProp("agent transport: apply the database configuration "
                + "after loading (default true). Off leaves the infobase holding new code and running "
                + "the old one until edt_update_database_config is called."));
        props.add("sessionTermination", strProp("agent transport: disable (default), prompt or force - "
                + "what to do when applying needs an exclusive lock and sessions hold the infobase."));

        JsonArray req = new JsonArray();
        req.add("projectName");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "WRITE (Phase 2): update an infobase's configuration FROM an EDT project "
                + "(configuration or extension) via EDT's own synchronization engine (the \"Update "
                + "infobase configuration\" action). Db-structure changes auto-confirmed; a conflict "
                + "aborts the update. EDT synchronization has NO per-project scope: it brings the "
                + "infobase in line with EVERY workspace project associated with it - the result lists "
                + "them in syncProjects, and the dry-run plan names them; to load one project only, use "
                + "transport=agent. Dry-run by default; apply=true mutates the infobase – use stands "
                + "you own. Token-gated.\n\ntransport=agent takes a different route entirely: the "
                + "project is exported to designer XML in-process and loaded through a configurator "
                + "agent (config load-config-from-files), which authenticates AS THE INFOBASE USER. "
                + "That is the only way into a server infobase that has users, and it loads XML "
                + "straight into the base - no throwaway base and no .cf in between, which matters for "
                + "a large configuration. It then applies the database configuration unless "
                + "updateDatabaseConfig=false.");
        t.addProperty("descriptionRu",
                "ЗАПИСЬ (Phase 2): обновить конфигурацию информационной базы ИЗ проекта EDT "
                + "(конфигурация или расширение) штатным механизмом синхронизации (действие "
                + "\"Обновить конфигурацию информационной базы\"). Изменения структуры БД "
                + "подтверждаются автоматически; конфликт (в базе свои изменения) прерывает "
                + "обновление. У синхронизации EDT НЕТ границы \"один проект\": база приводится в "
                + "соответствие ВСЕМ ассоциированным с ней проектам workspace - ответ перечисляет их "
                + "в syncProjects, план dry-run называет поимённо; чтобы загрузить один проект, "
                + "используйте transport=agent. По умолчанию dry-run; apply=true МЕНЯЕТ базу – "
                + "использовать на своих стендах. Требует токен.\n\ntransport=agent идёт другим путём: "
                + "проект выгружается "
                + "в XML конфигуратора внутри процесса и загружается через агент конфигуратора "
                + "(config load-config-from-files), который проходит аутентификацию КАК ПОЛЬЗОВАТЕЛЬ "
                + "БАЗЫ. Только так доступна серверная база с пользователями, и XML грузится прямо в "
                + "базу – без промежуточной базы и без .cf, что существенно для большой конфигурации. "
                + "После загрузки применяется конфигурация базы данных, если не указано "
                + "updateDatabaseConfig=false.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        String projectName = getStr(args, "projectName");
        if (projectName == null) {
            return McpServer.toolError("projectName is required");
        }
        String infobase = getStr(args, "infobase");
        boolean apply = args.has("apply") && !args.get("apply").isJsonNull() && args.get("apply").getAsBoolean();
        if ("agent".equalsIgnoreCase(getStr(args, "transport"))) {
            return viaAgent(args, projectName, infobase, apply);
        }
        try {
            PlatformGateway.UpdateInfobaseResult res = gateway.updateInfobase(projectName, infobase, apply);
            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            o.addProperty("project", res.project);
            if (res.infobaseName != null) {
                o.addProperty("infobaseName", res.infobaseName);
                o.addProperty("infobaseUuid", res.infobaseUuid);
                o.addProperty("connected", res.connected);
            }
            if (res.equality != null) {
                o.addProperty("equality", res.equality);
                // equality compares the project with the infobase's MAIN configuration. Sessions run
                // the DATABASE configuration, which is a separate thing: loaded-but-not-applied
                // changes leave every session on the old code while this still reports EQUAL. That
                // exact reading once cost a debugging session, so say it here rather than in a doc.
                o.addProperty("equalityNote", "equality compares the project with the infobase's MAIN "
                        + "configuration - it does NOT mean the database configuration was applied, and "
                        + "sessions execute the database one. Check with edt_infobase_config_state, and "
                        + "apply it with edt_update_database_config.");
            }
            if (!res.syncProjects.isEmpty()) {
                JsonArray sync = new JsonArray();
                res.syncProjects.forEach(sync::add);
                o.add("syncProjects", sync);
            }
            if (res.status != null) {
                o.addProperty("status", res.status);
            }
            BslGateway.MethodChangesResult mc = bsl.methodChanges(res.project);
            if (mc.changesMethods) {
                o.addProperty("changesMethods", true);
                o.addProperty("registrationWarning", "this extension changes methods of the base "
                        + "configuration (" + mc.count + " interception(s)), so in the infobase it must "
                        + "be registered with safe mode and dangerous-action protection OFF - both are "
                        + "ON by default. Clear them with edt_extension_properties.");
            } else if (mc.ok) {
                o.addProperty("changesMethods", false);
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
            return McpServer.toolError("edt_update_infobase failed: " + e.getMessage());
        }
    }

    /** The agent route: export the project to XML, load it, apply the database configuration. */
    private JsonObject viaAgent(JsonObject args, String projectName, String infobase, boolean apply) {
        try {
            DesignerAgentGateway.LoadProjectResult res = agent.loadProject(
                    projectName,
                    infobase,
                    getStr(args, "extension"),
                    getStr(args, "sessionTermination"),
                    getStr(args, "infobaseUser"),
                    getStr(args, "infobasePassword"),
                    getStr(args, "platformVersion"),
                    !args.has("updateDatabaseConfig") || args.get("updateDatabaseConfig").isJsonNull()
                            || args.get("updateDatabaseConfig").getAsBoolean(),
                    apply);

            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            o.addProperty("applied", res.applied);
            o.addProperty("transport", "designer agent");
            o.addProperty("project", res.project);
            if (res.infobase != null) {
                o.addProperty("infobase", res.infobase);
            }
            if (res.platform != null) {
                o.addProperty("platform", res.platform);
            }
            if (res.extension != null) {
                o.addProperty("extension", res.extension);
            }
            if (!res.issues.isEmpty()) {
                JsonArray issues = new JsonArray();
                res.issues.forEach(issues::add);
                o.add("issues", issues);
            }
            o.addProperty("databaseConfigUpdated", res.databaseConfigUpdated);
            if (!res.databaseChanges.isEmpty()) {
                o.addProperty("databaseChangeCount", res.databaseChanges.size());
                JsonArray changes = new JsonArray();
                res.databaseChanges.forEach(changes::add);
                o.add("databaseChanges", changes);
            }
            if (res.plan != null) {
                o.addProperty("plan", res.plan);
            }
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_update_infobase (agent) failed: " + e.getMessage());
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
