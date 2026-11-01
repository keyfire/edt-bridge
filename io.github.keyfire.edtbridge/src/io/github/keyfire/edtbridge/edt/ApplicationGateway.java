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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;
import com.google.inject.Injector;
import com._1c.g5.wiring.ServiceAccess;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationContextProvider;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

/** Native Applications view entries and sessions owned by this bridge instance. */
public final class ApplicationGateway {
    public static final class ApplicationInfo {
        public String id;
        public String name;
        public String type;
        public String infobaseUuid;
        public String lifecycle;
        public boolean bridgeSession;
    }
    public static final class Result {
        public boolean ok;
        public boolean applied;
        public boolean verified;
        public boolean already;
        public boolean associationVerified;
        public boolean associationAlready;
        public String associationContext;
        public boolean controllable;
        public String projectName;
        public String applicationId;
        public String applicationName;
        public String infobaseUuid;
        public String action;
        public String plan;
        public String message;
        public List<ApplicationInfo> applications = new ArrayList<>();
    }
    private record Session(Process process) {
        boolean active() { return process.isAlive(); }
    }

    static ExecutionContext executionContext() {
        ExecutionContext context = new ExecutionContext();
        // The native infobase client delegate requires the component type explicitly.
        context.setProperty("clientType", "com._1c.g5.v8.dt.platform.services.core.componentTypes.ThinClient");
        return context;
    }
    private static final Map<String, Session> SESSIONS = new HashMap<>();
    private static final Object LOCK = new Object();

    static Result associateExisting(IProject project, InfobaseReference base,
            IInfobaseAssociationManager associations, IApplicationManager applications,
            InfobaseAssociationContext context, boolean apply) throws Exception {
        if (context == null) throw new IllegalArgumentException("The active association context is required");
        Result result = new Result(); result.action = "associate"; result.projectName = project.getName();
        result.infobaseUuid = String.valueOf(base.getUuid());
        result.associationContext = context.toString();
        result.plan = "Associate registered infobase " + base.getName() + " with project " + project.getName()
                + " in its active EDT context";
        result.associationVerified = hasAssociation(project, base, associations, context);
        result.associationAlready = result.associationVerified;
        var existing = applications.findApplicationByInfobaseAndProject(base, project);
        if (existing.isPresent()) {
            result.ok = result.associationVerified;
            result.already = result.ok;
            if (result.ok) application(result, existing.get());
            else result.message = "An application exists, but its association in the active context could not be verified";
            return result;
        }
        result.ok = true;
        if (!apply) return result;
        if (!result.associationAlready) {
            associations.associate(project, base, InfobaseAssociationSettings.notSynchronized(context));
            result.applied = true;
        }
        try {
            result.associationVerified = hasAssociation(project, base, associations, context);
            if (!result.associationVerified) {
                result.ok = false;
                result.message = "EDT returned from associate, but the association was not found in the active context";
                return result;
            }
            var actual = awaitApplication(project, base, applications);
            result.ok = actual.isPresent();
            if (actual.isPresent()) {
                application(result, actual.get());
                result.already = result.associationAlready;
            } else {
                result.message = "The association is verified, but EDT has not exposed its application; "
                        + "the existing association was not removed or rewritten";
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result.ok = false;
            result.message = "Application verification was interrupted; re-read the native application list";
        } catch (Exception unavailable) {
            result.ok = false;
            result.message = "The association operation returned, but its final state could not be verified; "
                    + "re-read the native application list";
        }
        return result;
    }

    private static boolean hasAssociation(IProject project, InfobaseReference base,
            IInfobaseAssociationManager associations, InfobaseAssociationContext context) throws Exception {
        var actual = associations.getAssociation(project, context);
        return actual.isPresent() && actual.get().getInfobases().stream()
                .anyMatch(candidate -> base.getUuid().equals(candidate.getUuid()));
    }

    private static Optional<IApplication> awaitApplication(IProject project, InfobaseReference base,
            IApplicationManager applications) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (true) {
            var actual = applications.findApplicationByInfobaseAndProject(base, project);
            if (actual.isPresent() || System.nanoTime() >= deadline) return actual;
            Thread.sleep(25);
        }
    }

    private static void application(Result result, IApplication application) {
        result.applicationId = application.getId(); result.applicationName = application.getName(); result.verified = true;
    }

