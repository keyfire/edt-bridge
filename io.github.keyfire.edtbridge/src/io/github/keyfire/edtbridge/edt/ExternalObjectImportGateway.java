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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.import_.IImportOperation;
import com._1c.g5.v8.dt.import_.IImportOperationFactory;
import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectRestorer;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.wiring.ServiceAccess;
import com.google.inject.Injector;
import io.github.keyfire.edtbridge.core.ExternalObjectSource;
import io.github.keyfire.edtbridge.core.PlatformLog;
import io.github.keyfire.edtbridge.core.TreeRemoval;

/** Import an EPF/ERF through the platform's XML representation and EDT's native importer. */
public final class ExternalObjectImportGateway {
    private static final Object IMPORT_LOCK = new Object();

    public static final class Result {
        public boolean ok;
        public boolean applied;
        public boolean rolledBack;
        public String projectName;
        public String file;
        public String baseProjectName;
        public String kind;
        public String objectName;
        public String version;
        public String route;
        public String location;
        public String plan;
        public String message;
    }

    public Result importObject(String file, String projectName, String baseProjectName, String kind,
            String route, boolean apply) {
        Result result = new Result();
        result.projectName = projectName;
        result.baseProjectName = baseProjectName;
        result.route = route == null || route.isBlank() ? "auto" : route.trim().toLowerCase(Locale.ROOT);
        if (!List.of("auto", "edt", "disk").contains(result.route)) {
            result.message = "route must be auto, edt, or disk"; return result;
        }
        var workspace = ResourcesPlugin.getWorkspace();
        if (projectName == null || projectName.isBlank()
                || !workspace.validateName(projectName, IResource.PROJECT).isOK()) {
            result.message = "projectName must be a valid Eclipse project name"; return result;
        }
        IProject target = workspace.getRoot().getProject(projectName);
        Path projectFolder = workspace.getRoot().getLocation().toFile().toPath().resolve(projectName);
        if (target.exists() || Files.exists(projectFolder)) {
            result.message = "the project or its destination directory already exists"; return result;
        }
        Path input;
        try {
            input = file == null ? null : Path.of(file).toAbsolutePath().normalize();
            if (input == null || !Files.isRegularFile(input) || Files.size(input) == 0) {
                result.message = "file must name a readable, non-empty .epf or .erf"; return result;
            }
        } catch (RuntimeException | IOException invalid) {
            result.message = "file is not a readable .epf or .erf"; return result;
        }
        result.file = input.toString();
        String suffix = input.getFileName().toString().toLowerCase(Locale.ROOT);
        String inferred = suffix.endsWith(".epf") ? "ExternalDataProcessor"
                : suffix.endsWith(".erf") ? "ExternalReport" : null;
        if (inferred == null) { result.message = "file must have the .epf or .erf extension"; return result; }
        String requested = kind == null || kind.isBlank() ? inferred : switch (kind.toLowerCase(Locale.ROOT)) {
            case "externaldataprocessor", "processor" -> "ExternalDataProcessor";
            case "externalreport", "report" -> "ExternalReport";
            default -> null;
        };
        if (requested == null || !requested.equals(inferred)) {
            result.message = "kind must match the file extension: ExternalDataProcessor for .epf, ExternalReport for .erf";
            return result;
        }
        result.kind = inferred;
        IV8ProjectManager projects = ServiceAccess.get(IV8ProjectManager.class);
        IExternalObjectProjectManager external = ServiceAccess.get(IExternalObjectProjectManager.class);
        if (projects == null || external == null) {
            result.message = "EDT project services are unavailable"; return result;
        }
        IConfigurationProject base = null;
        Version version = Version.LATEST;
        if (baseProjectName != null && !baseProjectName.isBlank()) {
            IProject baseResource = workspace.getRoot().getProject(baseProjectName);
            if (!baseResource.isOpen() || !(projects.getProject(baseResource) instanceof IConfigurationProject configuration)) {
                result.message = "baseProjectName must name an open configuration project with a loaded model";
                return result;
            }
            base = configuration;
            version = GatewaySupport.projectVersion(baseResource);
        }
        result.version = version.toString();
        PlatformGateway platforms = new PlatformGateway();
        List<PlatformGateway.DiskPlatform> candidates = platforms.discoverFullPlatforms(result.version);
        if (result.route.equals("disk") && candidates.isEmpty()) {
            result.message = "no full platform with a thick client was found on disk"; return result;
        }
        IImportOperationFactory importer;
        try {
            importer = injector("com._1c.g5.v8.dt.import", "com._1c.g5.v8.dt.internal.import_.ImportPlugin")
                    .getInstance(IImportOperationFactory.class);
        } catch (Exception unavailable) {
            result.message = "EDT import services unavailable: " + GatewaySupport.describeCause(unavailable);
            return result;
        }
        result.ok = true;
        result.plan = "Import " + input.getFileName() + " into new EDT project " + projectName
                + " [" + version + ", route=" + result.route + "]"
                + (base == null ? "" : " for " + baseProjectName);
        if (!apply) {
            result.message = "dry-run: input and destination checked; binary extraction and import run only with apply=true";
            return result;
        }
        synchronized (IMPORT_LOCK) {
            if (target.exists() || Files.exists(projectFolder)) {
                result.ok = false; result.message = "the destination appeared since validation; nothing imported"; return result;
            }
            Path temporary = null;
            ProjectCreationClaim ownership = null;
            try {
                temporary = Files.createTempDirectory("edtbridge-external-import-");
                Path source = Files.copy(input, temporary.resolve("input" + (inferred.equals("ExternalReport") ? ".erf" : ".epf")));
                Path xml = temporary.resolve("object.xml");
                String nativeFailure = null;
                boolean extracted = false;
                if (!result.route.equals("disk")) {
                    ownership = claimProject(external, target, projectFolder, version, base);
                    waitProject(projects, target);
                    try {
                        IExternalObjectRestorer restorer = ServiceAccess.get(IExternalObjectRestorer.class);
                        if (restorer == null) throw new IOException("EDT external-object restorer is unavailable");
                        restorer.restore(target, source, xml, new NullProgressMonitor());
                        if (!Files.isRegularFile(xml) || Files.size(xml) == 0) throw new IOException("EDT produced no external-object XML");
                        extracted = true;
                        result.route = "edt";
                    } catch (Exception failed) {
                        nativeFailure = GatewaySupport.describeCause(failed);
                        if (result.route.equals("edt")) throw new IOException(nativeFailure, failed);
                    }
                }
                if (!extracted) {
                    Unpack unpack = unpack(candidates, source, temporary);
                    if (unpack.error() != null) throw new IOException(unpack.error()
                            + (nativeFailure == null ? "" : "; EDT route: " + nativeFailure));
                    xml = unpack.xml();
                    result.route = "disk";
                }
                ExternalObjectSource metadata = ExternalObjectSource.read(xml);
                if (!metadata.kind().equals(inferred)) throw new IOException("binary object kind does not match its extension");
                result.objectName = metadata.name();
                if (ownership == null) {
                    ownership = claimProject(external, target, projectFolder, version, base);
                    waitProject(projects, target);
                }
                IImportOperation operation = importer.createImportExternalObjectOperation(projectName, version, xml, base);
                operation.run(new NullProgressMonitor());
                IStatus status = operation.getStatus();
                if (status == null || status.matches(IStatus.ERROR | IStatus.CANCEL)) {
                    throw new IOException("EDT import failed: " + (status == null ? "no import status" : status.getMessage()));
                }
                target.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
                waitImportedObject(projects, target, metadata);
                result.applied = true;
                result.location = target.getLocation().toOSString();
                result.message = "imported " + inferred + "." + metadata.name() + " through EDT's XML importer; the input binary is unchanged";
                return result;
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                result.ok = false;
                result.message = "import failed: " + GatewaySupport.describeCause(failure);
                if (ownership != null) {
                    try {
                        result.rolledBack = ownership.rollback();
                        if (!result.rolledBack) result.message += "; rollback skipped or incomplete: project ownership could not be verified or its files could not be removed";
                    } catch (Exception rollback) {
                        result.message += "; rollback failed: " + GatewaySupport.describeCause(rollback);
                    }
                }
                return result;
            } finally {
                TreeRemoval.delete(temporary);
            }
        }
    }

