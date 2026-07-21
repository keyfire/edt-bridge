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
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.github.keyfire.edtbridge.core.IbcmdArgs;

/**
 * Talking to an infobase through {@code ibcmd}, the platform's stand-alone management utility.
 *
 * <p>Why this exists next to the EDT model: `ibcmd` reaches a database DIRECTLY - by file path, or by
 * DBMS coordinates, which works for a clustered infobase without going through the cluster. That is
 * the only way the bridge can answer questions about a running infobase, since EDT's own
 * synchronization needs an infobase session it cannot always open.
 *
 * <p>Deliberately narrow: this class launches ibcmd and reads what it says. Building the command line
 * lives in {@link IbcmdArgs} (pure, unit-tested), and finding the install stays with
 * {@link PlatformGateway}, which already knows what is on disk.
 */
public final class IbcmdGateway {

    /** Seconds any single ibcmd invocation may take before it is killed. */
    private static final int TIMEOUT_SECONDS = 600;

    private final PlatformGateway platform = new PlatformGateway();

    /** The resolved utility: its path and the install version, or why it could not be found. */
    public static final class Tool {
        public Path exe;
        public String version;
        public String problem;
    }

    /** One ibcmd invocation. */
    public static final class Run {
        public boolean ok;
        public int exitCode = -1;
        public String output = "";
        public String error;          // null when ok
    }

    /** Whether the infobase runs the configuration the caller thinks it runs. */
    public static final class ConfigStateResult {
        public boolean ok;
        public String infobase;                 // label, never carries the password
        public String platform;                 // ibcmd install used
        public Boolean databaseConfigMatches;   // null when it could not be established
        public String mainConfigHash;
        public String databaseConfigHash;
        public String plan;
        public String message;
    }

    /**
     * Find an on-disk full install carrying ibcmd, preferring the line of {@code platformVersion}
     * (ibcmd ships in 8.5.1.1302 but is absent from 8.5.1.1317, so the newest install is not always
     * the right one).
     */
    public Tool resolve(String platformVersion) {
        Tool tool = new Tool();
        PlatformGateway.DiskPlatform install =
                platform.findIbcmdInstall(PlatformGateway.platformLine(platformVersion));
        if (install == null) {
            tool.problem = "no on-disk full install carrying ibcmd was found"
                    + (platformVersion == null ? "" : " for version line " + platformVersion)
                    + " - a full 1C:Enterprise install (with ibcmd) is required.";
            return tool;
        }
        tool.exe = PlatformGateway.firstExisting(install.binDir, "ibcmd.exe", "ibcmd");
        tool.version = install.version;
        if (tool.exe == null) {
            tool.problem = "the install at " + install.binDir + " reports ibcmd but has no executable";
        }
        return tool;
    }

