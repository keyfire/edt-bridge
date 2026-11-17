/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

class ToolArgumentsTest {
    @ParameterizedTest
    @CsvSource({
        "edt_search_modules, query, pattern", "edt_search_modules, limit, maxResults",
        "edt_search_queries, query, pattern", "edt_search_queries, limit, maxResults",
        "edt_check_info, checkId, check", "edt_form_structure, fqn, formFqn",
        "edt_property_reference, formFqn, fqn", "edt_module_text, objectFqn, fqn",
        "edt_module_text, moduleFqn, fqn", "edt_validate_query, query, queryText"
    })
    void resolvesDocumentedAliasesAndRefusesTwoNames(String tool, String alias, String canonical) {
        var descriptor = ToolJson.descriptor(tool, "Read.", "Read.", new com.google.gson.JsonObject(), canonical);
        var schema = descriptor.getAsJsonObject("inputSchema");
        var props = schema.getAsJsonObject("properties");
        props.add(canonical, ToolJson.strProp("An argument."));
        if (tool.startsWith("edt_search_")) {
            props.add("pattern", ToolJson.strProp("Pattern."));
            props.add("maxResults", ToolJson.intProp("Limit."));
        }
        ToolArguments.describe(descriptor);
        assertEquals(props.getAsJsonObject(canonical).get("type"), props.getAsJsonObject(alias).get("type"));
        assertFalse(schema.getAsJsonArray("required").contains(new com.google.gson.JsonPrimitive(canonical)));
        var args = new com.google.gson.JsonObject();
        args.addProperty(alias, "value");
        ToolArguments.normalize(tool, args);
        assertEquals("value", args.get(canonical).getAsString());
        assertFalse(args.has(alias));
        args.addProperty(alias, "different");
        assertThrows(IllegalArgumentException.class, () -> ToolArguments.normalize(tool, args));
    }

    @Test void acceptsSeverityArrays() {
        var args = JsonParser.parseString("{\"levels\":[\"ERROR\",\"WARNING\"]}").getAsJsonObject();
        ToolArguments.normalize("edt_project_errors", args);
        assertEquals("ERROR,WARNING", args.get("severity").getAsString());
    }
}
