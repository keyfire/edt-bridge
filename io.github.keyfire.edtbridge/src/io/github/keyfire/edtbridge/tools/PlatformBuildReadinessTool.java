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

import java.math.BigDecimal;
import java.time.Duration;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.keyfire.edtbridge.edt.PlatformGateway;
import io.github.keyfire.edtbridge.mcp.McpServer;

/** MCP tool: explicit disk Designer readiness probe, token-gated. */
public final class PlatformBuildReadinessTool {
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private final PlatformGateway gateway = new PlatformGateway();

    public String name() {
        return "edt_platform_build_readiness";
    }

    public boolean isWrite() {
        return true;
    }

    public JsonObject descriptor() {
        JsonObject version = new JsonObject();
        version.addProperty("type", "string");
        version.addProperty("description", "Platform build or version line. The newest matching full "
                + "installation is checked. Omit to check the newest full installation.");
        JsonObject timeout = new JsonObject();
        timeout.addProperty("type", "integer");
        timeout.addProperty("description", "Time limit in seconds for each process.");
        timeout.addProperty("minimum", 1);
        timeout.addProperty("maximum", MAX_TIMEOUT_SECONDS);
        timeout.addProperty("default", DEFAULT_TIMEOUT_SECONDS);

        JsonObject props = new JsonObject();
        props.add("platformVersion", version);
        props.add("timeoutSeconds", timeout);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.addProperty("additionalProperties", false);

        JsonObject tool = new JsonObject();
        tool.addProperty("name", name());
        tool.addProperty("description", "WRITE: check whether an installed 1C:Enterprise Designer can "
                + "obtain a license and run in batch mode. Creates a temporary file infobase, dumps "
                + "its empty configuration, then removes the temporary files. Reports installation, "
                + "infobase creation, Designer and cleanup separately. Requires a token.");
        tool.addProperty("descriptionRu", "ЗАПИСЬ. Проверяет, может ли установленный Конфигуратор "
                + "1С:Предприятия получить лицензию и выполнить операцию в пакетном режиме. "
                + "Создает временную файловую базу, выгружает ее пустую конфигурацию и удаляет "
                + "временные файлы. Отдельно сообщает результат поиска установки, создания базы, "
                + "запуска Конфигуратора и удаления файлов. Требует токен.");
        tool.add("inputSchema", schema);
        return tool;
    }

    public JsonObject call(JsonObject args) {
        JsonElement version = args.get("platformVersion");
        if (version != null && (!version.isJsonPrimitive() || !version.getAsJsonPrimitive().isString())) {
            return McpServer.toolError("platformVersion must be a string");
        }
        int timeout;
        try {
            JsonElement supplied = args.get("timeoutSeconds");
            if (supplied == null) {
                timeout = DEFAULT_TIMEOUT_SECONDS;
            } else if (supplied.isJsonPrimitive() && supplied.getAsJsonPrimitive().isNumber()) {
                timeout = new BigDecimal(supplied.getAsString()).intValueExact();
            } else {
                timeout = 0;
            }
        } catch (NumberFormatException | ArithmeticException ex) {
            timeout = 0;
        }
        if (timeout < 1 || timeout > MAX_TIMEOUT_SECONDS) {
            return McpServer.toolError("timeoutSeconds must be an integer between 1 and " + MAX_TIMEOUT_SECONDS);
        }
        try {
            var result = gateway.platformBuildReadiness(version == null ? null : version.getAsString(),
                    Duration.ofSeconds(timeout));
            return McpServer.textResult(new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (RuntimeException ex) {
            return McpServer.toolError("edt_platform_build_readiness failed");
        }
    }
}
