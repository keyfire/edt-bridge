package io.github.keyfire.edtbridge.edt;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.jobs.Job;
import com._1c.g5.v8.derived.IDerivedDataManager;
import io.github.keyfire.edtbridge.core.ProgressObservations;

/** Read-only snapshots. No scheduler, refresh, wait, model transaction or persistent state. */
final class ProjectProgress {
    private static final ProgressObservations HISTORY = new ProgressObservations(512);
    private static final long PROCESS_STARTED_AT = ManagementFactory.getRuntimeMXBean().getStartTime();
    private static final int LIMIT = 16;

    private ProjectProgress() { }

    static Map<String, Object> model(IProject project, boolean present, Boolean locked,
            Boolean computed, IDerivedDataManager derived, String failure) {
        Map<String, Object> result = stamp();
        result.put("modelLocked", metric(locked, failure == null ? "modelUnavailable" : failure));
        result.put("derivedDataComputed", metric(computed,
                failure == null ? "derivedDataManagerUnavailable" : failure));
        List<String> reasons = new ArrayList<>();
        if (!present) reasons.add("modelNotLoaded");
        if (Boolean.TRUE.equals(locked)) reasons.add("modelLocked");
        if (computed == null) reasons.add("derivedDataStateUnavailable");
        else if (!computed) reasons.add("derivedDataPending");
        if (failure != null) reasons.add("modelStateUnavailable");
        result.put("pendingReasons", reasons);
        Map<String, Object> queue = derivedQueue(derived);
        result.put("derivedData", queue);
        String key = "model:" + project.getName() + ":" + project.getLocationURI();
        // Stable key ordering excludes observation times; SDK counters are copied below.
        String value = present + ":" + locked + ":" + computed + ":" + queue;
        result.put("observation", HISTORY.observe(key, value, System.currentTimeMillis()));
        return result;
    }

    private static Map<String, Object> metric(Object value, String unavailableReason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", value != null);
        if (value != null) result.put("value", value);
        else result.put("unavailableReason", unavailableReason);
        return result;
    }

    private static Map<String, Object> derivedQueue(IDerivedDataManager manager) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (manager == null) {
            result.put("available", false);
            result.put("unavailableReason", "derivedDataManagerUnavailable");
            return result;
        }
        try {
            var status = manager.getDerivedDataStatus();
            if (status == null) {
                result.put("available", false);
                result.put("unavailableReason", "derivedDataStatusUnavailable");
                return result;
            }
            result.put("available", true);
            result.put("idle", manager.isIdle());
            result.put("pipelineStatus", status.getPipelineStatus() == null ? "unknown"
                    : status.getPipelineStatus().name());
            result.put("activeStage", status.getActiveStage() == null ? "unknown"
                    : status.getActiveStage().name());
            result.put("modelSyncActive", status.isModelSyncActive());
            var active = new TreeMap<>(status.getActivePipelineSegments());
            result.put("activeSegmentCount", active.size());
            result.put("segmentsTruncated", active.size() > LIMIT);
            List<Map<String, Object>> segments = new ArrayList<>();
            for (var entry : active.entrySet()) {
                if (segments.size() >= LIMIT) break;
                Map<String, Object> segment = new LinkedHashMap<>();
                segment.put("name", safe(entry.getKey()));
                var progress = entry.getValue();
                segment.put("remainingUnits", metric(progress == null || progress.getRemainingUnitsOfWork() < 0
                        ? null : progress.getRemainingUnitsOfWork(), "notReported"));
                segment.put("totalUnits", metric(progress == null || progress.getTotalUnitsOfWork() < 0
                        ? null : progress.getTotalUnitsOfWork(), "notReported"));
                try {
                    var stats = manager.getSegmentStats(entry.getKey());
                    segment.put("remainingTasks", metric(stats == null || stats.remainingTasks() < 0
                            ? null : stats.remainingTasks(), "notReported"));
                } catch (RuntimeException | LinkageError unavailable) {
                    segment.put("remainingTasks", metric(null, unavailable.getClass().getSimpleName()));
                }
                segments.add(segment);
            }
            result.put("segments", segments);
            result.put("queueScope", "activeSegments");
        } catch (RuntimeException | LinkageError unavailable) {
            result.clear();
            result.put("available", false);
            result.put("unavailableReason", unavailable.getClass().getSimpleName());
        }
        return result;
    }

    static Map<String, Object> backgroundJobs() {
        Map<String, Object> result = stamp();
        result.put("scope", "workspace");
        try {
            var manager = Job.getJobManager();
            List<Map<String, Object>> active = new ArrayList<>();
            List<String> fingerprint = new ArrayList<>();
            int total = 0;
            int sleeping = 0;
            for (Job job : manager.find(null)) {
                int state = job.getState();
                if (state == Job.SLEEPING) sleeping++;
                if (state != Job.RUNNING && state != Job.WAITING) continue;
                total++;
                String name = safe(job.getName());
                String stateName = state == Job.RUNNING ? "RUNNING" : "WAITING";
                fingerprint.add(name + ":" + stateName);
                if (active.size() >= LIMIT) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);
                item.put("state", stateName);
                item.put("jobType", job.getClass().getName());
                item.put("system", job.isSystem());
                item.put("ruleType", job.getRule() == null ? "none" : job.getRule().getClass().getName());
                active.add(item);
            }
            fingerprint.sort(String::compareTo);
            boolean idle = manager.isIdle();
            result.put("available", true);
            result.put("idle", idle);
            result.put("activeCount", total);
            result.put("sleepingCount", sleeping);
            result.put("truncated", total > LIMIT);
            result.put("active", active);
            result.put("observation", HISTORY.observe("workspaceJobs", idle + ":" + fingerprint,
                    System.currentTimeMillis()));
        } catch (RuntimeException | LinkageError unavailable) {
            result.put("available", false);
            result.put("unavailableReason", unavailable.getClass().getSimpleName());
        }
        return result;
    }

    static Map<String, Object> report(String scope, int count, boolean modelReady,
            Boolean validationCompleted, Boolean settled, int quietPolls) {
        Map<String, Object> result = stamp();
        Map<String, Object> jobs = backgroundJobs();
        result.put("backgroundJobs", jobs);
        result.put("problemCount", count < 0 ? metric(null, "notRead")
                : HISTORY.observe("problems:" + scope, count, System.currentTimeMillis()));
        result.put("quietPolls", quietPolls < 0 ? metric(null, "notObserved") : metric(quietPolls, null));
        result.put("requiredQuietPolls", io.github.keyfire.edtbridge.core.ValidationSettle.REQUIRED_QUIET_POLLS);
        result.put("validationCompletion", metric(validationCompleted, "notRequestedByThisRead"));
        result.put("pendingReasons", ProgressObservations.waitingReasons(modelReady,
                (Boolean) jobs.get("idle"), validationCompleted, settled, quietPolls));
        return result;
    }

    static void observeCount(String scope, int count) {
        if (count >= 0) HISTORY.observe("problems:" + scope, count, System.currentTimeMillis());
    }

    private static Map<String, Object> stamp() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("observedAt", System.currentTimeMillis());
        result.put("processStartedAt", PROCESS_STARTED_AT);
        return result;
    }

    private static String safe(String value) {
        if (value == null) return "";
        String text = value.replaceAll("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/-]+=*", "$1 <redacted>")
                .replaceAll("(?i)\\b(password|passwd|pwd|token|secret|authorization)\\s*[:=]\\s*[^,;\\s]+", "$1=<redacted>")
                .replaceAll("[\\r\\n\\t]+", " ");
        return text.length() > 240 ? text.substring(0, 240) + "..." : text;
    }
}
