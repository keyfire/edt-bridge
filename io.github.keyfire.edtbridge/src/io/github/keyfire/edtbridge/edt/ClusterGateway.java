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
package io.github.keyfire.edtbridge.edt;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.keyfire.edtbridge.core.MaintenanceWindow;
import io.github.keyfire.edtbridge.core.RacOutput;

/**
 * The 1C:Enterprise cluster, through {@code rac} - the one place neither the agent nor {@code ibcmd}
 * reaches.
 *
 * <p>Sessions live in the cluster manager, so listing and ending them is cluster administration by
 * definition: {@code ibcmd session} addresses a stand-alone ibcmd server only, and the agent ends
 * sessions solely as a side effect of an update that needs an exclusive lock.
 *
 * <p>Why this earned its place: a designer session holds the infobase's CONFIGURATION LOCK, and a
 * designer that was killed rather than shut down leaves that session registered. Everything that
 * follows then fails with "Ошибка блокировки информационной базы для конфигурирования" and looks like
 * somebody else's lock. Seeing the sessions - and ending the orphan - is what turns that dead end into
 * a two-minute fix.
 */
public final class ClusterGateway {

    private final PlatformGateway platform = new PlatformGateway();

    /** One session as the cluster reports it. */
    public static final class Session {
        public String id;
        public String infobase;
        public String userName;
        public String host;
        public String appId;
        public String startedAt;
    }

    /** Result of listing or terminating sessions. */
    public static final class SessionsResult {
        public boolean ok;
        public boolean applied;
        public String server;
        public String cluster;
        public String infobase;          // infobase name, when narrowed to one
        public String platform;
        public final List<Session> sessions = new ArrayList<>();
        public final List<String> terminated = new ArrayList<>();
        public String plan;
        public String message;
    }

    /**
     * List the cluster's sessions, optionally narrowed to one infobase and to one kind of application
     * ({@code Designer}, {@code 1CV8C}, {@code BackgroundJob}, ...).
     *
     * @param server        cluster host, optionally with a port; the infobase's server when omitted
     * @param infobaseName  infobase name in the cluster (not the EDT name) - optional
     * @param appId         keep only sessions of this application - optional
     */
    public SessionsResult sessions(String server, String infobaseName, String appId,
            String clusterUser, String clusterPassword, String infobaseUser, String infobasePassword,
            String platformVersion) {
        SessionsResult r = new SessionsResult();
        r.server = server;
        r.infobase = infobaseName;
        Rac rac = resolve(server, platformVersion, r);
        if (rac == null) {
            return r;
        }
        String cluster = clusterId(rac, clusterUser, clusterPassword, r);
        if (cluster == null) {
            return r;
        }
        r.cluster = cluster;
        r.plan = "List sessions of cluster " + cluster + " on " + r.server
                + (infobaseName == null ? "" : ", infobase " + infobaseName)
                + (appId == null ? "" : ", application " + appId);

        List<String> args = new ArrayList<>(List.of("session", "list", "--cluster=" + cluster));
        addAuth(args, clusterUser, clusterPassword, "--cluster-user=", "--cluster-pwd=");
        String infobaseId = null;
        if (infobaseName != null && !infobaseName.isBlank()) {
            infobaseId = infobaseId(rac, cluster, infobaseName, clusterUser, clusterPassword,
                    infobaseUser, infobasePassword, r);
            if (infobaseId == null) {
                return r;
            }
            // session list takes only --infobase and --licenses (probed against rac 8.5.1):
            // passing --infobase-user here dies with "Ошибка разбора параметра". The infobase
            // credentials stay accepted by the tool for the operations that do need them.
            args.add("--infobase=" + infobaseId);
        }
        IbcmdGateway.Run run = IbcmdGateway.run(rac.exe, withServer(rac.server, args));
        if (!run.ok) {
            r.message = "rac session list failed: " + run.error;
            return r;
        }
        for (Map<String, String> record : RacOutput.parse(run.output)) {
            if (record.get("session") == null) {
                continue;
            }
            if (appId != null && !appId.isBlank()
                    && !appId.equalsIgnoreCase(record.getOrDefault("app-id", ""))) {
                continue;
            }
            Session s = new Session();
            s.id = record.get("session");
            s.infobase = record.get("infobase");
            s.userName = record.get("user-name");
            s.host = record.get("host");
            s.appId = record.get("app-id");
            s.startedAt = record.get("started-at");
            r.sessions.add(s);
        }
        r.ok = true;
        r.message = r.sessions.size() + " session(s)";
        return r;
    }

