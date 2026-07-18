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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.model.IThread;

import com._1c.g5.v8.dt.debug.core.IDebugConfigurationAttributes;
import com._1c.g5.v8.dt.debug.core.IDebugConfigurationTypes;
import com._1c.g5.v8.dt.debug.core.launchconfigurations.DebugServerLocationType;
import com._1c.g5.v8.dt.debug.core.launchconfigurations.DtLaunch;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTargetManager;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugTargetThread;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;
import com._1c.g5.v8.dt.debug.core.model.IDebugMonitoringManager;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType;
import com._1c.g5.v8.dt.debug.model.area.DebugAreaInfo;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationEngine;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationRequest;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationResult;
import com._1c.g5.v8.dt.debug.core.model.evaluation.EvaluationRequest;
import com._1c.g5.v8.dt.debug.core.model.values.BslValuePath;
import com._1c.g5.v8.dt.debug.model.calculations.CalculationResultBaseData;
import com._1c.g5.v8.dt.debug.model.calculations.BaseValueInfoData;
import com._1c.g5.v8.dt.debug.model.calculations.ViewInterface;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Live-debugger half of the EDT bridge: attach to a running infobase's debug server, inspect its
 * threads/frames/variables, control execution (suspend/resume/step) and evaluate BSL expressions.
 * Split out of the original model gateway to keep that file focused; behaviour is unchanged. Written
 * against EDT's reverse-engineered debug API. STAND-ONLY: never target production.
 */
public final class DebugGateway {

    // ---- Phase 3 debugger – attach a live debug session ---------------------------------

    /** A live debug session: the Eclipse launch + the connected 1C runtime debug target. */
    public static final class DebugSession {
        public final String sessionId;
        public final ILaunch launch;
        public final IRuntimeDebugClientTarget target;
        DebugSession(String id, ILaunch launch, IRuntimeDebugClientTarget target) {
            this.sessionId = id;
            this.launch = launch;
            this.target = target;
        }
    }

    /** Active debug sessions by id, so the control/inspect/evaluate tools can find the target later. */
    private static final Map<String, DebugSession> DEBUG_SESSIONS = new ConcurrentHashMap<>();

    /** Look up a live debug session by id (for the follow-up debug tools). */
    public static DebugSession debugSession(String id) {
        return DEBUG_SESSIONS.get(id);
    }

    /** Result of {@link #attachDebug}. */
    public static final class AttachResult {
        public boolean ok;
        public String sessionId;
        public String projectName;
        public String serverUrl;
        public int serverPort;
        public boolean launched;
        public boolean connected;
        public String debugServerUrl;   // as reported by the connected target
        public int targetCount;
        public String message;
        public String warning;
    }

