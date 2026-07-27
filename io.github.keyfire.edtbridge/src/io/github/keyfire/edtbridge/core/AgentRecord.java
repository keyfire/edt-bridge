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

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * What a running configurator agent leaves on disk so that a LATER bridge process can recognise its
 * remains.
 *
 * <p>The problem this solves: when a call dies - an exception, a dropped MCP connection, a killed
 * process - the agent goes with it, but its Designer session stays in the cluster and keeps the
 * infobase's CONFIGURATION LOCK. The next call then fails with "Ошибка блокировки информационной базы
 * для конфигурирования", and the only cure is to find that session by hand and terminate it.
 *
 * <p>Recognising the remains cannot be done by guessing. A Designer session carries the client host,
 * the infobase user and the start time - and nothing that says which program opened it. On a
 * developer machine EDT itself keeps agents of its own for the infobases its projects are bound to
 * (they differ only in their command line), so "a Designer session from this host under this user" is
 * NOT a proof of ownership: acting on that heuristic would end the developer's own configurator.
 *
 * <p>Hence this record. The agent writes it into its own {@code /AgentBaseDir} - a temporary directory
 * that a clean stop removes and a crash leaves behind, which makes the directory itself the trace -
 * and stores the id of the cluster session it opened. A sweep then terminates exactly that session,
 * and only when the process that owned it is gone.
 *
 * <p><b>No secrets here.</b> The record deliberately has no password field: it lives in a temporary
 * directory that outlives the process, and an infobase password written there would outlive it too.
 *
 * <p>Plain {@code Properties} rather than JSON on purpose - this package carries no dependencies, so
 * it compiles and is tested without EDT.
 */
public final class AgentRecord {

    /** File name inside the agent's base directory. */
    public static final String FILE_NAME = "edt-bridge-agent.properties";

    /** Prefix of the temporary base directories agents are given ({@code Files.createTempDirectory}). */
    public static final String BASE_DIR_PREFIX = "edtbridge-agent-";

    public String connectionString;   // /S<host>\<reference> or /F<path>
    public String label;              // how the caller named the infobase
    public int port;
    public long pid = -1;
    public String user = "";
    public String platformVersion;
    public long startedAtMillis;
    public String host;               // this machine, as the cluster reports a client host
    public String clusterSessionId;   // the Designer session this agent opened; null until known

    /** The cluster host, or null for a file infobase. */
    public String clusterServer() {
        return DesignerAddress.serverHost(connectionString);
    }

    /** The infobase name inside the cluster, or null for a file infobase. */
    public String clusterInfobase() {
        return DesignerAddress.serverReference(connectionString);
    }

    /** Only a server infobase has a cluster session worth sweeping. */
    public boolean isServerInfobase() {
        return DesignerAddress.isServer(connectionString);
    }

    public Properties toProperties() {
        Properties p = new Properties();
        put(p, "connectionString", connectionString);
        put(p, "label", label);
        p.setProperty("port", Integer.toString(port));
        p.setProperty("pid", Long.toString(pid));
        put(p, "user", user);
        put(p, "platformVersion", platformVersion);
        p.setProperty("startedAtMillis", Long.toString(startedAtMillis));
        put(p, "host", host);
        put(p, "clusterSessionId", clusterSessionId);
        return p;
    }

    public static AgentRecord fromProperties(Properties p) {
        AgentRecord r = new AgentRecord();
        r.connectionString = trimmedOrNull(p.getProperty("connectionString"));
        r.label = trimmedOrNull(p.getProperty("label"));
        r.port = (int) number(p.getProperty("port"), 0);
        r.pid = number(p.getProperty("pid"), -1);
        String user = p.getProperty("user");
        r.user = user == null ? "" : user;
        r.platformVersion = trimmedOrNull(p.getProperty("platformVersion"));
        r.startedAtMillis = number(p.getProperty("startedAtMillis"), 0);
        r.host = trimmedOrNull(p.getProperty("host"));
        r.clusterSessionId = trimmedOrNull(p.getProperty("clusterSessionId"));
        return r;
    }

    /**
     * Write the record into the agent's base directory. Written whole every time - the session id
     * is only learned after the infobase connection opens, which is later than the start.
     */
    public void write(Path baseDir) throws IOException {
        try (Writer w = Files.newBufferedWriter(baseDir.resolve(FILE_NAME), StandardCharsets.UTF_8)) {
            toProperties().store(w, "edt-bridge configurator agent - no credentials are stored here");
        }
    }

    /** Read a record, or null when the file is absent or unreadable. */
    public static AgentRecord read(Path baseDir) {
        Path file = baseDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException | IllegalArgumentException unreadable) {
            return null;
        }
        AgentRecord record = fromProperties(p);
        return record.connectionString == null ? null : record;
    }

    private static void put(Properties p, String key, String value) {
        if (value != null) {
            p.setProperty(key, value);
        }
    }

    private static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long number(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }
}