    /**
     * End sessions: the ones named, or every session matching the filters.
     *
     * <p>Dry-run by default, and {@code force} on top of {@code apply} - ending a session is somebody
     * else's work interrupted, and "terminate everything on this infobase" is far too much to hide
     * behind a single boolean.
     */
    public SessionsResult terminate(String server, String infobaseName, String appId,
            List<String> sessionIds, String clusterUser, String clusterPassword, String infobaseUser,
            String infobasePassword, String platformVersion, String reason, boolean apply,
            boolean force) {
        SessionsResult r = sessions(server, infobaseName, appId, clusterUser, clusterPassword,
                infobaseUser, infobasePassword, platformVersion);
        if (!r.ok) {
            return r;
        }
        List<String> targets = new ArrayList<>();
        if (sessionIds != null && !sessionIds.isEmpty()) {
            for (String id : sessionIds) {
                boolean known = r.sessions.stream().anyMatch(s -> id.equalsIgnoreCase(s.id));
                if (!known) {
                    r.ok = false;
                    r.message = "no such session in this cluster: " + id;
                    return r;
                }
                targets.add(id);
            }
        } else {
            r.sessions.forEach(s -> targets.add(s.id));
        }
        r.plan = targets.isEmpty()
                ? "Nothing to terminate - no session matches"
                : "Terminate " + targets.size() + " session(s) of cluster " + r.cluster
                        + (infobaseName == null ? "" : " on infobase " + infobaseName)
                        + (appId == null ? "" : ", application " + appId);
        if (targets.isEmpty()) {
            r.message = "no session matches - nothing to terminate";
            return r;
        }
        if (!apply || !force) {
            r.message = "dry-run: nothing terminated. Ending someone's session cannot be undone - "
                    + "re-call with apply=true AND force=true.";
            return r;
        }

        Rac rac = resolve(r.server, platformVersion, r);
        if (rac == null) {
            return r;
        }
        for (String id : targets) {
            List<String> args = new ArrayList<>(List.of("session", "terminate",
                    "--cluster=" + r.cluster, "--session=" + id));
            addAuth(args, clusterUser, clusterPassword, "--cluster-user=", "--cluster-pwd=");
            if (reason != null && !reason.isBlank()) {
                args.add("--error-message=" + reason);
            }
            IbcmdGateway.Run run = IbcmdGateway.run(rac.exe, withServer(rac.server, args));
            if (!run.ok) {
                r.ok = false;
                r.message = "terminated " + r.terminated.size() + " session(s), then failed on " + id
                        + ": " + run.error;
                return r;
            }
            r.terminated.add(id);
        }
        r.applied = true;
        r.message = "terminated " + r.terminated.size() + " session(s)";
        return r;
    }

    /** Result of a maintenance-window step. */
    public static final class MaintenanceResult {
        public boolean ok;
        public boolean applied;
        public String server;
        public String cluster;
        public String infobase;
        public String platform;
        public Map<String, String> flags;                      // the denial fields after the step
        public final List<Session> sessions = new ArrayList<>();
        public final List<Session> blockers = new ArrayList<>();
        public Boolean clearToUpdate;                          // null until sessions were looked at
        public String plan;
        public String message;
    }

