/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class PersonalDataTest {

    /** The cases the wrapper answers too (python/tests/test_pii.py): the two must not drift apart. */
    @TestFactory List<DynamicTest> theSharedCases() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        List<String> lines = Files.readAllLines(Path.of("tests/pii/cases.tsv"), StandardCharsets.UTF_8);
        for (int number = 1; number <= lines.size(); number++) {
            String line = lines.get(number - 1);
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            assertEquals(4, fields.length, "line " + number);
            String mode = fields[0];
            String name = "-".equals(fields[1]) ? null : fields[1];
            String value = fields[2].replace("<empty>", "");
            String expected = fields[3].replace("<empty>", "");
            tests.add(DynamicTest.dynamicTest("line " + number, () -> {
                PersonalData filter = new PersonalData(PersonalData.mode(mode), "test-salt", List.of(), List.of());
                assertEquals(expected, filter.named(name, value));
            }));
        }
        assertFalse(tests.isEmpty());
        return tests;
    }

    @Test void aFilterThatIsOffChangesNothing() {
        PersonalData filter = new PersonalData(PersonalData.mode(""), null, List.of(), List.of());
        assertFalse(filter.active());
        assertEquals("ИНН 7707083893", filter.named("ФИО", "ИНН 7707083893"));
        assertEquals(0, filter.count());
    }

    @Test void ownRulesExtendTheBuiltInOnes() {
        PersonalData filter = new PersonalData(PersonalData.Mode.REDACT, null,
                List.of(new PersonalData.Rule("contract", Pattern.compile("Д-[0-9]{6}"))),
                List.of(PersonalData.name("person", "Сотрудник")));
        assertEquals("по договору [contract]", filter.text("по договору Д-123456"));
        assertEquals("[person]", filter.named("сотрудник", "Петров"));
        assertEquals(2, filter.count());
    }

    @Test void anUnknownModeIsRefused() {
        assertEquals(PersonalData.Mode.PSEUDONYM, PersonalData.mode(" Pseudonym "));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> PersonalData.mode("mask"));
        assertTrue(refused.getMessage().contains(PersonalData.MODE_ENV));
    }

    @Test void withoutASaltPseudonymsHoldWithinOneFilter() {
        PersonalData filter = new PersonalData(PersonalData.Mode.PSEUDONYM, null, List.of(), List.of());
        assertEquals(filter.text("a@b.ru"), filter.text("A@B.RU"));
        assertNotEquals(new PersonalData(PersonalData.Mode.PSEUDONYM, "salt", List.of(), List.of()).text("a@b.ru"),
                filter.text("a@b.ru"));
    }
}
