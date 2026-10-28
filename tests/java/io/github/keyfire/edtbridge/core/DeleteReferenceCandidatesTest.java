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
package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DeleteReferenceCandidatesTest {
    private static final String NAME = "Версия";

    @Test void bslFindsDirectAccessAndQueryOrDynamicNameStringsButSkipsCommentsAndLongerNames() {
        String text = """
                // Запись.Версия
                Запись.Версия = Номер;
                Запись.ВерсияПроба = 1;
                НепроверяемыеРеквизиты.Добавить("Версия");
                Запрос.Текст = "ВЫБРАТЬ
                | Источник.Версия ИЗ РегистрСведений.ВерсииПодсистем КАК Источник";
                """;
        var scan = DeleteReferenceCandidates.bsl(text, NAME, 20);
        assertEquals(1, scan.counts.getOrDefault("bslCode", 0));
        assertEquals(2, scan.counts.getOrDefault("bslStringOrQuery", 0));
        assertEquals(3, scan.candidateCount());
        assertEquals(2, scan.examples.get(0).line());
    }

    @Test void bslUnderstandsEscapedQuotesAndDoesNotTreatCommentDelimitersInsideStringsAsComments() {
        var scan = DeleteReferenceCandidates.bsl(
                "Текст = \"// \"\"Версия\"\"\"; // Версия\nЗапись.версия = 1;", NAME, 20);
        assertEquals(2, scan.candidateCount());
        assertEquals(1, scan.counts.get("bslStringOrQuery"));
        assertEquals(1, scan.counts.get("bslCode"));
    }

    @Test void formReadsOnlyActualQueryFieldsAndDataPathsWithLogicalOwners() throws Exception {
        String xml = """
                <form xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <title>Версия</title>
                  <attributes><name>Список</name><extInfo xsi:type="form:DynamicListExtInfo">
                    <queryText>SELECT List.Версия FROM InformationRegister.Versions AS List
                // List.Версия
                WHERE "Версия" = "Версия"</queryText>
                  </extInfo></attributes>
                  <items xsi:type="form:InputField"><name>ВерсияПоле</name><dataPath>Запись.Версия</dataPath></items>
                  <items xsi:type="form:InputField"><name>Похожее</name><dataPath>Запись.ВерсияПроба</dataPath></items>
                </form>
                """;
        var scan = DeleteReferenceCandidates.xml(xml.getBytes(StandardCharsets.UTF_8), NAME, true, 20);
        assertEquals(1, scan.counts.get("queryField"));
        assertEquals(1, scan.counts.get("formBinding"));
        assertEquals(2, scan.candidateCount());
        assertEquals("Список", scan.examples.get(0).owner());
        assertEquals("ВерсияПоле", scan.examples.get(1).owner());
        assertEquals("Запись.Версия", scan.controls.get(0).dataPath());
    }

    @Test void dcsHandlesBomAndReportsOnlyRealDatasetQueries() throws Exception {
        String xml = """
                <schema xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <title>Версия</title>
                  <dataSets xsi:type="schema:DataCompositionSchemaDataSetQuery">
                    <name>Main</name><query>SELECT Source.Версия FROM InformationRegister.Versions AS Source</query>
                  </dataSets>
                </schema>
                """;
        byte[] source = ("\uFEFF" + xml).getBytes(StandardCharsets.UTF_16LE);
        var scan = DeleteReferenceCandidates.xml(source, NAME, false, 20);
        assertEquals(1, scan.candidateCount());
        assertEquals("Main", scan.examples.get(0).owner());
    }

    @Test void sampleCapDoesNotPretendToBeTheTotalCount() {
        var scan = DeleteReferenceCandidates.bsl("A.Версия = 1;\nB.Версия = 2;\nC.Версия = 3;", NAME, 1);
        assertEquals(3, scan.candidateCount());
        assertEquals(1, scan.examples.size());
    }

    @Test void detectsRetainedControlsWhoseDataPathWasClearedWithoutConfusingRemovedControls() throws Exception {
        String before = """
                <form xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                <items xsi:type="form:InputField"><name>Keep</name><dataPath>Record.Версия</dataPath></items>
                <items xsi:type="form:InputField"><name>Removed</name><dataPath>Record.Версия</dataPath></items>
                </form>
                """;
        String after = """
                <form xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                <items xsi:type="form:InputField"><name>Keep</name></items>
                <items xsi:type="form:InputField"><name>Other</name></items>
                </form>
                """;
        var old = DeleteReferenceCandidates.xml(before.getBytes(StandardCharsets.UTF_8), NAME, true, 20);
        var now = DeleteReferenceCandidates.xml(after.getBytes(StandardCharsets.UTF_8), NAME, true, 20);
        var unbound = DeleteReferenceCandidates.retainedWithoutDataPath(old.controls, now.controls, NAME);
        assertEquals(1, unbound.size());
        assertEquals("Keep", unbound.get(0).name());
    }

    @Test void malformedXmlAndDtdFailInsteadOfReturningACompleteEmptyAudit() {
        assertThrows(Exception.class, () -> DeleteReferenceCandidates.xml(
                "<form>".getBytes(StandardCharsets.UTF_8), NAME, true, 20));
        assertThrows(Exception.class, () -> DeleteReferenceCandidates.xml(
                "<!DOCTYPE form SYSTEM 'file:///unreadable'><form/>".getBytes(StandardCharsets.UTF_8), NAME, true, 20));
    }
}
