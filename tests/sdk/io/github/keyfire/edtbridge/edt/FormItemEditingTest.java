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
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import com._1c.g5.v8.dt.form.model.*;
import com._1c.g5.v8.dt.form.service.item.IFormItemMovementService;

class FormItemEditingTest {
    @Test void placementUsesFinalIndexAndPreservesTheMovedObject() {
        assertDoesNotThrow(() -> {
            Form form=FormFactory.eINSTANCE.createForm();
            FormGroup left=group("Left"), right=group("Right"), nested=group("Nested");
            nested.setId(77); nested.getTitle().put("ru","Nested title");
            FormField child=FormFactory.eINSTANCE.createFormField(); child.setName("Child"); child.setId(88);
            EventHandler handler=FormFactory.eINSTANCE.createEventHandler(); handler.setName("OnChange");
            child.getHandlers().add(handler); nested.getItems().add(child);
            left.getItems().add(nested); form.getItems().add(left); form.getItems().add(right);
            FormItemEdits.Position plan=position(form,nested,"Right",0,null,null);
            assertSame(left,nested.eContainer());
            plan.apply(movement());
            assertSame(nested,right.getItems().get(0)); assertEquals(77,nested.getId());
            assertSame(child,nested.getItems().get(0)); assertEquals(88,child.getId());
            assertSame(handler,child.getHandlers().get(0)); assertEquals("Nested title",nested.getTitle().get("ru"));
        });
    }
    @Test void placementRejectsCyclesConflictsAndOutOfRangeWithoutChangingTree() {
        assertDoesNotThrow(() -> {
            Form form=FormFactory.eINSTANCE.createForm(); FormGroup outer=group("Outer"), inner=group("Inner");
            outer.getItems().add(inner); form.getItems().add(outer);
            assertThrows(IllegalArgumentException.class,()->position(form,outer,"Inner",null,null,null));
            assertThrows(IllegalArgumentException.class,()->position(form,inner,"",3,null,null));
            assertThrows(IllegalArgumentException.class,()->position(form,inner,"",0,"Outer",null));
            assertThrows(IllegalArgumentException.class,()->position(form,inner,"",null,"Missing",null));
            assertSame(outer,inner.eContainer()); assertSame(form,outer.eContainer());
        });
    }
    @Test void sameParentAfterAnchorUsesListWithoutTheMovedItem() {
        assertDoesNotThrow(() -> {
            Form form=FormFactory.eINSTANCE.createForm(); FormGroup a=group("A"),b=group("B"),c=group("C");
            form.getItems().addAll(List.of(a,b,c));
            FormItemEdits.Position plan=position(form,a,null,null,null,"B");
            assertEquals(List.of(a,b,c),form.getItems());
            plan.apply(movement());
            assertEquals(List.of(b,a,c),form.getItems());
        });
    }
    @Test void groupOptionsUseTypedEnumsAndInvalidOptionsDoNotPartiallyApply() {
        assertDoesNotThrow(() -> {
            FormGroup group=group("Row");
            settings(group,Map.of("groupDirection","AlwaysHorizontal","representation","WeakSeparation",
                    "showTitle",true,"united",true,"verticalStretch",false));
            UsualGroupExtInfo info=(UsualGroupExtInfo)group.getExtInfo();
            assertEquals(FormChildrenGroup.ALWAYS_HORIZONTAL,info.getGroup());
            assertEquals(true, FormItemEdits.readSettings(group).get("showTitle")); assertEquals("true", info.getShowTitle851().toString()); assertTrue(info.isUnited()); assertEquals(false,group.getVerticalStretch());
            assertThrows(IllegalArgumentException.class,()->settings(group,Map.of("groupDirection","Vertical","representation","Unknown")));
            assertEquals(FormChildrenGroup.ALWAYS_HORIZONTAL,info.getGroup());
            assertThrows(IllegalArgumentException.class,()->settings(group,Map.of("maxHeight",10)));
        });
    }
    @Test void fieldOptionsPreserveTypedChoiceValuesAndReplaceTheList() {
        assertDoesNotThrow(() -> {
            FormField field=FormFactory.eINSTANCE.createFormField(); field.setType(ManagedFormFieldType.INPUT_FIELD);
            field.setExtInfo(FormFactory.eINSTANCE.createInputFieldExtInfo());
            settings(field,Map.of("maxHeight",5,"titleLocation","Left","choiceList",List.of(
                    Map.of("value","one","titleRu","First"),Map.of("value",new BigDecimal("2.5")),Map.of("value",true))));
            InputFieldExtInfo info=(InputFieldExtInfo)field.getExtInfo();
            assertEquals(5,info.getMaxHeight()); assertEquals(FormElementTitleLocation.LEFT,field.getTitleLocation());
            assertEquals("First",info.getChoiceList().get(0).getPresentation().get("ru"));
            assertInstanceOf(com._1c.g5.v8.dt.mcore.StringValue.class,info.getChoiceList().get(0).getValue());
            assertInstanceOf(com._1c.g5.v8.dt.mcore.NumberValue.class,info.getChoiceList().get(1).getValue());
            assertInstanceOf(com._1c.g5.v8.dt.mcore.BooleanValue.class,info.getChoiceList().get(2).getValue());
            assertThrows(IllegalArgumentException.class,()->settings(field,Map.of("maxHeight",-1,"choiceList",List.of())));
            assertEquals(3,info.getChoiceList().size());
            settings(field,Map.of("choiceList",List.of())); assertTrue(info.getChoiceList().isEmpty());
        });
    }
    @Test void changingFieldTypeValidatesTheFutureChoiceListBeforeCallingEdt() {
        FormField field=FormFactory.eINSTANCE.createFormField(); field.setType(ManagedFormFieldType.INPUT_FIELD);
        field.setExtInfo(FormFactory.eINSTANCE.createInputFieldExtInfo());
        assertThrows(IllegalArgumentException.class,()->FormItemEdits.applySettings(field,
                Map.of("fieldType","CheckBoxField","choiceList",List.of(Map.of("value",true))),
                type->{throw new AssertionError("invalid options reached EDT");}));
        assertEquals(ManagedFormFieldType.INPUT_FIELD,field.getType());
        FormItemEdits.applySettings(field,Map.of("fieldType","RadioButtonField","choiceList",
                List.of(Map.of("value","first"),Map.of("value","second"))),type->{
            field.setType(type); field.setExtInfo(FormFactory.eINSTANCE.createRadioButtonsFieldExtInfo());
        });
        assertEquals(ManagedFormFieldType.RADIO_BUTTON_FIELD,field.getType());
        assertEquals(2,((RadioButtonsFieldExtInfo)field.getExtInfo()).getChoiceList().size());
        assertEquals(List.of(Map.of("value","first"),Map.of("value","second")),FormItemEdits.readSettings(field).get("choiceList"));
    }
    @Test void duplicateItemNamesAreRefusedInsteadOfGuessing() {
        Form form=FormFactory.eINSTANCE.createForm(); FormGroup first=group("Same"),second=group("same");
        form.getItems().addAll(List.of(first,second));
        assertThrows(IllegalArgumentException.class,()->FormItemEdits.findUnique(form.getItems(),"Same"));
        assertThrows(IllegalArgumentException.class,()->position(form,null,"Same",null,null,null));
    }
    @Test void currentTitleUsesOnePersistedRepresentation() {
        FormGroup group=group("Title");
        settings(group,Map.of("showTitle",true));
        UsualGroupExtInfo info=(UsualGroupExtInfo)group.getExtInfo();
        int persistedRepresentations=(info.isShowTitle()?1:0)+("true".equals(info.getShowTitle851().toString())?1:0);
        assertEquals(1,persistedRepresentations,"the legacy and 8.5.1 features both serialize as showTitle");
        assertEquals("true",info.getShowTitle851().toString());
    }
    @Test void titleRepresentationMatchesOldAndCurrentRuntimeWithoutDuplicates() {
        FormGroup group=group("Title"); UsualGroupExtInfo info=(UsualGroupExtInfo)group.getExtInfo();
        FormItemEdits.applySettings(group,Map.of("showTitle",true),type->{},false);
        assertTrue(info.isShowTitle()); assertEquals("false",info.getShowTitle851().toString());
        FormItemEdits.applySettings(group,Map.of("showTitle",true),type->{},true);
        assertFalse(info.isShowTitle()); assertEquals("true",info.getShowTitle851().toString());
        FormItemEdits.applySettings(group,Map.of("showTitle",false),type->{},true);
        assertFalse(info.isShowTitle()); assertEquals("false",info.getShowTitle851().toString());
    }
    private static FormGroup group(String name) {
        FormGroup group=FormFactory.eINSTANCE.createFormGroup(); group.setName(name);
        group.setType(ManagedFormGroupType.USUAL_GROUP); group.setExtInfo(FormFactory.eINSTANCE.createUsualGroupExtInfo()); return group;
    }
    private static FormItemEdits.Position position(Form f,FormItem item,String parent,Integer index,String before,String after) {
        return FormItemEdits.position(f,item,parent,index,before,after);
    }
    private static void settings(FormItem item,Map<String,Object> values) {
        FormItemEdits.applySettings(item,values,type->{throw new AssertionError("unexpected type change");});
    }
    private static IFormItemMovementService movement() {
        return new IFormItemMovementService() {
            public boolean moveToEnd(FormItem item,FormItemContainer parent){return move(item,parent,parent.getItems().size());}
            public boolean move(FormItem item,FormItemContainer parent,int index){
                if(item.eContainer()==parent) parent.getItems().move(index,item); else parent.getItems().add(index,item); return true;
            }
        };
    }
}
