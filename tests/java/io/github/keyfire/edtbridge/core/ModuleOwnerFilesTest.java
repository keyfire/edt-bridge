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
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModuleOwnerFilesTest {
    private static final Set<String> PROJECT = Set.of(
            "src/Documents/Order/Order.mdo",
            "src/Documents/Order/ObjectModule.bsl",
            "src/Documents/Order/Forms/Main/Form.form",
            "src/Documents/Order/Forms/Main/Module.bsl",
            "src/CommonForms/Notice/Form.form",
            "src/CommonModules/Tools/Tools.mdo");

    private static String problem(String path) {
        return ModuleOwnerFiles.problem(path, PROJECT::contains);
    }

    @Test
    void modulesBesideTheirMetadataPass() {
        assertNull(problem("src/Documents/Order/Forms/Main/Module.bsl"));
        assertNull(problem("src/Documents/Order/ObjectModule.bsl"));
        assertNull(problem("src/CommonForms/Notice/Module.bsl"));
        assertNull(problem("src\\Documents\\Order\\Forms\\Main\\Module.bsl"));
    }

    @Test
    void formModuleWithoutFormModelIsDetached() {
        // The form the project never received: its module was written as a plain file.
        String refusal = problem("src/Documents/Order/Forms/DocumentForm/Module.bsl");
        assertNotNull(refusal);
        assertTrue(refusal.contains("Form.form"));
        assertTrue(refusal.contains("edt_adopt_object"));
        assertTrue(refusal.contains("Document.Order.Form.DocumentForm"));
    }

    @Test
    void objectModuleWithoutObjectIsDetached() {
        String refusal = problem("src/Documents/Invoice/ObjectModule.bsl");
        assertNotNull(refusal);
        assertTrue(refusal.contains("Invoice.mdo"));
    }

    @Test
    void modulesOutsideTheCheckAreLeftAlone() {
        assertNull(problem("src/CommonModules/Tools/Module.bsl"));
        assertNull(problem("src/Documents/Order/Commands/Print/CommandModule.bsl"));
        assertNull(problem(null));
        assertNull(problem("Module.bsl"));
    }
}
