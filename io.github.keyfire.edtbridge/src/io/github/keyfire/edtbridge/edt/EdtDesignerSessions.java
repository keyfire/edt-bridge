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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.core.runtimes.IRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com._1c.g5.wiring.ServiceAccess;
import com._1c.g5.wiring.ServiceProperties;

/**
 * The configurator sessions EDT itself keeps for its infobases, and the EDT way to close one.
 *
 * <p>EDT starts a configurator in /AgentMode for an infobase its projects are bound to and keeps the
 * connection in its own designer session pool. Such an agent holds the infobase's configuration lock
 * like any other, but it is not one of the bridge's agents, so the bridge's registry cannot see it.
 * The pool has no public API: it is read by reflection, without changing anything, and a session is
 * closed through the same pool call EDT uses when it closes the session itself.
 */
final class EdtDesignerSessions {
    private EdtDesignerSessions() { }

    private static final String CORE_BUNDLE = "com._1c.g5.v8.dt.platform.services.core";
    private static final String CORE_PLUGIN = "com._1c.g5.v8.dt.internal.platform.services.core.PlatformServicesCore";
    private static final String POOL = "com._1c.g5.v8.dt.internal.platform.services.core.runtimes.execution.DesignerSessionPool";
    private static final String SESSION_KEY = "com._1c.g5.v8.dt.internal.platform.services.core.runtimes.execution.DesignerSessionKey";
    private static final String RT_ENTERPRISE_PLATFORM = "com._1c.g5.v8.dt.platform.services.core.runtimeType.EnterprisePlatform";

