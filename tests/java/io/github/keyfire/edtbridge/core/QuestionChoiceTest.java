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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Answering - or deliberately not answering - a question the platform asks mid-operation. */
class QuestionChoiceTest {

    @Test
    @DisplayName("nothing requested answers nothing: the default is to report, not to guess")
    void noAnswerMatchesNothing() {
        assertFalse(QuestionChoice.matches("2", "Повторить", null));
        assertFalse(QuestionChoice.matches("2", "Повторить", ""));
        assertFalse(QuestionChoice.matches("2", "Повторить", "   "));
    }

    @Test
    @DisplayName("the answer is chosen by its value, and a label still matches for a human")
    void matchesValueOrLabel() {
        assertTrue(QuestionChoice.matches("2", "Повторить", "2"));
        assertTrue(QuestionChoice.matches("2", "Повторить", " 2 "));
        assertTrue(QuestionChoice.matches("2", "Повторить", "повторить"));
        assertFalse(QuestionChoice.matches("2", "Повторить", "1"));
        assertFalse(QuestionChoice.matches("2", "Повторить", "Отмена"));
    }

    @Test
    @DisplayName("a null value or label never matches by accident")
    void nullsDoNotMatch() {
        assertFalse(QuestionChoice.matches(null, null, "2"));
        assertFalse(QuestionChoice.matches(null, "Повторить", "2"));
        assertTrue(QuestionChoice.matches(null, "Повторить", "Повторить"));
    }

    @Test
    @DisplayName("an option is shown with the value to pass back, defaults marked")
    void optionCarriesTheValue() {
        assertEquals("2 = \"Повторить\"", QuestionChoice.option("2", "Повторить", false));
        assertEquals("1 = \"Отмена\" (default)", QuestionChoice.option("1", "Отмена", true));
    }

    @Test
    @DisplayName("the report names the question, every option, and how to answer it")
    void reportIsActionable() {
        String m = QuestionChoice.report("База данных заблокирована",
                List.of("1 = \"Отмена\"", "2 = \"Повторить\"", "3 = \"Обновить динамически\""));
        assertTrue(m.contains("База данных заблокирована"), m);
        assertTrue(m.contains("Обновить динамически"), m);
        assertTrue(m.contains("answer=<value>"), m);
        // The warning matters: one of these ends other people's sessions.
        assertTrue(m.contains("sessions"), m);
    }

    @Test
    @DisplayName("a question with no options still reports rather than pretending")
    void reportWithoutOptions() {
        String m = QuestionChoice.report(null, List.of());
        assertTrue(m.contains("(no text)"), m);
        assertTrue(m.contains("(no options offered)"), m);
    }

    @Test
    @DisplayName("answered-and-still-failed is told apart from answer-not-offered")
    void answeredAndFailed() {
        String m = QuestionChoice.answeredAndFailed("Повторить", "DatabaseRestructureException: ...");
        assertTrue(m.contains("Повторить"), m);
        assertTrue(m.contains("DatabaseRestructureException"), m);
        // The two cases must not read alike: this one never claims the answer was unavailable.
        assertFalse(m.contains("not among"), m);
        // And it points at what actually settles a lively infobase.
        assertTrue(m.contains("edt_infobase_maintenance"), m);
    }

    @Test
    @DisplayName("an answer that was not offered says so, and says why it may differ per call")
    void notOffered() {
        String m = QuestionChoice.notOffered("3", List.of("1 = \"Отмена\"", "2 = \"Повторить\""));
        assertTrue(m.contains("\"3\""), m);
        assertTrue(m.contains("Повторить"), m);
        assertTrue(m.contains("does not"), m);
    }
}
