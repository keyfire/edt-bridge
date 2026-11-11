/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class AllowedValuesTest {
    @Test void unknownValueNamesEveryAcceptedValue() {
        assertEquals("unknown titleLocation value: Lefft; allowed: Auto, Left, Right",
                AllowedValues.unknownValue("titleLocation", "Lefft", List.of("Auto", "Left", "Right")));
    }

    @Test void unsupportedNameNamesWhatTheTargetTakes() {
        assertEquals("unsupported form item property: colour; supported here: readOnly, width",
                AllowedValues.unsupportedName("form item property", "colour", List.of("readOnly", "width")));
    }

    @Test void unavailablePropertyNamesTheTargetAndItsOwnProperties() {
        assertEquals("choiceButton is not available on Table; available here: changeRowSet, readOnly",
                AllowedValues.unavailable("choiceButton", "Table", List.of("changeRowSet", "readOnly")));
    }

    @Test void emptyListIsSpelledOutInsteadOfLeavingADanglingColon() {
        assertEquals("unsupported field option: use; supported here: nothing",
                AllowedValues.unsupportedName("field option", "use", List.of()));
        assertEquals("unknown x value: null; allowed: nothing", AllowedValues.unknownValue("x", null, null));
    }
}
