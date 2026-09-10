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
 * Whose remains a leftover agent directory is, and how the listing says so.
 *
 * <p>The distinction is not cosmetic. "Left over from an earlier bridge process" tells the reader
 * that something outside this run went wrong and that sweeping is housekeeping after somebody else;
 * a directory THIS process created and failed to remove says the opposite - the bridge itself did
 * not finish a stop, and the agent it belonged to may still have a Designer session in the cluster
 * holding the infobase's configuration lock. Reported as an earlier run's, that reading is lost, and
 * the one wrong word sends the reader looking for a crash that never happened.
 *
 * <p>Ownership is knowledge, not a guess: the gateway remembers every base directory it created, so
 * a leftover is ours exactly when it is in that set.
 */
public final class AgentLeftovers {

    /** A base directory this bridge process created and could not remove. */
    public static final String FROM_THIS_PROCESS = "this bridge process";

    /** A base directory that was already on disk when this bridge process started. */
    public static final String FROM_EARLIER_PROCESS = "an earlier bridge process";

    private AgentLeftovers() {
    }

    /**
     * The tail of the {@code list} message, naming the two kinds apart.
     *
     * @return the phrase, or an empty string when nothing is left over
     */
    public static String summary(int fromThisProcess, int fromEarlierProcess) {
        int own = Math.max(0, fromThisProcess);
        int earlier = Math.max(0, fromEarlierProcess);
        int total = own + earlier;
        if (total == 0) {
            return "";
        }
        String what;
        if (earlier == 0) {
            what = own + " left over from " + FROM_THIS_PROCESS;
        } else if (own == 0) {
            what = earlier + " left over from " + FROM_EARLIER_PROCESS;
        } else {
            what = own + " left over from " + FROM_THIS_PROCESS + " and " + earlier
                    + " from an earlier one";
        }
        return what + " (action=sweep clears " + (total == 1 ? "it" : "them") + ")";
    }
}
