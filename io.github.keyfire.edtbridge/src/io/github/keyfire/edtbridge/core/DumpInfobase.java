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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * The infobase the on-disk build of an external data processor or report runs in.
 *
 * <p>{@code 1cv8 DESIGNER /LoadExternalDataProcessorOrReportFromFiles} resolves every type the
 * object names against the configuration of the infobase it runs in. An empty throwaway infobase
 * knows only platform types, so an attribute of a reference type of the base configuration (a
 * catalog, enumeration or defined type) fails the import with an XDTO exception on the file that
 * declares it. A file infobase that carries the base configuration resolves those types; the build
 * works on a copy of its data file, so the source infobase is never opened or changed.
 */
public final class DumpInfobase {

    /** Environment variable naming the file infobase whose configuration resolves the types. */
    public static final String ENV = "EDT_BRIDGE_DUMP_INFOBASE";

    /** The single data file of a file infobase; copying it is enough to open the copy. */
    public static final String DATA_FILE = "1Cv8.1CD";

    private DumpInfobase() {
    }

    /**
     * The infobase directory a setting points to, or {@code null} when the setting is empty.
     * Accepted forms: the directory, the path of its {@code 1Cv8.1CD}, or a file connection
     * string {@code File="<directory>";}.
     */
    public static Path directory(String setting) {
        if (setting == null || setting.isBlank()) {
            return null;
        }
        String value = setting.strip();
        if (value.regionMatches(true, 0, "File=", 0, 5)) {
            value = value.substring(5);
            int end = value.indexOf(';');
            if (end >= 0) {
                value = value.substring(0, end);
            }
            value = value.strip();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
        }
        Path path = Paths.get(value);
        Path name = path.getFileName();
        if (name != null && name.toString().toLowerCase(Locale.ROOT).equals(DATA_FILE.toLowerCase(Locale.ROOT))) {
            path = path.getParent();
        }
        return path;
    }

    /** {@code null} when the directory holds an infobase data file, otherwise why it cannot be used. */
    public static String unusable(Path directory) {
        if (directory == null) {
            return ENV + " is empty";
        }
        if (!Files.isRegularFile(directory.resolve(DATA_FILE))) {
            return ENV + " points to " + directory + ", which has no " + DATA_FILE;
        }
        return null;
    }

    /**
     * Whether a build log reports the XDTO read failure the platform raises on a type it cannot
     * resolve. Both spellings come from the platform resources ({@code backend_root.res} and
     * {@code backend_ru.res}).
     */
    public static boolean isXdtoReadFailure(String log) {
        return log != null
                && (log.contains("XDTO exception while reading file")
                        || log.contains("Исключение XDTO произошло при чтении файла"));
    }

    /**
     * The explanation to append to a failed build, or {@code null} when there is nothing to add.
     * It applies only to a build in an empty infobase: with a configured infobase the types were
     * resolvable, so an XDTO failure there has another cause.
     */
    public static String hint(String log, boolean emptyInfobase) {
        if (!emptyInfobase || !isXdtoReadFailure(log)) {
            return null;
        }
        return "the build ran in an empty infobase, which knows no types of the base configuration;"
                + " an attribute of a catalog, enumeration or defined type of that configuration fails"
                + " the import with this XDTO exception. Set " + ENV + " to a file infobase that"
                + " carries the base configuration: the build then runs in a copy of it";
    }
}
