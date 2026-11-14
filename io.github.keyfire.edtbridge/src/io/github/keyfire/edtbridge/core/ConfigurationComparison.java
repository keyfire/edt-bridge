package io.github.keyfire.edtbridge.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** An immutable comparison snapshot with no references to EDT's live model. */
public final class ConfigurationComparison {
    public record Change(String name, String kind, String main, String other, String ancestor,
            boolean doubleChanges, List<Change> children) {
        public Change {
            children = List.copyOf(children);
        }
    }

    public record Summary(int total, int added, int removed, int modified) { }

    public record Page(List<Change> items, int total, Integer nextOffset) { }

    private final List<Change> objects;
    private final Summary summary;

    public ConfigurationComparison(List<Change> objects) {
        this.objects = objects.stream().sorted(Comparator.comparing(Change::name)
                .thenComparing(Change::kind)).toList();
        int added = 0;
        int removed = 0;
        int modified = 0;
        for (Change object : objects) {
            switch (object.kind()) {
                case "added" -> added++;
                case "removed" -> removed++;
                case "modified" -> modified++;
                default -> throw new IllegalArgumentException("Unknown change kind: " + object.kind());
            }
        }
        summary = new Summary(objects.size(), added, removed, modified);
    }

    public Summary summary() {
        return summary;
    }

    public Page objects(int offset, int limit) {
        return page(objects, offset, limit);
    }

    public Page details(int objectIndex, List<Integer> path, int offset, int limit) {
        Change node = at(objects, objectIndex);
        for (int index : path) {
            node = at(node.children(), index);
        }
        return page(node.children(), offset, limit);
    }

    public static String kind(boolean main, boolean other, boolean changed) {
        if (main && other) return changed ? "modified" : null;
        if (main) return "removed";
        return other ? "added" : null;
    }

    private static Change at(List<Change> rows, int index) {
        if (index < 0 || index >= rows.size()) {
            throw new IllegalArgumentException("Unknown comparison node index: " + index);
        }
        return rows.get(index);
    }

    private static Page page(List<Change> rows, int offset, int limit) {
        if (offset < 0 || limit < 1) throw new IllegalArgumentException("offset must be nonnegative and limit positive");
        int from = Math.min(offset, rows.size());
        int to = from + Math.min(limit, rows.size() - from);
        List<Change> summaries = new ArrayList<>(to - from);
        for (Change row : rows.subList(from, to)) {
            summaries.add(new Change(row.name(), row.kind(), row.main(), row.other(), row.ancestor(),
                    row.doubleChanges(), List.of()));
        }
        return new Page(List.copyOf(summaries), rows.size(), to < rows.size() ? to : null);
    }

    public boolean hasDetails(int objectIndex, List<Integer> path) {
        Change node = at(objects, objectIndex);
        for (int index : path) {
            node = at(node.children(), index);
        }
        return !node.children().isEmpty();
    }
}
