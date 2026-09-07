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
import java.util.regex.Pattern;

/**
 * Which installed platform build answers a request for one.
 *
 * <p>The rule is read off the SHAPE of what the caller wrote. Four digits ({@code 8.5.1.1302}) name
 * a BUILD and pin it - nothing else may serve that request. Three or fewer ({@code 8.5.1}) name a
 * LINE: its newest installed build comes first, and other lines still serve when that line has
 * none, which is what creating a throwaway infobase relies on.
 *
 * <p>The pin exists because a build that differs from the server's is refused by the SERVER, not by
 * us: a configurator started as 8.5.1.1464 against a 8.5.1.1302 stand dies with "Несоответствие
 * версий клиента и сервера (8.5.1.1464 - 8.5.1.1302)", and rac against ras is no different.
 * Truncating a requested build to its line resolved every such request to the newest build
 * installed, so naming the stand's own build was the one thing a caller could not do.
 */
public final class PlatformSelection {

    /** A full build number - the only shape that pins. */
    private static final Pattern BUILD = Pattern.compile("\\d+\\.\\d+\\.\\d+\\.\\d+");

    private PlatformSelection() {
    }

    /** Whether the request names one build ({@code 8.5.1.1302}) rather than a line. */
    public static boolean isBuild(String requested) {
        return requested != null && BUILD.matcher(requested.trim()).matches();
    }

    /** The major.minor.release line of a request, or {@code null} when nothing was requested. */
    public static String line(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String[] p = requested.trim().split("\\.");
        return (p.length >= 3) ? p[0] + "." + p[1] + "." + p[2] : requested.trim();
    }

    /** Whether an installed version belongs to a line. */
    public static boolean matchesLine(String installed, String line) {
        return installed != null && line != null
                && (installed.equals(line) || installed.startsWith(line + "."));
    }

    /**
     * Whether an installed version may serve the request AT ALL. A requested build admits only
     * itself; a requested line admits everything, because there the line is a preference that the
     * ordering expresses and descending to another line is a deliberate fallback.
     *
     * <p>The same verdict answers "may this ALREADY RUNNING tool serve the request": being stricter
     * about a running build than about one a fresh start would have picked refuses work for no
     * reason, while handing back a build other than the one pinned is the failure this class exists
     * to stop.
     */
    public static boolean accepts(String installed, String requested) {
        return !isBuild(requested)
                || (installed != null && installed.trim().equals(requested.trim()));
    }

    /** Candidate order: the requested line first (newest build of it), then everything descending. */
    public static int compare(String a, String b, String requested) {
        String line = line(requested);
        boolean pa = matchesLine(a, line);
        boolean pb = matchesLine(b, line);
        if (pa != pb) {
            return pa ? -1 : 1;
        }
        return Versions.compare(b, a);   // newest build first
    }

    /**
     * Why nothing on disk can serve the request, naming what IS there. The list costs one line and
     * answers the reader's next question; a bare "not found" sends them looking for the wrong thing.
     */
    public static String unavailable(String what, String requested, List<String> installed) {
        String have = (installed == null || installed.isEmpty())
                ? "none is installed"
                : "installed: " + String.join(", ", installed);
        if (isBuild(requested)) {
            return "no " + what + " of build " + requested.trim() + " on disk (" + have
                    + "). Four digits pin that exact build - pass the line " + line(requested)
                    + " to take the newest build of it instead.";
        }
        if (requested == null || requested.isBlank()) {
            return "no " + what + " on disk (" + have + ").";
        }
        return "no " + what + " on disk for version line " + line(requested) + " (" + have + ").";
    }

    /** Why an agent already running cannot serve the request: its build against the pinned one. */
    public static String alreadyRunning(String running, String requested) {
        return "an agent of build " + running + " is already running for this infobase, and "
                + requested.trim() + " was pinned. Stop it (edt_designer_agent action=stop) or ask "
                + "without platformVersion to use the running one.";
    }
}
