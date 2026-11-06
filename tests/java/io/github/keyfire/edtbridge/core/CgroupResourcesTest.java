package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CgroupResourcesTest {
    @TempDir Path root;

    @Test void readsLimitsCountersAndPressureWithoutChangingFiles() throws Exception {
        Files.writeString(root.resolve("memory.current"), "1024\n");
        Files.writeString(root.resolve("memory.max"), "8192\n");
        Files.writeString(root.resolve("memory.high"), "max\n");
        Files.writeString(root.resolve("memory.events"), "low 0\nhigh 2\nmax 3\noom 1\noom_kill 1\n");
        Files.writeString(root.resolve("memory.pressure"), "some avg10=0.25 avg60=0.10 avg300=0.01 total=410\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=7\n");
        Files.writeString(root.resolve("cpu.max"), "250000 100000\n");
        var result = CgroupResources.snapshot(root);
        assertEquals(1024L, result.get("memoryCurrentBytes").value());
        assertEquals(8192L, result.get("memoryMaxBytes").value());
        assertEquals("unlimited", result.get("memoryHighBytes").value());
        assertEquals(1L, ((Map<?, ?>) result.get("memoryEvents").value()).get("oom_kill"));
        assertEquals(2.5, ((Map<?, ?>) result.get("cpuMax").value()).get("cpuLimit"));
        var pressure = (Map<?, ?>) result.get("memoryPressure").value();
        assertEquals(410L, ((Map<?, ?>) pressure.get("some")).get("total"));
        assertEquals("8192\n", Files.readString(root.resolve("memory.max")));
        assertFalse(result.get("cpuStat").available());
        assertEquals("notFound", result.get("cpuStat").unavailableReason());
    }

    @Test void missingMalformedAndOversizedMetricsAreUnknownNotZero() throws Exception {
        Files.writeString(root.resolve("memory.max"), "not-a-number\n");
        Files.writeString(root.resolve("memory.events"), "x".repeat(20000));
        Files.writeString(root.resolve("cpu.max"), "100000 0\n");
        var result = CgroupResources.snapshot(root);
        assertFalse(result.get("memoryCurrentBytes").available());
        assertNull(result.get("memoryCurrentBytes").value());
        assertEquals("invalidValue", result.get("memoryMaxBytes").unavailableReason());
        assertEquals("tooLarge", result.get("memoryEvents").unavailableReason());
        assertEquals("invalidValue", result.get("cpuMax").unavailableReason());
    }

    @Test void resolvesTheProcessesCgroupRatherThanAssumingTheMountRoot() throws Exception {
        Path mount = root.resolve("cgroup mount");
        Files.createDirectories(mount.resolve("worker"));
        Path cgroup = root.resolve("cgroup");
        Path mountinfo = root.resolve("mountinfo");
        Files.writeString(cgroup, "0::/tenant/worker\n");
        String escaped = mount.toString().replace('\\', '/').replace(" ", "\\040");
        Files.writeString(mountinfo, "29 23 0:26 /tenant " + escaped + " rw - cgroup2 cgroup rw\n");
        assertEquals(mount.resolve("worker").normalize(), CgroupResources.resolve(cgroup, mountinfo));
    }

    @Test void readsUnlimitedCpuQuotaAndCpuTime() throws Exception {
        Files.writeString(root.resolve("cpu.max"), "max 100000\n");
        Files.writeString(root.resolve("cpu.stat"), "usage_usec 374\nnr_throttled 0\n");
        var result = CgroupResources.snapshot(root);
        var limit = (Map<?, ?>) result.get("cpuMax").value();
        assertEquals("unlimited", limit.get("quotaMicros"));
        assertEquals("unlimited", limit.get("cpuLimit"));
        assertEquals(100000L, limit.get("periodMicros"));
        var stats = (Map<?, ?>) result.get("cpuStat").value();
        assertEquals(374L, stats.get("usage_usec"));
        assertEquals(0L, stats.get("nr_throttled"));
    }

    @Test void rejectsTraversalAndUnrelatedMounts() throws Exception {
        Path cgroup = root.resolve("cgroup");
        Path mountinfo = root.resolve("mountinfo");
        Files.writeString(cgroup, "0::/other/worker\n");
        Files.writeString(mountinfo, "29 23 0:26 /tenant /sys/fs/cgroup rw - cgroup2 cgroup rw\n");
        assertNull(CgroupResources.resolve(cgroup, mountinfo));
        Files.writeString(cgroup, "0::/tenant/../secret\n");
        assertNull(CgroupResources.resolve(cgroup, mountinfo));
    }

    @Test void runtimeSnapshotUsesJvmCountersAndStatesUnavailableMetricsExplicitly() {
        var resources = RuntimeResources.snapshot();
        assertTrue((long) resources.get("observedAt") > 0);
        assertTrue((long) resources.get("processStartedAt") > 0);
        assertEquals("running", resources.get("processState"));
        assertEquals("unknown", resources.get("previousExitCause"));
        var jvm = (Map<?, ?>) resources.get("jvm");
        var heap = (Map<?, ?>) jvm.get("heap");
        assertTrue((long) heap.get("usedBytes") >= 0);
        assertTrue((long) heap.get("committedBytes") >= 0);
        assertFalse(resources.containsKey("arguments"));
        assertFalse(resources.containsKey("environment"));
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("linux")) {
            var container = (Map<?, ?>) resources.get("container");
            assertEquals(false, container.get("available"));
            assertEquals("unsupportedOs", container.get("unavailableReason"));
        }
        assertFalse(RuntimeResources.counter(-1).available());
        assertEquals(0L, RuntimeResources.counter(0).value());
    }
}
