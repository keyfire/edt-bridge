package io.github.keyfire.edtbridge.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProgressObservationsTest {
    @Test void firstObservationDoesNotInventAnEarlierTransition() {
        var history = new ProgressObservations(3);
        var first = history.observe("model", "pending", 1000);
        assertEquals(1000L, first.firstObservedAt());
        assertNull(first.lastChangeObservedAt());
        assertEquals(1000L, first.unchangedSince());
        var repeated = history.observe("model", "pending", 1600);
        assertEquals(1600L, repeated.observedAt());
        assertEquals(1000L, repeated.unchangedSince());
        assertNull(repeated.lastChangeObservedAt());
    }

    @Test void reportsOnlyObservedChangesAndNewInstancesHaveNoOldReadiness() {
        var history = new ProgressObservations(3);
        history.observe("model", "pending", 1000);
        var changed = history.observe("model", "ready", 2000);
        assertEquals(2000L, changed.lastChangeObservedAt());
        assertEquals(2000L, changed.unchangedSince());
        var restarted = new ProgressObservations(3).observe("model", "pending", 3000);
        assertNull(restarted.lastChangeObservedAt());
        assertEquals("pending", restarted.value());
    }

    @Test void boundsHistoryAndKeepsScopesSeparate() {
        var history = new ProgressObservations(2);
        history.observe("alpha", 1, 10);
        history.observe("beta", 2, 20);
        history.observe("gamma", 3, 30);
        assertEquals(40L, history.observe("alpha", 4, 40).firstObservedAt());
    }

    @Test void completedObjectChecksRemainDistinctFromBusyWorkspace() {
        assertEquals(List.of("workspaceJobsActive"), ProgressObservations.waitingReasons(
                true, false, true, false, 0));
        assertEquals(List.of("validationNotCompleted", "modelNotReady"),
                ProgressObservations.waitingReasons(false, true, false, false, 0));
        assertEquals(List.of("problemCountNotStable"), ProgressObservations.waitingReasons(
                true, true, true, false, 2));
        assertEquals(List.of(), ProgressObservations.waitingReasons(true, true, true, true, 3));
        assertEquals(List.of("workspaceJobStateUnavailable"),
                ProgressObservations.waitingReasons(true, null, true, false, 0));
    }
}
