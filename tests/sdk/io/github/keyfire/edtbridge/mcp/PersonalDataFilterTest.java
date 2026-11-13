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

    @Test void aNumberOfDataIsMaskedAndANumberOfTheStructureIsNot() {
        JsonObject masked = text(PersonalDataFilter.apply(answer("{\"variables\":[{\"name\":\"ИНН\",\"value\":7707083893},"
                + "{\"name\":\"Количество\",\"value\":3}],\"line\":7707083893}"), redact()));

        assertEquals("[inn]", masked.getAsJsonArray("variables").get(0).getAsJsonObject().get("value").getAsString());
        assertEquals(3, masked.getAsJsonArray("variables").get(1).getAsJsonObject().get("value").getAsInt());
        assertEquals(7707083893L, masked.get("line").getAsLong());
    }

    @Test void theElementsOfAListTakeTheNameOfItsKey() {
        JsonObject masked = text(PersonalDataFilter.apply(answer("{\"ФИО\":[\"Иванов Иван\",\"Петров Петр\"]}"), redact()));

        assertEquals("[person]", masked.getAsJsonArray("ФИО").get(0).getAsString());
        assertEquals("[person]", masked.getAsJsonArray("ФИО").get(1).getAsString());
    }

    @Test void theMaintenanceAndTheDesignerAgentAreToolsOfData() {
        // Both answer with the sessions or the user of an infobase.
        assertTrue(PersonalDataFilter.TOOLS.contains("edt_infobase_maintenance"));
        assertTrue(PersonalDataFilter.TOOLS.contains("edt_designer_agent"));
        JsonObject masked = text(PersonalDataFilter.apply(answer("{\"blockers\":[{\"session\":\"5c1f\","
                + "\"userName\":\"Иванов И.И.\",\"host\":\"PC-IVANOV\"}]}"), redact()));
        assertEquals("[user]", masked.getAsJsonArray("blockers").get(0).getAsJsonObject().get("userName").getAsString());
    }

    @Test void theServiceFieldsOfTheDesignerAgentStayAsTheyAre() {
        // The suffix of a temporary directory is a random number: 6670017245504332848 passes the Luhn check.
        JsonObject agent = text(PersonalDataFilter.apply(answer("{\"agents\":[{\"infobase\":\"orders\","
                + "\"connectionString\":\"File=\\\"/srv/bases/7707083893\\\";\","
                + "\"baseDir\":\"/tmp/edtbridge-agent-6670017245504332848\",\"clusterSessionId\":\"5c1f\","
                + "\"user\":\"Иванов И.И.\",\"port\":1550}]}"), redact())).getAsJsonArray("agents").get(0).getAsJsonObject();

        assertEquals("/tmp/edtbridge-agent-6670017245504332848", agent.get("baseDir").getAsString());
        assertEquals("File=\"/srv/bases/7707083893\";", agent.get("connectionString").getAsString());
        assertEquals("[user]", agent.get("user").getAsString());
        assertEquals(1550, agent.get("port").getAsInt());
    }

    @Test void onlyTheToolsOfDataAreFiltered() {
        JsonObject module = answer("{\"text\":\"// ИНН 7707083893\\nПроцедура А() КонецПроцедуры\",\"sourceHash\":\"abc\"}");

        assertSame(module, PersonalDataFilter.apply("edt_module_text", module));
    }
}
