package io.github.keyfire.edtbridge.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded in-memory validation snapshots, compared as multisets independently of line shifts. */
public final class ProblemBaselines {
    public record Entry(String project, String severity, String message, String resource,
            int line, String source, String checkId) {
        Identity identity() {
            return new Identity(project, severity, message, resource, source, checkId);
        }
    }
    private record Identity(String project, String severity, String message, String resource,
            String source, String checkId) { }
    private record Snapshot(String scope, List<Entry> entries) { }
    public record Saved(String id, int count, List<String> evictedIds) { }
    public record Difference(List<Entry> added, List<Entry> removed) { }

    private final int maximumSnapshots;
    private final int maximumMarkers;
    private final String session = UUID.randomUUID().toString().substring(0, 12);
    private final LinkedHashMap<String, Snapshot> snapshots = new LinkedHashMap<>();
    private int markers;
    private long sequence;

    public ProblemBaselines(int maximumSnapshots, int maximumMarkers) {
        if (maximumSnapshots < 1 || maximumMarkers < 1) {
            throw new IllegalArgumentException("Snapshot and marker limits must be positive");
        }
        this.maximumSnapshots = maximumSnapshots;
        this.maximumMarkers = maximumMarkers;
    }

    public synchronized Saved save(String scope, List<Entry> entries) {
        if (entries.size() > maximumMarkers) {
            throw new IllegalArgumentException("The snapshot exceeds the marker budget; narrow fqn or modulePath");
        }
        List<Entry> copy = List.copyOf(entries);
        List<String> evicted = new ArrayList<>();
        while (!snapshots.isEmpty() && (snapshots.size() >= maximumSnapshots
                || markers + copy.size() > maximumMarkers)) {
            String oldest = snapshots.keySet().iterator().next();
            markers -= snapshots.remove(oldest).entries.size();
            evicted.add(oldest);
        }
        String id = "baseline-" + session + "-" + (++sequence);
        snapshots.put(id, new Snapshot(scope, copy));
        markers += copy.size();
        return new Saved(id, copy.size(), List.copyOf(evicted));
    }

    public synchronized Difference compare(String id, String scope, List<Entry> current) {
        Snapshot snapshot = snapshots.get(id);
        if (snapshot == null) {
            throw new IllegalArgumentException("Baseline not found or evicted; save a new baseline in this EDT session");
        }
        if (!snapshot.scope.equals(scope)) {
            throw new IllegalArgumentException("Baseline project, location or filters differ from this request");
        }
        Map<Identity, ArrayDeque<Entry>> old = new LinkedHashMap<>();
        for (Entry entry : snapshot.entries) {
            old.computeIfAbsent(entry.identity(), ignored -> new ArrayDeque<>()).add(entry);
        }
        List<Entry> added = new ArrayList<>();
        for (Entry entry : current) {
            ArrayDeque<Entry> matches = old.get(entry.identity());
            if (matches == null || matches.isEmpty()) {
                added.add(entry);
            } else {
                matches.removeFirst();
            }
        }
        List<Entry> removed = new ArrayList<>();
        old.values().forEach(removed::addAll);
        return new Difference(List.copyOf(added), List.copyOf(removed));
    }
}