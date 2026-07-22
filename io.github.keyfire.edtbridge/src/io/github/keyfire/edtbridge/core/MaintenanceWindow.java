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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pure logic of a maintenance window over an infobase: which sessions block an update, what
 * {@code rac infobase update} is told to raise or lower the denial flags, and which of the
 * {@code rac infobase info} fields describe the window.
 *
 * <p>The scenario that pays for it: updating the database configuration needs an exclusive lock,
 * and a lively application spawns a fresh batch of BackgroundJob sessions every minute -
 * terminating them is pointless while scheduled jobs are allowed to start. With
 * {@code scheduled-jobs-deny} raised the jobs drain BY THEMSELVES within a minute, nothing
 * respawns, and no session has to be killed. The flags live in the cluster, so this is rac
 * territory: the configurator agent's SSH client has no operation for them (checked against the
 * client bundle - its infobase tools are debug settings, data separators, dt export/import and
 * erase).
 */
public final class MaintenanceWindow {

    /** The {@code rac infobase info} fields that describe the denial window, in report order. */
    public static final List<String> FLAG_FIELDS = List.of(
            "scheduled-jobs-deny", "sessions-deny", "denied-from", "denied-to",
            "denied-message", "permission-code");

    private MaintenanceWindow() {
    }

    /**
     * The sessions that block an exclusive lock: everything whose {@code app-id} is not in
     * {@code allowedAppIds} (case-insensitive). Records are what {@link RacOutput#parse} returns.
     */
    public static List<Map<String, String>> blockers(List<Map<String, String>> sessions,
            Collection<String> allowedAppIds) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> s : sessions) {
            String appId = s.getOrDefault("app-id", "");
            boolean allowed = allowedAppIds.stream().anyMatch(a -> a.equalsIgnoreCase(appId));
            if (!allowed) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * The flag arguments of {@code rac infobase update} for one end of the window.
     *
     * @param raise          true to raise the flags (begin), false to lower them (end)
     * @param sessionsDeny   also deny NEW sessions, not only scheduled jobs. The permission code
     *                       then keeps a door open for whoever runs the update.
     * @param permissionCode pass-code written with the raise; ignored on lower and without
     *                       {@code sessionsDeny}
     * @param deniedMessage  message shown to a refused session; same scope as the code
     */
    public static List<String> denyArgs(boolean raise, boolean sessionsDeny, String permissionCode,
            String deniedMessage) {
        List<String> args = new ArrayList<>();
        String value = raise ? "on" : "off";
        args.add("--scheduled-jobs-deny=" + value);
        if (sessionsDeny) {
            args.add("--sessions-deny=" + value);
            if (raise && permissionCode != null && !permissionCode.isBlank()) {
                args.add("--permission-code=" + permissionCode.trim());
            }
            if (raise && deniedMessage != null && !deniedMessage.isBlank()) {
                args.add("--denied-message=" + deniedMessage.trim());
            }
        }
        return args;
    }

    /** The denial-window fields of one {@code rac infobase info} record, in report order. */
    public static Map<String, String> flags(Map<String, String> infobaseInfo) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String field : FLAG_FIELDS) {
            String value = infobaseInfo.get(field);
            if (value != null && !value.isBlank()) {
                out.put(field, value);
            }
        }
        return out;
    }
}
