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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com._1c.g5.designer.ssh.client.DesignerClient;
import com._1c.g5.designer.ssh.client.IDesignerSession;
import com._1c.g5.designer.ssh.client.operation.ExtensionScope;
import com._1c.g5.designer.ssh.client.operation.IDbStructureChange;
import com._1c.g5.designer.ssh.client.operation.IExtensionProperties;

/**
 * Talking to an infobase through the DESIGNER AGENT - a long-lived configurator started with
 * {@code /AgentMode}, which takes commands over SSH.
 *
 * <p>This is the third transport to an infobase, and the only one that reaches everything. The other
 * two fall short: EDT's own synchronization cannot open a session against an infobase that
 * authenticates its users from outside the UI, and {@code ibcmd}'s {@code extension} mode has no 1C
 * credentials at all ({@code --user} is rejected outright), so the extensions of an authenticating
 * infobase are unreachable through it. The agent authenticates over SSH AS THE INFOBASE USER - an
 * infobase without users takes an empty name and password, one with users takes its own - which is
 * exactly the missing piece. It is also what the production CI drives.
 *
 * <p>The client is the platform's own: {@code com._1c.g5.designer.ssh.client}, shipped with EDT and
 * already on the bundle path, so this costs no third-party dependency.
 *
 * <p><b>An agent is expensive to start and cheap to keep.</b> Starting one launches a configurator and
 * opens its infobase session; a large configuration takes a while. So agents are cached per infobase
 * and reused across calls, and only ever stopped explicitly.
 *
 * <p>Two properties of the agent shape this code and are easy to get wrong:
 * <ul>
 * <li>it serves ONE SSH client at a time, so calls against the same agent are serialized here;</li>
 * <li>file arguments are sandboxed to {@code /AgentBaseDir} - an absolute path elsewhere is refused
 *     with "Directory access violation", and files are meant to travel over SFTP. Inside that
 *     directory the agent gives each SSH user its own subdirectory, mapped in
 *     {@code agentbasedir.json}.</li>
 * </ul>
 */
public final class DesignerAgentGateway {

    /** Where the agent's SSH server starts looking for a free port. */
    private static final int FIRST_PORT = 1543;
    private static final int LAST_PORT = 1600;

    /** How long to wait for a freshly started agent to accept connections. */
    private static final int START_TIMEOUT_SECONDS = 120;

    /** How many times to retry the SSH connect while the agent finishes coming up. */
    private static final int CONNECT_ATTEMPTS = 15;

    /** Running agents, keyed by the infobase connection string they were started for. */
    private static final Map<String, Agent> AGENTS = new ConcurrentHashMap<>();

    private final PlatformGateway platform = new PlatformGateway();

    /** A running agent process and how to reach it. */
    public static final class Agent {
        public String infobase;            // label: how the caller named it
        public String connectionString;    // /F<path> or /S<server>\<ref>
        public int port;
        public long pid = -1;
        public String platformVersion;
        public String baseDir;
        public String user = "";
        String password = "";
        transient Process process;
        final ReentrantLock lock = new ReentrantLock();
        transient DesignerClient client;
        transient IDesignerSession session;
        /** The infobase connection belongs to the PROCESS, not to the SSH session that opened it. */
        boolean infobaseConnected;
    }

    /** Result of starting, listing or stopping agents. */
    public static final class AgentResult {
        public boolean ok;
        public boolean started;
        public boolean stopped;
        public final List<Agent> agents = new ArrayList<>();
        public String plan;
        public String message;
    }

    /** What the platform says about the infobase's DATABASE configuration. */
    public static final class ConfigStateResult {
        public boolean ok;
        public String infobase;
        public String platform;
        public String extension;                     // null = the configuration itself
        /** True when the database configuration is up to date; false when an update is pending. */
        public Boolean databaseConfigUpToDate;
        public final List<String> pendingChanges = new ArrayList<>();
        public int pendingChangeCount;
        public String plan;
        public String message;
    }

    /** Extension properties as the infobase holds them. */
    public static final class ExtensionsResult {
        public boolean ok;
        public boolean applied;
        public String infobase;
        public String platform;
        public String name;
        public final List<Map<String, Object>> extensions = new ArrayList<>();
        public final List<String> changed = new ArrayList<>();
        public String plan;
        public String message;
    }

    /** Applying the database configuration. */
    public static final class UpdateResult {
        public boolean ok;
        public boolean applied;
        public String infobase;
        public String platform;
        public String extension;
        public String sessionTermination;
        public final List<String> changes = new ArrayList<>();
        public String plan;
        public String message;
    }

    // ── lifecycle ───────────────────────────────────────────────────────────────────────────────

