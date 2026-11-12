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

import io.github.keyfire.edtbridge.core.CheckCodes;
import io.github.keyfire.edtbridge.edt.DocsGateway;
import io.github.keyfire.edtbridge.edt.QuickFixGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP tool: edt_check_info - READ. What an EDT validation check means and which development standard
 * it enforces.
 *
 * <p>The companion of {@code edt_project_errors}: that one says WHICH check fired and carries its id,
 * this one says WHY the rule exists. Without it the id is a slug and the standard behind it stays in
 * the IDE, where an agent cannot read it.
 */
public final class CheckInfoTool {

    private final DocsGateway gateway = new DocsGateway();

    public String name() {
        return "edt_check_info";
    }

    public JsonObject descriptor() {
        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("description", "Cap on listed checks (default 50).");

        JsonObject props = new JsonObject();
        props.add("check", strProp("Check id, exactly as edt_project_errors reports it - the "
                + "\"bundle:id\" form works as is. A short code (SU68) is resolved to its check when "
                + "projectName is given. Text that matches no id is looked up among the TITLES, so "
                + "pasting the problem message works too. Empty lists everything documented."));
        props.add("projectName", strProp("The project that reported the problem. Needed for a short "
                + "code only: the check settings of the project assign the codes, and the same check "
                + "can carry another code in another workspace."));
        props.add("language", strProp("ru for the Russian text, en (default) otherwise. The checks "
                + "ship both."));
        props.add("limit", limit);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", new JsonArray());

        JsonObject t = new JsonObject();
        t.addProperty("name", name());
        t.addProperty("description",
                "READ: what an EDT validation check means - its description, the non-compliant and "
                + "compliant examples, and LINKS TO THE 1C DEVELOPMENT STANDARDS it enforces. Pair it "
                + "with edt_project_errors, which reports the check id of every problem: that answers "
                + "\"what fired\", this answers \"why the rule exists and what the standard says\". An "
                + "exact id returns the full text; anything else lists what matches, by id or by "
                + "title, and every entry says which of the two matched. A problem can carry a short "
                + "code (SU68) instead of the id: pass projectName with it, the code is resolved "
                + "through the check settings of that project and the answer names the check in "
                + "resolvedId. The text comes from the check bundles shipped with EDT, in English or "
                + "Russian.");
        t.addProperty("descriptionRu",
                "ЧТЕНИЕ: что означает проверка EDT – её описание, примеры неправильного и правильного "
                + "кода и ССЫЛКИ НА СТАНДАРТЫ РАЗРАБОТКИ 1С, которые она проверяет. Дополняет "
                + "edt_project_errors, который отдаёт идентификатор проверки у каждой проблемы: тот "
                + "отвечает \"что сработало\", этот – \"почему такое правило и что говорит стандарт\". "
                + "Точный идентификатор возвращает полный текст, всё остальное – список того, что "
                + "совпало по идентификатору или по ЗАГОЛОВКУ, и у каждой записи сказано, чем именно "
                + "она нашлась. У проблемы вместо идентификатора бывает короткий код (SU68): "
                + "передайте вместе с ним projectName, код разрешится через настройки проверок этого "
                + "проекта, а ответ назовёт проверку в resolvedId. Текст берётся из бандлов проверок "
                + "в поставке EDT, на английском или русском.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        try {
            String check = getStr(args, "check");
            String projectName = getStr(args, "projectName");
            String language = getStr(args, "language");
            int limit = getInt(args, "limit");

            // A short code is a number of the project's check settings, and the descriptions are
            // filed by check id - resolve the code first when the project is known.
            String shortCode = null;
            String resolvedId = null;
            String unresolved = null;
            if (CheckCodes.isShortCode(check) && projectName != null && !projectName.isBlank()) {
                QuickFixGateway.ShortCode code = new QuickFixGateway().shortCode(projectName.trim(), check);
                shortCode = check.trim();
                resolvedId = code.id;
                unresolved = code.reason;
            }

            DocsGateway.CheckResult res = gateway.checkInfo(resolvedId != null ? resolvedId : check, language, limit);
            if (resolvedId != null && res.checks.isEmpty()) {
                // The description may live in another bundle than the one that contributes the check.
                res = gateway.checkInfo(CheckCodes.bare(resolvedId), language, limit);
            }
            if (resolvedId != null) {
                res.message = res.checks.isEmpty()
                        ? shortCode + " is " + resolvedId + " in " + projectName.trim() + ", and that check "
                          + "ships no description."
                        : shortCode + " is " + resolvedId + " in " + projectName.trim()
                          + (res.checks.size() == 1 ? "" : " - " + res.checks.size() + " descriptions match its id");
            } else if (unresolved != null) {
                res.message = unresolved + ". The titles are searched too: a part of the check title "
                        + "finds it without the code.";
            }

            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            if (check != null) {
                o.addProperty("query", check);
            }
            if (shortCode != null) {
                o.addProperty("shortCode", shortCode);
                if (resolvedId != null) {
                    o.addProperty("resolvedId", resolvedId);
                }
            }
            o.addProperty("language", res.language);
            o.addProperty("total", res.total);
            if (res.truncated) {
                o.addProperty("truncated", true);
            }
            JsonArray checks = new JsonArray();
            for (DocsGateway.CheckEntry entry : res.checks) {
                JsonObject one = new JsonObject();
                one.addProperty("id", entry.id);
                one.addProperty("bundle", entry.bundle);
                if (entry.title != null) {
                    one.addProperty("title", entry.title);
                }
                if (entry.matchedBy != null) {
                    one.addProperty("matchedBy", entry.matchedBy);
                }
                if (entry.text != null) {
                    one.addProperty("text", entry.text);
                }
                if (!entry.standards.isEmpty()) {
                    JsonArray standards = new JsonArray();
                    entry.standards.forEach(standards::add);
                    one.add("standards", standards);
                }
                checks.add(one);
            }
            o.add("checks", checks);
            if (res.message != null) {
                o.addProperty("message", res.message);
            }
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            return McpServer.toolError("edt_check_info failed: " + e.getMessage());
        }
    }

    private static JsonObject strProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        o.addProperty("description", desc);
        return o;
    }

    private static String getStr(JsonObject a, String k) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? a.get(k).getAsString() : null;
    }

    private static int getInt(JsonObject a, String k) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? a.get(k).getAsInt() : 0;
    }
}
