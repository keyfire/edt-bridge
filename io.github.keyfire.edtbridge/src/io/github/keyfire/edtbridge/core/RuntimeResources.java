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

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only snapshot of the running bridge JVM and its cgroup v2 resources, when available. */
public final class RuntimeResources {
    private RuntimeResources() {
    }

    public static Map<String, Object> snapshot() {
        var memory = ManagementFactory.getMemoryMXBean();
        Map<String, Object> jvm = Map.of(
                "heap", memory(memory.getHeapMemoryUsage()),
                "nonHeap", memory(memory.getNonHeapMemoryUsage()),
                "gc", garbageCollectors());
        return Map.of("observedAt", System.currentTimeMillis(),
                "processStartedAt", ManagementFactory.getRuntimeMXBean().getStartTime(),
                "processState", "running",
                "previousExitCause", "unknown",
                "jvm", jvm,
                "container", container());
    }

    public static CgroupResources.Metric counter(long value) {
        return value < 0 ? CgroupResources.Metric.unavailable("undefined")
                : CgroupResources.Metric.known(value);
    }

    private static Map<String, Object> memory(MemoryUsage usage) {
        return Map.of("usedBytes", usage.getUsed(), "committedBytes", usage.getCommitted(),
                "maxBytes", counter(usage.getMax()));
    }

    private static List<Map<String, Object>> garbageCollectors() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(bean -> Map.<String, Object>of("name", bean.getName(),
                        "collectionCount", counter(bean.getCollectionCount()),
                        "collectionTimeMillis", counter(bean.getCollectionTime())))
                .toList();
    }

    private static Map<String, Object> container() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux")) {
            return Map.of("available", false, "unavailableReason", "unsupportedOs");
        }
        Path cgroup = CgroupResources.resolve(Path.of("/proc/self/cgroup"), Path.of("/proc/self/mountinfo"));
        if (cgroup == null) {
            return Map.of("available", false, "unavailableReason", "cgroupV2Unresolved");
        }
        return Map.of("available", true, "kind", "cgroupV2", "metrics", CgroupResources.snapshot(cgroup));
    }
}
