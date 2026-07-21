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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.osgi.framework.Bundle;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseConfigurationChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateCallback;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateConflictResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolution;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.core.operations.CompoundOperationException;
import com._1c.g5.v8.dt.platform.services.core.operations.IInfobaseCreationOperation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.IRuntimeInstallationManager;
import com._1c.g5.v8.dt.export.IExportOperation;
import com._1c.g5.v8.dt.export.IExportOperationFactory;
import com._1c.g5.wiring.ServiceProperties;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.MatchingRuntimeNotFound;
import com._1c.g5.v8.dt.platform.services.model.AppArch;
import com._1c.g5.v8.dt.platform.services.model.CreateInfobaseArguments;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.wiring.ServiceAccess;
import com.google.inject.Injector;

/**
 * Platform / infobase operations: list registered infobases, update an infobase from a project,
 * discover and register 1C:Enterprise platform installations, create an empty file infobase, and
 * build a configuration extension (.cfe) via {@code ibcmd}. Split out of the original model gateway to
 * keep that file focused; behaviour is unchanged.
 */
public final class PlatformGateway {
    /**
     * The export bundle's Guice injector, via its plugin's own class loader. Its export services are
     * bound in a plain (non-service-aware) Guice module, so {@code ServiceAccess} cannot reach them;
     * the injector on {@code ExportPlugin.getDefault()} can. The plugin sits in an internal package,
     * so this stays reflection-only (no compile-time dependency on it).
     */
    private Injector exportInjector() throws Exception {
        Bundle bundle = Platform.getBundle("com._1c.g5.v8.dt.export");
        if (bundle == null) {
            return null;
        }
        Class<?> pluginClass = bundle.loadClass("com._1c.g5.v8.dt.internal.export.ExportPlugin");
        Object plugin = pluginClass.getMethod("getDefault").invoke(null);
        if (plugin == null) {
            return null;
        }
        return (Injector) pluginClass.getMethod("getInjector").invoke(plugin);
    }


    /** One registered infobase for {@link #listInfobases}. */
    public static final class InfobaseInfo {
        public String name;
        public String uuid;
        public String connection;
    }

    /** One project→infobase association for {@link #listInfobases}. */
    public static final class InfobaseAssociationInfo {
        public String project;
        public String infobaseName;
        public String infobaseUuid;
    }

    /** Result of {@link #listInfobases}. */
    public static final class InfobasesResult {
        public List<InfobaseInfo> infobases = new ArrayList<>();
        public List<InfobaseAssociationInfo> associations = new ArrayList<>();
        public String message;
    }

