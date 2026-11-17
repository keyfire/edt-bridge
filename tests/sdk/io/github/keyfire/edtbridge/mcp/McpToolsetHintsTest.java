/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.mcp;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.keyfire.edtbridge.core.ExactTextReplacement;
import io.github.keyfire.edtbridge.core.ToolSets;

class McpToolsetHintsTest {

    @Test
    void keepsBslAndDcsToolResultsIntactWithANarrowedToolset() {
        var selection = ToolSets.parse("report");
        var module = McpServer.textResult("""
                {"text":"// edt_clean_project","sourceHash":"%s"}
                """.formatted(ExactTextReplacement.hash("// edt_clean_project")));
        var dcs = McpServer.textResult("""
                {"xml":"<text>edt_clean_project</text>","sourceHash":"%s"}
                """.formatted(ExactTextReplacement.hash("<text>edt_clean_project</text>")));
        var moduleBefore = module.deepCopy();
        var dcsBefore = dcs.deepCopy();

        McpServer.annotateTexts(module, selection, "nextStep");
        McpServer.annotateTexts(dcs, selection, "nextStep");

        assertAll(() -> assertEquals(moduleBefore, module), () -> assertEquals(dcsBefore, dcs));
    }

    @Test
    void marksUnavailableToolsInAnActionableHint() {
        var answer = McpServer.textResult("""
                {"nextStep":"Rebuild the project with edt_clean_project."}
                """);

        McpServer.annotateTexts(answer, ToolSets.parse("report"), "nextStep");

        JsonObject content = answer.getAsJsonArray("content").get(0).getAsJsonObject();
        JsonObject payload = JsonParser.parseString(content.get("text").getAsString()).getAsJsonObject();
        assertTrue(payload.get("nextStep").getAsString().contains(
                "edt_clean_project (outside the active tool set 'report'"));
    }
}
