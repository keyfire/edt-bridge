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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Whether a load into the infobase can send only the files that changed, and if so which.
 *
 * <p>The rules follow the platform documentation on partial loading (Developer guide 2.17.5.3; the
 * agent's {@code load-config-from-files}, Administrator guide 4.7.6.7). With {@code --partial} every
 * listed file is loaded on its own: an object's description without its modules and forms, a module
 * without its object, a form module without the form. That makes a file-by-file comparison of two
 * exports the right unit - a file that changed is exactly a file that has to be loaded. A partial load
 * that fails rolls the main configuration back to the last saved one, so a full load after it is safe.
 *
 * <p>Every doubt resolves to the full load. A full load is slow. A partial load given the wrong list
 * leaves the infobase with a configuration that exists nowhere else, and nothing reports it. So a
 * partial load is planned only when the snapshot belongs to this infobase and this platform, the
 * infobase's generation proves nobody else changed it since, no file disappeared, and the root of the
 * configuration is untouched.
 */
public final class LoadPlan {

    /** The root of a designer export: the configuration's own properties and its list of objects. */
    static final String ROOT = "Configuration.xml";

    /** The configuration's vendor support settings, an external property of the root. */
    static final String SUPPORT = "Ext/ParentConfigurations.bin";

    public enum Mode { FULL, PARTIAL, SKIP }

    public final Mode mode;
    public final String reason;
    /** The files a partial load sends, '/'-separated and sorted; empty for the other modes. */
    public final List<String> changed;
    /** How many files the current export holds. */
    public final int total;

    private LoadPlan(Mode mode, String reason, List<String> changed, int total) {
        this.mode = mode;
        this.reason = reason;
        this.changed = changed;
        this.total = total;
    }

    /** A full load, for a reason found before any comparison was possible. */
    public static LoadPlan full(String reason, int total) {
        return new LoadPlan(Mode.FULL, reason, List.of(), total);
    }

    /**
     * Compare the export about to be loaded with the snapshot of the last load.
     *
     * @param previous      the snapshot of the last load into this target, or null when there is none
     * @param current       the export about to be loaded, hashed; its target and platform filled in
     * @param generation    the infobase's configuration generation id right now, or null when unknown
     * @param fullRequested the caller asked for a full load
     */
    public static LoadPlan decide(LoadSnapshot previous, LoadSnapshot current, String generation,
            boolean fullRequested) {
        int total = current.files.size();
        if (fullRequested) {
            return full("fullLoad=true was passed", total);
        }
        if (previous == null) {
            return full("this bridge has no record of an earlier load into this infobase", total);
        }
        if (!Objects.equals(previous.target, current.target)) {
            return full("the record of the earlier load belongs to another infobase", total);
        }
        if (!Objects.equals(previous.platformVersion, current.platformVersion)) {
            return full("the platform changed since the earlier load (" + previous.platformVersion + " -> "
                    + current.platformVersion + "), and the export format follows the platform", total);
        }
        if (isBlank(previous.generation) || isBlank(generation)) {
            return full("the infobase's configuration generation is unknown, so a change made outside this "
                    + "bridge cannot be ruled out", total);
        }
        if (!previous.generation.trim().equals(generation.trim())) {
            return full("the infobase's configuration changed after this bridge last loaded it - another "
                    + "load, EDT's synchronization or the Configurator - and only a full load brings it in "
                    + "line", total);
        }
        long removed = previous.files.keySet().stream().filter(p -> !current.files.containsKey(p)).count();
        if (removed > 0) {
            return full(removed + " file(s) are gone from the export (a removed object, form or module), "
                    + "and a removal is not loaded file by file", total);
        }
        List<String> changed = current.files.entrySet().stream()
                .filter(e -> !e.getValue().equals(previous.files.get(e.getKey())))
                .map(Map.Entry::getKey)
                .toList();
        if (changed.contains(ROOT)) {
            return full(ROOT + " changed (an object added, removed or renamed, or a property of the "
                    + "configuration), and loading the root on its own can take a configuration off "
                    + "vendor support", total);
        }
        if (changed.contains(SUPPORT)) {
            return full("the vendor support settings changed (" + SUPPORT + ")", total);
        }
        if (changed.isEmpty()) {
            return new LoadPlan(Mode.SKIP, "nothing changed since the earlier load", List.of(), total);
        }
        if (changed.size() * 2L > total) {
            return full("most of the configuration changed (" + changed.size() + " of " + total
                    + " files), and a partial load that large gains nothing", total);
        }
        return new LoadPlan(Mode.PARTIAL, changed.size() + " of " + total
                + " file(s) changed since the earlier load", changed, total);
    }

    /**
     * The content of the {@code --list-file}: one path per line, relative to the load directory.
     * Joined without a trailing line break - the platform does not accept an empty line in the list.
     */
    public static String listFile(List<String> paths, char separator) {
        return paths.stream()
                .map(p -> separator == '/' ? p : p.replace('/', separator))
                .collect(Collectors.joining("\n"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
