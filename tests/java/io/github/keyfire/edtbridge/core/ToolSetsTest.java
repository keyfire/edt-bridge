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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The tool sets EDT_BRIDGE_TOOLSET selects, and the table they are read from. */
class ToolSetsTest {

    /** The same shape the documentation guard reads a tool name by. */
    private static final Pattern TOOL_NAME =
            Pattern.compile("String name\\(\\)\\s*\\{\\s*return\\s+\"(edt_[a-z_]+)\"");

    @Test
    @DisplayName("an empty value keeps the whole list")
    void anEmptyValueLimitsNothing() {
        for (String raw : new String[] {null, "", "   ", " , ,"}) {
            ToolSets.Selection selection = ToolSets.parse(raw);
            assertFalse(selection.limits(), String.valueOf(raw));
            assertNull(selection.problem());
            assertTrue(selection.allows("edt_debug_attach"));
            assertTrue(selection.allows("a_name_the_table_has_never_seen"));
        }
    }

    @Test
    @DisplayName("one set serves the base and its own tools, and nothing else")
    void oneSetServesItsTools() {
        ToolSets.Selection selection = ToolSets.parse("processor");
        assertTrue(selection.limits());
        assertNull(selection.problem());
        assertEquals(List.of("processor"), selection.names());
        assertTrue(selection.allows("edt_projects"));
        assertTrue(selection.allows("edt_add_form"));
        assertTrue(selection.allows("edt_dump_external_object"));
        assertFalse(selection.allows("edt_read_dcs"));
        assertFalse(selection.allows("edt_adopt_object"));
        assertFalse(selection.allows("edt_debug_attach"));
        assertFalse(selection.allows("a_name_the_table_has_never_seen"));
    }

    @Test
    @DisplayName("several sets are joined; spaces, case, repeats and empty items do not matter")
    void severalSetsAreJoined() {
        ToolSets.Selection selection = ToolSets.parse(" Report, ,extension,report ");
        assertNull(selection.problem());
        assertEquals(List.of("report", "extension"), selection.names());
        assertTrue(selection.allows("edt_read_dcs"));
        assertTrue(selection.allows("edt_adopt_object"));
        assertTrue(selection.allows("edt_add_form"));
        assertFalse(selection.allows("edt_rename"));
    }

    @Test
    @DisplayName("a name that is not a set is a problem, and nothing is served")
    void anUnknownNameServesNothing() {
        ToolSets.Selection selection = ToolSets.parse("processor,procesor,whatever");
        assertTrue(selection.limits());
        assertEquals(List.of("procesor", "whatever"), selection.unknown());
        String problem = selection.problem();
        assertNotNull(problem);
        assertTrue(problem.contains("EDT_BRIDGE_TOOLSET"), problem);
        assertTrue(problem.contains("'procesor' (did you mean 'processor'?)"), problem);
        assertTrue(problem.contains("'whatever'"), problem);
        assertFalse(problem.contains("'whatever' (did you mean"), problem);
        assertTrue(problem.contains("Known tool sets: processor, report, extension"), problem);
        // Not the sets that were recognized, and not the whole list either.
        assertFalse(selection.allows("edt_projects"));
        assertFalse(selection.allows("edt_add_form"));
        assertFalse(selection.allows("edt_debug_attach"));
    }

    @Test
    @DisplayName("the base belongs to every set")
    void theBaseBelongsToEverySet() {
        for (String set : ToolSets.names()) {
            assertTrue(ToolSets.tools(set).containsAll(ToolSets.base()), set);
            for (String tool : ToolSets.base()) {
                assertTrue(ToolSets.parse(set).allows(tool), set + " " + tool);
            }
        }
        assertEquals(ToolSets.names(), ToolSets.containing("edt_project_errors"));
    }

    @Test
    @DisplayName("each set is what its kind of job builds with")
    void theSetsDifferWhereTheJobsDo() {
        assertEquals(List.of("processor", "report", "extension"), ToolSets.names());
        assertEquals(List.of("processor", "report"), ToolSets.containing("edt_add_form"));
        assertEquals(List.of("processor", "report"), ToolSets.containing("edt_dump_external_object"));
        assertEquals(List.of("report"), ToolSets.containing("edt_replace_dcs"));
        assertEquals(List.of("extension"), ToolSets.containing("edt_adopt_object"));
        assertEquals(List.of("extension"), ToolSets.containing("edt_build_extension"));
        assertEquals(ToolSets.names(), ToolSets.containing("edt_replace_method"));
        assertEquals(List.of(), ToolSets.containing("edt_evaluate"));
        // A report is a data processor plus its data composition schema.
        assertTrue(ToolSets.tools("report").containsAll(ToolSets.tools("processor")));
        assertEquals(List.of(), ToolSets.tools("no_such_set"));
        assertEquals(List.of(), ToolSets.own("no_such_set"));
    }

