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
 * Which infobase user an already running configurator agent answers for.
 *
 * <p>An agent authenticates with the credentials it was STARTED with: they are bound to the process
 * once and the SSH session uses those, so a user passed to a later call reaches nothing. Left
 * unchecked that is a silent substitution of identity - the caller asks to act as one user and the
 * work is done as another, or, when the running agent has no user at all, the call dies with a bare
 * "Auth fail" that names neither user nor cause.
 *
 * <p>Passwords are deliberately not compared here: comparing secrets to decide a message is a bad
 * trade, and the remedy for a wrong password is the same as for a wrong user - stop the agent and
 * start it again.
 */
public final class AgentUser {

    private AgentUser() {
    }

    /**
     * Whether an agent running as {@code running} answers a call that asked for {@code requested}.
     * Asking for nobody in particular takes whatever runs - that is how nearly every call is made,
     * and tightening it would refuse work the caller never had an opinion about.
     */
    public static boolean serves(String running, String requested) {
        if (requested == null || requested.isBlank()) {
            return true;
        }
        return requested.trim().equalsIgnoreCase(running == null ? "" : running.trim());
    }

    /** Why the running agent cannot answer for the requested user, and what to do about it. */
    public static String mismatch(String running, String requested) {
        String as = (running == null || running.isBlank())
                ? "without a user"
                : "as \"" + running.trim() + "\"";
        return "an agent for this infobase is already running " + as + ", and this call asked for \""
                + requested.trim() + "\". An agent authenticates with the credentials it was started "
                + "with - the ones passed here would be ignored. Stop it (edt_designer_agent "
                + "action=stop) and call again to start one for that user.";
    }
}
