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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Condensing the platform's error prose to what a reader acts on.
 *
 * <p>The case that pays for it: one licensing refusal arrives as tens of kilobytes - the
 * platform appends the FULL hardware inventory (CPUs, memory, HASP keys, disks) to every
 * licensing line, repeats the block per license file per lookup stage, and the SSH client
 * then wraps the same text into both the exception and its cause. The reader scrolls pages
 * to learn one line: "no license".
 */
public final class PlatformMessages {

    /**
     * The SSH refusal of the agent. The platform spells it two ways in one chain - its own
     * "Authentication failure ..." (with "Desginer" misspelt at the source) and JSch's bare
     * "Auth fail" - so both are matched rather than the prettier one alone.
     */
    static boolean isAuthenticationFailure(String message) {
        return message.contains("AuthenticationException")
                || message.contains("Authentication failure")
                || message.contains("Auth fail");
    }

    /** Hardware-inventory lines of the licensing dump; pure bulk, never the diagnosis. */
    private static final Pattern INVENTORY = Pattern.compile(
            "^(?:Phys mem_\\d+|CPU_\\d+|HASP_\\d+|Sys name_\\d+|DISK_\\d+):.*");

    /** Past this size the platform is quoting itself; a condensed message stays far below. */
    private static final int HARD_CAP = 4000;

    private PlatformMessages() {
    }

    /**
     * The message with the inventory dropped and repeated lines kept once. A message without
     * such bulk comes back unchanged - ordinary errors must not be touched.
     */
    public static String condense(String message) {
        if (message == null || message.length() < 200) {
            return message;  // short messages carry no dump worth the bookkeeping
        }
        String[] lines = message.split("\r?\n");
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int dropped = 0;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;  // the dump pads with blank lines; condensed output does not need them
            }
            if (INVENTORY.matcher(line).matches()) {
                dropped++;
                continue;
            }
            if (!seen.add(line)) {
                dropped++;  // the same licensing line per file and per lookup stage
                continue;
            }
            out.add(line);
        }
        if (dropped == 0) {
            return message;
        }
        out.add("(... " + dropped + " repeated/inventory lines omitted)");
        String condensed = String.join("\n", out);
        if (condensed.length() > HARD_CAP) {
            condensed = condensed.substring(0, HARD_CAP) + " (... truncated)";
        }
        return condensed;
    }

    /**
     * The configurator's "infobase is locked for configuring" refusal. The platform localises
     * its messages, so every recogniser here matches BOTH languages it speaks - the Russian
     * string and the English one, read out of the platform's own resource bundles
     * (backend/config _ru.res and _root.res).
     */
    public static boolean isConfigurationLockRefusal(String message) {
        return message != null
                && (message.contains("Ошибка блокировки информационной базы для конфигурирования")
                        || message.contains("Error locking infobase for configuration"));
    }

    /** The agent's "not connected to the infobase" reply, in either language. */
    public static boolean isNotConnectedReply(String message) {
        return message != null
                && (message.contains("Соединение с информационной базой не установлено")
                        || message.contains("Designer (agent mode) is not connected to the infobase"));
    }

    /**
     * The agent's reply to a second connect-ib on a process that already holds the infobase:
     * in Russian it surfaces as the agent's own configuration lock, in English the platform
     * also has a plain "already connected" wording.
     */
    public static boolean isAlreadyConnectedReply(String message) {
        return isConfigurationLockRefusal(message)
                || (message != null
                        && message.contains("Designer (agent mode) is already connected to the infobase"));
    }

    /**
     * The platform naming the culprit outright: the second sentence of its configuration-lock
     * refusal, which says the infobase is open in a Configurator already. Both wordings come from
     * the platform's own bundles ({@code backend_ru.res}, {@code backend_root.res}):
     * "Возможно, информационная база уже открыта Конфигуратором." and "Infobase may already be in
     * use by Designer."
     */
    public static boolean isHeldByConfigurator(String message) {
        return message != null
                && (message.contains("информационная база уже открыта Конфигуратором")
                        || message.contains("Infobase may already be in use by Designer"));
    }

    /**
     * What an agent's own {@code /Out} log says about a refused SSH login, or {@code null} when it
     * says nothing recognisable.
     *
     * <p>The SSH login authenticates an INFOBASE user, so an agent that could not open the infobase
     * has nobody to authenticate against and answers "Auth fail" - a message about credentials for a
     * failure that has nothing to do with them. That is how a foreign configurator holding the
     * configuration lock reached the caller as {@code AuthenticationException: Auth fail}, with the
     * real reason sitting in a log nothing read. The log is the only place the platform explains
     * itself here, so the diagnosis is read from it.
     */
    public static String authRefusalCause(String agentLog) {
        if (agentLog == null || agentLog.isBlank()) {
            return null;
        }
        if (isHeldByConfigurator(agentLog) || isConfigurationLockRefusal(agentLog)) {
            return "the agent could not lock the infobase for configuring - another Configurator "
                    + "holds it, so there was no infobase session to authenticate against and the "
                    + "login was refused as if the credentials were wrong";
        }
        return null;
    }

    /**
     * A hint for a platform refusal whose cause is regularly NOT what the text suggests, or
     * {@code null} when there is nothing to add. The one known so far: the configuration-lock
     * refusal reads as if somebody were configuring the infobase right now, while the usual
     * culprit is the Designer session of a configurator agent that died - the cluster keeps
     * the session, the session keeps the lock, and nothing in the platform's text points there.
     */
    public static String hint(String message) {
        if (message == null || message.contains("edt_infobase_sessions")) {
            return null;
        }
        if (isConfigurationLockRefusal(message)) {
            return "(hint: a Designer session may still hold the configuration lock - a "
                    + "configurator agent that died leaves its session in the cluster; list them "
                    + "with edt_infobase_sessions appId=Designer and terminate the orphan)";
        }
        // After the lock, not before it: an SSH refusal caused by a foreign configurator carries
        // BOTH the lock text and "Auth fail", and there the lock is the reason - pointing at
        // credentials would contradict the diagnosis the agent's own log already gave.
        if (isAuthenticationFailure(message)) {
            return "(hint: the agent authenticates with the credentials it was STARTED with, so "
                    + "infobaseUser/infobasePassword passed to a later call are not applied to a "
                    + "running agent - stop it with edt_designer_agent action=stop and call again "
                    + "with the right ones)";
        }
        return null;
    }
}
