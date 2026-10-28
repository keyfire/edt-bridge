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
class QueryTextFieldsTest {
    @Test void extractsOnlyTheDynamicListQueryAndAttributeIdentity() throws Exception {
        String xml="<Form xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><attributes><name>List</name><extInfo xsi:type=\"form:DynamicListExtInfo\"><queryText>SELECT Ref\nFROM Catalog.Products</queryText></extInfo></attributes><attributes><name>Caption</name><queryText>not a query field</queryText></attributes></Form>";
        var queries=QueryTextFields.extract(xml);
        assertEquals(1,queries.size());
        assertEquals("dynamicList",queries.get(0).kind());
        assertEquals("List",queries.get(0).ownerName());
        assertEquals("SELECT Ref\nFROM Catalog.Products",queries.get(0).text());
    }
    @Test void extractsPlatformAndEdtDcsDatasetQueries() throws Exception {
        for(String type:new String[]{"DataSetQuery","dcs:DataCompositionSchemaDataSetQuery"}) {
            var queries=QueryTextFields.extract("<schema xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><dataSet xsi:type=\""+type+"\"><query>SELECT 1 &gt; 0</query><name>Main</name></dataSet><parameter><query>ignored</query></parameter></schema>");
            assertEquals(1,queries.size());
            assertEquals("dcs",queries.get(0).kind());
            assertEquals("Main",queries.get(0).ownerName());
            assertEquals("SELECT 1 > 0",queries.get(0).text());
        }
    }
    @Test void decodesBomAndCdataAndPreservesQueryLineBreaks() throws Exception {
        String xml="<schema xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n<dataSet xsi:type=\"DataSetQuery\"><name>Main</name><query><![CDATA[SELECT 1\nFROM Catalog.Items]]></query></dataSet></schema>";
        var queries=QueryTextFields.extract(("\uFEFF"+xml).getBytes(StandardCharsets.UTF_16LE));
        assertEquals("SELECT 1\nFROM Catalog.Items",queries.get(0).text());
        assertEquals(2,queries.get(0).xmlLine());
    }
    @Test void malformedXmlAndEntitiesAreErrorsRatherThanAnEmptySuccess() {
        assertThrows(java.io.IOException.class,()->QueryTextFields.extract("<broken>"));
        assertThrows(java.io.IOException.class,()->QueryTextFields.extract("<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///not-read'>]><Form/>"));
    }
    @Test void neverTreatsOrdinaryQueryNamedNodesAsQuerySources() throws Exception {
        assertTrue(QueryTextFields.extract("<Form><query>SELECT 1</query><extInfo><queryText>SELECT 2</queryText></extInfo></Form>").isEmpty());
    }
}
