package io.github.keyfire.edtbridge.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Bounded, process-local observations; the first sample is not evidence of an earlier change. */
public final class ProgressObservations {
    public record Observation(Object value, long observedAt, long firstObservedAt,
            long unchangedSince, Long lastChangeObservedAt, boolean lastChangeKnown) { }

    private final int capacity;
    private final LinkedHashMap<String, Observation> history = new LinkedHashMap<>(16, 0.75f, true);

    public ProgressObservations(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    /** Values must be immutable snapshots, not references to a live SDK model. */
    public synchronized Observation observe(String key, Object value, long now) {
        Observation previous = history.get(key);
        boolean changed = previous != null && !Objects.equals(previous.value(), value);
        Observation current = new Observation(value, now,
                previous == null ? now : previous.firstObservedAt(),
                previous == null || changed ? now : previous.unchangedSince(),
                changed ? Long.valueOf(now) : previous == null ? null : previous.lastChangeObservedAt(),
                changed || previous != null && previous.lastChangeKnown());
        history.put(key, current);
        while (history.size() > capacity) history.remove(history.keySet().iterator().next());
        return current;
    }

    /** Reasons describe this observation and never change the caller's completeness decision. */
    public static List<String> waitingReasons(Boolean modelReady, Boolean jobsIdle,
            Boolean validationCompleted, Boolean settled, int quietPolls) {
        List<String> reasons = new ArrayList<>();
        if (Boolean.FALSE.equals(validationCompleted)) reasons.add("validationNotCompleted");
        if (modelReady == null) reasons.add("modelStateUnavailable");
        else if (!modelReady) reasons.add("modelNotReady");
        if (jobsIdle == null) reasons.add("workspaceJobStateUnavailable");
        else if (!jobsIdle) reasons.add("workspaceJobsActive");
        if (Boolean.TRUE.equals(modelReady) && Boolean.TRUE.equals(jobsIdle)
                && Boolean.TRUE.equals(validationCompleted) && Boolean.FALSE.equals(settled)
                && quietPolls < ValidationSettle.REQUIRED_QUIET_POLLS) {
            reasons.add("problemCountNotStable");
        }
        return reasons;
    }
}
