package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
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

class CleanCancellationRegressionTest {
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
    void anInterruptedJoinRestoresInterruptAndDoesNotLoseAppliedClean() {
        var f = new Fixture();
        f.interruptJoin = true;
        try {
            var result = f.run();
            assertFalse(result.ok);
            assertTrue(result.applied);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals("JOIN_MANUAL_BUILD", result.stage);
            assertEquals("threadInterrupted", result.cancelCause);
        } finally {
            Thread.interrupted();
        }
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
        boolean interruptJoin;
        Job[] active = new Job[0];
        final IProject project = (IProject) Proxy.newProxyInstance(IProject.class.getClassLoader(),
                new Class<?>[] {IProject.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("build")) throw new AssertionError(method.getName());
                    if ((Integer) args[0] == failKind && failure != null) throw failure;
                    return null;
                });
        final IJobManager jobs = (IJobManager) Proxy.newProxyInstance(IJobManager.class.getClassLoader(),
                new Class<?>[] {IJobManager.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "join" -> {
                        if (interruptJoin) throw new InterruptedException("synthetic interruption");
                        yield null;
                    }
                    case "find" -> active;
                    default -> throw new AssertionError(method.getName());
                });
        ProjectGateway.CleanProjectResult run() {
            var result = new ProjectGateway.CleanProjectResult();
            result.ok = true;
            ProjectGateway.executeCleanBuild(project, true, new NullProgressMonitor(), jobs, result);
            return result;
        }
    }
}
