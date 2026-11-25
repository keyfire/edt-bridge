package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.jupiter.api.Test;

import io.github.keyfire.edtbridge.core.Jobs;

class CleanCancellationRegressionTest {
    @Test
    void theBackgroundJobReportsDistinctStagesWhileCleaningAndRebuilding() throws InterruptedException {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var background = new Jobs<Void>("sample", executor, System::currentTimeMillis, ignored -> false);
            var stages = new ArrayList<String>();
            var f = new Fixture();
            f.onBuild = () -> stages.add(background.list().get(0).stage());
            var job = background.submit("edt_clean_project", () -> {
                assertTrue(f.run().applied);
                return null;
            });

            assertEquals(Jobs.State.SUCCEEDED, background.await(job.id(), 5_000).state());
            assertEquals(2, stages.size());
            assertTrue(stages.stream().noneMatch(String::isBlank), stages.toString());
            assertNotEquals(stages.get(0), stages.get(1));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aCanceledCleanIsNotReportedAsOkAndKeepsItsStage() {
        var f = new Fixture();
        f.failure = new OperationCanceledException();
        var result = f.run();
        assertFalse(result.ok);
        assertFalse(result.applied);
        assertTrue(result.canceled);
        assertEquals("CLEAN_BUILD", result.stage);
        assertEquals("unknown", result.cancelCause);
        assertNotNull(result.failureOrigin);
        assertNotNull(result.nextStep);
    }

    @Test
    void aCanceledFullBuildPreservesTheCompletedClean() {
        var f = new Fixture();
        f.failKind = IncrementalProjectBuilder.FULL_BUILD;
        f.failure = new OperationCanceledException();
        var result = f.run();
        assertFalse(result.ok);
        assertTrue(result.applied);
        assertEquals("FULL_BUILD", result.stage);
        assertTrue(result.completedStages.contains("CLEAN_BUILD"));
    }

    @Test
    void aCoreFailurePreservesItsStatusWithoutSecrets() {
        var f = new Fixture();
        f.failure = new CoreException(new Status(IStatus.ERROR, "sample.plugin", 73,
                "synthetic failure token=private-value", null));
        var result = f.run();
        assertFalse(result.ok);
        assertEquals(73, result.status.code);
        assertEquals("sample.plugin", result.status.plugin);
        assertFalse(result.message.contains("private-value"));
        assertFalse(result.status.message.contains("private-value"));
    }

    @Test
    void anInterruptedServiceWaitRestoresInterruptAndDoesNotLoseAppliedClean() {
        var f = new Fixture();
        f.serviceWait = () -> { throw new InterruptedException("synthetic interruption"); };
        try {
            var result = f.run();
            assertFalse(result.ok);
            assertTrue(result.applied);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals("WAIT_AFTER_CLEAN", result.stage);
            assertEquals("threadInterrupted", result.cancelCause);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void waitsForProjectServicesBeforeRebuildingAndBeforeValidation() {
        var f = new Fixture();
        var transitions = new ArrayList<String>();
        f.onBuild = () -> transitions.add("build");
        f.serviceWait = () -> transitions.add("wait");
        var result = f.run();
        assertEquals(List.of("build", "wait", "build", "wait"), transitions);
        assertEquals(List.of("CLEAN_BUILD", "WAIT_AFTER_CLEAN", "FULL_BUILD", "WAIT_AFTER_REBUILD"),
                result.completedStages);
    }

    @Test
    void aRestartTimeLimitPreservesCleanAndSkipsRebuild() {
        var f = new Fixture();
        f.serviceWait = () -> ProjectGateway.waitForCleanServices(() -> false, new NullProgressMonitor(),
                System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(1));
        var result = f.run();
        assertFalse(result.ok);
        assertTrue(result.applied);
        assertFalse(result.canceled);
        assertEquals("WAIT_AFTER_CLEAN", result.stage);
        assertEquals(List.of("CLEAN_BUILD"), result.completedStages);
    }

    @Test
    void anExplicitCancellationStopsTheServiceWaitBeforeReadingItsState() {
        var monitor = new NullProgressMonitor();
        monitor.setCanceled(true);
        assertThrows(OperationCanceledException.class, () -> ProjectGateway.waitForCleanServices(
                () -> { throw new AssertionError("the service state was read after cancellation"); },
                monitor, System.nanoTime()));
    }

    @Test
    void aWrappedSdkCancellationStillReportsCancellation() {
        var f = new Fixture();
        f.failure = new IllegalStateException("synthetic wrapper", new OperationCanceledException());
        var result = f.run();
        assertFalse(result.ok);
        assertTrue(result.canceled);
        assertEquals("unknown", result.cancelCause);
    }

    @Test
    void jobDiagnosticsAreBoundedAndRedactCredentialFields() {
        var f = new Fixture();
        f.failure = new OperationCanceledException();
        f.active = new Job[12];
        for (int i = 0; i < f.active.length; i++) {
            f.active[i] = Job.create("sample job " + i + " token=private-value",
                    (org.eclipse.core.runtime.jobs.IJobFunction) monitor -> Status.OK_STATUS);
        }
        var result = f.run();
        assertEquals(8, result.jobs.size());
        assertTrue(result.jobsTruncated);
        assertTrue(result.jobs.stream().noneMatch(job -> job.name.contains("private-value")));
    }

    @Test
    void authorizationBearerTokensDoNotSurviveDiagnosticRedaction() {
        var f = new Fixture();
        f.failure = new CoreException(new Status(IStatus.ERROR, "sample.plugin", 73,
                "Authorization: Bearer synthetic-value", null));
        var result = f.run();
        assertFalse(result.message.contains("synthetic-value"));
        assertFalse(result.status.message.contains("synthetic-value"));
    }

    @Test
    void authorizationBasicCredentialsDoNotSurviveDiagnostics() {
        var f = new Fixture();
        f.failure = new CoreException(new Status(IStatus.ERROR, "sample.plugin", 73,
                "Authorization: Basic c3ludGhldGljOnNlY3JldA==", null));
        var result = f.run();
        assertFalse(result.message.contains("c3ludGhldGljOnNlY3JldA=="));
        assertFalse(result.status.message.contains("c3ludGhldGljOnNlY3JldA=="));
    }

    @Test
    void quotedJsonCredentialKeysDoNotSurviveDiagnostics() {
        var f = new Fixture();
        f.failure = new CoreException(new Status(IStatus.ERROR, "sample.plugin", 73,
                "{\"password\":\"synthetic-secret\",\"token\":\"synthetic-token\"}", null));
        var result = f.run();
        for (String secret : new String[] {"synthetic-secret", "synthetic-token"}) {
            assertFalse(result.message.contains(secret));
            assertFalse(result.status.message.contains(secret));
        }
    }

    private static final class Fixture {
        int failKind = IncrementalProjectBuilder.CLEAN_BUILD;
        Exception failure;
        ProjectGateway.CleanWait serviceWait = () -> {};
        Job[] active = new Job[0];
        Runnable onBuild = () -> {};
        final IProject project = (IProject) Proxy.newProxyInstance(IProject.class.getClassLoader(),
                new Class<?>[] {IProject.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return "sample";
                    if (!method.getName().equals("build")) throw new AssertionError(method.getName());
                    onBuild.run();
                    if ((Integer) args[0] == failKind && failure != null) throw failure;
                    return null;
                });
        final IJobManager jobs = (IJobManager) Proxy.newProxyInstance(IJobManager.class.getClassLoader(),
                new Class<?>[] {IJobManager.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "find" -> active;
                    default -> throw new AssertionError(method.getName());
                });
        ProjectGateway.CleanProjectResult run() {
            var result = new ProjectGateway.CleanProjectResult();
            result.ok = true;
            ProjectGateway.executeCleanBuild(project, true, new NullProgressMonitor(), jobs, result, serviceWait);
            return result;
        }
    }
}