    @Test
    @DisplayName("a refused call names the active set, the tool and the sets that contain it")
    void theRefusalSaysWhereTheToolIs() {
        String refusal = ToolSets.parse("processor").refusal("edt_adopt_object");
        assertTrue(refusal.startsWith("edt_adopt_object is outside the active tool set 'processor'"), refusal);
        assertTrue(refusal.contains("EDT_BRIDGE_TOOLSET"), refusal);
        assertTrue(refusal.contains("nothing was done"), refusal);
        assertTrue(refusal.contains("Tool sets that contain it: extension."), refusal);

        String several = ToolSets.parse("processor,extension").refusal("edt_read_dcs");
        assertTrue(several.contains("the active tool sets 'processor', 'extension'"), several);
        assertTrue(several.contains("Tool sets that contain it: report."), several);

        String nowhere = ToolSets.parse("report").refusal("edt_delete_project");
        assertTrue(nowhere.contains("No tool set contains it."), nowhere);

        String note = ToolSets.parse("extension").note("edt_add_form");
        assertTrue(note.contains("outside the active tool set 'extension'"), note);
        assertTrue(note.contains("a call of it is refused"), note);
        assertTrue(note.contains("Tool sets that contain it: processor, report."), note);
    }

    @Test
    @DisplayName("every name in the table is placed once")
    void noNameIsPlacedTwice() {
        List<String> placed = new ArrayList<>(ToolSets.base());
        placed.addAll(ToolSets.outside());
        for (String tool : inSets()) {
            placed.add(tool);
        }
        Set<String> seen = new TreeSet<>();
        Set<String> twice = new TreeSet<>();
        for (String tool : placed) {
            if (!seen.add(tool)) {
                twice.add(tool);
            }
        }
        assertEquals(Set.of(), twice, "named in more than one of: base, sets, outside");
        for (String set : ToolSets.names()) {
            List<String> own = ToolSets.own(set);
            assertEquals(own.size(), new TreeSet<>(own).size(), "a repeat inside " + set);
        }
    }

    @Test
    @DisplayName("the table names exactly the tools the server registers")
    void theTableMatchesTheRegisteredTools() throws IOException {
        Set<String> registered = registeredTools();
        // Guards the reader itself: a pattern that stopped matching would compare with nothing.
        assertTrue(registered.size() > 80, "tool classes read: " + registered.size());
        assertTrue(registered.contains("edt_projects"));

        Set<String> placed = new TreeSet<>(ToolSets.base());
        placed.addAll(ToolSets.outside());
        placed.addAll(inSets());

        Set<String> misspelled = new TreeSet<>(placed);
        misspelled.removeAll(registered);
        assertEquals(Set.of(), misspelled, "named in ToolSets, but no tool class has this name");

        Set<String> unplaced = new TreeSet<>(registered);
        unplaced.removeAll(placed);
        assertEquals(Set.of(), unplaced,
                "registered, but not placed in ToolSets: add each to the base, to a set or to OUTSIDE");
    }

    /** The tools at least one set names beyond the base. */
    private static Set<String> inSets() {
        Set<String> tools = new TreeSet<>();
        for (String set : ToolSets.names()) {
            tools.addAll(ToolSets.own(set));
        }
        return tools;
    }

    /** The names the tool classes declare, read from their sources - no EDT is needed for that. */
    private static Set<String> registeredTools() throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> sources = Files.list(toolSources())) {
            for (Path source : (Iterable<Path>) sources::iterator) {
                if (!source.getFileName().toString().endsWith(".java")) {
                    continue;
                }
                Matcher matcher = TOOL_NAME.matcher(Files.readString(source, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
            }
        }
        return names;
    }

    /** The folder of the tool classes, found from wherever inside the repository the test runs. */
    private static Path toolSources() {
        Path tools = Path.of("io.github.keyfire.edtbridge", "src", "io", "github", "keyfire",
                "edtbridge", "tools");
        for (Path folder = Path.of("").toAbsolutePath(); folder != null; folder = folder.getParent()) {
            if (Files.isDirectory(folder.resolve(tools))) {
                return folder.resolve(tools);
            }
        }
        throw new IllegalStateException("the tool sources were not found above "
                + Path.of("").toAbsolutePath());
    }
}
