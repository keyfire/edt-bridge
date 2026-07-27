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
 * When a standing configurator agent has been idle long enough to give up.
 *
 * <p>An agent of a SERVER infobase is kept between calls on purpose - starting one opens a large
 * configuration and is slow - but it is not free: it holds a client license and a Designer session for
 * as long as it lives. On a stand with a small license pool that is a seat taken from a person. An
 * agent of a FILE infobase is released after every operation already, for a harder reason (it holds
 * the base file itself), so this policy is about server agents.
 *
 * <p>Pure arithmetic, kept out of the gateway so the boundary cases are under test rather than
 * observed in production.
 */
public final class AgentIdle {

    /** Idle minutes after which a server agent is stopped when nothing configures the timeout. */
    public static final long DEFAULT_MINUTES = 30;

    private AgentIdle() {
    }

    /**
     * Read the configured idle timeout in minutes.
     *
     * @param configured the raw setting; blank or absent means {@link #DEFAULT_MINUTES}
     * @return minutes to wait, or 0 when the reaper is switched off ({@code 0}, a negative number,
     *         {@code off}, {@code no}, {@code false} - and anything unparsable, because a typo must not
     *         silently shorten an agent's life)
     */
    public static long minutes(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_MINUTES;
        }
        String value = configured.trim();
        if (value.equalsIgnoreCase("off") || value.equalsIgnoreCase("no")
                || value.equalsIgnoreCase("false")) {
            return 0;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed <= 0 ? 0 : parsed;
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /** True when an agent last used at {@code lastUsedMillis} has outstayed the timeout. */
    public static boolean expired(long lastUsedMillis, long nowMillis, long timeoutMinutes) {
        if (timeoutMinutes <= 0 || lastUsedMillis <= 0) {
            return false;
        }
        return nowMillis - lastUsedMillis >= timeoutMinutes * 60_000L;
    }

    /** How long an agent has been idle, in whole seconds (0 when it has never been used). */
    public static long idleSeconds(long lastUsedMillis, long nowMillis) {
        if (lastUsedMillis <= 0 || nowMillis <= lastUsedMillis) {
            return 0;
        }
        return (nowMillis - lastUsedMillis) / 1000L;
    }
}