    /**
     *: attach a debug session to a RUNNING infobase's debug server (dbgs). Builds a
     * {@code REMOTE_RUNTIME} Eclipse launch configuration ({@link IDebugConfigurationAttributes}),
     * launches it in {@code DEBUG_MODE} (EDT's {@code RemoteRuntimeDebugLaunchDelegate} performs the
     * network connect), and records the resulting {@link IRuntimeDebugClientTarget} under a session id for
     * the control/inspect/evaluate tools. Token-gated (opens a session against a live IB). STAND-ONLY:
     * target the {@code tests} stand, never production.
     *
     * <p>Written against the reverse-engineered debug API; the feasibility spike ({@link #debugProbe})
     * proved the infra loads headless – this is the live network half, verified on the stand.
     */
    public AttachResult attachDebug(String projectName, String serverUrl, int serverPort,
            String infobaseAlias, String infobaseUuid) {
        AttachResult r = new AttachResult();
        r.projectName = projectName;
        r.serverUrl = serverUrl;
        r.serverPort = serverPort;
        if (serverUrl == null || serverUrl.isBlank()) {
            r.message = "serverUrl is required (the running IB's debug server / dbgs host)";
            return r;
        }
        DebugPlugin dp = DebugPlugin.getDefault();
        if (dp == null || dp.getLaunchManager() == null) {
            r.message = "Eclipse debug infrastructure unavailable in this runtime";
            return r;
        }
        ILaunchManager lm = dp.getLaunchManager();
        ILaunchConfigurationType type = lm.getLaunchConfigurationType(IDebugConfigurationTypes.REMOTE_RUNTIME);
        if (type == null) {
            r.message = "REMOTE_RUNTIME launch configuration type not registered";
            return r;
        }
        boolean haveAlias = infobaseAlias != null && !infobaseAlias.isBlank();
        boolean haveUuid = infobaseUuid != null && !infobaseUuid.isBlank();
        if (!haveAlias && !haveUuid) {
            r.message = "infobaseAlias (or infobaseUuid) is required – a debug server hosts many infobases; "
                    + "specify which one (the infobase's debug-server alias).";
            return r;
        }
        IRuntimeDebugClientTargetManager mgr = ServiceAccess.get(IRuntimeDebugClientTargetManager.class);
        IDebugMonitoringManager mon = ServiceAccess.get(IDebugMonitoringManager.class);
        if (mgr == null || mon == null) {
            r.message = "debug services unavailable (targetManager=" + (mgr != null)
                    + ", monitoringManager=" + (mon != null) + ")";
            return r;
        }
        // Compose a full debug-server URL (the manager parses it with new URL(...); the port lives in it).
        String url = serverUrl.contains("://") ? serverUrl : "http://" + serverUrl;
        if (serverPort > 0 && !url.matches(".*:\\d+(/.*)?$")) {
            url = url + ":" + serverPort;
        }
        r.serverUrl = url;
        DtLaunch launch = null;
        try {
            String name = lm.generateLaunchConfigurationName(
                    "edt-bridge-debug-" + (projectName == null || projectName.isBlank() ? "ib" : projectName));
            ILaunchConfigurationWorkingCopy wc = type.newInstance(null, name);
            if (projectName != null && !projectName.isBlank()) {
                wc.setAttribute(IDebugConfigurationAttributes.PROJECT_NAME, projectName);
            }
            wc.setAttribute(IDebugConfigurationAttributes.DEBUG_SERVER_URL, url);
            wc.setAttribute(IDebugConfigurationAttributes.DEBUG_SERVER_LOCATION_TYPE,
                    DebugServerLocationType.PROVIDED.name());
            if (haveUuid) {
                wc.setAttribute(IDebugConfigurationAttributes.INFOBASE_UUID, infobaseUuid);
            }
            if (haveAlias) {
                wc.setAttribute(IDebugConfigurationAttributes.DEBUG_INFOBASE_ALIAS, infobaseAlias);
            }
            // Attach EXACTLY as EDT's RemoteRuntimeDebugLaunchDelegate does, but WITHOUT
            // ILaunchConfiguration.launch(): that path runs preLaunchCheck/saveBeforeLaunch which load
            // org.eclipse.debug.ui (Prompter/ISuspendTrigger) – absent headless, so the launch aborts.
            // Instead build a DtLaunch directly and ask the target manager to create the remote target.
            launch = new DtLaunch(wc, ILaunchManager.DEBUG_MODE, "RemoteDebugServer", mon);
            lm.addLaunch(launch);
            String alias = haveAlias ? infobaseAlias : infobaseUuid;
            // getOrCreateRemote is idempotent (reuses an existing local target); but on a conflict it
            // returns null silently. Fall back to createRemote so the underlying server reason surfaces
            // (e.g. it throws "already in debug process with the requested infobase").
            IRuntimeDebugClientTarget rt = mgr.getOrCreateRemote(url, alias, launch);
            if (rt == null) {
                rt = mgr.createRemote(url, alias, launch);
            }
            if (rt != null) {
                launch.addDebugTarget(rt);
                // Auto-attach to running debug items so threads appear (background jobs, server calls,
                // clients). Without this the connection is up but has no threads to inspect/suspend.
                try {
                    // All desktop debug-item types (mobile excluded). Crucially includes the FILE_MODE
                    // variants (JOB_FILE_MODE, WEB_SOCKET_FILE_MODE) and plain CLIENT – a thin client
                    // against a FILE infobase registers through those, so a narrower list sees no threads.
                    rt.setAutoconnectDebugTargets(java.util.List.of(
                            DebugTargetType.CLIENT, DebugTargetType.MANAGED_CLIENT, DebugTargetType.WEB_CLIENT,
                            DebugTargetType.SERVER, DebugTargetType.SERVER_EMULATION,
                            DebugTargetType.JOB, DebugTargetType.JOB_FILE_MODE,
                            DebugTargetType.WEB_SOCKET, DebugTargetType.WEB_SOCKET_FILE_MODE,
                            DebugTargetType.HTTP_SERVICE, DebugTargetType.WEB_SERVICE,
                            DebugTargetType.ODATA, DebugTargetType.COM_CONNECTOR));
                } catch (Exception autoEx) {
                    r.warning = "autoconnect targets not set (" + autoEx.getClass().getSimpleName()
                            + ") – threads may not attach; ";
                }
            }
            r.launched = true;
            // createRemote may connect asynchronously; poll briefly until the target reports its server URL.
            if (rt != null) {
                long deadline = System.currentTimeMillis() + 15_000L;
                while (!rt.isTerminated() && System.currentTimeMillis() < deadline) {
                    try {
                        if (rt.getDebugServerUrl() != null) {
                            break;
                        }
                    } catch (RuntimeException ignored) {
                        // not ready yet
                    }
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            // Subscribe to the infobase's data areas. WITHOUT this the target is attached but sees NO
            // threads: a running client executes inside a data area the debugger isn't scoped to, so
            // getThreads() stays empty. For a plain (non-multitenant) infobase this is the single
            // default area; subscribe to every area the server reports.
            String areaNote = "";
            if (rt != null) {
                try {
                    java.util.List<DebugAreaInfo> areas = rt.getDebugAreas();
                    if (areas != null && !areas.isEmpty()) {
                        rt.setDebugAreas(areas);
                        areaNote = "subscribed to " + areas.size() + " data area(s)";
                    } else {
                        areaNote = "no data areas reported by the server";
                    }
                } catch (Exception areaEx) {
                    areaNote = "setDebugAreas failed (" + areaEx.getClass().getSimpleName() + ") – threads may stay hidden";
                }
            }
            r.targetCount = launch.getDebugTargets().length;
            if (rt == null) {
                detachLaunch(lm, launch);
                r.message = "createRemote returned no target – check the infobase alias and that the "
                        + "infobase is running with debugging enabled.";
                return r;
            }
            // IRuntimeDebugClientTarget does not expose isConnected(); a live, non-terminated Eclipse
            // debug target is our "connected" proxy (ITerminate.isTerminated from IDebugTarget).
            r.connected = !rt.isTerminated();
            try {
                r.debugServerUrl = rt.getDebugServerUrl();
            } catch (RuntimeException ignored) {
                // best-effort
            }
            String sid = "dbg-" + UUID.randomUUID().toString().substring(0, 8);
            DEBUG_SESSIONS.put(sid, new DebugSession(sid, launch, rt));
            r.sessionId = sid;
            r.ok = true;
            r.message = "attached debug session " + sid + " to " + url + " (infobase " + alias + ")"
                    + (r.connected ? " – connected" : " – created, not yet connected");
            r.warning = (r.warning == null ? "" : r.warning)
                    + "ОТЛАДКА ЖИВОЙ ИБ – только тестовый стенд, никогда продакшен. Области данных: "
                    + areaNote + ".";
        } catch (Exception ex) {
            if (launch != null) {
                detachLaunch(lm, launch);
            }
            r.message = "attach failed: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
        }
        return r;
    }

    /** Terminate + remove a launch, best-effort (shared by attach failure paths and detach). */
    private void detachLaunch(ILaunchManager lm, ILaunch launch) {
        try {
            if (!launch.isTerminated()) {
                launch.terminate();
            }
        } catch (Exception ignored) {
            // best-effort
        }
        try {
            lm.removeLaunch(launch);
        } catch (Exception ignored) {
            // best-effort
        }
    }

    /** Detach a debug session: terminate its launch and forget it. */
    public boolean detachDebug(String sessionId) {
        DebugSession s = (sessionId == null) ? null : DEBUG_SESSIONS.remove(sessionId);
        if (s == null) {
            return false;
        }
        try {
            if (s.launch != null && !s.launch.isTerminated()) {
                s.launch.terminate();
            }
            DebugPlugin dp = DebugPlugin.getDefault();
            if (dp != null && s.launch != null) {
                dp.getLaunchManager().removeLaunch(s.launch);
            }
        } catch (Exception ignored) {
            // best-effort
        }
        return true;
    }

    // ---- Phase 3 debugger – inspect (threads / stack frames / variables) ----------------

    /** A variable in a stack frame: name + rendered value. */
    public static final class DbgVar {
        public String name;
        public String value;
        public String type;
    }

    /** A stack frame of a suspended thread. */
    public static final class DbgFrame {
        public int level;
        public int line;
        public String signature;
        public String source;
        public List<DbgVar> variables = new ArrayList<>();
    }

    /** A debug thread (a running/suspended debug item: background job, server call, client, ...). */
    public static final class DbgThread {
        public String name;
        public String type;        // DebugTargetType
        public boolean suspended;
        public List<DbgFrame> frames = new ArrayList<>(); // populated only when suspended
    }

    /** Result of {@link #inspectDebug}. */
    public static final class InspectResult {
        public boolean ok;
        public String sessionId;
        public boolean targetTerminated;
        public int threadCount;
        public List<DbgThread> threads = new ArrayList<>();
        public String message;
    }

    /** Max stack frames / variables returned per thread (keep responses bounded). */
    private static final int DBG_FRAME_CAP = 50;
    private static final int DBG_VAR_CAP = 100;

    /**
     *: inspect a live debug session – list its threads (debug items) and, for suspended ones,
     * their BSL stack frames + the top frame's variables. Read-only. The session must have been opened by
     * {@link #attachDebug}. Frames/variables are only present for SUSPENDED threads (suspend via
     * {@link #controlDebug} or a breakpoint hit).
     */
    public InspectResult inspectDebug(String sessionId) {
        InspectResult r = new InspectResult();
        r.sessionId = sessionId;
        DebugSession s = (sessionId == null) ? null : DEBUG_SESSIONS.get(sessionId);
        if (s == null) {
            r.message = "no such debug session: " + sessionId + " (attach first)";
            return r;
        }
        IRuntimeDebugClientTarget t = s.target;
        try {
            r.targetTerminated = t.isTerminated();
            IRuntimeDebugTargetThread[] threads = t.getThreads();
            r.threadCount = threads == null ? 0 : threads.length;
            if (threads != null) {
                for (IRuntimeDebugTargetThread th : threads) {
                    DbgThread dt = new DbgThread();
                    try {
                        dt.name = th.getName();
                    } catch (Exception e) {
                        dt.name = "(name unavailable)";
                    }
                    try {
                        dt.type = th.getType() != null ? th.getType().getName() : null;
                    } catch (Exception ignored) {
                        // best-effort
                    }
                    dt.suspended = th.isSuspended();
                    if (dt.suspended) {
                        collectFrames(th, dt);
                    }
                    r.threads.add(dt);
                }
            }
            r.ok = true;
            r.message = "session " + sessionId + ": " + r.threadCount + " thread(s)"
                    + (r.targetTerminated ? " (target terminated)" : "");
        } catch (Exception ex) {
            r.message = "inspect failed: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
        }
        return r;
    }

    /** Collect a suspended thread's stack frames + the top frame's variables into {@code dt}. */
    private void collectFrames(IRuntimeDebugTargetThread th, DbgThread dt) {
        try {
            IBslStackFrame[] frames = th.getStackFrames();
            if (frames == null) {
                return;
            }
            for (int i = 0; i < frames.length && i < DBG_FRAME_CAP; i++) {
                IBslStackFrame f = frames[i];
                DbgFrame df = new DbgFrame();
                try {
                    df.level = f.getLevel();
                    df.line = f.getLineNumber();
                    df.signature = f.getSignature();
                    df.source = f.getSource() != null ? f.getSource().toString() : null;
                } catch (Exception ignored) {
                    // best-effort per-frame
                }
                if (i == 0) {
                    collectVars(f, df);
                }
                dt.frames.add(df);
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    /** Collect a frame's local variables (name + rendered value) into {@code df}. */
    private void collectVars(IBslStackFrame f, DbgFrame df) {
        try {
            IBslVariable[] vars = f.getVariables();
            if (vars == null) {
                return;
            }
            for (int j = 0; j < vars.length && j < DBG_VAR_CAP; j++) {
                IBslVariable v = vars[j];
                DbgVar dv = new DbgVar();
                try {
                    dv.name = v.getName();
                    IBslValue val = v.getValue();
                    if (val != null) {
                        dv.value = val.getValueString();
                        dv.type = val.getValueTypeName();
                    }
                } catch (Exception e) {
                    if (dv.value == null) {
                        dv.value = "(unavailable)";
                    }
                }
                df.variables.add(dv);
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    // ---- Phase 3 debugger – control (suspend / resume / step) ---------------------------

    /** Result of {@link #controlDebug}. */
    public static final class ControlResult {
        public boolean ok;
        public String sessionId;
        public String action;
        public String message;
    }

    /**
     *: control execution of a live debug session: {@code suspend}/{@code resume} the whole
     * target, or {@code stepOver}/{@code stepInto}/{@code stepReturn} a suspended thread (the first
     * suspended one, or {@code threadName} if given). Token-gated (changes runtime execution).
     */
    public ControlResult controlDebug(String sessionId, String action, String threadName) {
        ControlResult r = new ControlResult();
        r.sessionId = sessionId;
        r.action = action;
        DebugSession s = (sessionId == null) ? null : DEBUG_SESSIONS.get(sessionId);
        if (s == null) {
            r.message = "no such debug session: " + sessionId + " (attach first)";
            return r;
        }
        if (action == null) {
            r.message = "action is required (suspend|resume|stepOver|stepInto|stepReturn)";
            return r;
        }
        IRuntimeDebugClientTarget t = s.target;
        try {
            switch (action) {
                case "suspend":
                    if (t.canSuspend()) {
                        t.suspend();
                    }
                    r.ok = true;
                    r.message = "suspend requested";
                    break;
                case "resume":
                    if (t.canResume()) {
                        t.resume();
                    }
                    r.ok = true;
                    r.message = "resume requested";
                    break;
                case "stepOver":
                case "stepInto":
                case "stepReturn": {
                    IThread th = pickThread(t, threadName);
                    if (th == null) {
                        r.message = "no suspended thread to step (suspend first, or pass threadName)";
                        return r;
                    }
                    if ("stepOver".equals(action) && th.canStepOver()) {
                        th.stepOver();
                    } else if ("stepInto".equals(action) && th.canStepInto()) {
                        th.stepInto();
                    } else if ("stepReturn".equals(action) && th.canStepReturn()) {
                        th.stepReturn();
                    } else {
                        r.message = action + " not available on the selected thread";
                        return r;
                    }
                    r.ok = true;
                    r.message = action + " requested on thread " + safeThreadName(th);
                    break;
                }
                default:
                    r.message = "unknown action: " + action
                            + " (suspend|resume|stepOver|stepInto|stepReturn)";
            }
        } catch (Exception ex) {
            r.message = action + " failed: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
        }
        return r;
    }

    /** Pick a suspended thread to step: by name if given, else the first suspended one. */
    private IThread pickThread(IRuntimeDebugClientTarget t, String threadName) {
        try {
            IRuntimeDebugTargetThread[] threads = t.getThreads();
            if (threads == null) {
                return null;
            }
            for (IRuntimeDebugTargetThread th : threads) {
                if (!th.isSuspended()) {
                    continue;
                }
                if (threadName == null || threadName.isBlank() || threadName.equals(safeThreadName(th))) {
                    return th;
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
        return null;
    }

    private String safeThreadName(IThread th) {
        try {
            return th.getName();
        } catch (Exception e) {
            return "(thread)";
        }
    }

    // ---- Phase 3 debugger – evaluate (arbitrary BSL expression) --------------------------

    /** Result of {@link #evaluateDebug}. */
    public static final class EvaluateResult {
        public boolean ok;
        public String sessionId;
        public String expression;
        public boolean success;
        public String value;
        public String type;
        public String error;
        public String frame;     // signature of the frame the expression ran in
        public String message;
    }

    /**
     * flagship (HEAVIEST gate): evaluate an arbitrary BSL {@code expression} in a suspended
     * frame of a live debug session and return the result. This is <b>arbitrary code execution against a
     * live infobase</b>, so it is gated three ways: (1) a configured token (the tool is a write tool),
     * (2) the per-call {@code allowCodeExecution=true} opt-in, and (3) the server-side switch
     * {@code EDT_BRIDGE_ALLOW_EVALUATE} (env) – all required; off by default. STAND-ONLY.
     *
     * <p>Runs via {@link IEvaluationEngine#evaluateExpression} (async; we wait on a latch for the
     * {@link IEvaluationListener} callback) on the chosen suspended thread's frame.
     */
    public EvaluateResult evaluateDebug(String sessionId, String expression, String threadName,
            int frameLevel, boolean allowCodeExecution) {
        EvaluateResult r = new EvaluateResult();
        r.sessionId = sessionId;
        r.expression = expression;
        // Gate 1: per-call opt-in.
        if (!allowCodeExecution) {
            r.message = "refused: edt_evaluate runs ARBITRARY BSL against a live infobase – pass "
                    + "allowCodeExecution=true to confirm .";
            return r;
        }
        // Gate 2: server-side switch (off by default even with a token). Env var, or the "edt-bridge"
        // preference page.
        String sw = System.getenv("EDT_BRIDGE_ALLOW_EVALUATE");
        boolean enabled = (sw != null && (sw.equals("1") || sw.equalsIgnoreCase("true") || sw.equalsIgnoreCase("yes")))
                || io.github.keyfire.edtbridge.EdtBridgePrefs.getBool(
                        io.github.keyfire.edtbridge.EdtBridgePrefs.KEY_ALLOW_EVALUATE);
        if (!enabled) {
            r.message = "refused: server-side evaluate is disabled – set EDT_BRIDGE_ALLOW_EVALUATE=1 (or "
                    + "enable it on the edt-bridge preference page) to enable code execution .";
            return r;
        }
        if (expression == null || expression.isBlank()) {
            r.message = "expression is required";
            return r;
        }
        DebugSession s = (sessionId == null) ? null : DEBUG_SESSIONS.get(sessionId);
        if (s == null) {
            r.message = "no such debug session: " + sessionId + " (attach first)";
            return r;
        }
        try {
            IBslStackFrame frame = suspendedFrame(s.target, threadName, frameLevel);
            if (frame == null) {
                r.message = "no suspended frame to evaluate in – suspend a thread first (edt_debug_control "
                        + "suspend) or hit a breakpoint.";
                return r;
            }
            r.frame = frame.getSignature();
            IEvaluationEngine engine = s.target.getEvaluationEngine();
            if (engine == null) {
                r.message = "evaluation engine unavailable on the target";
                return r;
            }
            final IEvaluationResult[] holder = {null};
            final CountDownLatch latch = new CountDownLatch(1);
            IEvaluationRequest req = EvaluationRequest.builder(new BslValuePath(expression))
                    .setStackFrame(frame)
                    .setExpressionUuid(UUID.randomUUID()) // required by build() (checkArgument non-null)
                    .setInterfaces(java.util.List.of(ViewInterface.CONTEXT, ViewInterface.ENUM,
                            ViewInterface.COLLECTION)) // non-null required (engine reads .size())
                    .setMaxTestSize(4096)
                    .setMultiLine(false)
                    .setEvaluationListener(result -> {
                        holder[0] = result;
                        latch.countDown();
                    })
                    .build();
            engine.evaluateExpression(req);
            boolean done = latch.await(20, TimeUnit.SECONDS);
            if (!done || holder[0] == null) {
                r.message = "evaluation timed out (no result within 20s)";
                return r;
            }
            IEvaluationResult res = holder[0];
            r.success = res.isSuccess();
            if (!r.success) {
                r.error = res.getErrorMessage();
                r.message = "evaluation returned an error";
            } else {
                renderCalculation(res.getResult(), r);
                r.message = "evaluated";
            }
            r.ok = true;
        } catch (Exception ex) {
            r.message = "evaluate failed: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
        }
        return r;
    }

    /** The suspended frame to evaluate in: thread by name (or first suspended), frame at {@code level}. */
    private IBslStackFrame suspendedFrame(IRuntimeDebugClientTarget t, String threadName, int level) {
        try {
            IRuntimeDebugTargetThread[] threads = t.getThreads();
            if (threads == null) {
                return null;
            }
            for (IRuntimeDebugTargetThread th : threads) {
                if (!th.isSuspended()) {
                    continue;
                }
                if (threadName != null && !threadName.isBlank() && !threadName.equals(safeThreadName(th))) {
                    continue;
                }
                IBslStackFrame[] frames = th.getStackFrames();
                if (frames != null && frames.length > 0) {
                    int idx = (level >= 0 && level < frames.length) ? level : 0;
                    return frames[idx];
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
        return null;
    }

    /** Render a {@link CalculationResultBaseData} (typed value info) into the result's value+type. */
    private void renderCalculation(CalculationResultBaseData d, EvaluateResult r) {
        if (d == null) {
            r.value = "(no result)";
            return;
        }
        try {
            BaseValueInfoData vi = d.getResultValueInfo();
            if (vi == null) {
                r.value = "(complex value)";
                return;
            }
            r.type = vi.getTypeName();
            if (vi.getValueBoolean() != null) {
                r.value = vi.getValueBoolean().toString();
            } else if (vi.getValueDecimal() != null) {
                r.value = vi.getValueDecimal().toPlainString();
            } else if (vi.getValueDateTime() != null) {
                r.value = vi.getValueDateTime().toString();
            } else if (vi.getValueString() != null) {
                r.value = new String(vi.getValueString(), StandardCharsets.UTF_8);
            } else {
                r.value = "";
            }
        } catch (Exception e) {
            r.value = "(render failed: " + e.getClass().getSimpleName() + ")";
        }
    }

}