    /**
     * Start an agent for an infobase EDT knows (by name or uuid), or return the running one.
     *
     * @param infobase        registered infobase name or uuid
     * @param user            infobase user; empty for an infobase without users
     * @param password        that user's password
     * @param platformVersion version line to prefer for the configurator (optional). It must match the
     *                        server the infobase runs on - a designer of a different build is refused
     *                        by the server, not by us.
     */
    public AgentResult start(String infobase, String user, String password, String platformVersion) {
        AgentResult r = new AgentResult();
        Address resolved = resolveAddress(infobase);
        if (resolved.address == null) {
            r.message = resolved.problem;
            return r;
        }
        String connection = resolved.address;
        Agent running = AGENTS.get(connection);
        if (running != null && running.process != null && running.process.isAlive()) {
            r.ok = true;
            r.agents.add(running);
            r.message = "an agent for " + resolved.label + " is already running on port " + running.port;
            return r;
        }
        PlatformGateway.DiskPlatform install = findDesignerInstall(platformVersion);
        if (install == null) {
            r.message = "no on-disk full install with a configurator was found"
                    + (platformVersion == null ? "" : " for version line " + platformVersion);
            return r;
        }
        Path exe = PlatformGateway.firstExisting(install.binDir, "1cv8.exe", "1cv8");
        if (exe == null) {
            r.message = "the install at " + install.binDir + " has no 1cv8 executable";
            return r;
        }
        int port = freePort();
        if (port < 0) {
            r.message = "no free port for the agent in " + FIRST_PORT + ".." + LAST_PORT;
            return r;
        }

        Agent agent = new Agent();
        agent.infobase = resolved.label;
        agent.connectionString = connection;
        agent.port = port;
        agent.platformVersion = install.version;
        agent.user = user == null ? "" : user;
        agent.password = password == null ? "" : password;
        r.plan = "Start a configurator agent " + install.version + " for " + resolved.label
                + " on 127.0.0.1:" + port;

        Path baseDir = null;
        try {
            baseDir = Files.createTempDirectory("edtbridge-agent-");
            agent.baseDir = baseDir.toString();
            Path log = baseDir.resolve("agent.log");
            // The parameters are glued to their values (/FD:\base, /AgentPort1543): that is the
            // platform's own convention, and a shell that rewrites slash-arguments into paths (git
            // bash does) breaks the launch silently - the process starts and does nothing.
            List<String> cmd = List.of(exe.toString(), "DESIGNER", connection,
                    "/AgentMode",
                    "/AgentListenAddress" + "127.0.0.1",
                    "/AgentPort" + port,
                    "/AgentSSHHostKeyAuto",
                    "/AgentBaseDir" + baseDir,
                    "/Out" + log,
                    "/DisableStartupDialogs", "/DisableStartupMessages");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            agent.process = pb.start();
            agent.pid = agent.process.pid();

            if (!awaitPort(port, agent.process)) {
                String why = readAgentLog(log);
                agent.process.destroyForcibly();
                IbcmdGateway.deleteRecursively(baseDir);
                r.message = "the agent did not start listening on port " + port
                        + (why.isBlank() ? "" : ": " + why);
                return r;
            }
        } catch (IOException ex) {
            if (agent.process != null) {
                agent.process.destroyForcibly();
            }
            IbcmdGateway.deleteRecursively(baseDir);
            r.message = "could not start the agent: " + GatewaySupport.describeCause(ex);
            return r;
        }

        AGENTS.put(connection, agent);
        r.ok = true;
        r.started = true;
        r.agents.add(agent);
        r.message = "agent " + install.version + " for " + resolved.label
                + " listening on 127.0.0.1:" + port;
        return r;
    }

    /** Agents this bridge has running. */
    public AgentResult list() {
        AgentResult r = new AgentResult();
        r.ok = true;
        AGENTS.values().removeIf(a -> a.process == null || !a.process.isAlive());
        r.agents.addAll(AGENTS.values());
        r.message = r.agents.isEmpty() ? "no agent is running" : (r.agents.size() + " agent(s) running");
        return r;
    }

    /** Stop the agent for an infobase - politely first ({@code common shutdown}), then for real. */
    public AgentResult stop(String infobase) {
        AgentResult r = new AgentResult();
        Agent agent = lookup(infobase);
        if (agent == null) {
            r.ok = true;
            r.message = "no agent is running for " + infobase;
            return r;
        }
        agent.lock.lock();
        try {
            try {
                session(agent).common().shutdown().exec(Duration.ofMinutes(1));
            } catch (Exception politeFailed) {
                // the process is going away regardless
            }
            dropSession(agent);
            if (agent.process != null) {
                agent.process.destroy();
                if (agent.process.isAlive()) {
                    agent.process.destroyForcibly();
                }
            }
        } finally {
            agent.lock.unlock();
        }
        AGENTS.remove(agent.connectionString);
        if (agent.baseDir != null) {
            IbcmdGateway.deleteRecursively(Path.of(agent.baseDir));
        }
        r.ok = true;
        r.stopped = true;
        r.message = "the agent for " + agent.infobase + " was stopped";
        return r;
    }

    // ── operations ──────────────────────────────────────────────────────────────────────────────

