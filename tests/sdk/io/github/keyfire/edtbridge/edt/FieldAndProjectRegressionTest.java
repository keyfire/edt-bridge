package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.UUID;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.Test;
import com._1c.g5.v8.dt.metadata.mdclass.*;

class FieldAndProjectRegressionTest {
    @Test void catalogOptionsKeepTheSameFieldAndUuid() throws Exception {
        CatalogAttribute field = MdClassFactory.eINSTANCE.createCatalogAttribute();
        UUID id = UUID.randomUUID(); field.setUuid(id);
        options(field, Map.of("use", "ForFolder", "multiLine", true));
        assertEquals("ForFolder", field.getUse().getLiteral());
        assertTrue(field.isMultiLine()); assertEquals(id, field.getUuid());
        options(field, Map.of("use", "ForItem", "multiLine", false));
        assertEquals("ForItem", field.getUse().getLiteral()); assertFalse(field.isMultiLine());
        options(field, Map.of("use", "ForFolderAndItem"));
        assertEquals("ForFolderAndItem", field.getUse().getLiteral());
    }
    @Test void invalidFieldOptionDoesNotPartiallyApply() {
        CatalogAttribute field = MdClassFactory.eINSTANCE.createCatalogAttribute();
        assertThrows(IllegalArgumentException.class,
                () -> options(field, Map.of("multiLine", true, "use", "Unknown")));
        assertFalse(field.isMultiLine());
        assertThrows(IllegalArgumentException.class, () -> options(field, Map.of("multiLine", "true")));
        assertFalse(field.isMultiLine());
    }
    @Test void folderUseIsRejectedOnANonHierarchicalField() {
        DocumentAttribute field = MdClassFactory.eINSTANCE.createDocumentAttribute();
        assertThrows(IllegalArgumentException.class,
                () -> options(field, Map.of("multiLine", true, "use", "ForFolder")));
        assertFalse(field.isMultiLine());
    }
    @Test void closedProjectOpensOnceWithoutReimport() throws Exception {
        int[] openCalls = {0}; boolean[] open = {false};
        IProject project = project(open, openCalls, true, false);
        ProjectGateway.ImportProjectResult dry = existing(project, false);
        assertTrue(dry.ok); assertTrue(dry.already); assertFalse(dry.open); assertFalse(dry.applied);
        assertEquals(0, openCalls[0]);
        ProjectGateway.ImportProjectResult applied = existing(project, true);
        assertTrue(applied.ok); assertTrue(applied.open); assertTrue(applied.applied);
        ProjectGateway.ImportProjectResult repeated = existing(project, true);
        assertTrue(repeated.ok); assertTrue(repeated.open); assertFalse(repeated.applied);
        assertEquals(1, openCalls[0]);
    }
    @Test void missingOrRefusedProjectDoesNotReportSuccess() throws Exception {
        assertFalse(existing(project(new boolean[]{false}, new int[]{0}, false, false), true).ok);
        ProjectGateway.ImportProjectResult refused = existing(
                project(new boolean[]{false}, new int[]{0}, true, true), true);
        assertFalse(refused.ok); assertFalse(refused.open); assertFalse(refused.applied);
        assertNotNull(refused.message);
    }
    private static IProject project(boolean[] open, int[] calls, boolean exists, boolean refused) {
        return (IProject) Proxy.newProxyInstance(IProject.class.getClassLoader(), new Class<?>[]{IProject.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "exists" -> exists;
                    case "isOpen" -> open[0];
                    case "getName" -> "Sample";
                    case "open" -> { calls[0]++; if (refused) throw new CoreException(Status.error("Open refused")); open[0] = true; yield null; }
                    default -> throw new AssertionError("unexpected mutation or call: " + method.getName());
                });
    }
    private static ProjectGateway.ImportProjectResult existing(IProject project, boolean apply) throws Exception {
        ProjectGateway.ImportProjectResult result = new ProjectGateway.ImportProjectResult();
        Method method = ProjectGateway.class.getDeclaredMethod("reuseExistingProject", IProject.class,
                ProjectGateway.ImportProjectResult.class, boolean.class);
        method.setAccessible(true); invoke(method, project, result, apply); return result;
    }
    private static void options(EObject field, Map<String, Object> values) throws Exception {
        Method method = MetadataFields.class.getDeclaredMethod("applyOptions", EObject.class, Map.class);
        method.setAccessible(true); invoke(method, field, values);
    }
    private static void invoke(Method method, Object... args) throws Exception {
        try { method.invoke(null, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException) throw (RuntimeException) failure.getCause();
            throw failure;
        }
    }
}
