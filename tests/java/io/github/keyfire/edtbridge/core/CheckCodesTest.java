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
import org.junit.jupiter.api.Test;

class CheckCodesTest {
    @Test
    void lettersAndDigitsAreAShortCode() {
        assertTrue(CheckCodes.isShortCode("SU68"));
        assertTrue(CheckCodes.isShortCode("su248"));
        assertTrue(CheckCodes.isShortCode("  SU1  "));
        assertTrue(CheckCodes.isShortCode("ABCD1234"));
    }

    @Test
    void anIdOrAMessageIsNotAShortCode() {
        assertFalse(CheckCodes.isShortCode(null));
        assertFalse(CheckCodes.isShortCode(""));
        assertFalse(CheckCodes.isShortCode("SU"));
        assertFalse(CheckCodes.isShortCode("68"));
        assertFalse(CheckCodes.isShortCode("S68"));
        assertFalse(CheckCodes.isShortCode("ABCDE1"));
        assertFalse(CheckCodes.isShortCode("SU12345"));
        assertFalse(CheckCodes.isShortCode("SU68x"));
        assertFalse(CheckCodes.isShortCode("semicolon-missing"));
        assertFalse(CheckCodes.isShortCode("com.example.checks:semicolon-missing"));
        assertFalse(CheckCodes.isShortCode("Missing semicolon at the end of the statement"));
    }

    @Test
    void theQualifiedIdJoinsContributorAndCheck() {
        assertEquals("com.example.checks:semicolon-missing",
                CheckCodes.qualified("com.example.checks", "semicolon-missing"));
        assertEquals("semicolon-missing", CheckCodes.qualified(null, " semicolon-missing "));
        assertEquals("semicolon-missing", CheckCodes.qualified("  ", "semicolon-missing"));
        assertNull(CheckCodes.qualified("com.example.checks", " "));
        assertNull(CheckCodes.qualified("com.example.checks", null));
    }

    @Test
    void theBareIdDropsTheContributor() {
        assertEquals("semicolon-missing", CheckCodes.bare("com.example.checks:semicolon-missing"));
        assertEquals("semicolon-missing", CheckCodes.bare(" semicolon-missing "));
        assertNull(CheckCodes.bare(null));
    }
}
