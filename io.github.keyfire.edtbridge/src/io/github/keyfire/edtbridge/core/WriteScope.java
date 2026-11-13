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

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The write scope of an agent: which projects it may change, which it may only read, and where the
 * files the tools write may go.
 *
 * <p>A tool set limits which tools run, not where they write: a write tool could change any project
 * of the workspace and put a file anywhere. Three settings draw the boundary, each a system property
 * or an environment variable: the projects open for writing, the projects open for reading only, and
 * the directory for output files. The server checks every call against them before the tool runs,
 * whatever the client, so a client that reaches the port directly gets the same refusal.
 *
 * <p>What a call touches comes from the tool's declaration: a tool whose description is marked WRITE
 * changes the project it names, and the arguments listed in {@link #OUTPUT_PATHS} are files the tool
 * writes. A test checks every path-like argument of every tool against these lists, so a new tool
 * cannot add an unchecked output.
 *
 * <p>Pure string and path work, kept out of the server so it can be tested without EDT.
 */
public final class WriteScope {
    public static final String WRITE_ENV = "EDT_BRIDGE_WRITE_PROJECTS";
    public static final String WRITE_PROPERTY = "edt.bridge.write.projects";
    public static final String READ_ENV = "EDT_BRIDGE_READ_PROJECTS";
    public static final String READ_PROPERTY = "edt.bridge.read.projects";
    public static final String OUTPUT_ENV = "EDT_BRIDGE_OUTPUT_DIR";
    public static final String OUTPUT_PROPERTY = "edt.bridge.output.dir";

    /** Arguments that name a file or directory the tool writes; they must lie in the output directory. */
    public static final Map<String, Set<String>> OUTPUT_PATHS = Map.of(
            "edt_dump_external_object", Set.of("targetPath", "logPath"),
            "edt_build_extension", Set.of("outputPath"),
            "edt_infobase_dump", Set.of("outputPath"),
            "edt_create_infobase", Set.of("path"),
            "edt_form_render", Set.of("outPath"),
            "edt_spreadsheet_render", Set.of("outPath"));

    /**
     * Path-like arguments that are not written: files and directories a tool reads, paths inside a
     * project, and filters. Listed so that the test can tell a checked decision from an oversight.
     */
    public static final Map<String, Set<String>> OTHER_PATHS = Map.ofEntries(
            Map.entry("edt_import_external_object", Set.of("file")),
            Map.entry("edt_import_project", Set.of("path")),
            Map.entry("edt_infobase_dump", Set.of("databasePath")),
            Map.entry("edt_extension_properties", Set.of("databasePath")),
            Map.entry("edt_platform_help", Set.of("path")),
            Map.entry("edt_form_identifiers", Set.of("itemPaths")),
            Map.entry("edt_add_form_item", Set.of("dataPath")),
            Map.entry("edt_search_modules", Set.of("pathFilter")),
            Map.entry("edt_search_queries", Set.of("pathFilter")),
            Map.entry("edt_test_form", Set.of("path")));

    /** {@code modulePath} is a path inside the named project, checked by {@link #moduleProblem}. */
    public static final String MODULE_PATH = "modulePath";

    /** Tools that create a project, and the argument that names it. */
    private static final Map<String, String> CREATES = Map.of(
            "edt_create_extension", "name",
            "edt_create_external_object", "name",
            "edt_import_project", "name");

    /** WRITE tools that change no project source: reading the project they name is enough. */
    private static final Set<String> PROJECT_READS = Set.of(
            "edt_update_infobase", "edt_clean_project", "edt_debug_attach");

    /** Tools that write only with {@code apply=true}; their description is not marked WRITE. */
    private static final Set<String> WRITES_ON_APPLY = Set.of("edt_apply_fix", "edt_form_identifiers");

    private final Set<String> write;
    private final Set<String> read;
    private final boolean projectsLimited;
    private final Path outputDir;
    private final String problem;

    private WriteScope(Set<String> write, Set<String> read, boolean projectsLimited, Path outputDir, String problem) {
        this.write = write;
        this.read = read;
        this.projectsLimited = projectsLimited;
        this.outputDir = outputDir;
        this.problem = problem;
    }

    /**
     * The scope the three settings describe. Project lists are separated by commas or semicolons; an
     * empty setting is not set. The output directory must be absolute.
     */
    public static WriteScope parse(String writeProjects, String readProjects, String outputDirectory) {
        Set<String> write = names(writeProjects);
        Set<String> read = names(readProjects);
        read.removeAll(write);
        boolean limited = !blank(writeProjects) || !blank(readProjects);
        Path output = null;
        String problem = null;
        if (!blank(outputDirectory)) {
            try {
                Path candidate = Path.of(outputDirectory.strip());
                if (candidate.isAbsolute()) {
                    output = candidate.normalize();
                } else {
                    problem = OUTPUT_ENV + " must be an absolute directory, got " + outputDirectory.strip();
                }
            } catch (InvalidPathException ex) {
                problem = OUTPUT_ENV + " is not a valid path: " + outputDirectory.strip();
            }
        }
        return new WriteScope(write, read, limited, output, problem);
    }

    /** Whether any of the settings is given; without them every call passes. */
    public boolean active() {
        return projectsLimited || outputDir != null || problem != null;
    }

    /** A setting that cannot be used, or null. While it stands, every call is refused with it. */
    public String problem() {
        return problem;
    }

    public Set<String> writeProjects() {
        return write;
    }

    public Set<String> readProjects() {
        return read;
    }

    public boolean projectsLimited() {
        return projectsLimited;
    }

    /** The directory the tools may write files into, or null when none is set. */
    public Path outputDir() {
        return outputDir;
    }

    /** Whether a tool's argument names a file or directory the tool writes. */
    public static boolean isOutputPath(String tool, String argument) {
        return OUTPUT_PATHS.getOrDefault(tool, Set.of()).contains(argument);
    }

    /** Whether a path-like argument has been decided: an output, a read path, or a module path. */
    public static boolean isClassifiedPath(String tool, String argument) {
        return isOutputPath(tool, argument) || MODULE_PATH.equals(argument)
                || OTHER_PATHS.getOrDefault(tool, Set.of()).contains(argument);
    }

    /**
     * Why a call is outside the scope, naming what is allowed; null when it may run.
     *
     * @param writeTool whether the tool's description marks it as WRITE
     * @param declared  the argument names the tool declares
     * @param arguments the call's arguments that are strings or booleans, as text
     */
    public String refusal(String tool, boolean writeTool, Collection<String> declared, Map<String, String> arguments) {
        if (problem != null) {
            return problem;
        }
        if (!active()) {
            return null;
        }
        if (projectsLimited) {
            String projects = projectRefusal(tool, writeTool, declared, arguments);
            if (projects != null) {
                return projects;
            }
            String module = arguments.get(MODULE_PATH);
            if (module != null && moduleProblem(module)) {
                return "modulePath " + module + " leaves the project; give a path inside it, such as "
                        + "src/CommonModules/Name/Module.bsl";
            }
        }
        for (String argument : OUTPUT_PATHS.getOrDefault(tool, Set.of())) {
            String value = arguments.get(argument);
            if (value != null && !value.isBlank()) {
                String output = outputRefusal(argument, value);
                if (output != null) {
                    return output;
                }
            }
        }
        return null;
    }

    private String projectRefusal(String tool, boolean writeTool, Collection<String> declared,
            Map<String, String> arguments) {
        String created = CREATES.get(tool);
        if (created != null) {
            String name = arguments.get(created);
            boolean importing = "edt_import_project".equals(tool);
            if (name == null || name.isBlank()) {
                return tool + " under a write scope needs " + created + ": the project it "
                        + (importing ? "registers" : "creates") + " must be one of " + allowed(importing);
            }
            if (importing ? !readable(name) : !write.contains(name)) {
                return "project " + name + " is outside the write scope; " + tool + " may "
                        + (importing ? "register" : "create") + " " + allowed(importing);
            }
        }
        String base = arguments.get("baseProjectName");
        if (base != null && !base.isBlank() && !readable(base)) {
            return "base project " + base + " is outside the scope; readable projects: " + list(readableSet());
        }
        if (!declared.contains("projectName")) {
            return null;
        }
        String project = arguments.get("projectName");
        boolean writes = writeTool && !PROJECT_READS.contains(tool)
                || WRITES_ON_APPLY.contains(tool) && "true".equals(arguments.get("apply"));
        if (project == null || project.isBlank()) {
            return tool + " under a write scope needs projectName; " + (writes
                    ? "projects open for writing: " + list(write) : "readable projects: " + list(readableSet()));
        }
        if (writes && !write.contains(project)) {
            return tool + " writes into project " + project + ", which is outside the write scope; projects open "
                    + "for writing: " + list(write) + " (" + WRITE_ENV + ")";
        }
        if (!writes && !readable(project)) {
            return "project " + project + " is outside the scope; readable projects: " + list(readableSet())
                    + " (" + WRITE_ENV + ", " + READ_ENV + ")";
        }
        return null;
    }

    private String outputRefusal(String argument, String value) {
        if (outputDir == null) {
            return argument + " writes a file, and the write scope sets no output directory; set " + OUTPUT_ENV
                    + " to the directory the files may go to";
        }
        Path path;
        try {
            path = Path.of(value.strip());
        } catch (InvalidPathException ex) {
            return argument + " is not a valid path: " + value;
        }
        if (!path.isAbsolute()) {
            return argument + " must be an absolute path under " + outputDir + " (" + OUTPUT_ENV + "), got " + value;
        }
        Path normal = path.normalize();
        if (normal.equals(outputDir) || !normal.startsWith(outputDir)) {
            return argument + " " + value + " is outside the output directory " + outputDir + " (" + OUTPUT_ENV
                    + "); write it under that directory, e.g. " + outputDir.resolve(nameOf(normal));
        }
        return null;
    }

    /** A module path that is absolute or climbs out of the project. */
    static boolean moduleProblem(String modulePath) {
        String unified = modulePath.replace('\\', '/');
        if (unified.startsWith("/") || unified.matches("^[A-Za-z]:.*")) {
            return true;
        }
        return Arrays.asList(unified.split("/")).contains("..");
    }

    private boolean readable(String project) {
        return write.contains(project) || read.contains(project);
    }

    private Set<String> readableSet() {
        Set<String> all = new LinkedHashSet<>(write);
        all.addAll(read);
        return all;
    }

    private String allowed(boolean importing) {
        return importing ? list(readableSet()) : list(write);
    }

    private static String list(Collection<String> names) {
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    private static String nameOf(Path path) {
        Path name = path.getFileName();
        return name == null ? "file" : name.toString();
    }

    private static Set<String> names(String setting) {
        Set<String> names = new LinkedHashSet<>();
        if (!blank(setting)) {
            for (String part : setting.split("[,;]")) {
                if (!part.isBlank()) {
                    names.add(part.strip());
                }
            }
        }
        return names;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