    private static ProjectCreationClaim claimProject(IExternalObjectProjectManager external,
            IProject target, Path directory, Version version, IConfigurationProject base) throws Exception {
        // The SDK requires the root creation rule; explicit model waits and imports run outside it.
        return ProjectCreationClaim.create(ResourcesPlugin.getWorkspace(), target, directory,
                () -> external.create(target.getName(), version, null,
                        base == null ? null : base.getProject(), new NullProgressMonitor()));
    }

    private static Injector injector(String bundleName, String pluginClassName) throws Exception {
        var bundle = Platform.getBundle(bundleName);
        if (bundle == null) throw new IOException(bundleName + " is not installed");
        Class<?> pluginClass = bundle.loadClass(pluginClassName);
        Object plugin = pluginClass.getMethod("getDefault").invoke(null);
        if (plugin == null) throw new IOException(bundleName + " is not active");
        return (Injector) pluginClass.getMethod("getInjector").invoke(plugin);
    }

    private static IExternalObjectProject waitProject(IV8ProjectManager projects, IProject target) throws InterruptedException, IOException {
        for (int attempt = 0; attempt < 240; attempt++) {
            if (projects.getProject(target) instanceof IExternalObjectProject external) return external;
            Thread.sleep(250);
        }
        throw new IOException("EDT did not load the external-object project within 60 seconds");
    }

