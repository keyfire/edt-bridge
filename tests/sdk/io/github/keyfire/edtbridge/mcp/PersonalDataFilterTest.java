/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import io.github.keyfire.edtbridge.core.PersonalData;

class PersonalDataFilterTest {

    private static PersonalData redact() {
        return new PersonalData(PersonalData.Mode.REDACT, null, List.of(), List.of());
    }

    private static JsonObject answer(String json) {
        return McpServer.textResult(json);
    }

    private static JsonObject text(JsonObject result) {
        return JsonParser.parseString(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString())
                .getAsJsonObject();
    }

    @Test void theVariablesOfAFrameAreMaskedByTheirNameAndByTheirShape() {
        JsonObject result = answer("{\"ok\":true,\"threads\":[{\"name\":\"main\",\"frames\":[{\"level\":0,"
                + "\"source\":\"ОбщийМодуль.Сотрудники.Модуль\",\"variables\":["
                + "{\"name\":\"ФИО\",\"value\":\"Иванов Иван\",\"type\":\"Строка\"},"
                + "{\"name\":\"Комментарий\",\"value\":\"ИНН 7707083893\",\"type\":\"Строка\"},"
                + "{\"name\":\"Количество\",\"value\":\"3\",\"type\":\"Число\"}]}]}]}");

        JsonObject masked = text(PersonalDataFilter.apply(result, redact()));

        JsonObject frame = masked.getAsJsonArray("threads").get(0).getAsJsonObject()
                .getAsJsonArray("frames").get(0).getAsJsonObject();
        assertEquals("ОбщийМодуль.Сотрудники.Модуль", frame.get("source").getAsString());
        assertEquals("[person]", frame.getAsJsonArray("variables").get(0).getAsJsonObject().get("value").getAsString());
        assertEquals("ИНН [inn]", frame.getAsJsonArray("variables").get(1).getAsJsonObject().get("value").getAsString());
        assertEquals("3", frame.getAsJsonArray("variables").get(2).getAsJsonObject().get("value").getAsString());
        assertEquals("Строка", frame.getAsJsonArray("variables").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals(2, masked.getAsJsonObject("piiFilter").get("masked").getAsInt());
    }

    @Test void anEvaluatedExpressionIsNamedByItsLastSegment() {
        JsonObject masked = text(PersonalDataFilter.apply(answer(
                "{\"ok\":true,\"expression\":\"Объект.Фамилия\",\"success\":true,\"value\":\"Иванов\"}"), redact()));

        assertEquals("Объект.Фамилия", masked.get("expression").getAsString());
        assertEquals("[person]", masked.get("value").getAsString());
    }

    @Test void theUsersAndComputersOfTheSessionsAreMaskedWhole() {
        JsonObject masked = text(PersonalDataFilter.apply(answer("{\"ok\":true,\"sessions\":[{\"session\":\"5c1f\","
                + "\"infobase\":\"orders\",\"userName\":\"Иванов И.И.\",\"host\":\"PC-IVANOV\",\"appId\":\"1CV8C\"}]}"),
                redact()));

        JsonObject session = masked.getAsJsonArray("sessions").get(0).getAsJsonObject();
        assertEquals("[user]", session.get("userName").getAsString());
        assertEquals("[computer]", session.get("host").getAsString());
        assertEquals("orders", session.get("infobase").getAsString());
        assertEquals("1CV8C", session.get("appId").getAsString());
    }

    @Test void anErrorTextIsMaskedByItsShape() {
        JsonObject masked = PersonalDataFilter.apply(McpServer.toolError("edt_evaluate failed: нет адреса ivanov@example.com"),
                redact());

        assertEquals("edt_evaluate failed: нет адреса [email]",
                masked.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
        assertTrue(masked.get("isError").getAsBoolean());
    }

    @Test void onlyTheToolsOfDataAreFiltered() {
        JsonObject module = answer("{\"text\":\"// ИНН 7707083893\\nПроцедура А() КонецПроцедуры\",\"sourceHash\":\"abc\"}");

        assertSame(module, PersonalDataFilter.apply("edt_module_text", module));
    }
}
