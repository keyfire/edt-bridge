/*
 * edt-bridge - a 1C:EDT bridge that exposes the live EDT model over MCP.
 * Copyright 2026 edt-bridge contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.keyfire.edtbridge.edt;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.Test;
import com._1c.g5.v8.dt.core.model.IModelEditingSupport;
import com._1c.g5.v8.dt.core.model.EditingMode;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

class NativeServicesRegressionTest {
    @Test void editabilityUsesDirectNativePolicyAndDeniesLockedObjects() throws Exception {
        EObject owner = MdClassFactory.eINSTANCE.createCatalog();
        for (boolean editable : List.of(true, false)) {
            IModelEditingSupport policy = new IModelEditingSupport() {
                public boolean canEdit(EObject object, EditingMode mode) {
                    assertSame(owner, object); assertEquals(EditingMode.DIRECT, mode); return editable;
                }
                public boolean canDelete(EObject object, EditingMode mode) { throw new AssertionError("not an edit check"); }
            };
            Object result = inspect(owner, policy);
            assertEquals(editable ? "EDITABLE" : "READ_ONLY", property(result, "status"));
        }
    }
    @Test void unavailablePolicyOrObjectIsNeverReportedEditable() throws Exception {
        assertEquals("UNKNOWN", property(inspect(MdClassFactory.eINSTANCE.createCatalog(), null), "status"));
        assertEquals("UNKNOWN", property(inspect(null, null), "status"));
    }
    @Test void throwingPolicyFailsClosed() throws Exception {
        IModelEditingSupport policy = new IModelEditingSupport() {
            public boolean canEdit(EObject object, EditingMode mode) { throw new IllegalStateException("unavailable"); }
            public boolean canDelete(EObject object, EditingMode mode) { return false; }
        };
        assertEquals("UNKNOWN", property(inspect(MdClassFactory.eINSTANCE.createCatalog(), policy), "status"));
    }
    @Test void associationReusesAnExistingApplicationWithoutWriting() throws Exception {
        Fixture fixture = new Fixture(true);
        Object result = fixture.associate(true);
        assertEquals(true, property(result, "ok")); assertEquals(false, property(result, "applied"));
        assertEquals(0, fixture.writes);
    }
    @Test void associationIsDryRunFirstAndVerifiesNativeReadback() throws Exception {
        Fixture fixture = new Fixture(false);
        Object dry = fixture.associate(false);
        assertEquals(true, property(dry, "ok")); assertEquals(0, fixture.writes);
        Object result = fixture.associate(true);
        assertEquals(true, property(result, "ok")); assertEquals(true, property(result, "applied"));
        assertEquals(1, fixture.writes);
        fixture.associate(true); assertEquals(1, fixture.writes);
    }
    @Test void nativeClientContextSelectsTheThinClient() {
        assertEquals("com._1c.g5.v8.dt.platform.services.core.componentTypes.ThinClient",
                ApplicationGateway.executionContext().getProperty("clientType").orElseThrow());
        assertTrue(ApplicationGateway.executionContext().getProperty("activeLaunch").isEmpty());
    }
    @Test void aFailedAssociationReadbackKeepsTheAppliedResult() throws Exception {
        Fixture fixture = new Fixture(false);
        fixture.failReadback = true;
        Object result = fixture.associate(true);
        assertEquals(false, property(result, "ok"));
        assertEquals(true, property(result, "applied"));
        assertEquals(false, property(result, "verified"));
        assertEquals(1, fixture.writes);
    }
    private static class Fixture {
        final IProject project; final InfobaseReference base; final IApplication app;
        final IInfobaseAssociationManager associations; final IApplicationManager applications;
        boolean linked; boolean failReadback; int writes;
        Fixture(boolean already) {
            linked=already;
            project=(IProject)Proxy.newProxyInstance(IProject.class.getClassLoader(),new Class<?>[]{IProject.class},
                    (proxy,method,args)->switch(method.getName()) {
                        case "getName"->"Sample"; case "equals"->proxy==args[0]; case "hashCode"->1;
                        default->throw new AssertionError(method.getName());
                    });
            base=InfobaseReferences.newFileInfobaseReference("C:/sample/base"); base.setName("SampleBase"); base.setUuid(UUID.randomUUID());
            app=(IApplication)Proxy.newProxyInstance(IApplication.class.getClassLoader(),new Class<?>[]{IApplication.class},
                    (proxy,method,args)->switch(method.getName()) {
                        case "getId"->"sample-app"; case "getName"->"SampleBase"; case "getProject"->project;
                        default->throw new AssertionError(method.getName());
                    });
            associations=(IInfobaseAssociationManager)Proxy.newProxyInstance(IInfobaseAssociationManager.class.getClassLoader(),
                    new Class<?>[]{IInfobaseAssociationManager.class},(proxy,method,args)->switch(method.getName()) {
                        case "associate"->{assertSame(project,args[0]); assertSame(base,args[1]); writes++;linked=true;yield null;}
                        case "getAssociation"-> linked ? Optional.of((IInfobaseAssociation)Proxy.newProxyInstance(
                            IInfobaseAssociation.class.getClassLoader(),new Class<?>[]{IInfobaseAssociation.class},
                            (p,m,a)->switch(m.getName()) {
                                case "getProject"->project;case "getInfobases"->List.of(base);case "getDefaultInfobase"->base;
                                default->throw new AssertionError(m.getName());
                            })) : Optional.empty();
                        default->throw new AssertionError("unexpected association mutation: "+method.getName());
                    });
            applications=(IApplicationManager)Proxy.newProxyInstance(IApplicationManager.class.getClassLoader(),
                    new Class<?>[]{IApplicationManager.class},(proxy,method,args)->switch(method.getName()) {
                        case "findApplicationByInfobaseAndProject"->{
                            if(failReadback && writes>0)throw new IllegalStateException("readback unavailable");
                            yield linked?Optional.of(app):Optional.empty();
                        }
                        default->throw new AssertionError("unexpected application mutation: "+method.getName());
                    });
        }
        Object associate(boolean apply) throws Exception {
            Class<?> type=Class.forName("io.github.keyfire.edtbridge.edt.ApplicationGateway");
            Method method=type.getDeclaredMethod("associateExisting",IProject.class,InfobaseReference.class,
                    IInfobaseAssociationManager.class,IApplicationManager.class,InfobaseAssociationContext.class,boolean.class);
            method.setAccessible(true);return invoke(method,project,base,associations,applications,InfobaseAssociationContext.empty(),apply);
        }
    }
    private static Object property(Object result,String name) throws Exception {return result.getClass().getField(name).get(result);}
    private static Object inspect(EObject owner,IModelEditingSupport policy) throws Exception {
        Class<?> type=Class.forName("io.github.keyfire.edtbridge.edt.SupportStatusGateway");
        Method method=type.getDeclaredMethod("inspect",EObject.class,IModelEditingSupport.class);
        method.setAccessible(true);return invoke(method,owner,policy);
    }
    private static Object invoke(Method method,Object... args) throws Exception {
        try{return method.invoke(null,args);}catch(InvocationTargetException failure){
            if(failure.getCause() instanceof RuntimeException)throw(RuntimeException)failure.getCause();throw failure;
        }
    }
}
