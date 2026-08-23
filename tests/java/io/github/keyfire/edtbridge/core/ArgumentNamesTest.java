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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Argument names judged against the schema, so a misspelled one is refused instead of dropped. */
class ArgumentNamesTest {

    private static final List<String> DELETE_PROJECT =
            Arrays.asList("projectName", "deleteContent", "force", "apply");

    @Test
    @DisplayName("a name the schema knows passes")
    void knownNamesPass() {
        assertTrue(ArgumentNames.unknown(Arrays.asList("projectName", "force"), DELETE_PROJECT).isEmpty());
        assertNull(ArgumentNames.refusal("edt_delete_project",
                Arrays.asList("projectName", "deleteContent", "apply"), DELETE_PROJECT));
    }

    @Test
    @DisplayName("the plural that is not in the schema is named, with the name that is")
    void theNearMissIsNamed() {
        assertEquals(List.of("deleteContents"),
                ArgumentNames.unknown(Arrays.asList("projectName", "deleteContents"), DELETE_PROJECT));
        assertEquals("deleteContent", ArgumentNames.nearest("deleteContents", DELETE_PROJECT));
        String refusal = ArgumentNames.refusal("edt_delete_project",
                Arrays.asList("projectName", "deleteContents", "force"), DELETE_PROJECT);
        assertTrue(refusal.contains("'deleteContents'"), refusal);
        assertTrue(refusal.contains("did you mean 'deleteContent'"), refusal);
        assertTrue(refusal.contains("apply, deleteContent, force, projectName"), refusal);
    }

    @Test
    @DisplayName("case and the punctuation between words are not a different name")
    void caseAndPunctuationAreForgiven() {
        assertEquals("projectName", ArgumentNames.nearest("projectname", DELETE_PROJECT));
        assertEquals("projectName", ArgumentNames.nearest("project_name", DELETE_PROJECT));
        assertEquals("deleteContent", ArgumentNames.nearest("delete-content", DELETE_PROJECT));
    }

    @Test
    @DisplayName("a name that resembles nothing is refused without a guess")
    void nothingIsGuessedFromAFarName() {
        assertNull(ArgumentNames.nearest("whatever", DELETE_PROJECT));
        String refusal = ArgumentNames.refusal("edt_delete_project",
                Arrays.asList("whatever"), DELETE_PROJECT);
        assertTrue(refusal.contains("'whatever'"), refusal);
        assertTrue(!refusal.contains("did you mean"), refusal);
    }

    @Test
    @DisplayName("a short name forgives one edit, not two")
    void shortNamesAreJudgedStricter() {
        List<String> declared = Arrays.asList("kind", "apply");
        assertEquals("kind", ArgumentNames.nearest("kinds", declared));
        assertNull(ArgumentNames.nearest("kinder", declared));
    }

    @Test
    @DisplayName("several unknown names are all named in one answer")
    void everyUnknownNameIsNamed() {
        String refusal = ArgumentNames.refusal("edt_delete_project",
                Arrays.asList("deleteContents", "forced"), DELETE_PROJECT);
        assertTrue(refusal.contains("unknown arguments"), refusal);
        assertTrue(refusal.contains("'deleteContents'"), refusal);
        assertTrue(refusal.contains("'forced'"), refusal);
        assertTrue(refusal.contains("did you mean 'force'"), refusal);
    }

    @Test
    @DisplayName("a tool without arguments says so instead of listing nothing")
    void aToolWithoutArgumentsSaysSo() {
        String refusal = ArgumentNames.refusal("edt_projects", Arrays.asList("projectName"), List.of());
        assertTrue(refusal.contains("(none)"), refusal);
    }
}
