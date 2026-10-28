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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * What the bridge last loaded into an infobase through the configurator agent, file by file: the
 * memory that lets the next load send only the files that changed.
 *
 * <p>A load exports the project to designer XML and hands the directory to the agent's
 * {@code config load-config-from-files}. Handed the whole directory, the platform loads the whole
 * configuration, and on a large one that is minutes of work for a change of one line. It can load a
 * list of files instead ({@code --partial} with {@code --list-file}), but something has to know WHICH
 * files changed, and the export cannot tell: it is written from scratch every time. This snapshot is
 * that knowledge - the SHA-256 of every exported file, taken after a load succeeded, so that the next
 * export can be compared with what the infobase was last given.
 *
 * <p>Hashes of the EXPORT, not of the project sources. The export is what reaches the infobase, and
 * EDT's own layout maps to it in ways this package does not model.
 *
 * <p>The snapshot also keeps the infobase's configuration generation id as it was after that load.
 * The platform changes the id on every change of the configuration, and the id only compares for
 * equality - which makes it the proof that nobody else changed the infobase in between: another load,
 * EDT's synchronization, a human in the Configurator. Without that proof a partial load would lay this
 * project's changes over a configuration the snapshot knows nothing about.
 *
 * <p>A plain text file rather than JSON, for the same reason as {@link AgentRecord}: this package
 * carries no dependencies.
 */
public final class LoadSnapshot {

    static final String HEADER = "edt-bridge load snapshot 1";

    /** The version file of a designer export: not configuration content, never loaded, never compared. */
    static final String DUMP_INFO = "ConfigDumpInfo.xml";

    /** Which infobase and what in it - see {@link #target}. */
    public String target;
    public String platformVersion;
    /** The infobase's configuration generation id right after the load; null when unknown. */
    public String generation;
    /** Path relative to the export directory, '/'-separated, mapped to the SHA-256 of the file in hex. */
    public final SortedMap<String, String> files = new TreeMap<>();

    /**
     * What a snapshot is about: the infobase and whether the configuration or an extension went into
     * it. The connection string is lower-cased - a server name and a Windows path compare without case,
     * and the same infobase must not end up with two snapshots that disagree.
     */
    public static String target(String connectionString, String extension) {
        String infobase = connectionString == null ? "" : connectionString.trim().toLowerCase(Locale.ROOT);
        return infobase + (extension == null || extension.isBlank()
                ? " configuration"
                : " extension " + extension.trim());
    }

    /**
     * The file a target's snapshot is kept in. Named by a hash of the target: the target carries a
     * connection string, and that is full of characters a file name cannot hold.
     */
    public static String fileName(String target) {
        byte[] hash = sha256().digest((target == null ? "" : target).getBytes(StandardCharsets.UTF_8));
        return "load-" + HexFormat.of().formatHex(hash, 0, 8) + ".snapshot";
    }

    /** Hash every file of an export directory. The version file is left out - see {@link #DUMP_INFO}. */
    public static LoadSnapshot of(Path exportDir) throws IOException {
        LoadSnapshot s = new LoadSnapshot();
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(exportDir)) {
            paths = walk.filter(Files::isRegularFile).toList();
        }
        MessageDigest digest = sha256();
        byte[] buffer = new byte[1 << 16];
        for (Path file : paths) {
            String relative = relative(exportDir, file);
            if (relative.equals(DUMP_INFO)) {
                continue;
            }
            digest.reset();
            try (InputStream in = Files.newInputStream(file)) {
                for (int n = in.read(buffer); n > 0; n = in.read(buffer)) {
                    digest.update(buffer, 0, n);
                }
            }
            s.files.put(relative, HexFormat.of().formatHex(digest.digest()));
        }
        return s;
    }

    /** The path of a file inside a directory with '/' between the parts, whatever the system uses. */
    static String relative(Path root, Path file) {
        StringBuilder b = new StringBuilder();
        for (Path part : root.relativize(file)) {
            if (b.length() > 0) {
                b.append('/');
            }
            b.append(part);
        }
        return b.toString();
    }

    public String format() {
        StringBuilder b = new StringBuilder(HEADER).append('\n');
        line(b, "target", target);
        line(b, "platform", platformVersion);
        line(b, "generation", generation);
        files.forEach((path, hash) -> b.append(hash).append(' ').append(path).append('\n'));
        return b.toString();
    }

    /**
     * Read a snapshot back, or null when the text is not one. A line this version does not know also
     * gives null: a snapshot read wrong costs a wrong partial load, and starting over costs one full one.
     */
    public static LoadSnapshot parse(String text) {
        if (text == null) {
            return null;
        }
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !lines.get(0).equals(HEADER)) {
            return null;
        }
        LoadSnapshot s = new LoadSnapshot();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isEmpty()) {
                continue;
            }
            int space = line.indexOf(' ');
            if (space <= 0 || space == line.length() - 1) {
                return null;
            }
            String key = line.substring(0, space);
            String value = line.substring(space + 1);
            if (isHash(key)) {
                s.files.put(value, key);
            } else if (key.equals("target")) {
                s.target = value;
            } else if (key.equals("platform")) {
                s.platformVersion = value;
            } else if (key.equals("generation")) {
                s.generation = value;
            } else {
                return null;
            }
        }
        return s;
    }

    /**
     * Write the snapshot. Through a temporary file and a move, so that a process killed in the middle
     * leaves the old snapshot or the new one, never half of one.
     */
    public void write(Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, format(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Read a snapshot, or null when the file is absent, unreadable or not a snapshot. */
    public static LoadSnapshot read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static void line(StringBuilder b, String key, String value) {
        if (value != null && !value.isBlank()) {
            b.append(key).append(' ').append(value.trim()).append('\n');
        }
    }

    private static boolean isHash(String s) {
        if (s.length() != 64) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException absent) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException(absent);
        }
    }
}