    /** Registered infobases (EDT's infobases list) + which open project is associated with which. */
    public InfobasesResult listInfobases() {
        InfobasesResult r = new InfobasesResult();
        IInfobaseManager im = ServiceAccess.get(IInfobaseManager.class);
        if (im == null) {
            r.message = "IInfobaseManager service unavailable";
            return r;
        }
        // getAll() returns a tree (groups may nest bases); flatten it so grouped infobases
        // (e.g. a group holding several server infobases) are not missed.
        for (InfobaseReference ib : InfobaseReferences.asPlainList(im.getAll())) {
            InfobaseInfo info = new InfobaseInfo();
            info.name = ib.getName();
            info.uuid = String.valueOf(ib.getUuid());
            info.connection = String.valueOf(ib.getConnectionString());
            r.infobases.add(info);
        }
        IInfobaseAssociationManager am = ServiceAccess.get(IInfobaseAssociationManager.class);
        if (am != null) {
            for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (!p.isOpen()) {
                    continue;
                }
                try {
                    am.getAssociation(p).ifPresent(assoc -> {
                        InfobaseReference ib = assoc.getDefaultInfobase();
                        if (ib != null) {
                            InfobaseAssociationInfo ai = new InfobaseAssociationInfo();
                            ai.project = p.getName();
                            ai.infobaseName = ib.getName();
                            ai.infobaseUuid = String.valueOf(ib.getUuid());
                            r.associations.add(ai);
                        }
                    });
                } catch (Exception ignored) {
                    // a project without association support – skip
                }
            }
        }
        return r;
    }

    /** Result of {@link #updateInfobase}. */
    public static final class UpdateInfobaseResult {
        public boolean ok;
        public boolean applied;
        public String project;
        public String infobaseName;
        public String infobaseUuid;
        public boolean connected;
        public String equality;       // EDT's project-vs-infobase equality state, when known
        public String status;         // the update IStatus, when applied
        public String plan;
        public String message;
    }

    /**
     * Phase-2 write tool: update an infobase's configuration FROM an EDT project (a configuration
     * or a configuration-extension project) via EDT's own {@link IInfobaseSynchronizationManager} –
     * the engine behind "Update infobase configuration". The infobase is picked by name or UUID, or
     * defaults to the project's associated one. Database-structure changes are confirmed
     * automatically; a CONFLICT (the infobase has its own changes) aborts the update – resolve it
     * interactively in EDT then. Dry-run by default.
     */
    public UpdateInfobaseResult updateInfobase(String projectName, String infobase, boolean apply) {
        UpdateInfobaseResult r = new UpdateInfobaseResult();
        r.project = projectName;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName == null ? "" : projectName);
        if (projectName == null || !p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        IInfobaseSynchronizationManager sm = ServiceAccess.get(IInfobaseSynchronizationManager.class);
        IInfobaseManager im = ServiceAccess.get(IInfobaseManager.class);
        if (sm == null || im == null) {
            r.message = "infobase services unavailable (IInfobaseSynchronizationManager / IInfobaseManager)";
            return r;
        }
        InfobaseReference ref = null;
        if (infobase != null && !infobase.isBlank()) {
            ref = im.findInfobaseByName(infobase.trim()).orElse(null);
            if (ref == null) {
                try {
                    ref = im.findInfobaseByUuid(UUID.fromString(infobase.trim())).orElse(null);
                } catch (IllegalArgumentException notAUuid) {
                    // not a UUID – name lookup already failed
                }
            }
            if (ref == null) {
                r.message = "infobase not found by name or uuid: " + infobase;
                return r;
            }
        } else {
            IInfobaseAssociationManager am = ServiceAccess.get(IInfobaseAssociationManager.class);
            try {
                ref = (am == null) ? null
                        : am.getAssociation(p).map(a -> a.getDefaultInfobase()).orElse(null);
            } catch (Exception e) {
                ref = null;
            }
            if (ref == null) {
                r.message = "the project has no associated infobase – pass the infobase name or uuid";
                return r;
            }
        }
        r.infobaseName = ref.getName();
        r.infobaseUuid = String.valueOf(ref.getUuid());
        r.connected = sm.isConnected(p, ref);
        try {
            r.equality = String.valueOf(sm.getEqualityState(p, ref));
        } catch (RuntimeException e) {
            r.equality = null;
        }
        r.ok = true;
        r.plan = "Update infobase \"" + r.infobaseName + "\" from project " + projectName
                + " (db-structure changes auto-confirmed; a conflict aborts)";
        if (!apply) {
            return r;
        }
        final InfobaseReference target = ref;
        IInfobaseUpdateCallback callback = new IInfobaseUpdateCallback() {
            @Override
            public boolean onConfirm(IProject project, InfobaseReference infobaseRef,
                    List<com._1c.g5.designer.ssh.client.operation.IDbStructureChange> changes,
                    IProgressMonitor monitor) {
                return true; // batch mode: accept database-structure changes
            }

            @Override
            public InfobaseConflictResolution resolveInfobaseChanges(IProject project,
                    InfobaseReference infobaseRef, java.util.Set<EObject> set1, java.util.Set<EObject> set2,
                    java.util.Set<String> set3, IInfobaseConfigurationChange change,
                    IInfobaseUpdateConflictResolver resolver,
                    IInfobaseUpdateConflictResolver.IConflictResolveAssist assist,
                    com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationFlow flow,
                    IProgressMonitor monitor) {
                throw new IllegalStateException(
                        "the infobase has its own configuration changes – batch update aborted; "
                        + "resolve the conflict interactively in EDT");
            }
        };
        try {
            IStatus st = sm.updateInfobase(p, target, callback, false, new NullProgressMonitor());
            r.status = (st == null) ? "null" : (st.isOK() ? "OK" : st.toString());
            r.applied = st != null && st.isOK();
            r.message = r.applied ? ("infobase \"" + r.infobaseName + "\" updated from " + projectName)
                    : ("update finished with status: " + r.status);
        } catch (RuntimeException ex) {
            r.applied = false;
            r.message = "update failed: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
        }
        return r;
    }

    // ── 1C:Enterprise platform installations (what EDT resolves for dump / infobase creation) ──

    private static final String RT_ENTERPRISE_PLATFORM =
            "com._1c.g5.v8.dt.platform.services.core.runtimeType.EnterprisePlatform";
    private static final String COMP_THICK_CLIENT =
            "com._1c.g5.v8.dt.platform.services.core.componentTypes.ThickClient";

    /** One platform installation EDT knows about (a resolvable entry + its resolved concrete install). */
    public static final class PlatformInstallInfo {
        public String name;
        public String versionMask;     // the entry's version mask, e.g. 8.3.24 or 8.3.* (as EDT stores it)
        public boolean resolved;       // a concrete install was found for this entry
        public boolean thickClient;    // the resolved install carries a thick client (needed to create a file base)
        public String version;         // resolved: e.g. 8.3.24.1548 (version + build)
        public int build;
        public String location;        // resolved: filesystem path of the install
        public String arch;
        public boolean training;       // resolved: a training-edition platform (limited)
        public String uuid;
        public String typeId;
        public String note;
    }

    /** A full (thick-client) install found on disk, for {@link #platformInstallations}. */
    public static final class DiskFullInstall {
        public String version;
        public String binDir;
    }

    /** Result of {@link #platformInstallations}. */
    public static final class PlatformInstallationsResult {
        public boolean ok;
        public List<PlatformInstallInfo> installations = new ArrayList<>();
        public List<DiskFullInstall> diskFullInstalls = new ArrayList<>();  // full installs on disk (create-infobase fallback)
        public List<PlatformInstallInfo> registeredRaw = new ArrayList<>(); // EDT's raw registered list (writer manager)
        public String message;
    }

    /** True when a registered install's location holds a thick client (a {@code 1cv8} executable). */
    private static boolean isFullInstall(RuntimeInstallation ri) {
        java.net.URI loc = ri.getLocation();
        if (loc == null || !"file".equalsIgnoreCase(loc.getScheme())) {
            return false;
        }
        try {
            return firstExisting(java.nio.file.Paths.get(loc), "1cv8.exe", "1cv8") != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The EnterprisePlatform installation writer manager (Guice @Named binding), or null. */
    private IRuntimeInstallationManager installationWriter() {
        return ServiceAccess.get(IRuntimeInstallationManager.class,
                ServiceProperties.named(RT_ENTERPRISE_PLATFORM));
    }

    /** A normalized, case-insensitive key for an install's location (for de-duplicating the registry). */
    private static String canonicalLocation(RuntimeInstallation ri) {
        java.net.URI loc = ri.getLocation();
        if (loc == null) {
            return ri.getVersionWithBuild() + "|" + ri.getUuid();
        }
        try {
            return java.nio.file.Paths.get(loc).normalize().toString().toLowerCase(java.util.Locale.ROOT);
        } catch (RuntimeException e) {
            return loc.toString().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * List the 1C:Enterprise platform installations EDT knows about – the same set EDT resolves from
     * when it dumps an {@code .epf}/{@code .erf} or creates an infobase, so the caller can confirm the
     * RIGHT full install is picked without hard-coding a path (EDT keeps this per machine, so it also
     * works on macOS where the distributions live elsewhere). Read via EDT's own
     * {@link IResolvableRuntimeInstallationManager}: each registered entry is resolved to a concrete
     * install that carries a thick client (what dump / infobase creation need), reporting its version,
     * build, filesystem path and architecture. {@code discoverPath}/{@code register} are accepted for
     * signature stability but registration of non-standard installs is an EDT-preferences action –
     * EDT auto-discovers the standard locations (e.g. {@code C:\Program Files\1cv8}).
     */
    public PlatformInstallationsResult platformInstallations(String discoverPath, boolean register) {
        PlatformInstallationsResult r = new PlatformInstallationsResult();
        IResolvableRuntimeInstallationManager mgr =
                ServiceAccess.get(IResolvableRuntimeInstallationManager.class);
        if (mgr == null) {
            r.message = "IResolvableRuntimeInstallationManager service unavailable";
            return r;
        }
        try {
            java.util.Collection<IResolvableRuntimeInstallation> all = mgr.getAll(RT_ENTERPRISE_PLATFORM);
            for (IResolvableRuntimeInstallation entry : all) {
                PlatformInstallInfo info = new PlatformInstallInfo();
                info.name = entry.getName();
                info.versionMask = entry.getVersionMask();
                info.typeId = entry.getRuntimeTypeId();
                // First resolve the base install (no component requirement) to surface its version and
                // path; then probe separately for a thick client (what create-file-infobase needs).
                RuntimeInstallation concrete = null;
                try {
                    concrete = entry.resolve(java.util.List.of(), AppArch.AUTO);
                } catch (MatchingRuntimeNotFound nf) {
                    info.note = "no install matches this entry";
                }
                if (concrete != null) {
                    info.resolved = true;
                    info.version = concrete.getVersionWithBuild();
                    info.build = concrete.getBuild();
                    java.net.URI loc = concrete.getLocation();
                    info.location = (loc != null) ? toLocalPath(loc) : null;
                    info.arch = String.valueOf(concrete.getArch());
                    info.training = concrete.isTraining();
                    info.uuid = String.valueOf(concrete.getUuid());
                    try {
                        info.thickClient = entry.resolve(
                                java.util.List.of(COMP_THICK_CLIENT), AppArch.AUTO) != null;
                    } catch (MatchingRuntimeNotFound nf) {
                        info.thickClient = false;
                    }
                }
                r.installations.add(info);
            }
            r.ok = true;
        } catch (RuntimeException ex) {
            r.message = "listing installations failed: " + GatewaySupport.describeCause(ex);
        }
        // Also surface full (thick-client) installs present on disk – these are what create_infobase
        // falls back to when EDT's registered installs lack a thick client. Best-first order.
        for (DiskPlatform dp : discoverFullPlatforms(null)) {
            DiskFullInstall d = new DiskFullInstall();
            d.version = dp.version;
            d.binDir = dp.binDir.toString();
            r.diskFullInstalls.add(d);
        }
        // Raw registered list from the writer manager, each classified full/thin by its own location –
        // shows exactly what shadows what (a newer thin build hides an older full one for the resolver).
        try {
            IRuntimeInstallationManager writer = installationWriter();
            if (writer != null) {
                for (RuntimeInstallation ri : writer.getAllRegistered()) {
                    PlatformInstallInfo info = new PlatformInstallInfo();
                    info.name = ri.getName();
                    info.version = ri.getVersionWithBuild();
                    info.build = ri.getBuild();
                    info.location = (ri.getLocation() != null) ? toLocalPath(ri.getLocation()) : null;
                    info.arch = String.valueOf(ri.getArch());
                    info.training = ri.isTraining();
                    info.thickClient = isFullInstall(ri);
                    info.resolved = true;
                    r.registeredRaw.add(info);
                }
            }
        } catch (RuntimeException ex) {
            r.message = (r.message == null ? "" : r.message + "; ") + "registeredRaw failed: " + GatewaySupport.describeCause(ex);
        }
        if (discoverPath != null && !discoverPath.isBlank()) {
            r.message = (r.message == null ? "" : r.message + "; ")
                    + "discoverPath is not applied here: register non-standard installs in EDT's "
                    + "preferences (1C:Enterprise runtimes); EDT auto-discovers the standard locations.";
        }
        return r;
    }

    private static String toLocalPath(java.net.URI uri) {
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return java.nio.file.Paths.get(uri).toString();
            }
        } catch (RuntimeException ignore) {
            // fall through to the raw URI string
        }
        return uri.toString();
    }

    /** Result of {@link #registerPlatform}. */
    public static final class RegisterPlatformResult {
        public boolean ok;
        public List<String> registered = new ArrayList<>();   // versions added to EDT's list
        public List<String> alreadyKnown = new ArrayList<>(); // versions EDT already had
        public List<String> removed = new ArrayList<>();      // thin builds dropped so a full one resolves
        public String message;
    }

    /**
     * Register 1C:Enterprise platform install(s) into EDT so its own engine (dump / infobase update)
     * can use them – the writer manager is a Guice {@code @Named} binding, reached here via the
     * wiring {@code service.name} service property. {@code target} selects what to register: blank /
     * {@code "auto"} = every full (thick-client) install discovered on disk (best-first); a version
     * like {@code 8.5.1.1317}; or an absolute path to a version directory. EDT's own
     * {@link IRuntimeInstallationManager#search} reads the install, then it is added and saved.
     */
    public RegisterPlatformResult registerPlatform(String target) {
        RegisterPlatformResult r = new RegisterPlatformResult();
        IRuntimeInstallationManager mgr = ServiceAccess.get(IRuntimeInstallationManager.class,
                ServiceProperties.named(RT_ENTERPRISE_PLATFORM));
        if (mgr == null) {
            r.message = "IRuntimeInstallationManager (EnterprisePlatform) service unavailable";
            return r;
        }
        String t = (target == null) ? "" : target.trim();
        // reset: re-detect installs from disk (EDT's own refresh) and persist – restores the clean
        // auto-detected list, undoing any manual add/remove.
        if (t.equalsIgnoreCase("reset")) {
            try {
                mgr.refresh();
                List<RuntimeInstallation> all = mgr.getAllRegistered();
                // de-duplicate by canonical location – repeated adds can leave textual location variants
                // for the same install; keep one entry per real path.
                java.util.Set<String> seen = new java.util.HashSet<>();
                List<RuntimeInstallation> unique = new ArrayList<>();
                for (RuntimeInstallation ri : all) {
                    String key = canonicalLocation(ri);
                    if (seen.add(key)) {
                        unique.add(ri);
                    }
                }
                if (unique.size() != all.size()) {
                    mgr.save(unique);
                }
                r.ok = true;
                r.message = "reset from disk: " + all.size() + " -> " + unique.size() + " unique installs";
            } catch (RuntimeException ex) {
                r.message = "reset failed: " + GatewaySupport.describeCause(ex);
            }
            return r;
        }
        // Resolve the version directories to register.
        List<java.nio.file.Path> versionDirs = new ArrayList<>();
        boolean preferFull = t.equalsIgnoreCase("prefer-full");
        if (preferFull) {
            // advisory-only: no discovery / add (see the advisory block below)
        } else if (t.isEmpty() || t.equalsIgnoreCase("auto")) {
            for (DiskPlatform dp : discoverFullPlatforms(null)) {
                if (dp.binDir.getParent() != null) {
                    versionDirs.add(dp.binDir.getParent());
                }
            }
            if (versionDirs.isEmpty()) {
                r.message = "no full (thick-client) 1C:Enterprise install found on disk to register";
                return r;
            }
        } else if (t.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            DiskPlatform match = null;
            for (DiskPlatform dp : discoverFullPlatforms(null)) {
                if (dp.version.equals(t)) {
                    match = dp;
                    break;
                }
            }
            if (match == null || match.binDir.getParent() == null) {
                r.message = "version " + t + " not found as a full install on disk";
                return r;
            }
            versionDirs.add(match.binDir.getParent());
        } else {
            java.nio.file.Path dir = java.nio.file.Path.of(t);
            if (!java.nio.file.Files.isDirectory(dir)) {
                r.message = "not a directory: " + t;
                return r;
            }
            versionDirs.add(dir);
        }
        try {
            // Force EDT to re-index the found full installs: add each unique (version+location) even if
            // a thin build of the same line is already registered – EDT resolves the newest build per
            // line, so an older FULL build must be added with its own components to be resolvable.
            java.util.Set<String> foundKeys = new java.util.HashSet<>();
            List<RuntimeInstallation> toRegister = new ArrayList<>();
            for (java.nio.file.Path dir : versionDirs) {
                // search requires all three args non-null (Preconditions.checkNotNull) and MUTATES the
                // collection (it is the running set of taken names) – so a mutable list, not List.of().
                for (RuntimeInstallation ri : mgr.search(dir, new ArrayList<>(), new NullProgressMonitor())) {
                    String key = ri.getVersionWithBuild() + "|"
                            + (ri.getLocation() != null ? ri.getLocation().toString() : "");
                    if (foundKeys.add(key)) {
                        toRegister.add(ri);
                    }
                }
            }
            java.util.Set<String> registeredLocations = new java.util.HashSet<>();
            for (RuntimeInstallation ri : mgr.getAllRegistered()) {
                registeredLocations.add(String.valueOf(ri.getLocation()));
            }
            boolean changed = false;
            for (RuntimeInstallation ri : toRegister) {
                if (registeredLocations.contains(String.valueOf(ri.getLocation()))) {
                    r.alreadyKnown.add(ri.getVersionWithBuild());
                    continue;
                }
                mgr.add(ri);
                r.registered.add(ri.getVersionWithBuild());
                changed = true;
            }
            if (changed) {
                mgr.save(mgr.getAllRegistered());
            }
            // prefer-full: EDT's resolver picks the NEWEST build per version line, so a newer thin build
            // shadows an older full one. Minimal fix: in each line that has a full install, drop only the
            // thin builds NEWER than the newest full one (so the newest remaining is that full build),
            // then refresh, so resolve([ThickClient]) picks it and native dump/update work.
            if (preferFull) {
                List<RuntimeInstallation> current = new ArrayList<>(mgr.getAllRegistered());
                java.util.Map<String, String> newestFullPerLine = new java.util.HashMap<>();
                for (RuntimeInstallation ri : current) {
                    if (isFullInstall(ri)) {
                        String line = platformLine(ri.getVersionWithBuild());
                        String have = newestFullPerLine.get(line);
                        // compareVersionsDesc(v, have) < 0  ==>  v is newer than have
                        if (have == null || compareVersionsDesc(ri.getVersionWithBuild(), have) < 0) {
                            newestFullPerLine.put(line, ri.getVersionWithBuild());
                        }
                    }
                }
                for (RuntimeInstallation ri : current) {
                    String newestFull = newestFullPerLine.get(platformLine(ri.getVersionWithBuild()));
                    if (!isFullInstall(ri) && newestFull != null
                            && compareVersionsDesc(ri.getVersionWithBuild(), newestFull) < 0) {
                        r.removed.add(ri.getVersionWithBuild());  // thin build newer than the newest full
                    }
                }
                // ADVISORY ONLY: actually saving a filtered list does NOT change what EDT resolves (the
                // resolvable view is cached / rebuilt from disk on the next start, re-shadowing the full
                // install) and only bloats the registry. So we report the shadowing thin builds but do
                // NOT modify EDT. Coercing EDT's resolver from the bridge is not reliable – build via the
                // on-disk full install instead (see the .cfe/.epf disk-build path).
                r.message = "advisory: these thin builds shadow the newest full install for the resolver, "
                        + "but EDT's resolver cannot be reliably overridden from here – build with the "
                        + "on-disk full install instead. (Nothing was changed.)";
            }
            r.ok = true;
            if (toRegister.isEmpty() && r.removed.isEmpty()) {
                r.message = "search found no installs under: " + versionDirs;
            }
        } catch (RuntimeException ex) {
            r.message = "register failed: " + GatewaySupport.describeCause(ex);
        }
        return r;
    }

    // ── Create an empty file infobase via EDT's own creation operation (thick client under the hood) ──

    /** Result of {@link #createInfobase}. */
    public static final class CreateInfobaseResult {
        public boolean ok;
        public boolean applied;
        public String name;
        public String path;
        public String platform;        // pinned version, or "(auto)" when EDT resolves it itself
        public String method;          // "edt" or "disk:<version>" – how the base was actually created
        public String plan;
        public String message;
    }

    /**
     * Phase-2 write tool: create an EMPTY file infobase via EDT's own {@link IInfobaseCreationOperation}
     * (the engine behind the "Create infobase" wizard – it drives the platform's client in batch mode)
     * and register it in EDT's infobases list. EDT resolves the platform installation itself (see
     * {@link #platformInstallations}); {@code platformVersion} pins one, null lets EDT choose. A
     * {@code cfPath} loads that {@code .cf} as initial content; null creates a blank base. Dry-run by
     * default (validation only); {@code apply}=true performs the creation.
     */
    public CreateInfobaseResult createInfobase(String name, String path, String platformVersion,
            String cfPath, boolean apply) {
        CreateInfobaseResult r = new CreateInfobaseResult();
        r.name = name;
        r.path = path;
        r.platform = (platformVersion == null || platformVersion.isBlank()) ? "(auto)" : platformVersion.trim();
        if (name == null || name.isBlank()) {
            r.message = "name is required";
            return r;
        }
        if (path == null || path.isBlank()) {
            r.message = "path is required (the folder for the file infobase)";
            return r;
        }
        java.nio.file.Path dir = java.nio.file.Path.of(path);
        try {
            if (java.nio.file.Files.exists(dir.resolve("1Cv8.1CD"))) {
                r.message = "a file infobase already exists at " + path + " (1Cv8.1CD present)";
                return r;
            }
        } catch (RuntimeException ignore) {
            // best-effort existence check; continue
        }
        r.plan = "Create empty file infobase \"" + name + "\" at " + path
                + " [platform " + r.platform + "]" + (cfPath != null && !cfPath.isBlank() ? " from " + cfPath : "");

        IInfobaseCreationOperation op = ServiceAccess.get(IInfobaseCreationOperation.class);
        if (op == null) {
            r.message = "IInfobaseCreationOperation service unavailable";
            return r;
        }
        CreateInfobaseArguments args = ModelFactory.eINSTANCE.createCreateInfobaseArguments();
        java.nio.file.Path cf = (cfPath == null || cfPath.isBlank()) ? null : java.nio.file.Path.of(cfPath);
        String platform = (platformVersion == null || platformVersion.isBlank()) ? null : platformVersion.trim();
        IInfobaseCreationOperation.Descriptor desc = new IInfobaseCreationOperation.Descriptor(
                null, name, dir, cf, platform, null, args, true, true);
        try {
            op.validate(desc);
            r.ok = true;
        } catch (CompoundOperationException ex) {
            r.message = "validation failed: " + GatewaySupport.describeCause(ex)
                    + " (a registered 1C platform is required – see edt_platform_installations)";
            return r;
        }
        if (!apply) {
            return r;
        }
        // 1) Preferred path: EDT's own operation, using an install EDT has registered.
        Throwable edtFailure = null;
        try {
            java.nio.file.Files.createDirectories(dir);
            op.perform(desc, new NullProgressMonitor());
            if (java.nio.file.Files.exists(dir.resolve("1Cv8.1CD"))) {
                r.applied = true;
                r.method = "edt";
                r.message = "created file infobase " + name + " at " + path
                        + " (via EDT, registered in EDT's infobases list)";
                return r;
            }
            edtFailure = new IllegalStateException("operation finished but 1Cv8.1CD is missing");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            r.applied = false;
            r.message = "create interrupted: " + GatewaySupport.describeCause(ex);
            return r;
        } catch (CompoundOperationException | java.io.IOException | RuntimeException ex) {
            edtFailure = ex;
        }
        // 2) Fallback: EDT has no registered install with a thick client for this version. Find a full
        //    install on disk (highest matching version first), create the base with its own client,
        //    and register it in EDT. Honours the requested version line, then descends.
        return createInfobaseViaDiskPlatform(r, name, dir, platform, edtFailure);
    }

    /** A full (thick-client) platform install found on disk: its {@code bin} directory and version. */
    static final class DiskPlatform {
        final java.nio.file.Path binDir;
        final String version;         // e.g. 8.5.1.1317
        DiskPlatform(java.nio.file.Path binDir, String version) {
            this.binDir = binDir;
            this.version = version;
        }
    }

    /**
     * Fallback used by {@link #createInfobase} when EDT has no registered install carrying a thick
     * client: discover a full install on disk, create the file base with its own {@code 1cv8}
     * client, then register the base in EDT. Selection order matches the owner's rule – the requested
     * version line first (highest build), then all other versions descending – erroring out clearly
     * when no suitable full install exists anywhere.
     */
    private CreateInfobaseResult createInfobaseViaDiskPlatform(CreateInfobaseResult r, String name,
            java.nio.file.Path dir, String platformVersion, Throwable edtFailure) {
        List<DiskPlatform> candidates = discoverFullPlatforms(platformLine(platformVersion));
        if (candidates.isEmpty()) {
            r.applied = false;
            r.message = "no suitable full (thick-client) 1C:Enterprise install found on disk"
                    + (edtFailure != null ? " (EDT could not either: " + GatewaySupport.describeCause(edtFailure) + ")" : "")
                    + " – install a full 1C:Enterprise platform (the thin client / training editions do "
                    + "not include the components needed to create an infobase).";
            return r;
        }
        // A prior EDT attempt may have left an empty target dir; CREATEINFOBASE wants it absent/empty.
        try {
            if (java.nio.file.Files.isDirectory(dir)) {
                try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(dir)) {
                    if (s.findAny().isEmpty()) {
                        java.nio.file.Files.delete(dir);
                    }
                }
            }
        } catch (java.io.IOException ignore) {
            // leave it; CREATEINFOBASE will report if the dir is unusable
        }
        StringBuilder tried = new StringBuilder();
        for (DiskPlatform dp : candidates) {
            java.nio.file.Path exe = firstExisting(dp.binDir, "1cv8.exe", "1cv8");
            if (exe == null) {
                continue;
            }
            String err = runCreateInfobase(exe, dir);
            if (err == null && java.nio.file.Files.exists(dir.resolve("1Cv8.1CD"))) {
                r.applied = true;
                r.platform = dp.version;
                r.method = "disk:" + dp.version;
                String reg = registerFileInfobase(name, dir);
                r.message = "created file infobase " + name + " at " + dir + " using the on-disk platform "
                        + dp.version + " (" + exe + ")"
                        + (reg == null ? "; registered it in EDT's infobases list" : "; EDT registration skipped: " + reg);
                return r;
            }
            tried.append(dp.version).append(err != null ? " (" + err + ")" : " (no 1Cv8.1CD produced)").append("; ");
        }
        r.applied = false;
        r.message = "found full install(s) on disk but CREATEINFOBASE failed for: " + tried;
        return r;
    }

    /**
     * Whether EDT itself can resolve an install carrying a thick client for this version line. EDT
     * picks the NEWEST build in a line, so a line whose newest builds are thin clients resolves to
     * one even when full installs of older builds are registered - which is exactly when the native
     * dumper fails and the disk fallback has to take over.
     */
    boolean edtResolvesThickClient() {
        try {
            IResolvableRuntimeInstallationManager rm =
                    ServiceAccess.get(IResolvableRuntimeInstallationManager.class);
            if (rm == null) {
                return false;
            }
            for (IResolvableRuntimeInstallation e : rm.getAll(RT_ENTERPRISE_PLATFORM)) {
                if (e.resolve(java.util.List.of(COMP_THICK_CLIENT), AppArch.AUTO) != null) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // treat an unreadable installation list as "no thick client"
        }
        return false;
    }

    /** The best full install on disk for a version line, or {@code null} when there is none. */
    String diskPlatformFor(String versionLine) {
        for (DiskPlatform dp : discoverFullPlatforms(platformLine(versionLine))) {
            if (firstExisting(dp.binDir, "1cv8.exe", "1cv8") != null) {
                return dp.version;
            }
        }
        return null;
    }

    /**
     * Build an {@code .epf}/{@code .erf} without EDT's dumper: export the object to designer XML
     * in-process, then let a full on-disk platform assemble it. This exists because EDT resolves the
     * NEWEST build of a version line, so a line topped by thin-client builds makes the native dumper
     * fail with {@code MatchingRuntimeNotFound} even though full installs of that line are present.
     *
     * <p>The same shape as the infobase disk fallback: find a full install, do the work in a temp
     * directory, clean up afterwards. An object created in EDT as external exports straight into the
     * external-object XML layout, so no post-processing is needed.
     *
     * @return {@code null} on success, otherwise the reason it did not build
     */
    String dumpExternalObjectViaDisk(EObject root, java.nio.file.Path target, Version version,
            java.nio.file.Path logPath, StringBuilder methodOut) {
        String line = platformLine(String.valueOf(version));
        List<DiskPlatform> candidates = discoverFullPlatforms(line);
        if (candidates.isEmpty()) {
            return "no full (thick-client) 1C:Enterprise install found on disk for line " + line;
        }
        IExportOperationFactory exportFactory;
        try {
            Injector inj = exportInjector();
            exportFactory = (inj == null) ? null : inj.getInstance(IExportOperationFactory.class);
        } catch (Exception ex) {
            return "export service unavailable: " + GatewaySupport.describeCause(ex);
        }
        if (exportFactory == null) {
            return "export injector unavailable (com._1c.g5.v8.dt.export not loaded)";
        }

        java.nio.file.Path tempRoot = null;
        boolean keepTemp = false;
        try {
            tempRoot = java.nio.file.Files.createTempDirectory("edtbridge-epf-");
            java.nio.file.Path xmlDir = tempRoot.resolve("xml");
            java.nio.file.Files.createDirectories(xmlDir);

            IExportOperation op = exportFactory.createExportOperation(xmlDir, version, root);
            IStatus est = op.run(new NullProgressMonitor());
            if (est != null && est.getSeverity() == IStatus.ERROR) {
                return "export to designer files failed: " + est.getMessage();
            }
            // DESIGNER wants the object's ROOT .xml file, not the export directory, and refuses a
            // directory outright. The export nests it one level down - xml/ExternalDataProcessors/
            // <Name>.xml - so take the shallowest .xml in the tree.
            java.nio.file.Path source = null;
            try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.walk(xmlDir, 3)) {
                source = s.filter(java.nio.file.Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".xml"))
                        .min(java.util.Comparator.comparingInt(java.nio.file.Path::getNameCount))
                        .orElse(null);
            }
            if (source == null) {
                keepTemp = true;
                return "the export produced no root .xml (kept temp: " + tempRoot + ")";
            }

            if (target.getParent() != null) {
                java.nio.file.Files.createDirectories(target.getParent());
            }
            java.nio.file.Files.deleteIfExists(target);

            StringBuilder tried = new StringBuilder();
            for (DiskPlatform dp : candidates) {
                java.nio.file.Path exe = firstExisting(dp.binDir, "1cv8.exe", "1cv8");
                if (exe == null) {
                    continue;
                }
                // DESIGNER only runs against an infobase, so stand a throwaway one up in the temp root.
                java.nio.file.Path ib = tempRoot.resolve("ib-" + dp.version);
                java.nio.file.Files.createDirectories(ib);
                // The log goes where the caller asked, next to the artefact - a build that failed is
                // exactly when you want to read it, and a temp file would already be gone.
                java.nio.file.Path log = (logPath != null) ? logPath
                        : tempRoot.resolve("designer-" + dp.version + ".log");
                java.nio.file.Files.deleteIfExists(ib);
                String err = runCreateInfobase(exe, ib);
                if (err != null && !java.nio.file.Files.exists(ib.resolve("1Cv8.1CD"))) {
                    tried.append(dp.version).append(" (CREATEINFOBASE: ").append(err).append("); ");
                    continue;
                }
                err = runIbcmd(exe, "DESIGNER", "/F", ib.toString(),
                        "/DisableStartupDialogs", "/DisableStartupMessages",
                        "/LoadExternalDataProcessorOrReportFromFiles", source.toString(),
                        target.toString(), "/Out", log.toString());
                if (java.nio.file.Files.exists(target)) {
                    methodOut.append("disk:").append(dp.version);
                    return null;
                }
                // 1cv8 says nothing on stdout - everything useful is in the /Out log, so always read it.
                String logged = null;
                if (java.nio.file.Files.exists(log)) {
                    logged = java.nio.file.Files
                            .readString(log, java.nio.charset.Charset.defaultCharset()).trim();
                }
                String detail = (logged != null && !logged.isEmpty()) ? logged
                        : (err != null ? err : "no file produced");
                keepTemp = true;
                tried.append(dp.version).append(" (").append(detail).append("); ");
            }
            return "found full install(s) on disk but the build failed for: " + tried
                    + "(kept temp: " + tempRoot + ")";
        } catch (java.io.IOException | RuntimeException ex) {
            keepTemp = true;
            return GatewaySupport.describeCause(ex);
        } finally {
            if (tempRoot != null && !keepTemp) {
                deleteRecursively(tempRoot);
            }
        }
    }

    /** The major.minor.release line of a version (first three parts), or {@code null} if not given. */
    static String platformLine(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String[] p = version.trim().split("\\.");
        if (p.length >= 3) {
            return p[0] + "." + p[1] + "." + p[2];
        }
        return version.trim();
    }

    /**
     * Scan the platform install roots for full installs that carry a thick client (a {@code 1cv8}
     * executable in {@code bin}). Ordered best-first: entries whose version starts with
     * {@code preferredLine} (highest build first), then every other version descending. Roots are
     * derived from installs EDT already resolved (so it works wherever the distributions live, incl.
     * macOS) plus the standard Windows locations.
     */
    List<DiskPlatform> discoverFullPlatforms(String preferredLine) {
        java.util.LinkedHashSet<java.nio.file.Path> roots = new java.util.LinkedHashSet<>();
        try {
            IResolvableRuntimeInstallationManager rm =
                    ServiceAccess.get(IResolvableRuntimeInstallationManager.class);
            if (rm != null) {
                for (IResolvableRuntimeInstallation e : rm.getAll(RT_ENTERPRISE_PLATFORM)) {
                    try {
                        RuntimeInstallation ci = e.resolve(java.util.List.of(), AppArch.AUTO);
                        if (ci != null && ci.getLocation() != null && "file".equalsIgnoreCase(ci.getLocation().getScheme())) {
                            java.nio.file.Path loc = java.nio.file.Paths.get(ci.getLocation()); // .../<version>/bin
                            if (loc.getParent() != null && loc.getParent().getParent() != null) {
                                roots.add(loc.getParent().getParent());
                            }
                        }
                    } catch (RuntimeException ignore) {
                        // this entry has no concrete install (incl. MatchingRuntimeNotFound); skip
                    }
                }
            }
        } catch (RuntimeException ignore) {
            // resolver unavailable; fall back to the standard locations only
        }
        for (String p : new String[] {"C:\\Program Files\\1cv8", "C:\\Program Files (x86)\\1cv8"}) {
            java.nio.file.Path pp = java.nio.file.Path.of(p);
            if (java.nio.file.Files.isDirectory(pp)) {
                roots.add(pp);
            }
        }
        List<DiskPlatform> found = new ArrayList<>();
        java.util.Set<String> seenVersions = new java.util.HashSet<>();
        for (java.nio.file.Path root : roots) {
            try (java.util.stream.Stream<java.nio.file.Path> kids = java.nio.file.Files.list(root)) {
                for (java.nio.file.Path verDir : (Iterable<java.nio.file.Path>) kids::iterator) {
                    if (!java.nio.file.Files.isDirectory(verDir)) {
                        continue;
                    }
                    String ver = verDir.getFileName().toString();
                    if (!ver.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") || !seenVersions.add(ver)) {
                        continue;
                    }
                    java.nio.file.Path bin = verDir.resolve("bin");
                    if (firstExisting(bin, "1cv8.exe", "1cv8") != null) {
                        found.add(new DiskPlatform(bin, ver));
                    }
                }
            } catch (java.io.IOException ignore) {
                // unreadable root; skip
            }
        }
        found.sort((a, b) -> {
            boolean pa = matchesLine(a.version, preferredLine);
            boolean pb = matchesLine(b.version, preferredLine);
            if (pa != pb) {
                return pa ? -1 : 1;
            }
            return compareVersionsDesc(a.version, b.version);
        });
        return found;
    }

    private static boolean matchesLine(String version, String line) {
        return line != null && (version.equals(line) || version.startsWith(line + "."));
    }

    private static int compareVersionsDesc(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int va = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int vb = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (va != vb) {
                return Integer.compare(vb, va); // descending
            }
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static java.nio.file.Path firstExisting(java.nio.file.Path dir, String... names) {
        for (String n : names) {
            java.nio.file.Path p = dir.resolve(n);
            if (java.nio.file.Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /** Run {@code 1cv8 CREATEINFOBASE} in batch mode for an empty file base; null on success, else an error. */
    private static String runCreateInfobase(java.nio.file.Path exe, java.nio.file.Path dir) {
        java.nio.file.Path out = null;
        try {
            out = java.nio.file.Files.createTempFile("edtbridge-createib-", ".log");
            String conn = dir.toString().indexOf(' ') >= 0 ? "File=\"" + dir + "\"" : "File=" + dir;
            ProcessBuilder pb = new ProcessBuilder(exe.toString(), "CREATEINFOBASE", conn,
                    "/DisableStartupDialogs", "/DisableStartupMessages", "/Out", out.toString());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean done = proc.waitFor(180, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                return "timed out after 180s";
            }
            int code = proc.exitValue();
            if (code != 0) {
                String log = readSmall(out);
                return "exit " + code + (log.isBlank() ? "" : ": " + log.trim());
            }
            return null;
        } catch (java.io.IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ex.getClass().getSimpleName() + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
        } finally {
            if (out != null) {
                try {
                    java.nio.file.Files.deleteIfExists(out);
                } catch (java.io.IOException ignore) {
                    // temp log cleanup is best-effort
                }
            }
        }
    }

    private static String readSmall(java.nio.file.Path p) {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(p);
            String s = new String(b, java.nio.charset.StandardCharsets.UTF_8);
            return s.length() > 500 ? s.substring(0, 500) : s;
        } catch (java.io.IOException e) {
            return "";
        }
    }

    /** Register a freshly created file base in EDT's infobases list; null on success, else why it was skipped. */
    private String registerFileInfobase(String name, java.nio.file.Path dir) {
        try {
            IInfobaseManager im = ServiceAccess.get(IInfobaseManager.class);
            if (im == null) {
                return "IInfobaseManager unavailable";
            }
            if (im.findInfobaseByName(name).isPresent()) {
                return null; // already listed
            }
            InfobaseReference ref = InfobaseReferences.newFileInfobaseReference(dir.toString());
            ref.setName(name);
            ref.setUuid(UUID.randomUUID());
            im.add(ref, null);
            return null;
        } catch (RuntimeException ex) {
            return GatewaySupport.describeCause(ex);
        }
    }

    // ── Build a configuration extension (.cfe) from an EDT project: in-process export + on-disk ibcmd ──

    /** Result of {@link #buildExtension}. */
    public static final class BuildExtensionResult {
        public boolean ok;
        public boolean applied;
        public String project;
        public String extensionName;
        public String outputPath;
        public String platform;       // the on-disk full install (with ibcmd) used
        public String plan;
        public String message;
    }

    /**
     * Phase-2 write tool: build a configuration extension binary ({@code .cfe}) from an EDT
     * configuration-extension project, entirely off EDT's platform resolver. The extension model is
     * exported to designer XML in-process (no thick client – it is serialization), then a full 1C
     * install found on disk (one that carries {@code ibcmd}) builds the {@code .cfe} via a THROWAWAY
     * file infobase created under the system temp directory and deleted afterwards:
     * {@code ibcmd infobase create} -> {@code config import --extension} -> {@code config save --extension}.
     * Pick the install by {@code platformVersion} (its major.minor.release line) to match the target
     * base; otherwise the project's version. Dry-run by default.
     */
    public BuildExtensionResult buildExtension(String projectName, String extensionName,
            String outputPath, String platformVersion, boolean apply) {
        BuildExtensionResult r = new BuildExtensionResult();
        r.project = projectName;
        r.outputPath = outputPath;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName == null ? "" : projectName);
        if (projectName == null || !p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        if (outputPath == null || outputPath.isBlank()) {
            r.message = "outputPath is required (the .cfe file to write)";
            return r;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        IBmModel model = (mm == null) ? null : mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }
        final EObject[] found = {null};
        model.executeReadonlyTask(new AbstractBmTask<Object>("edt-bridge.buildExtension.find") {
            @Override
            public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                found[0] = (EObject) tx.getTopObjectByFqn("Configuration");
                return null;
            }
        });
        if (found[0] == null) {
            r.message = "no Configuration root in the project – is it a configuration-extension project? "
                    + projectName;
            return r;
        }
        String extName = (extensionName != null && !extensionName.isBlank())
                ? extensionName.trim()
                : (found[0] instanceof MdObject ? ((MdObject) found[0]).getName() : null);
        if (extName == null || extName.isBlank()) {
            r.message = "could not determine the extension name – pass extensionName explicitly";
            return r;
        }
        r.extensionName = extName;
        Version version = GatewaySupport.projectVersion(p);
        String line = platformLine((platformVersion != null && !platformVersion.isBlank())
                ? platformVersion : String.valueOf(version));
        DiskPlatform ib = findIbcmdInstall(line);
        if (ib == null) {
            r.message = "no on-disk full install carrying ibcmd was found"
                    + (line != null ? " for version line " + line : "")
                    + " – a full 1C:Enterprise install (with ibcmd) matching the base version is required.";
            return r;
        }
        r.platform = ib.version;
        r.plan = "Export extension \"" + extName + "\" from " + projectName + " and build .cfe via ibcmd "
                + ib.version + " (throwaway temp base) -> " + outputPath;
        if (!apply) {
            r.ok = true;
            return r;
        }

        IExportOperationFactory exportFactory;
        try {
            Injector inj = exportInjector();
            exportFactory = (inj == null) ? null : inj.getInstance(IExportOperationFactory.class);
        } catch (Exception ex) {
            r.message = "export service unavailable: " + GatewaySupport.describeCause(ex);
            return r;
        }
        if (exportFactory == null) {
            r.message = "export injector unavailable (com._1c.g5.v8.dt.export not loaded)";
            return r;
        }
        java.nio.file.Path tempRoot = null;
        boolean keepTemp = false;
        try {
            // one throwaway temp root under the system temp dir; removed in finally (kept on failure)
            tempRoot = java.nio.file.Files.createTempDirectory("edtbridge-build-");
            java.nio.file.Path xmlDir = tempRoot.resolve("xml");
            java.nio.file.Files.createDirectories(xmlDir);
            IExportOperation op = exportFactory.createExportOperation(xmlDir, version, found[0]);
            IStatus est = op.run(new NullProgressMonitor());
            if (est != null && est.getSeverity() == IStatus.ERROR) {
                keepTemp = true;
                r.message = "export to designer files failed: " + est.getMessage()
                        + " (kept temp: " + tempRoot + ")";
                return r;
            }
            java.nio.file.Path ibcmd = firstExisting(ib.binDir, "ibcmd.exe", "ibcmd");
            java.nio.file.Path tempData = tempRoot.resolve("data");
            java.nio.file.Files.createDirectories(tempData);
            java.nio.file.Path tempDb = tempRoot.resolve("db"); // ibcmd creates the base here
            String err = runIbcmd(ibcmd, "infobase", "create",
                    "--data=" + tempData, "--database-path=" + tempDb);
            if (err == null) {
                err = runIbcmd(ibcmd, "config", "import", "--data=" + tempData,
                        "--database-path=" + tempDb, "--extension=" + extName, xmlDir.toString());
            }
            if (err == null) {
                err = runIbcmd(ibcmd, "config", "save", "--data=" + tempData,
                        "--database-path=" + tempDb, "--extension=" + extName, outputPath);
            }
            if (err != null) {
                keepTemp = true;
                r.message = "ibcmd build failed: " + err + " (kept temp for inspection: " + tempRoot + ")";
                return r;
            }
            r.applied = java.nio.file.Files.exists(java.nio.file.Path.of(outputPath));
            r.ok = r.applied;
            r.message = r.applied
                    ? ("built .cfe for extension " + extName + " -> " + outputPath + " (ibcmd " + ib.version + ")")
                    : "ibcmd finished but the .cfe is missing at " + outputPath;
        } catch (java.io.IOException | RuntimeException ex) {
            r.message = "build failed: " + GatewaySupport.describeCause(ex);
        } finally {
            if (!keepTemp) {
                deleteRecursively(tempRoot);
            }
        }
        return r;
    }

    /** Properties of one extension as registered in an infobase. */
    public static final class ExtensionFlags {
        public String name;
        public String version;
        public Boolean active;
        public String purpose;
        public Boolean safeMode;
        public String securityProfileName;
        public Boolean unsafeActionProtection;
        public Boolean usedInDistributedInfobase;
        public String scope;
    }

    /** Result of {@link #extensionProperties}. */
    public static final class ExtensionPropertiesResult {
        public boolean ok;
        public boolean applied;
        public String infobase;      // how the infobase was addressed
        public String platform;      // ibcmd install used
        public String name;          // extension asked about; null = every extension
        public final List<ExtensionFlags> extensions = new ArrayList<>();
        public final List<String> changed = new ArrayList<>();
        public String plan;
        public String warning;
        public String message;
    }

    /**
     * Read - and optionally set - the properties an extension carries INSIDE an infobase, through
     * {@code ibcmd extension info|list|update}. These are registration properties of the infobase,
     * not of the project: building a {@code .cfe} or updating from EDT does not decide them.
     *
     * <p>Why it matters: a newly registered extension gets {@code safe-mode} and
     * {@code unsafe-action-protection} ON (verified - they are ibcmd's defaults, and an update from
     * EDT leaves them alone). An extension that changes methods of the base configuration cannot run
     * under them: the intercepted method loses what the original was allowed to do. So for such an
     * extension both have to be cleared, and {@code edt_build_extension} / {@code edt_update_infobase}
     * say so when they see interception annotations.
     *
     * <p>The infobase is addressed either by {@code databasePath} (a file infobase) or by the DBMS
     * coordinates, exactly as ibcmd expects them.
     *
     * @param apply {@code false} reports the current properties and the planned change; {@code true}
     *              performs the update and reads the properties back
     */
    public ExtensionPropertiesResult extensionProperties(String databasePath, String dbms,
            String dbServer, String dbName, String dbUser, String dbPassword, String name,
            Boolean safeMode, Boolean unsafeActionProtection, Boolean active, String platformVersion,
            boolean apply) {
        ExtensionPropertiesResult r = new ExtensionPropertiesResult();
        r.name = (name == null || name.isBlank()) ? null : name.trim();

        List<String> conn = new ArrayList<>();
        if (databasePath != null && !databasePath.isBlank()) {
            conn.add("--database-path=" + databasePath.trim());
            r.infobase = "file: " + databasePath.trim();
        } else if (dbms != null && !dbms.isBlank() && dbName != null && !dbName.isBlank()) {
            conn.add("--dbms=" + dbms.trim());
            if (dbServer != null && !dbServer.isBlank()) {
                conn.add("--database-server=" + dbServer.trim());
            }
            conn.add("--database-name=" + dbName.trim());
            if (dbUser != null && !dbUser.isBlank()) {
                conn.add("--database-user=" + dbUser.trim());
            }
            if (dbPassword != null && !dbPassword.isBlank()) {
                conn.add("--database-password=" + dbPassword);
            }
            r.infobase = dbms.trim() + ": " + (dbServer == null ? "" : dbServer.trim() + "/") + dbName.trim();
        } else {
            r.message = "the infobase is required: pass databasePath for a file infobase, or dbms +"
                    + " dbName (+ dbServer / dbUser / dbPassword) for a DBMS-hosted one";
            return r;
        }

        DiskPlatform ib = findIbcmdInstall(platformLine(platformVersion));
        if (ib == null) {
            r.message = "no on-disk full install carrying ibcmd was found"
                    + (platformVersion == null ? "" : " for version line " + platformVersion)
                    + " - a full 1C:Enterprise install (with ibcmd) is required.";
            return r;
        }
        r.platform = ib.version;
        java.nio.file.Path ibcmd = firstExisting(ib.binDir, "ibcmd.exe", "ibcmd");

        List<String> sets = new ArrayList<>();
        if (safeMode != null) {
            sets.add("--safe-mode=" + yesNo(safeMode));
        }
        if (unsafeActionProtection != null) {
            sets.add("--unsafe-action-protection=" + yesNo(unsafeActionProtection));
        }
        if (active != null) {
            sets.add("--active=" + yesNo(active));
        }
        if (!sets.isEmpty() && r.name == null) {
            r.message = "name is required to change properties - ibcmd updates one extension at a time";
            return r;
        }

        List<String> read = new ArrayList<>();
        read.add("extension");
        read.add(r.name == null ? "list" : "info");
        read.addAll(conn);
        if (r.name != null) {
            read.add("--name=" + r.name);
        }
        String[] readCmd = read.toArray(new String[0]);
        String out = runIbcmdCapturing(ibcmd, readCmd);
        if (out == null) {
            r.message = "ibcmd " + (r.name == null ? "extension list" : "extension info") + " failed"
                    + " - check the infobase coordinates and that the platform matches its version";
            return r;
        }
        parseExtensionFlags(out, r.extensions);
        r.ok = true;
        if (r.name != null && r.extensions.isEmpty()) {
            r.ok = false;
            r.message = "no extension named " + r.name + " is registered in this infobase";
            return r;
        }
        r.plan = sets.isEmpty()
                ? ("Report the registration properties of "
                   + (r.name == null ? "every extension" : r.name) + " in " + r.infobase)
                : ("Set " + String.join(" ", sets) + " on " + r.name + " in " + r.infobase);
        if (sets.isEmpty() || !apply) {
            if (!sets.isEmpty()) {
                r.message = "dry-run - nothing changed; re-run with apply to set " + String.join(" ", sets);
            }
            return r;
        }

        List<String> upd = new ArrayList<>();
        upd.add("extension");
        upd.add("update");
        upd.addAll(conn);
        upd.add("--name=" + r.name);
        upd.addAll(sets);
        String err = runIbcmd(ibcmd, upd.toArray(new String[0]));
        if (err != null) {
            r.ok = false;
            r.message = "ibcmd extension update failed: " + err;
            return r;
        }
        // Read back rather than trust the exit code - the point of the tool is the state, not the call.
        r.extensions.clear();
        String after = runIbcmdCapturing(ibcmd, readCmd);
        if (after != null) {
            parseExtensionFlags(after, r.extensions);
        }
        r.applied = true;
        r.changed.addAll(sets);
        ExtensionFlags now = r.extensions.isEmpty() ? null : r.extensions.get(0);
        r.message = "updated " + r.name + " in " + r.infobase
                + (now == null ? "" : " - now safeMode=" + now.safeMode
                        + ", unsafeActionProtection=" + now.unsafeActionProtection
                        + ", active=" + now.active);
        return r;
    }

    private static String yesNo(Boolean b) {
        return Boolean.TRUE.equals(b) ? "yes" : "no";
    }

    /** Parse ibcmd's {@code key : value} extension listing; blank line separates extensions. */
    private static void parseExtensionFlags(String out, List<ExtensionFlags> into) {
        ExtensionFlags cur = null;
        for (String raw : out.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("[")) {
                continue;   // blank separator, or an [INFO] log line
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = unquote(line.substring(colon + 1).trim());
            if ("name".equals(key)) {
                cur = new ExtensionFlags();
                cur.name = value;
                into.add(cur);
            }
            if (cur == null) {
                continue;
            }
            switch (key) {
                case "version" -> cur.version = value.isEmpty() ? null : value;
                case "active" -> cur.active = parseYesNo(value);
                case "purpose" -> cur.purpose = value.isEmpty() ? null : value;
                case "safe-mode" -> cur.safeMode = parseYesNo(value);
                case "security-profile-name" -> cur.securityProfileName = value.isEmpty() ? null : value;
                case "unsafe-action-protection" -> cur.unsafeActionProtection = parseYesNo(value);
                case "used-in-distributed-infobase" -> cur.usedInDistributedInfobase = parseYesNo(value);
                case "scope" -> cur.scope = value.isEmpty() ? null : value;
                default -> { /* hash-sum and anything ibcmd adds later */ }
            }
        }
    }

    private static Boolean parseYesNo(String v) {
        if ("yes".equalsIgnoreCase(v)) {
            return Boolean.TRUE;
        }
        if ("no".equalsIgnoreCase(v)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static String unquote(String v) {
        return (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\""))
                ? v.substring(1, v.length() - 1) : v;
    }

    /** Like {@link #runIbcmd} but returns the output on success (null on failure). */
    private static String runIbcmdCapturing(java.nio.file.Path exe, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        for (String a : args) {
            cmd.add(a);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            byte[] raw;
            try (java.io.InputStream in = proc.getInputStream()) {
                raw = in.readAllBytes();
            }
            if (!proc.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return null;
            }
            return (proc.exitValue() == 0) ? decodeIbcmd(raw) : null;
        } catch (java.io.IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * ibcmd writes command output in UTF-8 but its built-in help in the OEM code page; decode
     * strictly as UTF-8 and fall back to CP866 so a stray message cannot turn Cyrillic into noise.
     */
    private static String decodeIbcmd(byte[] raw) {
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(raw)).toString();
        } catch (java.nio.charset.CharacterCodingException notUtf8) {
            return new String(raw, java.nio.charset.Charset.forName("IBM866"));
        }
    }

    /** Best on-disk full install that carries {@code ibcmd}, preferring {@code line} then descending. */
    DiskPlatform findIbcmdInstall(String preferredLine) {
        for (DiskPlatform dp : discoverFullPlatforms(preferredLine)) {
            if (firstExisting(dp.binDir, "ibcmd.exe", "ibcmd") != null) {
                return dp;
            }
        }
        return null;
    }

    /** Run an {@code ibcmd} sub-command in batch mode; null on success (exit 0), else an error string. */
    private static String runIbcmd(java.nio.file.Path exe, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        for (String a : args) {
            cmd.add(a);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String out;
            try (java.io.InputStream in = proc.getInputStream()) {
                out = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            boolean done = proc.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                return "timed out: " + args[0] + " " + args[1];
            }
            int code = proc.exitValue();
            if (code != 0) {
                String tail = out.length() > 3000 ? out.substring(out.length() - 3000) : out;
                return args[0] + " " + args[1] + " exit " + code + (tail.isBlank() ? "" : ": " + tail.trim());
            }
            return null;
        } catch (java.io.IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ex.getClass().getSimpleName() + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
        }
    }

    /** Recursively delete a directory tree, best-effort (used to remove throwaway temp bases). */
    private static void deleteRecursively(java.nio.file.Path root) {
        if (root == null || !java.nio.file.Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(pth -> {
                try {
                    java.nio.file.Files.deleteIfExists(pth);
                } catch (java.io.IOException ignore) {
                    // best-effort cleanup
                }
            });
        } catch (java.io.IOException ignore) {
            // best-effort cleanup
        }
    }
}
