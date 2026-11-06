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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Read resource counters from the process's cgroup v2 directory. */
public final class CgroupResources {
    private static final int MAX_FILE_BYTES = 16 * 1024;

    public record Metric(boolean available, Object value, String unavailableReason) {
        static Metric known(Object value) {
            return new Metric(true, value, null);
        }

        static Metric unavailable(String reason) {
            return new Metric(false, null, reason);
        }
    }

    private CgroupResources() {
    }

    public static Map<String, Metric> snapshot(Path directory) {
        Map<String, Metric> metrics = new LinkedHashMap<>();
        metrics.put("memoryCurrentBytes", read(directory, "memory.current", CgroupResources::nonNegative));
        metrics.put("memoryMaxBytes", read(directory, "memory.max", CgroupResources::limit));
        metrics.put("memoryHighBytes", read(directory, "memory.high", CgroupResources::limit));
        metrics.put("memoryEvents", read(directory, "memory.events", CgroupResources::counters));
        metrics.put("memoryPressure", read(directory, "memory.pressure", CgroupResources::pressure));
        metrics.put("cpuMax", read(directory, "cpu.max", CgroupResources::cpuMax));
        metrics.put("cpuStat", read(directory, "cpu.stat", CgroupResources::counters));
        return metrics;
    }

    /**
     * Resolve the unified hierarchy using the mount's root and mount point from mountinfo.
     * The mounted root can be a subtree, so the cgroup path is relative to that root.
     */
    public static Path resolve(Path procSelfCgroup, Path mountInfo) {
        String cgroups = readProc(procSelfCgroup);
        String mounts = readProc(mountInfo);
        if (cgroups == null || mounts == null) {
            return null;
        }
        List<String> self = cgroups.lines().filter(line -> line.startsWith("0::"))
                .findFirst().map(line -> hierarchyPath(line.substring(3))).orElse(null);
        if (self == null) {
            return null;
        }
        Path best = null;
        int bestDepth = -1;
        for (String line : mounts.lines().toList()) {
            String[] fields = line.split(" ");
            int separator = List.of(fields).indexOf("-");
            if (separator < 6 || fields.length <= separator + 1 || !"cgroup2".equals(fields[separator + 1])) {
                continue;
            }
            List<String> root = hierarchyPath(unescape(fields[3]));
            if (root == null || root.size() <= bestDepth || root.size() > self.size()
                    || !self.subList(0, root.size()).equals(root)) {
                continue;
            }
            Path candidate = withinMount(unescape(fields[4]), self.subList(root.size(), self.size()));
            if (candidate != null) {
                best = candidate;
                bestDepth = root.size();
            }
        }
        return best;
    }

    private static Path withinMount(String mountPoint, List<String> relative) {
        try {
            Path mount = Path.of(mountPoint);
            if (!mount.isAbsolute() || !mount.equals(mount.normalize())) {
                return null;
            }
            Path target = mount;
            for (String segment : relative) {
                Path name = Path.of(segment);
                if (name.getRoot() != null || name.getNameCount() != 1) {
                    return null;
                }
                target = target.resolve(name);
            }
            Path realMount = mount.toRealPath();
            Path realTarget = target.toRealPath();
            return realTarget.startsWith(realMount) && Files.isDirectory(realTarget) ? realTarget : null;
        } catch (IOException | IllegalArgumentException | SecurityException ex) {
            return null;
        }
    }

