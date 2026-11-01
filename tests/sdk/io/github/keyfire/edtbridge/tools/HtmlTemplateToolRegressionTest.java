/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.tools;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class HtmlTemplateToolRegressionTest {
    @Test void missingOrNonStringTextCannotBeCoercedIntoAnExactSourceReplacement() {
        var args = new JsonObject();
        args.addProperty("projectName", "Demo"); args.addProperty("templateFqn", "CommonTemplate.Notice");
        args.addProperty("language", "ru");
        assertTrue(new ReplaceHtmlTemplateTool().call(args).get("isError").getAsBoolean());
        assertNull(ReadHtmlTemplateTool.value(args, "text"));
        args.addProperty("text", "");
        assertEquals("", ReadHtmlTemplateTool.value(args, "text"));
        args.addProperty("text", 42);
        assertTrue(new ReplaceHtmlTemplateTool().call(args).get("isError").getAsBoolean());
        args.addProperty("text", "\ufeff \r\n<p>Keep every character</p>\n");
        assertEquals("\ufeff \r\n<p>Keep every character</p>\n", ReadHtmlTemplateTool.value(args, "text"));
        args.addProperty("apply", "true");
        assertThrows(IllegalArgumentException.class, () -> ReadHtmlTemplateTool.flag(args, "apply"));
    }
}
