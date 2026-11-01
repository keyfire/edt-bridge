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
import java.lang.reflect.*;
import java.util.*;
import org.eclipse.core.resources.IProject;
import org.junit.jupiter.api.Test;
import com._1c.g5.v8.dt.platform.services.core.infobases.*;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.e1c.g5.dt.applications.*;

class ApplicationContextRegressionTest {
    @Test void associationUsesTheActiveBranchContext() throws Exception {
        Fixture f = new Fixture();
        Object result = f.run(true);
        assertEquals(f.context, f.writtenContext);
        assertTrue(flag(result, "ok")); assertTrue(flag(result, "associationVerified"));
        assertTrue(flag(result, "verified")); assertEquals(1, f.writes);
        assertFalse(flag(f.run(true), "applied")); assertEquals(1, f.writes);
    }
    @Test void dryRunDoesNotAssociateOrCreateAnApplication() throws Exception {
        Fixture f = new Fixture(); Object result = f.run(false);
        assertTrue(flag(result,"ok")); assertFalse(flag(result,"applied"));
        assertEquals(0,f.writes); assertFalse(f.linked);
    }
    @Test void providerSuccessDoesNotProveTheAssociationWasSaved() throws Exception {
        Fixture f = new Fixture(); f.persist = false;
        Object result=f.run(true);
        assertFalse(flag(result,"ok")); assertFalse(flag(result,"associationVerified"));
        assertFalse(flag(result,"verified"));
        assertFalse(String.valueOf(value(result,"message")).contains("was saved"));
    }
    @Test void delayedApplicationIsVerifiedAfterTheAssociationReadback() throws Exception {
        Fixture f = new Fixture(); f.delay = 2;
        Object result=f.run(true);
        assertTrue(flag(result,"ok")); assertTrue(flag(result,"associationVerified"));
        assertTrue(flag(result,"verified")); assertTrue(f.reads >= 3);
    }
    @Test void savedAssociationWithoutApplicationIsNotWrittenAgain() throws Exception {
        Fixture f = new Fixture(); f.linked=true; f.expose=false;
        Object result=f.run(true);
        assertFalse(flag(result,"ok")); assertTrue(flag(result,"associationVerified"));
        assertTrue(flag(result,"associationAlready")); assertFalse(flag(result,"applied"));
        assertEquals(0,f.writes);
    }
    private static boolean flag(Object o,String n) throws Exception { return (boolean)value(o,n); }
    private static Object value(Object o,String n) throws Exception {return o.getClass().getField(n).get(o);}
    private static class Fixture {
        final InfobaseAssociationContext context=InfobaseAssociationContext.of("refs/heads/feature/sample");
        final IProject project; final InfobaseReference base; final IApplication app;
        final IInfobaseAssociation association;
        final IInfobaseAssociationManager associations; final IApplicationManager applications;
        boolean linked,persist=true,expose=true;int writes,reads,delay;
        InfobaseAssociationContext writtenContext;
        Fixture() {
            project=(IProject)Proxy.newProxyInstance(IProject.class.getClassLoader(),new Class<?>[]{IProject.class},
                (p,m,args)->switch(m.getName()){case "getName"->"Sample";case "equals"->p==args[0];case "hashCode"->1;default->throw new AssertionError(m.getName());});
            base=InfobaseReferences.newFileInfobaseReference("C:/sample/base");base.setName("SampleBase");base.setUuid(UUID.randomUUID());
            app=(IApplication)Proxy.newProxyInstance(IApplication.class.getClassLoader(),new Class<?>[]{IApplication.class},
                (p,m,args)->switch(m.getName()){case "getId"->"sample-app";case "getName"->"SampleBase";case "getProject"->project;default->throw new AssertionError(m.getName());});
            association=(IInfobaseAssociation)Proxy.newProxyInstance(IInfobaseAssociation.class.getClassLoader(),new Class<?>[]{IInfobaseAssociation.class},
                (p,m,args)->switch(m.getName()){case "getProject"->project;case "getInfobases"->List.of(base);case "getDefaultInfobase"->base;default->throw new AssertionError(m.getName());});
            associations=(IInfobaseAssociationManager)Proxy.newProxyInstance(IInfobaseAssociationManager.class.getClassLoader(),new Class<?>[]{IInfobaseAssociationManager.class},
                (p,m,args)->switch(m.getName()){
                    case "getAssociation"->{assertEquals(context,args[1]);yield linked?Optional.of(association):Optional.empty();}
                    case "associate"->{writes++;writtenContext=((InfobaseAssociationSettings)args[2]).getContext();assertEquals(context,writtenContext);linked=persist;yield null;}
                    default->throw new AssertionError("Unexpected mutation: "+m.getName());
                });
            applications=(IApplicationManager)Proxy.newProxyInstance(IApplicationManager.class.getClassLoader(),new Class<?>[]{IApplicationManager.class},
                (p,m,args)->switch(m.getName()){
                    case "findApplicationByInfobaseAndProject"->{reads++;yield linked&&expose&&reads>delay?Optional.of(app):Optional.empty();}
                    default->throw new AssertionError(m.getName());
                });
        }
        Object run(boolean apply) throws Exception {
            Method m=ApplicationGateway.class.getDeclaredMethod("associateExisting",IProject.class,InfobaseReference.class,
                IInfobaseAssociationManager.class,IApplicationManager.class,InfobaseAssociationContext.class,boolean.class);
            m.setAccessible(true);try{return m.invoke(null,project,base,associations,applications,context,apply);}
            catch(InvocationTargetException failure){throw (Exception)failure.getCause();}
        }
    }
}
