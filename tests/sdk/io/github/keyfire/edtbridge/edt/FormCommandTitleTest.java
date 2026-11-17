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
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormFactory;

/** EDT-FORM-05: the title of a form command is its caption, and the tooltip is shown apart. */
class FormCommandTitleTest {

    private static FormCommand command(String title, String toolTip) {
        FormCommand command = FormFactory.eINSTANCE.createFormCommand();
        command.setName("Check");
        if (title != null) command.getTitle().put("ru", title);
        if (toolTip != null) command.getToolTip().put("ru", toolTip);
        return command;
    }

    @Test
    void theTitleIsTheCaptionAndTheTooltipIsApart() {
        // A command of a model session: the caption "Проверить", the tooltip that explains it.
        var described = new FormGateway().commandOf(command("Проверить", "Проверить артикулы номенклатуры"));
        assertEquals("Check", described.name);
        assertEquals("Проверить", described.title);
        assertEquals("Проверить артикулы номенклатуры", described.toolTip);
    }

    @Test
    void aCommandWithoutTooltipHasNone() {
        var described = new FormGateway().commandOf(command("Проверить", null));
        assertEquals("Проверить", described.title);
        assertNull(described.toolTip);
    }
}
