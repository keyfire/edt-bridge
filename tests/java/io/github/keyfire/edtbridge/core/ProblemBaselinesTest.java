package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProblemBaselinesTest {
    private static ProblemBaselines.Entry problem(String message, int line) {
        return new ProblemBaselines.Entry("Demo", "WARNING", message,
                "src/CommonModules/Orders/Module.bsl", line, "edt-check", "check");
    }

    @Test void reportsOnlyAddedAndRemovedProblems() {
        ProblemBaselines store = new ProblemBaselines(4, 20);
        var saved = store.save("scope", List.of(problem("old", 10), problem("kept", 20)));
        var diff = store.compare(saved.id(), "scope", List.of(problem("kept", 22), problem("new", 30)));
        assertEquals(List.of("new"), diff.added().stream().map(ProblemBaselines.Entry::message).toList());
        assertEquals(List.of("old"), diff.removed().stream().map(ProblemBaselines.Entry::message).toList());
    }

    @Test void tracksDuplicateCountsWithoutTreatingLineMovesAsNewProblems() {
        ProblemBaselines store = new ProblemBaselines(4, 20);
        var saved = store.save("scope", List.of(problem("same", 10), problem("same", 20)));
        var stable = store.compare(saved.id(), "scope", List.of(problem("same", 11), problem("same", 21)));
        assertTrue(stable.added().isEmpty());
        assertTrue(stable.removed().isEmpty());
        var fewer = store.compare(saved.id(), "scope", List.of(problem("same", 11)));
        assertEquals(1, fewer.removed().size());
        assertTrue(fewer.added().isEmpty());
    }

    @Test void rejectsAnotherProjectOrFilterScope() {
        ProblemBaselines store = new ProblemBaselines(4, 20);
        var saved = store.save("project=Demo;severity=WARNING", List.of(problem("old", 1)));
        assertThrows(IllegalArgumentException.class,
                () -> store.compare(saved.id(), "project=Other;severity=WARNING", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> store.compare(saved.id(), "project=Demo;severity=ERROR", List.of()));
    }

    @Test void evictsOldSnapshotsWhenCountOrMarkerBudgetIsReached() {
        ProblemBaselines store = new ProblemBaselines(2, 3);
        var first = store.save("scope", List.of(problem("one", 1), problem("two", 2)));
        var second = store.save("scope", List.of(problem("three", 3), problem("four", 4)));
        assertEquals(List.of(first.id()), second.evictedIds());
        assertThrows(IllegalArgumentException.class, () -> store.compare(first.id(), "scope", List.of()));
        assertEquals(2, store.compare(second.id(), "scope", List.of()).removed().size());
        var third = store.save("scope", List.of());
        var fourth = store.save("scope", List.of());
        assertEquals(List.of(second.id()), fourth.evictedIds());
        assertNotEquals(third.id(), fourth.id());
    }

    @Test void refusesOversizedSnapshotsWithoutLosingAnExistingOne() {
        ProblemBaselines store = new ProblemBaselines(2, 1);
        var kept = store.save("scope", List.of(problem("kept", 1)));
        assertThrows(IllegalArgumentException.class,
                () -> store.save("scope", List.of(problem("one", 1), problem("two", 2))));
        assertEquals(1, store.compare(kept.id(), "scope", List.of()).removed().size());
    }

    @Test void savesAnImmutableCopyOfTheProblems() {
        ProblemBaselines store = new ProblemBaselines(2, 20);
        var source = new java.util.ArrayList<>(List.of(problem("kept", 1)));
        var saved = store.save("scope", source);
        source.clear();
        assertEquals(1, store.compare(saved.id(), "scope", List.of()).removed().size());
    }
}