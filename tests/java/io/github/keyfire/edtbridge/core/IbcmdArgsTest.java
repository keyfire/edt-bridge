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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Addressing an infobase on an ibcmd command line. */
class IbcmdArgsTest {

    @Test
    void fileInfobaseIsAddressedByPath() {
        IbcmdArgs.Target target = IbcmdArgs.target("D:/Bases/Demo", null, null, null, null, null);

        assertTrue(target.usable());
        assertEquals(List.of("--database-path=D:/Bases/Demo"), target.args);
        assertEquals("file: D:/Bases/Demo", target.label);
    }

    @Test
    @DisplayName("a DBMS-hosted infobase gets the full coordinate set")
    void serverInfobaseCoordinates() {
        IbcmdArgs.Target target = IbcmdArgs.target(
                null, "PostgreSQL", "db-host", "app_db", "app_user", "s3cret");

        assertTrue(target.usable());
        assertEquals(List.of(
                "--dbms=PostgreSQL",
                "--database-server=db-host",
                "--database-name=app_db",
                "--database-user=app_user",
                "--database-password=s3cret"), target.args);
        assertEquals("PostgreSQL: db-host/app_db", target.label);
    }

    @Test
    @DisplayName("the server is optional - a local DBMS instance needs no host")
    void serverIsOptional() {
        IbcmdArgs.Target target = IbcmdArgs.target(null, "PostgreSQL", null, "app_db", null, null);

        assertEquals(List.of("--dbms=PostgreSQL", "--database-name=app_db"), target.args);
        assertEquals("PostgreSQL: app_db", target.label);
    }

    @Test
    @DisplayName("a file path wins over coordinates rather than mixing the two")
    void filePathWins() {
        IbcmdArgs.Target target = IbcmdArgs.target(
                "D:/Bases/Demo", "PostgreSQL", "db-host", "app_db", null, null);

        assertEquals(List.of("--database-path=D:/Bases/Demo"), target.args);
    }

    @Test
    void surroundingSpaceIsTrimmed() {
        IbcmdArgs.Target target = IbcmdArgs.target(null, " PostgreSQL ", " db-host ", " app_db ",
                " app_user ", null);

        assertTrue(target.args.contains("--dbms=PostgreSQL"));
        assertTrue(target.args.contains("--database-name=app_db"));
        assertTrue(target.args.contains("--database-user=app_user"));
    }

    @Test
    @DisplayName("nothing addressable is a problem, not an empty command line")
    void nothingAddressable() {
        IbcmdArgs.Target target = IbcmdArgs.target(null, null, null, null, null, null);

        assertFalse(target.usable());
        assertTrue(target.args.isEmpty());
        assertTrue(target.problem.contains("databasePath"));
    }

    @Test
    @DisplayName("a DBMS without a database name is incomplete - ibcmd would fail obscurely")
    void dbmsWithoutDatabaseName() {
        assertFalse(IbcmdArgs.target(null, "PostgreSQL", "db-host", null, null, null).usable());
    }

    // -- the password must not leak --------------------------------------------------------------

    @Test
    void theLabelNeverCarriesThePassword() {
        IbcmdArgs.Target target = IbcmdArgs.target(
                null, "PostgreSQL", "db-host", "app_db", "app_user", "s3cret");

        assertFalse(target.label.contains("s3cret"));
    }

    @Test
    @DisplayName("redact hides the password and leaves everything else alone")
    void redactHidesOnlyThePassword() {
        List<String> args = IbcmdArgs.target(
                null, "PostgreSQL", "db-host", "app_db", "app_user", "s3cret").args;

        List<String> safe = IbcmdArgs.redact(args);

        assertEquals(args.size(), safe.size());
        assertFalse(String.join(" ", safe).contains("s3cret"));
        assertTrue(safe.contains("--database-password=" + IbcmdArgs.REDACTED));
        assertTrue(safe.contains("--database-name=app_db"));
    }

    @Test
    void redactToleratesNothingToRedact() {
        assertTrue(IbcmdArgs.redact(null).isEmpty());
        assertEquals(List.of("--database-path=D:/Bases/Demo"),
                IbcmdArgs.redact(List.of("--database-path=D:/Bases/Demo")));
    }

