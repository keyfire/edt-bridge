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
            args.add("--infobase=" + infobaseId);
            addAuth(args, infobaseUser, infobasePassword, "--infobase-user=", "--infobase-pwd=");
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