    private static void waitImportedObject(IV8ProjectManager projects, IProject target,
            ExternalObjectSource metadata) throws InterruptedException, IOException {
        for (int attempt = 0; attempt < 240; attempt++) {
            if (projects.getProject(target) instanceof IExternalObjectProject external) {
                var objects = external.getExternalObjects();
                if (objects.size() == 1 && objects.stream().anyMatch(object -> metadata.name().equals(object.getName())
                        && metadata.kind().equals(object.eClass().getName()))) return;
            }
            Thread.sleep(250);
        }
        throw new IOException("the imported object was not available in EDT's model within 60 seconds");
    }

    private record Unpack(Path xml, String error) { }

    private static Unpack unpack(List<PlatformGateway.DiskPlatform> candidates,
            Path source, Path temporary) throws IOException {
        List<String> failures = new ArrayList<>();
        int attempt = 0;
        for (PlatformGateway.DiskPlatform candidate : candidates) {
            Path exe = PlatformGateway.firstExisting(candidate.binDir, "1cv8.exe", "1cv8");
            if (exe == null) continue;
            Path work = Files.createDirectory(temporary.resolve("attempt-" + attempt++));
            Path xml = work.resolve("object.xml");
            Path ib = work.resolve("ib");
            String create = PlatformGateway.runCreateInfobase(exe, ib);
            if (create != null) { failures.add(candidate.version + ": " + create); continue; }
            Path log = work.resolve("unpack.log");
            Files.deleteIfExists(xml);
            IbcmdGateway.Run run = IbcmdGateway.run(exe, List.of("DESIGNER", "/F", ib.toString(),
                    "/DisableStartupDialogs", "/DisableStartupMessages", "/DumpExternalDataProcessorOrReportToFiles",
                    xml.toString(), source.toString(), "/Out", log.toString()));
            if (run.ok && Files.isRegularFile(xml) && Files.size(xml) > 0) return new Unpack(xml, null);
            String detail = run.error;
            if (Files.isRegularFile(log)) detail = (detail == null ? "no XML produced" : detail) + ": " + PlatformLog.decode(Files.readAllBytes(log));
            failures.add(candidate.version + ": " + detail);
        }
        return new Unpack(null, "no platform could unpack the binary: " + String.join("; ", failures));
    }
}
