package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.Test;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com.e1c.g5.v8.dt.check.ICheckScheduler;
import com.e1c.g5.v8.dt.check.settings.ICheckProcessPreferences;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

class ValidationSchedulerRegressionTest {
    @Test
    void schedulesOnlyTheResolvedTopObjectInsideAReadonlyTransaction() {
        var f = new Fixture();
        var result = f.validate();
        assertEquals(1, f.schedules);
        assertEquals(1, f.waits);
        assertTrue(result.validationRequested);
        assertTrue(result.validationCompleted);
        assertNull(result.warning);
    }

    @Test
    void aMissingObjectNeverTurnsIntoAWholeProjectRequest() {
        var f = new Fixture();
        f.found = false;
        var result = f.validate();
        assertEquals(0, f.schedules);
        assertEquals(0, f.waits);
        assertFalse(result.validationCompleted);
        assertNotNull(result.warning);
    }

    @Test
    void disabledChecksRemainDisabledAndIncomplete() {
        var f = new Fixture();
        f.disabled = true;
        var result = f.validate();
        assertEquals(0, f.schedules);
        assertFalse(result.validationRequested);
        assertFalse(result.validationCompleted);
        assertNotNull(result.warning);
    }

    @Test
    void anUnfinishedPipelineDoesNotBecomeCompleted() {
        var f = new Fixture();
        f.finished = false;
        var result = f.validate();
        assertEquals(1, f.schedules);
        assertTrue(result.validationRequested);
        assertFalse(result.validationCompleted);
        assertNotNull(result.warning);
    }

    @Test
    void anUnavailableSchedulerFailsClosed() {
        var f = new Fixture();
        var result = ProjectGateway.revalidateObject(f.project, "CommonModule.Sample", f.model,
                null, f.repository, f.derived, new NullProgressMonitor(), 10);
        assertFalse(result.validationRequested);
        assertFalse(result.validationCompleted);
        assertNotNull(result.warning);
        assertEquals(0, f.waits);
    }

    @Test
    void anExactMetadataFileResolvesToItsOwnerWithoutChangingItsName() {
        assertEquals("CommonModule.Sample", ProjectGateway.validationFqn(null,
                "src/CommonModules/Sample/Sample.mdo"));
        assertNull(ProjectGateway.validationFqn(null, "src/CommonModules/../Sample.mdo"));
        assertNull(ProjectGateway.validationFqn(null, "src/Unknown/Sample/Sample.mdo"));
    }

    @Test
    void conflictingObjectAndFileFiltersDoNotValidateTheWrongObject() {
        assertNull(ProjectGateway.validationFqn("CommonModule.Other",
                "src/CommonModules/Sample/Sample.mdo"));
    }

    @Test
    void automaticChecksDoNotRequireManualRebuildMode() {
        var f = new Fixture();
        f.manual = false;
        var result = f.validate();
        assertEquals(1, f.schedules);
        assertTrue(result.validationRequested);
        assertTrue(result.validationCompleted);
    }

    private static final class Fixture {
        boolean found = true;
        boolean manual = true;
        boolean disabled;
        boolean finished = true;
        int schedules;
        int waits;
        final NullProgressMonitor monitor = new NullProgressMonitor();
        final IProject project = proxy(IProject.class, (method, args) -> switch (method) {
            case "getName" -> "Sample";
            default -> throw new AssertionError(method);
        });
        final IBmObject object = proxy(IBmObject.class, (method, args) -> switch (method) {
            case "bmGetId" -> 73L;
            default -> throw new AssertionError(method);
        });
        final IBmTransaction transaction = proxy(IBmTransaction.class, (method, args) -> switch (method) {
            case "isReadOnly" -> true;
            case "getTopObjectByFqn" -> {
                assertEquals("CommonModule.Sample", args[0]);
                yield found ? object : null;
            }
            default -> throw new AssertionError("unexpected transaction write: " + method);
        });
        final IBmModel model = proxy(IBmModel.class, (method, args) -> {
            if (!method.equals("executeReadonlyTask")) throw new AssertionError(method);
            return ((IBmTask<?>) args[0]).execute(transaction, monitor);
        });
        final ICheckProcessPreferences preferences = proxy(ICheckProcessPreferences.class, (method, args) -> {
            if (!method.equals("isManualRebuildsEnabled")) throw new AssertionError(method);
            return manual;
        });
        final ICheckRepository repository = proxy(ICheckRepository.class, (method, args) -> switch (method) {
            case "isMassiveCheckProcessDisabled" -> disabled;
            case "getCheckProcessPreferences" -> preferences;
            default -> throw new AssertionError("unexpected settings mutation: " + method);
        });
        final ICheckScheduler scheduler = proxy(ICheckScheduler.class, (method, args) -> {
            if (!method.equals("scheduleValidation")) throw new AssertionError(method);
            assertSame(project, args[0]);
            assertEquals(Set.of(), args[1]);
            assertEquals(List.of(73L), args[2]);
            assertEquals(false, args[3]);
            assertSame(transaction, args[4]);
            schedules++;
            return null;
        });
        final IDerivedDataManager derived = proxy(IDerivedDataManager.class, (method, args) -> switch (method) {
            case "waitAllComputations" -> { waits++; yield finished; }
            case "isAllComputed" -> finished;
            default -> throw new AssertionError(method);
        });
        ProjectGateway.RefreshResult validate() {
            return ProjectGateway.revalidateObject(project, "CommonModule.Sample", model, scheduler,
                    repository, derived, monitor, 10);
        }
    }

    @FunctionalInterface private interface Invocation {
        Object invoke(String method, Object[] args) throws Throwable;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation action) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> action.invoke(method.getName(), args));
    }
}
