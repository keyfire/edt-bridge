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

    /** Hard cap on captured output. ibcmd can emit hundreds of megabytes of a repeated prompt. */
    private static final int MAX_OUTPUT_BYTES = 1 << 20;

    /**
     * Run ibcmd once, capturing its combined output. Never throws; failures land in {@link Run#error}.
     *
     * <p>The output is read incrementally rather than with {@code readAllBytes}, and that is not a
     * refinement - it is the difference between an error and a hang. When an infobase authenticates
     * its users and no {@code --user} was given, ibcmd asks for a name and KEEPS ASKING: it ignores a
     * closed stdin and reprints the prompt forever (measured: 134 MB in 60 seconds). Waiting for the
     * stream to end therefore never returns. So the loop watches for that prompt and for a runaway
     * output size, and kills the process the moment either shows up.
     */
    public static Run run(Path exe, List<String> args) {
        Run r = new Run();
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        cmd.addAll(args);
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            // Nothing will answer a prompt here, so do not pretend otherwise. (ibcmd asks anyway -
            // see above - but an inherited stdin would be worse still.)
            pb.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
            proc = pb.start();

            java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
            long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000L;
            String abort = null;
            byte[] chunk = new byte[8192];
            try (InputStream in = proc.getInputStream()) {
                int read;
                while ((read = in.read(chunk)) >= 0) {
                    captured.write(chunk, 0, read);
                    if (needsInfobaseUser(decode(captured.toByteArray()))) {
                        abort = "the infobase requires authentication - pass infobaseUser (and "
                                + "infobasePassword). Note this is the 1C user, not the DBMS one.";
                        break;
                    }
                    if (captured.size() > MAX_OUTPUT_BYTES) {
                        abort = "ibcmd produced more than " + MAX_OUTPUT_BYTES
                                + " bytes of output and was stopped";
                        break;
                    }
                    if (System.currentTimeMillis() > deadline) {
                        abort = "ibcmd timed out after " + TIMEOUT_SECONDS + "s";
                        break;
                    }
                }
            }
            r.output = decode(captured.toByteArray());
            if (abort != null) {
                proc.destroyForcibly();
                proc.waitFor(10, TimeUnit.SECONDS);
                r.error = describe(args) + ": " + abort;
                return r;
            }
            if (!proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                r.error = "ibcmd timed out after " + TIMEOUT_SECONDS + "s: " + describe(args);
                return r;
            }
            r.exitCode = proc.exitValue();
            r.ok = r.exitCode == 0 && !looksLikeFailure(r.output);
            if (!r.ok) {
                r.error = describe(args) + (r.exitCode == 0 ? "" : " exit " + r.exitCode)
                        + ": " + tail(r.output);
            }
        } catch (IOException | InterruptedException ex) {
            if (proc != null) {
                proc.destroyForcibly();
            }
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            r.error = ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
        }
        return r;
    }

    /** Outcome of {@link #dumpInfobase}. */
    public static final class DumpResult {
        public boolean ok;
        public boolean applied;
        public String infobase;
        public String platform;
        public String outputPath;
        public long sizeBytes = -1;
        public String plan;
        public String message;
    }

    /**
     * Dump an infobase to a {@code .dt} file - the backup that belongs BEFORE any configuration
     * apply, and which the bridge had no way to take.
     *
     * <p>Dry-run by default: resolves ibcmd, checks the destination is writable and does not already
     * hold a file, and reports the plan. A dump reads everything in the base, so it is token-gated
     * like the write tools even though it changes nothing in the infobase itself.
     */
    public DumpResult dumpInfobase(IbcmdArgs.Target target, String outputPath, String platformVersion,
            boolean apply) {
        DumpResult r = new DumpResult();
        if (target == null || !target.usable()) {
            r.message = (target == null) ? "no infobase given" : target.problem;
            return r;
        }
        if (outputPath == null || outputPath.isBlank()) {
            r.message = "outputPath is required - the .dt file to write";
            return r;
        }
        r.infobase = target.label;
        r.outputPath = outputPath.trim();
        Tool tool = resolve(platformVersion);
        if (tool.problem != null) {
            r.message = tool.problem;
            return r;
        }
        r.platform = tool.version;

        Path out = Path.of(r.outputPath);
        Path parent = out.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            r.message = "the destination directory does not exist: " + parent;
            return r;
        }
        if (Files.exists(out)) {
            r.message = "refusing to overwrite an existing file: " + out;
            return r;
        }
        r.ok = true;
        r.plan = "Dump " + target.label + " to " + out + " with ibcmd " + tool.version;
        if (!apply) {
            r.message = "dry-run: nothing written. Re-call with apply=true to take the dump.";
            return r;
        }

        Path work = null;
        Run run;
        try {
            // Own working directory per run: without --data they all lock the same one.
            work = Files.createTempDirectory("edtbridge-dump-");
            List<String> dump = new ArrayList<>(List.of("infobase", "dump",
                    "--data=" + Files.createDirectories(work.resolve("data"))));
            dump.addAll(target.args);
            dump.add(out.toString());
            run = run(tool.exe, dump);
        } catch (IOException ex) {
            r.ok = false;
            r.message = "could not prepare a working directory: " + GatewaySupport.describeCause(ex);
            return r;
        } finally {
            deleteRecursively(work);
        }
        if (!run.ok) {
            r.ok = false;
            r.message = "dump failed: " + run.error;
            return r;
        }
        r.applied = true;
        try {
            r.sizeBytes = Files.size(out);
        } catch (IOException ignored) {
            r.sizeBytes = -1;
        }
        r.message = "written: " + out + (r.sizeBytes >= 0 ? " (" + r.sizeBytes + " bytes)" : "");
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

    /**
     * ibcmd can report a failure while still exiting 0 - it prints an [ERROR] line instead. Treating
     * exit code alone as success is how a broken call gets read as a good one.
     */
    private static boolean looksLikeFailure(String output) {
        return output != null && (output.contains("[ERROR]") || needsInfobaseUser(output));
    }

    /**
     * ibcmd asking who we are: the infobase authenticates its users and no {@code --user} was given.
     * Recognised in both languages the utility speaks, since the prompt itself is what it emits after
     * the message - and with stdin at EOF the two arrive together.
     */
    private static boolean needsInfobaseUser(String output) {
        if (output == null) {
            return false;
        }
        return output.contains("требуется аутентификация в информационной базе")
                || output.contains("Имя пользователя:")
                || output.contains("authentication is required")
                || output.contains("User name:");
    }

    /** The platform's bit bucket, so a child process reading stdin gets EOF instead of waiting. */
    private static java.io.File nullDevice() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return new java.io.File(windows ? "NUL" : "/dev/null");
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