    /** Run ibcmd once, capturing its combined output. Never throws; failures land in {@link Run#error}. */
    public static Run run(Path exe, List<String> args) {
        Run r = new Run();
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        cmd.addAll(args);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            byte[] raw;
            try (InputStream in = proc.getInputStream()) {
                raw = in.readAllBytes();
            }
            if (!proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                r.error = "ibcmd timed out after " + TIMEOUT_SECONDS + "s: " + describe(args);
                return r;
            }
            r.output = decode(raw);
            r.exitCode = proc.exitValue();
            r.ok = r.exitCode == 0 && !looksLikeFailure(r.output);
            if (!r.ok) {
                r.error = describe(args) + (r.exitCode == 0 ? "" : " exit " + r.exitCode)
                        + ": " + tail(r.output);
            }
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            r.error = ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
        }
        return r;
    }

    /**
     * Whether the infobase's DATABASE configuration matches its main one - the question
     * {@code equality: EQUAL} could not answer.
     *
     * <p>In 1C the code a session executes is the database configuration, a separate thing from the
     * configuration being edited. They diverge whenever changes are loaded but not applied, and every
     * running session keeps serving the old code until they are. Established by dumping both
     * ({@code config save} and {@code config save --db}) and comparing them: there is no cheaper
     * handle, since {@code --db} exists only on {@code save}, and {@code config export info|status}
     * work against an XML dump directory rather than a configuration file.
     *
     * @param target          the infobase, already addressed
     * @param platformVersion version line to prefer when picking the ibcmd install (optional)
     */
    public ConfigStateResult configState(IbcmdArgs.Target target, String platformVersion) {
        ConfigStateResult r = new ConfigStateResult();
        if (target == null || !target.usable()) {
            r.message = (target == null) ? "no infobase given" : target.problem;
            return r;
        }
        r.infobase = target.label;
        Tool tool = resolve(platformVersion);
        if (tool.problem != null) {
            r.message = tool.problem;
            return r;
        }
        r.platform = tool.version;
        r.plan = "Dump the main and the database configuration of " + target.label
                + " with ibcmd " + tool.version + " and compare them";

        Path work = null;
        try {
            work = Files.createTempDirectory("edtbridge-configstate-");
            Path mainCf = work.resolve("main.cf");
            Path dbCf = work.resolve("db.cf");

            List<String> save = new ArrayList<>(List.of("config", "save"));
            save.addAll(target.args);
            save.add(mainCf.toString());
            Run mainRun = run(tool.exe, save);
            if (!mainRun.ok) {
                r.message = "could not dump the main configuration: " + mainRun.error;
                return r;
            }

            List<String> saveDb = new ArrayList<>(List.of("config", "save", "--db"));
            saveDb.addAll(target.args);
            saveDb.add(dbCf.toString());
            Run dbRun = run(tool.exe, saveDb);
            if (!dbRun.ok) {
                r.message = "could not dump the database configuration: " + dbRun.error;
                return r;
            }

            r.mainConfigHash = sha256(mainCf);
            r.databaseConfigHash = sha256(dbCf);
            if (r.mainConfigHash == null || r.databaseConfigHash == null) {
                r.message = "the configurations were dumped but could not be read back for comparison";
                return r;
            }
            r.databaseConfigMatches = r.mainConfigHash.equals(r.databaseConfigHash);
            r.ok = true;
            r.message = Boolean.TRUE.equals(r.databaseConfigMatches)
                    ? "the database configuration matches the main one - sessions run this code"
                    : "the database configuration DIFFERS from the main one - the changes are loaded "
                      + "but not applied, so every running session still executes the previous code "
                      + "(apply them with config apply / from the Designer)";
        } catch (IOException ex) {
            r.message = "could not prepare a working directory: " + GatewaySupport.describeCause(ex);
        } finally {
            deleteRecursively(work);
        }
        return r;
    }

    /**
     * ibcmd writes command output in UTF-8 but its built-in help in the OEM code page; decode strictly
     * as UTF-8 and fall back to CP866 so a stray message cannot turn Cyrillic into noise.
     */
    public static String decode(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(raw, Charset.forName("IBM866"));
        }
    }

    /** SHA-256 of a file as hex, or null when it cannot be read. */
    static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] sum = digest.digest(Files.readAllBytes(file));
            StringBuilder hex = new StringBuilder(sum.length * 2);
            for (byte b : sum) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException ex) {
            return null;
        }
    }

    /**
     * ibcmd can report a failure while still exiting 0 - it prints an [ERROR] line instead. Treating
     * exit code alone as success is how a broken call gets read as a good one.
     */
    private static boolean looksLikeFailure(String output) {
        return output != null && output.contains("[ERROR]");
    }

    /** The command being run, for messages - the arguments never include the password. */
    private static String describe(List<String> args) {
        List<String> safe = IbcmdArgs.redact(args);
        return safe.stream().filter(a -> !a.startsWith("--")).findFirst().map(first ->
                "ibcmd " + String.join(" ", safe.subList(0, Math.min(2, safe.size()))))
                .orElse("ibcmd");
    }

    private static String tail(String output) {
        if (output == null || output.isBlank()) {
            return "(no output)";
        }
        String trimmed = output.strip();
        return trimmed.length() > 2000 ? trimmed.substring(trimmed.length() - 2000) : trimmed;
    }

    /** Remove a throwaway directory tree, best effort. */
    static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignore) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignore) {
            // best-effort cleanup
        }
    }
}