    /**
     * Ask the platform whether the DATABASE configuration is the one the infobase holds - the
     * question every "did my update actually land" comes down to, since a session executes the
     * database configuration, not the main one.
     *
     * <p>The answer comes from the platform itself: the update is started and its confirmation is
     * REFUSED. When an update is pending, the platform hands over the full list of structure changes
     * and waits - refusing leaves the infobase untouched (verified: the same list is offered again on
     * the next call). When nothing is pending, no confirmation is asked and no change is reported.
     *
     * <p>This replaces comparing dumped configuration files, which gave false negatives: a dynamic
     * update leaves the two containers different even when the platform considers them equal.
     */
    public ConfigStateResult databaseConfigState(String infobase, String extension, String user,
            String password, String platformVersion) {
        ConfigStateResult r = new ConfigStateResult();
        r.infobase = infobase;
        r.extension = extension;
        Ensured ensured = ensure(infobase, user, password, platformVersion);
        if (ensured.agent == null) {
            r.message = ensured.problem;
            return r;
        }
        Agent agent = ensured.agent;
        r.platform = agent.platformVersion;
        r.plan = "Ask the configurator agent what a database-configuration update would change in "
                + agent.infobase + (extension == null ? "" : ", extension " + extension)
                + ", and refuse it";

        agent.lock.lock();
        try {
            IDesignerSession s = session(agent);
            List<String> collected = new ArrayList<>();
            boolean[] refused = {false};
            var step = s.configure().updateDatabaseConfiguration();
            if (extension != null && !extension.isBlank()) {
                step = step.extension(extension.trim());
            }
            try {
                step.onConfirm(changes -> {
                    for (IDbStructureChange c : changes) {
                        collected.add("[" + c.getType() + "] " + c.getMessage());
                    }
                    refused[0] = true;
                    return false; // read-only: never apply from a state query
                }).exec(Duration.ofHours(1));
            } catch (Exception cancelled) {
                // Refusing the confirmation IS how this question gets asked, and the client reports
                // that refusal by throwing. Only an exception we did not cause is a failure.
                if (!refused[0]) {
                    throw cancelled;
                }
            }

            r.pendingChanges.addAll(collected);
            r.pendingChangeCount = collected.size();
            r.databaseConfigUpToDate = collected.isEmpty();
            r.ok = true;
            r.message = collected.isEmpty()
                    ? "the database configuration is up to date - the platform reports nothing to apply"
                    : "an update is PENDING: " + collected.size() + " structure change(s) are waiting, so "
                      + "sessions still execute the previous configuration. Nothing was applied.";
        } catch (Exception ex) {
            dropSession(agent, ex);
            r.message = "the agent could not report the state: " + describe(ex);
        } finally {
            agent.lock.unlock();
        }
        return r;
    }

    /** Read the properties of the infobase's extensions - including {@code active}. */
    public ExtensionsResult extensionProperties(String infobase, String name, String user,
            String password, String platformVersion) {
        ExtensionsResult r = new ExtensionsResult();
        r.infobase = infobase;
        r.name = name;
        Ensured ensured = ensure(infobase, user, password, platformVersion);
        if (ensured.agent == null) {
            r.message = ensured.problem;
            return r;
        }
        Agent agent = ensured.agent;
        r.platform = agent.platformVersion;
        r.plan = "Read extension properties of " + agent.infobase + " through the configurator agent";

        agent.lock.lock();
        try {
            IDesignerSession s = session(agent);
            var get = s.extensions().properties().get();
            List<IExtensionProperties> props = (name == null || name.isBlank())
                    ? get.allExtensions().exec(Duration.ofMinutes(10))
                    : get.extension(name.trim()).exec(Duration.ofMinutes(10));
            for (IExtensionProperties p : props) {
                r.extensions.add(describe(p));
            }
            r.ok = true;
            r.message = r.extensions.size() + " extension(s)";
        } catch (Exception ex) {
            dropSession(agent, ex);
            r.message = "the agent could not read extension properties: " + describe(ex);
        } finally {
            agent.lock.unlock();
        }
        return r;
    }

