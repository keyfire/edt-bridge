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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;

import com._1c.g5.v8.dt.core.lifecycle.IDtProjectResourceLifecycleBootstrap;
import com._1c.g5.v8.dt.core.platform.IConfigurationProjectManager;
import com._1c.g5.v8.dt.import_.IImportServiceRegistry;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.xml.IXmlVersionService;
import com._1c.g5.wiring.ServiceAccess;

import io.github.keyfire.edtbridge.core.Jobs;
import io.github.keyfire.edtbridge.core.PlatformLog;
import io.github.keyfire.edtbridge.core.PlatformProcess;
import io.github.keyfire.edtbridge.core.TreeRemoval;

/** Creates a configuration project and populates it through EDT's XML import service. */
final class ConfigurationImportGateway {
    private static final String CONFIGURATION_XML = "Configuration.xml";
    private static final String XML_NAMESPACE = "http://v8.1c.ru/8.3/MDClasses";
    private static final Duration PLATFORM_TIMEOUT = Duration.ofMinutes(10);

    ProjectGateway.ImportProjectResult importConfiguration(String path, String name, boolean apply) {
        var result = new ProjectGateway.ImportProjectResult();
        result.mode = "configuration";
        result.name = name == null ? null : name.trim();
        Path temporary = null;
        ProjectCreationClaim claim = null;
        try {
            Path input = sourcePath(path);
            result.sourcePath = input.toString();
            result.directory = Files.isDirectory(input);
            result.sourceKind = result.directory ? "xml" : "cf";
            var workspace = ResourcesPlugin.getWorkspace();
            if (result.name == null || result.name.isBlank()
                    || !workspace.validateName(result.name, IResource.PROJECT).isOK()) {
                throw new IOException("name must be a valid name for the new EDT project");
            }
            var root = workspace.getRoot();
            if (root.getLocation() == null) throw new IOException("the workspace has no local filesystem location");
            Path destination = root.getLocation().toFile().toPath().resolve(result.name).toAbsolutePath().normalize();
            result.location = destination.toString();
            result.insideWorkspace = true;
            var project = root.getProject(result.name);
            result.nameTaken = project.exists() || Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
            if (result.nameTaken) throw new IOException("the project or its destination directory already exists");
            if (destination.getParent().toRealPath().resolve(result.name).startsWith(input)) {
                throw new IOException("the destination would be inside the configuration source");
            }

            var registry = GatewaySupport.importInjector().getInstance(IImportServiceRegistry.class);
            var xmlVersions = ServiceAccess.get(IXmlVersionService.class);
            var configurationProjects = FormWriteGateway.coreService(IConfigurationProjectManager.class);
            var bootstrap = FormWriteGateway.coreService(IDtProjectResourceLifecycleBootstrap.class);
            if (xmlVersions == null || configurationProjects == null || bootstrap == null) {
                throw new IOException("EDT configuration import services are unavailable");
            }
            Path executable = result.directory ? null : thickClient(registry);
            Version version = result.directory ? selectImportVersion(registry, xmlVersions, input) : null;
            result.version = version == null ? null : version.toString();
            result.plan = "Import " + input + " into the new EDT project " + destination;
            if (!apply) {
                result.ok = true;
                return result;
            }

            if (executable != null) {
                Jobs.stage("extracting configuration files from the .cf file");
                temporary = Files.createTempDirectory("edtbridge-configuration-import-");
                input = unpack(executable, input, temporary);
                version = selectImportVersion(registry, xmlVersions, input);
                result.version = version.toString();
            }
            var importer = registry.getImportService(version);
            Version selectedVersion = version;
            IProgressMonitor monitor = JobProgressMonitor.create();
            checkCanceled(monitor);
            Jobs.stage("creating the new EDT configuration project");
            claim = ProjectCreationClaim.create(workspace, project, destination,
                    () -> configurationProjects.create(project.getName(), selectedVersion, null, monitor));
            Jobs.stage("importing configuration files in EDT");
            checkCanceled(monitor);
            // The manager registers a null-configuration creation as an import. Release that
            // lifecycle latch only after the importer has written and refreshed the project.
            IStatus status = importer.work(project, input, monitor);
            if (status == null) throw new IOException("EDT did not return an import status");
            if (status.matches(IStatus.ERROR | IStatus.CANCEL)) throw new CoreException(status);
            checkCanceled(monitor);
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
            if (!project.isOpen() || !project.getFile("src/Configuration/Configuration.mdo").exists()) {
                throw new IOException("EDT did not create an open project containing the configuration");
            }
            bootstrap.permitImport(project);
            result.open = true;
            result.descriptor = true;
            result.applied = true;
            result.ok = true;
            Jobs.applied("the configuration was imported into EDT project " + result.name);
            result.warning = status.matches(IStatus.WARNING) ? status.getMessage() : null;
            String persistence = ProjectGateway.snapshotWorkspace();
            if (persistence != null) {
                result.warning = result.warning == null ? persistence : result.warning + ". " + persistence;
            }
            result.message = "Imported configuration into EDT project " + result.name
                    + ". Read edt_projects to check model readiness.";
        } catch (Exception | LinkageError failure) {
            result.message = "Configuration import failed. " + GatewaySupport.describeCause(failure);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (failure instanceof ProjectCreationClaim.IncompleteCreationException) {
                result.warning = "The workspace or destination directory may contain an unfinished project. "
                        + "Inspect both before retrying.";
            }
            if (claim != null && !result.applied) {
                boolean interrupted = Thread.interrupted();
                try {
                    if (!claim.rollback()) {
                        result.warning = "The destination could not be fully removed after the failure. Inspect it before retrying.";
                    }
                } catch (Exception rollback) {
                    result.warning = "Could not remove the destination after the failure. " + GatewaySupport.describeCause(rollback);
                } finally {
                    if (interrupted || failure instanceof InterruptedException) Thread.currentThread().interrupt();
                }
            }
        } finally {
            TreeRemoval.delete(temporary);
        }
        return result;
    }

