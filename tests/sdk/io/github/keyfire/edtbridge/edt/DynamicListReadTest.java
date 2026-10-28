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
package io.github.keyfire.edtbridge.edt;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com._1c.g5.v8.dt.form.model.*;
import com._1c.g5.v8.dt.metadata.dbview.*;
class DynamicListReadTest {
 @Test void readsOnlyTheExactAttributesDynamicListAndBoundsItsQuery() {
  assertDoesNotThrow(()->{
   FormAttribute attribute=FormFactory.eINSTANCE.createFormAttribute(); attribute.setName("Orders");
   DynamicListExtInfo info=FormFactory.eINSTANCE.createDynamicListExtInfo(); info.setCustomQuery(true);
   info.setQueryText("SELECT Ref FROM Document.Order");
   DbViewDef table=DbViewFactory.eINSTANCE.createDbViewTableDef(); table.setName("Document.Order"); info.setMainTable(table); attribute.setExtInfo(info);
   FormGateway.FormAttr result=read(attribute,10);
   assertEquals("Orders",result.name);
   FormGateway.DynamicListInfo dynamic=result.dynamicList;
   assertEquals(true,dynamic.customQuery);
   assertEquals("Document.Order",dynamic.mainTable);
   assertEquals("SELECT Ref",dynamic.queryText);
   assertEquals(true,dynamic.queryTextTruncated);
   FormAttribute plain=FormFactory.eINSTANCE.createFormAttribute(); plain.setName("Plain");
   assertNull(read(plain,10).dynamicList);
  });
 }
 @Test void fullQueryAndEmptyMainTableRemainAttachedToTheirOwnerAttribute() {
  FormAttribute first=FormFactory.eINSTANCE.createFormAttribute(); first.setName("First");
  DynamicListExtInfo info=FormFactory.eINSTANCE.createDynamicListExtInfo(); info.setQueryText("SELECT 1"); first.setExtInfo(info);
  FormGateway.FormAttr result=read(first,8);
  assertEquals("First",result.name); assertFalse(result.dynamicList.customQuery);
  assertNull(result.dynamicList.mainTable); assertEquals("SELECT 1",result.dynamicList.queryText);
  assertEquals(8,result.dynamicList.queryTextLength); assertFalse(result.dynamicList.queryTextTruncated);
 }
 private static FormGateway.FormAttr read(FormAttribute attribute,int limit) {
  return FormGateway.attributeOf(attribute,limit);
 }
}
