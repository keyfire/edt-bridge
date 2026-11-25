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
import java.util.List;
import org.junit.jupiter.api.Test;
import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.model.util.BslUtil;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.McoreFactory;

class FormHandlerSignatureTest {
    @Test void itemHandlerIsRecognizedByEdtWithItsImplicitParameter() throws Exception {
        Event event = event();
        List<String> parameters = parameters(event, false, true);
        var method = BslFactory.eINSTANCE.createProcedure();
        for (String name : parameters) {
            var parameter = BslFactory.eINSTANCE.createFormalParam();
            parameter.setName(name);
            method.getFormalParams().add(parameter);
        }
        assertTrue(BslUtil.canBeEventHandlerByParamCounts(method, event.getParamSet().get(0), 1),
                "EDT's native form rename participant requires the implicit item parameter");
        assertEquals(List.of("Item"), parameters);
        assertEquals(List.of("Элемент"), parameters(event, true, true));
    }
    @Test void formHandlerUsesOnlyDeclaredParameters() throws Exception {
        Event event = event();
        var parameter = McoreFactory.eINSTANCE.createParameter();
        parameter.setName("Cancel"); parameter.setNameRu("Отказ");
        event.getParamSet().get(0).getParams().add(parameter);
        assertEquals(List.of("Cancel"), parameters(event, false, false));
        assertEquals(List.of("Отказ"), parameters(event, true, false));
        assertEquals(List.of("Item", "Cancel"), parameters(event, false, true));
        assertEquals(List.of("Элемент", "Отказ"), parameters(event, true, true));
    }
    @Test void aHandlerCalledWithoutTheFormContextGetsNoItemAndTheNoContextDirective() throws Exception {
        // As EDT 2026.2.2 gives the event: the flag for a call without context is not set.
        Event event = event();
        event.setName("OnGetDataAtServer");
        event.setNameRu("ПриПолученииДанныхНаСервере");
        assertFalse(event.isServerCallWithContextNotAllowed());
        for (String[] names : new String[][] {{"ItemName", "ИмяЭлемента"}, {"Settings", "Настройки"}, {"Rows", "Строки"}}) {
            var parameter = McoreFactory.eINSTANCE.createParameter();
            parameter.setName(names[0]);
            parameter.setNameRu(names[1]);
            event.getParamSet().get(0).getParams().add(parameter);
        }
        assertEquals(List.of("ИмяЭлемента", "Настройки", "Строки"), parameters(event, true, true));
        assertEquals(List.of("ItemName", "Settings", "Rows"), parameters(event, false, true));
        assertEquals("&НаСервереБезКонтекста", directive(event, true));
        assertEquals("&AtServerNoContext", directive(event, false));
    }

    @Test void anyEventTheModelMarksAsCalledWithoutContextIsTreatedSo() throws Exception {
        Event event = event();
        event.setName("OnSomethingAtServer");
        event.setServerCallWithContextNotAllowed(true);
        assertEquals("&AtServerNoContext", directive(event, false));
        assertEquals(List.of(), parameters(event, false, true));
    }

    @Test void aServerEventWithTheFormContextKeepsTheServerDirective() throws Exception {
        Event event = event();
        event.setName("OnCreateAtServer");
        event.setNameRu("ПриСозданииНаСервере");
        assertEquals("&НаСервере", directive(event, true));
        event.setName("OnChange");
        event.setNameRu("ПриИзменении");
        assertEquals("&НаКлиенте", directive(event, true));
    }

    private static Event event() {
        Event event = McoreFactory.eINSTANCE.createEvent();
        event.getParamSet().add(McoreFactory.eINSTANCE.createParamSet());
        return event;
    }
    private static String directive(Event event, boolean russian) throws Exception {
        var method = FormWriteGateway.class.getDeclaredMethod("directiveOf", Event.class, boolean.class);
        method.setAccessible(true);
        return (String) method.invoke(null, event, russian);
    }

    @SuppressWarnings("unchecked")
    private static List<String> parameters(Event event, boolean russian, boolean item) throws Exception {
        var method = FormWriteGateway.class.getDeclaredMethod("parameterNames", Event.class, boolean.class, boolean.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(null, event, russian, item);
    }
}