    /** One session of the pool: the infobase it serves and the agent behind it. */
    static final class Session {
        String infobaseUuid;
        String infobase;
        String connectionString;
        List<String> projects = new ArrayList<>();
        String platformVersion;
        Long pid;
        boolean processAlive;
        boolean connectionAlive;
        boolean launchedByEdt;
        String host;
        Integer port;
        String baseDir;
        /** live, dead (the agent process is gone) or external (EDT connected to an agent it did not start). */
        String state;
        String stop;
        transient Object key;

        Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("origin", "edt");
            m.put("infobase", infobase);
            m.put("infobaseUuid", infobaseUuid);
            m.put("connectionString", connectionString);
            m.put("projects", String.join(", ", projects));
            m.put("platform", platformVersion);
            m.put("pid", pid);
            m.put("state", state);
            m.put("launchedByEdt", launchedByEdt);
            m.put("host", host);
            m.put("port", port);
            m.put("baseDir", baseDir);
            m.put("stop", stop);
            return m;
        }
    }

    /** The pool's sessions, or an explanation of why they cannot be read on this EDT. */
    static final class Inventory {
        final List<Session> sessions = new ArrayList<>();
        String problem;
    }

    static Inventory inventory() {
        Inventory inventory = new Inventory();
        try {
            Object pool = pool();
            Map<?, ?> cached = snapshot(pool);
            IInfobaseManager infobases = ServiceAccess.get(IInfobaseManager.class);
            IInfobaseAssociationManager associations = ServiceAccess.get(IInfobaseAssociationManager.class);
            for (Map.Entry<?, ?> entry : cached.entrySet()) {
                inventory.sessions.add(describe(entry.getKey(), entry.getValue(), infobases, associations));
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            inventory.problem = "EDT's designer sessions cannot be read on this EDT build: " + cause(failure);
        }
        return inventory;
    }

    /**
     * Close EDT's session for one registered infobase. EDT sends its own agent the shutdown command
     * and removes the session; an agent EDT did not start is only disconnected and keeps running.
     */
    static String release(InfobaseReference infobase) throws ReflectiveOperationException {
        Object pool = pool();
        Map<?, ?> cached = snapshot(pool);
        Class<?> keyType = pool.getClass().getClassLoader().loadClass(SESSION_KEY);
        Method isOf = keyType.getMethod("isOf", InfobaseReference.class);
        Method keyOf = keyType.getMethod("of", RuntimeInstallation.class, InfobaseReference.class);
        IRuntimeInstallationManager installations = ServiceAccess.get(IRuntimeInstallationManager.class,
                ServiceProperties.named(RT_ENTERPRISE_PLATFORM));
        if (installations == null) throw new IllegalStateException("the EDT platform installation manager is unavailable");
        Method release = pool.getClass().getMethod("release", RuntimeInstallation.class, InfobaseReference.class,
                boolean.class, boolean.class);
        List<String> done = new ArrayList<>();
        for (Map.Entry<?, ?> entry : cached.entrySet()) {
            if (!Boolean.TRUE.equals(isOf.invoke(entry.getKey(), infobase))) continue;
            RuntimeInstallation installation = null;
            for (RuntimeInstallation candidate : installations.getAllRegistered()) {
                if (entry.getKey().equals(keyOf.invoke(null, candidate, infobase))) { installation = candidate; break; }
            }
            if (installation == null) {
                throw new IllegalStateException("no registered platform installation matches EDT's session "
                        + entry.getKey() + "; close it in EDT");
            }
            boolean launched = Boolean.TRUE.equals(field(entry.getValue(), "isLaunchedByIde"));
            Long pid = pid(entry.getValue());
            // The same call as IDesignerSessionThickClientLauncher.closeDesignerSession: shut the agent down
            // and keep EDT's temporary directory handling as EDT does it.
            release.invoke(pool, installation, infobase, true, false);
            done.add(launched ? "EDT shut down its agent" + (pid == null ? "" : " (pid " + pid + ")")
                    : "EDT disconnected from an agent it did not start" + (pid == null ? "" : " (pid " + pid + ")")
                            + "; that process keeps running");
        }
        return done.isEmpty() ? null : String.join("; ", done);
    }

    /** A registered infobase by name or uuid, as edt_infobases lists them, or null. */
    static InfobaseReference findInfobase(String nameOrUuid) {
        IInfobaseManager manager = ServiceAccess.get(IInfobaseManager.class);
        if (manager == null || nameOrUuid == null || nameOrUuid.isBlank()) return null;
        String needle = nameOrUuid.trim();
        for (InfobaseReference reference : InfobaseReferences.asPlainList(manager.getAll())) {
            if (needle.equalsIgnoreCase(reference.getName()) || needle.equalsIgnoreCase(String.valueOf(reference.getUuid()))) {
                return reference;
            }
        }
        return null;
    }

    private static Session describe(Object key, Object connection, IInfobaseManager infobases,
            IInfobaseAssociationManager associations) throws ReflectiveOperationException {
        Session s = new Session();
        s.key = key;
        Object uuid = field(key, "infobaseUuid");
        s.infobaseUuid = uuid == null ? null : uuid.toString();
        s.platformVersion = (String) field(key, "installationVersionWithBuild");
        s.launchedByEdt = Boolean.TRUE.equals(field(connection, "isLaunchedByIde"));
        s.host = (String) field(connection, "hostname");
        Object port = field(connection, "port");
        s.port = port instanceof Integer number && number > 0 ? number : null;
        Object base = field(connection, "agentBaseDirectory");
        s.baseDir = base == null ? null : base.toString();
        Process process = (Process) field(connection, "agentProcess");
        s.pid = pid(connection);
        s.processAlive = process != null && process.isAlive();
        s.connectionAlive = Boolean.TRUE.equals(connection.getClass().getMethod("isAlive").invoke(connection));
        InfobaseReference reference = infobases == null || uuid == null ? null
                : infobases.findInfobaseByUuid((UUID) uuid).orElse(null);
        if (reference != null) {
            s.infobase = reference.getName();
            s.connectionString = String.valueOf(reference.getConnectionString());
            if (associations != null) {
                try {
                    associations.getAssociation(reference)
                            .ifPresent(association -> s.projects.add(association.getProject().getName()));
                } catch (Exception unknown) {
                    // A broken association does not hide the session itself.
                }
            }
        }
        s.state = s.launchedByEdt ? (s.processAlive ? "live" : "dead") : "external";
        String name = s.infobase != null ? s.infobase : s.infobaseUuid;
        s.stop = "edt_designer_agent action=stop-edt infobase=" + name;
        return s;
    }

    private static Long pid(Object connection) throws ReflectiveOperationException {
        Process process = (Process) field(connection, "agentProcess");
        if (process == null) return null;
        try {
            return process.pid();
        } catch (UnsupportedOperationException unknown) {
            return null;
        }
    }

    private static Object pool() throws ReflectiveOperationException {
        Bundle bundle = Platform.getBundle(CORE_BUNDLE);
        if (bundle == null) throw new IllegalStateException("bundle " + CORE_BUNDLE + " is not installed");
        Class<?> plugin = bundle.loadClass(CORE_PLUGIN);
        Object core = plugin.getMethod("getDefault").invoke(null);
        if (core == null) throw new IllegalStateException("EDT platform services are not started");
        Object injector = field(core, "injector");
        if (injector == null) throw new IllegalStateException("EDT platform services have no injector yet");
        Class<?> poolType = bundle.loadClass(POOL);
        ClassLoader guice = injector.getClass().getClassLoader();
        Class<?> keyType = Class.forName("com.google.inject.Key", true, guice);
        Object key = keyType.getMethod("get", Class.class).invoke(null, poolType);
        Class<?> injectorType = Class.forName("com.google.inject.Injector", true, guice);
        // An existing binding only: asking the injector for an unbound type would build a second pool.
        Object binding = injectorType.getMethod("getExistingBinding", keyType).invoke(injector, key);
        if (binding == null) throw new IllegalStateException("EDT has no designer session pool binding");
        Object provider = binding.getClass().getMethod("getProvider").invoke(binding);
        Method get = provider.getClass().getMethod("get");
        get.setAccessible(true);
        return get.invoke(provider);
    }

    private static Map<?, ?> snapshot(Object pool) throws ReflectiveOperationException {
        Map<?, ?> cached = (Map<?, ?>) field(pool, "cachedConnections");
        if (cached == null) return Map.of();
        // The pool guards the map with its own monitor while it connects, which can take minutes; a copy
        // is taken without waiting for it and retried when a concurrent change interrupts it.
        for (int attempt = 0; ; attempt++) {
            try {
                return new LinkedHashMap<>(cached);
            } catch (ConcurrentModificationException changed) {
                if (attempt >= 4) throw changed;
            }
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException next) {
                // Look in the superclass.
            }
        }
        throw new NoSuchFieldException(owner.getClass().getName() + "." + name);
    }

    private static String cause(Throwable failure) {
        while (failure.getCause() != null && failure.getCause() != failure) failure = failure.getCause();
        return failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
    }
}
