package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import io.github.keyfire.edtbridge.tools.ProjectErrorsTool;

class ValidationRefreshRegressionTest {
    @Test
    void quietJobsWithoutValidationEvidenceDoNotMakeRefreshComplete() throws Exception {
        var report = report();
        report.refreshed.settled = true;
        report.refreshed.sawValidation = false;
        assertFalse(complete(report));
    }

    @Test
    void anOldMetadataMarkerRemainsIncompleteAfterAQuietWait() throws Exception {
        var report = report();
        report.staleCount = 1;
        report.refreshed.settled = true;
        report.refreshed.sawValidation = true;
        assertFalse(complete(report));
    }

    @Test
    void completedValidationCanMakeACurrentReportComplete() throws Exception {
        var report = report();
        report.refreshed.settled = true;
        report.refreshed.validationRequested = true;
        report.refreshed.validationCompleted = true;
        assertTrue(complete(report));
    }

    @Test
    void countOnlyDoesNotClaimFreshnessForUninspectedMarkers() throws Exception {
        var report = report();
        report.countOnly = true;
        report.staleCount = -1;
        report.markerFreshnessComplete = false;
        report.refreshed.settled = true;
        report.refreshed.validationCompleted = true;
        assertFalse(complete(report));
    }

    @Test
    void aTruncatedListDoesNotClaimFreshnessForUnlistedMarkers() throws Exception {
        var report = report();
        report.truncated = true;
        report.markerFreshnessComplete = false;
        report.refreshed.settled = true;
        report.refreshed.validationCompleted = true;
        assertFalse(complete(report));
    }

    @Test
    void baselineDisplayLimitsPreserveTheAlreadyInspectedSnapshot() throws Exception {
        var report = report();
        report.countOnly = true;
        report.truncated = true;
        report.refreshed.settled = true;
        report.refreshed.validationCompleted = true;
        assertTrue(complete(report));
    }

    @Test
    void unresolvedMarkerCoverageCannotClaimACompleteReport() throws Exception {
        var report = report();
        var problem = new ProjectGateway.Problem();
        problem.source = "edt-check";
        report.problems.add(problem);
        report.total = 1;
        report.refreshed.settled = true;
        report.refreshed.validationCompleted = true;
        ProjectGateway.finishFreshness(report, null);
        assertFalse(report.markerFreshnessComplete);
        assertEquals(1, report.unresolvedEdtMarkers);
        assertFalse(complete(report));
    }

    @Test
    void anEmptyInspectedSnapshotStillHasCompleteFreshnessCoverage() {
        var report = report();
        ProjectGateway.finishFreshness(report, null);
        assertTrue(report.markerFreshnessComplete);
        assertEquals(0, report.staleCount);
    }

    private static ProjectGateway.ProblemReport report() {
        var report = new ProjectGateway.ProblemReport();
        report.modelReady = true;
        report.markerFreshnessComplete = true;
        report.staleCount = 0;
        report.refreshed = new ProjectGateway.RefreshResult();
        report.refreshed.resources.add("src/CommonModules/Sample");
        return report;
    }

    private static boolean complete(ProjectGateway.ProblemReport report) throws Exception {
        Method method = ProjectErrorsTool.class.getDeclaredMethod("complete", ProjectGateway.ProblemReport.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, report);
    }
}