    /**
     * Set properties of one extension in the infobase. Dry-run by default: without {@code apply} it
     * reports what would change, which is the point - switching an extension off is a live change to a
     * running infobase.
     */
    public ExtensionsResult setExtensionProperties(String infobase, String name, Boolean active,
            Boolean safeMode, Boolean unsafeActionProtection, Boolean usedInDistributedInfobase,
            String securityProfile, String scope, String user, String password, String platformVersion,
            boolean apply) {
        ExtensionsResult r = new ExtensionsResult();
        r.infobase = infobase;
        r.name = name;
        if (name == null || name.isBlank()) {
            r.message = "an extension name is required";
            return r;
        }
        List<String> wanted = new ArrayList<>();
        if (active != null) {
            wanted.add("active=" + active);
        }
        if (safeMode != null) {
            wanted.add("safeMode=" + safeMode);
        }
        if (unsafeActionProtection != null) {
            wanted.add("unsafeActionProtection=" + unsafeActionProtection);
        }
        if (usedInDistributedInfobase != null) {
            wanted.add("usedInDistributedInfobase=" + usedInDistributedInfobase);
        }
        if (securityProfile != null) {
            wanted.add("securityProfile=" + securityProfile);
        }
        if (scope != null) {
            wanted.add("scope=" + scope);
        }
        if (wanted.isEmpty()) {
            r.message = "nothing to set - pass at least one property";
            return r;
        }
        Ensured ensured = ensure(infobase, user, password, platformVersion);
        if (ensured.agent == null) {
            r.message = ensured.problem;
            return r;
        }
        Agent agent = ensured.agent;
        r.platform = agent.platformVersion;
        r.plan = "Set " + String.join(", ", wanted) + " on extension " + name + " in " + agent.infobase;

        agent.lock.lock();
        try {
            IDesignerSession s = session(agent);
            var before = s.extensions().properties().get().extension(name.trim())
                    .exec(Duration.ofMinutes(10));
            for (IExtensionProperties p : before) {
                r.extensions.add(describe(p));
            }
            r.ok = true;
            if (!apply) {
                r.message = "dry-run: nothing changed. Re-call with apply=true to set "
                        + String.join(", ", wanted) + ".";
                return r;
            }
            var set = s.extensions().properties().set().extension(name.trim());
            if (active != null) {
                set = set.active(active);
            }
            if (safeMode != null) {
                set = set.safeMode(safeMode);
            }
            if (unsafeActionProtection != null) {
                set = set.unsafeActionProtection(unsafeActionProtection);
            }
            if (usedInDistributedInfobase != null) {
                set = set.usedInDistributedInfobase(usedInDistributedInfobase);
            }
            if (securityProfile != null) {
                set = set.securityProfile(securityProfile);
            }
            if (scope != null) {
                set = set.scope("data-separation".equalsIgnoreCase(scope)
                        ? ExtensionScope.DATA_SEPARATION : ExtensionScope.INFOBASE);
            }
            set.exec(Duration.ofMinutes(10));
            r.applied = true;
            r.changed.addAll(wanted);
            r.extensions.clear();
            for (IExtensionProperties p : s.extensions().properties().get()
                    .extension(name.trim()).exec(Duration.ofMinutes(10))) {
                r.extensions.add(describe(p));
            }
            r.message = "set " + String.join(", ", wanted) + " on " + name;
        } catch (Exception ex) {
            dropSession(agent, ex);
            r.ok = false;
            r.applied = false;
            r.message = "the agent could not set the properties: " + describe(ex);
        } finally {
            agent.lock.unlock();
        }
        return r;
    }

    /**
     * Apply the database configuration - the step that makes running sessions execute the new code.
     *
     * <p>Dry-run by default, and the dry-run is the real thing: it starts the same operation and
     * refuses the confirmation, so it reports exactly what would be applied.
     *
     * @param sessionTermination {@code disable} (default), {@code prompt} or {@code force} - what to do
     *                           when an exclusive lock is needed and sessions hold the infobase. This
     *                           is the whole "deny sessions, kick everyone out, apply, allow again"
     *                           procedure in one option, and {@code force} really does end other
     *                           people's sessions.
     */
    public UpdateResult updateDatabaseConfiguration(String infobase, String extension,
            String sessionTermination, String terminationMessage, String user, String password,
            String platformVersion, boolean apply) {
        UpdateResult r = new UpdateResult();
        r.infobase = infobase;
        r.extension = extension;
        r.sessionTermination = sessionTermination == null ? "disable" : sessionTermination.toLowerCase();
        Ensured ensured = ensure(infobase, user, password, platformVersion);
        if (ensured.agent == null) {
            r.message = ensured.problem;
            return r;
        }
        Agent agent = ensured.agent;
        r.platform = agent.platformVersion;
        r.plan = "Update the database configuration of " + agent.infobase
                + (extension == null ? "" : ", extension " + extension)
                + " (sessionTermination=" + r.sessionTermination + ")";

        agent.lock.lock();
        try {
            IDesignerSession s = session(agent);
            List<String> collected = new ArrayList<>();
            boolean[] refused = {false};
            var step = s.configure().updateDatabaseConfiguration();
            if (extension != null && !extension.isBlank()) {
                step = step.extension(extension.trim());
            }
            step = step.onInfobaseLockError(termination(r.sessionTermination));
            List<IDbStructureChange> result = null;
            try {
                result = step.onConfirm(changes -> {
                    for (IDbStructureChange c : changes) {
                        collected.add("[" + c.getType() + "] " + c.getMessage());
                    }
                    refused[0] = !apply;
                    return apply;
                }).exec(Duration.ofHours(2));
            } catch (Exception cancelled) {
                // A dry-run ends by refusing the confirmation, and the client reports that by
                // throwing - expected here, and only here.
                if (!refused[0]) {
                    throw cancelled;
                }
            }
            if (result != null) {
                for (IDbStructureChange c : result) {
                    String line = "[" + c.getType() + "] " + c.getMessage();
                    if (!collected.contains(line)) {
                        collected.add(line);
                    }
                }
            }
            r.changes.addAll(collected);
            r.ok = true;
            r.applied = apply;
            if (!apply) {
                r.message = collected.isEmpty()
                        ? "dry-run: nothing to apply - the database configuration is already up to date"
                        : "dry-run: " + collected.size() + " structure change(s) would be applied. "
                          + "Re-call with apply=true.";
            } else {
                r.message = collected.isEmpty()
                        ? "nothing to apply - the database configuration was already up to date"
                        : "applied " + collected.size() + " structure change(s)";
            }
        } catch (Exception ex) {
            dropSession(agent, ex);
            r.ok = false;
            r.applied = false;
            r.message = "the update failed: " + describe(ex);
        } finally {
            agent.lock.unlock();
        }
        return r;
    }