    private static List<String> hierarchyPath(String value) {
        if (!value.startsWith("/")) {
            return null;
        }
        if (value.equals("/")) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String name : value.substring(1).split("/", -1)) {
            if (name.isEmpty() || name.equals(".") || name.equals("..")) {
                return null;
            }
            names.add(name);
        }
        return names;
    }

    private static String unescape(String value) {
        return value.replace("\\040", " ").replace("\\011", "\t")
                .replace("\\012", "\n").replace("\\134", "\\");
    }

    private static String readProc(Path file) {
        try {
            byte[] bytes = readFile(file);
            return bytes.length > MAX_FILE_BYTES ? null : new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | SecurityException ex) {
            return null;
        }
    }

    private static Metric read(Path directory, String name, Function<String, Object> parse) {
        try {
            byte[] bytes = readFile(directory.resolve(name));
            if (bytes.length > MAX_FILE_BYTES) {
                return Metric.unavailable("tooLarge");
            }
            return Metric.known(parse.apply(new String(bytes, StandardCharsets.UTF_8).strip()));
        } catch (NoSuchFileException ex) {
            return Metric.unavailable("notFound");
        } catch (IOException | SecurityException ex) {
            return Metric.unavailable("unreadable");
        } catch (IllegalArgumentException ex) {
            return Metric.unavailable("invalidValue");
        }
    }

    private static byte[] readFile(Path file) throws IOException {
        if (!Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
            throw new IOException("Resource is not a regular file");
        }
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return input.readNBytes(MAX_FILE_BYTES + 1);
        }
    }

    private static long nonNegative(String value) {
        long result = Long.parseLong(value);
        if (result < 0) {
            throw new IllegalArgumentException("Negative cgroup counter");
        }
        return result;
    }

    private static Object limit(String value) {
        return value.equals("max") ? "unlimited" : nonNegative(value);
    }

    private static Map<String, Long> counters(String value) {
        Map<String, Long> counters = new LinkedHashMap<>();
        for (String line : value.lines().toList()) {
            String[] pair = line.strip().split("\\s+");
            if (pair.length != 2 || counters.putIfAbsent(pair[0], nonNegative(pair[1])) != null) {
                throw new IllegalArgumentException("Invalid cgroup counter");
            }
        }
        if (counters.isEmpty()) {
            throw new IllegalArgumentException("Empty cgroup counters");
        }
        return counters;
    }

    private static Map<String, Object> cpuMax(String value) {
        String[] values = value.split("\\s+");
        if (values.length != 2) {
            throw new IllegalArgumentException("Invalid CPU quota");
        }
        long period = nonNegative(values[1]);
        if (period == 0) {
            throw new IllegalArgumentException("Zero CPU period");
        }
        Object quota = limit(values[0]);
        Object cpuLimit = quota instanceof Long micros ? (double) micros / period : "unlimited";
        return Map.of("quotaMicros", quota, "periodMicros", period, "cpuLimit", cpuLimit);
    }

    private static Map<String, Object> pressure(String value) {
        Map<String, Object> pressure = new LinkedHashMap<>();
        for (String line : value.lines().toList()) {
            String[] fields = line.strip().split("\\s+");
            if (fields.length < 2 || !(fields[0].equals("some") || fields[0].equals("full"))
                    || pressure.putIfAbsent(fields[0], pressureLine(fields)) != null) {
                throw new IllegalArgumentException("Invalid memory pressure");
            }
        }
        if (pressure.isEmpty()) {
            throw new IllegalArgumentException("Empty memory pressure");
        }
        return pressure;
    }

    private static Map<String, Object> pressureLine(String[] fields) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (int i = 1; i < fields.length; i++) {
            String[] field = fields[i].split("=", -1);
            if (field.length != 2) {
                throw new IllegalArgumentException("Invalid pressure field");
            }
            Object number = switch (field[0]) {
                case "total" -> nonNegative(field[1]);
                case "avg10", "avg60", "avg300" -> average(field[1]);
                default -> throw new IllegalArgumentException("Unknown pressure field");
            };
            if (metrics.putIfAbsent(field[0], number) != null) {
                throw new IllegalArgumentException("Duplicate pressure field");
            }
        }
        if (metrics.size() != 4) {
            throw new IllegalArgumentException("Incomplete pressure sample");
        }
        return metrics;
    }

    private static double average(String value) {
        double average = Double.parseDouble(value);
        if (!Double.isFinite(average) || average < 0) {
            throw new IllegalArgumentException("Invalid pressure average");
        }
        return average;
    }
}
