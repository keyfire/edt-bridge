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

import io.github.keyfire.edtbridge.edt.DocsGateway;
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
                + "\"bundle:id\" form works as is. Text that matches no id is looked up among the "
                + "TITLES, which is how a problem whose id is a short code (SU200) still finds its "
                + "documentation: paste the problem message. Empty lists everything documented."));
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
                + "title - so pasting a problem message works when its id is a short code (SU200) "
                + "whose mapping lives in the check engine rather than in these resources. Every "
                + "entry says which of the two matched. The text comes from the check bundles shipped "
                + "with EDT, in English or Russian.");
        t.addProperty("descriptionRu",
                "ЧТЕНИЕ: что означает проверка EDT – её описание, примеры неправильного и правильного "
                + "кода и ССЫЛКИ НА СТАНДАРТЫ РАЗРАБОТКИ 1С, которые она проверяет. Дополняет "
                + "edt_project_errors, который отдаёт идентификатор проверки у каждой проблемы: тот "
                + "отвечает \"что сработало\", этот – \"почему такое правило и что говорит стандарт\". "
                + "Точный идентификатор возвращает полный текст, всё остальное – список того, что "
                + "совпало по идентификатору или по ЗАГОЛОВКУ: поэтому работает и вставка текста "
                + "проблемы, у которой идентификатор – короткий код (SU200), а его сопоставление "
                + "живёт в движке проверок, а не в этих ресурсах. У каждой записи сказано, чем "
                + "именно она нашлась. Текст берётся из бандлов проверок в поставке EDT, на "
                + "английском или русском.");
        t.add("inputSchema", schema);
        return t;
    }

    public JsonObject call(JsonObject args) {
        try {
            DocsGateway.CheckResult res = gateway.checkInfo(
                    getStr(args, "check"),
                    getStr(args, "language"),
                    getInt(args, "limit"));

            JsonObject o = new JsonObject();
            o.addProperty("ok", res.ok);
            if (res.query != null) {
                o.addProperty("query", res.query);
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
