/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteScopeTest {
    private static final List<String> PROJECT = List.of("projectName", "fqn", "methodText", "apply");

    @TempDir
    Path out;

    private WriteScope scope() {
        return WriteScope.parse("Own", "Base", out.toString());
    }

    @Test
    void withoutSettingsEveryCallPasses() {
        WriteScope none = WriteScope.parse(null, " ", "");
        assertFalse(none.active());
        assertNull(none.refusal("edt_add_method", true, PROJECT, Map.of("projectName", "Anything")));
        assertNull(none.refusal("edt_dump_external_object", true, List.of("projectName", "targetPath"),
                Map.of("projectName", "Anything", "targetPath", "/anywhere/x.epf")));
    }

    @Test
    void aWriteGoesOnlyToAProjectOpenForWriting() {
        WriteScope scope = scope();
        assertNull(scope.refusal("edt_add_method", true, PROJECT, Map.of("projectName", "Own")));
        String base = scope.refusal("edt_add_method", true, PROJECT, Map.of("projectName", "Base"));
        assertNotNull(base);
        assertTrue(base.contains("Base") && base.contains("projects open for writing: Own"), base);
        String foreign = scope.refusal("edt_add_method", true, PROJECT, Map.of("projectName", "Other"));
        assertTrue(foreign.contains(WriteScope.WRITE_ENV), foreign);
    }

    @Test
    void aReadReachesTheWriteAndTheReadOnlyProjectsOnly() {
        WriteScope scope = scope();
        List<String> declared = List.of("projectName", "fqn");
        assertNull(scope.refusal("edt_metadata_details", false, declared, Map.of("projectName", "Base")));
        assertNull(scope.refusal("edt_metadata_details", false, declared, Map.of("projectName", "Own")));
        String other = scope.refusal("edt_metadata_details", false, declared, Map.of("projectName", "Other"));
        assertTrue(other.contains("readable projects: Own, Base"), other);
        // A tool that reads every project when none is named has to name one under a scope.
        String all = scope.refusal("edt_project_errors", false, declared, Map.of());
        assertTrue(all.contains("needs projectName"), all);
    }

    @Test
    void aToolThatWritesOnApplyIsAReadUntilItApplies() {
        WriteScope scope = scope();
        List<String> declared = List.of("projectName", "apply");
        assertNull(scope.refusal("edt_apply_fix", false, declared, Map.of("projectName", "Base")));
        assertNotNull(scope.refusal("edt_apply_fix", false, declared, Map.of("projectName", "Base", "apply", "true")));
        assertNull(scope.refusal("edt_apply_fix", false, declared, Map.of("projectName", "Own", "apply", "true")));
    }

    @Test
    void aWriteToolThatOnlyReadsTheProjectNeedsItReadable() {
        assertNull(scope().refusal("edt_update_infobase", true, List.of("projectName"), Map.of("projectName", "Base")));
    }

    @Test
    void aProjectIsCreatedUnderAWritableNameFromAReadableBase() {
        WriteScope scope = scope();
        List<String> declared = List.of("name", "baseProjectName");
        assertNull(scope.refusal("edt_create_extension", true, declared, Map.of("name", "Own", "baseProjectName", "Base")));
        String name = scope.refusal("edt_create_extension", true, declared, Map.of("name", "Other", "baseProjectName", "Base"));
        assertTrue(name.contains("may create Own"), name);
        String base = scope.refusal("edt_create_extension", true, declared, Map.of("name", "Own", "baseProjectName", "Other"));
        assertTrue(base.contains("base project Other"), base);
        // Importing registers an existing project: a read-only one is fine, an unnamed one is not.
        assertNull(scope.refusal("edt_import_project", true, List.of("path", "name"), Map.of("path", "/p", "name", "Base")));
        assertNotNull(scope.refusal("edt_import_project", true, List.of("path", "name"), Map.of("path", "/p")));
    }

    @Test
    void anOutputFileGoesOnlyUnderTheOutputDirectory() {
        WriteScope scope = scope();
        List<String> declared = List.of("projectName", "targetPath", "logPath");
        String inside = out.resolve("Own.epf").toString();
        assertNull(scope.refusal("edt_dump_external_object", true, declared,
                Map.of("projectName", "Own", "targetPath", inside)));
        String elsewhere = out.getParent().resolve("elsewhere.epf").toString();
        String outside = scope.refusal("edt_dump_external_object", true, declared,
                Map.of("projectName", "Own", "targetPath", elsewhere));
        assertTrue(outside.contains("outside the output directory") && outside.contains(out.toString()), outside);
        String climbing = out.resolve("..").resolve("escaped.epf").toString();
        assertNotNull(scope.refusal("edt_dump_external_object", true, declared,
                Map.of("projectName", "Own", "targetPath", climbing)), "a path with .. is judged after normalizing");
        assertNotNull(scope.refusal("edt_dump_external_object", true, declared,
                Map.of("projectName", "Own", "targetPath", "Own.epf")), "a relative path is refused");
        assertNotNull(scope.refusal("edt_dump_external_object", true, declared,
                Map.of("projectName", "Own", "targetPath", inside, "logPath", elsewhere)), "every output is checked");
        assertNotNull(scope.refusal("edt_form_render", false, List.of("projectName", "outPath"),
                Map.of("projectName", "Base", "outPath", elsewhere)), "a read tool that writes a file is checked too");
    }

    @Test
    void anOutputNeedsAnOutputDirectoryOnceAScopeIsSet() {
        WriteScope projectsOnly = WriteScope.parse("Own", null, null);
        String refusal = projectsOnly.refusal("edt_build_extension", true, List.of("projectName", "outputPath"),
                Map.of("projectName", "Own", "outputPath", "/tmp/x.cfe"));
        assertTrue(refusal.contains(WriteScope.OUTPUT_ENV), refusal);
    }

    @Test
    void anOutputDirectoryAloneLeavesTheProjectsOpen() {
        WriteScope outputOnly = WriteScope.parse(null, null, out.toString());
        assertTrue(outputOnly.active());
        assertFalse(outputOnly.projectsLimited());
        assertNull(outputOnly.refusal("edt_add_method", true, PROJECT, Map.of("projectName", "Anything")));
    }

    @Test
    void aModulePathStaysInsideTheProject() {
        WriteScope scope = scope();
        List<String> declared = List.of("projectName", "modulePath");
        assertNull(scope.refusal("edt_add_method", true, declared,
                Map.of("projectName", "Own", "modulePath", "src/CommonModules/Common/Module.bsl")));
        assertNotNull(scope.refusal("edt_add_method", true, declared,
                Map.of("projectName", "Own", "modulePath", "src/../../Base/src/Module.bsl")));
        assertNotNull(scope.refusal("edt_add_method", true, declared,
                Map.of("projectName", "Own", "modulePath", "C:\\Base\\src\\Module.bsl")));
        assertTrue(WriteScope.moduleProblem("/etc/x"));
    }

    @Test
    void aRelativeOutputDirectoryIsASettingProblemThatRefusesEveryCall() {
        WriteScope broken = WriteScope.parse("Own", null, "relative/out");
        assertNotNull(broken.problem());
        assertEquals(broken.problem(), broken.refusal("edt_projects", false, List.of(), Map.of()));
    }

    @Test
    void listsAreSplitByCommasOrSemicolonsAndAWritableProjectIsNotAlsoReadOnly() {
        WriteScope scope = WriteScope.parse(" Own ; Second ", "Base, Own", null);
        assertEquals(Set.of("Own", "Second"), scope.writeProjects());
        assertEquals(Set.of("Base"), scope.readProjects());
    }

    @Test
    void everyOutputArgumentIsAlsoAClassifiedPath() {
        WriteScope.OUTPUT_PATHS.forEach((tool, arguments) -> arguments.forEach(argument ->
                assertTrue(WriteScope.isClassifiedPath(tool, argument), tool + "." + argument)));
        assertFalse(WriteScope.isClassifiedPath("edt_dump_external_object", "unknownPath"));
    }
}