    /**
     * Remove an extension from the infobase - the last step of the lifecycle the bridge could not do,
     * so cleaning up after itself meant hand-writing a client against the agent.
     *
     * <p>Destructive and irreversible from here: the extension's own configuration lives in the
     * infobase, not in the project, so deleting it there is not something a rebuild undoes. Hence the
     * same shape the other destructive tools have - {@code apply} is not enough on its own,
     * {@code force} has to say it out loud - and a dry-run that first proves the extension is really
     * there and reports what it is.
     */
    public ExtensionsResult deleteExtension(String infobase, String name, String user, String password,
            String platformVersion, boolean updateDatabaseConfig, boolean apply, boolean force) {
        ExtensionsResult r = new ExtensionsResult();
        r.infobase = infobase;
        r.name = name;
        if (name == null || name.isBlank()) {
            r.message = "an extension name is required";
            return r;
        }
        Ensured ensured = ensure(infobase, user, password, platformVersion);
        if (ensured.agent == null) {
            r.message = ensured.problem;
            return r;
        }
        Agent agent = ensured.agent;
        r.platform = agent.platformVersion;
        r.plan = "Delete extension " + name.trim() + " from " + agent.infobase
                + (updateDatabaseConfig ? ", then apply the database configuration" : "");

        agent.lock.lock();
        try {
            IDesignerSession s = session(agent);
            List<IExtensionProperties> before;
            try {
                before = s.extensions().properties().get()
                        .extension(name.trim()).exec(Duration.ofMinutes(10));
            } catch (Exception notThere) {
                if (!GatewaySupport.describeCause(notThere).contains("не найдено")) {
                    throw notThere;
                }
                r.message = "no extension named " + name.trim() + " in " + agent.infobase
                        + " - edt_extension_properties lists what is there";
                return r;
            }
            for (IExtensionProperties p : before) {
                // Match the name rather than trust a non-empty answer: asking about an extension that
                // is not there comes back as a record with an EMPTY name, and taking that for a hit
                // turns a clear "no such extension" into the platform's own confusing reply about an
                // extension named ''.
                if (name.trim().equalsIgnoreCase(p.getName())) {
                    r.extensions.add(describe(p));
                }
            }
            if (r.extensions.isEmpty()) {
                r.message = "no extension named " + name.trim() + " in " + agent.infobase
                        + " - edt_extension_properties lists what is there";
                return r;
            }
            r.ok = true;
            if (!apply || !force) {
                r.message = "dry-run: nothing deleted. Deleting an extension cannot be undone from "
                        + "here - re-call with apply=true AND force=true.";
                return r;
            }
            s.extensions().delete().extension(name.trim()).exec(Duration.ofMinutes(30));
            r.applied = true;
            r.changed.add("deleted");
            r.message = "extension " + name.trim() + " deleted from " + agent.infobase;
        } catch (Exception ex) {
            dropSession(agent, ex);
            r.ok = false;
            r.applied = false;
            r.message = "the deletion failed: " + describe(ex);
            return r;
        } finally {
            agent.lock.unlock();
        }

        if (updateDatabaseConfig) {
            UpdateResult update = updateDatabaseConfiguration(infobase, null, null, null, user, password,
                    platformVersion, true);
            r.message = r.message + "; " + update.message;
            r.ok = update.ok;
        }
        return r;
    }

    /** Loading an EDT project into an infobase through the agent. */
    public static final class LoadProjectResult {
        public boolean ok;
        public boolean applied;
        public String project;
        public String infobase;
        public String platform;
        public String extension;          // set when the project is loaded as an extension
        public final List<String> issues = new ArrayList<>();
        public boolean databaseConfigUpdated;
        public final List<String> databaseChanges = new ArrayList<>();
        public String plan;
        public String message;
    }

