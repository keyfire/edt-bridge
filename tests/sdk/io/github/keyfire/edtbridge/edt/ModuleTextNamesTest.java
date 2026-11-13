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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class ModuleTextNamesTest {

    @Test
    void aMissingMethodIsAnsweredWithTheNamesThatLookLikeIt() {
        List<String> names = List.of("ПриСозданииНаСервере", "ФормаОтчетаПриСозданииНаСервере", "Заполнить");
        assertEquals("; similar names: ПриСозданииНаСервере, ФормаОтчетаПриСозданииНаСервере",
                BslGateway.similarNames(names, "присозданиинасервере"));
        assertEquals("; the module has 3 methods, pass includeMethods=true for the list",
                BslGateway.similarNames(names, "Печать"));
    }
}
