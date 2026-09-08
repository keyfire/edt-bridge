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

    /** Everything a request may say: one to four groups of digits, and nothing else. */
    private static final Pattern REQUEST = Pattern.compile("\\d+(\\.\\d+){0,3}");

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
     * Whether an installed version may serve the request AT ALL - the filter over what is on disk.
     * A requested build admits only itself; a requested line admits everything, because there the
     * line is a preference that the ordering expresses and descending to another line is a
     * deliberate fallback for when that line has no install.
     *
     * <p>This is NOT the rule for reusing an ALREADY RUNNING tool. A line admits every version
     * here, so reuse decided by this method would hand back an agent of 8.3.24 to a request for
     * 8.5.1 even when 8.5.1 is installed and a fresh start would have taken it - and the server
     * would refuse that with the very error the pin exists to prevent. Reuse has its own rule in
     * {@link #reusable}.
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

    /**
     * Why a request cannot be read as a build or a line, or {@code null} when it can. A shape
     * outside both - five groups, a suffix, a typo - must not be waved through: everything that is
     * not four digits reads as a line, so {@code 8.5.1.1302.1} would quietly resolve to the newest
     * build installed. That is the substitution this class exists to stop, with a slip of the
     * keyboard for a trigger.
     */
    public static String problem(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String asked = requested.trim();
        if (REQUEST.matcher(asked).matches()) {
            return null;
        }
        return "\"" + asked + "\" is neither a build (8.5.1.1302) nor a line (8.5.1). Read as a "
                + "line it would resolve to the newest build installed, which is the silent "
                + "substitution a pinned build exists to prevent.";
    }

    /**
     * Whether a tool ALREADY RUNNING at build {@code running} serves a request for {@code requested}.
     *
     * <p>A build is honoured to the digit, as everywhere. A LINE is satisfied by any build OF THAT
     * LINE, and this is the half that had to be learned: measuring the running agent against the
     * newest build of the line instead turned a perfectly good agent away. On a stand pinned to
     * 8.5.1.1302 with 8.5.1.1464 also installed, a request for the line 8.5.1 refused the running
     * 8.5.1.1302 and advised a restart - which would have started 8.5.1.1464, the one build that
     * stand's server refuses. A line means "this line", not "the newest build I could find of it".
     *
     * <p>{@link #accepts} would be too weak here and {@link #compare}'s first candidate too strict;
     * this is the rule in the middle, and the only one under which every refusal it produces leaves
     * a restart as the sensible next step.
     */
    public static boolean reusable(String running, String requested) {
        if (requested == null || requested.isBlank()) {
            return true;   // a caller who asked for nothing takes whatever runs
        }
        String asked = requested.trim();
        if (isBuild(asked)) {
            return running != null && running.trim().equals(asked);
        }
        return matchesLine(running == null ? null : running.trim(), line(asked));
    }

    /**
     * Why an agent already running cannot serve the request, and what a restart would give instead.
     *
     * <p>The advice is ordered by what actually helps, which was worth a fix of its own: the stop is
     * named first only because {@link #reusable} has already established that a restart WOULD serve
     * the request. While reuse was measured against the newest build of the line, the same message
     * led with "stop it" for a running agent that was the only right one - and the restart it advised
     * would have produced the build the server refuses.
     *
     * @param wanted the build a fresh start would take - a line is named as a line, since calling it
     *               "pinned" would misdescribe it
     */
    public static String alreadyRunning(String running, String requested, String wanted) {
        String asked = isBuild(requested)
                ? requested.trim() + " was pinned"
                : "line " + line(requested) + " was asked for, which the running build is not in";
        return "an agent of build " + running + " is already running for this infobase, and " + asked
                + ". Stop it (edt_designer_agent action=stop) to start " + wanted
                + " instead, or ask without platformVersion to use the running one.";
    }
}
