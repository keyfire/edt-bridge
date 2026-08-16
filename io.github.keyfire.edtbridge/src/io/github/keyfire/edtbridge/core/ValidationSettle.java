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

/**
 * When a problem count may be reported as final.
 *
 * <p>EDT hangs its checks off the build, but the checks are NOT the build: joining the build job
 * families returns while validation is still to come. A count that merely stopped changing is
 * therefore not evidence of anything - right after a clean it sits at zero simply because nothing
 * has been reported yet, and answering "0 problems, settled" there is the worst possible lie: that
 * number is exactly what the caller asked clean for.
 *
 * <p>So two conditions, not one: the count must repeat AND the workspace must be idle - no jobs
 * running - for the same stretch. Idleness is what tells "validation has not started" apart from
 * "validation is done".
 *
 * <p>Pure arithmetic, kept out of the gateway so the boundary cases are under test rather than
 * observed in production.
 */
public final class ValidationSettle {

    /** How many consecutive quiet polls make a count final. */
    public static final int REQUIRED_QUIET_POLLS = 3;

    private int previous = Integer.MIN_VALUE;
    private int quiet;
    private boolean sawBusy;

    /**
     * Record one poll.
     *
     * @param problems   the problem count read now
     * @param jobsIdle   whether the job manager reported no running jobs at that moment
     * @return true when the count may be reported as final
     */
    public boolean poll(int problems, boolean jobsIdle) {
        if (!jobsIdle) {
            sawBusy = true;
        }
        quiet = (problems == previous && jobsIdle) ? quiet + 1 : 0;
        previous = problems;
        return settled();
    }

    /** True when the last {@link #poll} left the count final. */
    public boolean settled() {
        return quiet >= REQUIRED_QUIET_POLLS;
    }

    /** The count of the last poll ({@code Integer.MIN_VALUE} before the first one). */
    public int problems() {
        return previous;
    }

    /**
     * True when validation was seen running at least once.
     *
     * <p>Reported to the caller, because "settled without ever seeing a busy moment" is the shape of
     * a wait that finished before the checks even started - rare, but worth naming rather than
     * hiding behind a bare number.
     */
    public boolean sawWork() {
        return sawBusy;
    }
}
