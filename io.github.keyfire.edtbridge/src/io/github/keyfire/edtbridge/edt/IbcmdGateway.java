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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.keyfire.edtbridge.core.IbcmdArgs;
import io.github.keyfire.edtbridge.core.PlatformProcess;
import io.github.keyfire.edtbridge.core.PlatformSelection;
import io.github.keyfire.edtbridge.core.TreeRemoval;

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
     * Find an on-disk full install carrying ibcmd for {@code platformVersion} - four digits pin that
     * build, fewer prefer its line (ibcmd ships in 8.5.1.1302 but is absent from 8.5.1.1317, so the
     * newest install is not always the right one, and the pin is how a caller says which is).
     */
    public Tool resolve(String platformVersion) {
        Tool tool = new Tool();
        tool.problem = PlatformSelection.problem(platformVersion);
        if (tool.problem != null) {
            return tool;
        }
        // One walk of the install roots for both the pick and the list a refusal quotes.
        List<PlatformGateway.DiskPlatform> scanned = platform.scanFullPlatforms();
        PlatformGateway.DiskPlatform install =
                PlatformGateway.findIbcmdInstall(scanned, platformVersion);
        if (install == null) {
            tool.problem = PlatformSelection.unavailable("full install carrying ibcmd", platformVersion,
                            PlatformGateway.versionsCarrying(scanned, "ibcmd.exe", "ibcmd"))
                    + " A full 1C:Enterprise install (with ibcmd) is required.";
            return tool;
        }
        tool.exe = PlatformGateway.firstExisting(install.binDir, "ibcmd.exe", "ibcmd");
        tool.version = install.version;
        if (tool.exe == null) {
            tool.problem = "the install at " + install.binDir + " reports ibcmd but has no executable";
        }
        return tool;
    }

    /** Run a platform utility with bounded output and no interactive input. */
    public static Run run(Path exe, List<String> args) {
        Run r = new Run();
        Duration timeout = Duration.ofSeconds(TIMEOUT_SECONDS);
        var run = PlatformProcess.run(exe, args, null, timeout, bytes -> needsInfobaseUser(decode(bytes)));
        r.output = decode(run.output());
        r.exitCode = run.exitCode() == null ? -1 : run.exitCode();
        r.ok = run.end() == PlatformProcess.End.EXITED && r.exitCode == 0 && !looksLikeFailure(r.output);
        if (r.ok) {
            return r;
        }
        r.error = run.problem(exe, stage(exe, args), timeout);
        if (run.end() == PlatformProcess.End.STOP_REQUESTED) {
            r.error += ": the infobase requires authentication - pass infobaseUser (and "
                    + "infobasePassword). This is the 1C user, not the DBMS one.";
        } else if (run.end() == PlatformProcess.End.EXITED) {
            r.error += ": " + tail(IbcmdArgs.safeOutput(r.output, args));
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
            // The file was not there before (an existing one is refused above), so whatever lies there
            // now is this run's unfinished dump. Left in place it would pass for a backup, and the next
            // call with the same outputPath would refuse to overwrite it.
            try {
                if (Files.deleteIfExists(out)) {
                    r.message += "; the unfinished file was removed";
                }
            } catch (IOException | RuntimeException notRemoved) {
                r.message += "; the unfinished file could not be removed: " + out;
            }
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
        // The English string is the platform's own, read out of ibcli_root.res: "Authentication in
        // the infobase is required to perform the operation" - note the words between
        // "Authentication" and "is required", which a shorter guess here used to miss.
        return output.contains("требуется аутентификация в информационной базе")
                || output.contains("Имя пользователя:")
                || output.contains("Authentication in the infobase is required")
                || output.contains("User name:");
    }

    private static final Set<String> MODES = Set.of("config", "infobase", "extension", "cluster",
            "session", "lock");
    private static final Set<String> OPERATIONS = Set.of("create", "dump", "import", "save", "list",
            "info", "update", "terminate", "summary");
    private static final List<String> DESIGNER_OPERATIONS = List.of(
            "/LoadExternalDataProcessorOrReportFromFiles", "/DumpExternalDataProcessorOrReportToFiles");

    /** Select operation names only. Other arguments may contain connection data or credentials. */
    private static String stage(Path executable, List<String> args) {
        String name = executable.getFileName().toString();
        int modeIndex = name.equals("rac") || name.equalsIgnoreCase("rac.exe") ? 1 : 0;
        if (args.size() <= modeIndex) {
            return "";
        }
        String mode = args.get(modeIndex);
        if ("DESIGNER".equals(mode)) {
            for (String operation : DESIGNER_OPERATIONS) {
                if (args.contains(operation)) {
                    return mode + " " + operation;
                }
            }
            return mode;
        }
        if (!MODES.contains(mode)) {
            return "";
        }
        if (args.size() > modeIndex + 1 && OPERATIONS.contains(args.get(modeIndex + 1))) {
            return mode + " " + args.get(modeIndex + 1);
        }
        return mode;
    }

    private static String tail(String output) {
        if (output == null || output.isBlank()) {
            return "(no output)";
        }
        String trimmed = output.strip();
        return trimmed.length() > 2000 ? trimmed.substring(trimmed.length() - 2000) : trimmed;
    }

    /**
     * Remove a throwaway directory tree, best effort. One pass: nothing here is held by a process on
     * its way out - that case, and the waiting it needs, belongs to the configurator agent's trace
     * (see {@link TreeRemoval}).
     */
    static void deleteRecursively(Path root) {
        TreeRemoval.delete(root);
    }
}
