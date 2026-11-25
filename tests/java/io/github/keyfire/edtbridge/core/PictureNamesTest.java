/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PictureNamesTest {
    @Test void aStandardAndACommonPictureAreNamedAsFormFormStoresThem() {
        assertEquals(new PictureNames.Name(PictureNames.Kind.STANDARD, "Post"), PictureNames.parse("StdPicture.Post"));
        assertEquals(new PictureNames.Name(PictureNames.Kind.COMMON, "Гиперссылка"),
                PictureNames.parse("CommonPicture.Гиперссылка"));
        assertEquals("StdPicture.Post", PictureNames.parse("stdpicture.Post").stored());
        assertEquals("CommonPicture.Логотип", PictureNames.parse("ОбщаяКартинка.Логотип").stored());
    }

    @Test void theBslSpellingMayMeanEither() {
        assertEquals(PictureNames.Kind.EITHER, PictureNames.parse("БиблиотекаКартинок.Провести").kind());
        assertEquals("Провести", PictureNames.parse(" БиблиотекаКартинок.Провести ").name());
    }

    @Test void anEmptyNameClearsThePicture() {
        assertNull(PictureNames.parse(""));
        assertNull(PictureNames.parse("   "));
        assertNull(PictureNames.parse(null));
    }

    @Test void anythingElseIsRefusedWithTheFormsItTakes() {
        for (String wrong : new String[] {"Post", "StdPicture.", "Picture.Post", "StdPicture.Post.Big", "CommonPicture.1Logo"}) {
            var error = assertThrows(IllegalArgumentException.class, () -> PictureNames.parse(wrong), wrong);
            assertTrue(error.getMessage().contains("StdPicture.<Name>"), error.getMessage());
        }
    }
}
