package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ConfigurationComparisonTest {
    private static ConfigurationComparison.Change change(String name, String kind) {
        return new ConfigurationComparison.Change(name, kind, null, null, null, false, List.of());
    }

    @Test
    void countsTheWholeResultAndPagesByStableObjectName() {
        var snapshot = new ConfigurationComparison(List.of(
                change("Document.Z", "removed"), change("Catalog.A", "added"), change("Catalog.B", "modified")));

        var first = snapshot.objects(0, 2);
        assertEquals(List.of("Catalog.A", "Catalog.B"), first.items().stream().map(ConfigurationComparison.Change::name).toList());
        assertEquals(3, first.total());
        assertEquals(2, first.nextOffset());
        assertEquals(new ConfigurationComparison.Summary(3, 1, 1, 1), snapshot.summary());
        assertEquals("Document.Z", snapshot.objects(2, 2).items().get(0).name());
        assertNull(snapshot.objects(2, 2).nextOffset());
        assertEquals(List.of(), snapshot.objects(Integer.MAX_VALUE, 2).items());
    }

    @Test
    void followsADetailPathWithoutReturningItsWholeSubtree() {
        var method = change("Calculate", "modified");
        var module = new ConfigurationComparison.Change("module", "modified", null, null, null, false, List.of(method));
        var object = new ConfigurationComparison.Change("Catalog.A", "modified", null, null, null, false, List.of(module));
        var snapshot = new ConfigurationComparison(List.of(object));

        assertEquals(List.of(), snapshot.objects(0, 1).items().get(0).children());
        assertEquals("Calculate", snapshot.details(0, List.of(0), 0, 1).items().get(0).name());
        assertThrows(IllegalArgumentException.class, () -> snapshot.details(0, List.of(1), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> snapshot.objects(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> snapshot.objects(0, 0));
    }

    @Test
    void comparesEachSideWithItsExplicitCounterpart() {
        assertEquals("added", ConfigurationComparison.kind(false, true, false));
        assertEquals("removed", ConfigurationComparison.kind(true, false, false));
        assertEquals("modified", ConfigurationComparison.kind(true, true, true));
        assertNull(ConfigurationComparison.kind(true, true, false));
        assertNull(ConfigurationComparison.kind(false, false, false));
        assertEquals(new ConfigurationComparison.Summary(0, 0, 0, 0),
                new ConfigurationComparison(List.of()).summary());
    }
}
