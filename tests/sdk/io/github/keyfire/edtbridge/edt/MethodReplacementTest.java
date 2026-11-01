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
import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import org.eclipse.xtext.nodemodel.impl.NodeModelBuilder;
import org.junit.jupiter.api.Test;
import io.github.keyfire.edtbridge.tools.ReplaceMethodTool;

class MethodReplacementTest {
    @Test void acceptsOneMethodWithDocumentationPragmasAndItsTrailingComment() {
        String text = "// Method documentation\n&AtClient\nProcedure Run(Value) Export\nEndProcedure // tail";
        var module = module(text, "Run", false);
        assertSame(module.getMethods().get(0),
                BslRefactorGateway.replacementMethod(module, text, "run", false));
    }
    @Test void refusesAnotherNameOrKindAndMoreThanOneMethod() {
        String text = "Procedure Other()\nEndProcedure";
        assertThrows(IllegalArgumentException.class,
                () -> BslRefactorGateway.replacementMethod(module(text, "Other", false), text, "Run", false));
        String function = "Function Run()\n    Return 1;\nEndFunction";
        assertThrows(IllegalArgumentException.class,
                () -> BslRefactorGateway.replacementMethod(module(function, "Run", true), function, "Run", false));
        var module = module("Procedure Run()\nEndProcedure", "Run", false);
        var extra = BslFactory.eINSTANCE.createProcedure(); extra.setName("Other"); module.getMethods().add(extra);
        assertThrows(IllegalArgumentException.class,
                () -> BslRefactorGateway.replacementMethod(module, "Procedure Run()\nEndProcedure", "Run", false));
    }
    @Test void refusesModuleCodeAndPreprocessorOrRegionWrappers() {
        for (String[] wrapper : new String[][] {
                {"Var Outside;\n", ""}, {"Var Outside; ", ""}, {"", "\nMessage(1);"},
                {"#Region Public\n", "\n#EndRegion"},
                {"#If Server Then\n", "\n#EndIf"}, {"\ufeff", ""}}) {
            String text = wrapper[0] + "Procedure Run()\nEndProcedure" + wrapper[1];
            assertThrows(IllegalArgumentException.class,
                    () -> BslRefactorGateway.replacementMethod(module(text, "Run", false), text, "Run", false), text);
        }
    }
    @Test void caseInsensitiveMethodIdentityDoesNotPermitArbitraryTextOutsideTheMethod() {
        String text = "Procedure RUN()\nEndProcedure";
        assertNotNull(BslRefactorGateway.replacementMethod(module(text, "RUN", false), text, "Run", false));
        String extra = "// Separated header\n\n" + text;
        assertThrows(IllegalArgumentException.class,
                () -> BslRefactorGateway.replacementMethod(module(extra, "RUN", false), extra, "Run", false));
    }
    @Test void targetRangeKeepsModuleCodeOnTheSameLineOutsideTheReplacement() {
        String text = "Var Keep; Procedure Run()\nEndProcedure";
        var module = module(text, "Run", false);
        int[] range = BslGateway.methodRange(module.getMethods().get(0), text);
        assertEquals("Var Keep; ", text.substring(0, range[0]));
        assertEquals("Procedure Run()\nEndProcedure", text.substring(range[0], range[1]));
    }
    @Test void descriptorRequiresTheTargetAndReplacementAndMarksWrites() {
        var tool = new ReplaceMethodTool();
        var descriptor = tool.descriptor();
        assertEquals("edt_replace_method", descriptor.get("name").getAsString());
        assertTrue(tool.isWrite());
        var schema = descriptor.getAsJsonObject("inputSchema");
        assertTrue(schema.getAsJsonArray("required").asList().stream()
                .anyMatch(value -> value.getAsString().equals("methodText")));
        assertTrue(schema.getAsJsonObject("properties").has("expectedHash"));
        assertTrue(schema.getAsJsonObject("properties").has("force"));
        assertTrue(tool.call(new com.google.gson.JsonObject()).get("isError").getAsBoolean());
    }
    private static Module module(String text, String name, boolean function) {
        var module = BslFactory.eINSTANCE.createModule();
        Method method = function ? BslFactory.eINSTANCE.createFunction() : BslFactory.eINSTANCE.createProcedure();
        method.setName(name); module.getMethods().add(method);
        var builder = new NodeModelBuilder(); var root = builder.newRootNode(text);
        var node = builder.newCompositeNode(null, 0, root);
        String keyword = function ? "Function" : "Procedure";
        String ending = function ? "EndFunction" : "EndProcedure";
        int start = text.indexOf(keyword); int end = text.indexOf(ending) + ending.length();
        builder.newLeafNode(start, end - start, null, false, null, node);
        builder.associateWithSemanticElement(node, method);
        if (text.contains("&AtClient")) {
            var pragma = BslFactory.eINSTANCE.createPragma(); method.getPragmas().add(pragma);
            var pn = builder.newCompositeNode(null, 0, root);
            builder.newLeafNode(text.indexOf("&AtClient"), "&AtClient".length(), null, false, null, pn);
            builder.associateWithSemanticElement(pn, pragma);
        }
        return module;
    }
}