    // -- the two credential sets are not the same thing ------------------------------------------

    @Test
    @DisplayName("the 1C infobase user is a separate credential from the DBMS one")
    void infobaseCredentialsAreDistinct() {
        IbcmdArgs.Target target = IbcmdArgs.target(null, "PostgreSQL", "db-host", "app_db",
                "db_role", "db_secret", "Администратор", "ib_secret");

        assertEquals(List.of(
                "--dbms=PostgreSQL",
                "--database-server=db-host",
                "--database-name=app_db",
                "--database-user=db_role",
                "--database-password=db_secret",
                "--user=Администратор",
                "--password=ib_secret"), target.args);
    }

    @Test
    @DisplayName("an infobase user without a password is normal - many test bases have exactly that")
    void infobaseUserWithoutPassword() {
        IbcmdArgs.Target target = IbcmdArgs.target(null, "PostgreSQL", "db-host", "app_db",
                "db_role", null, "Администратор", null);

        assertTrue(target.args.contains("--user=Администратор"));
        assertFalse(String.join(" ", target.args).contains("--password="));
    }

    @Test
    void aFileInfobaseTakesInfobaseCredentialsToo() {
        IbcmdArgs.Target target = IbcmdArgs.target("D:/Bases/Demo", null, null, null, null, null,
                "Администратор", null);

        assertEquals(List.of("--database-path=D:/Bases/Demo", "--user=Администратор"), target.args);
    }

    @Test
    @DisplayName("the label names who we connect as, but never the passwords")
    void labelNamesTheUserNotTheSecrets() {
        IbcmdArgs.Target target = IbcmdArgs.target(null, "PostgreSQL", "db-host", "app_db",
                "db_role", "db_secret", "Администратор", "ib_secret");

        assertEquals("PostgreSQL: db-host/app_db as Администратор", target.label);
        assertFalse(target.label.contains("db_secret"));
        assertFalse(target.label.contains("ib_secret"));
    }

    @Test
    @DisplayName("redact covers BOTH passwords - the 1C one leaks just as easily")
    void redactCoversBothPasswords() {
        List<String> args = IbcmdArgs.target(null, "PostgreSQL", "db-host", "app_db",
                "db_role", "db_secret", "Администратор", "ib_secret").args;

        String safe = String.join(" ", IbcmdArgs.redact(args));

        assertFalse(safe.contains("db_secret"));
        assertFalse(safe.contains("ib_secret"));
        assertTrue(safe.contains("--password=" + IbcmdArgs.REDACTED));
        assertTrue(safe.contains("--database-password=" + IbcmdArgs.REDACTED));
        assertTrue(safe.contains("--user=Администратор"), "the user name is not a secret");
    }

    @Test
    void theSixArgumentFormStillWorks() {
        IbcmdArgs.Target target = IbcmdArgs.target("D:/Bases/Demo", null, null, null, null, null);
        assertEquals(List.of("--database-path=D:/Bases/Demo"), target.args);
    }

    @Test
    @DisplayName("dbArgs leaves the 1C credentials out - the extension mode rejects --user outright")
    void dbArgsExcludeInfobaseCredentials() {
        IbcmdArgs.Target target = IbcmdArgs.target(null, "PostgreSQL", "db-host", "app_db",
                "db_role", "db_secret", "Администратор", "ib_secret");

        assertTrue(target.hasInfobaseCredentials);
        assertTrue(String.join(" ", target.args).contains("--user="));
        assertFalse(String.join(" ", target.dbArgs).contains("--user="));
        assertFalse(String.join(" ", target.dbArgs).contains("--password="));
        assertTrue(target.dbArgs.contains("--database-user=db_role"));
    }

    @Test
    void withoutInfobaseCredentialsBothListsMatch() {
        IbcmdArgs.Target target = IbcmdArgs.target("D:/Bases/Demo", null, null, null, null, null);

        assertFalse(target.hasInfobaseCredentials);
        assertEquals(target.args, target.dbArgs);
    }
}