    /**
     * Load an EDT project into an infobase and, unless told otherwise, apply it to the database.
     *
     * <p>This is the path EDT's own synchronization cannot take against an infobase that authenticates
     * its users - there is no way to give it credentials from outside its UI. Here the project is
     * exported to designer XML in-process (the same export factory the extension build uses) and handed
     * to the agent's {@code config load-config-from-files}, which loads XML straight into the infobase.
     * No throwaway base and no {@code .cf} in between, which is what makes it usable for a configuration
     * with a million objects.
     *
     * <p>The export goes directly into the agent's own directory: file arguments are sandboxed to
     * {@code /AgentBaseDir}, and each SSH user gets a subdirectory of its own there - the platform
     * resolves which one.
     *
     * <p>Loading changes the MAIN configuration only. Sessions execute the DATABASE configuration, so
     * {@code updateDatabaseConfig} (on by default) applies it afterwards - leaving it off is how a
     * caller ends up with an infobase that holds the new code and runs the old.
     */
    public LoadProjectResult loadProject(String projectName, String infobase, String extension,
            String sessionTermination, String user, String password, String platformVersion,
            boolean updateDatabaseConfig, boolean apply) {
        LoadProjectResult r = new LoadProjectResult();
        r.project = projectName;
        r.infobase = infobase;
        org.eclipse.core.resources.IProject p = org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
                .getRoot().getProject(projectName == null ? "" : projectName);
        if (projectName == null || !p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        ProjectRoot root = configurationRoot(p);
        if (root.problem != null) {
            r.message = root.problem;
            return r;
        }
        r.extension = (extension != null && !extension.isBlank()) ? extension.trim()
                : (root.adopted ? root.name : null);

        Ensured ensured = ensure(infobase, user, password, platformVersion);
        if (ensured.agent == null) {
            r.message = ensured.problem;
            return r;
        }
        Agent agent = ensured.agent;
        r.platform = agent.platformVersion;
        r.plan = "Export " + projectName + " to designer XML, load it into " + agent.infobase
                + (r.extension == null ? " as the configuration" : " as extension " + r.extension)
                + (updateDatabaseConfig ? ", then apply the database configuration" : "");
        if (!apply) {
            r.ok = true;
            r.message = "dry-run: nothing exported and nothing loaded. Re-call with apply=true.";
            return r;
        }

        agent.lock.lock();
        Path exportDir = null;
        try {
            IDesignerSession session = session(agent);
            // The agent gives each SSH user a subdirectory of its own inside /AgentBaseDir, and only
            // the platform knows the layout - it asks the agent for its version and reads
            // agentbasedir.json. Note the overload: the first argument of the two-argument form is the
            // VERSION, not the user, and passing a user name there dies in version parsing.
            Path userDir = com._1c.g5.designer.ssh.client.integration.UserDirectoryProvider
                    .get(session, Path.of(agent.baseDir), agent.user);
            String name = "edtbridge-load-" + java.util.UUID.randomUUID();
            exportDir = userDir.resolve(name);
            Files.createDirectories(exportDir);

            com.google.inject.Injector injector = GatewaySupport.exportInjector();
            if (injector == null) {
                r.message = "export injector unavailable (com._1c.g5.v8.dt.export not loaded)";
                return r;
            }
            com._1c.g5.v8.dt.export.IExportOperationFactory factory =
                    injector.getInstance(com._1c.g5.v8.dt.export.IExportOperationFactory.class);
            org.eclipse.core.runtime.IStatus exported = factory
                    .createExportOperation(exportDir, GatewaySupport.projectVersion(p), root.object)
                    .run(new org.eclipse.core.runtime.NullProgressMonitor());
            if (exported != null && exported.getSeverity() == org.eclipse.core.runtime.IStatus.ERROR) {
                r.message = "export to designer XML failed: " + exported.getMessage();
                return r;
            }

            var load = session.configure().importXmlToInfobase(Path.of(name));
            if (r.extension != null) {
                load = load.extension(r.extension);
            }
            List<com._1c.g5.designer.ssh.client.operation.ILoadIssue> issues =
                    load.exec(Duration.ofHours(4));
            if (issues != null) {
                for (com._1c.g5.designer.ssh.client.operation.ILoadIssue issue : issues) {
                    r.issues.add("[" + issue.getLevel() + "] " + issue.getMessage());
                }
            }
            r.ok = true;
            r.applied = true;
            r.message = "loaded into " + agent.infobase
                    + (r.issues.isEmpty() ? "" : " with " + r.issues.size() + " issue(s)");
        } catch (Exception ex) {
            dropSession(agent, ex);
            r.message = "the load failed: " + describe(ex);
            return r;
        } finally {
            IbcmdGateway.deleteRecursively(exportDir);
            agent.lock.unlock();
        }

        if (updateDatabaseConfig) {
            UpdateResult update = updateDatabaseConfiguration(infobase, r.extension, sessionTermination,
                    null, user, password, platformVersion, true);
            r.databaseConfigUpdated = update.applied;
            r.databaseChanges.addAll(update.changes);
            r.message = r.message + "; " + update.message;
            r.ok = update.ok;
        } else {
            r.message = r.message + ". The DATABASE configuration was NOT applied - sessions still run "
                    + "the previous code until edt_update_database_config is called.";
        }
        return r;
    }

    /** The project's Configuration root, and whether it is an extension (an adopted configuration). */
    private static final class ProjectRoot {
        org.eclipse.emf.ecore.EObject object;
        String name;
        boolean adopted;
        String problem;
    }

    private static ProjectRoot configurationRoot(org.eclipse.core.resources.IProject p) {
        ProjectRoot r = new ProjectRoot();
        com._1c.g5.v8.dt.core.platform.IBmModelManager mm =
                com._1c.g5.wiring.ServiceAccess.get(com._1c.g5.v8.dt.core.platform.IBmModelManager.class);
        com._1c.g5.v8.bm.integration.IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.problem = "no BM model for project: " + p.getName();
            return r;
        }
        model.executeReadonlyTask(
                new com._1c.g5.v8.bm.integration.AbstractBmTask<Object>("edt-bridge.loadProject.root") {
                    @Override
                    public Object execute(com._1c.g5.v8.bm.core.IBmTransaction tx,
                            org.eclipse.core.runtime.IProgressMonitor monitor) {
                        Object cfg = tx.getTopObjectByFqn("Configuration");
                        if (cfg instanceof com._1c.g5.v8.dt.metadata.mdclass.Configuration) {
                            com._1c.g5.v8.dt.metadata.mdclass.Configuration conf =
                                    (com._1c.g5.v8.dt.metadata.mdclass.Configuration) cfg;
                            r.object = conf;
                            r.name = conf.getName();
                            r.adopted = conf.getObjectBelonging() != null
                                    && "Adopted".equalsIgnoreCase(conf.getObjectBelonging().getName());
                        }
                        return null;
                    }
                });
        if (r.object == null) {
            r.problem = "no Configuration root in project " + p.getName();
        }
        return r;
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────────

    /**
     * The agent's live SSH session, opened once and kept.
     *
     * <p>Not a per-call connection, and that is not an optimisation. A designer session takes the
     * infobase's CONFIGURATION LOCK, and closing the SSH session gives it up - on a clustered infobase
     * the release is not instant, so the next call's {@code connect-ib} fails with "Ошибка блокировки
     * информационной базы для конфигурирования". Holding one session for the life of the agent is also
     * how the production CI drives it.
     */
    private static IDesignerSession session(Agent agent) throws Exception {
        if (agent.session != null) {
            return agent.session;
        }
        Exception last = null;
        for (int attempt = 0; attempt < CONNECT_ATTEMPTS; attempt++) {
            DesignerClient client = new DesignerClient();
            client.setTimeout(120_000);
            try {
                client.connect("127.0.0.1", agent.port, agent.user, agent.password);
                IDesignerSession session = client.openSession();
                if (!agent.infobaseConnected) {
                    // Only ONCE per agent: the infobase connection outlives the SSH session, and
                    // asking a second time is refused with "Ошибка блокировки информационной базы для
                    // конфигурирования" - the agent's own configuration lock, seen as a rejection.
                    // Which is also how we recognise that it IS already connected, so that refusal is
                    // not an error here: note it and reconnect without asking again. (If the lock were
                    // somebody else's, the first real operation says so plainly.)
                    try {
                        session.common().connectInfobase().exec(Duration.ofHours(1));
                        agent.infobaseConnected = true;
                    } catch (Exception refused) {
                        if (!alreadyConnected(refused)) {
                            throw refused;
                        }
                        agent.infobaseConnected = true;
                        closeQuietly(client, session);
                        continue;
                    }
                }
                agent.client = client;
                agent.session = session;
                return session;
            } catch (com._1c.g5.designer.ssh.client.AuthenticationException refused) {
                throw refused;
            } catch (Exception notReadyYet) {
                // An open TCP port is not a ready SSH server: right after the agent binds, a
                // connection is accepted and then dropped, and that surfaces as a
                // DesignerClientException with neither message nor cause. Retrying tells the two apart.
                last = notReadyYet;
                closeQuietly(client, null);
                Thread.sleep(1000);
            }
        }
        throw last;
    }

    /**
     * Give up the cached session after a failure. When the agent says the infobase connection is gone,
     * forget that too - the connection belongs to the process and the process can lose it, so believing
     * our own flag over the agent leaves every later call failing with "Соединение с информационной
     * базой не установлено".
     */
    private static void dropSession(Agent agent, Exception cause) {
        if (cause != null && GatewaySupport.describeCause(cause)
                .contains("Соединение с информационной базой не установлено")) {
            agent.infobaseConnected = false;
        }
        dropSession(agent);
    }

    /** The agent refusing a second connect-ib because it already holds the infobase. */
    private static boolean alreadyConnected(Exception ex) {
        return GatewaySupport.describeCause(ex)
                .contains("Ошибка блокировки информационной базы для конфигурирования");
    }

    /** Give up the cached session so the next call reconnects instead of reusing a broken one. */
    private static void dropSession(Agent agent) {
        closeQuietly(agent.client, agent.session);
        agent.client = null;
        agent.session = null;
    }

    private static void closeQuietly(DesignerClient client, IDesignerSession session) {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
                // the client is going away anyway
            }
        }
        if (client != null) {
            try {
                client.disconnect();
            } catch (Exception ignored) {
                // ditto
            }
        }
    }

    /**
     * What went wrong, including the throwing frame. The SSH client is fond of exceptions with no
     * message and no cause, and a bare class name is not a diagnosis.
     */
    private static String describe(Exception ex) {
        String base = GatewaySupport.describeCause(ex);
        StackTraceElement[] frames = ex.getStackTrace();
        return frames.length == 0 ? base : base + " at " + frames[0];
    }

    /** An agent, or why there is none. */
    private static final class Ensured {
        Agent agent;
        String problem;
    }

    /** Find or start the agent for an infobase. */
    private Ensured ensure(String infobase, String user, String password, String platformVersion) {
        Ensured e = new Ensured();
        e.agent = lookup(infobase);
        if (e.agent != null) {
            return e;
        }
        AgentResult started = start(infobase, user, password, platformVersion);
        if (!started.ok || started.agents.isEmpty()) {
            e.problem = started.message;
            return e;
        }
        e.agent = started.agents.get(0);
        return e;
    }

    private Agent lookup(String infobase) {
        if (infobase == null || infobase.isBlank()) {
            return null;
        }
        String connection = resolveAddress(infobase).address;
        if (connection == null) {
            return null;
        }
        Agent agent = AGENTS.get(connection);
        if (agent != null && (agent.process == null || !agent.process.isAlive())) {
            AGENTS.remove(connection);
            return null;
        }
        return agent;
    }

    /** An infobase address and how it was named. */
    private static final class Address {
        String address;
        String label;
        String problem;
    }

    /**
     * Resolve what the caller called an infobase: a name or uuid EDT knows, or an address written out
     * ({@code /Shost\base}, {@code host\base}, a file infobase directory). EDT's list is preferred -
     * it carries the human name - but it is not a gate: a base no project is bound to is absent from it
     * and still perfectly reachable.
     */
    private Address resolveAddress(String infobase) {
        Address a = new Address();
        if (infobase == null || infobase.isBlank()) {
            a.problem = "an infobase name, uuid or address is required";
            return a;
        }
        PlatformGateway.RegisteredInfobase reg = platform.findRegisteredInfobase(infobase);
        if (reg.found) {
            a.address = connectionString(reg);
            a.label = reg.name;
            if (a.address == null) {
                a.problem = "the designer cannot address this infobase (" + reg.kind + "): "
                        + (reg.problem == null ? "unsupported kind" : reg.problem);
            }
            return a;
        }
        a.address = io.github.keyfire.edtbridge.core.DesignerAddress.explicit(infobase);
        a.label = infobase.trim();
        if (a.address == null) {
            a.problem = reg.problem + ". An address also works instead of a name: "
                    + "\"host\\base\" for a server infobase, or the directory of a file one.";
        }
        return a;
    }

    /** How the designer addresses the infobase on its command line. */
    private static String connectionString(PlatformGateway.RegisteredInfobase reg) {
        return io.github.keyfire.edtbridge.core.DesignerAddress.of(
                reg.kind, reg.filePath, reg.server, reg.reference);
    }

    /** A full install carrying the configurator (the thick client executable). */
    private PlatformGateway.DiskPlatform findDesignerInstall(String platformVersion) {
        String line = PlatformGateway.platformLine(platformVersion);
        for (PlatformGateway.DiskPlatform dp : platform.discoverFullPlatforms(line)) {
            if (PlatformGateway.firstExisting(dp.binDir, "1cv8.exe", "1cv8") != null) {
                return dp;
            }
        }
        return null;
    }

    /** The first port nothing is listening on. A busy port kills the agent at startup. */
    private static int freePort() {
        for (int candidate = FIRST_PORT; candidate <= LAST_PORT; candidate++) {
            final int port = candidate;
            boolean taken = AGENTS.values().stream().anyMatch(a -> a.port == port);
            if (taken) {
                continue;
            }
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 200);
            } catch (IOException nothingThere) {
                return port;
            }
        }
        return -1;
    }

    /** Wait until the agent accepts connections, or until it dies trying. */
    private static boolean awaitPort(int port, Process process) {
        long deadline = System.currentTimeMillis() + START_TIMEOUT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                return false;
            }
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return true;
            } catch (IOException notYet) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * The agent reports its own startup failures to the {@code /Out} log and nowhere else - a busy
     * port, for instance, ends as "Фатальная ошибка SSH-сервера: Binding to ...". Written UTF-8 with
     * a byte-order mark.
     */
    private static String readAgentLog(Path log) {
        try {
            if (!Files.exists(log)) {
                return "";
            }
            String text = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            return text.replace("﻿", "").strip();
        } catch (IOException unreadable) {
            return "";
        }
    }

    private static com._1c.g5.designer.ssh.client.operation.IUpdateConfigurationStep.SessionTermination
            termination(String mode) {
        if ("force".equals(mode)) {
            return com._1c.g5.designer.ssh.client.operation.IUpdateConfigurationStep
                    .SessionTermination.FORCE;
        }
        if ("prompt".equals(mode)) {
            return com._1c.g5.designer.ssh.client.operation.IUpdateConfigurationStep
                    .SessionTermination.PROMPT;
        }
        return com._1c.g5.designer.ssh.client.operation.IUpdateConfigurationStep
                .SessionTermination.DISABLE;
    }

    private static Map<String, Object> describe(IExtensionProperties p) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", p.getName());
        m.put("version", p.getVersion());
        m.put("purpose", String.valueOf(p.getPurpose()));
        m.put("active", p.isActive());
        m.put("safeMode", p.isSafeMode());
        m.put("securityProfile", p.getSecurityProfile());
        m.put("unsafeActionProtection", p.isUnsafeActionProtected());
        m.put("usedInDistributedInfobase", p.isUsedInDistributedInfobase());
        m.put("scope", String.valueOf(p.getScope()));
        m.put("hashSum", p.getHashSum());
        return m;
    }

}
