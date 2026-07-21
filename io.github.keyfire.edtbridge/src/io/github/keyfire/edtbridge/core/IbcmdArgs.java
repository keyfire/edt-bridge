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
import java.util.Collections;
import java.util.List;

/**
 * How an infobase is addressed on an {@code ibcmd} command line.
 *
 * <p>Two ways in: a file infobase by path, or a DBMS-hosted one by coordinates. The second reaches a
 * CLUSTERED infobase without going through the cluster, which is what makes ibcmd usable for the
 * server-side work at all.
 *
 * <p>Pure argument building, no process launching and no EDT types - so it is unit-tested without the
 * SDK. The password deserves the care: it travels in an argument, so {@link #redact} exists and every
 * human-readable label is built without it.
 */
public final class IbcmdArgs {

    /** Placeholder shown instead of a password in anything a caller may see or log. */
    public static final String REDACTED = "***";

    private IbcmdArgs() {
    }

    /** A resolved infobase address: the arguments to pass, a label to show, or the reason it is unusable. */
    public static final class Target {
        public final List<String> args;
        public final String label;
        public final String problem;

        private Target(List<String> args, String label, String problem) {
            this.args = Collections.unmodifiableList(args);
            this.label = label;
            this.problem = problem;
        }

        public boolean usable() {
            return problem == null;
        }
    }

    /**
     * Build the connection arguments for an infobase.
     *
     * @param databasePath file infobase directory; wins when given
     * @param dbms         DBMS kind (MSSQLServer / PostgreSQL / IBMDB2 / OracleDatabase)
     * @param dbServer     DBMS host (optional for a local instance)
     * @param dbName       database name
     * @param dbUser       database user (optional)
     * @param dbPassword   database password (optional); never appears in {@code label}
     */
    public static Target target(String databasePath, String dbms, String dbServer, String dbName,
            String dbUser, String dbPassword) {
        List<String> args = new ArrayList<>();
        if (notBlank(databasePath)) {
            args.add("--database-path=" + databasePath.trim());
            return new Target(args, "file: " + databasePath.trim(), null);
        }
        if (notBlank(dbms) && notBlank(dbName)) {
            args.add("--dbms=" + dbms.trim());
            if (notBlank(dbServer)) {
                args.add("--database-server=" + dbServer.trim());
            }
            args.add("--database-name=" + dbName.trim());
            if (notBlank(dbUser)) {
                args.add("--database-user=" + dbUser.trim());
            }
            if (notBlank(dbPassword)) {
                args.add("--database-password=" + dbPassword);
            }
            String label = dbms.trim() + ": " + (notBlank(dbServer) ? dbServer.trim() + "/" : "")
                    + dbName.trim();
            return new Target(args, label, null);
        }
        return new Target(new ArrayList<>(), null,
                "the infobase is required: pass databasePath for a file infobase, or dbms + dbName"
                + " (+ dbServer / dbUser / dbPassword) for a DBMS-hosted one");
    }

    /**
     * The same command line with any password replaced by {@link #REDACTED}, for messages, plans and
     * logs. A tool that echoes what it ran must echo this one.
     */
    public static List<String> redact(List<String> args) {
        List<String> safe = new ArrayList<>();
        if (args == null) {
            return safe;
        }
        for (String arg : args) {
            safe.add(arg != null && arg.startsWith("--database-password=")
                    ? "--database-password=" + REDACTED : arg);
        }
        return safe;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