    static Path sourcePath(String path) throws IOException {
        if (path == null || path.isBlank()) throw new IOException("path is required");
        Path input = Path.of(path).toRealPath();
        if (Files.isDirectory(input)) {
            if (!Files.isRegularFile(input.resolve(CONFIGURATION_XML))) {
                throw new IOException("the configuration dump directory must contain " + CONFIGURATION_XML);
            }
        } else if (!Files.isRegularFile(input) || Files.size(input) == 0
                || !input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cf")) {
            throw new IOException("path must name a configuration dump directory or a non-empty .cf file");
        }
        return input;
    }

    static String xmlFormat(Path directory) throws IOException, XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try (var input = Files.newInputStream(directory.resolve(CONFIGURATION_XML))) {
            var reader = factory.createXMLStreamReader(input);
            try {
                if (reader.nextTag() == XMLStreamConstants.START_ELEMENT
                        && "MetaDataObject".equals(reader.getLocalName())
                        && XML_NAMESPACE.equals(reader.getNamespaceURI())) {
                    String version = reader.getAttributeValue(null, "version");
                    if (version != null && !version.isBlank()
                            && reader.nextTag() == XMLStreamConstants.START_ELEMENT
                            && "Configuration".equals(reader.getLocalName())
                            && XML_NAMESPACE.equals(reader.getNamespaceURI())) return version;
                }
                throw new IOException(CONFIGURATION_XML + " does not have a platform configuration header");
            } finally {
                reader.close();
            }
        }
    }

    private static Version selectImportVersion(IImportServiceRegistry registry, IXmlVersionService xmlVersions,
            Path input) throws IOException, XMLStreamException {
        String format = xmlFormat(input);
        var supported = registry.getSupportedImportVersion();
        return xmlVersions.getRuntimeVersions(format).stream().filter(supported::contains)
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new IOException("EDT has no installed configuration importer for XML format " + format));
    }

    private static Path unpack(Path executable, Path source, Path temporary) throws IOException {
        Path infobase = temporary.resolve("ib");
        String createError = PlatformGateway.runCreateInfobase(executable, infobase);
        if (createError != null) throw new IOException(createError);
        Path log = temporary.resolve("load.log");
        runDesigner(executable, temporary, infobase, log, "/LoadCfg", source);
        Path xml = Files.createDirectory(temporary.resolve("xml"));
        runDesigner(executable, temporary, infobase, temporary.resolve("dump.log"), "/DumpConfigToFiles", xml);
        if (!Files.isRegularFile(xml.resolve(CONFIGURATION_XML))) {
            throw new IOException("1C:Enterprise did not extract " + CONFIGURATION_XML + " from the .cf file");
        }
        return xml;
    }

    private static Path thickClient(IImportServiceRegistry registry) throws IOException {
        var supported = registry.getSupportedImportVersion();
        return new PlatformGateway().discoverFullPlatforms(null).stream()
                .filter(platform -> supported.stream().anyMatch(version ->
                        platform.version.startsWith(version + ".")))
                .map(platform -> PlatformGateway.firstExisting(platform.binDir, "1cv8.exe", "1cv8"))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new IOException("a local 1C:Enterprise thick client on an EDT-supported version is required to unpack a .cf file"));
    }

    private static void runDesigner(Path executable, Path temporary, Path infobase, Path log,
            String command, Path argument) throws IOException {
        List<String> args = List.of("DESIGNER", "/F", infobase.toString(),
                "/DisableStartupDialogs", "/DisableStartupMessages", command, argument.toString(),
                "/Out", log.toString());
        var result = PlatformProcess.run(executable, args, temporary, PLATFORM_TIMEOUT, null);
        if (result.end() == PlatformProcess.End.EXITED && result.exitCode() == 0) return;
        String detail = result.problem(executable, command, PLATFORM_TIMEOUT);
        if (Files.isRegularFile(log)) {
            try (var stream = Files.newInputStream(log)) {
                String logged = PlatformLog.decode(stream.readNBytes(2048)).trim();
                if (!logged.isEmpty()) detail += ". " + logged;
            }
        }
        throw new IOException(detail);
    }

    private static void checkCanceled(IProgressMonitor monitor) {
        if (monitor.isCanceled() || Thread.currentThread().isInterrupted()) throw new OperationCanceledException();
    }
}