    /**
     * One step of a maintenance window around a database-configuration update.
     *
     * <p>{@code begin} raises {@code scheduled-jobs-deny} (and, when asked, {@code sessions-deny}
     * with a permission code), then watches the session list until only the allowed applications
     * remain - BackgroundJob sessions are short and drain by themselves within a minute once
     * nothing respawns them, so nothing has to be terminated. {@code end} lowers the same flags.
     * {@code status} reports the flags and the sessions without touching anything.
     *
     * <p>The flags are infobase properties in the cluster, so {@code rac} needs the infobase
     * administrator ({@code infobaseUser}/{@code infobasePassword}) - a bare call answers
     * "Недостаточно прав пользователя на информационную базу". The configurator agent cannot do
     * this at all: its SSH client has no operation for the denial flags.
     *
     * @param action        {@code status}, {@code begin} or {@code end}
     * @param allowedAppIds sessions of these applications do not block the update; Designer when
     *                      empty
     * @param waitSeconds   how long {@code begin} watches the drain; 0 looks once and reports
     */
    public MaintenanceResult maintenance(String action, String server, String infobaseName,
            List<String> allowedAppIds, boolean sessionsDeny, String permissionCode,
            String deniedMessage, int waitSeconds, String clusterUser, String clusterPassword,
            String infobaseUser, String infobasePassword, String platformVersion, boolean apply) {
        MaintenanceResult r = new MaintenanceResult();
        r.server = server;
        r.infobase = infobaseName;
        if (infobaseName == null || infobaseName.isBlank()) {
            r.message = "an infobase name is required - the denial flags belong to one infobase";
            return r;
        }
        Rac rac = resolveFor(server, platformVersion, r);
        if (rac == null) {
            return r;
        }
        String cluster = clusterIdFor(rac, clusterUser, clusterPassword, r);
        if (cluster == null) {
            return r;
        }
        r.cluster = cluster;
        String infobaseId = infobaseIdFor(rac, cluster, infobaseName, clusterUser, clusterPassword, r);
        if (infobaseId == null) {
            return r;
        }
        List<String> allowed = (allowedAppIds == null || allowedAppIds.isEmpty())
                ? List.of("Designer") : allowedAppIds;

        boolean begin = "begin".equalsIgnoreCase(action);
        boolean end = "end".equalsIgnoreCase(action);
        if (begin || end) {
            List<String> flagArgs = MaintenanceWindow.denyArgs(begin, sessionsDeny, permissionCode,
                    deniedMessage);
            r.plan = (begin
                    ? "Raise " + String.join(" ", flagArgs) + " on infobase " + infobaseName
                            + ", then watch the sessions up to " + Math.max(waitSeconds, 0)
                            + "s until only " + String.join(", ", allowed) + " remain"
                    : "Lower the denial flags (" + String.join(" ", flagArgs) + ") on infobase "
                            + infobaseName);
            if (!apply) {
                r.ok = true;
                r.message = "dry-run: nothing changed. Re-call with apply=true.";
                return r;
            }
            List<String> args = new ArrayList<>(List.of("infobase", "update",
                    "--cluster=" + cluster, "--infobase=" + infobaseId));
            addAuth(args, clusterUser, clusterPassword, "--cluster-user=", "--cluster-pwd=");
            addAuth(args, infobaseUser, infobasePassword, "--infobase-user=", "--infobase-pwd=");
            args.addAll(flagArgs);
            IbcmdGateway.Run run = IbcmdGateway.run(rac.exe, withServer(rac.server, args));
            if (!run.ok) {
                r.message = "rac infobase update failed: " + run.error;
                return r;
            }
            r.applied = true;
        }

        r.flags = infobaseFlags(rac, cluster, infobaseId, clusterUser, clusterPassword,
                infobaseUser, infobasePassword, r);
        if (r.flags == null) {
            return r;
        }

        // status and end look at the sessions once; begin watches them drain.
        long deadline = System.currentTimeMillis() + (begin ? Math.max(waitSeconds, 0) * 1000L : 0);
        while (true) {
            SessionsResult sess = sessions(server, infobaseName, null, clusterUser, clusterPassword,
                    infobaseUser, infobasePassword, platformVersion);
            if (!sess.ok) {
                r.message = sess.message;
                return r;
            }
            r.sessions.clear();
            r.blockers.clear();
            r.sessions.addAll(sess.sessions);
            for (Map<String, String> b : MaintenanceWindow.blockers(records(sess.sessions), allowed)) {
                r.blockers.add(sess.sessions.stream()
                        .filter(s -> b.get("session").equals(s.id)).findFirst().orElseThrow());
            }
            r.clearToUpdate = r.blockers.isEmpty();
            if (r.clearToUpdate || System.currentTimeMillis() >= deadline) {
                break;
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        r.ok = true;
        if (begin) {
            r.message = r.clearToUpdate
                    ? "clear to update: only " + String.join(", ", allowed) + " sessions remain"
                    : r.blockers.size() + " session(s) still block the update - list them below, "
                            + "terminate the stubborn ones with edt_infobase_sessions, or wait and "
                            + "re-call with action=status";
        } else if (end) {
            r.message = "denial flags lowered - scheduled jobs may start again";
        } else {
            r.message = (r.clearToUpdate ? "no blocking sessions" : r.blockers.size()
                    + " session(s) would block an update");
        }
        return r;
    }

    /** The denial-window fields of the infobase, or null with the reason in {@code r.message}. */
    private Map<String, String> infobaseFlags(Rac rac, String cluster, String infobaseId,
            String clusterUser, String clusterPassword, String infobaseUser, String infobasePassword,
            MaintenanceResult r) {
        List<String> args = new ArrayList<>(List.of("infobase", "info", "--cluster=" + cluster,
                "--infobase=" + infobaseId));
        addAuth(args, clusterUser, clusterPassword, "--cluster-user=", "--cluster-pwd=");
        addAuth(args, infobaseUser, infobasePassword, "--infobase-user=", "--infobase-pwd=");
        IbcmdGateway.Run run = IbcmdGateway.run(rac.exe, withServer(rac.server, args));
        if (!run.ok) {
            r.message = "rac infobase info failed (the flags need the infobase administrator): "
                    + run.error;
            return null;
        }
        List<Map<String, String>> records = RacOutput.parse(run.output);
        if (records.isEmpty()) {
            r.message = "rac infobase info returned nothing";
            return null;
        }
        return MaintenanceWindow.flags(records.get(0));
    }

    /** Sessions as parse-shaped records, for the core logic. */
    private static List<Map<String, String>> records(List<Session> sessions) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Session s : sessions) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("session", s.id);
            m.put("app-id", s.appId == null ? "" : s.appId);
            out.add(m);
        }
        return out;
    }

    // the sessions/terminate result and the maintenance result carry the same plumbing fields,
    // so the resolvers only differ in where they report
    private Rac resolveFor(String server, String platformVersion, MaintenanceResult r) {
        SessionsResult probe = new SessionsResult();
        Rac rac = resolve(server, platformVersion, probe);
        r.server = probe.server != null ? probe.server : r.server;
        r.platform = probe.platform;
        r.message = probe.message;
        return rac;
    }

    private String clusterIdFor(Rac rac, String user, String password, MaintenanceResult r) {
        SessionsResult probe = new SessionsResult();
        String id = clusterId(rac, user, password, probe);
        if (id == null) {
            r.message = probe.message;
        }
        return id;
    }

    private String infobaseIdFor(Rac rac, String cluster, String name, String clusterUser,
            String clusterPassword, MaintenanceResult r) {
        SessionsResult probe = new SessionsResult();
        String id = infobaseId(rac, cluster, name, clusterUser, clusterPassword, null, null, probe);
        if (id == null) {
            r.message = probe.message;
        }
        return id;
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────────

    /** The utility and the server it will be pointed at. */
    private static final class Rac {
        Path exe;
        String server;
        String version;
    }

    /**
     * Find {@code rac} on disk. Any full install carries it, and unlike ibcmd it is not missing from
     * some builds - so the newest full install of the preferred line will do.
     */
    private Rac resolve(String server, String platformVersion, SessionsResult r) {
        if (server == null || server.isBlank()) {
            r.message = "a cluster server is required, e.g. srv.example.test (optionally with a port)";
            return null;
        }
        for (PlatformGateway.DiskPlatform dp
                : platform.discoverFullPlatforms(PlatformGateway.platformLine(platformVersion))) {
            Path exe = PlatformGateway.firstExisting(dp.binDir, "rac.exe", "rac");
            if (exe != null) {
                Rac rac = new Rac();
                rac.exe = exe;
                rac.server = server.trim();
                rac.version = dp.version;
                r.platform = dp.version;
                r.server = rac.server;
                return rac;
            }
        }
        r.message = "no on-disk install carrying rac was found"
                + (platformVersion == null ? "" : " for version line " + platformVersion);
        return null;
    }

    /** The cluster on that server. With more than one, the first is used and the result says so. */
    private String clusterId(Rac rac, String user, String password, SessionsResult r) {
        List<String> args = new ArrayList<>(List.of(rac.server, "cluster", "list"));
        addAuth(args, user, password, "--cluster-user=", "--cluster-pwd=");
        IbcmdGateway.Run run = IbcmdGateway.run(rac.exe, args);
        if (!run.ok) {
            r.message = "rac cluster list failed: " + run.error;
            return null;
        }
        List<Map<String, String>> clusters = RacOutput.parse(run.output);
        String id = RacOutput.first(clusters, "cluster");
        if (id == null) {
            r.message = "the administration server on " + rac.server + " reports no cluster";
            return null;
        }
        if (clusters.size() > 1) {
            r.plan = "(" + clusters.size() + " clusters on this server, using the first)";
        }
        return id;
    }

    /** The infobase's uuid in that cluster, by its cluster name. */
    private String infobaseId(Rac rac, String cluster, String name, String clusterUser,
            String clusterPassword, String infobaseUser, String infobasePassword, SessionsResult r) {
        List<String> args = new ArrayList<>(List.of(rac.server, "infobase", "summary", "list",
                "--cluster=" + cluster));
        addAuth(args, clusterUser, clusterPassword, "--cluster-user=", "--cluster-pwd=");
        IbcmdGateway.Run run = IbcmdGateway.run(rac.exe, args);
        if (!run.ok) {
            r.message = "rac infobase summary list failed: " + run.error;
            return null;
        }
        List<Map<String, String>> found = RacOutput.where(RacOutput.parse(run.output), "name", name.trim());
        if (found.isEmpty()) {
            r.message = "no infobase named " + name + " in cluster " + cluster;
            return null;
        }
        return found.get(0).get("infobase");
    }

    private static void addAuth(List<String> args, String user, String password, String userFlag,
            String passwordFlag) {
        if (user != null && !user.isBlank()) {
            args.add(userFlag + user);
            args.add(passwordFlag + (password == null ? "" : password));
        }
    }

    /** rac wants the server first, then the mode - fix up an argument list built the other way round. */
    static List<String> withServer(String server, List<String> args) {
        List<String> full = new ArrayList<>();
        full.add(server);
        full.addAll(args);
        return full;
    }

    /** A session as a plain map, for the tool layer. */
    static Map<String, Object> describe(Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session", s.id);
        m.put("infobase", s.infobase);
        m.put("userName", s.userName);
        m.put("host", s.host);
        m.put("appId", s.appId);
        m.put("startedAt", s.startedAt);
        return m;
    }
}