    public Result execute(String action, String projectName, String infobase, String applicationId, boolean apply) {
        Result result = new Result(); result.action = action; result.projectName = projectName;
        if (!List.of("list", "associate", "start", "stop").contains(action)) {
            result.message = "action must be list, associate, start or stop"; return result;
        }
        if (projectName == null || projectName.isBlank()) { result.message = "projectName is required"; return result; }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists() || !project.isOpen()) { result.message = "project not found or closed"; return result; }
        try {
            IApplicationManager manager = applicationManager();
            if (manager == null) { result.message = "EDT application manager unavailable"; return result; }
            synchronized (LOCK) {
                if ("associate".equals(action)) {
                    IInfobaseManager bases = ServiceAccess.get(IInfobaseManager.class);
                    IInfobaseAssociationManager associations = ServiceAccess.get(IInfobaseAssociationManager.class);
                    if (bases == null || associations == null || infobase == null || infobase.isBlank()) {
                        result.message = "infobase name/uuid and native infobase services are required"; return result;
                    }
                    List<InfobaseReference> matches = new ArrayList<>();
                    for (InfobaseReference candidate : InfobaseReferences.asPlainList(bases.getAll())) {
                        if (infobase.equals(String.valueOf(candidate.getUuid())) || infobase.equals(candidate.getName())) matches.add(candidate);
                    }
                    if (matches.size() != 1) { result.message = "registered infobase is missing or ambiguous; use its uuid"; return result; }
                    IInfobaseAssociationContextProvider contexts = ServiceAccess.get(IInfobaseAssociationContextProvider.class);
                    if (contexts == null) {
                        result.message = "EDT active association context provider unavailable; no association was changed";
                        return result;
                    }
                    return associateExisting(project, matches.get(0), associations, manager, contexts.get(project), apply);
                }
                if ("list".equals(action)) {
                    for (IApplication app : manager.getApplications(project)) {
                        ApplicationInfo info = new ApplicationInfo(); info.id = app.getId(); info.name = app.getName();
                        info.type = app.getType().getId(); info.lifecycle = manager.getLifecycleState(app).toString();
                        if (app instanceof IInfobaseApplication local) info.infobaseUuid = String.valueOf(local.getInfobase().getUuid());
                        Session session = SESSIONS.get(key(projectName, app.getId()));
                        info.bridgeSession = session != null && session.active(); result.applications.add(info);
                    }
                    result.ok = true; result.verified = true; return result;
                }
                if (applicationId == null || applicationId.isBlank()) { result.message = "applicationId is required"; return result; }
                var selected = manager.getApplication(project, applicationId);
                if (selected.isEmpty()) { result.message = "application not found in this project"; return result; }
                IApplication app = selected.get(); application(result, app);
                String key = key(projectName, applicationId); Session session = SESSIONS.get(key);
                if ("stop".equals(action)) {
                    if (session == null) {
                        result.message = "no controllable session started by this bridge; other clients are left running"; return result;
                    }
                    result.ok = true; result.controllable = true; result.plan = "Stop bridge session of " + app.getName();
                    if (!session.active()) { result.already = true; SESSIONS.remove(key); return result; }
                    if (!apply) return result;
                    session.process().destroy();
                    result.applied = true;
                    result.verified = !session.active();
                    if (result.verified) SESSIONS.remove(key);
                    else result.message = "stop requested; the session is still exiting";
                    return result;
                }
                if (session != null && session.active()) {
                    result.ok = true; result.already = true; result.controllable = true; return result;
                }
                if (!(app instanceof IInfobaseApplication)) {
                    result.message = "only registered infobase applications can be started here"; return result;
                }
                result.ok = true; result.plan = "Start existing EDT application " + app.getName();
                if (!apply) return result;
                result.verified = false;
                Process process = manager.start(app, executionContext(), new NullProgressMonitor()).orElse(null);
                result.applied = true;
                if (process != null) {
                    Session created = new Session(process);
                    SESSIONS.put(key, created);
                    result.controllable = true;
                    result.verified = created.active();
                } else {
                    result.verified = false;
                    result.message = "EDT returned no process handle; the client start could not be verified and any client must be closed in EDT";
                }
                return result;
            }
        } catch (Exception failure) {
            result.ok = false;
            result.verified = false;
            // Native launch exceptions may embed credentials from saved infobase parameters.
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            StackTraceElement[] trace = cause.getStackTrace();
            result.message = "EDT application operation failed (" + failure.getClass().getSimpleName()
                    + " / " + cause.getClass().getSimpleName() + ")"
                    + (trace.length == 0 ? "" : " at " + trace[0].getClassName() + "." + trace[0].getMethodName());
            return result;
        }
    }
    private static String key(String project, String id) { return project + "\n" + id; }

    private static IApplicationManager applicationManager() {
        IApplicationManager manager = ServiceAccess.get(IApplicationManager.class);
        if (manager != null) return manager;
        try {
            Bundle bundle = Platform.getBundle("com.e1c.g5.dt.applications");
            if (bundle == null) return null;
            bundle.start(Bundle.START_TRANSIENT);
            Class<?> type = bundle.loadClass("com.e1c.g5.dt.internal.applications.ApplicationsPlugin");
            Object plugin = type.getMethod("getDefault").invoke(null);
            var accessor = type.getDeclaredMethod("getInjector"); accessor.setAccessible(true);
            Injector injector = (Injector) accessor.invoke(plugin);
            return injector.getInstance(IApplicationManager.class);
        } catch (Exception unavailable) { return null; }
    }
}
